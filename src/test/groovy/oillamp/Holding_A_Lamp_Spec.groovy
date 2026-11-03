package oillamp

import dev.lamp.LampExitStatus
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Title('Holding a lamp')
@Narrative('''
An *application* is a program built on oillamp's Java API, `dev.lamp`, such as a game that hosts
an agent. It holds a lamp through `Lamp`: `Lamp.at(dir)` gives a `LampDirectory`, which starts the
lamp, joins a session that is already running, or removes the lamp.

A lamp does not run the sandbox itself. It starts oillamp's *engine*, the same `oillamp` program
a person runs in a terminal, as a separate process, reads the events the engine writes, and ends
the session by closing the engine's standard input.

In these scenarios the engine runs in the same JVM, against a simulated machine, but connected to
the lamp by real pipes, so the lamp is used exactly as an application uses it.
''')
@Subject(Lamp)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class Holding_A_Lamp_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host
    final List<LampEvent> received = new CopyOnWriteArrayList<>()

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'An application starts a lamp, waits until it runs, and ends the session by closing it'() {
        reportInfo """
            This is the whole life of a lamp in an application built on oillamp's Java API: start
            it, hear what it reports, learn when it can be used, and end it. Closing the lamp is
            all it takes to end the session. `close()` returns only once the sandbox is gone, so
            that the application can exit knowing that nothing is left running.
        """
        given: 'the application starts a lamp, and listens to its events'
            var lamp = Lamp.at(host.lampPath()).onEvent { received << it }
                           .launchedBy(host.launcher).start()

        expect: '`awaitRunning` reports that the session is up'
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the application closes the lamp'
            lamp.close()

        then: 'the engine ended with success, and its summary says the application asked it to stop'
            lamp.exitStatus() == Optional.of(LampExitStatus.SUCCESS)
            received.any { it instanceof LampEvent.Summary
                           && it.lines().toList().any { line -> line.contains('asked to stop by the application') } }

        and: 'the session\'s `.oillamp/session.json` is gone'
            !Files.exists(host.lampPath().resolve('.oillamp/session.json'))
    }

    def 'A listener that throws does not stop the lamp or its other listeners from receiving the session\'s events'() {
        reportInfo """
            An application built on oillamp's Java API registers listeners with
            `LampDirectory.onEvent`. A listener is the application's own code, and may have a bug.
            When a listener throws, the exception goes to the uncaught exception handler, where the
            application can log it and show it, and nothing else changes: the lamp still learns
            that the session is up, and every other listener still receives every event. If the
            exception ended the thread that reads the engine's output instead, the application
            would wait for a session that was long running.
        """
        given: 'the application collects uncaught exceptions, as a Swing application might log them'
            var failures = new CopyOnWriteArrayList<Throwable>()
            var before = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, failed -> failures << failed }

        and: 'the first of its two listeners throws at every event'
            var lamp = Lamp.at(host.lampPath())
                           .onEvent { throw new IllegalStateException('a bug in the application') }
                           .onEvent { received << it }
                           .launchedBy(host.launcher).start()

        expect: 'the session comes up anyway'
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the application closes the lamp'
            lamp.close()

        then: 'the second listener received the whole session, from its opening to its summary'
            received.any { it instanceof LampEvent.SessionOpened }
            received.any { it instanceof LampEvent.Summary
                           && it.lines().toList().any { line -> line.contains('asked to stop by the application') } }

        and: 'the handler received one exception for each event'
            failures.size() == received.size()
            failures.every { it.message == 'a bug in the application' }

        cleanup:
            Thread.setDefaultUncaughtExceptionHandler(before)
    }

    def 'An application can leave its lamp running when it closes, until `oillamp stop` ends the session'() {
        reportInfo """
            An agent with jobs on its schedule should go on working after the application that
            started it has closed. The application, built on oillamp's Java API, says so with
            `Lamp.leaveRunning()`, which tells the engine on its standard input before the lamp
            closes that input. The end of the input, which otherwise ends the session, then ends
            nothing: the sandbox goes on running on its own, and `oillamp status` says so. The
            session ends as any session does, with `oillamp stop`.

            Only an application that calls `leaveRunning()` leaves its lamp running. One that
            closes, or crashes, without calling it still ends its session and its sandbox.
        """
        given: 'the application has started a lamp, and the session is up'
            var lamp = Lamp.at(host.lampPath()).onEvent { received << it }
                           .launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the application calls `leaveRunning()`, and closes the lamp'
            lamp.leaveRunning()
            lamp.close()
            Thread.sleep(1500)

        then: 'the engine goes on, and `oillamp status` says the session is running on its own'
            lamp.exitStatus().isEmpty()
            Files.exists(host.lampPath().resolve('.oillamp/session.json'))
            host.oillamp.run('status', host.lampPath().toString()).console()
                   .contains('running on its own')

        when: '`oillamp stop` runs'
            var stopped = host.oillamp.run('stop', host.lampPath().toString())
            host.engines.first().waitFor()
            // The lamp records how its engine ended on its own reader thread, a moment after the
            // process is gone.
            for (int i = 0; i < 100 && lamp.exitStatus().isEmpty(); i++) Thread.sleep(50)

        then: 'the engine ends with success, and the session\'s `session.json` is gone'
            stopped.succeeded()
            lamp.exitStatus() == Optional.of(LampExitStatus.SUCCESS)
            !Files.exists(host.lampPath().resolve('.oillamp/session.json'))
    }

    def 'An application that left its lamp running joins the session again, and can stop it'() {
        reportInfo """
            Let's consider an application built on oillamp's Java API that left its lamp running,
            and was closed. When it opens again, it finds the session with
            `LampDirectory.isRunning()`, and joins it with `LampDirectory.join()`, which starts the
            engine as `oillamp follow <dir> --embedded`. The application uses the `Lamp` it gets as
            one it started itself: it hears what the session reports, runs commands in the
            sandbox, and asks the agent. But the lamp does not hold the session, so closing it only
            stops following. `Lamp.stop()` ends the session, and returns once it has ended.
        """
        given: 'a lamp left running by an application that has closed'
            var first = Lamp.at(host.lampPath()).launchedBy(host.launcher).start()
            first.awaitRunning(Duration.ofSeconds(30))
            first.leaveRunning()
            first.close()

        when: 'the application opens again, finds the session running, and joins it'
            var starting = Lamp.at(host.lampPath()).onEvent { received << it }.launchedBy(host.launcher)
            var running = starting.isRunning()
            var joined = starting.join()

        then: 'the joined lamp runs, does not hold the session, gives ssh command lines, and follows with `oillamp follow --embedded`'
            running
            joined.awaitRunning(Duration.ofSeconds(10))
            !joined.holds()
            joined.commandLine('true').first() == 'ssh'
            host.engines.last().arguments == ['follow', host.lampPath().toString(), '--embedded']

        when: 'the application calls `Lamp.stop()`'
            joined.stop()

        then: 'the session ended with success, and the lamp is no longer running'
            joined.exitStatus() == Optional.of(LampExitStatus.SUCCESS)
            !starting.isRunning()
            received.any { it instanceof LampEvent.Summary }
    }

    def 'Joining a lamp that is not running reports `OIL-SESSION-001` as an event'() {
        reportInfo """
            An application built on oillamp's Java API may try to join every lamp it knows, with
            `LampDirectory.join()`. A lamp that is not running is nothing to crash over: the joined
            lamp never comes up, and a `Failure` event with `OIL-SESSION-001` says why.
        """
        given: 'a lamp whose session has ended'
            assert host.oillamp.run('at', host.lampPath().toString()).succeeded()

        when: 'the application joins it'
            var starting = Lamp.at(host.lampPath()).onEvent { received << it }.launchedBy(host.launcher)
            var joined = starting.join()

        then: 'the joined lamp never comes up, and a `Failure` event reports `OIL-SESSION-001`'
            !starting.isRunning()
            !joined.awaitRunning(Duration.ofSeconds(10))
            received.any { it instanceof LampEvent.Failure && it.problem().code().value() == 'OIL-SESSION-001' }
    }

    def '`Lamp.commandLine` gives the ssh command line for a command in the sandbox, with each argument arriving exactly as given'() {
        reportInfo """
            An application built on oillamp's Java API asks `Lamp.commandLine` for the command line
            that runs something in the sandbox. ssh joins its arguments with spaces and hands them
            to a shell in the sandbox, which would split `hello world` in two and expand a dollar
            sign. So the lamp quotes every argument for that shell: the application passes what it
            means, and the sandbox receives exactly that.
        """
        given: 'a lamp whose session is up'
            var lamp = Lamp.at(host.lampPath()).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the application asks for the command line of `echo` with two arguments, one with a space, one with a quote and a dollar sign'
            var line = lamp.commandLine('echo', 'hello world', 'it\'s $HOME')

        then: 'the command line is the engine\'s ssh command, without a terminal'
            line.first() == 'ssh'
            line.contains('-T')

        and: 'followed by each argument, quoted for the sandbox\'s shell'
            line.takeRight(3) == ["'echo'", "'hello world'", "'it'\\''s \$HOME'"]

        cleanup:
            lamp?.close()
    }

    def '`Lamp.exec` runs a command in the sandbox, which reads the application\'s input and answers on its output'() {
        reportInfo """
            An application built on oillamp's Java API runs a command in the sandbox with
            `Lamp.exec`, to move files in and out, or to speak a protocol with a program there,
            over the command's standard input and output. The lamp starts the command with its
            launcher, the part of the lamp that starts processes, the same way it starts the
            engine. So a lamp that was launched some other way, as this one is on a simulated
            machine, runs the command there too.
        """
        given: 'a lamp whose session is up'
            var lamp = Lamp.at(host.lampPath()).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'a command in the sandbox writes its input to a file in the agent\'s home, and prints where'
            Process writing = lamp.exec('sh', '-c', 'cat > "$1"; echo "$HOME/$1"', 'sh', 'it\'s here.txt')
            writing.outputStream.withCloseable { it.write('hello\nworld'.bytes) }
            var said = writing.inputStream.text.trim()

        then: 'the file holds the input, at the path the command printed, in the agent\'s home'
            writing.waitFor() == 0
            Files.readString(Path.of(said)) == 'hello\nworld'
            Lamp.agentHome(host.lampPath()).get().resolve('it\'s here.txt') == Path.of(said)

        cleanup:
            lamp?.close()
    }

    def '`Lamp.desktop()` names the socket on which the sandbox\'s desktop is served'() {
        reportInfo """
            An application built on oillamp's Java API, such as a game, that wants its user to
            watch the agent at work draws the sandbox's desktop in a window of its own. The desktop
            is served as VNC on a Unix socket that only this user can open, so no password is
            needed, and nothing listens on the network. The engine knows where the socket is, and
            `Lamp.desktop()` names it once the session is running.
        """
        given: 'a lamp whose session is up'
            var lamp = Lamp.at(host.lampPath()).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        expect: '`Lamp.desktop()` names the session\'s `vnc.sock`, in the user\'s runtime directory'
            lamp.desktop().fileName.toString() == 'vnc.sock'
            lamp.desktop().startsWith(host.runtime)

        cleanup:
            lamp?.close()
    }

    def '`Lamp.desktop()` throws for a lamp that is not running'() {
        reportInfo """
            Asking for the desktop of a lamp that never started, or has ended, is a mistake in the
            application. So `Lamp.desktop()` throws an `IllegalStateException` at once, instead of
            handing out the path of a socket that nothing listens on. Here the lamp never started,
            because oillamp refuses its directory, the home directory itself.
        """
        given: 'a lamp in the home directory, which oillamp refuses to start'
            var lamp = Lamp.at(host.home).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the application asks for its desktop'
            lamp.desktop()

        then: '`Lamp.desktop()` throws an `IllegalStateException`'
            thrown(IllegalStateException)

        cleanup:
            lamp?.close()
    }

    def '`LampDirectory.remove()` deletes a lamp through the engine'() {
        reportInfo """
            An application built on oillamp's Java API that lets its user create lamps, say one per
            conversation, must also let them delete one. Part of a lamp belongs to the sandbox's
            infra user, so the application cannot delete those files itself, but the engine can.
            `LampDirectory.remove()` runs `oillamp remove <dir> --yes --embedded`, which reports
            what it does as events, like everything else the engine does.
        """
        given: 'a lamp that has run once, and is closed'
            Lamp.at(host.lampPath()).launchedBy(host.launcher).start().withCloseable {
                assert it.awaitRunning(Duration.ofSeconds(30))
            }

        when: 'the application calls `LampDirectory.remove()`'
            var status = Lamp.at(host.lampPath()).onEvent { received << it }.launchedBy(host.launcher).remove()

        then: 'it returns success, and `.oillamp/` and `oillamp.toml` are gone'
            status == LampExitStatus.SUCCESS
            !Files.exists(host.lampPath().resolve('.oillamp'))
            !Files.exists(host.lampPath().resolve('oillamp.toml'))

        and: 'the engine ran as `oillamp remove <dir> --yes --embedded`, and reported events'
            host.engines.last().arguments == ['remove', host.lampPath().toString(), '--yes', '--embedded']
            !received.isEmpty()
    }

    def '`Lamp.agentHome` finds the agent\'s home in a lamp, whether the lamp runs or not'() {
        reportInfo """
            What the agent keeps, such as the saved conversations of its harness, is in its home, a
            directory in the lamp named after the agent's id. An application built on oillamp's
            Java API that shows those conversations, even while the sandbox is off, asks
            `Lamp.agentHome` where that home is, instead of knowing how oillamp names it. Before
            the lamp has first run, there is no home yet.
        """
        expect: 'before the lamp has first run, `Lamp.agentHome` finds no home'
            Lamp.agentHome(host.lampPath()).isEmpty()

        when: 'the lamp has run once, and is closed'
            Lamp.at(host.lampPath()).launchedBy(host.launcher).start().withCloseable {
                assert it.awaitRunning(Duration.ofSeconds(30))
            }

        then: '`Lamp.agentHome` finds the `agent-lamp-` directory, the one holding `AGENTS.md`'
            var home = Lamp.agentHome(host.lampPath())
            home.isPresent()
            home.get().fileName.toString().startsWith('agent-lamp-')
            Files.exists(home.get().resolve('AGENTS.md'))
    }

    def '`LampDirectory.remove()` leaves a directory that is not a lamp alone, and says why'() {
        reportInfo """
            An application built on oillamp's Java API that was given a wrong path must not delete
            the wrong thing. The engine refuses a directory that is not a lamp, leaves its files
            where they are, and says so in a `Failure` event that the application can show.
        """
        given: 'a directory with a file in it, which is not a lamp'
            Files.createDirectories(host.lampPath())
            Files.writeString(host.lampPath().resolve('notes.txt'), 'mine')

        when: 'the application calls `LampDirectory.remove()` on it'
            var status = Lamp.at(host.lampPath()).onEvent { received << it }.launchedBy(host.launcher).remove()

        then: 'it does not return success, the file is still there, and a `Failure` event says the directory is not an oillamp lamp'
            status != LampExitStatus.SUCCESS
            Files.exists(host.lampPath().resolve('notes.txt'))
            received.any { it instanceof LampEvent.Failure && it.problem().whatHappened().contains('not an oillamp lamp') }
    }

    def '`Lamp.commandLine` throws for a lamp that is not running, rather than give an ssh command that cannot connect'() {
        reportInfo """
            Let's consider an application that asks a lamp for a command line although the lamp
            never started, here because oillamp refused its directory, the home directory itself.
            An ssh command line would fail with an error about sockets, which says nothing about
            the real cause. So `Lamp.commandLine` throws an `IllegalStateException` at once,
            saying that the lamp is not running. That points at what went wrong, and the
            application can show the problem that the lamp's events already reported.
        """
        given: 'a lamp in the home directory, which oillamp refuses to start'
            var lamp = Lamp.at(host.home).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the application asks it for a command line'
            lamp.commandLine('true')

        then: '`Lamp.commandLine` throws an `IllegalStateException`'
            thrown(IllegalStateException)

        cleanup:
            lamp?.close()
    }

    def 'A lamp starts the engine as `oillamp at <dir> --embedded`'() {
        reportInfo """
            A lamp runs the same engine that a person runs in a terminal, `oillamp at <dir>`.
            `--embedded` makes it fit for an application built on oillamp's Java API: it opens no
            windows on the user's desktop, writes one event per line of JSON on its standard
            output for the application to read, and ends the session when the application closes
            its standard input. Without that one option, the application would get a terminal
            window it never asked for, and output it could not read.
        """
        given: 'an application starts a lamp'
            var lamp = Lamp.at(host.lampPath()).launchedBy(host.launcher).start()

        expect: 'the engine was started as `oillamp at <dir> --embedded`'
            host.engines.first().arguments == ['at', host.lampPath().toString(), '--embedded']

        cleanup:
            lamp?.close()
    }

    def 'A lamp whose engine refuses to start reports the problem as an event, and does not claim to be running'() {
        reportInfo """
            An application built on oillamp's Java API needs to tell its user what went wrong, in
            the same words oillamp uses in a terminal. So the problem, with its evidence and
            fixes, arrives as a `Failure` event. Here the lamp directory is the home directory
            itself, which oillamp refuses with `OIL-LAMP-003`.
        """
        given: 'a lamp in the home directory, which the application starts and listens to'
            var lamp = Lamp.at(host.home).onEvent { received << it }
                           .launchedBy(host.launcher).start()

        expect: 'it never runs, its engine ended without success, and a `Failure` event reports `OIL-LAMP-003`'
            !lamp.awaitRunning(Duration.ofSeconds(30))
            lamp.exitStatus().isPresent()
            lamp.exitStatus().get() != LampExitStatus.SUCCESS
            received.any { it instanceof LampEvent.Failure && it.problem().code().value() == 'OIL-LAMP-003' }

        cleanup:
            lamp?.close()
    }
}
