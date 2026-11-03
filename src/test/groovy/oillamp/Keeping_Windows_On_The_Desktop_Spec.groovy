package oillamp

import groovy.json.JsonOutput
import spock.lang.Narrative
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Title('Keeping windows on the desktop')
@Narrative('''
A program built on oillamp that shows the sandbox's desktop, such as Genies showing it in a panel,
may ask for the desktop to take the panel's size. sway, the sandbox's compositor, lays out
fullscreen and tiled windows again by itself, but leaves floating windows where they were, which on
a smaller desktop can be out of reach. The helper `keep-windows-on-screen` brings them back.

These scenarios run the real helper against a stand-in `swaymsg`, which hands out one prepared
window tree for each change of the desktop's size, and writes down every command the helper sends.
No container is needed. The desktop spike checks the same with real sway and a real window.
''')
@Requires({ Files.isExecutable(Path.of('/usr/bin/python3')) })
class Keeping_Windows_On_The_Desktop_Spec extends Specification {

    static final Path HELPER = Path.of('src/main/resources/image/rootfs/usr/local/lib/oillamp/keep-windows-on-screen').toAbsolutePath()
    static final int TITLE = 25

    Path directory

    def setup() {
        directory = Files.createTempDirectory('keep-windows-')
        // The stand-in swaymsg. Asked to subscribe, it reports one change of the desktop per tree,
        // each only once the helper has read the tree before it. Asked for the tree, it gives the
        // next one. Given anything else, it writes it down as a command, with the tree it came after.
        var swaymsg = directory.resolve('swaymsg')
        Files.writeString(swaymsg, """\
            #!/usr/bin/env bash
            dir=${directory}
            read_count() { cat "\$dir/read" 2>/dev/null || echo 0; }
            if [ "\$1 \$2" = "-t subscribe" ]; then
                trees=\$(ls "\$dir"/tree-*.json | wc -l)
                for ((i = 1; i <= trees; i++)); do
                    until [ "\$(read_count)" -ge \$((i - 1)) ]; do sleep 0.05; done
                    sleep 0.2
                    echo '{"change":"unspecified"}'
                done
                until [ "\$(read_count)" -ge "\$trees" ]; do sleep 0.05; done
                sleep 0.5
                exit 0
            elif [ "\$1 \$2" = "-t get_tree" ]; then
                n=\$((\$(read_count) + 1))
                cat "\$dir/tree-\$n.json"
                echo "\$n" > "\$dir/read"
            else
                echo "after tree \$(read_count): \$*" >> "\$dir/commands"
            fi
            """.stripIndent())
        swaymsg.toFile().setExecutable(true)
    }

    def cleanup() {
        directory.toFile().deleteDir()
    }

    def '`keep-windows-on-screen` moves a floating window that a smaller desktop cuts off back onto it, at the size it has'() {
        reportInfo """
            `keep-windows-on-screen` runs in the sandbox, and moves floating windows back onto
            sway's desktop when the desktop changes size. Let's consider the desktop shrinking from
            1920x1080 to 800x500, and a window at 1000,300 that is 600 wide and 400 high, title bar
            included. It now begins beyond the right edge. It still fits, so it keeps its size, and
            moves only as far as it must: against the right and bottom edges, to 200,100.
        """
        given: 'a desktop of 800x500, and a window at 1000,300, 600x400'
            desktop 1, 800, 500, window(5, 1000, 300, 600, 400)

        when: 'the helper runs'
            var commands = arrange()

        then: 'it moved the window to 200,100, and did not resize it'
            commands == ['after tree 1: [con_id=5] move absolute position 200 100']
    }

    def '`keep-windows-on-screen` makes a window larger than the desktop as large as the desktop'() {
        reportInfo """
            `keep-windows-on-screen` runs in the sandbox, and moves floating windows back onto
            sway's desktop when the desktop changes size. A window of 1200x900 cannot fit on a
            desktop of 800x500 by moving, so the helper makes it 800x500, the whole desktop, title
            bar included, and moves it to the corner.
        """
        given: 'a desktop of 800x500, and a window of 1200x900'
            desktop 1, 800, 500, window(5, 100, 100, 1200, 900)

        when: 'the helper runs'
            var commands = arrange()

        then: 'it made the window 800x500, and moved it to 0,0'
            commands == ['after tree 1: [con_id=5] resize set width 800 px height 500 px; [con_id=5] move absolute position 0 0']
    }

    def '`keep-windows-on-screen` leaves alone windows that fit, fullscreen windows, and windows on sway\'s scratchpad'() {
        reportInfo """
            `keep-windows-on-screen`, which runs in the sandbox, touches only windows that are out
            of reach after the desktop changed size. A window that fits stays where it is, sway
            itself sizes a fullscreen window, and sway's scratchpad, where it keeps hidden windows
            and which it lists as the output `__i3`, is not a screen.
        """
        given: 'a desktop of 800x500 with a window that fits and a fullscreen window, and a window far away on the scratchpad'
            tree 1, output('HEADLESS-1', 800, 500, [window(5, 10, 10, 300, 200),
                                                    window(6, 0, 0, 1920, 1080) + [fullscreen_mode: 1]]),
                    output('__i3', 0, 0, [window(7, 5000, 5000, 300, 200)])

        expect: 'the helper sent no command'
            arrange() == []
    }

    def 'When the desktop grows again, `keep-windows-on-screen` puts a window back where it was'() {
        reportInfo """
            Let's consider a program built on oillamp, such as Genies, that showed the desktop in a
            small panel. The user closes the panel, and the desktop goes back to its own size. The
            helper `keep-windows-on-screen` had moved and shrunk a window to fit the panel; now it
            puts the window back where it was, at the size it had.
        """
        given: 'a window moved and shrunk to fit a small desktop'
            desktop 1, 800, 500, window(5, 900, 300, 1000, 700)
        and: 'the desktop back at its own size, the window where it was put'
            desktop 2, 1920, 1080, window(5, 0, 0, 800, 500)

        when: 'the helper runs'
            var commands = arrange()

        then: 'it fitted the window into the small desktop, then put it back at 900,300, 1000x700'
            commands == ['after tree 1: [con_id=5] resize set width 800 px height 500 px; [con_id=5] move absolute position 0 0',
                         'after tree 2: [con_id=5] resize set width 1000 px height 700 px; [con_id=5] move absolute position 900 300']
    }

    def '`keep-windows-on-screen` leaves a window that someone moved in the meantime where they put it'() {
        reportInfo """
            `keep-windows-on-screen` moved a window to fit a small desktop. While the desktop was
            small, someone dragged the window to 40,40. That is now where the window belongs, so
            when the desktop grows, the helper does not take it back to where it once was.
        """
        given: 'the window moved to fit a small desktop, then dragged to 40,40, then the desktop grown again'
            desktop 1, 800, 500, window(5, 1000, 300, 600, 400)
            desktop 2, 800, 500, window(5, 40, 40, 600, 400)
            desktop 3, 1920, 1080, window(5, 40, 40, 600, 400)

        expect: 'the helper moved the window only the first time'
            arrange() == ['after tree 1: [con_id=5] move absolute position 200 100']
    }

    def '`keep-windows-on-screen` never puts a window\'s title or app id into a sway command'() {
        reportInfo """
            `keep-windows-on-screen` runs as the infra user, who owns sway, and sway runs whatever
            command it is given. The agent names its windows, so a window's title must never reach
            a command. The helper builds its commands only from numbers sway gave: a window's id
            and its position.
        """
        given: 'a window cut off by the desktop, whose title and app id hold sway commands'
            desktop 1, 800, 500, window(5, 1000, 300, 600, 400) + [name: 'x"]; exec rm -rf ~', app_id: 'evil; exit']

        when: 'the helper runs'
            var commands = arrange()

        then: 'its one command holds only the window\'s id and position'
            commands == ['after tree 1: [con_id=5] move absolute position 200 100']
    }

    // ─── the stand-in sway ─────────────────────────────────────────────────────────────────

    /** The tree sway gives for the `n`th change of the desktop: one screen of this size. */
    private void desktop(int n, int width, int height, Map... windows) {
        tree(n, output('HEADLESS-1', width, height, windows as List))
    }

    private void tree(int n, Map... outputs) {
        Files.writeString(directory.resolve("tree-${n}.json"), JsonOutput.toJson([type: 'root', nodes: outputs as List]))
    }

    private static Map output(String name, int width, int height, List windows) {
        [type: 'output', name: name, rect: [x: 0, y: 0, width: width, height: height],
         nodes: [[type: 'workspace', name: '1', floating_nodes: windows]]]
    }

    /** A floating window whose whole box, title bar included, is at x,y and this large. */
    private static Map window(int id, int x, int y, int width, int height) {
        [id: id, type: 'floating_con', name: 'a window', fullscreen_mode: 0,
         rect: [x: x, y: y + TITLE, width: width, height: height - TITLE],
         deco_rect: [x: x, y: y, width: width, height: TITLE]]
    }

    /** Runs the helper until the stand-in has reported every change; the commands it sent. */
    private List<String> arrange() {
        var process = new ProcessBuilder('python3', HELPER.toString()).redirectErrorStream(true)
        process.environment().put('PATH', "${directory}:${System.getenv('PATH')}".toString())
        var running = process.start()
        assert running.waitFor(30, TimeUnit.SECONDS), 'the helper did not end with the stand-in'
        var output = new String(running.inputStream.readAllBytes())
        assert running.exitValue() == 0, output
        var log = directory.resolve('commands')
        Files.exists(log) ? Files.readAllLines(log) : []
    }
}
