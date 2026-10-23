package oillamp

import dev.lamp.LampExitStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions

import java.time.Duration
import java.time.Instant

/**
 *  A "lamp" is one directory on disk that holds a sandbox: its settings, its state, and the
 *  agent's persistent home. These scenarios are about what `oillamp at <dir>` puts there, and -
 *  more importantly - about what it refuses to put there.
 */
class SettingUpALampSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        // Key generation is the one step whose *result* matters rather than its exit code:
        // a lamp is only really set up if the key files exist afterwards.
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'Oillamp\'s `at --dry-run` shows the user everything that would happen, and changes nothing'() {
        reportInfo """
            oillamp installs system packages, edits /etc/subuid - the file that grants a user the
            block of spare user-id numbers rootless containers need - writes SSH keys, and creates
            directories in the user's home. That is a great deal of trust to ask of somebody
            running a tool for the first time, so there is a mode that shows the complete plan
            before any of it happens.

            What makes this trustworthy is that --dry-run is not a separate code path: setup is
            modelled as a list of described changes, and the dry run simply announces them
            instead of carrying them out. There is no second implementation to drift.
        """
        given: 'a directory that does not exist yet'
            var lamp = host.lampPath()
            !Files.exists(lamp)

        when: 'the user asks what oillamp would do'
            var outcome = host.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'it succeeds'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the directory is still not there, and neither is anything else'
            !Files.exists(lamp)

        and: 'the plan covers the settings file, the identity, the keys and the agent home'
            outcome.steps().any { it.contains('oillamp.toml') }
            outcome.steps().any { it.contains('lamp.json') }
            outcome.steps().any { it.contains('client_ed25519') }
            outcome.steps().any { it.contains('host_ed25519') }
            outcome.steps().any { it.contains('agent-lamp-') && it.contains('workspace') }

        and: 'including the two directories the agent must not be able to write to'
            outcome.steps().any { it.contains('recordings') && it.contains('container uid 1001') }
            outcome.steps().any { it.contains('sockets/infra') && it.contains('container uid 1001') }

        and: 'the user is told plainly that nothing was done'
            outcome.console().contains('dry run')
    }

    def 'Oillamp\'s `at --dry-run` prints the plan even when sudo could not be used to carry it out'() {
        reportInfo """
            Found by running the real binary: on a machine where sudo would need a password and
            there is no terminal to ask in, --dry-run was refusing with "cannot run sudo".

            That is answering a different question. The user asked what oillamp *would* do, and a
            dry run changes nothing, so whether sudo happens to work right now is irrelevant to
            it. Being able to inspect the plan from a script, or before deciding whether to give
            the tool your password at all, is a large part of why the dry run exists.
        """
        given: 'a machine that needs packages installed, where sudo cannot currently be used'
            host.machine { it.withoutPodman().sudoNeedsPassword().interactive(false) }

        when: 'the user asks what oillamp would do'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')

        then: 'they get the plan, not a complaint about sudo'
            outcome.status() == LampExitStatus.SUCCESS
            !outcome.reported('OIL-PKG-002')
            outcome.stepKinds().contains('InstallPackages')

        and: 'the plan is complete, all the way to the per-session files'
            outcome.steps().any { it.contains('agent-lamp-') }
            outcome.steps().any { it.contains('runtime.env') }
            outcome.steps().any { it.contains('known_hosts') }

        when: 'but they actually ask oillamp to do it'
            var real = host.oillamp.run('at', host.lampPath().toString())

        then: 'then the missing sudo is the blocker it really is'
            real.reported('OIL-PKG-002')
    }

    def 'Setting up a new lamp gives the agent a home and keeps everything else out of it'() {
        reportInfo """
            This is the design's central idea, and the one thing that must never be got wrong:
            the lamp is two layers. The agent gets `agent-lamp-<id>/`, mounted as its home.
            Everything that *governs or observes* the agent - the network policy, the SSH keys,
            the logs, the screen recordings - sits beside it, never inside it.

            If the policy file ever ended up inside the agent's directory, the agent could edit
            its own firewall rules. So this scenario checks the separation directly rather than
            trusting that the code that creates the directories got it right.
        """
        given:
            var lamp = host.lampPath()

        when: 'the user sets up a lamp'
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'it succeeds'
            outcome.status() == LampExitStatus.SUCCESS
            outcome.errors().isEmpty()

        and: 'the agent has a home with somewhere for code and somewhere for native libraries'
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            Files.isDirectory(agentHome.resolve('workspace'))
            Files.isDirectory(agentHome.resolve('libs'))

        and: 'the settings file exists, and is NOT inside the agent home'
            Files.exists(lamp.resolve('oillamp.toml'))
            !Files.exists(agentHome.resolve('oillamp.toml'))

        and: 'neither are the keys, the logs or the recordings'
            Files.isDirectory(lamp.resolve('.oillamp/keys'))
            Files.isDirectory(lamp.resolve('.oillamp/logs'))
            Files.isDirectory(lamp.resolve('.oillamp/recordings'))
            !Files.exists(agentHome.resolve('.oillamp'))

        and: 'the state directory is readable only by its owner, so no other user on this machine can reach the sockets inside it'
            PosixFilePermissions.toString(Files.getPosixFilePermissions(lamp.resolve('.oillamp'))) == 'rwx------'

        and: 'the lamp got its own SSH key pair rather than borrowing the user\'s'
            Files.exists(lamp.resolve('.oillamp/keys/client_ed25519'))
            Files.exists(lamp.resolve('.oillamp/keys/host_ed25519.pub'))

        and: 'the user is never asked to trust a host key, because it is pinned in advance'
            Files.readString(lamp.resolve('.oillamp/known_hosts')).startsWith('lamp-')
    }

    def 'The agent gets the same environment however its shell was started'() {
        reportInfo """
            /etc/profile is read by *login* shells only. The terminal oillamp opens gets one, so
            everything works there - but `ssh <lamp> 'some command'`, which is how an agent drives
            a sandbox from a script, does not. That shell would start with no proxy variables, no
            DISPLAY and no `sdk`, and the same command would then behave differently depending on
            how it was invoked. An agent cannot diagnose that; it reports "the network is broken".

            Bash reads ~/.bashrc in exactly that case, so oillamp writes one. It is written once
            and never again: from the moment it exists it belongs to the agent.

            The guide is checked here for a related reason. The in-sandbox banner tells the agent
            to read ~/AGENTS.md, and for a long time nothing put a file there.
        """
        given:
            var lamp = host.lampPath()

        when:
            host.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()

        then: 'a non-login shell is sent to the same place a login shell gets its environment from'
            Files.readString(agentHome.resolve('.bashrc')).contains('/etc/profile.d/oillamp.sh')

        and: 'and the guide is where the banner says it is'
            Files.readString(agentHome.resolve('AGENTS.md')).contains('# This machine')

        when: 'the agent makes the file its own'
            Files.writeString(agentHome.resolve('.bashrc'), 'export EDITOR=vim\n')
            host.oillamp.run('at', lamp.toString())

        then: 'oillamp does not write over it'
            Files.readString(agentHome.resolve('.bashrc')) == 'export EDITOR=vim\n'

        and: 'while the guide, which describes this session, is rewritten'
            Files.readString(agentHome.resolve('AGENTS.md')).contains('# This machine')
    }

    def 'Oillamp\'s `remove` command deletes only what oillamp made, and only when given `--yes`'() {
        reportInfo """
            A lamp cannot be deleted with `rm -rf`. Part of one belongs to the sandbox's second
            user - the infra sockets and the recordings, which is what stops the agent tampering
            with the recording of its own screen - and those map onto subordinate ids the human
            who owns the directory has no permission over. `rm -rf` stops halfway with "Permission
            denied" on a path they have never heard of. So there is a command, and it goes through
            podman's user namespace.

            It deletes what oillamp created and nothing else. The lamp directory is the user's:
            they named it, and they may well have put notes or scripts of their own beside
            oillamp.toml. So the root survives - unless removal leaves it empty, in which case
            keeping it would just be litter.

            There is no prompt to answer in a tool that may be driven by a script, so --yes is the
            confirmation. Without it the command prints what would go and stops.
        """
        given: 'a lamp with work in it, and a file of the user\'s own beside it'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            Files.writeString(agentHome.resolve('workspace').resolve('README.md'), 'the agent\'s work\n')
            Files.writeString(lamp.resolve('my-notes.txt'), 'not oillamp\'s\n')

        when: 'the user asks without saying --yes'
            var asked = host.oillamp.run('remove', lamp.toString())

        then: 'it says what would go, names the agent\'s work, and stops'
            asked.status() == LampExitStatus.USAGE
            asked.console().contains('workspace')
            asked.console().contains('--yes')

        and: 'nothing has been removed'
            Files.exists(lamp.resolve('oillamp.toml'))
            Files.exists(agentHome.resolve('workspace').resolve('README.md'))

        when: 'the user says it'
            var removed = host.oillamp.run('remove', lamp.toString(), '--yes')

        then: 'everything oillamp made is gone, the agent home included'
            removed.status() == LampExitStatus.SUCCESS
            !Files.exists(lamp.resolve('oillamp.toml'))
            !Files.exists(lamp.resolve('.oillamp'))
            !Files.exists(agentHome)

        and: 'and what was never oillamp\'s is not, nor is the directory holding it'
            Files.readString(lamp.resolve('my-notes.txt')) == 'not oillamp\'s\n'

        and: 'the directory that is not a lamp any more says so if asked again'
            host.oillamp.run('remove', lamp.toString(), '--yes')
                    .errors().first().whatHappened().contains('not an oillamp lamp')
    }

    def 'Oillamp\'s `remove` command still removes a lamp that someone already half deleted with `rm -rf`'() {
        reportInfo """
            This is the sequence that actually happens. A user reaches for `rm -rf` first, it
            deletes lamp.json and then stops on the sockets it has no permission over, and what is
            left is a directory that can no longer say which lamp it was - and still cannot be
            deleted. Being told "this is not an oillamp lamp" at that point would be failing them
            at the exact moment the command exists for.

            So removal works from what is on disk rather than from an identity: any agent-lamp-*
            directory, the state directory, the config. Only the two things named after the lamp's
            id - its container and its runtime directory - are skipped, because without the id
            there is nothing to name them with.
        """
        given: 'a lamp that a hand-rolled deletion got halfway through'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()
            Files.delete(lamp.resolve('.oillamp/lamp.json'))

        when: 'its sandbox is still up, which the lamp can no longer say'
            host.machine { it.commandSucceeding('podman ps', """
                [{"Names":["oillamp-y62b5ihg"],"State":"running",
                  "Labels":{"oillamp.agent-id":"y62b5ihg","oillamp.lamp":"${lamp}"}}]
            """) }
            var refused = host.oillamp.run('remove', lamp.toString(), '--yes')

        then: 'podman is asked instead, by the label that carries the lamp path'
            refused.status() == LampExitStatus.LAMP_BUSY
            refused.errors().first().whatHappened().contains('oillamp-y62b5ihg')

        and: 'so nothing was pulled out from under a container that is still running'
            Files.exists(agentHome)

        when: 'the sandbox is gone'
            host.machine { it.commandSucceeding('podman ps', '[]') }
            var outcome = host.oillamp.run('remove', lamp.toString(), '--yes')

        then: 'removal works anyway, and takes the agent home it found by name'
            outcome.status() == LampExitStatus.SUCCESS
            !Files.exists(lamp.resolve('.oillamp'))
            !Files.exists(agentHome)
    }

    def 'A lamp reached through a symbolic link is the same lamp to every command'() {
        reportInfo """
            Lamps are often reached through a link, for example ~/lamps pointing at a larger disk.
            `oillamp at` follows the link, and labels the sandbox's container with the lamp's real
            path. `oillamp remove` asks podman for a container with that label, because it is the
            only thing left to ask once lamp.json is gone. It used to ask with the path as typed,
            the link, which never matched, so a running sandbox's home could be deleted from
            under it.
        """
        given: 'a lamp on another disk, reached through a link'
            var real = Files.createDirectories(host.home.resolve('big-disk/feature-x'))
            var link = host.lampPath()
            Files.createDirectories(link.parent)
            Files.createSymbolicLink(link, real)
            host.oillamp.run('at', link.toString())
            Files.delete(real.resolve('.oillamp/lamp.json'))

        and: 'its sandbox still running, labelled with the real path as `at` labels it'
            host.machine { it.commandSucceeding('podman ps', """
                [{"Names":["oillamp-y62b5ihg"],"State":"running",
                  "Labels":{"oillamp.agent-id":"y62b5ihg","oillamp.lamp":"${real.toRealPath()}"}}]
            """) }

        when: 'the user removes it by the path they know'
            var outcome = host.oillamp.run('remove', link.toString(), '--yes')

        then: 'the running sandbox is found, and nothing is removed'
            outcome.status() == LampExitStatus.LAMP_BUSY
            outcome.errors().first().whatHappened().contains('oillamp-y62b5ihg')
            Files.exists(real.resolve('oillamp.toml'))
    }

    def 'A link to a directory that does not exist yet makes the lamp where it points'() {
        reportInfo """
            A user who makes the link first, `ln -s /mnt/big/feature-x ~/lamps/feature-x`, and
            then runs `oillamp at ~/lamps/feature-x` means the lamp to live on the big disk. The
            link cannot be followed yet, because what it points to does not exist, and oillamp
            used to try to create a directory where the link already was, and report that as a
            bug in itself.
        """
        given: 'a link to a directory that does not exist yet'
            var real = host.home.resolve('big-disk/feature-x')
            var link = host.lampPath()
            Files.createDirectories(link.parent)
            Files.createSymbolicLink(link, real)

        when:
            var outcome = host.oillamp.run('at', link.toString())

        then: 'the lamp is made where the link points'
            outcome.status() == LampExitStatus.SUCCESS
            Files.exists(real.resolve('oillamp.toml'))
            Files.isSymbolicLink(link)
    }

    def 'Oillamp\'s `remove` command removes several lamps at once, and only if it can remove every one of them'() {
        reportInfo """
            Lamps made for experiments pile up, and the natural way to clean them up is a shell
            pattern: `oillamp remove ~/lamps/test* --yes`. The shell expands that into several
            directories. oillamp used to act on the first and ignore the rest without a word, so
            the user had to run it again for each.

            Now `remove` takes them all. A pattern may match more than was meant, so every
            directory is checked before anything is deleted: if any is not a lamp, or is still
            running, nothing is removed and each problem is named. Without --yes it lists what
            would go for each lamp, and the command it suggests names all of them.
        """
        given: 'two lamps, and a directory beside them that the pattern also matches'
            var first = host.lampPath('test1')
            var second = host.lampPath('test2')
            var notALamp = Files.createDirectories(host.lampPath('test-notes'))
            host.oillamp.run('at', first.toString())
            host.oillamp.run('at', second.toString())

        when: 'the pattern catches the directory that is not a lamp'
            var refused = host.oillamp.run('remove', first.toString(), notALamp.toString(),
                                              second.toString(), '--yes')

        then: 'nothing is removed, and the directory at fault is named'
            refused.status() == LampExitStatus.USAGE
            refused.errors().first().whatHappened().contains('test-notes')
            refused.console().contains('Nothing has been removed')
            Files.exists(first.resolve('oillamp.toml'))
            Files.exists(second.resolve('oillamp.toml'))

        when: 'only the lamps are named, without --yes'
            var asked = host.oillamp.run('remove', first.toString(), second.toString())

        then: 'it lists both, and offers one command that removes both'
            asked.status() == LampExitStatus.USAGE
            asked.console().contains(first.toString())
            asked.console().contains(second.toString())
            asked.console().contains("oillamp remove ${first} ${second} --yes")
            Files.exists(first.resolve('oillamp.toml'))

        when: 'the user confirms'
            var removed = host.oillamp.run('remove', first.toString(), second.toString(), '--yes')

        then: 'both are gone'
            removed.status() == LampExitStatus.SUCCESS
            !Files.exists(first)
            !Files.exists(second)
            removed.console().contains('All 2 lamps were removed')
    }

    def 'Oillamp refuses several lamps for a command that works on one, rather than quietly using the first'() {
        reportInfo """
            `oillamp stop ~/lamps/test*` looks as if it stops every matching lamp. Stopping the
            first and ignoring the rest would leave the user believing the others were stopped.
            So every command that takes one lamp says it was given several, and names them.
        """
        when:
            var outcome = host.oillamp.run('stop', host.lampPath('test1').toString(),
                                              host.lampPath('test2').toString())

        then:
            outcome.status() == LampExitStatus.USAGE
            outcome.errors().first().whatHappened().contains('works on one lamp, but was given 2')
    }

    def 'Oillamp\'s `remove` command refuses to remove a lamp whose container still exists'() {
        reportInfo """
            Let's consider a lamp whose container podman still knows about. Deleting the agent's
            home while a container has it mounted would leave the session working in directories
            that no longer exist, and the container would outlive everything that describes it,
            leaving a sandbox with nothing left to stop it with.

            The answer names `oillamp stop`, which is also what clears up a container left behind
            by a supervisor that died. Both cases need the same instruction, so they get the same
            message.
        """
        given: 'a lamp whose container is still registered with podman'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            host.machine { it.commandSucceeding('podman container exists', '') }

        when:
            var outcome = host.oillamp.run('remove', lamp.toString(), '--yes')

        then: 'it refuses, and says which command to run first'
            outcome.status() == LampExitStatus.LAMP_BUSY
            outcome.errors().first().whatHappened().contains('still there')
            outcome.errors().first().fixes().any { it.command().orElse('').startsWith('oillamp stop') }

        and: 'and the lamp is untouched'
            Files.exists(lamp.resolve('oillamp.toml'))
    }

    def 'Running oillamp again on the same lamp keeps the agent\'s world and the user\'s settings'() {
        reportInfo """
            The point of a lamp is that the agent comes back to the work it left: its repositories,
            its tool configuration, its shell history. And the user's edited policy must survive
            too - a tool that silently reset `oillamp.toml` on every run would be useless.

            So the second run must be almost entirely a no-op, and must say so.
        """
        given: 'a lamp that has been set up once'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            var agentHome = Files.list(lamp).filter { it.fileName.toString().startsWith('agent-lamp-') }
                                            .findFirst().orElseThrow()

        and: 'the user has changed a setting, and the agent has done some work'
            var settings = lamp.resolve('oillamp.toml')
            Files.writeString(settings, Files.readString(settings).replace('width  = 1920', 'width  = 2560'))
            Files.writeString(agentHome.resolve('workspace/notes.md'), 'work in progress')
            var identityBefore = Files.readString(lamp.resolve('.oillamp/lamp.json'))
            var keyBefore = Files.readString(lamp.resolve('.oillamp/keys/client_ed25519'))

        when: 'the user runs oillamp on that lamp again'
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'it succeeds'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the edited setting is still there'
            Files.readString(settings).contains('width  = 2560')

        and: 'and has taken effect'
            outcome.console().contains('2560x1440') || outcome.console().contains('2560')

        and: 'the agent\'s work is untouched'
            Files.readString(agentHome.resolve('workspace/notes.md')) == 'work in progress'

        and: 'the lamp keeps the same identity, so it is the same sandbox'
            var agentId = identityBefore.find(/"agentId": "(\w+)"/) { it[1] }
            Files.readString(lamp.resolve('.oillamp/lamp.json')).contains(agentId)

        and: 'and the same keys, so no host-key warning appears'
            Files.readString(lamp.resolve('.oillamp/keys/client_ed25519')) == keyBefore
    }

    def 'Oillamp refuses to make a lamp in a directory that already holds other files, unless given `--init`'() {
        reportInfo """
            `oillamp at ~/my-project` is an easy thing to type by mistake. Since a lamp takes
            ownership of its directory, doing that to a directory the user is already using would
            scatter state through it and, worse, expose part of it to the agent.

            So oillamp refuses a directory that already has something in it and is not already a
            lamp, with `OIL-LAMP-002` - but the refusal tells the user what is in the way and how
            to go ahead deliberately, with `--init`, if that is what they meant.
        """
        given: 'a directory with the user\'s own files in it'
            var directory = Files.createDirectories(tmp.resolve('home/dev/my-project'))
            Files.writeString(directory.resolve('README.md'), 'my actual project')
            Files.createDirectory(directory.resolve('src'))

        when: 'the user points oillamp at it'
            var outcome = host.oillamp.run('at', directory.toString())

        then: 'oillamp refuses'
            outcome.reported('OIL-LAMP-002')
            outcome.status() != LampExitStatus.SUCCESS

        and: 'nothing was created'
            !Files.exists(directory.resolve('.oillamp'))
            !Files.exists(directory.resolve('oillamp.toml'))

        and: 'the user is shown what is in the way'
            var problem = outcome.errors().first()
            problem.evidence().any { it.toString().contains('README.md') }

        and: 'and told how to say "yes, I meant it"'
            problem.fixes().any { it.command().orElse('').contains('--init') }

        when: 'the user confirms with --init'
            var confirmed = host.oillamp.run('at', directory.toString(), '--init')

        then: 'oillamp sets the lamp up alongside their files'
            confirmed.status() == LampExitStatus.SUCCESS
            Files.exists(directory.resolve('.oillamp/lamp.json'))
            Files.readString(directory.resolve('README.md')) == 'my actual project'
    }

    def 'Oillamp refuses outright to make a lamp of the home directory itself or of a system directory'() {
        reportInfo """
            A lamp attaches part of its own directory to the sandbox as the agent's home. Making
            the user's entire home directory into a lamp would therefore hand the agent every
            single thing the sandbox exists to keep away from it. Making /etc into a lamp would be
            worse still.

            There is deliberately no flag to override this. A flag to override it would be used
            exactly once, by someone in a hurry, on the wrong directory.
        """
        when: 'the user points oillamp at their home directory itself'
            var outcome = host.oillamp.run('at', host.home.toString())

        then: 'oillamp refuses and explains why, suggesting a subdirectory instead'
            outcome.reported('OIL-LAMP-003')
            outcome.errors().first().whatHappened().contains('home directory')
            outcome.errors().first().whatHappened().contains('lamps')

        when: 'or at a system directory'
            var system = host.oillamp.run('at', '/etc/oillamp')

        then: 'oillamp refuses that too'
            system.reported('OIL-LAMP-003')
            system.errors().first().whatHappened().contains('/etc')
    }

    def 'Oillamp will not set up a lamp anywhere inside a system directory: #path'() {
        reportInfo """
            oillamp will not set up a lamp in a directory that belongs to the operating system: /bin, /boot,
            /dev, /etc, /lib, /proc, /run, /sbin, /sys, /usr or /var, or any directory below one of them.
            `oillamp at` reports OIL-LAMP-003 and names the system directory.

            The reason is what oillamp does to a lamp's directory. It creates `.oillamp/` and the agent
            directory in it, sets their permissions, hands the recordings directory to the infra user, and
            attaches the agent directory to the sandbox. Inside /usr or /var, that would put files the agent
            controls among the system's programs and data.

            The check compares the start of the path with each directory in that list. A directory missing
            from the list would let lamps in under it, and no other check would stop them. The scenario
            asks `oillamp at` for a lamp a few levels inside /var, /usr and /boot, and expects
            OIL-LAMP-003 naming the right directory each time.
        """
        when:
            var outcome = host.oillamp.run('at', path)

        then: 'oillamp refuses, and names the system directory'
            outcome.reported('OIL-LAMP-003')
            outcome.errors().first().whatHappened().contains("inside the system directory ${directory}")

        where:
            path                         || directory
            '/var/lib/lamps/feature-x'   || '/var'
            '/usr/local/lamps/feature-x' || '/usr'
            '/boot/lamp'                 || '/boot'
    }

    def 'The agent cannot rearrange the socket directories the desktop and the network depend on'() {
        reportInfo """
            The container reaches the host through Unix sockets in three directories: host/ holds
            the network proxy, agent/ the agent's ssh listener, and infra/ the desktop the human
            watches. infra/ belongs to the sandbox's infrastructure user, so the agent cannot
            touch what is inside it.

            The agent runs as the lamp owner's own user id, though. Their shared parent directory
            belongs to that user, so if it were attached to the container, the agent could move
            infra/ aside and put its own desktop in its place, or a link pointing anywhere on the
            host. So each of the three is attached on its own, which the agent cannot move, and
            host/ is attached read-only, since the sandbox only ever connects to it.
        """
        when: 'the user asks what oillamp would run'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run', '--verbose')
            var podmanRun = outcome.stepDetails().find { it.startsWith('podman run') }

        then:
            podmanRun != null

        and: 'each socket directory is attached on its own, and host/ read-only'
            podmanRun.contains('.oillamp/sockets/host:/oillamp/sockets/host:ro')
            podmanRun.contains('.oillamp/sockets/agent:/oillamp/sockets/agent')
            podmanRun.contains('.oillamp/sockets/infra:/oillamp/sockets/infra')

        and: 'their parent is not attached at all'
            !podmanRun.contains('.oillamp/sockets:/oillamp/sockets')
    }

    def 'Oillamp refuses to start a lamp whose socket directory was replaced by a link, and does not follow the link'() {
        reportInfo """
            Before each socket directory was attached on its own, the agent could replace one with
            a symbolic link to any directory of the user's, such as their home or ~/.ssh. The next
            session would then have handed that directory to the infrastructure user, and attached
            it to the container.

            A lamp in that state must not start. oillamp refuses with `OIL-LAMP-011`, names the
            link and where it points, and says how to remove it, without touching either.
        """
        given: 'a lamp that has run before'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())

        and: 'whose infra/ directory the agent moved aside and replaced with a link to the user\'s home'
            var infra = lamp.resolve('.oillamp/sockets/infra')
            Files.move(infra, lamp.resolve('.oillamp/sockets/moved-away'))
            Files.createSymbolicLink(infra, host.home)

        when:
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'oillamp refuses, saying what it found'
            outcome.status() != LampExitStatus.SUCCESS
            outcome.reported('OIL-LAMP-011')
            outcome.errors().first().whatHappened().contains(infra.toString())
            outcome.errors().first().whatHappened().contains(host.home.toString())

        and: 'nothing was handed to the infrastructure user and no sandbox was started'
            !outcome.stepKinds().contains('ChownForContainer')
            !outcome.stepKinds().contains('RunContainer')

        and: 'the link is still there for the user to look at'
            Files.isSymbolicLink(infra)

        cleanup: 'the link, which the temporary directory\'s own cleanup would otherwise follow'
            Files.deleteIfExists(infra)
    }

    def 'Oillamp refuses a lamp on a filesystem that cannot hold Unix sockets, before it creates anything'() {
        reportInfo """
            Every channel between this machine and the sandbox - the shell, the desktop picture,
            the network proxy - is carried by a Unix domain socket living inside the lamp
            directory. A Unix domain socket is a special file that two processes on one machine use
            to talk to each other, and not every filesystem can hold one: network filesystems such
            as NFS cannot, and neither can the FAT filesystems used on most USB sticks.

            Discovering that halfway through starting a container would be a baffling failure, so
            oillamp checks it up front and reports `OIL-LAMP-005`, where the message can name the
            filesystem and suggest a fix.
        """
        given: 'a lamp directory on a network share'
            host.machine { it.lampFilesystemType('nfs') }

        when:
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then:
            outcome.reported('OIL-LAMP-005')
            outcome.errors().first().whatHappened().contains('nfs')
            !Files.exists(host.lampPath())
    }

    def 'Oillamp leaves a lamp written by a newer oillamp alone rather than damage it'() {
        reportInfo """
            A lamp holds the agent's accumulated work. If a future version changes the layout, an
            older oillamp reading it could misinterpret or destroy that. So the identity file
            carries a schema version, and for a version from the future oillamp stops with
            `OIL-LAMP-004` and the only useful advice there is: upgrade.
        """
        given: 'a lamp created by a later version of oillamp'
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

        when:
            var outcome = host.oillamp.run('at', lamp.toString())

        then:
            outcome.reported('OIL-LAMP-004')
            outcome.errors().first().fixes().any { it.description().contains('upgrade') }
    }

    /**
     *  Plants a recording: the name says when it started, the modification time when it stopped.
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

    def 'Oillamp\'s `recordings` command lists how long each recording ran and its size, and invents no length for other files'() {
        reportInfo """
            A recording is named after the session that made it, and wf-recorder writes to it until
            it is interrupted. So the file already carries both ends of its own life: the name is
            when it started, the modification time is when it stopped. The listing reads a duration
            out of that gap rather than opening the file - oillamp does not require ffmpeg on the
            host, and a listing that shelled out to ffprobe for every row would be a listing that
            failed on a machine without it.

            The directory belongs to the sandbox's infra user, not to the person running this. They
            can read it, which is why the listing works at all; they cannot write it, which is why
            deleting goes through podman.

            Anything in there that is not a recording is somebody's own file. It is listed as
            having no duration rather than hidden, deleted, or given a made-up one.
        """
        given: 'a lamp with two recordings, and a file nobody can date'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            givenRecording(lamp, '20260115-100000', Duration.ofMinutes(5), 2048)
            givenRecording(lamp, '20260115-140000', Duration.ofSeconds(90), 512)
            Files.write(lamp.resolve('.oillamp/recordings/notes.mkv'), new byte[1024])

        when:
            var listed = host.oillamp.run('recordings', lamp.toString())

        then: 'each is named with how long it ran and what it cost'
            listed.status() == LampExitStatus.SUCCESS
            listed.console().contains('20260115-100000.mkv')
            listed.console().contains('5m00s')
            listed.console().contains('2.0 MB')
            listed.console().contains('1m30s')

        and: 'the file that is not a session is there, without an invented duration'
            listed.console().contains('notes.mkv')
            listed.console().contains('3 recording(s)')

        when: 'a session that was never recorded is asked for'
            var missing = host.oillamp.run('recordings', lamp.toString(), '--open', '20200101-000000')

        then: 'it says so, and names the ones that do exist'
            missing.status() == LampExitStatus.USAGE
            missing.reported('OIL-LAMP-009')
            missing.errors().first().whyItMatters().contains('20260115-100000')
    }

    def 'Oillamp\'s `recordings --open <session>` opens that session\'s recording in the desktop\'s video player'() {
        reportInfo """
            `oillamp recordings <dir> --open <session>` hands the recording of that session to the
            desktop's own video player, the way opening the file by double-click would.
            `--open=<session>` means the same. oillamp does not wait for the player to close.
        """
        given: 'a desktop that can open files, and a lamp with one recording'
            host.machine { it.withPackages('xdg-utils') }
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            var recording = givenRecording(lamp, '20260115-100000', Duration.ofMinutes(5), 2048)

        when:
            var spaced = host.oillamp.run('recordings', lamp.toString(), '--open', '20260115-100000')
            var joined = host.oillamp.run('recordings', lamp.toString(), '--open=20260115-100000')

        then: 'both open the same file'
            spaced.status() == LampExitStatus.SUCCESS
            spaced.console().contains('opened ' + recording)
            joined.status() == LampExitStatus.SUCCESS
            joined.console().contains('opened ' + recording)
    }

    def 'Oillamp\'s `recordings --prune` deletes now what the next session\'s retention would delete'() {
        reportInfo """
            Retention, the rules `recording.max_age_days` and `recording.max_total_gb` in
            `oillamp.toml`, already runs at the start of every session, because a lamp used day
            after day would otherwise fill the host's disk without a warning. But a user who wants
            the disk back today should not have to start a sandbox to get it, so --prune brings
            the same decision forward.

            The same decision, not a second one: both call the same function on the same
            configuration. Two implementations of "keep 14 days" would eventually disagree about
            what it means, and the one the user reads about in oillamp.toml would be the one that
            was wrong.

            The files belong to the container's infra user, so even this deletion goes through
            podman's user namespace - the same reason `rm -rf` cannot remove a lamp.
        """
        given: 'a lamp that keeps a fortnight, and a recording from well before that'
            var lamp = host.lampPath()
            host.machine { it.clockAt(Instant.parse('2026-02-01T12:00:00Z')) }
            host.oillamp.run('at', lamp.toString())
            var old = givenRecording(lamp, '20251201-090000', Duration.ofMinutes(20), 64)
            var recent = givenRecording(lamp, '20260131-090000', Duration.ofMinutes(20), 64)

        when:
            var pruned = host.oillamp.run('recordings', lamp.toString(), '--prune')

        then: 'the one past its fortnight is gone and the one inside it is not'
            pruned.status() == LampExitStatus.SUCCESS
            !Files.exists(old)
            Files.exists(recent)

        when: 'there is nothing left to prune'
            var again = host.oillamp.run('recordings', lamp.toString(), '--prune')

        then: 'it says so rather than reporting work it did not do'
            again.status() == LampExitStatus.SUCCESS
            again.console().contains('nothing is beyond')
            Files.exists(recent)
    }

    def 'When the recordings exceed recording.max_total_gb, oillamp keeps the newest and deletes the oldest'() {
        reportInfo """
            With recording on, every session writes a video of the desktop into `.oillamp/recordings/`.
            Let's consider a lamp that is used every day: after a few weeks its recordings take many
            gigabytes. Two settings limit them, `recording.max_age_days` and `recording.max_total_gb`. At
            the start of every session, and on `oillamp recordings --prune`, oillamp deletes every
            recording outside either limit.

            This scenario is about the size limit. The newest recordings are the ones a person is most
            likely to watch, so those are kept. oillamp sorts the recordings from newest to oldest, by the
            time they stopped, and adds up their sizes in that order. A recording that would bring the
            total above the limit is deleted. With the sort the other way round, the same code would keep
            the oldest recordings and delete the newest.

            The lamp here has `max_total_gb = 1` and `max_age_days = 0`, which means no age limit, so only
            the size decides. There are three recordings of 400 MB each, from 13, 14 and 15 January. The
            two newest add up to 800 MB and are kept. With the oldest, the total would be 1200 MB, more than
            1 GB (1024 MB), so the oldest is deleted.

            The recordings are sparse files: each reports a size of 400 MB but uses no disk space.
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

        when:
            var pruned = host.oillamp.run('recordings', lamp.toString(), '--prune')

        then: 'the two newest fit into the gigabyte together, and are kept'
            pruned.status() == LampExitStatus.SUCCESS
            Files.exists(newest)
            Files.exists(middle)

        and: 'the oldest would make it 1.2 GB, so it is deleted'
            !Files.exists(oldest)
    }

    /**
     *  Plants a recording of the given size without writing its bytes: a sparse file reports its
     *  full size but takes no space on the disk. It stopped ten minutes after its session began.
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

    def 'Oillamp refuses a lamp directory whose path contains a colon, because podman could not mount it'() {
        reportInfo """
            Parts of the lamp are mounted into the sandbox with `podman run --volume
            HOST:CONTAINER`, which separates its parts with colons. A colon in the lamp's path
            would be read as one of those separators, and the sandbox would get the wrong
            directories or fail to start with a message about something else. So oillamp refuses
            such a path with `OIL-LAMP-001` before it creates anything.
        """
        given:
            var lamp = host.home.resolve('lamps').resolve('feature:x')

        when:
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'oillamp refuses before creating anything, and says what is wrong with the path'
            outcome.reported('OIL-LAMP-001')
            outcome.errors().first().whatHappened().contains('colon')
            !Files.exists(lamp)
    }
}
