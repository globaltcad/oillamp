package gui

import dev.gui.AppLog
import dev.gui.model.Notifications
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant

@Title('Telling the user what happened')
@Narrative('''
A genie's lamp asks the user things, and tells them things: a request of the agent's that
needs them too, a web server it published, what the checker model decided about a request,
a warning. Genies keeps all of it in the genie's `Notifications`, with what became of each,
until the user removes it; the bell in the genie's header says when something came that is
worth a look. Under all of it, the `AppLog` keeps the last lines Genies logged, for the user
to peek into.
''')
@Subject([Notifications, AppLog])
class Telling_The_User_What_Happened_Spec extends Specification {

    static final Instant AT = Instant.parse('2026-11-05T12:00:00Z')

    @TempDir Path tmp

    def 'What the lamp only tells needs nothing of the user, but a refusal is worth a look until they looked'() {
        reportInfo """
            Let's consider a genie whose checker model allowed one request and refused another.
            Both are kept as `TOLD`: the user has nothing to answer. The refusal went against
            what the agent wanted, so it is important, and the bell asks for a look, until the
            user opened the notifications after it came.
        """
        given:
            var notifications = Notifications.NONE
                .told(new Notifications.Judged('host_read_file', 'read ~/Notes/today.md', true, 'PASSIVE', 'within the rules'), AT)

        expect: 'an allowed request is not worth a look of its own'
            notifications.all()*.status() == [Notifications.Status.TOLD]
            !notifications.needsALook(AT)
            notifications.unseen() == 1

        when: 'the checker refuses the next one'
            notifications = notifications.told(new Notifications.Judged('host_write_file', 'write ~/.bashrc', false, 'ADDITIVE', 'outside ~/Projects'), AT.plusSeconds(5))

        then: 'it is worth a look'
            notifications.needsALook(AT.plusSeconds(5))

        when: 'the user looks'
            notifications = notifications.seenAt(AT.plusSeconds(10))

        then: 'nothing more is'
            !notifications.needsALook(AT.plusSeconds(10))
            notifications.unseen() == 0
    }

    def 'A question still waiting is worth a look however often the user looked, and clearing leaves it'() {
        reportInfo """
            A question the lamp waits on keeps the bell lit until it is answered or the lamp
            gives up after `Notifications.PATIENCE`. Clearing the notifications, or removing
            that one, leaves it: the lamp still waits for its answer. Everything else goes.
        """
        given:
            var notifications = Notifications.NONE
                .published(new Notifications.Published('opencode', 4096, URI.create('http://k3v7x2ab.localhost:41237/'), false), AT)
                .told(new Notifications.Warned('The sandbox image is old', 'It was made 41 days ago.'), AT)
                .asked(new Notifications.HostTool('host_run', 'run `make test`'), AT.plusSeconds(1))
                .seenAt(AT.plusSeconds(2))
            var waiting = notifications.waitingAt(AT.plusSeconds(2)).first()

        expect: 'the question keeps it worth a look'
            notifications.needsALook(AT.plusSeconds(2))

        and: 'removing it leaves it'
            notifications.without(waiting).all().size() == 3

        and: 'clearing leaves only it'
            notifications.cleared(AT.plusSeconds(2)).all().toList() == [waiting]

        and: 'once the lamp gave up on it, clearing takes it too'
            notifications.cleared(AT.plus(Notifications.PATIENCE).plusSeconds(1)).all().isEmpty()
    }

    def 'The app log keeps information and worse, finer lines only once asked for, and only the last ones'() {
        reportInfo """
            SwingTree says a lot at its finer levels. The `AppLog` puts lines of `DEBUG` and
            `TRACE` into words only after the user asked for them on the notifications page,
            and keeps no more than `AppLog.MOST`, the newest.
        """
        given:
            var log = new AppLog()

        when:
            log.add(AppLog.Level.INFO, 'lamp rex', 'Info[area=sandbox, text=started]')
            log.add(AppLog.Level.DEBUG, 'swingtree', 'laid out a panel')

        then: 'information is kept, detail is not'
            log.lines()*.level() == [AppLog.Level.INFO]

        when: 'the user asks for detail, and much is logged'
            log.keep(AppLog.Level.DEBUG)
            (AppLog.MOST + 5).times { log.add(AppLog.Level.DEBUG, 'swingtree', "line $it") }

        then: 'only the newest are kept, each on one line'
            log.lines().size() == AppLog.MOST
            log.lines().last().text() == "line ${AppLog.MOST + 4}"
            log.added() == AppLog.MOST + 6
    }

    def 'The app log also writes information and worse to a file, which outlives Genies, for its owner alone'() {
        reportInfo """
            The log in memory is gone once Genies closes, and with it why a genie's runs failed.
            So Genies names a file in its data folder, `genies.log`, and every line of
            information and worse goes there too, whole, as one line. Finer lines stay in memory
            only: they are many and say little afterwards. The lines are written on a thread of
            their own, so a slow disk never holds up the window. The file may hold what the genies
            were asked, so only its owner may read it, and past `AppLog.MOST_BYTES` it is moved to
            `genies.log.1` and begun again.
        """
        given: 'a log that writes to a file in a folder not made yet'
            var log = new AppLog()
            var file = tmp.resolve('genies').resolve('genies.log')
            log.writeTo(file)
            log.keep(AppLog.Level.DEBUG)

        when: 'a lamp warns, over several lines, and SwingTree says something fine'
            log.add(AppLog.Level.WARN, 'lamp leeho', 'pi could not switch to the model x\nModel not found')
            log.add(AppLog.Level.DEBUG, 'swingtree', 'laid out a panel')
            log.awaitWritten(10)

        then: 'the warning is in the file, on one line, and the detail is not'
            var lines = Files.readAllLines(file)
            lines.size() == 1
            lines[0].contains('WARN  lamp leeho: pi could not switch to the model x ⏎ Model not found')

        and: 'only its owner may read it'
            Files.getPosixFilePermissions(file) == [PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE] as Set

        when: 'Genies starts again on a file already past its limit, and a line comes'
            Files.writeString(file, 'x' * (AppLog.MOST_BYTES + 1))
            var again = new AppLog()
            again.writeTo(file)
            again.add(AppLog.Level.INFO, 'lamp leeho', 'started')
            again.awaitWritten(10)

        then: 'the full file was moved aside, and a new one begun with the line'
            Files.size(tmp.resolve('genies').resolve('genies.log.1')) == AppLog.MOST_BYTES + 1
            Files.readAllLines(file).size() == 1
            Files.getPosixFilePermissions(file) == [PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE] as Set
    }
}
