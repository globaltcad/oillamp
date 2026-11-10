package oillamp

import dev.lamp.Lamp
import dev.lamp.LampExitStatus
import dev.lamp.LampEvent
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

@Title('The session commands')
@Narrative('''
`oillamp at <lamp>` runs a session, and keeps running until the session ends. That process is the
session's *supervisor*: it holds the lamp's lock and the relays between the sandbox and the host.

`oillamp status`, `stop`, `shell` and `view` do not act on the sandbox themselves. Each sends a
request to the supervisor over its *control socket*, a Unix socket whose path the supervisor writes
into the lamp's `.oillamp/session.json`, and the supervisor carries it out. Only the supervisor can
do so safely: a `stop` that removed the container behind its back would leave it holding a lock and
relays for a sandbox that no longer exists. `oillamp list` is the exception, and asks podman.

In these scenarios a real session runs on a thread of its own, against a simulated machine, and
each command runs on the scenario's thread, as if typed into a second terminal.
''')
@Subject(OilLamp)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class The_Session_Commands_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    /**
     *  Everything the running session said, as it said it.
     *
     *  <p>Copy-on-write rather than a synchronized list: the session writes to it from its own
     *  thread while the scenario reads it from this one, and a synchronized list is only safe to
     *  iterate under a lock the caller holds - which a `waitUntil` predicate does not.
     */
    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread session
    OilLamp.Outcome sessionOutcome

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def cleanup() {
        if (session?.alive) {
            host.oillamp.run('stop', host.lampPath().toString())
            session.join(20_000)
        }
    }

    def '`oillamp status` gets the state of a running session from its supervisor'() {
        reportInfo """
            Let's consider a lamp whose session runs, started with `oillamp at`. `oillamp status
            <lamp>` asks that session's supervisor over the control socket, rather than working the
            answer out from the outside, because two of the things it reports are written down
            nowhere else: the state the session is in, and the number of extra shells attached to
            it.
        """
        given: 'a session runs for the lamp'
            var lamp = host.lampPath()
            startASession(lamp)

        when: '`oillamp status <lamp>` is run'
            var outcome = host.oillamp.run('status', lamp.toString())

        then:
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the answer names the state `running`, the container `oillamp-…`, the desktop size and the lamp\'s directory'
            var answer = outcome.events().find { it instanceof LampEvent.Answer }.text()
            answer.contains('running')
            answer.contains('oillamp-')
            answer.contains('1920x1080')
            answer.contains(lamp.toString())
    }

    def '`oillamp status` lists the web servers of the sandbox that a running session publishes'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` has no `[web]` section, so that its session
            publishes opencode's port 4096 to the person's browser. The session picks the port on
            this machine anew each time, so the link cannot be written down anywhere in advance.
            `oillamp status <lamp>` asks the running session, and its answer has a line `web` that
            gives the link and the port in the sandbox it leads to, as in
            `opencode: http://<lamp id>.localhost:<port>/ → port 4096 in the sandbox`.
        """
        given: 'a session runs for a lamp without `[web]`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')
            startASession(lamp)

        when: '`oillamp status <lamp>` is run'
            var outcome = host.oillamp.run('status', lamp.toString())
            var answer = outcome.events().find { it instanceof LampEvent.Answer }.text()

        then: 'the answer has a line `web` with the link that the session reported, and the port 4096 in the sandbox'
            var link = reported.find { it instanceof LampEvent.WebPublished }.port().link().toString()
            answer.readLines().any { it.startsWith('web') && it.contains("opencode: ${link} → port 4096 in the sandbox") }
    }

    def '`oillamp stop` asks the supervisor to end the session, rather than removing its container'() {
        reportInfo """
            Let's consider a session that runs, and `oillamp stop <lamp>` run from another terminal.
            `stop` does not remove the sandbox's container itself. It asks the session's supervisor,
            over the control socket, to end the session. Removing the container directly would
            leave the supervisor holding the lamp's lock for a sandbox that no longer exists, with
            its relays still bound and its recording unfinished. Asked instead, the supervisor shuts
            the session down as it does on Ctrl-C, and so finishes the recording, removes the
            container and releases the lock itself.
        """
        given: 'a session runs for the lamp'
            var lamp = host.lampPath()
            startASession(lamp)

        when: '`oillamp stop <lamp>` is run'
            var outcome = host.oillamp.run('stop', lamp.toString())

        then: '`stop` succeeds: the supervisor accepted the request'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the session then ends with success, and its summary says it was asked to stop by `oillamp stop`'
            session.join(30_000)
            sessionOutcome.status() == LampExitStatus.SUCCESS
            reported.any { it instanceof LampEvent.Summary &&
                           it.lines().toList().any { line -> line.contains('asked to stop by') } }
    }

    def 'A shell opened with `oillamp shell` attaches to a running session, and closing it does not end the session'() {
        reportInfo """
            Let's consider a session that runs, and someone who wants a second shell into its
            sandbox. `oillamp shell <lamp>` asks the session's supervisor for the ssh command line,
            and runs it in the terminal it was typed in. Such an *extra shell* does not decide when
            the session ends: closing it leaves the session running.
        """
        given: 'a session runs for the lamp'
            var lamp = host.lampPath()
            startASession(lamp)

        when: '`oillamp shell <lamp>` opens an extra shell, which is closed two seconds later'
            host.machine { it.windowsStayOpenFor(Duration.ofSeconds(2)) }
            var outcome = host.oillamp.run('shell', lamp.toString())

        then:
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the session reports "1 extra shell attached", then "the extra shell closed"'
            waitUntil { reported.any { it instanceof LampEvent.Info && it.text() == 'the extra shell closed' } }
            var news = reported.findAll { it instanceof LampEvent.Info }*.text()
            news.contains('1 extra shell attached')
            news.indexOf('1 extra shell attached') < news.indexOf('the extra shell closed')

        and: 'the session is still running after the extra shell has closed, and has printed no end-of-session summary'
            session.alive
            reported.every { !(it instanceof LampEvent.Summary && it.title().startsWith('session ')) }
    }

    def '`oillamp shell --window` has the session open the extra shell in a new terminal window'() {
        reportInfo """
            Let's consider an application that shows a running session, and has no terminal of its
            own to put a shell in. With `--window`, `oillamp shell <lamp>` does not run the ssh
            command line itself: the session's supervisor opens a new terminal window with that
            command line in it, as it opened the session's own shell window, and
            `oillamp shell` returns at once. The shell in that window is an *extra shell* like
            any other.
        """
        given: 'a session runs for the lamp, with the two windows `oillamp at` opened'
            var lamp = host.lampPath()
            startASession(lamp)
            var before = windowsOpened()

        when: '`oillamp shell <lamp> --window` is run'
            var outcome = host.oillamp.run('shell', lamp.toString(), '--window')

        then:
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the supervisor opened one more window, "an extra shell, in a new terminal window", which runs ssh'
            waitUntil { windowsOpened() == before + 1 }
            var window = reported.findAll { it instanceof LampEvent.WindowOpened }.last()
            window.what() == 'an extra shell, in a new terminal window'
            window.argv().contains('ssh')

        and: 'the session reports "1 extra shell attached"'
            waitUntil { reported.any { it instanceof LampEvent.Info && it.text() == '1 extra shell attached' } }
    }

    def '`oillamp follow --window` has the session open a terminal window that runs `oillamp follow`'() {
        reportInfo """
            Let's consider the same application, whose user wants to read what the session
            reports as a terminal shows it. With `--window`, `oillamp follow <lamp>` reports
            nothing itself: the session's supervisor opens a new terminal window whose command
            line is `oillamp follow <lamp>`, started with the Java runtime the session itself
            runs on, and `oillamp follow --window` returns at once.
        """
        given: 'a session runs for the lamp, with the two windows `oillamp at` opened'
            var lamp = host.lampPath()
            startASession(lamp)
            var before = windowsOpened()

        when: '`oillamp follow <lamp> --window` is run'
            var outcome = host.oillamp.run('follow', lamp.toString(), '--window')

        then: 'it succeeded, without waiting for the session to end'
            outcome.status() == LampExitStatus.SUCCESS
            session.alive

        and: 'the supervisor opened one more window, whose command line ends with `follow <lamp>`'
            waitUntil { windowsOpened() == before + 1 }
            var window = reported.findAll { it instanceof LampEvent.WindowOpened }.last()
            window.what() == 'what the session reports, in a new terminal window'
            window.argv().toList().takeRight(3) == [OilLamp.name, 'follow', lamp.toString()]
    }

    def '`oillamp shell --window` fails on a machine with no terminal emulator, and the session keeps running'() {
        reportInfo """
            Let's consider a session started with `oillamp at <lamp> --no-windows` on a machine
            where none of the terminal emulators oillamp knows is installed. Such a session
            needed no terminal to start. `oillamp shell <lamp> --window` needs one: the session's
            supervisor refuses, `oillamp shell` reports why, and the session is not affected.
        """
        given: 'a machine with no terminal emulator, and a session started with `--no-windows`'
            var lamp = host.lampPath()
            host.machine { it.withoutTerminals() }
            var oillamp = host.oillamp.observedBy { reported.add(it) }
            session = Thread.start { sessionOutcome = oillamp.run('at', lamp.toString(), '--no-windows') }
            waitUntil { reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' } }

        when: '`oillamp shell <lamp> --window` is run'
            var outcome = host.oillamp.run('shell', lamp.toString(), '--window')

        then: 'it fails, and says that no terminal emulator is on the PATH'
            outcome.status() != LampExitStatus.SUCCESS
            outcome.console().contains('none of the known terminal emulators is on PATH')

        and: 'the session opened no window, and still runs'
            windowsOpened() == 0
            session.alive
    }

    def 'An application opens a shell, a viewer and a follower in windows of their own through `Lamp`'() {
        reportInfo """
            Let's consider an application that joined a running session with
            `Lamp.at(dir).join()`, and offers its user three buttons. `Lamp.openShell()` runs
            `oillamp shell <dir> --window`, `Lamp.openViewer()` runs `oillamp view <dir>`, and
            `Lamp.openFollower()` runs `oillamp follow <dir> --window`. Each returns once the
            session's supervisor has started the window, and the joined `Lamp` hears the
            `WindowOpened` event of each.
        """
        given: 'a session runs for the lamp, and an application has joined it'
            var lamp = host.lampPath()
            startASession(lamp)
            var heard = new java.util.concurrent.CopyOnWriteArrayList<LampEvent>()
            var joined = Lamp.at(lamp).launchedBy(host.launcher).onEvent { heard.add(it) }.join()
            assert joined.awaitRunning(Duration.ofSeconds(30))

        when: 'the application calls `openShell()`, `openViewer()` and `openFollower()`'
            joined.openShell()
            joined.openViewer()
            joined.openFollower()

        then: 'the joined `Lamp` heard a `WindowOpened` event for each, in that order'
            waitUntil { heard.count { it instanceof LampEvent.WindowOpened } == 3 }
            heard.findAll { it instanceof LampEvent.WindowOpened }*.what() == [
                    'an extra shell, in a new terminal window',
                    'the desktop viewer',
                    'what the session reports, in a new terminal window']

        cleanup:
            joined?.close()
    }

    def '`oillamp at --no-windows` runs a session on a machine with no display, and prints how to get into it'() {
        reportInfo """
            Let's consider someone who starts oillamp inside tmux, so that the session outlives the
            desktop they are logged into, and who attaches to it when they choose. With
            `--no-windows`, `oillamp at` opens no window, and so needs no display. Instead, its
            summary "your session is up" lists the ways in: a shell with `oillamp shell`, the
            desktop with `oillamp view` on this machine, or, from another machine, an ssh forward of
            the desktop's VNC socket. The session ends as any session does, here with
            `oillamp stop`.
        """
        given: 'a machine with no graphical session'
            var lamp = host.lampPath()
            host.machine { it.noGraphicalSession() }
            var oillamp = host.oillamp.observedBy { reported.add(it) }

        when: '`oillamp at <lamp> --no-windows` runs until the session is up'
            session = Thread.start { sessionOutcome = oillamp.run('at', lamp.toString(), '--no-windows') }
            waitUntil { reported.any { it instanceof LampEvent.Summary &&
                                       it.title() == 'your session is up' } }

        then: 'it opened no window, and reported no failure for the missing display'
            reported.findAll { it instanceof LampEvent.WindowOpened }.isEmpty()
            reported.every { !(it instanceof LampEvent.Failure) }

        and: 'its summary names `oillamp shell <lamp>`, `oillamp view <lamp>`, and an ssh forward of port 5901 to the VNC socket'
            var briefing = reported.find { it instanceof LampEvent.Summary &&
                                           it.title() == 'your session is up' }.lines().join('\n')
            briefing.contains("oillamp shell ${lamp}")
            briefing.contains("oillamp view ${lamp}")
            briefing.contains('ssh -N -L 5901:') && briefing.contains('vnc.sock')

        and: 'it says which ways in are for this machine and which for another, since the ssh forward fails when run on this machine itself'
            briefing.contains('on this machine')
            briefing.contains('from another machine')

        and: 'with nobody connected, `oillamp status` says the session runs with no windows opened'
            host.oillamp.run('status', lamp.toString()).console().contains('no windows opened')

        when: '`oillamp stop <lamp>` is run'
            host.oillamp.run('stop', lamp.toString())
            session.join(20_000)

        then: 'the session ends with success'
            sessionOutcome.status() == LampExitStatus.SUCCESS
    }

    def 'After its shell window closes, a session keeps running, and `oillamp shell` can attach a new shell'() {
        reportInfo """
            Let's consider a session whose shell window, the terminal window that `oillamp at`
            opened, is closed, perhaps because a program in it hung. Closing that window does not
            mean the session is finished. The sandbox, the agent's programs and the desktop carry
            on, `oillamp status` says the window is closed, and `oillamp shell` opens a new way in.
        """
        given: 'a session whose shell window closes after one second'
            var lamp = host.lampPath()
            host.machine { it.windowsStayOpenFor(Duration.ofSeconds(1))
                                .userStopsTheSessionAfter(Duration.ofSeconds(60)) }
            var oillamp = host.oillamp.observedBy { reported.add(it) }
            session = Thread.start { sessionOutcome = oillamp.run('at', lamp.toString()) }
            waitUntil { reported.any { it instanceof LampEvent.Info &&
                                       it.text().contains('the shell window closed') } }

        when: '`oillamp status <lamp>`, then `oillamp shell <lamp>`, is run'
            var status = host.oillamp.run('status', lamp.toString())
            var shell = host.oillamp.run('shell', lamp.toString())

        then: 'the supervisor still answers `status`, and says the shell window is closed'
            status.status() == LampExitStatus.SUCCESS
            status.console().contains('running')
            status.console().contains('shell window oillamp opened is closed')

        and: 'an extra shell attached to the session'
            shell.status() == LampExitStatus.SUCCESS
            waitUntil { reported.any { it instanceof LampEvent.Info &&
                                       it.text().contains('extra shell attached') } }

        and: 'the session is still running, and has printed no end-of-session summary'
            session.alive
            reported.every { !(it instanceof LampEvent.Summary && it.title().startsWith('session ')) }
    }

    def '`oillamp view` opens one more viewer onto the desktop of a running session'() {
        reportInfo """
            Let's consider a session that runs, with the viewer window that `oillamp at` opened onto
            the sandbox's desktop. Someone may want to watch from a second window, or to reopen the
            viewer they closed. `oillamp view <lamp>` asks the session's supervisor to open one more
            viewer. Opening a viewer changes nothing about the session itself.
        """
        given: 'a session runs, with the viewer that `oillamp at` opened'
            var lamp = host.lampPath()
            startASession(lamp)
            var before = windowsOpened()

        when: '`oillamp view <lamp>` is run'
            var outcome = host.oillamp.run('view', lamp.toString())

        then:
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the supervisor opened one more window, a second viewer'
            waitUntil { windowsOpened() == before + 1 }
            reported.findAll { it instanceof LampEvent.WindowOpened }
                    .count { it.what().contains('viewer') } == 2
    }

    def '`oillamp view --view-only` opens a viewer that takes no keyboard or mouse input'() {
        reportInfo """
            Let's consider someone who wants to watch the agent work without a stray key press or
            click reaching the sandbox's desktop. `oillamp view <lamp> --view-only` opens a viewer
            that takes no input. A viewer opened without the flag takes input as usual. The
            scenario checks the option each viewer was started with: `-ViewOnly=1`, or none.
        """
        given: 'a session runs for the lamp'
            var lamp = host.lampPath()
            startASession(lamp)
            var viewers = {
                reported.findAll { it instanceof LampEvent.WindowOpened && it.what().contains('viewer') }
            }

        when: '`oillamp view --view-only` opens one viewer, then `oillamp view` an ordinary one'
            var watching = host.oillamp.run('view', lamp.toString(), '--view-only')
            waitUntil { viewers().size() == 2 }
            var ordinary = host.oillamp.run('view', lamp.toString())
            waitUntil { viewers().size() == 3 }

        then:
            watching.status() == LampExitStatus.SUCCESS
            ordinary.status() == LampExitStatus.SUCCESS

        and: 'only the viewer opened with `--view-only` was started with `-ViewOnly=1`'
            var watchingViewer = viewers()[1]
            var ordinaryViewer = viewers()[2]
            watchingViewer.argv().contains('-ViewOnly=1')
            !ordinaryViewer.argv().contains('-ViewOnly=1')
    }

    def '`oillamp list` reports the oillamp sandboxes running on the host, and says so in words when there are none'() {
        reportInfo """
            `oillamp list` asks podman for the containers oillamp started, rather than keeping a list
            of its own. Such a list could be wrong, and wrong exactly when it is needed:
            after a supervisor was killed before it could take its sandbox off the list. In this
            scenario no oillamp sandbox runs.
        """
        when: '`oillamp list` is run on a host where no oillamp sandbox runs'
            var outcome = host.oillamp.run('list')

        then: 'it succeeds, and its `Sandboxes` event lists none'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.events().find { it instanceof LampEvent.Sandboxes }.sandboxes().isEmpty()

        and: 'the console says "no oillamp sandboxes are running on this host"'
            outcome.console().contains('no oillamp sandboxes are running on this host')
    }

    def '`oillamp stop` removes what a killed supervisor left behind, whether or not its container is still there'() {
        reportInfo """
            Let's consider a supervisor killed outright, by a crash, a `kill -9` or a power cut. The
            lamp's lock goes with the process, but the control socket file and
            `.oillamp/session.json` stay behind, and so may the container. `oillamp status` then
            reports `OIL-SESSION-002`, that the session did not answer, and suggests
            `oillamp stop`.

            So `oillamp stop`, finding no supervisor to ask, cleans up: it removes the container if
            it is still there, the control socket file and `session.json`. An earlier `stop` removed
            only the container. When that was already gone, it removed nothing and gave the same
            advice again, which sent the user round in a circle.
        """
        given: 'what a killed supervisor leaves behind: a control socket nobody listens on, `session.json`, and perhaps its container'
            var lamp = host.lampPath()
            var socket = leftBehindByAKilledSupervisor(lamp)
            host.machine { containerStillThere ? it.commandSucceeding('podman container exists', '')
                                                  : it.commandFailing('podman container exists', 1, '') }

        expect: '`oillamp status` reports `OIL-SESSION-002`: the session did not answer'
            host.oillamp.run('status', lamp.toString()).reported('OIL-SESSION-002')

        when: '`oillamp stop <lamp>` is run'
            var stopped = host.oillamp.run('stop', lamp.toString())

        then: '`stop` succeeds and says what it removed, and the control socket and `session.json` are gone'
            stopped.status() == LampExitStatus.SUCCESS
            stopped.console().contains(containerStillThere ? 'the sandbox left by the previous session has been removed'
                                                          : 'its sandbox was already gone')
            !Files.exists(socket)
            !Files.exists(lamp.resolve('.oillamp/session.json'))

        and: '`oillamp status` now reports `OIL-SESSION-001`: no session is running'
            host.oillamp.run('status', lamp.toString()).reported('OIL-SESSION-001')

        where:
            containerStillThere << [true, false]
    }

    def 'A connection to the control socket that never sends a request does not hold up the other commands'() {
        reportInfo """
            Let's consider a session that runs, and a connection to its control socket that never
            sends a request, such as an `oillamp status` suspended with Ctrl-Z at the wrong moment.
            The supervisor once answered one connection at a time, and waited as long as it took
            for each to send its request, so such a connection kept `stop`, `status`, `view` and
            `shell` waiting for the rest of the session. Now each connection has a thread of its
            own, and five seconds to send its request before the supervisor closes it.
        """
        given: 'a session runs, and a connection to its control socket sends nothing'
            var lamp = host.lampPath()
            startASession(lamp)
            var silent = java.nio.channels.SocketChannel.open(UnixDomainSocketAddress.of(controlSocket(lamp)))

        when: '`oillamp status <lamp>` is run'
            var started = System.nanoTime()
            var outcome = host.oillamp.run('status', lamp.toString())

        then: '`status` is answered within three seconds'
            outcome.status() == LampExitStatus.SUCCESS
            Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3)

        and: 'the supervisor later closes the silent connection'
            silent.configureBlocking(true)
            silent.read(java.nio.ByteBuffer.allocate(16)) == -1

        cleanup:
            silent?.close()
    }

    def '`oillamp #command` gives up on a supervisor that accepts connections but never answers, and removes nothing'() {
        reportInfo """
            Let's consider a supervisor that is frozen: stopped with Ctrl-Z, or stuck. Its control
            socket is still there, and the kernel still accepts connections to it, but nothing
            answers. `oillamp status` and `oillamp stop` once waited for that answer forever, and
            could tell the user nothing. Now each waits five seconds, reports `OIL-SESSION-002`,
            that the session did not answer, and removes nothing, since the session may still be
            alive.
        """
        given: 'a control socket that accepts connections and never answers them'
            var lamp = host.lampPath()
            var socket = leftBehindByAKilledSupervisor(lamp)
            Files.delete(socket)
            var frozen = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            frozen.bind(UnixDomainSocketAddress.of(socket))

        when: '`oillamp <command> <lamp>` is run'
            var started = System.nanoTime()
            var outcome = host.oillamp.run(command, lamp.toString())

        then: 'it reports `OIL-SESSION-002` within 20 seconds'
            outcome.reported('OIL-SESSION-002')
            Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(20)

        and: 'the control socket and `session.json` are still there'
            Files.exists(socket)
            Files.exists(lamp.resolve('.oillamp/session.json'))

        cleanup:
            frozen?.close()

        where:
            command << ['status', 'stop']
    }

    private static Path controlSocket(Path lamp) {
        var sessionJson = Files.readString(lamp.resolve('.oillamp/session.json'))
        Path.of((sessionJson =~ /"controlSocket": "([^"]+)"/)[0][1] as String)
    }

    /**
     *  Runs a session to its end, then puts back what a supervisor killed with `kill -9` would
     *  have left: its control socket file with nothing listening, and `session.json`.
     *
     *  @return the control socket
     */
    private Path leftBehindByAKilledSupervisor(Path lamp) {
        startASession(lamp)
        var sessionJson = Files.readString(lamp.resolve('.oillamp/session.json'))
        var socket = Path.of((sessionJson =~ /"controlSocket": "([^"]+)"/)[0][1] as String)
        host.oillamp.run('stop', lamp.toString())
        session.join(20_000)

        var abandoned = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        abandoned.bind(UnixDomainSocketAddress.of(socket))
        abandoned.close()       // closing does not remove the file, as a killed process would not
        Files.writeString(lamp.resolve('.oillamp/session.json'), sessionJson)
        socket
    }

    // ─── running a session beside the scenario ─────────────────────────────────────────────

    /**
     *  Starts a real session on its own thread and returns once it is up.
     *
     *  <p>The windows are told to stay open for far longer than the scenario needs, so that the
     *  session is ended by whatever the scenario does rather than by a timer - which would make
     *  every one of these pass for the wrong reason.
     */
    private void startASession(Path lamp) {
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        session = Thread.start { sessionOutcome = oillamp.run('at', lamp.toString()) }
        waitUntil { reported.any { it instanceof LampEvent.Summary &&
                                   it.title() == 'your session is up' } }
    }

    private int windowsOpened() {
        reported.count { it instanceof LampEvent.WindowOpened }
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw new AssertionError("the session never got there: ${condition}" as Object)
    }
}
