package gui

import dev.gui.genie.GenieRunner
import dev.gui.genie.LampLighter
import dev.gui.genie.Lighter
import dev.gui.model.Entry
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.Settings
import dev.gui.model.Transcript
import dev.gui.pi.PiEvent
import dev.lamp.Lamp
import dev.lamp.LampEvent
import groovy.json.JsonSlurper
import oillamp.ScenarioHost
import sprouts.Tuple
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.UnaryOperator

/**
 *  The genies' model: the one in Genies' settings, for every genie and every conversation.
 *
 *  <p>The settings hold one model for all genies, and nothing else decides. Genies gives it to
 *  each genie's lamp, which has every answer written by it, in an old conversation as in a new
 *  one. Changed while a genie is awake, it applies to the genie's next message, and the genie's
 *  lamp stays lit, so whatever runs on its desktop goes on. Which model wrote an answer is shown
 *  only when the user points at the answer.
 *
 *  <p>The lamp is oillamp's real engine, in this JVM, on a simulated machine with a stand-in pi
 *  that keeps track of its model as pi does, and writes into each answer which model gave it.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ChoosingTheGeniesModelSpec extends Specification {

    static final String GPT = 'azure/gpt-6.1-sol'
    static final String DEEPSEEK = 'tensorx/deepseek/deepseek-v4.1-flash'

    @TempDir Path tmp
    ScenarioHost host
    Path lamp
    Genie genie = Genie.named('Jafar')
    final List<String> closed = new CopyOnWriteArrayList<>()
    GenieRunner runner

    def setup() {
        host = new ScenarioHost(tmp)
        lamp = host.lampPath('jafar')
        host.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80)).agent { String prompt -> 'You said: ' + prompt } }
        runner = new GenieRunner(lamp, lighter(), { UnaryOperator<Genie> change ->
            synchronized (this) { genie = change.apply(genie) }
        } as Consumer)
    }

    def cleanup() {
        runner?.sleepAndWait(30)
    }

    def 'The model in the settings reaches the genie\'s lamp, wherever the model runs: #where'() {
        reportInfo """
            Whichever way the user reaches a model, the model the settings name is the one the
            genie's lamp is given, as the model server names it, since that is how pi names it
            too: the Eden AI name, the name a server lists, or Genies' own version of an Ollama
            model, which it calls genies/ and the model.
        """
        when:
            runner.wake('Jafar', settings, 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }

        then:
            var arguments = host.engines.first().arguments
            arguments[arguments.indexOf('--model') + 1] == model

        and: 'pi starts a new conversation on it, too'
            piSettings().defaultModel == model

        where:
            where                     | settings                                                                                      || model
            'Eden AI'                 | Settings.defaults().withModel(GPT)                                                            || GPT
            'a server elsewhere'      | Settings.defaults().withPlace(Settings.Place.ELSEWHERE)
                                                .withElsewhere(new Settings.Elsewhere('https://llm.example.com/v1', '', 'llama3.3:70b')) || 'llama3.3:70b'
            'a server on this machine'| Settings.defaults().withPlace(Settings.Place.THIS_MACHINE)
                                                .withLocal(new Settings.OnThisMachine('http://127.0.0.1:1234/v1', 'qwen2.5-7b-instruct'))  || 'qwen2.5-7b-instruct'
            'Ollama, the simple way'  | Settings.defaults().withPlace(Settings.Place.THIS_MACHINE)
                                                .withLocal(new Settings.OnThisMachine(Settings.OLLAMA, 'genies/qwen3:8b'))                || 'genies/qwen3:8b'
    }

    def 'A model changed while the genie is awake writes its next answer, in the same conversation, and the lamp stays lit'() {
        reportInfo """
            The user picks another model in the settings while a genie is awake, and goes on
            chatting where they were. The next answer comes from the new model, though the
            conversation began on the old one. The genie's lamp is not put out for it: whatever
            the genie had open on its desktop is still there.
        """
        given: 'a genie that answered on DeepSeek'
            runner.wake('Jafar', Settings.defaults().withModel(DEEPSEEK), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY && answers().size() == 1 }

        when: 'the settings change to GPT, as leaving the settings page applies them'
            runner.useModel(Settings.defaults().withModel(GPT), 'sk-key')
            say('and again')
            waitUntil { genie.phase() == Genie.Phase.READY && answers().size() == 2 }

        then: 'GPT wrote the new answer in the same conversation, and pointing at each answer says so'
            Lamp.conversations(lamp).size() == 1
            answers()*.writtenBy() == ["Written by ${DEEPSEEK}", "Written by ${GPT}"]*.toString()

        and: 'the lamp stayed lit, and pi starts new conversations on GPT from now on'
            closed.isEmpty()
            runner.isAwake()
            piSettings().defaultModel == GPT
    }

    def 'Pointing at an answer of a conversation read back from the lamp says which model wrote it'() {
        reportInfo """
            The chat is drawn again from the conversation the lamp keeps, when the genie wakes
            or the user opens a conversation. pi recorded the model with each answer, so each
            answer still says which model wrote it, also in a conversation that changed model.
            The user's own messages say nothing.
        """
        given: 'a conversation answered by DeepSeek, then by GPT'
            runner.wake('Jafar', Settings.defaults().withModel(DEEPSEEK), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY && answers().size() == 1 }
            runner.useModel(Settings.defaults().withModel(GPT), 'sk-key')
            say('and again')
            waitUntil { genie.phase() == Genie.Phase.READY && answers().size() == 2 }

        when: 'the genie sleeps and wakes, and the chat is read back from the lamp'
            runner.sleep()
            waitUntil { genie.phase() == Genie.Phase.ASLEEP }
            synchronized (this) { genie = genie.withTranscript(Transcript.empty()) }
            runner.wake('Jafar', Settings.defaults().withModel(GPT), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY && answers().size() == 2 }

        then:
            answers()*.writtenBy() == ["Written by ${DEEPSEEK}", "Written by ${GPT}"]*.toString()
            genie.transcript().entries().findAll { it.kind() == Entry.Kind.YOU }*.writtenBy() == ['', '']
    }

    def 'A model from another place, or reached with another key, waits until the genie wakes again'() {
        reportInfo """
            An awake genie's lamp sends every request to the service it was lit with, with the
            key it was lit with. A model from another place, such as an Ollama model after
            switching away from Eden AI, is not known there: handed over at once, it would make
            the next message fail with a confusing error. So the genie keeps answering with its
            old model, and takes all the new settings together when it next wakes, as the
            settings say.
        """
        given: 'a genie awake on Eden AI\'s GPT'
            runner.wake('Jafar', Settings.defaults().withModel(GPT), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }

        when: 'the settings change to Ollama on this computer, and then to another Eden AI key'
            runner.useModel(Settings.defaults().withPlace(Settings.Place.THIS_MACHINE)
                    .withLocal(new Settings.OnThisMachine(Settings.OLLAMA, 'genies/qwen3:8b')), 'no-key-needed')
            runner.useModel(Settings.defaults().withModel(DEEPSEEK), 'sk-another-key')
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY && answers().size() == 1 }

        then: 'GPT still answers, and nothing was changed on the lamp or in pi\'s settings'
            answers()*.writtenBy() == ["Written by ${GPT}".toString()]
            !host.engines.any { it.arguments.first() == 'model' }
            piSettings().defaultModel == GPT

        when: 'the genie sleeps and wakes with the new settings'
            runner.sleep()
            waitUntil { genie.phase() == Genie.Phase.ASLEEP }
            runner.wake('Jafar', Settings.defaults().withPlace(Settings.Place.THIS_MACHINE)
                    .withLocal(new Settings.OnThisMachine(Settings.OLLAMA, 'genies/qwen3:8b')), 'no-key-needed')
            waitUntil { genie.phase() == Genie.Phase.READY }

        then: 'its lamp is lit with all of them'
            var arguments = host.engines.findAll { it.arguments.first() == 'at' }.last().arguments
            arguments[arguments.indexOf('--model') + 1] == 'genies/qwen3:8b'
            arguments[arguments.indexOf('--model-service') + 1] == Settings.OLLAMA
    }

    def 'A genie that kept running while Genies was closed keeps its model until it wakes again'() {
        reportInfo """
            Genies finds a genie it left running when it opens again, and joins its lamp. Which
            service and key that lamp was lit with, Genies cannot know: the settings may have
            changed while it was closed. Handing the lamp a model that its service may not have
            could break the genie's next message, so the joined genie keeps the model it was lit
            with, and takes the settings' model when it next wakes.
        """
        given: 'a genie on DeepSeek, left running as Genies closed'
            runner.wake('Jafar', Settings.defaults().withModel(DEEPSEEK), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }
            runner.leaveRunningAndWait(30)

        when: 'Genies opens again with GPT in its settings, joins the genie, and the user asks something'
            genie = Genie.named('Jafar')
            runner = new GenieRunner(lamp, lighter(), { UnaryOperator<Genie> change ->
                synchronized (this) { genie = change.apply(genie) }
            } as Consumer)
            runner.rejoin('', '')
            runner.useModel(Settings.defaults().withModel(GPT), 'sk-key')
            waitUntil { genie.phase() == Genie.Phase.READY }
            say('hello')
            waitUntil { genie.phase() == Genie.Phase.READY && answers().size() == 1 }

        then:
            answers()*.writtenBy() == ["Written by ${DEEPSEEK}".toString()]
    }

    def 'When the model service refuses a request, the chat says which model it refused it for'() {
        reportInfo """
            A service may refuse a request for one model and take the same request for another,
            as Eden AI did for a DeepSeek model with a reasoning setting it stopped accepting.
            Naming the model tells the user what to change. Without a known model, the chat says
            what it said before.
        """
        given:
            var refusal = '400: {"message":"OpenAIException - Invalid request","type":"invalid_request_error","param":null,"code":"invalid_parameter"}'

        expect: 'as the answer fails'
            Transcript.empty().you('hi').hear(new PiEvent.Answered('', refusal, 0, DEEPSEEK)).entries().last().text() ==
                    "${DEEPSEEK} refused the request (400): OpenAIException - Invalid request.".toString()

        and: 'read back from the conversation'
            Transcript.empty().hear(new PiEvent.History(Tuple.of(PiEvent.History.Line,
                    new PiEvent.History.Line(true, 'hi', 'q1', false, false, ''),
                    new PiEvent.History.Line(false, refusal, 'a1', false, true, DEEPSEEK)), 'a1')).entries().last().text() ==
                    "${DEEPSEEK} refused the request (400): OpenAIException - Invalid request.".toString()

        and: 'with no model known'
            Transcript.empty().you('hi').hear(new PiEvent.Answered('', refusal, 0, '')).entries().last().text() ==
                    'The model service refused the request (400): OpenAIException - Invalid request.'
    }

    def 'While genies are awake, the settings say calmly what reaches them now and what later'() {
        reportInfo """
            A new model reaches awake genies at once. A new place, address or key needs the
            genie's lamp to be lit again, which would end what runs on its desktop, so Genies
            does not do that by itself; the settings say it happens when a genie next wakes.
            With every genie asleep there is nothing to say.
        """
        given:
            var asleep = Genie.named('Jafar')
            var awake = Genie.named('Iago').waking().awake()

        expect:
            GeniesState.of(Tuple.of(Genie, asleep), Settings.defaults(), Optional.empty()).noteForAwakeGenies().isEmpty()
            GeniesState.of(Tuple.of(Genie, asleep, awake), Settings.defaults(), Optional.empty()).noteForAwakeGenies() ==
                    Optional.of('Genies that are awake answer with a new model from their next message on. ' +
                                'A new place, address or key reaches them when they next wake, and so does ' +
                                'everything for a genie that kept running while Genies was closed.')
    }

    def 'A model name that no service could have is pointed out in the settings'() {
        reportInfo """
            A name with a space, or a character pasted along from a web page such as a
            non-breaking hyphen, would fail every message. The settings say so before a genie
            wakes with it.
        """
        expect:
            Settings.defaults().withModel(name).problem(Optional.of('sk-key')).orElse('').contains("A model's name has no spaces") == refused

        where:
            name                 || refused
            GPT                  || false
            'azure/gpt 6.1'      || true
            'azure/gpt‑6.1-sol'  || true
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private void say(String text) {
        synchronized (this) { genie = genie.withDraft(text).send() }
        runner.sendMessage(text)
    }

    private List<Entry> answers() {
        genie.transcript().entries().findAll { it.kind() == Entry.Kind.GENIE && !it.isWriting() }
    }

    private Path home() { Lamp.agentHome(lamp).orElseThrow() }

    private Map piSettings() {
        new JsonSlurper().parseText(Files.readString(home().resolve('.pi/agent/settings.json'))) as Map
    }

    /**
     *  oillamp's engine in this JVM, through the Lamp API, noting when the genie's lamp is put
     *  out. A command run in the sandbox runs here, in the genie's home: the simulated machine has
     *  no sandbox to run it in.
     */
    private Lighter lighter() {
        var real = new LampLighter(host.launcher)
        var light = { Path directory, Settings settings, String key, Consumer<String> progress, Consumer<LampEvent> events ->
            here(real.light(directory, settings, key, progress, events))
        }
        var join = { Path directory, Consumer<LampEvent> events -> real.join(directory, events).map { here(it) } }
        return [light: light, join: join, isLit: { Path directory -> real.isLit(directory) },
                unlit: { Path directory -> real.unlit(directory) }] as Lighter
    }

    /** `lit`, except that its commands run here, in the genie's home, and putting it out is noted. */
    private Lighter.Lit here(Lighter.Lit lit) {
        new Lighter.Lit() {
            Process exec(String... command) {
                var builder = new ProcessBuilder(command as List<String>)
                builder.environment().put('HOME', home().toString())
                builder.directory(home().toFile())
                builder.start()
            }
            dev.gui.desktop.Desktop desktop() { lit.desktop() }
            LampEvent.Run send(Lamp.Question question) { lit.send(question) }
            void cancel(String run) { lit.cancel(run) }
            void useModel(String model) { lit.useModel(model) }
            void close() { closed << 'closed'; lit.close() }
            void leaveRunning() { lit.leaveRunning() }
        }
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw new AssertionError("never happened; the genie is ${genie.phase()} (${genie.activity()}), " +
                                 "its chat ${genie.transcript().entries()*.text()}" as Object)
    }
}
