package oillamp

import dev.lamp.LampExitStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 *  `oillamp.toml` is where a user says how big the desktop is, whether the screen is recorded,
 *  and - the part that matters most - where the agent is allowed to connect. It is the sandbox's
 *  policy, so a mistake in it has to be caught and explained rather than quietly ignored.
 */
class ConfiguringALampSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'Oillamp reports all three mistakes in one file at once, each with where it is'() {
        reportInfo """
            This is the behaviour a configuration file depends on most. A user with three
            mistakes should be told about all three, each with the file, the key path, the value
            they wrote and what was expected - so they fix the file once.

            The alternative, stopping at the first mistake, turns a two-minute edit into a
            fix-run-fix-run loop. oillamp collects problems rather than throwing on the first,
            precisely so this scenario can hold.
        """
        given: 'a settings file with an unknown key, a malformed network range, and two forwards claiming the same port'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [display]
                width   = 1920
                heigth  = 1080          # typo

                [[network.rules]]
                label  = "block the intranet"
                action = "deny"
                cidrs  = ["10.0.0/8"]   # not a valid network

                [[network.forwards]]
                name   = "llm"
                port   = 8000
                target = "llm.corp.example.com:8000"

                [[network.forwards]]
                name   = "metrics"
                port   = 8000           # already taken by "llm"
                target = "metrics.corp.example.com:9090"
            '''.stripIndent())

        when: 'the user asks oillamp to check the configuration'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'the exit code says the problem is the configuration, not the machine'
            outcome.status() == LampExitStatus.USAGE

        and: 'all three are reported'
            outcome.errors().size() == 3
            outcome.reported('OIL-CONFIG-002')      // unknown key
            outcome.reported('OIL-CONFIG-004')      // bad value

        and: 'the typo is located exactly, and the right key is suggested'
            outcome.console().contains('display.heigth')
            outcome.console().contains("did you mean 'height'?")

        and: 'the malformed network range is located down to the array element'
            outcome.console().contains('network.rules[0].cidrs')
            outcome.console().contains('10.0.0/8')

        and: 'and the port clash names the forward that already had the port'
            outcome.console().contains('network.forwards[1]')
            outcome.console().contains('already used by "llm"')
    }

    def 'Oillamp refuses an unknown key in `oillamp.toml` rather than ignoring it'() {
        reportInfo """
            This looks strict, and it is deliberate. The most dangerous typo in this file is one
            in a network rule: a rule key that oillamp silently ignores is a rule that does not
            apply, which is a hole in the sandbox that nothing announces.

            Since oillamp cannot tell a harmless typo from a dangerous one, it refuses every
            unknown key with `OIL-CONFIG-002` - and suggests the nearest known key, because it is
            almost always a typo.
        """
        given:
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [netwrok]
                default = "deny"
            '''.stripIndent())

        when:
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-002')
            outcome.console().contains("did you mean 'network'?")
    }

    def 'Oillamp refuses an `oillamp.toml` that does not say which schema it was written for'() {
        reportInfo """
            The version marker is what lets a future oillamp migrate an old file instead of
            guessing at it. Requiring it costs the user one line and buys the project the
            ability to change this format later without breaking anyone's lamp.
        """
        given: 'a configuration with no schema_version'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                [display]
                width = 1920
            '''.stripIndent())

        when:
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('schema_version')
    }

    def 'Oillamp reports an empty `oillamp.toml` as missing its schema_version, not as an internal error'() {
        reportInfo """
            A file with nothing in it is what someone gets by creating oillamp.toml with `touch`.
            It has no schema_version, and that is what oillamp tells the user, with
            `OIL-CONFIG-004`, as for any file that lacks it, rather than reporting a bug in itself
            (`OIL-INTERNAL-001`).
        """
        given: 'a configuration file with nothing in it'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '')

        when:
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-004')
            !outcome.reported('OIL-INTERNAL-001')
    }

    def 'Oillamp refuses a forward on a port the sandbox uses for its own proxy or model relay'() {
        reportInfo """
            Inside the sandbox, 127.0.0.1:3128 is the policy-controlled egress proxy, and
            127.0.0.1:3129 is where the harnesses reach the model service through oillamp. A
            forward is a fixed tunnel from a port inside the sandbox to one address on the
            network. A forward bound to either port would fail to start or - worse - take the
            port's connections in its place, so that
            traffic the user believes is being checked against their rules silently is not, or
            model requests go somewhere else.

            Better to refuse the configuration, with `OIL-CONFIG-004`, and say which port to
            move.
        """
        given:
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [[network.forwards]]
                name   = "llm"
                port   = ${port}
                target = "llm.corp.example.com:8000"
            '''.stripIndent().replace('${port}', port.toString()))

        when:
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains(explanation)

        where:
            port | explanation
            3128 | 'egress proxy'
            3129 | 'model service through oillamp'
    }

    def 'Oillamp refuses an `llm.forward` that names a forward that does not exist'() {
        reportInfo """
            `llm.forward` names one of the `[[network.forwards]]` entries, the fixed tunnels from a
            port inside the sandbox to one address. A typo there would
            otherwise surface much later, as an agent tool that cannot reach any model and a user
            with no idea why. So oillamp refuses it with `OIL-CONFIG-004`, and the message lists
            the forwards that do exist.
        """
        given:
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [[network.forwards]]
                name   = "llm"
                port   = 8000
                target = "llm.corp.example.com:8000"

                [llm]
                forward = "language-model"
            '''.stripIndent())

        when:
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('llm.forward')
            outcome.console().contains('"llm"')
    }

    def 'The commented file oillamp writes means exactly what oillamp does'() {
        reportInfo """
            A new lamp gets a fully commented `oillamp.toml`, which is the documentation most
            users will actually read. If it drifted from oillamp's built-in defaults it would be
            worse than no documentation at all - confidently wrong.

            So: create a lamp, then read back what the untouched file produces, and check it
            against the defaults oillamp reports for itself.
        """
        given: 'a freshly created lamp, with its shipped configuration file untouched'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())

        when: 'the user asks what the effective settings are'
            var outcome = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'it reads back the values the file documents'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.console().contains('1920x1080')
            outcome.console().contains('gpu auto')
            outcome.console().contains('clipboard to-agent')
            outcome.console().contains('memory 16g')

        and: 'including the shipped policy: the open web, minus Eden AI outside the EU and the intranet'
            outcome.console().contains('default allow')
            outcome.console().contains('2 rule')

        and: 'the file itself is not readable by anyone but the user, since it is the sandbox policy'
            java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(lamp.resolve('oillamp.toml'))) == 'rw-------'
    }

    def 'A company-wide setting applies to every lamp, and a lamp can still override it'() {
        reportInfo """
            There is an optional file at `~/.config/oillamp/config.toml` that applies to every
            lamp this user creates. It exists so that a company can set something like the address
            of its language-model service once, instead of in every lamp separately.

            Three sources of settings are merged, and later ones win over earlier ones: oillamp's
            own built-in defaults first, then this user-wide file, then the individual lamp's
            `oillamp.toml`.

            Tables merge key by key, but arrays replace wholesale - which is not an arbitrary
            choice. If a lamp's `network.rules` were merged with the global list, the effective
            policy would be a union nobody wrote down and nobody could reason about. A lamp that
            lists rules gets exactly those rules.
        """
        given: 'a company-wide file setting the desktop size and a default policy'
            host.givenGlobalConfig('''
                [display]
                width  = 2560
                height = 1440

                [[network.rules]]
                label  = "company mirror"
                action = "allow"
                hosts  = ["nexus.corp.example.com"]
            '''.stripIndent())

        and: 'a lamp that overrides only the width, and lists its own rules'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [display]
                width = 3840

                [[network.rules]]
                label  = "no intranet"
                action = "deny"
                cidrs  = ["10.0.0.0/8"]
            '''.stripIndent())

        when:
            var outcome = host.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'the lamp\'s width wins, but the height from the company file still applies'
            outcome.console().contains('3840x1440')

        and: 'the lamp\'s rules REPLACE the company rules rather than being added to them'
            outcome.console().contains('1 rule')
    }

    def 'Oillamp reports an `oillamp.toml` that is not valid TOML with a problem code of its own'() {
        reportInfo """
            Before any of oillamp's own validation can run, the file has to parse. Oillamp reports
            a syntax error with its own problem code, `OIL-CONFIG-001`, so the user is not left
            wondering whether they mistyped a key name or a bracket.
        """
        given:
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [display
                width = 1920
            '''.stripIndent())

        when:
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then:
            outcome.reported('OIL-CONFIG-001')
            outcome.status() == LampExitStatus.USAGE
    }

    def 'How long oillamp waits for the sandbox to start is set in the configuration'() {
        reportInfo """
            `timeouts.container_ready_seconds` is how long oillamp waits for the desktop and the
            shell to come up before it gives up. A slow machine needs more. The setting used to
            be accepted and then ignored.

            Straight after the image is built, the first start is slower, because nothing is
            cached yet, so oillamp waits twice as long then.
        """
        given:
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [timeouts]
                container_ready_seconds = 90
            '''.stripIndent())

        when: 'the image has to be built first'
            var afterABuild = host.oillamp.run('at', lamp.toString(), '--dry-run')

        then:
            afterABuild.steps().any { it.contains('ready (up to 180s)') }

        when: 'the image is already there'
            host.machine { it.commandSucceeding('podman image exists', '') }
            var withTheImage = host.oillamp.run('at', lamp.toString(), '--dry-run')

        then:
            withTheImage.steps().any { it.contains('ready (up to 90s)') }
    }

    def 'Windows float by default, and a lamp can have them tiled instead'() {
        reportInfo """
            `display.windows` decides how the sandbox desktop arranges windows. "floating", the
            default, works like most desktops: a window opens at its own size, moves by its title
            bar and resizes by its edges. "tiling" splits the screen between the windows, so none
            covers another. The setting reaches the sandbox in `runtime.env`, and the sandbox
            accepts only these two words, because it turns them into compositor configuration.
        """
        given: 'a lamp that asks for tiling'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [display]
                windows = "tiling"
            '''.stripIndent())

        when:
            host.oillamp.run('at', lamp.toString())
            var runtimeEnv = java.nio.file.Files.readString(lamp.resolve('.oillamp/session/runtime.env'))

        then: 'the sandbox is told to tile'
            runtimeEnv.contains("OILLAMP_WINDOWS='tiling'")

        when: 'the lamp says nothing about windows'
            host.givenConfig(lamp, 'schema_version = 1\n')
            var effective = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'they float'
            effective.console().contains('floating windows')

        when: 'the lamp asks for something else'
            host.givenConfig(lamp, '''
                schema_version = 1

                [display]
                windows = "stacking"
            '''.stripIndent())
            var checked = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'oillamp refuses it, with the two values that work'
            checked.status() == LampExitStatus.USAGE
            checked.reported('OIL-CONFIG-004')
            checked.console().contains('"floating" or "tiling"')
    }

    def 'Oillamp keeps the sandbox on Eden AI\'s EU endpoint, whatever the host\'s environment says'() {
        reportInfo """
            The team's model traffic has to stay in the EU. Inside the sandbox, pi and opencode
            are set up to use Eden AI's EU endpoint and nothing else. From the outside, two
            things could undo that: the user's own `EDENAI_BASE_URL`, if it were copied in, and a
            tool that ignores those settings. So the endpoint variables are never copied from the
            host, and the network policy oillamp writes into a new lamp's `oillamp.toml` refuses
            the global endpoint, `api.edenai.run`, by name.

            The key is not copied either. oillamp holds it on the host and adds it to each model
            request on the way out, so the sandbox has nothing to take elsewhere.
        """
        given: 'a user whose own environment points Eden AI at its global endpoint'
            host.machine {
                it.environmentVariable('EDENAI_API_KEY', 'a-key')
                  .environmentVariable('EDENAI_BASE_URL', 'https://api.edenai.run/v3')
                  .environmentVariable('EDENAI_EU_ONLY', '')
            }
            var lamp = host.lampPath()

        when:
            host.oillamp.run('at', lamp.toString())
            var runtimeEnv = Files.readString(lamp.resolve('.oillamp/session/runtime.env'))
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            var guide = Files.readString(agentHome.resolve('AGENTS.md'))

        then: 'neither the key nor the endpoint reaches the sandbox'
            !runtimeEnv.contains('a-key')
            !runtimeEnv.contains('EDENAI_API_KEY')
            !runtimeEnv.contains('EDENAI_BASE_URL')

        and: 'the new lamp\'s policy refuses the global endpoint, in a rule the user can see and remove'
            var toml = Files.readString(lamp.resolve('oillamp.toml'))
            toml.contains('label  = "Eden AI only through its EU endpoint"')
            toml.contains('hosts  = ["api.edenai.run"]')

        and: 'the agent is told, so it does not go looking for a way around it'
            guide.contains('through oillamp')
            guide.contains('Only models served in the EU are offered')
            guide.contains('`api.edenai.run`, is refused by the network policy')

        when: 'a lamp lists its own rules and leaves that one out'
            host.givenConfig(lamp, '''
                schema_version = 1

                [[network.rules]]
                label  = "block private, internal and loopback ranges"
                action = "deny"
                cidrs  = ["10.0.0.0/8"]
            '''.stripIndent())
            host.oillamp.run('at', lamp.toString())
            guide = Files.readString(agentHome.resolve('AGENTS.md'))

        then: 'the guide no longer claims the policy refuses it'
            guide.contains('Only models served in the EU are offered')
            !guide.contains('is refused by the network policy')
    }

    def 'Checking a configuration judges the same settings a session would use'() {
        reportInfo """
            A session merges the company-wide file `~/.config/oillamp/config.toml` with the lamp's
            own `oillamp.toml`. `oillamp config <dir> check` used to read only the lamp's file, so
            it could call a configuration valid that `oillamp at` then refused.
        """
        given: 'a valid lamp file, and a company-wide file with a mistake in it'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')
            host.givenGlobalConfig('''
                [display]
                width = 10
            '''.stripIndent())

        when:
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'the mistake is found, and located in the company-wide file'
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('.config/oillamp/config.toml')
    }

    def 'A value in the sandbox\'s settings file stays a value, whatever characters it holds'() {
        reportInfo """
            runtime.env is run with `source` by the container's first process, as container
            root, and by every shell of the agent. A value that closed its quotes could therefore
            run a command. Values come from the lamp's directory name and from the host's
            environment, so both are tried here with quotes, `\$(...)` and spaces, and the file is
            sourced by bash exactly as the entrypoint does it.
        """
        given: 'a lamp whose name, and a setting from the host, both look like shell code'
            var marker = tmp.resolve('ran')
            var hostile = "it's \$(touch ran)"
            var key = "k'\$(touch ran)\"`touch ran`"
            host.machine { it.environmentVariable('EDENAI_MAX_TOKENS', key) }
            var lamp = host.lampPath(hostile)

        when:
            host.oillamp.run('at', lamp.toString())
            var sourced = new ProcessBuilder('bash', '-c',
                    'set -a; . "$1"; set +a; printf "%s\\n%s\\n" "$OILLAMP_LAMP_NAME" "$EDENAI_MAX_TOKENS"',
                    'bash', lamp.resolve('.oillamp/session/runtime.env').toString())
                    .directory(tmp.toFile()).redirectErrorStream(true).start()
            var values = sourced.inputStream.text.readLines()

        then: 'bash reads back exactly the values oillamp was given'
            sourced.waitFor() == 0
            values == [hostile, key]

        and: 'and nothing in them ran'
            !Files.exists(marker)
    }

    def 'Oillamp refuses a host value with a line break rather than write it into the sandbox\'s settings file'() {
        reportInfo """
            The sandbox's settings file, `.oillamp/session/runtime.env`, is read with `source` by
            the container's first process and by every shell of the agent. Quoting cannot make a
            line break safe in a file that is read line by line, and no real setting contains
            one. So for a value from the host's environment with a line break, oillamp refuses
            to start the session, with a problem that names the variable and not its value.
        """
        given:
            host.machine { it.environmentVariable('EDENAI_MAX_TOKENS', 'first-line\ntouch /tmp/pwned') }
            var lamp = host.lampPath()

        when:
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'the session does not start'
            outcome.status() != LampExitStatus.SUCCESS
            outcome.console().contains('EDENAI_MAX_TOKENS')
            outcome.console().contains('line break')

        and: 'the value itself is not printed'
            !outcome.console().contains('first-line')

        and: 'and no settings file holding it was written'
            !Files.exists(lamp.resolve('.oillamp/session/runtime.env'))
    }

    def 'The sandbox is told where its language model is, when a forward is set up for one'() {
        reportInfo """
            `[llm]` names the forward that leads to the team's language model. The sandbox gets the
            model's address as it looks from inside, on the forward's port, together with the
            models and the provider name, so the agent's tools can be pointed at it.
        """
        given:
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [[network.forwards]]
                name   = "llm"
                port   = 8000
                target = "llm.corp.example.com:8000"

                [llm]
                forward       = "llm"
                base_path     = "/v1"
                models        = ["coder-large", "coder-small"]
                provider_name = "corp"
            '''.stripIndent())

        when:
            host.oillamp.run('at', lamp.toString())
            var runtimeEnv = Files.readString(lamp.resolve('.oillamp/session/runtime.env'))

        then:
            runtimeEnv.contains("OILLAMP_LLM_BASE_URL='http://127.0.0.1:8000/v1'")
            runtimeEnv.contains("OILLAMP_LLM_MODELS='coder-large,coder-small'")
            runtimeEnv.contains("OILLAMP_LLM_PROVIDER='corp'")
            runtimeEnv.contains("OILLAMP_FORWARDS='llm:8000'")
    }

    def 'Host access is off unless asked for, and oillamp refuses to turn it on without requirements'() {
        reportInfo """
            Host access gives the agent tools that reach the user's computer; a checker, a
            separate model call, judges each request against the requirements, the user's own
            words, in `host_access.requirements`. Reaching the host is off unless a lamp says
            otherwise, so a lamp that never mentions
            it behaves exactly as it did before the feature existed. Turning it on without saying
            what the agent may not do would leave the checker with nothing to judge by, and a
            checker with nothing to judge by says yes. So oillamp refuses that, and names the key
            to fill in, rather than start a lamp whose checker allows everything. It refuses a
            relative path in `host_access.roots`, the folders the agent may reach, as well.

            The checker's model is not one of those keys. Without one, requests are judged by the
            model the agent answers with, which is known only once a session starts with one.
        """
        given: 'a lamp that turns host access on, writes no requirements, and gives a relative root'
            var lamp = host.lampPath()
            host.givenConfig(lamp, """
                schema_version = 1

                [host_access]
                enabled = true
                requirements = ""
                roots = ["relative/path"]
            """.stripIndent())

        when: 'the user checks the configuration'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'the missing text and the relative path are both named'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().size() == 2
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('host_access.requirements')
            outcome.console().contains('host_access.roots')
            !outcome.console().contains('host_access.checker_model')

        and: 'a file without the table is simply off'
            host.givenConfig(lamp, 'schema_version = 1' + System.lineSeparator())
            host.oillamp.run('config', lamp.toString(), 'check').status() == LampExitStatus.SUCCESS
    }

    def 'The requirements are one piece of text, written over several lines'() {
        reportInfo """
            With host access on, a checker, a separate model call, judges each request the agent
            makes of the user's computer against the requirements in `host_access.requirements`.
            The requirements are the user's own words, not a checklist, so they are a single
            multi-line string. Both TOML spellings work: the literal one, which keeps a backslash
            as a backslash, is the one to use for paths and regular expressions.
        """
        given:
            var lamp = host.lampPath()
            host.givenConfig(lamp, """
                schema_version = 1

                [host_access]
                enabled = true
                requirements = '''
                You may read ~/Documents.
                Never read ~/.ssh, nor anything under ~/\\.gnupg.
                '''
            """.stripIndent())

        when:
            var outcome = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'the checker would be given both lines, backslash and all'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.console().contains('You may read ~/Documents.')
            outcome.console().contains('Never read ~/.ssh, nor anything under ~/\\.gnupg.')

        and: 'with no checker model of its own, the lamp judges with the agent\'s'
            outcome.console().contains("checker the agent's model")
    }

    def 'Oillamp lets the agent run commands on the host only when host_access.commands says "ask" or "checker"'() {
        reportInfo """
            With host access on, the agent may reach the user's computer through tools that a
            checker, a separate model call, judges first. One of them, `host_bash`, runs a command
            there, which can do anything the user can. Whether it exists in a lamp, and who allows
            each command, is the setting `host_access.commands`, one of three words:

            - `"off"`, the default: there is no `host_bash`.
            - `"ask"`: the checker judges each command, and then the user is asked before it runs.
            - `"checker"`: the checker's allow is enough.

            Any other word is refused with `OIL-CONFIG-004`, naming the key and the three words:
            a typo must not open the way to running commands.

            The setting replaced `host_access.executables`, a list of programs. Every lamp made
            before then has `executables = []` in its file, written there by oillamp's template,
            and an empty list meant no programs, which is what `"off"` says. So that line is
            accepted, and such a lamp starts as it did. A list that names programs is refused with
            `OIL-CONFIG-004`, naming `host_access.commands`: `"ask"` or `"checker"` would let the
            agent run any command, not only the programs listed, so oillamp does not choose one
            on the user's behalf.

            The scenario checks a lamp's file with `oillamp config <dir> check`, and reads what a
            valid one means with `oillamp config <dir> show-effective`.
        """
        given: 'a lamp whose file sets host_access.commands'
            var lamp = host.lampPath()
            host.givenConfig(lamp, """
                schema_version = 1

                [host_access]
                enabled = true
                requirements = "Build ~/project, and nothing else."
                ${setting}
            """.stripIndent())

        when: 'the user checks the file, and asks what it means'
            var checked = host.oillamp.run('config', lamp.toString(), 'check')
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'a valid setting passes, and says who allows a command'
            (checked.status() == LampExitStatus.SUCCESS) == (code == null)
            code == null ? shown.console().contains(means) : checked.reported(code)
            code == null || checked.console().contains(means)

        where:
            setting                         | code             | means
            ''                              | null             | 'no commands'
            'commands = "off"'              | null             | 'no commands'
            'commands = "ask"'              | null             | 'commands, each one asked of you first'
            'commands = "checker"'          | null             | 'commands, judged by the checker alone'
            'commands = "always"'           | 'OIL-CONFIG-004' | 'host_access.commands'
            'executables = []'              | null             | 'no commands'
            'executables = ["inkscape"]'    | 'OIL-CONFIG-004' | 'host_access.commands'
    }

    def 'Oillamp #verdict schedule.max_delay_minutes = #value'() {
        reportInfo """
            `schedule.max_delay_minutes` says how late a job's run may start. A running session
            checks the schedule as it starts, every 30 seconds after that, and whenever someone
            changes the schedule. When it sees a job whose time lies more than this many minutes
            in the past, oillamp does not wake the agent for it. The default is 30. 0 turns the
            limit off, as 0 does for `recording.max_age_days` and `recording.max_total_gb`.

            The largest value is 10080, the minutes of one week, the same upper bound
            `schedule.min_agent_interval_minutes` has. Oillamp measures how late a run is from the
            first time the job was due and did not run. With a limit of, say, ten days, a weekly
            job due on a Monday and seen by a session on the Wednesday of the week after would
            still run, nine days late, although its next Monday has passed too: the agent would do
            last week's work. Up to a week, a run is never later than one period of a weekly job.
            A negative number of minutes has no meaning.

            The scenario checks the edges with `oillamp config <dir> check`: -1 and 10081 are
            refused with `OIL-CONFIG-004`, naming the key and what it expects; 0 and 10080 pass.
        """
        given: 'a lamp whose file sets schedule.max_delay_minutes'
            var lamp = host.lampPath()
            host.givenConfig(lamp, "schema_version = 1\n[schedule]\nmax_delay_minutes = ${value}\n")

        when: 'the user checks the file'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'a value from 0 to 10080 passes, and any other is refused, naming the key and the range'
            (outcome.status() == LampExitStatus.SUCCESS) == accepted
            outcome.reported('OIL-CONFIG-004') == !accepted
            !accepted == outcome.console().contains('schedule.max_delay_minutes')
            !accepted == outcome.console().contains('a number of minutes from 0 to 10080, 0 for no limit')

        where:
            value  || accepted
            -1     || false
            0      || true
            10080  || true
            10081  || false

            verdict = accepted ? 'accepts' : 'refuses'
    }
}
