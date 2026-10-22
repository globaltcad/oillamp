package gui

import dev.gui.Genies
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.History
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
 *  <p>oillamp saves a genie's home in its lamp around its answers, and whenever the user saves
 *  by hand. Each save is a moment the genie can go back to: its files, and its conversations
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

    def 'Answering a question is a moment of the history, and a save by hand adds one'() {
        reportInfo """
            The history page lists the moments oillamp saved the genie's home at, newest first,
            in words: it went to work on a question, it answered it (titled by the conversation,
            with what it said last). Unless the lamp says otherwise, oillamp does not save as the
            genie wakes or sleeps, only around its answers. The user can save by hand too, awake
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

        then: 'the moments are there, newest first: before the answer and the answer, and none for waking or sleeping'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().read() }
            app.state().get().find(jafar).orElseThrow().history().moments()*.kind() == [History.Kind.RAN, History.Kind.BEFORE_RUN]

        and: 'the answer is titled by its conversation, with what the genie said'
            var answered = app.state().get().find(jafar).orElseThrow().history().moments().first()
            var now = LocalDateTime.ofInstant(NOW, BERLIN)
            app.state().get().find(jafar).orElseThrow().history().title(answered, app.state().get().find(jafar).orElseThrow(), now) == 'Answered: Plot the sales figures'
            answered.detail() == 'You said: Plot the sales figures'

        when: 'the user saves with nothing changed'
            SwingUtilities.invokeAndWait { app.save(jafar, 'before the big refactor') }

        then: 'nothing is saved, the newest moment is picked, and the page says why'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().busy().isEmpty() && !app.state().get().find(jafar).orElseThrow().history().note().isEmpty() }
            app.state().get().find(jafar).orElseThrow().history().picked() == app.state().get().find(jafar).orElseThrow().history().moments().first().id()
            app.state().get().find(jafar).orElseThrow().history().moments().first().kind() == History.Kind.RAN
            app.state().get().find(jafar).orElseThrow().transcript().entries().last().text() == app.state().get().find(jafar).orElseThrow().history().note()

        when: 'a file changes in the genie\'s home, and the user saves again'
            Files.writeString(Lamp.agentHome(app.lampOf(jafar)).orElseThrow().resolve('plan.md'), 'refactor the parser')
            SwingUtilities.invokeAndWait { app.save(jafar, 'before the big refactor') }

        then: 'the save is the newest moment, picked, and titled by what the user wrote'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().first().kind() == History.Kind.SAVED }
            var saved = app.state().get().find(jafar).orElseThrow().history().moments().first()
            app.state().get().find(jafar).orElseThrow().history().title(saved, app.state().get().find(jafar).orElseThrow(), now) == 'before the big refactor'
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
            newest. Undoing is itself a going back, which the history names as an undo; it ends
            the offer, since both ways are in the history. So does an answer or a save
            afterwards, since undoing would then lose them.
        """
        given: 'an awake genie, its history open, that answered once, then got a file, then answered again'
            var questionsAsked = { app.state().get().find(jafar).orElseThrow().conversations().all().first().steps().count { it.asked() } }
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.HISTORY) } }
            SwingUtilities.invokeAndWait {
                app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft('Write a parser') } }
                app.send()
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && app.state().get().find(jafar).orElseThrow().conversations().chatCount() == 1 }
            sandbox.machine { it.clockAt(NOW.plus(Duration.ofMinutes(20))) }
            Files.writeString(Lamp.agentHome(app.lampOf(jafar)).orElseThrow().resolve('plan.md'), 'refactor the parser')
            SwingUtilities.invokeAndWait {
                app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft('Now refactor it') } }
                app.send()
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && questionsAsked() == 2 }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().count { it.kind() == History.Kind.RAN } == 2 }

        when: 'the user goes back to just after the first answer'
            var afterTheFirstAnswer = app.state().get().find(jafar).orElseThrow().history().moments().findAll { it.kind() == History.Kind.RAN }.last()
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

        when: 'the genie answers again'
            SwingUtilities.invokeAndWait {
                app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft('Thanks') } }
                app.send()
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY && questionsAsked() == 3 }

        then: 'there is nothing to undo any more'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().first().kind() == History.Kind.RAN }
            app.state().get().find(jafar).orElseThrow().history().undo().isEmpty()
    }

    def 'When oillamp saves by itself is read from the lamp, and the history page changes it'() {
        reportInfo """
            Under the hood oillamp saves by itself at four moments, which the lamp's file
            switches on and off: as the genie wakes, after it went to sleep, as it begins on a
            question or a job, and as it answers or ends one. The history page shows these
            switches and writes them into the lamp. A genie that never woke has no lamp yet, and
            so nothing to read. The lamp reads its file as it wakes, so the page lets the user
            change them only while the genie sleeps.
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

        then: 'the page shows that a new lamp saves around its runs only'
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP && app.state().get().find(jafar).orElseThrow().history().autosave().isPresent() }
            app.state().get().find(jafar).orElseThrow().history().autosave().get() == new History.Autosave(false, false, true, true)
            app.state().get().find(jafar).orElseThrow().history().autosave().get().when() == 'answers'

        when: 'the user switches on saving as it wakes'
            SwingUtilities.invokeAndWait { app.autosave(jafar, app.state().get().find(jafar).orElseThrow().history().autosave().get().withWaking(true)) }

        then: 'the lamp\'s file has it'
            waitUntil { Lamp.at(app.lampOf(jafar)).autosave().startup() }
            waitUntil { app.state().get().find(jafar).orElseThrow().history().autosave().get().waking() }
            app.state().get().find(jafar).orElseThrow().history().autosave().get().when() == 'wakes or answers'
            app.state().get().find(jafar).orElseThrow().history().problem().isEmpty()

        when: 'the genie wakes again'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }

        then: 'oillamp saved as it woke, and the page shows it'
            waitUntil { app.state().get().find(jafar).orElseThrow().history().moments().any { it.kind() == History.Kind.WOKE } }
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
                                   kind, message, '', '', Optional.empty(), '')
            }
            var answers = (0..<50).collect { moment(it, History.Kind.RAN, '') }
            var history = History.UNREAD.readAs(Tuple.of(History.Moment, answers as History.Moment[]))

        expect: 'forty are shown, on the days they were saved on, the newest day first'
            history.hidden() == 10
            var days = history.days(BERLIN)
            days*.moments()*.size().sum() == 40
            days.first().date() == LocalDateTime.ofInstant(NOW, BERLIN).toLocalDate()
            days.first().date() > days.last().date()

        and: 'every one, once asked for'
            history.withEarlier(true).hidden() == 0
            history.withEarlier(true).days(BERLIN)*.moments()*.size().sum() == 50

        when: 'the genie went back to the third answer, then woke again'
            var third = answers[2]
            var before = moment(-1, History.Kind.BEFORE_GOING_BACK, 'before restoring ' + answers[0].id().take(8))
            var wentBack = moment(-2, History.Kind.WENT_BACK, 'back to ' + third.id().take(8) + ' (run, then)')
            var woke = moment(-3, History.Kind.WOKE, '')
            var afterwards = History.UNREAD.readAs(Tuple.of(History.Moment, ([woke, wentBack, before] + answers) as History.Moment[]))

        then: 'it offers to undo, and knows where it went back to'
            afterwards.undo().get() == before
            afterwards.wentBackTo(wentBack).get() == third

        when: 'the genie undid that going back instead'
            var undid = moment(-4, History.Kind.WENT_BACK, 'back to ' + before.id().take(8) + ' (before-restore, then)')
            var undone = History.UNREAD.readAs(afterwards.moments().addAt(0, undid))

        then: 'it knows that was an undo, and offers no undo of it'
            undone.undid(undid).get() == wentBack
            undone.undid(wentBack).isEmpty()
            undone.undo().isEmpty()

        when: 'the genie answers once more'
            var newest = moment(-5, History.Kind.RAN, '')
            var answeredAgain = History.UNREAD.readAs(afterwards.moments().addAt(0, newest))

        then:
            answeredAgain.undo().isEmpty()
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
                                 "its history ${shown.history()}" as Object)
    }
}
