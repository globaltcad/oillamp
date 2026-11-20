package oillamp

import dev.lamp.Lamp
import dev.lamp.LampExitStatus
import dev.lamp.LampEvent
import dev.lamp.LampFailedException
import dev.lamp.LampWallpaper
import dev.lamp.LampWebPublishing
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Title
import sprouts.Tuple

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
            and internal network ranges. It then compares everything `show-effective` prints with
            what it prints for a lamp whose `oillamp.toml` sets nothing, so that every other value
            it prints is checked too. Settings it does not print, such as the timeouts, the
            schedule and the saves, are not checked here.
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

        and: 'every setting `show-effective` prints is the same as for a lamp whose `oillamp.toml` sets nothing at all'
            var bare = host.lampPath('bare')
            host.givenConfig(bare, 'schema_version = 1\n')
            host.oillamp.run('at', bare.toString())
            var effective = { Path dir ->
                host.oillamp.run('config', dir.toString(), 'show-effective')
                        .events().find { it instanceof LampEvent.Answer }.text()
            }
            effective(lamp) == effective(bare)

        and: 'only the user may read and write `oillamp.toml` (mode `rw-------`), because it can hold what other users of the machine have no business reading, such as a git name and email under `[git]`, or the requirements for reaching the host'
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

    def 'Host access is off unless a lamp turns it on, and `oillamp config check` refuses a checker without instructions and an allowlist folder that is not absolute'() {
        reportInfo """
            Host access gives the agent tools that reach the user's computer. What it may do there
            is `host_access.may`, which is `"nothing"` unless a lamp says otherwise, so a lamp
            whose file does not mention host access works as it did before the feature existed.

            Each request passes the lamp's guards first. With the checker among them, a separate
            call to a model judges the request against `host_access.checker_instructions`: the
            user's own words for what the agent may and may not do there. A checker with nothing
            to judge by says yes, so oillamp refuses empty instructions with `OIL-CONFIG-004`,
            naming `host_access.checker_instructions`, rather than start a lamp whose checker
            allows everything. A lamp whose `guards` are only `"allowlists"` asks no checker, and
            needs no instructions.

            The allowlists, `host_access.read_allowlist` and `host_access.change_allowlist`, are
            the folders the agent may reach. A folder in them is `~`, starts with `~/`, or is
            absolute; a relative one would mean whatever directory oillamp happened to start in,
            so it is refused with `OIL-CONFIG-004` too, naming its list.

            `host_access.checker_model` is not required. Without it, the checker uses the model
            the agent answers with, which is only known once a session starts with one.
        """
        given: 'a lamp that lets the agent read, with the checker, empty checker instructions, and a relative folder in each allowlist'
            var lamp = host.lampPath()
            host.givenConfig(lamp, """
                schema_version = 1

                [host_access]
                may = "read"
                guards = "allowlists_checker"
                checker_instructions = ""
                read_allowlist = ["relative/path"]
                change_allowlist = ["~/Documents", "also/relative"]
            """.stripIndent())

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it reports three `OIL-CONFIG-004` errors, for the checker instructions and both allowlists, and none for `host_access.checker_model`'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().size() == 3
            outcome.errors().every { it.code().value() == 'OIL-CONFIG-004' }
            outcome.console().contains('host_access.checker_instructions')
            outcome.console().contains('host_access.read_allowlist = "relative/path"')
            outcome.console().contains('host_access.change_allowlist = "also/relative"')
            !outcome.console().contains('host_access.checker_model')

        and: 'with the allowlists as the only guard and absolute folders, the same empty instructions pass'
            host.givenConfig(lamp, """
                schema_version = 1

                [host_access]
                may = "read"
                guards = "allowlists"
                checker_instructions = ""
                read_allowlist = ["/srv/data"]
            """.stripIndent())
            host.oillamp.run('config', lamp.toString(), 'check').status() == LampExitStatus.SUCCESS

        and: 'a file without a `[host_access]` table passes, because host access is then off'
            host.givenConfig(lamp, 'schema_version = 1' + System.lineSeparator())
            host.oillamp.run('config', lamp.toString(), 'check').status() == LampExitStatus.SUCCESS
    }

    def '`host_access.checker_instructions` is one piece of text, which may span several lines'() {
        reportInfo """
            With host access on and the checker among the lamp's guards, a separate call to a
            model judges each request that the agent makes of the user's computer against
            `host_access.checker_instructions`. The instructions are the user's own words, not a
            checklist, so they are a single string, usually over several lines. Both of TOML's
            multi-line strings work. The literal one, between `'''`, keeps a backslash as a
            backslash, so it is the one to use for paths and regular expressions.
        """
        given: 'host access on, with checker instructions over two lines in a literal string, one of them with a backslash'
            var lamp = host.lampPath()
            host.givenConfig(lamp, """
                schema_version = 1

                [host_access]
                may = "read"
                checker_instructions = '''
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

    def '`host_access.may` says what the agent may do on the host, and `host_access.guards` what each request passes first: #may, #guards'() {
        reportInfo """
            With host access on, the agent may reach the user's computer through tools. Two words
            in the lamp's `oillamp.toml` say how far:

            - `host_access.may`, a ladder in which each step allows what the one before it does:
              `"nothing"` (the default: no tools at all), `"read"`, `"read_change"` (also write
              files, copy them out, open pages, notify) and `"read_change_run"` (also run
              commands with `host_bash`, which can do anything the user can).
            - `host_access.guards`, the chain each request passes, always in the same order:
              `"allowlists"` (only the folders), `"allowlists_checker"` (the default: then a
              checker model) and `"allowlists_checker_user"` (then the person too, for every
              command and for a folder outside the allowlists).

            Any other word is refused with `OIL-CONFIG-004`, naming the key: a typo must not open a
            tier, nor take a guard away. The scenario checks a lamp's file with
            `oillamp config <dir> check`, and reads what a valid one means with
            `oillamp config <dir> show-effective`.
        """
        given: 'a lamp whose file sets `may` and `guards`, or leaves them out'
            var lamp = host.lampPath()
            host.givenConfig(lamp, """
                schema_version = 1

                [host_access]
                ${may == null ? '' : "may = \"${may}\""}
                ${guards == null ? '' : "guards = \"${guards}\""}
                checker_instructions = "Build ~/project, and nothing else."
            """.stripIndent())

        when: '`oillamp config <dir> check` and `oillamp config <dir> show-effective` run'
            var checked = host.oillamp.run('config', lamp.toString(), 'check')
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'valid words pass, and `show-effective` says what they mean; any other is refused with `OIL-CONFIG-004`, naming the key'
            (checked.status() == LampExitStatus.SUCCESS) == (code == null)
            code == null ? shown.console().contains(means) : checked.reported(code)
            code == null || checked.console().contains(means)

        where:
            may                 | guards                     || code             | means
            null                | null                       || null             | 'host access  off'
            'read'              | null                       || null             | 'may read, guards allowlists_checker'
            'read_change'       | 'allowlists_checker'       || null             | 'may read_change, guards allowlists_checker'
            'read_change_run'   | 'allowlists_checker_user'  || null             | 'may read_change_run, guards allowlists_checker_user'
            'read'              | 'allowlists'               || null             | 'no checker: the allowlists are the only guard'
            'everything'        | null                       || 'OIL-CONFIG-004' | 'host_access.may'
            'read'              | 'checker'                  || 'OIL-CONFIG-004' | 'host_access.guards'
    }

    def 'A new lamp\'s `oillamp.toml` lets the agent do nothing on the host, and says next to each allowlist that a folder includes everything below it'() {
        reportInfo """
            A new lamp's `oillamp.toml` has the `[host_access]` table written out with its default
            values and a comment next to each key, because most users read that file as the
            documentation of the settings. By default the agent may do nothing on the host,
            `may = "nothing"`; the guards are the allowlists and then the checker,
            `guards = "allowlists_checker"`, for the day the user lets it do more; and both
            allowlists hold `~`, the user's home. The comments next to the allowlists say what a
            folder in them means: it includes everything below it, and links are followed, so a
            link cannot lead out of it.
        """
        given: 'a new lamp'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())

        when: 'the user reads its `oillamp.toml`, and `oillamp config <dir> show-effective` runs'
            var file = Files.readString(lamp.resolve('oillamp.toml'))
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'the file lets the agent do nothing, behind the allowlists and the checker, with `~` in both allowlists and no checker instructions yet'
            file.contains('may                  = "nothing"                   # what the agent may do here: "nothing",\n')
            file.contains('guards               = "allowlists_checker"        # what each request passes first:\n')
            file.contains('read_allowlist       = ["~"]                       # folders it may read: "~", "~/…" or\n')
            file.contains('change_allowlist     = ["~"]                       # folders it may read and change, and run\n')
            file.contains("checker_instructions = '''\n'''\n")

        and: 'the comments next to both allowlists say that a folder includes everything below it, and that a link cannot lead out'
            file.count('Each includes everything\n' + ' ' * 51 + '# below it') == 2
            file.count('link cannot lead out') == 2

        and: '`show-effective` says host access is off'
            shown.console().contains('host access  off')
    }

    def 'oillamp rewrites the `[host_access]` table an older oillamp wrote into a lamp\'s file, keeps the rest of the file, and the lamp starts'() {
        reportInfo """
            Let's consider a lamp made by an older oillamp, such as one of Genies' lamps. Its
            `oillamp.toml` says what the agent may do on the host in keys that no longer exist:
            `enabled`, `requirements`, `roots`, `commands`, `allow_additive` and `ask_the_user`.
            oillamp refuses a key it does not know, so without help such a lamp would no longer
            start.

            So whenever oillamp reads a lamp's `oillamp.toml` and finds one of those keys, it
            rewrites the table in the keys that replaced them, keeping what the old ones meant,
            and goes on with the rewritten file, without a word. The old keys go, with the comment
            lines below them that carry on their comments. `may`, `guards` and the two allowlists
            take the place of the first old key, with the comments a new lamp's file has.
            `requirements` becomes `checker_instructions` where it is, its value as it was written.
            Every other line stays as it was: the other tables, the user's own comments, and the
            keys of `[host_access]` that did not change.

            This lamp turned host access on and had the person approve each command
            (`commands = "ask"`), with no `roots`, which meant the user's home. That is
            `may = "read_change_run"`, `guards = "allowlists_checker_user"`, and `~` in both
            allowlists.
        """
        given: 'a lamp whose file an older oillamp wrote, with a comment and a table of the user\'s around `[host_access]`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''\
                schema_version = 1
                # my own note about this lamp

                [display]
                width  = 2560

                [host_access]
                enabled = true
                # Your own words. This is the only thing the checker judges by, and the agent never
                # sees it.
                requirements = \'\'\'
                {
                  "commands" : "The person approves every command themselves."
                }\'\'\'
                checker_model     = ""       # the model that judges; empty: the one the agent answers with
                roots             = []       # absolute paths the file tools, and a command's directory,
                                             # may name; empty means your home directory
                max_read_bytes    = 65536    # how much of one file the agent may be handed
                commands          = "ask"    # shell commands here: "off", "ask" (the checker, then you,
                                             # for every one) or "checker" (the checker alone)
                allow_additive    = true     # writing and copying files out, opening pages, notifications
                ask_the_user      = false    # ask you, rather than refuse, a request outside roots

                # my saves
                [autosave]
                startup = true
                '''.stripIndent())

        when: '`oillamp config <dir> check` reads the lamp'
            var checked = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it finds nothing wrong, and says nothing about the rewrite'
            checked.status() == LampExitStatus.SUCCESS
            checked.problems().isEmpty()

        and: 'the file has the new keys in place of the old ones, and every other line as it was'
            Files.readString(lamp.resolve('oillamp.toml')) == '''\
                schema_version = 1
                # my own note about this lamp

                [display]
                width  = 2560

                [host_access]
                may                  = "read_change_run"           # what the agent may do here: "nothing",
                                                                   # "read", "read_change" (also write files,
                                                                   # copy them out, open pages, notify) or
                                                                   # "read_change_run" (also run commands)
                guards               = "allowlists_checker_user"   # what each request passes first:
                                                                   # "allowlists", "allowlists_checker" (then
                                                                   # the checker model) or
                                                                   # "allowlists_checker_user" (then you too,
                                                                   # for every command, and for a folder
                                                                   # outside the allowlists, else refused)
                read_allowlist       = ["~"]                       # folders it may read: "~", "~/…" or
                                                                   # absolute paths. Each includes everything
                                                                   # below it, and links are followed, so a
                                                                   # link cannot lead out
                change_allowlist     = ["~"]                       # folders it may read and change, and run
                                                                   # commands in. Each includes everything
                                                                   # below it, and links are followed, so a
                                                                   # link cannot lead out
                # Your own words. This is the only thing the checker judges by, and the agent never
                # sees it.
                checker_instructions = \'\'\'
                {
                  "commands" : "The person approves every command themselves."
                }\'\'\'
                checker_model     = ""       # the model that judges; empty: the one the agent answers with
                max_read_bytes    = 65536    # how much of one file the agent may be handed

                # my saves
                [autosave]
                startup = true
                '''.stripIndent()

        when: 'a session starts in the lamp, with a model for the checker'
            var started = host.oillamp.run('at', lamp.toString(), '--model', 'azure/gpt-6.1-sol')

        then: 'it starts, and gives the agent the host tools, `host_bash` among them'
            started.succeeded()
            (started.events().find { it instanceof LampEvent.HostAccessEnabled } as LampEvent.HostAccessEnabled)
                    .tools().contains('host_bash')
    }

    def 'oillamp reads an older `[host_access]` table as the same settings in the new keys: #meaning'() {
        reportInfo """
            Let's consider the ways a lamp made by an older oillamp may have said what the agent
            may do on the host. oillamp rewrites each into the keys that replaced the old ones, so
            that the lamp does what it did:

            - `may` is `"nothing"` unless `enabled` was true. Then it is `"read_change_run"` when
              `commands` was `"ask"` or `"checker"`, else `"read"` when `allow_additive` was false,
              else `"read_change"`. A lamp that ran commands without the additive tier gets
              `"read_change_run"`: there is no step for running without changing, and taking the
              commands away would break what it was set up for.
            - `guards` is `"allowlists_checker_user"` when `commands` was `"ask"` or `ask_the_user`
              was true, else `"allowlists_checker"`.
            - both allowlists are what `roots` listed, or `["~"]` when it listed nothing, which
              meant the user's home.
            - `checker_instructions` is what `requirements` was.

            `executables`, from lamps older still, goes: it was empty in any lamp that started.
            A key the table already has in the new form is kept as it is.
        """
        given: 'a lamp whose `[host_access]` table has old keys'
            var lamp = host.lampPath()
            host.givenConfig(lamp, "schema_version = 1\n[host_access]\n${old}\nrequirements = 'Be careful.'\n")

        when: '`oillamp config <dir> check` reads the lamp'
            var checked = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'the lamp is valid, and its table has exactly the new keys'
            checked.status() == LampExitStatus.SUCCESS
            var table = new com.fasterxml.jackson.dataformat.toml.TomlMapper()
                    .readValue(Files.readString(lamp.resolve('oillamp.toml')), Map).host_access
            table == [may: may, guards: guards, read_allowlist: folders, change_allowlist: folders,
                      checker_instructions: 'Be careful.']

        where:
            meaning                                      | old                                                      || may               | guards                    | folders
            'off'                                        | 'enabled = false\ncommands = "checker"'                  || 'nothing'         | 'allowlists_checker'      | ['~']
            'on'                                         | 'enabled = true'                                         || 'read_change'     | 'allowlists_checker'      | ['~']
            'on, read only'                              | 'enabled = true\nallow_additive = false'                 || 'read'            | 'allowlists_checker'      | ['~']
            'commands, the checker alone'                | 'enabled = true\ncommands = "checker"'                   || 'read_change_run' | 'allowlists_checker'      | ['~']
            'commands, asking the person'                | 'enabled = true\ncommands = "ask"'                       || 'read_change_run' | 'allowlists_checker_user' | ['~']
            'commands without the additive tier'         | 'enabled = true\ncommands = "checker"\nallow_additive = false' || 'read_change_run' | 'allowlists_checker' | ['~']
            'asking the person past the roots'           | 'enabled = true\nask_the_user = true'                    || 'read_change'     | 'allowlists_checker_user' | ['~']
            'two roots'                                  | 'enabled = true\nroots = ["/srv/a", "/srv/b"]'           || 'read_change'     | 'allowlists_checker'      | ['/srv/a', '/srv/b']
            'the empty executables of an older lamp'     | 'enabled = true\nexecutables = []'                       || 'read_change'     | 'allowlists_checker'      | ['~']
            'a new key beside the old ones'              | 'enabled = true\nmay = "read"'                           || 'read'            | 'allowlists_checker'      | ['~']
    }

    def 'oillamp reads old `[host_access]` keys in `~/.config/oillamp/config.toml` as the new ones, and never rewrites that file'() {
        reportInfo """
            Settings in `~/.config/oillamp/config.toml` apply to every lamp of the user, beneath
            each lamp's own `oillamp.toml`. That file may still say what the agent may do on the
            host in the keys of an older oillamp. oillamp reads them as the keys that replaced
            them, as it does a lamp's own file, but it never rewrites the user-wide file: it is
            the user's, outside any lamp, and the same file may be read by an older oillamp too.
        """
        given: 'a user-wide file that turns host access on in the old keys, with one root'
            var global = '[host_access]\nenabled = true\nrequirements = "Only the shared folder."\nroots = ["/srv/shared"]\n'
            host.givenGlobalConfig(global)

        and: 'a lamp whose own file says nothing about host access'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')

        when: '`oillamp config <dir> show-effective` reads the lamp\'s settings'
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'the lamp has the user-wide settings, in the new keys'
            shown.status() == LampExitStatus.SUCCESS
            shown.console().contains('may read_change, guards allowlists_checker')
            shown.console().contains('read /srv/shared; read and change /srv/shared')
            shown.console().contains('| Only the shared folder.')

        and: 'neither file was rewritten'
            Files.readString(host.home.resolve('.config/oillamp/config.toml')) == global
            Files.readString(lamp.resolve('oillamp.toml')) == 'schema_version = 1\n'
    }

    def 'oillamp reads an older lamp file as the new keys and leaves the file as it was, #when'() {
        reportInfo """
            oillamp rewrites a lamp's `oillamp.toml` that still has `[host_access]` keys of an
            older oillamp into the keys that replaced them. A dry run promises to change nothing,
            and a lamp directory oillamp may not write in cannot take a new file. In both cases
            oillamp reads the old keys as the new ones all the same, every time it reads the
            file, and the lamp is as valid as it would be rewritten.
        """
        given: 'a lamp whose file has old keys'
            var lamp = host.lampPath()
            var old = 'schema_version = 1\n[host_access]\nenabled = true\nrequirements = "Be careful."\n'
            host.givenConfig(lamp, old)
            if (readOnly) Files.setPosixFilePermissions(lamp, java.nio.file.attribute.PosixFilePermissions.fromString('r-x------'))

        when: '`oillamp config <dir> check` reads the lamp'
            var checked = host.oillamp.run(*(['config', lamp.toString(), 'check'] + options))

        then: 'the lamp is valid, and its file is as it was'
            checked.status() == LampExitStatus.SUCCESS
            Files.readString(lamp.resolve('oillamp.toml')) == old

        and: '`oillamp config <dir> show-effective` reads the old keys as the agent may read and change'
            host.oillamp.run(*(['config', lamp.toString(), 'show-effective'] + options)).console()
                    .contains('may read_change, guards allowlists_checker')

        cleanup:
            Files.setPosixFilePermissions(lamp, java.nio.file.attribute.PosixFilePermissions.fromString('rwx------'))

        where:
            when                                   | options       | readOnly
            'in a dry run'                         | ['--dry-run'] | false
            'when oillamp may not write next to it'| []            | true
    }

    def 'oillamp leaves an older lamp file as it is while the lamp\'s session runs, reads it as the new keys, and rewrites it once the session has ended'() {
        reportInfo """
            oillamp changes a lamp's `oillamp.toml` only while it holds the lamp's lock, which a
            running session holds the whole time, and `oillamp host-access` while it writes. The
            rewrite of older `[host_access]` keys follows the same rule: otherwise a late rewrite
            of the old settings could write over a change made a moment earlier. So while a
            session runs, a command that reads the file, such as `oillamp config <dir> check`,
            leaves it as it is and reads the old keys as the new ones. The next read once the
            session has ended rewrites the file.

            Here the lamp's file is given old keys while its session runs, as an older program
            might write them.
        """
        given: 'a lamp whose session runs'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')
            var running = Lamp.at(lamp).launchedBy(host.launcher).start()
            assert running.awaitRunning(Duration.ofSeconds(30))

        and: 'its file then gets host access in the old keys'
            var old = 'schema_version = 1\n[host_access]\nenabled = true\nrequirements = "Be careful."\n'
            host.givenConfig(lamp, old)

        when: '`oillamp config <dir> check` reads the lamp while the session runs'
            var checked = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'the lamp is valid, and its file is as it was'
            checked.status() == LampExitStatus.SUCCESS
            Files.readString(lamp.resolve('oillamp.toml')) == old

        when: 'the session ends, and `oillamp config <dir> check` reads the lamp again'
            running.close()
            host.oillamp.run('config', lamp.toString(), 'check')

        then: 'the file has the new keys'
            var file = Files.readString(lamp.resolve('oillamp.toml'))
            file.contains('may                  = "read_change"')
            !file.contains('enabled')

        cleanup:
            running?.close()
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

    def 'oillamp publishes opencode\'s port 4096 for a lamp whose `oillamp.toml` has no `[web]` section'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` has no `[web]` section at all, as every lamp
            made before the section existed. The person working with it wants to open opencode's
            web interface in their own browser. They start it in the sandbox's shell with
            `opencode serve --port 4096`, and expect a link on their machine that leads to it.

            So such a lamp behaves as `publish = "listed"` with `ports = { opencode = 4096 }`.
            `oillamp config <dir> show-effective` says so, and `runtime.env` tells the sandbox, in
            `OILLAMP_WEB`, to serve that one port. oillamp never starts opencode itself: a published
            port is only a way in, which answers once something in the sandbox listens there.
        """
        given: 'a lamp whose `oillamp.toml` says nothing about `[web]`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')

        when: '`oillamp config <dir> show-effective` runs, and `oillamp at <dir>` starts the session'
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')
            host.oillamp.run('at', lamp.toString())
            var runtimeEnv = Files.readString(lamp.resolve('.oillamp/session/runtime.env'))

        then: '`show-effective` says that the listed port `opencode`, 4096, is published'
            shown.console().contains('web          listed: opencode 4096')

        and: '`runtime.env` tells the sandbox to serve that port, as `OILLAMP_WEB=\'opencode:4096\'`'
            runtimeEnv.contains("OILLAMP_WEB='opencode:4096'")
    }

    def 'oillamp publishes nothing when `[web]` says #how'() {
        reportInfo """
            Let's consider a person who does not want any page from the sandbox in their browser.
            A page opened from a published port runs in the browser, and from there it can send
            requests to the person's other local web servers and to other lamps' published ports.
            A company that locks its lamps down has the same concern.

            `publish = "off"` publishes nothing, whatever `ports` lists. `publish = "listed"` with
            `ports = {}` lists nothing, and so publishes nothing either. In both cases the sandbox
            gets an empty `OILLAMP_WEB` in `runtime.env` and serves no web socket.
        """
        given: 'a lamp whose `[web]` section says #how'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n\n[web]\n' + web)

        when: '`oillamp config <dir> show-effective` runs, and `oillamp at <dir>` starts the session'
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')
            host.oillamp.run('at', lamp.toString())
            var runtimeEnv = Files.readString(lamp.resolve('.oillamp/session/runtime.env'))

        then: '`show-effective` says so, and `runtime.env` holds an empty `OILLAMP_WEB`'
            shown.console().contains(effective)
            runtimeEnv.contains("OILLAMP_WEB=''")

        where:
            how                                         | web                                                   || effective
            '`publish = "off"`, with a port listed'     | 'publish = "off"\nports = { opencode = 4096 }\n'      || 'web          off'
            '`publish = "listed"` and `ports = {}`'     | 'publish = "listed"\nports = {}\n'                    || 'web          listed: no ports'
    }

    def 'oillamp refuses `web.publish = "#value"`, and names the levels it knows'() {
        reportInfo """
            `web.publish` decides which web servers inside the sandbox the person's browser may
            open. There are four levels: `off`, `listed`, `ask` and `any`. A typo, or a word that is
            none of them, must not be read as one of the four, because any guess could publish more
            or less than the person meant. So oillamp refuses it with `OIL-CONFIG-004`, names
            `web.publish`, and lists the four levels.
        """
        given: 'a lamp whose `oillamp.toml` sets `web.publish = "#value"`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n\n[web]\npublish = "' + value + '"\n')

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it reports `OIL-CONFIG-004` for `web.publish`, and names the four levels'
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('web.publish')
            outcome.console().contains('"off", "listed", "any" or "ask"')

        where:
            value << ['on', 'yes', 'all']
    }

    def 'oillamp accepts `web.publish = "#value"`, which publishes #what'() {
        reportInfo """
            Let's consider two people. One uses a lamp as a personal assistant and wants every web
            page the agent builds to open in their browser without being asked: `publish = "any"`.
            The other wants to decide each time: `publish = "ask"`, under which oillamp asks before
            it publishes a port the agent opened. Both levels publish the listed ports as well.
            `oillamp config <dir> check` accepts both, and `show-effective` says what each means.
        """
        given: 'a lamp whose `oillamp.toml` sets `web.publish = "#value"` and no ports of its own'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n\n[web]\npublish = "' + value + '"\n')

        when: '`oillamp config <dir> check` and `show-effective` run'
            var checked = host.oillamp.run('config', lamp.toString(), 'check')
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'the configuration is valid, and `show-effective` says what the level publishes'
            checked.status() == LampExitStatus.SUCCESS
            shown.console().contains(effective)

        where:
            value | what                                                   || effective
            'any' | 'opencode\'s port and every port the agent opens'      || 'web          any: opencode 4096, and every port the agent opens'
            'ask' | 'opencode\'s port and each port the person allows'     || 'web          ask: opencode 4096, and each port the agent opens that you allow'
    }

    def 'oillamp refuses to publish port #port, because #why'() {
        reportInfo """
            Each entry of `web.ports` names a port inside the sandbox that the person's browser may
            open. Some ports must never be published. 3128 is the sandbox's egress proxy and 3129
            is where the agent's tools reach the model service through oillamp; publishing either
            would let a page in the browser use them. A port of `[[network.forwards]]` leads to a
            target the person chose for the sandbox only, such as a company's model service, and
            publishing it would hand that target to the browser. Ports below 1024 are not used in
            the sandbox at all.

            So oillamp refuses such an entry with `OIL-CONFIG-004`, names the key, such as
            `web.ports.site`, and says what the port is used for, so that the person knows which
            entry to change.
        """
        given: 'a lamp with a forward "llm" on port 8000, and a web port "site" on port #port'
            var lamp = host.lampPath()
            host.givenConfig(lamp, """
                schema_version = 1

                [[network.forwards]]
                name   = "llm"
                port   = 8000
                target = "llm.corp.example.com:8000"

                [web]
                ports = { site = ${port} }
            """.stripIndent())

        when: '`oillamp config <dir> check` runs'
            var outcome = host.oillamp.run('config', lamp.toString(), 'check')

        then: 'it reports `OIL-CONFIG-004` for `web.ports.site`, and says why'
            outcome.status() == LampExitStatus.USAGE
            outcome.reported('OIL-CONFIG-004')
            outcome.console().contains('web.ports.site')
            outcome.console().contains(explanation)

        where:
            port | why                                         || explanation
            3128 | 'it is the sandbox\'s egress proxy'          || 'egress proxy'
            3129 | 'it is where the model service is reached'  || 'model service through oillamp'
            8000 | 'it is the forward "llm"'                   || 'the forward "llm"'
            80   | 'it is below 1024'                          || 'between 1024 and 65535'
    }

    def 'A lamp whose `oillamp.toml` was written before `[web]` existed still loads, and publishes opencode\'s port'() {
        reportInfo """
            Let's consider a lamp made by an older oillamp. Its `oillamp.toml` is the commented
            file that oillamp wrote then, which is today's file without the `[web]` section. oillamp
            refuses unknown keys, but here keys were only added, so the old file must still load
            exactly as before, and the lamp must start. It publishes what a lamp without `[web]`
            publishes: opencode's port 4096.
        """
        given: 'a lamp whose `oillamp.toml` is today\'s commented file without its `[web]` section'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            var file = lamp.resolve('oillamp.toml')
            var lines = Files.readAllLines(file)
            var start = lines.findIndexOf { it.startsWith('[web]') }
            var end = lines.findIndexOf(start + 1) { it.startsWith('[') }
            // The comment above [web] belongs to it as well; it starts after the blank line before.
            var commentStart = lines.subList(0, start).findLastIndexOf { it.isBlank() } + 1
            Files.write(file, lines.subList(0, commentStart) + lines.subList(end, lines.size()))

        when: '`oillamp config <dir> check` and `oillamp config <dir> show-effective` run'
            var checked = host.oillamp.run('config', lamp.toString(), 'check')
            var shown = host.oillamp.run('config', lamp.toString(), 'show-effective')

        then: 'the file has no `[web]` section, and is valid'
            !Files.readString(file).contains('[web]')
            checked.status() == LampExitStatus.SUCCESS

        and: 'the lamp publishes opencode\'s port 4096'
            shown.console().contains('web          listed: opencode 4096')
    }

    def '`~/AGENTS.md` names the ports the human can open in their browser, and how opencode\'s web interface gets there'() {
        reportInfo """
            Let's consider an agent asked to build a small web site, or a person who wants to use
            opencode's web interface. The agent can only help if it knows which of its ports the
            person can open in their browser through oillamp. So the guide `~/AGENTS.md`, which
            every session writes, lists the published ports. For a port named `opencode` it says
            that `opencode serve --port 4096` starts the web interface, that the person starts
            it, not oillamp, and that it prints the password the web interface asks for. With
            `web.publish = "off"` it says that nothing is published, and that the person would have
            to enable `[web]`.
        """
        given: 'a lamp whose `oillamp.toml` has no `[web]` section'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n')

        when: '`oillamp at <dir>` starts the session'
            host.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            var guide = Files.readString(agentHome.resolve('AGENTS.md'))

        then: 'the guide lists port 4096 as `opencode`, and says how to start opencode\'s web interface'
            guide.contains('## Web servers the human can open')
            guide.contains('- `4096` → opencode')
            guide.contains('`opencode serve --port 4096`')
            guide.contains('oillamp never\nstarts it')
            guide.contains('It prints a password, which the web interface asks for.')

        when: 'the lamp sets `web.publish = "off"`, and `oillamp at <dir>` starts the session again'
            host.givenConfig(lamp, 'schema_version = 1\n\n[web]\npublish = "off"\n')
            host.oillamp.run('at', lamp.toString())
            guide = Files.readString(agentHome.resolve('AGENTS.md'))

        then: 'the guide says that nothing is published, and that the human would have to enable `[web]`'
            guide.contains('Nothing in this sandbox is published')
            guide.contains('enable `[web]`')
            !guide.contains('`opencode serve')
    }

    def '`~/AGENTS.md` names the provider under which opencode offers the Eden AI models: `edenai-eu`, or `edenai` for a lamp that pins opencode 1'() {
        reportInfo """
            Let's consider an agent that runs opencode without its interface, as
            `opencode run -m <provider>/<model> ...`. It needs the provider ID under which
            opencode's configuration lists the Eden AI models, and that ID depends on opencode's
            major version. opencode 2 adds its own catalog to any provider whose ID it knows, so
            the image's configuration lists the EU models under `edenai-eu`. opencode 1 has them
            under its built-in provider `edenai`. opencode 1 also never asks a model for more than
            32000 tokens, which opencode 2 does not limit.

            A lamp gets opencode 2 unless `agent_tools.versions.opencode` in its `oillamp.toml`
            pins a release that starts with `0.` or `1.`. The guide `~/AGENTS.md`, which every
            session writes, names the provider for the lamp's version, and mentions the cap of
            32000 tokens only for opencode 1.
        """
        given: 'a lamp whose `agent_tools.versions.opencode` is #version'
            var lamp = host.lampPath()
            host.givenConfig(lamp, "schema_version = 1\n\n[agent_tools]\nversions = { opencode = \"$version\" }\n")

        when: '`oillamp at <dir>` starts the session'
            host.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            var guide = Files.readString(agentHome.resolve('AGENTS.md'))

        then: 'the guide names the provider `#provider`, and shows it in an `opencode run -m` command'
            guide.contains("under the provider `${provider}`")
            guide.contains("`opencode run -m ${provider}/<model> ...`")

        and: 'it mentions opencode\'s cap of 32000 tokens only for opencode 1'
            guide.contains('its own cap of 32000') == capMentioned

        where:
            version  || provider    | capMentioned
            'latest' || 'edenai-eu' | false
            '2.2.7'  || 'edenai-eu' | false
            '1.19.5' || 'edenai'    | true
    }

    def 'A lamp with a forward on port 4096 and no `[web]` section still starts, and oillamp leaves the default opencode port out'() {
        reportInfo """
            Let's consider a lamp made before `[web]` existed, with a forward "llm" on port 4096 in
            its `[[network.forwards]]`. A lamp without `[web]` publishes opencode's port 4096 by
            default. Publishing a forward's port would hand the forward's target, which the person
            chose for the sandbox only, to their browser, so the two cannot go together. But this
            lamp's file never asked for the web port; refusing it would stop a lamp that started
            yesterday from starting today.

            So oillamp leaves the default port out, says so with the warning `OIL-CONFIG-005`, and
            the lamp starts. `OILLAMP_WEB` in `runtime.env` is empty: nothing is published on 4096.
            A lamp whose own `web.ports` lists a forward's port is still refused.
        """
        given: 'a lamp with a forward "llm" on port 4096 and no `[web]` section'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [[network.forwards]]
                name   = "llm"
                port   = 4096
                target = "llm.corp.example.com:8000"
            '''.stripIndent())

        when: '`oillamp config <dir> check` runs, and `oillamp at <dir>` starts the session'
            var checked = host.oillamp.run('config', lamp.toString(), 'check')
            var started = host.oillamp.run('at', lamp.toString())
            var runtimeEnv = Files.readString(lamp.resolve('.oillamp/session/runtime.env'))

        then: 'the configuration is valid, with the warning `OIL-CONFIG-005` naming the forward "llm"'
            checked.status() == LampExitStatus.SUCCESS
            checked.reported('OIL-CONFIG-005')
            checked.console().contains("'llm'")

        and: 'the session starts, says the same, and publishes nothing on port 4096'
            started.reported('OIL-CONFIG-005')
            runtimeEnv.contains("OILLAMP_WEB=''")
            runtimeEnv.contains("OILLAMP_FORWARDS='llm:4096'")
    }

    def 'A lamp\'s `web.ports` replaces the list in `~/.config/oillamp/config.toml`, so a lamp can publish fewer ports'() {
        reportInfo """
            Let's consider a person whose `~/.config/oillamp/config.toml` publishes two ports for
            every lamp, `opencode = 4096` and `site = 8080`, and one lamp in which only opencode
            should be reachable from the browser. Tables in the configuration files are usually
            merged key by key, which would leave `site` published whatever the lamp says.

            `web.ports` is a list of ports by name, not a group of settings, so a lamp's own
            `web.ports` replaces the global one as a whole, as an array would. The lamp publishes
            exactly the ports it lists. A lamp that lists none of its own keeps the global list.
        """
        given: 'a user-wide file that publishes `opencode = 4096` and `site = 8080`'
            host.givenGlobalConfig('''
                [web]
                ports = { opencode = 4096, site = 8080 }
            '''.stripIndent())

        and: 'a lamp that lists only `opencode = 4096`, and a lamp that lists no ports'
            var narrow = host.lampPath('narrow')
            host.givenConfig(narrow, 'schema_version = 1\n\n[web]\nports = { opencode = 4096 }\n')
            var plain = host.lampPath('plain')
            host.givenConfig(plain, 'schema_version = 1\n')

        when: '`oillamp config <dir> show-effective` runs for both'
            var narrowShown = host.oillamp.run('config', narrow.toString(), 'show-effective')
            var plainShown = host.oillamp.run('config', plain.toString(), 'show-effective')

        then: 'the lamp with its own list publishes only opencode, and the other publishes both from the user-wide file'
            narrowShown.console().contains('web          listed: opencode 4096\n')
            plainShown.console().contains('web          listed: opencode 4096, site 8080')
    }

    def '`~/AGENTS.md` tells the agent that with `web.publish = "#value"` #what'() {
        reportInfo """
            Let's consider an agent asked to build a web site, in a lamp whose `oillamp.toml` sets
            `publish = "#value"` under `[web]`. The agent should know what happens when it starts its
            own web server, so that it can tell the person where to look. With `any`, a server on
            127.0.0.1 and a port from 1024 up becomes openable for the person by itself, with a link
            named `port-<number>`. With `ask`, the person is asked first, and may say no. The guide
            `~/AGENTS.md` says which.
        """
        given: 'a lamp whose `oillamp.toml` sets `web.publish = "#value"`'
            var lamp = host.lampPath()
            host.givenConfig(lamp, 'schema_version = 1\n\n[web]\npublish = "' + value + '"\n')

        when: '`oillamp at <dir>` starts the session'
            host.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            var guide = Files.readString(agentHome.resolve('AGENTS.md'))

        then: 'the guide still lists opencode\'s port, and says what happens to a server the agent starts'
            guide.contains('- `4096` → opencode')
            guide.contains(says)
            guide.contains('`port-<number>`')

        where:
            value | what                                                     || says
            'any' | 'a server it starts becomes openable by itself'          || 'becomes openable for the human by itself'
            'ask' | 'the person is asked about a server it starts first'     || 'oillamp asks the human whether to publish that port'
    }

    def 'An application reads `[web]` of a lamp whose `oillamp.toml` has none as `listed` with opencode on 4096, and writing it adds the table'() {
        reportInfo """
            Let's consider an application with a settings page for the web servers a lamp publishes,
            such as the desktop app Genies, and a lamp whose `oillamp.toml` has no `[web]` table, as
            every lamp made before the table existed. The application reads the settings with
            `LampDirectory.web()`, which runs `oillamp web <dir>`, and gets what a session of the
            lamp would publish: `LampWebPublishing.LISTED`, with one listed port, `opencode` on 4096.

            It writes the person's choice with `LampDirectory.web(Lamp.Web)`, which runs
            `oillamp web <dir> --publish <level> --ports <name>=<port>,...`. A file without the
            table gets it at its end, with `publish` and `ports`. `web(Lamp.Web)` returns the
            values as the file now has them, with the listed ports sorted by name, and reading
            again gives the same.
        """
        given: 'a lamp that has run once, whose `oillamp.toml` has no `[web]` table'
            var lamp = aLampThatHasRun('schema_version = 1\n')
            var settings = Lamp.at(lamp).launchedBy(host.launcher)

        expect: '`LampDirectory.web()` reads `LISTED`, with `opencode` on 4096 as the only listed port'
            settings.web() == new Lamp.Web(LampWebPublishing.LISTED,
                    Tuple.of(Lamp.Web.ListedPort, new Lamp.Web.ListedPort('opencode', 4096)))

        when: 'the application writes `ASK`, with `site` on 8080 and `opencode` on 4096, in that order'
            var written = settings.web(new Lamp.Web(LampWebPublishing.ASK, Tuple.of(Lamp.Web.ListedPort,
                    new Lamp.Web.ListedPort('site', 8080), new Lamp.Web.ListedPort('opencode', 4096))))

        then: 'it gets back `ASK` with both ports sorted by name, and reading again gives the same'
            written == new Lamp.Web(LampWebPublishing.ASK, Tuple.of(Lamp.Web.ListedPort,
                    new Lamp.Web.ListedPort('opencode', 4096), new Lamp.Web.ListedPort('site', 8080)))
            settings.web() == written

        and: 'the file ends with a new `[web]` table that holds `publish` and `ports`'
            Files.readString(lamp.resolve('oillamp.toml')) ==
                    'schema_version = 1\n\n[web]\npublish = "ask"\nports = { opencode = 4096, site = 8080 }\n'
    }

    def '`oillamp web` writes only the key it is given: `--publish` keeps the listed ports, and `--ports ""` lists none and keeps `publish`'() {
        reportInfo """
            Let's consider a person at a terminal who changes one setting of `[web]` at a time
            with `oillamp web <dir>`. `--publish <level>` writes `publish` and nothing else, so
            the listed ports stay. `--ports <name>=<port>,...` writes `ports` and nothing else, and
            an empty value, `--ports ""`, lists no ports at all. After writing, the command prints
            both values as the file now has them.

            Here the lamp's file has `publish = "ask"` and lists `opencode` on 4096. The person
            first switches publishing off, then lists no ports.
        """
        given: 'a lamp that has run once, whose `[web]` has `publish = "ask"` and lists `opencode` on 4096'
            var lamp = aLampThatHasRun('schema_version = 1\n\n[web]\npublish = "ask"\nports = { opencode = 4096 }\n')

        when: '`oillamp web <dir> --publish off` runs'
            var off = host.oillamp.run('web', lamp.toString(), '--publish', 'off')

        then: 'the file has `publish = "off"` and still lists `opencode` on 4096, and the command prints both'
            off.succeeded()
            Files.readString(lamp.resolve('oillamp.toml')) ==
                    'schema_version = 1\n\n[web]\npublish = "off"\nports = { opencode = 4096 }\n'
            off.console().contains('publish off, listed opencode 4096')

        when: '`oillamp web <dir> --ports ""` runs'
            var cleared = host.oillamp.run('web', lamp.toString(), '--ports', '')

        then: 'the file lists no ports and still has `publish = "off"`, and the command prints both'
            cleared.succeeded()
            Files.readString(lamp.resolve('oillamp.toml')) ==
                    'schema_version = 1\n\n[web]\npublish = "off"\nports = {}\n'
            cleared.console().contains('publish off, no ports listed')
    }

    def 'Writing `[web]` into the commented `oillamp.toml` of a new lamp changes the values of `publish` and `ports` in their columns, and no other line'() {
        reportInfo """
            A new lamp's `oillamp.toml` is a commented file that oillamp writes, and then the
            person's own. In its `[web]` table, `publish` and `ports` each have a comment beside
            them, in a column, that explains the value. `LampDirectory.web(Lamp.Web)` replaces the
            two values and nothing else: each comment stays in its column, and every other line of
            the file stays as it was.

            Here an application writes `LampWebPublishing.ANY` with no listed ports. `"any"` is
            three characters shorter than `"listed"`, and `{}` seventeen shorter than
            `{ opencode = 4096 }`, so each new value is followed by that many more spaces.
        """
        given: 'a new lamp that has run once, with the `oillamp.toml` oillamp wrote for it'
            var lamp = aLampThatHasRun()
            var file = lamp.resolve('oillamp.toml')
            var before = Files.readAllLines(file)

        when: 'an application writes `ANY` with no listed ports'
            var written = Lamp.at(lamp).launchedBy(host.launcher)
                    .web(new Lamp.Web(LampWebPublishing.ANY, Tuple.of(Lamp.Web.ListedPort)))

        then: 'it gets back `ANY` with no ports'
            written == new Lamp.Web(LampWebPublishing.ANY, Tuple.of(Lamp.Web.ListedPort))

        and: 'the file differs from before only in the two values, each padded so that its comment stays in its column'
            before.count { it.startsWith('publish = "listed"') } == 1
            before.count { it.startsWith('ports   = { opencode = 4096 }') } == 1
            Files.readAllLines(file) == before.collect {
                it.startsWith('publish = "listed"') ? 'publish = "any"   ' + it.substring('publish = "listed"'.length())
                : it.startsWith('ports   = { opencode = 4096 }') ? 'ports   = {}' + ' ' * 17 + it.substring('ports   = { opencode = 4096 }'.length())
                : it
            }
    }

    def '`LampDirectory.web` refuses to change `[web]` while a session runs, and the file stays as it was'() {
        reportInfo """
            A session reads `oillamp.toml` only as it starts, and publishes the listed ports then.
            A change to `[web]` while it runs would not reach it, and the file would no longer say
            what the session does. So `LampDirectory.web(Lamp.Web)` throws a `LampFailedException`
            with the status `LAMP_BUSY` while a session runs the lamp, and writes nothing. Reading
            with `LampDirectory.web()` works all the same.
        """
        given: 'a lamp whose `oillamp.toml` has no `[web]` table, and whose session runs'
            var lamp = aLampThatHasRun('schema_version = 1\n')
            var file = Files.readString(lamp.resolve('oillamp.toml'))
            var settings = Lamp.at(lamp).launchedBy(host.launcher)
            var session = aRunningSession(lamp)

        when: 'an application writes `OFF`'
            settings.web(new Lamp.Web(LampWebPublishing.OFF, Tuple.of(Lamp.Web.ListedPort)))

        then: 'it throws a `LampFailedException` with `LAMP_BUSY`, and the file is as it was'
            var busy = thrown(LampFailedException)
            busy.status() == LampExitStatus.LAMP_BUSY
            Files.readString(lamp.resolve('oillamp.toml')) == file

        and: 'reading still works, and gives `LISTED`'
            settings.web().publish() == LampWebPublishing.LISTED

        cleanup:
            host.oillamp.run('stop', lamp.toString())
            session?.join(20_000)
    }

    def '`LampDirectory.web` refuses to list #what, and leaves `oillamp.toml` as it was'() {
        reportInfo """
            What `LampDirectory.web(Lamp.Web)` writes must be a `[web]` table that the lamp's next
            session accepts, so it is checked by the same rules as the file itself: a name is up to
            32 lowercase letters, digits and dashes; a port is from 1024 to 65535 and none of
            oillamp's own in the sandbox: 3128, the egress proxy; 3129, the way to the model
            service; and 2828, where `lamp fetch` drives its headless Firefox; each name and each port is listed
            once; and no listed port is the port of one of the lamp's `[[network.forwards]]`.

            A listed port that breaks a rule is refused with a `LampFailedException` with the
            status `USAGE`, whose problem says which rule, and the file is not written. Here the
            lamp has a forward "llm" on port 8000.
        """
        given: 'a lamp that has run once, with a forward "llm" on port 8000'
            var lamp = aLampThatHasRun('''
                schema_version = 1

                [[network.forwards]]
                name   = "llm"
                port   = 8000
                target = "llm.corp.example.com:8000"
            '''.stripIndent())
            var file = Files.readString(lamp.resolve('oillamp.toml'))

        when: 'an application lists #what'
            Lamp.at(lamp).launchedBy(host.launcher).web(new Lamp.Web(LampWebPublishing.LISTED,
                    Tuple.of(Lamp.Web.ListedPort, ports.collect { new Lamp.Web.ListedPort(it.key, it.value) } as Lamp.Web.ListedPort[])))

        then: 'it throws a `LampFailedException` with `USAGE`, whose problem says "#says"'
            var refused = thrown(LampFailedException)
            refused.status() == LampExitStatus.USAGE
            refused.problem().whatHappened().contains(says)

        and: 'the file is as it was'
            Files.readString(lamp.resolve('oillamp.toml')) == file

        where:
            what                                              | ports              || says
            'port 2828, where `lamp fetch` drives Firefox'    | [fetch: 2828]      || '`lamp fetch` drives its headless Firefox'
            'port 3128, the sandbox\'s egress proxy'          | [proxy: 3128]      || 'egress proxy'
            'port 80, below 1024'                             | [site: 80]         || 'between 1024 and 65535'
            'a name with a capital letter and a space'        | ['My Site': 8080]  || 'lowercase letters, digits and dashes'
            'port 8080 under two names'                       | [a: 8080, b: 8080] || 'port 8080 is already listed as "a"'
            'port 8000, the forward "llm"'                    | [llm: 8000]        || 'the forward "llm"'
    }

    /** A lamp that has run one session; its `oillamp.toml` is the one oillamp writes, or `toml`. */
    private Path aLampThatHasRun(String toml = null) {
        var lamp = host.lampPath()
        if (toml != null) host.givenConfig(lamp, toml)
        assert host.oillamp.run('at', lamp.toString()).succeeded()
        lamp
    }

    /** Starts a session of `lamp` whose windows stay open, and returns its thread once it is up. */
    private Thread aRunningSession(Path lamp) {
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
        var reported = new java.util.concurrent.CopyOnWriteArrayList<LampEvent>()
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        var session = Thread.start { oillamp.run('at', lamp.toString()) }
        var deadline = System.currentTimeMillis() + 30_000
        while (!reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' }) {
            assert System.currentTimeMillis() < deadline : 'the session never came up'
            Thread.sleep(50)
        }
        session
    }
}
