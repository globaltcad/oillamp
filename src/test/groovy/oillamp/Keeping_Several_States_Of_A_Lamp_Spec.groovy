package oillamp

import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampExitStatus
import dev.lamp.LampFailedException
import dev.lamp.LampSaveKind
import dev.oillamp.OilLamp
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.IgnoreIf
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
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@Title('Keeping several states of a lamp')
@Narrative('''
A lamp has one agent home and runs one sandbox at a time. Its user may still want to keep several
states of it side by side: let the agent try a rewrite in one, keep the original in another, and go
back and forth between them.

Each such state is a *branch* of the lamp's *history*, `.oillamp/history`. The history holds
*snapshots*, each of the agent's home, `oillamp.toml` and the schedule (the file
`.oillamp/schedule.json`, with the jobs that wake the agent) as they were at one moment. A branch
is a line of snapshots of its own, and its *newest snapshot* is the last one saved on it.

- A lamp starts with one branch, `main`.
- The lamp is on one branch at a time, and every save adds a snapshot to that branch only.
- A snapshot can be on several branches: those saved before a branch was started are on the branch
  it was started from and on the new one alike.

The commands are `oillamp history <dir> branches`, `branch`, `branch --delete`, `switch` and
`reset`. An application built on oillamp's Java API has the same in `LampDirectory` and `Lamp`.

The files are real, in a temporary directory, and so is the history: a git repository, which some
scenarios read with git itself, where git is installed.
''')
@Subject([OilLamp, Lamp])
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class Keeping_Several_States_Of_A_Lamp_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

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

    def '`oillamp history <dir> branch` starts a branch at a snapshot, and leaves the lamp on the branch it is on'() {
        reportInfo """
            Let's consider a lamp saved twice: once with `plan.md` in its first version, and once
            with its second. Its user wants to keep the second, and also try something else from
            the first. `oillamp history <dir> branch try-again <snapshot>` starts the branch
            `try-again`, whose newest snapshot is the first one. Starting a branch does not move the
            lamp to it: the files stay as they are, the lamp stays on `main`, and oillamp prints the
            `switch` command that would move it. Without a snapshot, `branch` starts at the newest
            snapshot of the branch the lamp is on.
        """
        given: 'a lamp saved with the first version of the plan, and again with the second'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'version one\n')
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'version two\n')
            var second = saved(host.oillamp.run('history', lamp.toString(), 'save'))

        when: '`oillamp history <dir> branch try-again` is run with the first snapshot'
            var started = host.oillamp.run('history', lamp.toString(), 'branch', 'try-again', first.shortId())

        then: 'the new branch\'s newest snapshot is the first one, the lamp is not on it, and oillamp prints `oillamp history <dir> switch try-again`'
            started.succeeded()
            var created = started.events().find { it instanceof LampEvent.BranchCreated }.branch()
            created.name() == 'try-again'
            created.newest().get().id() == first.id()
            !created.current()
            started.console().contains('oillamp history ' + lamp + ' switch try-again')

        and: '`plan.md` still holds its second version'
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'version two\n'

        when: '`oillamp history <dir> branch keep` is run without a snapshot'
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'keep').succeeded()

        then: '`keep` starts at the newest snapshot of `main`, and the lamp is still on `main`'
            var branches = branchesOf(lamp)
            branches.collect { [it.name(), it.current(), it.newest().get().id()] } == [
                    ['keep', false, second.id()],
                    ['main', true, second.id()],
                    ['try-again', false, first.id()]]
    }

    def '`oillamp history <dir> branches` lists every branch, and marks the one the lamp is on with `*`'() {
        reportInfo """
            `oillamp history <dir> branches` lists every branch by name, with its newest snapshot,
            and marks the branch the lamp is on with `*`. Let's consider a lamp that was never
            saved: it is on `main`, which has no snapshot yet. Once the lamp is saved and the
            branch `rust-rewrite` is started, both branches are listed, with the same newest
            snapshot.
        """
        given: 'a lamp that was never saved'
            var lamp = aLampThatHasRun()

        expect: '`branches` lists `main` alone, marked with `*`, with no snapshot yet'
            var empty = host.oillamp.run('history', lamp.toString(), 'branches')
            empty.succeeded()
            empty.events().find { it instanceof LampEvent.Branches }.branches()*.name() == ['main']
            empty.console().contains('* main')
            empty.console().contains('(no snapshot yet)')

        when: 'the lamp is saved, the branch `rust-rewrite` is started, and `branches` is run again'
            var newest = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            var listed = host.oillamp.run('history', lamp.toString(), 'branches')

        then: 'both branches are listed with their newest snapshot, and `main` is marked with `*`'
            listed.succeeded()
            listed.console().contains(String.format('* %-17s %s', 'main', newest.shortId()))
            listed.console().contains(String.format('  %-17s %s', 'rust-rewrite', newest.shortId()))
    }

    def '`oillamp history <dir> branch` refuses the name "#name", and starts no branch'() {
        reportInfo """
            A branch's name is also the name of its file in the history, which is a git repository,
            so oillamp takes only names that are safe as file names and that git accepts.
            `oillamp history <dir> branch <name>` takes a name of lowercase letters, digits, `-`,
            `_` and `.`, that begins with a letter or a digit, is at most 64 characters long, does
            not hold `..`, and does not end in `.` or `.lock`. Lowercase only, so that no two
            branches differ by case alone, which some file systems cannot tell apart. Any other
            name is a mistake on the command line: oillamp reports `OIL-USAGE-001` with the rule
            the name breaks, and starts no branch.
        """
        given: 'a lamp saved once'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when: '`oillamp history <dir> branch -- <name>` is run, with `--` so that a name such as `-rust` is not taken for an option'
            var outcome = host.oillamp.run('history', lamp.toString(), 'branch', '--', name)

        then: 'it exits with `USAGE` and reports `OIL-USAGE-001` with the rule the name breaks, and the lamp still has only `main`'
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

    def '`oillamp history <dir> branch` refuses the name "#name", which a branch already has'() {
        reportInfo """
            Let's consider a lamp with the branch `rust-rewrite`, started at an earlier snapshot
            than the newest of `main`. Starting a branch under a name that is taken would move that
            branch to a new start, and the snapshots on it would drop off it. So
            `oillamp history <dir> branch <name>` refuses a name that is taken, `main` included,
            with `OIL-HISTORY-010`, and the branch stays where it was.
        """
        given: 'a lamp with the branch `rust-rewrite`, started at an earlier snapshot'
            var lamp = aLampThatHasRun()
            var earlier = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/later.txt', 'later\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when: '`oillamp history <dir> branch <name>` is run with a name that is taken'
            var again = host.oillamp.run('history', lamp.toString(), 'branch', name)

        then: 'it exits with `USAGE` and reports `OIL-HISTORY-010`, and `rust-rewrite` still ends at the earlier snapshot'
            again.status() == LampExitStatus.USAGE
            again.reported('OIL-HISTORY-010')
            branchesOf(lamp).find { it.name() == 'rust-rewrite' }.newest().get().id() == earlier.id()

        where:
            name << ['rust-rewrite', 'main']
    }

    // ─── saving and switching ──────────────────────────────────────────────────────────────

    def 'Oillamp adds each save to the branch the lamp is on, and to no other'() {
        reportInfo """
            Every save, whether by `oillamp history <dir> save`, around a run of the agent, or as a
            session starts or ends, adds a snapshot to the branch the lamp is on only. The newest
            snapshot of every other branch stays the same.

            Let's consider a lamp on `main`, with the branch `experiment` started at its newest
            snapshot. A change saved on `main` goes to `main` alone. After
            `oillamp history <dir> switch experiment`, a change saved there goes to `experiment`
            alone, and `oillamp history <dir>` lists the snapshots of `experiment`.
        """
        given: 'a lamp on `main`, and the branch `experiment` started at its newest snapshot'
            var lamp = aLampThatHasRun()
            var start = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'experiment').succeeded()

        when: '`oillamp history <dir> save` saves a change on `main`'
            write(home(lamp), 'workspace/main.txt', 'on main\n')
            var onMain = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'on main'))

        then: '`main` has the new snapshot, and `experiment` still ends where it started'
            newestOf(lamp, 'main').id() == onMain.id()
            newestOf(lamp, 'experiment').id() == start.id()

        when: '`oillamp history <dir> switch experiment` is run, and a change is saved there'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'experiment').succeeded()
            write(home(lamp), 'workspace/experiment.txt', 'on experiment\n')
            var onExperiment = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'on experiment'))

        then: '`experiment` has the new snapshot, and `main` still ends where it did'
            newestOf(lamp, 'experiment').id() == onExperiment.id()
            newestOf(lamp, 'main').id() == onMain.id()

        and: '`oillamp history <dir>` lists the snapshots of experiment, which the lamp is on'
            var listed = host.oillamp.run('history', lamp.toString())
            listed.events().find { it instanceof LampEvent.History }.branch() == 'experiment'
            listed.events().find { it instanceof LampEvent.History }.snapshots()*.id() == [onExperiment.id(), start.id()]
            listed.console().contains('on the branch experiment')
    }

    def '`oillamp history <dir> switch` brings back the agent\'s home, `oillamp.toml` and the jobs of the branch switched to'() {
        reportInfo """
            Let's consider a lamp on which the agent tries a Rust rewrite on a branch of its own,
            `rust-rewrite`, while the original stays on `main`. After the branch was started, `main`
            gets a second chapter of the story, a second job and a changed `oillamp.toml`.
            `oillamp history <dir> switch rust-rewrite` brings the lamp to that branch's newest
            snapshot: the home, `oillamp.toml` and the jobs as they are there. Switching adds no
            snapshot to the branch switched to. `oillamp history <dir> switch main` then brings back
            those of `main`.
        """
        given: 'a lamp on `main` with a story, a job and its `oillamp.toml`, saved'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/story.txt', 'chapter one\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')
            var config = lamp.resolve('oillamp.toml')
            var original = Files.readString(config)
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        and: 'the branch `rust-rewrite`, where the story was rewritten and saved'
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite').succeeded()
            Files.writeString(home(lamp).resolve('workspace/story.txt'), 'chapter one, in Rust\n')
            var rust = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'in Rust'))

        and: 'back on `main`, a second chapter, a second job and a changed `oillamp.toml`, saved'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()
            Files.writeString(home(lamp).resolve('workspace/story.txt'), 'chapter two\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')
            Files.writeString(config, original + '\n# changed on main\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when: '`oillamp history <dir> switch rust-rewrite` is run'
            var switched = host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite')

        then: 'the home, `oillamp.toml` and the jobs are as `rust-rewrite` holds them'
            switched.succeeded()
            switched.events().find { it instanceof LampEvent.Switched }.to().name() == 'rust-rewrite'
            Files.readString(home(lamp).resolve('workspace/story.txt')) == 'chapter one, in Rust\n'
            Files.readString(config) == original
            jobsOf(lamp)*.prompt() == ['Tidy up']

        and: 'no snapshot was added to `rust-rewrite`'
            newestOf(lamp, 'rust-rewrite').id() == rust.id()

        when: '`oillamp history <dir> switch main` is run'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()

        then: 'the home, `oillamp.toml` and the jobs of `main` are back'
            Files.readString(home(lamp).resolve('workspace/story.txt')) == 'chapter two\n'
            Files.readString(config).endsWith('# changed on main\n')
            jobsOf(lamp)*.prompt() == ['Tidy up', 'Look at the queue']
    }

    def '`oillamp history <dir> switch` first saves the unsaved changes of the branch it leaves'() {
        reportInfo """
            `oillamp history <dir> switch <name>` replaces the agent's home with what the other
            branch holds. Let's consider a lamp on `main` with a change that was never saved. The
            switch would lose it, so oillamp first saves the lamp on `main`, as a safety save of the
            kind `LampSaveKind.BEFORE_SWITCH`, and only then switches, printing the command that
            switches back. Switching back brings the change back, because it is now the newest
            snapshot of `main`. The branch `other`, which had no change, gets no save.
        """
        given: 'a lamp on main with the branch other, and a change on main that was never saved'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            write(home(lamp), 'workspace/draft.txt', 'not saved yet\n')

        when: '`oillamp history <dir> switch other` is run'
            var switched = host.oillamp.run('history', lamp.toString(), 'switch', 'other')

        then: '`main` was saved first, with the change, and the home of `other` does not have it'
            switched.succeeded()
            var safety = saved(switched)
            safety.kind() == LampSaveKind.BEFORE_SWITCH
            newestOf(lamp, 'main').id() == safety.id()
            !Files.exists(home(lamp).resolve('workspace/draft.txt'))
            switched.console().contains('oillamp history ' + lamp + ' switch main')

        when: '`oillamp history <dir> switch main` is run'
            var back = host.oillamp.run('history', lamp.toString(), 'switch', 'main')

        then: 'the change is back, and nothing was saved on `other`'
            back.succeeded()
            !back.events().any { it instanceof LampEvent.Saved }
            Files.readString(home(lamp).resolve('workspace/draft.txt')) == 'not saved yet\n'
    }

    def '`oillamp history <dir> switch` to the branch the lamp is on changes nothing, and says so'() {
        reportInfo """
            Let's consider a lamp on `main` with a change that was never saved.
            `oillamp history <dir> switch main` has nothing to do: oillamp saves nothing, leaves
            the files as they are, and says that nothing changed.
        """
        given: 'a lamp on `main`, saved, with a change that was never saved'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            write(home(lamp), 'workspace/draft.txt', 'not saved yet\n')

        when: '`oillamp history <dir> switch main` is run'
            var outcome = host.oillamp.run('history', lamp.toString(), 'switch', 'main')

        then: 'nothing is saved, oillamp says that nothing changed, and the change is still there'
            outcome.succeeded()
            !outcome.events().any { it instanceof LampEvent.Saved }
            outcome.console().contains('the lamp already is on main, so nothing changed')
            Files.readString(home(lamp).resolve('workspace/draft.txt')) == 'not saved yet\n'
    }

    def '`oillamp history <dir> switch` refuses a branch the history does not have'() {
        reportInfo """
            `oillamp history <dir> switch rust-rewrite`, on a lamp whose only branch is `main`, is
            refused with `OIL-HISTORY-009`, which lists the branches there are, and the lamp stays
            on `main`.
        """
        given: 'a lamp with only the branch `main`'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when: '`oillamp history <dir> switch rust-rewrite` is run'
            var outcome = host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite')

        then: 'it exits with `USAGE` and reports `OIL-HISTORY-009`, naming `main`, and the lamp is still on `main`'
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-HISTORY-009')
            outcome.errors().first().whatHappened().contains("no branch called 'rust-rewrite'; it has main")
            branchesOf(lamp).find { it.current() }.name() == 'main'
    }

    def 'Oillamp refuses `oillamp history <dir> #action` while a session runs'() {
        reportInfo """
            `oillamp history <dir> switch` and `oillamp history <dir> reset` both replace files in
            the agent's home, and programs running in the sandbox would go on working in files
            replaced under them. So both are refused while a session runs, with `OIL-HISTORY-005`
            and the exit status `LAMP_BUSY`. The lamp stays on its branch, with its files as they
            are.
        """
        given: 'a lamp with two snapshots on main, the branch other, and a running session'
            var lamp = aLampThatHasRun()
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/later.txt', 'later\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other', first.shortId()).succeeded()
            startASession(lamp)

        when: 'the command is run while the session runs'
            var outcome = host.oillamp.run(*(['history', lamp.toString()] + words(first)))

        then: 'it exits with `LAMP_BUSY` and reports `OIL-HISTORY-005`, and the lamp is still on `main`, with its files as they were'
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

    def '`oillamp history <dir> branch` starts a branch while a session runs, and leaves the lamp on its branch'() {
        reportInfo """
            Starting a branch only writes the new branch's file into the history, which the running
            session does not read, and leaves the lamp on its branch. Let's consider an agent about
            to try something risky. Its user can save while the session runs, and start a branch at
            that snapshot to come back to later, without stopping the agent.
        """
        given: 'a running session, and a snapshot saved during it'
            var lamp = aLampThatHasRun()
            startASession(lamp)
            var running = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'before the risky part'))

        when: '`oillamp history <dir> branch before-risk` is run'
            var started = host.oillamp.run('history', lamp.toString(), 'branch', 'before-risk')

        then: 'the branch starts at that snapshot, and the lamp is still on `main`'
            started.succeeded()
            newestOf(lamp, 'before-risk').id() == running.id()
            branchesOf(lamp).find { it.current() }.name() == 'main'
    }

    // ─── restore and reset stay on their branch ────────────────────────────────────────────

    def '`oillamp history <dir> restore` refuses a snapshot that only another branch holds, and says how to reach it'() {
        reportInfo """
            `oillamp history <dir> restore <snapshot>` goes back along the branch the lamp is on.
            Let's consider a snapshot that only the branch `rust-rewrite` holds, while the lamp is
            on `main`. That snapshot belongs to another state of the lamp, and restoring it would
            put that state on top of `main`. So oillamp refuses it with `OIL-HISTORY-008`, names
            the branch the snapshot is on, and says how to reach it: start a branch at it with
            `oillamp history <dir> branch <name> <snapshot>`, then switch to that branch. Nothing
            is saved, and the files stay as they are.
        """
        given: 'a snapshot that only `rust-rewrite` holds, and the lamp on `main`'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.rs', 'fn main() {}\n')
            var rust = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()
            var before = newestOf(lamp, 'main')

        when: '`oillamp history <dir> restore` is run with that snapshot'
            var outcome = host.oillamp.run('history', lamp.toString(), 'restore', rust.shortId())

        then: 'oillamp refuses with `OIL-HISTORY-008`, and prints the `branch` and `switch` commands that reach the snapshot'
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-HISTORY-008')
            outcome.errors().first().whatHappened().contains('is on the branch rust-rewrite, not on main')
            outcome.console().contains('oillamp history ' + lamp + ' branch <name> ' + rust.shortId())
            outcome.console().contains('oillamp history ' + lamp + ' switch <name>')

        and: 'nothing was saved on `main`, and the files are as they were'
            newestOf(lamp, 'main').id() == before.id()
            !Files.exists(home(lamp).resolve('workspace/main.rs'))
    }

    def '`oillamp history <dir> reset` without `--yes` changes nothing, and says what would be lost'() {
        reportInfo """
            `oillamp history <dir> reset <snapshot>` is a hard reset, as `git reset --hard` is: it
            brings the lamp back to a snapshot on its branch, and moves the branch back to it. The
            snapshots made after it leave the branch, changes not yet saved are thrown away, and
            nothing is saved first. Because it throws work away, it asks first, as `oillamp remove`
            does.

            Let's consider a lamp with three snapshots on `main`, and a change that was never saved.
            Without `--yes`, a reset to the first snapshot changes nothing: it says that 2 snapshots
            would leave the branch and that unsaved changes would be thrown away, prints the
            command with `--yes`, and exits with `USAGE`.
        """
        given: 'three snapshots on `main`, and a change that was never saved'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'one\n')
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'two\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            write(home(lamp), 'workspace/plan.md', 'three\n')
            var third = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'four, not saved\n')

        when: '`oillamp history <dir> reset` is run with the first snapshot, without `--yes`'
            var outcome = host.oillamp.run('history', lamp.toString(), 'reset', first.shortId())

        then: 'it says that 2 snapshots would leave the branch and unsaved changes would be lost, and prints the command with `--yes`'
            outcome.status() == LampExitStatus.USAGE
            var planned = outcome.events().find { it instanceof LampEvent.ResetPlanned }
            planned.target().id() == first.id()
            planned.snapshots() == 2
            planned.unsaved()
            outcome.console().contains('would take 2 snapshots off the branch, and throw away changes no snapshot holds')
            outcome.console().contains('oillamp history ' + lamp + ' reset ' + first.shortId() + ' --yes')

        and: 'the change is still there, and `main` still ends at the third snapshot'
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'four, not saved\n'
            newestOf(lamp, 'main').id() == third.id()
    }

    def '`oillamp history <dir> reset` without `--yes` says what would be lost even when the history is read-only'() {
        reportInfo """
            Without `--yes`, `oillamp history <dir> reset <snapshot>` only compares the lamp with its
            history, to say what a reset would throw away. It stores nothing, so it only needs to
            read the lamp's history, `.oillamp/history`. Let's consider a lamp whose history cannot
            be written, such as one kept on a backup that is only looked at. What a reset would lose
            is reported all the same.
        """
        given: 'two snapshots on `main`, a change that was never saved, and a history that cannot be written'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'one\n')
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'two\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            write(home(lamp), 'workspace/plan.md', 'three, not saved\n')
            var writable = readOnly(lamp.resolve('.oillamp/history'))

        when: '`oillamp history <dir> reset` is run with the first snapshot, without `--yes`'
            var outcome = host.oillamp.run('history', lamp.toString(), 'reset', first.shortId())

        then: 'it says that 1 snapshot would leave the branch and unsaved changes would be lost'
            outcome.status() == LampExitStatus.USAGE
            var planned = outcome.events().find { it instanceof LampEvent.ResetPlanned }
            planned.snapshots() == 1
            planned.unsaved()

        cleanup:
            writable?.call()
    }

    def '`oillamp history <dir> reset --yes` takes the later snapshots off the branch, and throws away unsaved changes'() {
        reportInfo """
            `oillamp history <dir> reset <snapshot> --yes` brings the agent's home, `oillamp.toml`
            and the schedule back to that snapshot, which must be on the branch the lamp is on, and
            moves the branch back to it. The snapshots made after it leave the branch, and
            `oillamp history <dir>` no longer lists them. A change that was never saved is gone. No
            snapshot is made, neither before nor after.

            The snapshots that left the branch are not deleted at once. Each stays a file in the
            history's `objects` directory, as git keeps it, until oillamp removes what no branch
            leads to.
        """
        given: 'three snapshots on `main`, the last with a job, and a change that was never saved'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'one\n')
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'two\n')
            var second = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')
            var third = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/unsaved.txt', 'never saved\n')

        when: '`oillamp history <dir> reset` is run with the first snapshot and `--yes`'
            var outcome = host.oillamp.run('history', lamp.toString(), 'reset', first.shortId(), '--yes')

        then: 'it reports 2 snapshots taken off and unsaved changes thrown away, and the home and the jobs are as the first snapshot holds them'
            outcome.succeeded()
            var done = outcome.events().find { it instanceof LampEvent.ResetDone }
            done.snapshots() == 2
            done.unsaved()
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'one\n'
            !Files.exists(home(lamp).resolve('workspace/unsaved.txt'))
            jobsOf(lamp).isEmpty()

        and: 'nothing was saved, and `main` now ends at the first snapshot'
            !outcome.events().any { it instanceof LampEvent.Saved }
            host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots()*.id() == [first.id()]
            host.oillamp.run('history', lamp.toString(), 'list', '--all').events()
                    .find { it instanceof LampEvent.HistoryOfAllBranches }.snapshots()*.snapshot()*.id() == [first.id()]

        and: 'the two later snapshots are still files in the history'
            [second, third].every { Files.exists(objectFile(lamp, it.id())) }
    }

    def 'Oillamp refuses a reset when it cannot read all of the agent\'s home, so that it never claims to throw away work it would keep'() {
        reportInfo """
            Before a hard reset, `oillamp history <dir> reset` reads the agent's home to find what it
            must replace or remove. If a directory in it cannot be read, oillamp cannot see the
            files inside, and restoring the saved files alone would leave those behind.

            Let's consider a saved directory, `workspace/private`, to which a draft was added and
            never saved, and from which all permissions were then taken away. The reset without
            `--yes` and the reset with `--yes` must both refuse with `OIL-HISTORY-003`, name the
            directory, and leave the branch, the directory's permissions and both files alone.
            Once the directory can be read again, the reset works and removes the draft.
        """
        given: 'a saved directory with a draft that was never saved, made unreadable'
            var lamp = aLampThatHasRun()
            var directory = home(lamp).resolve('workspace/private')
            write(home(lamp), 'workspace/private/saved.txt', 'saved\n')
            var snapshot = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/private/draft.txt', 'not saved\n')
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString('---------'))
            var before = Files.readString(lamp.resolve('.oillamp/history/refs/heads/main'))

        when: '`oillamp history <dir> reset` is run with the snapshot, without and then with `--yes`'
            var preview = host.oillamp.run('history', lamp.toString(), 'reset', snapshot.id())
            var confirmed = host.oillamp.run('history', lamp.toString(), 'reset', snapshot.id(), '--yes')

        then: 'both exit with `ERROR`, report `OIL-HISTORY-003` naming `workspace/private`, and report no reset, planned or done'
            [preview, confirmed].every {
                it.status() == LampExitStatus.ERROR && it.reported('OIL-HISTORY-003') &&
                        it.console().contains('workspace/private') &&
                        !it.events().any { event -> event instanceof LampEvent.ResetDone || event instanceof LampEvent.ResetPlanned }
            }

        and: 'the branch and the directory\'s permissions are as they were'
            Files.readString(lamp.resolve('.oillamp/history/refs/heads/main')) == before
            Files.getPosixFilePermissions(directory) == PosixFilePermissions.fromString('---------')

        when: 'the directory is made readable again'
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString('rwxr-xr-x'))

        then: 'both files are still there'
            Files.readString(directory.resolve('saved.txt')) == 'saved\n'
            Files.readString(directory.resolve('draft.txt')) == 'not saved\n'

        when: '`oillamp history <dir> reset --yes` is run again'
            var retried = host.oillamp.run('history', lamp.toString(), 'reset', snapshot.id(), '--yes')

        then: 'it succeeds, and the draft is gone'
            retried.succeeded()
            !Files.exists(directory.resolve('draft.txt'))
            Files.readString(directory.resolve('saved.txt')) == 'saved\n'

        cleanup:
            if (directory != null && Files.exists(directory))
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString('rwxr-xr-x'))
    }

    def '`oillamp history <dir> reset` moves only the branch the lamp is on, so a snapshot it takes off stays on the other branches'() {
        reportInfo """
            A snapshot can be on several branches. `oillamp history <dir> reset` moves only the
            branch the lamp is on, so a snapshot it takes off that branch is still on every other
            branch it was on. Let's consider a lamp with two snapshots on `main`, and the branch
            `keep`, started at the second to keep it. After `main` is reset to the first snapshot,
            `keep` still ends at the second, and switching to `keep` brings it back.
        """
        given: 'two snapshots on main, and the branch keep started at the second'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'one\n')
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'two\n')
            var second = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'keep').succeeded()

        when: '`main` is reset to the first snapshot, with `--yes`'
            var outcome = host.oillamp.run('history', lamp.toString(), 'reset', first.shortId(), '--yes')

        then: '`main` ends at the first snapshot, and `keep` still at the second'
            outcome.succeeded()
            newestOf(lamp, 'main').id() == first.id()
            newestOf(lamp, 'keep').id() == second.id()

        when: '`oillamp history <dir> switch keep` is run'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'keep').succeeded()

        then: '`plan.md` holds its second version again'
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'two\n'
    }

    def '`oillamp history <dir> reset` refuses a snapshot that only another branch holds'() {
        reportInfo """
            `oillamp history <dir> reset <snapshot>` moves the branch the lamp is on back along its
            own line. Let's consider a snapshot that only the branch `other` holds, while the lamp
            is on `main`. That snapshot is not on the line of `main`, so oillamp refuses it with
            `OIL-HISTORY-008`, even with `--yes`, and `main` stays where it was.
        """
        given: 'a snapshot that only `other` holds, and the lamp on `main`'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()
            write(home(lamp), 'workspace/other.txt', 'other\n')
            var elsewhere = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()
            var before = newestOf(lamp, 'main')

        when: '`oillamp history <dir> reset` is run with that snapshot and `--yes`'
            var outcome = host.oillamp.run('history', lamp.toString(), 'reset', elsewhere.shortId(), '--yes')

        then: 'it exits with `USAGE` and reports `OIL-HISTORY-008`, and `main` still ends where it did'
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-HISTORY-008')
            newestOf(lamp, 'main').id() == before.id()
    }

    // ─── deleting a branch ─────────────────────────────────────────────────────────────────

    def '`oillamp history <dir> branch --delete` deletes a branch only with `--yes`, and first says how many snapshots only it holds'() {
        reportInfo """
            `oillamp history <dir> branch --delete <name>` deletes a branch. The snapshots on that
            branch and on no other would then be on no branch at all. So without `--yes` it deletes
            nothing, as `oillamp remove` does: it says how many such snapshots there are, prints the
            command with `--yes`, and exits with `USAGE`. With `--yes` the branch is gone. Its
            snapshots stay files in the history until oillamp removes what no branch leads to.

            Let's consider the branch `rust-rewrite`, started at the newest snapshot of `main`, with
            two snapshots of its own saved after that.
        """
        given: 'the branch `rust-rewrite`, with two snapshots of its own, and the lamp on `main`'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.rs', 'fn main() {}\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            write(home(lamp), 'workspace/lib.rs', 'pub fn f() {}\n')
            var newest = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()

        when: '`oillamp history <dir> branch --delete rust-rewrite` is run without `--yes`'
            var asked = host.oillamp.run('history', lamp.toString(), 'branch', '--delete', 'rust-rewrite')

        then: 'it says that 2 snapshots would be on no branch, prints the command with `--yes`, and deletes nothing'
            asked.status() == LampExitStatus.USAGE
            asked.events().find { it instanceof LampEvent.BranchDeletePlanned }.snapshots() == 2
            asked.console().contains('would leave 2 snapshots on no branch')
            asked.console().contains('oillamp history ' + lamp + ' branch --delete rust-rewrite --yes')
            branchesOf(lamp)*.name() == ['main', 'rust-rewrite']

        when: 'the same command is run with `--yes`'
            var deleted = host.oillamp.run('history', lamp.toString(), 'branch', '--delete', 'rust-rewrite', '--yes')

        then: 'the branch is gone, and its newest snapshot is still a file in the history'
            deleted.succeeded()
            deleted.events().find { it instanceof LampEvent.BranchDeleted }.snapshots() == 2
            branchesOf(lamp)*.name() == ['main']
            Files.exists(objectFile(lamp, newest.id()))
    }

    def '`oillamp history <dir> branch --delete` refuses the branch the lamp is on'() {
        reportInfo """
            Every save goes to the branch the lamp is on, so the lamp always needs that branch.
            `oillamp history <dir> branch --delete main`, on a lamp that is on `main`, is refused
            with `OIL-HISTORY-011`, with or without `--yes`, and the branch stays.
        """
        given: 'a lamp on `main`, with the branch `other`'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()

        when: '`oillamp history <dir> branch --delete main` is run'
            var outcome = host.oillamp.run(*(['history', lamp.toString(), 'branch', '--delete', 'main'] + yes))

        then: 'it exits with `USAGE` and reports `OIL-HISTORY-011`, and both branches are still there'
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-HISTORY-011')
            branchesOf(lamp)*.name() == ['main', 'other']

        where:
            yes << [[], ['--yes']]
    }

    // ─── listing ───────────────────────────────────────────────────────────────────────────

    def '`oillamp history <dir>` lists the snapshots of the branch the lamp is on, and `list --all` those of every branch'() {
        reportInfo """
            `oillamp history <dir>`, and `oillamp history <dir> list`, list the snapshots of the
            branch the lamp is on, newest first. `oillamp history <dir> list --all` lists the
            snapshots of every branch, each once, with the names of the branches it is on.

            Let's consider a lamp with a snapshot on both `main` and `rust-rewrite`, then one on
            `main` only and one on `rust-rewrite` only, and the lamp on `rust-rewrite`.
        """
        given: 'a snapshot on both branches, then one on `main` and one on `rust-rewrite`'
            var lamp = aLampThatHasRun()
            var shared = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'shared'))
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.txt', 'main\n')
            var onMain = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'main only'))
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.rs', 'fn main() {}\n')
            var onRust = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'rust only'))

        when: '`oillamp history <dir> list` and `oillamp history <dir> list --all` are run'
            var current = host.oillamp.run('history', lamp.toString(), 'list')
            var all = host.oillamp.run('history', lamp.toString(), 'list', '--all')

        then: 'the plain list holds the snapshots of `rust-rewrite`, newest first'
            current.events().find { it instanceof LampEvent.History }.snapshots()*.id() == [onRust.id(), shared.id()]

        and: '`--all` holds every snapshot once, with the branches it is on, and the shared one last'
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

    def 'Oillamp gives each run of the agent a number that no run on any branch has had'() {
        reportInfo """
            Every run of the agent has a name, such as `run-12`, and its snapshots carry it in a
            trailer: the line `Oillamp-Run: run-12` at the end of the snapshot's commit message. A
            name must stand for one run of the lamp, on whichever branch it ran, so oillamp numbers
            a new run one above the highest number on any branch, not only on the branch the lamp
            is on.

            Let's consider a lamp saved before any run, with the branch `other` started at that
            snapshot. On `main`, `oillamp ask` wakes the agent twice: `run-1` and `run-2`. After
            `oillamp history <dir> switch other`, whose snapshots name no run, the next
            `oillamp ask` must start `run-3`.
        """
        given: 'a lamp saved before any run, with the branch other started there'
            var lamp = aLampThatHasRun('[autosave]\nchat_quiet_minutes = 0\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            anAgent { prompt -> 'Done.' }

        and: 'two runs on `main`, from `oillamp ask`'
            startASession(lamp)
            assert runOf(host.oillamp.run('ask', lamp.toString(), 'First?')) == 'run-1'
            assert runOf(host.oillamp.run('ask', lamp.toString(), 'Second?')) == 'run-2'
            stopTheSession(lamp)

        when: '`oillamp history <dir> switch other` is run, and `oillamp ask` wakes the agent again'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()
            startASession(lamp)
            var third = runOf(host.oillamp.run('ask', lamp.toString(), 'Third?'))
            stopTheSession(lamp)

        then: 'the new run is `run-3`'
            third == 'run-3'

        and: 'the branches together hold one run snapshot for each of the three runs'
            host.oillamp.run('history', lamp.toString(), 'list', '--all').events()
                    .find { it instanceof LampEvent.HistoryOfAllBranches }.snapshots()*.snapshot()
                    .findAll { it.kind() == LampSaveKind.RUN }*.run()*.get().sort() == ['run-1', 'run-2', 'run-3']
    }

    def 'Oillamp counts the agent\'s runs on every branch against `schedule.max_agent_runs_per_day`, with `history.max_growth_gb = #budget`'() {
        reportInfo """
            `schedule.max_agent_runs_per_day` limits how many runs of the agent's own jobs may start
            in 24 hours. A job of the agent's that comes due once the limit is reached is skipped,
            and oillamp says so, naming the setting. Each branch has its own schedule, but every run
            costs model tokens, whichever branch it ran on, so the runs on every branch count.

            Let's consider a lamp with the limit set to 1. On `main`, the agent adds a job for five
            minutes later, which runs in the next session. Then `oillamp history <dir> switch other`
            moves the lamp to a branch started before that run, whose schedule has no job. There the
            agent adds a job of its own, and when it comes due the same day it must be skipped: the
            run on `main` counts.

            The scenario runs twice: with `history.max_growth_gb = 0`, where oillamp never deletes a
            snapshot, and with a budget so small that a later save deletes the snapshot of the run on
            `main`. The run must count all the same, although no snapshot holds it any more.
        """
        given: 'a lamp whose agent may run one job of its own a day, with the branch `other`'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\nmax_agent_runs_per_day = 1\n[history]\nmax_growth_gb = ' + budget + '\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            anAgent { prompt -> 'Done.' }

        and: 'on `main`, a job of the agent\'s ran'
            startASession(lamp)
            assert agentAsks(op: 'add', at: 'in 5m', prompt: 'Check the build').ok
            stopTheSession(lamp)
            host.machine { it.clockAt(NOW.plus(Duration.ofMinutes(10))) }
            startASession(lamp)
            waitFor(LampEvent.RunFinished)
            stopTheSession(lamp)

        and: 'with the small budget, a later save deletes the snapshot of that run'
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

        and: 'on `other`, the agent added a job of its own'
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

        then: 'it is skipped, naming `schedule.max_agent_runs_per_day`, and the agent is not woken'
            skipped.text().contains('schedule.max_agent_runs_per_day')
            prompts.isEmpty()
            !reported.any { it instanceof LampEvent.RunStarted }

        where:
            budget << ['0', '0.000001']
    }

    def 'Oillamp tells the agent only about the runs of the branch the lamp is on'() {
        reportInfo """
            Each branch is a state of the lamp of its own. When a job wakes the agent, its prompt
            lists the recent runs from the history, with the last run's final words, and the agent's
            `run_history` tool shows those runs in more detail. Both look only at the branch the
            lamp is on: a run on another branch did not happen in this state.

            Let's consider a lamp with the branch `other`, started before any run. On `main`,
            `oillamp ask` wakes the agent with a question, and `run-1` answers "The answer is on
            main." After `oillamp history <dir> switch other`, a job added with `oillamp schedule`
            wakes the agent. Its prompt must say that this is the first run, and must not hold the
            answer from `main`. `run_history` must list the job's run, `run-2`, and know no
            `run-1`.
        """
        given: 'a lamp with the branch `other`, started before any run'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            anAgent { prompt -> prompt.contains('Where is the answer?') ? 'The answer is on main.' : 'Done.' }

        and: 'on `main`, `run-1` answered a question'
            startASession(lamp)
            assert runOf(host.oillamp.run('ask', lamp.toString(), 'Where is the answer?')) == 'run-1'
            stopTheSession(lamp)

        and: 'on `other`, a job added with `oillamp schedule` for a minute later'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()
            assert host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 1m', 'Tidy up').succeeded()

        when: 'a session runs once the job is due, and the agent calls `run_history`'
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

        and: '`run_history` lists only the run on `other`'
            listed.text.contains('run-2')
            !listed.text.contains('run-1')
            asked.text.contains('There is no run called \'run-1\'')
    }

    // ─── a lamp from before branches, and git ──────────────────────────────────────────────

    def 'Oillamp keeps the branch `main` in the same files as a history from before branches, so such a history needs no change'() {
        reportInfo """
            Before oillamp had branches, a lamp's history had one line of snapshots, kept as git
            keeps a branch: the file `.oillamp/history/HEAD` saying `ref: refs/heads/main`, and the
            file `refs/heads/main` naming the newest snapshot. That is exactly the branch `main`, so
            such a lamp needs no change: `oillamp history <dir> branches` lists `main` alone, as the
            branch the lamp is on, and saving, listing and restoring work as before.

            The scenario writes no old history itself. It checks that a lamp saved and restored by
            this oillamp has exactly those two files, and no other branch.
        """
        given: 'a lamp with two snapshots'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'one\n')
            var first = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            write(home(lamp), 'workspace/plan.md', 'two\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when: '`oillamp history <dir> restore` brings back the first snapshot, and `oillamp history <dir>` lists the snapshots'
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', first.shortId())
            var listed = host.oillamp.run('history', lamp.toString())

        then: 'restoring and listing work as before'
            restored.succeeded()
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'one\n'
            listed.events().find { it instanceof LampEvent.History }.snapshots()*.kind() ==
                    [LampSaveKind.RESTORE, LampSaveKind.IDLE, LampSaveKind.IDLE]

        and: 'the history has the one branch `main`, in the files an older oillamp wrote'
            var repository = lamp.resolve('.oillamp/history')
            Files.readString(repository.resolve('HEAD')) == 'ref: refs/heads/main\n'
            Files.list(repository.resolve('refs/heads')).withCloseable { it.collect { it.fileName.toString() } } == ['main']
            branchesOf(lamp).collect { [it.name(), it.current()] } == [['main', true]]
    }

    @IgnoreIf({ !['/usr/bin/git', '/bin/git', '/usr/local/bin/git'].any { Files.isExecutable(Path.of(it)) } })
    def '`git` itself lists the branches of a lamp\'s history, and their snapshots'() {
        reportInfo """
            oillamp writes the history in git's format itself, without running git: a branch is a
            file under `refs/heads/` naming its newest snapshot, and `HEAD` names the branch the lamp
            is on. So someone who knows git can read a lamp's history without oillamp:
            `git --git-dir=<lamp>/.oillamp/history branch` lists the branches and marks the one the
            lamp is on, and `git log --all` lists the snapshots of every branch. The scenario runs
            only where git is installed.
        """
        given: 'a lamp on `rust-rewrite`, with a snapshot on both branches and one on each alone'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save', '-m', 'shared').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.txt', 'main\n')
            var onMain = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'main only'))
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'rust-rewrite').succeeded()
            write(home(lamp), 'workspace/main.rs', 'fn main() {}\n')
            var onRust = saved(host.oillamp.run('history', lamp.toString(), 'save', '-m', 'rust only'))
            var repository = lamp.resolve('.oillamp/history')

        expect: '`git branch` lists both branches, and marks `rust-rewrite` with `*`'
            git(repository, 'branch', '--list').readLines()*.trim().sort() == ['* rust-rewrite', 'main']

        and: '`git rev-parse` names the newest snapshot of `main`, and `HEAD` that of `rust-rewrite`'
            git(repository, 'rev-parse', 'main').trim() == onMain.id()
            git(repository, 'rev-parse', 'HEAD').trim() == onRust.id()

        and: '`git log --all` lists the snapshots of both branches'
            git(repository, 'log', '--all', '--format=%s').readLines().containsAll(
                    ['idle save: main only', 'idle save: rust only', 'idle save: shared'])
    }

    @IgnoreIf({ !['/usr/bin/git', '/bin/git', '/usr/local/bin/git'].any { Files.isExecutable(Path.of(it)) } })
    def 'Oillamp lists, switches to and deletes branches that git packed into the file `packed-refs`'() {
        reportInfo """
            git keeps each branch in a file of its own under `refs/heads/`, until a command such as
            `git pack-refs` or `git gc` moves them all into the one file `packed-refs`. Someone may
            run such a command on a lamp's history by hand. oillamp still finds the branches in
            `packed-refs`: it lists them, switches between them, saves to them, and deletes one by
            removing its line from that file, so that git no longer lists it either. The scenario
            runs only where git is installed.
        """
        given: 'a lamp with the branches `main` and `other`, packed with `git pack-refs --all`'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/plan.md', 'one\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            write(home(lamp), 'workspace/plan.md', 'two\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            var repository = lamp.resolve('.oillamp/history')
            git(repository, 'pack-refs', '--all')
            assert !Files.exists(repository.resolve('refs/heads/other'))

        expect: '`oillamp history <dir> branches` lists both'
            branchesOf(lamp)*.name() == ['main', 'other']

        when: '`oillamp history <dir> switch other` is run, and a change is saved there'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()
            write(home(lamp), 'workspace/other.txt', 'other\n')
            var onOther = saved(host.oillamp.run('history', lamp.toString(), 'save'))

        then: 'the home is as `other` holds it, and git names the new snapshot as the newest of `other`'
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'one\n'
            git(repository, 'rev-parse', 'other').trim() == onOther.id()

        when: '`oillamp history <dir> switch main` is run, then `oillamp history <dir> branch --delete other --yes`'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'branch', '--delete', 'other', '--yes').succeeded()

        then: 'neither oillamp nor git lists `other` any more'
            branchesOf(lamp)*.name() == ['main']
            git(repository, 'branch', '--list').readLines()*.trim() == ['* main']
    }

    def 'Oillamp ignores the #state temporary file of an interrupted branch write'() {
        reportInfo """
            To save, or to start a branch, oillamp writes the new snapshot's commit id to a temporary
            file beside the branch's file in `refs/heads`, then renames it over the branch's file. A
            process interrupted in between leaves the temporary file behind: empty, partly written,
            or already holding a whole commit id. While a write is in progress, another oillamp that
            reads the history can see it too.

            Such a file is not a branch, even when it holds a whole commit id. oillamp lists only
            the real branches and their snapshots, and still starts a branch and switches to it. The
            scenario writes such a file itself, rather than interrupting a process at the right
            moment.
        """
        given: 'a saved lamp, and the temporary file an interrupted branch write left in `refs/heads`'
            var lamp = aLampThatHasRun()
            var snapshot = saved(host.oillamp.run('history', lamp.toString(), 'save'))
            var temporary = lamp.resolve('.oillamp/history/refs/heads/.oillamp-interrupted.tmp')
            Files.writeString(temporary, content(snapshot.id()))

        expect: 'only `main` and its snapshot are listed'
            branchesOf(lamp)*.name() == ['main']
            var all = host.oillamp.run('history', lamp.toString(), 'list', '--all')
            all.succeeded()
            all.events().find { it instanceof LampEvent.HistoryOfAllBranches }.snapshots()
                    .collect { [it.snapshot().id(), it.branches().toList()] } == [[snapshot.id(), ['main']]]

        when: '`oillamp history <dir> branch other` is run, then `oillamp history <dir> switch other`'
            var created = host.oillamp.run('history', lamp.toString(), 'branch', 'other')
            var switched = host.oillamp.run('history', lamp.toString(), 'switch', 'other')

        then: 'both succeed, and the branches are `main` and `other`, with the lamp on `other`'
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

    def 'An application keeps several states of a lamp with the branch methods of `LampDirectory`'() {
        reportInfo """
            An application built on oillamp's Java API may offer its user several states of one lamp,
            with buttons of its own. `LampDirectory.branch` starts a branch, `branches` lists them
            as `LampBranch` values, `switchTo` switches, and `historyOfAllBranches` lists every
            snapshot with the branches it is on. `wouldReset` and `wouldDeleteBranch` say what
            `reset` and `deleteBranch` would throw away, so that the application can ask its user
            first. Each runs the same `oillamp history` command as the command line, and a refusal
            arrives as a `LampFailedException` that carries the problem.
        """
        given: 'a lamp saved once, held as a `LampDirectory`'
            var lamp = aLampThatHasRun()
            var lamps = Lamp.at(lamp).launchedBy(host.launcher)
            write(home(lamp), 'workspace/plan.md', 'original\n')
            var original = lamps.save('original').orElseThrow()

        when: '`branch` starts `rust-rewrite`, `switchTo` moves the lamp there, and `save` saves a change'
            var started = lamps.branch('rust-rewrite')
            var switched = lamps.switchTo('rust-rewrite')
            Files.writeString(home(lamp).resolve('workspace/plan.md'), 'in Rust\n')
            var rust = lamps.save('in Rust').orElseThrow()

        then: '`branches`, `history` and `historyOfAllBranches` show the new snapshot on `rust-rewrite` alone'
            started.newest().get().id() == original.id()
            switched.name() == 'rust-rewrite' && switched.current()
            lamps.branches().collect { [it.name(), it.current(), it.newest().get().id()] } ==
                    [['main', false, original.id()], ['rust-rewrite', true, rust.id()]]
            lamps.history()*.id() == [rust.id(), original.id()]
            lamps.historyOfAllBranches().collect { [it.snapshot().id(), it.branches().toList()] } ==
                    [[rust.id(), ['rust-rewrite']], [original.id(), ['main', 'rust-rewrite']]]

        when: '`wouldReset` says what a reset to the original would lose, then `reset` does it'
            Files.writeString(home(lamp).resolve('workspace/plan.md'), 'in Rust, not saved\n')
            var wouldLose = lamps.wouldReset(original.shortId())
            var reset = lamps.reset(original.shortId())

        then: 'both count 1 snapshot and unsaved changes, and `plan.md` is the original again'
            wouldLose.snapshots() == 1 && wouldLose.unsaved()
            reset.snapshots() == 1 && reset.unsaved()
            Files.readString(home(lamp).resolve('workspace/plan.md')) == 'original\n'

        when: '`branch` is asked to start a branch at the Rust snapshot by its id, though no branch holds it now'
            lamps.branch('rust-again', rust.id())

        then: 'it throws a `LampFailedException` with `OIL-HISTORY-001`: a snapshot no branch leads to cannot be named'
            var gone = thrown(LampFailedException)
            gone.problem().code().value() == 'OIL-HISTORY-001'

        when: '`switchTo` moves the lamp to `main`, then `wouldDeleteBranch` and `deleteBranch` are called for `rust-rewrite`'
            lamps.switchTo('main')
            var wouldGo = lamps.wouldDeleteBranch('rust-rewrite')
            var deleted = lamps.deleteBranch('rust-rewrite')

        then: 'no snapshot would be left on no branch, and only `main` is left'
            wouldGo.snapshots() == 0
            deleted.branch() == 'rust-rewrite'
            lamps.branches()*.name() == ['main']

        when: '`deleteBranch` is called for `main`, the branch the lamp is on'
            lamps.deleteBranch('main')

        then: 'it throws a `LampFailedException` with `OIL-HISTORY-011` and the status `USAGE`'
            var refused = thrown(LampFailedException)
            refused.problem().code().value() == 'OIL-HISTORY-011'
            refused.status() == LampExitStatus.USAGE
    }

    def 'An application starts a branch while its session runs, with `Lamp.save` and `Lamp.branch`'() {
        reportInfo """
            Starting a branch only writes the new branch's file into the history, so an application
            built on oillamp's Java API can do it while the session runs, without stopping the agent.
            Let's consider an application that holds a running session as a `Lamp`, before the agent
            tries something risky. `Lamp.save` takes a snapshot of the lamp as it is now, and
            `Lamp.branch` starts a branch at it, to come back to later. The lamp stays on the branch
            it was on.
        """
        given: 'a lamp the application started, running'
            var directory = aLampThatHasRun()
            var lamp = Lamp.at(directory).launchedBy(host.launcher).start()
            assert lamp.awaitRunning(Duration.ofSeconds(30))

        when: '`Lamp.save` takes a snapshot, and `Lamp.branch` starts `before-risk` at it'
            write(home(directory), 'workspace/plan.md', 'before the risky part\n')
            var snapshot = lamp.save('before the risky part').orElseThrow()
            var branch = lamp.branch('before-risk')

        then: 'the branch starts at that snapshot, and the lamp is still on `main`'
            branch.name() == 'before-risk'
            branch.newest().get().id() == snapshot.id()
            !branch.current()
            lamp.branches().find { it.current() }.name() == 'main'

        cleanup:
            lamp?.close()
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** Takes the write permission from `directory` and everything in it; the closure returned gives it back. */
    private static Closure readOnly(Path directory) {
        Map<Path, Set<PosixFilePermission>> was = [:]
        Files.walk(directory).withCloseable { it.toList() }.findAll { !Files.isSymbolicLink(it) }.each { path ->
            was[path] = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
            Files.setPosixFilePermissions(path, was[path] - [PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE])
        }
        return { was.each { path, permissions -> Files.setPosixFilePermissions(path, permissions) } }
    }

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
