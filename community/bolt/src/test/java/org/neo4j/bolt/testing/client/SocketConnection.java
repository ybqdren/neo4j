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
package org.neo4j.bolt.testing.client;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketOption;
import java.net.SocketTimeoutException;
import org.neo4j.common.HexPrinter;
import org.neo4j.internal.helpers.HostnamePort;

public class SocketConnection extends AbstractTransportConnection {
    private static final Factory factory = new Factory();

    protected final HostnamePort address;

    protected Socket socket;
    protected InputStream in;
    protected OutputStream out;

    public static TransportConnection.Factory factory() {
        return factory;
    }

    public SocketConnection(HostnamePort address) {
        requireNonNull(address);

        this.address = address;
    }

    protected Socket createSocket() {
        return new Socket();
    }

    private void ensureConnected() {
        if (this.socket == null || this.in == null || this.out == null) {
            throw new IllegalStateException(
                    "Client has not established a connection - Make sure your test is configured correctly");
        }
    }

    @Override
    public TransportConnection connect() throws IOException {
        if (this.socket != null && this.socket.isConnected()) {
            return this;
        }

        this.socket = this.createSocket();
        this.socket.setSoTimeout((int) MINUTES.toMillis(5000));
        this.socket.connect(new InetSocketAddress(address.getHost(), address.getPort()));

        this.in = this.socket.getInputStream();
        this.out = this.socket.getOutputStream();

        return this;
    }

    @Override
    public <T> TransportConnection setOption(SocketOption<T> option, T value) {
        this.ensureConnected();

        try {
            this.socket.setOption(option, value);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to apply socket option", ex);
        }

        return this;
    }

    @Override
    public TransportConnection disconnect() throws IOException {
        if (socket != null && this.socket.isConnected()) {
            this.socket.close();

            this.socket = null;
            this.in = null;
            this.out = null;
        }

        return this;
    }

    @Override
    public TransportConnection sendRaw(ByteBuf rawBytes) throws IOException {
        this.ensureConnected();

        var heap = new byte[128];
        while (rawBytes.isReadable()) {
            var transmittedBytes = Math.min(heap.length, rawBytes.readableBytes());
            rawBytes.readBytes(heap, 0, transmittedBytes);
            out.write(heap, 0, transmittedBytes);
        }

        return this;
    }

    @Override
    public ByteBuf receive(int length) throws IOException {
        this.ensureConnected();

        var bytes = new byte[length];
        var left = length;
        int read;

        try {
            long startedAt = System.currentTimeMillis();

            while (left > 0 && (read = in.read(bytes, length - left, left)) != -1) {
                left -= read;
            }
        } catch (SocketTimeoutException e) {
            throw new SocketTimeoutException(
                    "Reading data timed out, missing " + left + " bytes. Buffer: " + HexPrinter.hex(bytes));
        }
        // all the bytes could not be read, fail
        if (left != 0) {
            throw new IOException("Failed to read " + length + " bytes, missing " + left + " bytes. Buffer: "
                    + HexPrinter.hex(bytes));
        }

        return Unpooled.wrappedBuffer(bytes);
    }

    private static class Factory implements TransportConnection.Factory {

        @Override
        public TransportConnection create(HostnamePort address) {
            return new SocketConnection(address);
        }

        @Override
        public String toString() {
            return "Plain Socket";
        }
    }
}
