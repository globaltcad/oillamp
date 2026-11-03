package oillamp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.lamp.LampDirectory
import dev.lamp.LampExitStatus
import dev.lamp.Lamp
import dev.lamp.LampEvent
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Title('Choosing the model for a lamp')
@Narrative('''
A program built on oillamp runs lamps through its Java API, `dev.lamp`. Such a program, a chat app
for example, may have its own settings screen, on which its user types a model service and a key.
`LampDirectory.modelService` and `LampDirectory.modelKey` hand both to the engine, which adds the
key to the sandbox's model requests on the host, exactly as it does with a key from its
environment. The sandbox sees neither.

In these scenarios the engine runs in this JVM against a simulated machine, connected to the
`Lamp` by real pipes. A stand-in model service runs on this machine's loopback, and the scenarios
send requests into the model relay's socket, as the relay inside the sandbox does.
''')
@Subject([Lamp, LampDirectory])
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class Choosing_The_Model_For_A_Lamp_Spec extends Specification {

    static final String APP_KEY = 'sk-typed-into-the-app-0123456789'

    @TempDir Path tmp
    ScenarioHost host
    HttpServer service
    /** The Authorization header of each request the stand-in model service received. */
    final List<String> received = new CopyOnWriteArrayList<>()
    /** The path of each request the stand-in model service received. */
    final List<String> paths = new CopyOnWriteArrayList<>()
    final List<LampEvent> events = new CopyOnWriteArrayList<>()
    Lamp lamp

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
        service = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        service.createContext('/') { HttpExchange exchange ->
            received << exchange.requestHeaders.getFirst('Authorization')
            paths << exchange.requestURI.toString()
            byte[] body = '{"data":[]}'.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        service.start()
    }

    def cleanup() {
        lamp?.close()
        service?.stop(0)
    }

    def 'The sandbox\'s model requests go to the service and carry the key given to `LampDirectory.modelService` and `modelKey`'() {
        reportInfo """
            Let's consider a program built on oillamp, such as a chat app, with a settings screen
            on which its user typed a model service and a key. The program starts the lamp with
            both, through `LampDirectory.modelService` and `LampDirectory.modelKey`. The sandbox's
            model requests then go to that service with that key, not to the service in the lamp's
            `oillamp.toml` with the key from the environment. `oillamp.toml` is not changed: the
            choice is the program's, for this session.
        """
        given: 'the key `EDENAI_API_KEY` in the environment the program inherited, which must not be used'
            host.machine { it.environmentVariable('EDENAI_API_KEY', 'sk-from-the-environment') }

        when: 'the program starts the lamp with its own service and key'
            lamp = Lamp.at(host.lampPath()).onEvent { events << it }
                       .modelService(standInService())
                       .modelKey(APP_KEY)
                       .launchedBy(host.launcher).start()

        then: 'the session is running'
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the sandbox asks for the model catalog with `GET /v3/models`, holding only its placeholder key'
            ask('GET /v3/models HTTP/1.1\r\nHost: 127.0.0.1:3129\r\n'
              + 'Authorization: Bearer held-by-oillamp-on-the-host\r\n\r\n')

        then: 'the stand-in service received the request, with the program\'s key'
            received == ["Bearer ${APP_KEY}".toString()]

        and: '`oillamp.toml`, which oillamp wrote for the new lamp, holds neither the key nor the service'
            var written = Files.readString(host.lampPath().resolve('oillamp.toml'))
            !written.contains(APP_KEY)
            !written.contains(standInService().toString())
    }

    def '`Lamp` passes the key to the engine in its environment, never on its command line'() {
        reportInfo """
            Every user of a machine can read the command line of every process, but only the
            process's own user can read its environment. So the `Lamp` puts the key into the
            engine's environment, as `OILLAMP_MODEL_KEY`, and names only that variable on the
            command line, with `--model-key-env`. The service is not a secret, and goes on the
            command line with `--model-service`.
        """
        when: 'the program starts the lamp with a service and a key'
            lamp = Lamp.at(host.lampPath()).modelService(standInService()).modelKey(APP_KEY)
                       .launchedBy(host.launcher).start()
            var engine = host.engines.first()

        then: 'the command line names the service and the variable'
            engine.arguments == ['at', host.lampPath().toString(), '--embedded',
                                 '--model-service', standInService().toString(),
                                 '--model-key-env', 'OILLAMP_MODEL_KEY']

        and: 'but not the key'
            !engine.arguments.any { it.contains(APP_KEY) }

        and: 'the key is in the engine\'s environment'
            engine.environment == [OILLAMP_MODEL_KEY: APP_KEY]
    }

    def 'Without `modelService` or `modelKey`, the lamp\'s `[model]` settings and the environment decide'() {
        reportInfo """
            A program built on oillamp needs no model settings of its own. The engine inherits the
            program's environment, so `EDENAI_API_KEY` there is used, for the service the lamp's
            `[model]` settings name, Eden AI's EU endpoint by default. Nothing about the model goes
            on the engine's command line.
        """
        given: 'the key `EDENAI_API_KEY` in the environment, and a lamp whose `[model] service` is the stand-in service'
            host.machine { it.environmentVariable('EDENAI_API_KEY', 'sk-from-the-environment') }
            host.givenConfig(host.lampPath(), """
                schema_version = 1
                [model]
                service = "${standInService()}"
            """.stripIndent())

        when: 'the program starts the lamp without a service or a key, and the sandbox sends `GET /v3/models`'
            lamp = Lamp.at(host.lampPath()).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))
            ask('GET /v3/models HTTP/1.1\r\nHost: 127.0.0.1:3129\r\n\r\n')

        then: 'the engine\'s command line holds nothing about the model, and the service received the key from the environment'
            host.engines.first().arguments == ['at', host.lampPath().toString(), '--embedded']
            received == ['Bearer sk-from-the-environment']
    }

    def 'With `modelKey` alone, the lamp\'s configured service gets the program\'s key'() {
        reportInfo """
            The common case on a settings screen: the user pastes their own key, and leaves the
            service as it is. The sandbox's requests go to the service in the lamp's `[model]`
            settings, by default Eden AI's EU endpoint, with the program's key.
        """
        given: 'a lamp whose `[model] service` is the stand-in service'
            host.givenConfig(host.lampPath(), """
                schema_version = 1
                [model]
                service = "${standInService()}"
            """.stripIndent())

        when: 'the program starts the lamp with `modelKey` only, and the sandbox sends `GET /v3/models`'
            lamp = Lamp.at(host.lampPath()).modelKey(APP_KEY).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))
            ask('GET /v3/models HTTP/1.1\r\nHost: 127.0.0.1:3129\r\n\r\n')

        then: 'the service received the program\'s key'
            received == ["Bearer ${APP_KEY}".toString()]
    }

    def '`modelService` can point a lamp at a model server on this machine'() {
        reportInfo """
            Let's consider someone who runs their own models with Ollama, LM Studio or llama.cpp's
            server, and wants the agent in their lamp to use them. Such a server answers under
            `/v1` on this machine's loopback, needs no key, and names no regions for its models.
            The program gives the server's address, path included, to `modelService`, and any key
            to `modelKey`, which the server ignores. The sandbox's requests then go to that path,
            and the harness in the sandbox is not told to keep only EU models, which would leave
            none of the server's.
        """
        when: 'the program starts the lamp with the service `http://127.0.0.1:<port>/v1` and the key `local`'
            lamp = Lamp.at(host.lampPath())
                       .modelService(URI.create("http://127.0.0.1:${service.address.port}/v1"))
                       .modelKey('local')
                       .launchedBy(host.launcher).start()

        then: 'the session is running'
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the sandbox sends `GET /v3/models`'
            ask('GET /v3/models HTTP/1.1\r\nHost: 127.0.0.1:3129\r\n\r\n')

        then: 'the server was asked for `/v1/models`'
            paths == ['/v1/models']

        and: '`runtime.env` tells the sandbox not to filter the models by region'
            Files.readString(host.lampPath().resolve('.oillamp/session/runtime.env')).contains("OILLAMP_MODEL_EU_ONLY='0'")
    }

    def '`Lamp.models` asks the service for its models at the same path, with the same key, as the sandbox does'() {
        reportInfo """
            A program built on oillamp lists the models its user can choose from with
            `Lamp.models`, which asks the model service from the host, before any lamp runs. That
            list is only right if it is the one the agent gets when it sends `GET /v3/models` from
            the sandbox, which the engine's model relay on the host sends on to the service. So the
            scenario checks that both requests reach the service at the same path, with the same
            key, for a service address with a path of its own, `/v1`, and for one without.
        """
        given: 'a lamp started with the service and a key'
            URI given = URI.create("http://127.0.0.1:${service.address.port}${path}")
            lamp = Lamp.at(host.lampPath()).modelService(given).modelKey(APP_KEY)
                       .launchedBy(host.launcher).start()

        expect: 'the session is running'
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the sandbox sends `GET /v3/models`, and the program calls `Lamp.models` with the same service and key'
            ask('GET /v3/models HTTP/1.1\r\nHost: 127.0.0.1:3129\r\n\r\n')
            Lamp.models(given, APP_KEY)

        then: 'both requests reached the same path, with the program\'s key'
            paths.size() == 2
            paths[0] == paths[1]
            received == ["Bearer ${APP_KEY}".toString()] * 2

        where:
            path << ['/v1', '']
    }

    def 'The engine refuses a model service on another machine over plain http, and the `Lamp` reports why in a `Failure` event'() {
        reportInfo """
            The key goes with every model request. With a service reached over plain http anywhere
            but on this machine's loopback, anyone on the network in between could read it. So the
            engine refuses such a service before anything starts, and exits with the status
            `USAGE`. The `Lamp` reports the refusal as a `Failure` event, which the program can
            show, in the words oillamp prints on a terminal: the service `must be https://`.
        """
        when: 'the program starts a lamp with the service `http://llm.example.com`'
            lamp = Lamp.at(host.lampPath()).onEvent { events << it }
                       .modelService(URI.create('http://llm.example.com'))
                       .modelKey(APP_KEY)
                       .launchedBy(host.launcher).start()

        then: 'the session never runs, the engine exited with `USAGE`, and a `Failure` event says the service must be https://'
            !lamp.awaitRunning(Duration.ofSeconds(30))
            lamp.exitStatus() == Optional.of(LampExitStatus.USAGE)
            events.any { it instanceof LampEvent.Failure && it.problem().whatHappened().contains('must be https://') }
    }

    def '`LampDirectory.modelKey` refuses a blank key at once, before an engine starts'() {
        reportInfo """
            An empty field on a settings screen would otherwise start a whole session in which
            every model request fails. `modelKey` throws `IllegalArgumentException` where the
            program calls it, so that the program can point at the field.
        """
        when: '`modelKey` is called with a key of two spaces'
            Lamp.at(host.lampPath()).modelKey('  ')

        then: 'it throws `IllegalArgumentException`, and no engine was started'
            thrown(IllegalArgumentException)
            host.engines.isEmpty()
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private URI standInService() { URI.create("http://127.0.0.1:${service.address.port}") }

    /** Sends one request into the relay's socket, as the sandbox does, and returns the answer. */
    private String ask(String request) {
        Path socket
        try (var lamps = Files.list(host.runtime.resolve('oillamp'))) {
            socket = lamps.findFirst().orElseThrow().resolve('sockets/host/model.sock')
        }
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(socket))
        channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)))
        var answer = new String(Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8)
        channel.close()
        answer
    }
}
