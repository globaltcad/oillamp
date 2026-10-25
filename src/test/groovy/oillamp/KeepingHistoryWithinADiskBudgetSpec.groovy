package oillamp

import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampExitStatus
import dev.lamp.LampFailedException
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture

/**
 * A lamp's history holds every snapshot whole, and a file that is the same in two snapshots, or on
 * two branches, is stored once. To keep the history within {@code history.max_growth_gb} on top of
 * the first snapshot, oillamp deletes the snapshots saved longest ago, and with them every stored
 * file that no remaining snapshot holds. The snapshots that remain keep their ids and their
 * content, even once the snapshot a branch was started at is gone.
 *
 * The scenarios set limits of a small fraction of a GB and save files of 64 KB of random bytes,
 * which do not compress, so that files really are deleted from disk without writing gigabytes.
 */
class KeepingHistoryWithinADiskBudgetSpec extends Specification {
    @TempDir Path tmp
    ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen').clockAt(Instant.parse('2026-09-22T14:15:03Z')) }
    }

    def 'A save deletes the oldest snapshot once the history grows more than `history.max_growth_gb` beyond its first snapshot'() {
        reportInfo '''
            Let's consider a user who puts a 64 KB file into a new lamp and saves it. This first snapshot
            is the lamp's baseline: what the user started with. `history.max_growth_gb` says how much the
            history may grow on top of it, and here that is about 32 KB, less than one more version of the
            file.

            The user replaces the file with a second version and saves again. The history has now grown by
            64 KB, more than it may, so the same save deletes the first snapshot. It reports this in a
            `HistoryPruned` event: which snapshot it deleted, on which branch, and how many bytes that freed
            on disk. The second snapshot keeps its id and its content.

            The baseline is measured once, as the first snapshot is saved, and stays the same after that
            snapshot is deleted. A third version is measured against the same limit.
        '''
        given: 'a lamp whose first snapshot holds a 64 KB file, with room for about 32 KB more'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_growth_gb = 0.00003\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def first = directory.save('').orElseThrow()
            def baseline = Long.parseLong(Files.readString(lamp.resolve('.oillamp/history/initial-bytes')).trim())

        when: 'the user saves a second version of the file'
            writeFileVersion(file, 2)
            def outcome = host.oillamp.run('history', lamp.toString(), 'save')
            def second = outcome.events().find { it instanceof LampEvent.Saved }.snapshot()
            def pruned = outcome.events().find { it instanceof LampEvent.HistoryPruned }

        then: 'the first snapshot is deleted, its files are gone from disk, and the limit counts on top of its size'
            outcome.succeeded()
            pruned.removed()*.snapshot()*.id() == [first.id()]
            pruned.removed().first().branches().toList() == ['main']
            pruned.bytesFreed() > 60000
            pruned.limitBytes() == baseline + (long) (0.00003 * 1024 * 1024 * 1024)
            pruned.remainingBytes() <= pruned.limitBytes()
            directory.history()*.id() == [second.id()]
            Files.readString(lamp.resolve('.oillamp/history/shallow')).trim() == second.id()
            !Files.exists(commitObjectFile(lamp, first.id()))
            LampEvent.fromJson(pruned.toJson()).orElseThrow() == pruned

        when: 'the user saves a third version'
            writeFileVersion(file, 3)
            def third = directory.save('').orElseThrow()

        then: 'the limit still counts on top of the first snapshot, and only the newest snapshot is left'
            Long.parseLong(Files.readString(lamp.resolve('.oillamp/history/initial-bytes')).trim()) == baseline
            directory.history()*.id() == [third.id()]
    }

    def 'Each branch keeps its newest snapshot when the snapshot both were started at is deleted'() {
        reportInfo '''
            Let's consider a lamp saved once, after which the user starts the branch `other` at that
            snapshot. On `main` they save a second version of a file, then switch to `other` and save a
            third. All branches share one `history.max_growth_gb`, and here it leaves room for less than one
            more version on top of the first snapshot.

            oillamp keeps the newest snapshot of every branch, whatever the limit, because that is the state
            the lamp comes back to when the user switches to the branch. The snapshot both branches were
            started at is not needed for that, since every snapshot holds the whole lamp. So the third save
            deletes it, and the two branches no longer have a snapshot in common. Switching between them
            still brings back each branch's own version of the file, and git itself, when it is installed,
            finds nothing wrong with the history.
        '''
        given: 'a snapshot, the branch other started at it, and a second version saved on main'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_growth_gb = 0.00003\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def fork = directory.save('').orElseThrow()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            writeFileVersion(file, 2)
            def main = directory.save('').orElseThrow()
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()

        when: 'the user saves a third version on other'
            writeFileVersion(file, 3)
            def other = directory.save('').orElseThrow()

        then: 'the snapshot both were started at is deleted, and each branch keeps its newest snapshot'
            def branches = host.oillamp.run('history', lamp.toString(), 'branches').events()
                    .find { it instanceof LampEvent.Branches }.branches()
            branches.collect { [it.name(), it.newest().get().id()] } == [['main', main.id()], ['other', other.id()]]
            !Files.exists(commitObjectFile(lamp, fork.id()))
            directory.history()*.id() == [other.id()]

        when: 'the user switches to main'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()

        then: 'the file is main\'s version'
            Files.readAllBytes(file) == fileVersion(2)

        when: 'the user switches to other'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()

        then: 'the file is other\'s version'
            Files.readAllBytes(file) == fileVersion(3)

        and: 'git finds nothing wrong with the history, when git is installed'
            if (Files.isExecutable(Path.of('/usr/bin/git'))) {
                def process = new ProcessBuilder('git', '--git-dir=' + lamp.resolve('.oillamp/history'), 'fsck', '--full').start()
                def error = process.errorStream.text
                assert process.waitFor() == 0 : error
            }
    }

    def '`oillamp history <dir> prune` previews which snapshots it would delete, and `prune --yes` deletes them'() {
        reportInfo '''
            Let's consider a lamp saved twice while `history.max_growth_gb = 0`, which never deletes a
            snapshot. The user then lowers the limit to about 32 KB on top of the first snapshot, without
            saving. Nothing is deleted yet: only a save or `oillamp history <dir> prune --yes` deletes.

            Before anything is lost, the user runs `oillamp history <dir> prune` without `--yes`. It sends
            a `HistoryPrunePlanned` event that lists the snapshots it would delete, deletes nothing, and
            exits with status 2, as every command does that waits for `--yes`. A program that embeds
            oillamp gets the same preview from `LampDirectory.wouldPruneHistory()`. Then `LampDirectory.pruneHistory()`, which runs
            `prune --yes`, deletes the first snapshot and reports it in a `HistoryPruned` event, the same
            event a save sends when it deletes snapshots.
        '''
        given: 'two snapshots, saved while max_growth_gb = 0'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_growth_gb = 0\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def first = directory.save('').orElseThrow()
            writeFileVersion(file, 2)
            def newest = directory.save('').orElseThrow()

        and: 'the user lowers the limit without saving'
            Files.writeString(lamp.resolve('oillamp.toml'), 'schema_version = 1\n[history]\nmax_growth_gb = 0.00003\n')

        when: 'the user runs oillamp history <dir> prune without --yes'
            def preview = host.oillamp.run('history', lamp.toString(), 'prune')

        then: 'it lists the first snapshot as the one it would delete, and both snapshots are still there'
            preview.status() == LampExitStatus.USAGE
            preview.events().any { it instanceof LampEvent.HistoryPrunePlanned }
            directory.history()*.id() == [newest.id(), first.id()]
            Files.exists(commitObjectFile(lamp, first.id()))

        and: 'LampDirectory.wouldPruneHistory() gives a program that embeds oillamp the same preview'
            directory.wouldPruneHistory().orElseThrow().removed()*.snapshot()*.id() == [first.id()]

        when: 'the program calls LampDirectory.pruneHistory()'
            def applied = directory.pruneHistory().orElseThrow()

        then: 'the first snapshot is deleted, and the second keeps its id'
            applied.removed()*.snapshot()*.id() == [first.id()]
            directory.history()*.id() == [newest.id()]
    }

    def 'An application reads and changes `history.max_growth_gb` with `LampDirectory.historyMaxGrowthGb`, and only that line of `oillamp.toml` changes'() {
        reportInfo '''
            Let's consider an application, a program built on oillamp's Java API, whose settings page shows
            how far a lamp's history may grow beyond its first snapshot, and lets its user change that.
            `LampDirectory.historyMaxGrowthGb()` reads `max_growth_gb` under `[history]` in the lamp's
            `oillamp.toml`, and `LampDirectory.historyMaxGrowthGb(0.5)` writes it, each by running
            `oillamp history <dir> max-growth`. The file is also the user's, so the comment on that line
            stays, and every other line too.

            Writing the size deletes nothing by itself: the next save does, or `LampDirectory.pruneHistory()`,
            so the application can first show what `LampDirectory.wouldPruneHistory()` would delete. A size
            outside 0 to 10000 GB is refused before the engine starts, with an `IllegalArgumentException`,
            and `oillamp history <dir> max-growth lots` is refused as a usage error that says what a size is.
        '''
        given: 'a lamp with the file a new lamp gets, saved twice with two versions of a 64 KB file'
            def lamp = host.lampPath()
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def first = directory.save('').orElseThrow()
            writeFileVersion(file, 2)
            def newest = directory.save('').orElseThrow()
            def before = Files.readString(lamp.resolve('oillamp.toml'))

        expect: 'the size a new lamp gets: 2 GB beyond the first snapshot'
            directory.historyMaxGrowthGb() == 2d

        when: 'the application sets half a GB'
            def written = directory.historyMaxGrowthGb(0.5)

        then: 'the file says so on the same line, with its comment, and nothing else in it changed'
            written == 0.5d
            directory.historyMaxGrowthGb() == 0.5d
            def after = Files.readString(lamp.resolve('oillamp.toml'))
            after.contains('max_growth_gb  = 0.5        # GB beyond the first snapshot; 0 = never delete snapshots\n')
            after.readLines().findAll { !it.startsWith('max_growth_gb') } == before.readLines().findAll { !it.startsWith('max_growth_gb') }

        and: '`oillamp history <dir> max-growth` says the same'
            def shown = host.oillamp.run('history', lamp.toString(), 'max-growth')
            shown.succeeded()
            shown.events().find { it instanceof LampEvent.HistoryGrowthLimit } == new LampEvent.HistoryGrowthLimit(0.5)
            shown.console().contains('may grow 0.5 GB beyond its first snapshot')

        when: 'the user sets about 32 KB on the command line, less than one more version of the file'
            def lowered = host.oillamp.run('history', lamp.toString(), 'max-growth', '0.00003')

        then: 'nothing is deleted yet, and LampDirectory.wouldPruneHistory() names the first snapshot'
            lowered.succeeded()
            directory.historyMaxGrowthGb() == 0.00003d
            directory.history()*.id() == [newest.id(), first.id()]
            directory.wouldPruneHistory().orElseThrow().removed()*.snapshot()*.id() == [first.id()]

        when: 'the application asks for a size no lamp can have'
            directory.historyMaxGrowthGb(-1)

        then: 'it is refused before the engine starts, and the file keeps its size'
            thrown(IllegalArgumentException)
            directory.historyMaxGrowthGb() == 0.00003d

        when: 'the user types a word where the size goes'
            def wrong = host.oillamp.run('history', lamp.toString(), 'max-growth', 'lots')

        then:
            wrong.status() == LampExitStatus.USAGE
            wrong.console().contains('expected a size in GB from 0 to 10000, such as 2 or 0.5; 0 never deletes a snapshot')
    }

    def '`LampDirectory.historyMaxGrowthGb` refuses to change the size while a session runs'() {
        reportInfo '''
            Let's consider an application that holds a running session of a lamp, and whose user changes
            how far the lamp's history may grow. A session reads `oillamp.toml` only as it starts, and its
            saves prune with the size it read then, so a new `max_growth_gb` would not reach it. So
            `LampDirectory.historyMaxGrowthGb(1)` throws a `LampFailedException` with the status
            `LAMP_BUSY` while the session runs, and the file stays as it was. Reading the size works all
            the same.
        '''
        given: 'a lamp whose session runs'
            def lamp = host.lampPath()
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            host.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
            def running = directory.start()
            assert running.awaitRunning(Duration.ofSeconds(30))

        when: 'the application sets 1 GB'
            directory.historyMaxGrowthGb(1)

        then:
            def busy = thrown(LampFailedException)
            busy.status() == LampExitStatus.LAMP_BUSY
            directory.historyMaxGrowthGb() == 2d

        cleanup:
            running?.close()
    }

    def 'A save that changed nothing deletes nothing, and a git tag stops oillamp from deleting snapshots'() {
        reportInfo '''
            Let's consider a lamp with one snapshot and room for less than one more version of its file. The
            user saves again without changing anything. No snapshot is made, so the history did not grow,
            and the save sends no `HistoryPruned` event.

            Then someone tags the first snapshot with git, as `refs/tags/keep`, to keep it. oillamp knows
            only branches, and it does not delete what a tag may be there to keep. So when the user saves a
            second version, the save succeeds, a warning says that pruning failed, and both snapshots are
            still there.
        '''
        given: 'a lamp with one snapshot'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_growth_gb = 0.00003\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def first = directory.save('').orElseThrow()

        when: 'the user saves again without changing anything'
            def unchanged = host.oillamp.run('history', lamp.toString(), 'save')

        then: 'the save succeeds and reports no deleted snapshots'
            unchanged.succeeded()
            !unchanged.events().any { it instanceof LampEvent.HistoryPruned }

        when: 'a git tag points at the first snapshot, and the user saves a second version'
            Files.writeString(lamp.resolve('.oillamp/history/refs/tags/keep'), first.id() + '\n')
            writeFileVersion(file, 2)
            def result = host.oillamp.run('history', lamp.toString(), 'save')

        then: 'the save succeeds, warns that pruning failed, and deletes nothing'
            result.succeeded()
            result.events().any { it instanceof LampEvent.Warning && it.problem().whatHappened().contains('pruning failed') }
            Files.exists(commitObjectFile(lamp, first.id()))
            directory.history().size() == 2
    }

    def 'The snapshot a run started at is kept until the run ends, and the run\'s number is not given out again'() {
        reportInfo '''
            Let's consider a lamp with room for less than one more version of a file on top of its first
            snapshot. The user wakes the agent with `Lamp.ask`, and the run starts at that first snapshot. While the
            agent works, the user saves two newer versions. Each of these saves would delete the first
            snapshot to make room, but oillamp keeps it as long as the run goes on, because when the run
            ends oillamp lists what the run changed since that snapshot, and the agent reads that list in
            later prompts.

            Once the run is over, the next save deletes the old snapshots, the run's own among them. That run
            was `run-1`. oillamp numbers runs from the highest number in the history, which no longer holds
            it, so it keeps the highest deleted number in a file outside the snapshots. The next session's
            run is `run-2`, not a second `run-1`.
        '''
        given: 'the agent in the middle of a run that started at the first snapshot'
            def lampPath = host.lampPath()
            host.givenConfig(lampPath, 'schema_version = 1\n[history]\nmax_growth_gb = 0.00003\n')
            assert host.oillamp.run('at', lampPath.toString()).succeeded()
            def directory = Lamp.at(lampPath).launchedBy(host.launcher)
            def file = Lamp.agentHome(lampPath).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def first = directory.save('').orElseThrow()
            def began = new CountDownLatch(1)
            def finish = new CountDownLatch(1)
            host.machine { it.agent { prompt -> began.countDown(); assert finish.await(30, TimeUnit.SECONDS); 'Done.' } }
            def lamp = Lamp.at(lampPath).launchedBy(host.launcher).start()
            assert lamp.awaitRunning(Duration.ofSeconds(30))
            def answer = CompletableFuture.supplyAsync { lamp.ask('Work on it') }
            assert began.await(30, TimeUnit.SECONDS)

        when: 'the user saves two newer versions during the run'
            writeFileVersion(file, 2)
            assert directory.save('').isPresent()
            writeFileVersion(file, 3)
            assert directory.save('').isPresent()

        then: 'the snapshot the run started at is still there'
            directory.history()*.id().contains(first.id())

        when: 'the run ends and the user saves a fourth version'
            finish.countDown()
            def ran = answer.get(30, TimeUnit.SECONDS)
            lamp.close()
            writeFileVersion(file, 4)
            assert directory.save('').isPresent()

        then: 'the old snapshots are deleted, the run\'s own among them'
            ran.run().id() == 'run-1'
            !directory.history()*.id().contains(first.id())
            !directory.history()*.id().contains(ran.snapshot().get().id())

        when: 'the user calls Lamp.ask again in the next session'
            lamp = Lamp.at(lampPath).launchedBy(host.launcher).start()
            assert lamp.awaitRunning(Duration.ofSeconds(30))
            def next = lamp.ask('Again')

        then: 'its run is run-2, not run-1 again'
            next.run().id() == 'run-2'

        cleanup: 'release the stand-in agent and close the session even if an assertion failed'
            finish?.countDown()
            lamp?.close()
    }

    def 'A restore keeps the snapshot it goes back to, and the save that can undo it, when that save takes the history past its limit'() {
        reportInfo '''
            Let's consider a lamp with two snapshots, saved while `history.max_growth_gb = 0`. The user then
            lowers the limit to about 32 KB on top of the first snapshot, writes a third version of a file
            without saving it, and restores the first snapshot.

            A restore saves the lamp first, so that it can be undone. That save takes the history past its
            limit, and the snapshots saved longest ago would go, the first one among them. oillamp keeps the
            snapshot the restore goes back to, and every snapshot after it on the branch, the save before the
            restore among them. So the file is back at its first version, and restoring the save before the
            restore brings back the third version, which no snapshot held before.
        '''
        given: 'two snapshots, saved while max_growth_gb = 0'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_growth_gb = 0\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def target = directory.save('').orElseThrow()
            writeFileVersion(file, 2)
            assert directory.save('').isPresent()

        and: 'a lower limit, and a third version of the file that is not saved'
            Files.writeString(lamp.resolve('oillamp.toml'), 'schema_version = 1\n[history]\nmax_growth_gb = 0.00003\n')
            writeFileVersion(file, 3)

        when: 'the user restores the first snapshot'
            def result = host.oillamp.run('history', lamp.toString(), 'restore', target.id())

        then: 'the file is back at its first version, and the first snapshot is still there'
            result.succeeded()
            result.events().any { it instanceof LampEvent.Restored }
            Files.readAllBytes(file) == fileVersion(1)
            directory.history()*.id().contains(target.id())

        when: 'the user restores the save oillamp made before the restore'
            def safety = result.events().find { it instanceof LampEvent.Saved }.snapshot()
            directory.restore(safety.id())

        then: 'the third version is back'
            Files.readAllBytes(file) == fileVersion(3)
    }

    def 'A user restores a saved oillamp.toml even when the current one is #damage'() {
        reportInfo '''
            Let's consider a user who saved a lamp, then edited its `oillamp.toml` and broke it. Restoring
            the snapshot is how they get the working file back. oillamp reads the limit for deleting
            snapshots from `oillamp.toml`, and it cannot read it from a broken file; refusing the restore for
            that reason would leave the user stuck. So the restore goes ahead without deleting any snapshot,
            and a warning says that pruning was skipped because the configuration is invalid. The save
            before the restore holds the broken file, so the restore can still be undone.
        '''
        given: 'a snapshot of a working oillamp.toml, and then a broken edit'
            def lamp = host.lampPath()
            def valid = 'schema_version = 1\n[history]\nmax_growth_gb = 2\n'
            host.givenConfig(lamp, valid)
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def saved = directory.save('').orElseThrow()
            Files.writeString(lamp.resolve('oillamp.toml'), broken)

        when: 'the user restores the snapshot'
            def result = host.oillamp.run('history', lamp.toString(), 'restore', saved.id())

        then: 'the working file is back, with a warning that pruning was skipped'
            result.succeeded()
            Files.readString(lamp.resolve('oillamp.toml')) == valid
            result.events().any { it instanceof LampEvent.Warning && it.problem().whatHappened().contains('configuration is invalid') }
            !result.events().any { it instanceof LampEvent.HistoryPruned }

        where: 'the file is broken in either of two ways'
            damage            | broken
            'malformed TOML'  | 'schema_version = [\n'
            'an invalid size' | 'schema_version = 1\n[history]\nmax_growth_gb = -1\n'
    }

    def 'oillamp deletes what a killed run left in the history, once the lamp is stopped'() {
        reportInfo '''
            Let's consider an oillamp process that was killed in the middle of a run. It leaves two files
            in the history: `active-base`, which holds the id of the snapshot the run started at, so that no
            save deletes that snapshot, and a file in `objects/` that it had not finished writing. With the
            lamp stopped, no run is going on, so neither file has anything left to keep.

            A program that embeds oillamp first calls `LampDirectory.wouldPruneHistory()` to preview what
            would be deleted. The preview lists the first snapshot, although `active-base` holds its id,
            and the preview changes nothing: both files are still there. Then `LampDirectory.pruneHistory()` deletes the
            snapshot and both files, and counts the unfinished file's bytes among those it freed.
        '''
        given: 'a stopped lamp with two snapshots, and the two files a killed run left'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_growth_gb = 0\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def first = directory.save('').orElseThrow()
            writeFileVersion(file, 2)
            def last = directory.save('').orElseThrow()
            def pin = lamp.resolve('.oillamp/history/active-base')
            def abandoned = lamp.resolve('.oillamp/history/objects/incoming-abandoned.tmp')
            Files.writeString(pin, first.id() + '\n')
            Files.write(abandoned, fileVersion(3))
            Files.writeString(lamp.resolve('oillamp.toml'), 'schema_version = 1\n[history]\nmax_growth_gb = 0.00003\n')

        when: 'the program calls LampDirectory.wouldPruneHistory()'
            def preview = directory.wouldPruneHistory().orElseThrow()

        then: 'the preview lists the first snapshot, and both files are still there'
            preview.removed()*.snapshot()*.id() == [first.id()]
            Files.exists(pin)
            Files.exists(abandoned)

        when: 'the program calls LampDirectory.pruneHistory()'
            def applied = directory.pruneHistory().orElseThrow()

        then: 'both files are deleted too, and their bytes are counted as freed'
            !Files.exists(pin)
            !Files.exists(abandoned)
            applied.bytesFreed() > 120000
            directory.history()*.id() == [last.id()]
    }

    def 'A program reads the history from its listener as soon as a save reports deleted snapshots'() {
        reportInfo '''
            Let's consider a program that embeds oillamp and shows its user the lamp's snapshots. When it
            receives a `HistoryPruned` event, it reads the history again at once, from within the listener
            that received the event. oillamp therefore sends that event only after it has released the
            history's lock. Otherwise the save would wait for the listener to return, the listener would wait
            for the save to release the lock, and neither would ever finish.
        '''
        given: 'a snapshot, and a listener that reads the history when snapshots are deleted'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_growth_gb = 0.00003\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            assert Lamp.at(lamp).launchedBy(host.launcher).save('').isPresent()
            def refreshed = []
            def engine = host.oillamp.observedBy { event ->
                if (event instanceof LampEvent.HistoryPruned)
                    refreshed << host.oillamp.run('history', lamp.toString())
            }

        when: 'the user saves a second version, which deletes the first'
            writeFileVersion(file, 2)
            def result = CompletableFuture.supplyAsync { engine.run('history', lamp.toString(), 'save') }
                    .get(15, TimeUnit.SECONDS)

        then: 'both the save and the listener finish, and the listener sees only the second snapshot'
            result.succeeded()
            refreshed.size() == 1
            refreshed.first().succeeded()
            refreshed.first().events().find { it instanceof LampEvent.History }.snapshots().size() == 1
    }

    def 'With max_growth_gb = 0 a save does not look through the history\'s files'() {
        reportInfo '''
            Let's consider a lamp with `history.max_growth_gb = 0`, which never deletes a snapshot. To decide
            what to delete, oillamp looks at every file in the history's `objects/` directory, and it refuses
            to delete anything when it finds a file there that it does not know, such as one git writes when
            it packs a history. With the limit at 0 there is nothing to decide, so a save does not look. A
            file oillamp does not know, `objects/pack/unsupported.pack`, causes no warning, and the save
            succeeds as usual.
        '''
        given: 'a lamp with max_growth_gb = 0, and a file in objects/ that oillamp does not know'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_growth_gb = 0\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            assert directory.save('').isPresent()
            def pack = lamp.resolve('.oillamp/history/objects/pack/unsupported.pack')
            Files.createDirectories(pack.parent)
            Files.writeString(pack, 'not read by disabled retention')
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)

        when: 'the user saves a changed file'
            def result = host.oillamp.run('history', lamp.toString(), 'save')

        then: 'the save succeeds, with no warning and no deleted snapshots'
            result.succeeded()
            result.events().any { it instanceof LampEvent.Saved }
            !result.events().any { it instanceof LampEvent.Warning || it instanceof LampEvent.HistoryPruned }
    }

    def 'When oillamp cannot delete some files, it still lists the snapshots it deleted, and counts only the bytes it freed'() {
        reportInfo '''
            Let's consider a lamp of two snapshots whose limit requires deleting the first, and a user who
            made the directories in the history's `objects/` read-only. oillamp deletes a snapshot in two
            steps: first it records that the history now begins at the second snapshot, then it deletes the
            files no remaining snapshot holds. Here the second step fails.

            The first snapshot is gone from the history all the same, so the `HistoryPruned` event lists it
            among the deleted snapshots. The event counts 0 bytes freed, because no file was deleted, and a
            warning says why. Once the user makes the directories writable again, the next prune deletes
            those files, and its event does not list the first snapshot a second time.
        '''
        given: 'two snapshots, and directories in objects/ that cannot be written'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_growth_gb = 0\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def first = directory.save('').orElseThrow()
            writeFileVersion(file, 2)
            def last = directory.save('').orElseThrow()
            Files.writeString(lamp.resolve('oillamp.toml'), 'schema_version = 1\n[history]\nmax_growth_gb = 0.00003\n')
            def dirs = Files.list(lamp.resolve('.oillamp/history/objects')).withCloseable { it.filter { Files.isDirectory(it) }.toList() }
            dirs.each { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString('r-x------')) }

        when: 'the user has the first snapshot deleted'
            def result = host.oillamp.run('history', lamp.toString(), 'prune', '--yes')
            def cut = result.events().find { it instanceof LampEvent.HistoryPruned }

        then: 'the event lists the first snapshot as deleted and counts 0 bytes freed'
            result.succeeded()
            cut.removed()*.snapshot()*.id() == [first.id()]
            cut.bytesFreed() == 0
            result.events().any { it instanceof LampEvent.Warning }
            directory.history()*.id() == [last.id()]

        when: 'the user makes the directories writable again and prunes once more'
            dirs.each { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString('rwx------')) }
            def retried = directory.pruneHistory().orElseThrow()

        then: 'the files are deleted now, and the event does not list the first snapshot again'
            retried.removed().isEmpty()
            retried.bytesFreed() > 60000

        cleanup: 'make the directories writable, so that the scenario\'s lamp can be removed'
            dirs?.each { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString('rwx------')) }
    }

    def 'oillamp prunes the snapshots saved longest ago first, whichever branch they are on'() {
        reportInfo '''
            Let's consider a lamp whose agent has worked on the branch `main` for six days, and
            the lamp was saved once on each of those days. On the seventh day the user wants to try
            something else, starting from where the lamp was on day two. They start the branch
            `experiment` at the snapshot of day two, switch the lamp to it, and save it three times
            that day.

            Each snapshot holds a 64 KB file of random bytes, different in every snapshot, so each
            snapshot costs about the same space. Before the last of the three saves, the user sets
            `history.max_growth_gb` so low that only five of these files fit on top of the first
            snapshot. The last save therefore has to make room.

            The user expects oillamp to delete what was saved longest ago: the snapshots of
            days one to four on `main`. The three saves on `experiment` are the newest work in
            this lamp, so they stay, even though `experiment` started at an old snapshot. A
            snapshot's place in its branch says nothing about when it was made: the first save on
            `experiment` is only the third snapshot on its branch, yet it is newer than every
            snapshot on `main`.
        '''
        given: 'six days of saves on main'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_growth_gb = 0\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            def main = (1..6).collect { day ->
                host.machine { it.clockAt(Instant.parse("2026-09-0${day}T09:00:00Z")) }
                writeFileVersion(file, day)
                directory.save('').orElseThrow()
            }

        and: 'the branch experiment, started at the snapshot of day two and saved twice on day seven'
            host.machine { it.clockAt(Instant.parse('2026-09-07T08:00:00Z')) }
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'experiment', main[1].id()).succeeded()
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'experiment').succeeded()
            def experiment = ["09", "12"].collect { hour ->
                host.machine { it.clockAt(Instant.parse("2026-09-07T${hour}:00:00Z")) }
                writeFileVersion(file, 100 + (hour as int))
                directory.save('').orElseThrow()
            }

        when: 'the user lowers the limit and saves experiment a third time'
            Files.writeString(lamp.resolve('oillamp.toml'), 'schema_version = 1\n[history]\nmax_growth_gb = 0.0003\n')
            host.machine { it.clockAt(Instant.parse('2026-09-07T15:00:00Z')) }
            writeFileVersion(file, 115)
            def outcome = host.oillamp.run('history', lamp.toString(), 'save')
            experiment << outcome.events().find { it instanceof LampEvent.Saved }.snapshot()
            def pruned = outcome.events().find { it instanceof LampEvent.HistoryPruned }

        then: 'the snapshots of days one to four on main are deleted, oldest first'
            outcome.succeeded()
            pruned.removed()*.snapshot()*.id() == main[0..3]*.id()

        and: 'the three saves on experiment and the last two days of main are kept'
            directory.historyOfAllBranches()*.snapshot()*.id() as Set == (experiment + main[4..5])*.id() as Set
    }

    def 'oillamp lists a lamp\'s history while another oillamp process is saving it'() {
        reportInfo '''
            Let's consider a lamp whose agent home is large, so that saving it takes minutes.
            While oillamp saves, it holds the lock `.oillamp/history.lock`, so that no second
            save, restore, switch or reset of this lamp runs at the same time.

            Meanwhile a program that embeds oillamp calls `LampDirectory.history()` for the lamp's
            snapshots, the way it does every time it shows them to its user. A save only ever adds to the history, so
            reading the history does not have to wait for it. The program gets the snapshots at
            once, and not only once the save has finished.

            The same holds for the running session itself: while a save holds the lock, the
            session still numbers its next run and counts the agent's runs of the last 24 hours
            from the history.

            In this scenario, the scenario itself holds `.oillamp/history.lock`, just as a long
            save in another oillamp process would.
        '''
        given: 'a lamp with one snapshot'
            def lamp = host.lampPath()
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def saved = directory.save('').orElseThrow()

        and: 'another process holding the history lock, as a long save does'
            def channel = FileChannel.open(lamp.resolve('.oillamp/history.lock'),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            def held = channel.lock()

        when: 'the program calls LampDirectory.history()'
            def listed = CompletableFuture.supplyAsync { directory.history() }.get(15, TimeUnit.SECONDS)

        then: 'it gets them without waiting for the save'
            listed*.id() == [saved.id()]

        cleanup: 'the other process finishes its save'
            held?.release()
            channel?.close()
    }

    private static byte[] fileVersion(int seed) {
        def bytes = new byte[65536]
        new Random(seed).nextBytes(bytes)
        bytes
    }

    private static void writeFileVersion(Path file, int seed) {
        Files.createDirectories(file.parent)
        Files.write(file, fileVersion(seed))
    }

    private static Path commitObjectFile(Path lamp, String id) {
        lamp.resolve('.oillamp/history/objects/' + id.substring(0, 2) + '/' + id.substring(2))
    }
}
