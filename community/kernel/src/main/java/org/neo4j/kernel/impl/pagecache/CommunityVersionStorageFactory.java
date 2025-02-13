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
package org.neo4j.kernel.impl.pagecache;

import org.neo4j.collection.Dependencies;
import org.neo4j.configuration.DatabaseConfig;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.IOController;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.impl.muninn.VersionStorage;
import org.neo4j.kernel.database.DatabaseTracers;
import org.neo4j.kernel.impl.api.TransactionIdSequence;
import org.neo4j.logging.LogProvider;
import org.neo4j.scheduler.JobScheduler;

public class CommunityVersionStorageFactory implements VersionStorageFactory {
    @Override
    public VersionStorage createVersionStorage(
            PageCache pageCache,
            IOController ioController,
            TransactionIdSequence idSequence,
            JobScheduler scheduler,
            LogProvider logProvider,
            Dependencies dependencies,
            DatabaseTracers databaseTracers,
            DatabaseLayout databaseLayout,
            DatabaseConfig databaseConfig) {
        return VersionStorage.EMPTY_STORAGE;
    }
}
