package oillamp

import dev.lamp.Lamp
import dev.lamp.LampPrompt
import dev.lamp.LampEvent
import dev.lamp.LampAgentProgress
import dev.lamp.LampRunOutcome
import dev.lamp.LampFailedException
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Title('Following a run')
@Narrative('''
Following a run as it happens, and stopping it: `Lamp.send`, `RunProgress`, `Lamp.cancel` and
`Lamp.agentStatus`, and on the command line `oillamp ask --no-wait` and `oillamp cancel`.

A chat app does not wait in silence while the agent works. It hands over the question, gets back
the run that answers it, and then draws the answer as it is written, each tool as it runs, and the
end. It shows whether the agent is busy, and has a button to stop it.

The program here holds its lamp through the `Lamp` API, as a real chat app would. The stand-in pi
streams its answers the way pi does.
''')
@Subject([Lamp, OilLamp])
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class Following_A_Run_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    Path lamp
    Lamp held
    final List<LampEvent> heard = new java.util.concurrent.CopyOnWriteArrayList<>()
    final List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>()
    final CountDownLatch release = new CountDownLatch(1)

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
        lamp = host.lampPath()
        // Each question is saved as its run ends, as a job's run is, so that its end carries its snapshot.
        host.givenConfig(lamp, 'schema_version = 1\n[autosave]\nchat_quiet_minutes = 0\n')
        assert host.oillamp.run('at', lamp.toString()).succeeded()
        host.machine { it.agent { String prompt ->
            prompts << prompt
            if (prompt.startsWith('wait')) release.await()
            if (prompt.startsWith('forever')) Thread.sleep(60_000)
            if (prompt.startsWith('files')) {
                Files.writeString(Lamp.agentHome(lamp).orElseThrow().resolve('workspace/list.txt'), 'a\nb\n')
                return '⚙ bash: ls ~/workspace\nThere are two files here.'
            }
            'Done: ' + prompt
        } }
        held = Lamp.at(lamp).launchedBy(host.launcher).onEvent { heard << it }.start()
        assert held.awaitRunning(Duration.ofSeconds(30))
    }

    def cleanup() {
        release.countDown()
        held?.close()
    }

    def 'A program built on oillamp follows a run with `Lamp.send` and `RunProgress`: the answer as it is written, and each tool as it runs'() {
        reportInfo """
            Let's consider a chat app built on oillamp, which holds its lamp through oillamp's Java
            API, `Lamp`. It hands over a question with `Lamp.send`, which at once returns the run
            that will answer it. The events of that run reach every listener the lamp was given
            with `onEvent`, whoever asked: which conversation the run is in, each tool the agent
            runs and what the tool put out, what the model thinks, the answer piece by piece, and
            the complete message. Put together, the pieces are exactly the answer the run ends
            with.
        """
        when: '`Lamp.send` hands over "files, please", and the run finishes'
            var run = held.send(LampPrompt.fresh('files, please'))
            var finished = waitFor(LampEvent.RunFinished) { it.run().id() == run.id() }

        then: 'the send came back with the run, before the run was done'
            run.prompt() == 'files, please'

        and: 'the `RunProgress` events came in the order it happened: opened, the tool, the thought, the pieces of the answer, the answer'
            var steps = progressOf(run.id())
            steps.first() instanceof LampAgentProgress.Opened
            steps.first().conversation() == finished.conversation().orElseThrow()
            var tool = steps.find { it instanceof LampAgentProgress.ToolStarted }
            tool.tool() == 'bash'
            tool.summary() == 'ls ~/workspace'
            steps.find { it instanceof LampAgentProgress.ToolFinished }.call() == tool.call()
            steps.find { it instanceof LampAgentProgress.Thought }.text() == 'Thinking it over.'
            steps.findAll { it instanceof LampAgentProgress.Said }*.text().join('') == 'There are two files here.'
            steps.last() instanceof LampAgentProgress.Answered
            steps.last().text() == 'There are two files here.'

        and: 'the run ends with the same answer, and with a snapshot'
            finished.outcome() == LampRunOutcome.FINISHED
            finished.answer() == 'There are two files here.'
            finished.snapshot().isPresent()
    }

    def '`Lamp.agentStatus` names the run the agent works on, and the runs waiting behind it'() {
        reportInfo """
            A program built on oillamp shows whether the agent is busy, for example to mark a
            question as waiting. `Lamp.agentStatus` names the run in progress and the ones queued
            behind it, and says the agent is idle once they are done.
        """
        when: '`Lamp.send` hands over a question the agent works on until it is let go, then a second; `agentStatus` is asked while the agent works, and again once both are done'
            var first = held.send(LampPrompt.fresh('wait for me'))
            waitFor(LampEvent.RunStarted) { it.run().id() == first.id() }
            var second = held.send(LampPrompt.fresh('then me'))
            var busy = held.agentStatus()
            release.countDown()
            waitFor(LampEvent.RunFinished) { it.run().id() == second.id() }
            var idle = held.agentStatus()

        then: 'while it worked, the agent was busy with the first run, and the second waited'
            busy.busy()
            busy.current().map { it.id() } == Optional.of(first.id())
            busy.waiting()*.id() == [second.id()]
            heard.any { it instanceof LampEvent.RunQueued && it.run().id() == second.id() }

        and: 'once both were done, the agent was idle'
            !idle.busy()
    }

    def '`Lamp.cancel` stops the run in progress, and a run still waiting never reaches the agent'() {
        reportInfo """
            A program built on oillamp has a stop button that calls `Lamp.cancel`. The session
            tells the run in progress to stop, and saves what it did until then in a snapshot, as
            for any run, with a message that says it was cancelled. Given a run's id,
            `Lamp.cancel` takes a run that still waits out of the queue, and the agent never sees
            its question. Called when nothing runs, it throws `LampFailedException`, saying the
            agent is not working on anything.
        """
        when: 'a question the agent works on for a minute is sent, then a second; `Lamp.cancel` is given the second\'s id, and then called without one'
            var working = held.send(LampPrompt.fresh('forever and ever'))
            waitFor(LampEvent.RunStarted) { it.run().id() == working.id() }
            var waiting = held.send(LampPrompt.fresh('never asked'))
            held.cancel(waiting.id())
            held.cancel()
            var stopped = waitFor(LampEvent.RunFinished) { it.run().id() == working.id() }

        then: 'the waiting run ended `CANCELLED`, without the agent seeing its question'
            var skipped = heard.find { it instanceof LampEvent.RunFinished && it.run().id() == waiting.id() }
            skipped.outcome() == LampRunOutcome.CANCELLED
            !prompts.contains('never asked')

        and: 'the run in progress ended `CANCELLED`, and its snapshot says it was cancelled'
            stopped.outcome() == LampRunOutcome.CANCELLED
            stopped.snapshot().map { it.message().contains('cancelled') } == Optional.of(true)

        when: 'nothing runs any more, and `Lamp.cancel` is called'
            held.cancel()

        then: 'it throws `LampFailedException`: the agent is not working on anything'
            var failed = thrown(LampFailedException)
            failed.problem().whatHappened().contains('the agent is not working on anything')
    }

    def 'From a terminal, `oillamp ask --no-wait` hands over a question and `oillamp cancel` stops its run'() {
        reportInfo """
            Someone at a terminal, or a script, can follow and stop a run too:
            `oillamp ask --no-wait` says which run will answer, and returns at once;
            `oillamp status` says which run the agent is working on; and `oillamp cancel` stops it.
            Cancelling again, when nothing runs, is reported as `OIL-SESSION-003`.
        """
        when: '`oillamp ask --no-wait` hands over a long question, `oillamp status` and `oillamp cancel` are run while the agent works on it, and `oillamp cancel` once more after the run ended'
            var asked = host.oillamp.run('ask', lamp.toString(), '--no-wait', 'forever, please')
            var run = asked.events().find { it instanceof LampEvent.RunAccepted }.run()
            waitFor(LampEvent.RunStarted) { it.run().id() == run.id() }
            var status = host.oillamp.run('status', lamp.toString())
            var cancelled = host.oillamp.run('cancel', lamp.toString(), run.id())
            waitFor(LampEvent.RunFinished) { it.run().id() == run.id() }
            var nothing = host.oillamp.run('cancel', lamp.toString())

        then: 'each command says what it did, and the second `cancel` reports `OIL-SESSION-003`'
            asked.succeeded()
            asked.console().contains('the agent answers this as ' + run.id())
            status.console().contains('working on ' + run.id())
            cancelled.succeeded()
            cancelled.console().contains('cancelled ' + run.id())
            nothing.reported('OIL-SESSION-003')
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private List<LampAgentProgress> progressOf(String run) {
        heard.findAll { it instanceof LampEvent.RunProgress && it.run() == run }*.progress()
    }

    private <T extends LampEvent> T waitFor(Class<T> kind, Closure<Boolean> which = { true }) {
        var deadline = System.currentTimeMillis() + 60_000
        while (true) {
            var found = heard.find { kind.isInstance(it) && which(it) }
            if (found != null) return (T) found
            assert System.currentTimeMillis() < deadline : "no ${kind.simpleName} came"
            Thread.sleep(20)
        }
    }
}
