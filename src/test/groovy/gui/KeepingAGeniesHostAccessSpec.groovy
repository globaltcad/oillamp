package gui

import dev.gui.Genies
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.HostAccess
import dev.gui.model.Lang
import dev.gui.model.Notifications
import dev.gui.model.Settings
import dev.lamp.Lamp
import dev.lamp.LampHostAccess
import dev.lamp.LampHostAccessGuards
import dev.lamp.LampWebPublishing
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
A genie's host access page shows the five settings a person decides on in the `[host_access]`
table of the genie's lamp's `oillamp.toml`, in the order the file has them, and Genies writes
them back as they are, with the Lamp API's `LampDirectory.hostAccess(Lamp.HostAccess)`. Under
them, the page shows the two settings of the file's `[web]` table, which say which web servers
in the genie's sandbox this computer's browser may open, and Genies writes them back with
`LampDirectory.web(Lamp.Web)`:

| On the page, in `HostAccess.Rules` | In the lamp's file |
|---|---|
| `may`: what it may do on this computer | `may`, from `nothing` up to `read_change_run` |
| `guards`: what guards each request | `guards`, from `allowlists` up to `allowlists_checker_user` |
| `readAllowlist`: folders it may read, one per line | `read_allowlist` |
| `changeAllowlist`: folders it may read and change, one per line | `change_allowlist` |
| `checkerInstructions`: free text | `checker_instructions` |
| `web.publish`: which web servers it publishes | `publish` under `[web]`, from `off` up to `any` |
| `web.ports`: the listed ports, each a name and a port in the sandbox | `ports` under `[web]` |

While the genie is awake, its lamp has web servers open to this computer's browser, which
the page lists as `HostAccess.webPorts()`: the ports the file lists, and apart from them, the
ones its agent opened during the session.

There is no permission system in Genies. oillamp checks the allowlists itself, and the checker,
another AI model, judges each request against the checker instructions as the user wrote them.
Genies only refuses to send what oillamp would refuse anyway, and says why on the page.

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
        reportInfo '''
            Let's consider Jafar, a genie that was never woken. A genie gets its lamp, and with
            it the `oillamp.toml` that keeps its host access, as it first wakes. When the user
            opens its host access page, Genies finds no lamp, and the page shows oillamp's
            defaults, `HostAccess.Rules.DEFAULTS`: `may = "nothing"`, so host access is off.
        '''
        when: 'the user opens Jafar\'s host access page'
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }

        then: 'the page has read that there is no lamp, and shows oillamp\'s defaults, with host access off'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().read() }
            !app.state().get().find(jafar).orElseThrow().hostAccess().lamp()
            app.state().get().find(jafar).orElseThrow().hostAccess().saved() == HostAccess.Rules.DEFAULTS
            !app.state().get().find(jafar).orElseThrow().hostAccess().saved().on()
    }

    def 'Saving the host access page writes its five settings into the lamp, and the page shows them as the file has them'() {
        reportInfo '''
            Let's consider a user who lets Jafar read and change files, `may = READ_CHANGE`, with
            the checker among the guards, `guards = ALLOWLISTS_CHECKER`. They type two folders it
            may read, with a blank line and stray spaces between them, one folder it may change,
            and one instruction for the checker. While the page shows this draft, it differs from
            the saved rules, `HostAccess.changed()`. The user presses Save, `Genies.saveHostAccess`,
            while Jafar sleeps. Genies writes the five settings with
            `LampDirectory.hostAccess(Lamp.HostAccess)`, each allowlist as a list of folders,
            without the blank line and the spaces. The page then shows the rules as the lamp's
            file now has them, one folder per line, and nothing is left to save.
        '''
        given: 'Jafar woke once, so it has a lamp, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        and: 'the user opened its host access page, which shows the new lamp\'s defaults'
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }
            app.state().get().find(jafar).orElseThrow().hostAccess().saved() == HostAccess.Rules.DEFAULTS

        when: 'the user changes the five settings on the page'
            var typed = new HostAccess.Rules(HostAccess.May.READ_CHANGE, HostAccess.Guards.ALLOWLISTS_CHECKER,
                    '~/Documents\n\n  ~/Pictures ', '~/Notes', 'Never open my tax returns.', HostAccess.Web.DEFAULTS)
            SwingUtilities.invokeAndWait { app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(typed)) } } }

        then: 'the page has changes to save'
            app.state().get().find(jafar).orElseThrow().hostAccess().changed()

        when: 'the user presses Save'
            SwingUtilities.invokeAndWait { app.saveHostAccess(jafar) }

        then: 'the page shows the rules as the file now has them, one folder per line, with nothing left to save'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().saved().on() && !app.state().get().find(jafar).orElseThrow().hostAccess().busy() }
            app.state().get().find(jafar).orElseThrow().hostAccess().saved() == new HostAccess.Rules(HostAccess.May.READ_CHANGE, HostAccess.Guards.ALLOWLISTS_CHECKER,
                    '~/Documents\n~/Pictures', '~/Notes', 'Never open my tax returns.', HostAccess.Web.DEFAULTS)
            !app.state().get().find(jafar).orElseThrow().hostAccess().changed()

        and: 'the lamp\'s file has the same five settings, as the Lamp API reads them'
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).hostAccess() ==
                new Lamp.HostAccess(LampHostAccess.MAY_READ_CHANGE, LampHostAccessGuards.ALLOWLISTS_CHECKER,
                    Tuple.of(String, '~/Documents', '~/Pictures'), Tuple.of(String, '~/Notes'), 'Never open my tax returns.')
    }

    def 'The host access page shows what a program wrote into the lamp\'s file, the checker instructions as plain text'() {
        reportInfo '''
            Let's consider a lamp whose `[host_access]` table a program wrote with the Lamp API's
            `LampDirectory.hostAccess(Lamp.HostAccess)`. Its checker instructions may be any text:
            in a lamp an older Genies wrote, they are the JSON that Genies once built, which oillamp
            carried over from the old key `requirements`. When the user opens
            Jafar's host access page, Genies reads the table with `LampDirectory.hostAccess()`, and
            the page shows each of the five settings as the file has them: `may` and `guards` as
            the same values of `HostAccess.May` and `HostAccess.Guards`, each allowlist one folder
            per line, and the checker instructions as the very text the file holds, JSON or not.
            Nothing differs from the file until the user changes something.
        '''
        given: 'Jafar woke once, so it has a lamp, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        and: 'a program wrote its lamp\'s host access with the Lamp API'
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher)
                .hostAccess(new Lamp.HostAccess(writtenMay, writtenGuards, Tuple.of(String, '/media/stick', '~/Documents'), Tuple.of(String, '~/project'), instructions))

        when: 'the user opens Jafar\'s host access page'
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }

        then: 'the page shows the five settings as the file has them, with nothing changed'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }
            app.state().get().find(jafar).orElseThrow().hostAccess().saved() ==
                new HostAccess.Rules(shownMay, shownGuards, '/media/stick\n~/Documents', '~/project', instructions, HostAccess.Web.DEFAULTS)
            !app.state().get().find(jafar).orElseThrow().hostAccess().changed()

        where:
            writtenMay                         | writtenGuards                                | instructions                                              || shownMay                        | shownGuards
            LampHostAccess.NOTHING             | LampHostAccessGuards.ALLOWLISTS              | ''                                                        || HostAccess.May.NOTHING          | HostAccess.Guards.ALLOWLISTS
            LampHostAccess.MAY_READ            | LampHostAccessGuards.ALLOWLISTS_CHECKER      | 'Only what I name.'                                       || HostAccess.May.READ             | HostAccess.Guards.ALLOWLISTS_CHECKER
            LampHostAccess.MAY_READ_CHANGE_RUN | LampHostAccessGuards.ALLOWLISTS_CHECKER_USER | '{\n  "in_the_users_own_words" : "Never delete a file."\n}' || HostAccess.May.READ_CHANGE_RUN  | HostAccess.Guards.ALLOWLISTS_CHECKER_USER
    }

    def 'Rules that oillamp would refuse say why on the host access page, and Save does not send them to the lamp'() {
        reportInfo '''
            Let's consider a user who makes a mistake that oillamp would refuse to write into the
            lamp's file: a line in an allowlist that is not `~`, does not start with `~/` and is not
            absolute, such as "everything"; a folder with a colon in it, which oillamp keeps
            between the folders of a list; or no checker instructions while the checker is among
            the guards of a genie that may do something. `HostAccess.Rules.mistake()` names the
            first such mistake, which the page says, in the language Genies speaks, beside Save.
            Pressing Save, `Genies.saveHostAccess`, sends nothing to the lamp then, so its file
            keeps the settings it had.
        '''
        given: 'Jafar woke once, so it has a lamp with oillamp\'s defaults, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }

        when: 'the user makes the mistake on the page, and presses Save'
            var typed = new HostAccess.Rules(HostAccess.May.READ, HostAccess.Guards.ALLOWLISTS_CHECKER, readAllowlist, changeAllowlist, instructions, HostAccess.Web.DEFAULTS)
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(typed)) } }
                app.saveHostAccess(jafar)
            }

        then: 'the page says what is wrong, in English here'
            app.state().get().find(jafar).orElseThrow().hostAccess().draft().mistake().orElseThrow().words(Lang.ENGLISH) == said

        and: 'nothing is on its way to the lamp, and its file still has oillamp\'s defaults'
            !app.state().get().find(jafar).orElseThrow().hostAccess().busy()
            app.state().get().find(jafar).orElseThrow().hostAccess().saved() == HostAccess.Rules.DEFAULTS
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).hostAccess().may() == LampHostAccess.NOTHING

        where:
            readAllowlist            | changeAllowlist | instructions     || said
            '~/Documents\neverything' | '~'            | 'Only ~/Documents.' || '"everything" is not a folder. Write ~, or a folder starting with ~/ or with /.'
            '~'                      | '~/a:b'         | 'Only ~/Documents.' || '"~/a:b" holds a colon, which a folder here cannot.'
            '~'                      | '~'             | '  \n '          || 'The checker needs instructions to judge by. Write them, or choose the allowlists alone.'
    }

    def 'Empty checker instructions are a mistake only while host access is on and the checker is among the guards'() {
        reportInfo '''
            Let's consider rules with no checker instructions. The checker, another AI model,
            judges every request against them, so oillamp refuses to write empty ones while it
            would be asked: while `may` is not `NOTHING` and `guards` include the checker. With
            `may = NOTHING` nothing is asked at all, and with `guards = ALLOWLISTS` no checker
            reads them, so `HostAccess.Rules.mistake()` finds none there, and the page saves them.
        '''
        given: 'rules with empty checker instructions'
            var rules = new HostAccess.Rules(may, guards, '~', '~', '', HostAccess.Web.DEFAULTS)

        expect:
            rules.mistake().isPresent() == mistake

        where:
            may                            | guards                                   || mistake
            HostAccess.May.NOTHING         | HostAccess.Guards.ALLOWLISTS_CHECKER     || false
            HostAccess.May.READ            | HostAccess.Guards.ALLOWLISTS             || false
            HostAccess.May.READ            | HostAccess.Guards.ALLOWLISTS_CHECKER     || true
            HostAccess.May.READ_CHANGE_RUN | HostAccess.Guards.ALLOWLISTS_CHECKER_USER || true
    }

    def 'Commands that only the allowlists guard are flagged as unchecked, and saved all the same'() {
        reportInfo '''
            Let's consider a user who lets Jafar run commands, `may = READ_CHANGE_RUN`, with only
            the allowlists among the guards, `guards = ALLOWLISTS`. No model then judges a
            command and nobody is asked, and a command can do anything the user can; the
            allowlists only decide the folder it starts in. `HostAccess.Rules.runsCommandsUnchecked()`
            says so, as `Lamp.HostAccess.runsCommandsUnchecked()` does, and the page shows a
            red warning for it. It is the user's computer, so Save, `Genies.saveHostAccess`,
            writes the rules all the same.
        '''
        given: 'Jafar woke once, so it has a lamp, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }

        expect: 'only commands with the allowlists alone are flagged'
            !new HostAccess.Rules(HostAccess.May.READ_CHANGE, HostAccess.Guards.ALLOWLISTS, '~', '~', '', HostAccess.Web.DEFAULTS).runsCommandsUnchecked()
            !new HostAccess.Rules(HostAccess.May.READ_CHANGE_RUN, HostAccess.Guards.ALLOWLISTS_CHECKER, '~', '~', 'Only builds.', HostAccess.Web.DEFAULTS).runsCommandsUnchecked()
            new HostAccess.Rules(HostAccess.May.READ_CHANGE_RUN, HostAccess.Guards.ALLOWLISTS, '~', '~/project', '', HostAccess.Web.DEFAULTS).runsCommandsUnchecked()

        when: 'the user chooses to let it run commands, guarded by the allowlists alone, and presses Save'
            var unchecked = new HostAccess.Rules(HostAccess.May.READ_CHANGE_RUN, HostAccess.Guards.ALLOWLISTS, '~', '~/project', '', HostAccess.Web.DEFAULTS)
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(unchecked)) } }
                app.saveHostAccess(jafar)
            }

        then: 'the page shows the saved rules, still flagged'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().saved() == unchecked }
            app.state().get().find(jafar).orElseThrow().hostAccess().saved().runsCommandsUnchecked()

        and: 'the lamp\'s file has them, which the Lamp API flags too'
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).hostAccess().runsCommandsUnchecked()
    }

    def 'A request for permission waits a minute for the user, and is kept with its answer'() {
        reportInfo """
            Some requests the checker allowed need the user too, such as running a program. The
            lamp asks, and refuses the request when nobody decided within a minute, so the banner
            under the genie's header stops showing it then. Genies keeps each question in the
            genie's `Notifications`, with what became of it: once the banner no longer shows it,
            the user can still look back on what was asked, and what they answered.
        """
        given:
            var asked = Instant.parse('2026-10-19T12:00:00Z')
            var notifications = Notifications.NONE
                    .asked(new Notifications.HostTool('host_run', 'run `inkscape a.svg`'), asked)
                    .asked(new Notifications.HostTool('host_read_file', 'read ~/Notes/today.md'), asked)

        expect: 'both wait, the oldest first'
            notifications.waitingAt(asked.plusSeconds(30))*.about()*.what() == ['run `inkscape a.svg`', 'read ~/Notes/today.md']

        and: 'after a minute, neither does, and both went unanswered'
            notifications.waitingAt(asked.plus(Notifications.PATIENCE)).isEmpty()
            notifications.all()*.statusAt(asked.plus(Notifications.PATIENCE)) == [Notifications.Status.UNANSWERED] * 2

        when: 'the user allows one'
            var decided = notifications.decided('run `inkscape a.svg`', true)

        then: 'the other is next, and the first is kept as allowed'
            decided.waitingAt(asked)*.about()*.what() == ['read ~/Notes/today.md']
            decided.all()*.status() == [Notifications.Status.ALLOWED, Notifications.Status.WAITING]

        and: 'when the lamp goes out, none waits, and both are still there'
            decided.wentOut().waitingAt(asked).isEmpty()
            decided.wentOut().all()*.status() == [Notifications.Status.ALLOWED, Notifications.Status.UNANSWERED]
    }

    def 'A genie keeps its notifications up to a limit, but never drops one still waiting for the user'() {
        reportInfo """
            A genie that runs for days may ask a lot. Its `Notifications` keep at most
            `Notifications.MOST`; past that, the oldest that need nothing of the user any more go
            first. A question still waiting, or a link that still works, stays.
        """
        given: 'a question nobody answered yet, then many the user answered'
            var at = Instant.parse('2026-10-19T12:00:00Z')
            var notifications = Notifications.NONE.asked(new Notifications.HostTool('host_run', 'run `make`'), at)
            for (int i = 0; i < Notifications.MOST + 10; i++)
                notifications = notifications.asked(new Notifications.HostTool('host_run', "run `step $i`"), at)
                                             .decided("run `step $i`", false)

        expect: 'there are no more than the limit, and the waiting one is first among them'
            notifications.all().size() == Notifications.MOST
            notifications.all().first().about().what() == 'run `make`'
            notifications.all().first().status() == Notifications.Status.WAITING

        and: 'the eleven oldest answered ones went, to make room for the waiting one too'
            notifications.all().get(1).about().what() == 'run `step 11`'
    }

    def 'A port the genie\'s lamp lists is kept among its notifications, not shown under its header, until the lamp goes out'() {
        reportInfo """
            Let's consider a genie whose lamp's `oillamp.toml` lists opencode's port 4096 under
            `[web]`, as every lamp's file does unless the person changed it. As the genie wakes,
            its lamp opens that port to this computer's browser and reports it with
            `LampEvent.WebPublished`, which carries the link, such as
            `http://k3v7x2ab.localhost:41237/`, and says that the agent did not open it. Nobody
            did anything, so the banner under the genie's header does not show it: Genies keeps it
            in the genie's `Notifications` as `PUT_AWAY`, an open link the notifications page lists
            with Open. Whether anything in the sandbox answers there, neither Genies nor the lamp
            knows. When the genie falls asleep, its lamp goes out, and the link closes with it: it
            is kept as `GONE`.
        """
        when: 'the genie wakes'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }

        then: 'its lamp opened opencode\'s port 4096, with a link under localhost for this computer\'s browser'
            waitUntil { app.state().get().find(jafar).orElseThrow().notifications().all().any { it.about() instanceof Notifications.Published } }
            var kept = app.state().get().find(jafar).orElseThrow().notifications().all().find { it.about() instanceof Notifications.Published }
            var published = kept.about() as Notifications.Published
            published.name() == 'opencode'
            published.sandboxPort() == 4096
            !published.openedByTheAgent()
            published.link().scheme == 'http'
            published.link().host.endsWith('.localhost')

        and: 'it is kept as an open link, but not shown under the genie\'s header'
            kept.status() == Notifications.Status.PUT_AWAY
            app.state().get().find(jafar).orElseThrow().notifications().links().isEmpty()

        when: 'the genie falls asleep'
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        then: 'the link closed with its lamp, and is kept as gone'
            app.state().get().find(jafar).orElseThrow().notifications().all().find { it.about() == published }.status() == Notifications.Status.GONE
    }

    def 'A web server the agent starts is a request under the genie\'s header, which the user answers'() {
        reportInfo """
            Let's consider a genie whose lamp's `oillamp.toml` says `publish = "ask"` under `[web]`.
            Its agent starts a web server on port 5173 in the sandbox. Before the lamp publishes
            it, it asks the user with `LampEvent.WebPortAsked`, and Genies shows the question in
            the banner under the genie's header, as it shows a request of the agent's to reach
            this computer: a `Notifications.Question`, here a `WebPort` about port 5173, which
            the lamp waits a minute for. The user's Allow calls `Genies.decidePermission` with the
            question's words, as for any request, and the lamp publishes the server as
            `port-5173`, whose link then shows in the banner. When the genie falls asleep, the
            question, had it still waited, and the link go with the lamp; both are kept, with what
            became of them. Unlike a port the lamp lists, this one the agent opened, and so it
            shows under the header, and Dismiss puts it away with `Notifications.putAway`.
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
            waitUntil { !app.state().get().find(jafar).orElseThrow().notifications().waitingAt(Instant.now()).isEmpty() }
            var request = app.state().get().find(jafar).orElseThrow().notifications().waitingAt(Instant.now()).first()

        then: 'the lamp asks about port 5173'
            request.about() instanceof Notifications.WebPort
            request.about().sandboxPort() == 5173
            request.about().what().contains('publish port 5173')

        when: 'the user allows it'
            SwingUtilities.invokeAndWait { app.decidePermission(jafar, request.about().what(), true) }

        then: 'the question is answered, and the lamp publishes the server as `port-5173`'
            waitUntil { app.state().get().find(jafar).orElseThrow().notifications().links().any { it.about().name() == 'port-5173' } }
            app.state().get().find(jafar).orElseThrow().notifications().waitingAt(Instant.now()).isEmpty()

        and: 'its link shows under the header, as one the agent opened, which Dismiss would put away'
            var shown = app.state().get().find(jafar).orElseThrow().notifications().links().find { it.about().name() == 'port-5173' }
            shown.about().openedByTheAgent()
            shown.about().sandboxPort() == 5173
            app.state().get().find(jafar).orElseThrow().notifications().putAway(shown).links().isEmpty()

        when: 'the genie falls asleep'
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        then: 'neither a question nor a link is left, and the question is kept as allowed'
            app.state().get().find(jafar).orElseThrow().notifications().waitingAt(Instant.now()).isEmpty()
            app.state().get().find(jafar).orElseThrow().notifications().links().isEmpty()
            app.state().get().find(jafar).orElseThrow().notifications().all().find { it.about() == request.about() }.status() == Notifications.Status.ALLOWED
    }

    def 'The host access page reads which web servers the browser may open from the `[web]` table of the lamp\'s file'() {
        reportInfo """
            Let's consider a lamp whose `[web]` table a program wrote with the Lamp API's
            `LampDirectory.web(Lamp.Web)`: `publish = "ask"`, and two listed ports, `site` on 8080
            and `opencode` on 4096, in that order. When the user opens Jafar's host access page
            while Jafar sleeps, Genies reads the table with `LampDirectory.web()`, and the page
            shows both settings in `HostAccess.Rules.web()`: `HostAccess.Publish.ASK`, and the
            listed ports sorted by name, as the file reads back. Nothing differs from the file
            until the user changes something. While the lamp is out, no web server is open, so
            `HostAccess.webPorts()` is empty.
        """
        given: 'Jafar woke once, so it has a lamp, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        and: 'a program wrote its lamp\'s `[web]` table with the Lamp API'
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).web(new Lamp.Web(LampWebPublishing.ASK,
                    Tuple.of(Lamp.Web.ListedPort, new Lamp.Web.ListedPort('site', 8080), new Lamp.Web.ListedPort('opencode', 4096))))

        when: 'the user opens Jafar\'s host access page'
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }

        then: 'the page shows `ASK` and both listed ports, sorted by name, with nothing changed'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().saved().web().publish() == HostAccess.Publish.ASK }
            app.state().get().find(jafar).orElseThrow().hostAccess().saved().web() == new HostAccess.Web(HostAccess.Publish.ASK,
                    Tuple.of(HostAccess.ListedPort, new HostAccess.ListedPort('opencode', 4096), new HostAccess.ListedPort('site', 8080)))
            !app.state().get().find(jafar).orElseThrow().hostAccess().changed()

        and: 'no web server is open, since the lamp is out'
            app.state().get().find(jafar).orElseThrow().hostAccess().webPorts().isEmpty()
    }

    def 'Saving the host access page writes which web servers the browser may open into the `[web]` table of the lamp\'s file'() {
        reportInfo """
            Let's consider a user who wants their browser to open a web site that Jafar builds on
            port 8080 in its sandbox, rather than opencode. A new lamp's file lists opencode's
            port 4096, with `publish = "listed"`. On the host access page, the user chooses
            `HostAccess.Publish.ASK`, so that Jafar may also ask them to publish a web server it
            starts; removes `opencode` from the listed ports; and types a port to add: the name
            " site " and the port "8080", which `HostAccess.ListedPort.typed` turns into `site` on
            8080, without the spaces. They press Save, `Genies.saveHostAccess`, while Jafar
            sleeps. Genies writes the `[web]` table with `LampDirectory.web(Lamp.Web)`, and the
            page then shows it as the file now has it. The five settings of `[host_access]` stay
            as they were, in the file and on the page.
        """
        given: 'Jafar woke once, so it has a lamp, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        and: 'the user opened its host access page, which shows the new lamp\'s defaults'
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }
            app.state().get().find(jafar).orElseThrow().hostAccess().saved() == HostAccess.Rules.DEFAULTS

        when: 'the user chooses `ASK`, removes `opencode`, adds what they typed, and presses Save'
            var site = HostAccess.ListedPort.typed(' site ', '8080').orElseThrow()
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(genie.hostAccess().draft().withWeb(
                        genie.hostAccess().draft().web().withPublish(HostAccess.Publish.ASK).without(new HostAccess.ListedPort('opencode', 4096)).with(site)))) } }
                app.saveHostAccess(jafar)
            }

        then: 'the page shows `ASK` with `site` on 8080 as the file now has them, with nothing left to save'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().saved().web().publish() == HostAccess.Publish.ASK && !app.state().get().find(jafar).orElseThrow().hostAccess().busy() }
            app.state().get().find(jafar).orElseThrow().hostAccess().saved().web() == new HostAccess.Web(HostAccess.Publish.ASK, Tuple.of(HostAccess.ListedPort, new HostAccess.ListedPort('site', 8080)))
            !app.state().get().find(jafar).orElseThrow().hostAccess().changed()

        and: 'the lamp\'s file has the same, as the Lamp API reads it'
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).web() ==
                new Lamp.Web(LampWebPublishing.ASK, Tuple.of(Lamp.Web.ListedPort, new Lamp.Web.ListedPort('site', 8080)))

        and: 'the five settings of `[host_access]` are still oillamp\'s defaults'
            app.state().get().find(jafar).orElseThrow().hostAccess().saved().withWeb(HostAccess.Web.DEFAULTS) == HostAccess.Rules.DEFAULTS
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).hostAccess().may() == LampHostAccess.NOTHING
    }

    def 'The page adds a listed port only from a typed name and a whole number, and says before saving that oillamp refuses a name such as "My Site"'() {
        reportInfo """
            Let's consider the row under the listed ports where the user types a port to add: its
            name, and its port in the sandbox. `HostAccess.ListedPort.typed` gives a port to add
            once the name is not blank and the port is a whole number, each without the spaces
            around it; otherwise there is nothing to add yet. Which names oillamp accepts, Genies
            checks before saving, since a name with a comma or an equals sign could not even be
            sent to oillamp: up to 32 lowercase letters, digits and dashes.
            So a listed port named "My Site" makes `HostAccess.Rules.mistake()` a
            `HostAccess.Mistake.NotAPortName`, which the page says beside Save, in the language
            Genies speaks.
        """
        expect: 'a port to add only from a name and a whole number, each without the spaces around it'
            HostAccess.ListedPort.typed(' site ', ' 8080 ') == Optional.of(new HostAccess.ListedPort('site', 8080))
            HostAccess.ListedPort.typed('  ', '8080').isEmpty()
            HostAccess.ListedPort.typed('site', 'http').isEmpty()

        when: 'the user adds a port named "My Site"'
            var rules = HostAccess.Rules.DEFAULTS.withWeb(HostAccess.Web.DEFAULTS.with(HostAccess.ListedPort.typed('My Site', '8080').orElseThrow()))

        then: 'the page says the name is the mistake, in English and in German here'
            rules.mistake().orElseThrow() == new HostAccess.Mistake.NotAPortName('My Site')
            rules.mistake().orElseThrow().words(Lang.ENGLISH) == '"My Site" cannot name a port. Use up to 32 lowercase letters, digits and dashes.'
            rules.mistake().orElseThrow().words(Lang.GERMAN) == '„My Site“ taugt nicht als Name eines Ports. Nimm bis zu 32 Kleinbuchstaben, Ziffern und Bindestriche.'
    }

    def 'A listed port that oillamp refuses is said on the host access page, which keeps the user\'s draft to correct'() {
        reportInfo """
            Let's consider a user who lists port 3129 for their browser, under the name `model`.
            Port 3129 is where Jafar's sandbox reaches the model service through oillamp, and
            oillamp never publishes it. Before saving, Genies checks only a port's name; whether
            oillamp accepts the port itself, such as one of its own or one of the lamp's
            forwards, oillamp says. So Save, `Genies.saveHostAccess`, sends the port, and
            `LampDirectory.web(Lamp.Web)` refuses it with a `LampFailedException` whose problem
            says why. The page says so as `HostAccess.Problem.CouldNotSave`, with oillamp's
            reason, and keeps the user's draft, so they can correct the port. The lamp's file
            keeps its `[web]` table as it was.
        """
        given: 'Jafar woke once, so it has a lamp with oillamp\'s defaults, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }

        when: 'the user lists `model` on port 3129, and presses Save'
            var model = new HostAccess.ListedPort('model', 3129)
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(genie.hostAccess().draft().withWeb(
                        genie.hostAccess().draft().web().with(model)))) } }
                app.saveHostAccess(jafar)
            }

        then: 'the page says that oillamp could not save it, and why, in oillamp\'s words'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().problem().isPresent() }
            app.state().get().find(jafar).orElseThrow().hostAccess().problem().orElseThrow() instanceof HostAccess.Problem.CouldNotSave
            app.state().get().find(jafar).orElseThrow().hostAccess().problem().orElseThrow().reason().contains('port 3129 is where the sandbox reaches the model service')
            app.state().get().find(jafar).orElseThrow().hostAccess().problem().orElseThrow().words(Lang.ENGLISH).startsWith('oillamp could not save it: ')

        and: 'the page still shows the user\'s draft, with `model` among the listed ports, and nothing is on its way'
            app.state().get().find(jafar).orElseThrow().hostAccess().draft().web().ports().contains(model)
            app.state().get().find(jafar).orElseThrow().hostAccess().changed()
            !app.state().get().find(jafar).orElseThrow().hostAccess().busy()

        and: 'the lamp\'s file still lists only opencode\'s port'
            app.state().get().find(jafar).orElseThrow().hostAccess().saved().web() == HostAccess.Web.DEFAULTS
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).web() ==
                new Lamp.Web(LampWebPublishing.LISTED, Tuple.of(Lamp.Web.ListedPort, new Lamp.Web.ListedPort('opencode', 4096)))
    }

    def 'When oillamp writes the `[web]` table but refuses the `[host_access]` table, the page shows the new `[web]` table as saved'() {
        reportInfo """
            Let's consider a user who changes both tables of the lamp's file at once. They choose
            `HostAccess.Publish.ASK`, and let Jafar read files, `HostAccess.May.READ`, with
            checker instructions pasted from a long document: more than 64 KiB. Genies does not
            check how long the instructions are, and oillamp refuses more than 64 KiB. Save,
            `Genies.saveHostAccess`, writes `[web]` first, with `LampDirectory.web(Lamp.Web)`,
            which oillamp accepts, and then `[host_access]`, with
            `LampDirectory.hostAccess(Lamp.HostAccess)`, which oillamp refuses with a
            `LampFailedException`. The file then has the new `[web]` table and the old
            `[host_access]` table, and `HostAccess.saved()` shows exactly that: the page does not
            count the `[web]` table among the changes still to save. It says why the rest could
            not be saved, as `HostAccess.Problem.CouldNotSave`, and keeps the user's draft of
            `[host_access]` to correct.
        """
        given: 'Jafar woke once, so it has a lamp with oillamp\'s defaults, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }

        when: 'the user chooses `ASK`, lets Jafar read with instructions of more than 64 KiB, and presses Save'
            var instructions = 'Never open my tax returns. ' * 2500
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(genie.hostAccess().draft()
                        .withMay(HostAccess.May.READ).withCheckerInstructions(instructions)
                        .withWeb(genie.hostAccess().draft().web().withPublish(HostAccess.Publish.ASK)))) } }
                app.saveHostAccess(jafar)
            }

        then: 'the page says that oillamp could not save it, and why, in oillamp\'s words'
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().problem().isPresent() }
            app.state().get().find(jafar).orElseThrow().hostAccess().problem().orElseThrow() instanceof HostAccess.Problem.CouldNotSave
            app.state().get().find(jafar).orElseThrow().hostAccess().problem().orElseThrow().reason().contains('more than 64 KiB')

        and: 'the page shows `ASK` as saved, and the five settings of `[host_access]` as the file still has them'
            app.state().get().find(jafar).orElseThrow().hostAccess().saved().web().publish() == HostAccess.Publish.ASK
            app.state().get().find(jafar).orElseThrow().hostAccess().saved().withWeb(HostAccess.Web.DEFAULTS) == HostAccess.Rules.DEFAULTS

        and: 'only the draft of `[host_access]` is left to save, and nothing is on its way'
            app.state().get().find(jafar).orElseThrow().hostAccess().draft().web() == app.state().get().find(jafar).orElseThrow().hostAccess().saved().web()
            app.state().get().find(jafar).orElseThrow().hostAccess().draft().may() == HostAccess.May.READ
            app.state().get().find(jafar).orElseThrow().hostAccess().changed()
            !app.state().get().find(jafar).orElseThrow().hostAccess().busy()

        and: 'the lamp\'s file has `ask` under `[web]`, and still `nothing` under `[host_access]`, as the Lamp API reads them'
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).web().publish() == LampWebPublishing.ASK
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).hostAccess().may() == LampHostAccess.NOTHING
    }

    def 'While the genie is awake, Save does not write the `[web]` table, and its lamp keeps the setting it woke with'() {
        reportInfo """
            Let's consider a user who, while Jafar is awake, chooses `HostAccess.Publish.OFF` on
            its host access page. A session reads the lamp's file only as it starts, and oillamp
            refuses to write `[web]` while one runs; the page only offers Save while the genie
            sleeps. Should Save, `Genies.saveHostAccess`, be called while Jafar is awake all the
            same, Genies sends nothing: nothing is on its way, the page keeps the user's draft,
            and the lamp's file keeps `publish = "listed"`.
        """
        given: 'Jafar is awake, and the user opened its host access page'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }

        when: 'the user chooses `OFF`, and Save is called'
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(genie.hostAccess().draft().withWeb(
                        genie.hostAccess().draft().web().withPublish(HostAccess.Publish.OFF)))) } }
                app.saveHostAccess(jafar)
            }

        then: 'nothing is on its way, and the page keeps `OFF` as the user\'s draft, while `LISTED` stays saved'
            !app.state().get().find(jafar).orElseThrow().hostAccess().busy()
            app.state().get().find(jafar).orElseThrow().hostAccess().draft().web().publish() == HostAccess.Publish.OFF
            app.state().get().find(jafar).orElseThrow().hostAccess().saved().web().publish() == HostAccess.Publish.LISTED

        and: 'the lamp\'s file still says `listed`, as the Lamp API reads it'
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).web().publish() == LampWebPublishing.LISTED
    }

    def 'While the genie is awake, the host access page lists the web servers its lamp opened: the listed ports, and apart from them the ones its agent opened'() {
        reportInfo """
            Let's consider Jafar, whose lamp's file lists opencode's port 4096, and whose user
            chose `HostAccess.Publish.ANY` on its host access page, so that every web server
            Jafar's agent starts is published unasked. Jafar wakes, and its agent starts Python's
            web server on port 5173 in the sandbox. The lamp's session reports
            `LampEvent.SessionStateChanged` whenever its state or its open web servers change,
            and its `status().webPorts()` lists every open one; the page keeps the latest list
            as `HostAccess.webPorts()`. It tells the two kinds apart by the ports the lamp's file
            lists: `HostAccess.published(HostAccess.ListedPort)` finds where the browser opens a
            listed port, and `HostAccess.openedThisSession()` holds the others, here
            `port-5173`, with the program that serves it, as the lamp found it. When Jafar falls
            asleep, its lamp goes out, every link closes with it, and the page lists no web
            server.
        """
        given: 'a sandbox in which the agent\'s Python web server listens on 127.0.0.1, port 5173'
            sandbox.machine {
                it.generatedAgentId(AGENT_ID)
                  .commandSucceeding("podman exec --user lamp oillamp-${AGENT_ID} cat /proc/net/tcp", AGENT_LISTENS_ON_5173)
                  // The lamp reads the program's command line, its NUL bytes turned into spaces.
                  .commandSucceeding("podman exec --user 1000 oillamp-${AGENT_ID} sh -c", 'python3 -m http.server 5173 ')
            }

        and: 'Jafar woke once and sleeps, and the user chose `ANY` on its host access page, and saved it'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HOST_ACCESS) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().lamp() }
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHostAccess(genie.hostAccess().withDraft(genie.hostAccess().draft().withWeb(
                        genie.hostAccess().draft().web().withPublish(HostAccess.Publish.ANY)))) } }
                app.saveHostAccess(jafar)
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().hostAccess().saved().web().publish() == HostAccess.Publish.ANY }

        when: 'Jafar wakes, and its lamp publishes the agent\'s web server'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { !app.state().get().find(jafar).orElseThrow().hostAccess().openedThisSession().isEmpty() }
            var access = app.state().get().find(jafar).orElseThrow().hostAccess()

        then: 'opencode\'s listed port is open, with a link under localhost for this computer\'s browser'
            var opencode = access.published(new HostAccess.ListedPort('opencode', 4096)).orElseThrow()
            opencode.link().scheme == 'http'
            opencode.link().host.endsWith('.localhost')

        and: 'apart from it, the page lists the one port the agent opened, `port-5173`, with its link and the program serving it'
            access.webPorts().size() == 2
            access.openedThisSession().size() == 1
            var opened = access.openedThisSession().first()
            opened.name() == 'port-5173'
            opened.sandboxPort() == 5173
            opened.link().host.endsWith('.localhost')
            opened.program() == 'python3 -m http.server 5173'

        when: 'Jafar falls asleep'
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        then: 'the page lists no web server, listed or opened'
            app.state().get().find(jafar).orElseThrow().hostAccess().webPorts().isEmpty()
            app.state().get().find(jafar).orElseThrow().hostAccess().published(new HostAccess.ListedPort('opencode', 4096)).isEmpty()
            app.state().get().find(jafar).orElseThrow().hostAccess().openedThisSession().isEmpty()
    }

    /** The lamp id the scenarios about a web server the agent starts give the genie's lamp. */
    static final String AGENT_ID = 'k3v7x2ab'

    /**
     * What the sandbox's `/proc/net/tcp` says in the scenarios about a web server the agent starts:
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
