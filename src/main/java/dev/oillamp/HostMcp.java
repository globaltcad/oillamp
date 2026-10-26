package dev.oillamp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.lamp.LampHostRisk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sprouts.Tuple;

/// The host tools, served to the sandbox as an MCP server on `sockets/host/host-access.sock`.
///
/// MCP (the Model Context Protocol) is how an agent harness such as pi finds tools it does not
/// have built in, and calls them. Inside the sandbox, pi starts `socat`, which copies bytes
/// between its own standard input and output and this socket; pi speaks MCP to it as to any
/// local MCP server. So the protocol is MCP's own over standard input and output: one JSON-RPC
/// message per line, on one connection that stays open for the whole pi session.
///
/// ```
///  pi ── stdin/stdout ── socat ── host-access.sock ──▶ HostMcp ──▶ HostAccess ──▶ the host
///                                                      (adapts)    (decides, does)
/// ```
///
/// This class adapts and decides nothing about the host. Which tools are listed is
/// [HostTool#offeredBy]; what a call's arguments mean is [HostTool#read]; whether a call
/// happens is [HostAccess#answer]. What to do with one message is [#decide], a pure function
/// of the connection's [Stage] and the message; [Server] carries that out.
///
/// Only what host access needs is spoken: `initialize`, `ping`, `tools/list` and `tools/call`,
/// and the notifications `notifications/initialized` and `notifications/cancelled`. No
/// resources, prompts or sampling: a resource read would be a second way to host data, past the
/// gate.
final class HostMcp {

    private HostMcp() {}

    /// The protocol versions this server speaks, newest first. A client asking for another is
    /// answered with the newest, which MCP lets it accept or hang up on.
    static final Tuple<String> VERSIONS = Tuple.of(String.class, "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    /// The longest message read, in bytes. A file written with `host_write_file` is in one
    /// message, so this is generous; anything longer is refused rather than held in memory.
    static final int LONGEST_MESSAGE = 8 * 1024 * 1024;

    /// How many calls one connection may have waiting at once. Each may wait for a model and a
    /// person, and pi calls tools one after another; this only bounds a client that does not.
    static final int CALLS_AT_ONCE = 8;

    // JSON-RPC's error codes, as MCP uses them.
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    // ─── a message, read ────────────────────────────────────────────────────────────────────

    /// One message from the client, read from its line of JSON.
    sealed interface Incoming {
        /// A request, which is answered with its `id`.
        record Request(JsonNode id, String method, JsonNode params) implements Incoming {}
        /// A notification, which has no `id` and is never answered.
        record Notification(String method, JsonNode params) implements Incoming {}
        /// A response to a request of ours. This server sends none, so there is nothing to match.
        record Response() implements Incoming {}
        /// Not a JSON-RPC message. Answered with an error, with the id when one could be read.
        record Unreadable(JsonNode id, int code, String message) implements Incoming {}
    }

    /// Reads one line as a JSON-RPC 2.0 message.
    static Incoming read(String line) {
        Optional<JsonNode> parsed = JsonUtil.parse(line);
        if (parsed.isEmpty()) return new Incoming.Unreadable(NullNode.instance, PARSE_ERROR,
                HostAccessProblems.notARequest().whatHappened());
        JsonNode message = parsed.get();
        if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asText()))
            return new Incoming.Unreadable(NullNode.instance, INVALID_REQUEST, "not a JSON-RPC 2.0 message");
        JsonNode id = message.path("id");
        JsonNode method = message.path("method");
        JsonNode params = message.has("params") ? message.get("params") : JsonUtil.object();
        if (method.isMissingNode())
            return message.has("result") || message.has("error") ? new Incoming.Response()
                    : new Incoming.Unreadable(id.isMissingNode() ? NullNode.instance : id, INVALID_REQUEST, "no method");
        if (!method.isTextual())
            return new Incoming.Unreadable(id.isMissingNode() ? NullNode.instance : id, INVALID_REQUEST, "method is not a string");
        if (id.isMissingNode()) return new Incoming.Notification(method.asText(), params);
        if (!id.isTextual() && !id.isIntegralNumber())
            return new Incoming.Unreadable(NullNode.instance, INVALID_REQUEST, "id is not a string or a number");
        return new Incoming.Request(id, method.asText(), params);
    }

    // ─── what to do with it ─────────────────────────────────────────────────────────────────

    /// Where a connection is in MCP's lifecycle. A client must `initialize` before anything
    /// else; the version agreed is the one the client asked for, when it is one of [#VERSIONS].
    sealed interface Stage {
        record Fresh() implements Stage {}
        record Initialized(String version) implements Stage {}
    }

    /// What the connection does about one message.
    sealed interface Reaction {
        /// Writes one line back.
        record Send(String line) implements Reaction {}
        /// Answers with an error, and tells the user that a call could not be read as a request.
        ///
        /// @param tool the tool it named, or `?` when it named none
        record Refuse(JsonNode id, int code, String message, String tool) implements Reaction {}
        /// Takes the request to the gate, on a thread of its own, and answers with what the gate
        /// decided, unless it is cancelled first.
        record Perform(JsonNode id, HostTool tool) implements Reaction {}
        /// Withdraws the call with that id, if it is still waiting or running. It is not answered.
        record Cancel(JsonNode id) implements Reaction {}
        /// Nothing to do.
        record Ignore() implements Reaction {}
    }

    /// The connection's next stage, and what it does now.
    record Step(Stage next, Reaction reaction) {}

    /// Decides what one message means for a connection. Pure: everything it needs is passed in.
    ///
    /// @param waiting how many calls of this connection are waiting for the gate now
    static Step decide(Stage stage, Incoming message, LampConfig.HostAccess access, int waiting) {
        return switch (message) {
            case Incoming.Unreadable bad -> new Step(stage, new Reaction.Send(error(bad.id(), bad.code(), bad.message())));
            case Incoming.Response _ -> new Step(stage, new Reaction.Ignore());
            case Incoming.Notification notification -> new Step(stage, switch (notification.method()) {
                case "notifications/cancelled" -> notification.params().has("requestId")
                        ? new Reaction.Cancel(notification.params().get("requestId")) : new Reaction.Ignore();
                default -> new Reaction.Ignore();
            });
            case Incoming.Request request -> decideRequest(stage, request, access, waiting);
        };
    }

    private static Step decideRequest(Stage stage, Incoming.Request request, LampConfig.HostAccess access, int waiting) {
        JsonNode id = request.id();
        if (request.method().equals("initialize")) {
            String version = agreedVersion(request.params().path("protocolVersion").asText(""));
            return new Step(new Stage.Initialized(version), new Reaction.Send(result(id, initialized(version))));
        }
        if (request.method().equals("ping")) return new Step(stage, new Reaction.Send(result(id, JsonUtil.object())));
        if (stage instanceof Stage.Fresh)
            return new Step(stage, new Reaction.Send(error(id, INVALID_REQUEST, "the client has not sent initialize yet")));
        return new Step(stage, switch (request.method()) {
            case "tools/list" -> new Reaction.Send(result(id, toolList(access)));
            case "tools/call" -> decideCall(id, request.params(), waiting);
            default -> new Reaction.Send(error(id, METHOD_NOT_FOUND, "this server has no method " + request.method()));
        });
    }

    /// A `tools/call`, read into the request the gate judges. A tool there is not, or arguments
    /// that do not fit it, are protocol errors: no request was made, so there is nothing to
    /// judge. A tool this lamp keeps closed is a request, and the gate refuses it, with a log.
    private static Reaction decideCall(JsonNode id, JsonNode params, int waiting) {
        String name = params.path("name").asText("");
        Optional<HostToolKind> kind = HostToolKind.named(name);
        if (kind.isEmpty())
            return new Reaction.Refuse(id, INVALID_PARAMS, HostAccessProblems.noSuchTool(name).whatHappened(),
                                       name.isEmpty() ? "?" : name);
        JsonNode arguments = params.has("arguments") ? params.get("arguments") : JsonUtil.object();
        Result<HostTool> tool = HostTool.read(kind.get(), arguments);
        if (tool instanceof Result.Err<HostTool> bad)
            return new Reaction.Refuse(id, INVALID_PARAMS, bad.problems().first().whatHappened(), name);
        if (waiting >= CALLS_AT_ONCE)
            return new Reaction.Send(error(id, INVALID_REQUEST, "already " + CALLS_AT_ONCE
                    + " calls are waiting on this connection; wait for one to end"));
        return new Reaction.Perform(id, ((Result.Ok<HostTool>) tool).value());
    }

    private static String agreedVersion(String asked) {
        return VERSIONS.contains(asked) ? asked : VERSIONS.first();
    }

    // ─── what is written back ───────────────────────────────────────────────────────────────

    private static ObjectNode initialized(String version) {
        ObjectNode result = JsonUtil.object();
        result.put("protocolVersion", version);
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo").put("name", "oillamp").put("version", OilLamp.VERSION);
        result.put("instructions", "Tools that act on the user's own computer, outside the sandbox. "
                + "oillamp judges every call against the user's rules before it happens, and may refuse it.");
        return result;
    }

    /// The tools this lamp offers, each with the schema its arguments are held to.
    static ObjectNode toolList(LampConfig.HostAccess access) {
        ObjectNode result = JsonUtil.object();
        ArrayNode tools = result.putArray("tools");
        for (HostToolKind kind : HostTool.offeredBy(access)) {
            ObjectNode tool = tools.addObject();
            tool.put("name", kind.toolName);
            tool.put("description", kind.description);
            tool.set("inputSchema", schemaOf(kind));
            tool.set("annotations", annotationsOf(kind));
        }
        return result;
    }

    /// The JSON Schema of a tool's arguments. No argument beyond these is accepted, and the
    /// schema says so, so that a client can tell before it calls.
    static ObjectNode schemaOf(HostToolKind kind) {
        ObjectNode schema = JsonUtil.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (HostToolKind.Parameter parameter : kind.parameters) {
            ObjectNode property = properties.putObject(parameter.name());
            switch (parameter.type()) {
                case TEXT -> property.put("type", "string").put("minLength", 1);
                case TEXT_OR_EMPTY -> property.put("type", "string");
                case WHOLE_NUMBER -> property.put("type", "integer");
            }
            property.put("description", parameter.description());
            if (parameter.required()) required.add(parameter.name());
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    /// MCP's hints about a tool, for a client that wants to show them. They are descriptions,
    /// not permissions: the gate decides whatever they say.
    private static ObjectNode annotationsOf(HostToolKind kind) {
        ObjectNode hints = JsonUtil.object();
        hints.put("title", kind.summary);
        // A copy in changes nothing on the host, but it writes a file in the agent's home, and
        // may replace one there, so it is neither read-only nor harmless to repeat.
        hints.put("readOnlyHint", kind.tier == LampHostRisk.PASSIVE && kind != HostToolKind.COPY_IN);
        hints.put("destructiveHint", switch (kind) {
            case WRITE_FILE, EDIT_FILE, COPY_OUT, COPY_IN, CLIPBOARD_WRITE, BASH -> true;
            case LIST_DIR, STAT, READ_FILE, CLIPBOARD_READ, SHOW, OPEN_URL, NOTIFY -> false;
        });
        hints.put("openWorldHint", switch (kind) {
            case OPEN_URL, BASH -> true;
            case LIST_DIR, STAT, READ_FILE, CLIPBOARD_READ, SHOW, WRITE_FILE, EDIT_FILE, COPY_OUT,
                 COPY_IN, CLIPBOARD_WRITE, NOTIFY -> false;
        });
        return hints;
    }

    /// A tool's result, as MCP has it: text, and `isError` for anything that was not done.
    static ObjectNode callResult(HostAccess.Answer answer) {
        ObjectNode result = JsonUtil.object();
        String text = switch (answer) {
            case HostAccess.Answer.Done done -> done.text();
            case HostAccess.Answer.Refused refused -> refused.why();
            case HostAccess.Answer.Failed failed -> failed.why();
        };
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", !(answer instanceof HostAccess.Answer.Done));
        return result;
    }

    static String result(JsonNode id, JsonNode result) {
        ObjectNode message = JsonUtil.object();
        message.put("jsonrpc", "2.0");
        message.set("id", id);
        message.set("result", result);
        return message.toString();
    }

    static String error(JsonNode id, int code, String text) {
        ObjectNode message = JsonUtil.object();
        message.put("jsonrpc", "2.0");
        message.set("id", id);
        message.putObject("error").put("code", code).put("message", text);
        return message.toString();
    }

    // ─── the socket ─────────────────────────────────────────────────────────────────────────

    /// The listening socket and its connections, owned by the session while host access is on.
    ///
    /// Each connection is read on a thread of its own, and each call it makes is judged on
    /// another, so that a call waiting for a person does not stop the connection from reading a
    /// cancellation, or a second call. Closing the server closes every connection, and a
    /// connection that closes withdraws every call it was waiting for.
    static final class Server implements AutoCloseable {

        private final Path socket;
        private final ServerSocketChannel channel;
        private final Set<SocketChannel> connections = ConcurrentHashMap.newKeySet();
        private volatile boolean closing;

        private Server(Path socket, ServerSocketChannel channel) {
            this.socket = socket;
            this.channel = channel;
        }

        static Result<Server> open(Path socket, HostAccess gate, LampConfig.HostAccess access) {
            return Relay.bind(socket).map(channel -> {
                Server server = new Server(socket, channel);
                Thread.ofVirtual().name("oillamp-host-mcp").start(() -> server.acceptLoop(gate, access));
                return server;
            });
        }

        private void acceptLoop(HostAccess gate, LampConfig.HostAccess access) {
            while (channel.isOpen()) {
                SocketChannel client;
                try {
                    client = channel.accept();
                } catch (IOException e) {
                    if (closing) return;
                    continue;
                }
                connections.add(client);
                Thread.ofVirtual().name("oillamp-host-mcp-connection")
                      .start(() -> new Connection(client, gate, access).serve());
            }
        }

        private final class Connection {
            private final SocketChannel client;
            private final HostAccess gate;
            private final LampConfig.HostAccess access;
            private final Lines lines;
            /// The calls waiting for the gate, by their id as JSON text, each with what completes
            /// when it is withdrawn. A call that is no longer here when it ends was cancelled, and
            /// is not answered.
            private final Map<String, CompletableFuture<Void>> waiting = new ConcurrentHashMap<>();
            private final Object writing = new Object();

            Connection(SocketChannel client, HostAccess gate, LampConfig.HostAccess access) {
                this.client = client;
                this.gate = gate;
                this.access = access;
                this.lines = new Lines(client);
            }

            void serve() {
                Stage stage = new Stage.Fresh();
                try (client) {
                    while (true) {
                        Optional<Line> line = lines.next();
                        if (line.isEmpty()) break;
                        if (line.get() instanceof Line.Whole(String text) && text.isBlank()) continue;
                        Incoming message = switch (line.get()) {
                            case Line.Whole whole -> read(whole.text());
                            case Line.TooLong _ -> new Incoming.Unreadable(NullNode.instance, INVALID_REQUEST,
                                    "the message is longer than " + LONGEST_MESSAGE / (1024 * 1024) + " MiB");
                        };
                        Step step = decide(stage, message, access, waiting.size());
                        stage = step.next();
                        carryOut(step.reaction());
                    }
                } catch (IOException ended) {
                    // The client hung up, or the session closed the connection.
                } finally {
                    // Nobody is left to read an answer, so nothing waits on for one.
                    for (CompletableFuture<Void> withdrawn : waiting.values()) withdrawn.complete(null);
                    waiting.clear();
                    connections.remove(client);
                }
            }

            private void carryOut(Reaction reaction) {
                switch (reaction) {
                    case Reaction.Send send -> send(send.line());
                    case Reaction.Refuse refuse -> {
                        gate.refusedUnread(refuse.tool(), refuse.message());
                        send(error(refuse.id(), refuse.code(), refuse.message()));
                    }
                    case Reaction.Perform perform -> perform(perform.id(), perform.tool());
                    case Reaction.Cancel cancel -> {
                        CompletableFuture<Void> withdrawn = waiting.remove(cancel.id().toString());
                        if (withdrawn != null) withdrawn.complete(null);
                    }
                    case Reaction.Ignore _ -> { }
                }
            }

            private void perform(JsonNode id, HostTool tool) {
                String key = id.toString();
                if (waiting.containsKey(key)) {
                    send(error(id, INVALID_REQUEST, "a call with this id is already waiting"));
                    return;
                }
                CompletableFuture<Void> withdrawn = new CompletableFuture<>();
                waiting.put(key, withdrawn);
                Thread.ofVirtual().name("oillamp-host-mcp-call").start(() -> {
                    HostAccess.Answer answer = gate.answer(tool, withdrawn);
                    if (waiting.remove(key, withdrawn)) send(result(id, callResult(answer)));
                });
            }

            private void send(String line) {
                ByteBuffer bytes = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
                synchronized (writing) {
                    try {
                        while (bytes.hasRemaining()) client.write(bytes);
                    } catch (IOException gone) {
                        // The client hung up; its reading thread ends the connection.
                    }
                }
            }
        }

        @Override public void close() {
            closing = true;
            try {
                channel.close();
            } catch (IOException ignored) {
                // Already closed. The socket file is removed below either way.
            }
            for (SocketChannel connection : connections) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                    // Closed already, which is what was wanted.
                }
            }
            try {
                Files.deleteIfExists(socket);
            } catch (IOException ignored) {
                // The next session unlinks before it binds, so a leftover file is harmless.
            }
        }
    }

    // ─── reading lines ──────────────────────────────────────────────────────────────────────

    /// One line from the client: a whole message, or one longer than [#LONGEST_MESSAGE], which is
    /// skipped up to its end.
    sealed interface Line {
        record Whole(String text) implements Line {}
        record TooLong() implements Line {}
    }

    /// The lines a connection sends, read as bytes and decoded once each, so that a character
    /// split across two reads arrives whole.
    private static final class Lines {
        private final SocketChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocate(64 * 1024).flip();

        Lines(SocketChannel channel) { this.channel = channel; }

        /// The next line, or empty at the end of the stream.
        Optional<Line> next() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean tooLong = false;
            while (true) {
                if (!buffer.hasRemaining()) {
                    buffer.clear();
                    int read = channel.read(buffer);
                    buffer.flip();
                    if (read < 0) return line.size() == 0 && !tooLong ? Optional.empty() : Optional.of(ended(line, tooLong));
                }
                byte next = buffer.get();
                if (next == '\n') return Optional.of(ended(line, tooLong));
                if (tooLong) continue;
                line.write(next);
                if (line.size() > LONGEST_MESSAGE) {
                    tooLong = true;
                    line.reset();
                }
            }
        }

        private static Line ended(ByteArrayOutputStream line, boolean tooLong) {
            return tooLong ? new Line.TooLong() : new Line.Whole(line.toString(StandardCharsets.UTF_8));
        }
    }
}
