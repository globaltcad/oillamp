package oillamp

import dev.lamp.Lamp
import groovy.json.JsonSlurper
import spock.lang.Narrative
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Subject
import spock.lang.Tag
import spock.lang.Title

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

import static oillamp.RealLamps.*

@Title('Using a model on this machine')
@Narrative('''
A lamp whose model runs on this machine, with Ollama, as a program built on oillamp sets it up
through `Lamp`: the service with the path of its API, and a key the server ignores.

This is a spike, and its scenarios run in order on one real session. It needs Ollama listening on
`127.0.0.1:11434`, with the model `OILLAMP_SPIKE_LOCAL_MODEL` pulled, by default `qwen2.5:0.5b`
(about 400 MB: `ollama pull qwen2.5:0.5b`). Without it, the spike is skipped. Nothing here uses
Eden AI.
''')
@Subject(Lamp)
@Tag('spike')
@Stepwise
@Requires({ Spike.containerNetworkWorks() && Using_A_Model_On_This_Machine_Spec.ollamaHas(Using_A_Model_On_This_Machine_Spec.MODEL) })
class Using_A_Model_On_This_Machine_Spec extends Specification {

    static final String MODEL = System.getenv('OILLAMP_SPIKE_LOCAL_MODEL') ?: 'qwen2.5:0.5b'
    static final String OLLAMA = 'http://127.0.0.1:11434'

    @Shared Path directory = newLampPath('local-model')
    @Shared Lamp lamp

    def cleanupSpec() {
        lamp?.close()
        remove(directory)
    }

    def 'A lamp pointed at Ollama starts, and its harness is not told to keep only EU models'() {
        reportInfo """
            Ollama answers OpenAI-style requests under `/v1` on this machine's loopback. The lamp
            is given `http://127.0.0.1:11434/v1` and any key. In the sandbox, the harness's model
            address, `EDENAI_BASE_URL`, stays that of oillamp's model relay, which sends each
            request on from the host. But `EDENAI_EU_ONLY` is not set: Ollama's model list names no
            regions, and filtering it would leave nothing.
        """
        when: 'a program starts the lamp with Ollama\'s address and the key `local`'
            lamp = Lamp.at(directory).modelService(URI.create(OLLAMA + '/v1')).modelKey('local').start()

        then: 'the session is running'
            lamp.awaitRunning(FIRST_START)

        and: 'in the sandbox, `EDENAI_BASE_URL` is the model relay, and `EDENAI_EU_ONLY` is not set'
            inSandbox(lamp, 'sh', '-c', 'echo "[$EDENAI_BASE_URL] [${EDENAI_EU_ONLY:-unset}]"').out.strip() ==
                    '[http://127.0.0.1:3129/v3] [unset]'
    }

    def 'Through oillamp\'s model relay, the sandbox sees Ollama\'s models and gets answers'() {
        reportInfo """
            The sandbox asks oillamp's model relay, at `127.0.0.1:3129`, under `/v3`, as it always
            does, and the relay asks Ollama under `/v1`. The model list is Ollama's own, and a chat
            request is answered by the model on this machine. This scenario uses the session the
            spike started first.
        """
        when: '`curl` in the sandbox asks for the model list, and sends a chat request'
            var models = inSandbox(lamp, 'curl', '-s', '--max-time', '30', 'http://127.0.0.1:3129/v3/models')
            var answer = inSandbox(lamp, 'curl', '-s', '--max-time', '120',
                    '-H', 'Content-Type: application/json',
                    '-d', '{"model":"' + MODEL + '","max_tokens":5,"messages":[{"role":"user","content":"Reply with the word ok"}]}',
                    'http://127.0.0.1:3129/v3/chat/completions')

        then: 'the list names the model, and the chat request got an answer'
            new JsonSlurper().parseText(models.out).data*.id.contains(MODEL)
            new JsonSlurper().parseText(answer.out).choices[0].message.content instanceof String
    }

    def 'The harness pi uses the model on this machine'() {
        reportInfo """
            The harness itself, as an agent runs it: pi's Eden AI extension reads the model list
            through oillamp's model relay, keeps all of it because `EDENAI_EU_ONLY` is not set, and
            the local model answers. This scenario uses the session the spike started first.
        """
        when: 'pi in the sandbox is asked to reply with one word, on the local model'
            var ran = inSandbox(lamp, 'pi', '--provider', 'edenai', '--model', MODEL, '--no-session',
                                '-p', 'Reply with the single word ok.')

        then: 'it succeeds, and prints an answer'
            ran.ok
            !ran.out.isBlank()
    }

    static boolean ollamaHas(String model) {
        try {
            var response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(OLLAMA + '/v1/models')).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString())
            (new JsonSlurper().parseText(response.body()).data ?: [])*.id.contains(model)
        } catch (Exception notThere) {
            false
        }
    }
}
