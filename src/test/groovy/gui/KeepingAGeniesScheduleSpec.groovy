package gui

import dev.gui.Genies
import dev.gui.model.Entry
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.Lang
import dev.gui.model.JobDraft
import dev.gui.model.Schedule
import dev.gui.model.Settings
import dev.lamp.Lamp
import oillamp.ScenarioHost
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import sprouts.Tuple

import javax.swing.SwingUtilities
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 *  A genie's schedule, read and changed from its page, and the jobs on it waking the genie.
 *
 *  <p>The schedule lives in the genie's lamp, so it is read and changed whether the genie is
 *  awake or asleep, all through the Lamp API. Genies lights every lamp with scheduling on, so a
 *  job wakes the genie while it is awake; the page follows the run as it happens, and lists it
 *  afterwards with what the genie said. Here the app is driven as its window drives it, through
 *  {@link dev.gui.Genies}; its lamps are oillamp's real engine, in this JVM, on a simulated
 *  machine whose clock the scenario moves, with a stand-in pi.
 *
 *  <p>Each scenario reads on its own, and shows how the window talks to the app: every call to
 *  Genies, and every read of its state, is written out where it happens, on Swing's event
 *  thread as the window makes it. No helper method stands between a scenario and that API, as
 *  one would hide the API the scenario is about; repeating a call is fine, hiding one is not.
 *  Helpers that do not touch the app, such as waiting for a condition, are fine.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class KeepingAGeniesScheduleSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-09-29T12:32:00Z')
    static final ZoneId BERLIN = ZoneId.of('Europe/Berlin')

    @TempDir Path tmp
    ScenarioHost sandbox
    Genies app
    UUID jafar
    final CountDownLatch release = new CountDownLatch(1)

    def setup() {
        sandbox = new ScenarioHost(tmp)
        sandbox.machine { it.reallyRuns('ssh-keygen').clockAt(NOW).timeZone('Europe/Berlin')
                            .windowsStayOpenFor(Duration.ofSeconds(80)).agent { String prompt ->
            if (prompt.contains('Tidy the downloads')) {
                release.await()
                return 'I tidied the downloads folder.'
            }
            'You said: ' + prompt
        } }
        // Genies opens as it closed last, with Jafar on its shelf, never woken, and a key in its environment.
        var genie = Genie.named('Jafar')
        jafar = genie.id()
        app = Genies.open(sandbox.home.resolve('genies'), Tuple.of(Genie, genie), Settings.defaults(), sandbox.launcher,
                          { name -> name == Settings.KEY_VARIABLE ? Optional.of('sk-key') : Optional.empty() })
    }

    def cleanup() {
        release.countDown()
        var done = new CountDownLatch(1)
        SwingUtilities.invokeAndWait { app?.quit(false, Optional.empty(), { done.countDown() }) }
        done.await(3, TimeUnit.MINUTES)
    }

    def 'A sleeping genie\'s schedule is read and changed through its lamp'() {
        reportInfo """
            Nothing about a schedule needs the genie's sandbox: the jobs are kept in its lamp. So
            the page reads them, and adds, switches off, pauses and removes them, with the genie
            asleep. A job written in the editor becomes the cron expression oillamp reads; once
            it is added, the editor closes and the new job is picked, so its times stand out.
        """
        given: 'a genie that has woken once, and sleeps'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        when:
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.SCHEDULE) } }

        then:
            waitUntil { app.state().get().find(jafar).orElseThrow().schedule().read() }
            app.state().get().find(jafar).orElseThrow().schedule().jobs().isEmpty()
            app.state().get().find(jafar).orElseThrow().schedule().zone() == BERLIN

        when: 'the user writes a job for every weekday at nine'
            var draft = JobDraft.fresh(local()).withPrompt('  Check the build  ').withRepeats(true)
                    .withRepeat(JobDraft.Repeat.WEEKDAYS).withTime('9:00')
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withSchedule(genie.schedule().withDraft(Optional.of(draft))) } }
                app.saveJob(jafar)
            }

        then: 'it is on the schedule, as it was meant, with its times over the week'
            waitUntil { app.state().get().find(jafar).orElseThrow().schedule().jobs().size() == 1 && app.state().get().find(jafar).orElseThrow().schedule().draft().isEmpty() }
            with(app.state().get().find(jafar).orElseThrow().schedule().jobs().first()) {
                id() == 'job-1'
                repeats().get().describe(Lang.ENGLISH) == 'Every weekday at 09:00'
                prompt() == 'Check the build'
                upcoming().size() == 5
            }
            app.state().get().find(jafar).orElseThrow().schedule().picked() == 'job-1'

        when:
            SwingUtilities.invokeAndWait { app.switchJob(jafar, 'job-1', false) }

        then:
            waitUntil { !app.state().get().find(jafar).orElseThrow().schedule().jobs().first().enabled() }

        when:
            SwingUtilities.invokeAndWait { app.pauseSchedule(jafar, true) }

        then:
            waitUntil { app.state().get().find(jafar).orElseThrow().schedule().paused() }

        when:
            SwingUtilities.invokeAndWait { app.removeJob(jafar, 'job-1') }

        then:
            waitUntil { app.state().get().find(jafar).orElseThrow().schedule().jobs().isEmpty() }
    }

    def 'Changing a job replaces it, and one that cannot be added leaves the old one and says why'() {
        reportInfo """
            oillamp has no way to edit a job in place, so a changed job is added anew and the old
            one removed, in that order: if oillamp refuses the new one, the old one stays, and the
            editor stays open with oillamp's reason.
        """
        given:
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.SCHEDULE) } }
            var daily = JobDraft.fresh(local()).withPrompt('Check the build').withRepeats(true).withTime('09:00')
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withSchedule(genie.schedule().withDraft(Optional.of(daily))) } }
                app.saveJob(jafar)
            }
            waitUntil { app.state().get().find(jafar).orElseThrow().schedule().jobs().size() == 1 }

        when: 'the user changes it to a cron expression oillamp cannot read'
            var wrong = daily.withReplaces(Optional.of('job-1')).withRepeat(JobDraft.Repeat.CUSTOM).withCron('0 25 * * *')
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withSchedule(genie.schedule().withDraft(Optional.of(wrong))) } }
                app.saveJob(jafar)
            }

        then:
            waitUntil { !app.state().get().find(jafar).orElseThrow().schedule().busy() && !app.state().get().find(jafar).orElseThrow().schedule().problem().isEmpty() }
            app.state().get().find(jafar).orElseThrow().schedule().problem().contains('the hour 25 is not between 0 and 23')
            app.state().get().find(jafar).orElseThrow().schedule().draft().isPresent()
            app.state().get().find(jafar).orElseThrow().schedule().jobs()*.id() == ['job-1']

        when: 'they put it right'
            var right = wrong.withCron('0 7 * * *')
            SwingUtilities.invokeAndWait {
                app.state().update { it.update(jafar) { genie -> genie.withSchedule(genie.schedule().withDraft(Optional.of(right))) } }
                app.saveJob(jafar)
            }

        then:
            waitUntil { app.state().get().find(jafar).orElseThrow().schedule().jobs()*.id() == ['job-2'] && app.state().get().find(jafar).orElseThrow().schedule().draft().isEmpty() }
            app.state().get().find(jafar).orElseThrow().schedule().jobs().first().repeats().get().describe(Lang.ENGLISH) == 'Every day at 07:00'
    }

    def 'A job that wakes the genie shows as running, then as a run with what the genie said'() {
        reportInfo """
            While a job's run goes on, the timeline shows it at now, with a way to stop it. Once
            it ends, it is among the runs, with how it ended, what the genie said last, and the
            conversation it had, which the chat can open, and which is in the tree of scheduled
            runs rather than among the user's conversations. The chat itself was left as it was
            while the job ran.
        """
        given: 'a sleeping genie with a job for five minutes from now, and its schedule on show'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            var tidying = Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).once('in 5m', 'Tidy the downloads folder')
            SwingUtilities.invokeAndWait { app.state().update { it.select(jafar).withPage(GeniesState.Page.SCHEDULE) } }

        when: 'it wakes ten minutes later, when the job is due'
            sandbox.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }

        then: 'the page shows it running'
            waitUntil { app.state().get().find(jafar).orElseThrow().schedule().running().filter { it.job() == tidying.id() }.isPresent() }

        when:
            release.countDown()

        then: 'it ended, and is among the runs'
            waitUntil { app.state().get().find(jafar).orElseThrow().schedule().running().isEmpty() && app.state().get().find(jafar).orElseThrow().schedule().runs().size() == 1 }
            with(app.state().get().find(jafar).orElseThrow().schedule().runs().first()) {
                job() == 'job-1'
                outcome() == Schedule.Outcome.FINISHED
                said() == 'I tidied the downloads folder.'
                conversation().isPresent()
            }

        and: 'the chat was left alone'
            app.state().get().find(jafar).orElseThrow().transcript().isEmpty()

        and: 'its conversation is among the scheduled runs, not the user\'s conversations'
            waitUntil { app.state().get().find(jafar).orElseThrow().conversations().jobCount() == 1 }
            app.state().get().find(jafar).orElseThrow().conversations().jobRuns(Lang.ENGLISH)*.id() == [app.state().get().find(jafar).orElseThrow().schedule().runs().first().conversation().get()]
            app.state().get().find(jafar).orElseThrow().conversations().chatCount() == 0

        when: 'the user opens the run\'s conversation'
            SwingUtilities.invokeAndWait { app.openConversation(jafar, app.state().get().find(jafar).orElseThrow().schedule().runs().first().conversation().get()) }

        then:
            waitUntil { app.state().get().find(jafar).orElseThrow().transcript().entries().any { it.kind() == Entry.Kind.GENIE && it.text() == 'I tidied the downloads folder.' } }
    }

    def 'A genie woken after a job ran opens the user\'s chat, not the job\'s run'() {
        reportInfo """
            Waking a genie shows the conversation the user was last in, or else the most recent
            one. A job's run is not one of the user's conversations: it is listed with the
            scheduled runs. So a genie that has had no chat yet, but ran a job, wakes to an empty
            chat. Opening the job's run instead would put the user's next message into the
            job's conversation, among its prompt and its notes.
        """
        given: 'a genie that ran a job while it was awake, and was put to sleep'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }
            Lamp.at(app.lampOf(jafar)).launchedBy(sandbox.launcher).once('in 5m', 'Water the plants')
            sandbox.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }
            waitUntil { Lamp.conversations(app.lampOf(jafar)).any { it.job().isPresent() && it.entries().size() > 2 } }
            SwingUtilities.invokeAndWait { app.sleep(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.ASLEEP }

        when: 'it wakes again'
            SwingUtilities.invokeAndWait { app.wake(jafar) }
            waitUntil { app.state().get().find(jafar).orElseThrow().phase() == Genie.Phase.READY }

        then: 'the chat is empty, ready for a conversation of the user\'s own'
            app.state().get().find(jafar).orElseThrow().transcript().isEmpty()
            app.state().get().find(jafar).orElseThrow().conversations().current().isEmpty()
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private LocalDateTime local() {
        LocalDateTime.ofInstant(NOW, BERLIN)
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        var shown = app.state().get().find(jafar).orElseThrow()
        throw new AssertionError("never happened; the genie is ${shown.phase()} (${shown.activity()}), " +
                                 "its schedule ${shown.schedule()}" as Object)
    }
}
