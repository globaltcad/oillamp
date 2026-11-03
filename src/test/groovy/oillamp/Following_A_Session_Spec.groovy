package oillamp

import dev.lamp.LampExitStatus
import dev.lamp.Lamp
import dev.lamp.LampPrompt
import dev.lamp.LampEvent
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Title('Following a session')
@Narrative('''
The events of a session go to the program that started it: the terminal where `oillamp at` runs,
or the application that started it through oillamp's Java API. Someone else may want to see them
too, such as an application that closed and opened again, or a person at another terminal.

`oillamp follow <lamp>` asks the session's *supervisor*, the `oillamp` process that runs the
session, for its events over the session's control socket. The supervisor first sends what is
going on now, then each event as it happens, until the session ends.

In these scenarios an application holds the session, started with `Lamp.at(dir).start()`. The
agent is a stand-in that answers each prompt with "Done: " and the prompt, and that waits for the
scenario's signal before it answers a prompt beginning with "wait".
''')
@Subject(OilLamp)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class Following_A_Session_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    Path lamp
    Lamp held
    final List<LampEvent> followed = new CopyOnWriteArrayList<>()
    final CountDownLatch release = new CountDownLatch(1)

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
        lamp = host.lampPath()
        assert host.oillamp.run('at', lamp.toString()).succeeded()
        host.machine { it.agent { String prompt ->
            if (prompt.startsWith('wait')) release.await()
            'Done: ' + prompt
        } }
        held = Lamp.at(lamp).launchedBy(host.launcher).start()
        assert held.awaitRunning(Duration.ofSeconds(30))
    }

    def cleanup() {
        release.countDown()
        held?.close()
    }

    def '`oillamp follow`, started late, reports the runs in progress first, then each event as it happens'() {
        reportInfo """
            Let's consider an agent that has answered one question, and is at work on a second,
            with a third waiting its turn, when `oillamp follow <lamp>` starts. The session does
            not repeat its past to the follower, such as the first question's run,
            only its present: the `SessionOpened` event, which says how to reach the sandbox, the
            run in progress from its start, the run waiting, and last the agent's status, which
            marks the end of the catching up. After that, `follow` reports every event as it
            happens: both runs finishing, and at last the session's summary as it ends, which ends
            `follow` too.
        """
        given: 'the agent has answered "done before", is at work on the question "wait for me", and "and then this" waits'
            var past = held.ask('done before').run()
            var first = held.send(LampPrompt.fresh('wait for me'))
            var second = held.send(LampPrompt.fresh('and then this'))
            eventually { held.agentStatus().waiting().any { it.id() == second.id() } }

        when: '`oillamp follow <lamp>` starts, on a thread of its own'
            var following = follow()

        then: 'it reports the `SessionOpened` event, the run in progress and the run waiting'
            eventually { followed.any { it instanceof LampEvent.SessionOpened } }
            eventually { followed.any { it instanceof LampEvent.RunStarted && it.run().id() == first.id() } }
            eventually { followed.any { it instanceof LampEvent.RunQueued && it.run().id() == second.id() } }

        and: 'last, the agent\'s status, with the run in progress and the one waiting, which ends the catching up'
            eventually { followed.any { it instanceof LampEvent.AgentStatus } }
            var status = followed.find { it instanceof LampEvent.AgentStatus }
            status.current().get().id() == first.id()
            status.waiting()*.id() == [second.id()]
            followed.indexOf(status) > followed.findIndexOf { it instanceof LampEvent.RunQueued }

        and: 'no `RunQueued`, `RunStarted` or `RunFinished` for the run of "done before", which ended before `follow` started'
            followed.findAll { it instanceof LampEvent.RunQueued || it instanceof LampEvent.RunStarted
                               || it instanceof LampEvent.RunFinished }
                    .every { it.run().id() != past.id() }

        when: 'the scenario lets the agent answer'
            release.countDown()

        then: 'it reports both runs finishing, with the second run\'s answer'
            eventually { finished(first.id()) && finished(second.id()) }
            followed.find { it instanceof LampEvent.RunFinished && it.run().id() == second.id() }
                    .answer() == 'Done: and then this'

        when: 'the application closes its `Lamp`, which ends the session'
            held.close()
            following.join(30_000)

        then: '`follow` ends with success, after reporting the session\'s summary'
            following.outcome.status() == LampExitStatus.SUCCESS
            followed.any { it instanceof LampEvent.Summary }
    }

    def 'When an application stops following a session, the session keeps running'() {
        reportInfo """
            An application built on oillamp's Java API follows a session by running
            `oillamp follow <lamp> --embedded` as a child process. It stops following by closing
            that process's standard input, the same signal that ends a session it started with
            `oillamp at --embedded`. Let's consider a session that another application holds:
            closing the standard input of `follow` ends only the following, and the session keeps
            running.
        """
        when: '`oillamp follow <lamp> --embedded` runs, and its standard input closes a second later'
            host.machine { it.standardInput(closingAfter(Duration.ofSeconds(1))) }
            var outcome = host.oillamp.run('follow', lamp.toString(), '--embedded')

        then: '`follow` ended with success, after reporting the `SessionOpened` event'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.events().any { it instanceof LampEvent.SessionOpened }

        and: 'the session still runs: it has no exit status, and reports the agent\'s status'
            held.exitStatus().isEmpty()
            held.agentStatus() != null
    }

    def '`oillamp follow` reports that no session runs, rather than waiting for one'() {
        reportInfo """
            `oillamp follow <lamp>`, for a lamp whose session has ended, reports `OIL-SESSION-001`,
            as `oillamp status` does, rather than waiting for a session that may never come.
        """
        given: 'the application closes its `Lamp`, which ends the session'
            held.close()

        when: '`oillamp follow <lamp>` is run'
            var outcome = host.oillamp.run('follow', lamp.toString())

        then: 'it fails, and reports `OIL-SESSION-001`'
            !outcome.succeeded()
            outcome.reported('OIL-SESSION-001')
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** `oillamp follow <lamp>` on a thread of its own, as a terminal would run it. */
    private Following follow() {
        var following = new Following()
        following.thread = Thread.start {
            following.outcome = host.oillamp.observedBy { followed << it }.run('follow', lamp.toString())
        }
        following
    }

    private static class Following {
        Thread thread
        volatile dev.oillamp.OilLamp.Outcome outcome
        void join(long millis) { thread.join(millis) }
    }

    /** A standard input that ends after `delay`, as an application's does when it closes. */
    private static InputStream closingAfter(Duration delay) {
        new InputStream() {
            @Override int read() {
                Thread.sleep(delay.toMillis())
                -1
            }
        }
    }

    private boolean finished(String run) {
        followed.any { it instanceof LampEvent.RunFinished && it.run().id() == run }
    }

    private static void eventually(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 60_000
        while (!condition()) {
            assert System.currentTimeMillis() < deadline : 'it never happened'
            Thread.sleep(20)
        }
    }
}
