package oillamp

import dev.lamp.LampExitStatus
import dev.lamp.Lamp
import dev.lamp.LampEvent
import spock.lang.Narrative
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Subject
import spock.lang.Tag
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

import static oillamp.RealLamps.*

@Title('Running a lamp for a program built on oillamp')
@Narrative('''
The whole life of a lamp held by a program built on oillamp's Java API, such as a game that hosts
an agent, on this machine with real podman.

Nothing is simulated. `Lamp` starts oillamp's engine as a separate Java process from this JVM's
own classpath, as such a program would. The engine checks the host, builds the sandbox image if
needed, starts the container, and runs the egress proxy and the SSH relay. Every command goes from
this JVM over real ssh, through the relay, into the container.

This is a spike. Its scenarios follow one lamp from creation to deletion, in order: each relies on
what the one before it left behind, as such a program would.
''')
@Subject(Lamp)
@Tag('spike')
@Stepwise
@Requires({ Spike.containerNetworkWorks() })
class Running_A_Lamp_For_An_Application_Spec extends Specification {

    @Shared Path directory = newLampPath('application')
    @Shared Lamp lamp
    @Shared List<LampEvent> heard = new CopyOnWriteArrayList<>()
    @Shared String firstAgentId

    def cleanupSpec() {
        lamp?.close()
        remove(directory)
    }

    def 'A program built on oillamp starts a lamp from nothing with `Lamp.start`, and hears it come up step by step'() {
        reportInfo """
            Let's consider a program built on oillamp's Java API, such as a game that hosts an
            agent. It names a directory that does not exist yet, and asks for a lamp there with
            `Lamp.at(dir).start()`. Oillamp does everything a first `oillamp at` does: it checks
            the host, creates the lamp, builds the sandbox image if this version of oillamp has none
            yet, which can take many minutes, starts the container and opens the session.

            The program hears of each step as it happens, in the same events a terminal shows, so
            that it can show its user progress rather than a screen that does not change. A single
            event tells it that the session is usable, and how to reach the sandbox.
        """
        when: '`Lamp.at(dir).start()` is called for a directory that does not exist'
            lamp = Lamp.at(directory).onEvent { heard << it }.start()

        then: 'the session comes up'
            lamp.awaitRunning(FIRST_START)

        and: 'the program heard each stage succeed, in order: host, lamp, image, session'
            var areas = heard.findAll { it instanceof LampEvent.Ok }*.area().unique()
            areas.containsAll(['host', 'lamp', 'image', 'session'])
            ['host', 'lamp', 'image', 'session'].collect { areas.indexOf(it) } ==
                    ['host', 'lamp', 'image', 'session'].collect { areas.indexOf(it) }.sort()

        and: 'it heard that the image is ready, and that the sandbox is running, in plain words'
            heard.any { it instanceof LampEvent.Ok && it.text().startsWith('sandbox image ready') }
            heard.any { it instanceof LampEvent.Ok && it.text().startsWith('sandbox running') }

        and: 'no problem was reported on the way'
            heard.findAll { it instanceof LampEvent.Failure }.isEmpty()

        and: 'the lamp exists on disk, with its identity and the agent\'s home'
            Files.isRegularFile(directory.resolve('oillamp.toml'))
            identity(directory).schemaVersion == 1
            Files.isDirectory(agentHome(directory).resolve('workspace'))
            Files.isRegularFile(directory.resolve('.oillamp/session.json'))

        and: 'podman runs its container, labelled with the lamp it belongs to'
            var container = containerOf(directory)
            containerExists(container)
            Spike.run('podman', 'container', 'inspect', '--format', '{{index .Config.Labels "oillamp.lamp"}}', container)
                 .out.strip() == directory.toRealPath().toString()

        and: 'no window opened on this desktop'
            heard.findAll { it instanceof LampEvent.WindowOpened }.isEmpty()

        cleanup:
            firstAgentId = agentId(directory)
    }

    def 'Commands run with `Lamp.exec` run in the sandbox as the agent, in its home, with the same environment as its shell'() {
        reportInfo """
            A program built on oillamp runs programs in the sandbox, such as an agent harness, with
            `Lamp.exec`. They must run as the agent would run them by hand: as the unprivileged
            `agent` user, in its home directory, with the proxy, display and tool settings every
            shell in the sandbox gets. A harness started without the proxy setting, for example,
            would find no internet, and conclude that its model service is down.
        """
        expect: 'the agent user, in its home'
            with(inSandbox(lamp, 'sh', '-c', 'id -un; id -u; pwd')) {
                ok
                out.readLines() == ['agent', '1000', '/home/agent']
            }

        and: 'with the proxy, the desktop and the agent\'s own tool directories'
            with(inSandbox(lamp, 'sh', '-c', 'echo "$HTTPS_PROXY|$WAYLAND_DISPLAY|$DISPLAY"; echo "$PATH"')) {
                ok
                out.readLines()[0] == 'http://127.0.0.1:3128|/run/lamp/wayland-1|:0'
                out.readLines()[1].contains('/home/agent/.local/bin')
            }

        and: 'arguments arrive exactly as the program gave them: spaces, quotes and dollars included'
            with(inSandbox(lamp, 'printf', '%s\\n', 'two words', 'it\'s', '$HOME', '"quoted"', '')) {
                ok
                out.readLines() == ['two words', 'it\'s', '$HOME', '"quoted"', '']
            }

        and: 'a failing command reports its own exit code and error output, separately'
            with(inSandbox(lamp, 'sh', '-c', 'echo to-out; echo to-err >&2; exit 7')) {
                exit == 7
                out.strip() == 'to-out'
                err.strip() == 'to-err'
            }
    }

    def 'A program built on oillamp exchanges messages with a process in the sandbox, one at a time, over its standard input and output'() {
        reportInfo """
            This is how a program built on oillamp will talk to an agent harness: it starts the
            harness with `Lamp.exec`, and exchanges messages over the harness's standard input and
            output, each one answered before the next is sent, as protocols such as ACP, the Agent
            Client Protocol, do.

            A pipe that delivered its data only when closed, or a relay that held bytes back until
            a buffer filled, would make that impossible, and would still look fine for commands
            that run once. So each message here must be answered while the process is still
            running and waiting for more. The process is a few lines of Python that answer JSON
            with JSON.
        """
        given: 'a Python process that answers each JSON message with one of its own'
            var program = '''
import json, sys
for line in sys.stdin:
    message = json.loads(line)
    print(json.dumps({"id": message["id"], "heard": message["say"].upper()}), flush=True)
'''
            Process conversation = lamp.exec('python3', '-u', '-c', program)
            var toProgram = new PrintWriter(new OutputStreamWriter(conversation.outputStream, 'UTF-8'), true)
            var fromProgram = new BufferedReader(new InputStreamReader(conversation.inputStream, 'UTF-8'))

        when: 'three messages are sent, each only after the answer to the one before'
            var answers = []
            ['a dragon', 'wakes up', 'hungry'].eachWithIndex { String text, int id ->
                toProgram.println("""{"id": $id, "say": "$text"}""")
                answers << fromProgram.readLine()
            }

        then: 'each answer came back in turn, while the program was still running'
            answers == ['{"id": 0, "heard": "A DRAGON"}',
                        '{"id": 1, "heard": "WAKES UP"}',
                        '{"id": 2, "heard": "HUNGRY"}']
            conversation.alive

        when: 'the program ends the conversation by closing the process\'s input'
            toProgram.close()

        then: 'the process finishes on its own'
            conversation.waitFor(30, TimeUnit.SECONDS)
            conversation.exitValue() == 0
    }

    def 'Eight megabytes of random bytes sent into the sandbox with `Lamp.exec` come back unchanged'() {
        reportInfo """
            Files, images and model output will go through the same pipes. Every byte passes
            through ssh, the supervisor's relay and a socket in the container, and any of them
            could mangle binary data or cut a large transfer short. Eight megabytes of random
            bytes go in, the sandbox hashes them, sends them back, and both sides must agree.
        """
        given:
            var bytes = new byte[8 * 1024 * 1024]
            new Random(42).nextBytes(bytes)
            var expected = MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()

        when: 'the bytes are sent to `tee`, which hashes them and echoes them back'
            Process echo = lamp.exec('sh', '-c', 'tee /tmp/received | cat; sha256sum /tmp/received >&2')
            var sender = Thread.start { echo.outputStream.withCloseable { it.write(bytes) } }
            var returned = echo.inputStream.bytes
            var hashedInside = echo.errorStream.text
            sender.join()
            echo.waitFor(1, TimeUnit.MINUTES)

        then: 'the sandbox received exactly what was sent'
            hashedInside.startsWith(expected)

        and: 'and what came back is identical too'
            returned.length == bytes.length
            MessageDigest.getInstance('SHA-256').digest(returned).encodeHex().toString() == expected
    }

    def 'What the agent writes in its home appears in the lamp on the host, owned by the user'() {
        reportInfo """
            The agent's home is the one place it shares with the host, on purpose: the program
            built on oillamp, here a game, and the user can read what the agent produced, a
            campaign's notes or a generated map, without copying anything out of a container. The
            files are owned by the user who runs oillamp, so they can be edited, moved and
            committed without changing ownership.
        """
        when: 'the agent writes notes into `~/workspace/notes.md`'
            var wrote = feeding(lamp, 'The old chapel is haunted.\n',
                                'sh', '-c', 'cat > ~/workspace/notes.md')

        then: 'the file is in the lamp on the host, owned by the user who runs oillamp'
            wrote.ok
            var onHost = agentHome(directory).resolve('workspace/notes.md')
            Files.readString(onHost) == 'The old chapel is haunted.\n'
            Files.getOwner(onHost).name == System.getProperty('user.name')
    }

    def 'The sandbox reaches the internet through the egress proxy, which refuses the private network'() {
        reportInfo """
            The agent may use the internet: to install packages, read documentation, or reach its
            model service. It may not reach the host's own network: the router, a database on the
            local network, a service on this machine. Every connection goes through oillamp's
            proxy, which decides by where a name really points.

            A refusal is not a silent timeout. The agent gets an answer naming the rule, so that it
            stops retrying, and the program that started the lamp gets an event, so that it can
            show what was blocked. The decision is also written to the session's network log.
        """
        when: 'the agent fetches a public web page'
            var publicPage = inSandbox(lamp, 'curl', '-s', '-o', '/dev/null', '-w', '%{http_code}',
                                       '--max-time', '30', 'https://example.com/')

        then: 'it arrives'
            publicPage.out == '200'

        when: 'it tries an address on the local network'
            var sessionId = heard.find { it instanceof LampEvent.SessionOpened }.session()
            var privateAddress = inSandbox(lamp, 'curl', '-s', '-w', '\n%{http_code}', '--max-time', '30',
                                           'http://192.168.1.1/')

        then: 'the proxy refuses it, and says which rule did'
            privateAddress.out.readLines().last() == '403'
            privateAddress.out.contains('denied by rule "block private, internal and loopback ranges"')

        and: 'the program heard of the refusal'
            eventually(Duration.ofSeconds(10)) {
                heard.any { it instanceof LampEvent.Info && it.area() == 'network' && it.text().contains('192.168.1.1') }
            }

        and: 'and the network log recorded it, next to the page that was allowed'
            var log = directory.resolve(".oillamp/logs/network-${sessionId}.jsonl")
            eventually(Duration.ofSeconds(10)) { Files.exists(log) && Files.readString(log).contains('192.168.1.1') }
            Files.readAllLines(log).any { it.contains('"host":"192.168.1.1"') && it.contains('"decision":"deny"') }
            Files.readAllLines(log).any { it.contains('"host":"example.com"') && it.contains('"decision":"allow"') }
    }

    def 'There is no way around the proxy: the sandbox has no network of its own'() {
        reportInfo """
            A proxy only protects the host if nothing can go around it. The sandbox has no network
            interface except loopback, and no name service: a program that ignores the proxy
            settings cannot even look up a name, let alone connect.
        """
        expect: 'no network interface besides loopback'
            inSandbox(lamp, 'sh', '-c', 'ls /sys/class/net').out.readLines() == ['lo']

        and: 'no name can be looked up directly'
            !inSandbox(lamp, 'getent', 'hosts', 'example.com').ok

        and: 'a request told not to use the proxy fails'
            !inSandbox(lamp, 'curl', '-s', '--noproxy', '*', '--max-time', '10', 'https://example.com/').ok
    }

    def 'The agent cannot see the host, nor the rules that restrict it'() {
        reportInfo """
            The agent runs as the user's own uid inside the container, so what keeps it away from
            the user's files is what the container can see, not file permissions. It sees its
            home and nothing else of the host: not the user's home directory, and not the lamp's
            configuration, which holds the network rules. An agent that could edit those rules
            could lift its own restrictions.
        """
        expect: 'the user\'s real home directory is not there'
            !inSandbox(lamp, 'test', '-e', System.getProperty('user.home')).ok

        and: 'the lamp directory on the host is not there either'
            !inSandbox(lamp, 'test', '-e', directory.toString()).ok

        and: 'the configuration file is nowhere in the sandbox'
            inSandbox(lamp, 'sh', '-c', 'find / -path /proc -prune -o -name oillamp.toml -print 2>/dev/null')
                    .out.strip() == ''

        and: 'the system itself cannot be changed'
            !inSandbox(lamp, 'touch', '/usr/local/bin/planted').ok
    }

    def '`oillamp status` and `oillamp list` see the lamp that a program built on oillamp started'() {
        reportInfo """
            When something goes wrong in a program built on oillamp, such as a game, the person
            investigating opens a terminal. They must be able to ask the same questions of a lamp
            that program started as of one they started themselves: whether it runs, and in which
            container.
        """
        when: '`oillamp status` is run for the lamp'
            var status = oillamp('status', directory.toString())

        then: 'it says the lamp is "running, for the application that started it", and names its container'
            status.status() == LampExitStatus.SUCCESS
            var answer = status.events().find { it instanceof LampEvent.Answer }.text()
            answer.contains('running, for the application that started it')
            answer.contains(containerOf(directory))

        and: 'the lamp is listed with the other sandboxes on this machine'
            oillamp('list').events().find { it instanceof LampEvent.Answer }.text()
                           .contains(containerOf(directory))
    }

    def '`Lamp.close` ends the session, even with a command still running, and leaves nothing behind'() {
        reportInfo """
            A program built on oillamp that exits must be able to trust that the sandbox exits with
            it, even when the agent is in the middle of something. `Lamp.close` stops the
            container, which ends every process in it, and removes everything the session created:
            the container, the session file, the sockets. It returns only once that is done.
        """
        given: 'a command that would run for ten minutes'
            Process busy = lamp.exec('sleep', '600')
            var container = containerOf(directory)

        when: '`Lamp.close` is called'
            var closing = Instant.now()
            lamp.close()
            var took = Duration.between(closing, Instant.now())

        then: 'the engine ended with success, because the program asked it to stop'
            lamp.exitStatus() == Optional.of(LampExitStatus.SUCCESS)
            heard.any { it instanceof LampEvent.Summary
                        && it.lines().toList().any { line -> line.contains('asked to stop by the application') } }

        and: 'well within the time the engine allows for stopping a container'
            took < Duration.ofSeconds(90)

        and: 'the running command ended with it'
            busy.waitFor(30, TimeUnit.SECONDS)

        and: 'the container is gone, and so is the session'
            !containerExists(container)
            !Files.exists(directory.resolve('.oillamp/session.json'))
            oillamp('status', directory.toString()).reported('OIL-SESSION-001')

        and: 'the agent\'s work is still there'
            Files.exists(agentHome(directory).resolve('workspace/notes.md'))
    }

    def 'Opening the lamp again brings back the same agent and everything it kept'() {
        reportInfo """
            Let's consider a game built on oillamp's Java API whose campaign continues next week.
            The lamp is the same directory, so opening it again gives the same agent, with its
            home as it left it. The image already exists, so this start takes seconds rather than
            minutes.
        """
        given: 'the events heard so far are forgotten'
            heard.clear()

        when: '`Lamp.at(dir).start()` is called for the same directory'
            lamp = Lamp.at(directory).onEvent { heard << it }.start()

        then: 'the session comes up'
            lamp.awaitRunning(LATER_START)

        and: 'the same agent'
            agentId(directory) == firstAgentId

        and: 'with the notes it wrote last time'
            inSandbox(lamp, 'cat', 'workspace/notes.md').out == 'The old chapel is haunted.\n'

        cleanup:
            lamp?.close()
    }

    def '`oillamp remove` deletes the lamp, including the files the user cannot delete'() {
        reportInfo """
            Some of a lamp's files belong to the sandbox's infrastructure user, which is not the
            user on the host, so `rm -rf` fails on them. A program built on oillamp that deletes a
            lamp, as a game deletes a campaign, runs `oillamp remove <dir> --yes`, which can delete
            them.
        """
        when: '`oillamp remove <dir> --yes` is run'
            var removed = oillamp('remove', directory.toString(), '--yes')

        then: 'it succeeds, and the directory is gone'
            removed.status() == LampExitStatus.SUCCESS
            !Files.exists(directory)
    }
}
