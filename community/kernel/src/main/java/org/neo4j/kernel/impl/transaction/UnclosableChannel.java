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
package org.neo4j.kernel.impl.transaction;

import java.io.IOException;
import org.neo4j.io.fs.DelegatingStoreChannel;
import org.neo4j.kernel.impl.transaction.log.LogVersionedStoreChannel;

public class UnclosableChannel extends DelegatingStoreChannel<LogVersionedStoreChannel>
        implements LogVersionedStoreChannel {
    public UnclosableChannel(LogVersionedStoreChannel channel) {
        super(channel);
    }

    @Override
    public long getVersion() {
        return delegate.getVersion();
    }

    @Override
    public byte getLogFormatVersion() {
        return delegate.getLogFormatVersion();
    }

    @Override
    public void close() throws IOException {
        // do not close since channel is shared
    }
}
