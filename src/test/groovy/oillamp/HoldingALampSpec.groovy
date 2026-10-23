package oillamp

import dev.lamp.LampExitStatus
import dev.lamp.Lamp
import dev.lamp.LampEvent
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 *  An application, such as a game that hosts an agent, holding a lamp through {@link Lamp}.
 *
 *  <p>The lamp starts oillamp's engine as a separate process, reads its events, and ends the
 *  session by closing the engine's standard input. Here the engine runs in this JVM against the
 *  simulated machine ({@link EngineInThisProcess}), connected by real pipes, so the lamp is
 *  tested exactly as an application uses it.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class HoldingALampSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host
    final List<LampEvent> received = new CopyOnWriteArrayList<>()

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'An application starts a lamp, sees it running, and ends it by closing it'() {
        reportInfo """
            An application is a program, such as a game that hosts an agent, built on oillamp's
            Java API. This is the whole life of a lamp in an application: start it, hear what it
            does, know when it is usable, and end it. Closing the lamp is all it takes to end the
            session, and it returns only once the sandbox is gone, so the application can exit
            knowing nothing is left running.
        """
        given:
            var lamp = Lamp.at(host.lampPath()).onEvent { received << it }
                           .launchedBy(host.launcher).start()

        expect: 'the session comes up'
            lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            lamp.close()

        then: 'the engine ended cleanly, because it was asked to'
            lamp.exitStatus() == Optional.of(LampExitStatus.SUCCESS)
            received.any { it instanceof LampEvent.Summary
                           && it.lines().toList().any { line -> line.contains('asked to stop by the application') } }

        and: 'nothing of the session is left'
            !Files.exists(host.lampPath().resolve('.oillamp/session.json'))
    }

    def 'A listener that throws does not stop the lamp or its other listeners from receiving the session\'s events'() {
        reportInfo """
            An application, a program built on oillamp's Java API, registers listeners with
            `LampDirectory.onEvent`. A listener is the application's own code, and may have a bug.
            When it throws, the exception goes to the uncaught exception handler, where the
            application can log and show it, and nothing else changes: the lamp still learns that
            the session is up, and every other listener still receives every event. A throwing
            listener once ended the thread that reads the engine's output, and left the
            application waiting for a session that was long running.
        """
        given: 'the application collects what goes wrong, as a Swing application might log it'
            var failures = new CopyOnWriteArrayList<Throwable>()
            var before = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, failed -> failures << failed }

        and: 'its first listener throws at every event'
            var lamp = Lamp.at(host.lampPath())
                           .onEvent { throw new IllegalStateException('a bug in the application') }
                           .onEvent { received << it }
                           .launchedBy(host.launcher).start()

        expect: 'the session comes up anyway'
            lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            lamp.close()

        then: 'the other listener heard the whole session'
            received.any { it instanceof LampEvent.SessionOpened }
            received.any { it instanceof LampEvent.Summary
                           && it.lines().toList().any { line -> line.contains('asked to stop by the application') } }

        and: 'each failure reached the handler'
            failures.size() == received.size()
            failures.every { it.message == 'a bug in the application' }

        cleanup:
            Thread.setDefaultUncaughtExceptionHandler(before)
    }

    def 'An application can leave its lamp running when it closes, until oillamp stop ends it'() {
        reportInfo """
            An agent with jobs on its schedule should keep working after the application (a
            program built on oillamp's Java API) that started it is closed. The application says
            so with `Lamp.leaveRunning()`, which tells the engine on its standard input before
            closing it. The end of the input, which otherwise ends the session, then ends nothing:
            the sandbox runs on its own, and `oillamp status` says so. It ends as any session
            does, with `oillamp stop`.

            Only an application that calls `leaveRunning()` leaves its lamp running. One that
            closes, or crashes, without calling it still ends its session and its sandbox.
        """
        given:
            var lamp = Lamp.at(host.lampPath()).onEvent { received << it }
                           .launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'the application leaves it running, and closes it'
            lamp.leaveRunning()
            lamp.close()
            Thread.sleep(1500)

        then: 'the session goes on, on its own'
            lamp.exitStatus().isEmpty()
            Files.exists(host.lampPath().resolve('.oillamp/session.json'))
            host.oillamp.run('status', host.lampPath().toString()).console()
                   .contains('running on its own')

        when: 'someone stops it'
            var stopped = host.oillamp.run('stop', host.lampPath().toString())
            host.engines.first().waitFor()
            // The lamp records how its engine ended on its own reader thread, a moment after the
            // process is gone.
            for (int i = 0; i < 100 && lamp.exitStatus().isEmpty(); i++) Thread.sleep(50)

        then: 'it ends cleanly, and leaves nothing behind'
            stopped.succeeded()
            lamp.exitStatus() == Optional.of(LampExitStatus.SUCCESS)
            !Files.exists(host.lampPath().resolve('.oillamp/session.json'))
    }

    def 'An application that left its lamp running joins it again, and can stop it'() {
        reportInfo """
            Let's consider an application, a program built on oillamp's Java API, that left its
            lamp running and was closed. Opened again, it finds the session with
            `LampDirectory.isRunning()`, and joins it with `join()`, which follows the session
            with `oillamp follow --embedded`. The lamp it gets is used as one it started: it hears
            what the session reports, runs commands in the sandbox, and asks the agent. Closing it
            only stops following. stop() ends the session, and returns once it has.
        """
        given: 'a lamp left running by an application that has closed'
            var first = Lamp.at(host.lampPath()).launchedBy(host.launcher).start()
            first.awaitRunning(Duration.ofSeconds(30))
            first.leaveRunning()
            first.close()

        when: 'the application opens again, and joins it'
            var starting = Lamp.at(host.lampPath()).onEvent { received << it }.launchedBy(host.launcher)
            var running = starting.isRunning()
            var joined = starting.join()

        then: 'it is running, and usable as before'
            running
            joined.awaitRunning(Duration.ofSeconds(10))
            !joined.holds()
            joined.commandLine('true').first() == 'ssh'
            host.engines.last().arguments == ['follow', host.lampPath().toString(), '--embedded']

        when: 'it stops the session'
            joined.stop()

        then: 'the session has ended, and nothing of it is left'
            joined.exitStatus() == Optional.of(LampExitStatus.SUCCESS)
            !starting.isRunning()
            received.any { it instanceof LampEvent.Summary }
    }

    def 'Joining a lamp that is not running reports `OIL-SESSION-001` as an event'() {
        reportInfo """
            An application, a program built on oillamp's Java API, may try to join every lamp it
            knows with `LampDirectory.join()`. A lamp that is not running is not an error to crash
            on: the joined lamp never comes up, and a `Failure` event with `OIL-SESSION-001` says
            why.
        """
        given: 'a lamp whose session has ended'
            assert host.oillamp.run('at', host.lampPath().toString()).succeeded()

        when:
            var starting = Lamp.at(host.lampPath()).onEvent { received << it }.launchedBy(host.launcher)
            var joined = starting.join()

        then:
            !starting.isRunning()
            !joined.awaitRunning(Duration.ofSeconds(10))
            received.any { it instanceof LampEvent.Failure && it.problem().code().value() == 'OIL-SESSION-001' }
    }

    def 'A command for the sandbox goes over ssh, with each argument arriving exactly as given'() {
        reportInfo """
            An application, a program built on oillamp's Java API, asks `Lamp.commandLine` for the
            command that runs something in the sandbox. ssh joins its arguments with spaces and
            hands them to a shell in the sandbox, which would split "hello world" in two and
            expand a dollar sign. The lamp quotes every argument, so the application passes what
            it means and the sandbox receives that.
        """
        given:
            var lamp = Lamp.at(host.lampPath()).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            var line = lamp.commandLine('echo', 'hello world', 'it\'s $HOME')

        then: 'the engine\'s ssh command, without a terminal'
            line.first() == 'ssh'
            line.contains('-T')

        and: 'then each argument, quoted for the sandbox\'s shell'
            line.takeRight(3) == ["'echo'", "'hello world'", "'it'\\''s \$HOME'"]

        cleanup:
            lamp?.close()
    }

    def 'A command run in the sandbox gets its input, and gives its output, byte for byte'() {
        reportInfo """
            An application, a program built on oillamp's Java API, runs a command in the sandbox
            with `Lamp.exec` to move files in and out, or to speak a protocol with a program
            there, over the command's standard input and output. The lamp starts it with its
            launcher, the part of the lamp that starts processes, as it starts the engine, so a
            lamp launched some other way, as this one is on a simulated machine, runs it there
            too.
        """
        given:
            var lamp = Lamp.at(host.lampPath()).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when: 'it writes what it is given to a file in the agent\'s home, and says where'
            Process writing = lamp.exec('sh', '-c', 'cat > "$1"; echo "$HOME/$1"', 'sh', 'it\'s here.txt')
            writing.outputStream.withCloseable { it.write('hello\nworld'.bytes) }
            var said = writing.inputStream.text.trim()

        then:
            writing.waitFor() == 0
            Files.readString(Path.of(said)) == 'hello\nworld'
            Lamp.agentHome(host.lampPath()).get().resolve('it\'s here.txt') == Path.of(said)

        cleanup:
            lamp?.close()
    }

    def 'An application can show the sandbox\'s desktop, through the socket the lamp names'() {
        reportInfo """
            An application, a program built on oillamp's Java API such as a game, that wants its
            user to watch the agent work draws the sandbox's desktop in a window of its own. The
            desktop is served as VNC on a Unix socket that only this user can open, so no password
            is needed and nothing listens on the network. `Lamp.desktop()` names the socket once
            the session is running; the engine is the one that knows where it is.
        """
        given:
            var lamp = Lamp.at(host.lampPath()).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        expect: 'the desktop socket of this session'
            lamp.desktop().fileName.toString() == 'vnc.sock'
            lamp.desktop().startsWith(host.runtime)

        cleanup:
            lamp?.close()
    }

    def 'Lamp.desktop() throws for a lamp that is not running'() {
        reportInfo """
            Asking for the desktop of a lamp that never started, or has ended, is a mistake in the
            application, the program built on oillamp's Java API. `Lamp.desktop()` throws
            `IllegalStateException` at once instead of handing out a socket path that nothing
            listens on. Here the lamp never started, because oillamp refused its directory, the
            home directory itself.
        """
        given:
            var lamp = Lamp.at(host.home).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            lamp.desktop()

        then:
            thrown(IllegalStateException)

        cleanup:
            lamp?.close()
    }

    def 'An application deletes a lamp it no longer needs through the engine'() {
        reportInfo """
            An application, a program built on oillamp's Java API, that lets its user make lamps,
            one per conversation say, must also let them delete one. Part of a lamp belongs to the
            sandbox's own users, so the application cannot delete the files itself; the engine
            can, and `LampDirectory.remove()` runs `oillamp remove <dir> --yes --embedded`,
            reporting what it did as events, like everything else.
        """
        given: 'a lamp that ran once, and is closed'
            Lamp.at(host.lampPath()).launchedBy(host.launcher).start().withCloseable {
                assert it.awaitRunning(Duration.ofSeconds(30))
            }

        when:
            var status = Lamp.at(host.lampPath()).onEvent { received << it }.launchedBy(host.launcher).remove()

        then: 'it is gone'
            status == LampExitStatus.SUCCESS
            !Files.exists(host.lampPath().resolve('.oillamp'))
            !Files.exists(host.lampPath().resolve('oillamp.toml'))

        and: 'the engine was asked as the command line asks it, confirmed and answering in JSON'
            host.engines.last().arguments == ['remove', host.lampPath().toString(), '--yes', '--embedded']
            !received.isEmpty()
    }

    def 'An application finds the agent\'s home in a lamp, whether the lamp runs or not'() {
        reportInfo """
            What the agent keeps, such as its harness's saved conversations, is in its home, which
            is a directory in the lamp named after the agent's id. An application that shows those
            conversations, even while the sandbox is off, asks `Lamp.agentHome` where that home is
            instead of knowing how oillamp names it. Before the lamp first ran, there is no home
            yet.
        """
        expect: 'no home before the first start'
            Lamp.agentHome(host.lampPath()).isEmpty()

        when: 'the lamp ran once, and is closed'
            Lamp.at(host.lampPath()).launchedBy(host.launcher).start().withCloseable {
                assert it.awaitRunning(Duration.ofSeconds(30))
            }

        then: 'its home is the agent directory, the one holding AGENTS.md'
            var home = Lamp.agentHome(host.lampPath())
            home.isPresent()
            home.get().fileName.toString().startsWith('agent-lamp-')
            Files.exists(home.get().resolve('AGENTS.md'))
    }

    def 'The engine refuses to delete a directory that is not a lamp, and says why'() {
        reportInfo """
            An application, a program built on oillamp's Java API, with a wrong path must not
            delete the wrong thing. The engine refuses a directory that is not a lamp, leaves its
            files where they are, and says so in a `Failure` event the application can show.
        """
        given:
            Files.createDirectories(host.lampPath())
            Files.writeString(host.lampPath().resolve('notes.txt'), 'mine')

        when:
            var status = Lamp.at(host.lampPath()).onEvent { received << it }.launchedBy(host.launcher).remove()

        then:
            status != LampExitStatus.SUCCESS
            Files.exists(host.lampPath().resolve('notes.txt'))
            received.any { it instanceof LampEvent.Failure && it.problem().whatHappened().contains('not an oillamp lamp') }
    }

    def 'Lamp.commandLine throws for a lamp that is not running, rather than giving an ssh command that cannot connect'() {
        reportInfo """
            Here the lamp never started, because oillamp refused its directory, the home directory
            itself. An application, a program built on oillamp's Java API, that asked it for a
            command line anyway would otherwise get an ssh process that fails with an error about
            sockets, which says nothing about the real cause. Refusing at once with
            `IllegalStateException`, saying the lamp is not running, points at what actually went
            wrong, and the application can show the problem the events already carried.
        """
        given:
            var lamp = Lamp.at(host.home).launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            lamp.commandLine('true')

        then:
            thrown(IllegalStateException)

        cleanup:
            lamp?.close()
    }

    def 'The engine is started with the lamp directory, in embedded mode'() {
        reportInfo """
            The lamp runs the same engine a person runs on a terminal, as `oillamp at <dir>`.
            `--embedded` is what makes it suitable for an application, a program built on
            oillamp's Java API: no windows on the user's desktop, one event per line of JSON on
            standard output for the application to read, and a session that ends when the
            application closes the engine's standard input. Without that one option, the
            application would get a terminal window it never asked for, and output it could not
            read.
        """
        given:
            var lamp = Lamp.at(host.lampPath()).launchedBy(host.launcher).start()

        expect:
            host.engines.first().arguments == ['at', host.lampPath().toString(), '--embedded']

        cleanup:
            lamp?.close()
    }

    def 'A lamp whose engine refuses to start reports the problem as an event, and does not claim to be running'() {
        reportInfo """
            An application, a program built on oillamp's Java API, needs to tell its user what
            went wrong, in the same words oillamp uses on a terminal: the problem, with its
            evidence and fixes, arrives as an event. Here the lamp directory is the home directory
            itself, which oillamp refuses with `OIL-LAMP-003`.
        """
        given:
            var lamp = Lamp.at(host.home).onEvent { received << it }
                           .launchedBy(host.launcher).start()

        expect:
            !lamp.awaitRunning(Duration.ofSeconds(30))
            lamp.exitStatus().isPresent()
            lamp.exitStatus().get() != LampExitStatus.SUCCESS
            received.any { it instanceof LampEvent.Failure && it.problem().code().value() == 'OIL-LAMP-003' }

        cleanup:
            lamp?.close()
    }
}
