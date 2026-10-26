package oillamp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonGenerator
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A model service on loopback that plays oillamp's host-access checker, for the scenarios about
 * reaching the host.
 *
 * <p>It records the body of every request the checker sent, and answers each with the verdict
 * the scenario set, after thinking for as long as the scenario says. Nothing about it is
 * oillamp's: to the engine it is a model service at an address, like any other.
 */
class StandInChecker implements AutoCloseable {

    static final String ALLOW_PASSIVE = '{"decision":"allow","risk":"passive","reason":"The user asked about it."}'

    /** The body of each request the checker model received, in order. */
    final List<String> checked = new CopyOnWriteArrayList<>()
    /** What the stand-in answers next. */
    volatile String verdict = ALLOW_PASSIVE
    /** How long it takes before it answers. */
    volatile Duration thinking = Duration.ZERO
    /**
     * When set, the answer is sent in two chunks of HTTP's chunked encoding, split after the
     * first byte of the first occurrence of this character: in the middle of it, when it takes
     * more than one byte.
     */
    volatile String splitAt = null

    private final HttpServer server
    private static final JsonGenerator AS_WRITTEN = new JsonGenerator.Options().disableUnicodeEscaping().build()

    StandInChecker() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { HttpExchange exchange ->
            String body = new String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            checked << body
            Thread.sleep(thinking.toMillis())
            // Written as it is, not escaped as \\u00e9, as a model service sends a reason in French.
            String answer = '{"choices":[{"message":{"role":"assistant","content":' + AS_WRITTEN.toJson((String) verdict) + '}}]}'
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.set('Content-Type', 'application/json')
            if (splitAt == null) {
                exchange.sendResponseHeaders(200, bytes.length)
                exchange.responseBody.withCloseable { it.write(bytes) }
            } else {
                // A length of 0 makes the server send the body in chunks, one for each flush.
                int cut = answer.substring(0, answer.indexOf(splitAt)).getBytes(StandardCharsets.UTF_8).length + 1
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.withCloseable { out ->
                    out.write(bytes, 0, cut)
                    out.flush()
                    out.write(bytes, cut, bytes.length - cut)
                }
            }
        }
        // Each request is answered on a thread of its own; a single-threaded server would hold
        // the connection the client is still writing on.
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    /** Where the application tells the lamp its model service is. */
    URI address() { URI.create("http://127.0.0.1:${server.address.port}/v1") }

    /** What the checker was shown about the request: the JSON after the message's first line. */
    static Map judged(String body) {
        var request = new JsonSlurper().parseText(body) as Map
        String content = request.messages.find { it.role == 'user' }.content
        new JsonSlurper().parseText(content.substring(content.indexOf('{'))) as Map
    }

    /** The model a checker request asked for. */
    static String modelOf(String body) {
        (new JsonSlurper().parseText(body) as Map).model
    }

    @Override void close() {
        server.stop(0)
        server.executor.shutdownNow()
    }
}
