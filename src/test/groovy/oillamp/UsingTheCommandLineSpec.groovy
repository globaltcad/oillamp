package oillamp

import dev.lamp.LampExitStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 *  The first thing anyone does with a new tool is type it wrong. These scenarios are about what
 *  happens then - and about the exit codes, which are a contract for anything wrapping oillamp
 *  in a script.
 */
class UsingTheCommandLineSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

    def setup() { host = new ScenarioHost(tmp) }

    def 'Running oillamp with no arguments shows what it can do'() {
        when:
            var outcome = host.oillamp.run()

        then: 'the user is shown the commands rather than an error about nothing'
            outcome.console().contains('at <dir>')
            outcome.console().contains('doctor [<dir>]')
            outcome.console().contains('config <dir>')

        and: 'and it is still a usage error, so a script notices'
            outcome.status() == LampExitStatus.USAGE
            outcome.status().code() == 2
    }

    def 'Oillamp refuses a mistyped command or option before anything happens'() {
        reportInfo """
            oillamp installs packages and writes to the user's home. Acting on a half-understood
            command line would be the worst possible moment to be helpful, so an unrecognised
            command or option stops everything, and the message says so explicitly.
        """
        when: 'the user mistypes the command'
            var command = host.oillamp.run('doctro')

        then:
            command.status() == LampExitStatus.USAGE
            command.errors().first().whatHappened().contains("'doctro'")
            command.errors().first().whyItMatters().contains('nothing on your machine changed')

        when: 'or invents an option'
            var option = host.oillamp.run('doctor', '--fix-everything')

        then:
            option.status() == LampExitStatus.USAGE
            option.errors().first().whatHappened().contains('--fix-everything')
    }

    def 'Oillamp refuses an option or argument the command does not take, rather than ignoring it: oillamp #line'() {
        reportInfo """
            Each option means something to only some commands, and the others used to ignore
            it. That is worst when the option is a safety: `oillamp stop <dir> --dry-run` stopped
            the session, because only `at`, `remove` and `recordings` know what a dry run is.
            It also hides a misunderstanding, as with `oillamp at <dir> --view-only`, which opened
            an ordinary viewer. So an option the command does not take, or an argument too many,
            is a usage error, and the message shows what the command does take.
        """
        given:
            var lamp = host.lampPath().toString()

        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', lamp).split(' '))

        then:
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
            'list --embedded'                  | "`oillamp list` does not take --embedded"  | 'oillamp list'
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

    def 'The options a command does take are still accepted: oillamp #line'() {
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
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

    def 'Asking to set up a lamp without saying where is a usage error, not a crash'() {
        when:
            var outcome = host.oillamp.run('at')

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.console().contains('oillamp at <dir>')
    }

    def 'Oillamp reports its version without needing a machine or a lamp'() {
        when:
            var outcome = host.oillamp.run('version')

        then:
            outcome.status() == LampExitStatus.SUCCESS
            outcome.console().contains('oillamp 0.2.0')

        and: 'and the Java it runs on, which a bug report needs as well'
            outcome.console().contains('java ' + Runtime.version())
    }

    def 'Someone new can learn what oillamp is for, and how to use it, from oillamp itself'() {
        reportInfo """
            `help` lists the commands, which is enough once you know what they are for. `about`
            and `guide` are for the moment before that. Like `help`, they need no machine and
            no lamp, and change nothing.
        """
        when: 'the user asks what oillamp is'
            var about = host.oillamp.run('about')

        then: 'they learn why it exists and what it is built from'
            about.status() == LampExitStatus.SUCCESS
            about.console().contains('WHY IT EXISTS')
            about.console().contains('podman')
            about.console().contains('oillamp 0.2.0')

        when: 'and how to get going'
            var guide = host.oillamp.run('guide')

        then: 'they get the steps in the order they need them, from checking the machine to deleting the lamp'
            guide.status() == LampExitStatus.SUCCESS
            var steps = ['oillamp doctor', 'oillamp at ~/lamps/first', 'oillamp stop ~/lamps/first',
                         'oillamp remove ~/lamps/first --yes'].collect { guide.console().indexOf(it) }
            steps.every { it >= 0 }
            steps == steps.sort(false)

        and: 'it says how to run a session where no window can open, since --no-viewer is not that'
            guide.console().contains('oillamp at ~/lamps/first --no-windows')
            guide.console().contains('--no-viewer is not the same')

        and: 'it names the keys for the desktop\'s windows, since sway draws no buttons on them'
            ['Ctrl+Super+F', 'Ctrl+Super+Q', 'Ctrl+Super+M', 'Ctrl+Super+N'].every { guide.console().contains(it) }

        and: 'the command list sends newcomers to both'
            var help = host.oillamp.run('help')
            help.console().contains('oillamp guide')
            help.console().contains('oillamp about')
    }

    def 'A bug inside oillamp still reaches the user as something they can report'() {
        reportInfo """
            No bare stack traces on the console, ever. That has to hold even for a genuine bug,
            because the moment a user sees a stack trace is the moment they stop being able to
            tell "I did something wrong" from "this tool is broken".

            An unexpected failure therefore becomes a normal problem, with a code, the advice to
            re-run with --verbose, and the start of the trace kept as evidence rather than printed
            raw.
        """
        given: 'a machine whose id lookup returns something impossible'
            host.machine { it.generatedAgentId('NOT A VALID ID') }

        when: 'the user tries to set up a lamp'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'they get a problem, not a stack trace'
            outcome.reported('OIL-INTERNAL-001')
            outcome.status() == LampExitStatus.ERROR
            !outcome.console().contains('\tat dev.oillamp')

        and: 'with the advice that makes a useful bug report'
            outcome.errors().first().fixes().any { it.description().contains('--verbose') }
    }

    def 'Commands that need a running session say which lamp has none'() {
        reportInfo """
            `view`, `shell`, `stop` and `status` are questions put to a session that is already
            running. Asked of a directory that is not a lamp at all, the honest answer names the
            directory and offers the command that would make one - rather than "no session",
            which would send the user looking for a session they never started.

            They must also change nothing on the way to answering. A `status` that quietly
            created or repaired a lamp would be the last thing anyone wants from a command whose
            whole purpose is to report.
        """
        given: 'a directory that was never made into a lamp'
            var lamp = host.lampPath()

        when:
            var outcome = host.oillamp.run('view', lamp.toString())

        then: 'oillamp says so, and names the way out'
            !outcome.succeeded()
            outcome.errors().first().whatHappened().contains('not an oillamp lamp')

        and: 'and the directory is still not there, because asking created nothing'
            !java.nio.file.Files.exists(lamp)
    }

    def 'Oillamp\'s `recordings --open` without a session to play is a usage error, not a silent listing'() {
        when:
            var outcome = host.oillamp.run('recordings', host.lampPath().toString(), '--open')

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.console().contains('--open needs the session to play')
    }

    def 'Oillamp\'s `--no-color` leaves no colour codes in the output'() {
        reportInfo """
            Colour codes are invisible on a terminal but end up as junk in a log file or a
            pasted bug report. `NO_COLOR=1` turned them off before; the `--no-color` option was
            listed in the help but did nothing.
        """
        when: 'the same command runs on a terminal, with and without the option'
            var coloured = host.oillamp.run('doctor')
            var plain = host.oillamp.run('doctor', '--no-color')

        then:
            coloured.console().contains('\u001B[')
            !plain.console().contains('\u001B[')
    }

    def 'Checking a lamp whose configuration is broken says what is wrong, and exits as a usage error'() {
        reportInfo """
            `oillamp doctor <dir>` checks the machine and then the lamp's configuration. A mistake
            in oillamp.toml is the user's to fix, so it is reported with the file and key, and the
            exit code is 2, the same as for any other usage error, so a script can tell it apart
            from a machine that cannot run sandboxes (3).
        """
        given:
            host.givenConfig(host.lampPath(), """
                schema_version = 1

                [display]
                size = "huge"
                """.stripIndent())

        when:
            var outcome = host.oillamp.run('doctor', host.lampPath().toString())

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().any { it.code().value().startsWith('OIL-CONFIG-') }
            outcome.console().contains('size')
    }

    def 'Oillamp\'s `config <dir> path` prints where the lamp\'s configuration lives'() {
        when:
            var outcome = host.oillamp.run('config', host.lampPath().toString(), 'path')

        then:
            outcome.status() == LampExitStatus.SUCCESS
            outcome.console().trim().endsWith(host.lampPath().resolve('oillamp.toml').toString())
    }

    def 'The completion script is only the script, and completes what oillamp accepts'() {
        reportInfo """
            `eval "\$(oillamp completion bash)"` runs whatever oillamp prints, so the output must
            be the script and nothing else: no banner, no colour. And the completions must match
            the command line oillamp parses. In `oillamp config <dir> check` and
            `oillamp history <dir> save`, the directory comes first, so the word after `config` or
            `history` is a path and the one after that is the action. After
            `oillamp history <dir> switch` comes a branch, one of the lines of snapshots in the
            lamp's history, and the script offers the names of those the history has.
        """
        when:
            var outcome = host.oillamp.run('completion', 'bash')

        then: 'nothing but the script is printed'
            outcome.status() == LampExitStatus.SUCCESS
            !outcome.console().contains('🪔')
            !outcome.console().contains('\u001B[')

        and: 'bash accepts it and completes commands, options and config actions'
            var script = tmp.resolve('completion.bash')
            Files.writeString(script, outcome.console())
            complete(script, 'oillamp', 'sta').containsAll(['status'])
            complete(script, 'oillamp', 'at', '/x', '--dry').containsAll(['--dry-run'])
            complete(script, 'oillamp', 'at', '/x', '--model').containsAll(['--model', '--model-service', '--model-key-env'])
            !complete(script, 'oillamp', 'config', '').contains('check')
            complete(script, 'oillamp', 'config', '/x', '').containsAll(['check', 'show-effective', 'path'])

        and: 'it completes the history actions after the lamp, and the moments and on or off after autosave'
            !complete(script, 'oillamp', 'history', '').contains('save')
            complete(script, 'oillamp', 'history', '/x', '') == ['list', 'save', 'restore', 'autosave', 'branches', 'branch', 'switch', 'reset', 'prune']
            complete(script, 'oillamp', 'history', '/x', 'autosave', '') == ['startup', 'shutdown', 'before-run', 'after-run']
            complete(script, 'oillamp', 'history', '/x', 'autosave', 'startup', '') == ['on', 'off']
            complete(script, 'oillamp', 'history', '/x', 'autosave', 'startup', 'on', 'a') == ['after-run']
            complete(script, 'oillamp', 'history', '/x', 'save', '--m') == ['--message']
            complete(script, 'oillamp', 'history', '/x', 'list', '--a') == ['--all']
            complete(script, 'oillamp', 'history', '/x', 'branch', '--d') == ['--debug', '--delete']
            complete(script, 'oillamp', 'history', '/x', 'reset', 'a1b2', '--y') == ['--yes']

        and: 'it completes the branches of the lamp\'s history after switch'
            var heads = Files.createDirectories(tmp.resolve('lamp/.oillamp/history/refs/heads'))
            Files.writeString(heads.resolve('main'), '0' * 40 + '\n')
            Files.writeString(heads.resolve('rust-rewrite'), '0' * 40 + '\n')
            complete(script, 'oillamp', 'history', tmp.resolve('lamp').toString(), 'switch', '') == ['main', 'rust-rewrite']
            complete(script, 'oillamp', 'history', tmp.resolve('lamp').toString(), 'switch', 'r') == ['rust-rewrite']
            !complete(script, 'oillamp', 'sa').contains('save')

        and: 'it offers each command only the options that command takes'
            complete(script, 'oillamp', 'stop', '/x', '--d') == ['--debug']
            complete(script, 'oillamp', 'view', '/x', '--v').containsAll(['--view-only', '--verbose'])
            complete(script, 'oillamp', 'update', '/x', '--s') == ['--system']
            !complete(script, 'oillamp', 'at', '/x', '--').contains('--yes')
            complete(script, 'oillamp', 'completion', '') == ['bash']
            complete(script, 'oillamp', 'list', '').isEmpty()
    }

    def 'An option\'s value can follow it, or be joined to it with =: oillamp #line'() {
        reportInfo """
            `--model-service https://x` and `--model-service=https://x` mean the same, as they
            do for most command-line tools, and that holds for every option that takes a value.
            Each pair of lines here is refused for the same reason and quotes the same value,
            which shows that both spellings handed oillamp the same thing. A value joined with =
            is quoted without the =.
        """
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
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

    def 'An option given last, with no value after it, says what value it needs: oillamp #line'() {
        reportInfo """
            An option that takes a value and comes last on the line was usually cut short, or
            the value was lost to quoting in the shell. Running without it could do something
            other than what was meant, so oillamp stops, and shows an example of the value.
        """
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
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

    def 'Oillamp refuses a model service, key variable or model that cannot be right, before anything starts: oillamp #line'() {
        reportInfo """
            `--model-service` and `--model-key-env` override the lamp's [model] settings for one
            session. The key is sent to that service, so an address that is not https, or a
            variable name the shell could never have set, is refused at once, in the same words
            as the same mistake in oillamp.toml would be. `--model` names the model every run is
            answered by. No service names a model with a space or a character outside ASCII,
            such as the non-breaking hyphen a copy from a web page can bring along, so such a
            name is refused too, rather than failing every run of the session.
        """
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(complaint)

        and: 'nothing was set up'
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

    def 'After --, every argument is taken as written, even one that looks like an option'() {
        reportInfo """
            A prompt may well start with a dash, such as a list of things to do. Without `--`,
            oillamp would take `-` followed by anything as an option it does not know. After
            `--`, it takes everything as it is written. The lamp here has no session, so the
            question is not asked; what matters is that oillamp got as far as looking for one,
            rather than refusing the line.
        """
        when: 'a prompt that starts with a dash comes after --'
            var literal = host.oillamp.run('ask', host.lampPath().toString(), '--', '--list the open tickets')

        then: 'it is not mistaken for an option'
            literal.status() != LampExitStatus.USAGE
            !literal.errors().any { it.whatHappened().contains('is not an option oillamp knows') }

        when: 'the same prompt without --'
            var option = host.oillamp.run('ask', host.lampPath().toString(), '--list the open tickets')

        then: 'it is taken for an option, and refused'
            option.status() == LampExitStatus.USAGE
            option.errors().first().whatHappened().contains("'--list the open tickets' is not an option oillamp knows")
    }

    def 'Oillamp\'s `-v` is short for `--verbose`, and `--debug` turns verbose output on too'() {
        reportInfo """
            `--verbose` shows each step oillamp takes as it goes, not only the outcome. `-v` is
            its short form. `--debug` does nothing more than `--verbose` yet, but it promises at
            least as much, so it turns verbose output on as well.
        """
        given:
            var lamp = host.lampPath().toString()

        when: 'the same dry run, plain and with each of the three'
            var quiet = host.oillamp.run('at', lamp, '--dry-run')
            var verbose = host.oillamp.run('--verbose', 'at', lamp, '--dry-run')
            var shortForm = host.oillamp.run('-v', 'at', lamp, '--dry-run')
            var debug = host.oillamp.run('--debug', 'at', lamp, '--dry-run')

        then: 'verbose output shows more than the plain one'
            verbose.console().length() > quiet.console().length()

        and: 'and the other two show the same'
            shortForm.console() == verbose.console()
            debug.console() == verbose.console()
    }

    def 'A command that works on a lamp says so when no lamp is given: oillamp #command'() {
        reportInfo """
            Every one of these commands acts on one lamp directory, and none can guess which.
            Without one, the answer is a usage error that names the missing directory, rather
            than a crash or a guess at the current directory.
        """
        when:
            var outcome = host.oillamp.run(command)

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains("`oillamp ${command}` needs the path of a lamp directory")
            outcome.console().contains("oillamp ${command} <dir>")

        where:
            command << ['at', 'view', 'shell', 'stop', 'status', 'follow', 'recordings', 'config', 'remove',
                        'history', 'schedule', 'ask', 'conversations', 'cancel']
    }

    def 'A command that works on one lamp refuses several, rather than acting on the first: oillamp #command'() {
        reportInfo """
            A shell pattern such as `test*` easily expands to more than one directory. A
            command that quietly acted on the first and ignored the rest would look as if it
            had done them all, so it refuses, and lists what it was given. `remove` is the one
            command that takes several lamps on purpose.
        """
        given:
            var first = host.lampPath('one').toString()
            var second = host.lampPath('two').toString()

        when:
            var outcome = host.oillamp.run(command, first, second)

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened()
                    .contains("`oillamp ${command}` works on one lamp, but was given 2 directories (${first}, ${second}); run it once for each")

        where:
            command << ['at', 'view', 'shell', 'stop', 'status', 'follow', 'recordings', 'doctor']
    }

    def 'The first line names the lamp the command works on'() {
        reportInfo """
            Commands that set up or change a lamp start with a line naming oillamp, its version
            and the lamp, so that a terminal with several sessions in it, or a pasted log, shows
            which lamp each part is about. `remove` says how many lamps it was given instead.
        """
        given:
            var lamp = host.lampPath().toString()

        expect:
            host.oillamp.run('doctor', lamp).console().contains("oillamp 0.2.0 — ${lamp}")
            host.oillamp.run('at', lamp, '--dry-run').console().contains("oillamp 0.2.0 — ${lamp}")
            host.oillamp.run('history', lamp, 'save').console().contains("oillamp 0.2.0 — ${lamp}")
            host.oillamp.run('history', lamp, 'restore', 'a1b2c3d4').console().contains("oillamp 0.2.0 — ${lamp}")
            host.oillamp.run('remove', lamp, host.lampPath('other').toString()).console().contains('oillamp 0.2.0 — 2 lamps')
    }

    def 'Oillamp\'s `config` command checks the lamp when no action is given, and refuses an action it does not know'() {
        reportInfo """
            `oillamp config <dir>` on its own checks the configuration, which is what someone
            who just edited oillamp.toml wants. `show-effective` prints every setting as a
            session would use it, which a check does not. An action oillamp does not know is a
            usage error, not a silent check.
        """
        given:
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')

        when:
            var bare = host.oillamp.run('config', lamp.toString())
            var check = host.oillamp.run('config', lamp.toString(), 'check')
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')
            var unknown = host.oillamp.run('config', lamp.toString(), 'fix')

        then: 'with no action, it checks'
            bare.status() == LampExitStatus.SUCCESS
            bare.console() == check.console()

        and: 'show-effective shows more than the check does'
            shown.status() == LampExitStatus.SUCCESS
            shown.console() != check.console()
            shown.events().any { it instanceof dev.lamp.LampEvent.Answer }
            !check.events().any { it instanceof dev.lamp.LampEvent.Answer }

        and: 'an unknown action is refused, with the actions there are'
            unknown.status() == LampExitStatus.USAGE
            unknown.errors().first().whatHappened().contains("'fix' is not a config action")
            unknown.console().contains('oillamp config <dir> (check | show-effective | path)')
    }

    def 'The completion script is for bash, also when no shell is named'() {
        reportInfo """
            oillamp ships a completion script for bash only. So `oillamp completion` with no
            shell gives that script, and naming another shell is refused: a bash script handed
            to zsh would fail in ways that are hard to trace back to oillamp.
        """
        when:
            var unnamed = host.oillamp.run('completion')
            var zsh = host.oillamp.run('completion', 'zsh')

        then: 'with no shell named, it is the bash script'
            unnamed.status() == LampExitStatus.SUCCESS
            unnamed.console() == host.oillamp.run('completion', 'bash').console()

        and: 'another shell is refused, rather than given a script it cannot run'
            zsh.status() == LampExitStatus.USAGE
            zsh.errors().first().whatHappened().contains("oillamp only ships a completion script for bash, not 'zsh'")
    }

    def 'Oillamp\'s `help` command lists the commands, and is not an error'() {
        reportInfo """
            Running oillamp with nothing lists the commands too, but as a usage error, since
            nothing was asked. `oillamp help` asked for the list, so it succeeds.
        """
        when:
            var outcome = host.oillamp.run('help')

        then:
            outcome.status() == LampExitStatus.SUCCESS
            outcome.console().contains('at <dir>')
            outcome.console().contains('doctor [<dir>]')
    }

    def 'Oillamp says why there is no `image` command when someone asks for one'() {
        reportInfo """
            An `image` command was planned and then dropped. oillamp builds the sandbox image
            again by itself when its settings change, and `oillamp update` renews what is in it.
            Someone who read about an `image` command, or expects one from other container tools,
            is told that, not just "unknown command".
        """
        when:
            var outcome = host.oillamp.run('image')

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains("there is no 'image' command")
            outcome.errors().first().whatHappened().contains('oillamp update <dir> --system')
    }

    def 'Oillamp refuses an `ask`, `history` or `schedule` line that leaves out what the command acts on: oillamp #line'() {
        reportInfo """
            These lines name a lamp but leave out the one thing the command needs to act: the
            question to ask, the snapshot to go back to, whether to switch a moment of autosave on
            or off, the prompt for a new job, or the job to change; or, for
            `schedule <dir> pause`, give it a word it does not take. Oillamp
            refuses each with a usage error that says what is missing or extra, before it looks
            for a lamp or a session.
        """
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
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

    def 'Oillamp refuses an action that `schedule` does not know, before it looks for the lamp'() {
        reportInfo """
            `oillamp schedule <dir>` lists, adds, removes, enables, disables, pauses and resumes.
            Oillamp reports any other word as a usage error before it looks for the lamp, so a
            typo is reported as a typo and not as a lamp that is missing.
        """
        when:
            var outcome = host.oillamp.run('schedule', host.lampPath().toString(), 'tidy')

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains("'tidy' is not something `oillamp schedule` does")
    }

    def 'Oillamp saves, lists, restores and sets autosave under `history <dir>`'() {
        reportInfo """
            Let's consider a person who looks after one lamp's snapshots. Taking one, listing
            them, going back to one and saying when oillamp takes one by itself all act on the
            same history, so they share one command, `oillamp history <dir>`, as the jobs share
            `oillamp schedule <dir>`. The lamp comes first and the action after it:
            `oillamp history <dir>` alone lists, as `oillamp history <dir> list` does, and `save`,
            `restore` and `autosave` do the rest. Here the person does each in turn, and each one
            succeeds.
        """
        given: 'a lamp that has run one session'
            host.machine { it.reallyRuns('ssh-keygen') }
            var lamp = host.lampPath().toString()
            assert host.oillamp.run('at', lamp).succeeded()

        when: 'the person saves the lamp with a message'
            var saved = host.oillamp.run('history', lamp, 'save', '--message', 'before the upgrade')

        then: 'oillamp makes a snapshot with that message'
            saved.succeeded()
            var snapshot = saved.events().find { it instanceof dev.lamp.LampEvent.Saved }.snapshot()
            snapshot.message() == 'before the upgrade'

        when: 'the person lists the snapshots'
            var listed = host.oillamp.run('history', lamp)

        then: 'the new snapshot is the newest one'
            listed.succeeded()
            listed.events().find { it instanceof dev.lamp.LampEvent.History }.snapshots().first().id() == snapshot.id()

        when: 'the person lists them again, with the word list'
            var listedByName = host.oillamp.run('history', lamp, 'list')

        then: 'oillamp lists the same snapshots'
            listedByName.succeeded()
            listedByName.events().find { it instanceof dev.lamp.LampEvent.History }.snapshots()*.id() ==
                    listed.events().find { it instanceof dev.lamp.LampEvent.History }.snapshots()*.id()

        when: 'the person goes back to it'
            var restored = host.oillamp.run('history', lamp, 'restore', snapshot.shortId())

        then:
            restored.succeeded()
            restored.events().any { it instanceof dev.lamp.LampEvent.Restored }

        when: 'the person reads when oillamp saves by itself, and switches one moment on'
            var shown = host.oillamp.run('history', lamp, 'autosave')
            var switched = host.oillamp.run('history', lamp, 'autosave', 'startup', 'on')

        then: 'the first reports the settings, and the second writes the one moment to the file'
            shown.succeeded()
            shown.events().any { it instanceof dev.lamp.LampEvent.AutosaveSettings }
            switched.succeeded()
            switched.events().find { it instanceof dev.lamp.LampEvent.AutosaveSettings }.startup()
    }

    def 'Oillamp refuses a word after `history <dir>` that is not one of its actions: oillamp #line'() {
        reportInfo """
            Let's consider a person who types `oillamp history <dir> tidy`, expecting a clean-up
            that does not exist. After the lamp, `oillamp history <dir>` knows eight actions:
            `list`, `save`, `restore`, `autosave`, `branches`, `branch`, `switch` and `reset`. Any
            other word is a usage error that names those eight and shows how each is written, and
            oillamp reports it before it looks for the lamp, so a typo is reported as a typo and
            not as a lamp that is missing.
        """
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains(
                    "'${word}' is not something `oillamp history <dir>` does; it does list, save, restore, autosave, "
                     + "branches, branch, switch, reset and prune")
            ['oillamp history <dir> [list] [--all]', 'oillamp history <dir> save [--message <text>]',
             'oillamp history <dir> restore <snapshot>', 'oillamp history <dir> autosave [',
             'oillamp history <dir> branches', 'oillamp history <dir> branch <name> [<snapshot>]',
             'oillamp history <dir> branch --delete <name> [--yes]', 'oillamp history <dir> switch <name>',
             'oillamp history <dir> reset <snapshot> [--yes]'].every { outcome.console().contains(it) }

        where:
            line                        | word
            'history LAMP tidy'         | 'tidy'
            'history LAMP undo a1b2c3d4' | 'undo'
    }

    def 'Oillamp refuses `save`, `restore` and `autosave` as commands of their own: oillamp #line'() {
        reportInfo """
            Let's consider a person who types `oillamp save ~/lamps/first`. Taking a snapshot is
            an action of the lamp's history, so it is written `oillamp history <dir> save`, next
            to `restore` and `autosave`. Oillamp has one way to write each command line, so it
            knows no `save`, `restore` or `autosave` of their own: it refuses each as it refuses
            any word it does not know, with the list of commands, and the lamp stays as it was.
        """
        when:
            var outcome = host.oillamp.run(*line.replace('LAMP', host.lampPath().toString()).split(' '))

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains("'${line.split(' ')[0]}' is not an oillamp command")
            outcome.console().contains('history <dir> save [--message <text>]')

        and: 'no lamp was made'
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
