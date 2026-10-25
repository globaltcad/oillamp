package oillamp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
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

    private final HttpServer server

    StandInChecker() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { HttpExchange exchange ->
            String body = new String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            checked << body
            Thread.sleep(thinking.toMillis())
            String answer = '{"choices":[{"message":{"role":"assistant","content":' + JsonOutput.toJson((String) verdict) + '}}]}'
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.set('Content-Type', 'application/json')
            exchange.sendResponseHeaders(200, bytes.length)
            exchange.responseBody.withCloseable { it.write(bytes) }
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
