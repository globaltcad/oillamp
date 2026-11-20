package oillamp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Title('The lamp command')
@Narrative('''
`lamp` is the command the agent uses to drive its desktop: it takes screenshots, clicks, types,
presses keys, and puts buttons on the dock. It is a shell script in the sandbox image,
`/usr/local/bin/lamp`, and a thin wrapper over grim, wtype, `lamp-pointer` and a few other tools.
With `lamp search`, the agent also searches the web, through SearXNG, a meta search engine
installed in the image.

These scenarios run the real script, with stand-ins for those tools on the `PATH` that write down
how they were called. So they check the command lines `lamp` builds, where a shell script usually
goes wrong (an argument list built wrongly, a quoting mistake, an option in the wrong place),
without a compositor or a container. They therefore run in the fast suite, not among the spikes.

For `lamp search`, a stand-in takes SearXNG's place in a directory of its own, which the scenarios
name to `lamp` in `LAMP_SEARXNG_HOME`. It is a small Python HTTP server on the Unix socket that
`lamp search` names in `SEARXNG_BIND_ADDRESS`, as SearXNG is. It answers every search with the
JSON a scenario gives it, in the shape of SearXNG's answer: `results`, each with `title`, `url`
and `content`, and `unresponsive_engines`, each an engine's name and why it failed. It writes
down each time it is started, and each search it was asked. Whether the real SearXNG answers
real searches, only the real image shows: see `docs/WEB-SEARCH.md`.
''')
class The_Lamp_Command_Spec extends Specification {

    @TempDir Path temporary

    Path lamp
    Path stubs
    Path calls
    /** The stand-in SearXNG's directory; see [#standInSearxng]. */
    Path searxng

    def setup() {
        lamp = Path.of('src/main/resources/image/rootfs/usr/local/bin/lamp').toAbsolutePath()
        stubs = Files.createDirectories(temporary.resolve('bin'))
        calls = temporary.resolve('calls.txt')
        ['grim', 'wtype', 'wlrctl', 'slurp', 'swaymsg', 'lamp-pointer'].each { stub(it) }
    }

    def cleanup() {
        // The stand-in SearXNG runs on after `lamp search` ends, as the real one does.
        if (searxng == null) return
        startsOfSearxng().each { ProcessHandle.of(it.pid as long).ifPresent { it.destroyForcibly() } }
        searxng.toFile().deleteDir()
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
            'search'               | 'search takes what to search for'
            'search -n'            | '-n takes how many results to print'
            'search -n 0 sqlite'   | '-n takes how many results to print'
    }

    def '`lamp search` prints the top five results, each with a number, its title, its link and a snippet'() {
        reportInfo """
            Let's consider the agent running `lamp search "sqlite write ahead log"`. `lamp search`
            asks SearXNG for the results as JSON, with `format=json`, and prints them as plain
            text: for each result a number and the title, then the link on a line of its own, then
            the snippet SearXNG gives as `content`, the words around the match on the page.
            SearXNG merges what several engines found, often twenty results or more, so
            `lamp search` prints the first five unless `-n COUNT` asks for another number. Here the
            stand-in SearXNG answers with seven results.
        """
        given: 'a stand-in SearXNG that answers with seven results'
            standInSearxng()
            answer((1..7).collect { [title: "Result $it", url: "https://example.org/$it", content: "About $it."] }, [])

        when: '`lamp search "sqlite write ahead log"` is run, and then `lamp search -n 2 "sqlite write ahead log"`'
            var five = search('sqlite write ahead log')
            var two = search('-n', '2', 'sqlite write ahead log')

        then: 'SearXNG was asked for the query as JSON'
            searchesAsked().first() == '/search?q=sqlite+write+ahead+log&format=json'

        and: 'the first prints results 1 to 5, the second results 1 and 2, each as number and title, link, snippet, and a blank line'
            five.status == 0
            five.output == (1..5).collect { "${it}. Result $it\n   https://example.org/$it\n   About $it.\n\n" }.join()
            two.status == 0
            two.output == (1..2).collect { "${it}. Result $it\n   https://example.org/$it\n   About $it.\n\n" }.join()
    }

    def 'When every engine failed, `lamp search` names them on stderr and exits with 1, rather than reporting no results'() {
        reportInfo """
            Search engines refuse automated searches for a while after a burst of them, with a
            captcha or HTTP 429, "too many requests". SearXNG then answers with no `results`, and
            lists the engines that failed in `unresponsive_engines`, each with the reason. Printing
            "no results" would tell the agent that nothing about its question exists. So
            `lamp search` prints nothing on stdout, names each failed engine and its reason on
            stderr, tells the agent to try again in a few minutes, and exits with 1.
        """
        given: 'a stand-in SearXNG that answers with no results, and Brave and DuckDuckGo in `unresponsive_engines`'
            standInSearxng()
            answer([], [['brave', 'too many requests'], ['duckduckgo', 'CAPTCHA']])

        when: '`lamp search "sqlite write ahead log"` is run'
            var result = search('sqlite write ahead log')

        then: 'it exits with 1 and prints nothing on stdout'
            result.status == 1
            result.output == ''

        and: 'stderr names both engines with their reasons, and says to try again in a few minutes'
            result.errors.contains('no results, and engines failed: brave (too many requests), duckduckgo (CAPTCHA)\n')
            result.errors.contains('Try again in a few minutes.')
    }

    def 'When the engines answered and found nothing, `lamp search` prints `No results for "…".` and exits with 0'() {
        reportInfo """
            Let's consider a search for something no page mentions. Every engine answers, and
            none found anything: SearXNG's answer has no `results` and no `unresponsive_engines`.
            That is an answer to the search, not a failure, so `lamp search` says so on stdout and
            exits with 0. Only an empty answer with failed engines is an error.
        """
        given: 'a stand-in SearXNG that answers with no results and no failed engines'
            standInSearxng()
            answer([], [])

        when: '`lamp search "xyzzy plover frobnicate"` is run'
            var result = search('xyzzy plover frobnicate')

        then: 'it prints `No results for "xyzzy plover frobnicate".`, nothing on stderr, and exits with 0'
            result.status == 0
            result.output == 'No results for "xyzzy plover frobnicate".\n'
            result.errors == ''
    }

    def 'When some engines failed, `lamp search` prints what the others found, and names the failed engines on stderr'() {
        reportInfo """
            Most days, one engine or another refuses a search, and the others answer. The results
            are then still worth printing, but there may be fewer than usual, and the agent should
            know why. So `lamp search` prints the results on stdout and exits with 0, and names the
            failed engines on stderr, where they stay out of the results.
        """
        given: 'a stand-in SearXNG that answers with one result, and Brave in `unresponsive_engines`'
            standInSearxng()
            answer([[title: 'Write-Ahead Logging', url: 'https://sqlite.org/wal.html', content: 'WAL mode.']],
                   [['brave', 'too many requests']])

        when: '`lamp search "sqlite write ahead log"` is run'
            var result = search('sqlite write ahead log')

        then: 'stdout holds the one result, and the exit status is 0'
            result.status == 0
            result.output == '1. Write-Ahead Logging\n   https://sqlite.org/wal.html\n   WAL mode.\n\n'

        and: 'stderr names Brave and its reason'
            result.errors.trim() == 'lamp: not every engine answered, so these results may be fewer than usual: brave (too many requests)'
    }

    def 'The first `lamp search` starts SearXNG on a Unix socket with a secret key, and the next search asks the same SearXNG'() {
        reportInfo """
            SearXNG is not started with the session: a session that never searches should not pay
            for it. The first `lamp search` finds nothing answering on SearXNG's socket, and starts
            SearXNG as `python -m searx.webapp`, with the Python of SearXNG's virtualenv. It passes
            three variables: `SEARXNG_SETTINGS_PATH`, the settings in the image,
            `/etc/searxng/settings.yml`; `SEARXNG_BIND_ADDRESS`, the socket `searxng.sock` in the
            agent's runtime directory, given here in `LAMP_SEARCH_DIR`; and `SEARXNG_SECRET`, a
            secret key made for this start, which SearXNG needs in order to run at all. A Unix
            socket rather than a port, because oillamp offers every port the agent listens on to
            the user, to open in their browser. SearXNG keeps running after the search, and the
            next search asks it again instead of starting another.
        """
        given: 'a stand-in SearXNG that is not running'
            standInSearxng()
            answer([[title: 'Write-Ahead Logging', url: 'https://sqlite.org/wal.html', content: 'WAL mode.']], [])

        when: '`lamp search` is run twice'
            var first = search('sqlite write ahead log')
            var second = search('sqlite checkpoint')

        then: 'both printed the result'
            first.status == 0
            second.status == 0
            [first, second].every { it.output.startsWith('1. Write-Ahead Logging') }

        and: 'SearXNG was started once, as `python -m searx.webapp`, with the settings in the image, the socket in `LAMP_SEARCH_DIR` and a secret key of 64 hexadecimal digits'
            var starts = startsOfSearxng()
            starts.size() == 1
            starts.first().arguments == '-m searx.webapp'
            starts.first().settings == '/etc/searxng/settings.yml'
            starts.first().bindAddress == "unix://${searxng.resolve('run/searxng.sock')}"
            starts.first().secret ==~ /[0-9a-f]{64}/

        and: 'the one SearXNG was asked both searches'
            searchesAsked() == ['/search?q=sqlite+write+ahead+log&format=json', '/search?q=sqlite+checkpoint&format=json']
    }

    def 'Two first searches at once start one SearXNG, and both print its results'() {
        reportInfo """
            An agent may run two searches at once, for example in two shells. When neither finds
            SearXNG answering, both would start one, and the second SearXNG would take over the
            socket of the first. So the start is done under a lock, the file `start.lock` beside
            the socket: the second search waits for the lock, then finds SearXNG answering, and
            starts nothing. Here the stand-in SearXNG takes one second before it listens, so that
            the second search arrives while the first one is still starting it.
        """
        given: 'a stand-in SearXNG that takes one second to start listening'
            standInSearxng()
            answer([[title: 'Write-Ahead Logging', url: 'https://sqlite.org/wal.html', content: 'WAL mode.']], [])

        when: 'two `lamp search` commands are started together'
            var searches = ['sqlite write ahead log', 'sqlite checkpoint'].collect {
                startSearch([STUB_SEARXNG_DELAY: '1'], it)
            }
            var results = searches.collect { finished(it) }

        then: 'both printed the result'
            results.every { it.status == 0 && it.output.startsWith('1. Write-Ahead Logging') }

        and: 'SearXNG was started once'
            startsOfSearxng().size() == 1
    }

    def 'When SearXNG does not answer within `LAMP_SEARCH_START_SECONDS`, `lamp search` stops it, fails, and names its log'() {
        reportInfo """
            Let's consider a SearXNG that starts but never answers, for example because it hangs
            while loading. `lamp search` waits for it for a limited time, 30 seconds unless
            `LAMP_SEARCH_START_SECONDS` says otherwise, here 2. It then stops that SearXNG, so that
            the next search starts a new one, and fails with exit status 1. The message names the
            log `searxng.log`, beside the socket, into which SearXNG writes what went wrong.
        """
        given: 'a stand-in SearXNG that starts but never listens'
            standInSearxng()

        when: '`lamp search` is run with `LAMP_SEARCH_START_SECONDS=2`'
            var result = search([STUB_SEARXNG_SILENT: '1', LAMP_SEARCH_START_SECONDS: '2'], 'sqlite write ahead log')

        then: 'it exits with 1, and says that SearXNG did not answer within 2 seconds and was stopped'
            result.status == 1
            result.errors.contains('did not answer within 2 seconds, and was stopped')

        and: 'the message names the log, which exists'
            var log = searxng.resolve('run/searxng.log')
            result.errors.contains("Its log may say why: $log")
            Files.exists(log)

        and: 'the SearXNG it started no longer runs'
            endsWithinSeconds(startsOfSearxng().first().pid as long, 5)
    }

    def 'When SearXNG ended and left its socket behind, the next `lamp search` removes the socket and starts a new SearXNG'() {
        reportInfo """
            Let's consider a SearXNG that `lamp search` started, and that ended later, killed or
            crashed. Its socket file `searxng.sock` is still there, but nothing answers on it. The
            next `lamp search` finds that nothing answers, removes the socket file, since a new
            SearXNG could not listen on that path while the file exists, and starts a new SearXNG,
            which then answers the search.
        """
        given: 'a stand-in SearXNG that the first `lamp search` started, then killed, which leaves its socket file behind'
            standInSearxng()
            answer([[title: 'Write-Ahead Logging', url: 'https://sqlite.org/wal.html', content: 'WAL mode.']], [])
            assert search('sqlite write ahead log').status == 0
            var first = startsOfSearxng().first()
            ProcessHandle.of(first.pid as long).ifPresent { it.destroyForcibly() }
            assert endsWithinSeconds(first.pid as long, 5)
            assert Files.exists(searxng.resolve('run/searxng.sock'))

        when: '`lamp search "sqlite checkpoint"` is run'
            var result = search('sqlite checkpoint')

        then: 'it printed the result, with exit status 0'
            result.status == 0
            result.output.startsWith('1. Write-Ahead Logging')

        and: 'SearXNG was started a second time, and the stand-in was asked the second search'
            startsOfSearxng().size() == 2
            searchesAsked().last() == '/search?q=sqlite+checkpoint&format=json'
    }

    def 'When SearXNG ends while it starts, `lamp search` fails with its exit code and names its log'() {
        reportInfo """
            Let's consider a SearXNG that cannot start, for example because its settings do not
            parse: it writes why into its output and ends. `lamp search` sends that output to the
            log `searxng.log`, beside the socket. It sees that SearXNG ended before it answered,
            and fails at once with exit status 1, rather than wait out its 30 seconds. The message
            gives SearXNG's exit code and names the log. Here the stand-in SearXNG writes one line
            and ends with exit code 3.
        """
        given: 'a stand-in SearXNG that writes "stand-in SearXNG: cannot start" and ends with exit code 3'
            standInSearxng()

        when: '`lamp search` is run'
            var result = search([STUB_SEARXNG_EXIT: '3'], 'sqlite write ahead log')

        then: 'it exits with 1, and says on stderr that SearXNG ended while starting, with exit code 3'
            result.status == 1
            result.errors.contains('ended while starting (exit code 3)')

        and: 'the message names the log, which holds what SearXNG wrote'
            var log = searxng.resolve('run/searxng.log')
            result.errors.contains("Its log says why: $log")
            Files.readString(log).contains('stand-in SearXNG: cannot start')
    }

    def 'When an engine fails with a connection error, `lamp search` adds that a network rule of the lamp may refuse its site'() {
        reportInfo """
            The lamp's network policy may refuse an engine's site. oillamp's proxy then answers
            with HTTP 403 and a body that names the rule, and the agent's guide tells it to report
            that message. But the engine's request is SearXNG's, not the agent's: SearXNG keeps
            only its own reason, "HTTP connection error", which also stands for a site that is
            down. So after the failed engines, `lamp search` names each that failed with a
            connection error, and adds that a network rule of this lamp may refuse its site, and
            that oillamp shows the human each refusal with its rule. Here Yahoo failed with a
            connection error and Brave with "too many requests", and there are no results.
        """
        given: 'a stand-in SearXNG that answers with no results, Yahoo failed with "HTTP connection error" and Brave with "too many requests"'
            standInSearxng()
            answer([], [['brave', 'too many requests'], ['yahoo', 'HTTP connection error']])

        when: '`lamp search "sqlite write ahead log"` is run'
            var result = search('sqlite write ahead log')

        then: 'it exits with 1, and stderr names both engines with their reasons'
            result.status == 1
            result.errors.contains('engines failed: brave (too many requests), yahoo (HTTP connection error)')

        and: 'the next line names Yahoo alone, and says a network rule of this lamp may refuse its site'
            result.errors.contains("\nyahoo: a connection error may also mean that a network rule of this lamp refuses " +
                    "that engine's site. oillamp shows the human each refusal, with the rule.\n")
    }

    def 'Without SearXNG in the image, `lamp search` says that search is not installed'() {
        reportInfo """
            SearXNG is installed when the image is built, and a failed install does not fail the
            build: the image is then without search. Before it starts SearXNG, `lamp search`
            checks for SearXNG's directory, `/opt/searxng`, and when SearXNG is not there, says
            that it is not installed, so that the agent can tell the user rather than look for a
            fault of its own. Here `LAMP_SEARXNG_HOME` names an empty directory in its place.
        """
        given: 'an empty directory in place of `/opt/searxng`'
            standInSearxng()
            var nowhere = Files.createDirectories(temporary.resolve('no-searxng'))

        when: '`lamp search` is run'
            var result = search([LAMP_SEARXNG_HOME: nowhere.toString()], 'sqlite write ahead log')

        then: 'it exits with 1, prints nothing on stdout, and says on stderr that SearXNG is not installed in this image'
            result.status == 1
            result.output == ''
            result.errors.contains('SearXNG, which it asks, is not installed in this image')

        and: 'no SearXNG was started'
            startsOfSearxng().isEmpty()
    }

    /**
     * Puts a stand-in for SearXNG in a directory of its own, laid out as `/opt/searxng` is:
     * `venv/bin/python` and `src/searx/`. Its `python` runs `server.py`, which writes a line into
     * `starts.txt`; with `STUB_SEARXNG_EXIT` it prints one line and ends with that exit code. It
     * waits `STUB_SEARXNG_DELAY` seconds, or for ever with `STUB_SEARXNG_SILENT`,
     * and then serves on the socket `SEARXNG_BIND_ADDRESS` names: `/healthz` answers `OK`, and
     * every other path is written into `requests.txt` and answered with `answer.json`.
     *
     * <p>Not under {@code temporary}: a Unix socket's path may be at most 107 bytes long.
     */
    private void standInSearxng() {
        searxng = Files.createTempDirectory('lamp-search')
        Files.createDirectories(searxng.resolve('src/searx'))
        var server = searxng.resolve('server.py')
        Files.writeString(server, [
            'import http.server, os, socketserver, sys, time',
            'here = os.path.dirname(os.path.abspath(__file__))',
            'with open(os.path.join(here, "starts.txt"), "a") as starts:',
            '    starts.write("\\t".join([str(os.getpid()), " ".join(sys.argv[1:]), os.environ["SEARXNG_SETTINGS_PATH"],',
            '                            os.environ["SEARXNG_BIND_ADDRESS"], os.environ["SEARXNG_SECRET"]]) + "\\n")',
            'if os.environ.get("STUB_SEARXNG_EXIT"):',
            '    print("stand-in SearXNG: cannot start", flush=True)',
            '    sys.exit(int(os.environ["STUB_SEARXNG_EXIT"]))',
            'time.sleep(float(os.environ.get("STUB_SEARXNG_DELAY", "0")))',
            'while os.environ.get("STUB_SEARXNG_SILENT"):',
            '    time.sleep(60)',
            'class Handler(http.server.BaseHTTPRequestHandler):',
            '    def do_GET(self):',
            '        if self.path == "/healthz":',
            '            body = b"OK"',
            '        else:',
            '            with open(os.path.join(here, "requests.txt"), "a") as requests:',
            '                requests.write(self.path + "\\n")',
            '            with open(os.path.join(here, "answer.json"), "rb") as answer:',
            '                body = answer.read()',
            '        self.send_response(200)',
            '        self.send_header("Content-Length", str(len(body)))',
            '        self.end_headers()',
            '        self.wfile.write(body)',
            '    def log_message(self, *arguments):',
            '        pass',
            'class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):',
            '    daemon_threads = True',
            'Server(os.environ["SEARXNG_BIND_ADDRESS"][len("unix://"):], Handler).serve_forever()',
        ].join('\n') + '\n')
        var python = Files.createDirectories(searxng.resolve('venv/bin')).resolve('python')
        Files.writeString(python, ['#!/usr/bin/env bash', "exec python3 '$server' \"\$@\""].join('\n') + '\n')
        Files.setPosixFilePermissions(python, PosixFilePermissions.fromString('rwxr-xr-x'))
    }

    /** What the stand-in SearXNG answers every search with, in the shape of SearXNG's JSON. */
    private void answer(List<Map> results, List<List<String>> unresponsiveEngines) {
        Files.writeString(searxng.resolve('answer.json'), JsonOutput.toJson(
                [query: '', results: results, answers: [], corrections: [], infoboxes: [],
                 suggestions: [], unresponsive_engines: unresponsiveEngines]))
    }

    /** Each start of the stand-in SearXNG: its process id, arguments and the three variables. */
    private List<Map> startsOfSearxng() {
        var file = searxng.resolve('starts.txt')
        if (!Files.exists(file)) return []
        Files.readAllLines(file).collect { line ->
            var fields = line.split('\\t', -1)
            [pid: fields[0], arguments: fields[1], settings: fields[2], bindAddress: fields[3], secret: fields[4]]
        }
    }

    /** The paths, with their queries, of the searches the stand-in SearXNG was asked. */
    private List<String> searchesAsked() {
        var file = searxng.resolve('requests.txt')
        Files.exists(file) ? Files.readAllLines(file) : []
    }

    /** Whether the process ends, or has ended, within that many seconds. */
    private static boolean endsWithinSeconds(long pid, int seconds) {
        var process = ProcessHandle.of(pid)
        if (process.isEmpty()) return true
        try {
            process.get().onExit().get(seconds, TimeUnit.SECONDS)
            return true
        } catch (TimeoutException ignored) {
            return false
        }
    }

    private Map search(String... arguments) { search([:], arguments) }

    private Map search(Map<String, String> extraEnvironment, String... arguments) {
        finished(startSearch(extraEnvironment, arguments))
    }

    /** Starts `lamp search` against the stand-in SearXNG, with stdout and stderr kept apart. */
    private Map startSearch(Map<String, String> extraEnvironment, String... arguments) {
        var home = Files.createDirectories(temporary.resolve('home'))
        var errors = Files.createTempFile(temporary, 'stderr', '.txt')
        var process = new ProcessBuilder(['bash', lamp.toString(), 'search'] + (arguments as List<String>))
        process.redirectError(errors.toFile())
        process.environment().putAll(
                HOME: home.toString(),
                LAMP_SEARXNG_HOME: searxng.toString(),
                LAMP_SEARCH_DIR: searxng.resolve('run').toString())
        process.environment().putAll(extraEnvironment)
        [process: process.start(), errors: errors]
    }

    private static Map finished(Map started) {
        Process process = started.process
        var output = process.inputStream.text
        process.waitFor()
        [status: process.exitValue(), output: output, errors: Files.readString(started.errors as Path)]
    }
}
