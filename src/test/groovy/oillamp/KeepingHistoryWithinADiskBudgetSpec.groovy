package oillamp

import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampExitStatus
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture

/**
 * History retains complete snapshots, sharing identical objects between saves and branches.
 * Retention cuts old ancestry at Git shallow boundaries, then deletes only objects no retained
 * snapshot needs. Retained ids and branch states stay the same, even if their fork is gone.
 * Small fractional-GB budgets and deterministic, poorly compressible files exercise real disk
 * reclamation without making the scenarios write gigabytes.
 */
class KeepingHistoryWithinADiskBudgetSpec extends Specification {
    @TempDir Path tmp
    ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen').clockAt(Instant.parse('2026-09-22T14:15:03Z')) }
    }

    def 'Every new snapshot makes room by pruning the oldest states, with the first snapshot as a fixed allowance'() {
        reportInfo '''
            The configured history size is extra space beyond the first snapshot, not a cap on
            the user's initial home. A large initial home must therefore fit even with a small
            allowance. Each new snapshot applies retention immediately and reports which saved
            states were removed, their branches, and how many compressed bytes were reclaimed.
            The newest state keeps its id and content; a retained shallow boundary tells Git
            where earlier history ends. The original allowance stays fixed after its snapshot goes.
        '''
        given: 'an initial file larger than the extra allowance'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_total_gb = 0.00003\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def first = directory.save('').orElseThrow()
            def baseline = Long.parseLong(Files.readString(lamp.resolve('.oillamp/history/initial-bytes')).trim())

        when: 'the file is replaced with a second version and saved'
            writeFileVersion(file, 2)
            def outcome = host.oillamp.run('history', lamp.toString(), 'save')
            def second = outcome.events().find { it instanceof LampEvent.Saved }.snapshot()
            def pruned = outcome.events().find { it instanceof LampEvent.HistoryPruned }

        then: 'the first snapshot is discarded, its space reclaimed, and the allowance includes its original size'
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

        when: 'a third version is saved'
            writeFileVersion(file, 3)
            def third = directory.save('').orElseThrow()

        then: 'the allowance is still the first snapshot size and the newest snapshot is kept'
            Long.parseLong(Files.readString(lamp.resolve('.oillamp/history/initial-bytes')).trim()) == baseline
            directory.history()*.id() == [third.id()]
    }

    def 'Both branches keep their saved homes when pruning removes their common ancestor'() {
        reportInfo '''
            All branches share one allowance. A branch's newest snapshot is protected, even an
            old branch the user has not visited recently. Keeping it does not require keeping
            the ancient fork or the other branch's intervening states. Once those are discarded,
            the branches may be disconnected; each still restores its exact saved home and ids.
            Identical file contents remain shared regardless of whether ancestry is connected.
        '''
        given: 'two branches with a common saved home'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_total_gb = 0.00003\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def fork = directory.save('').orElseThrow()
            assert host.oillamp.run('history', lamp.toString(), 'branch', 'other').succeeded()
            writeFileVersion(file, 2)
            def main = directory.save('').orElseThrow()
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()

        when: 'the other branch saves its own version'
            writeFileVersion(file, 3)
            def other = directory.save('').orElseThrow()

        then: 'the fork is gone but both branch tips survive unchanged'
            def branches = host.oillamp.run('history', lamp.toString(), 'branches').events()
                    .find { it instanceof LampEvent.Branches }.branches()
            branches.collect { [it.name(), it.newest().get().id()] } == [['main', main.id()], ['other', other.id()]]
            !Files.exists(commitObjectFile(lamp, fork.id()))
            directory.history()*.id() == [other.id()]

        when: 'the user switches to main'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'main').succeeded()

        then: 'main has its own saved bytes'
            Files.readAllBytes(file) == fileVersion(2)

        when: 'the user switches to other'
            assert host.oillamp.run('history', lamp.toString(), 'switch', 'other').succeeded()

        then: 'other has its own saved bytes'
            Files.readAllBytes(file) == fileVersion(3)

        and: 'Git accepts the disconnected shallow history when installed'
            if (Files.isExecutable(Path.of('/usr/bin/git'))) {
                def process = new ProcessBuilder('git', '--git-dir=' + lamp.resolve('.oillamp/history'), 'fsck', '--full').start()
                def error = process.errorStream.text
                assert process.waitFor() == 0 : error
            }
    }

    def 'A user can preview pruning without changes and apply a smaller allowance explicitly'() {
        reportInfo '''
            Zero disables automatic retention. The user may later set a smaller extra allowance
            and ask history prune what would go. The preview neither cuts ancestry nor removes
            objects. Confirming with --yes applies the same selection and emits HistoryPruned,
            so an application can present the result just as it presents automatic pruning.
        '''
        given: 'two saved versions, initially kept with automatic pruning disabled'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_total_gb = 0\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def first = directory.save('').orElseThrow()
            writeFileVersion(file, 2)
            def newest = directory.save('').orElseThrow()

        and: 'the user lowers the extra allowance without making a new snapshot'
            Files.writeString(lamp.resolve('oillamp.toml'), 'schema_version = 1\n[history]\nmax_total_gb = 0.00003\n')

        when: 'the user asks the command line for a pruning preview'
            def preview = host.oillamp.run('history', lamp.toString(), 'prune')

        then: 'it describes cleanup without removing either snapshot or its objects'
            preview.status() == LampExitStatus.USAGE
            preview.events().any { it instanceof LampEvent.HistoryPrunePlanned }
            directory.history()*.id() == [newest.id(), first.id()]
            Files.exists(commitObjectFile(lamp, first.id()))

        and: 'an application can obtain the same preview through LampDirectory'
            directory.wouldPruneHistory().orElseThrow().removed()*.snapshot()*.id() == [first.id()]

        when: 'the application applies the configured retention'
            def applied = directory.pruneHistory().orElseThrow()

        then: 'the older version is removed and the newest snapshot keeps its id'
            applied.removed()*.snapshot()*.id() == [first.id()]
            directory.history()*.id() == [newest.id()]
    }

    def 'An unchanged save makes no pruning event and an unsupported Git ref leaves history intact'() {
        reportInfo '''
            Retention accompanies growth, so saving an unchanged home must not claim cleanup
            happened. If a person adds a Git tag, automatic cleanup refuses that unsupported layout
            rather than deleting tagged objects. The new snapshot still succeeds, a warning
            explains why cleanup could not run, and all previous history remains readable.
        '''
        given: 'a lamp with one saved file version'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_total_gb = 0.00003\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def first = directory.save('').orElseThrow()

        when: 'the user saves again without changing anything'
            def unchanged = host.oillamp.run('history', lamp.toString(), 'save')

        then: 'the save succeeds without reporting pruning'
            unchanged.succeeded()
            !unchanged.events().any { it instanceof LampEvent.HistoryPruned }

        when: 'a tag protects the old state and the user saves a new version'
            Files.writeString(lamp.resolve('.oillamp/history/refs/tags/keep'), first.id() + '\n')
            writeFileVersion(file, 2)
            def result = host.oillamp.run('history', lamp.toString(), 'save')

        then: 'the new save succeeds, but warns that the tagged history cannot be pruned'
            result.succeeded()
            result.events().any { it instanceof LampEvent.Warning && it.problem().whatHappened().contains('pruning failed') }
            Files.exists(commitObjectFile(lamp, first.id()))
            directory.history().size() == 2
    }

    def 'A save while the agent works keeps its starting snapshot reachable and pruning does not reuse its run number'() {
        reportInfo '''
            Automatic pruning also runs on saves made while the agent is working. Its starting
            snapshot must stay reachable until the run finishes, even if a user saves several
            newer states in between. Readers and the sweep share the history lock. After the run
            releases that starting snapshot, later saves can prune it normally. Run numbers from
            automatically discarded snapshots are remembered outside restored homes, so a later
            session never gives a conversation the same run number just because history shrank.
        '''
        given: 'an agent held in the middle of its answer'
            def lampPath = host.lampPath()
            host.givenConfig(lampPath, 'schema_version = 1\n[history]\nmax_total_gb = 0.00003\n')
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

        then: 'the run base is still reachable despite the budget pressure'
            directory.history()*.id().contains(first.id())

        when: 'the run ends and a later save makes room again'
            finish.countDown()
            def ran = answer.get(30, TimeUnit.SECONDS)
            lamp.close()
            writeFileVersion(file, 4)
            assert directory.save('').isPresent()

        then: 'its old snapshots can go'
            ran.run().id() == 'run-1'
            !directory.history()*.id().contains(first.id())
            !directory.history()*.id().contains(ran.snapshot().get().id())

        when: 'the next session asks again'
            lamp = Lamp.at(lampPath).launchedBy(host.launcher).start()
            assert lamp.awaitRunning(Duration.ofSeconds(30))
            def next = lamp.ask('Again')

        then: 'the discarded run number is not reused'
            next.run().id() == 'run-2'

        cleanup: 'release the stand-in agent and close the session even if an assertion failed'
            finish?.countDown()
            lamp?.close()
    }

    def 'A restore protects its target and safety snapshot even when the safety save triggers retention'() {
        reportInfo '''
            A restore first saves unsaved work, which can itself trigger pruning. Its chosen
            target and the safety snapshot needed to undo the restore remain protected during
            that operation. Retention never deletes the objects that the restore is about to
            read. The completed restored snapshot is retained and keeps the target's exact bytes.
        '''
        given: 'two saved states kept with pruning disabled'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_total_gb = 0\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def target = directory.save('').orElseThrow()
            writeFileVersion(file, 2)
            assert directory.save('').isPresent()

        and: 'a smaller allowance and a third, unsaved version'
            Files.writeString(lamp.resolve('oillamp.toml'), 'schema_version = 1\n[history]\nmax_total_gb = 0.00003\n')
            writeFileVersion(file, 3)

        when: 'the user restores the first snapshot, causing a safety save under the smaller allowance'
            def result = host.oillamp.run('history', lamp.toString(), 'restore', target.id())

        then: 'restoring succeeds and its target remains available with the original file contents'
            result.succeeded()
            result.events().any { it instanceof LampEvent.Restored }
            Files.readAllBytes(file) == fileVersion(1)
            directory.history()*.id().contains(target.id())

        when: 'the user undoes the restore using the safety snapshot reported by Oillamp'
            def safety = result.events().find { it instanceof LampEvent.Saved }.snapshot()
            directory.restore(safety.id())

        then: 'the previously unsaved third version returns'
            Files.readAllBytes(file) == fileVersion(3)
    }

    def 'A saved valid configuration can be restored even when the current configuration is #damage'() {
        reportInfo '''
            The configuration is part of a snapshot. Restoring it is how a user recovers after
            an invalid edit, so validating the broken current configuration must not block recovery.
            Oillamp warns that retention is temporarily disabled, saves the damaged state for undo,
            and restores the valid configuration without pruning either recovery state.
        '''
        given: 'a saved valid configuration followed by a broken edit'
            def lamp = host.lampPath()
            def valid = 'schema_version = 1\n[history]\nmax_total_gb = 2\n'
            host.givenConfig(lamp, valid)
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def saved = directory.save('').orElseThrow()
            Files.writeString(lamp.resolve('oillamp.toml'), broken)

        when: 'the user restores the saved snapshot through the command line'
            def result = host.oillamp.run('history', lamp.toString(), 'restore', saved.id())

        then: 'recovery succeeds and explains why it skipped retention'
            result.succeeded()
            Files.readString(lamp.resolve('oillamp.toml')) == valid
            result.events().any { it instanceof LampEvent.Warning && it.problem().whatHappened().contains('configuration is invalid') }
            !result.events().any { it instanceof LampEvent.HistoryPruned }

        where: 'both malformed syntax and invalid values remain recoverable'
            damage            | broken
            'malformed TOML'  | 'schema_version = [\n'
            'an invalid size' | 'schema_version = 1\n[history]\nmax_total_gb = -1\n'
    }

    def 'A stopped lamp ignores a dead run pin and reclaims its abandoned temporary object'() {
        reportInfo '''
            A terminated process can leave a starting-snapshot pin and an incomplete object file.
            They protect no live work once the lamp is stopped. Preview ignores the stale pin
            without changing it; confirmed pruning removes the pin and temporary bytes and reports
            their reclaimed space alongside the old snapshots. This needs no precisely timed crash.
        '''
        given: 'a stopped lamp with two versions and files left by a terminated run'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_total_gb = 0\n')
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
            Files.writeString(lamp.resolve('oillamp.toml'), 'schema_version = 1\n[history]\nmax_total_gb = 0.00003\n')

        when: 'the application previews cleanup'
            def preview = directory.wouldPruneHistory().orElseThrow()

        then: 'the old snapshot is eligible, but the crash leftovers are untouched'
            preview.removed()*.snapshot()*.id() == [first.id()]
            Files.exists(pin)
            Files.exists(abandoned)

        when: 'the application confirms cleanup'
            def applied = directory.pruneHistory().orElseThrow()

        then: 'the dead pin and temporary object are removed and their bytes are counted'
            !Files.exists(pin)
            !Files.exists(abandoned)
            applied.bytesFreed() > 120000
            directory.history()*.id() == [last.id()]
    }

    def 'A pruning listener can refresh history before the save returns'() {
        reportInfo '''
            A GUI or logger may respond to a pruning event by reading the remaining history.
            Oillamp must release the history lock before invoking that listener, otherwise its
            own synchronous callback waits for the save which is waiting for the callback.
        '''
        given: 'a saved version and a listener which immediately reads history'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_total_gb = 0.00003\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            assert Lamp.at(lamp).launchedBy(host.launcher).save('').isPresent()
            def refreshed = []
            def engine = host.oillamp.observedBy { event ->
                if (event instanceof LampEvent.HistoryPruned)
                    refreshed << host.oillamp.run('history', lamp.toString())
            }

        when: 'a new version triggers pruning'
            writeFileVersion(file, 2)
            def result = CompletableFuture.supplyAsync { engine.run('history', lamp.toString(), 'save') }
                    .get(15, TimeUnit.SECONDS)

        then: 'the callback and save both complete and the refresh shows only the new state'
            result.succeeded()
            refreshed.size() == 1
            refreshed.first().succeeded()
            refreshed.first().events().find { it instanceof LampEvent.History }.snapshots().size() == 1
    }

    def 'Disabled retention does not inspect unrelated object files when another snapshot is saved'() {
        reportInfo '''
            Zero disables cleanup, rather than merely giving it an infinite target. Once the
            first snapshot allowance has been measured, subsequent saves need no retention scan.
            An unsupported file elsewhere in the object directory must not produce a pruning
            warning when pruning was explicitly disabled; ordinary loose-object saves still work.
        '''
        given: 'a saved lamp with retention disabled and an unrelated pack-shaped file'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_total_gb = 0\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            assert directory.save('').isPresent()
            def pack = lamp.resolve('.oillamp/history/objects/pack/unsupported.pack')
            Files.createDirectories(pack.parent)
            Files.writeString(pack, 'not read by disabled retention')
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)

        when: 'the user saves a changed home'
            def result = host.oillamp.run('history', lamp.toString(), 'save')

        then: 'the snapshot succeeds without a cleanup warning or pruning event'
            result.succeeded()
            result.events().any { it instanceof LampEvent.Saved }
            !result.events().any { it instanceof LampEvent.Warning || it instanceof LampEvent.HistoryPruned }
    }

    def 'A partial object sweep reports the history cut and only the bytes it actually reclaimed'() {
        reportInfo '''
            Cutting ancestry and reclaiming its files are separate steps. If permissions prevent
            some unneeded objects from being deleted, the cut is already effective. The application
            must still learn which snapshots became unavailable, receive a cleanup warning, and see
            actual reclaimed bytes rather than the planned total. Repairing permissions allows retry.
        '''
        given: 'two saved states and object directories which cannot be written'
            def lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n[history]\nmax_total_gb = 0\n')
            assert host.oillamp.run('at', lamp.toString()).succeeded()
            def directory = Lamp.at(lamp).launchedBy(host.launcher)
            def file = Lamp.agentHome(lamp).orElseThrow().resolve('workspace/data.bin')
            writeFileVersion(file, 1)
            def first = directory.save('').orElseThrow()
            writeFileVersion(file, 2)
            def last = directory.save('').orElseThrow()
            Files.writeString(lamp.resolve('oillamp.toml'), 'schema_version = 1\n[history]\nmax_total_gb = 0.00003\n')
            def dirs = Files.list(lamp.resolve('.oillamp/history/objects')).withCloseable { it.filter { Files.isDirectory(it) }.toList() }
            dirs.each { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString('r-x------')) }

        when: 'the user confirms pruning despite the unwritable object directories'
            def result = host.oillamp.run('history', lamp.toString(), 'prune', '--yes')
            def cut = result.events().find { it instanceof LampEvent.HistoryPruned }

        then: 'the event reports the removed state and no falsely reclaimed bytes'
            result.succeeded()
            cut.removed()*.snapshot()*.id() == [first.id()]
            cut.bytesFreed() == 0
            result.events().any { it instanceof LampEvent.Warning }
            directory.history()*.id() == [last.id()]

        when: 'the user restores directory permissions and retries'
            dirs.each { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString('rwx------')) }
            def retried = directory.pruneHistory().orElseThrow()

        then: 'the remaining garbage is reclaimed without reporting the same cut twice'
            retried.removed().isEmpty()
            retried.bytesFreed() > 60000

        cleanup: 'restore permissions so the temporary lamp can be removed'
            dirs?.each { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString('rwx------')) }
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
