package oillamp

import dev.lamp.ExitStatus
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Narrative('''
`oillamp update <dir>` gets newer agent harnesses and a fresh model list, without touching the
agent's home.

The sandbox image contains **opencode**, **pi**, and **opencode's list of Eden AI models**. All
three are downloaded once, when the image is built. After that the image is reused, by later
sessions and by every other lamp with the same settings. Starting a lamp never looks for a newer
release. Without a way to ask for one, a user would keep whatever was current on the day the
image was first built.

`update` builds a new image for one lamp, and the next `oillamp at` uses it. Three rules make it
safe to run:

- **The lamp switches only after a successful build.** A failed update leaves the lamp as it was.
- **The agent's home is never changed.** Anything installed there stays, including the agent's
  own copy of a harness.
- **A lamp in use is refused.** Update takes the same lock as a session.

A successful update writes a revision into `.oillamp/image/tools-revision`. The revision is a
build argument, so it is part of the image's hash. Every later start reads the file, computes the
same tag, and finds the updated image.

Podman is simulated here, so no image is really built. The last scenario runs the real installer
script, with stand-in `npm`, `node` and `pi` commands.
''')
class UpdatingTheAgentToolsSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host
    Path lamp

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
        lamp = host.lampPath()
        // Updating needs an existing lamp. One session creates it, and its image.
        assert host.oillamp.run('at', lamp.toString()).status() == ExitStatus.SUCCESS
    }

    def 'An update builds a new image that the following sessions start from'() {
        reportInfo """
            The point of an update is that the next session runs the newer tools. So update builds
            an image and starts nothing, and the next `oillamp at` must start from that image,
            not from the old one.

            A user may have installed their own copy of pi into the agent's home. That copy is
            found before the one in the image, so after the update they would still run their
            old version. Update leaves the copy alone, but warns about it, so the user is not
            surprised.

            This scenario also pins pi to an exact version, to check that `agent_tools.versions`
            reaches npm.
        """
        given: 'the agent has its own copy of pi in its home'
            var ownCopy = agentHome().resolve('.local/bin/pi')
            Files.createDirectories(ownCopy.parent)
            Files.writeString(ownCopy, 'the agent installed this')

        and: 'the lamp asks for pi 1.2.0'
            pinPiTo('1.2.0')

        when:
            var update = host.oillamp.run('update', lamp.toString(), '--verbose')

        then: 'an image was built, but no sandbox was started'
            update.status() == ExitStatus.SUCCESS
            update.stepKinds().contains('BuildImage')
            !update.stepKinds().contains('RunContainer')

        and: 'npm is asked for exactly that version'
            update.console().contains('@earendil-works/pi-coding-agent@1.2.0')

        and: 'the build is told this is an update, which may not fail quietly'
            update.console().contains('AGENT_TOOLS_REVISION=')
            update.console().contains('AGENT_TOOLS_STRICT=true')

        and: 'the copy in the home is reported, and left as it was'
            update.console().contains('takes precedence')
            Files.readString(ownCopy) == 'the agent installed this'

        and: 'the lamp remembers the new image'
            Files.exists(revisionFile())

        when: 'the next session is planned'
            var next = host.oillamp.run('at', lamp.toString(), '--dry-run', '--verbose')

        then: 'it starts from the image the update built'
            next.status() == ExitStatus.SUCCESS
            var built = (update.console() =~ /localhost\/oillamp\/sandbox:[0-9a-f]{16}/)[0]
            next.console().contains(built)
    }

    def 'After an update, an ordinary rebuild still forgives a failed download'() {
        reportInfo """
            Only `oillamp update` is strict. It is the one command where the user asked for new
            tools, and where a failure can leave the lamp on its old image.

            An ordinary start builds the image again whenever its inputs change, for example when
            the user adds a package to `image.extra_apt_packages`. That build must forgive a
            failed download, as it always has: if npm is briefly unreachable, the user should
            still get a sandbox, not a failed start.

            So after an update, later starts still use the updated image, but they do not pass on
            strict mode.
        """
        given: 'the lamp was updated'
            assert host.oillamp.run('update', lamp.toString()).status() == ExitStatus.SUCCESS

        and: 'then the user adds a system package, so the next start must build the image again'
            var config = lamp.resolve('oillamp.toml')
            Files.writeString(config, Files.readString(config)
                    .replace('extra_apt_packages = []', 'extra_apt_packages = ["jq"]'))

        when:
            var next = host.oillamp.run('at', lamp.toString(), '--dry-run', '--verbose')

        then: 'the image is built again'
            next.stepKinds().contains('BuildImage')

        and: 'it still carries the revision of the update'
            next.console().contains('AGENT_TOOLS_REVISION=' + Files.readString(revisionFile()).strip())

        and: 'but it forgives failed downloads, like any ordinary build'
            !next.console().contains('AGENT_TOOLS_STRICT=true')
    }

    def 'An update removes the image the lamp no longer uses'() {
        reportInfo """
            The sandbox image is about 3.5 GB. Every update builds a new one, and the lamp never
            uses the old one again, so without cleaning up, each update would leave 3.5 GB behind.
            (Most of it is layers shared with the new image, but podman cannot free those either
            while the old image still exists.)

            So after a successful update, the image of the lamp's previous update is removed.
            The image of a lamp that was never updated is different: other lamps with the same
            settings may use it too, so update leaves that one alone.
        """
        when: 'a lamp that was never updated is updated'
            var first = host.oillamp.run('update', lamp.toString())

        then: 'its old image is kept, because other lamps may share it'
            first.status() == ExitStatus.SUCCESS
            !first.console().contains('removed the image')

        when: 'the lamp is updated again'
            var firstImage = (first.console() =~ /localhost\/oillamp\/sandbox:[0-9a-f]{16}/)[0]
            var second = host.oillamp.run('update', lamp.toString())

        then: 'the image of the first update is removed, since only this lamp used it'
            second.status() == ExitStatus.SUCCESS
            second.console().contains('removed the image the lamp used before, ' + firstImage)
    }

    def 'Two updates straight after each other still give two different images'() {
        reportInfo """
            Each update must build a new image, or podman would reuse the one from the last
            update, with the same old tools. The revision that makes the image new is based on
            the time. If the clock has not moved on between two updates (here it is stopped), the
            revision must change all the same.
        """
        when:
            var first = host.oillamp.run('update', lamp.toString())
            var firstRevision = Files.readString(revisionFile())
            var second = host.oillamp.run('update', lamp.toString())

        then:
            first.status() == ExitStatus.SUCCESS
            second.status() == ExitStatus.SUCCESS
            Files.readString(revisionFile()) != firstRevision
    }

    def 'A dry run shows the update and changes nothing'() {
        reportInfo """
            `--dry-run` works the same way for every oillamp command: it prints the plan and does
            nothing. For update, that means no image is built and the lamp keeps the image it
            uses now.
        """
        given: 'the lamp was updated once already'
            assert host.oillamp.run('update', lamp.toString()).status() == ExitStatus.SUCCESS
            var current = Files.readString(revisionFile())

        when:
            var dry = host.oillamp.run('update', lamp.toString(), '--dry-run')

        then:
            dry.status() == ExitStatus.SUCCESS
            Files.readString(revisionFile()) == current
    }

    def 'A failed update leaves the lamp on the image it had'() {
        reportInfo """
            An update downloads from npm and from Eden AI, and either can fail. If the lamp
            switched to a half-built image anyway, the next session might have no harness at all.
            So the lamp only switches after the build has succeeded. When the build fails, the
            user is told that nothing changed, and the next session is the same as the last one.
        """
        given: 'the lamp was updated once already'
            assert host.oillamp.run('update', lamp.toString()).status() == ExitStatus.SUCCESS
            var current = Files.readString(revisionFile())

        and: 'the next build will fail'
            host.machine { it.commandFailing('podman build', 1, 'catalog fetch failed') }

        when:
            var failed = host.oillamp.run('update', lamp.toString())

        then: 'the update reports the failure'
            failed.status() == ExitStatus.ERROR
            failed.console().contains('the lamp keeps the image it had')

        and: 'the lamp still uses the image from the earlier update'
            Files.readString(revisionFile()) == current
    }

    def 'A lamp that is in use is not updated, and the user is told which session holds it'() {
        reportInfo """
            A running session holds the lamp's lock. Building a new image underneath it would
            not change the running sandbox, and would leave the user unsure which tools they are
            using. So update takes the same lock as a session. If it is taken, update stops
            before building anything, with the same "busy" exit code as a second `oillamp at`.

            The message names the session that holds the lamp, as `oillamp at` does: when it
            started and its process id, read from the `session.json` that session wrote. With
            those, the user can find the session and stop it.
        """
        given: 'a session holds the lamp: it took the lock and wrote session.json'
            var channel = FileChannel.open(lamp.resolve('.oillamp/lock'), StandardOpenOption.WRITE)
            var lock = channel.lock()
            Files.writeString(lamp.resolve('.oillamp/session.json'),
                    '{"supervisorPid": 4242, "startedAt": "2026-10-15T09:00:00Z"}')

        when:
            var outcome = host.oillamp.run('update', lamp.toString())

        then: 'nothing is built, and the lamp is unchanged'
            outcome.status() == ExitStatus.LAMP_BUSY
            !outcome.stepKinds().contains('BuildImage')
            !Files.exists(revisionFile())

        and: 'the user is told which session holds it'
            outcome.console().contains('4242')
            outcome.console().contains('2026-10-15T09:00:00Z')

        cleanup:
            lock.release()
            channel.close()
    }

    def 'A version that is not a release number is refused before anything is built'() {
        reportInfo """
            `agent_tools.versions` accepts `latest` or an exact release number such as `1.2.0`.
            Anything else would reach npm as an argument, and fail there after minutes of
            building, with a message from npm instead of oillamp. So a wrong value is a
            configuration error, reported straight away, and the lamp is not changed.
        """
        given:
            pinPiTo('some release')

        when:
            var update = host.oillamp.run('update', lamp.toString())

        then:
            update.status() == ExitStatus.USAGE
            update.console().contains('expected latest or an exact version')
            !update.stepKinds().contains('BuildImage')
            !Files.exists(revisionFile())
    }

    def 'The installer forgives failures in a first build, but not in an update'() {
        reportInfo """
            The same script installs the harnesses for every image, and it has two modes.

            In an ordinary build, failures are only warnings. A sandbox without a harness still
            has its desktop, shell and recording, and failing the whole first build because npm
            was briefly unreachable would cost the user all of that.

            An update is different. The user asked for new tools, and the lamp is about to switch
            to this image. So in strict mode, the mode an update uses, any failure ends the build.
            That includes a harness that installs but reports a different version from the one
            that was asked for.

            The script runs for real here. `npm`, `node` and `pi` are stand-ins: npm and node
            succeed or fail as each step needs, and pi always says it is version 1.2.0.
        """
        given: 'npm and node that fail, and a pi that says it is 1.2.0'
            var bin = tmp.resolve('stand-ins')
            Files.createDirectories(bin)
            standIn(bin, 'npm', 'exit 1')
            standIn(bin, 'node', 'exit 1')
            standIn(bin, 'pi', 'echo 1.2.0')

        expect: 'an ordinary build continues after the failed install; an update stops'
            installer(bin, false, 'pi').exit == 0
            installer(bin, true, 'pi').exit == 1

        when: 'npm and node succeed'
            standIn(bin, 'npm', 'exit 0')
            standIn(bin, 'node', 'exit 0')

        then: 'an update succeeds when pi reports the version that was asked for'
            installer(bin, true, '@earendil-works/pi-coding-agent@1.2.0').exit == 0

        and: 'and fails when pi reports a different one'
            installer(bin, true, '@earendil-works/pi-coding-agent@1.3.0').exit == 1
    }

    def 'An update accepts the version it asked for, even when the tool prints more than the number'() {
        reportInfo """
            In strict mode the installer checks that each harness reports the version that was
            asked for. What `--version` prints is up to the tool. opencode and pi print just the
            number today, but if one of them printed `pi 1.2.0` instead, comparing the whole
            output with `1.2.0` would make every pinned update fail, although the right version
            was installed. So the installer takes the version number out of the output, and
            compares only that.

            The stand-in pi here prints `pi 1.2.0`.
        """
        given: 'a pi that prints its name before its version'
            var bin = tmp.resolve('stand-ins')
            Files.createDirectories(bin)
            standIn(bin, 'npm', 'exit 0')
            standIn(bin, 'node', 'exit 0')
            standIn(bin, 'pi', 'echo "pi 1.2.0"')

        expect: 'an update that asked for 1.2.0 succeeds'
            installer(bin, true, '@earendil-works/pi-coding-agent@1.2.0').exit == 0

        and: 'one that asked for 1.3.0 still fails'
            installer(bin, true, '@earendil-works/pi-coding-agent@1.3.0').exit == 1
    }

    private void pinPiTo(String version) {
        var config = lamp.resolve('oillamp.toml')
        Files.writeString(config, Files.readString(config).replace('pi = "latest"', "pi = \"$version\""))
    }

    private static void standIn(Path bin, String name, String body) {
        var file = bin.resolve(name)
        Files.writeString(file, "#!/bin/bash\n$body\n")
        file.toFile().setExecutable(true)
    }

    /** Runs the image's installer script on this machine, with the stand-ins first on `PATH`. */
    private static Map installer(Path bin, boolean strict, String tool) {
        var script = Path.of('src/main/resources/image/build/install-agent-tools.sh').toAbsolutePath()
        var process = new ProcessBuilder('bash', script.toString(), tool).redirectErrorStream(true)
        process.environment().put('PATH', bin.toString() + ':/usr/bin:/bin')
        process.environment().put('AGENT_TOOLS_STRICT', strict.toString())
        var running = process.start()
        var output = running.inputStream.text
        [exit: running.waitFor(), output: output]
    }

    private Path revisionFile() { lamp.resolve('.oillamp/image/tools-revision') }

    private Path agentHome() {
        Files.list(lamp).withCloseable { paths ->
            paths.filter { it.fileName.toString().startsWith('agent-lamp-') }.findFirst().orElseThrow()
        }
    }
}
