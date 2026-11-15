package dev.gui.genie;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// The claim one Genies holds on its [Shelf], so that no second Genies opens on the same shelf
/// while it runs.
///
/// Two would take the same genies from each other: each joins the lamps the other lit, and
/// putting a genie to sleep in one puts out the lamp the other uses; and the second fails to
/// wake a genie whose lamp the first holds. So a second Genies asks the one that holds the claim
/// to show its window, and does not open: see [#claimOrShowTheOther].
///
/// The claim is an operating-system lock on [Shelf#claimLock], which the kernel lets go of when
/// Genies ends, however it ends. Beside it, the holder listens on the Unix socket
/// [Shelf#claimSocket] for another Genies asking it to show itself.
public final class ShelfClaim implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ShelfClaim.class);

    /// What the Genies that holds the claim answered when another asked it to show its window.
    public enum Answer {
        /// It shows its window, or is about to, as it is opening. Shown, a window is no longer
        /// minimized, and the desktop is asked to bring it to the front, which it may refuse.
        SHOWN,
        /// It is closing: putting the genies to sleep, or leaving them running.
        CLOSING,
        /// It did not answer: it could not be reached, or took too long.
        NONE
    }

    /// How long [#askToShow] waits for the holder to answer. The holder may have taken the claim
    /// a moment ago and not be listening yet.
    public static final Duration PATIENCE = Duration.ofSeconds(5);

    private static final String SHOW = "show";

    /// The lock files of the shelves claimed in this process. Linux keeps a file lock per process
    /// and file, and closing any channel on the file lets go of it, so a claim made here is
    /// refused without opening its file again. Guarded by itself.
    private static final Set<Path> CLAIMED_HERE = new HashSet<>();

    private final Path lockFile;
    private final Path socket;
    private final FileChannel channel;
    private final FileLock lock;
    private final Optional<ServerSocketChannel> listening;
    /// Whether [#close] gave the shelf up already. Guarded by [#CLAIMED_HERE].
    private boolean closed;

    private ShelfClaim(Path lockFile, Path socket, FileChannel channel, FileLock lock, Optional<ServerSocketChannel> listening) {
        this.lockFile = lockFile;
        this.socket = socket;
        this.channel = channel;
        this.lock = lock;
        this.listening = listening;
    }

    /// Claims `shelf` for this Genies, and from then on answers each Genies that asks it to show
    /// its window with what `whenAsked` answers. `whenAsked` is called on a thread of its own.
    ///
    /// When the claim is held, but the socket cannot be listened on, the claim holds all the
    /// same; a second Genies then hears no answer.
    ///
    /// @return the claim, or empty when another Genies holds it
    /// @throws IOException when the shelf's lock file could not be made or locked
    public static Optional<ShelfClaim> claim(Shelf shelf, Supplier<Answer> whenAsked) throws IOException {
        Files.createDirectories(shelf.root());
        // The real path makes two names of one shelf one key, such as a shelf reached through a link.
        Path lockFile = shelf.root().toRealPath().resolve(shelf.claimLock().getFileName());
        synchronized (CLAIMED_HERE) {
            if (CLAIMED_HERE.contains(lockFile)) return Optional.empty();
            FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                                                   StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (IOException | RuntimeException failed) {
                channel.close();
                throw failed;
            }
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            CLAIMED_HERE.add(lockFile);
            Path socket = shelf.claimSocket();
            return Optional.of(new ShelfClaim(lockFile, socket, channel, lock, listenOn(socket, whenAsked)));
        }
    }

    /// Claims `shelf`, as [#claim] does; or, while another Genies holds it, asks that one to show
    /// its window, with [#askToShow]. When it does not answer that it shows it, the shelf is
    /// claimed once more, as the other may have ended meanwhile; when it is still held,
    /// `notShown` hears the answer, to tell the user why no window came.
    ///
    /// @return the claim, when this Genies is to open; empty when it is not
    /// @throws IOException when the shelf's lock file could not be made or locked at first
    public static Optional<ShelfClaim> claimOrShowTheOther(Shelf shelf, Supplier<Answer> whenAsked,
                                                           Consumer<Answer> notShown) throws IOException {
        Optional<ShelfClaim> claim = claim(shelf, whenAsked);
        if (claim.isPresent()) return claim;
        Answer answer = askToShow(shelf);
        if (answer == Answer.SHOWN) return Optional.empty();
        try {
            claim = claim(shelf, whenAsked);
        } catch (IOException failed) {
            // The other Genies holds the shelf, as far as this one knows: it must not open beside it.
            LOG.warn("Could not claim the shelf {} again, so it counts as held still", shelf.root(), failed);
            claim = Optional.empty();
        }
        if (claim.isEmpty()) notShown.accept(answer);
        return claim;
    }

    /// Asks the Genies that holds the claim on `shelf` to show its window, and waits for its
    /// answer, at most for [#PATIENCE].
    public static Answer askToShow(Shelf shelf) {
        Path socket = shelf.claimSocket();
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        AtomicReference<Answer> answer = new AtomicReference<>(Answer.NONE);
        Thread asking = Thread.ofVirtual().name("ask the other Genies").start(() -> {
            while (System.nanoTime() < deadline) {
                try (SocketChannel connection = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                    connection.write(ByteBuffer.wrap((SHOW + "\n").getBytes(StandardCharsets.UTF_8)));
                    String said = new BufferedReader(new InputStreamReader(Channels.newInputStream(connection),
                                                                           StandardCharsets.UTF_8)).readLine();
                    for (Answer known : Answer.values())
                        if (known != Answer.NONE && known.name().equals(said)) answer.set(known);
                    return;
                } catch (IOException notListeningYet) {
                    // The holder may not listen yet, as it took the claim a moment ago. Or this
                    // thread was interrupted, which closed the connection, and the sleep ends it.
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException giveUp) {
                    return;
                }
            }
        });
        try {
            // A holder that never answers keeps this one waiting no longer: interrupting the
            // thread closes its connection.
            if (!asking.join(PATIENCE.plusSeconds(1))) asking.interrupt();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return Objects.requireNonNullElse(answer.get(), Answer.NONE);
    }

    /// Listens on `socket`, which a Genies that crashed may have left behind: no other Genies
    /// listens there while this one holds the claim.
    private static Optional<ServerSocketChannel> listenOn(Path socket, Supplier<Answer> whenAsked) {
        ServerSocketChannel server = null;
        try {
            Files.deleteIfExists(socket);
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
        } catch (IOException | RuntimeException failed) {
            // Such as a path too long for a Unix socket. Genies still holds its shelf alone.
            LOG.warn("Could not listen on {}, so another Genies started on this shelf cannot ask this one to show itself",
                     socket, failed);
            if (server != null) closeQuietly(server);
            return Optional.empty();
        }
        ServerSocketChannel listening = server;
        Thread.ofVirtual().name("answer another Genies").start(() -> {
            while (listening.isOpen()) {
                SocketChannel connection;
                try {
                    connection = listening.accept();
                } catch (IOException closed) {
                    return;
                }
                Thread.ofVirtual().name("show this Genies").start(() -> answer(connection, whenAsked));
            }
        });
        return Optional.of(listening);
    }

    private static void answer(SocketChannel connection, Supplier<Answer> whenAsked) {
        try (connection) {
            String asked = new BufferedReader(new InputStreamReader(Channels.newInputStream(connection),
                                                                    StandardCharsets.UTF_8)).readLine();
            if (!SHOW.equals(asked)) return;
            Answer answer = whenAsked.get();
            connection.write(ByteBuffer.wrap((answer.name() + "\n").getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException failed) {
            LOG.warn("Could not answer another Genies that asked this one to show itself", failed);
        }
    }

    private static void closeQuietly(java.nio.channels.Channel channel) {
        try {
            channel.close();
        } catch (IOException alreadyGone) {
            // Nothing is left to close.
        }
    }

    /// Gives the shelf up: stops listening, and lets go of the lock. Genies itself never does,
    /// it holds the shelf until it ends.
    @Override public void close() throws IOException {
        synchronized (CLAIMED_HERE) {
            if (closed) return;
            closed = true;
            try {
                if (listening.isPresent()) {
                    closeQuietly(listening.get());
                    Files.deleteIfExists(socket);
                }
                if (lock.isValid()) lock.release();
            } finally {
                channel.close();
                CLAIMED_HERE.remove(lockFile);
            }
        }
    }
}
