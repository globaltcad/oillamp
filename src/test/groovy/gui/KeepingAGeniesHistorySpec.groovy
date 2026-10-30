package gui

import dev.gui.Genies
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.History
import dev.gui.model.Lang
import dev.gui.model.Lines
import dev.gui.model.Settings
import dev.lamp.Lamp
import oillamp.ScenarioHost
import sprouts.Tuple
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import javax.swing.SwingUtilities
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 *  A genie's history, read from its page, saved to by hand, and gone back to.
 *
 *  <p>oillamp saves a genie's home in its lamp once a chat with it is over, and whenever the
 *  user saves by hand. Each save is a moment the genie can go back to: its files, and its conversations
 *  with them, become what they were then. Here the app is driven as its window drives it,
 *  through {@link dev.gui.Genies}; its lamps are oillamp's real engine, in this JVM, on a
 *  simulated machine whose clock the scenario moves, with a stand-in pi.
 *
 *  <p>Each scenario reads on its own, and shows how the window talks to the app: every call to
 *  Genies, and every read of its state, is written out where it happens, on Swing's event
 *  thread as the window makes it. No helper method stands between a scenario and that API, as
 *  one would hide the API the scenario is about; repeating a call is fine, hiding one is not.
 *  Helpers that do not touch the app, such as waiting for a condition, are fine.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class KeepingAGeniesHistorySpec extends Specification {

    static final Instant NOW = Instant.parse('2026-09-29T12:32:00Z')
    static final ZoneId BERLIN = ZoneId.of('Europe/Berlin')

    @TempDir Path tmp
    ScenarioHost sandbox
    Genies app
    UUID jafar

    def setup() {
        sandbox = new ScenarioHost(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen').clockAt(NOW).timeZone('Europe/Berlin')
                            .windowsStayOpenFor(Duration.ofSeconds(80)).agent { String prompt -> 'You said: ' + prompt } }
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

    def 'A chat is a moment of the history once it is over, and a save by hand adds one'() {
        reportInfo """
            The history page lists the moments oillamp saved the genie's home at, newest first,
            in words. A chat is saved once it is over: when no question was asked for ten minutes,
            or when the genie goes to sleep before that, titled by its conversation. Saving reads
            the genie's whole home, so oillamp does not do it around every answer, and unless the
            lamp says otherwise, not as the genie wakes or sleeps either. The user can save by hand too, awake
            or asleep, with a few words to remember the moment by; the new moment is picked, so
            it stands out. When
            nothing changed since the newest moment, oillamp saves nothing, and the page says so
            rather than leaving the user to wonder. The chat says what came of a save too, since
            the user can save from there, through the genie's menu.
        """
        given: 'a genie that woke, answered a question and went to sleep'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait {
                app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft('Plot the sales figures') } }
                app.send()
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && app.state().get().find(jafar).orElseThrow().conversations().chatCount() == 1 }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        when: 'the user opens its history'
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HISTORY) } }

        then: 'the chat is the one moment, and there is none for waking or sleeping'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().read() }
            app.state().get().find(jafar).orElseThrow().history().moments()*.kind() == [History.Kind.CHATTED]

        and: 'it is titled by its conversation'
            var chatted = app.state().get().find(jafar).orElseThrow().history().moments().first()
            var now = LocalDateTime.ofInstant(NOW, BERLIN)
            app.state().get().find(jafar).orElseThrow().history().title(chatted, app.state().get().find(jafar).orElseThrow(), now, Lang.ENGLISH) == 'Talked: Plot the sales figures'
            chatted.detail(Lang.ENGLISH) == 'Saved once the chat had gone quiet'

        when: 'the user saves with nothing changed'
            SwingUtilities.invokeAndWait { app.save(jafar, 'before the big refactor') }

        then: 'nothing is saved, the newest moment is picked, and the page says why'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().busy().isEmpty() && !app.state().get().find(jafar).orElseThrow().history().note().isEmpty() }
            app.state().get().find(jafar).orElseThrow().history().picked() == app.state().get().find(jafar).orElseThrow().history().moments().first().id()
            app.state().get().find(jafar).orElseThrow().history().moments().first().kind() == History.Kind.CHATTED
            app.state().get().find(jafar).orElseThrow().transcript().entries().last().text() == app.state().get().find(jafar).orElseThrow().history().note()

        when: 'a file changes in the genie\'s home, and the user saves again'
            Files.writeString(Lamp.agentHome(app.lampOf(jafar)).orElseThrow().resolve('plan.md'), 'refactor the parser')
            SwingUtilities.invokeAndWait { app.save(jafar, 'before the big refactor') }

        then: 'the save is the newest moment, picked, and titled by what the user wrote'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().first().kind() == History.Kind.SAVED }
            var saved = app.state().get().find(jafar).orElseThrow().history().moments().first()
            app.state().get().find(jafar).orElseThrow().history().title(saved, app.state().get().find(jafar).orElseThrow(), now, Lang.ENGLISH) == 'before the big refactor'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().picked() == saved.id() }
            app.state().get().find(jafar).orElseThrow().history().note().isEmpty()
            app.state().get().find(jafar).orElseThrow().history().problem().isEmpty()

        and: 'the chat says so'
            var told = app.state().get().find(jafar).orElseThrow().transcript().entries().last().text()
            told == 'Saved as a moment of the history: before the big refactor. The history page brings the genie back to it.'
    }

    def 'Going back brings the files and the conversations back, sleeping meanwhile, and can be undone'() {
        reportInfo """
            Going back to a moment makes the genie's home what it was then, with its
            conversations, which pi keeps there. oillamp refuses while the sandbox runs, so an
            awake genie sleeps while it goes back, and wakes again after. Before going back,
            oillamp saves the home as it is, if that changed since the newest moment, so nothing
            is lost: the history then offers to undo, which goes back to the moment that was the
            newest. A chat not yet saved is saved as the sandbox stops, so going back loses no
            answer either. Undoing is itself a going back, which the history titles "Undid going
            back to how it was …"; it ends the offer, since both ways are in the history. So does
            a chat or a save afterwards, once it is saved.
        """
        given: 'an awake genie, its history open, that answered once and slept, then woke, got a file, and answered again'
            var questionsAsked = { app.state().get().find(jafar).orElseThrow().conversations().all().first().steps().count { it.asked() } }
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HISTORY) } }
            SwingUtilities.invokeAndWait {
                app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft('Write a parser') } }
                app.send()
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && app.state().get().find(jafar).orElseThrow().conversations().chatCount() == 1 }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            sandbox.machine { it.clockAt(NOW.plus(Duration.ofMinutes(20))) }
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            Files.writeString(Lamp.agentHome(app.lampOf(jafar)).orElseThrow().resolve('plan.md'), 'refactor the parser')
            SwingUtilities.invokeAndWait {
                app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft('Now refactor it') } }
                app.send()
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && questionsAsked() == 2 }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().count { it.kind() == History.Kind.CHATTED } == 1 }

        when: 'the user goes back to just after the first chat'
            var afterTheFirstAnswer = app.state().get().find(jafar).orElseThrow().history().moments().find { it.kind() == History.Kind.CHATTED }
            SwingUtilities.invokeAndWait { app.goBack(jafar, afterTheFirstAnswer.id()) }

        then: 'the genie is awake again, and its home is as it was then'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().first().kind() == History.Kind.WENT_BACK }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && app.state().get().find(jafar).orElseThrow().history().busy().isEmpty() }
            app.state().get().find(jafar).orElseThrow().history().problem() == ''
            !Files.exists(Lamp.agentHome(app.lampOf(jafar)).orElseThrow().resolve('plan.md'))

        and: 'its conversation holds the first question only'
            waitUntil { questionsAsked() == 1 }

        and: 'the history says it went back, and offers to undo that'
            var wentBack = app.state().get().find(jafar).orElseThrow().history().moments().first()
            app.state().get().find(jafar).orElseThrow().history().wentBackTo(wentBack).get().id() == afterTheFirstAnswer.id()
            var undo = app.state().get().find(jafar).orElseThrow().history().undo()
            undo.isPresent()

        when: 'the user undoes it'
            SwingUtilities.invokeAndWait { app.goBack(jafar, undo.get().id()) }

        then: 'the file and the second question are back'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().first().id() != wentBack.id() }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && questionsAsked() == 2 && app.state().get().find(jafar).orElseThrow().history().busy().isEmpty() }
            Files.readString(Lamp.agentHome(app.lampOf(jafar)).orElseThrow().resolve('plan.md')) == 'refactor the parser'

        and: 'the history says it undid the going back, and offers no undo of the undo'
            var undone = app.state().get().find(jafar).orElseThrow().history().moments().first()
            undone.kind() == History.Kind.WENT_BACK
            app.state().get().find(jafar).orElseThrow().history().undid(undone).get().id() == wentBack.id()
            app.state().get().find(jafar).orElseThrow().history().undo().isEmpty()

        when: 'the genie answers again, and sleeps'
            SwingUtilities.invokeAndWait {
                app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft('Thanks') } }
                app.send()
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && questionsAsked() == 3 }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }

        then: 'there is nothing to undo any more'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().first().kind() == History.Kind.CHATTED }
            app.state().get().find(jafar).orElseThrow().history().undo().isEmpty()
    }

    def 'When oillamp saves by itself is read from the lamp, and the history page changes it'() {
        reportInfo """
            Under the hood oillamp saves by itself at four moments, which the lamp's file
            switches on and off: as the genie wakes, after it went to sleep, before a job, and
            after one. The questions of a chat are saved together instead, once the genie was
            asked nothing for a while, ten minutes unless the lamp says otherwise, or around each
            question as around a job. The history page shows these settings and writes them into
            the lamp. A genie that never woke has no lamp yet, and so nothing to read. The lamp
            reads its file as it wakes, so the page lets the user change them only while the
            genie sleeps.
        """
        when: 'the user opens the history of a genie that never woke'
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HISTORY) } }

        then: 'nothing is saved, and there is nothing to say when it saves'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().read() }
            app.state().get().find(jafar).orElseThrow().history().moments().isEmpty()
            app.state().get().find(jafar).orElseThrow().history().autosave().isEmpty()

        when: 'it wakes once, and goes to sleep, while the page is open'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }

        then: 'the page shows that a new lamp saves around its jobs, and a chat once quiet for ten minutes'
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP && app.state().get().find(jafar).orElseThrow().history().autosave().isPresent() }
            app.state().get().find(jafar).orElseThrow().history().autosave().get() == new History.Autosave(false, false, true, true, 10)
            app.state().get().find(jafar).orElseThrow().history().autosave().get().when(Lang.ENGLISH) == 'finishes a job or was asked nothing for 10 minutes'

        when: 'the user switches on saving as it wakes'
            SwingUtilities.invokeAndWait { app.autosave(jafar, app.state().get().find(jafar).orElseThrow().history().autosave().get().withWaking(true)) }

        then: 'the lamp\'s file has it'
            waitUntil { Lamp.at(app.lampOf(jafar)).autosave().startup() }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().autosave().get().waking() }
            app.state().get().find(jafar).orElseThrow().history().autosave().get().when(Lang.ENGLISH) == 'wakes, finishes a job or was asked nothing for 10 minutes'
            app.state().get().find(jafar).orElseThrow().history().problem().isEmpty()

        when: 'the user lets a chat be quiet for an hour'
            SwingUtilities.invokeAndWait { app.autosave(jafar, app.state().get().find(jafar).orElseThrow().history().autosave().get().withChatQuietMinutes(60)) }

        then: 'the lamp\'s file has it, and the page says it'
            waitUntil { Lamp.at(app.lampOf(jafar)).autosave().chatQuietMinutes() == 60 }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().autosave().get().chatQuietMinutes() == 60 }
            app.state().get().find(jafar).orElseThrow().history().autosave().get().when(Lang.ENGLISH) == 'wakes, finishes a job or was asked nothing for 1 hour'
            app.state().get().find(jafar).orElseThrow().history().autosave().get().when(Lang.GERMAN) == 'aufwacht, einen Auftrag beendet oder 1 Stunde lang nichts gefragt wurde'

        when: 'the genie wakes again'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }

        then: 'oillamp saved as it woke, and the page shows it'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().any { it.kind() == History.Kind.WOKE } }
    }

    def 'The history page says when a genie saves by itself: #when'() {
        reportInfo """
            Let's consider the sentence at the top of a genie's history page, such as "Saved
            whenever Jafar wakes, finishes a job or was asked nothing for 10 minutes." It names
            the moments its lamp saves at by itself. A chat is saved once the genie was asked
            nothing for so long, unless every question is saved by itself, and then the moments
            around a job are those around an answer too. Saving only before a job, without after,
            is named by its beginning.
        """
        expect:
            new History.Autosave(waking, sleeping, beforeRun, afterRun, quiet).when(Lang.ENGLISH) == when

        where:
            waking | sleeping | beforeRun | afterRun | quiet || when
            false  | false    | true      | true     | 10    || 'finishes a job or was asked nothing for 10 minutes'
            true   | true     | true      | true     | 30    || 'wakes, finishes a job, was asked nothing for 30 minutes or sleeps'
            false  | false    | true      | false    | 120   || 'begins a job or was asked nothing for 2 hours'
            false  | false    | false     | false    | 1     || 'was asked nothing for 1 minute'
            false  | false    | true      | true     | 0     || 'answers or finishes a job'
            false  | false    | true      | false    | 0     || 'begins to answer or begins a job'
            false  | false    | false     | false    | 0     || ''
    }

    def 'A long history shows its newest forty moments, day by day, and waking does not end the offer to undo'() {
        reportInfo """
            A genie that is used a lot gathers a moment for every answer, so the page shows the
            newest forty, grouped by the day they were saved on, and a button shows the rest.
            Right after going back, an awake genie is woken again, which may save a moment of
            its own; that must not take away the offer to undo, while an answer does.
        """
        given: 'fifty answers, one an hour, the newest first'
            var moment = { int hoursAgo, History.Kind kind, String message ->
                new History.Moment(Integer.toHexString(1000 + hoursAgo) + '0' * 36, NOW.minus(Duration.ofHours(hoursAgo)),
                                   kind, message, '', '', Optional.empty(), '', Tuple.of(String, 'main'))
            }
            var read = { List<History.Moment> moments ->
                History.UNREAD.readAs(1, Tuple.of(History.Branch, new History.Branch('main', true, moments.first().id())),
                                      Tuple.of(History.Moment, moments as History.Moment[]))
            }
            var answers = (0..<50).collect { moment(it, History.Kind.RAN, '') }
            var history = read(answers)

        expect: 'forty are shown, on the days they were saved on, the newest day first'
            history.hidden() == 10
            var rows = history.lines(BERLIN).rows()
            rows.count { it instanceof Lines.Row.Shown } == 40
            var days = rows.findAll { it instanceof Lines.Row.Day }*.date()
            days.first() == LocalDateTime.ofInstant(NOW, BERLIN).toLocalDate()
            days.first() > days.last()

        and: 'every one, once the user clicks "Show 10 earlier moments"'
            history.withEarlier(true).hidden() == 0
            history.withEarlier(true).lines(BERLIN).rows().count { it instanceof Lines.Row.Shown } == 50

        when: 'the genie went back to the third answer, then woke again'
            var third = answers[2]
            var before = moment(-1, History.Kind.BEFORE_GOING_BACK, 'before restoring ' + answers[0].id().take(8))
            var wentBack = moment(-2, History.Kind.WENT_BACK, 'back to ' + third.id().take(8) + ' (run, then)')
            var woke = moment(-3, History.Kind.WOKE, '')
            var afterwards = read([woke, wentBack, before] + answers)

        then: 'it offers to undo, and knows where it went back to'
            afterwards.undo().get() == before
            afterwards.wentBackTo(wentBack).get() == third

        when: 'the genie undid that going back instead'
            var undid = moment(-4, History.Kind.WENT_BACK, 'back to ' + before.id().take(8) + ' (before-restore, then)')
            var undone = read([undid] + afterwards.moments().toList())

        then: 'it knows that was an undo, and offers no undo of it'
            undone.undid(undid).get() == wentBack
            undone.undid(wentBack).isEmpty()
            undone.undo().isEmpty()

        when: 'the genie answers once more'
            var newest = moment(-5, History.Kind.RAN, '')
            var answeredAgain = read([newest] + afterwards.moments().toList())

        then:
            answeredAgain.undo().isEmpty()
    }

    def 'New branch… keeps the newest moment as a branch of its own, and the genie stays on the branch it lives on'() {
        reportInfo """
            Let's consider a genie, Jafar, that answered once and went to sleep. On its history
            page the user clicks "New branch…", types rust-rewrite, and Genies calls
            `LampDirectory.branch('rust-rewrite')`, which starts the branch at the newest moment of
            the branch Jafar lives on, main. Starting a branch changes nothing in Jafar's home: it
            only writes the branch into the lamp's history, pointing at that moment. So Jafar
            stays on main, the page still shows main's line, and main's newest card says
            "⑂ rust-rewrite stands here". rust-rewrite gets no line of its own until it has a
            moment of its own. A name oillamp would refuse, such as "Rust Rewrite", is caught by
            `History.branchNameProblem` before Genies calls `LampDirectory.branch`, and the
            dialog opens again with the sentence it gives.
        """
        given: 'Jafar answered once and sleeps, with its history open'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait {
                app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft('Write a parser') } }
                app.send()
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && app.state().get().find(jafar).orElseThrow().conversations().chatCount() == 1 }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HISTORY) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().read() && !app.state().get().find(jafar).orElseThrow().history().moments().isEmpty() }
            var newest = app.state().get().find(jafar).orElseThrow().history().moments().first()

        when: 'the user starts the branch rust-rewrite'
            SwingUtilities.invokeAndWait { app.startBranch(jafar, 'rust-rewrite', '') }

        then: 'the lamp has it, at the newest moment, and Jafar still lives on main'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().branches().size() == 2 }
            Lamp.at(app.lampOf(jafar)).branches().collect { [it.name(), it.current(), it.newest().get().id()] } ==
                    [['main', true, newest.id()], ['rust-rewrite', false, newest.id()]]
            app.state().get().find(jafar).orElseThrow().history().lampBranch() == 'main'
            app.state().get().find(jafar).orElseThrow().history().shownLine() == 'main'

        and: 'main\'s newest card says rust-rewrite stands there, and no lane is drawn for it'
            var lines = app.state().get().find(jafar).orElseThrow().history().lines(BERLIN)
            lines.lanes().isEmpty()
            lines.rows().find { it instanceof Lines.Row.Shown }.newestOf().toList() == ['rust-rewrite']

        when: 'the user types a name oillamp would refuse'
            var refused = app.state().get().find(jafar).orElseThrow().history().branchNameProblem('Rust Rewrite', Lang.ENGLISH)
            SwingUtilities.invokeAndWait { app.startBranch(jafar, 'Rust Rewrite', '') }

        then: 'the dialog can say why before Genies calls LampDirectory.branch, and no branch is started'
            refused.get().startsWith('A branch\'s name is made of lowercase letters')
            app.state().get().find(jafar).orElseThrow().history().busy().isEmpty()
            Lamp.at(app.lampOf(jafar)).branches()*.name() == ['main', 'rust-rewrite']
    }

    def 'Showing another branch only changes the page, and Move Jafar to … moves the genie there while it sleeps'() {
        reportInfo """
            Let's consider Jafar, living on main, with a branch rust-rewrite started at main's
            newest moment. Clicking the chip rust-rewrite above the lines calls `History.showLine`
            through the window's lens: the page shows rust-rewrite's line with its words, and that
            is all. Jafar's home stays main's, and `LampDirectory.branches()` still marks main as
            the current branch. Moving Jafar is a button of its own, "Move Jafar to rust-rewrite…",
            which calls `LampDirectory.switchTo('rust-rewrite')`. The lamp refuses that while its
            sandbox runs, so Genies offers it only while Jafar sleeps, and does nothing while it
            is awake. Once moved, the page follows Jafar's branch again, and a save goes to
            rust-rewrite alone. When the user clicks the chip main, that save is a dot on a line
            of its own to the left of main's, the line of the branch Jafar lives on, with the
            lamp's "now" node at its top.
        """
        given: 'Jafar answered once and slept, with rust-rewrite started at the moment that saved, and is awake again'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait {
                app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft('Write a parser') } }
                app.send()
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && app.state().get().find(jafar).orElseThrow().conversations().chatCount() == 1 }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HISTORY) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().any { it.kind() == History.Kind.CHATTED } }
            SwingUtilities.invokeAndWait { app.startBranch(jafar, 'rust-rewrite', '') }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().branches().size() == 2 }

        when: 'the user clicks rust-rewrite above the lines'
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHistory(genie.history().showLine('rust-rewrite')) } }
            }

        then: 'the page shows rust-rewrite, and the genie still lives on main'
            app.state().get().find(jafar).orElseThrow().history().shownLine() == 'rust-rewrite'
            app.state().get().find(jafar).orElseThrow().history().lampBranch() == 'main'
            Lamp.at(app.lampOf(jafar)).branches().find { it.current() }.name() == 'main'

        when: 'Genies is called to move Jafar while it is awake, as the button would if it were offered'
            SwingUtilities.invokeAndWait { app.switchBranch(jafar, 'rust-rewrite') }

        then: 'nothing happens: the button is offered only while Jafar sleeps'
            app.state().get().find(jafar).orElseThrow().history().busy().isEmpty()
            Lamp.at(app.lampOf(jafar)).branches().find { it.current() }.name() == 'main'

        when: 'Jafar sleeps, and the user clicks Move Jafar to rust-rewrite…, then Move in the dialog'
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.switchBranch(jafar, 'rust-rewrite') }

        then: 'Jafar lives on rust-rewrite, and the page follows it there'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().lampBranch() == 'rust-rewrite' && app.state().get().find(jafar).orElseThrow().history().busy().isEmpty() }
            Lamp.at(app.lampOf(jafar)).branches().find { it.current() }.name() == 'rust-rewrite'
            app.state().get().find(jafar).orElseThrow().history().line() == ''
            app.state().get().find(jafar).orElseThrow().history().problem() == ''

        when: 'a file changes in Jafar\'s home, the user saves, and then looks at main'
            Files.writeString(Lamp.agentHome(app.lampOf(jafar)).orElseThrow().resolve('plan.md'), 'in Rust')
            SwingUtilities.invokeAndWait { app.save(jafar, 'in Rust') }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().first().message() == 'in Rust' }
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHistory(genie.history().showLine('main')) } }
            }

        then: 'the save is on rust-rewrite alone, a dot on the lane of the branch Jafar lives on, under the lamp\'s node'
            var history = app.state().get().find(jafar).orElseThrow().history()
            history.moments().first().branches().toList() == ['rust-rewrite']
            var lines = history.lines(BERLIN)
            lines.lanes().collect { [it.branch(), it.lampIsOn()] } == [['rust-rewrite', true]]
            (lines.rows().first() as Lines.Row.Now).lampLane() == Optional.of(0)
            lines.rows().find { it instanceof Lines.Row.Between }.strands().first().dots()*.moment()*.message() == ['in Rust']
    }

    def 'Reset to here… shows what `LampDirectory.wouldReset` says would be lost, and only "Reset main" takes the later moments off the branch'() {
        reportInfo """
            Let's consider Jafar, asleep, that answered in two chats, each saved as it slept after
            it, and whose home has a file no moment holds yet. The user clicks the first chat's
            card, then "Reset to here…". Genies calls
            `LampDirectory.wouldReset(snapshot)`, which changes nothing, and the page shows what
            it said: the moment of the second chat would leave main, and the unsaved file would
            be lost. A reset keeps no copy, unlike going back, so the page shows this in a banner,
            with "Cancel", "Go back instead" and "Reset main". Cancel removes the banner and changes
            nothing. Clicking "Reset to here…" again and then "Reset main" calls
            `LampDirectory.reset(snapshot)`: the branch ends at the first chat, the file is
            gone, and no moment says it went back, so there is nothing to undo.
        """
        given: 'Jafar answered in two chats, sleeps, and has a file no moment holds'
            var questionsAsked = { app.state().get().find(jafar).orElseThrow().conversations().all().first().steps().count { it.asked() } }
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait {
                app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft('Write a parser') } }
                app.send()
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && app.state().get().find(jafar).orElseThrow().conversations().chatCount() == 1 }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait {
                app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft('Now refactor it') } }
                app.send()
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && questionsAsked() == 2 }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HISTORY) } }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().count { it.kind() == History.Kind.CHATTED } == 2 }
            var firstAnswer = app.state().get().find(jafar).orElseThrow().history().moments().findAll { it.kind() == History.Kind.CHATTED }.last()
            var unsaved = Lamp.agentHome(app.lampOf(jafar)).orElseThrow().resolve('notes.md')
            Files.writeString(unsaved, 'not saved yet')

        when: 'the user clicks Reset to here… on the first chat'
            SwingUtilities.invokeAndWait { app.planReset(jafar, firstAnswer.id()) }

        then: 'the page says what the lamp said would be lost, and nothing changed yet'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().plan().isPresent() }
            app.state().get().find(jafar).orElseThrow().history().plan().get() == new History.Plan.Reset(firstAnswer.id(), 1, true)
            app.state().get().find(jafar).orElseThrow().history().moments().first().id() != firstAnswer.id()
            Files.exists(unsaved)

        when: 'the user clicks Cancel'
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHistory(genie.history().withPlan(Optional.empty())) } }
            }

        then:
            app.state().get().find(jafar).orElseThrow().history().plan().isEmpty()
            Files.exists(unsaved)

        when: 'the user clicks Reset to here… again, then Reset main'
            SwingUtilities.invokeAndWait { app.planReset(jafar, firstAnswer.id()) }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().plan().isPresent() }
            SwingUtilities.invokeAndWait { app.reset(jafar, firstAnswer.id()) }

        then: 'main ends at the first chat, the unsaved file is gone, and there is nothing to undo'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().first().id() == firstAnswer.id() && app.state().get().find(jafar).orElseThrow().history().busy().isEmpty() }
            !Files.exists(unsaved)
            app.state().get().find(jafar).orElseThrow().history().plan().isEmpty()
            app.state().get().find(jafar).orElseThrow().history().undo().isEmpty()
            app.state().get().find(jafar).orElseThrow().history().moments().none { it.kind() == History.Kind.WENT_BACK }

        and: 'the conversation holds the first question only'
            waitUntil { questionsAsked() == 1 }
    }

    def 'Delete try… shows how many moments only try holds, and the banner\'s Delete try deletes it'() {
        reportInfo """
            Let's consider Jafar, asleep on main, with a branch try that it was moved to, saved
            one moment on, and was moved back from. The page shows try, and the user clicks
            "Delete try…". Genies calls `LampDirectory.wouldDeleteBranch('try')`, and the page
            shows what the lamp answered in a banner: "One moment is on try alone, and goes with
            it." The user clicks the banner's "Delete try", Genies calls
            `LampDirectory.deleteBranch('try')`, and the page, which showed try, follows Jafar's
            branch again, since try is gone.
        """
        given: 'Jafar sleeps on main, and try holds one moment of its own'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HISTORY) } }
            Files.writeString(Lamp.agentHome(app.lampOf(jafar)).orElseThrow().resolve('plan.md'), 'the plan')
            SwingUtilities.invokeAndWait { app.save(jafar, 'the plan') }
            waitUntil { !app.state().get().find(jafar).orElseThrow().history().moments().isEmpty() }
            SwingUtilities.invokeAndWait { app.startBranch(jafar, 'try', '') }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().branches().size() == 2 }
            SwingUtilities.invokeAndWait { app.switchBranch(jafar, 'try') }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().lampBranch() == 'try' && app.state().get().find(jafar).orElseThrow().history().busy().isEmpty() }
            Files.writeString(Lamp.agentHome(app.lampOf(jafar)).orElseThrow().resolve('plan.md'), 'tried')
            SwingUtilities.invokeAndWait { app.save(jafar, 'tried') }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().first().message() == 'tried' }
            SwingUtilities.invokeAndWait { app.switchBranch(jafar, 'main') }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().lampBranch() == 'main' && app.state().get().find(jafar).orElseThrow().history().busy().isEmpty() }
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withHistory(genie.history().showLine('try')) } }
            }

        when: 'the user clicks Delete try…'
            SwingUtilities.invokeAndWait { app.planDeletingBranch(jafar, 'try') }

        then: 'the page says one moment is on try alone'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().plan().isPresent() }
            app.state().get().find(jafar).orElseThrow().history().plan().get() == new History.Plan.DeleteBranch('try', 1)

        when: 'the user clicks Delete try in the banner'
            SwingUtilities.invokeAndWait { app.deleteBranch(jafar, 'try') }

        then: 'try is gone, and the page shows main'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().branches()*.name() == ['main'] }
            Lamp.at(app.lampOf(jafar)).branches()*.name() == ['main']
            app.state().get().find(jafar).orElseThrow().history().shownLine() == 'main'
            app.state().get().find(jafar).orElseThrow().history().plan().isEmpty()
    }

    def 'How large the history may grow is read from the lamp and changed on the page, and a save past it says what oillamp deleted'() {
        reportInfo """
            Let's consider Jafar, asleep, whose lamp's `oillamp.toml` lets its history grow 2 GB
            beyond its first moment, as a new lamp's does. The page reads that with
            `LampDirectory.historyMaxGrowthGb()`. The user picks a far smaller size, about 32 KB,
            and Genies writes it with `LampDirectory.historyMaxGrowthGb(0.00003)`; while Jafar is
            awake it does not, since a lamp reads its file as it wakes. With nothing saved past
            the size, clicking "Tidy up now…" calls `LampDirectory.wouldPruneHistory()`, and the page
            says "The history fits within its size, so there is nothing to delete." Then the user saves a 64 KB file twice, in two versions.
            The second save takes the history past its size, so oillamp deletes the first moment,
            and reports it in a `HistoryPruned` event, which lists that moment among those
            deleted. The page says so, quietly, beside the size: how many moments went, and how
            much the history takes now.
        """
        given: 'Jafar woke once and sleeps, with its history open'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HISTORY) } }

        expect: 'the size a new lamp has'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().maxGrowthGb() == Optional.of(2d) }

        when: 'the user picks about 32 KB'
            SwingUtilities.invokeAndWait { app.maxGrowth(jafar, 0.00003) }

        then: 'the lamp\'s file has it'
            waitUntil { Lamp.at(app.lampOf(jafar)).historyMaxGrowthGb() == 0.00003d }
            app.state().get().find(jafar).orElseThrow().history().maxGrowthGb() == Optional.of(0.00003d)

        when: 'the user clicks Tidy up now… with nothing saved'
            SwingUtilities.invokeAndWait { app.planPruning(jafar) }

        then: 'the page says there is nothing to delete, and shows no banner'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().note().contains('nothing to delete') }
            app.state().get().find(jafar).orElseThrow().history().plan().isEmpty()

        when: 'the user saves a 64 KB file, then another version of it'
            var file = Lamp.agentHome(app.lampOf(jafar)).orElseThrow().resolve('data.bin')
            Files.write(file, randomBytes(1))
            SwingUtilities.invokeAndWait { app.save(jafar, 'first version') }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments()*.message() == ['first version'] }
            var first = app.state().get().find(jafar).orElseThrow().history().moments().first()
            Files.write(file, randomBytes(2))
            SwingUtilities.invokeAndWait { app.save(jafar, 'second version') }

        then: 'oillamp deleted the first moment, and the page says what it deleted'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().pruned().isPresent() }
            app.state().get().find(jafar).orElseThrow().history().pruned().get().moments() == 1
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments()*.message() == ['second version'] }
            app.state().get().find(jafar).orElseThrow().history().find(first.id()).isEmpty()

        when: 'Jafar wakes, and the user picks another size'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.maxGrowth(jafar, 5) }

        then: 'nothing is written while it is awake'
            app.state().get().find(jafar).orElseThrow().history().maxGrowthGb() == Optional.of(0.00003d)
            Lamp.at(app.lampOf(jafar)).historyMaxGrowthGb() == 0.00003d
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** 64 KB that do not compress, so that a save really takes that much room in the history. */
    private static byte[] randomBytes(int seed) {
        var bytes = new byte[65536]
        new Random(seed).nextBytes(bytes)
        bytes
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        var shown = app.state().get().find(jafar).orElseThrow()
        throw new AssertionError("never happened; the genie is ${shown.phase()} (${shown.activity()}), " +
                                 "its history ${shown.history()}" as Object)
    }
}
