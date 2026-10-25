package oillamp

import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampExitStatus
import dev.lamp.LampFailedException
import dev.lamp.LampSaveKind
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 *  {@code branches}, {@code branch}, {@code switch} and {@code reset}: several states of one lamp.
 *
 *  <p>A lamp has one agent home and runs one sandbox at a time. A user may still want to keep
 *  several states of it side by side: let the agent try a rewrite in one, keep the original in
 *  another, and go back and forth. Each such state is a branch of the lamp's history: a line of
 *  snapshots of its own, each holding the agent's home, {@code oillamp.toml} and the schedule. The
 *  lamp is on one branch at a time, and every save goes to that one.
 *
 *  <p>The files are real, in a temporary directory, and so is the history: a git repository that
 *  git itself can read, which some scenarios check with git where it is installed.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class KeepingSeveralStatesOfALampSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

    /** A Tuesday afternoon: 16:15 in Berlin. */
    static final Instant NOW = Instant.parse('2026-09-22T14:15:03Z')

    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    final List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread session

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

    // ─── starting a branch ─────────────────────────────────────────────────────────────────

    def 'Oillamp starts a branch at the snapshot it is given, and leaves the lamp on its branch'() {
        reportInfo """
            A lamp's history is a line of snapshots, each holding the agent's home, `oillamp.toml`
            and the schedule as they were at one moment. A branch is such a line of its own. A lamp
            starts with one branch, `main`; it is on one branch at a time, and every save goes to
            that branch. A branch's newest snapshot is the last one saved on it.

            Let's consider a user who saved a lamp twice: once with `plan.md` in its first version,
            and once with its second. They want to keep the second, and also try something else
            from the first. `oillamp history <dir> branch try-again <snapshot>` starts a branch
            called `try-again` whose newest snapshot is the first one. Starting a branch does not
            move the lamp to it: the files stay as they are, `main` stays the branch the lamp is
            on, and `oillamp history <dir> branches` lists both branches with their newest
            snapshots. Without a snapshot, `branch` starts at the newest snapshot of the branch
            the lamp is on.
        """
        given: 'a lamp saved with the first version of the plan, and again with the second'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'version one\n')
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'version two\n')
            var second = saved(host.oillamp.run('history', lamp.toString(), 'save'))

        when: 'the user starts a branch at the first snapshot'
            var started = host.oillamp.run('history', lamp.toString(), 'branch', 'try-again', first.shortId())

        then: 'its newest snapshot is the first one, and the lamp is not on it'
            started.succeeded()
            var created = started.events().find { it instanceof LampEvent.BranchCreated }.branch()
            created.name() == 'try-again'
            created.newest().get().id() == first.id()
            !created.current()
            started.console().contains('oillamp history ' + lamp + ' switch try-again')

        and: 'the files are as they were'
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'version two\n'

        when: 'the user starts another branch without naming a snapshot'
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'keep').succeeded()

        then: 'it starts at the newest snapshot of main, and main is still the branch the lamp is on'
            var branches = branchesOf(lamp)
            branches.collect { [it.name(), it.current(), it.newest().get().id()] } == [
                    ['keep', false, second.id()],
                    ['main', true, second.id()],
                    ['try-again', false, first.id()]]
    }

    def 'Oillamp lists every branch, and marks the one the lamp is on'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one branch at a
            time, and every save goes to it. `oillamp history <dir> branches` lists every branch
            by name, in alphabetical order, with its newest snapshot, the last one saved on it, and
            marks the branch the lamp is on with `*`. A lamp that was never saved is on `main`, which
            has no snapshot yet.
        """
        given: 'a lamp that was never saved'
            var lamp = aLampThatHasRun()

        expect: 'it is on main, which has no snapshot yet'
            var empty = host.oillamp.run('history', lamp.toString(), 'branches')
            empty.succeeded()
            empty.events().find { it instanceof LampEvent.Branches }.branches()*.name() == ['main']
            empty.console().contains('* main')
            empty.console().contains('(no snapshot yet)')

        when: 'the lamp is saved, and a branch is started'
            var newest = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            var listed = host.oillamp.run('history', lamp.toString(), 'branches')

        then: 'both are listed with their newest snapshot, and main is marked'
            listed.succeeded()
            listed.console().contains(String.format('* %-17s %s', 'main', newest.shortId()))
            listed.console().contains(String.format('  %-17s %s', 'rust-rewrite', newest.shortId()))
    }

    def 'Oillamp refuses a branch name it cannot keep: "#name"'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history, and its name is the name of a
            file in that history, as git keeps it, so it is limited to what is safe as a file name
            and what git accepts. `oillamp history <dir> branch <name>` takes a name made of lowercase
            letters, digits, `-`, `_` and `.`, that begins with a letter or a digit, is at most 64
            characters long, and neither holds `..` nor ends in `.` or `.lock`. Lowercase only, so
            that no two branches differ by case alone, which some file systems cannot tell apart.
            Oillamp refuses any other name as a mistake on the command line, `OIL-USAGE-001`, with
            the rule, before it looks at the lamp, and starts no branch.
        """
        given:
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when:
            var outcome = host.oillamp.run('history', lamp.toString(), 'branch', '--', name)

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-USAGE-001')
            outcome.errors().first().whatHappened().contains(why)
            branchesOf(lamp)*.name() == ['main']

        where:
            name             | why
            'Rust'           | 'lowercase letters, digits'
            'HEAD'           | 'lowercase letters, digits'
            '-rust'          | 'begins with a letter or a digit'
            '.rust'          | 'begins with a letter or a digit'
            'rust/rewrite'   | 'lowercase letters, digits'
            'rust rewrite'   | 'lowercase letters, digits'
            'rust..rewrite'  | "cannot hold '..'"
            'rust.'          | "end in '.' or '.lock'"
            'rust.lock'      | "end in '.' or '.lock'"
            'r' * 65         | 'at most 64 characters long'
    }

    def 'Oillamp refuses to start a branch under a name the history already has'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history, known by its name. Starting a
            branch under a name the history has already would move that branch to another snapshot,
            and the snapshots on it would no longer be on it. So
            `oillamp history <dir> branch <name>` refuses a name that is taken, `main` included,
            with `OIL-HISTORY-010`, and the branch stays as it was.
        """
        given: 'a lamp with the branch rust-rewrite, started at an earlier snapshot'
            var lamp = aLampThatHasRun()
            var earlier = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/later.txt', 'later\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when:
            var again = host.oillamp.run('history', lamp.toString(), 'branch', name)

        then:
            again.status() == LampExitStatus.USAGE
            again.reported('OIL-HISTORY-010')
            branchesOf(lamp).find { it.name() == 'rust-rewrite' }.newest().get().id() == earlier.id()

        where:
            name << ['rust-rewrite', 'main']
    }

    // ─── saving and switching ──────────────────────────────────────────────────────────────

    def 'Oillamp saves to the branch the lamp is on, and to no other'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history. The lamp is on one branch at a
            time, and every save, whether by `oillamp history <dir> save`, around a run, or as a
            session starts or ends, adds a snapshot to that branch only. The newest snapshot of
            every other branch stays the same.

            The scenario starts the branch `experiment` at the newest snapshot of `main`, saves a
            change while the lamp is on `main`, then switches to `experiment` with
            `oillamp history <dir> switch experiment` and saves a change there.
        """
        given: 'a lamp on main, and the branch experiment started at its newest snapshot'
            var lamp = aLampThatHasRun()
            var start = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'experiment').succeeded()

        when: 'the user saves a change on main'
            write(home(lamp), 'workspace/main.txt', 'on main\n')
            var onMain = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'on main'))

        then: 'main has the new snapshot, and experiment still ends where it started'
            newestOf(lamp, 'main').id() == onMain.id()
            newestOf(lamp, 'experiment').id() == start.id()

        when: 'the user switches to experiment and saves a change there'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'experiment').succeeded()
            write(home(lamp), 'workspace/experiment.txt', 'on experiment\n')
            var onExperiment = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'on experiment'))

        then: 'experiment has it, and main is as it was'
            newestOf(lamp, 'experiment').id() == onExperiment.id()
            newestOf(lamp, 'main').id() == onMain.id()

        and: '`oillamp history <dir>` lists the snapshots of experiment, which the lamp is on'
            var listed = host.oillamp.run('history', lamp.toString())
            listed.events().find { it instanceof LampEvent.History }.branch() == 'experiment'
            listed.events().find { it instanceof LampEvent.History }.snapshots()*.id() == [onExperiment.id(), start.id()]
            listed.console().contains('on the branch experiment')
    }

    def 'Oillamp\'s `switch` brings back the other branch\'s home, oillamp.toml and jobs'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history, each holding the agent's home,
            `oillamp.toml` and the schedule, the file `.oillamp/schedule.json` with the jobs that
            wake the agent. The lamp is on one branch at a time. A branch's newest snapshot is the
            last one saved on it.

            Let's consider a user who lets the agent try a Rust rewrite on a branch of its own,
            `rust-rewrite`, and keeps the original on `main`. On `main` they add a job and change
            `oillamp.toml` after the branch was started. `oillamp history <dir> switch rust-rewrite`
            must bring the lamp to that branch's newest snapshot: the home, `oillamp.toml` and the
            jobs as they were there. Switching back to `main` must bring back `main`'s.
            Switching adds no snapshot to the branch switched to.
        """
        given: 'a lamp on main with a story, a job and its oillamp.toml, saved'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/story.txt', 'chapter one\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')
            var config = lamp.resolve('oillamp.toml')
            var original = Files.readString(config)
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        and: 'the branch rust-rewrite, on which the agent rewrote the story'
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite').succeeded()
            Files.writeString(home(lamp).resolve('workspace/story.txt'), 'chapter one, in Rust\n')
            var rust = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'in Rust'))

        and: 'back on main, a second chapter, a second job and a changed oillamp.toml, saved'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()
            Files.writeString(home(lamp).resolve('workspace/story.txt'), 'chapter two\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')
            Files.writeString(config, original + '\n# changed on main\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when: 'the user switches to rust-rewrite'
            var switched = host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite')

        then: 'the home, oillamp.toml and the jobs are as rust-rewrite holds them'
            switched.succeeded()
            switched.events().find { it instanceof LampEvent.Switched }.to().name() == 'rust-rewrite'
            Files.readString(home(lamp).resolve('workspace/story.txt')) == 'chapter one, in Rust\n'
            Files.readString(config) == original
            jobsOf(lamp)*.prompt() == ['Tidy up']

        and: 'no snapshot was added to rust-rewrite'
            newestOf(lamp, 'rust-rewrite').id() == rust.id()

        when: 'the user switches back to main'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()

        then: 'main\'s home, oillamp.toml and jobs are back'
            Files.readString(home(lamp).resolve('workspace/story.txt')) == 'chapter two\n'
            Files.readString(config).endsWith('# changed on main\n')
            jobsOf(lamp)*.prompt() == ['Tidy up', 'Look at the queue']
    }

    def 'Oillamp saves what changed on the branch it leaves before it switches'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            `oillamp history <dir> switch <name>` replaces the agent's home with what the other
            branch holds. A change the user made since the last save would be lost by that, so
            oillamp first saves the lamp on the branch it leaves, as a safety save before a switch
            (`LampSaveKind.BEFORE_SWITCH`), and only then switches. Switching back brings the change
            back, because it is the newest snapshot of that branch.
        """
        given: 'a lamp on main with the branch other, and a change on main that was never saved'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            write(home(lamp), 'workspace/draft.txt', 'not saved yet\n')

        when: 'the user switches to other'
            var switched = host.oillamp.run('history', lamp.toString(), 'switch', 'other')

        then: 'main was saved first, with the change'
            switched.succeeded()
            var safety = saved(switched)
            safety.kind() == LampSaveKind.BEFORE_SWITCH
            newestOf(lamp, 'main').id() == safety.id()
            !Files.exists(home(lamp).resolve('workspace/draft.txt'))
            switched.console().contains('oillamp history ' + lamp + ' switch main')

        when: 'the user switches back'
            var back = host.oillamp.run('history', lamp.toString(), 'switch', 'main')

        then: 'the change is there again, and other had nothing to save'
            back.succeeded()
            !back.events().any { it instanceof LampEvent.Saved }
            Files.readString(home(lamp).resolve('workspace/draft.txt')) == 'not saved yet\n'
    }

    def 'Oillamp\'s `switch` to the branch the lamp is on changes nothing, and says so'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            `oillamp history <dir> switch main` on a lamp that is on `main` already has nothing to
            do: oillamp saves nothing, leaves the files as they are, and says that nothing changed.
        """
        given:
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            write(home(lamp), 'workspace/draft.txt', 'not saved yet\n')

        when:
            var outcome = host.oillamp.run('history', lamp.toString(), 'switch', 'main')

        then:
            outcome.succeeded()
            !outcome.events().any { it instanceof LampEvent.Saved }
            outcome.console().contains('the lamp already is on main, so nothing changed')
            Files.readString(home(lamp).resolve('workspace/draft.txt')) == 'not saved yet\n'
    }

    def 'Oillamp refuses to switch to a branch the history does not have'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history, known by its name.
            `oillamp history <dir> switch <name>` with a name no branch has is refused with
            `OIL-HISTORY-009`, which lists the branches there are, and the lamp stays as it was.
        """
        given:
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when:
            var outcome = host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite')

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-HISTORY-009')
            outcome.errors().first().whatHappened().contains("no branch called 'rust-rewrite'; it has main")
            branchesOf(lamp).find { it.current() }.name() == 'main'
    }

    def 'Oillamp refuses to #action while a session runs'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            `oillamp history <dir> switch` and `oillamp history <dir> reset` both rewrite the agent's
            home, and programs running in the sandbox would go on working in files that were
            replaced under them. So, like a restore, both are refused while a session runs, with
            `OIL-HISTORY-005` and the exit status `LAMP_BUSY`, and the lamp stays on its branch with
            its files as they are.
        """
        given: 'a lamp with two snapshots on main, the branch other, and a running session'
            var lamp = aLampThatHasRun()
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/later.txt', 'later\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other', first.shortId()).succeeded()
            startASession(lamp)

        when:
            var outcome = host.oillamp.run(*(['history', lamp.toString()] + words(first)))

        then:
            outcome.status() == LampExitStatus.LAMP_BUSY
            outcome.reported('OIL-HISTORY-005')
            outcome.errors().first().title() == 'Stop the session before you ' + action
            Files.readString(home(lamp).resolve('workspace/later.txt')) == 'later\n'
            branchesOf(lamp).find { it.current() }.name() == 'main'

        where:
            action   | words
            'switch' | { snapshot -> ['switch', 'other'] }
            'reset'  | { snapshot -> ['reset', snapshot.shortId(), '--yes'] }
    }

    def 'Oillamp starts a branch while a session runs'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            Starting a branch only writes the new branch's file into the history, which no session
            reads, and leaves the lamp on its branch. So a user can save while the session runs and
            start a branch at that snapshot, to come back to it later, without stopping the agent.
        """
        given:
            var lamp = aLampThatHasRun()
            startASession(lamp)
            var running = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'before the risky part'))

        when:
            var started = host.oillamp.run('history', lamp.toString(), 'branch', 'before-risk')

        then:
            started.succeeded()
            newestOf(lamp, 'before-risk').id() == running.id()
            branchesOf(lamp).find { it.current() }.name() == 'main'
    }

    // ─── restore and reset stay on their branch ────────────────────────────────────────────

    def 'Oillamp refuses to restore a snapshot of another branch, and says how to reach it'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            `oillamp history <dir> restore <snapshot>` goes back along the branch the lamp is on. A
            snapshot that only another branch holds belongs to another state of the lamp, and
            restoring it would put that state on top of this one's line. So oillamp refuses it with
            `OIL-HISTORY-008`, names the branch it is on, and says how to reach it: start a branch
            at it with `oillamp history <dir> branch <name> <snapshot>`, then switch to that branch.
            Nothing is saved, and the files stay as they are.
        """
        given: 'a lamp with a snapshot on the branch rust-rewrite only, and the lamp on main'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.rs', 'fn main() {}\n')
            var rust = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()
            var before = newestOf(lamp, 'main')

        when:
            var outcome = host.oillamp.run('history', lamp.toString(), 'restore', rust.shortId())

        then: 'oillamp refuses, and says how to reach the snapshot'
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-HISTORY-008')
            outcome.errors().first().whatHappened().contains('is on the branch rust-rewrite, not on main')
            outcome.console().contains('oillamp history ' + lamp + ' branch <name> ' + rust.shortId())
            outcome.console().contains('oillamp history ' + lamp + ' switch <name>')

        and: 'nothing was saved or changed'
            newestOf(lamp, 'main').id() == before.id()
            !Files.exists(home(lamp).resolve('workspace/main.rs'))
    }

    def 'Oillamp\'s `reset` without --yes changes nothing, and says what would be lost'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            `oillamp history <dir> reset <snapshot>` is a hard reset, as `git reset --hard` is: it
            brings the lamp back to a snapshot on its branch and moves the branch back to it, so
            the snapshots made after it are no longer on the branch, and changes not yet saved are
            thrown away. Nothing is saved first.

            Because it throws work away, it asks first, as `oillamp remove` does: without `--yes`
            it changes nothing, says how many snapshots would leave the branch and whether there
            are unsaved changes, prints the command with `--yes`, and exits with `USAGE`.
        """
        given: 'three snapshots on main, and a change that was never saved'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'one\n')
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'two\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            write(home(lamp), 'workspace/plan.md', 'three\n')
            var third = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'four, not saved\n')

        when:
            var outcome = host.oillamp.run('history', lamp.toString(), 'reset', first.shortId())

        then: 'it says what would be lost'
            outcome.status() == LampExitStatus.USAGE
            var planned = outcome.events().find { it instanceof LampEvent.ResetPlanned }
            planned.target().id() == first.id()
            planned.snapshots() == 2
            planned.unsaved()
            outcome.console().contains('would take 2 snapshots off the branch, and throw away changes no snapshot holds')
            outcome.console().contains('oillamp history ' + lamp + ' reset ' + first.shortId() + ' --yes')

        and: 'nothing changed'
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'four, not saved\n'
            newestOf(lamp, 'main').id() == third.id()
    }

    def 'Oillamp\'s `reset --yes` takes the later snapshots off the branch and throws away unsaved changes'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            `oillamp history <dir> reset <snapshot> --yes` brings the agent's home, `oillamp.toml`
            and the schedule back to that snapshot, which must be on the branch the lamp is on, and
            moves the branch back to it. The snapshots made after it are no longer on the branch:
            `oillamp history <dir>` no longer lists them. A change that was never saved is gone.
            No snapshot is made, neither before nor after.

            The snapshots that left the branch are not deleted at once. Each is a file in the
            history's `objects` directory, as git keeps it, and stays there until oillamp removes
            what no branch leads to.
        """
        given: 'three snapshots on main, the last with a job, and a change that was never saved'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'one\n')
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'two\n')
            var second = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')
            var third = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/unsaved.txt', 'never saved\n')

        when:
            var outcome = host.oillamp.run('history', lamp.toString(), 'reset', first.shortId(), '--yes')

        then: 'the lamp is as the first snapshot holds it'
            outcome.succeeded()
            var done = outcome.events().find { it instanceof LampEvent.ResetDone }
            done.snapshots() == 2
            done.unsaved()
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'one\n'
            !Files.exists(home(lamp).resolve('workspace/unsaved.txt'))
            jobsOf(lamp).isEmpty()

        and: 'nothing was saved, and the branch ends at the first snapshot'
            !outcome.events().any { it instanceof LampEvent.Saved }
            host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots()*.id() == [first.id()]
            host.oillamp.run('history', lamp.toString(), 'list', '--all').events()
                    .find { it instanceof LampEvent.HistoryOfAllBranches }.snapshots()*.snapshot()*.id() == [first.id()]

        and: 'the later snapshots are still files in the history'
            [second, third].every { Files.exists(objectFile(lamp, it.id())) }
    }

    def 'Oillamp refuses a hard reset it cannot read completely, so it never claims to discard work it kept'() {
        reportInfo """
            A hard reset throws away unsaved changes and brings the home back to a snapshot.
            Before doing that, oillamp reads the current home to find what must be replaced or
            removed. If a directory cannot be read, that picture is incomplete: extra files inside
            it would be invisible, and restoring the saved files alone would leave them behind.

            Let's consider a saved directory to which the user added an unsaved draft, then took
            away all permissions. Both the reset preview and the confirmed reset must refuse with
            `OIL-HISTORY-003`, name the unreadable directory, and leave the branch and home alone.
            Once the user makes the directory readable, resetting works and removes the draft.
            Oillamp must never report that unsaved work is gone while silently keeping it.
        """
        given: 'a saved directory with an unsaved draft, which the user made unreadable'
            var lamp = aLampThatHasRun()
            var directory = home(lamp).resolve('workspace/private')
            write(home(lamp), 'workspace/private/saved.txt', 'saved\n')
            var snapshot = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/private/draft.txt', 'not saved\n')
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString('---------'))
            var before = Files.readString(lamp.resolve('.oillamp/history/refs/heads/main'))

        when: 'the user previews the reset and then tries to confirm it'
            var preview = host.oillamp.run('history', lamp.toString(), 'reset', snapshot.id())
            var confirmed = host.oillamp.run('history', lamp.toString(), 'reset', snapshot.id(), '--yes')

        then: 'both refuse, explain which directory could not be read, and report no completed reset'
            [preview, confirmed].every {
                it.status() == LampExitStatus.ERROR && it.reported('OIL-HISTORY-003') &&
                        it.console().contains('workspace/private') &&
                        !it.events().any { event -> event instanceof LampEvent.ResetDone || event instanceof LampEvent.ResetPlanned }
            }

        and: 'the branch and the directory permissions are unchanged'
            Files.readString(lamp.resolve('.oillamp/history/refs/heads/main')) == before
            Files.getPosixFilePermissions(directory) == PosixFilePermissions.fromString('---------')

        when: 'the user restores access to the directory'
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString('rwxr-xr-x'))

        then: 'the refused resets left both files intact'
            Files.readString(directory.resolve('saved.txt')) == 'saved\n'
            Files.readString(directory.resolve('draft.txt')) == 'not saved\n'

        when: 'the user confirms the reset again'
            var retried = host.oillamp.run('history', lamp.toString(), 'reset', snapshot.id(), '--yes')

        then: 'it succeeds and discards the unsaved draft'
            retried.succeeded()
            !Files.exists(directory.resolve('draft.txt'))
            Files.readString(directory.resolve('saved.txt')) == 'saved\n'

        cleanup:
            if (directory != null && Files.exists(directory))
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString('rwxr-xr-x'))
    }

    def 'Oillamp keeps the snapshots a reset takes off one branch on every other branch that has them'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time. A
            snapshot can be on several branches: those from before a branch was started are on the
            branch it was started from and on the new one alike. `oillamp history <dir> reset`
            moves only the branch the lamp is on. A snapshot it takes off that branch is still on
            every other branch it was on, so a user who started a branch to keep it can still
            switch there.
        """
        given: 'two snapshots on main, and the branch keep started at the second'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'one\n')
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'two\n')
            var second = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'keep').succeeded()

        when: 'main is reset to the first snapshot'
            var outcome = host.oillamp.run('history', lamp.toString(), 'reset', first.shortId(), '--yes')

        then: 'main ends at the first, and keep still at the second'
            outcome.succeeded()
            newestOf(lamp, 'main').id() == first.id()
            newestOf(lamp, 'keep').id() == second.id()

        when: 'the user switches to keep'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'keep').succeeded()

        then:
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'two\n'
    }

    def 'Oillamp refuses to reset to a snapshot of another branch'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            `oillamp history <dir> reset <snapshot>` moves the branch the lamp is on back along its
            own line. A snapshot that only another branch holds is not on that line, so oillamp
            refuses it with `OIL-HISTORY-008`, as it refuses to restore one, and changes nothing.
        """
        given: 'a snapshot on the branch other only, and the lamp on main'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()
            write(home(lamp), 'workspace/other.txt', 'other\n')
            var elsewhere = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()
            var before = newestOf(lamp, 'main')

        when:
            var outcome = host.oillamp.run('history', lamp.toString(), 'reset', elsewhere.shortId(), '--yes')

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-HISTORY-008')
            newestOf(lamp, 'main').id() == before.id()
    }

    // ─── deleting a branch ─────────────────────────────────────────────────────────────────

    def 'Oillamp deletes a branch only with --yes, and says first how many snapshots only it holds'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            `oillamp history <dir> branch --delete <name>` deletes a branch. Snapshots that are on
            that branch and on no other would then be on no branch at all, so without `--yes` it
            deletes nothing, says how many such snapshots there are, prints the command with
            `--yes`, and exits with `USAGE`, as `oillamp remove` does. With `--yes` the branch is
            gone. Its snapshots stay as files in the history until oillamp removes what no branch
            leads to.

            Here the branch `rust-rewrite` was started at the newest snapshot of `main`, and holds
            two snapshots of its own after it.
        """
        given: 'the branch rust-rewrite, with two snapshots of its own, and the lamp on main'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.rs', 'fn main() {}\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            write(home(lamp), 'workspace/lib.rs', 'pub fn f() {}\n')
            var newest = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()

        when: 'the user asks to delete it without --yes'
            var asked = host.oillamp.run('history', lamp.toString(), 'branch', '--delete', 'rust-rewrite')

        then: 'oillamp says what would be left on no branch, and deletes nothing'
            asked.status() == LampExitStatus.USAGE
            asked.events().find { it instanceof LampEvent.BranchDeletePlanned }.snapshots() == 2
            asked.console().contains('would leave 2 snapshots on no branch')
            asked.console().contains('oillamp history ' + lamp + ' branch --delete rust-rewrite --yes')
            branchesOf(lamp)*.name() == ['main', 'rust-rewrite']

        when: 'the user confirms'
            var deleted = host.oillamp.run('history', lamp.toString(), 'branch', '--delete', 'rust-rewrite', '--yes')

        then: 'the branch is gone, and its snapshots are still files in the history'
            deleted.succeeded()
            deleted.events().find { it instanceof LampEvent.BranchDeleted }.snapshots() == 2
            branchesOf(lamp)*.name() == ['main']
            Files.exists(objectFile(lamp, newest.id()))
    }

    def 'Oillamp refuses to delete the branch the lamp is on'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time, and
            every save goes to it, so the lamp always needs that branch.
            `oillamp history <dir> branch --delete <name>` with the branch the lamp is on is refused
            with `OIL-HISTORY-011`, with or without `--yes`, and the branch stays.
        """
        given:
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()

        when:
            var outcome = host.oillamp.run(*(['history', lamp.toString(), 'branch', '--delete', 'main'] + yes))

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-HISTORY-011')
            branchesOf(lamp)*.name() == ['main', 'other']

        where:
            yes << [[], ['--yes']]
    }

    // ─── listing ───────────────────────────────────────────────────────────────────────────

    def 'Oillamp lists the snapshots of the branch the lamp is on, and with --all those of every branch'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time. A
            snapshot can be on several branches: those from before a branch was started are on the
            branch it was started from and on the new one alike.

            `oillamp history <dir>`, and `oillamp history <dir> list`, list the snapshots of the
            branch the lamp is on, newest first. `oillamp history <dir> list --all` lists the
            snapshots of every branch, newest first and each once, each with the names of the
            branches it is on.
        """
        given: 'a snapshot on both branches, then one on main and one on rust-rewrite'
            var lamp = aLampThatHasRun()
            var shared = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'shared'))
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.txt', 'main\n')
            var onMain = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'main only'))
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.rs', 'fn main() {}\n')
            var onRust = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'rust only'))

        when:
            var current = host.oillamp.run('history', lamp.toString(), 'list')
            var all = host.oillamp.run('history', lamp.toString(), 'list', '--all')

        then: 'the plain list holds the snapshots of rust-rewrite, the branch the lamp is on'
            current.events().find { it instanceof LampEvent.History }.snapshots()*.id() == [onRust.id(), shared.id()]

        and: '--all holds every snapshot once, with the branches it is on'
            var listed = all.events().find { it instanceof LampEvent.HistoryOfAllBranches }
            listed.current() == 'rust-rewrite'
            listed.snapshots().collect { [it.snapshot().id(), it.branches().toList()] } as Set == [
                    [onRust.id(), ['rust-rewrite']],
                    [onMain.id(), ['main']],
                    [shared.id(), ['main', 'rust-rewrite']]] as Set
            listed.snapshots().last().snapshot().id() == shared.id()
            all.console().readLines().any { it.startsWith(shared.shortId()) && it.contains('main,rust-rewrite') && it.endsWith('shared') }
    }

    // ─── runs on several branches ──────────────────────────────────────────────────────────

    def 'Oillamp gives each run a number that no run on any branch has had'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            Every run of the agent has a name, such as `run-12`, and the snapshots of a run carry it
            in a trailer, the line `Oillamp-Run: run-12` at the end of the snapshot's commit
            message. A name must stand for one run of the lamp, on whichever branch it was, so
            oillamp numbers a new run one above the highest number on any branch, not only on the
            branch the lamp is on.

            Let's consider a lamp saved before any run, with the branch `other` started at that
            snapshot. On `main` the user asks the agent twice with `oillamp ask`: `run-1` and
            `run-2`. Then they switch to `other`, whose snapshots name no run, and ask again. That
            run must be `run-3`.
        """
        given: 'a lamp saved before any run, with the branch other started there'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            anAgent { prompt -> 'Done.' }

        and: 'two runs on main'
            startASession(lamp)
            assert runOf(host.oillamp.run('ask', lamp.toString(), 'First?')) == 'run-1'
            assert runOf(host.oillamp.run('ask', lamp.toString(), 'Second?')) == 'run-2'
            stopTheSession(lamp)

        when: 'the user switches to other and asks again'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()
            startASession(lamp)
            var third = runOf(host.oillamp.run('ask', lamp.toString(), 'Third?'))
            stopTheSession(lamp)

        then:
            third == 'run-3'

        and: 'every branch together holds one run snapshot for each run'
            host.oillamp.run('history', lamp.toString(), 'list', '--all').events()
                    .find { it instanceof LampEvent.HistoryOfAllBranches }.snapshots()*.snapshot()
                    .findAll { it.kind() == LampSaveKind.RUN }*.run()*.get().sort() == ['run-1', 'run-2', 'run-3']
    }

    def 'Oillamp counts the agent\'s runs on every branch against schedule.max_agent_runs_per_day with history budget #budget'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time, and
            each branch has its own schedule. `schedule.max_agent_runs_per_day` limits how many runs
            of the agent's own jobs may start in 24 hours; a job of the agent's that comes due when
            the limit is reached is skipped, and oillamp says so, naming the setting. Every run costs
            model tokens, whichever branch it was on, so the runs on every branch count, not only
            those on the branch the lamp is on.

            The scenario sets the limit to 1. On `main` the agent adds a job for five minutes later,
            and it runs in the next session. Then the user switches to `other`, a branch started
            before that run, whose schedule has no job. There the agent adds a job of its own, and
            when it comes due the same day, it must be skipped: the run on `main` counts.
        """
        given: 'a lamp whose agent may run one job of its own a day, with the branch other'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\nmax_agent_runs_per_day = 1\n[history]\nmax_growth_gb = ' + budget + '\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            anAgent { prompt -> 'Done.' }

        and: 'on main, a job of the agent\'s ran'
            startASession(lamp)
            assert agentAsks(op: 'add', at: 'in 5m', prompt: 'Check the build').ok
            stopTheSession(lamp)
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)
            waitFor(LampEvent.RunFinished)
            stopTheSession(lamp)

        and: 'with a small budget, a later save prunes that run but remembers its daily usage'
            if (budget != '0') {
                var data = new byte[65536]
                new Random(42).nextBytes(data)
                Files.createDirectories(home(lamp).resolve('workspace'))
                Files.write(home(lamp).resolve('workspace/big.bin'), data)
                var saved = host.oillamp.run('history', lamp.toString(), 'save')
                assert saved.succeeded()
                assert saved.events().any { it instanceof LampEvent.HistoryPruned &&
                        it.removed().any { entry -> entry.snapshot().kind() == LampSaveKind.RUN } }
            }

        and: 'on other, the agent added a job of its own'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()
            startASession(lamp)
            assert agentAsks(op: 'add', at: 'in 5m', prompt: 'Check the build again').ok
            stopTheSession(lamp)

        when: 'that job comes due, the same day'
            reported.clear()
            prompts.clear()
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(20))) }
            startASession(lamp)
            var skipped = waitFor(LampEvent.Info) { it.text().contains('was skipped') }

        then: 'it is skipped, because of the run on main'
            skipped.text().contains('schedule.max_agent_runs_per_day')
            prompts.isEmpty()
            !reported.any { it instanceof LampEvent.RunStarted }

        where:
            budget << ['0', '0.000001']
    }

    def 'Oillamp tells the agent only about the runs of the branch the lamp is on'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time, and
            each branch is a state of the lamp of its own. When a job wakes the agent, its prompt
            lists the recent runs, from the history, with the last run's final words, and the
            agent's `run_history` tool shows the runs of the history in more detail. Both look only
            at the branch the lamp is on: the agent remembers the state it is in, and a run on
            another branch did not happen in it.

            Let's consider a lamp with the branch `other`, started before any run. On `main` the
            user asks the agent something, and it answers "The answer is on main." Then the user
            switches to `other`, where a job of the user's wakes the agent. Its prompt must say that
            this is the first run, and must not hold the answer from `main`; `run_history` must list
            the job's run, and know no `run-1`.
        """
        given: 'a lamp with the branch other, started before any run, and a job on other'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            anAgent { prompt -> prompt.contains('Where is the answer?') ? 'The answer is on main.' : 'Done.' }

        and: 'on main, run-1 answered a question'
            startASession(lamp)
            assert runOf(host.oillamp.run('ask', lamp.toString(), 'Where is the answer?')) == 'run-1'
            stopTheSession(lamp)

        and: 'on other, a job of the user\'s for a minute later'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()
            assert host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 1m', 'Tidy up').succeeded()

        when: 'a session runs once the job is due'
            reported.clear()
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(5))) }
            startASession(lamp)
            var ran = waitFor(LampEvent.RunFinished)
            var listed = agentAsks(op: 'history')
            var asked = agentAsks(op: 'history', run: 'run-1')

        then: 'the job\'s prompt knows no run before it'
            ran.run().id() == 'run-2'
            prompts.last().contains('This is the first run.')
            !prompts.last().contains('The answer is on main.')

        and: 'run_history lists only the run on other'
            listed.text.contains('run-2')
            !listed.text.contains('run-1')
            asked.text.contains('There is no run called \'run-1\'')
    }

    // ─── a lamp from before branches, and git ──────────────────────────────────────────────

    def 'Oillamp reads a history from before branches as the branch main'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            Before oillamp had branches, a lamp's history had one line of snapshots, kept as git
            keeps a branch: the file `.oillamp/history/HEAD` saying `ref: refs/heads/main`, and the
            file `refs/heads/main` naming the newest snapshot. That is exactly the branch `main`, so
            such a lamp needs no change: `oillamp history <dir> branches` lists `main` alone as the
            branch the lamp is on, and saving, listing and restoring work as before.

            The scenario checks that a lamp saved and restored now still has exactly those two
            files and no other branch, as an older oillamp wrote them.
        """
        given: 'a lamp with two snapshots'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'one\n')
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'two\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when:
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', first.shortId())
            var listed = host.oillamp.run('history', lamp.toString())

        then: 'restoring and listing work as before'
            restored.succeeded()
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'one\n'
            listed.events().find { it instanceof LampEvent.History }.snapshots()*.kind() ==
                    [LampSaveKind.RESTORE, LampSaveKind.IDLE, LampSaveKind.IDLE]

        and: 'the history has the one branch main, in the files an older oillamp wrote'
            var repository = lamp.resolve('.oillamp/history')
            Files.readString(repository.resolve('HEAD')) == 'ref: refs/heads/main\n'
            Files.list(repository.resolve('refs/heads')).withCloseable { it.collect { it.fileName.toString() } } == ['main']
            branchesOf(lamp).collect { [it.name(), it.current()] } == [['main', true]]
    }

    @IgnoreIf({ !['/usr/bin/git', '/bin/git', '/usr/local/bin/git'].any { Files.isExecutable(Path.of(it)) } })
    def 'The program git itself reads the branches of a lamp\'s history'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time.
            oillamp writes the history in git's format itself, rather than by running git: a branch
            is a file under `refs/heads/` naming its newest snapshot, and `HEAD` names the branch
            the lamp is on. A person who knows git can read it without oillamp:
            `git --git-dir=<lamp>/.oillamp/history branch` lists the branches and marks the one the
            lamp is on, and `git log --all` lists the snapshots of every branch.
        """
        given: 'a lamp on the branch rust-rewrite, with a snapshot on each branch of its own'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save', '-m', 'shared').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.txt', 'main\n')
            var onMain = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'main only'))
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.rs', 'fn main() {}\n')
            var onRust = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'rust only'))
            var repository = lamp.resolve('.oillamp/history')

        expect: 'git branch lists both, and marks the one the lamp is on'
            git(repository, 'branch', '--list').readLines()*.trim().sort() == ['* rust-rewrite', 'main']

        and: 'git names the newest snapshot of each'
            git(repository, 'rev-parse', 'main').trim() == onMain.id()
            git(repository, 'rev-parse', 'HEAD').trim() == onRust.id()

        and: 'git log --all lists the snapshots of both branches'
            git(repository, 'log', '--all', '--format=%s').readLines().containsAll(
                    ['idle save: main only', 'idle save: rust only', 'idle save: shared'])
    }

    @IgnoreIf({ !['/usr/bin/git', '/bin/git', '/usr/local/bin/git'].any { Files.isExecutable(Path.of(it)) } })
    def 'Oillamp reads, switches to and deletes branches that git packed into one file'() {
        reportInfo """
            A branch is a line of snapshots of a lamp's history; the lamp is on one at a time. git
            keeps each branch in a file of its own under `refs/heads/`, until a command such as
            `git pack-refs` or `git gc` moves them all into the one file `packed-refs`. A person may
            run such a command on a lamp's history by hand. oillamp then still reads the branches
            from `packed-refs`: it lists them, switches between them, saves to them, and deletes one
            by removing its line from that file, so that git no longer lists it either.
        """
        given: 'a lamp with the branches main and other, which git packed'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'one\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            write(home(lamp), 'workspace/plan.md', 'two\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            var repository = lamp.resolve('.oillamp/history')
            git(repository, 'pack-refs', '--all')
            assert !Files.exists(repository.resolve('refs/heads/other'))

        expect: 'oillamp lists both'
            branchesOf(lamp)*.name() == ['main', 'other']

        when: 'the user switches to other and saves there'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()
            write(home(lamp), 'workspace/other.txt', 'other\n')
            var onOther = saved(host.oillamp.run('history', lamp.toString(), 'save'))

        then:
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'one\n'
            git(repository, 'rev-parse', 'other').trim() == onOther.id()

        when: 'the user switches back and deletes other'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', '--delete', 'other', '--yes').succeeded()

        then: 'neither oillamp nor git lists it any more'
            branchesOf(lamp)*.name() == ['main']
            git(repository, 'branch', '--list').readLines()*.trim() == ['* main']
    }

    def 'Oillamp ignores an interrupted branch write whose temporary file is #state'() {
        reportInfo """
            Saving or starting a branch writes its new commit id to a temporary file beside the
            branch, then renames it over the branch's file. A history reader can see that temporary
            file while a write is in progress, and an interrupted process can leave it behind.
            It may be empty, partially written, or already hold a complete commit id.

            Those files are not branches. Oillamp must still list the real branches and their
            snapshots, and let the user start and switch to another branch. The scenario leaves
            exactly such a file in `refs/heads` without needing to kill a process at just the right
            instant. Even a fully written temporary file must not appear as an alternative state.
        """
        given: 'a saved lamp and the temporary file left by an interrupted branch write'
            var lamp = aLampThatHasRun()
            var snapshot = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            var temporary = lamp.resolve('.oillamp/history/refs/heads/.oillamp-interrupted.tmp')
            Files.writeString(temporary, content(snapshot.id()))

        expect: 'only the real branch and its snapshot are listed'
            branchesOf(lamp)*.name() == ['main']
            var all = host.oillamp.run('history', lamp.toString(), 'list', '--all')
            all.succeeded()
            all.events().find { it instanceof LampEvent.HistoryOfAllBranches }.snapshots()
                    .collect { [it.snapshot().id(), it.branches().toList()] } == [[snapshot.id(), ['main']]]

        when: 'the user starts a branch and switches to it'
            var created = host.oillamp.run('history', lamp.toString(), 'branch', 'other')
            var switched = host.oillamp.run('history', lamp.toString(), 'switch', 'other')

        then: 'both work and the temporary file never becomes a branch'
            created.succeeded()
            switched.succeeded()
            branchesOf(lamp).collect { [it.name(), it.current()] } == [['main', false], ['other', true]]

        where:
            state              | content
            'empty'            | { id -> '' }
            'partly written'   | { id -> id.substring(0, 12) }
            'fully written'    | { id -> id + '\n' }
    }

    // ─── through the Java API ──────────────────────────────────────────────────────────────

    def 'An application keeps several states of a lamp through the Lamp API'() {
        reportInfo """
            An application, a program built on oillamp that uses `dev.lamp`, offers its user several
            states of one lamp with its own buttons. Each state is a branch: a line of snapshots of
            the lamp's history, with its own agent home, `oillamp.toml` and jobs. The lamp is on one
            branch at a time.

            `LampDirectory.branch` starts a branch, `branches` lists them as `LampBranch` values,
            `switchTo` switches, `historyOfAllBranches` lists every snapshot with the branches it
            is on, `wouldReset` and `wouldDeleteBranch` say what `reset` and `deleteBranch` would
            throw away, so that the application can ask its user first. Each runs the same engine
            command as the command line, and a refusal arrives as a `LampFailedException` that
            carries the problem.
        """
        given: 'a lamp saved once'
            var lamp = aLampThatHasRun()
            var lamps = Lamp.at(lamp).launchedBy(host.launcher)
            write(home(lamp), 'workspace/plan.md', 'original\n')
            var original = lamps.save('original').orElseThrow()

        when: 'it starts a branch, switches to it and saves a change there'
            var started = lamps.branch('rust-rewrite')
            var switched = lamps.switchTo('rust-rewrite')
            Files.writeString(home(lamp).resolve('workspace/plan.md'), 'in Rust\n')
            var rust = lamps.save('in Rust').orElseThrow()

        then:
            started.newest().get().id() == original.id()
            switched.name() == 'rust-rewrite' && switched.current()
            lamps.branches().collect { [it.name(), it.current(), it.newest().get().id()] } ==
                    [['main', false, original.id()], ['rust-rewrite', true, rust.id()]]
            lamps.history()*.id() == [rust.id(), original.id()]
            lamps.historyOfAllBranches().collect { [it.snapshot().id(), it.branches().toList()] } ==
                    [[rust.id(), ['rust-rewrite']], [original.id(), ['main', 'rust-rewrite']]]

        when: 'it asks what resetting to the original would lose, and then resets'
            Files.writeString(home(lamp).resolve('workspace/plan.md'), 'in Rust, not saved\n')
            var wouldLose = lamps.wouldReset(original.shortId())
            var reset = lamps.reset(original.shortId())

        then:
            wouldLose.snapshots() == 1 && wouldLose.unsaved()
            reset.snapshots() == 1 && reset.unsaved()
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'original\n'

        when: 'it starts a branch at the Rust snapshot, which is on no branch now, by its id'
            lamps.branch('rust-again', rust.id())

        then: 'that snapshot cannot be named any more, because no branch leads to it'
            var gone = thrown(LampFailedException)
            gone.problem().code().value() == 'OIL-HISTORY-001'

        when: 'it switches back to main and deletes rust-rewrite, after asking what that would lose'
            lamps.switchTo('main')
            var wouldGo = lamps.wouldDeleteBranch('rust-rewrite')
            var deleted = lamps.deleteBranch('rust-rewrite')

        then:
            wouldGo.snapshots() == 0
            deleted.branch() == 'rust-rewrite'
            lamps.branches()*.name() == ['main']

        when: 'it tries to delete the branch the lamp is on'
            lamps.deleteBranch('main')

        then:
            var refused = thrown(LampFailedException)
            refused.problem().code().value() == 'OIL-HISTORY-011'
            refused.status() == LampExitStatus.USAGE
    }

    def 'An application starts a branch of the lamp it holds while the session runs'() {
        reportInfo """
            An application, a program built on oillamp that uses `dev.lamp`, holds a running
            session as a `Lamp`. A branch is a line of snapshots of the lamp's history, and starting
            one only writes the new branch's file, so the application can do it while the session
            runs, without stopping the agent: `Lamp.save` takes a snapshot of the lamp as it is now,
            and `Lamp.branch` starts a branch at it, to come back to later. The session goes on, on
            the branch it was on.
        """
        given: 'a lamp the application holds, running'
            var directory = aLampThatHasRun()
            var lamp = Lamp.at(directory).launchedBy(host.launcher).start()
            assert lamp.awaitRunning(Duration.ofSeconds(30))

        when:
            write(home(directory), 'workspace/plan.md', 'before the risky part\n')
            var snapshot = lamp.save('before the risky part').orElseThrow()
            var branch = lamp.branch('before-risk')

        then:
            branch.name() == 'before-risk'
            branch.newest().get().id() == snapshot.id()
            !branch.current()
            lamp.branches().find { it.current() }.name() == 'main'

        cleanup:
            lamp?.close()
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** A lamp that exists and has run one session, with the file a new lamp gets. */
    private Path aLampThatHasRun() {
        var lamp = host.lampPath()
        assert host.oillamp.run('at', lamp.toString()).succeeded()
        lamp
    }

    /** A lamp that exists and has run one session, with these settings. */
    private Path aLampThatHasRun(String toml) {
        var lamp = host.lampPath()
        host.givenConfig(lamp, 'schema_version = 1\n' + toml)
        assert host.oillamp.run('at', lamp.toString()).succeeded()
        lamp
    }

    private static saved(outcome) {
        assert outcome.succeeded()
        outcome.events().find { it instanceof LampEvent.Saved }.snapshot()
    }

    /** The branches as `oillamp history <dir> branches` lists them. */
    private List branchesOf(Path lamp) {
        var listed = host.oillamp.run('history', lamp.toString(), 'branches')
        assert listed.succeeded()
        listed.events().find { it instanceof LampEvent.Branches }.branches().collect()
    }

    private newestOf(Path lamp, String branch) {
        branchesOf(lamp).find { it.name() == branch }.newest().get()
    }

    /** The jobs on a lamp's schedule, as `oillamp schedule` lists them. */
    private List jobsOf(Path lamp) {
        var listing = host.oillamp.run('schedule', lamp.toString())
        assert listing.succeeded()
        listing.events().find { it instanceof LampEvent.Schedule }.jobs().collect()
    }

    /** Where git keeps a snapshot's commit: one file per object, named after its id. */
    private static Path objectFile(Path lamp, String id) {
        lamp.resolve('.oillamp/history/objects').resolve(id.substring(0, 2)).resolve(id.substring(2))
    }

    private static String runOf(outcome) {
        outcome.events().find { it instanceof LampEvent.RunFinished }.run().id()
    }

    private void anAgent(Closure<String> work) {
        host.machine { it.agent { String prompt -> prompts << prompt; work(prompt) } }
    }

    private void startASession(Path lamp) {
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(90)) }
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        session = Thread.start { oillamp.run('at', lamp.toString()) }
        waitFor(LampEvent.Summary) { it.title() == 'your session is up' }
    }

    private void stopTheSession(Path lamp) {
        assert host.oillamp.run('stop', lamp.toString()).succeeded()
        session.join(30_000)
        assert !session.alive
        reported.clear()
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

    /** What the agent's scheduling tools do: one JSON line to the session's schedule socket, one line back. */
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

    private static void write(Path home, String relative, String content) {
        var file = home.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private static Path home(Path lamp) { Lamp.agentHome(lamp).orElseThrow() }

    private static String git(Path repository, String... arguments) {
        var process = new ProcessBuilder(['git', '--git-dir', repository.toString()] + arguments.toList()).start()
        var output = process.inputStream.text
        assert process.waitFor() == 0 : process.errorStream.text
        output
    }
}
