package gui

import dev.gui.Genies
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.HostAccess
import dev.gui.model.Settings
import dev.lamp.Lamp
import oillamp.ScenarioHost
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title
import sprouts.Tuple

import javax.swing.SwingUtilities
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Title('A genie\'s host access, read from its lamp and written into it')
@Narrative('''
A genie's host access page says whether its agent may reach this computer, and with which
permission rules: folders it may read, folders it may read and change, and anything else in the
user's own words. Genies writes them into the genie's lamp, as `enabled` and `requirements` under
`[host_access]` in its `oillamp.toml`, with the requirements as JSON. There is no permission
system in Genies: the checker model reads the JSON with every request, as it would any words.

Here the app is driven as its window drives it, through `Genies`; its lamps are oillamp's real
engine, in this JVM, on a simulated machine, with a stand-in pi.

Each scenario reads on its own, and shows how the window talks to the app: every call to
`Genies`, and every read of its state, is written out where it happens, on Swing's event
thread as the window makes it. No helper method stands between a scenario and that API, as
one would hide the API the scenario is about; repeating a call is fine, hiding one is not.
Helpers that do not touch the app, such as waiting for a condition, are fine.
''')
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class KeepingAGeniesHostAccessSpec extends Specification {

    @TempDir Path tmp
    ScenarioHost sandbox
    Genies app
    UUID jafar

    def setup() {
        sandbox = new ScenarioHost(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80))
                            .agent { String prompt -> 'You said: ' + prompt } }
        // Genies opens as it closed last, with Jafar on its shelf, never woken, and a key in its environment.
        var genie = Genie.named('Jafar')
        jafar = genie.id()
        app = Genies.open(sandbox.home.resolve('genies'), Tuple.of(Genie, genie), Settings.defaults(), sandbox.launcher,
                          { name -> name == Settings.KEY_VARIABLE ? Optional.of('sk-key') : Optional.empty() })
    }

    def cleanup() {
        var done = new CountDownLatch(1)
        SwingUtilities.invokeAndWait { app?.quit(false, Optional.empty(), { done.countDown() }) }
        done.await(3, TimeUnit.MINUTES)
    }

    def 'A genie that never woke has no lamp to keep the rules in, and its host access is off'() {
        when:
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }

        then:
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().read() }
            !app.state().get().find(jafar).orElseThrow().hostAccess().lamp()
            !app.state().get().find(jafar).orElseThrow().hostAccess().saved().on()
    }

    def 'The permission rules are written into the lamp as JSON, and read back into the page'() {
        reportInfo """
            A new lamp has host access off. The user turns it on, names the folders and says the
            rest in their own words, and saves while the genie sleeps. The lamp's file then has
            the switch on and the rules as JSON, which is what the checker model reads.
        """
        given: 'a genie that woke once, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }

        expect: 'a new lamp keeps it off'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }
            app.state().get().find(jafar).orElseThrow().hostAccess().saved() == HostAccess.PermissionRules.OFF

        when: 'the user turns it on, with rules, and saves'
            var rules = new HostAccess.PermissionRules(true, '~/Documents\n\n  ~/Pictures ', '~/Notes',
                    'Never open my tax returns.')
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(rules)) } }
                app.saveHostAccess(jafar)
            }

        then: 'the page shows what the file now has'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().saved().on() && !app.state().get().find(jafar).orElseThrow().hostAccess().busy() }
            app.state().get().find(jafar).orElseThrow().hostAccess().saved() == new HostAccess.PermissionRules(true, '~/Documents\n~/Pictures', '~/Notes',
                    'Never open my tax returns.')
            !app.state().get().find(jafar).orElseThrow().hostAccess().changed()

        and: 'the file has the switch on, and the rules as JSON'
            var written = Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).hostAccess()
            written.enabled()
            var json = new groovy.json.JsonSlurper().parseText(written.requirements()) as Map
            json.folders_it_may_read == ['~/Documents', '~/Pictures']
            json.folders_it_may_read_and_change == ['~/Notes']
            json.in_the_users_own_words == 'Never open my tax returns.'
            json.rule == 'Allow a request only when these settings allow it. Refuse everything else.'
    }

    def 'Requirements a person wrote by hand become the user\'s own words, so nothing of them is lost'() {
        given:
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).hostAccess(true, 'Only ~/Documents, and nothing private.')

        when:
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }

        then:
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().saved() == new HostAccess.PermissionRules(true, '', '', 'Only ~/Documents, and nothing private.') }
    }

    def 'Rules that name something other than a path, or nothing while on, are saved with a warning'() {
        reportInfo """
            The model that judges reads words, so "everything" in the folders it may read is a
            rule it understands. The page only warns, and saves the rules as they are.
        """
        given: 'a genie that woke once, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        expect:
            var rules = new HostAccess.PermissionRules(on, mayRead, '', words)
            rules.warning().orElse('').contains(because)

        when:
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(rules)) } }
                app.saveHostAccess(jafar)
            }

        then:
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().saved() == rules }

        where:
            on    | mayRead        | words      | because
            true  | 'everything'   | ''         | '"everything" is not a path'
            true  | ''             | ''         | 'will refuse every request'
            true  | '~/Documents'  | ''         | ''
            true  | ''             | 'Only ~/x' | ''
    }

    def 'A request for permission waits a minute for the user, and goes once they decided'() {
        reportInfo """
            Some requests the checker allowed need the user too, such as running a program. The
            lamp asks, and refuses the request when nobody decided within a minute, so the page
            stops showing it then.
        """
        given:
            var asked = Instant.parse('2026-10-19T12:00:00Z')
            var access = HostAccess.UNREAD
                    .permissionRequested(new HostAccess.RequestForPermission('host_run', 'run `inkscape a.svg`', asked))
                    .permissionRequested(new HostAccess.RequestForPermission('host_read_file', 'read ~/Notes/today.md', asked))

        expect: 'both wait, the oldest first'
            access.waitingAt(asked.plusSeconds(30))*.what() == ['run `inkscape a.svg`', 'read ~/Notes/today.md']

        and: 'after a minute, neither does'
            access.waitingAt(asked.plus(HostAccess.PATIENCE)).isEmpty()

        and: 'once the user decided about one, the other is next'
            access.permissionDecided('run `inkscape a.svg`').waitingAt(asked)*.what() == ['read ~/Notes/today.md']

        and: 'when the lamp goes out, none waits'
            access.wentOut().waitingAt(asked).isEmpty()
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        var shown = app.state().get().find(jafar).orElseThrow()
        throw new AssertionError("never happened; the genie is ${shown.phase()} (${shown.activity()}), " +
                                 "its host access ${shown.hostAccess()}" as Object)
    }
}
