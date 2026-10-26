package oillamp

import com.sun.net.httpserver.HttpServer
import dev.lamp.Lamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Title

import java.nio.charset.StandardCharsets

@Title('Listing the models a model service offers')
@Narrative('''
An application, a program built on oillamp's Java API, may ask before any lamp runs which models
a model service will offer the agent, so that its settings page can list them rather than a list
written into the application. It calls `Lamp.models(service)`, or `Lamp.models(service, key)`.

`Lamp.models` asks from this machine, where the engine's model relay would ask for the sandbox,
at the same address and with the key the relay would send. That the relay asks the same place is
shown in `ChoosingTheModelForALampSpec`.

## How the scenarios see it

A stand-in model server on loopback answers the way Ollama does, with an OpenAI-style list of
models, and records which path it was asked for and with which key.
''')
class ListingAServicesModelsSpec extends Specification {

    HttpServer server
    String answer = '{"object":"list","data":[{"id":"qwen2.5:7b","object":"model","owned_by":"library"},' +
                    '{"id":"llama3.2:3b","object":"model","owned_by":"library"}]}'
    int status = 200
    final List<String> asked = []
    final List<String> keys = []

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            asked << exchange.requestURI.toString()
            keys << exchange.requestHeaders.getFirst('Authorization')
            byte[] body = answer.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
    }

    def cleanup() { server?.stop(0) }

    def 'Oillamp asks a service with a path under that path, and gives back its models in its order'() {
        reportInfo """
            Let's consider an application that lets the user pick a model from a server on their
            machine, such as Ollama at `http://127.0.0.1:11434/v1`. The address names where the
            server's OpenAI-style API is, so `Lamp.models` asks for the list at `/v1/models`. The
            ids come back in the order the server gave them, and are exactly what the agent names
            its model with.
        """
        when: 'the application asks a service at /v1/ for its models'
            var models = Lamp.models(address('/v1/'))

        then: 'the server was asked at /v1/models, and its two models come back in its order'
            models == ['qwen2.5:7b', 'llama3.2:3b']
            asked == ['/v1/models']
    }

    def 'Oillamp asks a service without a path under /v3, as Eden AI is asked'() {
        reportInfo """
            Inside the sandbox, the model service is always reached under `/v3`, Eden AI's path.
            The engine's relay passes a request for a service given without a path of its own on
            unchanged, so its list is at `/v3/models`, and that is where `Lamp.models` asks too.
        """
        when: 'the application asks a service given without a path'
            var models = Lamp.models(address(''))

        then: 'the server was asked at /v3/models'
            models == ['qwen2.5:7b', 'llama3.2:3b']
            asked == ['/v3/models']
    }

    def 'Oillamp sends the key with the request, as the relay sends it, and none without one'() {
        reportInfo """
            A model server elsewhere, such as Ollama behind a proxy that checks a key, lists its
            models only for that key. `Lamp.models(service, key)` sends it the way the relay sends
            it with every model request: as a bearer token, with the spaces around it trimmed.
            `Lamp.models(service)` sends no key, for a server on this machine or a public list.
        """
        when: 'the application asks once with a key and once without'
            Lamp.models(address('/v1'), '  sk-for-the-server  ')
            Lamp.models(address('/v1'))

        then: 'the first request carried the key as a bearer token, the second no key at all'
            keys == ['Bearer sk-for-the-server', null]
    }

    def 'Oillamp asks only a service the engine would accept, since the key travels to it'() {
        reportInfo """
            The engine refuses a model service over plain `http` anywhere but on this machine,
            because the key would cross the network readable by anyone on the way. Asking such a
            service for its list would send the key the same way, so `Lamp.models` throws
            `IllegalArgumentException` before anything is sent.
        """
        when: 'the application asks a service on another machine over plain http, with a key'
            Lamp.models(URI.create('http://192.168.1.20:11434/v1'), 'sk-for-the-server')

        then: 'it is refused, saying the service must be https'
            var refused = thrown(IllegalArgumentException)
            refused.message.contains('must be https://')
    }

    def 'When the service cannot be asked, oillamp says what to check'() {
        reportInfo """
            The usual mistakes when someone sets up a model server: the address lacks the API's
            path, so the server answers 404; the key is wrong, so it answers 401; or the server is
            not running. `Lamp.models` throws `IOException` for each, with a sentence an
            application can show next to its settings.
        """
        when: 'the server answers 404 to an address without a path'
            status = 404
            Lamp.models(address(''))

        then: 'the message says the address may need its API path, such as …/v1'
            var wrongPath = thrown(IOException)
            wrongPath.message.contains('answered 404')
            wrongPath.message.contains('…/v1')

        when: 'the server answers 401 to a key'
            status = 401
            Lamp.models(address('/v1'), 'sk-wrong')

        then: 'the message says the server refused the key'
            var wrongKey = thrown(IOException)
            wrongKey.message.contains('refused the key')

        when: 'the server is not running'
            server.stop(0)
            Lamp.models(address('/v1'))

        then: 'the message asks whether it is running'
            var notRunning = thrown(IOException)
            notRunning.message.contains('Is the model server running?')
    }

    def 'Oillamp offers no models from an answer that is not an OpenAI-style model list'() {
        reportInfo """
            Some other program may answer at the address, such as a web page. What it says is not
            taken for model names: `Lamp.models` gives back an empty list.
        """
        when: 'a web page answers at the address'
            answer = '<html>not a model server</html>'
            var models = Lamp.models(address('/v1'))

        then: 'there are no models'
            models.isEmpty()
    }

    def 'Oillamp keeps every model of a service other than Eden AI, whatever regions its list names'() {
        reportInfo """
            Eden AI's list says where each model is served, and the harness in the sandbox keeps
            only the ones served in the EU. That filter is for Eden AI alone. Another service's
            list is kept whole, also when it happens to name regions, here one model in the US and
            one with none.
        """
        when: 'a service that is not Eden AI lists a model served in the US, one in the EU, and one with no region'
            answer = '''{"data":[
                {"id":"mistral/mistral-small-latest","regions":[{"code":"eu"}]},
                {"id":"openai/gpt-far-away","regions":[{"code":"us"}]},
                {"id":"somewhere/unknown"}]}'''
            var models = Lamp.models(address('/v1'))

        then: 'all three are offered'
            models == ['mistral/mistral-small-latest', 'openai/gpt-far-away', 'somewhere/unknown']
    }

    private URI address(String path) { URI.create("http://127.0.0.1:${server.address.port}${path}") }
}
