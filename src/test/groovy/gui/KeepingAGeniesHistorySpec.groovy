package gui

import dev.gui.ErrorLog
import dev.gui.Genies
import dev.gui.genie.Shelf
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
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class KeepingAGeniesHistorySpec extends Specification {

    static final Instant NOW = Instant.parse('2026-09-29T12:32:00Z')
    static final ZoneId BERLIN = ZoneId.of('Europe/Berlin')

    @TempDir Path tmp
    ScenarioHost sandbox
    Shelf shelf
    Genies app
    UUID jafar

    def setup() {
        sandbox = new ScenarioHost(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen').clockAt(NOW).timeZone('Europe/Berlin')
                            .windowsStayOpenFor(Duration.ofSeconds(80)).agent { String prompt -> 'You said: ' + prompt } }
        // Jafar on the shelf, never woken, and Genies opened with a key in its environment.
        shelf = new Shelf(sandbox.home.resolve('genies'))
        var genie = Genie.named('Jafar')
        jafar = genie.id()
        shelf.keep(Tuple.of(Genie, genie))
        shelf.keep(Settings.defaults())
        app = Genies.open(shelf, new ErrorLog(shelf.errorLog()), sandbox.launcher,
                          { name -> name == Settings.KEY_VARIABLE ? Optional.of('sk-key') : Optional.empty() })
    }

    def cleanup() {
        var done = new CountDownLatch(1)
        onTheWindowsThread { app?.quit(false, Optional.empty(), { done.countDown() }) }
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
            wake()
            ask('Plot the sales figures')
            waitUntil { genie().phase() == Genie.Phase.READY && genie().conversations().chatCount() == 1 }
            onTheWindowsThread { app.sleep(jafar) }
            waitUntil { genie().phase() == Genie.Phase.ASLEEP }

        when: 'the user opens its history'
            open(GeniesState.Page.HISTORY)

        then: 'the moments are there, newest first: before the answer and the answer, and none for waking or sleeping'
            waitUntil { genie().history().read() }
            genie().history().moments()*.kind() == [History.Kind.RAN, History.Kind.BEFORE_RUN]

        and: 'the answer is titled by its conversation, with what the genie said'
            var answered = genie().history().moments().first()
            var now = LocalDateTime.ofInstant(NOW, BERLIN)
            genie().history().title(answered, genie(), now) == 'Answered: Plot the sales figures'
            answered.detail() == 'You said: Plot the sales figures'

        when: 'the user saves with nothing changed'
            onTheWindowsThread { app.save(jafar, 'before the big refactor') }

        then: 'nothing is saved, the newest moment is picked, and the page says why'
            waitUntil { genie().history().busy().isEmpty() && !genie().history().note().isEmpty() }
            genie().history().picked() == genie().history().moments().first().id()
            genie().history().moments().first().kind() == History.Kind.RAN
            genie().transcript().entries().last().text() == genie().history().note()

        when: 'a file changes in the genie\'s home, and the user saves again'
            Files.writeString(home().resolve('plan.md'), 'refactor the parser')
            onTheWindowsThread { app.save(jafar, 'before the big refactor') }

        then: 'the save is the newest moment, picked, and titled by what the user wrote'
            waitUntil { genie().history().moments().first().kind() == History.Kind.SAVED }
            var saved = genie().history().moments().first()
            genie().history().title(saved, genie(), now) == 'before the big refactor'
            waitUntil { genie().history().picked() == saved.id() }
            genie().history().note().isEmpty()
            genie().history().problem().isEmpty()

        and: 'the chat says so'
            var told = genie().transcript().entries().last().text()
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
            var questionsAsked = { genie().conversations().all().first().steps().count { it.asked() } }
            wake()
            open(GeniesState.Page.HISTORY)
            ask('Write a parser')
            waitUntil { genie().phase() == Genie.Phase.READY && genie().conversations().chatCount() == 1 }
            sandbox.machine { it.clockAt(NOW.plus(Duration.ofMinutes(20))) }
            Files.writeString(home().resolve('plan.md'), 'refactor the parser')
            ask('Now refactor it')
            waitUntil { genie().phase() == Genie.Phase.READY && questionsAsked() == 2 }
            waitUntil { genie().history().moments().count { it.kind() == History.Kind.RAN } == 2 }

        when: 'the user goes back to just after the first answer'
            var afterTheFirstAnswer = genie().history().moments().findAll { it.kind() == History.Kind.RAN }.last()
            onTheWindowsThread { app.goBack(jafar, afterTheFirstAnswer.id()) }

        then: 'the genie is awake again, and its home is as it was then'
            waitUntil { genie().history().moments().first().kind() == History.Kind.WENT_BACK }
            waitUntil { genie().phase() == Genie.Phase.READY && genie().history().busy().isEmpty() }
            genie().history().problem() == ''
            !Files.exists(home().resolve('plan.md'))

        and: 'its conversation holds the first question only'
            waitUntil { questionsAsked() == 1 }

        and: 'the history says it went back, and offers to undo that'
            var wentBack = genie().history().moments().first()
            genie().history().wentBackTo(wentBack).get().id() == afterTheFirstAnswer.id()
            var undo = genie().history().undo()
            undo.isPresent()

        when: 'the user undoes it'
            onTheWindowsThread { app.goBack(jafar, undo.get().id()) }

        then: 'the file and the second question are back'
            waitUntil { genie().history().moments().first().id() != wentBack.id() }
            waitUntil { genie().phase() == Genie.Phase.READY && questionsAsked() == 2 && genie().history().busy().isEmpty() }
            Files.readString(home().resolve('plan.md')) == 'refactor the parser'

        and: 'the history says it undid the going back, and offers no undo of the undo'
            var undone = genie().history().moments().first()
            undone.kind() == History.Kind.WENT_BACK
            genie().history().undid(undone).get().id() == wentBack.id()
            genie().history().undo().isEmpty()

        when: 'the genie answers again'
            ask('Thanks')
            waitUntil { genie().phase() == Genie.Phase.READY && questionsAsked() == 3 }

        then: 'there is nothing to undo any more'
            waitUntil { genie().history().moments().first().kind() == History.Kind.RAN }
            genie().history().undo().isEmpty()
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
            open(GeniesState.Page.HISTORY)

        then: 'nothing is saved, and there is nothing to say when it saves'
            waitUntil { genie().history().read() }
            genie().history().moments().isEmpty()
            genie().history().autosave().isEmpty()

        when: 'it wakes once, and goes to sleep, while the page is open'
            wake()
            onTheWindowsThread { app.sleep(jafar) }

        then: 'the page shows that a new lamp saves around its runs only'
            waitUntil { genie().phase() == Genie.Phase.ASLEEP && genie().history().autosave().isPresent() }
            genie().history().autosave().get() == new History.Autosave(false, false, true, true)
            genie().history().autosave().get().when() == 'answers'

        when: 'the user switches on saving as it wakes'
            onTheWindowsThread { app.autosave(jafar, genie().history().autosave().get().withWaking(true)) }

        then: 'the lamp\'s file has it'
            waitUntil { Lamp.at(lamp()).autosave().startup() }
            waitUntil { genie().history().autosave().get().waking() }
            genie().history().autosave().get().when() == 'wakes or answers'
            genie().history().problem().isEmpty()

        when: 'the genie wakes again'
            wake()

        then: 'oillamp saved as it woke, and the page shows it'
            waitUntil { genie().history().moments().any { it.kind() == History.Kind.WOKE } }
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

    /** Jafar as the window shows him now. */
    private Genie genie() { app.state().get().find(jafar).orElseThrow() }

    private void wake() {
        onTheWindowsThread { app.wake(jafar) }
        waitUntil { genie().phase() == Genie.Phase.READY }
    }

    /** The user goes to Jafar's `page`. */
    private void open(GeniesState.Page page) {
        onTheWindowsThread { app.state().update { it.select(jafar).withPage(page) } }
    }

    /** The user types `text` to Jafar and sends it. */
    private void ask(String text) {
        onTheWindowsThread {
            app.state().update { it.select(jafar).update(jafar) { genie -> genie.withDraft(text) } }
            app.send()
        }
    }

    private Path lamp() { shelf.lampOf(jafar) }

    private Path home() { Lamp.agentHome(lamp()).orElseThrow() }

    /** As the window calls the app: on Swing's event thread. */
    private static void onTheWindowsThread(Closure action) {
        SwingUtilities.invokeAndWait(action)
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw new AssertionError("never happened; the genie is ${genie().phase()} (${genie().activity()}), " +
                                 "its history ${genie().history()}" as Object)
    }
}
