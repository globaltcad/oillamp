package oillamp

import dev.lamp.LampJobAuthor
import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampRunOutcome
import dev.lamp.LampSaveKind
import dev.lamp.LampRun
import dev.oillamp.OilLamp
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 *  {@code schedule} and {@code ask}: waking the agent while a session runs.
 *
 *  <p>A session holds one agent: pi, in the sandbox, which oillamp starts and talks to. Jobs on the
 *  lamp's schedule wake it at set times, and {@code oillamp ask} wakes it at once. Every run begins
 *  a new conversation, so what the agent should remember is put into its prompt: its own notes,
 *  what recent runs changed, and what the last one said. The lamp is saved just before and just
 *  after each run, so the history shows what each run did, and any run can be undone.
 *
 *  <p>The agent here is a stand-in that answers the way pi does, given what each scenario wants it
 *  to do. Everything else is real: the session, the sockets, the schedule file and the history.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class WakingTheAgentSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

    /** A Tuesday afternoon: 16:15 in Berlin. */
    static final Instant NOW = Instant.parse('2026-09-22T14:15:03Z')

    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    final List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread session
    /** What the session printed and reported, once it has ended. */
    volatile OilLamp.Outcome sessionOutcome

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen').clockAt(NOW).timeZone('Europe/Berlin') }
    }

    def cleanup() {
        if (session?.alive) {
            host.oillamp.run('stop', host.lampPath().toString())
            session.join(30_000)
        }
    }

    // ─── the schedule, from the command line ───────────────────────────────────────────────

    def 'A job the user adds is listed with when it runs next, on this machine\'s clock'() {
        reportInfo """
            "0 9 * * 1-5" means nine in the morning on weekdays, and the person who wrote it meant
            nine on their own clock. So a schedule is read in the host's time zone, and listed in
            it. The schedule is kept beside the history, where the agent cannot edit it.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when:
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '0 9 * * 1-5', 'Check the nightly build and fix what broke')
            var listed = host.oillamp.run('schedule', lamp.toString())

        then: 'it runs next on Wednesday at nine, Berlin time'
            added.succeeded()
            var job = added.events().find { it instanceof LampEvent.JobAdded }.job()
            job.id() == 'job-1'
            job.author() == LampJobAuthor.USER
            job.next() == Optional.of(Instant.parse('2026-09-23T07:00:00Z'))
            job.expires().isEmpty()

        and: 'the listing shows it on the local clock'
            listed.console().contains('job-1')
            listed.console().contains('2026-09-23 09:00')
            listed.console().contains('Europe/Berlin')

        and: 'the schedule lives in the lamp\'s state, out of the agent\'s home'
            Files.exists(lamp.resolve('.oillamp/schedule.json'))
            !Files.exists(home(lamp).resolve('schedule.json'))
    }

    def 'The schedule lists when each job runs over the coming week, so it can be drawn as a timeline'() {
        reportInfo """
            An application that shows the schedule on a timeline needs every time a job runs, not
            just the next one, and cannot work them out itself without reading cron. So each job
            comes with its times over the next seven days, up to when it expires. A job switched
            off has none.
        """
        given: 'Tuesday, 16:15 in Berlin'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when:
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '0 9 * * 1-5', 'Weekday check')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '0 9 * * *', '--expires', 'in 3d', 'Daily, for three days')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 2h', 'Once')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Switched off')
            host.oillamp.run('schedule', lamp.toString(), 'disable', 'job-4')
            var listing = host.oillamp.run('schedule', lamp.toString())
            var listed = listing.events().find { it instanceof LampEvent.Schedule }.jobs()

        then: 'nine on each weekday of the week ahead, which ends next Tuesday at 16:15'
            listed[0].upcoming().collect() == ['2026-09-23', '2026-09-24', '2026-09-25', '2026-09-28', '2026-09-29']
                    .collect { Instant.parse(it + 'T07:00:00Z') }

        and: 'the daily job only until it expires on Friday afternoon'
            listed[1].upcoming().size() == 3
            listed[1].upcoming().last() == Instant.parse('2026-09-25T07:00:00Z')

        and: 'the job that runs once, once; the one switched off, never'
            listed[2].upcoming().collect() == [NOW.plus(Duration.ofHours(2))]
            listed[3].upcoming().isEmpty()
    }

    def 'A time oillamp cannot read, or one that has passed, is refused, and says why'() {
        reportInfo """
            A schedule that silently never runs is worse than an error: the person finds out days
            later. So anything oillamp cannot turn into a time is refused at once, with what was
            wrong, and the schedule stays as it was.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when:
            var badHour = host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '0 25 * * *', 'x')
            var passed = host.oillamp.run('schedule', lamp.toString(), 'add', '--at', '2026-01-01 09:00', 'x')
            var both = host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', '--at', 'in 2h', 'x')

        then:
            [badHour, passed, both].every { !it.succeeded() && it.reported('OIL-SCHEDULE-001') }
            badHour.problems().first().whatHappened().contains('the hour 25 is not between 0 and 23')
            passed.problems().first().whatHappened().contains('has already passed')

        and: 'nothing was added'
            var listing = host.oillamp.run('schedule', lamp.toString())
            var jobs = listing.events().find { it instanceof LampEvent.Schedule }.jobs()
            jobs.isEmpty()
    }

    def 'A job can be switched off and on, and removed, and the whole schedule paused'() {
        reportInfo """
            A person who wants the agent to leave a job alone for a week switches it off rather
            than deleting it and writing it again later. A person going on holiday pauses the whole
            schedule. Both only change what runs; the jobs stay as they were written.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 2h', 'Send the summary')
            var jobsOnTheSchedule = {
                var listing = host.oillamp.run('schedule', lamp.toString())
                assert listing.succeeded()
                listing.events().find { it instanceof LampEvent.Schedule }.jobs()
            }

        when:
            var off = host.oillamp.run('schedule', lamp.toString(), 'disable', 'job-1')
            var switchedOff = jobsOnTheSchedule()
            host.oillamp.run('schedule', lamp.toString(), 'enable', 'job-1')
            var paused = host.oillamp.run('schedule', lamp.toString(), 'pause')
            var pausedListing = host.oillamp.run('schedule', lamp.toString()).console()
            host.oillamp.run('schedule', lamp.toString(), 'resume')
            var removed = host.oillamp.run('schedule', lamp.toString(), 'remove', 'job-2')
            var missing = host.oillamp.run('schedule', lamp.toString(), 'remove', 'job-9')

        then:
            off.succeeded()
            !switchedOff.find { it.id() == 'job-1' }.enabled()
            switchedOff.find { it.id() == 'job-1' }.next().isEmpty()
            paused.succeeded()
            pausedListing.contains('paused')
            removed.events().any { it instanceof LampEvent.JobRemoved && it.job().id() == 'job-2' }
            missing.reported('OIL-SCHEDULE-003')

        and: 'only the first job is left, switched on again'
            var jobs = jobsOnTheSchedule()
            jobs*.id() == ['job-1']
            jobs.first().enabled()
    }

    def 'An hourly job switched off on Tuesday and back on on Friday runs at the next full hour, not immediately'() {
        reportInfo """
            oillamp computes the next time of a repeating job from the time it last ran, or, if it never
            ran, from the time it was added. A job that is switched off never runs, but that starting point
            stays where it was while the job is off.

            Let's consider an `@hourly` job that the user added on Tuesday at 16:15 and switched off at
            once. On Friday at 16:15 they switch it on again. Counted from Tuesday, its next time would be
            Tuesday at 17:00, which has passed, so the next session would run the job as soon as it
            started. The user did not ask for that run.

            So when a repeating job is switched on, oillamp sets its last run to that moment. In the
            scenario, `oillamp schedule` lists the job after `enable`, and its next time must be Friday at
            17:00 Berlin time, the first full hour after it was switched on.
        """
        given: 'an hourly job, added on Tuesday at 16:15 and switched off at once'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')
            host.oillamp.run('schedule', lamp.toString(), 'disable', 'job-1')

        when: 'it is switched on again three days later, on Friday at 16:15'
            host.machine { it.clockAt(NOW.plus(Duration.ofDays(3))) }
            host.oillamp.run('schedule', lamp.toString(), 'enable', 'job-1')
            var listing = host.oillamp.run('schedule', lamp.toString())
            var job = listing.events().find { it instanceof LampEvent.Schedule }.jobs().find { it.id() == 'job-1' }

        then: 'it runs next at the top of the coming hour, 17:00 in Berlin'
            job.enabled()
            job.next() == Optional.of(Instant.parse('2026-09-25T15:00:00Z'))
    }

    def 'Oillamp reads a time written as a span from now: #written'() {
        reportInfo """
            `--at` and `--expires` accept a time written as a span from now, such as "in 2h", as well as a
            date such as "2026-10-01 09:00". A span is a number and a unit: m, min or minutes; h or hours;
            d or days; w or weeks. A week is 7 days. "+2h" means the same as "in 2h".

            The scenario adds one job for each unit at a fixed moment, and checks that the job's next time
            is that moment plus the span: 90 minutes, 2 hours, 3 days and 7 days.
        """
        given: 'Tuesday, 16:15 in Berlin'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when:
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--at', written, 'Look again')

        then:
            added.succeeded()
            added.events().find { it instanceof LampEvent.JobAdded }.job().next() == Optional.of(NOW.plus(span))

        where:
            written       || span
            'in 90m'      || Duration.ofMinutes(90)
            'in 2h'       || Duration.ofHours(2)
            'in 3 days'   || Duration.ofDays(3)
            'in 1w'       || Duration.ofDays(7)
    }

    def 'Oillamp reads a so-called "cron expression" the way the Linux scheduler cron reads it: #meaning'() {
        reportInfo """
            Unix systems have run commands at set times with a program called cron since the 1970s. Each line of its configuration file, the crontab, starts with five fields that say
            when to run: the minute, the hour, the day of the month, the month and the day of the week.
            `0 9 * * 1-5`, for example, means 9:00 on Monday to Friday. Those five fields are called a
            "cron expression", and many other tools use the same notation, among them GitHub Actions and
            Kubernetes.

            oillamp uses it for a repeating job's time, because people already know it and copy it from
            crontabs and documentation. The basic format is standardised in POSIX. Most Linux systems run
            Vixie cron, which adds a few rules to it, and expressions found in the wild rely on them. So
            oillamp reads an expression the way Vixie cron does. Two of those rules are easy to get wrong:

            - In the day-of-week field, Sunday may be written as 0, as 7, or as `sun`. Some crons count
              Sunday as 0, others as 7, and both spellings are in use. `0 9 * * 7` must run on Sundays at
              9:00. If oillamp knew only 0, the expression would match no day at all, and
              `oillamp schedule add` would refuse it as naming no time.
            - A step after a number, as in `5/20` in the minute field, starts at that number and goes on to
              the end of the field: minutes 5, 25 and 45. `*/20` starts at 0: minutes 0, 20 and 40. Read as
              "minute 5 only", `5/20` would run once an hour instead of three times.

            The scenario adds each expression with `oillamp schedule add --cron` on Tuesday 22 September
            2026 at 16:15 in Berlin, and compares the first runs the job lists with the runs cron makes.
            Berlin is two hours ahead of UTC in September, so 9:00 there is 07:00Z.
        """
        given: 'Tuesday, 16:15 in Berlin'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when:
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', cron, 'Look again')

        then: 'the first runs are the ones cron would make'
            added.succeeded()
            var upcoming = added.events().find { it instanceof LampEvent.JobAdded }.job().upcoming()
            upcoming.collect().take(expected.size()) == expected.collect { Instant.parse(it) }

        where:
            meaning                                  | cron            || expected
            'Sunday written as 7'                    | '0 9 * * 7'     || ['2026-09-27T07:00:00Z']
            'Sunday written as 0'                    | '0 9 * * 0'     || ['2026-09-27T07:00:00Z']
            'Sunday written by name'                 | '0 9 * * sun'   || ['2026-09-27T07:00:00Z']
            'a step that starts at minute 5'         | '5/20 9 * * *'  || ['2026-09-23T07:05:00Z', '2026-09-23T07:25:00Z', '2026-09-23T07:45:00Z']
            'a step over the whole hour'             | '*/20 9 * * *'  || ['2026-09-23T07:00:00Z', '2026-09-23T07:20:00Z', '2026-09-23T07:40:00Z']
    }

    def 'A job can be written with --cron=, --at= and --expires=, and a prompt that starts with a dash goes after --'() {
        reportInfo """
            `--cron=@daily` means the same as `--cron @daily`, so a job comes out the same
            whichever way it was typed. A prompt is often a list of things to do, and a list
            starts with a dash, which oillamp would take for an option it does not know. After
            `--`, every argument is taken as it is written.
        """
        given: 'Tuesday, 16:15 in Berlin'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when:
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron=0 9 * * 1-5', '--expires=in 3d', 'Weekday check')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at=in 2h', 'Once')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', '--', '- read the inbox\n- answer what is urgent')
            var listing = host.oillamp.run('schedule', lamp.toString())
            var listed = listing.events().find { it instanceof LampEvent.Schedule }.jobs()

        then: 'the first runs at nine on weekdays, and only until Friday afternoon'
            listed[0].when().contains('0 9 * * 1-5')
            listed[0].next() == Optional.of(Instant.parse('2026-09-23T07:00:00Z'))
            listed[0].expires() == Optional.of(NOW.plus(Duration.ofDays(3)))

        and: 'the second runs once, two hours from now'
            listed[1].next() == Optional.of(NOW.plus(Duration.ofHours(2)))

        and: 'the third has its prompt exactly as written'
            listed[2].prompt() == '- read the inbox\n- answer what is urgent'
    }

    def 'With the schedule switched off, a job is kept, but the user is told it will not run'() {
        reportInfo """
            Waking the agent costs model tokens, so a lamp's schedule is off until its owner turns
            it on. Adding a job to a lamp whose schedule is off is allowed, since the person may be
            setting things up first, but they are told plainly that nothing will run yet.
        """
        given:
            var lamp = aLampThatHasRun('')

        when:
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')

        then:
            added.succeeded()
            added.reported('OIL-SCHEDULE-004')
            var listing = host.oillamp.run('schedule', lamp.toString())
            var jobs = listing.events().find { it instanceof LampEvent.Schedule }.jobs()
            jobs.size() == 1
    }

    def 'A session started with --enable-scheduling runs the jobs, and leaves oillamp.toml as it is'() {
        reportInfo """
            Turning the schedule on for one session should not mean editing a file. With
            --enable-scheduling, the session runs the jobs as if `enabled = true` were set, and
            `oillamp schedule` says so while it runs. The lamp's own setting is untouched, so the
            next session without the flag runs no jobs again.
        """
        given: 'a lamp whose schedule is off, with a job for five minutes from now'
            var lamp = aLampThatHasRun('')
            var toml = Files.readString(lamp.resolve('oillamp.toml'))
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 5m', 'Tidy up')
            anAgent { prompt -> 'Tidied up.' }

        when: 'a session starts ten minutes later with the flag'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp, '--enable-scheduling')
            var finished = waitFor(LampEvent.RunFinished)
            var during = host.oillamp.run('schedule', lamp.toString())
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)
            assert !session.alive
            var after = host.oillamp.run('schedule', lamp.toString())

        then: 'the job ran'
            finished.run().job() == Optional.of('job-1')
            finished.outcome() == LampRunOutcome.FINISHED

        and: 'the schedule was on while the session ran, and is off again after it'
            during.events().find { it instanceof LampEvent.Schedule }.enabled()
            !after.events().find { it instanceof LampEvent.Schedule }.enabled()

        and: 'the lamp\'s configuration was not changed'
            Files.readString(lamp.resolve('oillamp.toml')) == toml
    }

    // ─── runs ──────────────────────────────────────────────────────────────────────────────

    def 'A job whose time has come wakes the agent, and the run is saved as a snapshot of its own'() {
        reportInfo """
            The whole point: the agent is woken with the job's prompt, works, and says what it did.
            The lamp is saved as the run ends, whatever happened, with the run's name and the
            agent's last words in the snapshot's message, so the history reads as a log of what
            the agent did and when. A job meant to run once is then off the schedule.

            A job that is due as the session starts runs at once, but only after the session has
            said it is open, so an application that waits for that hears of the run after it.
        """
        given: 'a job for five minutes from now'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 5m', 'Write the weekly report into ~/workspace/report.md')

        and: 'an agent that writes the report'
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/report.md'), '# Week 39\n')
                'I wrote the weekly report to ~/workspace/report.md.'
            }

        when: 'a session runs ten minutes later'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)
            var finished = waitFor(LampEvent.RunFinished)
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)
            assert !session.alive

        then: 'the session said it was open before anything of the run'
            reported.any { it instanceof LampEvent.SessionOpened }
            reported.findIndexOf { it instanceof LampEvent.SessionOpened } <
                    reported.findIndexOf { it instanceof LampEvent.RunQueued || it instanceof LampEvent.RunStarted }

        and: 'the agent was woken with the job\'s prompt, as the job\'s first run'
            reported.find { it instanceof LampEvent.RunStarted }.run() == new LampRun('run-1', Optional.of('job-1'),
                    'Write the weekly report into ~/workspace/report.md', Optional.empty())
            prompts.size() == 1
            prompts[0].contains('You were woken by job-1, a job the user added')
            prompts[0].contains('## The task\n\nWrite the weekly report')
            prompts[0].contains('You have no notes yet')
            prompts[0].contains('This is the first run.')

        and: 'it finished, and said what it did'
            finished.outcome() == LampRunOutcome.FINISHED
            finished.answer() == 'I wrote the weekly report to ~/workspace/report.md.'

        and: 'the run is a snapshot, named after it and holding the agent\'s words'
            var snapshot = finished.snapshot().orElseThrow()
            snapshot.kind() == LampSaveKind.RUN
            snapshot.run() == Optional.of('run-1')
            snapshot.message().contains('I wrote the weekly report')
            var history = host.oillamp.run('history', lamp.toString())
            var newest = history.events().find { it instanceof LampEvent.History }.snapshots().first()
            newest.id() == snapshot.id()

        and: 'the history says which job it was, how it ended, and where its conversation is'
            with (newest) {
                job() == Optional.of('job-1')
                outcome() == Optional.of(LampRunOutcome.FINISHED)
                conversation().isPresent()
                conversation() == finished.conversation()
            }

        and: 'the job ran once, as it was meant to, and is gone'
            reported.any { it instanceof LampEvent.JobRemoved && it.job().id() == 'job-1' }
            var listing = host.oillamp.run('schedule', lamp.toString())
            var jobs = listing.events().find { it instanceof LampEvent.Schedule }.jobs()
            jobs.isEmpty()
    }

    def 'The next run is told what the last one did: its notes, the files it changed and its last words'() {
        reportInfo """
            A job wakes the agent in a new conversation, so on its own it would remember nothing.
            Its prompt therefore carries what it needs: the notes it left itself in
            ~/workspace/NOTES.md, which files the recent runs changed, and what the last run said
            at its end. The notes are marked as the agent's own, not as the user's instructions.
        """
        given: 'two jobs, one after the other'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 1m', 'Do the first task')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 2m', 'Do the second task')
            anAgent { prompt ->
                if (prompt.contains('Do the first task')) {
                    Files.writeString(home(lamp).resolve('workspace/NOTES.md'), 'Next: add the chart.\n')
                    Files.writeString(home(lamp).resolve('workspace/data.csv'), 'a,b\n')
                    return 'Collected the data.'
                }
                'Added the chart.'
            }

        when: 'a session runs once both are due'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)
            waitFor(LampEvent.RunFinished) { it.run().id() == 'run-2' }
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)
            assert !session.alive

        then: 'the second run was given the notes, the changes and the last words of the first'
            prompts.size() == 2
            prompts[1].contains('You were woken by job-2')
            prompts[1].contains('<notes>\nNext: add the chart.\n</notes>')
            prompts[1].contains('run-1')
            prompts[1].contains('~/workspace/data.csv (added)')
            prompts[1].contains('~/workspace/NOTES.md (added)')
            prompts[1].contains('<last-message>\nCollected the data.\n</last-message>')
    }

    def 'With the saves around runs switched off, a run makes no snapshot, and the next is told nothing of it'() {
        reportInfo """
            The saves just before and just after every run are on unless `autosave.before_run`
            and `autosave.after_run` are off. Off, the history holds no runs, so the next job's
            prompt cannot say what the last run changed or said. The agent still has its notes.
        """
        given: 'two jobs, one after the other, on a lamp that does not save around runs'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n[autosave]\nbefore_run = false\nafter_run = false\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 1m', 'Do the first task')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 2m', 'Do the second task')
            anAgent { prompt ->
                if (prompt.contains('Do the first task')) {
                    Files.writeString(home(lamp).resolve('workspace/NOTES.md'), 'Next: add the chart.\n')
                    return 'Collected the data.'
                }
                'Added the chart.'
            }

        when: 'a session runs once both are due'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)
            var finished = waitFor(LampEvent.RunFinished) { it.run().id() == 'run-2' }
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)
            assert !session.alive

        then: 'neither run was saved'
            finished.snapshot().isEmpty()
            !reported.any { it instanceof LampEvent.Saved }
            host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots().isEmpty()

        and: 'the second run was given the notes, but nothing of the first run'
            prompts.size() == 2
            prompts[1].contains('<notes>\nNext: add the chart.\n</notes>')
            !prompts[1].contains('Collected the data.')
            prompts[1].contains('The user has switched off the saves after runs')
    }

    def 'What a person asks goes to the agent exactly as they wrote it'() {
        reportInfo """
            A question someone asks is what a chat shows as their message, so it is never wrapped
            in notes or recent runs. The agent's guide already tells it to read its notes.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            anAgent { prompt -> 'Fine.' }
            startASession(lamp)

        when:
            host.oillamp.run('ask', lamp.toString(), 'How are you?')

        then:
            prompts == ['How are you?']
    }

    def 'Asking a busy agent waits its turn'() {
        reportInfo """
            The agent works on one thing at a time. A second question, or a job that comes due,
            while it is busy is not refused and not run alongside: it waits, and the session says
            so, and it runs as soon as the agent is free.
        """
        given:
            var lamp = aLampThatHasRun('')
            var release = new CountDownLatch(1)
            anAgent { prompt ->
                if (prompt == 'slow') release.await()
                'answered: ' + prompt
            }
            startASession(lamp)

        when:
            var slow = Thread.start { assert host.oillamp.run('ask', lamp.toString(), 'slow').succeeded() }
            waitFor(LampEvent.RunStarted)
            var quick = null
            var second = Thread.start { quick = host.oillamp.run('ask', lamp.toString(), 'quick') }
            var queued = waitFor(LampEvent.RunQueued)
            release.countDown()
            slow.join(20_000)
            second.join(20_000)

        then:
            queued.run().prompt() == 'quick'
            quick.succeeded()
            quick.console().contains('answered: quick')
            prompts == ['slow', 'quick']
    }

    def 'A run that takes too long is stopped, and what it did so far is saved'() {
        reportInfo """
            An agent can loop, or wait for something that never comes, and every minute it does
            costs money. A run is therefore stopped after schedule.max_run_minutes. What it had
            done by then is saved like any run, marked as stopped, so it can be looked at or undone.
        """
        given: 'runs of at most one minute, on a clock that runs ten minutes a second'
            var lamp = aLampThatHasRun('''
                [schedule]
                max_run_minutes = 1

                [timeouts]
                container_ready_seconds  = 3600
                terminal_connect_seconds = 3600
            '''.stripIndent())
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/half-done.txt'), 'started\n')
                Thread.sleep(60_000)
                'never reached'
            }
            host.machine { it.clockRunsFaster(600) }
            startASession(lamp)

        when:
            var asked = host.oillamp.run('ask', lamp.toString(), 'Work forever')

        then:
            !asked.succeeded()
            var finished = asked.events().find { it instanceof LampEvent.RunFinished }
            finished.outcome() == LampRunOutcome.TIMED_OUT
            asked.console().contains('ran out of time')

        and: 'the half-done work is in the run\'s snapshot'
            var history = host.oillamp.run('history', lamp.toString())
            var snapshot = history.events().find { it instanceof LampEvent.History }.snapshots().first()
            snapshot.kind() == LampSaveKind.RUN
            snapshot.message().contains('timed out')
    }

    def 'Ending the session in the middle of a run stops it, and saves it first'() {
        reportInfo """
            Ctrl-C, closing the terminal or oillamp stop must not lose a run's work, and must not
            leave the agent working in a sandbox that is about to disappear. The session tells the
            agent to stop, saves the run, and only then stops the sandbox.
        """
        given:
            var lamp = aLampThatHasRun('')
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/partial.txt'), 'some of it\n')
                Thread.sleep(60_000)
                'never reached'
            }
            startASession(lamp)

        when:
            Thread.start { host.oillamp.run('ask', lamp.toString(), 'A long task') }
            waitFor(LampEvent.RunStarted)
            Thread.sleep(300)
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)
            assert !session.alive

        then:
            var finished = reported.find { it instanceof LampEvent.RunFinished }
            finished.outcome() == LampRunOutcome.INTERRUPTED
            var history = host.oillamp.run('history', lamp.toString())
            var snapshots = history.events().find { it instanceof LampEvent.History }.snapshots()
            var run = snapshots.find { it.kind() == LampSaveKind.RUN }
            run.message().contains('interrupted')
            !reported.any { it instanceof LampEvent.Failure }
    }

    def 'Without pi in the sandbox, a run fails and says why, and the session carries on'() {
        reportInfo """
            A lamp whose image has no pi, or a pi that crashes at start, cannot do a run. That is
            reported where the user looks, with what pi said, and the session stays up: the
            sandbox itself is fine.
        """
        given: 'a sandbox without an agent'
            var lamp = aLampThatHasRun('')
            startASession(lamp)

        when:
            var asked = host.oillamp.run('ask', lamp.toString(), 'Hello?')

        then:
            !asked.succeeded()
            asked.events().find { it instanceof LampEvent.RunFinished }.outcome() == LampRunOutcome.FAILED
            reported.any { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-SCHEDULE-005'
                           && it.problem().whatHappened().contains('pi: command not found') }
            session.alive
    }

    def 'What the agent says cannot take over the user\'s terminal'() {
        reportInfo """
            The agent's answer is printed in the terminal the user started oillamp from, on the
            host. A terminal obeys escape sequences in what it prints: they can clear the screen,
            rewrite lines, or change the window's title. So everything the agent wrote is printed
            with such sequences made harmless, as the output of any program in the sandbox is.
        """
        given:
            var lamp = aLampThatHasRun('')
            anAgent { prompt -> 'Done.\u001B[2J\u001B]0;owned\u0007 All fine.' }
            startASession(lamp)

        when:
            var asked = host.oillamp.run('ask', lamp.toString(), 'Anything')

        then:
            asked.succeeded()
            asked.console().contains('Done.')
            !asked.console().contains('\u001B[2J')
            !asked.console().contains('\u001B]0;')
    }

    def 'A long answer in any language comes back to `oillamp ask` exactly as the agent wrote it'() {
        reportInfo """
            The answer travels from the session to `oillamp ask` over a socket, which hands it over
            in pieces of 64 kB. A letter such as € takes three bytes, and a piece can end in the
            middle of one. The pieces are joined before they are read as text, so no letter is
            broken at such a seam.
        """
        given: 'answers of 90 kB of euro signs, after one, two and three spaces, so that one of them has a seam inside a €'
            var lamp = aLampThatHasRun('')
            var answers = (1..3).collect { spaces -> 'Kosten:' + ' ' * spaces + '€' * 30_000 }
            anAgent { prompt -> answers[prompt.toInteger()] }
            startASession(lamp)

        when:
            var heard = (0..2).collect { which ->
                host.oillamp.run('ask', lamp.toString(), which.toString())
                        .events().find { it instanceof LampEvent.RunFinished }.answer()
            }

        then:
            heard == answers
    }

    def 'A schedule file that is not valid JSON is reported once, and again once it is fixed'() {
        reportInfo """
            The lamp's jobs are kept in .oillamp/schedule.json. oillamp writes it, but a person
            may edit it by hand, and a typo leaves text that is not valid JSON: here, `{ not json`.
            oillamp cannot tell which jobs that file holds, so no job runs until it is fixed, and
            the user is told.

            The session reads the file every half minute, and whenever someone changes the
            schedule. Repeating the same warning at every reading would bury everything else the
            session reports. So the warning comes once. When the file is valid JSON again, the
            session says it can read the schedule again, and the jobs run as before.
        """
        given: 'a session with the schedule on'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            startASession(lamp)
            var file = lamp.resolve('.oillamp/schedule.json')

        and: 'a way to make the session read the schedule now, as `oillamp schedule` does after a change'
            var control = host.runtime.resolve('oillamp/k3v7x2ab/run/control.sock')
            var readNow = {
                SocketChannel.open(UnixDomainSocketAddress.of(control)).withCloseable { channel ->
                    channel.write(ByteBuffer.wrap('{"op":"schedule-changed"}\n'.getBytes(StandardCharsets.UTF_8)))
                    channel.read(ByteBuffer.allocate(1024))
                }
            }
            var warnings = {
                reported.count { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-SCHEDULE-002' }
            }

        when: 'someone leaves text in the file that is not JSON, and the session reads it four times'
            Files.writeString(file, '{ not json')
            readNow()
            waitFor(LampEvent.Warning) { it.problem().code().value() == 'OIL-SCHEDULE-002' }
            3.times { readNow(); Thread.sleep(300) }

        then: 'the user was warned once'
            warnings() == 1

        when: 'the file holds valid JSON again: an empty schedule'
            Files.writeString(file, '{}')
            readNow()

        then: 'the session says it can read the schedule again, and warns no more'
            waitFor(LampEvent.Ok) { it.text() == 'the schedule can be read again' }
            warnings() == 1
    }

    // ─── the agent's side ──────────────────────────────────────────────────────────────────

    def 'The agent adds and removes its own jobs through the session, within the limits the user set'() {
        reportInfo """
            The agent's tools do not write the schedule. They ask the session over a socket, and
            the session applies the rules: the agent may have only so many jobs, which may not run
            too often, and which expire. It can read the user's jobs, but not remove them. A
            refusal says what the rule is, so the agent can adjust.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\nmax_agent_jobs = 1\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'The user\'s own job')
            startASession(lamp)

        when:
            var tooOften = agentAsks(op: 'add', cron: '*/5 * * * *', prompt: 'Check the build')
            var hourly = agentAsks(op: 'add', cron: '0 * * * *', prompt: 'Check the build')
            var oneTooMany = agentAsks(op: 'add', at: 'in 2h', prompt: 'Another one')
            var usersJob = agentAsks(op: 'remove', id: 'job-1')
            var listed = agentAsks(op: 'list')

        then:
            !tooOften.ok
            tooOften.error.contains('may run at most every 15 minutes')
            hourly.ok
            hourly.text.startsWith('Added job-2')
            !oneTooMany.ok
            oneTooMany.error.contains('may have 1 jobs')
            !usersJob.ok
            usersJob.error.contains('only the user can remove it')
            listed.text.contains('job-1 (the user\'s; you cannot change it)')
            listed.text.contains('job-2 (yours)')

        and: 'the agent\'s job ends after fourteen days, although it asked for no end'
            var listing = host.oillamp.run('schedule', lamp.toString())
            var jobs = listing.events().find { it instanceof LampEvent.Schedule }.jobs()
            var job = jobs.find { it.id() == 'job-2' }
            job.author() == LampJobAuthor.AGENT
            job.expires() == Optional.of(NOW.plus(Duration.ofDays(14)))

        and: 'the session reported the new job'
            reported.any { it instanceof LampEvent.JobAdded && it.job().id() == 'job-2' }
    }

    def 'Oillamp ends the agent\'s jobs within schedule.max_agent_days, whatever end or time the agent asks for'() {
        reportInfo """
            The agent can add jobs to the lamp's schedule through the session. Each of its jobs ends after
            `schedule.max_agent_days` at the latest: 14 days, unless the user set another number. Without
            that limit an agent could schedule work for itself months ahead, and every run would cost the
            user model tokens.

            The agent can ask for something past 14 days in two ways, and oillamp handles both:

            - It asks for a repeating job that ends in 30 days. oillamp adds the job, but sets its end to
              14 days from now.
            - It asks for a single run in 20 days. oillamp refuses, and the refusal says "at most 14 days
              ahead", so the agent learns the limit.

            The scenario sends both requests over the session's schedule socket, as the agent's tools do,
            and reads the end of the added job with `oillamp schedule`.
        """
        given: 'a session on a lamp whose schedule is on, with the default limit of fourteen days'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            startASession(lamp)

        when: 'the agent asks for a daily job that ends in thirty days'
            var daily = agentAsks(op: 'add', cron: '0 9 * * *', prompt: 'Check the build', expires: 'in 30d')

        then: 'the job is added, but it ends after fourteen days'
            daily.ok
            var listing = host.oillamp.run('schedule', lamp.toString())
            var job = listing.events().find { it instanceof LampEvent.Schedule }.jobs().find { it.id() == 'job-1' }
            job.expires() == Optional.of(NOW.plus(Duration.ofDays(14)))

        when: 'the agent asks for one run in twenty days'
            var later = agentAsks(op: 'add', at: 'in 20d', prompt: 'Look at the release')

        then: 'it is refused, and told how far ahead it may go'
            !later.ok
            later.error.contains('at most 14 days ahead')
    }

    def 'Oillamp runs a one-time job that came due during a short pause once the schedule is resumed'() {
        reportInfo """
            `oillamp schedule pause` stops every job from running, without changing or removing
            any. Prompts sent with `oillamp ask` are answered as usual, paused or not. Let's
            consider a user who pauses the schedule for a few minutes, during which a job added
            with `--at` comes due.

            A one-time job keeps its time through a pause: it has no later time it could wait for.
            When the user resumes the schedule, the job is due, and five minutes late, which
            `schedule.max_delay_minutes` (30 by default) allows, so it runs at once. A repeating
            job is different: resuming counts it as if it had just run, so it waits for its next
            time.

            A running session checks the schedule as it starts, every 30 seconds after that, and
            whenever someone changes the schedule. In the scenario the job's time is five minutes
            before the session starts, so the job is due when the session first checks. The
            schedule is paused, so the job must not run: the prompt the user sends next must be
            the first and only prompt the agent receives. Then the user runs `oillamp schedule
            resume`, the session checks again, and the job runs.
        """
        given: 'a job that was due five minutes before the session starts, on a paused schedule'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 5m', 'Write the weekly report')
            host.oillamp.run('schedule', lamp.toString(), 'pause')
            anAgent { prompt -> 'Done.' }
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)

        when: 'the person asks something while the schedule is paused'
            assert host.oillamp.run('ask', lamp.toString(), 'Are you there?').succeeded()

        then: 'only the question reached the agent'
            prompts == ['Are you there?']

        when: 'the schedule is resumed'
            host.oillamp.run('schedule', lamp.toString(), 'resume')
            var ran = waitFor(LampEvent.RunFinished) { it.run().job() == Optional.of('job-1') }

        then: 'the one-time job that waited runs now'
            ran.outcome() == LampRunOutcome.FINISHED
            prompts.size() == 2
            prompts[1].contains('Write the weekly report')
    }

    def 'With schedule.max_agent_runs_per_day = 0, oillamp skips the agent\'s due job and says why'() {
        reportInfo """
            `schedule.max_agent_runs_per_day` limits how many runs of the agent's own jobs may start in 24
            hours. It does not limit the user's jobs, nor questions asked with `oillamp ask`. When one of
            the agent's jobs comes due, oillamp counts the runs of the agent's jobs in the last 24 hours,
            including any that are running or waiting. If that count has reached the limit, the job does
            not run: oillamp marks it as run, and prints that it was skipped, naming the setting.

            "Reached" means equal or more. With a limit of 3, the third run starts and the fourth is
            skipped. The scenario uses a limit of 0, where this shows directly: with 0 runs counted and a
            limit of 0, the agent's job must be skipped.

            In a first session the agent adds a job for five minutes later. The simulated clock does not
            move during a session, so the scenario ends the session, moves the clock ten minutes ahead, and
            starts a second session, in which the job is due. It waits for the message that the job was
            skipped, and checks that the agent received no prompt.
        """
        given: 'a lamp whose agent may add jobs, but none of them may run'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\nmax_agent_runs_per_day = 0\n')
            anAgent { prompt -> 'Done.' }

        and: 'the agent added a job for five minutes later, during a session'
            startASession(lamp)
            assert agentAsks(op: 'add', at: 'in 5m', prompt: 'Check the build').ok
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)

        when: 'the next session starts ten minutes later, with the job due'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)
            var skipped = waitFor(LampEvent.Info) { it.text().contains('was skipped') }

        then: 'the user is told why it did not run, and which setting decides'
            skipped.text().contains('job-1 was due')
            skipped.text().contains('schedule.max_agent_runs_per_day')

        and: 'the agent was not woken'
            prompts.isEmpty()
            !reported.any { it instanceof LampEvent.RunStarted }
    }

    def 'The agent looks back at an earlier run, as the host\'s history holds it'() {
        reportInfo """
            The prompt describes only the last few runs. To look further back, the agent asks for a
            run by name, and gets what it changed and what it said at its end, from the history on
            the host, which the agent cannot see or change.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/answer.txt'), '42\n')
                'The answer is in answer.txt.'
            }
            startASession(lamp)
            host.oillamp.run('ask', lamp.toString(), 'Find the answer')

        when:
            var all = agentAsks(op: 'history')
            var one = agentAsks(op: 'history', run: 'run-1')
            var none = agentAsks(op: 'history', run: 'run-7')

        then:
            all.text.contains('run-1')
            one.text.contains('~/workspace/answer.txt (added)')
            one.text.contains('The answer is in answer.txt.')
            none.text.contains('There is no run called \'run-7\'')
    }

    def 'With the schedule on, the agent is told how it works, and pi is given the tools'() {
        reportInfo """
            AGENTS.md is what the agent reads about the machine it is on. With the schedule on, it
            also says what the scheduling tools do, what limits apply, and that the agent's notes
            are its only memory between runs. The tools themselves are a pi extension that oillamp
            puts in pi's directory each session, and takes away again when the schedule is off.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\nmax_agent_jobs = 3\n')
            var guide = Files.readString(home(lamp).resolve('AGENTS.md'))
            var tools = home(lamp).resolve('.pi/agent/extensions/oillamp-schedule.js')

        expect:
            guide.contains('## Working on a schedule')
            guide.contains('at most 3 jobs at once')
            guide.contains('~/workspace/NOTES.md')
            Files.readString(tools).contains('/oillamp/sockets/host/schedule.sock')

        when: 'the schedule is switched off again'
            Files.writeString(lamp.resolve('oillamp.toml'),
                    Files.readString(lamp.resolve('oillamp.toml')).replace('enabled = true', 'enabled = false'))
            host.oillamp.run('at', lamp.toString())

        then:
            !Files.readString(home(lamp).resolve('AGENTS.md')).contains('## Working on a schedule')
            !Files.exists(tools)
    }

    def 'An application schedules jobs and asks the agent through the Lamp API'() {
        reportInfo """
            An application that holds a lamp offers the same with its own controls: it reads and
            changes the schedule, and asks the agent something and gets the answer back as a
            value, with the snapshot that recorded the run.
        """
        given:
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            anAgent { prompt -> 'Hello from the sandbox.' }
            var lamps = Lamp.at(lamp).launchedBy(host.launcher)

        when:
            var job = lamps.repeat('0 8 * * *', 'Morning check')
            var once = lamps.once('in 1h', '- starts with a dash')
            var listed = lamps.schedule()
            lamps.unschedule(job.id())
            var ending = lamps.repeat('0 8 * * *', 'For two days', NOW.plus(Duration.ofDays(2)))
            var atAnInstant = lamps.once(NOW.plus(Duration.ofMinutes(90)), 'At an instant')
            lamps.disable(ending.id())
            var switchedOff = lamps.schedule().jobs().find { it.id() == ending.id() }
            lamps.enable(ending.id())
            lamps.pause()
            var paused = lamps.schedule().paused()
            lamps.resume()
            lamps.unschedule(ending.id())
            lamps.unschedule(atAnInstant.id())
            var running = lamps.onEvent { reported << it }.start()
            assert running.awaitRunning(Duration.ofSeconds(30))
            var answer = running.ask('Say hello')
            running.close()

        then:
            job.when() == '0 8 * * *'
            once.prompt() == '- starts with a dash'
            listed.jobs()*.id() == ['job-1', 'job-2']
            lamps.schedule().jobs()*.id() == ['job-2']
            ending.expires() == Optional.of(NOW.plus(Duration.ofDays(2)))
            atAnInstant.next() == Optional.of(NOW.plus(Duration.ofMinutes(90)))
            !switchedOff.enabled()
            paused
            !lamps.schedule().paused()
            answer.outcome() == LampRunOutcome.FINISHED
            answer.answer() == 'Hello from the sandbox.'
            answer.snapshot().isPresent()

        and: 'the run happened in the session the application holds'
            reported.any { it instanceof LampEvent.RunStarted && it.run().prompt() == 'Say hello' }
    }

    // ─── run numbers ───────────────────────────────────────────────────────────────────────

    def 'Oillamp gives a prompt sent with `oillamp ask` its own number while the run before it has no snapshot yet'() {
        reportInfo """
            Every run has a name, such as `run-12`. The name is in the run's snapshots, in the
            name of its pi conversation, and in `oillamp history`, so two runs must never share
            one.

            Oillamp takes the next number from the lamp's history. Each snapshot of a run carries
            the run's name in a trailer: a `key: value` line at the end of the snapshot's commit
            message, such as `Oillamp-Run: run-12`, which `oillamp history` reads. The next run
            gets the number one above the highest such line. A run is numbered when it is queued,
            but its snapshots are made later: the `before-run` snapshot as it begins, and only
            when `autosave.before_run` is on and something changed, and the `run` snapshot only as
            it ends. So while the first run of a lamp works, the history may name no run yet. If
            oillamp looked at the history alone, a prompt sent at that moment would get the name
            `run-1` a second time. Oillamp therefore also remembers the highest number the session
            has given out, and takes the higher of the two.

            The scenario makes that moment certain with `autosave.before_run = false`, so that a
            run's only snapshot is the one made as it ends. The agent works on a first prompt
            until the scenario lets it finish. A second prompt is sent with `oillamp ask`
            meanwhile, and waits. Its `RunQueued` event, which says that a run waits its turn,
            must name it `run-2`, and the history must hold one `run` snapshot for each of the
            two.
        """
        given: 'a session whose agent keeps working on "slow" until it is let go, on a lamp that saves runs only as they end'
            var lamp = aLampThatHasRun('[autosave]\nbefore_run = false\n')
            var release = new CountDownLatch(1)
            anAgent { prompt ->
                if (prompt == 'slow') release.await()
                'answered: ' + prompt
            }
            startASession(lamp)

        when: 'a second question is asked while the agent works on the first'
            var slow = Thread.start { assert host.oillamp.run('ask', lamp.toString(), 'slow').succeeded() }
            var first = waitFor(LampEvent.RunStarted)
            var quick = Thread.start { assert host.oillamp.run('ask', lamp.toString(), 'quick').succeeded() }
            var queued = waitFor(LampEvent.RunQueued)
            release.countDown()
            slow.join(20_000)
            quick.join(20_000)

        then: 'the first is run-1, and the one that waits is run-2'
            first.run().id() == 'run-1'
            queued.run().id() == 'run-2'

        and: 'the history has one run snapshot for each'
            var snapshots = host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots()
            snapshots.findAll { it.kind() == LampSaveKind.RUN }*.run()*.get().sort() == ['run-1', 'run-2']
    }

    def 'Oillamp numbers the runs of a session one after another when it saves none of them'() {
        reportInfo """
            Oillamp takes a run's number from the lamp's history. Each snapshot of a run carries
            the run's name as a `key: value` line at the end of the snapshot's commit message,
            such as `Oillamp-Run: run-12`, which `oillamp history` reads, and the next run gets
            the number one above the highest. With `autosave.before_run = false` and
            `autosave.after_run = false`, a run makes no snapshot, so the history never names one.
            The session still remembers the numbers it has given out, and the next run gets the
            number above the highest of them.

            The scenario sends two prompts with `oillamp ask`, one after the other, on a lamp that
            saves no runs. The first must be `run-1` and the second `run-2`, and the history stays
            empty.
        """
        given: 'a session on a lamp that saves nothing around runs'
            var lamp = aLampThatHasRun('[autosave]\nbefore_run = false\nafter_run = false\n')
            anAgent { prompt -> 'Fine.' }
            startASession(lamp)

        when: 'two questions are asked, one after the other'
            var first = host.oillamp.run('ask', lamp.toString(), 'One?')
            var second = host.oillamp.run('ask', lamp.toString(), 'Two?')

        then: 'they are run-1 and run-2'
            first.events().find { it instanceof LampEvent.RunFinished }.run().id() == 'run-1'
            second.events().find { it instanceof LampEvent.RunFinished }.run().id() == 'run-2'

        and: 'the history holds no snapshot to number them from'
            host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots().isEmpty()
    }

    def 'Oillamp reads a schedule file an older version of oillamp wrote, and ignores the run counter in it'() {
        reportInfo """
            An older version of oillamp counted runs in `.oillamp/schedule.json`, as `"next_run"`.
            Oillamp now saves the schedule file in every snapshot, and a restore brings it back; a
            counter kept in it would go back with the restore and give out the same numbers again.
            So oillamp numbers runs from the history instead: one above the highest run its
            snapshots name. A lamp made by the older version still has the counter in its file.

            Oillamp reads such a file without a warning, keeps its jobs and `"next_job"`, the
            number the next job gets, and leaves out `"next_run"` the next time it writes the
            file.

            In this scenario the file says the next run is number 7, while the history holds no
            run. The scenario sends a prompt with `oillamp ask` in a session, whose run must be
            `run-1`. Then it adds a job, which must be `job-2`, since the file says the next job
            is number 2. Last it reads the file, which must no longer hold `"next_run"`.
        """
        given: 'a schedule file as an older oillamp wrote it, with one job and the next run numbered 7'
            var lamp = aLampThatHasRun('')
            var file = lamp.resolve('.oillamp/schedule.json')
            Files.writeString(file, JsonOutput.toJson([paused: false, next_job: 2, next_run: 7, jobs: [
                    [id: 'job-1', cron: '@daily', prompt: 'Tidy up', author: 'user',
                     created: NOW.toString(), enabled: true]]]))
            anAgent { prompt -> 'Fine.' }

        when: 'the user lists the schedule'
            var listed = host.oillamp.run('schedule', lamp.toString())

        then: 'oillamp reads it without a warning'
            listed.succeeded()
            !listed.reported('OIL-SCHEDULE-002')
            listed.events().find { it instanceof LampEvent.Schedule }.jobs()*.id() == ['job-1']

        when: 'the user asks the agent something in a session'
            startASession(lamp)
            var asked = host.oillamp.run('ask', lamp.toString(), 'Hello?')

        then: 'the run is numbered from the history, which names no run yet'
            asked.events().find { it instanceof LampEvent.RunFinished }.run().id() == 'run-1'

        when: 'the user adds a job'
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 2h', 'Look again')

        then: 'the job gets the number the file kept for it, and the file no longer counts runs'
            added.events().find { it instanceof LampEvent.JobAdded }.job().id() == 'job-2'
            !Files.readString(file).contains('next_run')
    }

    def 'Oillamp does not count the schedule among the files a run changed'() {
        reportInfo """
            The agent learns what its earlier runs did from a list of the files each one changed:
            the prompt of a job's run carries it, and the agent's `run_history` tool shows it.
            Oillamp makes the list by comparing the files a run's snapshot holds with those of the
            snapshot the lamp was in as the run began. Among the files a snapshot holds is the
            lamp's schedule, at the path `.oillamp/schedule.json`, and the schedule changes with
            every run of a job, because oillamp records that the job ran. That file is not in the
            agent's home; the agent could not even open it. Listing it would only tell the agent
            of a file it has no part in, so oillamp leaves it out.

            Let's consider a one-time job on a lamp with `autosave.before_run = false`. Without
            the save just before the run, the run is compared with the newest snapshot before it,
            which the scenario makes by hand while the job is still on the schedule. The run
            removes the job, so the two snapshots hold different schedules. The agent writes
            nothing in the run; only pi does, keeping the run's conversation under
            `~/.pi/agent/sessions/`. When the agent asks about `run-1` with `run_history`, the
            session must list that directory, and nothing under `.oillamp`.
        """
        given: 'a job for five minutes from now, on a lamp that saves runs only as they end'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n[autosave]\nbefore_run = false\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 5m', 'Look around, change nothing')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            anAgent { prompt -> 'Nothing to do.' }

        when: 'a session runs the job ten minutes later, and the agent asks what run-1 changed'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)
            waitFor(LampEvent.RunFinished)
            var asked = agentAsks(op: 'history', run: 'run-1')

        then: 'the run changed only the directory of pi\'s conversations, and the schedule is not listed'
            asked.text.contains('It changed:\n- ~/.pi/agent/sessions/ (added)\n\n')
            !asked.text.contains('.oillamp')
    }

    // ─── late runs ─────────────────────────────────────────────────────────────────────────

    def 'Oillamp does not wake the agent for an hourly job after a three-day break'() {
        reportInfo """
            Jobs run only while a session runs. Let's consider an `@hourly` job, added on Tuesday
            at 16:15 in Berlin, on a lamp that nobody starts again until Friday at 16:20. The
            job's next time, counted from when it was added, was Tuesday at 17:00. That run is now
            three days late. A prompt such as "Summarise this afternoon's build failures" asks
            about Tuesday afternoon; answered on Friday, it reports on the wrong afternoon, and
            the run still costs model tokens.

            A running session checks the schedule as it starts, every 30 seconds after that, and
            whenever someone changes the schedule. `schedule.max_delay_minutes`, 30 unless the
            lamp says otherwise, is how late a run may start. When the session sees a job whose
            time lies more than that many minutes in the past, oillamp misses the run: it does not
            wake the agent, and reports a `RunMissed` event, with when the run was due and how
            late it was. A repeating job then waits for its next time after now, here Friday at
            17:00. Oillamp does not catch up on the run itself. An application, a program built on
            oillamp, such as a desktop app, that starts the engine and receives its events, can
            still have the prompt answered with `oillamp ask` if it decides the run is worth
            making.

            The scenario starts the session on Friday, waits for `RunMissed`, and checks that the
            agent got no prompt, that `oillamp schedule` lists 17:00 on Friday as the job's next
            time, and that the session's terminal says the run was missed.
        """
        given: 'an hourly job, added on Tuesday at 16:15 in Berlin'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')
            anAgent { prompt -> 'Looked.' }

        when: 'a session starts on Friday at 16:20'
            host.machine { it.clockAt(Instant.parse('2026-09-25T14:20:00Z')) }
            startASession(lamp)
            var missed = waitFor(LampEvent.RunMissed)

        then: 'oillamp reports the run that was due on Tuesday at 17:00 as missed'
            missed.job().id() == 'job-1'
            missed.due() == Instant.parse('2026-09-22T15:00:00Z')
            missed.late() == Duration.between(Instant.parse('2026-09-22T15:00:00Z'), Instant.parse('2026-09-25T14:20:00Z'))

        and: 'the job runs next on Friday at 17:00 in Berlin'
            missed.job().next() == Optional.of(Instant.parse('2026-09-25T15:00:00Z'))
            host.oillamp.run('schedule', lamp.toString()).events().find { it instanceof LampEvent.Schedule }
                    .jobs().find { it.id() == 'job-1' }.next() == Optional.of(Instant.parse('2026-09-25T15:00:00Z'))

        and: 'the agent was not woken'
            prompts.isEmpty()
            !reported.any { it instanceof LampEvent.RunStarted }

        when: 'the session ends'
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)

        then: 'its terminal said which run was missed, how late it was, and which setting decides'
            sessionOutcome.console().contains('job-1 missed its run of 2026-09-22 15:00:00 UTC: it was 2 days and 23 hours late, '
                    + 'more than schedule.max_delay_minutes allows, so the agent was not woken; it runs next 2026-09-25 15:00:00 UTC')
    }

    def 'Oillamp #verdict when an hourly job\'s run is #late minutes late, #limit'() {
        reportInfo """
            `schedule.max_delay_minutes` says how late a job's run may start. A running session
            checks the schedule as it starts, every 30 seconds after that, and whenever someone
            changes the schedule. When it sees a job whose time lies more than that many minutes
            in the past, oillamp misses the run: it does not wake the agent, and reports a
            `RunMissed` event. A run late by exactly the limit still starts; "more than" is meant
            strictly. 0 turns the limit off, so a run starts however late it is.

            Let's consider an `@hourly` job, added on Tuesday at 16:15 in Berlin, whose first run
            is due at 17:00. The scenarios run on a simulated machine whose clock the scenario
            sets, and which does not move by itself. This one starts a session with the clock
            exactly the given number of minutes after 17:00, and waits for either the run to
            finish or `RunMissed`. It checks the edges: 30 and 31 minutes with the default limit
            of 30, 90 and 121 minutes with a limit of 120, and three days with no limit.
        """
        given: 'an hourly job, added on Tuesday at 16:15 in Berlin, first due at 17:00'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n' + setting)
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')
            anAgent { prompt -> 'Looked.' }

        when: 'a session starts that many minutes after 17:00'
            host.machine { it.clockAt(Instant.parse('2026-09-22T15:00:00Z').plus(Duration.ofMinutes(late))) }
            startASession(lamp)
            var outcome = woken ? waitFor(LampEvent.RunFinished) : waitFor(LampEvent.RunMissed)

        then: 'the agent got the job\'s prompt, or oillamp reported the run as missed, never both'
            prompts.size() == (woken ? 1 : 0)
            reported.any { it instanceof LampEvent.RunMissed } == !woken

        and: 'a missed run says how late it was'
            woken || outcome.late() == Duration.ofMinutes(late)

        where:
            setting                         | late     || woken
            ''                              | 30       || true
            ''                              | 31       || false
            'max_delay_minutes = 120\n'     | 90       || true
            'max_delay_minutes = 120\n'     | 121      || false
            'max_delay_minutes = 0\n'       | 3 * 1440 || true

            verdict = woken ? 'wakes the agent' : 'misses the run'
            limit = setting.isEmpty() ? 'with the default limit of 30 minutes'
                  : setting.contains('= 0') ? 'with schedule.max_delay_minutes = 0'
                  : 'with schedule.max_delay_minutes = 120'
    }

    def 'Oillamp takes a one-time job off the schedule when it misses its run'() {
        reportInfo """
            A job added with `--at` runs once. Let's consider one that was due at 16:20 on Tuesday
            in Berlin, on a lamp whose next session starts at 16:51. The session checks the
            schedule as it starts, and sees the job's time 31 minutes in the past. That is more
            than the 30 minutes `schedule.max_delay_minutes` allows by default, so oillamp misses
            the run and does not wake the agent.

            A repeating job would wait for its next time, but a one-time job has none. So oillamp
            takes it off the schedule, and says why: first a `RunMissed` event, in which the job
            has no next time, then a `JobRemoved` event, whose reason names the run that was
            missed and the setting.

            The scenario waits for `JobRemoved`, then checks both events, that the agent got no
            prompt, and that `oillamp schedule` lists no job.
        """
        given: 'a job for 16:20 on Tuesday, added at 16:15'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 5m', 'Send the summary')
            anAgent { prompt -> 'Sent.' }

        when: 'a session starts at 16:51'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(36))) }
            startASession(lamp)
            var removed = waitFor(LampEvent.JobRemoved)

        then: 'oillamp reported the run as missed, 31 minutes late, with no next time'
            var missed = reported.find { it instanceof LampEvent.RunMissed }
            missed.job().id() == 'job-1'
            missed.due() == NOW.plus(Duration.ofMinutes(5))
            missed.late() == Duration.ofMinutes(31)
            missed.job().next().isEmpty()

        and: 'then took the job off the schedule, and said why'
            reported.indexOf(missed) < reported.indexOf(removed)
            removed.job().id() == 'job-1'
            removed.why().contains('was missed')
            removed.why().contains('schedule.max_delay_minutes')

        and: 'the agent was not woken, and the schedule is empty'
            prompts.isEmpty()
            host.oillamp.run('schedule', lamp.toString()).events()
                    .find { it instanceof LampEvent.Schedule }.jobs().isEmpty()
    }

    def 'Oillamp reports no missed run for a job someone switched off and on again'() {
        reportInfo """
            When someone switches a repeating job back on, oillamp counts it as if it had just
            run, so its next time is the first one after that moment. A job someone switched off
            did not miss its runs: it was not meant to run. So it must not produce a `RunMissed`
            event.

            Let's consider an `@hourly` job, added on Tuesday at 16:15 in Berlin and switched off
            at once, and switched on again on Friday at 16:15. Counted from Tuesday, its next time
            would be Tuesday at 17:00, three days late. Counted from when it was switched on, it
            is Friday at 17:00, not yet due when a session starts at 16:20.

            To know that the session has checked the schedule, the scenario adds a second job, for
            16:16 on Friday, and waits until it has run. Each time the session checks the
            schedule, it goes through all due jobs in the order of their due times. Had the hourly
            job counted as due since Tuesday, its `RunMissed` would have come before the second
            job woke the agent.
        """
        given: 'an hourly job, added on Tuesday at 16:15 and switched off at once'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')
            host.oillamp.run('schedule', lamp.toString(), 'disable', 'job-1')
            anAgent { prompt -> 'Done.' }

        and: 'switched on again on Friday at 16:15, with a one-time job for a minute later'
            host.machine { it.clockAt(NOW.plus(Duration.ofDays(3))) }
            host.oillamp.run('schedule', lamp.toString(), 'enable', 'job-1')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 1m', 'Send the summary')

        when: 'a session starts on Friday at 16:20, and the one-time job runs'
            host.machine { it.clockAt(NOW.plus(Duration.ofDays(3)).plus(Duration.ofMinutes(5))) }
            startASession(lamp)
            var ran = waitFor(LampEvent.RunFinished)

        then: 'only the one-time job woke the agent'
            ran.run().job() == Optional.of('job-2')
            prompts.size() == 1

        and: 'no run was reported as missed'
            !reported.any { it instanceof LampEvent.RunMissed }
    }

    def 'Oillamp reports no missed run for a repeating job after a long pause'() {
        reportInfo """
            `oillamp schedule pause` stops every job from running; `oillamp schedule resume` lets
            them run again. A paused job was not meant to run, so the times the pause covered are
            not runs it missed. Resuming therefore counts every repeating job as if it had just
            run, as switching one job back on does. The job runs at its first time after the
            resume, and no `RunMissed` event is reported for it.

            Let's consider an `@hourly` job, added on Tuesday at 16:15 in Berlin, on a schedule
            paused at once and resumed on Friday at 16:15. Counted from Tuesday, its next time
            would be Tuesday at 17:00, three days later than `schedule.max_delay_minutes` allows.
            Counted from the resume, it is Friday at 17:00, not yet due when a session starts at
            16:20.

            To know that the session has checked the schedule, the scenario adds a one-time job
            for 16:16 on Friday and waits until it has run. Each time the session checks the
            schedule, it goes through all due jobs in the order of their due times. Had the hourly
            job counted as due since Tuesday, its `RunMissed` would have come before the one-time
            job woke the agent.
        """
        given: 'an hourly job, added on Tuesday at 16:15, on a schedule paused at once'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')
            host.oillamp.run('schedule', lamp.toString(), 'pause')
            anAgent { prompt -> 'Done.' }

        and: 'the schedule resumed on Friday at 16:15, with a one-time job for a minute later'
            host.machine { it.clockAt(NOW.plus(Duration.ofDays(3))) }
            host.oillamp.run('schedule', lamp.toString(), 'resume')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 1m', 'Send the summary')

        when: 'a session starts on Friday at 16:20, and the one-time job runs'
            host.machine { it.clockAt(NOW.plus(Duration.ofDays(3)).plus(Duration.ofMinutes(5))) }
            startASession(lamp)
            var ran = waitFor(LampEvent.RunFinished)

        then: 'only the one-time job woke the agent, and no run was reported as missed'
            ran.run().job() == Optional.of('job-2')
            prompts.size() == 1
            !reported.any { it instanceof LampEvent.RunMissed }

        and: 'the hourly job runs next at 17:00 on Friday'
            host.oillamp.run('schedule', lamp.toString()).events().find { it instanceof LampEvent.Schedule }
                    .jobs().find { it.id() == 'job-1' }.next() == Optional.of(Instant.parse('2026-09-25T15:00:00Z'))
    }

    def 'Oillamp reports no missed run for a job that waits in the queue while the agent is busy'() {
        reportInfo """
            The agent works on one thing at a time. A job that comes due while it is busy waits in
            the queue, and runs once the agent is free. A running session checks the schedule as
            it starts, every 30 seconds after that, and whenever someone changes the schedule.
            `schedule.max_delay_minutes` applies when the session checks and sees a job due: a job
            in time then is queued, and is not missed afterwards, however long it waits. Each
            later check skips the jobs already queued.

            Let's consider an `@hourly` job, first due at 17:00 in Berlin, and a session started
            at 16:30, whose agent is soon busy with a prompt that takes long. At 17:00 the session
            sees the job due and queues it. The clock then goes on past 17:30, while the session
            keeps checking the schedule. It must not report a `RunMissed` event, and the job must
            run once the agent has answered.

            The scenarios run on a simulated machine whose clock the scenario sets. It cannot be
            set again while a session runs, but it can run faster than real time. Here it runs 300
            times faster: one real second is five minutes. The scenario makes the session check
            the schedule twice a real second, through the session's control socket, as `oillamp
            schedule` does after a change.
        """
        given: 'an hourly job, first due at 17:00, on a lamp whose runs may take a whole day'
            var lamp = aLampThatHasRun("""
                [schedule]
                enabled = true
                max_run_minutes = 1440

                [timeouts]
                container_ready_seconds  = 3600
                terminal_connect_seconds = 3600
            """.stripIndent())
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')
            var release = new CountDownLatch(1)
            anAgent { prompt ->
                if (prompt == 'A long question') release.await()
                'Done.'
            }

        and: 'a session from 16:30, on a clock that runs 300 times faster than real time'
            host.machine { it.clockAt(Instant.parse('2026-09-22T14:30:00Z')).clockRunsFaster(300) }
            startASession(lamp)

        when: 'the agent works on a long question, and the job comes due meanwhile'
            var asked = Thread.start { host.oillamp.run('ask', lamp.toString(), 'A long question') }
            waitFor(LampEvent.RunStarted) { it.run().prompt() == 'A long question' }
            var deadline = System.currentTimeMillis() + 30_000
            while (!reported.any { it instanceof LampEvent.RunQueued && it.run().job() == Optional.of('job-1') }) {
                assert System.currentTimeMillis() < deadline : 'the job was never queued'
                lookAtTheScheduleNow()
                Thread.sleep(500)
            }

        and: 'it waits for twelve real seconds, an hour on the clock, while the session keeps looking'
            24.times { lookAtTheScheduleNow(); Thread.sleep(500) }
            release.countDown()
            asked.join(20_000)
            var ran = waitFor(LampEvent.RunFinished) { it.run().job() == Optional.of('job-1') }

        then: 'the job ran once the agent was free, and no run was reported as missed'
            ran.outcome() == LampRunOutcome.FINISHED
            !reported.any { it instanceof LampEvent.RunMissed }
            reported.count { it instanceof LampEvent.RunStarted && it.run().job() == Optional.of('job-1') } == 1
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private Path aLampThatHasRun(String toml) {
        var lamp = host.lampPath()
        host.givenConfig(lamp, 'schema_version = 1\n' + toml.stripIndent())
        assert host.oillamp.run('at', lamp.toString()).succeeded()
        lamp
    }

    private void anAgent(Closure<String> work) {
        host.machine { it.agent { String prompt -> prompts << prompt; work(prompt) } }
    }

    private static Path home(Path lamp) { Lamp.agentHome(lamp).orElseThrow() }

    private void startASession(Path lamp, String... options) {
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(90)) }
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        session = Thread.start { sessionOutcome = oillamp.run(*(['at', lamp.toString()] + options.toList())) }
        waitFor(LampEvent.Summary) { it.title() == 'your session is up' }
    }

    private <T extends LampEvent> T waitFor(Class<T> kind, Closure<Boolean> which = { true }) {
        var deadline = System.currentTimeMillis() + 60_000
        while (true) {
            var found = reported.find { kind.isInstance(it) && which(it) }
            if (found != null) return (T) found
            assert System.currentTimeMillis() < deadline : "no ${kind.simpleName} came"
            Thread.sleep(50)
        }
    }

    /** Makes the session look at the schedule now, as `oillamp schedule` does after a change. */
    private void lookAtTheScheduleNow() {
        var control = host.runtime.resolve('oillamp/k3v7x2ab/run/control.sock')
        SocketChannel.open(UnixDomainSocketAddress.of(control)).withCloseable { channel ->
            channel.write(ByteBuffer.wrap('{"op":"schedule-changed"}\n'.getBytes(StandardCharsets.UTF_8)))
            channel.read(ByteBuffer.allocate(1024))
        }
    }

    /** What the agent's tools do: one JSON line to the session's schedule socket, one line back. */
    private Map agentAsks(Map request) {
        var socket = host.runtime.resolve('oillamp/k3v7x2ab/sockets/host/schedule.sock')
        SocketChannel.open(UnixDomainSocketAddress.of(socket)).withCloseable { channel ->
            channel.write(ByteBuffer.wrap((JsonOutput.toJson(request) + '\n').getBytes(StandardCharsets.UTF_8)))
            channel.shutdownOutput()
            var answer = new ByteArrayOutputStream()
            var buffer = ByteBuffer.allocate(8192)
            while (channel.read(buffer) >= 0) {
                answer.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            (Map) new JsonSlurper().parseText(answer.toString(StandardCharsets.UTF_8).readLines().first())
        }
    }
}
