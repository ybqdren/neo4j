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
package org.neo4j.dbms.database;

import static java.util.Objects.requireNonNull;
import static org.neo4j.dbms.database.TicketMachine.Barrier.NO_BARRIER;

import java.io.IOException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.collections.api.set.ImmutableSet;
import org.neo4j.dbms.database.TicketMachine.Barrier;
import org.neo4j.dbms.database.TicketMachine.Ticket;
import org.neo4j.io.pagecache.IOController;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.io.pagecache.buffer.IOBufferFactory;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.impl.muninn.VersionStorage;
import org.neo4j.io.pagecache.monitoring.PageFileCounters;
import org.neo4j.io.pagecache.tracing.DatabaseFlushEvent;
import org.neo4j.io.pagecache.tracing.FileFlushEvent;
import org.neo4j.io.pagecache.tracing.FileMappedListener;
import org.neo4j.io.pagecache.tracing.version.FileTruncateEvent;

/**
 * Wrapper around global page cache for an individual database. Abstracts the knowledge that database can have about other databases mapped files
 * by restricting access to files that mapped by other databases.
 * Any lookup or attempts to flush/close page file or cache itself will influence only files that were mapped by particular database over this wrapper.
 * Database specific page cache lifecycle tight to an individual database, and it will be closed as soon as the particular database will be closed.
 */
public class DatabasePageCache implements PageCache {

    private final PageCache globalPageCache;
    private final CopyOnWriteArrayList<DatabasePageFile> databasePagedFiles = new CopyOnWriteArrayList<>();
    private final IOController ioController;
    private final List<FileMappedListener> mappedListeners = new CopyOnWriteArrayList<>();
    private boolean closed;
    private final TicketMachine ticketMachine = new TicketMachine();
    private final VersionStorage versionStorage;

    public DatabasePageCache(PageCache globalPageCache, IOController ioController, VersionStorage versionStorage) {
        this.globalPageCache = requireNonNull(globalPageCache);
        this.ioController = requireNonNull(ioController);
        this.versionStorage = requireNonNull(versionStorage);
    }

    @Override
    public PagedFile map(
            Path path,
            int pageSize,
            String databaseName,
            ImmutableSet<OpenOption> openOptions,
            IOController ignoredController,
            VersionStorage ignoredVersionStorage)
            throws IOException {
        // no one should call this version of map method with emptyDatabaseName != null,
        // since it is this class that is decorating map calls with the name of the database
        PagedFile pagedFile =
                globalPageCache.map(path, pageSize, databaseName, openOptions, ioController, versionStorage);
        DatabasePageFile databasePageFile =
                new DatabasePageFile(pagedFile, databasePagedFiles, mappedListeners, ticketMachine.newTicket());
        databasePagedFiles.add(databasePageFile);
        invokeFileMapListeners(mappedListeners, databasePageFile);
        return databasePageFile;
    }

    @Override
    public Optional<PagedFile> getExistingMapping(Path path) {
        Path canonicalFile = path.normalize();
        return databasePagedFiles.stream()
                .filter(pagedFile -> pagedFile.path().equals(canonicalFile))
                .map(pf -> (PagedFile) pf)
                .findFirst();
    }

    @Override
    public List<PagedFile> listExistingMappings() {
        return new ArrayList<>(databasePagedFiles);
    }

    @Override
    public void flushAndForce(DatabaseFlushEvent flushEvent) throws IOException {
        flushAndForce(flushEvent, NO_BARRIER);
    }

    private void flushAndForce(DatabaseFlushEvent flushEvent, Barrier barrier) throws IOException {
        for (DatabasePageFile pagedFile : databasePagedFiles) {
            if (barrier.canPass(pagedFile.flushTicket())) {
                try (FileFlushEvent fileFlushEvent = flushEvent.beginFileFlush()) {
                    pagedFile.flushAndForce(fileFlushEvent);
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            throw new IllegalStateException("Database page cache was already closed");
        }
        for (PagedFile pagedFile : databasePagedFiles) {
            pagedFile.close();
        }
        databasePagedFiles.clear();
        closed = true;
    }

    @Override
    public int pageSize() {
        return globalPageCache.pageSize();
    }

    @Override
    public int pageReservedBytes(ImmutableSet<OpenOption> openOptions) {
        return globalPageCache.pageReservedBytes(openOptions);
    }

    @Override
    public long maxCachedPages() {
        return globalPageCache.maxCachedPages();
    }

    @Override
    public IOBufferFactory getBufferFactory() {
        return globalPageCache.getBufferFactory();
    }

    private static void invokeFileMapListeners(List<FileMappedListener> listeners, DatabasePageFile databasePageFile) {
        for (FileMappedListener mappedListener : listeners) {
            mappedListener.fileMapped(databasePageFile);
        }
    }

    private static void invokeFileUnmapListeners(
            List<FileMappedListener> listeners, DatabasePageFile databasePageFile) {
        for (FileMappedListener mappedListener : listeners) {
            mappedListener.fileUnmapped(databasePageFile);
        }
    }

    public void registerFileMappedListener(FileMappedListener mappedListener) {
        mappedListeners.add(mappedListener);
    }

    public void unregisterFileMappedListener(FileMappedListener mappedListener) {
        mappedListeners.remove(mappedListener);
    }

    public FlushGuard flushGuard(DatabaseFlushEvent flushEvent) {
        Barrier barrier = ticketMachine.nextBarrier();
        return () -> flushAndForce(flushEvent, barrier);
    }

    /**
     * A flush guard to flush any mapped and un-flushed files since creation of the guard
     */
    @FunctionalInterface
    public interface FlushGuard {
        void flushUnflushed() throws IOException;
    }

    private static class DatabasePageFile implements PagedFile {
        private final PagedFile delegate;
        private final List<DatabasePageFile> databaseFiles;
        private final List<FileMappedListener> mappedListeners;
        private final Ticket flushTicket;

        DatabasePageFile(
                PagedFile delegate,
                List<DatabasePageFile> databaseFiles,
                List<FileMappedListener> mappedListeners,
                Ticket flushTicket) {
            this.delegate = delegate;
            this.databaseFiles = databaseFiles;
            this.mappedListeners = mappedListeners;
            this.flushTicket = flushTicket;
        }

        @Override
        public PageCursor io(long pageId, int pf_flags, CursorContext context) throws IOException {
            return delegate.io(pageId, pf_flags, context);
        }

        @Override
        public int pageSize() {
            return delegate.pageSize();
        }

        @Override
        public int payloadSize() {
            return delegate.payloadSize();
        }

        @Override
        public int pageReservedBytes() {
            return delegate.pageReservedBytes();
        }

        @Override
        public long fileSize() throws IOException {
            return delegate.fileSize();
        }

        @Override
        public Path path() {
            return delegate.path();
        }

        @Override
        public void flushAndForce(FileFlushEvent flushEvent) throws IOException {
            delegate.flushAndForce(flushEvent);
            flushTicket.use();
        }

        @Override
        public long getLastPageId() throws IOException {
            return delegate.getLastPageId();
        }

        @Override
        public void increaseLastPageIdTo(long newLastPageId) {
            delegate.increaseLastPageIdTo(newLastPageId);
        }

        @Override
        public void close() {
            invokeFileUnmapListeners(mappedListeners, this);
            delegate.close();
            databaseFiles.remove(this);
        }

        @Override
        public void setDeleteOnClose(boolean deleteOnClose) {
            delegate.setDeleteOnClose(deleteOnClose);
        }

        @Override
        public boolean isDeleteOnClose() {
            return delegate.isDeleteOnClose();
        }

        @Override
        public String getDatabaseName() {
            return delegate.getDatabaseName();
        }

        @Override
        public PageFileCounters pageFileCounters() {
            return delegate.pageFileCounters();
        }

        @Override
        public boolean isMultiVersioned() {
            return delegate.isMultiVersioned();
        }

        @Override
        public void truncate(long pagesToKeep, FileTruncateEvent fileTruncateEvent) throws IOException {
            delegate.truncate(pagesToKeep, fileTruncateEvent);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DatabasePageFile that = (DatabasePageFile) o;
            return delegate.equals(that.delegate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(delegate);
        }

        Ticket flushTicket() {
            return flushTicket;
        }
    }
}
