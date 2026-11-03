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
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Title('Waking the agent')
@Narrative('''
A session holds one agent: pi, in the sandbox, which oillamp starts and talks to. Two things wake
it:

- a *job* on the lamp's schedule, at the time the job names. Jobs are added with
  `oillamp schedule add`, by a program through the `Lamp` API, or by the agent itself through
  its scheduling tools;
- a question sent with `oillamp ask`, at once.

Each time the agent is woken is a *run*, named `run-1`, `run-2` and so on, and each run begins a
new pi conversation. So that the agent can carry on where it left off, a job's prompt carries the
agent's notes, `~/workspace/NOTES.md`, the files the recent runs changed, and what the last run
said. Oillamp saves the lamp just before and just after each job's run, and the questions of a
chat together once the chat has been quiet, so `oillamp history` shows what each run did, and any
run can be undone.

The schedule is off until `schedule.enabled = true` is set in `oillamp.toml`, because every run
costs model tokens.

The agent in these scenarios is a stand-in that answers as pi does, and does what each scenario
has it do. Everything else is real: the session, the sockets, the schedule file and the history.
The host's clock is simulated, and stands at Tuesday 22 September 2026, 16:15 in Berlin, unless a
scenario moves it.
''')
@Subject([OilLamp, Lamp])
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class Waking_The_Agent_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

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

    def '`oillamp schedule` lists a job with its next time on the host\'s clock'() {
        reportInfo """
            Let's consider a lamp whose schedule is on, on a host in Berlin, on Tuesday at 16:15.
            A job is added with `oillamp schedule add --cron "0 9 * * 1-5"`: nine in the morning on
            weekdays. Whoever writes "9" means nine on their own clock, so oillamp reads a cron
            expression in the host's time zone, and `oillamp schedule` lists the job's times in it,
            naming the zone.

            Oillamp keeps the schedule in the lamp's `.oillamp/schedule.json`, outside the agent's
            home, so the agent cannot edit it.
        """
        given: 'a lamp whose schedule is on'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when: '`oillamp schedule add` adds the job, and `oillamp schedule` lists the schedule'
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '0 9 * * 1-5', 'Check the nightly build and fix what broke')
            var listed = host.oillamp.run('schedule', lamp.toString())

        then: 'the `JobAdded` event names `job-1`, added by the user, next due on Wednesday at 9:00 Berlin time, with no end'
            added.succeeded()
            var job = added.events().find { it instanceof LampEvent.JobAdded }.job()
            job.id() == 'job-1'
            job.author() == LampJobAuthor.USER
            job.next() == Optional.of(Instant.parse('2026-09-23T07:00:00Z'))
            job.expires().isEmpty()

        and: 'the listing shows `job-1` at `2026-09-23 09:00` in `Europe/Berlin`'
            listed.console().contains('job-1')
            listed.console().contains('2026-09-23 09:00')
            listed.console().contains('Europe/Berlin')

        and: 'the schedule is in `.oillamp/schedule.json`, not in the agent\'s home'
            Files.exists(lamp.resolve('.oillamp/schedule.json'))
            !Files.exists(home(lamp).resolve('schedule.json'))
    }

    def '`oillamp schedule` lists every time a job runs in the coming seven days'() {
        reportInfo """
            Let's consider a program built on oillamp that draws a lamp's schedule as a timeline.
            It needs every time a job runs, not only the next one, and should not have to read cron
            expressions to work them out. So each job in the `Schedule` event lists its `upcoming`
            times over the next seven days, and none after the job's end. A job that is switched
            off lists none.

            On Tuesday at 16:15 Berlin time, the scenario adds four jobs: one at nine on weekdays,
            one at nine every day that ends in three days, one that runs once in two hours, and an
            hourly one that it switches off.
        """
        given: 'Tuesday, 16:15 in Berlin, on a lamp whose schedule is on'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when: '`oillamp schedule add` adds the four jobs, `oillamp schedule disable` switches off the fourth, and `oillamp schedule` lists them'
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '0 9 * * 1-5', 'Weekday check')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '0 9 * * *', '--expires', 'in 3d', 'Daily, for three days')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 2h', 'Once')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Switched off')
            host.oillamp.run('schedule', lamp.toString(), 'disable', 'job-4')
            var listing = host.oillamp.run('schedule', lamp.toString())
            var listed = listing.events().find { it instanceof LampEvent.Schedule }.jobs()

        then: 'the weekday job runs at nine on each weekday until next Tuesday at 16:15'
            listed[0].upcoming().collect() == ['2026-09-23', '2026-09-24', '2026-09-25', '2026-09-28', '2026-09-29']
                    .collect { Instant.parse(it + 'T07:00:00Z') }

        and: 'the daily job runs three times, the last on Friday morning, before it ends on Friday afternoon'
            listed[1].upcoming().size() == 3
            listed[1].upcoming().last() == Instant.parse('2026-09-25T07:00:00Z')

        and: 'the one-time job runs once, in two hours; the job switched off never runs'
            listed[2].upcoming().collect() == [NOW.plus(Duration.ofHours(2))]
            listed[3].upcoming().isEmpty()
    }

    def '`oillamp schedule add` refuses a time it cannot use, and says why'() {
        reportInfo """
            A job that never runs because its time was wrong is found out only days later. So
            `oillamp schedule add` refuses at once a time it cannot use, reports `OIL-SCHEDULE-001`
            with what was wrong, and adds nothing. The scenario tries three: the cron expression
            `0 25 * * *`, whose hour does not exist; `--at "2026-01-01 09:00"`, which has passed;
            and a job given both `--cron` and `--at`.
        """
        given: 'a lamp whose schedule is on'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when: '`oillamp schedule add` is run with hour 25, with a date that has passed, and with both `--cron` and `--at`'
            var badHour = host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '0 25 * * *', 'x')
            var passed = host.oillamp.run('schedule', lamp.toString(), 'add', '--at', '2026-01-01 09:00', 'x')
            var both = host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', '--at', 'in 2h', 'x')

        then: 'each is refused with `OIL-SCHEDULE-001`; the first says "the hour 25 is not between 0 and 23", the second "has already passed"'
            [badHour, passed, both].every { !it.succeeded() && it.reported('OIL-SCHEDULE-001') }
            badHour.problems().first().whatHappened().contains('the hour 25 is not between 0 and 23')
            passed.problems().first().whatHappened().contains('has already passed')

        and: '`oillamp schedule` lists no job'
            var listing = host.oillamp.run('schedule', lamp.toString())
            var jobs = listing.events().find { it instanceof LampEvent.Schedule }.jobs()
            jobs.isEmpty()
    }

    def '`oillamp schedule` switches a job off and on, pauses the whole schedule, and removes a job'() {
        reportInfo """
            Someone who wants the agent to leave a job alone for a week switches it off with
            `oillamp schedule disable`, rather than removing it and writing it again later.
            Someone going on holiday pauses the whole schedule with `oillamp schedule pause`. Both
            only change whether jobs run. `oillamp schedule remove` takes a job off the schedule,
            and reports `OIL-SCHEDULE-003` for a job that does not exist.
        """
        given: 'a lamp with two jobs: `job-1`, hourly, and `job-2`, which runs once'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 2h', 'Send the summary')
            var jobsOnTheSchedule = {
                var listing = host.oillamp.run('schedule', lamp.toString())
                assert listing.succeeded()
                listing.events().find { it instanceof LampEvent.Schedule }.jobs()
            }

        when: '`job-1` is switched off and on again, the schedule is paused and resumed, and `job-2` and then `job-9`, which does not exist, are removed'
            var off = host.oillamp.run('schedule', lamp.toString(), 'disable', 'job-1')
            var switchedOff = jobsOnTheSchedule()
            host.oillamp.run('schedule', lamp.toString(), 'enable', 'job-1')
            var paused = host.oillamp.run('schedule', lamp.toString(), 'pause')
            var pausedListing = host.oillamp.run('schedule', lamp.toString()).console()
            host.oillamp.run('schedule', lamp.toString(), 'resume')
            var removed = host.oillamp.run('schedule', lamp.toString(), 'remove', 'job-2')
            var missing = host.oillamp.run('schedule', lamp.toString(), 'remove', 'job-9')

        then: 'switched off, `job-1` had no next time; paused, the listing said `paused`; `job-2` was removed; `job-9` was refused with `OIL-SCHEDULE-003`'
            off.succeeded()
            !switchedOff.find { it.id() == 'job-1' }.enabled()
            switchedOff.find { it.id() == 'job-1' }.next().isEmpty()
            paused.succeeded()
            pausedListing.contains('paused')
            removed.events().any { it instanceof LampEvent.JobRemoved && it.job().id() == 'job-2' }
            missing.reported('OIL-SCHEDULE-003')

        and: 'only `job-1` is left, switched on again'
            var jobs = jobsOnTheSchedule()
            jobs*.id() == ['job-1']
            jobs.first().enabled()
    }

    def 'An hourly job switched off on Tuesday and on again on Friday runs next at the coming full hour, not at once'() {
        reportInfo """
            Oillamp works out the next time of a repeating job from when it last ran, or, if it
            never ran, from when it was added. While a job is switched off, that starting point
            stays where it was.

            Let's consider an `@hourly` job added on Tuesday at 16:15 and switched off at once with
            `oillamp schedule disable`. On Friday at 16:15 it is switched on again with
            `oillamp schedule enable`. Counted from Tuesday, its next time would be Tuesday at
            17:00, which has passed, so the next session would run the job as soon as it started:
            a run nobody asked for.

            So `enable` counts a repeating job as if it had just run. `oillamp schedule` must then
            list the job's next time as Friday at 17:00 Berlin time, the first full hour after it
            was switched on.
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

    def '`oillamp schedule add --at` reads a time written as a span from now: #written'() {
        reportInfo """
            `--at` and `--expires` accept a time written as a span from now, such as "in 2h", as
            well as a date such as "2026-10-01 09:00". A span is a number and a unit: m, min or
            minutes; h or hours; d or days; w or weeks. A week is 7 days. "+2h" means the same as
            "in 2h".

            On Tuesday at 16:15, the scenario adds one job with each unit, and one with "+2h",
            and checks that the job's next time is 16:15 plus the span: 90 minutes, 2 hours,
            3 days or 7 days.
        """
        given: 'Tuesday, 16:15 in Berlin'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when: 'a job is added with `oillamp schedule add --at` and the span'
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--at', written, 'Look again')

        then: 'it runs next at 16:15 plus the span'
            added.succeeded()
            added.events().find { it instanceof LampEvent.JobAdded }.job().next() == Optional.of(NOW.plus(span))

        where:
            written       || span
            'in 90m'      || Duration.ofMinutes(90)
            'in 2h'       || Duration.ofHours(2)
            'in 3 days'   || Duration.ofDays(3)
            'in 1w'       || Duration.ofDays(7)
            '+2h'         || Duration.ofHours(2)
    }

    def '`oillamp schedule add --cron` reads a cron expression as the Linux scheduler cron does: #meaning'() {
        reportInfo """
            Unix systems have run commands at set times with a program called cron since the
            1970s. Each line of its configuration file, the crontab, starts with five fields that
            say when to run: the minute, the hour, the day of the month, the month and the day of
            the week. `0 9 * * 1-5`, for example, means 9:00 on Monday to Friday. Those five fields
            are called a "cron expression", and many other tools use the same notation, among them
            GitHub Actions and Kubernetes.

            Oillamp uses it for a repeating job's time, because people already know it and copy it
            from crontabs and documentation. The basic format is standardised in POSIX. Most Linux
            systems run Vixie cron, which adds a few rules to it, and expressions found in the wild
            rely on them. So oillamp reads an expression the way Vixie cron does. Two of those
            rules are easy to get wrong:

            - In the day-of-week field, Sunday may be written as 0, as 7, or as `sun`. Some crons
              count Sunday as 0, others as 7, and both spellings are in use. `0 9 * * 7` must run
              on Sundays at 9:00. If oillamp knew only 0, the expression would match no day at all,
              and `oillamp schedule add` would refuse it as naming no time.
            - A step after a number, as in `5/20` in the minute field, starts at that number and
              goes on to the end of the field: minutes 5, 25 and 45. `*/20` starts at 0: minutes 0,
              20 and 40. Read as "minute 5 only", `5/20` would run once an hour instead of three
              times.

            The scenario adds each expression with `oillamp schedule add --cron` on Tuesday
            22 September 2026 at 16:15 in Berlin, and compares the first runs the job lists with
            the runs cron makes. Berlin is two hours ahead of UTC in September, so 9:00 there is
            07:00Z.
        """
        given: 'Tuesday, 16:15 in Berlin'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when: 'a job is added with `oillamp schedule add --cron` and the expression'
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', cron, 'Look again')

        then: 'the first times the job lists are the ones cron would run it at'
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

    def '`oillamp schedule add --cron` combines the two day fields of a cron expression as cron does: #meaning'() {
        reportInfo """
            A cron expression has two fields that name days: the third, the day of the month, and
            the fifth, the day of the week. Cron, the Linux scheduler, combines them in a way that
            surprises people, and oillamp combines them the same way, so that an expression copied
            from a crontab runs when it did there:

            - When both fields are restricted, a day counts when either one matches.
              `0 0 13 * 5` is midnight on the 13th and also midnight on every Friday, not only on
              a Friday that is the 13th.
            - A field that starts with a star does not count as restricted, even with a step after
              it. `0 0 */2 * 1` is every other day of the month starting with the 1st, and of those
              only Mondays: both fields must match.

            The scenario adds each expression with `oillamp schedule add --cron` on Tuesday
            22 September 2026 at 16:15 in Berlin, and reads the job's next time. Berlin is two
            hours ahead of UTC in September, so midnight there is 22:00Z the day before.
        """
        given: 'Tuesday 22 September, 16:15 in Berlin'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when: 'a job is added with `oillamp schedule add --cron` and the expression'
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', cron, 'Look again')

        then: 'its next time is the day cron would run it'
            added.succeeded()
            added.events().find { it instanceof LampEvent.JobAdded }.job().next() == Optional.of(Instant.parse(next))

        where:
            meaning                                                        | cron           || next
            'Friday the 25th counts, although it is not the 13th'          | '0 0 13 * 5'   || '2026-09-24T22:00:00Z'
            'Monday the 28th is an even day, so the next is Monday 5 October' | '0 0 */2 * 1' || '2026-10-04T22:00:00Z'
    }

    def '`oillamp schedule add` takes `--cron=`, `--at=` and `--expires=`, and a prompt that starts with a dash after `--`'() {
        reportInfo """
            `--cron=@daily` means the same as `--cron @daily`. Both spellings are common, and a job
            comes out the same whichever was typed. A prompt is often a list of things to do, and a
            list starts with a dash, which oillamp would read as an option it does not know. After
            `--`, oillamp takes every argument as it is written.
        """
        given: 'Tuesday, 16:15 in Berlin'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')

        when: '`oillamp schedule add` adds two jobs written with `=`, and a third whose prompt, a list, follows `--`'
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron=0 9 * * 1-5', '--expires=in 3d', 'Weekday check')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at=in 2h', 'Once')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', '--', '- read the inbox\n- answer what is urgent')
            var listing = host.oillamp.run('schedule', lamp.toString())
            var listed = listing.events().find { it instanceof LampEvent.Schedule }.jobs()

        then: 'the first job runs at nine on weekdays, and ends on Friday afternoon'
            listed[0].when().contains('0 9 * * 1-5')
            listed[0].next() == Optional.of(Instant.parse('2026-09-23T07:00:00Z'))
            listed[0].expires() == Optional.of(NOW.plus(Duration.ofDays(3)))

        and: 'the second job runs once, two hours from now'
            listed[1].next() == Optional.of(NOW.plus(Duration.ofHours(2)))

        and: 'the third job\'s prompt is exactly the list that was written'
            listed[2].prompt() == '- read the inbox\n- answer what is urgent'
    }

    def 'With the schedule off, `oillamp schedule add` keeps the job, and reports `OIL-SCHEDULE-004`: it will not run'() {
        reportInfo """
            Waking the agent costs model tokens, so a lamp's schedule is off until
            `schedule.enabled = true` is set in its `oillamp.toml`. `oillamp schedule add` still adds a
            job to such a lamp, since someone may set up the jobs before turning the schedule on.
            But it reports `OIL-SCHEDULE-004`, which says that no job runs yet.
        """
        given: 'a lamp whose schedule is off'
            var lamp = aLampThatHasRun('')

        when: '`oillamp schedule add` adds a daily job'
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')

        then: 'it succeeds and reports `OIL-SCHEDULE-004`, and `oillamp schedule` lists the job'
            added.succeeded()
            added.reported('OIL-SCHEDULE-004')
            var listing = host.oillamp.run('schedule', lamp.toString())
            var jobs = listing.events().find { it instanceof LampEvent.Schedule }.jobs()
            jobs.size() == 1
    }

    def '`oillamp at --enable-scheduling` runs the jobs for one session, and leaves `oillamp.toml` as it is'() {
        reportInfo """
            Someone who wants the schedule on for one session should not have to edit
            `oillamp.toml` and change it back afterwards. With `--enable-scheduling`, `oillamp at`
            runs the jobs as if `schedule.enabled = true` were set, and `oillamp schedule` says the
            schedule is on while that session runs. `oillamp.toml` is not changed, so once the
            session has ended, `oillamp schedule` says the schedule is off again.
        """
        given: 'a lamp whose schedule is off, with a job for five minutes from now'
            var lamp = aLampThatHasRun('')
            var toml = Files.readString(lamp.resolve('oillamp.toml'))
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 5m', 'Tidy up')
            anAgent { prompt -> 'Tidied up.' }

        when: 'ten minutes later, `oillamp at --enable-scheduling` starts a session, which `oillamp stop` ends after the run'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp, '--enable-scheduling')
            var finished = waitFor(LampEvent.RunFinished)
            var during = host.oillamp.run('schedule', lamp.toString())
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)
            assert !session.alive
            var after = host.oillamp.run('schedule', lamp.toString())

        then: 'the job ran, and finished'
            finished.run().job() == Optional.of('job-1')
            finished.outcome() == LampRunOutcome.FINISHED

        and: 'the schedule was on while the session ran, and is off again after it'
            during.events().find { it instanceof LampEvent.Schedule }.enabled()
            !after.events().find { it instanceof LampEvent.Schedule }.enabled()

        and: '`oillamp.toml` was not changed'
            Files.readString(lamp.resolve('oillamp.toml')) == toml
    }

    // ─── runs ──────────────────────────────────────────────────────────────────────────────

    def 'A job whose time has come wakes the agent with its prompt, and the run is saved in a snapshot of its own'() {
        reportInfo """
            Let's consider a job, due in five minutes, that asks the agent to write the weekly
            report into `~/workspace/report.md`, and a session that starts ten minutes later. The
            job is due as the session starts, so it runs at once, but only after the session has
            reported `SessionOpened`: a program that waits for that event hears of the run after it.

            The session wakes the agent with the job's prompt, and the agent works and says what it
            did. As the run ends, oillamp saves the lamp. The snapshot names the run, and its
            message holds the agent's last words, so `oillamp history` reads as a log of what the
            agent did and when. A job added with `--at` runs once, and is then taken off the
            schedule.
        """
        given: 'a job, added with `--at`, for five minutes from now'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 5m', 'Write the weekly report into ~/workspace/report.md')

        and: 'an agent that writes the report'
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/report.md'), '# Week 39\n')
                'I wrote the weekly report to ~/workspace/report.md.'
            }

        when: 'a session starts ten minutes later, and `oillamp stop` ends it after the run'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)
            var finished = waitFor(LampEvent.RunFinished)
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)
            assert !session.alive

        then: 'the session reported `SessionOpened` before the run was queued or started'
            reported.any { it instanceof LampEvent.SessionOpened }
            reported.findIndexOf { it instanceof LampEvent.SessionOpened } <
                    reported.findIndexOf { it instanceof LampEvent.RunQueued || it instanceof LampEvent.RunStarted }

        and: 'the agent was woken for `run-1` of `job-1`, with the job\'s prompt, word that it has no notes yet, and that this is the first run'
            reported.find { it instanceof LampEvent.RunStarted }.run() == new LampRun('run-1', Optional.of('job-1'),
                    'Write the weekly report into ~/workspace/report.md', Optional.empty())
            prompts.size() == 1
            prompts[0].contains('You were woken by job-1, a job the user added')
            prompts[0].contains('## The task\n\nWrite the weekly report')
            prompts[0].contains('You have no notes yet')
            prompts[0].contains('This is the first run.')

        and: 'the run finished, and the agent said what it did'
            finished.outcome() == LampRunOutcome.FINISHED
            finished.answer() == 'I wrote the weekly report to ~/workspace/report.md.'

        and: 'the run\'s snapshot is a `RUN` snapshot named `run-1`, holds the agent\'s words, and is the newest in `oillamp history`'
            var snapshot = finished.snapshot().orElseThrow()
            snapshot.kind() == LampSaveKind.RUN
            snapshot.run() == Optional.of('run-1')
            snapshot.message().contains('I wrote the weekly report')
            var history = host.oillamp.run('history', lamp.toString())
            var newest = history.events().find { it instanceof LampEvent.History }.snapshots().first()
            newest.id() == snapshot.id()

        and: '`oillamp history` lists the snapshot with the job, the run\'s outcome and its pi conversation'
            with (newest) {
                job() == Optional.of('job-1')
                outcome() == Optional.of(LampRunOutcome.FINISHED)
                conversation().isPresent()
                conversation() == finished.conversation()
            }

        and: '`job-1` was removed after its one run, and `oillamp schedule` lists no job'
            reported.any { it instanceof LampEvent.JobRemoved && it.job().id() == 'job-1' }
            var listing = host.oillamp.run('schedule', lamp.toString())
            var jobs = listing.events().find { it instanceof LampEvent.Schedule }.jobs()
            jobs.isEmpty()
    }

    def 'A job\'s prompt carries the agent\'s notes, the files the last run changed, and the last run\'s final words'() {
        reportInfo """
            Each job wakes the agent in a new pi conversation, so on its own the agent would
            remember nothing of the runs before. The prompt therefore carries what it needs: the
            notes the agent left itself in `~/workspace/NOTES.md`, which files the recent runs
            changed, and what the last run said at its end. The notes are in `<notes>`, marked as
            the agent's own, not as the user's instructions.

            Let's consider two one-time jobs, due a minute apart. In the first run, the agent
            writes its notes and `~/workspace/data.csv`, and answers "Collected the data.".
        """
        given: 'two one-time jobs, a minute apart; in the first run, the agent writes its notes and `data.csv`'
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

        when: 'a session starts once both are due, and ends after the second run'
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)
            waitFor(LampEvent.RunFinished) { it.run().id() == 'run-2' }
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)
            assert !session.alive

        then: 'the second run\'s prompt holds the notes in `<notes>`, both files as added in `run-1`, and the first run\'s last words in `<last-message>`'
            prompts.size() == 2
            prompts[1].contains('You were woken by job-2')
            prompts[1].contains('<notes>\nNext: add the chart.\n</notes>')
            prompts[1].contains('run-1')
            prompts[1].contains('~/workspace/data.csv (added)')
            prompts[1].contains('~/workspace/NOTES.md (added)')
            prompts[1].contains('<last-message>\nCollected the data.\n</last-message>')
    }

    def 'With `autosave.before_run` and `autosave.after_run` off, a run makes no snapshot, and the next job\'s prompt says nothing of it'() {
        reportInfo """
            Oillamp saves the lamp just before and just after every run of a job, unless
            `autosave.before_run` and `autosave.after_run` are set to `false`. With both off, the
            history holds no runs, so the next job's prompt cannot say what the last run changed or
            said. It still carries the agent's notes, a file in the agent's home, and says that the
            saves after runs are switched off.
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

        then: 'neither run made a snapshot, and `oillamp history` lists none'
            finished.snapshot().isEmpty()
            !reported.any { it instanceof LampEvent.Saved }
            host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots().isEmpty()

        and: 'the second run\'s prompt holds the notes, and says the saves after runs are off, but not the first run\'s last words'
            prompts.size() == 2
            prompts[1].contains('<notes>\nNext: add the chart.\n</notes>')
            !prompts[1].contains('Collected the data.')
            prompts[1].contains('The user has switched off the saves after runs')
    }

    def '`oillamp ask` sends the question to the agent exactly as it was written'() {
        reportInfo """
            A job's prompt carries the agent's notes and its recent runs. A question sent with
            `oillamp ask` does not: a chat shows the prompt as the person's message, so it must be
            what they wrote. The agent's guide, `AGENTS.md`, already tells it to read its notes.
        """
        given: 'a session on a lamp whose schedule is on'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            anAgent { prompt -> 'Fine.' }
            startASession(lamp)

        when: '`oillamp ask` sends "How are you?"'
            host.oillamp.run('ask', lamp.toString(), 'How are you?')

        then: 'the agent received "How are you?" and nothing else'
            prompts == ['How are you?']
    }

    def 'Oillamp saves the questions sent with `oillamp ask` together, once the chat has been quiet for ten minutes'() {
        reportInfo """
            Saving a lamp reads the agent's whole home, which takes a while when it is large. A
            job's run is saved just before it begins and as it ends, but saving each question that
            way would make every answer in a chat wait twice. So oillamp saves the questions sent
            with `oillamp ask` together, once no run has begun for `autosave.chat_quiet_minutes`,
            ten by default. That snapshot is a `CHAT` snapshot, named after the run of the last
            question, and its message names the runs of all of them.

            The session runs on a clock sixty times faster than real time, so ten quiet minutes
            pass in ten seconds, and two questions sent one after the other fall into one chat.
        """
        given: 'an agent that writes each question into a file, on a clock that runs a minute a second'
            var lamp = aLampThatHasRun('')
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/' + prompt + '.md'), prompt)
                'Noted.'
            }
            host.machine { it.clockRunsFaster(60) }
            startASession(lamp)

        when: '`oillamp ask` sends "first", then "second"'
            assert host.oillamp.run('ask', lamp.toString(), 'first').succeeded()
            assert host.oillamp.run('ask', lamp.toString(), 'second').succeeded()
            var finished = reported.findAll { it instanceof LampEvent.RunFinished }

        then: 'neither run made a snapshot of its own'
            finished*.run()*.id() == ['run-1', 'run-2']
            finished*.snapshot().every { it.isEmpty() }
            !reported.any { it instanceof LampEvent.Saved }

        when: 'the chat stays quiet for ten minutes'
            var saved = waitFor(LampEvent.Saved).snapshot()

        then: 'both questions are in one `CHAT` snapshot, named `run-2`, with the message `run-1 to run-2 (asked)`'
            saved.kind() == LampSaveKind.CHAT
            saved.run() == Optional.of('run-2')
            saved.message() == 'run-1 to run-2 (asked)'
            reported.count { it instanceof LampEvent.Saved } == 1

        and: 'it is the newest snapshot, and holds the chat\'s pi conversation'
            var newest = host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots().first()
            newest.id() == saved.id()
            newest.conversation() == finished.last().conversation()
    }

    def 'Oillamp saves a chat that a job interrupts in a `CHAT` snapshot of its own, #saves'() {
        reportInfo """
            Let's consider someone who sends "Make a plan" with `oillamp ask`, and a job that comes
            due a moment later, before the chat has been quiet for `autosave.chat_quiet_minutes`.
            Oillamp saves the chat first, in a `CHAT` snapshot of its own, which names the
            question's run and pi conversation, and only then begins the job's run. So
            `oillamp history` shows that someone asked for the plan, and the job's snapshots hold
            only what the job did. That holds with the save just before the job's run,
            `autosave.before_run`, and without it.

            The session runs on a clock sixty times faster than real time, so the job, due a
            minute after it is added, runs a second later.
        """
        given: 'an agent that writes a file of its own for every prompt'
            var lamp = aLampThatHasRun("[schedule]\nenabled = true\n[autosave]\nbefore_run = ${beforeRun}\n")
            int prompts = 0
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve("workspace/prompt-${++prompts}.md"), prompt)
                'Done.'
            }
            host.machine { it.clockRunsFaster(60) }
            startASession(lamp)

        when: '`oillamp ask` sends "Make a plan", and a job is added for a minute later'
            assert host.oillamp.run('ask', lamp.toString(), 'Make a plan').succeeded()
            var asked = reported.find { it instanceof LampEvent.RunFinished }
            assert host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 1m', 'Tidy up').succeeded()
            var job = waitFor(LampEvent.RunFinished) { it.run().job().isPresent() }

        then: 'the newest snapshots are the job\'s, and before them the `CHAT` snapshot of `run-1`, with the question\'s conversation'
            var snapshots = host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots()
            snapshots.take(kinds.size())*.kind() == kinds
            var chat = snapshots.find { it.kind() == LampSaveKind.CHAT }
            chat.run() == Optional.of('run-1')
            chat.message() == 'run-1 (asked)'
            chat.conversation() == asked.conversation()

        and: 'the job\'s `RUN` snapshot is the newest'
            job.snapshot().isPresent()
            snapshots.first().id() == job.snapshot().get().id()

        where:
            beforeRun || kinds
            true      || [LampSaveKind.RUN, LampSaveKind.BEFORE_RUN, LampSaveKind.CHAT]
            false     || [LampSaveKind.RUN, LampSaveKind.CHAT]
            saves = beforeRun ? 'with a save just before the job\'s run' : 'without a save just before the job\'s run'
    }

    def 'Oillamp saves a chat later when the saves of the job\'s run that interrupts it fail'() {
        reportInfo """
            Let's consider someone who sends a question with `oillamp ask`, and a job that comes
            due a moment later, before the chat has been quiet for `autosave.chat_quiet_minutes`.
            Oillamp saves the chat as the job's run begins. Should that save alone fail, the job's
            own snapshots would still hold what the question changed. In the rare case that the
            lamp's history, `.oillamp/history`, cannot be written at all while the job runs, here
            because it is read-only, the chat save and both saves of the job's run fail. The chat
            must then be saved later, at the latest as the session ends.

            The session runs on a clock sixty times faster than real time, so the job, due a
            minute after it is added, runs a second later.
        """
        given: 'a lamp saved once with `oillamp history save`, and a session whose agent answers everything'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            assert host.oillamp.run('history', lamp.toString(), 'save', '-m', 'to begin with').succeeded()
            anAgent { prompt -> 'Done.' }
            host.machine { it.clockRunsFaster(60) }
            startASession(lamp)

        when: '`oillamp ask` sends a question, and a job runs while `.oillamp/history` is read-only'
            assert host.oillamp.run('ask', lamp.toString(), 'Make a plan').succeeded()
            var writable = readOnly(lamp.resolve('.oillamp/history'))
            assert host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 1m', 'Tidy up').succeeded()
            var job = waitFor(LampEvent.RunFinished) { it.run().job().isPresent() }

        then: 'the job\'s run made no snapshot'
            job.snapshot().isEmpty()

        when: '`.oillamp/history` can be written again, and `oillamp stop` ends the session'
            writable()
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)

        then: 'the question is in a `CHAT` snapshot named `run-1`'
            var snapshots = host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots()
            snapshots.any { it.kind() == LampSaveKind.CHAT && it.run() == Optional.of('run-1') }

        cleanup:
            writable?.call()
    }

    def 'Oillamp saves a question sent just before the session ends as the session ends'() {
        reportInfo """
            Let's consider a question sent with `oillamp ask`, and `oillamp stop` run right after
            it, before the chat has been quiet for `autosave.chat_quiet_minutes`. The chat is over
            all the same, so oillamp saves it as the session ends, while the sandbox is still
            there, whether or not `autosave.shutdown` is on.
        """
        given: 'a session'
            var lamp = aLampThatHasRun('')
            anAgent { prompt -> 'Fine.' }
            startASession(lamp)

        when: '`oillamp ask` sends "How are you?", and `oillamp stop` ends the session at once'
            assert host.oillamp.run('ask', lamp.toString(), 'How are you?').succeeded()
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)

        then: 'the newest snapshot is a `CHAT` snapshot named `run-1`'
            var newest = host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots().first()
            newest.kind() == LampSaveKind.CHAT
            newest.run() == Optional.of('run-1')
            newest.message() == 'run-1 (asked)'
    }

    def 'A question sent with `oillamp ask` while the agent is busy waits its turn'() {
        reportInfo """
            The agent works on one thing at a time. A question sent while it is busy is neither
            refused nor run alongside: it waits, the session reports a `RunQueued` event, and the
            question runs as soon as the agent is free. A job that comes due while the agent is
            busy waits the same way.

            Let's consider an agent that works on the question "slow" until the scenario lets it
            finish, and a second question, "quick", sent meanwhile.
        """
        given: 'an agent that works on "slow" until it is let go'
            var lamp = aLampThatHasRun('')
            var release = new CountDownLatch(1)
            anAgent { prompt ->
                if (prompt == 'slow') release.await()
                'answered: ' + prompt
            }
            startASession(lamp)

        when: '`oillamp ask` sends "slow", then "quick" while the agent works on it, and the agent is let go'
            var slow = Thread.start { assert host.oillamp.run('ask', lamp.toString(), 'slow').succeeded() }
            waitFor(LampEvent.RunStarted)
            var quick = null
            var second = Thread.start { quick = host.oillamp.run('ask', lamp.toString(), 'quick') }
            var queued = waitFor(LampEvent.RunQueued)
            release.countDown()
            slow.join(20_000)
            second.join(20_000)

        then: '"quick" was queued, and answered after "slow"'
            queued.run().prompt() == 'quick'
            quick.succeeded()
            quick.console().contains('answered: quick')
            prompts == ['slow', 'quick']
    }

    def 'Oillamp stops a run that takes longer than `schedule.max_run_minutes`, and saves it'() {
        reportInfo """
            An agent can loop, or wait for something that never comes, and every minute it does
            costs model tokens. So oillamp stops a run after `schedule.max_run_minutes`. It saves
            the run like any other, marked as timed out, so that it can be looked at or undone.

            The agent here writes `half-done.txt` and then never finishes. Restoring the run's
            snapshot brings that file back, which shows that the snapshot holds the half-done work.
        """
        given: 'runs of at most one minute, on a clock that runs ten minutes a second'
            var lamp = aLampThatHasRun('''
                [schedule]
                max_run_minutes = 1

                [autosave]
                chat_quiet_minutes = 0

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

        when: '`oillamp ask` sends "Work forever"'
            var asked = host.oillamp.run('ask', lamp.toString(), 'Work forever')

        then: '`oillamp ask` fails, the run ended `TIMED_OUT`, and the console says it ran out of time'
            !asked.succeeded()
            var finished = asked.events().find { it instanceof LampEvent.RunFinished }
            finished.outcome() == LampRunOutcome.TIMED_OUT
            asked.console().contains('ran out of time')

        and: 'the newest snapshot is the run\'s, marked as timed out'
            var history = host.oillamp.run('history', lamp.toString())
            var snapshot = history.events().find { it instanceof LampEvent.History }.snapshots().first()
            snapshot.kind() == LampSaveKind.RUN
            snapshot.message().contains('timed out')

        when: 'the session is stopped, `half-done.txt` is deleted, and the run\'s snapshot is restored with `oillamp history <dir> restore`'
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)
            Files.delete(home(lamp).resolve('workspace/half-done.txt'))
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', snapshot.id())

        then: '`half-done.txt` is back, as the agent left it: the run\'s snapshot held the half-done work'
            restored.succeeded()
            Files.readString(home(lamp).resolve('workspace/half-done.txt')) == 'started\n'
    }

    def '`oillamp stop` in the middle of a run stops the run, and saves it'() {
        reportInfo """
            Ending a session, with `oillamp stop`, Ctrl-C or by closing its terminal, must not lose
            the work of a run in progress, nor leave the agent working in a sandbox that is about to
            be removed. So the session tells the agent to stop, and saves the run, marked as
            interrupted, before the sandbox goes. The scenario ends the session with
            `oillamp stop`.
        """
        given: 'a session whose agent works on any question for a minute'
            var lamp = aLampThatHasRun('[autosave]\nchat_quiet_minutes = 0\n')
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/partial.txt'), 'some of it\n')
                Thread.sleep(60_000)
                'never reached'
            }
            startASession(lamp)

        when: '`oillamp ask` sends "A long task", and `oillamp stop` ends the session while the agent works on it'
            Thread.start { host.oillamp.run('ask', lamp.toString(), 'A long task') }
            waitFor(LampEvent.RunStarted)
            Thread.sleep(300)
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)
            assert !session.alive

        then: 'the run ended `INTERRUPTED`, its `RUN` snapshot is marked as interrupted, and no failure was reported'
            var finished = reported.find { it instanceof LampEvent.RunFinished }
            finished.outcome() == LampRunOutcome.INTERRUPTED
            var history = host.oillamp.run('history', lamp.toString())
            var snapshots = history.events().find { it instanceof LampEvent.History }.snapshots()
            var run = snapshots.find { it.kind() == LampSaveKind.RUN }
            run.message().contains('interrupted')
            !reported.any { it instanceof LampEvent.Failure }
    }

    def 'Without pi in the sandbox, a run fails with `OIL-SCHEDULE-005`, and the session keeps running'() {
        reportInfo """
            A lamp whose image has no pi, or whose pi crashes as it starts, cannot do a run.
            Oillamp reports `OIL-SCHEDULE-005` with what pi printed, and keeps the session running,
            since the sandbox itself works.
        """
        given: 'a session in a sandbox without pi'
            var lamp = aLampThatHasRun('')
            startASession(lamp)

        when: '`oillamp ask` sends "Hello?"'
            var asked = host.oillamp.run('ask', lamp.toString(), 'Hello?')

        then: 'the run `FAILED`, the warning `OIL-SCHEDULE-005` quotes "pi: command not found", and the session still runs'
            !asked.succeeded()
            asked.events().find { it instanceof LampEvent.RunFinished }.outcome() == LampRunOutcome.FAILED
            reported.any { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-SCHEDULE-005'
                           && it.problem().whatHappened().contains('pi: command not found') }
            session.alive
    }

    def '`oillamp ask` prints the agent\'s answer with its terminal escape sequences made harmless'() {
        reportInfo """
            `oillamp ask` prints the agent's answer in the terminal it was run from, on the host. A
            terminal obeys escape sequences in what it prints: they can clear the screen, rewrite
            lines, or change the window's title. So oillamp makes such sequences harmless before it
            prints the answer, as it does with the output of any program in the sandbox.
        """
        given: 'an agent whose answer holds the escape sequences that clear the screen and set the window title'
            var lamp = aLampThatHasRun('')
            anAgent { prompt -> 'Done.\u001B[2J\u001B]0;owned\u0007 All fine.' }
            startASession(lamp)

        when: '`oillamp ask` sends a question'
            var asked = host.oillamp.run('ask', lamp.toString(), 'Anything')

        then: 'the console shows the answer\'s text, without either sequence'
            asked.succeeded()
            asked.console().contains('Done.')
            !asked.console().contains('\u001B[2J')
            !asked.console().contains('\u001B]0;')
    }

    def '`oillamp ask` receives a long answer in any language exactly as the agent wrote it'() {
        reportInfo """
            The session sends the answer to `oillamp ask` over its control socket, which hands it
            over in pieces of 64 kB. A character such as € takes three bytes in UTF-8, and a piece
            can end in the middle of one. Oillamp joins the pieces before it decodes them as text,
            so no character is broken at such a seam.
        """
        given: 'answers of 90 kB of euro signs, after one, two and three spaces, so that one of them has a seam inside a €'
            var lamp = aLampThatHasRun('')
            var answers = (1..3).collect { spaces -> 'Kosten:' + ' ' * spaces + '€' * 30_000 }
            anAgent { prompt -> answers[prompt.toInteger()] }
            startASession(lamp)

        when: '`oillamp ask` asks for each of the three answers'
            var heard = (0..2).collect { which ->
                host.oillamp.run('ask', lamp.toString(), which.toString())
                        .events().find { it instanceof LampEvent.RunFinished }.answer()
            }

        then: 'each answer arrived exactly as the agent wrote it'
            heard == answers
    }

    def 'Oillamp warns once with `OIL-SCHEDULE-002` about a schedule file that is not valid JSON, and says when it can be read again'() {
        reportInfo """
            Oillamp keeps a lamp's jobs in `.oillamp/schedule.json`. Oillamp writes it, but someone
            may edit it by hand, and a typo can leave text that is not valid JSON: here,
            `{ not json`. Oillamp cannot tell which jobs such a file holds, so it runs none of them
            until the file is fixed, and warns with `OIL-SCHEDULE-002`.

            A running session reads the file every 30 seconds, and whenever someone changes the
            schedule. The same warning at every reading would bury everything else the session
            reports, so the session warns once. When the file is valid JSON again, the session says
            that the schedule can be read again.
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

        when: '`{ not json` is written into the file, and the session reads it four times'
            Files.writeString(file, '{ not json')
            readNow()
            waitFor(LampEvent.Warning) { it.problem().code().value() == 'OIL-SCHEDULE-002' }
            3.times { readNow(); Thread.sleep(300) }

        then: 'the session warned with `OIL-SCHEDULE-002` once'
            warnings() == 1

        when: '`{}`, an empty schedule, is written into the file'
            Files.writeString(file, '{}')
            readNow()

        then: 'the session says "the schedule can be read again", and has warned no more'
            waitFor(LampEvent.Ok) { it.text() == 'the schedule can be read again' }
            warnings() == 1
    }

    // ─── the agent's side ──────────────────────────────────────────────────────────────────

    def 'The agent adds and removes its own jobs over the session\'s schedule socket, within the limits in `oillamp.toml`'() {
        reportInfo """
            The agent's scheduling tools do not write the schedule file. They send a request over
            the session's schedule socket, and the session applies the limits set in
            `oillamp.toml`: the agent may have at most `schedule.max_agent_jobs` jobs, none of them
            may run more often than every `schedule.min_agent_interval_minutes`, and each ends after
            `schedule.max_agent_days`. The agent can list the user's jobs, but not remove them. A
            refusal states the limit, so that the agent can change its request.

            The scenario sends the requests as the agent's tools do, on a lamp that allows the
            agent one job, and that already has a daily job the user added.
        """
        given: 'a lamp whose agent may have one job, with a daily job the user added'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\nmax_agent_jobs = 1\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'The user\'s own job')
            startASession(lamp)

        when: 'the agent asks for a job every five minutes, an hourly job and a one-time job, to remove the user\'s `job-1`, and for the list of jobs'
            var tooOften = agentAsks(op: 'add', cron: '*/5 * * * *', prompt: 'Check the build')
            var hourly = agentAsks(op: 'add', cron: '0 * * * *', prompt: 'Check the build')
            var oneTooMany = agentAsks(op: 'add', at: 'in 2h', prompt: 'Another one')
            var usersJob = agentAsks(op: 'remove', id: 'job-1')
            var listed = agentAsks(op: 'list')

        then: 'only the hourly job is added, as `job-2`; each refusal names its limit; the list says whose each job is'
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

    def 'Oillamp refuses the agent a job that runs too often, also when its frequent runs are days away'() {
        reportInfo """
            `schedule.min_agent_interval_minutes`, 15 by default, is the shortest time the agent's
            own jobs may leave between two runs. Let's consider an agent that asks, on Tuesday
            22 September, for `*/5 0 1 * *`: every five minutes, but only in the first hour of the
            first day of a month. The next week holds no run of it at all, so a check that only
            looked at the coming days would let it through, and the agent would get a job that
            runs every five minutes whenever the month turns.

            So oillamp looks at every run the job could make before it ends. The agent's jobs end
            after `schedule.max_agent_days`, 14 by default, which from 22 September includes
            1 October, and oillamp refuses the job, saying what the rule is.
        """
        given: 'a lamp whose schedule is on, on Tuesday 22 September'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            startASession(lamp)

        when: 'the agent asks for a job that runs every five minutes, just after midnight on the 1st'
            var asked = agentAsks(op: 'add', cron: '*/5 0 1 * *', prompt: 'Close the month')

        then: 'oillamp refuses it, naming the shortest gap allowed'
            !asked.ok
            asked.error.contains('may run at most every 15 minutes')
    }

    def 'Oillamp ends the agent\'s jobs within `schedule.max_agent_days`, whatever end or time the agent asks for'() {
        reportInfo """
            The agent can add jobs to the lamp's schedule through the session. Each of its jobs ends after
            `schedule.max_agent_days` at the latest: 14 days, unless the user set another number. Without
            that limit an agent could schedule work for itself months ahead, and every run would cost the
            user model tokens.

            The agent can ask for something past 14 days in two ways, and oillamp handles both:

            - It asks for a repeating job that ends in 30 days. Oillamp adds the job, but sets its end to
              14 days from now.
            - It asks for a single run in 20 days. Oillamp refuses, and the refusal says "at most 14 days
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
            any. Questions sent with `oillamp ask` are answered as usual, paused or not. Let's
            consider someone who pauses the schedule for a few minutes, during which a job added
            with `--at` comes due.

            A one-time job keeps its time through a pause: it has no later time it could wait for.
            When the schedule is resumed, the job is due, and five minutes late, which
            `schedule.max_delay_minutes` (30 by default) allows, so it runs at once. A repeating
            job is different: resuming counts it as if it had just run, so it waits for its next
            time.

            A running session checks the schedule as it starts, every 30 seconds after that, and
            whenever someone changes the schedule. In the scenario the job's time is five minutes
            before the session starts, so the job is due when the session first checks. The
            schedule is paused, so the job must not run: the question sent next must be the first
            and only prompt the agent receives. Then `oillamp schedule resume` resumes the
            schedule, the session checks again, and the job runs.
        """
        given: 'a job that was due five minutes before the session starts, on a paused schedule'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 5m', 'Write the weekly report')
            host.oillamp.run('schedule', lamp.toString(), 'pause')
            anAgent { prompt -> 'Done.' }
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)

        when: '`oillamp ask` sends "Are you there?" while the schedule is paused'
            assert host.oillamp.run('ask', lamp.toString(), 'Are you there?').succeeded()

        then: 'only the question reached the agent'
            prompts == ['Are you there?']

        when: '`oillamp schedule resume` resumes the schedule'
            host.oillamp.run('schedule', lamp.toString(), 'resume')
            var ran = waitFor(LampEvent.RunFinished) { it.run().job() == Optional.of('job-1') }

        then: 'the one-time job that waited runs now'
            ran.outcome() == LampRunOutcome.FINISHED
            prompts.size() == 2
            prompts[1].contains('Write the weekly report')
    }

    def 'With `schedule.max_agent_runs_per_day = 0`, Oillamp skips the agent\'s due job, and says why'() {
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

        then: 'the session reports that `job-1` was skipped, and names `schedule.max_agent_runs_per_day`'
            skipped.text().contains('job-1 was due')
            skipped.text().contains('schedule.max_agent_runs_per_day')

        and: 'the agent was not woken'
            prompts.isEmpty()
            !reported.any { it instanceof LampEvent.RunStarted }
    }

    def 'The agent reads an earlier run from the history on the host with its `run_history` tool'() {
        reportInfo """
            A job's prompt describes only the last few runs. To look further back, the agent's
            `run_history` tool asks the session for a run by name, such as `run-1`, and gets what
            the run changed and what the agent said at its end. The session reads both from the
            lamp's history on the host, which the agent cannot see or change. Without a name, the
            tool lists the runs.
        """
        given: 'a session in which `oillamp ask` sent a question, and the agent wrote `answer.txt`'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n[autosave]\nchat_quiet_minutes = 0\n')
            anAgent { prompt ->
                Files.writeString(home(lamp).resolve('workspace/answer.txt'), '42\n')
                'The answer is in answer.txt.'
            }
            startASession(lamp)
            host.oillamp.run('ask', lamp.toString(), 'Find the answer')

        when: 'the agent asks for all runs, for `run-1`, and for `run-7`, which does not exist'
            var all = agentAsks(op: 'history')
            var one = agentAsks(op: 'history', run: 'run-1')
            var none = agentAsks(op: 'history', run: 'run-7')

        then: 'the list names `run-1`; `run-1` added `answer.txt` and ended with the agent\'s answer; of `run-7` the session says there is no such run'
            all.text.contains('run-1')
            one.text.contains('~/workspace/answer.txt (added)')
            one.text.contains('The answer is in answer.txt.')
            none.text.contains('There is no run called \'run-7\'')
    }

    def 'With the schedule on, `AGENTS.md` tells the agent how scheduling works, and pi gets the scheduling tools'() {
        reportInfo """
            `AGENTS.md`, in the agent's home, is what the agent reads about the machine it is on.
            With the schedule on, oillamp adds a section on working on a schedule: what the
            scheduling tools do, which limits apply, and that the agent's notes,
            `~/workspace/NOTES.md`, are its only memory between runs. The tools themselves are a pi
            extension, which oillamp puts into pi's directory as each session starts, and removes
            when the schedule is off.
        """
        given: 'a lamp whose schedule is on, which allows the agent three jobs, after one session'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\nmax_agent_jobs = 3\n')
            var guide = Files.readString(home(lamp).resolve('AGENTS.md'))
            var tools = home(lamp).resolve('.pi/agent/extensions/oillamp-schedule.js')

        expect: '`AGENTS.md` has the section `## Working on a schedule`, names the limit of 3 jobs and the notes, and the extension talks to the schedule socket'
            guide.contains('## Working on a schedule')
            guide.contains('at most 3 jobs at once')
            guide.contains('~/workspace/NOTES.md')
            Files.readString(tools).contains('/oillamp/sockets/host/schedule.sock')

        when: 'the schedule is switched off in `oillamp.toml`, and `oillamp at` runs a session'
            Files.writeString(lamp.resolve('oillamp.toml'),
                    Files.readString(lamp.resolve('oillamp.toml')).replace('enabled = true', 'enabled = false'))
            host.oillamp.run('at', lamp.toString())

        then: 'the section and the extension are gone'
            !Files.readString(home(lamp).resolve('AGENTS.md')).contains('## Working on a schedule')
            !Files.exists(tools)
    }

    def 'A program built on oillamp schedules jobs and asks the agent through the `Lamp` API'() {
        reportInfo """
            A program built on oillamp, such as a desktop app, offers what `oillamp schedule` and
            `oillamp ask` do with its own controls, through the `Lamp` API. `repeat` and `once` add
            jobs, `schedule` lists them, `unschedule` removes one, `disable` and `enable` switch one
            off and on, and `pause` and `resume` act on the whole schedule. In a running session,
            `ask` wakes the agent and returns a `RunFinished` event with the agent's answer, the
            run's outcome and the snapshot that saved the run.
        """
        given: 'a lamp whose schedule is on, and a `Lamp` for it'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n[autosave]\nchat_quiet_minutes = 0\n')
            anAgent { prompt -> 'Hello from the sandbox.' }
            var lamps = Lamp.at(lamp).launchedBy(host.launcher)

        when: 'the program adds, lists, switches off and on, pauses, resumes and removes jobs, then starts a session and asks "Say hello"'
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

        then: 'each call did what it names, and `ask` returned the agent\'s answer with a snapshot'
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

        and: 'the run happened in the session the program started'
            reported.any { it instanceof LampEvent.RunStarted && it.run().prompt() == 'Say hello' }
    }

    // ─── run numbers ───────────────────────────────────────────────────────────────────────

    def 'Oillamp gives a question sent with `oillamp ask` its own number while the run before it has no snapshot yet'() {
        reportInfo """
            Every run has a name, such as `run-12`. The name is in the run's snapshots, in the
            name of its pi conversation, and in `oillamp history`, so two runs must never share
            one.

            Oillamp takes the next number from the lamp's history. Each snapshot of a run carries
            the run's name in a trailer, a `key: value` line at the end of the snapshot's commit
            message, such as `Oillamp-Run: run-12`. The next run gets the number one above the
            highest. But a run is numbered when it is queued, and its snapshots are made later:
            the `BEFORE_RUN` snapshot as it begins, when `autosave.before_run` is on and something
            changed, and the `RUN` snapshot as it ends. So while the first run of a lamp works, the
            history may name no run yet, and a question sent at that moment would be named `run-1`
            as well. Oillamp therefore also remembers the highest number the session has given out,
            and takes the higher of the two.

            The scenario makes that moment certain with `autosave.before_run = false`, so that a
            run's only snapshot is made as it ends. While the agent works on a first question, a
            second is sent with `oillamp ask`, and waits. Its `RunQueued` event must name it
            `run-2`, and the history must hold one `RUN` snapshot for each of the two.
        """
        given: 'a session whose agent keeps working on "slow" until it is let go, on a lamp that saves runs only as they end'
            var lamp = aLampThatHasRun('[autosave]\nbefore_run = false\nchat_quiet_minutes = 0\n')
            var release = new CountDownLatch(1)
            anAgent { prompt ->
                if (prompt == 'slow') release.await()
                'answered: ' + prompt
            }
            startASession(lamp)

        when: '`oillamp ask` sends a second question while the agent works on the first'
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

        and: 'the history has one `RUN` snapshot for each'
            var snapshots = host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots()
            snapshots.findAll { it.kind() == LampSaveKind.RUN }*.run()*.get().sort() == ['run-1', 'run-2']
    }

    def 'Oillamp numbers the runs of a session one after another when it saves none of them'() {
        reportInfo """
            Oillamp takes a run's number from the lamp's history. Each snapshot of a run carries
            the run's name in a `key: value` line at the end of the snapshot's commit message, such
            as `Oillamp-Run: run-12`, and the next run gets the number one above the highest. With
            `autosave.before_run = false` and `autosave.after_run = false`, a run makes no
            snapshot, so the history never names one. The session still remembers the numbers it
            has given out, and the next run gets the number above the highest of them.

            The scenario sends two questions with `oillamp ask`, one after the other. The first
            must be `run-1` and the second `run-2`, and the history stays empty.
        """
        given: 'a session on a lamp that saves nothing around runs'
            var lamp = aLampThatHasRun('[autosave]\nbefore_run = false\nafter_run = false\n')
            anAgent { prompt -> 'Fine.' }
            startASession(lamp)

        when: '`oillamp ask` sends two questions, one after the other'
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

        when: '`oillamp schedule` lists the schedule'
            var listed = host.oillamp.run('schedule', lamp.toString())

        then: 'oillamp reads it without a warning'
            listed.succeeded()
            !listed.reported('OIL-SCHEDULE-002')
            listed.events().find { it instanceof LampEvent.Schedule }.jobs()*.id() == ['job-1']

        when: '`oillamp ask` sends a question in a session'
            startASession(lamp)
            var asked = host.oillamp.run('ask', lamp.toString(), 'Hello?')

        then: 'the run is numbered from the history, which names no run yet'
            asked.events().find { it instanceof LampEvent.RunFinished }.run().id() == 'run-1'

        when: '`oillamp schedule add` adds a job'
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
            17:00. Oillamp does not catch up on the missed run. A program built on oillamp that
            receives the `RunMissed` event can still send the job's prompt with `Lamp.ask`, if it
            decides the run is worth making.

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

    /** Takes the write permission from `directory` and everything in it; the closure returned gives it back. */
    private static Closure readOnly(Path directory) {
        Map<Path, Set<PosixFilePermission>> was = [:]
        Files.walk(directory).withCloseable { it.toList() }.findAll { !Files.isSymbolicLink(it) }.each { path ->
            was[path] = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
            Files.setPosixFilePermissions(path, was[path] - [PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE])
        }
        return { was.each { path, permissions -> Files.setPosixFilePermissions(path, permissions) } }
    }

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
