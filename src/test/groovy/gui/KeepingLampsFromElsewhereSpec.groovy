package gui

import dev.gui.Genies
import dev.gui.genie.Shelf
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.Settings
import dev.lamp.Lamp
import dev.lamp.LampEvent
import oillamp.ScenarioHost
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title
import sprouts.Tuple

import javax.swing.SwingUtilities
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Title('Lamps someone made elsewhere, with `oillamp at`, shown in Genies beside its own genies')
@Narrative('''
Genies makes its genies' lamps on its own shelf. A person who also uses oillamp from a terminal
makes lamps elsewhere, with `oillamp at <dir>`, and would like to see those in Genies too. Genies
cannot know of such a lamp until it is lit: then `Lamp.running()` names its directory, and Genies
takes it in by itself, after its own genies, and joins its session. A sleeping one the user adds
by hand, with "Add a lamp from elsewhere…", which calls `Genies.addLamp`.

Genies did not make these lamps, so it does not decide over them: removing one from Genies
leaves it where it is, as it is, and quitting Genies leaves it running.

Here the app is driven as its window drives it, through `Genies`; its lamps are oillamp's real
engine, in this JVM, on a simulated machine, with a stand-in pi. The person's lamp is lit the way
a person lights it, with `oillamp at`, on the same simulated machine.

Each scenario reads on its own, and shows how the window talks to the app: every call to
`Genies`, and every read of its state, is written out where it happens, on Swing's event
thread as the window makes it. Helpers that do not touch the app, such as waiting for a
condition, are fine.
''')
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class KeepingLampsFromElsewhereSpec extends Specification {

    @TempDir Path tmp
    ScenarioHost sandbox
    Genies app
    UUID juniper
    Path shelf
    /// The lamp a person lit from their terminal, in a directory of their own.
    Path north
    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread person

    def setup() {
        sandbox = new ScenarioHost(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(100))
                            .agent { String prompt -> 'You said: ' + prompt } }
        north = sandbox.lampPath('north')
        shelf = sandbox.home.resolve('genies')
        // Genies opens as it closed last, with Juniper on its shelf, never woken, and a key in its environment.
        var genie = Genie.named('Juniper')
        juniper = genie.id()
        app = Genies.open(shelf, Tuple.of(Genie, genie), Settings.defaults(), sandbox.launcher,
                          { name -> name == Settings.KEY_VARIABLE ? Optional.of('sk-key') : Optional.<String>empty() })
    }

    def cleanup() {
        var done = new CountDownLatch(1)
        SwingUtilities.invokeAndWait { app?.quit(false, Optional.empty(), { done.countDown() }) }
        done.await(3, TimeUnit.MINUTES)
        sandbox.oillamp.run('stop', north.toString())
        person?.join(20_000)
    }

    def 'Genies takes in a lamp someone lit with `oillamp at`, after its own genies, and joins its session'() {
        reportInfo '''
            Let's consider a person who lit the lamp "north" from a terminal, while Genies runs
            with its genie Juniper selected. When Genies looks for lamps, which it does by itself
            now and then and `Genies.lookForLamps` does at once, it finds "north" lit. It takes
            it in as a genie named as its directory is, after Juniper, with
            `Genie.elsewhere()` holding the directory, and joins its session, so that it is
            awake in Genies too. Juniper stays selected: the user did not ask for "north" now.
        '''
        given: 'Genies shows Juniper, and a person lit "north" from a terminal'
            SwingUtilities.invokeAndWait { app.state().update { it.select(juniper) } }
            lightFromTheCommandLine(north)

        when:
            SwingUtilities.invokeAndWait { app.lookForLamps() }

        then: '"north" follows Juniper, from elsewhere, and awake'
            waitUntil { app.state().get().genies().size() == 2 }
            var genies = app.state().get().genies()
            genies*.name() == ['Juniper', 'north']
            !genies.get(0).fromElsewhere()
            genies.get(1).elsewhere() == Optional.of(north)
            waitUntil { app.state().get().genies().get(1).phase().isAwake() }

        and: 'Juniper is still the one shown'
            app.state().get().selected() == juniper

        and: 'the person\'s session goes on, joined, not taken over'
            Lamp.running(sandbox.launcher)*.lamp() == [north]
    }

    def 'Genies joins a lamp made elsewhere although it has no outbox, and leaves its session running'() {
        reportInfo '''
            A lamp a person made with `oillamp at` has no `~/outbox` and `~/inbox`: Genies makes
            those as it lights its own genies, not as it joins a lamp. In the case that got this
            scenario written, looking into the missing outbox failed, Genies took the failure for
            a broken lamp and put it out, and so ended the person's session. Genies must join the
            lamp, show no files to save, and never end a session it joined.
        '''
        given: 'a person lit "north", whose home has no outbox'
            lightFromTheCommandLine(north)
            assert !Files.exists(Lamp.agentHome(north).orElseThrow().resolve('outbox'))

        when:
            SwingUtilities.invokeAndWait { app.lookForLamps() }

        then: 'Genies joined it, with nothing to save'
            waitUntil { app.state().get().genies().any { it.fromElsewhere() && it.phase().isAwake() } }
            app.state().get().genies().find { it.fromElsewhere() }.handouts().isEmpty()

        and: 'the person\'s session goes on'
            person.alive
            Lamp.running(sandbox.launcher)*.lamp() == [north]
    }

    def 'Waking a lamp from elsewhere that is lit joins it, rather than lighting it a second time'() {
        reportInfo '''
            Let's consider a lamp the user added by hand while it slept, which a person then lit
            from a terminal, before Genies looked again. Genies shows it asleep, and the user
            presses Wake. Lighting it would find it taken by the person's session, so
            `Genies.wake` joins it instead, and the person's session goes on as it was.
        '''
        given: 'the user added "north" while it slept, and then a person lit it'
            sandbox.givenConfig(north, 'schema_version = 1\n')
            SwingUtilities.invokeAndWait { app.addLamp(north) }
            UUID fromElsewhere = app.state().get().selected()
            lightFromTheCommandLine(north)

        when: 'the user wakes it in Genies'
            SwingUtilities.invokeAndWait { app.wake(fromElsewhere) }

        then: 'it is awake, joined, and still the person\'s'
            waitUntil { app.state().get().find(fromElsewhere).orElseThrow().phase().isAwake() }
            person.alive
            Lamp.running(sandbox.launcher)*.lamp() == [north]
    }

    def 'A sleeping lamp from elsewhere that a person lights is joined when Genies looks for lamps'() {
        reportInfo '''
            Let's consider a lamp Genies already lists, asleep, which a person then lights from
            a terminal while Genies runs. The user does not press Wake: Genies notices by itself,
            the next time it looks for lamps, as `Genies.lookForLamps` does at once. It joins the
            person's session, so the genie is awake, and it does not list the lamp a second time.
        '''
        given: 'the user added "north" while it slept, and then a person lit it'
            sandbox.givenConfig(north, 'schema_version = 1\n')
            SwingUtilities.invokeAndWait { app.addLamp(north) }
            UUID fromElsewhere = app.state().get().selected()
            assert app.state().get().find(fromElsewhere).orElseThrow().phase() == Genie.Phase.ASLEEP
            lightFromTheCommandLine(north)

        when: 'Genies looks for lamps'
            SwingUtilities.invokeAndWait { app.lookForLamps() }

        then: 'it is awake, joined, listed once, and still the person\'s'
            waitUntil { app.state().get().find(fromElsewhere).orElseThrow().phase().isAwake() }
            app.state().get().genies()*.name() == ['Juniper', 'north']
            person.alive
            Lamp.running(sandbox.launcher)*.lamp() == [north]
    }

    def 'Genies lights a lamp from elsewhere as it is, without a genie\'s instructions'() {
        reportInfo '''
            When Genies lights one of its own genies, it gives the engine the genie's
            instructions, "You are Juniper, a genie…", and the settings' model, which the engine
            writes into pi's files in the agent's home. A lamp someone made elsewhere has an agent
            of its own, which answers them in their terminal too, so Genies lights it with neither:
            pi's appended instructions stay as the lamp had them, here none at all.
        '''
        given: 'the user added the sleeping lamp "west"'
            Path west = sandbox.lampPath('west')
            sandbox.givenConfig(west, 'schema_version = 1\n')
            SwingUtilities.invokeAndWait { app.addLamp(west) }
            UUID fromElsewhere = app.state().get().selected()

        when: 'the user wakes it'
            SwingUtilities.invokeAndWait { app.wake(fromElsewhere) }

        then: 'it is awake, without a genie\'s instructions'
            waitUntil { app.state().get().find(fromElsewhere).orElseThrow().phase().isAwake() }
            !Files.exists(Lamp.agentHome(west).orElseThrow().resolve('.pi/agent/APPEND_SYSTEM.md'))

        cleanup:
            SwingUtilities.invokeAndWait { app.sleep(fromElsewhere) }
            waitUntil { !app.state().get().find(fromElsewhere).map { it.phase().isAwake() }.orElse(false) }
    }

    def 'Removing a lamp from elsewhere leaves it running, and Genies does not take it in again'() {
        reportInfo '''
            Genies did not make "north", so removing it with `Genies.delete` does not delete it,
            as it deletes a genie of its own. It only leaves Genies: its directory is as it was,
            and its session goes on, as `Lamp.running` shows. Genies remembers that the user
            removed it, so the next look for lamps passes it by, although it is still lit.
        '''
        given: 'Genies took in "north", which a person lit'
            lightFromTheCommandLine(north)
            SwingUtilities.invokeAndWait { app.lookForLamps() }
            waitUntil { app.state().get().genies().any { it.fromElsewhere() } }
            UUID fromElsewhere = app.state().get().genies().find { it.fromElsewhere() }.id()

        when: 'the user removes it from Genies'
            SwingUtilities.invokeAndWait { app.delete(fromElsewhere) }

        then: 'it left Genies, but its lamp is there, and lit'
            app.state().get().genies()*.name() == ['Juniper']
            Files.isRegularFile(north.resolve('oillamp.toml'))
            waitUntil { Lamp.running(sandbox.launcher)*.lamp() == [north] }

        when: 'Genies looks for lamps again, and once more after a while'
            SwingUtilities.invokeAndWait { app.lookForLamps() }
            Thread.sleep(2_000)
            SwingUtilities.invokeAndWait { app.lookForLamps() }
            Thread.sleep(2_000)

        then: 'it is not taken in again'
            app.state().get().genies()*.name() == ['Juniper']
    }

    def 'A lamp the user adds by hand is shown at once, lit or not, and a directory without a lamp is refused'() {
        reportInfo '''
            A lamp that is not lit cannot be found, so the user adds it by hand, with "Add a lamp
            from elsewhere…", which calls `Genies.addLamp` with the directory they picked. Genies
            takes it in and shows it, asleep. A directory with neither `oillamp.toml` nor
            `.oillamp` in it is no lamp: `Genies.addLamp` throws, with a message for the user
            that says how to make one there.
        '''
        given: 'a lamp "west" someone configured, never lit, and a folder that is no lamp'
            Path west = sandbox.lampPath('west')
            sandbox.givenConfig(west, 'schema_version = 1\n')
            Path notes = Files.createDirectories(sandbox.home.resolve('notes'))

        when: 'the user adds "west"'
            SwingUtilities.invokeAndWait { app.addLamp(west) }

        then: 'it is listed after Juniper, shown, and asleep'
            app.state().get().genies()*.name() == ['Juniper', 'west']
            var shown = app.state().get().genie()
            shown.name() == 'west'
            shown.elsewhere() == Optional.of(west)
            shown.phase() == Genie.Phase.ASLEEP

        when: 'the user adds their notes'
            IOException refused = null
            SwingUtilities.invokeAndWait {
                try { app.addLamp(notes) } catch (IOException failed) { refused = failed }
            }

        then:
            refused.message == notes.toString() + ' is no lamp: it has no oillamp.toml, nor a .oillamp folder. `oillamp at ' + notes + '` makes one there.'
            app.state().get().genies().size() == 2
    }

    def 'A new genie goes before the lamps from elsewhere, which stay at the end of the list'() {
        reportInfo '''
            The list shows Genies' own genies first, and the lamps from elsewhere after them,
            under a heading of their own. So a genie made after a lamp from elsewhere was added
            goes before it, not at the end.
        '''
        given: 'the user added the lamp "west"'
            Path west = sandbox.lampPath('west')
            sandbox.givenConfig(west, 'schema_version = 1\n')
            SwingUtilities.invokeAndWait { app.addLamp(west) }

        when: 'they make a new genie'
            SwingUtilities.invokeAndWait { app.state().update { it.add(Genie.named('Rex')) } }

        then:
            app.state().get().genies()*.name() == ['Juniper', 'Rex', 'west']
    }

    def 'Genies keeps a lamp from elsewhere on its shelf, with its directory, and leaves it running when it quits'() {
        reportInfo '''
            Once taken in, a lamp from elsewhere is one of the genies Genies opens with next time,
            so `genies.json` on its shelf holds its directory, as `Shelf.genies()` reads it back.
            Quitting Genies with its genies put to sleep, as `Genies.quit(false, …)` does, puts
            out the lamps Genies lit, but not one from elsewhere: whoever lit it decides when it
            goes out. `Lamp.running` still lists it after Genies is done.
        '''
        given: 'Genies took in "north", which a person lit, and joined it'
            lightFromTheCommandLine(north)
            SwingUtilities.invokeAndWait { app.lookForLamps() }
            waitUntil { app.state().get().genies().any { it.fromElsewhere() && it.phase().isAwake() } }

        when: 'Genies quits, putting its genies to sleep'
            var done = new CountDownLatch(1)
            SwingUtilities.invokeAndWait { app.quit(false, Optional.empty(), { done.countDown() }) }
            assert done.await(2, TimeUnit.MINUTES)

        then: 'its shelf keeps "north" by its directory'
            new Shelf(shelf).genies().find { it.name() == 'north' }.elsewhere() == Optional.of(north)

        and: 'north is still lit'
            Lamp.running(sandbox.launcher)*.lamp() == [north]
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /// Lights a lamp as a person does, with `oillamp at <dir>`, and waits until it is up.
    private void lightFromTheCommandLine(Path lamp) {
        var oillamp = sandbox.oillamp.observedBy { reported.add(it) }
        person = Thread.start { oillamp.run('at', lamp.toString()) }
        waitUntil { reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' } }
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw new AssertionError(("never happened; the genies are ${app.state().get().genies().collect { it.name() + ' ' + it.phase() + ' (' + it.activity() + ')' }}; "
                + "the person's session said ${reported.findAll { it instanceof LampEvent.Failure }}") as Object)
    }
}
