package oillamp

import dev.lamp.LampExitStatus
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions

import java.time.Duration
import java.time.Instant

@Title('Setting up a lamp')
@Narrative('''
A *lamp* is a directory from which oillamp runs one sandbox. It holds:

- `oillamp.toml`, the settings file, with the network policy;
- `.oillamp/`, oillamp's own state: the lamp's identity `lamp.json`, its SSH keys, logs, sockets
  and screen recordings;
- `agent-lamp-<id>/`, the agent's home directory, which the sandbox mounts as `/home/agent` and
  which keeps the agent's work from one session to the next.

These scenarios are about what `oillamp at <dir>` creates in that directory, which directories it
refuses to turn into a lamp, how `oillamp remove` deletes a lamp again, and how
`oillamp recordings` lists, opens and deletes the screen recordings a lamp keeps.
''')
@Subject(OilLamp)
class Setting_Up_A_Lamp_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        // Key generation is the one step whose *result* matters rather than its exit code:
        // a lamp is only really set up if the key files exist afterwards.
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def '`oillamp at --dry-run` lists the steps of setting up a lamp, and does not create the lamp'() {
        reportInfo """
            Let's consider someone who runs oillamp for the first time. Setting up a lamp may
            install system packages with sudo, add a block of subordinate user ids to /etc/subuid
            (the spare user ids that rootless containers run on), generate SSH keys and create
            directories in their home. That is a lot to allow a tool they do not know yet. So
            `oillamp at <dir> --dry-run` prints the whole plan and carries out none of it.

            The printed plan can be trusted because it is not written separately from the setup.
            oillamp plans the setup as a list of steps, each with a description. A normal run
            carries out the steps; a dry run prints their descriptions instead.
        """
        given: 'a lamp path where no directory exists yet'
            var lamp = host.lampPath()
            !Files.exists(lamp)

        when: '`oillamp at <dir> --dry-run` runs'
            var outcome = host.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'it succeeds'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the lamp directory has not been created'
            !Files.exists(lamp)

        and: 'the plan includes writing `oillamp.toml`, the identity file `lamp.json`, the client and host SSH keys, and the agent home\'s `workspace`'
            outcome.steps().any { it.contains('oillamp.toml') }
            outcome.steps().any { it.contains('lamp.json') }
            outcome.steps().any { it.contains('client_ed25519') }
            outcome.steps().any { it.contains('host_ed25519') }
            outcome.steps().any { it.contains('agent-lamp-') && it.contains('workspace') }

        and: 'the plan also includes the steps that give `.oillamp/recordings` and `.oillamp/sockets/infra` to the container\'s infra user, uid 1001, so that the agent cannot write to them'
            outcome.steps().any { it.contains('recordings') && it.contains('container uid 1001') }
            outcome.steps().any { it.contains('sockets/infra') && it.contains('container uid 1001') }

        and: 'the output says that this was a dry run'
            outcome.console().contains('dry run')
    }

    def '`oillamp at --dry-run` prints the plan even where oillamp cannot become root; only the real `oillamp at` stops with `OIL-PKG-002`'() {
        reportInfo """
            Let's consider a machine without podman. Setting up a lamp there includes installing
            podman, which needs root rights. sudo wants a password, but there is no terminal to
            type it into, for example because oillamp was started from a script, and `pkexec`,
            which would ask for the password in a window of the desktop session, is not
            installed. A real `oillamp at` cannot go on, and reports `OIL-PKG-002`.

            `oillamp at --dry-run` must not report it. A dry run carries out no step, so whether
            oillamp can become root makes no difference to it. And on exactly such a machine, a
            dry run is what someone wants: to read the plan before deciding whether to give
            oillamp their password at all.

            The remedies of `OIL-PKG-002` name the two ways oillamp can ask for the password:
            sudo in a terminal, and `pkexec` in a desktop session. An earlier oillamp also told
            the user to drop `--no-install`, a flag that was never passed.
        """
        given: 'a machine without podman and without pkexec, where sudo needs a password and there is no terminal to ask for one'
            host.machine { it.withoutPodman().withoutPkexec().sudoNeedsPassword().interactive(false) }

        when: '`oillamp at <dir> --dry-run` runs'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')

        then: 'it succeeds without `OIL-PKG-002`, and the plan includes installing the packages'
            outcome.status() == LampExitStatus.SUCCESS
            !outcome.reported('OIL-PKG-002')
            outcome.stepKinds().contains('InstallPackages')

        and: 'the plan goes on to the end: the agent home, and the files written for each session, such as `runtime.env` and `known_hosts`'
            outcome.steps().any { it.contains('agent-lamp-') }
            outcome.steps().any { it.contains('runtime.env') }
            outcome.steps().any { it.contains('known_hosts') }

        when: '`oillamp at <dir>` runs without `--dry-run`'
            var real = host.oillamp.run('at', host.lampPath().toString())

        then: 'it reports `OIL-PKG-002`, because now it would really need root rights'
            real.reported('OIL-PKG-002')

        and: 'the remedies of `OIL-PKG-002` name a terminal, where sudo can ask for the password, and pkexec'
            var noRoot = real.errors().find { it.code().value() == 'OIL-PKG-002' }
            noRoot.fixes().any { it.description().contains('terminal') }
            noRoot.fixes().any { it.description().contains('pkexec') }

        and: 'no remedy of any problem mentions `--no-install`'
            real.problems().every { problem -> problem.fixes().every { !it.description().contains('--no-install') } }
    }

    def '`oillamp at` creates the agent\'s home in the lamp, and keeps the settings, keys, logs and recordings outside it'() {
        reportInfo """
            The most important rule of a lamp's layout is that it has two layers. The agent's
            home, `agent-lamp-<id>/`, is mounted into the sandbox, and the agent can change
            anything in it. Everything that controls or watches the agent sits next to that
            directory, never inside it: the settings file `oillamp.toml` with the network policy,
            and the SSH keys, the logs and the screen recordings in `.oillamp/`. If `oillamp.toml`
            were inside the agent's home, the agent could edit its own network policy.

            The scenario sets up a new lamp and then checks the directories on disk, rather than
            trusting the code that created them.
        """
        given: 'a lamp path where no directory exists yet'
            var lamp = host.lampPath()

        when: '`oillamp at <dir>` sets up a new lamp'
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'it succeeds without errors'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.errors().isEmpty()

        and: 'the agent home has a `workspace` directory for repositories and a `libs` directory for native libraries'
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            Files.isDirectory(agentHome.resolve('workspace'))
            Files.isDirectory(agentHome.resolve('libs'))

        and: '`oillamp.toml` is in the lamp directory, not in the agent home'
            Files.exists(lamp.resolve('oillamp.toml'))
            !Files.exists(agentHome.resolve('oillamp.toml'))

        and: 'the keys, the logs and the recordings are outside it too, in `.oillamp/`, and the agent home has no `.oillamp/` of its own'
            Files.isDirectory(lamp.resolve('.oillamp/keys'))
            Files.isDirectory(lamp.resolve('.oillamp/logs'))
            Files.isDirectory(lamp.resolve('.oillamp/recordings'))
            !Files.exists(agentHome.resolve('.oillamp'))

        and: 'only the owner may open `.oillamp/` (mode `rwx------`), so no other user on this machine can reach the sockets inside it'
            PosixFilePermissions.toString(Files.getPosixFilePermissions(lamp.resolve('.oillamp'))) == 'rwx------'

        and: 'the lamp has an SSH key pair of its own, rather than using the user\'s keys'
            Files.exists(lamp.resolve('.oillamp/keys/client_ed25519'))
            Files.exists(lamp.resolve('.oillamp/keys/host_ed25519.pub'))

        and: 'the sandbox\'s host key is already in the lamp\'s `known_hosts`, so ssh never asks the user to trust it'
            Files.readString(lamp.resolve('.oillamp/known_hosts')).startsWith('lamp-')
    }

    def 'oillamp gives the agent a `~/.bashrc` once, so that commands sent over ssh get the same environment as a login shell'() {
        reportInfo """
            The agent's environment, such as the proxy variables, `DISPLAY` and SDKMAN's `sdk`
            command, is set in /etc/profile.d/oillamp.sh. Bash reads that file, through
            /etc/profile, only in a login shell. The terminal that oillamp opens on the desktop is
            a login shell. But a command sent with `ssh <lamp> '<command>'`, which is how an agent
            drives a sandbox from a script, runs in a shell that is not a login shell. Without help, that command
            would have no proxy, no display and no `sdk`, and would behave differently from the
            same command typed into the terminal. The agent could not tell why, and would conclude
            that the network is broken.

            Bash does read `~/.bashrc` in a shell that ssh starts for a command, so oillamp writes
            a `~/.bashrc` that reads /etc/profile.d/oillamp.sh. It writes the file only when there
            is none yet. After that the file belongs to the agent, and oillamp never writes over it.

            The login banner tells the agent to read the guide `~/AGENTS.md`, so the scenario also
            checks that the guide is there. Unlike `~/.bashrc`, the guide describes the session's
            configuration, so oillamp writes it again for every session, over whatever is there.
        """
        given: 'a lamp path where no directory exists yet'
            var lamp = host.lampPath()

        when: '`oillamp at <dir>` sets up the lamp'
            host.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()

        then: 'the agent\'s `~/.bashrc` reads `/etc/profile.d/oillamp.sh`, the file a login shell gets its environment from'
            Files.readString(agentHome.resolve('.bashrc')).contains('/etc/profile.d/oillamp.sh')

        and: 'the guide `~/AGENTS.md` is where the login banner says it is'
            Files.readString(agentHome.resolve('AGENTS.md')).contains('# This machine')

        when: 'the agent replaces `~/.bashrc` and `~/AGENTS.md` with its own, and `oillamp at` runs again'
            Files.writeString(agentHome.resolve('.bashrc'), 'export EDITOR=vim\n')
            Files.writeString(agentHome.resolve('AGENTS.md'), 'my own notes\n')
            host.oillamp.run('at', lamp.toString())

        then: 'oillamp leaves the agent\'s `~/.bashrc` as it is'
            Files.readString(agentHome.resolve('.bashrc')) == 'export EDITOR=vim\n'

        and: '`~/AGENTS.md` is the guide again'
            Files.readString(agentHome.resolve('AGENTS.md')).contains('# This machine')
    }

    def '`oillamp remove --yes` deletes what oillamp created in a lamp, and without `--yes` only lists it'() {
        reportInfo """
            `rm -rf` cannot delete a lamp. Some of its files belong to the sandbox's infra user:
            the recordings and the infra sockets. That ownership is what stops the agent from
            changing the recording of its own screen. On the host, these files belong to a
            subordinate user id over which the owner of the lamp has no permission, so `rm -rf`
            stops halfway with "Permission denied". `oillamp remove <dir>` deletes them through
            podman's user namespace, where the owner does have that permission.

            It deletes only what oillamp created. The lamp directory itself is the user's: they
            chose its name, and they may keep notes or scripts of their own next to `oillamp.toml`.
            So the directory stays, unless removing oillamp's files leaves it empty.

            The command may be run from a script, where nobody could answer a prompt, so the flag
            `--yes` is the confirmation. Without it, `oillamp remove` lists what it would delete,
            and stops.
        """
        given: 'a lamp with the agent\'s work in its workspace, and a file of the user\'s own in the lamp directory'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            Files.writeString(agentHome.resolve('workspace').resolve('README.md'), 'the agent\'s work\n')
            Files.writeString(lamp.resolve('my-notes.txt'), 'not oillamp\'s\n')

        when: '`oillamp remove <dir>` runs without `--yes`'
            var asked = host.oillamp.run('remove', lamp.toString())

        then: 'it lists what it would delete, including the agent\'s `workspace`, says to add `--yes`, and stops'
            asked.status() == LampExitStatus.USAGE
            asked.console().contains('workspace')
            asked.console().contains('--yes')

        and: 'nothing has been deleted'
            Files.exists(lamp.resolve('oillamp.toml'))
            Files.exists(agentHome.resolve('workspace').resolve('README.md'))

        when: '`oillamp remove <dir> --yes` runs'
            var removed = host.oillamp.run('remove', lamp.toString(), '--yes')

        then: 'what oillamp created is gone: `oillamp.toml`, `.oillamp/` and the agent home'
            removed.status() == LampExitStatus.SUCCESS
            !Files.exists(lamp.resolve('oillamp.toml'))
            !Files.exists(lamp.resolve('.oillamp'))
            !Files.exists(agentHome)

        and: 'the user\'s own file is still there, and so is the lamp directory that holds it'
            Files.readString(lamp.resolve('my-notes.txt')) == 'not oillamp\'s\n'

        and: 'a second `oillamp remove` reports that the directory is no longer an oillamp lamp'
            host.oillamp.run('remove', lamp.toString(), '--yes')
                    .errors().first().whatHappened().contains('not an oillamp lamp')
    }

    def '`oillamp remove` still removes a lamp that `rm -rf` has already half deleted'() {
        reportInfo """
            Let's consider what usually happens first: a user tries `rm -rf` on a lamp. It
            deletes the identity file `.oillamp/lamp.json`, then stops at the sockets it has no
            permission over. What is left can no longer say which lamp it was, and still cannot
            be deleted by hand. If `oillamp remove` now answered "this is not an oillamp lamp",
            it would fail in exactly the situation it exists for.

            So `oillamp remove` works from what it finds on disk: any `agent-lamp-*` directory,
            `.oillamp/` and `oillamp.toml`. It cannot find the lamp's runtime directory, which lies
            outside the lamp and is named after the agent id that `lamp.json` held, so it leaves
            that alone.

            The sandbox may still be running, though, and its container is named after the same
            id. But every container that `oillamp at` starts also carries the lamp's path in its
            label `oillamp.lamp`. So `oillamp remove` asks podman for a container with that
            label, and refuses while one is running.
        """
        given: 'a lamp whose `.oillamp/lamp.json` was deleted by an attempted `rm -rf`'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            Files.delete(lamp.resolve('.oillamp/lamp.json'))

        when: 'its sandbox is still running, and `oillamp remove <dir> --yes` runs'
            host.machine { it.commandSucceeding('podman ps', """
                [{"Names":["oillamp-y62b5ihg"],"State":"running",
                  "Labels":{"oillamp.agent-id":"y62b5ihg","oillamp.lamp":"${lamp}"}}]
            """) }
            var refused = host.oillamp.run('remove', lamp.toString(), '--yes')

        then: 'podman finds the container by its `oillamp.lamp` label, and `oillamp remove` refuses, naming the container'
            refused.status() == LampExitStatus.LAMP_BUSY
            refused.errors().first().whatHappened().contains('oillamp-y62b5ihg')

        and: 'the agent home is still there, because the running container still uses it'
            Files.exists(agentHome)

        when: 'the sandbox has stopped, and `oillamp remove <dir> --yes` runs again'
            host.machine { it.commandSucceeding('podman ps', '[]') }
            var outcome = host.oillamp.run('remove', lamp.toString(), '--yes')

        then: 'it deletes `.oillamp/`, and the agent home, which it found by its `agent-lamp-` name'
            outcome.status() == LampExitStatus.SUCCESS
            !Files.exists(lamp.resolve('.oillamp'))
            !Files.exists(agentHome)
    }

    def '`oillamp remove`, given a lamp through a symbolic link, still finds the lamp\'s running sandbox'() {
        reportInfo """
            Lamps are often reached through a symbolic link, for example from ~/lamps to a
            directory on a larger disk. `oillamp at` follows the link, and puts the lamp's real
            path into the label `oillamp.lamp` of the sandbox's container.

            Once `.oillamp/lamp.json` is gone, that label is the only way for `oillamp remove` to
            find out whether the lamp's sandbox is still running. So it, too, must ask podman with
            the real path, not with the link that the user typed. With the link it would find no
            container, and would delete the home of a sandbox that is still running.
        """
        given: 'a lamp on another disk, reached through a link, whose `lamp.json` was deleted'
            var real = Files.createDirectories(host.home.resolve('big-disk/feature-x'))
            var link = host.lampPath()
            Files.createDirectories(link.parent)
            Files.createSymbolicLink(link, real)
            host.oillamp.run('at', link.toString())
            Files.delete(real.resolve('.oillamp/lamp.json'))

        and: 'its sandbox still running, with the real path in its `oillamp.lamp` label, as `oillamp at` sets it'
            host.machine { it.commandSucceeding('podman ps', """
                [{"Names":["oillamp-y62b5ihg"],"State":"running",
                  "Labels":{"oillamp.agent-id":"y62b5ihg","oillamp.lamp":"${real.toRealPath()}"}}]
            """) }

        when: '`oillamp remove --yes` is given the path of the link'
            var outcome = host.oillamp.run('remove', link.toString(), '--yes')

        then: 'it finds the running sandbox and refuses, and the lamp is still there'
            outcome.status() == LampExitStatus.LAMP_BUSY
            outcome.errors().first().whatHappened().contains('oillamp-y62b5ihg')
            Files.exists(real.resolve('oillamp.toml'))
    }

    def '`oillamp at`, given a link to a directory that does not exist yet, creates the lamp where the link points'() {
        reportInfo """
            Let's consider a user who creates the link first, with
            `ln -s /mnt/big/feature-x ~/lamps/feature-x`, and then runs
            `oillamp at ~/lamps/feature-x`. They want the lamp on the big disk. But
            /mnt/big/feature-x does not exist yet, so the link cannot be followed, and an earlier
            oillamp failed trying to create a directory where the link itself is. `oillamp at`
            creates the directory that the link points to instead, and leaves the link in place.
        """
        given: 'a link to a directory that does not exist yet'
            var real = host.home.resolve('big-disk/feature-x')
            var link = host.lampPath()
            Files.createDirectories(link.parent)
            Files.createSymbolicLink(link, real)

        when: '`oillamp at` is given the path of the link'
            var outcome = host.oillamp.run('at', link.toString())

        then: 'the lamp is created where the link points, and the link is still a link'
            outcome.status() == LampExitStatus.SUCCESS
            Files.exists(real.resolve('oillamp.toml'))
            Files.isSymbolicLink(link)
    }

    def '`oillamp remove` removes several lamps at once, but only if it can remove all of them'() {
        reportInfo """
            Lamps made for experiments pile up, and the natural way to clear them away is a shell
            pattern: `oillamp remove ~/lamps/test* --yes`. The shell turns the pattern into a list
            of directories, and `oillamp remove` takes all of them.

            A pattern can match more than was meant. So `oillamp remove` checks every directory
            before it deletes anything: if one of them is not a lamp, or its sandbox is still
            running, it removes none of them and names each problem. Without `--yes`, it lists
            what it would delete from each lamp, and suggests one command that removes them all.
        """
        given: 'two lamps, `test1` and `test2`, and a directory `test-notes` that is not a lamp but matches the same pattern'
            var first = host.lampPath('test1')
            var second = host.lampPath('test2')
            var notALamp = Files.createDirectories(host.lampPath('test-notes'))
            host.oillamp.run('at', first.toString())
            host.oillamp.run('at', second.toString())

        when: '`oillamp remove --yes` is given all three'
            var refused = host.oillamp.run('remove', first.toString(), notALamp.toString(),
                                              second.toString(), '--yes')

        then: 'it removes nothing, and names `test-notes` as the directory at fault'
            refused.status() == LampExitStatus.USAGE
            refused.errors().first().whatHappened().contains('test-notes')
            refused.console().contains('Nothing has been removed')
            Files.exists(first.resolve('oillamp.toml'))
            Files.exists(second.resolve('oillamp.toml'))

        when: '`oillamp remove` is given only the two lamps, without `--yes`'
            var asked = host.oillamp.run('remove', first.toString(), second.toString())

        then: 'it lists both, suggests one command that removes both, and removes nothing yet'
            asked.status() == LampExitStatus.USAGE
            asked.console().contains(first.toString())
            asked.console().contains(second.toString())
            asked.console().contains("oillamp remove ${first} ${second} --yes")
            Files.exists(first.resolve('oillamp.toml'))

        when: 'that suggested command runs'
            var removed = host.oillamp.run('remove', first.toString(), second.toString(), '--yes')

        then: 'both lamp directories are gone, and the output says that all 2 lamps were removed'
            removed.status() == LampExitStatus.SUCCESS
            !Files.exists(first)
            !Files.exists(second)
            removed.console().contains('All 2 lamps were removed')
    }

    def 'A command that works on one lamp refuses to be given several, rather than quietly using the first'() {
        reportInfo """
            Let's consider `oillamp stop ~/lamps/test*`. It looks as if it stops every lamp that
            the pattern matches, but `oillamp stop` works on one lamp. If it stopped the first
            and ignored the others, the user would believe that all of them had stopped. So
            `oillamp stop`, like every command that works on one lamp, refuses a list of several,
            and says how many it was given.
        """
        when: '`oillamp stop` is given two lamps'
            var outcome = host.oillamp.run('stop', host.lampPath('test1').toString(),
                                              host.lampPath('test2').toString())

        then: 'it refuses with a usage error: it works on one lamp, but was given 2'
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains('works on one lamp, but was given 2')
    }

    def '`oillamp remove` refuses to remove a lamp whose container still exists, and suggests `oillamp stop`'() {
        reportInfo """
            Let's consider a lamp whose container podman still knows about. If `oillamp remove`
            deleted the agent's home now, the session in the container would go on working in
            directories that no longer exist. And the container would outlive the files that
            describe it, so `oillamp stop` would have nothing left to find it with.

            So `oillamp remove` refuses, and suggests running `oillamp stop` first. A container
            can also be left behind when the oillamp process that ran its session died, and
            `oillamp stop` clears that away too, so the same advice fits both cases.
        """
        given: 'a lamp whose container podman still knows about'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            host.machine { it.commandSucceeding('podman container exists', '') }

        when: '`oillamp remove <dir> --yes` runs'
            var outcome = host.oillamp.run('remove', lamp.toString(), '--yes')

        then: 'it refuses because the container is still there, and suggests an `oillamp stop` command'
            outcome.status() == LampExitStatus.LAMP_BUSY
            outcome.errors().first().whatHappened().contains('still there')
            outcome.errors().first().fixes().any { it.command().orElse('').startsWith('oillamp stop') }

        and: '`oillamp.toml` is still there'
            Files.exists(lamp.resolve('oillamp.toml'))
    }

    def 'Running `oillamp at` again on a lamp keeps the agent\'s work, the user\'s settings and the lamp\'s identity'() {
        reportInfo """
            A lamp exists so that the agent comes back to the work it left: its repositories, its
            tool configuration, its shell history. The user's changes to `oillamp.toml` must last
            as well; a tool that reset the file on every run would be useless.

            So when `oillamp at` runs on a lamp that is already set up, it leaves the user's and
            the agent's files alone, and keeps the lamp's agent id and SSH keys.
        """
        given: 'a lamp that has been set up once'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()

        and: 'the user has changed the display width in `oillamp.toml`, and the agent has written a file into its workspace'
            var settings = lamp.resolve('oillamp.toml')
            Files.writeString(settings, Files.readString(settings).replace('width  = 1920', 'width  = 2560'))
            Files.writeString(agentHome.resolve('workspace/notes.md'), 'work in progress')
            var identityBefore = Files.readString(lamp.resolve('.oillamp/lamp.json'))
            var keyBefore = Files.readString(lamp.resolve('.oillamp/keys/client_ed25519'))
            var hostKeyBefore = Files.readString(lamp.resolve('.oillamp/keys/host_ed25519'))

        when: '`oillamp at` runs on that lamp again'
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'it succeeds'
            outcome.status() == LampExitStatus.SUCCESS

        and: '`oillamp.toml` still holds the new width'
            Files.readString(settings).contains('width  = 2560')

        and: 'the output shows the new desktop size, 2560x1080, so this session uses it'
            outcome.console().contains('2560x1080')

        and: 'the agent\'s file is unchanged'
            Files.readString(agentHome.resolve('workspace/notes.md')) == 'work in progress'

        and: '`lamp.json` still holds the same agent id, so it is the same sandbox'
            var agentId = identityBefore.find(/"agentId": "(\w+)"/) { it[1] }
            Files.readString(lamp.resolve('.oillamp/lamp.json')).contains(agentId)

        and: 'the lamp\'s SSH client key is unchanged'
            Files.readString(lamp.resolve('.oillamp/keys/client_ed25519')) == keyBefore

        and: 'so is its SSH host key'
            Files.readString(lamp.resolve('.oillamp/keys/host_ed25519')) == hostKeyBefore
    }

    def '`oillamp at` refuses a directory that already holds other files, unless given `--init`'() {
        reportInfo """
            `oillamp at ~/my-project` is easy to type by mistake. A lamp takes over its
            directory, so if oillamp went ahead, it would put its own files among those of a
            project the user is working on.

            So `oillamp at` refuses a directory that is not empty and is not already a lamp, with
            `OIL-LAMP-002`. The message shows what is in the way, and how to go ahead on purpose:
            with `--init`, oillamp sets up the lamp next to the files that are already there.
        """
        given: 'a directory with the user\'s own files in it'
            var directory = Files.createDirectories(tmp.resolve('home/dev/my-project'))
            Files.writeString(directory.resolve('README.md'), 'my actual project')
            Files.createDirectory(directory.resolve('src'))

        when: '`oillamp at` is given that directory'
            var outcome = host.oillamp.run('at', directory.toString())

        then: 'it refuses with `OIL-LAMP-002`'
            outcome.reported('OIL-LAMP-002')
            outcome.status() != LampExitStatus.SUCCESS

        and: 'it has created neither `.oillamp/` nor `oillamp.toml`'
            !Files.exists(directory.resolve('.oillamp'))
            !Files.exists(directory.resolve('oillamp.toml'))

        and: 'the message shows `README.md` among what is in the way'
            var problem = outcome.errors().first()
            problem.evidence().any { it.toString().contains('README.md') }

        and: 'it also suggests a command with `--init`'
            problem.fixes().any { it.command().orElse('').contains('--init') }

        when: '`oillamp at --init` is given the same directory'
            var confirmed = host.oillamp.run('at', directory.toString(), '--init')

        then: 'it sets up the lamp next to the user\'s files, and leaves them unchanged'
            confirmed.status() == LampExitStatus.SUCCESS
            Files.exists(directory.resolve('.oillamp/lamp.json'))
            Files.readString(directory.resolve('README.md')) == 'my actual project'
    }

    def '`oillamp at` refuses outright to make a lamp of the home directory itself or of a system directory'() {
        reportInfo """
            oillamp writes its state into a lamp's directory, gives part of it to the sandbox's
            infra user, and mounts another part into the sandbox as the agent's home. A lamp
            therefore needs a directory of its own. The user's home directory holds their keys,
            settings and projects, and /etc holds the configuration of the whole machine; neither
            is a place for a sandbox to live in.

            `--init`, which lets a lamp share a directory with other files, does not override
            this, and no other flag does either. Such a flag would be used once, by someone in a
            hurry, on the wrong directory.
        """
        when: '`oillamp at` is given the user\'s home directory itself'
            var outcome = host.oillamp.run('at', host.home.toString())

        then: 'it refuses with `OIL-LAMP-003`, and suggests a directory under `~/lamps` instead'
            outcome.reported('OIL-LAMP-003')
            outcome.errors().first().whatHappened().contains('home directory')
            outcome.errors().first().whatHappened().contains('lamps')

        when: '`oillamp at` is given a directory in /etc'
            var system = host.oillamp.run('at', '/etc/oillamp')

        then: 'it refuses that with `OIL-LAMP-003` too, naming /etc'
            system.reported('OIL-LAMP-003')
            system.errors().first().whatHappened().contains('/etc')
    }

    def '`oillamp at` refuses a lamp anywhere below a system directory: #path'() {
        reportInfo """
            oillamp will not set up a lamp in a directory that belongs to the operating system: /bin,
            /boot, /dev, /etc, /lib, /proc, /run, /sbin, /sys, /usr or /var, or any directory below
            one of them. `oillamp at` reports `OIL-LAMP-003` and names the system directory.

            The reason is what oillamp does to a lamp's directory. It creates `.oillamp/` and the
            agent home in it, sets their permissions, gives the recordings directory to the
            sandbox's infra user, and mounts the agent home into the sandbox. Inside /usr or /var,
            that would put files the agent controls among the system's programs and data.

            `oillamp at` compares the start of the path with each directory in that list. A
            directory missing from the list would let lamps in below it, and no other check would
            stop them. So the scenario asks for a lamp a few levels below /var, /usr and /boot, and
            expects `OIL-LAMP-003` to name the right system directory each time.
        """
        when: '`oillamp at` is given the path'
            var outcome = host.oillamp.run('at', path)

        then: 'it refuses with `OIL-LAMP-003`, and names the system directory the path is in'
            outcome.reported('OIL-LAMP-003')
            outcome.errors().first().whatHappened().contains("inside the system directory ${directory}")

        where:
            path                         || directory
            '/var/lib/lamps/feature-x'   || '/var'
            '/usr/local/lamps/feature-x' || '/usr'
            '/boot/lamp'                 || '/boot'
    }

    def '`oillamp at` mounts each socket directory into the container on its own, so the agent cannot swap one for a directory of its own'() {
        reportInfo """
            The sandbox and the host talk through Unix sockets in three directories under
            `.oillamp/sockets/`. `host/` holds the sockets that oillamp listens on outside the
            sandbox, such as the network proxy's. `agent/` holds the socket of the
            sandbox's ssh server. `infra/` holds the socket that carries the desktop picture the
            human watches. `infra/` belongs to the sandbox's infra user, so the agent cannot change
            what is inside it.

            The agent, however, runs as the lamp owner's own user id, and the owner also owns the
            parent directory `.oillamp/sockets/`. If that parent were mounted into the container,
            the agent could rename `infra/` and put a directory of its own in its place, with a
            desktop socket of its own in it, or a link to anywhere on the host. So `oillamp at`
            mounts each of the three directories on its own, and a mount point is something the
            agent cannot rename. It mounts `host/` read-only, because the sandbox only ever
            connects to the sockets in it.

            The scenario reads the mounts from the `podman run` command that
            `oillamp at --dry-run --verbose` prints.
        """
        when: '`oillamp at --dry-run --verbose` prints the `podman run` command it would use'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run', '--verbose')
            var podmanRun = outcome.stepDetails().find { it.startsWith('podman run') }

        then: 'the plan has a `podman run` step'
            podmanRun != null

        and: 'each socket directory is mounted on its own, and `host/` read-only'
            podmanRun.contains('.oillamp/sockets/host:/oillamp/sockets/host:ro')
            podmanRun.contains('.oillamp/sockets/agent:/oillamp/sockets/agent')
            podmanRun.contains('.oillamp/sockets/infra:/oillamp/sockets/infra')

        and: 'their parent `.oillamp/sockets` is not mounted at all'
            !podmanRun.contains('.oillamp/sockets:/oillamp/sockets')
    }

    def '`oillamp at` refuses to start a lamp whose socket directory was replaced by a link, and does not follow the link'() {
        reportInfo """
            Before oillamp mounted each socket directory on its own, the agent could replace one of
            them with a symbolic link to any of the user's directories, such as their home or
            ~/.ssh. At the lamp's next start, `oillamp at` would have followed the link: it would
            have given that directory to the sandbox's infra user and mounted it into the
            container.

            A lamp found in that state must not start. `oillamp at` refuses with `OIL-LAMP-011`,
            names the link and where it points, and changes neither, so that the user can look at
            what happened.
        """
        given: 'a lamp that has run before'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())

        and: 'whose `infra/` socket directory was moved aside and replaced with a link to the user\'s home'
            var infra = lamp.resolve('.oillamp/sockets/infra')
            Files.move(infra, lamp.resolve('.oillamp/sockets/moved-away'))
            Files.createSymbolicLink(infra, host.home)

        when: '`oillamp at` runs on the lamp again'
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'it refuses with `OIL-LAMP-011`, naming the link and the user\'s home it points to'
            outcome.status() != LampExitStatus.SUCCESS
            outcome.reported('OIL-LAMP-011')
            outcome.errors().first().whatHappened().contains(infra.toString())
            outcome.errors().first().whatHappened().contains(host.home.toString())

        and: 'it gave nothing to the infra user, and started no container'
            !outcome.stepKinds().contains('ChownForContainer')
            !outcome.stepKinds().contains('RunContainer')

        and: 'the link is still there for the user to look at'
            Files.isSymbolicLink(infra)

        cleanup: 'the link, which the temporary directory\'s own cleanup would otherwise follow'
            Files.deleteIfExists(infra)
    }

    def '`oillamp at` refuses a lamp on a filesystem that cannot hold Unix sockets, before it creates anything'() {
        reportInfo """
            Every channel between the host and the sandbox, such as the ssh shell, the desktop
            picture and the network proxy, goes through a Unix domain socket inside the lamp
            directory. A Unix domain socket is a special file through which two processes on the
            same machine talk to each other. Not every filesystem can hold one: network
            filesystems such as NFS cannot, and neither can the FAT filesystems on most USB sticks.

            If oillamp found this out halfway through starting the container, the error would make
            no sense to the user. So `oillamp at` checks the filesystem first, and reports
            `OIL-LAMP-005` with the type of the filesystem and a suggestion where to put the lamp
            instead.
        """
        given: 'a lamp path on an NFS share'
            host.machine { it.lampFilesystemType('nfs') }

        when: '`oillamp at` is given that path'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'it reports `OIL-LAMP-005`, naming `nfs`, and has not created the lamp directory'
            outcome.reported('OIL-LAMP-005')
            outcome.errors().first().whatHappened().contains('nfs')
            !Files.exists(host.lampPath())
    }

    def '`oillamp at` refuses a lamp created by a newer oillamp, rather than risk damaging it'() {
        reportInfo """
            A lamp holds the agent's accumulated work. A future version of oillamp may change the
            lamp's layout, and an older oillamp that read such a lamp could misread or destroy that
            work. So the identity file `.oillamp/lamp.json` records a schema version. When it is
            higher than any this oillamp understands, `oillamp at` stops with `OIL-LAMP-004`, and
            gives the only useful advice: upgrade oillamp.
        """
        given: 'a lamp whose `lamp.json` declares schema version 99, written by oillamp 9.0.0'
            var lamp = Files.createDirectories(host.lampPath())
            Files.createDirectories(lamp.resolve('.oillamp'))
            Files.writeString(lamp.resolve('.oillamp/lamp.json'), '''
                {
                  "schemaVersion": 99,
                  "agentId": "k3v7x2ab",
                  "createdAt": "2027-01-01T00:00:00Z",
                  "createdBy": "oillamp 9.0.0"
                }
            '''.stripIndent())

        when: '`oillamp at` runs on it'
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'it reports `OIL-LAMP-004`, and suggests upgrading oillamp'
            outcome.reported('OIL-LAMP-004')
            outcome.errors().first().fixes().any { it.description().contains('upgrade') }
    }

    /**
     *  Creates the recording file of a session, as the screen recorder would leave it: named
     *  after the session's start time, and last modified `ran` later, when the recording stopped.
     */
    private static Path givenRecording(Path lamp, String session, Duration ran, int kilobytes) {
        Path file = lamp.resolve('.oillamp/recordings').resolve(session + '.mkv')
        Files.createDirectories(file.parent)
        Files.write(file, new byte[kilobytes * 1024])
        Files.setLastModifiedTime(file, FileTime.from(
                Instant.parse(session[0..3] + '-' + session[4..5] + '-' + session[6..7]
                            + 'T' + session[9..10] + ':' + session[11..12] + ':' + session[13..14] + 'Z')
                        .plus(ran)))
        file
    }

    def '`oillamp recordings` lists each recording of a lamp with how long it ran and its size'() {
        reportInfo """
            With recording switched on, every session writes a video of the desktop into
            `.oillamp/recordings/`, named after the session's start time, such as
            `20260115-100000.mkv`. wf-recorder, the program that records the screen, writes to the
            file until the session stops it, so the file's modification time is when the
            recording ended. `oillamp recordings <dir>` lists each file with how long it ran and
            its size. It takes the start from the file's creation time where the filesystem keeps
            one, and otherwise from the session time in the name. It never opens the video itself:
            oillamp does not require ffmpeg on the host, and a listing that needed ffprobe would
            fail on every host without it.

            A file in there whose name is not a session time, such as `notes.mkv`, is somebody's
            own file. The listing shows it too, rather than hiding or deleting it, but with `-`
            for its duration: oillamp did not record it, so its file times say nothing about how
            long a recording ran.

            Given `--open` and a session that was never recorded, `oillamp recordings` reports
            `OIL-LAMP-009` and names the sessions that were.
        """
        given: 'a lamp with a 5-minute and a 90-second recording, and a file `notes.mkv` that is not named after a session'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            givenRecording(lamp, '20260115-100000', Duration.ofMinutes(5), 2048)
            givenRecording(lamp, '20260115-140000', Duration.ofSeconds(90), 512)
            Files.write(lamp.resolve('.oillamp/recordings/notes.mkv'), new byte[1024])

        when: '`oillamp recordings <dir>` runs'
            var listed = host.oillamp.run('recordings', lamp.toString())

        then: 'it lists each recording with how long it ran and its size'
            listed.status() == LampExitStatus.SUCCESS
            listed.console().contains('20260115-100000.mkv')
            listed.console().contains('5m00s')
            listed.console().contains('2.0 MB')
            listed.console().contains('1m30s')

        and: 'it lists `notes.mkv` too, with `-` for its duration, and counts 3 files'
            var notes = listed.console().readLines().find { it.contains('notes.mkv') }
            notes.trim().split(/\s+/)[1] == '-'
            listed.console().contains('3 recording(s)')

        when: '`oillamp recordings <dir> --open` is given a session that was never recorded'
            var missing = host.oillamp.run('recordings', lamp.toString(), '--open', '20200101-000000')

        then: 'it reports `OIL-LAMP-009`, and names a session that was recorded'
            missing.status() == LampExitStatus.USAGE
            missing.reported('OIL-LAMP-009')
            missing.errors().first().whyItMatters().contains('20260115-100000')
    }

    def '`oillamp recordings --open <session>` opens that session\'s recording in the desktop\'s video player'() {
        reportInfo """
            `oillamp recordings <dir> --open <session>` hands the recording of that session to
            `xdg-open`, which opens it in the desktop's default video player, as a double-click on
            the file would. `--open=<session>` means the same. oillamp does not wait for the player
            to close, because with some players `xdg-open` returns only once the video is closed.
        """
        given: 'a desktop with `xdg-open`, and a lamp with one recording'
            host.machine { it.withPackages('xdg-utils') }
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            var recording = givenRecording(lamp, '20260115-100000', Duration.ofMinutes(5), 2048)

        when: 'the session is given to `--open` once as a separate argument, and once as `--open=<session>`'
            var spaced = host.oillamp.run('recordings', lamp.toString(), '--open', '20260115-100000')
            var joined = host.oillamp.run('recordings', lamp.toString(), '--open=20260115-100000')

        then: 'both open the same recording file'
            spaced.status() == LampExitStatus.SUCCESS
            spaced.console().contains('opened ' + recording)
            joined.status() == LampExitStatus.SUCCESS
            joined.console().contains('opened ' + recording)
    }

    def '`oillamp recordings --prune` deletes right away what the next session would delete under the retention limits'() {
        reportInfo """
            Two settings in `oillamp.toml` limit the recordings a lamp keeps:
            `recording.max_age_days` and `recording.max_total_gb`. oillamp applies these
            retention limits at the start of every session, so that a lamp used day after day
            does not fill the host's disk without warning. A user who wants the disk space back
            today should not have to start a sandbox for it, so `oillamp recordings <dir> --prune`
            applies the same limits right away.

            The start of a session and `--prune` use the same code with the same settings. Two
            separate implementations of "keep 14 days" would sooner or later disagree about what
            it means.

            The recordings belong to the sandbox's infra user, so `--prune` deletes them through
            podman's user namespace, as `oillamp remove` does.
        """
        given: 'on 1 February 2026, a lamp with the default age limit of 14 days, a recording from December and one from 31 January'
            var lamp = host.lampPath()
            host.machine { it.clockAt(Instant.parse('2026-02-01T12:00:00Z')) }
            host.oillamp.run('at', lamp.toString())
            var old = givenRecording(lamp, '20251201-090000', Duration.ofMinutes(20), 64)
            var recent = givenRecording(lamp, '20260131-090000', Duration.ofMinutes(20), 64)

        when: '`oillamp recordings <dir> --prune` runs'
            var pruned = host.oillamp.run('recordings', lamp.toString(), '--prune')

        then: 'it deletes the recording from December and keeps the one from 31 January'
            pruned.status() == LampExitStatus.SUCCESS
            !Files.exists(old)
            Files.exists(recent)

        when: '`--prune` runs again, with no recording older than 14 days left'
            var again = host.oillamp.run('recordings', lamp.toString(), '--prune')

        then: 'it says that nothing is beyond the limits, and the recent recording is still there'
            again.status() == LampExitStatus.SUCCESS
            again.console().contains('nothing is beyond')
            Files.exists(recent)
    }

    def '`oillamp recordings --prune` keeps the newest recordings within `recording.max_total_gb` and deletes the older ones'() {
        reportInfo """
            With recording switched on, every session writes a video of the desktop into
            `.oillamp/recordings/`. Let's consider a lamp that is used every day: after a few weeks
            its recordings take up many gigabytes. Two settings limit them,
            `recording.max_age_days` and `recording.max_total_gb`. At the start of every session,
            and on `oillamp recordings --prune`, oillamp deletes every recording outside either
            limit.

            This scenario is about the size limit. The newest recordings are the ones a person is
            most likely to watch, so those are kept. oillamp sorts the recordings from newest to
            oldest, by the time they stopped, and adds up their sizes in that order. Each recording
            that would bring the total above the limit is deleted. With the order the other way
            round, the same code would keep the oldest recordings and delete the newest.

            The lamp here has `max_total_gb = 1` and `max_age_days = 0`, which means no age limit,
            so only the size decides. There are three recordings of 400 MB each, from 13, 14 and 15
            January. The two newest add up to 800 MB and are kept. With the oldest, the total would
            be 1200 MB, more than 1 GB (1024 MB), so the oldest is deleted.

            The recordings are sparse files: each reports a size of 400 MB but takes up no space on
            the disk.
        """
        given: 'a lamp that keeps at most one gigabyte of recordings, of any age'
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [recording]
                max_age_days = 0
                max_total_gb = 1
                '''.stripIndent())
            host.oillamp.run('at', lamp.toString())

        and: 'three recordings of 400 MB each, from three days in a row'
            var oldest = givenRecordingOfSize(lamp, '20260113-100000', 400)
            var middle = givenRecordingOfSize(lamp, '20260114-100000', 400)
            var newest = givenRecordingOfSize(lamp, '20260115-100000', 400)

        when: '`oillamp recordings <dir> --prune` runs'
            var pruned = host.oillamp.run('recordings', lamp.toString(), '--prune')

        then: 'the two newest fit into the gigabyte together, and are kept'
            pruned.status() == LampExitStatus.SUCCESS
            Files.exists(newest)
            Files.exists(middle)

        and: 'with the oldest, the total would be 1200 MB, so the oldest is deleted'
            !Files.exists(oldest)
    }

    /**
     *  Creates the recording file of a session with the given size, without writing its bytes: a
     *  sparse file reports its full size but takes up no space on the disk. The recording stopped
     *  ten minutes after the session began.
     */
    private static Path givenRecordingOfSize(Path lamp, String session, int megabytes) {
        Path file = givenRecording(lamp, session, Duration.ofMinutes(10), 0)
        new RandomAccessFile(file.toFile(), 'rw').withCloseable { it.setLength(megabytes * 1024L * 1024L) }
        Files.setLastModifiedTime(
            file,
            FileTime.from(
                Instant.parse(
                        session[0..3] + '-' +
                        session[4..5] + '-' +
                        session[6..7] + 'T' +
                        session[9..10] + ':' +
                        session[11..12] + ':' +
                        session[13..14] + 'Z'
                )
                .plus(Duration.ofMinutes(10))
            )
        )
        return file
    }

    def '`oillamp at` refuses a lamp path that contains a colon, because podman could not mount it'() {
        reportInfo """
            oillamp mounts parts of the lamp into the sandbox with
            `podman run --volume HOST:CONTAINER`, which separates the parts of its value with
            colons. podman would read a colon in the lamp's path as one of those separators, and
            the sandbox would get the wrong directories, or fail to start with a message about
            something else entirely. So `oillamp at` refuses such a path with `OIL-LAMP-001`
            before it creates anything.
        """
        given: 'a lamp path `~/lamps/feature:x`'
            var lamp = host.home.resolve('lamps').resolve('feature:x')

        when: '`oillamp at` is given that path'
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'it refuses with `OIL-LAMP-001`, says that the path contains a colon, and has not created the lamp directory'
            outcome.reported('OIL-LAMP-001')
            outcome.errors().first().whatHappened().contains('colon')
            !Files.exists(lamp)
    }
}
