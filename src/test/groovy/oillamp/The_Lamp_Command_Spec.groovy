package oillamp

import groovy.json.JsonSlurper
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

@Title('The lamp command')
@Narrative('''
`lamp` is the command the agent uses to drive its desktop: it takes screenshots, clicks, types,
presses keys, and puts buttons on the dock. It is a shell script in the sandbox image,
`/usr/local/bin/lamp`, and a thin wrapper over grim, wtype, `lamp-pointer` and a few other tools.

These scenarios run the real script, with stand-ins for those tools on the `PATH` that write down
how they were called. So they check the command lines `lamp` builds, where a shell script usually
goes wrong (an argument list built wrongly, a quoting mistake, an option in the wrong place),
without a compositor or a container. They therefore run in the fast suite, not among the spikes.
''')
class The_Lamp_Command_Spec extends Specification {

    @TempDir Path temporary

    Path lamp
    Path stubs
    Path calls

    def setup() {
        lamp = Path.of('src/main/resources/image/rootfs/usr/local/bin/lamp').toAbsolutePath()
        stubs = Files.createDirectories(temporary.resolve('bin'))
        calls = temporary.resolve('calls.txt')
        ['grim', 'wtype', 'wlrctl', 'slurp', 'swaymsg', 'lamp-pointer'].each { stub(it) }
    }

    /**
     * A stub that records exactly how it was called, and nothing else.
     *
     * <p>Built by joining explicit lines rather than from an indented block: the shebang must be
     * the first two characters of the file or the kernel will not honour it, and that is too
     * important to leave to whichever {@code stripIndent} happens to be dispatched to.
     */
    private void stub(String name) {
        var script = stubs.resolve(name)
        var lines = [
            '#!/usr/bin/env bash',
            "printf '%s' '$name' >> '$calls'",
            'for argument in "$@"; do printf \' [%s]\' "$argument" >> ' + "'$calls'; done",
            "printf '\\n' >> '$calls'",
            // grim is asked to produce a file; make one so the caller's checks are meaningful.
            name == 'grim' ? ': > "${@: -1}"' : 'true',
            // The pointer helper answers "where" with the last position it moved to.
            name == 'lamp-pointer' ? '[ "$1" = where ] && echo "700 400"' : 'true',
            // ...and "size" with the size the desktop has now.
            name == 'lamp-pointer' ? '[ "$1" = size ] && echo "${STUB_DESKTOP_SIZE:-1920x1080}"' : 'true',
            'exit 0',
        ]
        Files.writeString(script, lines.join('\n') + '\n')
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString('rwxr-xr-x'))
    }

    private Map runLamp(String... arguments) { runLamp([:], arguments) }

    private Map runLamp(Map<String, String> extraEnvironment, String... arguments) {
        var home = Files.createDirectories(temporary.resolve('home'))
        var argv = ['bash', lamp.toString()] + (arguments as List<String>)
        var process = new ProcessBuilder(argv)
        process.redirectErrorStream(true)
        Map<String, String> environment = process.environment()
        environment.put('PATH', stubs.toString() + ':' + System.getenv('PATH'))
        environment.put('LAMP_POINTER', stubs.resolve('lamp-pointer').toString())
        environment.put('HOME', home.toString())
        environment.put('OILLAMP_DISPLAY_WIDTH', '1920')
        environment.put('OILLAMP_DISPLAY_HEIGHT', '1080')
        environment.put('OILLAMP_RENDERER', 'pixman')
        environment.putAll(extraEnvironment)
        var started = process.start()
        var output = started.inputStream.text
        started.waitFor()
        [status: started.exitValue(), output: output,
         calls : Files.exists(calls) ? Files.readString(calls).trim().readLines() : []]
    }

    def '`lamp key #combination` runs wtype with one `-M` for each modifier and `-k` for the last key'() {
        reportInfo """
            Let's consider the agent pressing Ctrl+Shift+T with `lamp key ctrl+shift+t`. wtype
            takes each modifier as its own `-M`, and only the last key as `-k`, so `lamp` must run
            `wtype -M ctrl -M shift -k t`. Built the wrong way round, the command fails without an
            error: wtype sends a key press that does nothing, and the agent concludes that the
            application ignored it, and tries something else. Every call also carries `-s 150`,
            the pause without which X11 applications lose the first key.
        """

        when: '`lamp key` is run with the combination'
            var result = runLamp('key', combination)

        then: 'it called wtype once, with a `-M` for each modifier and `-k` for the last key'
            result.status == 0
            result.calls == [expected]

        where:
            combination        || expected
            't'                || 'wtype [-s] [150] [-k] [t]'
            'ctrl+t'           || 'wtype [-s] [150] [-M] [ctrl] [-k] [t]'
            'ctrl+shift+t'     || 'wtype [-s] [150] [-M] [ctrl] [-M] [shift] [-k] [t]'
            'ctrl+alt+shift+f' || 'wtype [-s] [150] [-M] [ctrl] [-M] [alt] [-M] [shift] [-k] [f]'
    }

    def '`lamp screenshot --region X,Y,W,H` passes the region to grim as `X,Y WxH`'() {
        reportInfo """
            The agent's guide, `AGENTS.md`, documents `--region X,Y,W,H`, because that is what an
            agent guesses. grim takes a region as `X,Y WxH`. If `lamp` passed the region on as it
            was, grim would fail, or capture another part of the screen, and the agent would
            reason about a picture of something else. `lamp screenshot` prints the path of the file
            it wrote, so that the agent can open it.
        """
        when: '`lamp screenshot --region 10,20,800,600 --out shot.png` is run'
            var result = runLamp('screenshot', '--region', '10,20,800,600', '--out',
                                 temporary.resolve('shot.png').toString())

        then: 'grim was called with `-g "10,20 800x600"`'
            result.status == 0
            result.calls.first().startsWith('grim [-g] [10,20 800x600]')

        and: 'the output ends with the file\'s path'
            result.output.trim().endsWith('shot.png')
    }

    def '`lamp screenshot` with no arguments writes into `~/screenshots`, and prints the file\'s path'() {
        when: '`lamp screenshot` is run'
            var result = runLamp('screenshot')

        then: 'it prints the path of a file `screenshot-….png` in `~/screenshots`'
            result.status == 0
            result.output.trim().contains('/screenshots/screenshot-')
            result.output.trim().endsWith('.png')
    }

    def '`lamp click X Y` clicks at the screen position X,Y, not at an offset from the pointer'() {
        reportInfo """
            `lamp click X Y` once passed X and Y to `wlrctl pointer move`, which moves the pointer
            *by* that much. Every click then landed away from the position the agent named, and
            nothing reported it. `lamp` now hands the click to the pointer helper, `lamp-pointer`,
            as a position on the screen. This scenario checks the call `lamp` makes; the desktop
            spike checks that an application receives the click at that position.
        """
        when: '`lamp click 100 200` is run'
            var result = runLamp('click', '100', '200')

        then: '`lamp-pointer click 100 200` was called, once'
            result.status == 0
            result.calls == ['lamp-pointer [click] [100] [200]']

        and: 'wlrctl was not called'
            result.calls.every { !it.startsWith('wlrctl') }
    }

    def '`lamp scroll` without a position scrolls where the pointer was put last'() {
        when: '`lamp scroll 3` is run'
            var result = runLamp('scroll', '3')

        then: '`lamp` asked `lamp-pointer` where the pointer is, and scrolled 3 steps there'
            result.calls == ['lamp-pointer [where]', 'lamp-pointer [scroll] [700] [400] [3]']
    }

    def '`lamp drag X1 Y1 X2 Y2` drags from the first position to the second'() {
        when: '`lamp drag 10 20 30 40` is run'
            var result = runLamp('drag', '10', '20', '30', '40')

        then: '`lamp-pointer drag 10 20 30 40` was called'
            result.calls == ['lamp-pointer [drag] [10] [20] [30] [40]']
    }

    def '`lamp type` passes the text to wtype as one argument, spaces and all'() {
        when: '`lamp type` is run with text that holds single and repeated spaces'
            var result = runLamp('type', 'hello world  with   spaces')

        then: 'wtype got the text as one argument, after `--`'
            result.calls == ['wtype [-s] [150] [--] [hello world  with   spaces]']
    }

    def 'When a tool such as wtype is missing, `lamp` names that tool, rather than failing in general'() {
        reportInfo """
            Let's consider an image from which `wtype` is missing, and the agent running
            `lamp type`. The agent must learn which tool is missing, and that `lamp` is only a thin
            wrapper over such tools, so that it does not read the failure as "the desktop is
            broken".
        """
        when: 'wtype is not on the `PATH`, and `lamp type hello` is run'
            Files.delete(stubs.resolve('wtype'))
            Files.createSymbolicLink(stubs.resolve('bash'), Path.of('/bin/bash'))
            var result = runLamp([PATH: stubs.toString()], 'type', 'hello')

        then: '`lamp` fails, names wtype, and says it is a thin wrapper'
            result.status != 0
            result.output.contains('wtype')
            result.output.contains('thin wrapper')
    }

    def '`lamp info` reports the desktop\'s size now, and its own size when that differs'() {
        reportInfo """
            Let's consider someone who watches the desktop in a panel of Genies, a desktop app
            built on oillamp, which gives the desktop the panel's size. The agent clicks at screen
            positions, so `lamp info` asks the desktop for its size now, rather than repeating the
            size the desktop started with. When the two differ, it also names the desktop's own
            size, which the desktop goes back to.
        """
        when: '`lamp info` is run while the desktop has its own size, 1920x1080'
            var own = runLamp('info')
        and: '`lamp info` is run while the desktop has the size of a panel, 800x500'
            var panel = runLamp([STUB_DESKTOP_SIZE: '800x500'], 'info')

        then: 'the first reports `size 1920x1080` alone; the second `size 800x500`, and `own size 1920x1080`'
            own.output.readLines().first() == 'size       1920x1080'
            !own.output.contains('own size')
            panel.output.readLines().take(2) == [
                    'size       800x500',
                    'own size   1920x1080, which it goes back to when the user stops showing it in a panel']
    }

    def '`lamp dock add` writes a button into `~/.config/lamp/dock.json`, which the dock reads'() {
        reportInfo """
            The agent can put buttons on the dock at the bottom of the desktop, for the user to
            click. The dock reads them from `~/.config/lamp/dock.json`, in the agent's home, which
            outlives the session, so a button is still there after the lamp has been restarted.
            `lamp dock add` writes that file, so that the agent need not write the JSON itself.
        """
        when: '`lamp dock add` adds "Report", with an icon, and "Notes", whose command holds quotes, and `lamp dock` lists them'
            runLamp('dock', 'add', 'Report', 'firefox ~/report.html', '--icon', 'firefox-esr')
            runLamp('dock', 'add', 'Notes', 'foot -e nano "my notes.txt"')
            var listed = runLamp('dock')

        then: '`dock.json` holds both buttons, each command exactly as written'
            var file = temporary.resolve('home/.config/lamp/dock.json')
            new JsonSlurper().parse(file.toFile()) == [
                    [label: 'Report', command: 'firefox ~/report.html', icon: 'firefox-esr'],
                    [label: 'Notes', command: 'foot -e nano "my notes.txt"']]

        and: '`lamp dock` lists both'
            listed.status == 0
            listed.output.contains('Report: firefox ~/report.html  (icon firefox-esr)')
            listed.output.contains('Notes: foot -e nano "my notes.txt"')
    }

    def '`lamp dock add` with a label that is already on the dock replaces that button'() {
        reportInfo """
            A label names a button, so the agent changes what its "Report" button does by adding
            "Report" again. The button keeps its place on the dock, and the dock does not show two
            buttons called "Report".
        """
        when: '"Report" and "Notes" are added, then "Report" again with another command'
            runLamp('dock', 'add', 'Report', 'firefox ~/old.html', '--icon', 'firefox-esr')
            runLamp('dock', 'add', 'Notes', 'foot')
            var replaced = runLamp('dock', 'add', 'Report', 'firefox ~/new.html')

        then: '`lamp dock add` says it replaced "Report", which keeps its place before "Notes"'
            replaced.output.contains("replaced 'Report' on the dock")
            var file = temporary.resolve('home/.config/lamp/dock.json')
            new JsonSlurper().parse(file.toFile()) == [
                    [label: 'Report', command: 'firefox ~/new.html'],
                    [label: 'Notes', command: 'foot']]
    }

    def '`lamp dock add` without a label adds an icon-only button, which its command replaces and removes'() {
        reportInfo """
            An icon can be enough to show what a button starts. The agent then leaves out the
            label, and the command names the button: adding the same command again replaces the
            button, and `lamp dock remove` with the command removes it.
        """
        when: 'a button for `firefox ~/report.html` is added with `--icon firefox-esr`, then again with `--icon web-browser`'
            var added = runLamp('dock', 'add', 'firefox ~/report.html', '--icon', 'firefox-esr')
            var replaced = runLamp('dock', 'add', 'firefox ~/report.html', '--icon', 'web-browser')
            var listed = runLamp('dock')

        then: 'the second replaced the first, and `lamp dock` lists the command with its new icon, and no label'
            added.status == 0
            replaced.status == 0
            replaced.output.contains("replaced 'firefox ~/report.html' on the dock")
            new JsonSlurper().parse(temporary.resolve('home/.config/lamp/dock.json').toFile()) == [
                    [command: 'firefox ~/report.html', icon: 'web-browser']]
            listed.output.contains('firefox ~/report.html  (icon web-browser)')
            !listed.output.contains('None:')

        when: '`lamp dock remove "firefox ~/report.html"` is run'
            var removed = runLamp('dock', 'remove', 'firefox ~/report.html')

        then: 'the dock file holds no button'
            removed.status == 0
            Files.readString(temporary.resolve('home/.config/lamp/dock.json')).trim() == '[]'
    }

    def '`lamp dock add` refuses a button with neither a label nor an icon'() {
        when: '`lamp dock add foot` is run, with no label and no `--icon`'
            var result = runLamp('dock', 'add', 'foot')

        then: 'it fails, says that an icon-only button needs `--icon ICON`, and writes no dock file'
            result.status != 0
            result.output.contains('an icon-only button needs --icon ICON')
            !Files.exists(temporary.resolve('home/.config/lamp/dock.json'))
    }

    def 'A label and an icon-only command with the same text name two separate dock buttons'() {
        reportInfo """
            Let's consider a labelled button called "foot", and an icon-only button whose command
            is `foot`. Adding one again must not replace the other: a labelled button is replaced
            only by the same label, an icon-only one only by the same command. `lamp dock remove
            foot` could mean either, and removes the labelled button first, as it did before there
            were icon-only buttons. A second removal takes the icon-only one. The scenario adds the
            two buttons in either order.
        """
        given: 'a labelled button "foot" and an icon-only button for `foot`, added in either order'
            var labelled = ['dock', 'add', 'foot', 'foot -e nano']
            var iconOnly = ['dock', 'add', 'foot', '--icon', 'utilities-terminal']
            runLamp(*(iconFirst ? iconOnly : labelled))
            runLamp(*(iconFirst ? labelled : iconOnly))

        when: 'each is added again, the labelled one with a new command and the icon-only one with a new icon'
            var replacedLabel = runLamp('dock', 'add', 'foot', 'foot -e bash')
            var replacedIcon = runLamp('dock', 'add', 'foot', '--icon', 'foot')

        then: 'each replaced only its own button, and both kept their places'
            replacedLabel.status == 0
            replacedIcon.status == 0
            var file = temporary.resolve('home/.config/lamp/dock.json')
            var expectedLabel = [label: 'foot', command: 'foot -e bash']
            var expectedIcon = [command: 'foot', icon: 'foot']
            new JsonSlurper().parse(file.toFile()) == (iconFirst
                    ? [expectedIcon, expectedLabel] : [expectedLabel, expectedIcon])

        when: '`lamp dock remove foot` is run'
            var removedLabel = runLamp('dock', 'remove', 'foot')

        then: 'the labelled button is gone'
            removedLabel.status == 0
            new JsonSlurper().parse(file.toFile()) == [expectedIcon]

        when: '`lamp dock remove foot` is run again'
            var removedIcon = runLamp('dock', 'remove', 'foot')

        then: 'the icon-only button is gone too'
            removedIcon.status == 0
            new JsonSlurper().parse(file.toFile()) == []

        where:
            iconFirst << [false, true]
    }

    def '`lamp dock remove` removes a button, and fails when there is none by that name'() {
        reportInfo """
            Removing a button that is not there is a mistake the agent should hear about, rather
            than a quiet success. The error also says that `lamp dock` lists what is on the
            dock.
        """
        given: 'a dock with a "Report" button'
            runLamp('dock', 'add', 'Report', 'firefox ~/report.html')

        when: '`lamp dock remove Report` is run twice'
            var removed = runLamp('dock', 'remove', 'Report')
            var missing = runLamp('dock', 'remove', 'Report')

        then: 'the first removes it; the second fails, says there is no "Report" on the dock, and that `lamp dock` lists what is there'
            removed.status == 0
            Files.readString(temporary.resolve('home/.config/lamp/dock.json')).trim() == '[]'
            missing.status != 0
            missing.output.contains("there is no 'Report' on the dock")
            missing.output.contains('`lamp dock` lists what is there')
    }

    def '`lamp dock add` does not write over a dock file it cannot read'() {
        reportInfo """
            The agent may also edit `~/.config/lamp/dock.json` by hand. If that left the file
            broken, writing a new list over it would throw away every button in it, unnoticed. So
            `lamp dock add` refuses, and names the file to fix.
        """
        given: 'a dock file cut off in the middle of its first button'
            var file = temporary.resolve('home/.config/lamp/dock.json')
            Files.createDirectories(file.parent)
            Files.writeString(file, '[{"label": "Report",')

        when: '`lamp dock add Notes foot` is run'
            var result = runLamp('dock', 'add', 'Notes', 'foot')

        then: 'it fails, says the file cannot be read, and leaves the file as it was'
            result.status != 0
            result.output.contains("${file} cannot be read")
            Files.readString(file) == '[{"label": "Report",'
    }

    def '`lamp dock` tells the agent when the dock is not running, so that the user cannot see the buttons'() {
        reportInfo """
            A button on a dock that is not running is a button nobody can click. The dock writes
            its process id into a file when it starts, and `lamp dock` checks whether that process
            runs, so that the agent can tell the user, rather than believe the button is there.
        """
        given: 'the path of the dock\'s process id file'
            var pidFile = temporary.resolve('lamp-dock.pid')

        when: 'no dock ever started, and `lamp dock` is run'
            var stopped = runLamp([LAMP_DOCK_PID_FILE: pidFile.toString()], 'dock')
        and: 'a running process wrote its id into the file, and `lamp dock` is run again'
            Files.writeString(pidFile, "${ProcessHandle.current().pid()}\n")
            var running = runLamp([LAMP_DOCK_PID_FILE: pidFile.toString()], 'dock')

        then: 'the first says the dock is not running, so the user does not see these buttons; the second does not'
            stopped.output.contains('The dock is not running, so the user does not see these buttons.')
            !running.output.contains('not running')
    }

    def '`lamp help` names the tools `lamp` wraps, so that the agent can use them directly'() {
        reportInfo """
            `lamp` is a thin script, and the agent may need something it does not do. The agent can
            only turn to the tools underneath if it knows them, so `lamp help` names them, and how
            to read the script itself.
        """
        when: '`lamp help` is run'
            var result = runLamp('help')

        then: 'it names grim, slurp, wtype and `lamp-pointer`'
            result.status == 0
            ['grim', 'slurp', 'wtype', 'lamp-pointer'].every { result.output.contains(it) }

        and: 'it shows how to read the script: `cat $(command -v lamp)`'
            result.output.contains('cat $(command -v lamp)')
    }

    def '`lamp` fails on a subcommand it does not know, and points to `lamp help`'() {
        when: '`lamp teleport` is run'
            var result = runLamp('teleport')

        then: 'it fails, names `teleport`, and points to `lamp help`'
            result.status != 0
            result.output.contains('teleport')
            result.output.contains('lamp help')
    }

    def '`lamp` explains a missing or malformed value, rather than leaving it to bash: lamp #line'() {
        reportInfo """
            The agent reads what `lamp` says and tries again, so an error must say what to write
            instead. A missing value once stopped bash with "unbound variable", and a `wait-stable`
            time that was not a whole number with an arithmetic syntax error.
        """
        when: '`lamp` is run with the value missing or malformed'
            var result = runLamp(*line.split(' '))

        then: 'it fails, and says what the option takes, without an error from bash'
            result.status != 0
            result.output.contains(explanation)
            !result.output.contains('unbound variable')
            !result.output.contains('syntax error')

        and: 'no tool was called'
            result.calls.isEmpty()

        where:
            line                   | explanation
            'screenshot --region'  | '--region takes X,Y,W,H'
            'screenshot --out'     | '--out takes the file to write'
            'wait-stable soon'     | 'wait-stable takes a whole number of seconds'
            'wait-stable 2.5'      | 'wait-stable takes a whole number of seconds'
    }
}
