package oillamp

import spock.lang.Narrative
import spock.lang.Tag
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

@Title('Verifying podman assumptions')
@Narrative('''
Checks the podman behaviour the container design depends on: that rootless podman works on Ubuntu,
the mapping of user ids, a read-only container with writable mounts, and Unix sockets across the
container boundary.

This spike runs against real podman on a real machine. It is tagged `spike`, so `./gradlew test`
leaves it out; run it with `./gradlew spikes`.
''')
@Tag('spike')
@Stepwise
@Requires({ Spike.podmanAvailable() })
class Verifying_Podman_Assumptions_Spec extends Specification {

    @Shared Path scratch

    def setupSpec() {
        Spike.ensureBaseImage()
    }

    def setup() {
        scratch = Files.createTempDirectory('oillamp-spike-')
    }

    def cleanup() {
        // Not Files.deleteIfExists: some of these directories contain files owned by a subuid.
        Spike.removeTree(scratch.toString())
    }

    def 'Rootless podman runs here despite the Ubuntu restriction on unprivileged user namespaces'() {
        reportInfo """
            Ubuntu 23.10 and newer refuse unprivileged user namespaces to programs without a
            matching AppArmor profile, and rootless podman needs them. Oillamp assumes that Ubuntu
            ships a profile that permits podman. If that were wrong, oillamp would not work at all
            on Ubuntu, the platform it is made for first, and `OIL-PODMAN-004` would be the most
            common message oillamp prints, rather than a rare one.

            This scenario is the cheapest check there is: if a container runs, the assumption
            holds.
        """
        when: 'a container is asked to do the simplest possible thing'
            var result = Spike.inBaseImage('echo alive')

        then: 'it runs, which means user namespaces were granted'
            result.ok
            result.mentions('alive')

        and: 'and podman reports itself as rootless, not quietly running as root'
            Spike.run('podman', 'info', '--format', '{{.Host.Security.Rootless}}').mentions('true')
    }

    def 'The container sees the human as its own user, and the infrastructure as a different one'() {
        reportInfo """
            The security design depends on this assumption, so it is explained from the start.

            On Linux every file is owned by a number, and every running process has one. Names
            such as "dev" or "root" are a convenience on top; the kernel only compares numbers. A
            container can be given its own, private set of those numbers, with a table that says
            which private number stands for which real one. That table decides, in the end, which
            files the programs inside a container may touch.

            Oillamp arranges the table so that it has two properties at once. Both are needed, and
            neither is obvious enough to take on trust:

            1. Files that belong to the person running oillamp must appear, inside the container,
               to belong to the container's user number 1000, the account the agent works as. That
               lets the agent write freely in its own home directory, while those files remain
               ordinary files of the human outside, which they can edit. Without it, the only
               choices would be to make everything writable by all, or to leave the human unable
               to open what the agent made.

            2. It must also be possible to hand a directory to container user number 1001, the
               account that runs the compositor, the screen recorder and the network bridges, so
               that the agent cannot write to it. That is what makes a screen recording
               trustworthy: the agent can see that it is recorded, and cannot change the
               recording.

            Without the second property, there would be no boundary that lets a human trust the
            sandbox, and the second user would have had to go from the design. That is why this
            was checked against real podman before any of the code that depends on it was
            written.

            The scenario reads the host's user id rather than assuming 1000: on the machine this
            first ran on, the user was 1001, which would have made a fixed assertion pass or fail
            for the wrong reason.
        """
        given: 'a state directory, part of it handed to the infra user the way oillamp will'
            var state = Files.createDirectories(scratch.resolve('state'))
            Files.writeString(state.resolve('agent-owned.txt'), 'hello\n')
            var infra = Files.createDirectories(state.resolve('infra'))
            Spike.run('podman', 'unshare', 'chown', '1001:1001', infra.toString())

        when: 'a container runs with the mapping oillamp will use'
            var result = Spike.run('podman', 'run', '--rm', '--network=none',
                    '--userns=keep-id:uid=1000,gid=1000',
                    '-v', "$state:/probe".toString(),
                    Spike.BASE_IMAGE,
                    'sh', '-lc', 'id -u; stat -c "%u:%g %n" /probe /probe/agent-owned.txt /probe/infra')

        then: 'the container user is 1000'
            result.ok
            result.out.readLines().first().trim() == '1000'

        and: 'files the host user owns are the container user\'s own, with no chmod anywhere'
            result.mentions('1000:1000 /probe')
            result.mentions('1000:1000 /probe/agent-owned.txt')

        and: 'and what was given to the infra user is uid 1001 inside, a user the agent is not'
            result.mentions('1001:1001 /probe/infra')

        and: 'while on the host that same directory is a subuid the user does not own'
            var hostOwner = Spike.run('stat', '-c', '%u', infra.toString()).out.trim()
            hostOwner != Spike.run('id', '-u').out.trim()
            hostOwner.toInteger() > 65535
    }

    def 'A read-only container can still write to its bind-mounted home'() {
        reportInfo """
            The container runs with `--read-only`, so that everything outside the agent's home is
            discarded, and cannot be changed unnoticed. That is only useful if bind mounts onto
            mount points made beforehand stay writable; otherwise the agent cannot work at all.
        """
        given: 'a workspace directory on the host'
            var work = Files.createDirectories(scratch.resolve('work'))

        when: 'a read-only container writes into the bind mount'
            var result = Spike.run('podman', 'run', '--rm', '--network=none', '--read-only',
                    '--userns=keep-id:uid=1000,gid=1000',
                    '-v', "$work:/work".toString(),
                    Spike.BASE_IMAGE,
                    'sh', '-lc', 'touch /work/written && echo wrote-to-mount; ' +
                                 'touch /nope 2>/dev/null && echo WROTE-TO-ROOTFS || echo rootfs-is-read-only')

        then: 'the bind mount accepted the write'
            result.ok
            result.mentions('wrote-to-mount')

        and: 'the root filesystem refused it'
            result.mentions('rootfs-is-read-only')
            !result.mentions('WROTE-TO-ROOTFS')

        and: 'and the file is on the host, owned by the user rather than by a subuid'
            Files.exists(work.resolve('written'))
            Spike.run('stat', '-c', '%u', work.resolve('written').toString()).out.trim() ==
                    Spike.run('id', '-u').out.trim()
    }

    def 'A Unix socket the container creates is connectable from the host'() {
        reportInfo """
            This is the direction ssh uses: sshd listens inside the container on a socket in a
            bind-mounted directory, and the host's ssh client connects to it. It is what keeps
            `--network=none` possible, while still giving the user a shell.

            The container borrows the host's socat; `Spike.borrowedSocat` says why.
        """
        given: 'a directory both sides can see'
            var sockets = Files.createDirectories(scratch.resolve('sockets'))

        when: 'the container listens on a socket there'
            Spike.run('podman', 'run', '-d', '--name', 'oillamp-spike-listen', '--network=none',
                    '--userns=keep-id:uid=1000,gid=1000',
                    '-v', "$sockets:/sock".toString(), '-v', '/usr:/hostusr:ro',
                    Spike.BASE_IMAGE,
                    *Spike.borrowedSocat('UNIX-LISTEN:/sock/agent.sock,fork',
                                         'SYSTEM:echo HELLO-FROM-CONTAINER'))
            waitForFile(sockets.resolve('agent.sock'))

        then: 'the socket appears on the host'
            Files.exists(sockets.resolve('agent.sock'))

        and: 'and the host can talk to whatever is behind it'
            Spike.run('socat', '-T5', '-', "UNIX-CONNECT:${sockets.resolve('agent.sock')}".toString())
                 .mentions('HELLO-FROM-CONTAINER')

        cleanup:
            Spike.run('podman', 'rm', '-f', 'oillamp-spike-listen')
    }

    def 'A Unix socket the host creates is connectable from the container'() {
        reportInfo """
            The network design depends on this direction. The agent has no route and no DNS, and
            reaches the egress proxy only through a socket the supervisor on the host listens on.
            If a container could not connect to it, `--network=none` would have to go, and with it
            the guarantee that nothing leaves the sandbox unseen.
        """
        given: 'the host listening on a socket in a shared directory'
            var sockets = Files.createDirectories(scratch.resolve('sockets'))
            var socket = sockets.resolve('host.sock')
            var listener = new ProcessBuilder('socat', "UNIX-LISTEN:$socket,fork".toString(),
                                              'SYSTEM:echo HELLO-FROM-HOST')
                    .redirectErrorStream(true).start()
            waitForFile(socket)

        when: 'the container connects to it'
            var result = Spike.run('podman', 'run', '--rm', '--network=none',
                    '--userns=keep-id:uid=1000,gid=1000',
                    '-v', "$sockets:/sock".toString(), '-v', '/usr:/hostusr:ro',
                    Spike.BASE_IMAGE,
                    *Spike.borrowedSocat('-T5', '-', 'UNIX-CONNECT:/sock/host.sock'))

        then: 'the host answers'
            result.mentions('HELLO-FROM-HOST')

        cleanup:
            listener.destroyForcibly()
    }

    def 'The agent cannot move a socket directory that is attached on its own, and still reaches the proxy'() {
        reportInfo """
            The agent runs as the lamp owner's user id, so it owns the directory that holds the
            three socket directories. If that directory were attached, the agent could move the
            infrastructure user's directory aside and put a link or its own desktop in its place.
            oillamp therefore attaches each socket directory on its own. This checks the three
            things that relies on: a directory that is its own mount point cannot be moved from
            inside, the read-only one cannot be written, and a socket on a read-only mount can
            still be connected to, which is how the sandbox reaches the proxy.
        """
        given: 'the host listening on a socket in the host directory'
            var host = Files.createDirectories(scratch.resolve('sockets/host'))
            var infra = Files.createDirectories(scratch.resolve('sockets/infra'))
            var socket = host.resolve('proxy.sock')
            var listener = new ProcessBuilder('socat', "UNIX-LISTEN:$socket,fork".toString(),
                                              'SYSTEM:echo HELLO-FROM-HOST')
                    .redirectErrorStream(true).start()
            waitForFile(socket)

        when: 'the agent, in a read-only container with each directory attached on its own, tries'
            var result = Spike.run('podman', 'run', '--rm', '--network=none', '--read-only',
                    '--userns=keep-id:uid=1000,gid=1000', '--user', '1000:1000',
                    '-v', "$host:/oillamp/sockets/host:ro".toString(),
                    '-v', "$infra:/oillamp/sockets/infra".toString(),
                    '-v', '/usr:/hostusr:ro',
                    Spike.BASE_IMAGE, 'sh', '-c',
                    'mv /oillamp/sockets/infra /oillamp/sockets/moved && echo MOVED; '
                  + 'touch /oillamp/sockets/host/planted && echo WROTE; '
                  + "${Spike.borrowedSocat('-T5', '-', 'UNIX-CONNECT:/oillamp/sockets/host/proxy.sock').join(' ')}")

        then: 'the socket directory stays where it is, and the read-only one takes nothing'
            !result.out.contains('MOVED')
            !result.out.contains('WROTE')
            !Files.exists(host.resolve('planted'))

        and: 'but the proxy socket on the read-only mount still answers'
            result.out.contains('HELLO-FROM-HOST')

        cleanup:
            listener?.destroyForcibly()
    }

    private static void waitForFile(Path path, int attempts = 50) {
        for (int i = 0; i < attempts && !Files.exists(path); i++) Thread.sleep(100)
    }
}
