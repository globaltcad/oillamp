package oillamp

import dev.lamp.LampExitStatus
import dev.lamp.LampEvent
import dev.oillamp.Machine
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

@Title('Starting the sandbox')
@Narrative('''
Starting the sandbox, and knowing whether it really started.

Every scenario here comes from one run on a real machine. The second session on a lamp came up with
a working shell and a desktop that did not answer. Oillamp printed a green tick, told the user
which socket to connect to, and returned. The container died two seconds later. The reason was in
`podman logs` the whole time, and nothing showed it.

The cause was one file. `/oillamp/sockets` is a bind mount, so it outlives the container, and
`vnc.sock` from the previous session was still there when wayvnc tried to bind it. wayvnc has
nothing like socat's `unlink-early`, so it failed, and every check on both sides of the mount only
asked whether the file existed, which the leftover file answered with yes.

So these scenarios all ask, in different places, whether oillamp knows the difference between a
socket file that exists and a server that listens on it.
''')
@Subject(OilLamp)
class Starting_The_Sandbox_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def 'Oillamp reports a session as started only after it has connected to every socket itself'() {
        reportInfo """
            `ready.json`, the readiness file the container writes into `.oillamp/sockets/infra/`,
            is the sandbox's own report on itself. The container writes it once its servers are
            up. If one of them then dies, or never bound its socket, the file still says that
            everything is fine.

            So the last thing `oillamp at` does is connect to each socket it is about to hand the
            user: `vnc.sock` for the desktop and `ssh.sock` for the shell. That is what the viewer
            does a moment later, so a connection that succeeds shows that the servers listen.
        """
        given: 'a lamp'
            var lamp = host.lampPath()

        when: '`oillamp at` starts a session'
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'the session starts'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'its `CheckEndpoints` step connected to the sockets, rather than trusting the readiness file'
            outcome.stepKinds().contains('CheckEndpoints')

        and: 'the step names both sockets, since a desktop without a shell is as broken as the reverse'
            outcome.stepDetails().any { it.contains('vnc.sock') && it.contains('ssh.sock') }
    }

    def 'Oillamp fails a start whose sandbox reports itself ready but refuses connections, and shows the sandbox\'s log'() {
        reportInfo """
            Let's consider a sandbox as a user met it on a real machine: the desktop's socket file,
            `vnc.sock`, is there, the readiness file is written, and nothing listens. Oillamp
            reports `OIL-SANDBOX-004`, naming the desktop. The user must also learn why, without
            searching for it: the reason exists only in the container's own log, so the problem
            carries that log.
        """
        given: 'a sandbox whose VNC socket is there but answers nothing'
            host.machine { it.endpointRefusingConnections('vnc.sock') }
            var lamp = host.lampPath()

        when: '`oillamp at` is run'
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'the start does not succeed'
            outcome.status() != LampExitStatus.SUCCESS

        and: 'the problem is `OIL-SANDBOX-004`, and names the desktop'
            outcome.errors().any { it.toString().contains('desktop') }
            outcome.errors().any { it.toString().contains('OIL-SANDBOX-004') }

        and: 'the console shows the sandbox log'
            outcome.console().contains('sandbox log')

        and: 'it prints no instructions to connect a viewer to a socket that refuses connections'
            !outcome.console().contains('vncviewer')
    }

    def 'Oillamp removes a sandbox that fails to start, instead of leaving it running without a session'() {
        reportInfo """
            Once `podman run` has succeeded, a container runs in the background with the agent's
            desktop in it, using memory and processors, and recording if recording is on. If the
            start then fails, because the desktop never answers or the wait runs out, oillamp
            reports the problem and exits. An earlier oillamp left the container behind, with no
            session watching it, until the next `oillamp at` or `oillamp stop` on that lamp, while
            the user had every reason to believe that nothing was running.
        """
        given: 'a sandbox whose desktop never answers'
            host.machine { it.endpointRefusingConnections('vnc.sock') }

        when: '`oillamp at` is run'
            var outcome = host.oillamp.run('at', host.lampPath().toString())

        then: 'the start fails'
            outcome.status() != LampExitStatus.SUCCESS
            outcome.reported('OIL-SANDBOX-004')

        and: 'oillamp removed the container it started, after checking the sockets'
            var kinds = outcome.stepKinds().toList()
            kinds.lastIndexOf('RemoveContainer') > kinds.indexOf('CheckEndpoints')

        and: 'the console says it removed the sandbox that did not start'
            outcome.console().contains('removed the sandbox that did not start')
    }

    def 'Oillamp reports a bug that happens while the sandbox starts, and removes the container it started'() {
        reportInfo """
            Oillamp reports a bug in itself as one, `OIL-INTERNAL-001`, with where it happened. But
            if the bug happens after `podman run`, while oillamp waits for the sandbox to answer,
            the container already runs, and nothing else would remove it once oillamp exits. So
            oillamp removes it first.

            Here the bug is in a listener, code that receives oillamp's events as they happen, as a
            program built on oillamp listens to the start. The listener fails as soon as the
            container runs, and it is called on the thread that starts the sandbox.
        """
        given: 'a listener that fails once the container is running'
            var oillamp = host.oillamp.observedBy { event ->
                if (event instanceof LampEvent.StepSucceeded && event.step().kind() == 'RunContainer')
                    throw new IllegalStateException('the listener broke')
            }

        when: '`oillamp at` is run with that listener'
            var outcome = oillamp.run('at', host.lampPath().toString())

        then: 'the bug is reported as one'
            outcome.reported('OIL-INTERNAL-001')
            outcome.console().contains('the listener broke')

        and: 'the last step removed the container, and the console says so'
            outcome.stepKinds().toList().last() == 'RemoveContainer'
            outcome.console().contains('removed the sandbox that did not start')
    }

    def 'Oillamp does not mistake the previous session\'s readiness file for this session\'s'() {
        reportInfo """
            The container writes a readiness file, `ready.json`, into the sockets directory once
            its servers are up. The sockets directory outlives the container, so on every session
            after the first there is already a `ready.json` in it. A check that only asked whether
            the file exists would report the sandbox ready before the container has done anything,
            and the host would go on to hand out sockets that do not exist yet.

            Two things prevent that, and this scenario is about the second. Oillamp clears the
            previous session's files before the start, and it checks that the readiness file it
            finds names this session's id. The second still holds when the first fails, which can
            happen, since the clearing runs through `podman unshare`.
        """
        given: 'a lamp that has been used before, so that a readiness file is already there'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            var readyFile = lamp.resolve('.oillamp/sockets/infra/ready.json')
            var previous = Files.readString(readyFile)
            previous.contains('"session"')

        and: 'a later session, whose attempt to clear that file fails'
            host.machine {
                it.clockAt(Instant.parse('2026-09-22T18:30:00Z'))
                  .command('podman unshare rm', new Machine.Outcome.Finished(
                          1, '', 'rm: cannot remove: Operation not permitted', Duration.ofMillis(5)))
            }

        and: 'and whose container never writes a readiness file of its own'
            host.machine {
                it.command('podman run', new Machine.Outcome.Finished(
                        0, 'simulated-container-id', '', Duration.ofMillis(10)))
            }

        when: '`oillamp at` is run'
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'the session fails, because the only readiness file present is the old one'
            outcome.status() != LampExitStatus.SUCCESS

        and: 'the previous session\'s file is still there, unchanged, and was not taken for this session\'s'
            Files.readString(readyFile) == previous

        and: 'oillamp reports one of the `OIL-SANDBOX` problems'
            outcome.errors().any { it.toString().contains('OIL-SANDBOX-00') }
    }

    def 'Oillamp clears the previous session\'s socket files before it starts the next session'() {
        reportInfo """
            The sockets directory, `.oillamp/sockets`, outlives the container, so the previous
            session's socket files and its readiness file, `ready.json`, are still in it when the
            next session starts. A leftover `vnc.sock` once made wayvnc, the desktop's server,
            fail to start. So on an ordinary second run oillamp removes the old files before the
            container starts: wayvnc finds its socket path free, and the host never reads the
            previous session's readiness file.
        """
        given: 'a lamp that has been used before'
            var lamp = host.lampPath()
            host.oillamp.run('at', lamp.toString())
            var readyFile = lamp.resolve('.oillamp/sockets/infra/ready.json')
            var previous = Files.readString(readyFile)

        when: 'the lamp is used again with `oillamp at`, a minute later'
            host.machine { it.clockAt(Instant.parse('2026-09-22T18:30:00Z')) }
            var outcome = host.oillamp.run('at', lamp.toString())

        then: 'the session starts'
            outcome.status() == LampExitStatus.SUCCESS

        and: 'the readiness file is this session\'s, not the one that was already there'
            Files.readString(readyFile) != previous

        and: 'because the previous session\'s files were cleared first'
            outcome.stepKinds().contains('DeleteContainerOwnedFiles')
            outcome.steps().any { it.contains('previous session') }
    }

    def 'A second session on the same lamp starts as cleanly as the first'() {
        reportInfo """
            On a real machine, the first session on a lamp always worked. The second came up with
            a desktop that did not answer: it was the first session to find the previous session's
            socket file, `vnc.sock`, already there, so the desktop's server could not create it.
            Running `oillamp at` twice is the scenario that covers the state the user was in.
        """
        given: 'a lamp'
            var lamp = host.lampPath()

        when: '`oillamp at` runs twice in a row'
            var first = host.oillamp.run('at', lamp.toString())
            var second = host.oillamp.run('at', lamp.toString())

        then: 'both sessions start'
            first.status() == LampExitStatus.SUCCESS
            second.status() == LampExitStatus.SUCCESS

        and: 'the second checked its sockets, like the first'
            second.stepKinds().contains('CheckEndpoints')
    }

    def '`oillamp at --dry-run` plans to remove the previous session\'s socket files and readiness file'() {
        reportInfo """
            The previous session's socket files and its readiness file, `ready.json`, outlive the
            container in `.oillamp/sockets`. They belong to one of the sandbox's own users. Inside
            the container that user is number 1001; on this machine, the kernel translates it to a
            number from the block of subordinate ids reserved for the person running oillamp, a
            number that belongs to no real account. So a plain `rm` is refused with "operation not
            permitted", although the files are in a directory the user owns.

            That refusal is the same mechanism that stops the agent from deleting the recording of
            its own screen. Here it is in the way, so oillamp removes the files through
            `podman unshare`; a plan that showed an ordinary delete would fail on every machine.
            The scenario reads the plan `oillamp at --dry-run` prints, and finds the step that
            removes these files.
        """
        given: 'a lamp'
            var lamp = host.lampPath()

        when: '`oillamp at --dry-run` is run'
            var outcome = host.oillamp.run('at', lamp.toString(), '--dry-run')

        then: 'the plan has a step that removes the previous session\'s files'
            outcome.steps().any { it.contains('previous session') }

        and: 'the step names the readiness file'
            outcome.stepDetails().any { it.contains('ready.json') }
    }
}
