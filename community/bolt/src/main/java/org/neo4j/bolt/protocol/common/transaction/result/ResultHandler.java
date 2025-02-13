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
package org.neo4j.bolt.protocol.common.transaction.result;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.neo4j.bolt.protocol.common.connector.connection.Connection;
import org.neo4j.bolt.protocol.common.message.Error;
import org.neo4j.bolt.protocol.common.message.encoder.DiscardingRecordMessageWriter;
import org.neo4j.bolt.protocol.common.message.encoder.RecordMessageWriter;
import org.neo4j.bolt.protocol.common.message.response.FailureMessage;
import org.neo4j.bolt.protocol.common.message.response.IgnoredMessage;
import org.neo4j.bolt.protocol.common.message.response.SuccessMessage;
import org.neo4j.bolt.protocol.common.message.result.BoltResult;
import org.neo4j.bolt.protocol.common.message.result.ResponseHandler;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.logging.InternalLogProvider;
import org.neo4j.logging.Log;
import org.neo4j.values.AnyValue;
import org.neo4j.values.storable.BooleanValue;
import org.neo4j.values.virtual.MapValue;
import org.neo4j.values.virtual.MapValueBuilder;

public class ResultHandler implements ResponseHandler {
    // Errors that are expected when the client disconnects mid-operation
    private static final Set<Status> CLIENT_MID_OP_DISCONNECT_ERRORS =
            new HashSet<>(Arrays.asList(Status.Transaction.Terminated, Status.Transaction.LockClientStopped));
    private final MapValueBuilder metadata = new MapValueBuilder();

    protected final Log log;
    protected final Connection connection;

    private Error error;
    private boolean ignored;

    @SuppressWarnings("removal")
    public ResultHandler(Connection connection, InternalLogProvider logging) {
        this.connection = connection;
        this.log = logging.getLog(ResultHandler.class);
    }

    @Override
    public boolean onPullRecords(BoltResult result, long size) throws Throwable {
        return markHasMore(result.handleRecords(new RecordMessageWriter(this.connection, this), size));
    }

    @Override
    public boolean onDiscardRecords(BoltResult result, long size) throws Throwable {
        return markHasMore(result.discardRecords(new DiscardingRecordMessageWriter(this), size));
    }

    @Override
    public void onMetadata(String key, AnyValue value) {
        metadata.add(key, value);
    }

    @Override
    public void markIgnored() {
        this.ignored = true;
    }

    @Override
    public void markFailed(Error error) {
        this.error = error;
    }

    @Override
    public void onFinish() {
        try {
            if (ignored) {
                connection.channel().writeAndFlush(IgnoredMessage.INSTANCE).sync();
            } else if (error != null) {
                publishError(error);
            } else {
                connection
                        .channel()
                        .writeAndFlush(new SuccessMessage(getMetadata()))
                        .sync();
            }
        } catch (Throwable e) {
            connection.close();
            log.error("Failed to write response to driver", e);
        } finally {
            clearState();
        }
    }

    private MapValue getMetadata() {
        return metadata.build();
    }

    private void clearState() {
        error = null;
        ignored = false;
        metadata.clear();
    }

    private boolean markHasMore(boolean hasMore) {
        if (hasMore) {
            onMetadata("has_more", BooleanValue.TRUE);
        }
        return hasMore;
    }

    private void publishError(Error error) {
        if (error.isFatal()) {
            log.debug("Publishing fatal error: %s", error);
        }

        var ch = connection.channel();
        var remoteAddress = ch.remoteAddress();

        ch.writeAndFlush(new FailureMessage(error.status(), error.message(), error.isFatal()))
                .addListener(f -> {
                    if (f.isSuccess()) {
                        return;
                    }

                    // TODO: Re-Evaluate after StateMachine refactor (Should be handled upstream)

                    // Can't write error to the client, because the connection is closed.
                    // Very likely our error is related to the connection being closed.

                    // If the error is that the transaction was terminated, then the error is a side-effect of
                    // us cleaning up stuff that was running when the client disconnected. Log a warning without
                    // stack trace to highlight clients are disconnecting while stuff is running:
                    if (CLIENT_MID_OP_DISCONNECT_ERRORS.contains(error.status())) {
                        log.warn(
                                "Client %s disconnected while query was running. Session has been cleaned up. "
                                        + "This can be caused by temporary network problems, but if you see this often, "
                                        + "ensure your applications are properly waiting for operations to complete before exiting.",
                                remoteAddress);
                        return;
                    }

                    // If the error isn't that the tx was terminated, log it to the console for debugging. It's likely
                    // there are other "ok" errors that we can whitelist into the conditional above over time.
                    var ex = f.cause();
                    ex.addSuppressed(error.cause());

                    log.warn("Unable to send error back to the client. " + ex.getMessage(), ex);
                });
    }
}
