package gui

import dev.gui.genie.Shelf
import dev.gui.genie.ShelfClaim
import dev.gui.genie.ShelfWindow
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Supplier

@Title('Opening Genies once')
@Narrative('''
Genies keeps its genies on a *shelf*, the directory `~/.local/share/genies`, with each genie's
lamp in it. Two Genies open on the same shelf would take the same genies from each other: as it
opens, each joins the lamps the other left running or lit, and putting a genie to sleep in one
puts out the lamp the other is using. Waking a genie in one while the other holds its lamp fails
with oillamp's `OIL-LOCK-001`.

So Genies claims its shelf as it starts, before it joins any lamp, with
`ShelfClaim.claimOrShowTheOther`. The claim is an operating-system lock on the shelf's
`genies.lock`, and the Genies that holds it listens on the Unix socket `genies.socket` beside it.
A second Genies started on the same shelf, as from the desktop's "New Window", gets no claim. It
asks the first through that socket to show its window, and ends without opening one. What the
first answers comes from its `ShelfWindow`, which knows whether its window is opening, open or
closing.
''')
@Subject([ShelfClaim, ShelfWindow])
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Opening_Genies_Once_Spec extends Specification {

    @TempDir Path tmp
    final List<ShelfClaim> claims = []
    final List<ShelfClaim.Answer> toldTheUser = new CopyOnWriteArrayList<>()

    def cleanup() {
        claims*.close()
    }

    def 'A second Genies gets no claim on the shelf, and the Genies that has it is asked to show its window'() {
        reportInfo """
            Let's consider a Genies whose window is open on a shelf, and the user starting Genies
            a second time. The second Genies calls `ShelfClaim.claimOrShowTheOther`, which gets no
            claim, and asks the first through `genies.socket` to show its window. The first
            Genies' `ShelfWindow` runs the action that shows the window once, and answers `SHOWN`.
            So `claimOrShowTheOther` returns no claim, and never calls its `notShown` consumer,
            which hears what to tell the user when no window came.
        """
        given: 'a Genies that has claimed the shelf, with its window open, counting how often it is shown'
            var shelf = new Shelf(tmp.resolve('genies'))
            var shown = new AtomicInteger()
            var window = new ShelfWindow()
            window.opened { shown.incrementAndGet() }
            claims << ShelfClaim.claim(shelf, window::askedToShow as Supplier).orElseThrow()

        when: 'a second Genies starts on the same shelf'
            var second = ShelfClaim.claimOrShowTheOther(shelf, { ShelfClaim.Answer.SHOWN } as Supplier, toldTheUser::add as Consumer)

        then: 'it gets no claim, and the first Genies\' window was shown once'
            second.isEmpty()
            shown.get() == 1

        and: 'its `notShown` consumer was not called'
            toldTheUser.isEmpty()
    }

    def 'A second Genies started while the first is closing tells the user so'() {
        reportInfo """
            Let's consider the user closing Genies, which then puts the genies to sleep, or leaves
            them running, before it ends. That can take a while, as each lamp saves its genie's
            home, and meanwhile the closing Genies still holds the shelf. A Genies started then
            cannot show the user a window that is about to go: the closing Genies' `ShelfWindow`
            answers `CLOSING`. `ShelfClaim.claimOrShowTheOther` then tries to claim the shelf once
            more, finds it still held, and passes `CLOSING` on, for the new Genies to tell the user
            to open it again once that window has gone.
        """
        given: 'a Genies that has claimed the shelf, and whose window is closing'
            var shelf = new Shelf(tmp.resolve('genies'))
            var window = new ShelfWindow()
            window.opened { }
            window.closing()
            claims << ShelfClaim.claim(shelf, window::askedToShow as Supplier).orElseThrow()

        when: 'a second Genies starts on the same shelf'
            var second = ShelfClaim.claimOrShowTheOther(shelf, { ShelfClaim.Answer.SHOWN } as Supplier, toldTheUser::add as Consumer)

        then: 'it gets no claim, and is to tell the user that Genies is closing'
            second.isEmpty()
            toldTheUser == [ShelfClaim.Answer.CLOSING]
    }

    def 'A second Genies gets the claim after all when the Genies it asked has given up the shelf meanwhile'() {
        reportInfo """
            Let's consider a Genies that gives up the shelf as it answers `CLOSING`, as one that
            ends right after does. The Genies that asked must not tell the user to wait for a
            window that is gone already, nor keep them from opening Genies. So
            `ShelfClaim.claimOrShowTheOther` claims the shelf once more after any answer but
            `SHOWN`, and returns that claim, for the new Genies to open.
        """
        given: 'a Genies that has claimed the shelf, and, asked to show its window, gives the shelf up and answers `CLOSING`'
            var shelf = new Shelf(tmp.resolve('genies'))
            ShelfClaim first
            first = ShelfClaim.claim(shelf, { first.close(); ShelfClaim.Answer.CLOSING } as Supplier).orElseThrow()

        when: 'a second Genies starts on the same shelf'
            var second = ShelfClaim.claimOrShowTheOther(shelf, { ShelfClaim.Answer.SHOWN } as Supplier, toldTheUser::add as Consumer)
            second.ifPresent { claims << it }

        then: 'it gets the claim, and its `notShown` consumer was not called'
            second.isPresent()
            toldTheUser.isEmpty()
    }

    def 'The shelf is free again once the process that claimed it ends, however it ends'() {
        reportInfo """
            Let's consider a Genies that does not end normally: the kernel kills it as the
            computer runs out of memory, say. Its claim must not keep every later Genies from
            opening. The claim is the operating system's lock on `genies.lock`, and the kernel
            lets go of a process's locks when the process ends, however it ends.

            Here a separate Java process locks the shelf's `genies.lock` as a Genies does, and is
            then killed with `SIGKILL`. While it runs, `ShelfClaim.claim` returns no claim; once
            it is gone, `ShelfClaim.claim` returns one.
        """
        given: 'a separate process that holds the lock on the shelf\'s `genies.lock`'
            var shelf = new Shelf(tmp.resolve('genies'))
            Files.createDirectories(shelf.root())
            var holder = processHoldingTheLockOn(shelf.claimLock())

        expect: '`ShelfClaim.claim` returns no claim on the shelf'
            ShelfClaim.claim(shelf, { ShelfClaim.Answer.SHOWN } as Supplier).isEmpty()

        when: 'the process is killed'
            holder.destroyForcibly().waitFor()

        then: '`ShelfClaim.claim` returns a claim'
            var claim = ShelfClaim.claim(shelf, { ShelfClaim.Answer.SHOWN } as Supplier)
            claim.isPresent()
            claims << claim.get()

        cleanup:
            holder?.destroyForcibly()
    }

    def 'A socket file left behind by a Genies that was killed does not keep the next one from being asked'() {
        reportInfo """
            Let's consider a Genies that was killed: its `genies.socket` stays on the shelf, as
            the kernel removes no socket file. The next Genies to claim the shelf replaces it
            with its own, which it may do: while it holds the claim, no other Genies listens
            there. `ShelfClaim.askToShow`, called by a Genies started after it, then reaches it
            and hears `SHOWN`.
        """
        given: 'a shelf with a socket file nobody listens on any more'
            var shelf = new Shelf(tmp.resolve('genies'))
            Files.createDirectories(shelf.root())
            var left = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            left.bind(UnixDomainSocketAddress.of(shelf.claimSocket()))
            left.close()
            assert Files.exists(shelf.claimSocket())

        and: 'a Genies that claims the shelf, and answers that it shows its window'
            claims << ShelfClaim.claim(shelf, { ShelfClaim.Answer.SHOWN } as Supplier).orElseThrow()

        expect: '`ShelfClaim.askToShow` reaches it, and hears `SHOWN`'
            ShelfClaim.askToShow(shelf) == ShelfClaim.Answer.SHOWN
    }

    def 'A second Genies whose question nobody answers gives up after `ShelfClaim.PATIENCE`, and tells the user that Genies is open'() {
        reportInfo """
            Let's consider a Genies that holds the shelf, but cannot be reached on
            `genies.socket`: it could not listen there, or it hangs. A Genies started then must
            not wait for it forever. `ShelfClaim.askToShow` tries again for `ShelfClaim.PATIENCE`,
            five seconds, as the holder may have claimed the shelf a moment ago and not listen
            yet, and then answers `NONE`. `ShelfClaim.claimOrShowTheOther` finds the shelf still
            held, and passes `NONE` on, for the new Genies to tell the user that Genies is open
            already, and to look for its window.
        """
        given: 'a Genies that holds the shelf, but whose socket file is gone'
            var shelf = new Shelf(tmp.resolve('genies'))
            claims << ShelfClaim.claim(shelf, { ShelfClaim.Answer.SHOWN } as Supplier).orElseThrow()
            Files.delete(shelf.claimSocket())

        when: 'a second Genies starts on the same shelf'
            long started = System.nanoTime()
            var second = ShelfClaim.claimOrShowTheOther(shelf, { ShelfClaim.Answer.SHOWN } as Supplier, toldTheUser::add as Consumer)
            var waited = Duration.ofNanos(System.nanoTime() - started)

        then: 'it gets no claim, and is to tell the user that no answer came'
            second.isEmpty()
            toldTheUser == [ShelfClaim.Answer.NONE]

        and: 'it waited about `ShelfClaim.PATIENCE`'
            waited >= ShelfClaim.PATIENCE
            waited < ShelfClaim.PATIENCE.plusSeconds(3)
    }

    def 'A `ShelfWindow` answers `SHOWN` while its window is opening or open, shows it only once it is open, and answers `CLOSING` after'() {
        reportInfo """
            Let's consider the Genies that holds the shelf, through the life of its window. While
            the window is still opening, `ShelfWindow.askedToShow` returns `SHOWN`: the window is
            on its way, and nothing is there to show yet. Once `ShelfWindow.opened` was called,
            `askedToShow` runs the action that shows the window, and returns `SHOWN`. After
            `ShelfWindow.closing`, it returns `CLOSING`, and shows nothing.
        """
        given: 'a window that is opening, and counts how often it is shown'
            var shown = new AtomicInteger()
            var window = new ShelfWindow()

        expect: 'it answers `SHOWN`, and shows nothing yet'
            window.askedToShow() == ShelfClaim.Answer.SHOWN
            shown.get() == 0

        when: 'it is open'
            window.opened { shown.incrementAndGet() }

        then: 'it answers `SHOWN`, and is shown'
            window.askedToShow() == ShelfClaim.Answer.SHOWN
            shown.get() == 1

        when: 'it closes'
            window.closing()

        then: 'it answers `CLOSING`, and is not shown again'
            window.askedToShow() == ShelfClaim.Answer.CLOSING
            shown.get() == 1
    }

    /// A Java process of its own that locks `lockFile` as a Genies does, and says so once it holds it.
    private Process processHoldingTheLockOn(Path lockFile) {
        Path program = tmp.resolve('HoldTheLock.java')
        Files.writeString(program, '''
            import java.nio.channels.FileChannel;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;

            class HoldTheLock {
                public static void main(String[] arguments) throws Exception {
                    var channel = FileChannel.open(Path.of(arguments[0]), StandardOpenOption.CREATE,
                                                   StandardOpenOption.READ, StandardOpenOption.WRITE);
                    channel.lock();
                    System.out.println("held");
                    System.out.flush();
                    Thread.sleep(Long.MAX_VALUE);
                }
            }
        '''.stripIndent())
        String java = Path.of(System.getProperty('java.home'), 'bin', 'java').toString()
        Process holder = new ProcessBuilder(java, program.toString(), lockFile.toString()).redirectErrorStream(true).start()
        String said = holder.inputReader().readLine()
        assert said == 'held'
        holder
    }
}
