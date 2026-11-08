package oillamp

import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampFailedException
import dev.lamp.LampSandbox
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

@Title('Finding the lamps that run on a machine, whoever started them')
@Narrative('''
An application built on oillamp's Java API knows the lamps it started. Someone who uses oillamp
from a terminal starts sessions for other lamps, anywhere on the machine, and an application that
wants to show those too needs a way to find them. `Lamp.running()` lists the sandboxes that run on
the machine, each with its lamp's directory, by which the application can join the session as it
joins one of its own.

oillamp keeps no list of its lamps for this. It asks podman for the containers that carry the
labels oillamp gives every sandbox, the same question `oillamp list` asks. A list of its own
would be wrong exactly when it matters: after a session was killed without tidying up.

The scenarios run oillamp's real engine in this JVM, on a simulated machine whose podman keeps
track of the containers started on it.
''')
@Subject(Lamp)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class Finding_Running_Lamps_Spec extends Specification {

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
        reportInfo """
            Let's consider a machine on which oillamp is installed but no session runs. podman runs
            no container with oillamp's labels, so `Lamp.running` returns an empty list rather than
            failing: no sandbox is an answer like any other.
        """
        expect:
            Lamp.running(host.launcher).isEmpty()
    }

    def 'A session started with `oillamp at` is found by its lamp\'s directory, and is gone from the list once it has ended'() {
        reportInfo """
            Let's consider a session started from a terminal with `oillamp at <dir>`, for a lamp in
            a directory no application knows of. `Lamp.running` lists its sandbox: the container's
            name, podman's word for its state, and the lamp's directory, which is all an
            application needs to join the session. Once `oillamp stop` has ended the session, the
            sandbox is gone, and so is its entry in the list.
        """
        given: 'a session runs for the lamp "north", started from a terminal with `oillamp at`'
            Path north = host.lampPath('north')
            lightFromTheCommandLine(north)

        when: '`Lamp.running` is called'
            List<LampSandbox> running = Lamp.running(host.launcher)

        then: 'it lists the sandbox by its lamp\'s directory, as `running`, in a container named `oillamp-…`'
            running*.lamp() == [north]
            running.first().state() == 'running'
            running.first().container().startsWith('oillamp-')

        when: '`oillamp stop` ends the session'
            host.oillamp.run('stop', north.toString())
            sessions.each { it.join(20_000) }

        then: '`Lamp.running` lists no sandbox'
            Lamp.running(host.launcher).isEmpty()
    }

    def 'Sandboxes started from a terminal and by an application are each listed, by their own lamp\'s directory'() {
        reportInfo """
            A machine may run several sessions at once, started in different ways. Let's consider
            the lamp "north", started from a terminal with `oillamp at`, and the lamp "south",
            started by an application with `Lamp.at(dir).start()`. `Lamp.running` lists both, each
            with its own directory, because the `oillamp.lamp` label on each sandbox names the lamp
            it belongs to. Closing the application's `Lamp` ends its session, and the list then
            holds "north" alone.
        """
        given: 'a session for "north" started from a terminal, and one for "south" started by an application'
            Path north = host.lampPath('north')
            Path south = host.lampPath('south')
            lightFromTheCommandLine(north)
            // Each lamp has an agent id of its own, which names its container.
            host.machine { it.generatedAgentId('southabc') }
            Lamp lamp = Lamp.at(south).launchedBy(host.launcher).start()
            assert lamp.awaitRunning(Duration.ofSeconds(60))

        expect: '`Lamp.running` lists both'
            Lamp.running(host.launcher)*.lamp() as Set == [north, south] as Set

        when: 'the application closes its `Lamp`'
            lamp.close()

        then: 'only "north" is left'
            Lamp.running(host.launcher)*.lamp() == [north]
    }

    def '`Lamp.running` leaves out a container that does not say which lamp it belongs to'() {
        reportInfo """
            In the rare case that podman runs a container with oillamp's label `oillamp.agent-id`
            but without `oillamp.lamp`, such as one started by hand from an oillamp image, there is
            no directory to join it by. `Lamp.running` leaves it out, rather than listing a lamp at
            an empty path.
        """
        given: 'podman runs one sandbox of a lamp and one container without a lamp'
            host.machine { it.commandSucceeding('podman ps', '''[
                {"Names":["oillamp-k3v7x2ab"],"State":"running",
                 "Labels":{"oillamp.agent-id":"k3v7x2ab","oillamp.lamp":"/home/dev/lamps/east"}},
                {"Names":["hand-made"],"State":"running","Labels":{"oillamp.agent-id":"x"}}
            ]''') }

        expect: '`Lamp.running` lists only the sandbox of the lamp "east"'
            Lamp.running(host.launcher) == [new LampSandbox('oillamp-k3v7x2ab', 'running', Path.of('/home/dev/lamps/east'))]
    }

    def 'When podman cannot be asked, `Lamp.running` throws with podman\'s own words'() {
        reportInfo """
            When `podman ps` fails, it is not known whether any lamp runs, and an empty list would
            say that none does. So `Lamp.running` throws a `LampFailedException`. Its problem says
            which command failed, with its exit code and podman's own error line, and quotes what
            podman said as its evidence, for the application to show.
        """
        given: '`podman ps` fails with exit code 125: it cannot reach the Podman socket'
            host.machine { it.commandFailing('podman ps', 125, 'Error: cannot connect to Podman socket') }

        when: '`Lamp.running` is called'
            Lamp.running(host.launcher)

        then: 'it throws a `LampFailedException` naming `podman ps`, its exit code and podman\'s error, with podman\'s message as evidence'
            var failed = thrown(LampFailedException)
            failed.problem().whatHappened() == 'podman ps exited with code 125: cannot connect to Podman socket'
            failed.problem().evidence().any { it.toString().contains('Error: cannot connect to Podman socket') }
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /// Starts a session for `lamp` as from a terminal, with `oillamp at <dir>`, and waits until it is up.
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
