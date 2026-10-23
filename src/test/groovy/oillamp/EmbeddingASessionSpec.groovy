package oillamp

import dev.lamp.LampExitStatus
import dev.lamp.LampEvent
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 *  A session started by another application, such as a game that hosts an agent, rather than by
 *  a person at a terminal.
 *
 *  <p>The application starts {@code oillamp at <dir> --embedded} as a child process and keeps
 *  its standard input open for as long as it wants the sandbox. It has its own ways into the
 *  sandbox, so oillamp opens no windows. The simulated machine plays the application: it closes
 *  oillamp's standard input after a while, as a real one does when it is done or when it dies.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class EmbeddingASessionSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'An embedded session opens no windows, and ends when the application closes oillamp\'s standard input'() {
        reportInfo """
            Let's consider an application, such as a game that hosts an agent, that starts
            `oillamp at <dir> --embedded` as a child process and has its own ways into the
            sandbox. A window popping up on the player's desktop would be a surprise, and a
            terminal that nobody asked for would be closed, so oillamp opens neither. The session
            is up as soon as the sandbox answers.

            It ends when oillamp's standard input closes. That happens when the application is
            done, and also when it crashes, because the operating system closes the pipe for it.
            So the sandbox never outlives the application that wanted it.
        """
        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--embedded')

        then: 'the session ran and ended cleanly'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'no window was opened'
            outcome.events().findAll { it instanceof LampEvent.WindowOpened }.isEmpty()

        and: 'it was running without anyone connecting a terminal'
            outcome.events().any { it instanceof LampEvent.SessionStateChanged
                                   && it.status().state() == 'running' }

        and: 'it told the application how to run commands in the sandbox: over ssh, without a terminal'
            var opened = outcome.events().find { it instanceof LampEvent.SessionOpened }
            opened.command().first() == 'ssh'
            opened.command().toList().contains('-T')
            opened.command().any { it.contains('UNIX-CONNECT:') && it.endsWith('/ssh.sock') }

        and: 'it ended because the application closed its standard input, and removed its session file'
            outcome.events().any { it instanceof LampEvent.Summary
                                   && it.lines().toList().any { line ->
                                          line.contains('asked to stop by the application') } }
            !Files.exists(host.lampPath().resolve('.oillamp/session.json'))
    }

    def 'An embedded oillamp reports on standard output in JSON, one event per line, and nothing else'() {
        reportInfo """
            Let's consider an application, such as a game, that starts `oillamp at <dir>
            --embedded` as a child process and reads its standard output to show the player what
            the sandbox is doing. A banner, a coloured line or a progress spinner in between would
            be a line it cannot read, so in embedded mode every line is one event written as JSON,
            which `LampEvent.fromJson` reads back, and every event is a line.
        """
        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--embedded')

        then: 'every line of output reads back as an event'
            var lines = outcome.console().readLines()
            var read = lines.collect { LampEvent.fromJson(it) }
            read.every { it.present }

        and: 'and together they are exactly what the session reported, in order'
            read.collect { it.get() } == outcome.events().toList()
    }

    def 'Oillamp does not end an embedded session because no terminal connects to it'() {
        reportInfo """
            Oillamp ends a normal session that no terminal connects to after
            `timeouts.terminal_connect_seconds`, with `OIL-TERM-002`, because nobody is in it. An
            embedded session, started by an application with `oillamp at <dir> --embedded`, never
            has that terminal, so the timeout must not apply, or every embedded session would end
            after a minute. Here the timeout is one second, and the application keeps the session
            for three.
        """
        given: 'a short terminal timeout, and an application that keeps the session longer'
            host.machine { it.clockRuns().applicationLeavesAfter(Duration.ofSeconds(3)) }
            host.givenConfig(host.lampPath(), """
                schema_version = 1
                [timeouts]
                terminal_connect_seconds = 1
            """.stripIndent())

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--embedded')

        then:
            outcome.status() == LampExitStatus.SUCCESS
            !outcome.reported('OIL-TERM-002')
    }

    def 'An embedded session needs no graphical session on the host'() {
        reportInfo """
            An embedded session, started by an application with `oillamp at <dir> --embedded`,
            opens no windows, so it has no use for the host's display. An application may well run
            where there is none: under tmux, on a server, over a plain SSH login. Refusing to
            start there would be refusing for a reason that does not apply.
        """
        given: 'a machine with no desktop session'
            host.machine { it.noGraphicalSession() }

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--embedded')

        then: 'the session runs, and nobody is told a display is missing'
            outcome.status() == LampExitStatus.SUCCESS
            !outcome.reported('OIL-HOST-003')
    }
}
