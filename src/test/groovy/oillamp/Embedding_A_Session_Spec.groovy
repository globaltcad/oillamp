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

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

@Title('Embedding a session')
@Narrative('''
A session started by a program built on oillamp, such as a game that hosts an agent, rather than by
a person at a terminal.

The program starts `oillamp at <dir> --embedded` as a child process, and keeps its standard input
open for as long as it wants the sandbox. It has its own ways into the sandbox, so oillamp opens no
windows. The simulated machine plays the program: it closes oillamp's standard input after a while,
as a real program does when it is done or when it dies.
''')
@Subject(OilLamp)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class Embedding_A_Session_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def '`oillamp at --embedded` opens no windows, and ends when the program that started it closes its standard input'() {
        reportInfo """
            Let's consider a program built on oillamp, such as a game that hosts an agent, that
            starts `oillamp at <dir> --embedded` as a child process, and has its own ways into the
            sandbox. A window appearing on the player's desktop would surprise them, and they would
            close a terminal nobody asked for, so oillamp opens neither. The session is up as soon
            as the sandbox answers.

            It ends when oillamp's standard input closes. That happens when the program is done,
            and also when it crashes, because the operating system then closes the pipe. So the
            sandbox never outlives the program that wanted it.
        """
        when: '`oillamp at --embedded` runs until the program closes its standard input'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--embedded')

        then: 'the session ran, and ended with success'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'no window was opened'
            outcome.events().findAll { it instanceof LampEvent.WindowOpened }.isEmpty()

        and: 'it was running without a terminal connected'
            outcome.events().any { it instanceof LampEvent.SessionStateChanged
                                   && it.status().state() == 'running' }

        and: 'its `SessionOpened` event tells the program how to run commands in the sandbox: over ssh, without a terminal'
            var opened = outcome.events().find { it instanceof LampEvent.SessionOpened }
            opened.command().first() == 'ssh'
            opened.command().toList().contains('-T')
            opened.command().any { it.contains('UNIX-CONNECT:') && it.endsWith('/ssh.sock') }

        and: 'it ended because the program closed its standard input, and removed its session file'
            outcome.events().any { it instanceof LampEvent.Summary
                                   && it.lines().toList().any { line ->
                                          line.contains('asked to stop by the application') } }
            !Files.exists(host.lampPath().resolve('.oillamp/session.json'))
    }

    def '`oillamp at --embedded` writes one JSON event per line on standard output, and nothing else'() {
        reportInfo """
            Let's consider a program built on oillamp, such as a game, that starts
            `oillamp at <dir> --embedded` as a child process, and reads its standard output to show
            the player what the sandbox is doing. A banner, a coloured line or a progress spinner
            in between would be a line it cannot read. So in embedded mode, every line is one event
            written as JSON, which `LampEvent.fromJson` reads back, and every event is a line.
        """
        when: '`oillamp at --embedded` runs a session'
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
            embedded session, started by a program built on oillamp with
            `oillamp at <dir> --embedded`, never has that terminal, so the timeout must not apply,
            or every embedded session would end after a minute. Here the timeout is one second,
            and the program keeps the session for three.
        """
        given: 'a terminal timeout of one second, and a program that keeps the session for three'
            host.machine { it.clockRuns().applicationLeavesAfter(Duration.ofSeconds(3)) }
            host.givenConfig(host.lampPath(), """
                schema_version = 1
                [timeouts]
                terminal_connect_seconds = 1
            """.stripIndent())

        when: '`oillamp at --embedded` runs the session'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--embedded')

        then: 'it ended with success, without `OIL-TERM-002`'
            outcome.status() == LampExitStatus.SUCCESS
            !outcome.reported('OIL-TERM-002')
    }

    def 'An embedded session needs no graphical session on the host'() {
        reportInfo """
            An embedded session, started by a program built on oillamp with
            `oillamp at <dir> --embedded`, opens no windows, so it has no use for the host's
            display. Such a program may well run where there is none: under tmux, on a server, over
            a plain SSH login. So oillamp does not refuse to start there with `OIL-HOST-003`, as it
            refuses a session that would open windows.
        """
        given: 'a machine with no desktop session'
            host.machine { it.noGraphicalSession() }

        when: '`oillamp at --embedded` runs a session'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--embedded')

        then: 'the session ran, without `OIL-HOST-003`'
            outcome.status() == LampExitStatus.SUCCESS
            !outcome.reported('OIL-HOST-003')
    }
}
