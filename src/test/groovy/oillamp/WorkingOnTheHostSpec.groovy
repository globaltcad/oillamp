package oillamp

import dev.lamp.Lamp
import dev.lamp.LampEvent
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Title('Working on the host: editing files and running commands, each call judged first')
@Narrative('''
An agent in a lamp can be let work on the user's own computer much as pi would if it ran there:
read a file, edit it, and run a command such as a build or a test, with the output coming back.
Each of those is one tool call, and oillamp judges every call before it happens. A separate
model call, the checker, reads the user's requirements, the instruction of the run the agent is
in, and the call itself, and allows it or refuses it.

```
 pi in the sandbox                         oillamp on the host
 ─────────────────                         ───────────────────
 mcp__oillamp__host_edit_file  ──MCP──▶   the path checks, the checker  ──▶  the file, replaced
 mcp__oillamp__host_bash       ──MCP──▶   the cwd checks, the checker,  ──▶  env -u <secrets>
                                          the person if the lamp asks         bash -c '<command>'
                               ◀────────  exit status, stdout, stderr
```

Running commands is the executing tier, and it is off unless the lamp's `oillamp.toml` says
otherwise with `host_access.commands`:

| `commands` | `host_bash` |
|---|---|
| `"off"` (the default) | not offered; a call is refused before the checker is asked |
| `"ask"` | the checker judges, then the person is asked before each command |
| `"checker"` | the checker's allow is enough |

A command is judged at the moment it is asked for, as a whole. Once allowed, it runs as the
user, and what it touches from its working directory is not checked again, file by file. That
is why the checker is shown the whole command, and why the setting is the user's.

## How the scenarios see it

The engine runs in this JVM against the simulated machine, but `env` and `bash` really run, so a
command really happens on this machine, in a temporary directory. A stand-in model service on
loopback plays the checker, and the scenarios speak MCP to the session's host-access socket as
pi does through `socat` in the sandbox.
''')
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class WorkingOnTheHostSpec extends Specification {

    static final String MODEL = 'azure/gpt-6.1-sol'
    /** The key the application gives the lamp, which oillamp keeps in `OILLAMP_MODEL_KEY`. */
    static final String KEY = 'sk-0123456789'
    static final String REQUIREMENTS = 'You may work in ~/project: read, change and build it. Nothing else.'
    static final String ALLOW_ADDITIVE = '{"decision":"allow","risk":"additive","reason":"An edit in the project."}'
    static final String ALLOW_EXECUTING = '{"decision":"allow","risk":"executing","reason":"A build in the project."}'

    @TempDir Path tmp
    @Subject ScenarioHost host
    StandInChecker checker
    final List<LampEvent> events = new CopyOnWriteArrayList<>()
    Lamp lamp
    Path project

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen', 'env', 'bash') }
        checker = new StandInChecker()
        project = Files.createDirectories(host.home.resolve('project'))
    }

    def cleanup() {
        lamp?.close()
        checker?.close()
    }

    def 'The agent edits a file on the host by replacing one exact piece of it'() {
        reportInfo """
            Let's consider an agent asked to fix a typo in a file in the user's project. It calls
            `host_edit_file` with the file's `path`, the `old_text` as it is in the file, and the
            `new_text` it becomes. Editing is in the additive tier, like writing a file, so it is
            open while `host_access.allow_additive` is true, and the checker judges each edit.

            The old text must occur in the file exactly once. When it does not occur at all, or
            occurs twice, the file stays as it was and the agent is told which: a replacement in
            the wrong one of two places would be worse than none, and the agent can give more of
            the lines around it.
        """
        given: 'a project file on the host with a typo, and a line that occurs twice'
            var readme = Files.writeString(project.resolve('README.md'), 'Teh build.\nSee below.\nSee below.\n')
            givenHostAccess(commands: 'off')
            checker.verdict = ALLOW_ADDITIVE
            start()

        when: 'the agent replaces the typo'
            var fixed = useTool('host_edit_file', [path: '~/project/README.md', old_text: 'Teh build.', new_text: 'The build.'])

        then: 'the file has the new text, and the rest of it as it was'
            !fixed.isError
            Files.readString(readme) == 'The build.\nSee below.\nSee below.\n'

        when: 'it names a text that is not there, and one that is there twice'
            var missing = useTool('host_edit_file', [path: '~/project/README.md', old_text: 'Teh build.', new_text: 'x'])
            var twice = useTool('host_edit_file', [path: '~/project/README.md', old_text: 'See below.', new_text: 'x'])

        then: 'both are refused with the reason, and the file did not change'
            missing.isError && McpClient.text(missing).contains('does not occur')
            twice.isError && McpClient.text(twice).contains('occurs more than once')
            Files.readString(readme) == 'The build.\nSee below.\nSee below.\n'
    }

    def 'With `commands = "checker"`, the agent runs a command in a project on the host and gets its exit status and output'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` sets `host_access.commands = "checker"`: the
            user lets the agent run commands on their computer, judged by the checker alone. The
            agent calls `host_bash` with the `command`, as it would type it into bash, and `cwd`,
            the directory it runs in. oillamp runs it there with `bash -c`, as the user, and hands
            back the exit status, then what the command printed to stdout and to stderr.

            A command that exits with a status other than 0 has still run. That is an answer, not
            a failure of the tool: the result says `exit status 3`, and MCP's `isError` stays
            false, so the agent reads it as it would read a failed build in its own terminal.

            Nobody can type into a command run this way, so its input is empty: a command that
            reads it, as `read` does here, gets nothing at once, rather than waiting for its
            timeout.
        """
        given: 'a lamp that lets the checker alone allow commands'
            givenHostAccess(commands: 'checker')
            checker.verdict = ALLOW_EXECUTING
            start()

        when: 'the agent runs a command in the project that reads its input, prints to both outputs and exits with 3'
            var ran = useTool('host_bash', [command: 'pwd; read line || echo "no input"; echo "something went wrong" >&2; exit 3',
                                           cwd: '~/project'])

        then: 'the agent is told the exit status and both outputs, and the command ran in the project'
            !ran.isError
            var text = McpClient.text(ran)
            text.startsWith('exit status 3\n')
            text.contains("stdout:\n${project.toRealPath()}\nno input\n")
            text.contains('stderr:\nsomething went wrong\n')

        and: 'no person was asked'
            !events.any { it instanceof LampEvent.HostAccessAsked }
    }

    def 'A command the checker refuses never runs'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` sets `host_access.commands = "checker"`, so
            that a command needs only the checker's allow. The checker is a separate model call
            that judges each request against the user's requirements before oillamp carries it
            out. Here it refuses a command. The session reports the refusal as a
            `HostAccessDecided` event, and oillamp does not start bash at all, so the file the
            command would have made does not exist. The agent gets the checker's reason as the
            tool's error: a result with MCP's `isError` set.
        """
        given: 'a lamp that lets the checker alone allow commands, and a checker that refuses'
            givenHostAccess(commands: 'checker')
            checker.verdict = '{"decision":"deny","risk":"executing","reason":"Deleting files is not building."}'
            start()

        when: 'the agent asks to run a command that would leave a file behind'
            var refused = useTool('host_bash', [command: 'touch ran-anyway', cwd: '~/project'])

        then: 'the session reports the checker\'s refusal'
            var decided = events.find { it instanceof LampEvent.HostAccessDecided } as LampEvent.HostAccessDecided
            decided.tool() == 'host_bash'
            !decided.verdict().allowed()

        and: 'the agent is told no, with the reason, and the command did not run'
            refused.isError
            McpClient.text(refused).contains('Deleting files is not building.')
            !Files.exists(project.resolve('ran-anyway'))
    }

    def 'With `commands = "ask"`, the person is asked before each command, and a no means it does not run'() {
        reportInfo """
            With `host_access.commands = "ask"` in the lamp's `oillamp.toml`, a command the checker
            allows still waits for the person. The session sends a `HostAccessAsked` event whose
            text holds the whole command and where it runs, and an application gives the person's
            decision with `Lamp.decidePermission`. A yes runs the command. A no, or no answer
            within 60 seconds, refuses it, and nothing runs. Each command is asked about on its
            own: a yes for one is not a yes for the next.
        """
        given: 'a lamp that asks the person about every command'
            givenHostAccess(commands: 'ask')
            checker.verdict = ALLOW_EXECUTING
            start()

        when: 'the agent asks to run a command, and the person says yes'
            var first = CompletableFuture.supplyAsync { useTool('host_bash', [command: 'touch first', cwd: '~/project']) }
            var asked = waitFor(LampEvent.HostAccessAsked) { true }
            lamp.decidePermission(asked.what(), true)

        then: 'the person was shown the whole command and where it runs, and it ran'
            asked.tool() == 'host_bash'
            asked.what() == 'run in ~/project: touch first'
            !first.get(30, TimeUnit.SECONDS).isError
            Files.exists(project.resolve('first'))

        when: 'it asks to run another, and the person says no'
            events.clear()
            var second = CompletableFuture.supplyAsync { useTool('host_bash', [command: 'touch second', cwd: '~/project']) }
            lamp.decidePermission(waitFor(LampEvent.HostAccessAsked) { true }.what(), false)

        then: 'the agent is told the person said no, and the command did not run'
            var refused = second.get(30, TimeUnit.SECONDS)
            refused.isError
            McpClient.text(refused).contains('said no')
            !Files.exists(project.resolve('second'))
    }

    def 'The person is asked one question at a time, and a second one meanwhile is declined at once'() {
        reportInfo """
            With `host_access.commands = "ask"`, every command the checker allows waits for the
            person. A running session shows the person one question at a time, as a
            `HostAccessAsked` event. A second command arriving while the first question is open
            is not left waiting out its minute for a person who never sees it: it is refused at
            once, with the reason, and its command does not run. Once the first question is
            answered, the next command is asked about as usual.
        """
        given: 'a lamp that asks the person about every command'
            givenHostAccess(commands: 'ask')
            checker.verdict = ALLOW_EXECUTING
            start()

        when: 'the agent asks to run a command, and while the person is asked, a second one'
            var first = CompletableFuture.supplyAsync { useTool('host_bash', [command: 'touch first', cwd: '~/project']) }
            var asked = waitFor(LampEvent.HostAccessAsked) { true }
            var second = useTool('host_bash', [command: 'touch second', cwd: '~/project'])

        then: 'the second is refused at once, because the person is already being asked'
            second.isError
            McpClient.text(second).contains('could not be asked')
            !Files.exists(project.resolve('second'))

        when: 'the person allows the first, and the agent asks for a third'
            lamp.decidePermission(asked.what(), true)
            var firstAnswer = first.get(30, TimeUnit.SECONDS)
            events.clear()
            var third = CompletableFuture.supplyAsync { useTool('host_bash', [command: 'touch third', cwd: '~/project']) }

        then: 'the first ran, and the third is asked about'
            !firstAnswer.isError
            Files.exists(project.resolve('first'))
            waitFor(LampEvent.HostAccessAsked) { it.what() == 'run in ~/project: touch third' }

        cleanup:
            events.findAll { it instanceof LampEvent.HostAccessAsked }.each {
                try { lamp.decidePermission(it.what(), false) } catch (Exception answeredAlready) { }
            }
            third?.get(30, TimeUnit.SECONDS)
    }

    def 'With `commands = "off"`, the agent has no tool to run a command, and a call is refused without asking the checker'() {
        reportInfo """
            `host_access.commands` is `"off"` unless the lamp's `oillamp.toml` says otherwise. Then
            the session does not list `host_bash` when pi asks for its tools with `tools/list`, so
            the model never sees it. A client that calls it anyway, by name, is refused by the
            setting's name, before the checker is asked: whether commands may run at all is the
            user's setting, not a model's opinion.
        """
        given: 'a lamp with host access on and commands off'
            givenHostAccess(commands: 'off')
            checker.verdict = ALLOW_EXECUTING
            start()

        when: 'pi lists the tools, and a client calls host_bash anyway'
            Map listed
            Map called
            McpClient.initialized(hostAccessSocket()).withCloseable { client ->
                listed = client.request('tools/list', [:])
                called = client.call('host_bash', [command: 'touch ran-anyway', cwd: '~/project'])
            }

        then: 'host_bash is not listed, the call is refused by the setting, and the checker was not asked'
            !(listed.result.tools as List<Map>)*.name.contains('host_bash')
            called.isError
            McpClient.text(called).contains('host_access.commands is "off"')
            checker.checked.isEmpty()
            !Files.exists(project.resolve('ran-anyway'))
    }

    def 'The checker is shown the whole command, and oillamp refuses a command too long to show whole without asking it'() {
        reportInfo """
            A command is judged as a whole, so the checker must see all of it. The other
            arguments the checker is shown, such as the text of a file being written, are cut
            after 2000 characters; a command never is. A command longer than 16 KiB is refused
            before the checker is asked, rather than shown in part: a command whose end the
            checker never read is not a command it judged. For the same reason the session's log
            of host requests, `.oillamp/logs/host-access-<session>.jsonl`, keeps a command whole,
            where it cuts other arguments after 200 characters: it is the record of what ran.
        """
        given: 'a lamp that lets the checker alone allow commands'
            givenHostAccess(commands: 'checker')
            checker.verdict = ALLOW_EXECUTING
            start()

        when: 'the agent runs a command of 3000 characters, and then one of more than 16 KiB'
            var long3000 = 'echo ' + 'a' * 2990 + ' # end'
            useTool('host_bash', [command: long3000, cwd: '~/project'])
            var tooLong = useTool('host_bash', [command: 'echo ' + 'b' * (16 * 1024), cwd: '~/project'])

        then: 'the checker was asked once, and saw the first command whole, to its last character'
            checker.checked.size() == 1
            var request = StandInChecker.judged(checker.checked.first()).request
            request.arguments.command == long3000

        and: 'it saw the tier, and the two minutes a command gets when the agent names no timeout'
            request.tier == 'executing'
            request.arguments.cwd == '~/project'
            request.arguments.timeout_seconds == '120'

        and: 'the session\'s log of host requests holds the whole command that ran'
            var log = Files.list(host.lampPath().resolve('.oillamp/logs')).withCloseable { it.toList() }
                    .find { it.fileName.toString().startsWith('host-access-') }
            var ranEntry = Files.readAllLines(log).collect { new groovy.json.JsonSlurper().parseText(it) as Map }
                    .find { it.outcome == 'done' }
            ranEntry.arguments.command == long3000

        and: 'the second was refused, saying why'
            tooLong.isError
            McpClient.text(tooLong).contains('longer than 16 KiB')
    }

    def 'A command that runs past its time is stopped, with everything it started'() {
        reportInfo """
            `host_bash` takes `timeout_seconds`, from 1 to 600, and two minutes when it is left
            out. When the time is up, oillamp stops the command and every process it started,
            not only bash itself: a build that started a server in the background must not leave
            the server running on the user's computer. The agent is told the command timed out,
            with what it printed so far.
        """
        given: 'a lamp that lets the checker alone allow commands'
            givenHostAccess(commands: 'checker')
            checker.verdict = ALLOW_EXECUTING
            start()

        when: 'the agent runs a command that starts a process in the background and waits for it, with a timeout of one second'
            var ran = useTool('host_bash', [command: 'sleep 300 & echo $! > child.pid; echo started; wait',
                                         cwd: '~/project', timeout_seconds: 1])

        then: 'the agent is told it timed out, with what it printed'
            !ran.isError
            McpClient.text(ran).startsWith('timed out: it was given 1 s')
            McpClient.text(ran).contains('started')

        and: 'the process it started in the background was stopped too'
            long child = Long.parseLong(Files.readString(project.resolve('child.pid')).strip())
            eventually { !ProcessHandle.of(child).map { it.alive }.orElse(false) }
    }

    def 'When pi cancels a call while the checker is still thinking, the command never runs'() {
        reportInfo """
            An MCP client that gives up on a call it made, as pi does when a tool call is
            aborted, tells the server so with MCP's `notifications/cancelled`, naming the call by
            its id. oillamp
            checks for that before it carries a call out: a call cancelled while the checker or
            the person was still deciding is refused, and its command never starts, even when the
            checker allows it a moment later. The cancelled call is not answered, as MCP asks.
        """
        given: 'a lamp that lets the checker alone allow commands, and a checker that takes two seconds'
            givenHostAccess(commands: 'checker')
            checker.verdict = ALLOW_EXECUTING
            checker.thinking = Duration.ofSeconds(2)
            start()
            var client = McpClient.initialized(hostAccessSocket())

        when: 'the agent asks to run a command, and pi cancels the call while the checker thinks'
            int id = client.send('tools/call', [name: 'host_bash', arguments: [command: 'touch too-late', cwd: '~/project']])
            waitFor(LampEvent.HostAccessRequested) { it.tool() == 'host_bash' }
            client.notify('notifications/cancelled', [requestId: id, reason: 'the user pressed Escape'])

        then: 'the checker allowed it, and oillamp refused it all the same, as cancelled'
            waitFor(LampEvent.HostAccessDecided) { it.verdict().allowed() }
            waitFor(LampEvent.HostAccessRefused) { it.why().contains('withdrew') }

        and: 'the command never ran, and the cancelled call was not answered'
            !Files.exists(project.resolve('too-late'))
            !client.answered(id)

        cleanup:
            client?.close()
    }

    def 'A command on the host does not get the model key oillamp holds'() {
        reportInfo """
            A command runs with oillamp's own environment, as one typed into the user's terminal
            would, so that `PATH` and the user's other settings are there. oillamp's own secrets
            are not: the variable holding the model key, here `OILLAMP_MODEL_KEY`, in which an
            application's key reaches the engine, is removed with `env -u` before bash starts. So
            are the variables an application names for its requirements and instructions. The
            checker is the only part of oillamp that uses the key, and the agent is not the
            checker.

            The JVM these scenarios run in has `OILLAMP_MODEL_KEY` set to the same key, by the
            build, so a command that inherited the variable would print it.
        """
        given: 'oillamp\'s environment holds the model key, and the lamp lets the checker alone allow commands'
            assert System.getenv('OILLAMP_MODEL_KEY') == KEY
            givenHostAccess(commands: 'checker')
            checker.verdict = ALLOW_EXECUTING
            start()

        when: 'the agent prints the variable, and the home directory'
            var ran = useTool('host_bash', [command: 'printenv OILLAMP_MODEL_KEY; echo "printenv: $?"; echo "home: $HOME"', cwd: '~/project'])

        then: 'the variable is not there, and the rest of the environment is'
            var text = McpClient.text(ran)
            text.contains('printenv: 1')
            !text.contains(KEY)
            text.contains("home: ${System.getenv('HOME')}")
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private void givenHostAccess(Map options) {
        host.givenConfig(host.lampPath(), """
            schema_version = 1

            [host_access]
            enabled = true
            roots = ["${project}"]
            commands = "${options.commands}"
            requirements = '''
            ${REQUIREMENTS}
            '''
        """.stripIndent())
    }

    private void start() {
        lamp = Lamp.at(host.lampPath()).onEvent { events << it }
                   .modelService(checker.address())
                   .model(MODEL)
                   .modelKey(KEY)
                   .launchedBy(host.launcher).start()
        assert lamp.awaitRunning(Duration.ofSeconds(30))
    }

    /** One tool call, as pi makes it: MCP, on a connection that introduced itself first. */
    private Map useTool(String tool, Map arguments) {
        McpClient.initialized(hostAccessSocket()).withCloseable { it.call(tool, arguments) }
    }

    /** The host-access socket of the running lamp, under the short runtime directory. */
    private Path hostAccessSocket() {
        Files.list(host.runtime.resolve('oillamp')).withCloseable { lamps ->
            lamps.map { it.resolve('sockets/host/host-access.sock') }.filter { Files.exists(it) }.findFirst().orElseThrow() }
    }

    private <T extends LampEvent> T waitFor(Class<T> type, Closure<Boolean> which) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < until) {
            var found = events.find { type.isInstance(it) && which(it) }
            if (found != null) return (T) found
            Thread.sleep(20)
        }
        throw new AssertionError("no ${type.simpleName} within 30 seconds; heard ${events.collect { it.toString().take(200) }}")
    }

    private static void eventually(Closure<Boolean> condition) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            if (condition()) return
            Thread.sleep(50)
        }
        assert condition()
    }
}
