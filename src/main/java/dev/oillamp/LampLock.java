package dev.oillamp;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The exclusive lock on a lamp, held by the supervisor for the whole session, so that only one
/// session can run on a lamp at a time.
///
/// It is an operating-system file lock on `.oillamp/lock`, not a file containing a process
/// id, because the kernel releases the lock when the process dies, however it dies. After a
/// `kill -9` or a power cut, the next `oillamp at` simply starts.
///
/// `session.json` beside it is only informational. The lock decides whether a session is
/// running.
///
/// The same class locks the history and the schedule, where several threads of one process may
/// ask for one file at once. Linux keeps such a lock per process and file, not per open file:
/// closing *any* descriptor of the file releases every lock the process holds on it. So this
/// class never opens a file a second time while the process holds a lock on it. It remembers
/// what the process holds, and answers a second request from that: a share of a shared lock
/// joins it, anything else is refused, without touching the file. A channel whose lock was taken
/// is kept open for the next try rather than closed, at most one for each file.
final class LampLock implements AutoCloseable {

    /// What this process holds, by the real path of the lock file. Guarded by itself.
    private static final Map<Path, Held> HELD = new HashMap<>();
    /// A channel on each lock file whose lock was taken when this process last asked, kept open
    /// for the next try instead of closed. The lock may be held by another channel of this
    /// process that this class does not know of, and closing this one would release it.
    /// Guarded by [#HELD].
    private static final Map<Path, Spare> SPARE = new HashMap<>();

    /// A channel kept open, with the identity of the file it was opened on: a lock file deleted
    /// and made again under the same name is another file, which the channel cannot lock.
    private record Spare(FileChannel channel, Object fileKey) {}

    /// One lock this process holds, and how many [LampLock]s hold it.
    private static final class Held {
        final FileChannel channel;
        final FileLock lock;
        int holders = 1;

        Held(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }
    }

    private final Path file;
    private final Held held;
    /// Whether [#close] already let go. Guarded by [#HELD].
    private boolean closed;

    private LampLock(Path file, Held held) {
        this.file = file;
        this.held = held;
    }

    /// Tries to claim the lamp.
    ///
    /// @return the held lock, or empty if another session has it
    public static Optional<LampLock> tryAcquire(Path lockFile) throws IOException {
        return tryAcquire(lockFile, false);
    }

    /// Tries to claim the lock, or with `shared` a share of it, which others that only want a
    /// share can hold at the same time.
    ///
    /// @return the held lock, or empty if another holds it in a way that excludes this, in this
    ///         process or in another
    public static Optional<LampLock> tryAcquire(Path lockFile, boolean shared) throws IOException {
        Path parent = lockFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        synchronized (HELD) {
            // A file the process holds a lock on exists, so creating one here never opens a
            // file that is held. The real path makes two names of one file one key, such as a
            // lamp reached through a link.
            if (!Files.exists(lockFile)) {
                try {
                    Files.createFile(lockFile);
                } catch (FileAlreadyExistsException madeMeanwhile) {
                    // Another process made it first; it is the same file either way.
                }
            }
            Path file = lockFile.toRealPath();
            Held known = HELD.get(file);
            if (known != null) {
                if (!shared || !known.lock.isShared()) return Optional.empty();
                known.holders++;
                return Optional.of(new LampLock(file, known));
            }
            Object fileKey = Objects.requireNonNullElse(
                    Files.readAttributes(file, BasicFileAttributes.class).fileKey(), file);
            Spare spare = SPARE.remove(file);
            if (spare != null && !spare.fileKey().equals(fileKey)) {
                // The file it was opened on is gone, so nothing holds a lock through it.
                spare.channel().close();
                spare = null;
            }
            FileChannel channel = spare != null && spare.channel().isOpen() ? spare.channel()
                    : FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock(0, Long.MAX_VALUE, shared);
            } catch (OverlappingFileLockException heldHereElsewhere) {
                lock = null;
            } catch (IOException | RuntimeException failed) {
                SPARE.put(file, new Spare(channel, fileKey));
                throw failed;
            }
            if (lock == null) {
                // Taken by another process, or by a channel of this one that this class did not
                // open. Either way the channel stays open for the next try.
                SPARE.put(file, new Spare(channel, fileKey));
                return Optional.empty();
            }
            Held taken = new Held(channel, lock);
            HELD.put(file, taken);
            return Optional.of(new LampLock(file, taken));
        }
    }

    /// Claims a lock that is only ever held for moments, such as the one on a lamp's schedule,
    /// waiting for whoever holds it.
    ///
    /// Waits by trying again, rather than by the operating system's blocking lock, because the
    /// operating system does not make two threads of one process wait for each other.
    ///
    /// @return the held lock, or empty if it was still taken after `patience`
    public static Optional<LampLock> acquireWithin(Path lockFile, Duration patience) throws IOException {
        return acquireWithin(lockFile, false, patience);
    }

    /// The same, or with `shared` a share of it.
    public static Optional<LampLock> acquireWithin(Path lockFile, boolean shared, Duration patience) throws IOException {
        long deadline = System.nanoTime() + patience.toNanos();
        while (true) {
            Optional<LampLock> lock = tryAcquire(lockFile, shared);
            if (lock.isPresent() || System.nanoTime() > deadline) return lock;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /// Lets go of this hold. The file lock itself is released once nothing in this process holds
    /// it any more.
    @Override public void close() throws IOException {
        synchronized (HELD) {
            if (closed) return;
            closed = true;
            if (--held.holders > 0) return;
            HELD.remove(file);
            try {
                if (held.lock.isValid()) held.lock.release();
            } finally {
                held.channel.close();
            }
        }
    }
}
