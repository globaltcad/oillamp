package oillamp

import dev.lamp.LampExitStatus
import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampSaveKind
import dev.lamp.LampFailedException
import dev.lamp.LampJobAuthor
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
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

@Title('Travelling back in time')
@Narrative('''
An agent can wreck its own home: delete a project, break a toolchain, fill a configuration file
with nonsense. The sandbox keeps that away from the user's machine, but the work inside is still
lost. So a lamp keeps a *history*, `.oillamp/history`, beside the agent's home, where the agent
cannot see it. The history is a git repository of *snapshots*, each of the agent's home,
`oillamp.toml` and the schedule, `.oillamp/schedule.json`, as they were at one moment.

- `oillamp history <dir> save` makes a snapshot, and `oillamp history <dir>` lists them, newest
  first.
- `oillamp history <dir> restore <snapshot>` brings the lamp back to one.
- The `[autosave]` settings in `oillamp.toml` let oillamp save by itself at chosen moments, such
  as when a session starts.
- An application built on oillamp's Java API does the same through `LampDirectory`.

The files are real, in a temporary directory. A snapshot is only worth something if the exact
bytes, permissions and links come back, and a simulated disk would hide exactly the mistakes that
lose them.
''')
@Subject([OilLamp, Lamp])
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class Travelling_Back_In_Time_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread session

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def cleanup() {
        if (session?.alive) {
            host.oillamp.run('stop', host.lampPath().toString())
            session.join(20_000)
        }
    }

    def 'By default, oillamp saves nothing as a session starts or ends'() {
        reportInfo """
            Saving a large home takes time and space, and most sessions are not worth going back
            to. So the saves as a session starts and as it ends are off unless `autosave.startup`
            and `autosave.shutdown` are on, and a save by hand is always possible. Let's consider a
            new lamp whose agent writes a file during its session: after the session, the history
            is empty.
        """
        given: 'a new lamp whose agent writes a file during the session'
            var lamp = host.lampPath()
            var oillamp = host.oillamp.observedBy { event ->
                if (event instanceof LampEvent.Summary && event.title() == 'your session is up')
                    Files.writeString(home(lamp).resolve('workspace/notes.txt'), 'done for today\n')
            }

        when: '`oillamp at <lamp>` runs a session to its end'
            var ran = oillamp.run('at', lamp.toString())

        then: 'oillamp started no save and made none, and `oillamp history <dir>` lists no snapshot'
            ran.succeeded()
            !ran.events().any { it instanceof LampEvent.Saved }
            !ran.events().any { it instanceof LampEvent.StepStarted && it.step().kind() == 'SaveLamp' }
            host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots().isEmpty()
    }

    def 'With `autosave.startup` on, oillamp saves the lamp as a session starts'() {
        reportInfo """
            Nobody remembers to save before the thing that goes wrong. With `autosave.startup` on, a
            session begins with a save, made before the sandbox runs, and whatever the agent does in
            that session can be undone by going back to it. The snapshot is of the kind `STARTUP`
            and names the session it began, so that someone reading the history knows what it is.

            Saving a large home can take a while, so oillamp first prints "saving lamp state now —
            this may take a while (startup save)" and starts its activity line, rather than seeming
            stuck. Here the session changes nothing, so its end makes no second save, although
            `autosave.shutdown` is on too.
        """
        given: 'a new lamp with `autosave.startup` and `autosave.shutdown` on'
            var lamp = aLampThatSavesAtStartAndEnd()
            boolean announcedBeforeSaving = false
            var oillamp = host.oillamp.observedBy { event ->
                if (event instanceof LampEvent.StepStarted && event.step().kind() == 'SaveLamp'
                        && !announcedBeforeSaving) {
                    announcedBeforeSaving = !Files.exists(lamp.resolve('.oillamp/history/HEAD'))
                }
            }

        when: 'a session runs on it and ends, having changed nothing'
            var ran = oillamp.run('at', lamp.toString())

        then:
            ran.succeeded()

        and: 'oillamp announced the save before it wrote the history, then made one `STARTUP` snapshot, which names the session'
            announcedBeforeSaving
            ran.console().contains('saving lamp state now — this may take a while (startup save)')
            var startingSave = ran.events().findIndexOf {
                it instanceof LampEvent.StepStarted && it.step().kind() == 'SaveLamp'
            }
            startingSave < ran.events().findIndexOf { it instanceof LampEvent.Saved }
            var saved = ran.events().findAll { it instanceof LampEvent.Saved }*.snapshot()
            saved*.kind() == [LampSaveKind.STARTUP]
            saved[0].session().isPresent()

        and: 'nothing had changed when it ended, so there was no second save'
            ran.events().any { it instanceof LampEvent.Info && it.text().startsWith('nothing changed since') }
            var listed = host.oillamp.run('history', lamp.toString())
            listed.succeeded()
            var snapshots = listed.events().find { it instanceof LampEvent.History }.snapshots()
            snapshots*.kind() == [LampSaveKind.STARTUP]
    }

    def 'With `autosave.shutdown` on, oillamp saves the lamp again at the end of a session that changed it'() {
        reportInfo """
            The end of a session is the other moment worth keeping: it holds the work the agent did.
            With `autosave.shutdown` on, oillamp saves the lamp as part of the shutdown, the same one
            that runs after Ctrl-C. The save runs once the container has stopped, so that nothing
            writes to the home while the save reads it. Let's consider a lamp that saves as its
            sessions start and end, whose agent writes a file during the session.
        """
        given: 'a lamp that saves as its sessions start and end, whose agent writes a file during the session'
            var lamp = aLampThatSavesAtStartAndEnd()
            var oillamp = host.oillamp.observedBy { event ->
                if (event instanceof LampEvent.Summary && event.title() == 'your session is up')
                    Files.writeString(home(lamp).resolve('workspace/notes.txt'), 'done for today\n')
            }

        when: '`oillamp at <lamp>` runs a session to its end'
            var ran = oillamp.run('at', lamp.toString())

        then: '`oillamp history <dir>` lists the shutdown save, then the startup save, newest first'
            ran.succeeded()
            var listed = host.oillamp.run('history', lamp.toString())
            listed.succeeded()
            var snapshots = listed.events().find { it instanceof LampEvent.History }.snapshots()
            snapshots*.kind() == [LampSaveKind.SHUTDOWN, LampSaveKind.STARTUP]
    }

    def '`oillamp history <dir> save` keeps the message given, and saves nothing when nothing changed'() {
        reportInfo """
            `oillamp history <dir> save --message <text>` marks a moment, such as "before the
            upgrade", and the history lists the snapshot with that message, so that it can be found
            by it later. A second save with nothing changed would only add a snapshot identical to
            the one before, so oillamp makes none, and says so.
        """
        given: 'a lamp whose agent wrote `plan.md`'
            var lamp = aLampThatHasRun()
            Files.writeString(home(lamp).resolve('workspace/plan.md'), '# the plan\n')

        when: '`save --message "before the upgrade"` is run, then `save -m again`'
            var first = host.oillamp.run('history', lamp.toString(), 'save', '--message', 'before the upgrade')
            var second = host.oillamp.run('history', lamp.toString(), 'save', '-m', 'again')

        then: 'the first made a snapshot with the message, of the kind `IDLE`: saved while no session ran'
            first.succeeded()
            var made = first.events().find { it instanceof LampEvent.Saved }.snapshot()
            made.kind() == LampSaveKind.IDLE
            made.message() == 'before the upgrade'
            first.console().contains('saved ' + made.shortId())
            first.console().contains('saving lamp state now — this may take a while (idle save)')

        and: 'the second found nothing changed since the first, and made no snapshot'
            second.succeeded()
            !second.events().any { it instanceof LampEvent.Saved }
            second.console().contains('nothing changed since ' + made.shortId())
            second.events().any { it instanceof LampEvent.StepStarted && it.step().kind() == 'SaveLamp' }

        and: '`oillamp history <dir>` lists the snapshot with its message'
            var listed = host.oillamp.run('history', lamp.toString())
            listed.console().contains(made.shortId())
            listed.console().contains('idle save')
            listed.console().contains('before the upgrade')
    }

    def '`oillamp history <dir> save` keeps the message exactly as written, in each of the ways it can be given'() {
        reportInfo """
            `--message=text` is the same as `--message text` and `-m text`. Whatever follows
            `--message` or `-m` is the message, even when it looks like an option or is `--`
            itself, so a snapshot marked with "-- after the upgrade --" is listed with exactly
            that.
        """
        given: 'a lamp that has run'
            var lamp = aLampThatHasRun()

        when: 'the lamp changes before each save, so each one makes a snapshot'
            var notes = home(lamp).resolve('workspace/notes.txt')
            Files.writeString(notes, 'first')
            var joined = host.oillamp.run('history', lamp.toString(), 'save', '--message=before the upgrade')
            Files.writeString(notes, 'second')
            var dashed = host.oillamp.run('history', lamp.toString(), 'save', '-m', '-- after the upgrade --')
            Files.writeString(notes, 'third')
            var bare = host.oillamp.run('history', lamp.toString(), 'save', '--message', '--')

        then: 'all three saves succeed'
            [joined, dashed, bare].every { it.succeeded() }

        and: 'each snapshot has its message exactly as written'
            var joinedSnapshot = joined.events().find { it instanceof LampEvent.Saved }.snapshot()
            var dashedSnapshot = dashed.events().find { it instanceof LampEvent.Saved }.snapshot()
            var bareSnapshot = bare.events().find { it instanceof LampEvent.Saved }.snapshot()
            joinedSnapshot.message() == 'before the upgrade'
            dashedSnapshot.message() == '-- after the upgrade --'
            bareSnapshot.message() == '--'
    }

    def 'Oillamp saves a file whose content changed although its size and time of modification are as they were'() {
        reportInfo """
            Let's consider an agent home of many gigabytes, of which a run changes a few files.
            Reading all of it for every save would take minutes. So a save records, in
            `.oillamp/history/file-stats`, each file's size, time of modification, inode, and the
            time its inode last changed. The next save reuses the stored content of a file whose
            four values are unchanged, without reading it. A save records only files that last
            changed some seconds before it began, so that a change within the same tick of the
            filesystem's clock always gives the file a different time.

            `cp -p`, `rsync -t` and `tar` set a file's time of modification back to the one it had,
            and a file of fixed length, such as a database page, keeps its size. When both happen,
            only the time the inode last changed tells the new content apart, and no program can
            set that back. Here a file of four bytes is left alone for four seconds, saved, given
            four other bytes with its old time of modification, and saved again. The second
            `oillamp history <dir> save` must make a snapshot, and restoring that snapshot must
            bring the new bytes back.
        """
        given: 'a file left unchanged for four seconds, then saved'
            var lamp = aLampThatHasRun()
            var data = home(lamp).resolve('workspace/data.bin')
            Files.writeString(data, 'aaaa')
            var modified = Files.getLastModifiedTime(data)
            Thread.sleep(4_000)
            assert host.oillamp.run('history', lamp.toString(), 'save', '-m', 'first').succeeded()

        when: 'its content changes, with its size and its time of modification as they were, and it is saved again'
            Files.writeString(data, 'bbbb')
            Files.setLastModifiedTime(data, modified)
            var second = host.oillamp.run('history', lamp.toString(), 'save', '-m', 'second')

        then: 'the second save made a snapshot'
            second.succeeded()
            var made = second.events().find { it instanceof LampEvent.Saved }?.snapshot()
            made?.message() == 'second'

        when: 'the file changes once more, and the second snapshot is restored'
            Files.writeString(data, 'cccc')
            assert host.oillamp.run('history', lamp.toString(), 'restore', made.shortId()).succeeded()

        then: 'it holds the bytes the second save found'
            Files.readString(data) == 'bbbb'
    }

    def '`oillamp history <dir> restore` brings back exactly what was saved, and removes what came after'() {
        reportInfo """
            The point of a snapshot is that the agent's home comes back as it was: every byte, and
            the things that git on its own would lose. A project the agent cloned has its own `.git`
            directory, which a plain `git add` would store only as a reference to another
            repository, without its files. A private key must come back as `rw-------`, or ssh
            refuses to use it. A link must come back as a link, and an empty directory as a
            directory. A directory the agent made read-only, as Go does with its module cache, must
            neither stop the restore nor come back writable.

            Whatever appeared after the snapshot is removed, or the lamp would be a mixture of two
            moments rather than the one chosen.
        """
        given: 'a lamp whose agent has a cloned project, a key, a script, a link and odd directories'
            var lamp = aLampThatHasRun()
            var home = home(lamp)
            write(home, 'workspace/game/.git/HEAD', 'ref: refs/heads/main\n')
            write(home, 'workspace/game/.git/objects/ab/cdef', 'not really an object\n')
            write(home, 'workspace/game/src/Main.java', 'class Main {}\n')
            write(home, '.ssh/id_ed25519', 'PRIVATE KEY\n')
            chmod(home.resolve('.ssh/id_ed25519'), 'rw-------')
            chmod(home.resolve('.ssh'), 'rwx------')
            write(home, 'bin/build.sh', '#!/bin/sh\necho build\n')
            chmod(home.resolve('bin/build.sh'), 'rwxr-xr-x')
            Files.createSymbolicLink(home.resolve('latest'), Path.of('workspace/game'))
            Files.createDirectories(home.resolve('empty/inside'))
            write(home, 'go/pkg/mod/lib@v1/lib.go', 'package lib\n')
            chmod(home.resolve('go/pkg/mod/lib@v1'), 'r-xr-xr-x')
            var save = host.oillamp.run('history', lamp.toString(), 'save')
            assert save.succeeded()
            var saved = save.events().find { it instanceof LampEvent.Saved }.snapshot()

        and: 'then the home is wrecked: the project deleted, the script overwritten, the key opened to everyone, the link removed, junk added and the module\'s file deleted'
            deleteTree(home.resolve('workspace/game'))
            Files.writeString(home.resolve('bin/build.sh'), 'rm -rf everything\n')
            chmod(home.resolve('.ssh/id_ed25519'), 'rw-r--r--')
            Files.delete(home.resolve('latest'))
            write(home, 'workspace/junk.bin', 'x' * 10_000)
            chmod(home.resolve('go/pkg/mod/lib@v1'), 'rwxr-xr-x')
            Files.delete(home.resolve('go/pkg/mod/lib@v1/lib.go'))
            chmod(home.resolve('go/pkg/mod/lib@v1'), 'r-xr-xr-x')

        when: '`oillamp history <dir> restore` is run with the first eight characters of the snapshot\'s id'
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId())

        then: 'it reports the snapshot it restored'
            restored.succeeded()
            restored.events().any { it instanceof LampEvent.Restored && it.target().id() == saved.id() }

        and: 'the cloned project is back, its .git directory included'
            Files.readString(home.resolve('workspace/game/.git/HEAD')) == 'ref: refs/heads/main\n'
            Files.readString(home.resolve('workspace/game/.git/objects/ab/cdef')) == 'not really an object\n'
            Files.readString(home.resolve('workspace/game/src/Main.java')) == 'class Main {}\n'

        and: 'contents, permissions and links are as they were'
            Files.readString(home.resolve('bin/build.sh')) == '#!/bin/sh\necho build\n'
            mode(home.resolve('bin/build.sh')) == 'rwxr-xr-x'
            mode(home.resolve('.ssh/id_ed25519')) == 'rw-------'
            mode(home.resolve('.ssh')) == 'rwx------'
            Files.isSymbolicLink(home.resolve('latest'))
            Files.readSymbolicLink(home.resolve('latest')) == Path.of('workspace/game')
            Files.isDirectory(home.resolve('empty/inside'))

        and: 'the read-only directory got its file back, and is read-only again'
            Files.readString(home.resolve('go/pkg/mod/lib@v1/lib.go')) == 'package lib\n'
            mode(home.resolve('go/pkg/mod/lib@v1')) == 'r-xr-xr-x'

        and: 'what the agent added afterwards is gone'
            !Files.exists(home.resolve('workspace/junk.bin'))
    }

    def '`oillamp history <dir> restore` saves the lamp first, so that the restore can be undone'() {
        reportInfo """
            Restoring the wrong snapshot must not be a second disaster. So
            `oillamp history <dir> restore` first saves the lamp as it is, as a snapshot of the kind
            `BEFORE_RESTORE`, and prints the command that goes back to it. The restore itself adds
            a snapshot of the kind `RESTORE`, rather than removing the snapshots made after its
            target, so undoing it is one more restore.
        """
        given: 'a lamp saved with chapter one of the story, then changed to chapter two'
            var lamp = aLampThatHasRun()
            var home = home(lamp)
            write(home, 'workspace/story.txt', 'chapter one\n')
            var save = host.oillamp.run('history', lamp.toString(), 'save')
            assert save.succeeded()
            var early = save.events().find { it instanceof LampEvent.Saved }.snapshot()
            Files.writeString(home.resolve('workspace/story.txt'), 'chapter two\n')

        when: '`oillamp history <dir> restore` is run with the early snapshot'
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', early.shortId())

        then: 'the lamp was saved first, the command back to that save is printed, and chapter one is back'
            restored.succeeded()
            var safety = restored.events().find { it instanceof LampEvent.Saved }.snapshot()
            safety.kind() == LampSaveKind.BEFORE_RESTORE
            restored.console().contains('oillamp history ' + lamp + ' restore ' + safety.shortId())
            Files.readString(home.resolve('workspace/story.txt')) == 'chapter one\n'

        when: 'the safety save is restored'
            var undone = host.oillamp.run('history', lamp.toString(), 'restore', safety.shortId())

        then: 'chapter two is back'
            undone.succeeded()
            Files.readString(home.resolve('workspace/story.txt')) == 'chapter two\n'

        and: '`oillamp history <dir>` lists both restores, the safety save and the first save, newest first'
            var listed = host.oillamp.run('history', lamp.toString())
            listed.succeeded()
            var snapshots = listed.events().find { it instanceof LampEvent.History }.snapshots()
            snapshots*.kind().take(4) == [LampSaveKind.RESTORE, LampSaveKind.RESTORE, LampSaveKind.BEFORE_RESTORE, LampSaveKind.IDLE]
    }

    def '`oillamp history <dir> restore` brings back the lamp\'s `oillamp.toml` too'() {
        reportInfo """
            `oillamp.toml` says what the sandbox may reach and how its desktop looks. A restore that
            brought back the agent's home but kept today's network rules would not bring back the
            lamp as it was. So `oillamp.toml` comes back too, readable and writable only by its
            owner, `rw-------`.
        """
        given: 'a lamp saved, whose `oillamp.toml` changed after the save'
            var lamp = aLampThatHasRun()
            var config = lamp.resolve('oillamp.toml')
            var original = Files.readString(config)
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            var listed = host.oillamp.run('history', lamp.toString())
            assert listed.succeeded()
            var saved = listed.events().find { it instanceof LampEvent.History }.snapshots().first()
            Files.writeString(config, original + '\n# changed later\n')

        when: '`oillamp history <dir> restore` is run with that snapshot'
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId())

        then: '`oillamp.toml` is as it was saved, and `rw-------`'
            restored.succeeded()
            Files.readString(config) == original
            mode(config) == 'rw-------'
    }

    def 'Oillamp refuses `oillamp history <dir> restore` while a session runs, but saves while one runs'() {
        reportInfo """
            Replacing the agent's home would leave the programs running in the sandbox working in
            files changed beneath them. So oillamp restores only while no session runs, and
            otherwise refuses with `OIL-HISTORY-005` and the exit status `LAMP_BUSY`.

            Saving is different: the moment someone most wants a snapshot is while the session
            runs, just before the agent tries something risky. That save is of the kind `RUNNING`
            and names the session, because a program writing at that moment may leave a file half
            written.
        """
        given: 'a running session, on a lamp saved earlier'
            var lamp = aLampThatHasRun()
            var earlier = host.oillamp.run('history', lamp.toString(), 'save').events()
                    .find { it instanceof LampEvent.Saved }.snapshot()
            startASession(lamp)
            write(home(lamp), 'workspace/risky.txt', 'about to try something\n')

        when: '`oillamp history <dir> restore` is run with the earlier snapshot'
            var restore = host.oillamp.run('history', lamp.toString(), 'restore', earlier.shortId())

        then: 'it exits with `LAMP_BUSY` and reports `OIL-HISTORY-005`, and the new file is still there'
            restore.status() == LampExitStatus.LAMP_BUSY
            restore.reported('OIL-HISTORY-005')
            Files.exists(home(lamp).resolve('workspace/risky.txt'))

        when: '`oillamp history <dir> save` is run'
            var save = host.oillamp.run('history', lamp.toString(), 'save', '-m', 'before the risky part')

        then: 'it makes a snapshot of the kind `RUNNING`, which names the session'
            save.succeeded()
            var made = save.events().find { it instanceof LampEvent.Saved }.snapshot()
            made.kind() == LampSaveKind.RUNNING
            made.session().isPresent()
    }

    def '`oillamp history <dir> restore` refuses the snapshot id "#given", and points to `oillamp history <dir>`'() {
        reportInfo """
            A restore replaces the agent's home, so oillamp restores only a snapshot it is sure was
            meant: one named by a beginning of its id that is at least four characters long and that
            no other snapshot shares. It refuses anything else with `OIL-HISTORY-001`, the reason,
            and the command that lists the snapshots, `oillamp history <dir>`. The scenario tries an
            id that no snapshot has, one of two characters, and one that is not an id at all.
        """
        given: 'a lamp with no snapshot'
            var lamp = aLampThatHasRun()

        when: '`oillamp history <dir> restore <id>` is run'
            var outcome = host.oillamp.run('history', lamp.toString(), 'restore', given)

        then: 'it exits with `USAGE`, reports `OIL-HISTORY-001`, and prints `oillamp history <dir>`'
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-HISTORY-001')
            outcome.console().contains('oillamp history ' + lamp)

        where:
            given << ['0000000', 'ab', 'not-an-id']
    }

    def 'An application saves, lists and restores with `LampDirectory.save`, `history` and `restore`'() {
        reportInfo """
            An application built on oillamp's Java API, such as a desktop app with an agent in each
            lamp, may offer saving, listing and restoring with buttons of its own. It calls
            `LampDirectory.save`, `history` and `restore`, and gets the snapshots back as values
            rather than text to parse. A failure arrives as a `LampFailedException` that carries
            the whole problem.
        """
        given: 'a lamp with a first draft'
            var lamp = aLampThatHasRun()
            var lamps = Lamp.at(lamp).launchedBy(host.launcher)
            write(home(lamp), 'workspace/draft.txt', 'first draft\n')

        when: '`save` saves it, the draft is ruined, then `history` lists the snapshots and `restore` brings the draft back'
            var saved = lamps.save('first draft').orElseThrow()
            Files.writeString(home(lamp).resolve('workspace/draft.txt'), 'ruined\n')
            var listed = lamps.history()
            var restored = lamps.restore(saved.shortId())

        then: 'the snapshot has its message and is listed first, and the restore made a snapshot of the kind `RESTORE`'
            saved.message() == 'first draft'
            listed.first().id() == saved.id()
            restored.kind() == LampSaveKind.RESTORE
            Files.readString(home(lamp).resolve('workspace/draft.txt')) == 'first draft\n'

        when: '`restore` is called with `deadbeef`, an id no snapshot has'
            lamps.restore('deadbeef')

        then: 'it throws a `LampFailedException` with `OIL-HISTORY-001` and the status `USAGE`'
            var failed = thrown(LampFailedException)
            failed.problem().code().value() == 'OIL-HISTORY-001'
            failed.status() == LampExitStatus.USAGE
    }

    def 'An application reads and changes the `[autosave]` settings, and `oillamp.toml` keeps its comments'() {
        reportInfo """
            An application built on oillamp's Java API may have a settings page that shows when the
            lamp saves itself, and changes it. It reads and writes the five values under
            `[autosave]` with `LampDirectory.autosave`, which runs `oillamp history <dir> autosave`.
            `oillamp.toml` also belongs to its user, so their comments and the alignment of the
            values stay as they were. On the command line, the same command changes one value at a
            time, and refuses a value out of range or a setting it does not know.
        """
        given: 'a lamp with the `oillamp.toml` a new lamp gets'
            var lamp = aLampThatHasRun()
            var settings = Lamp.at(lamp).launchedBy(host.launcher)

        expect: 'the defaults: no save as a session starts or ends, a save before and after every job\'s run, and a chat saved once quiet for ten minutes'
            settings.autosave() == new Lamp.Autosave(false, false, true, true, 10)

        when: 'the application switches every one of them the other way'
            var written = settings.autosave(new Lamp.Autosave(true, true, false, false, 30))

        then: '`oillamp.toml` holds the new values, with the comments it had'
            written == new Lamp.Autosave(true, true, false, false, 30)
            settings.autosave() == written
            var file = Files.readString(lamp.resolve('oillamp.toml'))
            file.contains('startup    = true    # as a session starts, before the sandbox runs\n')
            file.contains('after_run  = false   # as a run ends, with the agent\'s last message\n')
            file.contains('chat_quiet_minutes = 30\n')

        and: '`oillamp history <dir> autosave` lists the same values'
            var shown = host.oillamp.run('history', lamp.toString(), 'autosave')
            shown.succeeded()
            shown.console().contains('startup on, shutdown on, before a job\'s run off, after a job\'s run off, a chat once quiet for 30 minutes')

        when: '`oillamp history <dir> autosave after-run on` is run'
            var one = host.oillamp.run('history', lamp.toString(), 'autosave', 'after-run', 'on')

        then: 'only `after_run` changed'
            one.succeeded()
            settings.autosave() == new Lamp.Autosave(true, true, false, true, 30)

        when: '`oillamp history <dir> autosave chat-quiet-minutes 0` is run, so that oillamp saves around each question of a chat'
            var each = host.oillamp.run('history', lamp.toString(), 'autosave', 'chat-quiet-minutes', '0')

        then: '`chat_quiet_minutes` is 0'
            each.succeeded()
            settings.autosave() == new Lamp.Autosave(true, true, false, true, 0)

        when: '`chat-quiet-minutes 1441` is given, more than a day'
            var tooLong = host.oillamp.run('history', lamp.toString(), 'autosave', 'chat-quiet-minutes', '1441')

        then: 'oillamp refuses with `USAGE`, and says that the minutes must be from 0 to 1440'
            tooLong.status() == LampExitStatus.USAGE
            tooLong.console().contains('chat-quiet-minutes needs a number of minutes from 0 to 1440')

        when: '`lunch`, a setting oillamp does not know, is given'
            var wrong = host.oillamp.run('history', lamp.toString(), 'autosave', 'lunch', 'on')

        then: 'oillamp refuses with `USAGE`, and lists the settings it knows'
            wrong.status() == LampExitStatus.USAGE
            wrong.console().contains('expected startup, shutdown, before-run, after-run or chat-quiet-minutes')
    }

    def '`LampDirectory.autosave` refuses to change the `[autosave]` settings while a session runs'() {
        reportInfo """
            A session reads `oillamp.toml` only as it starts, so a change to `[autosave]` would not
            reach a running session. So `LampDirectory.autosave` throws a `LampFailedException` with
            the status `LAMP_BUSY` while a session runs, and the settings stay as they were.
        """
        given: 'a lamp with a running session'
            var lamp = aLampThatHasRun()
            var settings = Lamp.at(lamp).launchedBy(host.launcher)
            startASession(lamp)

        when: '`LampDirectory.autosave` is called with new settings'
            settings.autosave(new Lamp.Autosave(true, true, true, true, 10))

        then: 'it throws a `LampFailedException` with `LAMP_BUSY`, and the settings are still the defaults'
            var busy = thrown(LampFailedException)
            busy.status() == LampExitStatus.LAMP_BUSY
            settings.autosave() == new Lamp.Autosave(false, false, true, true, 10)
    }

    @IgnoreIf({ !['/usr/bin/git', '/bin/git', '/usr/local/bin/git'].any { Files.isExecutable(Path.of(it)) } })
    def '`git` itself reads a lamp\'s history'() {
        reportInfo """
            oillamp writes the history in git's format itself, rather than by running git, so that a
            nested `.git` directory in the agent's home is stored like any other directory. The
            history is still an ordinary repository: someone who knows git can read it with git,
            without oillamp. `git fsck --strict` may warn about the nested `.git`, and about nothing
            else. The scenario runs only where git is installed.
        """
        given: 'a home with a nested repository and `readme.txt`, saved with a message'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/game/.git/HEAD', 'ref: refs/heads/main\n')
            write(home(lamp), 'workspace/readme.txt', 'hello\n')
            assert host.oillamp.run('history', lamp.toString(), 'save', '--message', 'with a nested repository').succeeded()
            var repository = lamp.resolve('.oillamp/history')
            var agentDir = home(lamp).fileName.toString()

        expect: '`git log` lists the snapshot with its message'
            git(repository, 'log', '--format=%s').readLines().first() == 'idle save: with a nested repository'

        and: '`git show` prints `readme.txt` from the snapshot'
            git(repository, 'show', "HEAD:${agentDir}/workspace/readme.txt") == 'hello\n'

        and: '`git fsck --strict` finds nothing wrong but the nested `.git`'
            var check = new ProcessBuilder('git', '--git-dir', repository.toString(), 'fsck', '--strict')
                    .redirectErrorStream(true).start()
            var said = check.inputStream.text
            check.waitFor() == 0 || said.readLines().every { it.contains('hasDotgit') }
    }

    // ─── the schedule in the snapshots ─────────────────────────────────────────────────────

    def 'Oillamp makes a snapshot when only the schedule changed'() {
        reportInfo """
            Every snapshot holds the lamp's schedule, `.oillamp/schedule.json`, so that a restore can
            bring back the jobs the lamp had. A save compares the files it would store, the schedule
            among them, with those of the newest snapshot, and makes no snapshot when nothing
            changed.

            Let's consider a lamp saved a moment ago, to which `oillamp schedule add` then adds a
            job, and nothing else changes. The next `oillamp history <dir> save` must make a
            snapshot. A save straight after it must make none.
        """
        given: 'a lamp saved a moment ago'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when: '`oillamp schedule add` adds a job, and `oillamp history <dir> save` is run'
            assert host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up').succeeded()
            var afterTheJob = host.oillamp.run('history', lamp.toString(), 'save', '-m', 'with a job')

        then: 'the save made a snapshot'
            afterTheJob.succeeded()
            afterTheJob.events().find { it instanceof LampEvent.Saved }.snapshot().message() == 'with a job'

        when: '`oillamp history <dir> save` is run again, with nothing changed'
            var again = host.oillamp.run('history', lamp.toString(), 'save')

        then: 'that save made none'
            again.succeeded()
            !again.events().any { it instanceof LampEvent.Saved }
    }

    def '`oillamp history <dir> restore` brings back the jobs the snapshot holds'() {
        reportInfo """
            A snapshot holds the lamp's schedule, and a restore brings it back as the snapshot holds
            it: every job, the user's and the agent's alike, each switched on or off as it was. A job
            added after the snapshot is gone after the restore, whoever added it. The jobs belong to
            the state the lamp is brought back to: a job the agent added for work it did after the
            snapshot would otherwise wake it for work that no longer exists.

            The schedule file also holds the number the next job gets, and that comes back too. In
            the restored state `job-3` was never added, so the next job added is `job-3`.

            Let's consider a snapshot holding the user's `job-1`, switched off, and `job-2`, which
            the agent added during a session. After the snapshot, `job-1` is switched on, `job-2`
            removed and `job-3` added. The restore must bring back `job-1`, switched off, and the
            agent's `job-2`, and take away `job-3`. A job added after the restore is `job-3`
            again.
        """
        given: 'a lamp with a daily job of the user\'s'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')

        and: 'a job the agent added in a session'
            startASession(lamp)
            assert agentAsks(op: 'add', cron: '0 9 * * *', prompt: 'Check the build').ok
            stopTheSession(lamp)

        and: 'a snapshot made once `oillamp schedule disable job-1` switched the user\'s job off'
            host.oillamp.run('schedule', lamp.toString(), 'disable', 'job-1')
            var saved = host.oillamp.run('history', lamp.toString(), 'save').events().find { it instanceof LampEvent.Saved }.snapshot()

        and: 'later, `job-1` was switched on, the agent\'s `job-2` removed, and `job-3` added'
            host.oillamp.run('schedule', lamp.toString(), 'enable', 'job-1')
            host.oillamp.run('schedule', lamp.toString(), 'remove', 'job-2')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')

        when: '`oillamp history <dir> restore` is run with the snapshot'
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId())

        then: '`job-1` is back, switched off, and so is the agent\'s `job-2`; `job-3` is gone'
            restored.succeeded()
            var jobs = jobsOf(lamp)
            jobs*.id() == ['job-1', 'job-2']
            !jobs[0].enabled()
            jobs[0].author() == LampJobAuthor.USER
            jobs[1].enabled()
            jobs[1].author() == LampJobAuthor.AGENT
            jobs[1].prompt() == 'Check the build'

        when: '`oillamp schedule add` adds a job after the restore'
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 2h', 'Send the summary')

        then: 'it is `job-3`, the number the next job had in the snapshot'
            added.events().find { it instanceof LampEvent.JobAdded }.job().id() == 'job-3'
    }

    def '`oillamp history <dir> restore` brings back a schedule that was #state when the snapshot was made'() {
        reportInfo """
            `oillamp schedule pause` stops every job from running, without changing any job, and
            `oillamp schedule resume` lets them run again. Whether the schedule is paused is kept in
            `.oillamp/schedule.json`, beside the jobs, so a snapshot holds it with them.

            A restore puts back the whole schedule file as the snapshot holds it, and keeps nothing
            of the present schedule. That gives one rule to know: after a restore, the schedule is
            the snapshot's. So restoring a snapshot made while the schedule ran lets the jobs run
            again, even if the schedule was paused since.

            The scenario saves a lamp whose schedule is ${state}, then ${change} it, restores the
            snapshot, and reads with `oillamp schedule` whether the schedule is paused.
        """
        given: 'a lamp with one job, saved while its schedule is paused or not'
            var lamp = aLampThatHasRun()
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')
            if (pausedThen) host.oillamp.run('schedule', lamp.toString(), 'pause')
            var saved = host.oillamp.run('history', lamp.toString(), 'save').events().find { it instanceof LampEvent.Saved }.snapshot()

        and: 'later, the schedule was resumed, or paused'
            host.oillamp.run('schedule', lamp.toString(), pausedThen ? 'resume' : 'pause')

        when: '`oillamp history <dir> restore` is run with the snapshot'
            assert host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId()).succeeded()

        then: '`oillamp schedule` says the schedule is paused, or not, as it was in the snapshot'
            host.oillamp.run('schedule', lamp.toString()).events()
                    .find { it instanceof LampEvent.Schedule }.paused() == pausedThen

        where:
            pausedThen << [true, false]
            state = pausedThen ? 'paused' : 'running'
            change = pausedThen ? 'resumes' : 'pauses'
    }

    def '`oillamp history <dir> restore` brings back when each job last ran'() {
        reportInfo """
            Oillamp works out when a repeating job runs next from the moment it last ran, or from
            when it was added if it never ran. That moment is kept with the job in
            `.oillamp/schedule.json`, so a snapshot holds it, and a restore brings it back.

            Let's consider an `@hourly` job added on Tuesday at 16:15 in Berlin, and the lamp saved
            at once: in that snapshot the job has never run, and runs next at 17:00. A session at
            17:05 runs it, and it then runs next at 18:00. Restoring the snapshot brings the lamp
            back to before that run, so the job must run next at 17:00 again.
        """
        given: 'an hourly job, added on Tuesday at 16:15 in Berlin, and a snapshot made at once'
            host.machine { it.clockAt(TUESDAY).timeZone('Europe/Berlin') }
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')
            var saved = host.oillamp.run('history', lamp.toString(), 'save').events().find { it instanceof LampEvent.Saved }.snapshot()

        and: 'a session at 17:05, in which the job ran'
            host.machine { it.agent { String prompt -> 'Looked.' }.clockAt(Instant.parse('2026-09-22T15:05:00Z')) }
            startASession(lamp)
            waitFor(LampEvent.RunFinished)
            stopTheSession(lamp)
            assert jobsOf(lamp).first().next() == Optional.of(Instant.parse('2026-09-22T16:00:00Z'))

        when: '`oillamp history <dir> restore` is run with the snapshot'
            assert host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId()).succeeded()

        then: 'the job runs next at 17:00 again'
            jobsOf(lamp).first().next() == Optional.of(Instant.parse('2026-09-22T15:00:00Z'))
    }

    def '`oillamp history <dir> restore` keeps the current jobs when the snapshot holds no schedule'() {
        reportInfo """
            Snapshots made by an older oillamp hold only the agent's home and `oillamp.toml`, and no
            schedule. Such a snapshot says nothing about the jobs the lamp had, and restoring an
            empty schedule would be a guess that deletes the user's jobs. So oillamp restores the
            home and `oillamp.toml` from it, and keeps the schedule as it is. The snapshot that
            records the restore holds that schedule, so a save straight after the restore finds
            nothing changed and makes no snapshot.

            The current oillamp no longer makes such snapshots, so the scenario writes one into the
            history by hand, as a git commit without `.oillamp/schedule.json` among its files.
            After it, a file changes and a job is added, and then that snapshot is restored.
        """
        given: 'a lamp whose newest snapshot was made by an older oillamp, without the schedule'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/story.txt', 'chapter one\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            var older = aSnapshotWithoutTheSchedule(lamp)

        and: 'later, a file changed and a job was added'
            Files.writeString(home(lamp).resolve('workspace/story.txt'), 'chapter two\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')

        when: '`oillamp history <dir> restore` is run with the older snapshot'
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', older.substring(0, 8))

        then: 'the home is as the snapshot holds it'
            restored.succeeded()
            Files.readString(home(lamp).resolve('workspace/story.txt')) == 'chapter one\n'

        and: '`job-1` is still on the schedule'
            jobsOf(lamp)*.id() == ['job-1']

        and: 'a save straight after makes no snapshot, because nothing changed'
            !host.oillamp.run('history', lamp.toString(), 'save').events().any { it instanceof LampEvent.Saved }
    }

    def 'Oillamp does not give out a run number a second time after a restore'() {
        reportInfo """
            Every run of the agent has a name, such as `run-12`. Each snapshot of a run carries it in
            a trailer: a `key: value` line at the end of the snapshot's commit message, here
            `Oillamp-Run: run-12`, which `oillamp history` reads. So the history reads as a log of
            the runs, and two runs must never share a name, or the log would mix up what they did.

            A restore brings back the schedule file, but does not take the snapshots made after its
            target out of the history: it adds a snapshot of its own. So oillamp numbers a run one
            above the highest `Oillamp-Run` in the history. A counter in the schedule file would go
            back with the restore, and give out the same numbers again.

            Let's consider a lamp saved before any run. In a session, `oillamp ask` wakes the agent
            twice, as `run-1` and `run-2`. Then the snapshot from before them is restored, and
            `oillamp ask` wakes the agent again in a new session. That run must be `run-3`, and the
            history must hold one run snapshot each for `run-1`, `run-2` and `run-3`.
        """
        given: 'a lamp saved before any run'
            var lamp = aLampThatHasRun('[autosave]\nchat_quiet_minutes = 0\n')
            write(home(lamp), 'workspace/plan.md', '# the plan\n')
            var saved = host.oillamp.run('history', lamp.toString(), 'save').events().find { it instanceof LampEvent.Saved }.snapshot()
            host.machine { it.agent { String prompt -> 'Done.' } }

        and: 'a session in which `oillamp ask` woke the agent twice'
            startASession(lamp)
            assert runOf(host.oillamp.run('ask', lamp.toString(), 'First?')) == 'run-1'
            assert runOf(host.oillamp.run('ask', lamp.toString(), 'Second?')) == 'run-2'
            stopTheSession(lamp)

        when: 'the snapshot from before them is restored, and `oillamp ask` wakes the agent in a new session'
            assert host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId()).succeeded()
            reported.clear()
            startASession(lamp)
            var third = runOf(host.oillamp.run('ask', lamp.toString(), 'Third?'))

        then: 'the new run is `run-3`'
            third == 'run-3'

        and: 'the history holds one run snapshot for each run'
            var snapshots = host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots()
            snapshots.findAll { it.kind() == LampSaveKind.RUN }*.run()*.get().sort() == ['run-1', 'run-2', 'run-3']
    }

    @IgnoreIf({ !['/usr/bin/git', '/bin/git', '/usr/local/bin/git'].any { Files.isExecutable(Path.of(it)) } })
    def '`git show` reads the jobs a snapshot holds, without oillamp'() {
        reportInfo """
            Let's consider someone who wants to see which jobs a snapshot holds before restoring it,
            because a restore replaces the whole schedule with the snapshot's. The history is an
            ordinary git repository, each snapshot a git commit, and the snapshot holds the schedule
            at the path it has in the lamp, `.oillamp/schedule.json`. So
            `git show <snapshot>:.oillamp/schedule.json` prints it. The scenario saves a lamp with
            one job, and reads that job's prompt from the newest snapshot with git, where git is
            installed.
        """
        given: 'a lamp with one job, saved'
            var lamp = aLampThatHasRun()
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        expect: '`git show HEAD:.oillamp/schedule.json` prints the job\'s prompt'
            git(lamp.resolve('.oillamp/history'), 'show', 'HEAD:.oillamp/schedule.json').contains('Tidy up')
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** Tuesday afternoon: 16:15 in Berlin. */
    static final Instant TUESDAY = Instant.parse('2026-09-22T14:15:03Z')

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

    /** The jobs on a lamp's schedule, as `oillamp schedule` lists them. */
    private List jobsOf(Path lamp) {
        var listing = host.oillamp.run('schedule', lamp.toString())
        assert listing.succeeded()
        listing.events().find { it instanceof LampEvent.Schedule }.jobs().collect()
    }

    private static String runOf(outcome) {
        outcome.events().find { it instanceof LampEvent.RunFinished }.run().id()
    }

    private void stopTheSession(Path lamp) {
        assert host.oillamp.run('stop', lamp.toString()).succeeded()
        session.join(30_000)
        assert !session.alive
    }

    private <T extends LampEvent> T waitFor(Class<T> kind) {
        var deadline = System.currentTimeMillis() + 60_000
        while (true) {
            var found = reported.find { kind.isInstance(it) }
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

    /**
     * Adds a snapshot to the lamp's history as an oillamp from before snapshots held the schedule
     * made them: the newest snapshot's tree without its {@code .oillamp} directory, committed on
     * top of it in git's own format. Returns the new snapshot's id.
     */
    private static String aSnapshotWithoutTheSchedule(Path lamp) {
        var repository = lamp.resolve('.oillamp/history')
        var branch = repository.resolve('refs/heads/main')
        var head = Files.readString(branch).strip()
        var tree = new String(readObject(repository, head), StandardCharsets.ISO_8859_1).readLines().first().substring(5)
        var entries = readObject(repository, tree)
        var kept = new ByteArrayOutputStream()
        for (int at = 0; at < entries.length; ) {
            int nul = at
            while (entries[nul] != 0) nul++
            var name = new String(entries, at, nul - at, StandardCharsets.UTF_8).split(' ', 2)[1]
            if (name != '.oillamp') kept.write(entries, at, nul + 21 - at)
            at = nul + 21
        }
        var older = writeObject(repository, 'tree', kept.toByteArray())
        var stamp = "oillamp <oillamp@localhost> ${Instant.now().epochSecond} +0000"
        var commit = writeObject(repository, 'commit', ("tree ${older}\nparent ${head}\nauthor ${stamp}\ncommitter ${stamp}\n\n"
                + 'idle save: as an older oillamp made it\n\nOillamp-Save: idle\n').getBytes(StandardCharsets.UTF_8))
        Files.writeString(branch, commit + '\n')
        commit
    }

    private static byte[] readObject(Path repository, String id) {
        var file = repository.resolve('objects').resolve(id.substring(0, 2)).resolve(id.substring(2))
        var content = new InflaterInputStream(Files.newInputStream(file)).withCloseable { it.readAllBytes() }
        int nul = 0
        while (content[nul] != 0) nul++
        Arrays.copyOfRange(content, nul + 1, content.length)
    }

    private static String writeObject(Path repository, String type, byte[] content) {
        var whole = new ByteArrayOutputStream()
        whole.write("${type} ${content.length}\u0000".getBytes(StandardCharsets.US_ASCII))
        whole.write(content)
        var id = MessageDigest.getInstance('SHA-1').digest(whole.toByteArray()).encodeHex().toString()
        var file = repository.resolve('objects').resolve(id.substring(0, 2)).resolve(id.substring(2))
        Files.createDirectories(file.parent)
        if (!Files.exists(file))
            new DeflaterOutputStream(Files.newOutputStream(file)).withCloseable { it.write(whole.toByteArray()) }
        id
    }

    /** A lamp that does not exist yet, whose sessions save as they start and as they end. */
    private Path aLampThatSavesAtStartAndEnd() {
        var lamp = host.lampPath()
        host.givenConfig(lamp, 'schema_version = 1\n[autosave]\nstartup = true\nshutdown = true\n')
        lamp
    }

    private static Path home(Path lamp) { Lamp.agentHome(lamp).orElseThrow() }

    private static void write(Path home, String relative, String content) {
        var file = home.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private static void chmod(Path path, String permissions) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
    }

    private static String mode(Path path) {
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
    }

    private static void deleteTree(Path root) {
        Files.walk(root).sorted(Comparator.reverseOrder()).each { Files.delete(it) }
    }

    private static String git(Path repository, String... arguments) {
        var process = new ProcessBuilder(['git', '--git-dir', repository.toString()] + arguments.toList()).start()
        var output = process.inputStream.text
        assert process.waitFor() == 0 : process.errorStream.text
        output
    }

    private void startASession(Path lamp) {
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        session = Thread.start { oillamp.run('at', lamp.toString()) }
        var deadline = System.currentTimeMillis() + 30_000
        while (!reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' }) {
            assert System.currentTimeMillis() < deadline : 'the session never came up'
            Thread.sleep(50)
        }
    }
}
