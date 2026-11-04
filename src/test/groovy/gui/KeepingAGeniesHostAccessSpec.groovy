package gui

import dev.gui.Genies
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.HostAccess
import dev.gui.model.Lang
import dev.gui.model.Settings
import dev.lamp.Lamp
import dev.lamp.LampHostCommandApproval
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

The page also says when the user decides too, after the checker model allowed a request: who
lets the agent run a command on this computer, `commands`, and whether a request for a folder
outside the user's home folder goes to the user rather than being refused, `ask_the_user`.

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
                    'Never open my tax returns.', HostAccess.Commands.NEVER, false)
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(rules)) } }
                app.saveHostAccess(jafar)
            }

        then: 'the page shows what the file now has'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().saved().on() && !app.state().get().find(jafar).orElseThrow().hostAccess().busy() }
            app.state().get().find(jafar).orElseThrow().hostAccess().saved() == new HostAccess.PermissionRules(true, '~/Documents\n~/Pictures', '~/Notes',
                    'Never open my tax returns.', HostAccess.Commands.NEVER, false)
            !app.state().get().find(jafar).orElseThrow().hostAccess().changed()

        and: 'the file has the switch on, and the rules as JSON'
            var written = Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).hostAccess()
            written.enabled()
            var json = new groovy.json.JsonSlurper().parseText(written.requirements()) as Map
            json.folders_it_may_read == ['~/Documents', '~/Pictures']
            json.folders_it_may_read_and_change == ['~/Notes']
            json.in_the_users_own_words == 'Never open my tax returns.'
            json.rule == 'Allow a request only when these settings allow it. Refuse everything else.'
            !json.containsKey('commands')
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
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().saved() == new HostAccess.PermissionRules(true, '', '', 'Only ~/Documents, and nothing private.',
                                                                                                        HostAccess.Commands.NEVER, false) }
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
            var rules = new HostAccess.PermissionRules(on, mayRead, '', words, HostAccess.Commands.NEVER, false)
            rules.warning(Lang.ENGLISH, Path.of('/home/jafar')).orElse('').contains(because)

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

    def 'Who lets a genie run commands, and whether it asks about folders outside the home folder, are read from its lamp\'s file'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` already says, under `[host_access]`, who
            approves a shell command the agent wants to run on this computer, `commands`, and
            whether a request for a folder outside the user's home folder goes to the user,
            `ask_the_user`. Here a program wrote them with the Lamp API's
            `LampDirectory.hostAccess(Lamp.HostAccess)`. When the user opens the genie's host
            access page, Genies reads them back with `LampDirectory.hostAccess()`: `off` becomes
            "Nobody" (`Commands.NEVER`), `ask` becomes "You, each time" (`Commands.YOU_EACH_TIME`)
            and `checker` becomes "The model alone" (`Commands.THE_MODEL_ALONE`). The page shows
            them as the file has them, so nothing is changed until the user changes it.
        """
        given: 'a genie that woke once, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        and: 'its lamp\'s file says who approves commands, and whether to ask the user'
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher)
                .hostAccess(new Lamp.HostAccess(true, 'Only ~/Documents.', approval, askTheUser))

        when: 'the user opens its host access page'
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }

        then: 'the page shows what the file has'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }
            app.state().get().find(jafar).orElseThrow().hostAccess().saved() ==
                new HostAccess.PermissionRules(true, '', '', 'Only ~/Documents.', commands, askTheUser)
            !app.state().get().find(jafar).orElseThrow().hostAccess().changed()

        where:
            approval                        | askTheUser || commands
            LampHostCommandApproval.OFF     | false      || HostAccess.Commands.NEVER
            LampHostCommandApproval.ASK     | true       || HostAccess.Commands.YOU_EACH_TIME
            LampHostCommandApproval.CHECKER | true       || HostAccess.Commands.THE_MODEL_ALONE
    }

    def 'Saving the host access page writes who lets the genie run commands, and whether it asks, into its lamp\'s file'() {
        reportInfo """
            Let's consider a user who lets the genie run commands with nobody asked but the model
            that judges, "The model alone", and who wants to be asked about a USB stick under
            `/media`, which is outside their home folder, rather than have it refused. They
            choose both on the host access page and press Save, `Genies.saveHostAccess`, while
            the genie sleeps. Genies writes them with `LampDirectory.hostAccess(Lamp.HostAccess)`,
            so the lamp's file then has `commands = "checker"` and `ask_the_user = true`, next to
            the switch and the rules.
        """
        given: 'a genie that woke once, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }

        when: 'the user chooses "The model alone", and Yes for folders outside the home folder, and saves'
            var rules = new HostAccess.PermissionRules(true, '/media/stick', '', 'Only builds in ~/project.',
                    HostAccess.Commands.THE_MODEL_ALONE, true)
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(rules)) } }
                app.saveHostAccess(jafar)
            }

        then: 'the page shows what the file now has'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().saved() == rules }
            !app.state().get().find(jafar).orElseThrow().hostAccess().changed()

        and: 'the file has who approves commands, and that the user is asked'
            var written = Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).hostAccess()
            written.commands() == LampHostCommandApproval.CHECKER
            written.askTheUser()
    }

    def 'The requirements tell the model that judges about commands only once the user lets the genie run them'() {
        reportInfo """
            Let's consider the requirements JSON Genies writes for the model that judges. Its
            `rule` says to refuse everything the settings do not allow, and the settings list
            only folders, so a command would always be refused. The key `commands` lifts that,
            and says nothing about which commands are fine: that is the user's to say, in their
            own words. With "You, each time" it tells the model that the person approves every
            command, so the model refuses only one that clearly breaks the rules, and lets every
            other one through to the person. With "The model alone" it tells the model to judge
            each command against the settings and the user's own words. With "Nobody" there is
            no key `commands` at all, so the JSON of a genie that never ran commands stays as it
            was.
        """
        given: 'the rules of a genie that may read ~/Documents'
            var rules = new HostAccess.PermissionRules(true, '~/Documents', '', '', commands, false)

        expect:
            (new groovy.json.JsonSlurper().parseText(rules.requirements()) as Map).commands == said

        where:
            commands                            || said
            HostAccess.Commands.NEVER           || null
            HostAccess.Commands.YOU_EACH_TIME   || HostAccess.PermissionRules.THE_PERSON_APPROVES_EVERY_COMMAND
            HostAccess.Commands.THE_MODEL_ALONE || HostAccess.PermissionRules.COMMANDS_ARE_SWITCHED_ON
    }

    def 'A folder outside the home folder is saved with a warning, unless the user lets the lamp ask them'() {
        reportInfo """
            Let's consider a user whose home folder is `/home/jafar`, and who lists `/media/stick`
            under the folders the genie may read. Genies leaves the lamp's `roots` empty, which
            means the home folder, so the lamp refuses `/media/stick` before the model that
            judges even sees the request, unless `ask_the_user` is on: then a request the model
            allowed goes to the user. `PermissionRules.warning` says so while the user is not
            asked. A folder inside the home folder, written out in full, gets no warning.
        """
        given:
            var rules = new HostAccess.PermissionRules(true, folder, '', '', HostAccess.Commands.NEVER, askOutsideHome)

        expect:
            rules.warning(Lang.ENGLISH, Path.of('/home/jafar')).orElse('') == warning

        where:
            folder                | askOutsideHome || warning
            '/media/stick'        | false          || '"/media/stick" is outside your home folder, so the lamp refuses it unless you let Genies ask you.'
            '/media/stick'        | true           || ''
            '/home/jafar/Music'   | false          || ''
            '/home/jafar/../anna' | false          || '"/home/jafar/../anna" is outside your home folder, so the lamp refuses it unless you let Genies ask you.'
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
                    .permissionRequested(new HostAccess.RequestForPermission(new HostAccess.RequestForPermission.About.HostTool('host_run'), 'run `inkscape a.svg`', asked))
                    .permissionRequested(new HostAccess.RequestForPermission(new HostAccess.RequestForPermission.About.HostTool('host_read_file'), 'read ~/Notes/today.md', asked))

        expect: 'both wait, the oldest first'
            access.waitingAt(asked.plusSeconds(30))*.what() == ['run `inkscape a.svg`', 'read ~/Notes/today.md']

        and: 'after a minute, neither does'
            access.waitingAt(asked.plus(HostAccess.PATIENCE)).isEmpty()

        and: 'once the user decided about one, the other is next'
            access.permissionDecided('run `inkscape a.svg`').waitingAt(asked)*.what() == ['read ~/Notes/today.md']

        and: 'when the lamp goes out, none waits'
            access.wentOut().waitingAt(asked).isEmpty()
    }

    def 'A web server the genie\'s lamp publishes shows its link under the genie\'s header, until the lamp goes out'() {
        reportInfo """
            Let's consider a genie whose lamp's `oillamp.toml` lists opencode's port 4096 under
            `[web]`, as every lamp's file does unless the person changed it. As the genie wakes,
            its lamp publishes that port to this computer's browser and reports it with
            `LampEvent.WebPublished`, which carries the link to open, such as
            `http://k3v7x2ab.localhost:41237/`. Genies keeps it in the genie's
            `HostAccess.published()`, and the banner under the genie's header shows it, with Open
            and Dismiss. Dismiss puts it away with `HostAccess.putAway`. When the genie falls
            asleep, its lamp goes out, and the link with it: there is nothing to open any more.
        """
        when: 'the genie wakes'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }

        then: 'its lamp published opencode\'s port, with a link under localhost for this computer\'s browser'
            waitUntil { !app.state().get().find(jafar).orElseThrow().hostAccess().published().isEmpty() }
            var published = app.state().get().find(jafar).orElseThrow().hostAccess().published().first()
            published.name() == 'opencode'
            published.link().scheme == 'http'
            published.link().host.endsWith('.localhost')

        and: 'Dismiss would put it away'
            app.state().get().find(jafar).orElseThrow().hostAccess().putAway(published).published().isEmpty()

        when: 'the genie falls asleep'
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        then: 'the link went with its lamp'
            app.state().get().find(jafar).orElseThrow().hostAccess().published().isEmpty()
    }

    def 'A web server the agent starts is a request under the genie\'s header, which the user answers'() {
        reportInfo """
            Let's consider a genie whose lamp's `oillamp.toml` says `publish = "ask"` under `[web]`.
            Its agent starts a web server on port 5173 in the sandbox. Before the lamp publishes
            it, it asks the user with `LampEvent.WebPortAsked`, and Genies shows the question in
            the banner under the genie's header, as it shows a request of the agent's to reach
            this computer: a `HostAccess.RequestForPermission`, here about `WebPort(5173)`, which
            the lamp waits a minute for. The user's Allow calls `Genies.decidePermission` with the
            question's words, as for any request, and the lamp publishes the server as
            `port-5173`, whose link then shows in the banner. When the genie falls asleep, the
            question, had it still waited, and the link go with the lamp.
        """
        given: 'a sandbox in which the agent listens on 127.0.0.1, port 5173'
            sandbox.machine {
                it.generatedAgentId(AGENT_ID)
                  .commandSucceeding("podman exec --user lamp oillamp-${AGENT_ID} cat /proc/net/tcp", AGENT_LISTENS_ON_5173)
            }

        and: 'a genie that woke once, whose lamp\'s file then says `publish = "ask"`'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            var file = app.lampOf(jafar).resolve('oillamp.toml')
            Files.writeString(file, Files.readString(file).replaceFirst('publish = "listed"', 'publish = "ask"'))

        when: 'the genie wakes, and its agent\'s server is found'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { !app.state().get().find(jafar).orElseThrow().hostAccess().waitingAt(Instant.now()).isEmpty() }
            var request = app.state().get().find(jafar).orElseThrow().hostAccess().waitingAt(Instant.now()).first()

        then: 'the lamp asks about port 5173'
            request.about() == new HostAccess.RequestForPermission.About.WebPort(5173)
            request.what().contains('publish port 5173')

        when: 'the user allows it'
            SwingUtilities.invokeAndWait { app.decidePermission(jafar, request.what(), true) }

        then: 'the question is answered, and the lamp publishes the server as `port-5173`'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().published().any { it.name() == 'port-5173' } }
            app.state().get().find(jafar).orElseThrow().hostAccess().waitingAt(Instant.now()).isEmpty()

        when: 'the genie falls asleep'
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        then: 'neither a question nor a link is left'
            app.state().get().find(jafar).orElseThrow().hostAccess().waitingAt(Instant.now()).isEmpty()
            app.state().get().find(jafar).orElseThrow().hostAccess().published().isEmpty()
    }

    /** The lamp id the scenario about a web server the agent starts gives the genie's lamp. */
    static final String AGENT_ID = 'k3v7x2ab'

    /**
     * What the sandbox's `/proc/net/tcp` says in the scenario about a web server the agent starts:
     * the agent (uid 1000) listening on 127.0.0.1:5173 (`0100007F:1435`).
     */
    static final String AGENT_LISTENS_ON_5173 = """\
          sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 0100007F:1435 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 41001 1 0000000000000000 100 0 0 10 0
        """.stripIndent()

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
