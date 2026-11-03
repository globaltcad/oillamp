package oillamp

import dev.lamp.Lamp
import dev.lamp.LampExitStatus
import dev.lamp.LampWallpaper
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@Title('Configuring a lamp')
@Narrative('''
Each lamp has a settings file, `oillamp.toml`, in its directory. It says, for example, how big the
sandbox's desktop is, whether the screen is recorded, which wallpaper the desktop shows and, most
importantly, where the agent may connect to: the lamp's network policy. Settings for every lamp of
a user can also go into `~/.config/oillamp/config.toml`.

Because the file is the sandbox's policy, oillamp does not quietly ignore a mistake in it. It
refuses the file, with a problem code for the kind of mistake:

- `OIL-CONFIG-001`: the file is not valid TOML;
- `OIL-CONFIG-002`: a key that oillamp does not know;
- `OIL-CONFIG-004`: a value that is not allowed for its key.

`oillamp config <dir> check` checks the settings without starting anything, and
`oillamp config <dir> show-effective` prints what the merged settings mean.
''')
@Subject(OilLamp)
class Configuring_A_Lamp_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def '`oillamp config check` reports every mistake in `oillamp.toml` at once, and names the key of each'() {
        reportInfo """
            Let's consider a settings file with three mistakes in it. If oillamp stopped at the
            first, the user would fix it, run oillamp again, meet the second, and so on. So
            oillamp checks the whole file and reports every mistake together, each with the path
            of the key, the value written there and what was expected, so that the user can fix
            the file in one go.

            The file here has a misspelt key, `display.heigth`; a network range that is not a
            valid one, `10.0.0/8`; and two forwards on the same port, 8000. A forward is a fixed
            tunnel from a port inside the sandbox to one address on the network.
        """
        given: 'an `oillamp.toml` with a misspelt key, a malformed network range, and two forwards on the same port'
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

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it exits with the usage status, because the mistake is in the configuration, not on the machine'
            outcome.status() == LampExitStatus.USAGE

        and: 'it reports three errors: an unknown key, `OIL-CONFIG-002`, and bad values, `OIL-CONFIG-004`'
            outcome.errors().size() == 3
            outcome.reported('OIL-CONFIG-002')      // unknown key
            outcome.reported('OIL-CONFIG-004')      // bad value

        and: 'it names the misspelt key `display.heigth`, and suggests `height`'
            outcome.console().contains('display.heigth')
            outcome.console().contains("did you mean 'height'?")

        and: 'it locates the malformed range down to the array element, `network.rules[0].cidrs`'
            outcome.console().contains('network.rules[0].cidrs')
            outcome.console().contains('10.0.0/8')

        and: 'it says that the port of the second forward, `network.forwards[1]`, is already used by "llm"'
            outcome.console().contains('network.forwards[1]')
            outcome.console().contains('already used by "llm"')
    }

    def '`oillamp config check` refuses an unknown key in `oillamp.toml` rather than ignoring it, and suggests the nearest known one'() {
        reportInfo """
            Refusing every unknown key looks strict, and it is on purpose. Let's consider a user
            who wants the network to refuse every connection that no rule allows, and writes
            `default = "deny"` into a table `[netwrok]` instead of `[network]`. If oillamp ignored
            the unknown table, the lamp would go on allowing those connections, and nothing would
            point out the mistake.

            oillamp cannot tell a harmless typo from a dangerous one, so it refuses every unknown
            key with `OIL-CONFIG-002`. An unknown key is almost always a typo, so the message
            suggests the known key closest to it.
        """
        given: 'an `oillamp.toml` that sets `default = "deny"` in a misspelt table `[netwrok]`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [netwrok]
                default = "deny"
            '''.stripIndent())

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it refuses the key with `OIL-CONFIG-002`, and asks whether `network` was meant'
            outcome.reported('OIL-CONFIG-002')
            outcome.console().contains("did you mean 'network'?")
    }

    def '`oillamp config check` refuses an `oillamp.toml` that does not say which schema version it was written for'() {
        reportInfo """
            Every `oillamp.toml` begins with `schema_version = 1`. With that number, a future
            oillamp can recognise a file written for an older format and convert it, instead of
            guessing what it means. It costs the user one line, and it lets the format change
            later without breaking anyone's lamp. So oillamp refuses a file without it, with
            `OIL-CONFIG-004`, naming `schema_version`.
        """
        given: 'an `oillamp.toml` without `schema_version`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                [display]
                width = 1920
            '''.stripIndent())

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it reports `OIL-CONFIG-004`, naming `schema_version`'
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('schema_version')
    }

    def '`oillamp config check` reports an empty `oillamp.toml` as missing its `schema_version`, not as an internal error'() {
        reportInfo """
            An empty file is what someone gets who creates `oillamp.toml` with `touch`. It lacks
            `schema_version`, like any other file without that line, so oillamp reports
            `OIL-CONFIG-004` for it, and not `OIL-INTERNAL-001`, the code with which oillamp
            reports a bug in itself.
        """
        given: 'an empty `oillamp.toml`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '')

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it reports `OIL-CONFIG-004`, and not `OIL-INTERNAL-001`'
            outcome.reported('OIL-CONFIG-004')
            !outcome.reported('OIL-INTERNAL-001')
    }

    def '`oillamp config check` refuses a forward on port #port, which the sandbox already uses'() {
        reportInfo """
            A forward is a fixed tunnel from a port on 127.0.0.1 inside the sandbox to one address
            on the network. Two of those ports are taken by oillamp itself: 3128 is the egress
            proxy, through which all other outbound traffic goes and which checks it against the
            lamp's network rules, and 3129 is where the agent's tools reach the model service
            through oillamp. A forward on either port would fail to start or, worse, receive that
            port's connections in its place. Traffic the user believes is checked against their
            rules would then not be, or model requests would go somewhere else.

            So oillamp refuses such a forward with `OIL-CONFIG-004`, and says what the port is
            used for, so that the user knows which forward to move.
        """
        given: 'an `oillamp.toml` with a forward on the port'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [[network.forwards]]
                name   = "llm"
                port   = ${port}
                target = "llm.corp.example.com:8000"
            '''.stripIndent().replace('${port}', port.toString()))

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it reports `OIL-CONFIG-004`, and says what the sandbox uses the port for'
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains(explanation)

        where:
            port | explanation
            3128 | 'egress proxy'
            3129 | 'model service through oillamp'
    }

    def '`oillamp config check` refuses an `llm.forward` that names a forward that does not exist'() {
        reportInfo """
            The table `[llm]` describes the team's own language model, and `llm.forward` names
            the entry of `[[network.forwards]]` that leads to it. (A forward is a fixed tunnel from
            a port inside the sandbox to one address on the network.) A typo in that name would
            otherwise show up much later: the agent's tools could not reach any model, and the
            user would have no idea why. So oillamp refuses it with `OIL-CONFIG-004`, and the
            message lists the forwards that do exist.
        """
        given: 'an `oillamp.toml` with one forward, "llm", and an `llm.forward` that names "language-model"'
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

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it reports `OIL-CONFIG-004` for `llm.forward`, and lists the forward "llm"'
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('llm.forward')
            outcome.console().contains('"llm"')
    }

    def 'The commented `oillamp.toml` of a new lamp means oillamp\'s built-in defaults'() {
        reportInfo """
            A new lamp gets an `oillamp.toml` in which the settings are written out with their
            default values and with comments. Most users read this file as the documentation of
            the settings. If it described other values than those oillamp uses when a setting is
            missing, it would be worse than no documentation: confidently wrong.

            The scenario creates a lamp, leaves its file untouched, and checks with
            `oillamp config <dir> show-effective` that the file means the built-in defaults: a
            1920x1080 desktop, GPU `auto`, a clipboard that goes from the user to the agent only,
            16g of memory, and the shipped network policy. That policy allows every connection
            except those its two rules refuse: Eden AI outside its EU endpoint, and the private
            and internal network ranges.
        """
        given: 'a new lamp, whose `oillamp.toml` is untouched'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())

        when: '`oillamp config <dir> show-effective` prints the effective settings'
            var outcome = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'they are the built-in defaults: a 1920x1080 desktop, GPU `auto`, the clipboard going to the agent, 16g of memory'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.console().contains('1920x1080')
            outcome.console().contains('gpu auto')
            outcome.console().contains('clipboard to-agent')
            outcome.console().contains('memory 16g')

        and: 'with the shipped network policy: every connection is allowed, except those its 2 rules refuse'
            outcome.console().contains('default allow')
            outcome.console().contains('2 rule')

        and: 'only the user may read and write `oillamp.toml` (mode `rw-------`), because it is the sandbox\'s policy'
            java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(lamp.resolve('oillamp.toml'))) == 'rw-------'
    }

    def 'Settings in `~/.config/oillamp/config.toml` apply to every lamp, and a lamp\'s `oillamp.toml` overrides them'() {
        reportInfo """
            Settings in the optional file `~/.config/oillamp/config.toml` apply to every lamp of
            this user. The file exists so that, for example, a company can give its developers the
            address of its language-model service once, instead of writing it into every lamp.

            oillamp merges three sources of settings, and a later one wins over an earlier one:
            its built-in defaults, then `~/.config/oillamp/config.toml`, then the lamp's own
            `oillamp.toml`.

            Tables are merged key by key, but an array replaces the earlier one as a whole. If a
            lamp's `network.rules` were added to those of the user-wide file, the lamp's policy
            would be a combination that nobody wrote down in one place. A lamp that lists rules
            gets exactly those rules.
        """
        given: 'a user-wide file that sets the desktop to 2560x1440, and has a rule allowing a company mirror'
            host.givenGlobalConfig('''
                [display]
                width  = 2560
                height = 1440

                [[network.rules]]
                label  = "company mirror"
                action = "allow"
                hosts  = ["nexus.corp.example.com"]
            '''.stripIndent())

        and: 'a lamp that sets only the width, to 3840, and lists one rule of its own'
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

        when: '`oillamp at <dir> --dry-run` prints the settings the session would use'
            var outcome = host.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'the lamp\'s width wins, and the height still comes from the user-wide file'
            outcome.console().contains('3840x1440')

        and: 'the lamp has 1 rule: its own rule replaced the user-wide one, rather than being added to it'
            outcome.console().contains('1 rule')
    }

    def '`oillamp config check` reports an `oillamp.toml` that is not valid TOML with a problem code of its own'() {
        reportInfo """
            Before oillamp can check any setting, the file has to be valid TOML. oillamp reports a
            syntax error, such as a table header without its closing bracket, with a code of its
            own, `OIL-CONFIG-001`. The user can then tell at once that a bracket or a quote is
            wrong, and not the name of a key.
        """
        given: 'an `oillamp.toml` whose `[display` header lacks its closing bracket'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [display
                width = 1920
            '''.stripIndent())

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it reports `OIL-CONFIG-001`, and exits with the usage status'
            outcome.reported('OIL-CONFIG-001')
            outcome.status() == LampExitStatus.USAGE
    }

    def '`timeouts.container_ready_seconds` sets how long `oillamp at` waits for the sandbox, and it waits twice as long after building the image'() {
        reportInfo """
            `timeouts.container_ready_seconds` is how long oillamp waits for the sandbox's desktop
            and shell to come up before it gives up. A slow machine needs more time. The first
            start straight after the sandbox image was built is slower still, because nothing is
            cached yet, so oillamp waits twice as long then.

            The scenario sets 90 seconds, and reads the wait from the plan that
            `oillamp at --dry-run` prints: once while the image still has to be built, and once
            when it exists.
        """
        given: 'an `oillamp.toml` with `container_ready_seconds = 90`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [timeouts]
                container_ready_seconds = 90
            '''.stripIndent())

        when: '`oillamp at <dir> --dry-run` runs while the image still has to be built'
            var afterABuild = host.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'the plan waits up to 180 seconds for the sandbox'
            afterABuild.steps().any { it.contains('ready (up to 180s)') }

        when: 'the image exists, and `oillamp at <dir> --dry-run` runs again'
            host.machine { it.commandSucceeding('podman image exists', '') }
            var withTheImage = host.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'the plan waits up to 90 seconds'
            withTheImage.steps().any { it.contains('ready (up to 90s)') }
    }

    def 'Windows on the sandbox\'s desktop float, unless `display.windows` asks for tiling'() {
        reportInfo """
            `display.windows` decides how the sandbox's desktop arranges its windows.
            `"floating"`, the default, works as on most desktops: a window opens at its own size,
            moves by its title bar and resizes by its edges. `"tiling"` divides the screen among
            the windows, so that none covers another.

            oillamp passes the setting to the sandbox in `.oillamp/session/runtime.env`, the file
            of environment variables that a session starts with. The sandbox turns the word into
            configuration of its compositor, the program that draws the windows, so oillamp
            accepts only these two words, and refuses any other with `OIL-CONFIG-004`.
        """
        given: 'an `oillamp.toml` with `windows = "tiling"`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [display]
                windows = "tiling"
            '''.stripIndent())

        when: '`oillamp at <dir>` starts a session'
            host.oillamp.run('at', lamp.toString())
            var runtimeEnv = java.nio.file.Files.readString(lamp.resolve('.oillamp/session/runtime.env'))

        then: '`runtime.env` tells the sandbox to tile'
            runtimeEnv.contains("OILLAMP_WINDOWS='tiling'")

        when: '`oillamp.toml` says nothing about windows'
            host.givenConfig(lamp, 'schema_version = 1\n')
            var effective = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: '`oillamp config <dir> show-effective` says that the windows float'
            effective.console().contains('floating windows')

        when: '`oillamp.toml` sets `windows = "stacking"`, and `oillamp config <dir> check` runs'
            host.givenConfig(lamp, '''
                schema_version = 1

                [display]
                windows = "stacking"
            '''.stripIndent())
            var checked = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it refuses the value with `OIL-CONFIG-004`, and names the two values that work'
            checked.status() == LampExitStatus.USAGE
            checked.reported('OIL-CONFIG-004')
            checked.console().contains('"floating" or "tiling"')
    }

    def '`oillamp at --wallpaper` chooses a new lamp\'s wallpaper, and the lamp keeps it in its `oillamp.toml`'() {
        reportInfo """
            Let's consider a user who wants a quieter desktop than the default wallpaper, `lamp`,
            which shows the lamp on a panel. There are four wallpapers: `lamp`; `waves`, the
            inside of the lamp with its name carved into the wall, which has no colour of its
            own; and `waves-light` and `waves-dark`, the same picture on a light and on a dark
            colour.

            `oillamp at <dir> --wallpaper <name>` chooses one when it creates a lamp. oillamp
            writes the choice into the new `oillamp.toml` as `display.wallpaper`, so the lamp
            keeps it, and passes it to the sandbox in `runtime.env`. Once the file exists, the
            file decides: `--wallpaper` does not change the wallpaper of an existing lamp, and
            oillamp says so.

            The sandbox turns the name into configuration of its compositor, so only these four
            names are accepted, on the command line and in the file.
        """
        given: 'a lamp path where no lamp exists yet'
            var lamp = host.lampPath()

        when: '`oillamp at <dir> --wallpaper waves-dark` creates the lamp'
            var made = host.oillamp.run('at', lamp.toString(), '--wallpaper', 'waves-dark')
            var settings = Files.readString(lamp.resolve('oillamp.toml'))
            var runtimeEnv = Files.readString(lamp.resolve('.oillamp/session/runtime.env'))

        then: 'the first session already shows `waves-dark`'
            made.status() == LampExitStatus.SUCCESS
            runtimeEnv.contains("OILLAMP_WALLPAPER='waves-dark'")

        and: 'the new `oillamp.toml` sets `wallpaper = "waves-dark"`, and its comment names the other wallpapers'
            settings.contains('wallpaper = "waves-dark"')
            settings.contains('"waves-light"')
            settings.contains('"waves"')

        when: '`oillamp at <dir>` starts the lamp again, without `--wallpaper`'
            host.oillamp.run('at', lamp.toString())

        then: 'the lamp still shows `waves-dark`'
            Files.readString(lamp.resolve('.oillamp/session/runtime.env')).contains("OILLAMP_WALLPAPER='waves-dark'")

        when: '`oillamp at <dir> --wallpaper waves-light` runs on the existing lamp'
            var again = host.oillamp.run('at', lamp.toString(), '--wallpaper', 'waves-light')

        then: '`oillamp.toml` is unchanged and still decides, and oillamp says that `--wallpaper` only chooses the wallpaper of a new lamp'
            Files.readString(lamp.resolve('oillamp.toml')) == settings
            Files.readString(lamp.resolve('.oillamp/session/runtime.env')).contains("OILLAMP_WALLPAPER='waves-dark'")
            again.console().contains('--wallpaper only chooses the wallpaper of a new lamp')

        when: 'another lamp is created without `--wallpaper`'
            var plain = host.lampPath('plain')
            host.oillamp.run('at', plain.toString())

        then: 'it shows the default wallpaper, `lamp`'
            Files.readString(plain.resolve('oillamp.toml')).contains('wallpaper = "lamp"')
            Files.readString(plain.resolve('.oillamp/session/runtime.env')).contains("OILLAMP_WALLPAPER='lamp'")

        when: '`oillamp at --wallpaper beach` is asked to create a lamp'
            var wrong = host.oillamp.run('at', host.lampPath('beach').toString(), '--wallpaper', 'beach')

        then: 'it refuses with a usage error that names the four wallpapers'
            wrong.status() == LampExitStatus.USAGE
            wrong.console().contains('"lamp", "waves", "waves-light" or "waves-dark"')

        when: '`oillamp.toml` sets `wallpaper = "beach"`, and `oillamp config <dir> check` runs'
            host.givenConfig(lamp, '''
                schema_version = 1

                [display]
                wallpaper = "beach"
            '''.stripIndent())
            var checked = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it refuses the value with `OIL-CONFIG-004`, and names the four wallpapers'
            checked.status() == LampExitStatus.USAGE
            checked.reported('OIL-CONFIG-004')
            checked.console().contains('"lamp", "waves", "waves-light" or "waves-dark"')
    }

    def '`oillamp at --background` chooses the colour that a new lamp\'s wallpaper is drawn on'() {
        reportInfo """
            Let's consider a user whose own desktop is deep blue. The `waves` wallpaper has no
            colour of its own, only light and shadow that fade out towards the edges, so it takes
            on the colour it is drawn on. `oillamp at <dir> --background <colour>` chooses that
            colour when it creates a lamp, and writes it into the new `oillamp.toml` as
            `display.background`, as `--wallpaper` does with the wallpaper. Without it,
            `display.background` stays empty, and each wallpaper is drawn on a colour of its own,
            such as `#15121c` for `waves-dark`.

            The colour ends up in the configuration of the sandbox's compositor, so oillamp accepts
            only `#` followed by six hexadecimal digits, on the command line and in the file.
            Anything more could add configuration of its own, such as a command to run.
        """
        given: 'a lamp path where no lamp exists yet'
            var lamp = host.lampPath()

        when: '`oillamp at <dir> --wallpaper waves --background #1b2a44` creates the lamp'
            var made = host.oillamp.run('at', lamp.toString(), '--wallpaper', 'waves', '--background', '#1b2a44')
            var settings = Files.readString(lamp.resolve('oillamp.toml'))

        then: 'the first session draws the wallpaper on `#1b2a44`, and the new `oillamp.toml` keeps that colour'
            made.status() == LampExitStatus.SUCCESS
            Files.readString(lamp.resolve('.oillamp/session/runtime.env')).contains("OILLAMP_BACKGROUND='#1b2a44'")
            settings.contains('background = "#1b2a44"')

        when: '`oillamp at <dir> --background #ffffff` runs on the existing lamp'
            var again = host.oillamp.run('at', lamp.toString(), '--background', '#ffffff')

        then: '`oillamp.toml` is unchanged and still decides, and oillamp says that `--background` only chooses the colour of a new lamp'
            Files.readString(lamp.resolve('oillamp.toml')) == settings
            Files.readString(lamp.resolve('.oillamp/session/runtime.env')).contains("OILLAMP_BACKGROUND='#1b2a44'")
            again.console().contains('--background only chooses the background colour of a new lamp')

        when: 'another lamp is created with `--wallpaper waves-dark` and no colour'
            var dark = host.lampPath('dark')
            host.oillamp.run('at', dark.toString(), '--wallpaper', 'waves-dark')

        then: 'its `oillamp.toml` leaves `background` empty, and the wallpaper is drawn on its own dark colour, `#15121c`'
            Files.readString(dark.resolve('oillamp.toml')).contains('background = ""')
            Files.readString(dark.resolve('.oillamp/session/runtime.env')).contains("OILLAMP_BACKGROUND='#15121c'")

        when: '`oillamp at --background navy` is asked to create a lamp'
            var wrong = host.oillamp.run('at', host.lampPath('named').toString(), '--background', 'navy')

        then: 'it refuses with a usage error that shows what a colour looks like'
            wrong.status() == LampExitStatus.USAGE
            wrong.console().contains('--background "navy": expected a colour such as "#15121c"')

        when: '`oillamp.toml` sets a `background` with a command appended to the colour, and `oillamp config <dir> check` runs'
            host.givenConfig(lamp, '''
                schema_version = 1

                [display]
                background = "#123; exec foot"
            '''.stripIndent())
            var checked = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it refuses the value, and shows what a colour looks like'
            checked.status() == LampExitStatus.USAGE
            checked.console().contains('a colour such as "#15121c"')
    }

    def 'An application gives the lamp it creates a wallpaper and a background colour, as `--wallpaper` and `--background` would'() {
        reportInfo """
            Let's consider an application built on oillamp's Java API, `dev.lamp`, that lets its
            user choose the look of the sandbox's desktop when they create a lamp. The application
            calls `LampDirectory.wallpaper` and `LampDirectory.background`, and the lamp passes the
            choices on as `--wallpaper` and `--background` to the engine, the oillamp process that
            the application starts for the lamp. The new lamp gets them exactly as a lamp created
            in a terminal would: in its `oillamp.toml`, and from its first session on.

            `LampDirectory.background` checks the colour at once, and throws an
            `IllegalArgumentException` for anything that is not `#` and six hexadecimal digits.
        """
        given: 'a lamp path where no lamp exists yet'
            var lamp = host.lampPath()

        when: 'the application creates the lamp with `LampWallpaper.WAVES_LIGHT` and starts it'
            var running = Lamp.at(lamp).wallpaper(LampWallpaper.WAVES_LIGHT).launchedBy(host.launcher).start()
            var up = running.awaitRunning(Duration.ofSeconds(30))
            var runtimeEnv = Files.readString(lamp.resolve('.oillamp/session/runtime.env'))
            running.close()

        then: 'the engine was started with `--wallpaper waves-light`'
            up
            host.engines.first().arguments == ['at', lamp.toString(), '--embedded', '--wallpaper', 'waves-light']

        and: 'the first session shows `waves-light`, and the new `oillamp.toml` keeps it'
            runtimeEnv.contains("OILLAMP_WALLPAPER='waves-light'")
            Files.readString(lamp.resolve('oillamp.toml')).contains('wallpaper = "waves-light"')

        when: 'the application creates another lamp with `LampWallpaper.WAVES` on its own surface colour, `#202833`'
            var other = host.lampPath('other')
            var second = Lamp.at(other).wallpaper(LampWallpaper.WAVES).background('#202833').launchedBy(host.launcher).start()
            second.awaitRunning(Duration.ofSeconds(30))
            second.close()

        then: 'the engine was started with `--background #202833` as well, and the new `oillamp.toml` keeps the colour'
            host.engines.last().arguments == ['at', other.toString(), '--embedded', '--wallpaper', 'waves', '--background', '#202833']
            Files.readString(other.resolve('oillamp.toml')).contains('background = "#202833"')

        when: 'the application gives `LampDirectory.background` the word "navy"'
            Lamp.at(other).background('navy')

        then: 'it throws an `IllegalArgumentException`'
            thrown(IllegalArgumentException)
    }

    def 'oillamp keeps the sandbox on Eden AI\'s EU endpoint, whatever the host\'s environment says'() {
        reportInfo """
            Let's consider a team whose requests to language models must stay in the EU. Inside
            the sandbox, pi and opencode are set up to use Eden AI's EU endpoint and nothing else.
            Two things could undo that: the user's own `EDENAI_BASE_URL`, if oillamp copied it
            into the sandbox, and a tool in the sandbox that ignores these settings. So oillamp
            does not copy `EDENAI_BASE_URL` from the host, and the network policy it writes into
            a new lamp's `oillamp.toml` refuses the global endpoint, `api.edenai.run`, by name.

            The key, `EDENAI_API_KEY`, is not copied either. oillamp keeps it on the host and adds
            it to each model request on the way out, so there is no key in the sandbox that a tool
            could take elsewhere.

            The agent's guide `~/AGENTS.md` tells the agent about this, so that it does not look
            for a way around it. When a lamp's own rules leave out the rule for `api.edenai.run`,
            the guide no longer claims that the policy refuses it.
        """
        given: 'a host whose environment holds an Eden AI key, and points `EDENAI_BASE_URL` at the global endpoint'
            host.machine {
                it.environmentVariable('EDENAI_API_KEY', 'a-key')
                  .environmentVariable('EDENAI_BASE_URL', 'https://api.edenai.run/v3')
                  .environmentVariable('EDENAI_EU_ONLY', '')
            }
            var lamp = host.lampPath()

        when: '`oillamp at <dir>` creates a lamp and starts its session'
            host.oillamp.run('at', lamp.toString())
            var runtimeEnv = Files.readString(lamp.resolve('.oillamp/session/runtime.env'))
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            var guide = Files.readString(agentHome.resolve('AGENTS.md'))

        then: 'neither the key nor `EDENAI_BASE_URL` is in the sandbox\'s `runtime.env`'
            !runtimeEnv.contains('a-key')
            !runtimeEnv.contains('EDENAI_API_KEY')
            !runtimeEnv.contains('EDENAI_BASE_URL')

        and: 'the new `oillamp.toml` has a rule that refuses `api.edenai.run`, which the user can see and remove'
            var toml = Files.readString(lamp.resolve('oillamp.toml'))
            toml.contains('label  = "Eden AI only through its EU endpoint"')
            toml.contains('hosts  = ["api.edenai.run"]')

        and: '`~/AGENTS.md` tells the agent that models are reached through oillamp, that only EU models are offered, and that `api.edenai.run` is refused'
            guide.contains('through oillamp')
            guide.contains('Only models served in the EU are offered')
            guide.contains('`api.edenai.run`, is refused by the network policy')

        when: 'the lamp\'s `oillamp.toml` lists its own rules without that one, and `oillamp at <dir>` starts the session again'
            host.givenConfig(lamp, '''
                schema_version = 1

                [[network.rules]]
                label  = "block private, internal and loopback ranges"
                action = "deny"
                cidrs  = ["10.0.0.0/8"]
            '''.stripIndent())
            host.oillamp.run('at', lamp.toString())
            guide = Files.readString(agentHome.resolve('AGENTS.md'))

        then: 'the guide still says that only EU models are offered, but no longer that the policy refuses `api.edenai.run`'
            guide.contains('Only models served in the EU are offered')
            !guide.contains('is refused by the network policy')
    }

    def '`oillamp config check` checks the same merged settings that a session would use'() {
        reportInfo """
            A session merges the user-wide file `~/.config/oillamp/config.toml` with the lamp's
            own `oillamp.toml`. So `oillamp config <dir> check` checks both. If it read only the
            lamp's file, it could call a configuration valid that `oillamp at` then refuses.
        """
        given: 'a valid `oillamp.toml`, and a user-wide file that sets a desktop width of 10 pixels'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')
            host.givenGlobalConfig('''
                [display]
                width = 10
            '''.stripIndent())

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it reports `OIL-CONFIG-004`, and locates the mistake in `~/.config/oillamp/config.toml`'
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('.config/oillamp/config.toml')
    }

    def 'A value in the sandbox\'s `runtime.env` stays a value, whatever characters it holds'() {
        reportInfo """
            `.oillamp/session/runtime.env` holds the environment variables of a session. The
            container's first process reads it with `source`, as container root, and so does every
            shell of the agent. A value that closed its quotes could therefore run a command. The
            values come from, among other places, the name of the lamp's directory and the host's
            environment. So the scenario tries both with quotes, `\$(...)`, backticks and spaces,
            and then has bash `source` the file the way the container's first process does.
        """
        given: 'a lamp directory whose name, and a host variable `EDENAI_MAX_TOKENS` whose value, both contain shell code'
            var marker = tmp.resolve('ran')
            var hostile = "it's \$(touch ran)"
            var key = "k'\$(touch ran)\"`touch ran`"
            host.machine { it.environmentVariable('EDENAI_MAX_TOKENS', key) }
            var lamp = host.lampPath(hostile)

        when: '`oillamp at <dir>` writes `runtime.env`, and bash sources it'
            host.oillamp.run('at', lamp.toString())
            var sourced = new ProcessBuilder('bash', '-c',
                    'set -a; . "$1"; set +a; printf "%s\\n%s\\n" "$OILLAMP_LAMP_NAME" "$EDENAI_MAX_TOKENS"',
                    'bash', lamp.resolve('.oillamp/session/runtime.env').toString())
                    .directory(tmp.toFile()).redirectErrorStream(true).start()
            var values = sourced.inputStream.text.readLines()

        then: 'bash reads back exactly the values oillamp was given'
            sourced.waitFor() == 0
            values == [hostile, key]

        and: 'none of the commands in them ran'
            !Files.exists(marker)
    }

    def '`oillamp at` refuses a host value with a line break, rather than write it into `runtime.env`'() {
        reportInfo """
            The container's first process and every shell of the agent read the session's
            environment variables from `.oillamp/session/runtime.env` with `source`. Quoting
            cannot make a line break safe in a file that the shell reads line by line, and no real
            setting contains one. So when a variable from the host's environment holds a line
            break, `oillamp at` refuses to start the session. Its message names the variable, but
            does not show the value.
        """
        given: 'a host variable `EDENAI_MAX_TOKENS` whose value holds a line break and a second command'
            host.machine { it.environmentVariable('EDENAI_MAX_TOKENS', 'first-line\ntouch /tmp/pwned') }
            var lamp = host.lampPath()

        when: '`oillamp at <dir>` runs'
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'the session does not start, and the message names `EDENAI_MAX_TOKENS` and the line break'
            outcome.status() != LampExitStatus.SUCCESS
            outcome.console().contains('EDENAI_MAX_TOKENS')
            outcome.console().contains('line break')

        and: 'the message does not show the value'
            !outcome.console().contains('first-line')

        and: 'no `runtime.env` was written'
            !Files.exists(lamp.resolve('.oillamp/session/runtime.env'))
    }

    def 'The sandbox learns the address of the team\'s language model from the forward that `llm.forward` names'() {
        reportInfo """
            The table `[llm]` describes the team's own language model. `llm.forward` names the
            forward that leads to it, a fixed tunnel from a port inside the sandbox to the model's
            server. In `runtime.env`, the sandbox gets the model's address as it looks from inside
            the sandbox: 127.0.0.1, the forward's port, and `llm.base_path`. It also gets the list
            of models and the provider name, so that the agent's tools can be pointed at the
            model.
        """
        given: 'a forward "llm" from port 8000 to the model\'s server, and an `[llm]` table that names it'
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

        when: '`oillamp at <dir>` starts the session'
            host.oillamp.run('at', lamp.toString())
            var runtimeEnv = Files.readString(lamp.resolve('.oillamp/session/runtime.env'))

        then: '`runtime.env` holds the model\'s address inside the sandbox, the models, the provider name and the forward'
            runtimeEnv.contains("OILLAMP_LLM_BASE_URL='http://127.0.0.1:8000/v1'")
            runtimeEnv.contains("OILLAMP_LLM_MODELS='coder-large,coder-small'")
            runtimeEnv.contains("OILLAMP_LLM_PROVIDER='corp'")
            runtimeEnv.contains("OILLAMP_FORWARDS='llm:8000'")
    }

    def 'Host access is off unless a lamp turns it on, and `oillamp config check` refuses to turn it on without requirements'() {
        reportInfo """
            Host access gives the agent tools that reach the user's computer. A checker, a
            separate call to a model, judges each request against the requirements in
            `host_access.requirements`: the user's own words for what the agent may and may not do
            there. Host access is off unless a lamp turns it on, so a lamp whose file does not
            mention it works as it did before the feature existed.

            Turning host access on with empty requirements would leave the checker with nothing
            to judge by, and a checker with nothing to judge by says yes. So oillamp refuses that
            with `OIL-CONFIG-004`, naming `host_access.requirements`, rather than start a lamp
            whose checker allows everything. It also refuses a relative path in
            `host_access.roots`, the folders the agent may reach.

            `host_access.checker_model` is not required. Without it, the checker uses the model
            the agent answers with, which is only known once a session starts with one.
        """
        given: 'a lamp that turns host access on, with empty requirements and a relative path in `roots`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, """
                schema_version = 1

                [host_access]
                enabled = true
                requirements = ""
                roots = ["relative/path"]
            """.stripIndent())

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it reports two `OIL-CONFIG-004` errors, for `host_access.requirements` and `host_access.roots`, and none for `host_access.checker_model`'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().size() == 2
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('host_access.requirements')
            outcome.console().contains('host_access.roots')
            !outcome.console().contains('host_access.checker_model')

        and: 'a file without a `[host_access]` table passes, because host access is then off'
            host.givenConfig(lamp, 'schema_version = 1' + System.lineSeparator())
            host.oillamp.run('config', lamp.toString(), 'check').status() == LampExitStatus.SUCCESS
    }

    def '`host_access.requirements` is one piece of text, which may span several lines'() {
        reportInfo """
            With host access on, a checker, a separate call to a model, judges each request that
            the agent makes of the user's computer against `host_access.requirements`. The
            requirements are the user's own words, not a checklist, so they are a single string,
            usually over several lines. Both of TOML's multi-line strings work. The literal one,
            between `'''`, keeps a backslash as a backslash, so it is the one to use for paths and
            regular expressions.
        """
        given: 'host access on, with requirements over two lines in a literal string, one of them with a backslash'
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

        when: '`oillamp config <dir> show-effective` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'it shows both lines as they were written, backslash included'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.console().contains('You may read ~/Documents.')
            outcome.console().contains('Never read ~/.ssh, nor anything under ~/\\.gnupg.')

        and: 'with no `checker_model` of its own, the lamp\'s checker uses the agent\'s model'
            outcome.console().contains("checker the agent's model")
    }

    def 'The agent may run commands on the host only when `host_access.commands` says "ask" or "checker"'() {
        reportInfo """
            With host access on, the agent may reach the user's computer through tools that a
            checker, a separate call to a model, judges first. One of them, `host_bash`, runs a
            command there, which can do anything the user can. Whether it exists in a lamp, and
            who allows each command, is the setting `host_access.commands`, one of three words:

            - `"off"`, the default: there is no `host_bash`.
            - `"ask"`: the checker judges each command, and then the user is asked before it runs.
            - `"checker"`: the checker's allow is enough.

            Any other word is refused with `OIL-CONFIG-004`, naming the key: a typo must not open
            the way to running commands.

            The setting replaced `host_access.executables`, a list of programs. Every lamp created
            before that change has `executables = []` in its file, written there by oillamp's
            template, and an empty list meant no programs, which is what `"off"` says. So that
            line is accepted, and such a lamp starts as it did. A list that names programs is refused with
            `OIL-CONFIG-004`, naming `host_access.commands`: `"ask"` or `"checker"` would let the
            agent run any command, not only the programs listed, so oillamp does not choose one
            on the user's behalf.

            The scenario checks a lamp's file with `oillamp config <dir> check`, and reads what a
            valid one means with `oillamp config <dir> show-effective`.
        """
        given: 'a lamp with host access on, whose file sets `host_access.commands`, the old `executables`, or neither'
            var lamp = host.lampPath()
            host.givenConfig(lamp, """
                schema_version = 1

                [host_access]
                enabled = true
                requirements = "Build ~/project, and nothing else."
                ${setting}
            """.stripIndent())

        when: '`oillamp config <dir> check` and `oillamp config <dir> show-effective` run'
            var checked = host.oillamp.run('config', lamp.toString(), 'check')
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'a valid setting passes, and `show-effective` says who allows a command; any other is refused with `OIL-CONFIG-004`, naming `host_access.commands`'
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

    def '`oillamp config check` #verdict `schedule.max_delay_minutes = #value`'() {
        reportInfo """
            `schedule.max_delay_minutes` says how late a job's run may start. A running session
            checks the schedule as it starts, every 30 seconds after that, and whenever someone
            changes the schedule. When it sees a job whose time lies more than this many minutes
            in the past, oillamp does not wake the agent for it. The default is 30. 0 turns the
            limit off, as 0 does for `recording.max_age_days` and `recording.max_total_gb`.

            The largest value is 10080, the minutes of one week, the same upper bound
            `schedule.min_agent_interval_minutes` has. oillamp measures how late a run is from the
            first time the job was due and did not run. With a limit of, say, ten days, a weekly
            job due on a Monday and seen by a session on the Wednesday of the week after would
            still run, nine days late, although its next Monday has passed too: the agent would do
            last week's work. Up to a week, a run is never later than one period of a weekly job.
            A negative number of minutes has no meaning.

            The scenario checks the edges with `oillamp config <dir> check`: -1 and 10081 are
            refused with `OIL-CONFIG-004`, naming the key and what it expects; 0 and 10080 pass.
        """
        given: 'a lamp whose `oillamp.toml` sets `schedule.max_delay_minutes`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, "schema_version = 1\n[schedule]\nmax_delay_minutes = ${value}\n")

        when: '`oillamp config <dir> check` runs'
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
