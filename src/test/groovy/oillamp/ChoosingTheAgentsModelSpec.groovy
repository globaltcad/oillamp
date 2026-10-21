package oillamp

import dev.lamp.Lamp
import dev.lamp.LampPrompt
import dev.lamp.LampEvent
import dev.lamp.LampRunOutcome
import dev.lamp.LampAgentProgress
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Narrative
import spock.lang.Rollup
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

@Narrative('''
Choosing the model the agent answers with: `oillamp at --model` and `oillamp model` by hand, and
`Lamp.Starting.model` and `Lamp.useModel` in an application such as Genies.

## How a model gets from the application to an answer

```
 application                 engine (host)                            sandbox: /home/agent
 ───────────                 ─────────────                            ────────────────────
 Lamp.at(dir)
   .modelService(url)  ───▶  oillamp at <dir> --embedded
   .modelKey(key)              --model-service <url>
   .model(id)                  --model-key-env OILLAMP_MODEL_KEY      the key travels only in
   .instructions(text)         --model <id>                           that variable
   .start()                    --instructions-env OILLAMP_INSTRUCTIONS
                               │
                               ├ model relay: holds <url> and the key ◀── every model request,
                               │                                          sent by pi to
                               │                                          EDENAI_BASE_URL=
                               │                                          http://127.0.0.1:3129/v3
                               │                                          with a placeholder key
                               │
                               ├ once the sandbox is up, writes ────────▶ ~/.pi/agent/settings.json
                               │                                            defaultProvider: edenai
                               │                                            defaultModel:    <id>
                               │                                          ~/.pi/agent/APPEND_SYSTEM.md
                               │                                            the instructions
                               │
                               └ at the first run, starts ──────────────▶ ssh … pi --mode rpc
                                 and before every run:                    (stays for the session)
                                   get_state      which model is pi on?
                                   set_model id   only if it is another one
                                   prompt
```

The agent's home, `/home/agent` in the sandbox, is the lamp's `agent-lamp-…` folder on the host,
so the engine writes pi's files there directly.

**Why pi's provider is always `edenai`, even for Ollama.** The model service and its key stay on
the host, in the engine's model relay. Inside the sandbox, pi has a single provider: the one the
pi-edenai extension adds, which talks to whatever stands behind its address. That is the relay,
which sends each request on to the session's service, Eden AI or Ollama or any other, and adds
the key. The extension fetches the list of models through the relay too; for Eden AI the relay
leaves only the models served in the EU. So `azure/gpt-6.1-sol` and `genies/qwen3:8b` are both
`edenai` models to pi.

**Where pi decides which model answers.** pi reads its settings each time it starts or opens a
conversation, not only when its process starts. A new conversation starts on the default model
the settings name, and pi records it in the conversation's file: a `model_change` entry, and the
thinking level as a `thinking_level_change`. A conversation opened again goes back to the model
its file last names, whatever the default says.

**So the session does two things.** It writes its model as pi's default, so that a new
conversation starts on it by itself. And before every run it asks pi which model it is on and
tells it to switch only if that is another one, so that an old conversation moves to the
session's model too. Telling pi is not free: on every `set_model` pi writes a `model_change`
into the conversation's file, even for the model it is on, and sets the thinking level again
from its settings.

**Why it is built this way.** Before, an application froze the model in two places: Genies
wrote it into pi's settings when a genie woke, and every conversation stayed on the model it
had started on. A real genie's old chat stayed on a DeepSeek model that Eden AI had begun to
refuse, while Genies' settings named a model that worked.

## How the scenarios see it

The engine runs on a simulated machine with a stand-in pi that keeps track of its model the way
pi does: it reads the default from its settings for each conversation it starts or opens,
passes over a default the service does not offer, goes back to an opened conversation's model,
and writes into each answer the model that gave it. The scenarios read the conversation's file:
`answeredBy` lists the model of each answer, and `modelsRecorded` the model of each
`model_change`: one that pi writes itself when a conversation starts, and one more for every
switch.
''')
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ChoosingTheAgentsModelSpec extends Specification {

    /** The model a scenario gives the session: one Eden AI offers, as Eden AI names it. */
    static final String GPT = 'azure/gpt-6.1-sol'
    /** Another one, for a conversation or a default that is not the session's model. */
    static final String DEEPSEEK = 'tensorx/deepseek/deepseek-v4.1-flash'

    /** Models no service could have, by how an application's settings field might hold them. */
    static final Map<String, String> UNUSABLE_MODELS = [
            'an empty field'                      : '',
            'only a space'                        : ' ',
            'a space inside'                      : 'azure/gpt 6.1',
            'a line break pasted along at the end': 'azure/gpt-6.1-sol\n']

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

    def 'The session writes its model as pi\'s default before pi starts, so a new conversation starts on it without a switch'() {
        reportInfo """
            pi starts a new conversation on the default model in its settings, and records it in
            the conversation's file. If that were another model, the session would have to tell pi
            to switch before every new conversation, and each switch adds a `model_change` to the
            file and sets the thinking level again. So the session writes its model into pi's
            settings first: once the sandbox is up, and before the first run starts pi. The key
            `defaultProvider` is always `edenai`, the one provider pi has in a lamp.
        """
        given: 'pi\'s settings name DeepSeek as the default, as pi\'s own /model command would have left them'
            aLampWhosePiDefaultsTo(DEEPSEEK)

        when: 'a session given GPT is asked something in a new conversation'
            startASession('--model', GPT)
            var asked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')

        then: 'GPT answered'
            var conversation = conversationOf(asked)
            answeredBy(conversation) == [GPT]

        and: 'the conversation records only the model pi started on, so no switch was needed'
            modelsRecorded(conversation) == [GPT]

        and: 'pi\'s settings name GPT now, and the session said so'
            piSettings().defaultProvider == 'edenai'
            piSettings().defaultModel == GPT
            reported.any { it instanceof LampEvent.Info && it.text().contains("pi starts new conversations on ${GPT}") }
    }

    def 'An old conversation that was on another model is answered by the session\'s model from then on, after one switch'() {
        reportInfo """
            This is what happened to a user whose model service stopped offering the model their
            old chat had started on: every question in that chat failed, although the
            application's settings named a model that worked. pi goes back to the model a
            conversation's file names whenever it opens the conversation, whatever its default
            says, so the default alone cannot help. The session asks pi which model it is on
            before each run, and tells it to switch when that is another one. The earlier answers
            stay as they were, each with the model that gave it, and once switched the
            conversation stays on the new model, so pi is told only once.
        """
        given: 'a conversation that DeepSeek answered, in a session given no model'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            startASession()
            var old = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))
            stopTheSession()

        when: 'it is continued twice in a session given GPT'
            startASession('--model', GPT)
            assert host.oillamp.run('ask', lamp.toString(), '--in', old, 'Name a fruit').succeeded()
            assert host.oillamp.run('ask', lamp.toString(), '--in', old, 'Name a tree').succeeded()

        then: 'GPT gave both new answers, and DeepSeek\'s first answer stays'
            answeredBy(old) == [DEEPSEEK, GPT, GPT]

        and: 'pi was told to switch once: DeepSeek is the model pi recorded at the start, GPT the switch'
            modelsRecorded(old) == [DEEPSEEK, GPT]
    }

    def 'A conversation already on the session\'s model is continued without telling pi anything'() {
        reportInfo """
            The usual case, once the session has written its model as pi's default: every
            conversation starts on it. pi is then never told to switch, so the conversation's file
            holds no entries beyond the one pi writes itself when a conversation starts, and the
            thinking level the conversation was on stays as it was.
        """
        given: 'a session given GPT'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            startASession('--model', GPT)

        when: 'a conversation is started and continued twice'
            var conversation = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a fruit').succeeded()
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a tree').succeeded()

        then: 'GPT answered all three, and only pi\'s own record of its model is in the file'
            answeredBy(conversation) == [GPT, GPT, GPT]
            modelsRecorded(conversation) == [GPT]
    }

    def 'A running session is given another model, and the next question is answered by it, in an old conversation too'() {
        reportInfo """
            A user who changes the model in an application's settings expects the next answer
            to come from the new model, without the sandbox starting again: whatever runs on the
            agent's desktop would be gone. `oillamp model`, and `Lamp.useModel`, tell the running
            session; the next run uses the new model, whether it continues a conversation or
            starts one. pi is told to switch only once per conversation, as with --model.
        """
        given: 'a session given DeepSeek, and a conversation DeepSeek answered'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            startASession('--model', DEEPSEEK)
            var conversation = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))

        when: 'the session is given GPT'
            var changed = host.oillamp.run('model', lamp.toString(), GPT)

        then: 'it says so, and so does the session to whoever follows it'
            changed.succeeded()
            changed.console().contains("the agent answers with ${GPT} from its next run on")
            waitFor(LampEvent.Ok) { it.text().contains("answers with ${GPT}") }

        when: 'the conversation goes on, twice'
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a fruit').succeeded()
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a tree').succeeded()

        then: 'GPT answered both, after one switch'
            answeredBy(conversation) == [DEEPSEEK, GPT, GPT]
            modelsRecorded(conversation) == [DEEPSEEK, GPT]

        when: 'an application holding the lamp goes back to DeepSeek, and the conversation goes on'
            Lamp.at(lamp).launchedBy(host.launcher).join().withCloseable { it.useModel(DEEPSEEK) }
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a river').succeeded()

        then: 'DeepSeek answered'
            answeredBy(conversation) == [DEEPSEEK, GPT, GPT, DEEPSEEK]
    }

    def 'A model changed while the session runs becomes pi\'s default too, so a conversation started later needs no switch'() {
        reportInfo """
            pi is already running when the model changes, and it stays for the whole session.
            That does not stop it from starting the next new conversation on the new model: pi
            reads its settings again whenever it starts or opens a conversation. So the session
            writes the new model into them as soon as it is given it, and a conversation started
            after that holds only pi's own record of its model, as at the start of a session.
        """
        given: 'a session given DeepSeek, whose pi has started and answered once'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            startASession('--model', DEEPSEEK)
            assert host.oillamp.run('ask', lamp.toString(), 'Name a colour').succeeded()

        when: 'the session is given GPT, and asked something in a new conversation'
            assert host.oillamp.run('model', lamp.toString(), GPT).succeeded()
            var fresh = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a fruit'))

        then: 'pi\'s settings name GPT'
            piSettings().defaultModel == GPT

        and: 'the new conversation started on GPT without a switch'
            answeredBy(fresh) == [GPT]
            modelsRecorded(fresh) == [GPT]
    }

    def 'A run in progress when the model changes finishes on the model it started with'() {
        reportInfo """
            Switching the model in the middle of a run would mix two models in one answer. The
            change waits for the next run; the one in progress ends on its own model.
        """
        given: 'an agent that works on a slow question until it is let go'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            var letGo = new java.util.concurrent.CountDownLatch(1)
            host.machine { it.agent { String prompt -> prompts << prompt; if (prompt.contains('slowly')) letGo.await(); 'Fine.' } }
            startASession()
            var slow = Thread.start { host.oillamp.run('ask', lamp.toString(), 'Count slowly') }
            waitFor(LampEvent.RunStarted)

        when: 'the model changes while it works, and then it finishes'
            assert host.oillamp.run('model', lamp.toString(), GPT).succeeded()
            letGo.countDown()
            slow.join(30_000)
            var slowRun = waitFor(LampEvent.RunFinished)
            var next = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))

        then: 'the slow run kept DeepSeek, and the next one is GPT\'s'
            answeredBy(slowRun.conversation().orElseThrow()) == [DEEPSEEK]
            answeredBy(next) == [GPT]
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

        then: 'GPT wrote the report'
            finished.outcome() == LampRunOutcome.FINISHED
            answeredBy(finished.conversation().orElseThrow()) == [GPT]
    }

    def 'An application gives the model with Lamp.Starting.model, and its questions are answered by it'() {
        reportInfo """
            Genies has one model setting for all its genies, and starts a genie's lamp each time
            the genie is woken. It hands the model to the lamp the way it hands over the model
            service and the key. The engine gets the model on its command line, which is fine,
            since a model's name is no secret; the key goes in the environment instead.
        """
        given: 'a lamp whose pi defaults to DeepSeek'
            aLampWhosePiDefaultsTo(DEEPSEEK)

        when: 'an application starts the lamp with GPT, asks a question, and closes the lamp'
            var running = Lamp.at(lamp).model(GPT).launchedBy(host.launcher).start()
            assert running.awaitRunning(Duration.ofSeconds(30))
            var answer = running.ask(LampPrompt.fresh('Name a colour'))
            running.close()

        then: 'the engine was told the model on its command line'
            host.engines.first().arguments == ['at', lamp.toString(), '--embedded', '--model', GPT]

        and: 'GPT answered'
            answer.outcome() == LampRunOutcome.FINISHED
            answeredBy(answer.conversation().orElseThrow()) == [GPT]
    }

    def 'pi\'s other settings stay as they were when the session writes its model'() {
        reportInfo """
            pi's settings file holds more than the model. It lists the packages pi loads, among
            them the pi-edenai extension, without which pi in a lamp would have no provider at
            all; and whatever the user or the agent set, such as a theme or the thinking level.
            The session changes the two keys of the default model and nothing else.

            The image's copy of these settings reaches the agent's home only when the sandbox
            starts, and only if the home has no settings file yet. That is why the session writes
            the model once the sandbox is up: a file written before would keep the image's from
            being copied, packages and all.
        """
        given: 'pi\'s settings with its packages, a theme, a thinking level and an old default'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            writePiSettings(packages: ['git:github.com/edenai/pi-edenai'], theme: 'light',
                            defaultThinkingLevel: 'high', defaultProvider: 'edenai', defaultModel: DEEPSEEK)

        when: 'a session given GPT starts'
            startASession('--model', GPT)

        then: 'the default model is GPT, and everything else is as it was'
            piSettings() == [packages: ['git:github.com/edenai/pi-edenai'], theme: 'light',
                             defaultThinkingLevel: 'high', defaultProvider: 'edenai', defaultModel: GPT]
    }

    def 'pi\'s settings that are not JSON are left as they are, and the session says so'() {
        reportInfo """
            A settings file that is not a JSON object cannot be read by pi either, and replacing
            it would throw away whatever someone wrote there. So the session leaves it alone, and
            says so with a warning naming the file. It still has every run answered by its
            model: pi starts on a default of its own, and is told to switch.
        """
        given: 'pi\'s settings, cut off in the middle'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            var broken = '{"packages": ["git:github.com/edenai/pi-edenai"'
            Files.writeString(piSettingsFile(), broken)

        when: 'a session given GPT starts and is asked something'
            startASession('--model', GPT)
            var conversation = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))

        then: 'the session warned about the file, and left it as it was'
            warnings('OIL-LAMP-013').any { it.whatHappened().contains(piSettingsFile().toString()) }
            Files.readString(piSettingsFile()) == broken

        and: 'GPT still answered, after pi had been told to switch from its own default'
            answeredBy(conversation) == [GPT]
            modelsRecorded(conversation).size() == 2
            modelsRecorded(conversation).last() == GPT
    }

    def 'A link the agent put in place of pi\'s settings is not followed, and the session says what it refused'() {
        reportInfo """
            The agent can change anything in its home, and on the host it runs as the user. Had
            it replaced pi's settings with a symbolic link to one of the user's files, such as
            `~/.bashrc`, a session that followed the link would write pi's settings into that
            file. The session never follows a link in the agent's home, neither when it starts
            nor when its model changes later, while the agent is running and could plant the
            link at any moment. It warns, naming the link, and goes on: every run is still
            answered by its model, after a switch.
        """
        given: 'the agent replaced pi\'s settings with a link to a file of the user\'s'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            var usersOwnFile = Files.writeString(tmp.resolve('users-bashrc'), 'export EDITOR=vim\n')
            Files.delete(piSettingsFile())
            Files.createSymbolicLink(piSettingsFile(), usersOwnFile)

        when: 'a session given GPT starts and is asked something'
            startASession('--model', GPT)
            var conversation = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))

        then: 'the session warned, naming the link, and the user\'s file is as it was'
            warnings('OIL-LAMP-012').any { it.whatHappened().contains("${piSettingsFile()} is a symbolic link") }
            Files.readString(usersOwnFile) == 'export EDITOR=vim\n'
            Files.isSymbolicLink(piSettingsFile())

        and: 'GPT answered all the same'
            answeredBy(conversation) == [GPT]

        when: 'the session is given DeepSeek while it runs'
            reported.clear()
            assert host.oillamp.run('model', lamp.toString(), DEEPSEEK).succeeded()

        then: 'it refuses the link again'
            waitFor(LampEvent.Warning) { it.problem().code().value() == 'OIL-LAMP-012' }
            Files.readString(usersOwnFile) == 'export EDITOR=vim\n'
    }

    def 'Without a model, pi decides as before, and its settings are left alone'() {
        reportInfo """
            A session started without --model leaves the model to pi, as before there was such
            an option: pi's default for a new conversation, the conversation's own model for an
            old one. It does not touch pi's settings either, so a user who sets pi's default with
            pi's own /model command keeps it.
        """
        given: 'a conversation DeepSeek answered, in a session given no model'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            startASession()
            var old = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))

        when: 'pi\'s default becomes GPT, the old conversation goes on and a new one starts'
            aLampWhosePiDefaultsTo(GPT)
            var settingsBefore = Files.readString(piSettingsFile())
            assert host.oillamp.run('ask', lamp.toString(), '--in', old, 'Name a fruit').succeeded()
            var fresh = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a tree'))

        then: 'the old conversation stays on DeepSeek, and pi was never told to switch'
            answeredBy(old) == [DEEPSEEK, DEEPSEEK]
            modelsRecorded(old) == [DEEPSEEK]

        and: 'the new one is on the new default'
            answeredBy(fresh) == [GPT]
            modelsRecorded(fresh) == [GPT]

        and: 'pi\'s settings are exactly as they were written'
            Files.readString(piSettingsFile()) == settingsBefore
    }

    def 'A model the service does not offer fails the run, and the answer names the model'() {
        reportInfo """
            A model can disappear from a service, or be mistyped in an application's settings.
            pi passes over a default it cannot find, so a new conversation starts on another
            model, and the session tells pi to switch, which pi refuses. Continuing silently on
            that other model would hide the problem. So the run fails before the question reaches
            the agent, and says which model could not be used and why. The conversation keeps no
            trace of the model, and the session goes on.
        """
        given: 'a service that offers GPT and DeepSeek, and a session given a model it does not offer'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            host.machine { it.modelsOffered(GPT, DEEPSEEK) }
            startASession('--model', 'azure/gpt-0-retired')

        when: 'it is asked something'
            var asked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')

        then: 'the run failed, naming the model and pi\'s reason'
            !asked.succeeded()
            var finished = asked.events().find { it instanceof LampEvent.RunFinished }
            finished.outcome() == LampRunOutcome.FAILED
            finished.answer() == 'pi could not switch to the model azure/gpt-0-retired: Model not found: edenai/azure/gpt-0-retired'

        and: 'the agent never got the question, and the conversation has no trace of the model'
            prompts.isEmpty()
            var conversation = finished.conversation().orElseThrow()
            !modelsRecorded(conversation).contains('azure/gpt-0-retired')

        and: 'the session is still up'
            session.alive
    }

    def 'The conversations say which model wrote each answer, and so does each answer as it comes'() {
        reportInfo """
            pi records the model with every answer. An application can show it, for instance
            when the user points at an answer, which matters most in a conversation that changed
            model: each answer says where it came from, and old answers keep their model.
        """
        given: 'a conversation DeepSeek answered, and then the session given GPT'
            aLampWhosePiDefaultsTo(DEEPSEEK)
            startASession()
            var conversation = conversationOf(host.oillamp.run('ask', lamp.toString(), 'Name a colour'))
            assert host.oillamp.run('model', lamp.toString(), GPT).succeeded()

        when: 'the conversation goes on'
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a fruit').succeeded()

        then: 'the conversation names the model of each answer, and of nothing else'
            var entries = Lamp.conversation(lamp, conversation).orElseThrow().entries()
            entries.findAll { it.kind() == Lamp.Conversation.Kind.MESSAGE_FROM_AGENT }*.model() == [Optional.of(DEEPSEEK), Optional.of(GPT)]
            entries.findAll { it.kind() != Lamp.Conversation.Kind.MESSAGE_FROM_AGENT }.every { it.model().isEmpty() }

        and: 'each answer, as the session reported it, named its model'
            reported.findAll { it instanceof LampEvent.RunProgress && it.progress() instanceof LampAgentProgress.Answered }
                    *.progress()*.model() == [Optional.of(DEEPSEEK), Optional.of(GPT)]
    }

    def 'A running session cannot be given a model no service could have, and a lamp without a session says so'() {
        reportInfo """
            The same rule as for --model: a name with a space, or outside ASCII, is refused before
            the session is asked. A lamp with no session running has nothing to change, and says
            so, rather than keeping the model for later.
        """
        given: 'a lamp with no session running'
            aLampWhosePiDefaultsTo(DEEPSEEK)

        when: '`oillamp model` is run for it'
            var noSession = host.oillamp.run('model', lamp.toString(), GPT)

        then: 'it says no session is running'
            noSession.reported('OIL-SESSION-001')

        when: 'an application holding the lamp gives a model with a space in it'
            Lamp.at(lamp).launchedBy(host.launcher).join().withCloseable { it.useModel('azure/gpt 6.1') }

        then: 'the lamp refuses it before asking any session'
            thrown(IllegalArgumentException)
    }

    @Rollup
    def 'A model no service could have is refused where the application gives it'() {
        reportInfo """
            An empty settings field, or a model name with a space pasted into it, would only
            fail each run later. The lamp refuses it at once, so the application can point at
            the field, and no engine is started.
        """
        when: 'an application gives the lamp the model the row below describes'
            Lamp.at(lamp).model(UNUSABLE_MODELS[model])

        then: 'the lamp refuses it, and no engine was started'
            thrown(IllegalArgumentException)
            host.engines.isEmpty()

        where:
            model << UNUSABLE_MODELS.keySet()
    }

    def '`oillamp help` says what --model does, and what happens without it'() {
        reportInfo """
            Whoever starts a session by hand needs to know, before choosing, that the model
            applies to old conversations too and becomes pi's default, and what a session without
            one does.
        """
        when: 'a person asks for help'
            var help = host.oillamp.run('help')

        then: 'it shows the option, and says what it does with and without it'
            help.console().contains('[--model <id>]')
            help.console().contains('--model <id>    answer every run with this model')
            help.console().contains("ones that were on another model; it also becomes pi's")
            help.console().contains("default. Without it, a new conversation gets pi's default,")
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /**
     * A lamp that has had a session, so that its agent's home exists, with pi's settings there
     * naming {@code model} as pi's default, as pi's own /model command would leave them.
     */
    private void aLampWhosePiDefaultsTo(String model, String toml = '') {
        if (Lamp.agentHome(lamp).isEmpty()) {
            host.givenConfig(lamp, 'schema_version = 1\n' + toml)
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            host.machine { it.agent { String prompt -> prompts << prompt; 'Fine.' } }
        }
        writePiSettings(defaultProvider: 'edenai', defaultModel: model)
    }

    /** pi's settings file in the agent's home, on the host. */
    private Path piSettingsFile() {
        Lamp.agentHome(lamp).orElseThrow().resolve('.pi/agent/settings.json')
    }

    private void writePiSettings(Map settings) {
        Files.createDirectories(piSettingsFile().parent)
        Files.writeString(piSettingsFile(), JsonOutput.toJson(settings))
    }

    private Map piSettings() {
        new JsonSlurper().parseText(Files.readString(piSettingsFile())) as Map
    }

    /** The warnings with {@code code} that the session reported. */
    private List<dev.lamp.Problem> warnings(String code) {
        reported.findAll { it instanceof LampEvent.Warning && it.problem().code().value() == code }*.problem()
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

    /**
     * The model of each `model_change` entry in a conversation: the one pi writes when the
     * conversation starts, and one for each time pi was told to switch.
     */
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
