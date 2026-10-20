package gui

import dev.gui.genie.GenieRunner
import dev.gui.genie.LampLighter
import dev.gui.genie.Lighter
import dev.gui.model.Genie
import dev.gui.model.HostAccess
import dev.gui.model.Settings
import dev.lamp.Lamp
import dev.lamp.LampEvent
import oillamp.ScenarioHost
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.UnaryOperator

@Title('A genie\'s host access, read from its lamp and written into it')
@Narrative('''
A genie's host access page says whether its agent may reach this computer, and with which
permission rules: folders it may read, folders it may read and change, and anything else in the
user's own words. Genies writes them into the genie's lamp, as `enabled` and `requirements` under
`[host_access]` in its `oillamp.toml`, with the requirements as JSON. There is no permission
system in Genies: the checker model reads the JSON with every request, as it would any words.

Here the lamp is oillamp's real engine, in this JVM, on a simulated machine, with a stand-in pi.
''')
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class KeepingAGeniesHostAccessSpec extends Specification {

    @TempDir Path tmp
    ScenarioHost sandbox
    Path lamp
    Genie genie = Genie.named('Jafar')
    GenieRunner runner

    def setup() {
        sandbox = new ScenarioHost(tmp)
        lamp = sandbox.lampPath('jafar')
        sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80))
                            .agent { String prompt -> 'You said: ' + prompt } }
        runner = new GenieRunner(lamp, lighter(), { UnaryOperator<Genie> change ->
            synchronized (this) { genie = change.apply(genie) }
        } as Consumer)
    }

    def cleanup() {
        runner?.sleepAndWait(30)
    }

    def 'A genie that never woke has no lamp to keep the rules in, and its host access is off'() {
        when:
            runner.hostAccess().read()

        then:
            waitUntil { genie.hostAccess().read() }
            !genie.hostAccess().lamp()
            !genie.hostAccess().saved().on()
    }

    def 'The permission rules are written into the lamp as JSON, and read back into the page'() {
        reportInfo """
            A new lamp has host access off. The user turns it on, names the folders and says the
            rest in their own words, and saves while the genie sleeps. The lamp's file then has
            the switch on and the rules as JSON, which is what the checker model reads.
        """
        given: 'a genie that woke once, and sleeps'
            wakeAndSleep()
            runner.hostAccess().read()
            waitUntil { genie.hostAccess().read() }

        expect: 'a new lamp keeps it off'
            genie.hostAccess().lamp()
            genie.hostAccess().saved() == HostAccess.PermissionRules.OFF

        when: 'the user turns it on, with rules, and saves'
            var rules = new HostAccess.PermissionRules(true, '~/Documents\n\n  ~/Pictures ', '~/Notes',
                    'Never open my tax returns.')
            runner.hostAccess().save(rules)

        then: 'the page shows what the file now has'
            waitUntil { genie.hostAccess().saved().on() && !genie.hostAccess().busy() }
            genie.hostAccess().saved() == new HostAccess.PermissionRules(true, '~/Documents\n~/Pictures', '~/Notes',
                    'Never open my tax returns.')
            !genie.hostAccess().changed()

        and: 'the file has the switch on, and the rules as JSON'
            var written = Lamp.at(lamp).launchedBy(sandbox.launcher).hostAccess()
            written.enabled()
            var json = new groovy.json.JsonSlurper().parseText(written.requirements()) as Map
            json.folders_it_may_read == ['~/Documents', '~/Pictures']
            json.folders_it_may_read_and_change == ['~/Notes']
            json.in_the_users_own_words == 'Never open my tax returns.'
            json.rule == 'Allow a request only when these settings allow it. Refuse everything else.'
    }

    def 'Requirements a person wrote by hand become the user\'s own words, so nothing of them is lost'() {
        given:
            wakeAndSleep()
            Lamp.at(lamp).launchedBy(sandbox.launcher).hostAccess(true, 'Only ~/Documents, and nothing private.')

        when:
            runner.hostAccess().read()

        then:
            waitUntil { genie.hostAccess().read() }
            genie.hostAccess().saved() == new HostAccess.PermissionRules(true, '', '', 'Only ~/Documents, and nothing private.')
    }

    def 'Rules that name something other than a path, or nothing while on, are saved with a warning'() {
        reportInfo """
            The model that judges reads words, so "everything" in the folders it may read is a
            rule it understands. The page only warns, and saves the rules as they are.
        """
        given: 'a genie that woke once, and sleeps'
            wakeAndSleep()

        expect:
            var rules = new HostAccess.PermissionRules(on, mayRead, '', words)
            rules.warning().orElse('').contains(because)

        when:
            runner.hostAccess().save(rules)

        then:
            waitUntil { genie.hostAccess().saved() == rules }

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

    private void wakeAndSleep() {
        runner.wake('Jafar', Settings.defaults(), 'sk-key')
        waitUntil { genie.phase() == Genie.Phase.READY }
        runner.sleep()
        waitUntil { genie.phase() == Genie.Phase.ASLEEP }
    }

    /// oillamp's engine in this JVM; commands run in the sandbox run on this machine, in the
    /// genie's home.
    private Lighter lighter() {
        var real = new LampLighter(sandbox.launcher)
        return new Lighter() {
            Lighter.Lit light(Path directory, String name, Settings settings, String key, Consumer<String> progress, Consumer<LampEvent> events) {
                var lit = real.light(directory, name, settings, key, progress, events)
                new Lighter.Lit() {
                    Process exec(String... command) {
                        var home = Lamp.agentHome(lamp).orElseThrow()
                        var builder = new ProcessBuilder(command as List<String>)
                        builder.environment().put('HOME', home.toString())
                        builder.directory(home.toFile())
                        builder.start()
                    }
                    dev.gui.desktop.Desktop desktop() { lit.desktop() }
                    LampEvent.Run send(Lamp.Question question) { lit.send(question) }
                    void cancel(String run) { lit.cancel(run) }
                    void useModel(String model) { lit.useModel(model) }
                    void decidePermission(String request, boolean allowed) { lit.decidePermission(request, allowed) }
                    void close() { lit.close() }
                }
            }
            Lamp.Starting unlit(Path directory) { real.unlit(directory) }
        }
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw new AssertionError("never happened; the genie is ${genie.phase()} (${genie.activity()}), " +
                                 "its host access ${genie.hostAccess()}" as Object)
    }
}
