package dev.oillamp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import dev.lamp.LampProblem;

/// A place on the host that forwards each connection into a Unix socket inside the sandbox.
///
/// What a relay does is described by a [Route]: where it listens, which sandbox socket it copies
/// into, how many connections it admits, and what to call the far side in a problem message.
///
/// The SSH relays are the first users. The container listens on `sockets/agent/ssh.sock`. The
/// supervisor listens on `run/ssh-primary.sock` and `run/ssh.sock`, which are outside anything
/// mounted into the container, and copies each connection through. Because the agent cannot reach
/// the primary socket, it cannot pose as the shell window whose connecting starts the session. The
/// primary relay admits exactly one connection per session: the terminal window. The extra relay
/// admits any number, for `oillamp shell`.
final class Relay implements AutoCloseable {

    /// The kernel's limit on a Unix socket path, in bytes. Sockets are addressed through the short
    /// runtime directory for this reason, but a long user name or a custom runtime directory can
    /// still exceed it, and `bind` would then fail with an unhelpful `EINVAL`.
    static final int MAX_SOCKET_PATH = 107;

    private static final int BUFFER_BYTES = 64 * 1024;

    /// How many connections the kernel holds for a socket until they are accepted. The default is
    /// 50, and a build that opens more than that through the proxy at once had the rest refused.
    /// The same as the proxy's own limit on open connections.
    static final int ACCEPT_QUEUE = 512;

    private static final InetAddress LOOPBACK = loopback();

    /// Everything that sets one relay apart from another.
    ///
    /// @param listening    where the relay listens on the host
    /// @param sandbox      the Unix socket each connection is copied into
    /// @param admission    whether the relay admits one connection or any number
    /// @param label        what the far side is called in a problem message, such as "the shell (SSH)"
    record Route(Listening listening, Path sandbox, Admission admission, String label) {
        public Route {
            if (label.isBlank()) throw new IllegalArgumentException("a relay needs a label for its problems");
        }
    }

    /// Where a relay listens on the host.
    sealed interface Listening {

        /// A Unix socket file, made 0600 so that only this user may connect.
        record OnUnixSocket(Path socket) implements Listening {}

        /// A TCP port on 127.0.0.1, never on any other address, so that only this machine can
        /// connect. Port 0 asks the kernel for a free one; [Relay#listening()] then says which.
        record OnLoopbackPort(int port) implements Listening {
            public OnLoopbackPort {
                if (port < 0 || port > 65535) throw new IllegalArgumentException("not a TCP port: " + port);
            }
        }
    }

    /// How many connections a relay admits during its life.
    sealed interface Admission {

        /// The first connection, and no other. Each later one is closed and `refusal` reported.
        record OnlyOne(LampProblem refusal) implements Admission {}

        /// Every connection.
        record AnyNumber() implements Admission {}
    }

    /// What the supervisor is told, as it happens. Every method is called from a relay thread.
    interface Listener {
        void connected();
        void disconnected();
        void trouble(LampProblem problem);
    }

    private final Route route;
    private final Listening listening;
    private final Listener listener;
    private final ServerSocketChannel server;
    private final List<SocketChannel> live = new CopyOnWriteArrayList<>();
    private volatile boolean closing;

    private Relay(Route route, Listening listening, Listener listener, ServerSocketChannel server) {
        this.route = route;
        this.listening = listening;
        this.listener = listener;
        this.server = server;
    }

    /// Starts listening where `route` says, and forwards everything it receives into the sandbox.
    static Result<Relay> open(Route route, Listener listener) {
        return listen(route).map(bound -> {
            Relay relay = new Relay(route, bound.listening(), listener, bound.server());
            Thread.ofVirtual().name("oillamp-relay-" + describe(bound.listening())).start(relay::acceptLoop);
            return relay;
        });
    }

    /// Where this relay actually listens. For a loopback port asked for as 0, this holds the port
    /// the kernel picked.
    Listening listening() { return listening; }

    /// A server that is listening, and where. Only a loopback port asked for as 0 ends up somewhere
    /// other than where the route said.
    private record Bound(ServerSocketChannel server, Listening listening) {}

    private static Result<Bound> listen(Route route) {
        return switch (route.listening()) {
            case Listening.OnUnixSocket unix -> bind(unix.socket()).map(server -> new Bound(server, unix));
            case Listening.OnLoopbackPort(int port) -> bindLoopback(port, route.label());
        };
    }

    private static String describe(Listening listening) {
        return switch (listening) {
            case Listening.OnUnixSocket(Path socket) -> String.valueOf(socket.getFileName());
            case Listening.OnLoopbackPort(int port) -> "127.0.0.1:" + port;
        };
    }

    /// Binds a host-only Unix socket at 0600. Used by the relays and by the control socket.
    ///
    /// The path length is checked first, because a path over the kernel's limit fails with an
    /// unhelpful `EINVAL`. An existing file is deleted first, because a socket file left by a
    /// crashed session makes `bind` fail with "address already in use". The container's
    /// entrypoint does the same for its own sockets.
    static Result<ServerSocketChannel> bind(Path socket) {
        int length = socket.toString().getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_SOCKET_PATH)
            return Result.err(ProblemCatalogUtil.socketPathTooLong(socket, MAX_SOCKET_PATH));
        try {
            Path parent = socket.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.deleteIfExists(socket);
            ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                server.bind(UnixDomainSocketAddress.of(socket), ACCEPT_QUEUE);
                // 0600 at once: only this user may connect to the session's relays and control socket.
                FilesystemUtil.setMode(socket, PosixMode.PRIVATE_FILE);
            } catch (IOException | RuntimeException e) {
                server.close();
                throw e;
            }
            return Result.ok(server);
        } catch (IOException e) {
            return Result.err(ProblemCatalogUtil.cannotListen(socket, ProblemCatalogUtil.reason(e)));
        }
    }

    /// Binds a TCP port on 127.0.0.1 only. Port 0 asks the kernel for a free one.
    ///
    /// The address is spelled out as 127.0.0.1 rather than asked of the system, which could answer
    /// with the IPv6 loopback on some machines and would then not match what we tell the user.
    private static Result<Bound> bindLoopback(int port, String label) {
        try {
            ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.INET);
            try {
                server.bind(new InetSocketAddress(LOOPBACK, port), ACCEPT_QUEUE);
                int bound = ((InetSocketAddress) server.getLocalAddress()).getPort();
                return Result.ok(new Bound(server, new Listening.OnLoopbackPort(bound)));
            } catch (IOException | RuntimeException e) {
                server.close();
                throw e;
            }
        } catch (IOException e) {
            return Result.err(ProblemCatalogUtil.cannotListenOnLoopback(port, label, ProblemCatalogUtil.reason(e)));
        }
    }

    /// Whether something is listening on this socket right now.
    ///
    /// Used by the health check during a session. Connecting is the only check that tells a
    /// listening server apart from a leftover file with the right name.
    static boolean answers(Path socket) {
        if (!Files.exists(socket)) return false;
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            return channel.isConnected();
        } catch (IOException refused) {
            return false;
        }
    }

    private void acceptLoop() {
        // Only this thread accepts, so a plain local is enough to remember the one admitted connection.
        boolean admittedOne = false;
        while (server.isOpen()) {
            SocketChannel client;
            try {
                client = server.accept();
            } catch (IOException e) {
                if (!closing) listener.trouble(cannotListen(ProblemCatalogUtil.reason(e)));
                return;
            }
            switch (route.admission()) {
                case Admission.AnyNumber() -> { }
                case Admission.OnlyOne(LampProblem refusal) -> {
                    if (admittedOne) {
                        // A relay for one connection belongs to whoever took it first, such as the
                        // terminal window oillamp opened. A second connection is a mistake.
                        closeQuietly(client);
                        listener.trouble(refusal);
                        continue;
                    }
                    admittedOne = true;
                }
            }
            Thread.ofVirtual().name("oillamp-relay-connection").start(() -> relayOneConnection(client));
        }
    }

    private LampProblem cannotListen(String reason) {
        return switch (listening) {
            case Listening.OnUnixSocket(Path socket) -> ProblemCatalogUtil.cannotListen(socket, reason);
            case Listening.OnLoopbackPort(int port) ->
                    ProblemCatalogUtil.cannotListenOnLoopback(port, route.label(), reason);
        };
    }

    /// One connection: open the far side, copy both ways, and report it when it ends.
    private void relayOneConnection(SocketChannel client) {
        SocketChannel sandbox;
        try {
            sandbox = SocketChannel.open(UnixDomainSocketAddress.of(route.sandbox()));
        } catch (IOException e) {
            // The sandbox's socket is gone or refusing. Whoever connected only sees a connection
            // that opens and closes, so this is reported in the supervisor's terminal.
            listener.trouble(ProblemCatalogUtil.sandboxEndpointDead(route.label(), route.sandbox(),
                    "the sandbox", "connecting to " + route.label() + ": " + ProblemCatalogUtil.reason(e)));
            closeQuietly(client);
            return;
        }
        live.add(client);
        live.add(sandbox);
        listener.connected();

        AtomicInteger directions = new AtomicInteger(2);
        Runnable finished = () -> {
            if (directions.decrementAndGet() > 0) return;
            live.remove(client);
            live.remove(sandbox);
            closeQuietly(client);
            closeQuietly(sandbox);
            if (!closing) listener.disconnected();
        };
        Thread.ofVirtual().start(() -> { copy(client, sandbox); finished.run(); });
        Thread.ofVirtual().start(() -> { copy(sandbox, client); finished.run(); });
    }

    /// Moves bytes until one end stops talking, then half-closes the other.
    ///
    /// The half-close matters for SSH: the client sends EOF when the user types `exit`,
    /// and a relay that closed both directions at that moment would cut off the server's goodbye.
    private static void copy(SocketChannel from, SocketChannel to) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_BYTES);
        try {
            while (from.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) to.write(buffer);
                buffer.clear();
            }
            to.shutdownOutput();
        } catch (IOException ignored) {
            // Either end going away is how a session normally ends, not a failure to report.
        }
    }

    /// Stops accepting, drops every connection and removes the socket file, if it listens on one.
    ///
    /// The first step of the shutdown sequence. Safe to call more than once, because the terminal
    /// closing, a signal and `oillamp stop` can all start a shutdown.
    @Override public void close() {
        closing = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // Already closed, or never opened. Either way there is nothing left to do about it.
        }
        for (SocketChannel channel : live) closeQuietly(channel);
        live.clear();
        switch (listening) {
            case Listening.OnUnixSocket(Path socket) -> {
                try {
                    Files.deleteIfExists(socket);
                } catch (IOException ignored) {
                    // A socket file we cannot remove is tidied by the next session, which unlinks first.
                }
            }
            case Listening.OnLoopbackPort port -> { }
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException impossible) {
            // Only thrown for an address of the wrong length.
            throw new IllegalStateException(impossible);
        }
    }

    private static void closeQuietly(Channel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Closing is best-effort by definition; the fd is going away with the process anyway.
        }
    }
}
