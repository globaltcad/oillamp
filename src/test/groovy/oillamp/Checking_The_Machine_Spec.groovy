package oillamp

import dev.lamp.LampExitStatus
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Path

@Title('Checking the machine')
@Narrative('''
`oillamp doctor` checks whether this machine can run oillamp, and changes nothing. Someone runs it
when something is wrong, or before they trust oillamp with anything, so what matters is what it
tells them: what it found, what is missing, and the command that fixes it.

`oillamp at` runs the same checks, and fixes what it may. With installing allowed, a missing
package becomes a step in its plan; with `--no-install`, or `host.auto_install = false`, it is a
reported problem.

The machine is simulated: a stock Ubuntu 24.04 desktop with everything oillamp needs, unless a
scenario takes something away.
''')
@Subject(OilLamp)
class Checking_The_Machine_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    def setup() { host = new ScenarioHost(tmp) }

    def 'On a machine that is ready, `oillamp doctor` lists what it checked'() {
        reportInfo """
            Let's consider a machine that has everything oillamp needs: a stock Ubuntu desktop.
            `oillamp doctor` should still say what it checked there: the distribution, the session
            type, the podman version and how podman runs. A bare "OK" would give the user no reason
            to believe it.
        """
        given: 'a stock Ubuntu desktop with everything oillamp needs'
            var oillamp = host.oillamp

        when: '`oillamp doctor` is run'
            var outcome = oillamp.run('doctor')

        then: 'it succeeds'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'it reports no problem'
            outcome.errors().isEmpty()

        and: 'it names the distribution and the session it found'
            outcome.console().contains('Ubuntu 24.04')
            outcome.console().contains('Wayland (GNOME)')

        and: 'it names podman\'s version, that podman runs rootless, and its runtime, crun'
            outcome.console().contains('podman 5.4.2')
            outcome.console().contains('rootless')
            outcome.console().contains('crun')
    }

    def 'On a machine without podman, `oillamp doctor` names what to install and how'() {
        reportInfo """
            Let's consider a machine on which podman and socat were never installed. `oillamp at`
            may install the host packages it needs, but `oillamp doctor` never changes anything. So
            on such a machine doctor reports `OIL-PKG-001`, names the missing packages, and gives a
            command that can be pasted.

            Its second remedy points at `oillamp at`, the command that installs them. An earlier
            doctor told people to "drop --no-install" instead: a flag they had never passed, which
            suggested that oillamp could not install packages at all.

            The exit status is 3, `PREREQUISITES_MISSING`, rather than a general failure, so that a
            script that runs oillamp can tell the two apart.
        """
        given: 'a machine where podman and socat were never installed'
            host.machine { it.withoutPodman().withoutPackages('socat') }

        when: '`oillamp doctor` is run'
            var outcome = host.oillamp.run('doctor')

        then: 'oillamp reports the prerequisites as missing, with its own problem code'
            outcome.reported('OIL-PKG-001')

        and: 'it exits with `PREREQUISITES_MISSING`'
            outcome.status() == LampExitStatus.PREREQUISITES_MISSING

        and: 'the problem names both missing packages'
            var problem = outcome.errors().find { it.code().value() == 'OIL-PKG-001' }
            problem.whatHappened().contains('podman')
            problem.whatHappened().contains('socat')

        and: 'one remedy is an `apt-get install` command that can be pasted'
            problem.fixes().any { it.command().orElse('').startsWith('sudo apt-get install -y') }
            problem.fixes().any { it.command().orElse('').contains('podman') }

        and: 'another points at `oillamp at`, and none at `--no-install`'
            problem.fixes().any { it.description().contains('oillamp at') }
            problem.fixes().every { !it.description().contains('--no-install') }
    }

    def 'Oillamp reports every missing prerequisite at once, not one per attempt'() {
        reportInfo """
            Let's consider a machine with three problems at once: podman is missing, the user has
            no block of subordinate ids (the spare user ids rootless podman needs), and there is no
            terminal program to open the agent's shell in. Were oillamp to report one problem per
            attempt, the user would fix it, start again and find the next, three times over. So
            oillamp runs every check that does not depend on another, and reports all their
            problems together: `OIL-PKG-001`, `OIL-HOST-010` and `OIL-TERM-001`.

            The scenario runs `oillamp at --no-install`, because only then does oillamp report the
            first two problems. With installing allowed, they become steps in its plan instead.
        """
        given: 'a machine without podman, without a block of subordinate ids, and without a terminal program'
            host.machine {
                it.withoutPodman()
                  .withoutSubordinateIds()
                  .withoutTerminals()
            }

        when: '`oillamp at --no-install` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--no-install')

        then: 'it reports all three problems, in one run'
            outcome.reported('OIL-PKG-001')
            outcome.reported('OIL-HOST-010')
            outcome.reported('OIL-TERM-001')

        and: 'it exits with `PREREQUISITES_MISSING`'
            outcome.status() == LampExitStatus.PREREQUISITES_MISSING
    }

    def 'With installing allowed, oillamp plans to install missing prerequisites instead of reporting them'() {
        reportInfo """
            Let's consider a machine without podman, whose user has no block of subordinate ids
            (the spare user ids rootless podman needs). Oillamp is meant to work on a machine
            straight out of the box: the user types `oillamp at`, and oillamp fixes what it can fix
            itself. A program that installs system packages must say so first, so
            `oillamp at --dry-run` prints everything oillamp would change on the machine, every
            package and every command, and carries none of it out. The scenario reads that plan: a
            step that installs podman, a step that adds the block of ids, a step that has podman
            read its id map again, and no `OIL-PKG-001`.
        """
        given: 'a machine with no podman and no subordinate id range, but a terminal and sudo'
            host.machine { it.withoutPodman().withoutSubordinateIds() }

        when: '`oillamp at --dry-run` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')

        then: 'the plan installs the packages and adds a block of ids, and oillamp reports no `OIL-PKG-001`'
            outcome.stepKinds().contains('InstallPackages')
            outcome.stepKinds().contains('AddSubIds')
            !outcome.reported('OIL-PKG-001')

        and: 'the plan names podman, and the block of ids it picked'
            outcome.steps().any { it.contains('podman') }
            outcome.steps().any { it.contains('100000-165535') }

        and: 'the plan has podman read its id map again afterwards, or podman would keep the old one'
            outcome.stepKinds().contains('PodmanMigrate')
    }

    def 'Oillamp picks an id range for the user that overlaps no other user\'s: #situation'() {
        reportInfo """
            Rootless podman maps the users inside a container to ids on the host. Besides the user's own
            id, it needs a block of ids that belong to that user alone: the subordinate ids, listed one
            block per line in /etc/subuid and /etc/subgid as `name:first id:count`. The sandbox's infra
            user, container uid 1001, is one of those ids on the host.

            When the user has no block, oillamp plans to add one of 65536 ids, through sudo. Let's
            consider a machine where other users already have blocks. If the new block overlapped one of
            them, the same host ids would belong to two users. A file that one user's sandbox writes as
            the infra user would be owned by a host id that the other user's containers also run as, and
            they could read and change it. podman reports no error for an overlap, so oillamp has to avoid
            it when it picks the block.

            Oillamp starts at id 100000 and goes through the existing blocks in order. When a block is in
            the way, it moves the start past the end of that block, rounded up to the next multiple of
            65536. It stops at the first start where 65536 ids fit before the next block.

            The scenario runs `oillamp at --dry-run` with three arrangements of other users' blocks, and
            reads the block in the plan's `AddSubIds` step:

            - one block at 100000: the new block starts after it, at 196608;
            - blocks at 100000 and 200000: the ids from 196608 to 199999 are too few, so the new block
              starts after the second one, at 327680;
            - one block at 165536: exactly 65536 ids fit below it, so the new block is 100000 to 165535.
        """
        given: 'a user without subordinate ids, on a machine where other users have some'
            host.machine { machine ->
                taken.inject(machine.withoutSubordinateIds()) { m, block ->
                    m.subordinateIdsTakenBy(block[0], block[1], 65536)
                }
            }

        when: '`oillamp at --dry-run` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')

        then: 'the plan adds the first block that is free'
            outcome.stepKinds().contains('AddSubIds')
            outcome.steps().any { it.contains(expected) }

        where:
            situation                                       | taken                                           || expected
            'another user has the first block'              | [['alice', 100000]]                             || '196608-262143'
            'the gap between two blocks is too small'       | [['alice', 100000], ['bob', 200000]]            || '327680-393215'
            'a block starts exactly where a free one ends'  | [['alice', 165536]]                             || '100000-165535'
    }

    def 'Oillamp plans to install crun alongside podman, because Ubuntu\'s podman would otherwise use runc'() {
        reportInfo """
            Ubuntu 24.04 ships podman 4.9.3 with runc, not crun. This was found the first time
            oillamp ran on real hardware; the simulated machine could not have shown it. The
            sandbox needs crun to use the graphics card, because only crun supports
            `--group-add keep-groups`, which carries the host's `render` group into the container.

            Without crun, nothing fails: the sandbox's desktop is drawn by the processor instead of
            the graphics card, without an error, with the default setting `display.gpu = auto`, on
            Ubuntu, the platform oillamp is made for first. The user would have no error message to
            search for. So crun is a required package, and this scenario keeps it one, although
            podman works without it.
        """
        given: 'a stock Ubuntu where podman is present but crun is not'
            host.machine { it.withoutPackages('crun') }

        when: '`oillamp at --dry-run` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')

        then: 'the plan installs crun'
            outcome.stepKinds().contains('InstallPackages')
            outcome.steps().any { it.contains('crun') }

        and: 'the plan says why: Ubuntu\'s podman uses runc, and the `render` group needs crun'
            outcome.stepDetails().any { it.contains('runc') && it.contains('render group') }
    }

    def 'Oillamp plans to install slirp4netns, because rootless podman cannot build an image without it'() {
        reportInfo """
            This was found by running real podman, in a spike: a test that runs real third-party
            tools to check an assumption. Without slirp4netns, rootless podman fails with

                could not find slirp4netns, the network namespace can't be configured

            The simulated machine did not predict it, because it knows only what we believed, and
            we believed that podman brought its own networking.

            The sandbox itself starts with no network interfaces, `--network=none`, and needs no
            networking from podman, so every scenario about running the container passed. Building
            the image does need a network, because apt-get runs there. So oillamp worked on a
            machine where the image was already built, and failed for every new user.
        """
        given: 'a machine with podman but no rootless networking backend'
            host.machine { it.withoutPackages('slirp4netns') }

        when: '`oillamp at --dry-run` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')

        then: 'the plan installs slirp4netns, before an image build needs it'
            outcome.stepKinds().contains('InstallPackages')
            outcome.steps().any { it.contains('slirp4netns') }

        and: 'the plan says why: the sandbox runs with `--network=none`, but building the image needs a network'
            outcome.stepDetails().any { it.contains('--network=none') }
    }

    def 'Oillamp plans to install `passt`, whose `pasta` gives rootless podman 5 a network to build the image with'() {
        reportInfo """
            Since version 5, rootless podman gives a container a network with `pasta`, a program
            in the package `passt`, and no longer with slirp4netns. Ubuntu's podman package only
            recommends `passt`, and oillamp installs packages without their recommendations. So
            on a laptop where oillamp had just installed podman 5, the image build stopped at its
            first `apt-get`, and `podman build` exited with code 125:

                could not find pasta, the network namespace can't be configured

            Oillamp installs both `slirp4netns` and `passt`, whichever podman version the machine
            has, as the next system update may change the version. The scenario reads the plan of
            `oillamp at --dry-run` on a machine with podman 5 and everything else oillamp needs,
            except `passt`.
        """
        given: 'a machine with podman 5 and slirp4netns, but without the package `passt`'
            host.machine { it.podman('5.4.2', 'crun').withoutPackages('passt') }

        when: '`oillamp at --dry-run` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')

        then: 'the plan installs `passt`, before an image build needs it'
            outcome.stepKinds().contains('InstallPackages')
            outcome.steps().any { it.contains('passt') }

        and: 'the plan says why: `pasta` gives podman 5 a network while the image is built'
            outcome.stepDetails().any { it.contains('pasta') && it.contains('podman 5') }
    }

    def 'When AppArmor blocks rootless podman, `oillamp doctor` names AppArmor and its remedies'() {
        reportInfo """
            Ubuntu 23.10 and newer refuse unprivileged user namespaces to programs without a
            matching AppArmor profile. Rootless podman needs them, so oillamp does not work, and the
            raw error, "cannot clone: Operation not permitted", tells the user neither why nor what
            to do.

            This is the most likely reason that oillamp fails to start on a current Ubuntu, so it
            has its own problem code, `OIL-PODMAN-004`, and its own remedies, the safest first. The
            last one turns the sysctl off, and says what that costs: it weakens a protection of the
            whole system, not only of oillamp's sandbox, so oillamp never does it itself.
        """
        given: 'a modern Ubuntu whose AppArmor policy blocks unprivileged user namespaces'
            host.machine { it.userNamespacesBlockedByAppArmor() }

        when: '`oillamp doctor` is run'
            var outcome = host.oillamp.run('doctor')

        then: 'it reports `OIL-PODMAN-004`, whose title names AppArmor'
            outcome.reported('OIL-PODMAN-004')
            var problem = outcome.errors().find { it.code().value() == 'OIL-PODMAN-004' }
            problem.title().contains('AppArmor')

        and: 'it shows the command it ran, and what the kernel setting says'
            outcome.console().contains('$ podman unshare true')
            outcome.console().contains('apparmor_restrict_unprivileged_userns: 1')

        and: 'the first remedy is the safe one: Ubuntu-packaged podman'
            var fixes = problem.fixes().collect { it.description() }
            fixes.first().contains('Ubuntu-packaged podman')

        and: 'the last remedy turns the protection off, and says that this weakens it'
            fixes.last().contains('weakens')
            problem.fixes().last().command().get().contains('apparmor_restrict_unprivileged_userns=0')
    }

    def '`oillamp doctor` reports a podman that does not answer under another code than one that is too old'() {
        reportInfo """
            Oillamp asks podman for its version before anything else. A podman that fails to
            answer, often a stale copy earlier on `PATH` or a broken configuration file, was once
            reported under the same code as one that is too old, `OIL-PODMAN-001`, and a user who
            looked the code up would upgrade a podman that is new enough. Now it has its own code,
            `OIL-PODMAN-005`, and its own remedies: see what podman says, and check which podman is
            on `PATH`.
        """
        given: 'podman is installed, but asking it for its version fails'
            host.machine { it.commandFailing('podman version', 125,
                    'Error: cannot parse configuration file containers.conf') }

        when: '`oillamp doctor` is run'
            var outcome = host.oillamp.run('doctor')

        then: 'it reports `OIL-PODMAN-005`, not `OIL-PODMAN-001`, and a remedy names `PATH`'
            outcome.reported('OIL-PODMAN-005')
            !outcome.reported('OIL-PODMAN-001')
            var problem = outcome.errors().find { it.code().value() == 'OIL-PODMAN-005' }
            problem.title().contains('did not respond')
            problem.fixes().any { it.description().contains('PATH') }
    }

    def '`oillamp doctor` runs without a graphical session, where `oillamp at` refuses to'() {
        reportInfo """
            `oillamp at` opens two windows, so it refuses to run without a graphical session.
            `oillamp doctor` must still run there: "I am on a plain SSH login and oillamp will not
            start" is a situation someone runs doctor to diagnose.

            `oillamp at` reports `OIL-HOST-003` there, and the problem names a way round it:
            `--no-windows`, which opens no window at all. `--no-viewer` is easy to mistake for it,
            but still opens a shell window.
        """
        given: 'a machine reached over SSH, with no desktop session'
            host.machine { it.noGraphicalSession() }

        when: '`oillamp doctor` is run'
            var outcome = host.oillamp.run('doctor')

        then: 'it succeeds'
            outcome.status() == LampExitStatus.SUCCESS

        when: '`oillamp at --dry-run` is run'
            var session = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')

        then: 'it reports `OIL-HOST-003`: there is nowhere to put the shell window and the viewer'
            session.reported('OIL-HOST-003')

        and: 'a remedy runs the session with `--no-windows`'
            session.errors().first().fixes().any { it.command().orElse('').contains('--no-windows') }
    }

    def 'Oillamp tells a user who is not in the graphics card\'s group the command that adds them'() {
        reportInfo """
            A graphics card makes the sandbox's desktop faster, and nothing else. So it never stops
            a session: when oillamp cannot use the card, it draws the desktop with the processor
            instead, and says why.

            "You are not in the 'render' group" says why, but not what to do, and the first person
            to read that line asked what exactly to do, and on which machine. So the line carries
            the command, with the real group and user name from the probe, and says what is easy to
            miss: a new group reaches only processes started after a new login, so the session
            running now does not get it.

            Oillamp does not run that command itself, although it does run `sudo usermod` to give
            the user a block of subordinate ids. Those ids are a prerequisite, and the group is
            not: everything works without the graphics card, only more slowly. And the change
            takes effect only after the user logs in again, so running it unasked would leave them
            with the desktop drawn by the processor all the same, and a changed account.
        """
        given: 'a machine with a render node the user has no access to'
            var lamp = host.lampPath()
            host.machine { it.renderNode('/dev/dri/renderD128', 'render', 'i915') }

        when: '`oillamp at --dry-run` is run'
            var outcome = host.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'the reason is stated'
            outcome.console().contains("not in the 'render' group")

        and: 'so is the command, for this machine and this user'
            outcome.console().contains('sudo usermod -aG render dev')

        and: 'and the step that is easy to miss: log out and back in'
            outcome.console().contains('log out and back in')
    }

    def 'On an operating system that is not Linux, `oillamp doctor` says at once that oillamp cannot run'() {
        reportInfo """
            The sandbox is a rootless podman container, built on Linux user namespaces. There is no
            partial version of it on another operating system, so `oillamp doctor` reports
            `OIL-HOST-001` at once, rather than failing later with a message about missing
            programs.
        """
        given: 'oillamp running on Mac OS X'
            host.machine { it.notLinux('Mac OS X') }

        when: '`oillamp doctor` is run'
            var outcome = host.oillamp.run('doctor')

        then: 'it reports `OIL-HOST-001`, and does not succeed'
            outcome.reported('OIL-HOST-001')
            outcome.status() != LampExitStatus.SUCCESS

        and: 'the message names the platform it actually found'
            outcome.errors().first().whatHappened().contains('Mac OS X')
    }

    def 'Where sudo needs a password and there is no terminal, oillamp plans to become root through `pkexec`, which asks for the password in a window'() {
        reportInfo """
            Let's consider a desktop application such as Genies, which starts oillamp to set up a
            lamp. Oillamp then runs without a terminal, so where sudo needs a password, sudo has
            nowhere to ask for it. A desktop session offers another way to become root: `pkexec`
            asks for the password in a window, as GNOME and KDE do when one of their settings
            pages needs root rights.

            On a machine without podman and without a block of subordinate ids, oillamp therefore
            plans both of its root commands, `apt-get install` and `usermod`, through `pkexec`.
            The scenario runs `oillamp at <dir> --dry-run` without a terminal and reads the plan,
            of which a dry run carries out nothing.
        """
        given: 'a desktop session without podman and without subordinate ids, where sudo needs a password, there is no terminal, and pkexec is installed'
            host.machine { it.withoutPodman().withoutSubordinateIds().sudoNeedsPassword().interactive(false) }

        when: '`oillamp at <dir> --dry-run` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')

        then: 'the step that installs podman would run `apt-get install` through pkexec'
            outcome.stepDetails().any { it.startsWith('pkexec apt-get install -y --no-install-recommends podman') }

        and: 'the step that adds the block of ids would run `usermod` through pkexec'
            outcome.stepDetails().any { it.startsWith('pkexec usermod --add-subuids 100000-165535') }

        and: 'no step\'s command line starts with sudo'
            outcome.stepDetails().every { !it.startsWith('sudo ') }
    }

    def 'Oillamp started from a terminal plans to become root through sudo, which asks for the password there, even in a desktop session where `pkexec` is installed'() {
        reportInfo """
            Let's consider someone who types `oillamp at` into a terminal, in a desktop session
            where `pkexec` is installed, on a machine without podman, where sudo needs a password.
            sudo asks for it in that terminal, as it always has with oillamp, and as it must on a
            machine without a desktop environment such as GNOME or KDE. So oillamp turns to
            `pkexec` only where there is no terminal; here it plans to install podman with
            `sudo apt-get install`. The scenario reads the plan of `oillamp at --dry-run`.
        """
        given: 'a desktop session without podman, where sudo needs a password, pkexec is installed, and oillamp runs in a terminal'
            host.machine { it.withoutPodman().sudoNeedsPassword().interactive(true) }

        when: '`oillamp at <dir> --dry-run` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')

        then: 'the step that installs podman would run `apt-get install` through sudo'
            outcome.stepDetails().any { it.startsWith('sudo apt-get install -y --no-install-recommends podman') }

        and: 'no step\'s command line starts with pkexec'
            outcome.stepDetails().every { !it.startsWith('pkexec ') }
    }

    def 'When `pkexec` exits with #exitCode because #situation, `oillamp at` reports `OIL-PKG-004` instead of a failed `apt-get`'() {
        reportInfo """
            Let's consider oillamp started without a terminal in a desktop session, on a machine
            without podman, where sudo needs a password. Oillamp runs
            `pkexec apt-get install -y --no-install-recommends podman`, and pkexec shows a
            password window. pkexec runs nothing and exits with 126 when the person closes that
            window, and with 127 when it gets no permission: the password was wrong, or no polkit
            authentication agent, the program of the desktop session that shows such windows,
            is running.

            Either way `apt-get` never started. `OIL-PKG-003`, which points at apt-get's output
            and suggests updating the package lists, would send the user looking in the wrong
            place. So oillamp reports `OIL-PKG-004`, whose text says that pkexec stopped, and
            why.
        """
        given: 'a desktop session without podman, where sudo needs a password, there is no terminal, and pkexec exits with #exitCode'
            host.machine {
                it.withoutPodman().sudoNeedsPassword().interactive(false)
                  .commandFailing('pkexec', exitCode, pkexecError)
            }

        when: '`oillamp at <dir>` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'oillamp reports `OIL-PKG-004`, and not `OIL-PKG-003`'
            outcome.reported('OIL-PKG-004')
            !outcome.reported('OIL-PKG-003')

        and: 'the text of `OIL-PKG-004` says what happened'
            outcome.errors().find { it.code().value() == 'OIL-PKG-004' }.whatHappened().contains(explanation)

        and: 'oillamp exits with `PREREQUISITES_MISSING`'
            outcome.status() == LampExitStatus.PREREQUISITES_MISSING

        where:
            exitCode | situation                               | pkexecError                                                   | explanation
            126      | 'the person closed its password window' | 'Error executing command as another user: Request dismissed' | 'the password window was closed'
            127      | 'it got no permission'                  | 'Error executing command as another user: Not authorized'    | 'pkexec could not get permission'
    }

    def 'On #distribution, `oillamp at` plans to install podman and `newuidmap` with #manager, under the package names #distribution uses'() {
        reportInfo """
            Let's consider a ${distribution} machine on which everything oillamp needs is
            installed except podman and `newuidmap`, the program with which rootless podman maps
            the container's users onto the subordinate ids. Each family of distributions installs
            packages with its own package manager, and some give the same program a different
            package name: Debian's package `uidmap`, which holds `newuidmap`, is `shadow-utils`
            on Fedora and `shadow` on openSUSE and Arch.

            So on ${distribution}, oillamp plans to run `${command}`. An earlier oillamp knew only
            `apt-get`, and refused to install anything outside Debian and Ubuntu. The scenario
            reads the plan of `oillamp at --dry-run`, on a machine where sudo needs no password.
        """
        given: 'a #distribution machine with everything oillamp needs under #distribution\'s package names, except podman and the package that holds `newuidmap`'
            host.machine { os(it).withPackages(*installed).withoutPodman() }

        when: '`oillamp at <dir> --dry-run` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString(), '--dry-run')

        then: 'the step that installs packages would run exactly `#command`'
            outcome.stepDetails().any { it.startsWith(command + '\n') }

        and: 'oillamp reports no `OIL-HOST-002`, the problem of a distribution it cannot install packages on'
            !outcome.reported('OIL-HOST-002')

        where:
            distribution         | manager  | os                       | installed                                                                            | command
            'Fedora 42'          | 'dnf'    | { it.fedora('42') }      | ['crun', 'slirp4netns', 'passt', 'catatonit', 'socat', 'openssh-clients', 'tigervnc'] | 'sudo dnf install -y podman shadow-utils'
            'openSUSE Leap 16.0' | 'zypper' | { it.opensuse('16.0') }  | ['crun', 'slirp4netns', 'passt', 'catatonit', 'socat', 'openssh-clients', 'tigervnc'] | 'sudo zypper --non-interactive install --no-recommends podman shadow'
            'Arch Linux'         | 'pacman' | { it.arch() }            | ['crun', 'slirp4netns', 'passt', 'catatonit', 'socat', 'openssh', 'tigervnc']         | 'sudo pacman -S --needed --noconfirm podman shadow'
    }

    def 'On a Linux whose package manager oillamp does not know, `oillamp doctor` lists the packages to install by hand'() {
        reportInfo """
            Oillamp installs packages with apt-get, dnf, zypper or pacman: the package managers of
            Debian, Fedora, openSUSE and Arch, and of the distributions built like them. Let's
            consider Gentoo, which is none of these. There `oillamp doctor` reports `OIL-HOST-002`
            with the packages oillamp needs, under Debian's names, for the user to look up and
            install themselves. It reports no `OIL-PKG-001` besides: that problem's remedy is an
            install command, and oillamp knows none for Gentoo.
        """
        given: 'a Gentoo machine'
            host.machine { it.distribution('gentoo', '', '2.17', 'Gentoo Linux') }

        when: '`oillamp doctor` is run'
            var outcome = host.oillamp.run('doctor')

        then: 'it reports `OIL-HOST-002`'
            outcome.reported('OIL-HOST-002')

        and: 'the packages oillamp needs are listed, so the user can install them themselves'
            var problem = outcome.errors().find { it.code().value() == 'OIL-HOST-002' }
            problem.evidence().any { it.toString().contains('podman') }
            problem.evidence().any { it.toString().contains('tigervnc-viewer') }

        and: 'it reports no `OIL-PKG-001`'
            !outcome.reported('OIL-PKG-001')
    }

    def 'Oillamp installs nothing for a lamp configured with `host.auto_install = false`'() {
        reportInfo """
            `host.auto_install = false` in `oillamp.toml` says what `--no-install` says on the
            command line: report what is missing, but do not run sudo. An earlier oillamp accepted
            the setting and ignored it, and asked for a sudo password anyway.
        """
        given: 'a machine without podman, and a lamp that says not to install'
            host.machine { it.withoutPodman() }
            var lamp = host.lampPath()
            host.givenConfig(lamp, '''
                schema_version = 1

                [host]
                auto_install = false
            '''.stripIndent())

        when: '`oillamp at --dry-run` is run'
            var outcome = host.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'nothing is installed; the missing packages are reported instead'
            !outcome.stepKinds().contains('InstallPackages')
            outcome.reported('OIL-PKG-001')

        and: 'the advice points at the setting, not at a flag the user never passed'
            outcome.console().contains('auto_install = true')
            !outcome.console().contains('without --no-install')
    }
}
