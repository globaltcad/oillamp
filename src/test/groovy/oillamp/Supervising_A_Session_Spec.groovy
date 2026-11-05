package oillamp

import dev.lamp.LampExitStatus
import dev.lamp.LampEvent
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

@Title('Supervising a session')
@Narrative('''
A whole session, run by the supervisor: the `oillamp at` process that starts the sandbox, opens
the windows, watches the sandbox while the session runs, and removes everything when it ends.

These scenarios run a whole session. The container is simulated, but nothing else is: the relays
bind real Unix sockets, the simulated terminal really connects through the primary relay and then
closes, and the simulated user ends the session through its real control socket, as
`oillamp stop` does. The supervisor's whole job happens between processes, so a test that replaced
the sockets with stand-ins would test only the stand-ins.
''')
@Subject(OilLamp)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class Supervising_A_Session_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'A session opens two new windows, and leaves the terminal oillamp was started from alone'() {
        reportInfo """
            The terminal someone typed `oillamp at` into goes on printing what the session does,
            so it is where they look to find out what happened. A session that took that terminal
            over for the sandbox's shell would hide what they want to read. The user asked for
            this explicitly.

            So there are two windows, both new: one with the shell, one with the desktop. Neither
            of them is the terminal oillamp was started from.
        """
        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session ran, and ended with success'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'two windows were opened, and oillamp said which was which'
            var windows = outcome.events().findAll { it instanceof LampEvent.WindowOpened }
            windows.size() == 2
            windows.any { it.what().contains('terminal') }
            windows.any { it.what().contains('viewer') }

        and: 'the shell window is a new window of the desktop\'s terminal program, ptyxis'
            var terminal = windows.find { it.what().contains('terminal') }.argv().toList()
            terminal.first() == 'ptyxis'
            terminal.contains('--new-window')

        and: 'it reaches the sandbox through the socket kept for that one window, and starts in `~/workspace`'
            terminal.any { it.contains('UNIX-CONNECT:') && it.contains('ssh-primary.sock') }
            terminal.any { it.contains('cd ~/workspace') }

        and: 'the viewer is pointed at the VNC socket, with no TCP port anywhere'
            var viewer = windows.find { it.what().contains('viewer') }.argv().toList()
            viewer.first() == 'vncviewer'
            viewer.last().endsWith('/sockets/infra/vnc.sock')
    }

    def 'Closing the shell window leaves the session running; it ends when the user says so'() {
        reportInfo """
            The shell window is one way into the session, not the session itself. Someone who
            closes it may want a new shell, or none for a while, and should not lose the sandbox
            for it. The session ends on Ctrl-C in the terminal oillamp was started from, when that
            terminal closes, or on `oillamp stop`. When it ends, everything the session created
            must go, the container, the sockets and the session file, or the next `oillamp at` on
            this lamp finds a name taken and a lock held, and cannot say why.

            The simulated shell window connects through the real relay and closes; the simulated
            user then runs `oillamp stop`. Nothing about the shutdown is simulated.
        """
        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'oillamp exits with success, since this is how a session is meant to end'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the window closing was reported, with how to open another shell, and ended nothing'
            var events = outcome.events().toList()
            var closed = events.findIndexOf { it instanceof LampEvent.Info
                                              && it.text().contains('the shell window closed') }
            closed >= 0
            events[closed].text().contains('the session keeps running')
            events[closed].text().contains('oillamp shell ')

        and: 'the session ended only when it was asked to, and says so in the closing summary'
            var summaryAt = events.findIndexOf { it instanceof LampEvent.Summary
                                                 && it.title().startsWith('session ') }
            summaryAt > closed
            events[summaryAt].lines().toList().any { it.contains('asked to stop by') }

        and: 'the container was asked to stop, with time to finalise the recording, and is gone'
            outcome.console().contains('stopping the sandbox — up to 15s')
            outcome.console().contains('(removed)')
            var sessionFile = host.lampPath().resolve('.oillamp/session.json')
            !java.nio.file.Files.exists(sessionFile)
    }

    def 'The summary "your session is up" says what runs, where it is, and every way to end it'() {
        reportInfo """
            Two windows have just appeared, and a container runs that the user cannot see into.
            The summary "your session is up" says what is going on, including two things that are
            easy to miss: that the agent can see exactly one directory of the lamp, and that there
            are three ways to end the session.
        """
        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'it printed the summary "your session is up"'
            var briefing = outcome.events().find { it instanceof LampEvent.Summary
                                                   && it.title() == 'your session is up' }
            briefing != null

        and: 'the summary gives the desktop\'s size, and the commands that open more shell and viewer windows'
            var lines = briefing.lines().toList().join('\n')
            lines.contains('1920x1080')
            lines.contains('oillamp view ')
            lines.contains('oillamp shell ')

        and: 'it names the one directory of the lamp the agent can see, and says it sees nothing else'
            lines.contains('agent-lamp-')
            lines.contains('and nothing else of this lamp')

        and: 'it says that this terminal goes on reporting, and the ways to end the session'
            lines.contains('keeps reporting the sandbox')
            lines.contains('Ctrl-C')
            lines.contains('oillamp stop ')
    }

    def 'When the desktop viewer will not open, oillamp warns, and the session goes on without it'() {
        reportInfo """
            The viewer is the window, `vncviewer`, that shows the sandbox's desktop. Its life does
            not depend on the session's, and this is why: a session with no view of the desktop
            still works. The shell works, the agent runs, the recording is made. Ending the session
            would throw that away over a window the user can open again with `oillamp view`.
        """
        given: 'a machine whose viewer refuses to start'
            host.machine { it.windowRefusing('vncviewer') }

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session still ran, and still ended with success'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'oillamp warned the user, with the command that failed'
            outcome.reported('OIL-VIEW-001')
            outcome.warnings().any { it.code().value() == 'OIL-VIEW-001' }

        and: 'the shell window was opened all the same'
            outcome.events().findAll { it instanceof LampEvent.WindowOpened }
                   .any { it.what().contains('terminal') }
    }

    def 'When the shell\'s terminal window will not open, oillamp ends the session'() {
        reportInfo """
            Oillamp opens the agent's shell in a new window of a terminal program, here ptyxis. A
            terminal that cannot open almost always means that the terminal setting is wrong, and
            it would be wrong in every session. So oillamp ends the session and reports
            `OIL-TERM-003`, with the exact command that would not start, so that the user fixes
            the setting now, rather than work around a missing window in every session.
        """
        given: 'a machine whose terminal emulator refuses to start'
            host.machine { it.windowRefusing('ptyxis') }

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session failed, with `SESSION_FAILED` rather than a general error'
            outcome.status() == LampExitStatus.SESSION_FAILED

        and: 'the reason names the terminal, and quotes what was run'
            outcome.reported('OIL-TERM-003')
            outcome.errors().any { problem ->
                problem.evidence().any { it.toString().contains('ptyxis') }
            }

        and: 'the sandbox was not left behind'
            var sessionFile = host.lampPath().resolve('.oillamp/session.json')
            !java.nio.file.Files.exists(sessionFile)
    }

    def 'Oillamp gives up on a terminal window that opens but never connects, and frees the lamp'() {
        reportInfo """
            Let's consider a terminal program that starts, and shows its window, while the ssh
            inside it fails on something of its own. Nothing is wrong with the sandbox or with
            oillamp, and the session would wait for a shell that never comes, holding the lamp's
            lock all the while, so that no other session could start on the lamp.

            So oillamp waits at most `timeouts.terminal_connect_seconds`, and then reports
            `OIL-TERM-002`. That rule needs time to pass, so this scenario runs on a clock that
            moves.
        """
        given: 'a terminal that opens and never reaches the sandbox, and a terminal timeout of one second'
            host.machine { it.terminalThatNeverConnects().clockRuns()
                                .windowsStayOpenFor(Duration.ofSeconds(10)) }
            host.givenConfig(host.lampPath(), """
                schema_version = 1
                [timeouts]
                terminal_connect_seconds = 2
            """.stripIndent())

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'oillamp gives up, rather than wait, and says what it waited for'
            outcome.status() == LampExitStatus.SESSION_FAILED
            outcome.reported('OIL-TERM-002')

        and: 'the lamp is free again: a second `oillamp at` is not refused as busy'
            var second = host.oillamp.run('at', host.lampPath().toString())
            second.status() != LampExitStatus.LAMP_BUSY
    }

    def '`oillamp at --no-viewer` opens the shell window and nothing else'() {
        reportInfo """
            For someone who runs an agent on a machine they are not sitting at, or who does not
            want a desktop window in the way. The session is otherwise the same: the viewer is how
            the user watches, not part of how the session works.
        """
        given: 'windows that stay open for less than half a second'
            host.machine { it.windowsStayOpenFor(Duration.ofMillis(400)) }

        when: '`oillamp at --no-viewer` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--no-viewer')

        then: 'the session ended with success, and opened one window: the shell\'s'
            outcome.status() == LampExitStatus.SUCCESS
            var windows = outcome.events().findAll { it instanceof LampEvent.WindowOpened }
            windows.size() == 1
            windows.first().what().contains('terminal')
    }

    def 'The terminal oillamp was started from goes on reporting the health of the sandbox'() {
        reportInfo """
            Checking the sockets only at the start is not enough: a desktop that died an hour into
            a session would look fine to any check that does less than connect. The user asked
            for this too.

            So while the session runs, it keeps connecting to all three sockets, the desktop, the
            shell and the egress proxy, and says so in the terminal it was started from.
            Connecting is the only check that tells a server from a file with the right name,
            which is also why oillamp reports `OIL-SANDBOX-004` at the start when a socket file is
            there, but nothing answers on it.
        """
        given: 'a session that stays up long enough to report on itself'
            host.machine { it.windowsStayOpenFor(Duration.ofSeconds(4)) }

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session ended with success'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'it went on checking that all three sockets still answer'
            outcome.console().contains('desktop, shell and network all still answering')

        and: 'and reported how long the session had been up, and where to ask for more'
            outcome.console().contains('oillamp status ')
    }

    def 'A sandbox that dies during a session ends it, says so, and leaves nothing behind'() {
        reportInfo """
            The container's first process stops the sandbox when a part the human depends on
            dies: the compositor, the desktop server, the recorder or a network bridge. Otherwise
            the user would go on typing into a shell attached to a desktop nobody can see. The
            supervisor notices within seconds, ends the session with its own exit status, names
            the sandbox's exit code, and still removes everything the session made.
        """
        given: 'a sandbox that exits a second after it started, while the shell is still open'
            host.machine {
                it.windowsStayOpenFor(Duration.ofSeconds(60))
                  .sandboxDiesAfter(Duration.ofSeconds(1))
            }

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session ends as failed, not as a normal stop'
            outcome.status() == LampExitStatus.SESSION_FAILED

        and: 'the summary says why, with the sandbox\'s own exit code'
            outcome.console().contains('the sandbox container exited (code 70)')

        and: 'the container and the session\'s files were still removed'
            outcome.console().contains('(removed)')
            !java.nio.file.Files.exists(host.lampPath().resolve('.oillamp/session.json'))
    }

    def 'A bug that breaks the session\'s event loop still ends the session, and removes the sandbox'() {
        reportInfo """
            One thread of oillamp's, the event loop, decides when a session ends. If a bug made it
            fail, nothing would stop the sandbox, and oillamp would then wait many minutes on its
            way out for a loop that was gone. So a failing event loop is reported as a bug, and
            the session is shut down at once, as for any other ending.

            Here the bug is in a listener, code that receives oillamp's events as they happen, as
            a program built on oillamp listens to the session. The listener fails the moment the
            session is up, and it is called on the event loop's thread.
        """
        given: 'a listener that fails once, when the session is up'
            var failed = new java.util.concurrent.atomic.AtomicBoolean()
            var oillamp = host.oillamp.observedBy { event ->
                if (event instanceof LampEvent.SessionStateChanged && event.status().state() == 'running'
                        && failed.compareAndSet(false, true))
                    throw new IllegalStateException('the listener broke')
            }

        when: '`oillamp at` runs a session with that listener'
            var outcome = oillamp.run('at', host.lampPath().toString())

        then: 'the bug is reported as one'
            outcome.status() == LampExitStatus.ERROR
            outcome.reported('OIL-INTERNAL-001')
            outcome.console().contains('the listener broke')

        and: 'the session ended the way every session does, and says why'
            outcome.console().contains('oillamp itself failed')
            outcome.console().contains('(removed)')
            outcome.events().any { it instanceof LampEvent.SessionStateChanged && it.status().state() == 'stopped' }
            !java.nio.file.Files.exists(host.lampPath().resolve('.oillamp/session.json'))
    }

    def 'Oillamp refuses a second connection to the shell window\'s socket, and the session goes on'() {
        reportInfo """
            The socket the shell window connects through accepts exactly one connection per
            session, because that connection is what tells oillamp the user's shell is up. The
            socket is outside anything the sandbox can see, so a second connection is a mistake on
            the host, such as a copied ssh command. Oillamp refuses it with the warning
            `OIL-SSH-002`, which points to `oillamp shell`, and the session goes on.
        """
        given: 'a session whose shell window stays open'
            host.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
            var reported = new java.util.concurrent.CopyOnWriteArrayList<LampEvent>()
            var oillamp = host.oillamp.observedBy { reported.add(it) }
            var session = Thread.start { oillamp.run('at', host.lampPath().toString()) }
            waitUntil { reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' } }

        when: 'something else connects to the shell window\'s socket'
            var primary = java.nio.file.Files.list(host.runtime.resolve('oillamp')).toList().first()
                                                 .resolve('run/ssh-primary.sock')
            java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(primary)).close()

        then: 'oillamp refuses it with a warning that names `oillamp shell`'
            waitUntil { reported.any { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-SSH-002' } }
            reported.find { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-SSH-002' }
                    .problem().toString().contains('oillamp shell')

        and: 'the session is still running'
            session.alive

        cleanup:
            host.oillamp.run('stop', host.lampPath().toString())
            session?.join(20_000)
    }

    def 'oillamp publishes opencode\'s port as a link on this machine, and a connection to it reaches the web server in the sandbox'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` has no `[web]` section, so that it publishes
            opencode's port 4096. Inside the sandbox, a bridge serves that port on the Unix socket
            `web-opencode.sock` in the lamp's socket directory. On this machine, the session listens
            on a TCP port of the loopback address 127.0.0.1, picked anew each session so that two
            lamps never ask for the same one, and copies each connection to that socket.

            When the session is up, oillamp reports the link as `LampEvent.WebPublished`, which the
            terminal prints and an application receives: `http://<lamp id>.localhost:<port>/`.
            Browsers send every `*.localhost` name to the loopback address, and a host name of its
            own gives each lamp's pages their own cookies. The same port is in the session's state,
            `LampSessionStatus.webPorts`, for an application that joins later.

            In this scenario the container is simulated, so a small server in the scenario stands
            in for the sandbox's bridge on `web-opencode.sock`. It answers "pong" to "ping". The
            scenario connects to the published port as a browser would, and the bytes must go
            through in both directions.
        """
        given: 'a session for a lamp without `[web]`, whose shell window stays open'
            host.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')
            var reported = new java.util.concurrent.CopyOnWriteArrayList<LampEvent>()
            var oillamp = host.oillamp.observedBy { reported.add(it) }
            var session = Thread.start { oillamp.run('at', lamp.toString()) }
            waitUntil { reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' } }

        and: 'a stand-in for the sandbox\'s bridge on `web-opencode.sock`, which answers "pong" to "ping"'
            var runtimeOfTheLamp = java.nio.file.Files.list(host.runtime.resolve('oillamp')).toList().first()
            var agentId = runtimeOfTheLamp.fileName.toString()
            var bridge = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
            bridge.bind(java.net.UnixDomainSocketAddress.of(runtimeOfTheLamp.resolve('sockets/infra/web-opencode.sock')))
            var heard = new java.util.concurrent.CompletableFuture<String>()
            Thread.start {
                try (var channel = bridge.accept()) {
                    heard.complete(new String(java.nio.channels.Channels.newInputStream(channel).readAllBytes(), 'UTF-8'))
                    channel.write(java.nio.ByteBuffer.wrap('pong'.getBytes('UTF-8')))
                }
            }

        when: 'oillamp reports the published port'
            var event = reported.find { it instanceof LampEvent.WebPublished } as LampEvent.WebPublished
            var published = event.port()

        then: 'it is `opencode`, port 4096 in the sandbox, with a link to `<lamp id>.localhost` on the port oillamp listens on'
            published.name() == 'opencode'
            published.sandboxPort() == 4096
            published.link() == URI.create("http://${agentId}.localhost:${published.hostPort()}/")

        and: 'it says the lamp listed it, rather than the agent opening it'
            !event.openedByTheAgent()

        and: 'the session\'s state lists the same port, for an application that joins later'
            waitUntil { reported.any { it instanceof LampEvent.SessionStateChanged && it.status().webPorts().contains(published) } }

        when: 'a client connects to 127.0.0.1 on that port, as a browser would, sends "ping" and finishes sending'
            var client = java.nio.channels.SocketChannel.open(new InetSocketAddress('127.0.0.1', published.hostPort()))
            client.write(java.nio.ByteBuffer.wrap('ping'.getBytes('UTF-8')))
            client.shutdownOutput()
            var answer = new String(java.nio.channels.Channels.newInputStream(client).readAllBytes(), 'UTF-8')
            client.close()

        then: 'the stand-in heard "ping", and the client got "pong" back'
            heard.get(10, TimeUnit.SECONDS) == 'ping'
            answer == 'pong'

        cleanup:
            bridge?.close()
            host.oillamp.run('stop', lamp.toString())
            session?.join(20_000)
    }

    def 'When nothing in the sandbox takes a connection to a published web server, oillamp says once how to start it'() {
        reportInfo """
            Let's consider a lamp that publishes opencode's port 4096, and a person who opens its
            link before opencode's web interface runs in the sandbox, or after the bridge for it
            has stopped. A browser then opens one connection after another and gets nothing. The
            person must learn what to do: start the server in the sandbox, with
            `opencode serve --port 4096`. Removing the sandbox, the advice for a sandbox that does
            not answer at all, would be wrong here.

            So oillamp reports the warning `OIL-NET-013`, which names `opencode serve --port 4096`.
            Browsers retry, so it is reported once, and again only after a connection got through.
            In this scenario nothing serves the sandbox's `web-opencode.sock`, and two connections
            to the published port fail.
        """
        given: 'a session for a lamp without `[web]`, with nothing behind the sandbox\'s `web-opencode.sock`'
            host.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')
            var reported = new java.util.concurrent.CopyOnWriteArrayList<LampEvent>()
            var oillamp = host.oillamp.observedBy { reported.add(it) }
            var session = Thread.start { oillamp.run('at', lamp.toString()) }
            waitUntil { reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' } }
            var published = reported.find { it instanceof LampEvent.WebPublished }.port()

        when: 'a browser connects to the published port twice'
            2.times {
                var client = java.nio.channels.SocketChannel.open(new InetSocketAddress('127.0.0.1', published.hostPort()))
                java.nio.channels.Channels.newInputStream(client).readAllBytes()
                client.close()
            }
            waitUntil { reported.any { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-NET-013' } }
            Thread.sleep(500)

        then: 'oillamp warned with `OIL-NET-013` once, and the warning says to run `opencode serve --port 4096`'
            var warnings = reported.findAll { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-NET-013' }
            warnings.size() == 1
            warnings.first().problem().toString().contains('opencode serve --port 4096')

        and: 'it gives no advice to remove the sandbox, and the session goes on'
            !warnings.first().problem().toString().contains('podman rm')
            session.alive

        cleanup:
            host.oillamp.run('stop', lamp.toString())
            session?.join(20_000)
    }

    /**
     * What the sandbox's `/proc/net/tcp` says in the scenarios about web servers the agent starts.
     * Three sockets: the agent (uid 1000) listening on 127.0.0.1:5173 (`0100007F:1435`); the infra
     * user (uid 1001) listening on every address, port 6000 (`00000000:1770`), as one of its bridges
     * would; and a connection of the agent's from port 5174 (state `01`, not listening).
     */
    static final String PROC_NET_TCP = """\
          sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 0100007F:1435 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 41001 1 0000000000000000 100 0 0 10 0
           1: 00000000:1770 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1001        0 41002 1 0000000000000000 100 0 0 10 0
           2: 0100007F:1436 0100007F:9C40 01 00000000:00000000 00:00000000 00000000  1000        0 41003 1 0000000000000000 20 4 30 10 -1
        """.stripIndent()

    /** The lamp id the scenarios about web servers the agent starts give their lamp. */
    static final String AGENT_ID = 'k3v7x2ab'

    /** Starts a session for a lamp with this `[web]`, whose sandbox's `/proc/net/tcp` is `procNetTcp`. */
    private Map startASessionWhoseAgentListens(String web, String procNetTcp = PROC_NET_TCP) {
        host.machine {
            it.windowsStayOpenFor(Duration.ofSeconds(60))
              .generatedAgentId(AGENT_ID)
              .commandSucceeding("podman exec --user lamp oillamp-${AGENT_ID} cat /proc/net/tcp", procNetTcp)
        }
        var lamp = host.lampPath()
        host.givenConfig(lamp, 'schema_version = 1\n\n[web]\n' + web)
        var reported = new java.util.concurrent.CopyOnWriteArrayList<LampEvent>()
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        var session = Thread.start { oillamp.run('at', lamp.toString()) }
        waitUntil { reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' } }
        [lamp: lamp, reported: reported, session: session]
    }

    def 'With `web.publish = "any"`, oillamp publishes a web server the agent starts, and nothing of the infra user\'s'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` sets `publish = "any"` under `[web]`, used as a
            personal assistant. The agent builds a small web site and starts its development server
            on 127.0.0.1, port 5173. The person should be able to open it in their browser without
            doing anything.

            Every two seconds the session reads the sandbox's `/proc/net/tcp` and `/proc/net/tcp6`,
            with `podman exec`. A port counts when its socket is listening, on the loopback or every
            address, and belongs to the agent's user, uid 1000 in the sandbox. That leaves out the
            infra user's sockets, such as the bridges of the proxy and the forwards, and a socket of
            the agent's that is a connection rather than a listener. A new port is published as
            `port-<number>`: the session starts a bridge on `web-port-5173.sock` in the sandbox, the
            same socat the sandbox starts for a listed port, listens on a loopback port of this
            machine, and reports `LampEvent.WebPublished`.

            Here the sandbox's `/proc/net/tcp` lists the agent's listener on 5173, a listener of the
            infra user (uid 1001) on 6000, and a connection of the agent's from 5174. A small server
            in the scenario stands in for the bridge on `web-port-5173.sock`, and answers "pong" to
            "ping".
        """
        given: 'a session for a lamp with `publish = "any"` and no ports of its own, whose agent listens on 5173'
            var started = startASessionWhoseAgentListens('publish = "any"\nports = {}\n')
            var reported = started.reported as List<LampEvent>

        and: 'a stand-in for the bridge on `web-port-5173.sock`, which answers "pong" to "ping"'
            var bridge = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
            bridge.bind(java.net.UnixDomainSocketAddress.of(host.runtime.resolve("oillamp/${AGENT_ID}/sockets/infra/web-port-5173.sock")))
            Thread.start {
                try (var channel = bridge.accept()) {
                    java.nio.channels.Channels.newInputStream(channel).readAllBytes()
                    channel.write(java.nio.ByteBuffer.wrap('pong'.getBytes('UTF-8')))
                }
            }

        when: 'the session has looked into the sandbox a few times'
            waitUntil { reported.any { it instanceof LampEvent.WebPublished } }
            Thread.sleep(5_000)

        then: 'it published `port-5173`, port 5173 in the sandbox, and nothing else'
            var published = reported.findAll { it instanceof LampEvent.WebPublished }*.port()
            published*.name() == ['port-5173']
            published.first().sandboxPort() == 5173

        and: 'it says the agent opened it'
            reported.find { it instanceof LampEvent.WebPublished }.openedByTheAgent()

        and: 'the session\'s state lists it'
            reported.any { it instanceof LampEvent.SessionStateChanged && it.status().webPorts().contains(published.first()) }

        when: 'a client connects to its port on 127.0.0.1, sends "ping" and finishes sending'
            var client = java.nio.channels.SocketChannel.open(new InetSocketAddress('127.0.0.1', published.first().hostPort()))
            client.write(java.nio.ByteBuffer.wrap('ping'.getBytes('UTF-8')))
            client.shutdownOutput()
            var answer = new String(java.nio.channels.Channels.newInputStream(client).readAllBytes(), 'UTF-8')
            client.close()

        then: 'the answer came back from the stand-in in the sandbox'
            answer == 'pong'

        cleanup:
            bridge?.close()
            host.oillamp.run('stop', host.lampPath().toString())
            (started?.session as Thread)?.join(20_000)
    }

    def 'With `web.publish = "any"`, oillamp publishes at most 16 ports the agent opens, and says so once when there are more'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` sets `publish = "any"` under `[web]`, and an
            agent that runs a test suite which starts a web server per test, 17 of them, on the ports
            5001 to 5017. Each published port costs a port on this machine, a relay and a bridge in
            the sandbox, so a session publishes at most 16 ports that the agent opens. The 17th is
            not published. oillamp warns once with `OIL-NET-014`, which says that further ports are
            not published this session and that a port can be listed in `web.ports` instead. It
            does not warn again on each later look into the sandbox.
        """
        given: 'a session for a lamp with `publish = "any"` and no ports of its own, whose agent listens on 17 ports'
            var listeners = (5001..5017).withIndex().collect { int port, int i ->
                String.format('%4d: 0100007F:%04X 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 %d 1 0000000000000000 100 0 0 10 0', i, port, 42000 + i)
            }
            var started = startASessionWhoseAgentListens('publish = "any"\nports = {}\n',
                    '  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n' + listeners.join('\n') + '\n')
            var reported = started.reported as List<LampEvent>

        when: 'the session has looked into the sandbox several times'
            waitUntil { reported.any { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-NET-014' } }
            Thread.sleep(5_000)

        then: '16 ports are published, the first 16, and not the 17th'
            var published = reported.findAll { it instanceof LampEvent.WebPublished }*.port()*.sandboxPort()
            published.size() == 16
            !published.contains(5017)

        and: 'oillamp warned once with `OIL-NET-014`, which points to `web.ports`'
            var warnings = reported.findAll { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-NET-014' }
            warnings.size() == 1
            warnings.first().problem().toString().contains('web.ports')

        cleanup:
            host.oillamp.run('stop', host.lampPath().toString())
            (started?.session as Thread)?.join(20_000)
    }

    def 'With `web.publish = "ask"`, oillamp publishes a port the agent starts once the person says yes'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` sets `publish = "ask"` under `[web]`. The
            agent starts a web server on 127.0.0.1, port 5173. Before anything is published, the
            person is asked, with `LampEvent.WebPortAsked`, whether to publish port 5173 to their
            browser. An application answers with `Lamp.decidePermission`, a person in a terminal
            with `oillamp answer <dir> yes -- <question>`. Only a yes publishes it, as `port-5173`.
        """
        given: 'a session for a lamp with `publish = "ask"` and no ports of its own, whose agent listens on 5173'
            var started = startASessionWhoseAgentListens('publish = "ask"\nports = {}\n')
            var reported = started.reported as List<LampEvent>

        when: 'the session asks the person'
            waitUntil { reported.any { it instanceof LampEvent.WebPortAsked } }
            var asked = reported.find { it instanceof LampEvent.WebPortAsked }

        then: 'the question is about port 5173, and nothing is published yet'
            asked.sandboxPort() == 5173
            asked.what().contains('publish port 5173')
            !reported.any { it instanceof LampEvent.WebPublished }

        when: 'the person answers yes, with `oillamp answer <dir> yes -- <question>`'
            var answered = host.oillamp.run('answer', host.lampPath().toString(), 'yes', '--', asked.what())

        then: 'the answer is taken, and the session publishes `port-5173`'
            answered.status() == LampExitStatus.SUCCESS
            waitUntil { reported.any { it instanceof LampEvent.WebPublished } }
            reported.find { it instanceof LampEvent.WebPublished }.port().name() == 'port-5173'

        cleanup:
            host.oillamp.run('stop', host.lampPath().toString())
            (started?.session as Thread)?.join(20_000)
    }

    def 'With `web.publish = "ask"`, a port the person refuses stays unpublished, and oillamp does not ask about it again'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` sets `publish = "ask"` under `[web]`, and an
            agent whose web server on port 5173 keeps running. The person is asked once, and says
            no. The session looks into the sandbox every two seconds and finds the server there
            each time; asking again each time would turn the question into a nuisance the person
            can only silence by saying yes. So a no, like no answer within a minute, holds for the
            rest of the session: the port stays unpublished, and the person is not asked again.
        """
        given: 'a session for a lamp with `publish = "ask"`, whose agent listens on 5173'
            var started = startASessionWhoseAgentListens('publish = "ask"\nports = {}\n')
            var reported = started.reported as List<LampEvent>
            waitUntil { reported.any { it instanceof LampEvent.WebPortAsked } }
            var asked = reported.find { it instanceof LampEvent.WebPortAsked }

        when: 'the person answers no, and the session goes on looking into the sandbox for several seconds'
            host.oillamp.run('answer', host.lampPath().toString(), 'no', '--', asked.what())
            Thread.sleep(6_000)

        then: 'nothing is published, and the person was asked only that once'
            !reported.any { it instanceof LampEvent.WebPublished }
            reported.count { it instanceof LampEvent.WebPortAsked } == 1

        cleanup:
            host.oillamp.run('stop', host.lampPath().toString())
            (started?.session as Thread)?.join(20_000)
    }

    def 'With `web.publish = "listed"`, oillamp ignores a web server the agent starts on a port that is not listed'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` sets `publish = "listed"` under `[web]`, with no
            ports listed. The person decided which ports their browser may open, and listed none.
            When the agent starts a web server on 127.0.0.1, port 5173, oillamp neither publishes it
            nor asks about it.
        """
        given: 'a session for a lamp with `publish = "listed"` and `ports = {}`, whose agent listens on 5173'
            var started = startASessionWhoseAgentListens('publish = "listed"\nports = {}\n')
            var reported = started.reported as List<LampEvent>

        when: 'the session runs for several seconds'
            Thread.sleep(6_000)

        then: 'nothing is published, and the person is asked nothing'
            !reported.any { it instanceof LampEvent.WebPublished }
            !reported.any { it instanceof LampEvent.WebPortAsked }

        cleanup:
            host.oillamp.run('stop', host.lampPath().toString())
            (started?.session as Thread)?.join(20_000)
    }

    private static void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw new AssertionError("the session never got there: ${condition}" as Object)
    }

    def 'A second Ctrl-C during shutdown does not turn a clean session into a bug report'() {
        reportInfo """
            Found on real hardware. Shutting down takes a moment: the container has to stop, and,
            if recording is on, the recorder has to finish the file. When a terminal seems to
            hang, people press Ctrl-C again.

            `podman stop` is a child of oillamp, and so shares the process group of the terminal
            oillamp was started from, so that second Ctrl-C reached it. It died with no output at
            all, and oillamp reported `OIL-INTERNAL-001`, "this is a bug in oillamp, please
            report", for a session that had in fact been cleaned up, because `podman rm -f` then
            removed the container anyway.

            Two things changed. The cleanup commands now run shielded from the terminal's
            signals, so that a second Ctrl-C cannot reach them. And oillamp reports the end state,
            whether the container is gone, rather than what each command said of itself.
        """
        given: 'a `podman stop` that dies the way a signalled process does: non-zero, and silent'
            host.machine {
                it.commandFailing('podman stop', 130, '')
                  .windowsStayOpenFor(Duration.ofMillis(400))
            }

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the session still ends with success, because the container did go away'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'nothing is reported as an internal error'
            !outcome.console().contains('OIL-INTERNAL-001')
            outcome.events().findAll { it instanceof LampEvent.Failure }.isEmpty()

        and: 'it says plainly what happened, without a colon followed by nothing'
            outcome.console().contains('the container had to be forced')
            !outcome.console().contains('did not stop cleanly: \n')
    }

    def 'Oillamp reports a container it truly cannot remove as `OIL-SANDBOX-005`, not as an internal error'() {
        reportInfo """
            At the end of a session, oillamp stops the container with `podman stop`, and removes
            it with `podman rm -f`. If neither works, the container is really still there, and the
            user must hear of it: it keeps its memory and processor reservations, and the next
            session on this lamp will find its name taken.

            It is still not a bug in oillamp, but in podman or on the machine, so it has its own
            code, and the command that clears it, rather than "please report this bug".
        """
        given: 'a podman that can neither stop nor remove the container'
            host.machine {
                it.commandFailing('podman stop', 125, 'Error: no such container')
                  .commandFailing('podman rm', 125, 'Error: container is in use')
                  .windowsStayOpenFor(Duration.ofMillis(400))
            }

        when: '`oillamp at` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the user is told, with `OIL-SANDBOX-005`, the code for a container left behind'
            outcome.console().contains('OIL-SANDBOX-005')
            !outcome.console().contains('OIL-INTERNAL-001')

        and: 'and given the one command that clears it'
            outcome.console().contains('podman rm -f ')
    }

    def 'A podman that is too busy to answer does not end a working session'() {
        reportInfo """
            Every two seconds, the session asks podman whether the sandbox still runs. Podman can
            be slow, or fail to answer, for example while another lamp's image is being built and
            podman's database is locked. An earlier session took any failed answer to mean "the
            sandbox has died", and shut down, taking the user's shell with it.

            Now only a clear answer ends the session: podman saying that the container stopped,
            or that it no longer exists. When podman does not answer, the user is warned once,
            with `OIL-SANDBOX-006`, and the session goes on.
        """
        given: 'a podman that fails every question about the container while the session runs'
            host.machine {
                it.commandFailing('podman container inspect', 125,
                                  'Error: timed out waiting for the database lock')
                  .windowsStayOpenFor(Duration.ofSeconds(6))
            }

        when: 'the session runs until the user closes the terminal'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'it ended because the terminal closed, not because the sandbox was thought dead'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.console().contains('ended because')
            !outcome.console().contains('the sandbox stopped')

        and: 'the user was told once that podman was not answering, with its own words'
            outcome.problems().count { it.code().value() == 'OIL-SANDBOX-006' } == 1
            outcome.console().contains('timed out waiting for the database lock')
    }
}
