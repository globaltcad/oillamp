package oillamp

import dev.lamp.Lamp
import dev.lamp.Lamp.Question
import dev.lamp.LampEvent
import dev.lamp.LampEvent.RunOutcome
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 *  Choosing the model the agent answers with: {@code oillamp at --model} and
 *  {@code Lamp.Starting.model}.
 *
 *  <p>pi writes into each conversation's file which model it was on, and goes back to that model
 *  whenever the conversation is opened again. So a conversation started months ago stays on the
 *  model it started with, even after the user chose another one, and even when the service no
 *  longer offers the old one. A session given a model has every run answered by it instead: new
 *  conversations, old ones, questions and scheduled jobs alike. The earlier answers keep the model
 *  that gave them.
 *
 *  <p>pi is told to switch only when it is on another model. Telling it is not free: every time,
 *  it writes a {@code model_change} entry into the conversation's file, and sets the thinking level
 *  again from its settings.
 *
 *  <p>The stand-in pi here keeps track of its model the way pi does: a new conversation starts on
 *  the default model in pi's settings in the agent's home, an opened one goes back to the model it
 *  was last on, and every answer in the file names the model that gave it. The scenarios read the
 *  conversation's file to see which model answered and how often pi was told to switch.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ChoosingTheAgentsModelSpec extends Specification {

    static final String GPT = 'azure/gpt-6.1-sol'
    static final String DEEPSEEK = 'tensorx/deepseek/deepseek-v4.1-flash'

    /** A Tuesday afternoon: 16:15 in Berlin. */
    static final Instant NOW = Instant.parse('2026-09-22T14:15:03Z')

    @TempDir Path tmp
    @Subject ScenarioHost host

    Path lamp
    final List<LampEvent> reported = new CopyOnWriteArrayList<>()
    final List<String> prompts = new CopyOnWriteArrayList<>()
    Thread session

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen').clockAt(NOW).timeZone('Europe/Berlin') }
        lamp = host.lampPath()
    }

    def cleanup() {
        stopTheSession()
    }

    def 'A new conversation is answered by the model the session was given'() {
        reportInfo """
            pi's settings in the agent's home name a default model, which is what a new
            conversation starts on. A session given another model has the question answered by
            that one, not by the default.
        """
        given: 'a lamp whose pi would start on DeepSeek'
            aLampWhosePiDefaultsTo(DEEPSEEK)

        when: 'a session given GPT is asked a question'
            startASession('--model', GPT)
            var asked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')

        then: 'GPT answered it'
            asked.succeeded()
            var conversation = conversationOf(asked)
            answeredBy(conversation) == [GPT]

        and: 'pi was told once, at the start, after it had written down its default'
            modelsRecorded(conversation) == [DEEPSEEK, GPT]
    }

    def 'An old conversation that was on another model is answered by the session\'s model from then on'() {
        reportInfo """
            This is what happened to a user whose model service stopped offering the model their
            old chat had started on: every question in that chat failed, although the
            application's settings named a model that worked. With the model given to the
            session, the next answer in the old chat comes from that model. The earlier answers
            stay as they were, from the model that gave them.
        """
        given: 'a conversation that DeepSeek answered, in a session given no model'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            startASession()
            var first = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))
            stopTheSession()
            assert answeredBy(first) == [DEEPSEEK]

        when: 'it is continued in a session given GPT'
            startASession('--model', GPT)
            var asked = host.oillamp.run('ask', lamp.toString(), '--in', first, 'Name a fruit')

        then: 'GPT gave the new answer, and DeepSeek\'s stays'
            asked.succeeded()
            answeredBy(first) == [DEEPSEEK, GPT]

        and: 'pi was told to switch once'
            modelsRecorded(first) == [DEEPSEEK, GPT]
    }

    def 'A conversation already on the session\'s model is continued without telling pi anything'() {
        reportInfo """
            The usual case: the application wrote the same model into pi's settings as it gives
            the session. pi is then never told to switch, so the conversation's file holds no
            entries beyond the one pi writes itself when a conversation starts, and the thinking
            level the conversation was on stays as it was.
        """
        given:
            aLampWhosePiDefaultsTo(GPT)
            startASession('--model', GPT)

        when: 'a conversation is started and continued twice'
            var conversation = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a fruit').succeeded()
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a tree').succeeded()

        then:
            answeredBy(conversation) == [GPT, GPT, GPT]
            modelsRecorded(conversation) == [GPT]
    }

    def 'A conversation the session switched once is continued on that model without switching again'() {
        reportInfo """
            After the first answer from the session's model, the conversation is on it, so the
            questions after that need no switch. Each switch would add an entry to the file.
        """
        given: 'a conversation DeepSeek started'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            startASession('--model', GPT)
            var conversation = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))
            var recorded = modelsRecorded(conversation)

        when:
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a fruit').succeeded()

        then:
            answeredBy(conversation) == [GPT, GPT]
            modelsRecorded(conversation) == recorded
    }

    def 'Without a model, pi decides as before: its default for a new conversation, the conversation\'s own for an old one'() {
        reportInfo """
            A session started without --model leaves the model to pi, exactly as before there
            was such an option. A user who changes pi's default with pi's own /model command
            keeps every old conversation on the model it was on.
        """
        given: 'a conversation DeepSeek answered'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            startASession()
            var old = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))

        when: 'pi\'s default becomes GPT'
            aLampWhosePiDefaultsTo(GPT)
            assert host.oillamp.run('ask', lamp.toString(), '--in', old, 'Name a fruit').succeeded()
            var fresh = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a tree'))

        then: 'the old conversation stays on DeepSeek, and pi was never told to switch'
            answeredBy(old) == [DEEPSEEK, DEEPSEEK]
            modelsRecorded(old) == [DEEPSEEK]

        and: 'the new one is on the new default'
            answeredBy(fresh) == [GPT]
            modelsRecorded(fresh) == [GPT]
    }

    def 'A model the service does not offer fails the run, and the answer names the model'() {
        reportInfo """
            A model can disappear from a service, or be mistyped in an application's settings.
            Continuing silently on the conversation's old model would hide that, and might be
            the very model that fails. So the run fails before the question reaches the agent,
            and says which model could not be used and why. The conversation is left as it was,
            and the session goes on.
        """
        given:
            aLampWhosePiDefaultsTo(DEEPSEEK)
            host.machine { it.modelsOffered(GPT, DEEPSEEK) }
            startASession('--model', 'azure/gpt-0-retired')

        when:
            var asked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')

        then: 'the run failed, naming the model and pi\'s reason'
            !asked.succeeded()
            var finished = asked.events().find { it instanceof LampEvent.RunFinished }
            finished.outcome() == RunOutcome.FAILED
            finished.answer() == 'pi could not switch to the model azure/gpt-0-retired: Model not found: edenai/azure/gpt-0-retired'

        and: 'the agent never got the question, and the conversation has no trace of the model'
            prompts.isEmpty()
            var conversation = finished.conversation().orElseThrow()
            !modelsRecorded(conversation).contains('azure/gpt-0-retired')

        and: 'the session is still up'
            session.alive
    }

    def 'A job on the schedule is answered by the session\'s model too'() {
        reportInfo """
            Jobs wake the agent through the same runs as questions do, so the model the session
            was given applies to them as well. A genie's weekly report comes from the model the
            user chose, not from whatever pi's settings said when the job was added.
        """
        given: 'a job due five minutes from now'
            aLampWhosePiDefaultsTo(DEEPSEEK, '[schedule]\nenabled = true\n')
            assert host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 5m', 'Write the weekly report').succeeded()

        when: 'a session given GPT runs ten minutes later'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession('--model', GPT)
            var finished = waitFor(LampEvent.RunFinished)

        then:
            finished.outcome() == RunOutcome.FINISHED
            answeredBy(finished.conversation().orElseThrow()) == [GPT]
    }

    def 'An application gives the model with Lamp.Starting.model, and its questions are answered by it'() {
        reportInfo """
            Genies has one model setting, in its own settings, for all its genies, and starts a
            genie's lamp each time the genie is woken. It hands the model to the lamp the way it
            hands over the model service and the key; the engine gets it on its command line,
            which is fine, since a model's name is no secret.
        """
        given:
            aLampWhosePiDefaultsTo(DEEPSEEK)

        when:
            var running = Lamp.at(lamp).model(GPT).launchedBy(host.launcher).start()
            assert running.awaitRunning(Duration.ofSeconds(30))
            var answer = running.ask(Question.fresh('Name a colour'))
            running.close()

        then: 'the engine was told the model'
            host.engines.first().arguments == ['at', lamp.toString(), '--embedded', '--model', GPT]

        and: 'it answered the question'
            answer.outcome() == RunOutcome.FINISHED
            answeredBy(answer.conversation().orElseThrow()) == [GPT]
    }

    def 'A model no service could have is refused where the application gives it'() {
        reportInfo """
            An empty settings field, or a model name with a space pasted into it, would only
            fail each run later. The lamp refuses it at once, so the application can point at
            the field.
        """
        when:
            Lamp.at(lamp).model(given)

        then:
            thrown(IllegalArgumentException)
            host.engines.isEmpty()

        where:
            given << ['', ' ', 'azure/gpt 6.1', 'azure/gpt-6.1-sol\n']
    }

    def '`oillamp help` says what --model does, and what happens without it'() {
        reportInfo """
            Whoever starts a session by hand needs to know that the model applies to old
            conversations too, and what a session without one does, before choosing.
        """
        when:
            var help = host.oillamp.run('help')

        then:
            help.console().contains('[--model <id>]')
            help.console().contains('--model <id>    answer every run with this model')
            help.console().contains('ones that were on another model')
            help.console().contains("conversation gets pi's default, and an old one keeps its own")
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /**
     * A lamp that has had a session, so that its agent's home exists, and pi's settings there
     * naming {@code model} as pi's default, as Genies writes them.
     */
    private void aLampWhosePiDefaultsTo(String model, String toml = '') {
        if (Lamp.agentHome(lamp).isEmpty()) {
            host.givenConfig(lamp, 'schema_version = 1\n' + toml)
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            host.machine { it.agent { String prompt -> prompts << prompt; 'Fine.' } }
        }
        Path settings = Lamp.agentHome(lamp).orElseThrow().resolve('.pi/agent/settings.json')
        Files.createDirectories(settings.parent)
        Files.writeString(settings, JsonOutput.toJson([defaultProvider: 'edenai', defaultModel: model]))
    }

    /** The conversation a run that `oillamp ask` reported happened in. */
    private static String conversationOf(Object asked) {
        assert asked.succeeded()
        asked.events().find { it instanceof LampEvent.RunFinished }.conversation().orElseThrow()
    }

    /** The entries of a conversation's file, as pi wrote them. */
    private List<Map> entriesOf(String conversation) {
        var found = Lamp.conversation(lamp, conversation).orElseThrow()
        Files.readAllLines(Lamp.agentHome(lamp).orElseThrow().resolve(found.file()))
            .collect { (Map) new JsonSlurper().parseText(it) }
    }

    /** The model of each answer in a conversation, in order. */
    private List<String> answeredBy(String conversation) {
        entriesOf(conversation).findAll { it.type == 'message' && it.message.role == 'assistant' }
                               .collect { it.message.model as String }
    }

    /** The model of each `model_change` entry in a conversation: one for each time pi recorded or was told one. */
    private List<String> modelsRecorded(String conversation) {
        entriesOf(conversation).findAll { it.type == 'model_change' }.collect { it.modelId as String }
    }

    private void startASession(String... options) {
        reported.clear()
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(110)) }
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        session = Thread.start { oillamp.run(*(['at', lamp.toString()] + options.toList())) }
        waitFor(LampEvent.Summary) { it.title() == 'your session is up' }
    }

    private void stopTheSession() {
        if (session?.alive) {
            host.oillamp.run('stop', lamp.toString())
            session.join(30_000)
            assert !session.alive
        }
    }

    private <T extends LampEvent> T waitFor(Class<T> kind, Closure<Boolean> which = { true }) {
        var deadline = System.currentTimeMillis() + 60_000
        while (true) {
            var found = reported.find { kind.isInstance(it) && which(it) }
            if (found != null) return (T) found
            assert System.currentTimeMillis() < deadline : "no ${kind.simpleName} came"
            Thread.sleep(50)
        }
    }
}
