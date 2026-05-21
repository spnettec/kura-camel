/*******************************************************************************
 * Copyright (c) 2026 YOFC
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.kura.camel.component;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watch a single file on disk and fire a callback when its content is modified.
 *
 * <p>WatchService can only register on directories, so we watch the parent
 * directory and filter events by file name. A short debounce coalesces the
 * typical "write twice in 50ms" pattern many editors produce (atomic save
 * via temp file + rename).
 */
public final class FileChangeWatcher implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(FileChangeWatcher.class);

    /** Wait this long after first event before invoking callback (editor write-twice debounce). */
    private static final long DEBOUNCE_MILLIS = 250;

    private final Path absoluteFile;
    private final Runnable onChange;
    private final WatchService watchService;
    private final Thread thread;
    private volatile boolean closed;

    /**
     * @param filePath
     *            absolute path to the watched file
     * @param onChange
     *            callback invoked from the watcher thread on each detected modification
     * @throws IOException
     *             if the parent directory cannot be registered with the watch service
     */
    public FileChangeWatcher(final String filePath, final Runnable onChange) throws IOException {
        this.absoluteFile = Paths.get(filePath).toAbsolutePath();
        this.onChange = onChange;
        final Path dir = this.absoluteFile.getParent();
        if (dir == null) {
            throw new IOException("File has no parent directory: " + filePath);
        }
        this.watchService = FileSystems.getDefault().newWatchService();
        dir.register(this.watchService, ENTRY_MODIFY, ENTRY_CREATE);
        this.thread = new Thread(this::runLoop, "FileChangeWatcher-" + this.absoluteFile.getFileName());
        this.thread.setDaemon(true);
    }

    public void start() {
        this.thread.start();
        logger.info("Watching file for changes: {}", this.absoluteFile);
    }

    private void runLoop() {
        final Path watchedName = this.absoluteFile.getFileName();
        while (!this.closed) {
            final WatchKey key;
            try {
                key = this.watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }

            boolean fireCallback = false;
            for (final WatchEvent<?> evt : key.pollEvents()) {
                if (evt.kind() == OVERFLOW) {
                    // events were dropped; treat as change to be safe
                    fireCallback = true;
                    continue;
                }
                final Object ctx = evt.context();
                if (ctx instanceof Path && watchedName.equals(((Path) ctx).getFileName())) {
                    fireCallback = true;
                }
            }

            if (!key.reset()) {
                logger.warn("Watch key invalid for {}, stopping watcher", this.absoluteFile);
                return;
            }

            if (fireCallback) {
                try {
                    Thread.sleep(DEBOUNCE_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // drain any events queued during the debounce window
                final WatchKey drain = this.watchService.poll();
                if (drain != null) {
                    drain.pollEvents();
                    drain.reset();
                }
                try {
                    this.onChange.run();
                } catch (Throwable t) {
                    logger.warn("File-change callback failed for {}", this.absoluteFile, t);
                }
            }
        }
    }

    @Override
    public void close() {
        this.closed = true;
        try {
            this.watchService.close();
        } catch (IOException e) {
            // ignore
        }
        this.thread.interrupt();
        logger.info("Stopped watching file: {}", this.absoluteFile);
    }

    public Path getAbsoluteFile() {
        return this.absoluteFile;
    }
}
