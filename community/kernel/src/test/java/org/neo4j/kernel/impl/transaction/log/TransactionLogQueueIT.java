/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.transaction.log;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.neo4j.monitoring.PanicEventGenerator.NO_OP;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.neo4j.graphdb.DatabaseShutdownException;
import org.neo4j.internal.kernel.api.security.AuthSubject;
import org.neo4j.io.ByteUnit;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.impl.api.TestCommand;
import org.neo4j.kernel.impl.api.TestCommandReaderFactory;
import org.neo4j.kernel.impl.api.TransactionToApply;
import org.neo4j.kernel.impl.transaction.SimpleLogVersionRepository;
import org.neo4j.kernel.impl.transaction.SimpleTransactionIdStore;
import org.neo4j.kernel.impl.transaction.log.files.LogFiles;
import org.neo4j.kernel.impl.transaction.log.files.LogFilesBuilder;
import org.neo4j.kernel.impl.transaction.tracing.LogAppendEvent;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.monitoring.DatabaseHealth;
import org.neo4j.storageengine.api.StoreId;
import org.neo4j.storageengine.api.cursor.StoreCursors;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.LifeExtension;
import org.neo4j.test.extension.Neo4jLayoutExtension;
import org.neo4j.test.scheduler.ThreadPoolJobScheduler;

@Neo4jLayoutExtension
@ExtendWith(LifeExtension.class)
class TransactionLogQueueIT {
    @Inject
    private FileSystemAbstraction fileSystem;

    @Inject
    private LifeSupport life;

    @Inject
    private DatabaseLayout databaseLayout;

    private ThreadPoolJobScheduler jobScheduler;
    private SimpleLogVersionRepository logVersionRepository;
    private SimpleTransactionIdStore transactionIdStore;
    private TransactionMetadataCache metadataCache;
    private DatabaseHealth databaseHealth;
    private NullLogProvider logProvider;

    @BeforeEach
    void setUp() {
        jobScheduler = new ThreadPoolJobScheduler();

        logVersionRepository = new SimpleLogVersionRepository();
        transactionIdStore = new SimpleTransactionIdStore();
        logProvider = NullLogProvider.getInstance();
        metadataCache = new TransactionMetadataCache();
        databaseHealth = new DatabaseHealth(NO_OP, logProvider.getLog(DatabaseHealth.class));
    }

    @AfterEach
    void tearDown() {
        life.shutdown();
        jobScheduler.close();
    }

    @Test
    void processMessagesByTheTransactionQueue() throws IOException, ExecutionException, InterruptedException {
        LogFiles logFiles = buildLogFiles(logVersionRepository, transactionIdStore);
        life.add(logFiles);

        TransactionLogQueue logQueue = createLogQueue(logFiles);
        life.add(logQueue);

        long committedTransactionId = transactionIdStore.getLastCommittedTransactionId();
        for (int i = 0; i < 100; i++) {
            TransactionToApply transaction = createTransaction();
            assertEquals(
                    ++committedTransactionId,
                    logQueue.submit(transaction, LogAppendEvent.NULL).getCommittedTxId());
        }
    }

    @Test
    void doNotProcessMessagesAfterShutdown() throws IOException, ExecutionException, InterruptedException {
        LogFiles logFiles = buildLogFiles(logVersionRepository, transactionIdStore);
        life.add(logFiles);

        TransactionLogQueue logQueue = createLogQueue(logFiles);
        life.add(logQueue);

        assertDoesNotThrow(
                () -> logQueue.submit(createTransaction(), LogAppendEvent.NULL).getCommittedTxId());

        logQueue.shutdown();

        assertThatThrownBy(() -> logQueue.submit(createTransaction(), LogAppendEvent.NULL)
                        .getCommittedTxId())
                .isInstanceOf(DatabaseShutdownException.class);
    }

    @Test
    void stillProcessMessagesAfterStop() throws Exception {
        LogFiles logFiles = buildLogFiles(logVersionRepository, transactionIdStore);
        life.add(logFiles);

        TransactionLogQueue logQueue = createLogQueue(logFiles);
        life.add(logQueue);

        assertDoesNotThrow(
                () -> logQueue.submit(createTransaction(), LogAppendEvent.NULL).getCommittedTxId());

        logQueue.stop();

        assertDoesNotThrow(
                () -> logQueue.submit(createTransaction(), LogAppendEvent.NULL).getCommittedTxId());
    }

    private static TransactionToApply createTransaction() {
        PhysicalTransactionRepresentation tx = new PhysicalTransactionRepresentation(List.of(new TestCommand()));
        tx.setHeader(ArrayUtils.EMPTY_BYTE_ARRAY, 1, 2, 3, 4, AuthSubject.ANONYMOUS);
        return new TransactionToApply(tx, CursorContext.NULL_CONTEXT, StoreCursors.NULL);
    }

    private TransactionLogQueue createLogQueue(LogFiles logFiles) {
        return new TransactionLogQueue(
                logFiles, transactionIdStore, databaseHealth, metadataCache, jobScheduler, logProvider);
    }

    private LogFiles buildLogFiles(
            SimpleLogVersionRepository logVersionRepository, SimpleTransactionIdStore transactionIdStore)
            throws IOException {
        var storeId = new StoreId(1, 2, "engine-1", "format-1", 3, 4);
        return LogFilesBuilder.builder(databaseLayout, fileSystem)
                .withLogVersionRepository(logVersionRepository)
                .withRotationThreshold(ByteUnit.mebiBytes(1))
                .withTransactionIdStore(transactionIdStore)
                .withCommandReaderFactory(new TestCommandReaderFactory())
                .withStoreId(storeId)
                .build();
    }
}
