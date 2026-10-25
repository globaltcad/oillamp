package oillamp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The sandbox's side of host access, as pi speaks it: MCP, one JSON-RPC message per line, on one
 * connection to the session's {@code host-access.sock}. In a real sandbox, pi writes these lines
 * to {@code socat}, which copies them to the socket; here they go to the socket directly, which
 * is the same thing to the engine.
 *
 * <p>Answers are read on a thread of their own and kept by their id, because a call that waits
 * for a person may be answered after one sent later.
 */
class McpClient implements AutoCloseable {

    private final SocketChannel channel
    private final Map<Object, Map> answers = new ConcurrentHashMap<>()
    private int nextId = 1
    private volatile boolean ended

    private McpClient(SocketChannel channel) {
        this.channel = channel
        Thread.startDaemon('mcp-client-reader') {
            var lines = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))
            try {
                String line
                while ((line = lines.readLine()) != null) {
                    var message = new JsonSlurper().parseText(line) as Map
                    answers.put(message.id ?: 'no id', message)
                }
            } catch (IOException closed) {
                // The connection ended; anything still awaited times out.
            }
            ended = true
        }
    }

    /** Connects to the socket, as socat does when pi starts it. */
    static McpClient connect(Path socket) {
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(socket))
        new McpClient(channel)
    }

    /** Connects and introduces itself as pi does: {@code initialize}, then {@code notifications/initialized}. */
    static McpClient initialized(Path socket) {
        var client = connect(socket)
        client.request('initialize', [protocolVersion: '2025-06-18', capabilities: [:],
                                      clientInfo: [name: 'pi', version: '1.4.0']])
        client.notify('notifications/initialized', [:])
        client
    }

    /** Sends a request and waits for its answer, the whole JSON-RPC message. */
    Map request(String method, Map params) {
        awaitAnswer(send(method, params))
    }

    /** Sends a request, and returns its id without waiting. */
    synchronized int send(String method, Map params) {
        int id = nextId++
        sendLine(JsonOutput.toJson([jsonrpc: '2.0', id: id, method: method, params: params]))
        id
    }

    void notify(String method, Map params) {
        sendLine(JsonOutput.toJson([jsonrpc: '2.0', method: method, params: params]))
    }

    /** One line as it is, for a scenario about what the server makes of a line that is not a message. */
    synchronized void sendLine(String line) {
        var bytes = ByteBuffer.wrap((line + '\n').getBytes(StandardCharsets.UTF_8))
        while (bytes.hasRemaining()) channel.write(bytes)
    }

    /** The answer to the request with this id, or to a line that had none. */
    Map awaitAnswer(Object id, long seconds = 30) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < until) {
            var answer = answers.remove(id)
            if (answer != null) return answer
            Thread.sleep(10)
        }
        throw new AssertionError("no answer to request ${id} within ${seconds} seconds")
    }

    /** Whether the server closed the connection, within ten seconds. */
    boolean closedByTheServer() {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!ended && System.nanoTime() < until) Thread.sleep(10)
        ended
    }

    /** Whether an answer to this id has come, without waiting. */
    boolean answered(Object id) { answers.containsKey(id) }

    /** A {@code tools/call}, and the tool's result: its {@code content} and {@code isError}. */
    Map call(String tool, Map arguments, Map meta = [:]) {
        var params = [name: tool, arguments: arguments] + (meta ? [_meta: meta] : [:])
        var answer = request('tools/call', params)
        assert answer.result != null: "tools/call ${tool} was answered with ${answer}"
        answer.result as Map
    }

    /** The text of a tool's result. */
    static String text(Map result) {
        (result.content as List<Map>).collect { it.text }.join('\n')
    }

    @Override void close() { channel.close() }
}
