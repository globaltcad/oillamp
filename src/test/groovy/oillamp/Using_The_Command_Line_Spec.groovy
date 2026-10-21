package oillamp

import dev.lamp.LampExitStatus
import dev.lamp.LampProblem
import dev.oillamp.OilLamp
import spock.lang.Narrative
import dev.lamp.LampProblem
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

@Title('Using the command line')
@Narrative('''
The first thing anyone does with a new tool is type it wrong. These scenarios are about what
`oillamp` does then: it refuses a command line it does not fully understand before it changes
anything, says what does not fit, and shows how the command is written.

They are also about oillamp's exit statuses, which scripts that run oillamp depend on. The ones
these scenarios meet are:

- `0`, success;
- `1`, an error not covered by a more specific status;
- `2`, usage: the command line or the configuration was wrong;
- `3`, the machine lacks what the sandbox needs, and oillamp was not allowed to install it.

Most scenarios run a line on a lamp path where no lamp exists yet, since oillamp refuses these
lines before it looks for a lamp.
''')
@Subject(OilLamp)
class Using_The_Command_Line_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    def setup() { host = new ScenarioHost(tmp) }

    def '`oillamp` without arguments lists its commands, and exits with the usage status'() {
        reportInfo """
            Let's consider someone who types `oillamp` and nothing else, to see what it can do.
            oillamp answers with the list of its commands rather than an error about a missing
            command. It still exits with the usage status, 2, because the line asked for nothing,
            and a script that ran it by mistake should notice.
        """
        when: '`oillamp` runs without arguments'
            var outcome = host.oillamp.run()

        then: 'it lists the commands, such as `at <dir>`, `doctor [<dir>]` and `config <dir>`'
            outcome.console().contains('at <dir>')
            outcome.console().contains('doctor [<dir>]')
            outcome.console().contains('config <dir>')

        and: 'it exits with the usage status, 2'
            outcome.status() == LampExitStatus.USAGE
            outcome.status().code() == 2
    }

    def 'oillamp refuses a mistyped command or an unknown option before it does anything'() {
        reportInfo """
            oillamp may install packages and write into the user's home. Acting on a command line
            it only half understood would be the worst moment to guess what was meant. So an
            unknown command or option stops oillamp before it does anything, and the message says
            that nothing on the machine changed.
        """
        when: 'the command is mistyped, as `oillamp doctro`'
            var command = host.oillamp.run('doctro')

        then: 'it exits with the usage status, quotes `doctro`, and says that nothing on the machine changed'
            command.status() == LampExitStatus.USAGE
            command.errors().first().whatHappened().contains("'doctro'")
            command.errors().first().whyItMatters().contains('nothing on your machine changed')

        when: '`oillamp doctor` is given an option that does not exist, `--fix-everything`'
            var option = host.oillamp.run('doctor', '--fix-everything')

        then: 'it exits with the usage status, and names the option'
            option.status() == LampExitStatus.USAGE
            option.errors().first().whatHappened().contains('--fix-everything')
    }

    def 'oillamp refuses an option or argument that the command does not take, rather than ignoring it: oillamp #line'() {
        reportInfo """
            Each option means something to only some of oillamp's commands. A command that
            ignored an option it does not take would be most dangerous where the option is a
            safety: `oillamp stop <dir> --dry-run` would stop the session, because only `at`,
            `remove` and `recordings` know what a dry run is. Ignoring an option also hides a
            misunderstanding: `oillamp at <dir> --view-only` would open an ordinary viewer, not a
            view-only one, because only `oillamp view` takes `--view-only`.

            So an option that the command does not take, or one argument too many, is a usage
            error. The message names what does not fit, and shows how the command is written.
        """
        given: 'a lamp path, written `LAMP` in the table'
            var lamp = host.lampPath().toString()

        when: 'oillamp runs the line'
            var outcome = host.oillamp.run(*line.replace('LAMP', lamp).split(' '))

        then: 'it exits with the usage status, names what the command does not take, and shows how the command is written'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(complaint)
            outcome.console().contains(usage)

        where:
            line                               | complaint                        | usage
            'stop LAMP --dry-run'              | "`oillamp stop` does not take --dry-run" | 'oillamp stop <dir>'
            'status LAMP --yes'                | "`oillamp status` does not take --yes"   | 'oillamp status <dir>'
            'at LAMP --view-only'              | "`oillamp at` does not take --view-only" | 'oillamp at <dir> [--init]'
            'view LAMP --prune'                | "`oillamp view` does not take --prune"   | 'oillamp view <dir> [--view-only]'
            'list --open 20260101-120000'      | "`oillamp list` does not take --open"    | 'oillamp list'
            'list LAMP'                        | "`oillamp list` takes no arguments"      | 'oillamp list'
            'version extra'                    | "`oillamp version` takes no arguments"   | 'oillamp version'
            'help extra'                       | "`oillamp help` takes no arguments"      | 'oillamp help'
            'about extra'                      | "`oillamp about` takes no arguments"     | 'oillamp about'
            'guide extra'                      | "`oillamp guide` takes no arguments"     | 'oillamp guide'
            'genies extra'                     | "`oillamp genies` takes no arguments"    | 'oillamp genies'
            'genies --yes'                     | "`oillamp genies` does not take --yes"   | 'oillamp genies'
            'config LAMP path extra'           | "'extra'"                                | 'oillamp config <dir> (check | show-effective | path)'
            'completion bash zsh'              | "'zsh'"                                  | 'oillamp completion bash'
            'history LAMP restore a1b2c3d4 extra' | "'extra'"                             | 'oillamp history <dir> restore <snapshot>'
            'history LAMP save extra'          | "'extra'"                                | 'oillamp history <dir> save [--message <text>]'
            'ask LAMP hello extra'             | "'extra'"                                | 'oillamp ask <dir> [--in'
            'conversations LAMP c1 extra'      | "'extra'"                                | 'oillamp conversations <dir>'
            'cancel LAMP run-1 extra'          | "'extra'"                                | 'oillamp cancel <dir>'
            'schedule LAMP remove job-1 extra' | "'extra'"                                | 'oillamp schedule <dir>'
            'stop LAMP -y'                     | "`oillamp stop` does not take --yes"     | 'oillamp stop <dir>'
            'history LAMP -m note'             | '--message only goes with `oillamp history <dir> save`' | 'oillamp history <dir> save'
            'history LAMP restore a1 -m note'  | '--message only goes with `oillamp history <dir> save`' | 'oillamp history <dir> save'
            'stop LAMP --message=note'         | "`oillamp stop` does not take --message" | 'oillamp stop <dir>'
            'shell LAMP --embedded'            | "`oillamp shell` does not take --embedded" | 'oillamp shell <dir>'
            'list --yes'                       | "`oillamp list` does not take --yes"       | 'oillamp list'
            'stop LAMP --init'                 | "`oillamp stop` does not take --init"    | 'oillamp stop <dir>'
            'status LAMP --no-viewer'          | "`oillamp status` does not take --no-viewer" | 'oillamp status <dir>'
            'status LAMP --no-windows'         | "`oillamp status` does not take --no-windows" | 'oillamp status <dir>'
            'view LAMP --model-service https://api.eu.edenai.run' | "`oillamp view` does not take --model-service" | 'oillamp view <dir>'
            'view LAMP --model-key-env EDENAI_API_KEY' | "`oillamp view` does not take --model-key-env" | 'oillamp view <dir>'
            'ask LAMP hello --model azure/gpt-6.1-sol' | "`oillamp ask` does not take --model" | 'oillamp ask <dir>'
            'model LAMP azure/gpt-6.1-sol --model x' | "`oillamp model` does not take --model" | 'oillamp model <dir> <id>'
            'model LAMP azure/gpt-6.1-sol extra' | "'extra'"                                  | 'oillamp model <dir> <id>'
            'model LAMP'                       | 'needs the model, as the service names it' | 'oillamp model <dir> <id>'
            'model LAMP azure/gpt‑6.1'         | 'expected a model as the service names it'  | 'oillamp model <dir> <id>'
            'stop LAMP --enable-scheduling'    | "`oillamp stop` does not take --enable-scheduling" | 'oillamp stop <dir>'
            'history LAMP --in c1'             | "`oillamp history` does not take --in"   | 'oillamp history <dir>'
            'history LAMP --after e1'          | "`oillamp history` does not take --after" | 'oillamp history <dir>'
            'history LAMP --instead-of e1'     | "`oillamp history` does not take --instead-of" | 'oillamp history <dir>'
            'stop LAMP --no-wait'              | "`oillamp stop` does not take --no-wait" | 'oillamp stop <dir>'
            'history LAMP save --cron @daily'  | "`oillamp history` does not take --cron" | 'oillamp history <dir> save'
            'history LAMP save --at in-2h'     | "`oillamp history` does not take --at"   | 'oillamp history <dir> save'
            'history LAMP save --expires in-3d' | "`oillamp history` does not take --expires" | 'oillamp history <dir> save'
            'status LAMP --prune'              | "`oillamp status` does not take --prune" | 'oillamp status <dir>'
            'history LAMP save --all'          | '--all only goes with `oillamp history <dir> list`' | 'oillamp history <dir> [list] [--all]'
            'history LAMP switch rust --delete' | '--delete only goes with `oillamp history <dir> branch`' | 'oillamp history <dir> branch --delete <name>'
            'history LAMP restore a1b2c3d4 --yes' | '--yes only goes with `oillamp history <dir> reset`' | 'oillamp history <dir> reset <snapshot> [--yes]'
            'history LAMP switch rust extra'   | "'extra'"                                | 'oillamp history <dir> switch <name>'
            'history LAMP reset a1b2c3d4 extra' | "'extra'"                               | 'oillamp history <dir> reset <snapshot> [--yes]'
            'history LAMP branches extra'      | "'extra'"                                | 'oillamp history <dir> branches'
            'history LAMP branch rust a1b2c3d4 extra' | "'extra'"                         | 'oillamp history <dir> branch <name> [<snapshot>]'
            'history LAMP branch --delete rust extra' | "'extra'"                         | 'oillamp history <dir> branch --delete <name> [--yes]'
            'history LAMP list --cron @daily'  | "`oillamp history` does not take --cron" | 'oillamp history <dir>'
    }

    def 'oillamp accepts the options that a command does take: oillamp #line'() {
        reportInfo """
            oillamp refuses an option that a command does not take. The lines here are the
            opposite case: each combines a command with options it does take. Whatever else they
            report, for example because the lamp does not exist, none is refused for an option
            that the command "does not take".
        """
        when: 'oillamp runs the line'
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then: 'it is not refused for an option the command does not take'
            outcome.status() != LampExitStatus.USAGE || !outcome.errors().any { it.whatHappened().contains('does not take') }

        where:
            line << ['--verbose doctor', 'doctor --dry-run', 'at LAMP --dry-run --init --no-viewer --no-install',
                     'at LAMP --dry-run --no-windows',
                     'remove LAMP --dry-run', 'remove LAMP -y', 'recordings LAMP --prune --dry-run', '--no-color list',
                     'config LAMP check', 'completion bash',
                     'at LAMP --dry-run --model-service https://api.eu.edenai.run --model-key-env EDENAI_API_KEY',
                     'at LAMP --dry-run --model azure/gpt-6.1-sol', 'at LAMP --dry-run --model=llama3.2:3b',
                     'at LAMP --dry-run --enable-scheduling', 'doctor --no-install',
                     'config LAMP check --dry-run --no-install', 'view LAMP --view-only',
                     'recordings LAMP --open 20260101-120000', 'remove LAMP --yes --embedded',
                     'history LAMP save --message note --embedded', 'history LAMP --embedded',
                     'history LAMP restore a1b2c3d4 --embedded', 'history LAMP autosave --embedded', 'stop LAMP --embedded', 'status LAMP --embedded',
                     'history LAMP list --all --embedded', 'history LAMP --all', 'history LAMP branches --embedded',
                     'history LAMP branch rust a1b2c3d4 --embedded', 'history LAMP branch --delete rust --yes --embedded',
                     'history LAMP branch -y --delete rust', 'history LAMP switch rust --embedded', 'history LAMP reset a1b2c3d4 --yes --embedded',
                     'follow LAMP --embedded', 'cancel LAMP --embedded', 'model LAMP azure/gpt-6.1-sol --embedded', 'conversations LAMP --embedded',
                     'schedule LAMP --embedded', 'schedule LAMP add --at in-2h --expires in-3d hello',
                     'ask LAMP --no-wait --embedded hello']
    }

    def '`oillamp at` without a directory is a usage error, not a crash'() {
        reportInfo """
            `oillamp at` needs the directory of the lamp to set up. Without one, oillamp exits with
            the usage status, and shows how the command is written, `oillamp at <dir>`.
        """
        when: '`oillamp at` runs without a directory'
            var outcome = host.oillamp.run('at')

        then: 'it exits with the usage status, and shows `oillamp at <dir>`'
            outcome.status() == LampExitStatus.USAGE
            outcome.console().contains('oillamp at <dir>')
    }

    def '`oillamp version` prints oillamp\'s version and the version of Java it runs on'() {
        reportInfo """
            `oillamp version` needs no lamp and checks nothing on the machine. Besides oillamp's own
            version, it prints the version of Java that runs it, because a bug report needs both.
        """
        when: '`oillamp version` runs'
            var outcome = host.oillamp.run('version')

        then: 'it prints oillamp\'s version'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.console().contains('oillamp 0.3.0')

        and: 'it also prints the version of Java it runs on'
            outcome.console().contains('java ' + Runtime.version())
    }

    def '`oillamp about` says what oillamp is for, and `oillamp guide` how to use it'() {
        reportInfo """
            `oillamp help` lists the commands, which is enough once someone knows what they are
            for. `oillamp about` and `oillamp guide` are for the moment before that: `about`
            explains why oillamp exists and what it is built from, and `guide` walks through the
            commands in the order a newcomer needs them. Like `help`, they need no lamp, and
            change nothing.
        """
        when: '`oillamp about` runs'
            var about = host.oillamp.run('about')

        then: 'it explains why oillamp exists, names podman, and gives oillamp\'s version'
            about.status() == LampExitStatus.SUCCESS
            about.console().contains('WHY IT EXISTS')
            about.console().contains('podman')
            about.console().contains('oillamp 0.3.0')

        when: '`oillamp guide` runs'
            var guide = host.oillamp.run('guide')

        then: 'it lists the steps in the order they are needed: checking the machine, creating a lamp, stopping it, and removing it'
            guide.status() == LampExitStatus.SUCCESS
            var steps = ['oillamp doctor', 'oillamp at ~/lamps/first', 'oillamp stop ~/lamps/first',
                         'oillamp remove ~/lamps/first --yes'].collect { guide.console().indexOf(it) }
            steps.every { it >= 0 }
            steps == steps.sort(false)

        and: 'it says how to run a session where no window can open, `--no-windows`, and that `--no-viewer` is something else'
            guide.console().contains('oillamp at ~/lamps/first --no-windows')
            guide.console().contains('--no-viewer is not the same')

        and: 'it names the keys for the windows of the sandbox\'s desktop, since its compositor, sway, draws no buttons on them'
            ['Ctrl+Super+F', 'Ctrl+Super+Q', 'Ctrl+Super+M', 'Ctrl+Super+N'].every { guide.console().contains(it) }

        and: '`oillamp help` points newcomers to both'
            var help = host.oillamp.run('help')
            help.console().contains('oillamp guide')
            help.console().contains('oillamp about')
    }

    def 'A bug in oillamp reaches the user as a problem they can report, not as a stack trace'() {
        reportInfo """
            oillamp never prints a bare stack trace, not even for a bug of its own. A user who
            sees a stack trace can no longer tell "I did something wrong" from "this tool is
            broken".

            So an unexpected failure becomes a problem like any other: with the code
            `OIL-INTERNAL-001`, the advice to run the command again with `--verbose`, and the start
            of the stack trace kept as evidence instead of printed.

            To cause such a bug, the scenario gives oillamp a machine that generates an agent id
            no lamp may have.
        """
        given: 'a machine that generates an impossible agent id, `NOT A VALID ID`'
            host.machine { it.generatedAgentId('NOT A VALID ID') }

        when: '`oillamp at <dir>` sets up a lamp'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'it reports `OIL-INTERNAL-001` with the error status, and prints no stack trace'
            outcome.reported('OIL-INTERNAL-001')
            outcome.status() == LampExitStatus.ERROR
            !outcome.console().contains('\tat dev.oillamp')

        and: 'the problem advises running again with `--verbose`'
            outcome.errors().first().fixes().any { it.description().contains('--verbose') }

        and: 'it keeps the start of the stack trace as evidence, an excerpt titled "stack trace" with oillamp\'s frames in it'
            var trace = outcome.errors().first().evidence()
                    .find { it instanceof LampProblem.Evidence.Excerpt && it.title() == 'stack trace' }
            trace.text().contains('at dev.oillamp.')
    }

    def '`oillamp view` on a directory that is not a lamp says so, and creates nothing'() {
        reportInfo """
            `oillamp view`, `shell`, `stop` and `status` all ask something of a session that is
            already running. Let's consider `oillamp view` given a directory that was never made
            into a lamp. The honest answer is that the directory is not an oillamp lamp. "No
            session is running" would send the user looking for a session they never started.

            These commands also change nothing on the way to their answer. A command that only
            asks a session must not create or repair a lamp.
        """
        given: 'a directory that was never made into a lamp'
            var lamp = host.lampPath()

        when: '`oillamp view` is given that directory'
            var outcome = host.oillamp.run('view', lamp.toString())

        then: 'it fails, and says that the directory is not an oillamp lamp'
            !outcome.succeeded()
            outcome.errors().first().whatHappened().contains('not an oillamp lamp')

        and: 'the directory still does not exist'
            !java.nio.file.Files.exists(lamp)
    }

    def '`oillamp recordings --open` without a session is a usage error, not a listing'() {
        reportInfo """
            `oillamp recordings <dir>` lists a lamp's screen recordings, and
            `oillamp recordings <dir> --open <session>` plays the recording of one session.
            Given `--open` without a session, oillamp does not fall back to the listing, which
            would hide that the session is missing. It exits with the usage status, and says that
            `--open` needs the session to play.
        """
        when: '`oillamp recordings <dir> --open` runs without a session'
            var outcome = host.oillamp.run('recordings', host.lampPath().toString(), '--open')

        then: 'it exits with the usage status, and says that `--open` needs the session to play'
            outcome.status() == LampExitStatus.USAGE
            outcome.console().contains('--open needs the session to play')
    }

    def '`--no-color` leaves no colour codes in oillamp\'s output'() {
        reportInfo """
            On a terminal, oillamp colours its output with escape codes. The codes are invisible
            there, but they end up as junk in a log file or a pasted bug report. With
            `--no-color`, oillamp writes none, as it already did when `NO_COLOR=1` is set.
        """
        when: '`oillamp doctor` runs on a terminal, once without and once with `--no-color`'
            var coloured = host.oillamp.run('doctor')
            var plain = host.oillamp.run('doctor', '--no-color')

        then: 'the first output has colour codes, the second none'
            coloured.console().contains('\u001B[')
            !plain.console().contains('\u001B[')
    }

    def '`oillamp doctor <dir>` reports a mistake in the lamp\'s `oillamp.toml`, and exits with the usage status'() {
        reportInfo """
            `oillamp doctor <dir>` checks the machine and then the lamp's configuration. A mistake
            in `oillamp.toml` is the user's to fix, so oillamp reports it with an `OIL-CONFIG-`
            code and the key, and exits with 2, the usage status, as for any other mistake in what
            the user wrote. A script can tell that apart from 3, the status for a machine that
            lacks what the sandbox needs.
        """
        given: 'a lamp whose `oillamp.toml` has a key `display.size` that does not exist'
            host.givenConfig(host.lampPath(), """
                schema_version = 1

                [display]
                size = "huge"
                """.stripIndent())

        when: '`oillamp doctor <dir>` runs'
            var outcome = host.oillamp.run('doctor', host.lampPath().toString())

        then: 'it exits with the usage status, reports an `OIL-CONFIG-` problem, and names `size`'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().any { it.code().value().startsWith('OIL-CONFIG-') }
            outcome.console().contains('size')
    }

    def '`oillamp config <dir> path` prints the path of the lamp\'s `oillamp.toml`'() {
        reportInfo """
            A script or an editor that wants to open a lamp's settings needs the path of its
            `oillamp.toml`. `oillamp config <dir> path` prints it, as the last thing on its
            output.
        """
        when: '`oillamp config <dir> path` runs'
            var outcome = host.oillamp.run('config', host.lampPath().toString(), 'path')

        then: 'it succeeds, and its output ends with the path of `oillamp.toml` in the lamp'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.console().trim().endsWith(host.lampPath().resolve('oillamp.toml').toString())
    }

    def '`oillamp completion bash` prints only the script, and the script completes what oillamp accepts'() {
        reportInfo """
            Bash completes oillamp's command lines with a script that oillamp prints, usually
            loaded with `eval "\$(oillamp completion bash)"`. That runs whatever oillamp prints,
            so the output must be the script and nothing else: no banner, no colour codes.

            And the completions must match the command line oillamp parses. In
            `oillamp config <dir> check` and `oillamp history <dir> save`, the lamp directory comes
            first, so the word after `config` or `history` is a path, and the word after that is
            the action. After `oillamp history <dir> switch` comes a branch, one of the lines of
            snapshots in the lamp's history, and the script offers the names of the branches that
            the history has.

            The scenario loads the script into bash, and asks bash what it would offer for the
            last word of several command lines.
        """
        when: '`oillamp completion bash` runs'
            var outcome = host.oillamp.run('completion', 'bash')

        then: 'it prints the script and nothing else: no banner, no colour codes'
            outcome.status() == LampExitStatus.SUCCESS
            !outcome.console().contains('🪔')
            !outcome.console().contains('\u001B[')

        and: 'bash completes commands, the options of `oillamp at`, and the actions of `oillamp config` after the directory'
            var script = tmp.resolve('completion.bash')
            Files.writeString(script, outcome.console())
            complete(script, 'oillamp', 'sta').containsAll(['status'])
            complete(script, 'oillamp', 'at', '/x', '--dry').containsAll(['--dry-run'])
            complete(script, 'oillamp', 'at', '/x', '--model').containsAll(['--model', '--model-service', '--model-key-env'])
            !complete(script, 'oillamp', 'config', '').contains('check')
            complete(script, 'oillamp', 'config', '/x', '').containsAll(['check', 'show-effective', 'path'])

        and: 'after `oillamp history <dir>` it completes the actions, after `autosave` the moments of autosave and then on or off, and the options of each action'
            !complete(script, 'oillamp', 'history', '').contains('save')
            complete(script, 'oillamp', 'history', '/x', '') == ['list', 'save', 'restore', 'autosave', 'branches', 'branch', 'switch', 'reset', 'prune', 'max-growth']
            complete(script, 'oillamp', 'history', '/x', 'autosave', '') == ['startup', 'shutdown', 'before-run', 'after-run', 'chat-quiet-minutes']
            complete(script, 'oillamp', 'history', '/x', 'autosave', 'startup', '') == ['on', 'off']
            complete(script, 'oillamp', 'history', '/x', 'autosave', 'chat-quiet-minutes', '') == []
            complete(script, 'oillamp', 'history', '/x', 'autosave', 'startup', 'on', 'a') == ['after-run']
            complete(script, 'oillamp', 'history', '/x', 'save', '--m') == ['--message']
            complete(script, 'oillamp', 'history', '/x', 'list', '--a') == ['--all']
            complete(script, 'oillamp', 'history', '/x', 'branch', '--d') == ['--debug', '--delete']
            complete(script, 'oillamp', 'history', '/x', 'reset', 'a1b2', '--y') == ['--yes']

        and: 'after `oillamp history <dir> switch` it completes the branches in the lamp\'s history'
            var heads = Files.createDirectories(tmp.resolve('lamp/.oillamp/history/refs/heads'))
            Files.writeString(heads.resolve('main'), '0' * 40 + '\n')
            Files.writeString(heads.resolve('rust-rewrite'), '0' * 40 + '\n')
            complete(script, 'oillamp', 'history', tmp.resolve('lamp').toString(), 'switch', '') == ['main', 'rust-rewrite']
            complete(script, 'oillamp', 'history', tmp.resolve('lamp').toString(), 'switch', 'r') == ['rust-rewrite']
            !complete(script, 'oillamp', 'sa').contains('save')

        and: 'it offers each command only the options that the command takes'
            complete(script, 'oillamp', 'stop', '/x', '--d') == ['--debug']
            complete(script, 'oillamp', 'view', '/x', '--v').containsAll(['--view-only', '--verbose'])
            complete(script, 'oillamp', 'update', '/x', '--s') == ['--system']
            !complete(script, 'oillamp', 'at', '/x', '--').contains('--yes')
            complete(script, 'oillamp', 'completion', '') == ['bash']
            complete(script, 'oillamp', 'list', '').isEmpty()
            complete(script, 'oillamp', 'gen') == ['genies']
            complete(script, 'oillamp', 'genies', '').isEmpty()
    }

    def 'An option\'s value can follow it, or be joined to it with `=`: oillamp #line'() {
        reportInfo """
            `--model-service https://x` and `--model-service=https://x` mean the same, as they do
            for most command-line tools, and that holds for every option that takes a value. Each
            pair of lines here is refused for the same reason and quotes the same value, which
            shows that both spellings gave oillamp the same value. A value joined with `=` is
            quoted without the `=`.
        """
        when: 'oillamp runs the line'
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then: 'it exits with the usage status, and quotes the value in its reason'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(complaint)

        where:
            line                                                   | complaint
            'at LAMP --model-service ftp://models.example'         | '--model-service "ftp://models.example": expected a scheme'
            'at LAMP --model-service=ftp://models.example'         | '--model-service "ftp://models.example": expected a scheme'
            'at LAMP --model-key-env not-a-name'                   | '--model-key-env "not-a-name": expected the name of an environment variable'
            'at LAMP --model-key-env=not-a-name'                   | '--model-key-env "not-a-name": expected the name of an environment variable'
            'at LAMP --model azure/gpt‑6.1'                        | '--model "azure/gpt‑6.1": expected a model as the service names it'
            'at LAMP --model=azure/gpt‑6.1'                        | '--model "azure/gpt‑6.1": expected a model as the service names it'
            'ask LAMP --after e1 hello'                            | '--after and --instead-of name an entry of the conversation that --in names'
            'ask LAMP --after=e1 hello'                            | '--after and --instead-of name an entry of the conversation that --in names'
            'ask LAMP --instead-of e1 hello'                       | '--after and --instead-of name an entry of the conversation that --in names'
            'ask LAMP --instead-of=e1 hello'                       | '--after and --instead-of name an entry of the conversation that --in names'
            'ask LAMP --in c1 --after e1 --instead-of e2 hello'    | 'either after an entry or instead of a question, not both'
            'ask LAMP --in=c1 --after=e1 --instead-of=e2 hello'    | 'either after an entry or instead of a question, not both'
            'schedule LAMP list --cron @daily'                     | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
            'schedule LAMP list --cron=@daily'                     | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
            'schedule LAMP pause --at in-2h'                       | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
            'schedule LAMP pause --at=in-2h'                       | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
            'schedule LAMP resume --expires in-3d'                 | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
            'schedule LAMP resume --expires=in-3d'                 | '--cron, --at and --expires only go with `oillamp schedule <dir> add`'
    }

    def 'An option that comes last, without its value, is refused with an example of the value: oillamp #line'() {
        reportInfo """
            An option that takes a value but comes last on the line was usually cut short, or its
            value was lost to the shell's quoting. Running without the value could do something
            other than what was meant, so oillamp stops, says what value the option needs and
            gives an example, and shows how the command is written.
        """
        when: 'oillamp runs the line'
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then: 'it exits with the usage status, says what value the option needs, and shows how the command is written'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(complaint)
            outcome.console().contains(usage)

        where:
            line                           | complaint                                                         | usage
            'history LAMP save --message'  | '--message needs the text to save with, for example --message "before the upgrade"' | 'oillamp history <dir> save'
            'history LAMP save -m'         | '--message needs the text to save with'                           | 'oillamp history <dir> save'
            'ask LAMP hello --in'          | '--in needs a conversation, as `oillamp conversations` lists them' | 'oillamp ask <dir>'
            'ask LAMP hello --after'       | '--after needs an entry, as `oillamp conversations <dir> <conversation>` shows them' | 'oillamp ask <dir>'
            'ask LAMP hello --instead-of'  | '--instead-of needs an entry'                                     | 'oillamp ask <dir>'
            'schedule LAMP add hi --cron'  | '--cron needs a value, for example --cron "0 9 * * 1-5"'          | 'oillamp schedule <dir>'
            'schedule LAMP add hi --at'    | '--at needs a value, for example --at "2026-10-01 09:00"'         | 'oillamp schedule <dir>'
            'schedule LAMP add hi --expires' | '--expires needs a value, for example --expires "in 14d"'       | 'oillamp schedule <dir>'
            'at LAMP --model-service'      | '--model-service needs a value, for example --model-service https://api.eu.edenai.run' | 'oillamp at <dir>'
            'at LAMP --model-key-env'      | '--model-key-env needs a value, for example --model-key-env MY_MODEL_KEY' | 'oillamp at <dir>'
            'at LAMP --model'              | '--model needs a value, for example --model azure/gpt-6.1-sol' | 'oillamp at <dir>'
    }

    def '`oillamp at` refuses a model service, key variable or model that cannot be right, before it sets anything up: oillamp #line'() {
        reportInfo """
            `--model-service` and `--model-key-env` override the lamp's `[model]` settings for one
            session: the address of the model service, and the name of the environment variable
            that holds the key for it. oillamp sends the key to that service, so it refuses an
            address that is not https, or a variable name that the shell could never have set,
            at once, in the same words as for the same mistake in `oillamp.toml`.

            `--model` names the model that answers every run of the session. No service names a
            model with a space or a character outside ASCII, such as the non-breaking hyphen that
            a copy from a web page can bring along. So oillamp refuses such a name too, rather
            than let every run of the session fail.
        """
        when: '`oillamp at` runs the line'
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then: 'it exits with the usage status, and says what is wrong with the value'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(complaint)

        and: 'the lamp directory has not been created'
            !Files.exists(host.lampPath())

        where:
            line                                                | complaint
            'at LAMP --model-service http://models.example'     | 'it must be https://'
            'at LAMP --model-service https://models.example?x'  | 'expected a scheme, a host'
            'at LAMP --model-key-env 1PASSWORD'                 | 'expected the name of an environment variable, such as EDENAI_API_KEY'
            'at LAMP --model-key-env $EDENAI_API_KEY'           | 'expected the name of an environment variable'
            'at LAMP --model azure/gpt‑6.1-sol'                 | 'expected a model as the service names it, such as azure/gpt-6.1-sol, without spaces'
            'at LAMP --model=gpt-6.1-sol\u00a0'                 | 'expected a model as the service names it'
    }

    def 'After `--`, oillamp takes every argument as written, even one that looks like an option'() {
        reportInfo """
            A question for the agent may start with a dash, such as a list of things to do.
            oillamp takes any argument that starts with a dash for an option, and refuses an
            option it does not know. After `--`, it takes every argument as it is written.

            The lamp here has no session, so the question is never asked. What the scenario
            checks is that `oillamp ask` got as far as looking for a session, instead of refusing
            the line.
        """
        when: '`oillamp ask <dir>` is given `--`, and then a question that starts with a dash'
            var literal = host.oillamp.run('ask', host.lampPath().toString(), '--', '--list the open tickets')

        then: 'the question is not taken for an option'
            literal.status() != LampExitStatus.USAGE
            !literal.errors().any { it.whatHappened().contains('is not an option oillamp knows') }

        when: '`oillamp ask <dir>` is given the same question without `--`'
            var option = host.oillamp.run('ask', host.lampPath().toString(), '--list the open tickets')

        then: 'it is taken for an option that oillamp does not know, and refused'
            option.status() == LampExitStatus.USAGE
            option.errors().first().whatHappened().contains("'--list the open tickets' is not an option oillamp knows")
    }

    def '`-v` is short for `--verbose`, and `--debug` turns verbose output on too'() {
        reportInfo """
            With `--verbose`, oillamp shows each step it takes as it goes, not only the outcome.
            `-v` is the short form. `--debug` does not show more than `--verbose` yet, but it
            promises at least as much, so it turns verbose output on as well.
        """
        given: 'a lamp path'
            var lamp = host.lampPath().toString()

        when: '`oillamp at <dir> --dry-run` runs plain, with `--verbose`, with `-v` and with `--debug`'
            var quiet = host.oillamp.run('at', lamp, '--dry-run')
            var verbose = host.oillamp.run('--verbose', 'at', lamp, '--dry-run')
            var shortForm = host.oillamp.run('-v', 'at', lamp, '--dry-run')
            var debug = host.oillamp.run('--debug', 'at', lamp, '--dry-run')

        then: 'the output with `--verbose` is longer than the plain one'
            verbose.console().length() > quiet.console().length()

        and: 'the output with `-v` and with `--debug` is the same as with `--verbose`'
            shortForm.console() == verbose.console()
            debug.console() == verbose.console()
    }

    def 'A command that works on a lamp says so when it is given none: oillamp #command'() {
        reportInfo """
            Each of these commands works on one lamp directory, and none can guess which. Given
            none, the command is a usage error that says it needs the path of a lamp directory,
            rather than a crash, or a guess that the current directory is meant.
        """
        when: '`oillamp #command` runs without a directory'
            var outcome = host.oillamp.run(command)

        then: 'it exits with the usage status, says that it needs the path of a lamp directory, and shows how it is written'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains("`oillamp ${command}` needs the path of a lamp directory")
            outcome.console().contains("oillamp ${command} <dir>")

        where:
            command << ['at', 'view', 'shell', 'stop', 'status', 'follow', 'recordings', 'config', 'remove',
                        'history', 'schedule', 'ask', 'conversations', 'cancel']
    }

    def 'A command that works on one lamp refuses several, rather than acting on the first: oillamp #command'() {
        reportInfo """
            A shell pattern such as `test*` easily expands to more than one directory. A command
            that quietly acted on the first and ignored the others would look as if it had done
            them all. So it refuses, names the directories it was given, and says to run it once
            for each. `oillamp remove` is the one command that takes several lamps on purpose.
        """
        given: 'two lamp paths'
            var first = host.lampPath('one').toString()
            var second = host.lampPath('two').toString()

        when: '`oillamp #command` is given both'
            var outcome = host.oillamp.run(command, first, second)

        then: 'it exits with the usage status, names both directories, and says to run it once for each'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened()
                    .contains("`oillamp ${command}` works on one lamp, but was given 2 directories (${first}, ${second}); run it once for each")

        where:
            command << ['at', 'view', 'shell', 'stop', 'status', 'follow', 'recordings', 'doctor']
    }

    def 'The first line of a command that sets up or changes a lamp names oillamp\'s version and the lamp'() {
        reportInfo """
            Commands that set up or change a lamp begin their output with a line that names
            oillamp, its version and the lamp, such as `oillamp 0.2.0 — ~/lamps/first`. A terminal
            with several sessions in it, or a pasted log, then shows which lamp each part is
            about. `oillamp remove` names how many lamps it was given instead.
        """
        given: 'a lamp path'
            var lamp = host.lampPath().toString()

        expect: 'the first line of `doctor`, `at --dry-run`, `history save` and `history restore` names the lamp, and that of `remove` the number of lamps'
            firstLine(host.oillamp.run('doctor', lamp)).endsWith("oillamp 0.3.0 — ${lamp}")
            firstLine(host.oillamp.run('at', lamp, '--dry-run')).endsWith("oillamp 0.3.0 — ${lamp}")
            firstLine(host.oillamp.run('history', lamp, 'save')).endsWith("oillamp 0.3.0 — ${lamp}")
            firstLine(host.oillamp.run('history', lamp, 'restore', 'a1b2c3d4')).endsWith("oillamp 0.3.0 — ${lamp}")
            firstLine(host.oillamp.run('remove', lamp, host.lampPath('other').toString())).endsWith('oillamp 0.3.0 — 2 lamps')
    }

    /** The first line of a command's output that is not blank. */
    private static String firstLine(outcome) {
        outcome.console().readLines().find { !it.isBlank() }.strip()
    }

    def '`oillamp config <dir>` checks the configuration when given no action, and refuses an action it does not know'() {
        reportInfo """
            `oillamp config <dir>` on its own checks the lamp's configuration, which is what
            someone who has just edited `oillamp.toml` wants. `oillamp config <dir> show-effective`
            goes further: it prints every setting as a session would use it, which a check does
            not. A word that is not one of the actions is a usage error, not a quiet check.
        """
        given: 'a lamp with a valid `oillamp.toml`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')

        when: '`oillamp config <dir>` runs with no action, with `check`, with `show-effective`, and with `fix`'
            var bare = host.oillamp.run('config', lamp.toString())
            var check = host.oillamp.run('config', lamp.toString(), 'check')
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')
            var unknown = host.oillamp.run('config', lamp.toString(), 'fix')

        then: 'with no action, it does exactly what `check` does'
            bare.status() == LampExitStatus.SUCCESS
            bare.console() == check.console()

        and: '`show-effective` prints the settings as an answer, which `check` does not'
            shown.status() == LampExitStatus.SUCCESS
            shown.console() != check.console()
            shown.events().any { it instanceof dev.lamp.LampEvent.Answer }
            !check.events().any { it instanceof dev.lamp.LampEvent.Answer }

        and: '`fix` is refused, with the actions there are'
            unknown.status() == LampExitStatus.USAGE
            unknown.errors().first().whatHappened().contains("'fix' is not a config action")
            unknown.console().contains('oillamp config <dir> (check | show-effective | path)')
    }

    def '`oillamp completion` without a shell prints the bash script, and refuses any other shell'() {
        reportInfo """
            oillamp ships a completion script for bash only. So `oillamp completion` without a
            shell prints that script, and naming another shell is refused: a bash script loaded
            into zsh would fail in ways that are hard to trace back to oillamp.
        """
        when: '`oillamp completion` runs without a shell, and with `zsh`'
            var unnamed = host.oillamp.run('completion')
            var zsh = host.oillamp.run('completion', 'zsh')

        then: 'without a shell, it prints the bash script'
            unnamed.status() == LampExitStatus.SUCCESS
            unnamed.console() == host.oillamp.run('completion', 'bash').console()

        and: '`zsh` is refused with the usage status, rather than given a script it cannot run'
            zsh.status() == LampExitStatus.USAGE
            zsh.errors().first().whatHappened().contains("oillamp only ships a completion script for bash, not 'zsh'")
    }

    def '`oillamp help` lists the commands, and succeeds'() {
        reportInfo """
            `oillamp` without arguments lists the commands too, but exits with the usage status,
            because the line asked for nothing. `oillamp help` asks for the list, so it
            succeeds.
        """
        when: '`oillamp help` runs'
            var outcome = host.oillamp.run('help')

        then: 'it succeeds, and lists the commands'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.console().contains('at <dir>')
            outcome.console().contains('doctor [<dir>]')
            outcome.console().contains('genies')
    }

    def '`oillamp genies` opens Genies on the Java oillamp runs on, and says so when it cannot'() {
        reportInfo """
            Genies, the desktop app for chatting with agents, is built on oillamp and comes in
            the same file. `oillamp genies` starts it as a program of its own, on the Java runtime
            and classpath oillamp itself runs on, and waits in the terminal until its window is
            closed. If that program cannot be started, oillamp says what it tried to start. Here
            it cannot: a simulated machine has only the programs it was given.
        """
        when:
            var outcome = host.oillamp.run('genies')

        then:
            outcome.status() == LampExitStatus.ERROR
            var problem = outcome.errors().first()
            problem.code().value() == 'OIL-GENIES-001'
            var tried = (problem.evidence().first() as LampProblem.Evidence.Command).argv()
            tried.toList() == [Path.of(System.getProperty('java.home'), 'bin', 'java').toString(),
                               '-cp', System.getProperty('java.class.path'), 'dev.gui.GeniesBackend']
    }

    def '`oillamp image` says why there is no such command, and what to use instead'() {
        reportInfo """
            An `image` command was planned and then dropped. oillamp builds the sandbox image
            again by itself when its settings change, and `oillamp update <dir> --system` renews
            what is in it. Someone who read about an `image` command, or expects one from other
            container tools, is told that, not just "unknown command".
        """
        when: '`oillamp image` runs'
            var outcome = host.oillamp.run('image')

        then: 'it exits with the usage status, says there is no `image` command, and points to `oillamp update <dir> --system`'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains("there is no 'image' command")
            outcome.errors().first().whatHappened().contains('oillamp update <dir> --system')
    }

    def 'oillamp refuses an `ask`, `history` or `schedule` line that leaves out what the command acts on: oillamp #line'() {
        reportInfo """
            Each of these lines names a lamp, but leaves out the one thing the command needs: the
            question to ask, the snapshot to go back to, whether to switch a moment of autosave on
            or off, the branch, the prompt for a new job, or the job to change. One line gives
            `oillamp schedule <dir> pause` a word that it does not take. oillamp refuses each with
            a usage error that says what is missing or extra, before it looks for the lamp or a
            session.
        """
        when: 'oillamp runs the line'
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then: 'it exits with the usage status, and says what is missing or extra'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(complaint)

        where:
            line                    | complaint
            'ask LAMP'              | '`oillamp ask` needs something to ask the agent, in quotes'
            'history LAMP restore'  | '`oillamp history <dir> restore` needs the snapshot to go back to'
            'history LAMP autosave startup' | '`oillamp history <dir> autosave` startup needs on or off after it'
            'history LAMP switch'   | '`oillamp history <dir> switch` needs the branch to switch to'
            'history LAMP reset'    | '`oillamp history <dir> reset` needs the snapshot to go back to'
            'history LAMP branch'   | '`oillamp history <dir> branch` needs the name of the branch'
            'history LAMP branch --delete' | '`oillamp history <dir> branch --delete` needs the name of the branch to delete'
            'schedule LAMP add'     | '`oillamp schedule <dir> add` needs the prompt the agent is woken with, in quotes'
            'schedule LAMP remove'  | '`oillamp schedule <dir> remove` needs the job, such as job-3'
            'schedule LAMP enable'  | '`oillamp schedule <dir> enable` needs the job, such as job-3'
            'schedule LAMP disable' | '`oillamp schedule <dir> disable` needs the job, such as job-3'
            'schedule LAMP pause x' | "`oillamp schedule <dir> pause` takes nothing more, but was given 'x'"
    }

    def 'oillamp refuses an action that `oillamp schedule` does not know, before it looks for the lamp'() {
        reportInfo """
            `oillamp schedule <dir>` lists, adds, removes, enables, disables, pauses and resumes
            the jobs that wake the agent. oillamp reports any other word as a usage error before
            it looks for the lamp, so that a typo is reported as a typo, and not as a missing
            lamp.
        """
        when: '`oillamp schedule <dir> tidy` runs on a lamp path where no lamp exists'
            var outcome = host.oillamp.run('schedule', host.lampPath().toString(), 'tidy')

        then: 'it exits with the usage status, and says that `tidy` is not something `oillamp schedule` does'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains("'tidy' is not something `oillamp schedule` does")
    }

    def '`oillamp history <dir>` saves, lists and restores snapshots, and sets when oillamp saves by itself'() {
        reportInfo """
            A lamp's history is a series of snapshots of the lamp, which oillamp can go back to.
            Let's consider a person who looks after the snapshots of one lamp. Taking one, listing
            them, going back to one, and saying when oillamp takes one by itself all act on the
            same history, so they share one command, `oillamp history <dir>`, as the jobs share
            `oillamp schedule <dir>`. The lamp comes first, and the action after it.
            `oillamp history <dir>` alone lists the snapshots, as `oillamp history <dir> list`
            does, and `save`, `restore` and `autosave` do the rest. The person does each in turn,
            and each succeeds.
        """
        given: 'a lamp that has run one session'
            host.machine { it.reallyRuns('ssh-keygen') }
            var lamp = host.lampPath().toString()
            assert host.oillamp.run('at', lamp).succeeded()

        when: '`oillamp history <dir> save --message "before the upgrade"` runs'
            var saved = host.oillamp.run('history', lamp, 'save', '--message', 'before the upgrade')

        then: 'oillamp takes a snapshot with that message'
            saved.succeeded()
            var snapshot = saved.events().find { it instanceof dev.lamp.LampEvent.Saved }.snapshot()
            snapshot.message() == 'before the upgrade'

        when: '`oillamp history <dir>` runs without an action'
            var listed = host.oillamp.run('history', lamp)

        then: 'it lists the new snapshot first, as the newest'
            listed.succeeded()
            listed.events().find { it instanceof dev.lamp.LampEvent.History }.snapshots().first().id() == snapshot.id()

        when: '`oillamp history <dir> list` runs'
            var listedByName = host.oillamp.run('history', lamp, 'list')

        then: 'it lists the same snapshots'
            listedByName.succeeded()
            listedByName.events().find { it instanceof dev.lamp.LampEvent.History }.snapshots()*.id() ==
                    listed.events().find { it instanceof dev.lamp.LampEvent.History }.snapshots()*.id()

        when: '`oillamp history <dir> restore` is given the snapshot\'s short id'
            var restored = host.oillamp.run('history', lamp, 'restore', snapshot.shortId())

        then: 'oillamp restores the snapshot'
            restored.succeeded()
            restored.events().any { it instanceof dev.lamp.LampEvent.Restored }

        when: '`oillamp history <dir> autosave` runs alone, and then with `startup on`'
            var shown = host.oillamp.run('history', lamp, 'autosave')
            var switched = host.oillamp.run('history', lamp, 'autosave', 'startup', 'on')

        then: 'the first reports the autosave settings, and the second switches on saving at startup'
            shown.succeeded()
            shown.events().any { it instanceof dev.lamp.LampEvent.AutosaveSettings }
            switched.succeeded()
            switched.events().find { it instanceof dev.lamp.LampEvent.AutosaveSettings }.startup()
    }

    def 'oillamp refuses a word after `oillamp history <dir>` that is not one of its actions: oillamp #line'() {
        reportInfo """
            Let's consider a person who types `oillamp history <dir> tidy`, expecting a clean-up
            that does not exist. After the lamp, `oillamp history <dir>` knows ten actions:
            `list`, `save`, `restore`, `autosave`, `branches`, `branch`, `switch`, `reset`, `prune`
            and `max-growth`. Any other word is a usage error that names those ten and shows how
            each is written. oillamp reports it before it looks for the lamp, so that a typo is
            reported as a typo, and not as a missing lamp.
        """
        when: 'oillamp runs the line'
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then: 'it exits with the usage status, names the ten actions, and shows how each is written'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(
                    "'${word}' is not something `oillamp history <dir>` does; it does list, save, restore, autosave, "
                     + "branches, branch, switch, reset, prune and max-growth")
            ['oillamp history <dir> [list] [--all]', 'oillamp history <dir> save [--message <text>]',
             'oillamp history <dir> restore <snapshot>', 'oillamp history <dir> autosave [',
             'oillamp history <dir> branches', 'oillamp history <dir> branch <name> [<snapshot>]',
             'oillamp history <dir> branch --delete <name> [--yes]', 'oillamp history <dir> switch <name>',
             'oillamp history <dir> reset <snapshot> [--yes]', 'oillamp history <dir> max-growth [<gb>]'].every { outcome.console().contains(it) }

        where:
            line                        | word
            'history LAMP tidy'         | 'tidy'
            'history LAMP undo a1b2c3d4' | 'undo'
    }

    def 'oillamp refuses `save`, `restore` and `autosave` as commands of their own: oillamp #line'() {
        reportInfo """
            Let's consider a person who types `oillamp save ~/lamps/first`. Taking a snapshot is
            an action of the lamp's history, so it is written `oillamp history <dir> save`, next
            to `restore` and `autosave`. oillamp has one way to write each command line, so there
            is no `save`, `restore` or `autosave` command of its own. oillamp refuses each as it
            refuses any word it does not know, with the list of commands, and the lamp stays as it
            was.
        """
        when: 'oillamp runs the line'
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then: 'it says that the first word is not an oillamp command, and lists the commands, among them `history <dir> save`'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains("'${line.split(' ')[0]}' is not an oillamp command")
            outcome.console().contains('history <dir> save [--message <text>]')

        and: 'no lamp directory was created'
            !Files.exists(host.lampPath())

        where:
            line << ['save LAMP', 'save LAMP --message note', 'restore LAMP a1b2c3d4', 'autosave LAMP', 'autosave LAMP startup on']
    }

    /** What bash offers for the last word, using the completion script oillamp printed. */
    private static List<String> complete(Path script, String... words) {
        var program = """
            source '${script}'
            COMP_WORDS=(${words.collect { "'" + it + "'" }.join(' ')})
            COMP_CWORD=${words.length - 1}
            _oillamp
            printf '%s\\n' "\${COMPREPLY[@]}"
            """.stripIndent()
        var process = new ProcessBuilder('bash', '-c', program).redirectErrorStream(true).start()
        var output = process.inputStream.text
        assert process.waitFor() == 0 : output
        output.readLines().findAll { !it.isBlank() }
    }
}
