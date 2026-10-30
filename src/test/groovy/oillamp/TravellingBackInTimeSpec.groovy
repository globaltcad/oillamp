package oillamp

import dev.lamp.LampExitStatus
import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampSaveKind
import dev.lamp.LampFailedException
import dev.lamp.LampJobAuthor
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
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 *  {@code save}, {@code history} and {@code restore}: a lamp's history.
 *
 *  <p>An agent can wreck its own home: delete a project, break a toolchain, fill a config file
 *  with nonsense. The sandbox keeps that away from the user's machine, but the work inside is
 *  still lost. A lamp therefore keeps snapshots of the agent's home, its configuration and its
 *  schedule, in a git repository beside the home that the agent cannot see, and can go back to
 *  any of them.
 *
 *  <p>The files are real, in a temporary directory, as everywhere in these scenarios. A snapshot
 *  is only worth something if exact bytes, permissions and links come back, and a simulated disk
 *  would hide exactly the mistakes that lose them.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TravellingBackInTimeSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

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

    def 'By default a session saves nothing as it starts or ends'() {
        reportInfo """
            Saving a large home takes time and space at every start and every stop, and most
            sessions are not worth going back to. So the saves at the start and the end of a
            session are off unless `autosave.startup` and `autosave.shutdown` are on. A person
            still saves by hand whenever they want to.
        """
        given: 'a new lamp whose agent writes a file during the session'
            var lamp = host.lampPath()
            var oillamp = host.oillamp.observedBy { event ->
                if (event instanceof LampEvent.Summary && event.title() == 'your session is up')
                    Files.writeString(home(lamp).resolve('workspace/notes.txt'), 'done for today\n')
            }

        when:
            var ran = oillamp.run('at', lamp.toString())

        then: 'nothing was saved, and the history is empty'
            ran.succeeded()
            !ran.events().any { it instanceof LampEvent.Saved }
            !ran.events().any { it instanceof LampEvent.StepStarted && it.step().kind() == 'SaveLamp' }
            host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots().isEmpty()
    }

    def 'With autosave.startup on, a session saves the lamp as it starts'() {
        reportInfo """
            Nobody remembers to save before the thing that goes wrong. So a session can begin
            with a save, taken before the sandbox runs, and whatever the agent does in that
            session can be undone by going back to it. The snapshot says it is a startup save,
            and which session it began, so a person reading the history knows what it is.
            Saving a large home can take time, so the CLI explains what is happening before
            reading the files and starts its activity line rather than appearing stuck.
        """
        given: 'a new lamp that saves as its sessions start and end'
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

        and: 'the lamp was saved as the session started'
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

    def 'With autosave.shutdown on, oillamp saves the lamp again at the end of a session that changed it'() {
        reportInfo """
            The end of a session is the other moment worth keeping: it is the work the agent did.
            The save runs after the container has stopped, so nothing is writing while it reads.
            It is part of the shutdown itself, which is also what runs after Ctrl-C.
        """
        given: 'a lamp that saves as its sessions start and end, whose agent writes a file during the session'
            var lamp = aLampThatSavesAtStartAndEnd()
            var oillamp = host.oillamp.observedBy { event ->
                if (event instanceof LampEvent.Summary && event.title() == 'your session is up')
                    Files.writeString(home(lamp).resolve('workspace/notes.txt'), 'done for today\n')
            }

        when:
            var ran = oillamp.run('at', lamp.toString())

        then: 'the history has the startup save and, newest first, the shutdown save'
            ran.succeeded()
            var listed = host.oillamp.run('history', lamp.toString())
            listed.succeeded()
            var snapshots = listed.events().find { it instanceof LampEvent.History }.snapshots()
            snapshots*.kind() == [LampSaveKind.SHUTDOWN, LampSaveKind.STARTUP]
    }

    def 'A save by hand keeps what the person wrote, and saves nothing when nothing changed'() {
        reportInfo """
            A person saves by hand with `oillamp history <dir> save` to mark a moment, such as
            "before the upgrade", and will look for that moment by what they wrote. A second save with
            nothing changed would only add a line to the history that is identical to the one
            before, so oillamp makes none.
        """
        given:
            var lamp = aLampThatHasRun()
            Files.writeString(home(lamp).resolve('workspace/plan.md'), '# the plan\n')

        when:
            var first = host.oillamp.run('history', lamp.toString(), 'save', '--message', 'before the upgrade')
            var second = host.oillamp.run('history', lamp.toString(), 'save', '-m', 'again')

        then: 'the first made a snapshot, marked as saved while no session ran'
            first.succeeded()
            var made = first.events().find { it instanceof LampEvent.Saved }.snapshot()
            made.kind() == LampSaveKind.IDLE
            made.message() == 'before the upgrade'
            first.console().contains('saved ' + made.shortId())
            first.console().contains('saving lamp state now — this may take a while (idle save)')

        and: 'the second found nothing to save'
            second.succeeded()
            !second.events().any { it instanceof LampEvent.Saved }
            second.console().contains('nothing changed since ' + made.shortId())
            second.events().any { it instanceof LampEvent.StepStarted && it.step().kind() == 'SaveLamp' }

        and: 'the history lists it by what the person wrote'
            var listed = host.oillamp.run('history', lamp.toString())
            listed.console().contains(made.shortId())
            listed.console().contains('idle save')
            listed.console().contains('before the upgrade')
    }

    def 'A save message is kept as it was written, however it was given'() {
        reportInfo """
            `--message=text` is the same as `--message text` and `-m text`. Whatever follows
            --message is the message, even when it looks like an option or is `--` itself, so a
            person who marks a moment with "-- after the upgrade --" finds exactly that in the
            history.
        """
        given:
            var lamp = aLampThatHasRun()

        when: 'the lamp changes before each save, so each one makes a snapshot'
            var notes = home(lamp).resolve('workspace/notes.txt')
            Files.writeString(notes, 'first')
            var joined = host.oillamp.run('history', lamp.toString(), 'save', '--message=before the upgrade')
            Files.writeString(notes, 'second')
            var dashed = host.oillamp.run('history', lamp.toString(), 'save', '-m', '-- after the upgrade --')
            Files.writeString(notes, 'third')
            var bare = host.oillamp.run('history', lamp.toString(), 'save', '--message', '--')

        then:
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
            Let's consider an agent directory of many gigabytes, of which a run changes a few files.
            Reading all of it for every save would take minutes, so a save remembers, in
            `.oillamp/history/file-stats`, each file's size, time of modification, inode and the
            time its inode last changed, and the next save takes the stored content of a file whose
            four are unchanged, without reading it. It remembers only files that last changed
            seconds before it began, so that a change in the same tick of the filesystem's clock
            always gives a file another time.

            `cp -p`, `rsync -t` and `tar` set a file's time of modification back to the one it had,
            and a file of fixed length, such as a database page, keeps its size. In the rare case of
            both, only the time the inode last changed tells the file apart, which no program can set
            back. Here a file of four bytes settles, is saved, gets four other bytes with its old
            time of modification, and is saved again: the second `oillamp history <dir> save` makes a
            snapshot, and `oillamp history <dir> restore` of it brings the new bytes back.
        """
        given: 'a file that last changed several seconds before it was saved'
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

    def 'Oillamp\'s `history <dir> restore` brings back exactly what was saved, and removes what came after'() {
        reportInfo """
            The whole point of a snapshot is that the agent's home comes back as it was: every
            byte, and the things git on its own would lose. A project the agent cloned has its
            own .git directory, which a plain `git add` would store only as a reference to
            another repository, without its files, losing the project. A private key must come back as 0600, or ssh refuses to use it. A link must
            come back as a link, an empty directory as a directory, and a directory the agent made
            read-only, as Go does with its module cache, must neither stop the restore nor come
            back writable.

            Whatever appeared after the snapshot is removed, or the lamp would be a mixture of
            two moments rather than the one the person chose.
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

        and: 'then the agent wrecks it'
            deleteTree(home.resolve('workspace/game'))
            Files.writeString(home.resolve('bin/build.sh'), 'rm -rf everything\n')
            chmod(home.resolve('.ssh/id_ed25519'), 'rw-r--r--')
            Files.delete(home.resolve('latest'))
            write(home, 'workspace/junk.bin', 'x' * 10_000)
            chmod(home.resolve('go/pkg/mod/lib@v1'), 'rwxr-xr-x')
            Files.delete(home.resolve('go/pkg/mod/lib@v1/lib.go'))
            chmod(home.resolve('go/pkg/mod/lib@v1'), 'r-xr-xr-x')

        when: 'the person restores the snapshot, by the first eight characters of its id'
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId())

        then:
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

    def 'Oillamp saves the lamp before it restores a snapshot, so the restore can be undone'() {
        reportInfo """
            Restoring the wrong snapshot must not be a second disaster. So
            `oillamp history <dir> restore` first saves the lamp as it is, and prints the command
            that goes back to that. The history only ever grows: the restore adds a snapshot of its
            own rather than removing the ones after its target.
        """
        given:
            var lamp = aLampThatHasRun()
            var home = home(lamp)
            write(home, 'workspace/story.txt', 'chapter one\n')
            var save = host.oillamp.run('history', lamp.toString(), 'save')
            assert save.succeeded()
            var early = save.events().find { it instanceof LampEvent.Saved }.snapshot()
            Files.writeString(home.resolve('workspace/story.txt'), 'chapter two\n')

        when: 'the person restores the early snapshot'
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', early.shortId())

        then: 'the lamp was saved first, and the way back is printed'
            restored.succeeded()
            var safety = restored.events().find { it instanceof LampEvent.Saved }.snapshot()
            safety.kind() == LampSaveKind.BEFORE_RESTORE
            restored.console().contains('oillamp history ' + lamp + ' restore ' + safety.shortId())
            Files.readString(home.resolve('workspace/story.txt')) == 'chapter one\n'

        when: 'they change their mind'
            var undone = host.oillamp.run('history', lamp.toString(), 'restore', safety.shortId())

        then: 'chapter two is back'
            undone.succeeded()
            Files.readString(home.resolve('workspace/story.txt')) == 'chapter two\n'

        and: 'every step is in the history, newest first'
            var listed = host.oillamp.run('history', lamp.toString())
            listed.succeeded()
            var snapshots = listed.events().find { it instanceof LampEvent.History }.snapshots()
            snapshots*.kind().take(4) == [LampSaveKind.RESTORE, LampSaveKind.RESTORE, LampSaveKind.BEFORE_RESTORE, LampSaveKind.IDLE]
    }

    def 'Oillamp\'s `history <dir> restore` brings back the lamp\'s `oillamp.toml` too'() {
        reportInfo """
            oillamp.toml says what the sandbox may reach and how it looks. A snapshot that brought
            back the agent's home but kept today's network rules would not be the lamp as it was.
        """
        given:
            var lamp = aLampThatHasRun()
            var config = lamp.resolve('oillamp.toml')
            var original = Files.readString(config)
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            var listed = host.oillamp.run('history', lamp.toString())
            assert listed.succeeded()
            var saved = listed.events().find { it instanceof LampEvent.History }.snapshots().first()
            Files.writeString(config, original + '\n# changed later\n')

        when:
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId())

        then:
            restored.succeeded()
            Files.readString(config) == original
            mode(config) == 'rw-------'
    }

    def 'Oillamp refuses to restore while a session runs, but saves while one runs'() {
        reportInfo """
            Replacing the agent's home under running programs would leave them working in files
            that changed beneath them, so oillamp restores only while no session runs, and
            otherwise refuses with `OIL-HISTORY-005`.

            Saving is different: the moment a person most wants a snapshot is while the session
            runs, just before they let the agent try something risky. That save is marked as a
            running save, because a program writing at that moment may leave a file half written.
        """
        given: 'a running session, on a lamp saved earlier'
            var lamp = aLampThatHasRun()
            var earlier = host.oillamp.run('history', lamp.toString(), 'save').events()
                    .find { it instanceof LampEvent.Saved }.snapshot()
            startASession(lamp)
            write(home(lamp), 'workspace/risky.txt', 'about to try something\n')

        when:
            var restore = host.oillamp.run('history', lamp.toString(), 'restore', earlier.shortId())

        then:
            restore.status() == LampExitStatus.LAMP_BUSY
            restore.reported('OIL-HISTORY-005')
            Files.exists(home(lamp).resolve('workspace/risky.txt'))

        when:
            var save = host.oillamp.run('history', lamp.toString(), 'save', '-m', 'before the risky part')

        then:
            save.succeeded()
            var made = save.events().find { it instanceof LampEvent.Saved }.snapshot()
            made.kind() == LampSaveKind.RUNNING
            made.session().isPresent()
    }

    def 'Oillamp refuses to restore a snapshot id it does not know, and points to `oillamp history <dir>`'() {
        reportInfo """
            A restore replaces the agent's home, so it happens only for a snapshot oillamp is
            sure the person meant: a unique beginning of an id, at least four characters long.
            Oillamp refuses anything else with `OIL-HISTORY-001`, the reason, and the command
            that lists the snapshots, `oillamp history <dir>`.
        """
        given:
            var lamp = aLampThatHasRun()

        when:
            var outcome = host.oillamp.run('history', lamp.toString(), 'restore', given)

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-HISTORY-001')
            outcome.console().contains('oillamp history ' + lamp)

        where:
            given << ['0000000', 'ab', 'not-an-id']
    }

    def 'An application saves, lists and restores through the Lamp API'() {
        reportInfo """
            An application, a program built on oillamp's Java API that holds lamps, such as a
            desktop app with an agent in each, offers saving, listing and restoring with its own
            buttons, through `LampDirectory.save`, `history` and `restore`. It gets values back rather than text to
            parse, and a failure as an exception that carries the full problem.
        """
        given:
            var lamp = aLampThatHasRun()
            var lamps = Lamp.at(lamp).launchedBy(host.launcher)
            write(home(lamp), 'workspace/draft.txt', 'first draft\n')

        when:
            var saved = lamps.save('first draft').orElseThrow()
            Files.writeString(home(lamp).resolve('workspace/draft.txt'), 'ruined\n')
            var listed = lamps.history()
            var restored = lamps.restore(saved.shortId())

        then:
            saved.message() == 'first draft'
            listed.first().id() == saved.id()
            restored.kind() == LampSaveKind.RESTORE
            Files.readString(home(lamp).resolve('workspace/draft.txt')) == 'first draft\n'

        when: 'it asks for a snapshot that is not there'
            lamps.restore('deadbeef')

        then:
            var failed = thrown(LampFailedException)
            failed.problem().code().value() == 'OIL-HISTORY-001'
            failed.status() == LampExitStatus.USAGE
    }

    def 'An application switches the saves on and off, and only those lines of the file change'() {
        reportInfo """
            An application, a program built on oillamp's Java API, has a settings page that shows
            when the lamp saves itself, and changes it. It reads and writes the five values under
            [autosave] with `LampDirectory.autosave`, which runs `oillamp history <dir> autosave`. The file is
            also the user's, so their comments and the alignment of the values stay.
        """
        given: 'a lamp with the file a new lamp gets'
            var lamp = aLampThatHasRun()
            var settings = Lamp.at(lamp).launchedBy(host.launcher)

        expect: 'the defaults: no saves at the start or end of a session, but around every job\'s run, and a chat once quiet for ten minutes'
            settings.autosave() == new Lamp.Autosave(false, false, true, true, 10)

        when: 'the application switches every one of them the other way'
            var written = settings.autosave(new Lamp.Autosave(true, true, false, false, 30))

        then: 'the file says it, with the comments it had'
            written == new Lamp.Autosave(true, true, false, false, 30)
            settings.autosave() == written
            var file = Files.readString(lamp.resolve('oillamp.toml'))
            file.contains('startup    = true   # as a session starts, before the sandbox runs\n')
            file.contains('after_run  = false    # as a run ends, with the agent\'s last message\n')
            file.contains('chat_quiet_minutes = 30\n')

        and: 'the command line says the same'
            var shown = host.oillamp.run('history', lamp.toString(), 'autosave')
            shown.succeeded()
            shown.console().contains('startup on, shutdown on, before a job\'s run off, after a job\'s run off, a chat once quiet for 30 minutes')

        when: 'one moment is switched on the command line'
            var one = host.oillamp.run('history', lamp.toString(), 'autosave', 'after-run', 'on')

        then: 'only that one changed'
            one.succeeded()
            settings.autosave() == new Lamp.Autosave(true, true, false, true, 30)

        when: 'every question is to be saved by itself, on the command line'
            var each = host.oillamp.run('history', lamp.toString(), 'autosave', 'chat-quiet-minutes', '0')

        then:
            each.succeeded()
            settings.autosave() == new Lamp.Autosave(true, true, false, true, 0)

        when: 'a chat is to wait longer than a day'
            var tooLong = host.oillamp.run('history', lamp.toString(), 'autosave', 'chat-quiet-minutes', '1441')

        then: 'oillamp refuses, and says how long it may be'
            tooLong.status() == LampExitStatus.USAGE
            tooLong.console().contains('chat-quiet-minutes needs a number of minutes from 0 to 1440')

        when: 'a moment oillamp does not know is given'
            var wrong = host.oillamp.run('history', lamp.toString(), 'autosave', 'lunch', 'on')

        then:
            wrong.status() == LampExitStatus.USAGE
            wrong.console().contains('expected startup, shutdown, before-run, after-run or chat-quiet-minutes')
    }

    def 'LampDirectory.autosave refuses to change the saves while a session runs'() {
        reportInfo """
            A session reads `oillamp.toml` only as it starts, so a change to `[autosave]` would not
            reach a running session. So `LampDirectory.autosave` throws `LampFailedException`,
            with the status `LAMP_BUSY`, while a session runs, and the file stays as it was.
        """
        given:
            var lamp = aLampThatHasRun()
            var settings = Lamp.at(lamp).launchedBy(host.launcher)
            startASession(lamp)

        when:
            settings.autosave(new Lamp.Autosave(true, true, true, true, 10))

        then:
            var busy = thrown(LampFailedException)
            busy.status() == LampExitStatus.LAMP_BUSY
            settings.autosave() == new Lamp.Autosave(false, false, true, true, 10)
    }

    @IgnoreIf({ !['/usr/bin/git', '/bin/git', '/usr/local/bin/git'].any { Files.isExecutable(Path.of(it)) } })
    def 'The program git itself can read a lamp\'s history'() {
        reportInfo """
            oillamp writes the history in git's format itself, rather than by running git, so
            that a nested .git directory is stored like any other. It is still an ordinary
            repository: a person who knows git can read it with git, without oillamp.
        """
        given:
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/game/.git/HEAD', 'ref: refs/heads/main\n')
            write(home(lamp), 'workspace/readme.txt', 'hello\n')
            assert host.oillamp.run('history', lamp.toString(), 'save', '--message', 'with a nested repository').succeeded()
            var repository = lamp.resolve('.oillamp/history')
            var agentDir = home(lamp).fileName.toString()

        expect: 'git log reads the messages'
            git(repository, 'log', '--format=%s').readLines().first() == 'idle save: with a nested repository'

        and: 'git show reads a file'
            git(repository, 'show', "HEAD:${agentDir}/workspace/readme.txt") == 'hello\n'

        and: 'git finds nothing wrong beyond the nested .git it warns about'
            var check = new ProcessBuilder('git', '--git-dir', repository.toString(), 'fsck', '--strict')
                    .redirectErrorStream(true).start()
            var said = check.inputStream.text
            check.waitFor() == 0 || said.readLines().every { it.contains('hasDotgit') }
    }

    // ─── the schedule in the snapshots ─────────────────────────────────────────────────────

    def 'Oillamp makes a snapshot when only the schedule changed'() {
        reportInfo """
            A lamp's jobs are kept in `.oillamp/schedule.json`, outside the agent's home. Every
            snapshot holds that file too, among the files it holds, at the path
            `.oillamp/schedule.json`, so that a restore can bring back the jobs the lamp had. A
            save compares the files it would store with those of the newest snapshot, and makes no
            new snapshot when nothing changed.

            Let's consider a lamp saved a moment ago, after which the user adds a job with
            `oillamp schedule add` and changes nothing else. The schedule file is among the files
            compared, so the next `oillamp history <dir> save` must make a snapshot. A save
            straight after it, with nothing changed, must make none.
        """
        given: 'a lamp saved a moment ago'
            var lamp = aLampThatHasRun()
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        when: 'the user adds a job, and saves'
            assert host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up').succeeded()
            var afterTheJob = host.oillamp.run('history', lamp.toString(), 'save', '-m', 'with a job')

        then: 'the save made a snapshot'
            afterTheJob.succeeded()
            afterTheJob.events().find { it instanceof LampEvent.Saved }.snapshot().message() == 'with a job'

        when: 'the user saves again, with nothing changed'
            var again = host.oillamp.run('history', lamp.toString(), 'save')

        then: 'that save made none'
            again.succeeded()
            !again.events().any { it instanceof LampEvent.Saved }
    }

    def 'Oillamp brings the jobs back with the snapshot it restores'() {
        reportInfo """
            A snapshot holds the lamp's schedule, and a restore brings it back as the snapshot
            holds it: every job, the user's and the agent's alike, each switched on or off as it
            was. A job added after the snapshot is gone after the restore, whoever added it. The
            jobs belong to the state the lamp is brought back to; a job the agent added for work
            it did after the snapshot would otherwise wake it for work that no longer exists.

            The number the next job gets is kept in the same file, and comes back with it. The
            numbering belongs to the restored state, like the jobs it counts: in that state
            `job-3` was never added, so the next job added is `job-3`.

            In this scenario the snapshot holds the user's `job-1`, switched off, and `job-2`,
            which the agent added through the session. After the snapshot the user switches
            `job-1` on, removes `job-2` and adds `job-3`. The restore must bring back `job-1`,
            switched off, and the agent's `job-2`, and take away `job-3`. A job added after the
            restore is called `job-3` again.
        """
        given: 'a lamp with a daily job of the user\'s'
            var lamp = aLampThatHasRun('[schedule]\nenabled = true\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')

        and: 'a job the agent added in a session'
            startASession(lamp)
            assert agentAsks(op: 'add', cron: '0 9 * * *', prompt: 'Check the build').ok
            stopTheSession(lamp)

        and: 'a snapshot made once the user switched their job off'
            host.oillamp.run('schedule', lamp.toString(), 'disable', 'job-1')
            var saved = host.oillamp.run('history', lamp.toString(), 'save').events().find { it instanceof LampEvent.Saved }.snapshot()

        and: 'later, the user switched it on, removed the agent\'s job and added another'
            host.oillamp.run('schedule', lamp.toString(), 'enable', 'job-1')
            host.oillamp.run('schedule', lamp.toString(), 'remove', 'job-2')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@hourly', 'Look at the queue')

        when: 'the user restores the snapshot'
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId())

        then: 'the user\'s job is back, switched off, and so is the agent\'s; the later one is gone'
            restored.succeeded()
            var jobs = jobsOf(lamp)
            jobs*.id() == ['job-1', 'job-2']
            !jobs[0].enabled()
            jobs[0].author() == LampJobAuthor.USER
            jobs[1].enabled()
            jobs[1].author() == LampJobAuthor.AGENT
            jobs[1].prompt() == 'Check the build'

        when: 'the user adds a job after the restore'
            var added = host.oillamp.run('schedule', lamp.toString(), 'add', '--at', 'in 2h', 'Send the summary')

        then: 'it gets the number the next job had in the snapshot'
            added.events().find { it instanceof LampEvent.JobAdded }.job().id() == 'job-3'
    }

    def 'Oillamp brings back a schedule that was #state when the snapshot was made'() {
        reportInfo """
            `oillamp schedule pause` stops every job from running, without changing any; `oillamp
            schedule resume` lets them run again. Whether the schedule is paused is kept in
            `.oillamp/schedule.json`, beside the jobs, and a snapshot holds it with them.

            A restore puts back the whole schedule file as the snapshot holds it, and keeps
            nothing of the schedule from the present. That gives one rule to know: after a
            restore, the schedule is the snapshot's. It follows that a restore can switch a paused
            schedule back on: restoring a snapshot made while the schedule ran lets the jobs run
            again, whether or not the user paused it since.

            The scenario saves a lamp whose schedule is ${state}, then ${change} it, restores the
            snapshot, and reads the paused flag with `oillamp schedule`.
        """
        given: 'a lamp with one job, saved while its schedule is paused or not'
            var lamp = aLampThatHasRun()
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')
            if (pausedThen) host.oillamp.run('schedule', lamp.toString(), 'pause')
            var saved = host.oillamp.run('history', lamp.toString(), 'save').events().find { it instanceof LampEvent.Saved }.snapshot()

        and: 'later, the user changed that'
            host.oillamp.run('schedule', lamp.toString(), pausedThen ? 'resume' : 'pause')

        when: 'the user restores the snapshot'
            assert host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId()).succeeded()

        then: 'the schedule is paused, or not, as it was in the snapshot'
            host.oillamp.run('schedule', lamp.toString()).events()
                    .find { it instanceof LampEvent.Schedule }.paused() == pausedThen

        where:
            pausedThen << [true, false]
            state = pausedThen ? 'paused' : 'running'
            change = pausedThen ? 'resumes' : 'pauses'
    }

    def 'Oillamp brings back when each job last ran'() {
        reportInfo """
            Oillamp computes a repeating job's next time from the moment it last ran, or from when
            it was added if it never ran. That moment is kept with the job in
            `.oillamp/schedule.json`, so a snapshot holds it, and a restore brings it back.

            Let's consider an `@hourly` job, added on Tuesday at 16:15 in Berlin. The lamp is
            saved at once: in that snapshot the job has never run, and runs next at 17:00. A
            session at 17:05 runs it, after which it runs next at 18:00. Restoring the snapshot
            brings the lamp back to the moment before that run, so the job runs next at 17:00
            again, as `oillamp schedule` must list it.
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

        when: 'the user restores the snapshot'
            assert host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId()).succeeded()

        then: 'the job runs next at 17:00 again'
            jobsOf(lamp).first().next() == Optional.of(Instant.parse('2026-09-22T15:00:00Z'))
    }

    def 'Oillamp keeps the current jobs when it restores a snapshot that holds no schedule'() {
        reportInfo """
            Snapshots an older version of oillamp made hold only the agent's home and
            `oillamp.toml`, and no schedule. Such a snapshot says nothing about which jobs the
            lamp had, and an empty schedule would be a guess that deletes the user's jobs. So
            oillamp restores the home and `oillamp.toml` from it, and keeps the schedule as it is.
            The snapshot that records the restore holds that schedule, so when the user saves
            straight after the restore, oillamp makes no new snapshot, because nothing changed.

            The current oillamp no longer makes such a snapshot, so this scenario writes one into
            the lamp's history by hand, as a git commit: the newest snapshot, without the
            `.oillamp/schedule.json` among its files. Then the user changes a file and adds a job,
            and restores the older snapshot. The file must be as the snapshot holds it, the job
            must still be on the schedule, and `oillamp history <dir> save` must make no new
            snapshot.
        """
        given: 'a lamp whose newest snapshot was made by an older oillamp, without the schedule'
            var lamp = aLampThatHasRun()
            write(home(lamp), 'workspace/story.txt', 'chapter one\n')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()
            var older = aSnapshotWithoutTheSchedule(lamp)

        and: 'later, the user changed a file and added a job'
            Files.writeString(home(lamp).resolve('workspace/story.txt'), 'chapter two\n')
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')

        when: 'the user restores the older snapshot'
            var restored = host.oillamp.run('history', lamp.toString(), 'restore', older.substring(0, 8))

        then: 'the home is as the snapshot holds it'
            restored.succeeded()
            Files.readString(home(lamp).resolve('workspace/story.txt')) == 'chapter one\n'

        and: 'the job is still there'
            jobsOf(lamp)*.id() == ['job-1']

        and: 'oillamp makes no new snapshot when the user saves right after, because nothing changed'
            !host.oillamp.run('history', lamp.toString(), 'save').events().any { it instanceof LampEvent.Saved }
    }

    def 'Oillamp does not give a run number out a second time after a restore'() {
        reportInfo """
            Every run has a name, such as `run-12`. Each snapshot of a run carries that name in a
            trailer: a `key: value` line at the end of the snapshot's commit message, such as
            `Oillamp-Run: run-12`, which `oillamp history` reads. So the history reads as a log of
            the runs, and two runs must never share a name, or the log would mix up what two
            different runs did.

            A restore brings back the schedule file, but not the history: the history only grows,
            and a restore adds a snapshot rather than removing the ones after its target. So
            oillamp numbers runs from the history, one above the highest `Oillamp-Run` line in it.
            A counter in the schedule file would go back with the restore and give out the same
            numbers again.

            Let's consider a lamp saved before any run. In a session, the user sends two prompts
            with `oillamp ask`, whose runs are `run-1` and `run-2`. Then the user restores the
            snapshot from before them, and sends a third prompt in a new session. Its run must be
            `run-3`, and the history must hold one `run` snapshot each for `run-1`, `run-2` and
            `run-3`.
        """
        given: 'a lamp saved before any run'
            var lamp = aLampThatHasRun('[autosave]\nchat_quiet_minutes = 0\n')
            write(home(lamp), 'workspace/plan.md', '# the plan\n')
            var saved = host.oillamp.run('history', lamp.toString(), 'save').events().find { it instanceof LampEvent.Saved }.snapshot()
            host.machine { it.agent { String prompt -> 'Done.' } }

        and: 'a session in which the user asked two questions'
            startASession(lamp)
            assert runOf(host.oillamp.run('ask', lamp.toString(), 'First?')) == 'run-1'
            assert runOf(host.oillamp.run('ask', lamp.toString(), 'Second?')) == 'run-2'
            stopTheSession(lamp)

        when: 'the user restores the snapshot from before them, and asks again in a new session'
            assert host.oillamp.run('history', lamp.toString(), 'restore', saved.shortId()).succeeded()
            reported.clear()
            startASession(lamp)
            var third = runOf(host.oillamp.run('ask', lamp.toString(), 'Third?'))

        then: 'the third question is run-3'
            third == 'run-3'

        and: 'the history holds one run snapshot for each run'
            var snapshots = host.oillamp.run('history', lamp.toString()).events()
                    .find { it instanceof LampEvent.History }.snapshots()
            snapshots.findAll { it.kind() == LampSaveKind.RUN }*.run()*.get().sort() == ['run-1', 'run-2', 'run-3']
    }

    @IgnoreIf({ !['/usr/bin/git', '/bin/git', '/usr/local/bin/git'].any { Files.isExecutable(Path.of(it)) } })
    def 'A person can read the jobs a snapshot holds with git, without oillamp'() {
        reportInfo """
            Let's consider a user who wants to see which jobs a snapshot holds before restoring
            it, because a restore replaces the whole schedule with the snapshot's. The history is
            an ordinary git repository, each snapshot one git commit, and among the files a
            snapshot holds is the schedule, at the path `.oillamp/schedule.json`, the same path
            the file has in the lamp. So `git show <snapshot>:.oillamp/schedule.json` prints it.
            The scenario saves a lamp with one job and reads that job's prompt from the newest
            snapshot with git.
        """
        given: 'a lamp with one job, saved'
            var lamp = aLampThatHasRun()
            host.oillamp.run('schedule', lamp.toString(), 'add', '--cron', '@daily', 'Tidy up')
            assert host.oillamp.run('history', lamp.toString(), 'save').succeeded()

        expect: 'git show reads the job from the newest snapshot'
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
