package oillamp

import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampFailedException
import dev.lamp.LampSandbox
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

@Title('Finding the lamps that run on a machine, whoever started them')
@Narrative('''
An application that embeds oillamp knows the lamps it made. A person who uses oillamp from a
terminal makes others, anywhere on the machine, and an application that wants to show them too
needs a way to find them. `Lamp.running()` answers which sandboxes run on the machine, each with
its lamp's directory, which the application can then join like any lamp of its own.

oillamp keeps no list of its lamps for this. It asks podman for the containers that carry the
labels oillamp gives every sandbox, the same question `oillamp list` asks. A list of its own
would be wrong exactly when it matters: after a session was killed without tidying up.

The scenarios run oillamp's real engine in this JVM, on a simulated machine whose podman keeps
track of the containers started on it.
''')
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class FindingRunningLampsSpec extends Specification {

    @TempDir Path tmp
    ScenarioHost host
    /// The sessions a scenario started from the command line, each on a thread of its own.
    final List<Thread> sessions = []
    /// What those sessions reported, from their threads.
    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(60)) }
    }

    def cleanup() {
        for (Path lamp : [host.lampPath('north'), host.lampPath('south')])
            host.oillamp.run('stop', lamp.toString())
        sessions.each { it.join(20_000) }
    }

    def '`Lamp.running` finds no sandbox on a machine where none runs'() {
        reportInfo '''
            Let's consider a machine on which oillamp is installed but no lamp is lit. podman runs
            no container with oillamp's labels, so `Lamp.running` returns an empty list rather
            than failing: no sandbox is an answer like any other.
        '''
        expect:
            Lamp.running(host.launcher).isEmpty()
    }

    def 'A lamp a person lit with `oillamp at` is found by its directory, and is gone once it went out'() {
        reportInfo '''
            In this scenario a person lights a lamp from their terminal with `oillamp at <dir>`, in
            a directory no application knows of. An application asks `Lamp.running`, and the lamp
            is there: its container's name, podman's word for its state, and the lamp's
            directory, which is all the application needs to join it. Once the person ran
            `oillamp stop`, the sandbox is gone, and so is the lamp from the list.
        '''
        given: 'a person lit the lamp "north" from a terminal'
            Path north = host.lampPath('north')
            lightFromTheCommandLine(north)

        when:
            List<LampSandbox> running = Lamp.running(host.launcher)

        then: 'the lamp is listed by its directory'
            running*.lamp() == [north]
            running.first().state() == 'running'
            running.first().container().startsWith('oillamp-')

        when: 'the person puts it out'
            host.oillamp.run('stop', north.toString())
            sessions.each { it.join(20_000) }

        then:
            Lamp.running(host.launcher).isEmpty()
    }

    def 'Lamps lit by a person and by an application are each listed, by their own directory'() {
        reportInfo '''
            A machine may run several lamps at once, made in different ways: here "north" was lit
            by a person from a terminal, and "south" by an application through `Lamp.at(dir)
            .start()`. `Lamp.running` lists both, each with its own directory, since the label on
            each sandbox names the lamp it belongs to. Closing the application's lamp ends its
            session, and the list then holds the person's alone.
        '''
        given: 'a person lit "north", and an application lit "south"'
            Path north = host.lampPath('north')
            Path south = host.lampPath('south')
            lightFromTheCommandLine(north)
            // Each lamp has an agent id of its own, which names its container.
            host.machine { it.generatedAgentId('southabc') }
            Lamp lamp = Lamp.at(south).launchedBy(host.launcher).start()
            assert lamp.awaitRunning(Duration.ofSeconds(60))

        expect: 'both are listed'
            Lamp.running(host.launcher)*.lamp() as Set == [north, south] as Set

        when: 'the application closes its lamp'
            lamp.close()

        then: 'only the person\'s is left'
            Lamp.running(host.launcher)*.lamp() == [north]
    }

    def 'A container that does not say which lamp it belongs to is left out'() {
        reportInfo '''
            In the rare case that podman runs a container with oillamp's `oillamp.agent-id` label
            but without `oillamp.lamp`, such as one a person started by hand from an oillamp
            image, there is no directory to join it by. `Lamp.running` leaves it out rather than
            listing a lamp at an empty path.
        '''
        given: 'podman runs one sandbox of a lamp and one container without a lamp'
            host.machine { it.commandSucceeding('podman ps', '''[
                {"Names":["oillamp-k3v7x2ab"],"State":"running",
                 "Labels":{"oillamp.agent-id":"k3v7x2ab","oillamp.lamp":"/home/dev/lamps/east"}},
                {"Names":["hand-made"],"State":"running","Labels":{"oillamp.agent-id":"x"}}
            ]''') }

        expect:
            Lamp.running(host.launcher) == [new LampSandbox('oillamp-k3v7x2ab', 'running', Path.of('/home/dev/lamps/east'))]
    }

    def 'When podman cannot be asked, `Lamp.running` throws with podman\'s own words'() {
        reportInfo '''
            An empty list would say that no lamp runs, which is not known when podman did not
            answer. So `Lamp.running` throws a `LampFailedException` instead, whose problem
            names the command that failed and quotes what podman said as its evidence, for the
            application to show.
        '''
        given:
            host.machine { it.commandFailing('podman ps', 125, 'Error: cannot connect to Podman socket') }

        when:
            Lamp.running(host.launcher)

        then:
            var failed = thrown(LampFailedException)
            failed.problem().whatHappened() == 'podman ps exited with code 125'
            failed.problem().evidence().any { it.toString().contains('Error: cannot connect to Podman socket') }
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /// Lights a lamp as a person does, with `oillamp at <dir>`, and waits until it is up.
    private void lightFromTheCommandLine(Path lamp) {
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        sessions << Thread.start { oillamp.run('at', lamp.toString()) }
        waitUntil { reported.count { it instanceof LampEvent.Summary && it.title() == 'your session is up' } >= sessions.size() }
    }

    private static void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw new AssertionError("never happened: ${condition}" as Object)
    }
}
