package oillamp

import dev.lamp.Lamp
import dev.lamp.LampDirectory
import dev.lamp.LampPrompt
import dev.lamp.LampEvent
import dev.lamp.LampRunOutcome
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Rollup
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Title('Giving the agent instructions')
@Narrative('''
A session can give the agent instructions that hold for the whole session: with
`LampDirectory.instructions` in a program built on oillamp's Java API, `dev.lamp`, or with
`oillamp at --instructions-env` on the command line. They tell the agent about the world around
its sandbox, such as how the user talks to it. The sandbox itself is explained to the agent by
`~/AGENTS.md`, which every session writes.

## How the instructions get to pi

```
 application                        engine (host)                     sandbox: /home/agent
 ───────────                        ─────────────                     ────────────────────
 Lamp.at(dir)
   .instructions(text)   ──start──▶ oillamp at <dir> --embedded
   .start()                           --instructions-env OILLAMP_INSTRUCTIONS
                                    environment: OILLAMP_INSTRUCTIONS=<text>
                                      │
                                      │ read before anything else; a missing or unusable
                                      │ variable stops the session before it starts
                                      │
                                      └ once the sandbox is up, writes ──▶ ~/.pi/agent/APPEND_SYSTEM.md
                                        before the first run starts pi      which pi adds to its system
                                                                            prompt in every conversation
```

**Why the environment.** Instructions can run to many lines and a few kilobytes. On the
engine's command line they would be visible to every user of the machine in `ps`, and limited in
length; the engine's standard input already has a job, since the session lasts as long as it is
open; and a file would have to be made, found and cleaned up by someone. The model key is already
passed in the environment, which only this user can read. The instructions go the same way, in
a variable of their own. By hand: `GENIE="$(cat about.md)" oillamp at <dir> --instructions-env GENIE`.

**Why the engine writes the file, not the application.** `APPEND_SYSTEM.md` is pi's own file, in
pi's own directory. An application should not need to know that the agent is pi, or where pi
keeps its files; it gives the text to the lamp. pi reads the file each time it starts or opens a
conversation, so the instructions apply to old conversations as well as new ones.

**While the session runs.** `Lamp.useInstructions`, or `oillamp instructions <dir>
--instructions-env <name>` by hand, gives the running session new instructions, which the engine
writes into the same file. pi holds them from the next question on.

**Without instructions, the file stays as it is.** It is in the agent's home, where the agent or
the user may have written it, so a session that was given none does not touch it, whatever an
earlier session wrote there.

**Never through a link.** The agent can change anything in its home, and on the host it runs as
the user. The engine writes pi's files without following a symbolic link anywhere in the home,
so the agent cannot make it write elsewhere on the machine.
''')
@Subject([OilLamp, Lamp, LampDirectory])
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class Giving_The_Agent_Instructions_Spec extends Specification {

    /** Instructions as an application writes them: several lines of Markdown. */
    static final String INSTRUCTIONS = '''\
        You are Ada, an assistant with a Linux desktop of your own.

        - Save files for the user in ~/outbox.
        - Keep answers short.
        '''.stripIndent()

    /** Instructions an application gives a running session in place of [INSTRUCTIONS]. */
    static final String IN_GERMAN = '''\
        You are Ada, an assistant with a Linux desktop of your own.

        - Always answer in German.
        '''.stripIndent()

    /** What the variable ADA holds, by what the scenario says of it: empty when it is not set. */
    static final Map<String, Optional<String>> VARIABLES = [
            'is not set'               : Optional.<String>empty(),
            'is empty'                 : Optional.of(''),
            'holds 64 KiB and one byte': Optional.of('x' * (64 * 1024 + 1))]

    /** Instructions an application might give, by what the scenario says of them. */
    static final Map<String, String> TEXTS = [
            'empty'                      : '',
            'blank: spaces and a newline': '  \n ',
            '64 KiB and one byte long'   : 'x' * (64 * 1024 + 1)]

    @TempDir Path tmp
    ScenarioHost host

    Path lamp
    final List<LampEvent> reported = new CopyOnWriteArrayList<>()
    /** What pi's instructions file held each time the stand-in pi was given a question. */
    final List<String> instructionsWhenAsked = new CopyOnWriteArrayList<>()
    Thread session

    def setup() {
        host = new ScenarioHost(tmp)
        lamp = host.lampPath()
        host.machine {
            it.reallyRuns('ssh-keygen').agent { String prompt ->
                instructionsWhenAsked << (Files.exists(piFile('APPEND_SYSTEM.md')) ? Files.readString(piFile('APPEND_SYSTEM.md')) : '')
                'Fine.'
            }
        }
    }

    def cleanup() {
        stopTheSession()
    }

    def 'Instructions given to `LampDirectory.instructions` reach pi before pi starts, by way of the engine\'s environment'() {
        reportInfo """
            A program built on oillamp gives the text to the lamp with
            `LampDirectory.instructions`, and nothing else: it need not know where pi keeps its
            instructions. The `Lamp` hands the text to the engine in a variable of the engine's
            environment, and names the variable on the command line, as it does for the key. The
            engine writes the text into pi's `APPEND_SYSTEM.md` once the sandbox is up, before the
            first run starts pi, so pi has it from its very first question.
        """
        when: 'a program starts a lamp with instructions, and asks a question with `Lamp.ask`'
            var running = Lamp.at(lamp).instructions(INSTRUCTIONS).launchedBy(host.launcher).start()
            assert running.awaitRunning(Duration.ofSeconds(30))
            var answer = running.ask(LampPrompt.fresh('Who are you?'))
            running.close()

        then: 'the engine was given the name of a variable on its command line, and the text in that variable'
            host.engines.first().arguments == ['at', lamp.toString(), '--embedded', '--instructions-env', 'OILLAMP_INSTRUCTIONS']
            host.engines.first().environment == [OILLAMP_INSTRUCTIONS: INSTRUCTIONS]

        and: 'pi\'s instructions file held the text when pi was given the first question'
            answer.outcome() == LampRunOutcome.FINISHED
            instructionsWhenAsked == [INSTRUCTIONS]
    }

    def '`Lamp.useInstructions` replaces a running session\'s instructions, and pi\'s instructions file holds the new text when the next question continues an earlier conversation'() {
        reportInfo """
            Let's consider an application whose instructions depend on a setting the user can
            change while the agent is awake, such as the language the agent is to answer in.
            Restarting the session to pass the new text would end whatever runs on the agent's
            desktop. `Lamp.useInstructions` gives the running session the new text instead. As at
            the start, the `Lamp` passes the text in the engine's environment, and the engine
            writes it into pi's `APPEND_SYSTEM.md`, replacing the old text.

            pi reads that file whenever it starts or opens a conversation, and before every
            question the session has pi start a conversation or open one. So what matters is what the file holds
            when a question reaches pi, and that is what this scenario checks, for a question in
            the conversation the agent answered under the old instructions.
        """
        given: 'a session started with instructions, and a conversation the agent answered under them'
            var running = Lamp.at(lamp).instructions(INSTRUCTIONS).launchedBy(host.launcher).start()
            assert running.awaitRunning(Duration.ofSeconds(30))
            var first = running.ask(LampPrompt.fresh('Who are you?'))

        when: 'the application gives the session new instructions with `Lamp.useInstructions`'
            running.useInstructions(IN_GERMAN)

        then: 'the `Lamp` started an engine with `oillamp instructions`, naming a variable on its command line and putting the new text in it'
            host.engines.last().arguments == ['instructions', lamp.toString(), '--instructions-env', 'OILLAMP_INSTRUCTIONS', '--embedded']
            host.engines.last().environment == [OILLAMP_INSTRUCTIONS: IN_GERMAN]

        when: 'the application asks a second question in that conversation'
            running.ask(LampPrompt.in(first.conversation().orElseThrow(), 'And what can you do?'))
            running.close()

        then: 'pi\'s instructions file held the first instructions when pi got the first question, and the new ones when it got the second'
            instructionsWhenAsked == [INSTRUCTIONS, IN_GERMAN]
    }

    def '`oillamp instructions` needs the variable that holds the new instructions, and a running session to give them to'() {
        reportInfo """
            `oillamp instructions <lamp> --instructions-env <name>` is how `Lamp.useInstructions`
            reaches the running session, and someone can run it by hand. Without
            `--instructions-env` there is no text to give, so oillamp refuses the command line with
            `OIL-USAGE-001`. For a lamp with no session running there is no agent to tell, so
            oillamp reports `OIL-SESSION-001` rather than keep the text for a later session.
        """
        given: 'a lamp with no session running, and instructions in the variable `ADA`'
            aLampThatHasHadASession()
            host.machine { it.environmentVariable('ADA', INSTRUCTIONS) }

        when: '`oillamp instructions` is run without `--instructions-env`'
            var noVariable = host.oillamp.run('instructions', lamp.toString())

        then: 'oillamp refuses the command line, and names the option it needs'
            noVariable.reported('OIL-USAGE-001')
            noVariable.errors().first().whatHappened().contains('--instructions-env')

        when: '`oillamp instructions` is run with `--instructions-env ADA`'
            var noSession = host.oillamp.run('instructions', lamp.toString(), '--instructions-env', 'ADA')

        then: 'oillamp says no session is running'
            noSession.reported('OIL-SESSION-001')
    }

    @Rollup
    def '`Lamp.useInstructions` refuses instructions that are blank or longer than 64 KiB before it asks the session'() {
        reportInfo """
            `Lamp.useInstructions` checks the text as `LampDirectory.instructions` does, and throws
            `IllegalArgumentException`, so the application learns of the mistake where it gives
            the text.
        """
        when: 'the application gives a lamp the instructions the row describes'
            Lamp.at(lamp).launchedBy(host.launcher).join().withCloseable { it.useInstructions(TEXTS[text]) }

        then: 'the `Lamp` throws `IllegalArgumentException`, and started no engine with `oillamp instructions`'
            thrown(IllegalArgumentException)
            !host.engines.any { it.arguments.first() == 'instructions' }

        where:
            text << TEXTS.keySet()
    }

    def '`oillamp at --instructions-env` takes the instructions from the variable it names, and replaces what pi\'s file held'() {
        reportInfo """
            Let's consider a lamp whose pi instructions file, `~/.pi/agent/APPEND_SYSTEM.md`,
            holds what an earlier session wrote. Each session given instructions writes them again,
            whole, so the file holds exactly what this session was given, not what an earlier
            session or the agent left there.
        """
        given: 'a lamp whose pi\'s instructions file holds what an earlier session wrote'
            aLampThatHasHadASession()
            Files.writeString(piFile('APPEND_SYSTEM.md'), 'You are Bob.\n')

        and: 'the instructions in the variable `ADA`, as someone would put them there from a file'
            host.machine { it.environmentVariable('ADA', INSTRUCTIONS) }

        when: 'a session is started with `--instructions-env ADA`'
            startASession('--instructions-env', 'ADA')

        then: 'pi\'s instructions file holds the variable\'s text, and the session said that pi adds it to its system prompt'
            Files.readString(piFile('APPEND_SYSTEM.md')) == INSTRUCTIONS
            reported.any { it instanceof LampEvent.Info && it.text().contains("pi adds the session's instructions to its system prompt") }
    }

    def 'Without instructions or a model, the session leaves pi\'s files as they are, whoever wrote them'() {
        reportInfo """
            pi's instructions file and its settings are in the agent's home, where the user or
            the agent may have written them by hand, or pi itself, or an earlier session. A
            session given neither instructions nor a model has nothing to tell pi, so it changes
            neither file and makes neither: a file that was not there is still not there.
        """
        given: 'instructions the user wrote by hand, and no settings for pi'
            aLampThatHasHadASession()
            Files.writeString(piFile('APPEND_SYSTEM.md'), 'Always answer in French.\n')
            Files.deleteIfExists(piFile('settings.json'))

        when: 'a session is started with neither'
            startASession()

        then: 'the user\'s instructions are as they were, and there are still no settings'
            Files.readString(piFile('APPEND_SYSTEM.md')) == 'Always answer in French.\n'
            !Files.exists(piFile('settings.json'))
    }

    def 'The session does not follow a link the agent put in place of `~/.pi`, and warns about each file it did not write'() {
        reportInfo """
            Had the agent replaced `~/.pi` with a symbolic link to a directory of the user's, a
            session that followed it would write pi's settings, its instructions and oillamp's pi
            extensions there, on the host, as the user. The session writes none of those files,
            warns about each with `OIL-LAMP-012`, naming the link, and goes on: the sandbox works,
            only pi lacks what the session would have written.
        """
        given: 'the agent replaced its `~/.pi` with a link to a directory of the user\'s'
            aLampThatHasHadASession()
            var usersDirectory = Files.createDirectories(tmp.resolve('users-config'))
            deleteTree(agentHome().resolve('.pi'))
            Files.createSymbolicLink(agentHome().resolve('.pi'), usersDirectory)

        when: 'a session is started with instructions and a model'
            host.machine { it.environmentVariable('ADA', INSTRUCTIONS) }
            startASession('--instructions-env', 'ADA', '--model', 'azure/gpt-6.1-sol')

        then: 'it refused pi\'s instructions, its settings and oillamp\'s extensions, naming the link each time'
            var refused = warnings('OIL-LAMP-012')
            refused*.whatHappened().every { it.contains("${agentHome().resolve('.pi')} is a symbolic link") }
            ['APPEND_SYSTEM.md', 'settings.json', 'oillamp-conversations.js', 'oillamp-schedule.js'].every { file ->
                refused.any { it.whatHappened().contains(file) }
            }

        and: 'nothing was written into the user\'s directory'
            Files.list(usersDirectory).count() == 0

        and: 'the session is up all the same'
            session.alive
    }

    @Rollup
    def 'Oillamp refuses to start a session whose instructions variable is unset, empty or over 64 KiB'() {
        reportInfo """
            `oillamp at --instructions-env ADA` takes the session's instructions from the
            environment variable `ADA`. A session whose instructions did not arrive would run an
            agent that does not know what it is for, and someone would learn of it only from the
            agent's odd answers. So the engine reads the variable first, and refuses the command
            line with `OIL-USAGE-001` if the variable is not set, is empty, or holds more than
            64 KiB, before anything on the machine changes. 64 KiB is far more than any useful
            instructions, and half of what Linux lets one variable hold.
        """
        given: 'the variable `ADA`, as the row says'
            VARIABLES[variable].ifPresent { value -> host.machine { it.environmentVariable('ADA', value) } }

        when: 'a session is started with `--instructions-env ADA`'
            var outcome = host.oillamp.run('at', lamp.toString(), '--instructions-env', 'ADA')

        then: 'oillamp refuses the command line, saying why, and makes no lamp'
            outcome.reported('OIL-USAGE-001')
            outcome.errors().first().whatHappened() == "--instructions-env ADA: ${why}".toString()
            !Files.exists(lamp)

        where:
            variable                     || why
            'is not set'                 || 'the variable is not set, or empty'
            'is empty'                   || 'the variable is not set, or empty'
            'holds 64 KiB and one byte'  || 'the instructions are longer than 64 KiB'
    }

    @Rollup
    def '`LampDirectory.instructions` refuses instructions that are blank or longer than 64 KiB'() {
        reportInfo """
            A program built on oillamp gives the instructions with `LampDirectory.instructions`,
            which checks at once what the engine would check, and throws
            `IllegalArgumentException`. So the program learns of the mistake where it gives the
            text, before any engine is started.
        """
        when: 'the program gives the lamp the instructions the row describes'
            Lamp.at(lamp).instructions(TEXTS[text])

        then: 'the lamp refuses them, and no engine was started'
            thrown(IllegalArgumentException)
            host.engines.isEmpty()

        where:
            text << TEXTS.keySet()
    }

    def '`oillamp help` says where the instructions go, and what happens without them'() {
        reportInfo """
            Someone who starts a session from the command line needs to know which file the
            instructions end up in, so that they can look at it, and that a session without them
            leaves that file alone.
        """
        when: '`oillamp help` is run'
            var help = host.oillamp.run('help')

        then: 'it shows the option, and names the file the text goes to'
            help.console().contains('[--instructions-env <name>]')
            help.console().contains('~/.pi/agent/APPEND_SYSTEM.md. Without it, that file stays')
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** A lamp that has had a session, so that its agent's home and pi's directory exist. */
    private void aLampThatHasHadASession() {
        assert host.oillamp.run('at', lamp.toString()).succeeded()
    }

    private Path agentHome() { Lamp.agentHome(lamp).orElseThrow() }

    /** A file in pi's directory in the agent's home, on the host. */
    private Path piFile(String name) { agentHome().resolve('.pi/agent').resolve(name) }

    /** The warnings with {@code code} that the session reported. */
    private List<dev.lamp.LampProblem> warnings(String code) {
        reported.findAll { it instanceof LampEvent.Warning && it.problem().code().value() == code }*.problem()
    }

    private static void deleteTree(Path path) {
        if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) Files.list(path).each { deleteTree(it) }
        Files.deleteIfExists(path)
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
