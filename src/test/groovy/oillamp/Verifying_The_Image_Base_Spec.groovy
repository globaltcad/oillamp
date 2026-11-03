package oillamp

import spock.lang.Narrative
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Tag
import spock.lang.Title

@Title('Verifying the image base')
@Narrative('''
Checks that the packages the sandbox image installs exist in Debian trixie.

A missing package would otherwise be found only when an image build fails halfway, on a user's
machine. This spike asks trixie directly. It needs a network inside the container, so it is
skipped where rootless podman has no networking backend. Run it with `./gradlew spikes`.
''')
@Tag('spike')
@Requires({ Spike.containerNetworkWorks() })
class Verifying_The_Image_Base_Spec extends Specification {

    /** The desktop packages in the Containerfile, and what oillamp loses if each is absent. */
    static final Map<String, String> REQUIRED = [
            'sway'        : 'the Wayland compositor the whole desktop is',
            'xwayland'    : 'X11 applications, which is how Swing apps run at all',
            'x11-xserver-utils': 'xhost, which gives the agent access to the X11 display',
            'x11-utils'   : 'xdpyinfo, for checking the X11 display',
            'wayvnc'      : 'the VNC server the human watches through',
            'wf-recorder' : 'the screen recording',
            'grim'        : 'lamp screenshot',
            'slurp'       : 'lamp screenshot --region',
            'wtype'       : 'lamp type and lamp key',
            'foot'        : 'a terminal inside the desktop',
            'openssh-server': 'the sshd the user gets a shell through',
            'socat'       : 'bridging that sshd onto a Unix socket',
            'catatonit'   : 'reaping orphaned processes as pid 1',
            'dbus'        : 'what at-spi and GTK expect to exist',
            'at-spi2-core': 'accessibility, which some toolkits hang without',
            'xdg-utils'   : 'xdg-open, so the agent can open a URL',
    ]

    /** Packages that were once in doubt, with what would be lost without them. */
    static final Map<String, String> DOUBTED = [
            'wlrctl'      : 'wlrctl toplevel, the agent\'s way to wait for, focus and close windows',
            'firefox-esr' : 'a browser for the agent to use',
    ]

    def setupSpec() {
        Spike.ensureBaseImage()
    }

    def 'Every package the sandbox image needs exists in Debian trixie'() {
        reportInfo """
            The image is built once and then cached by the hash of its contents, so a missing
            package fails on the user's very first run, after a long download, with an apt error
            that names one package and says nothing about what oillamp wanted it for.

            So each package is listed with the reason it is needed. If one has been renamed or
            dropped from trixie, whoever reads the failure knows at once whether the answer is to
            find the new name, or to change the feature that needed it.
        """
        given: 'the package list the image installs'
            var missing = [:]

        when: 'trixie\'s `apt-cache policy` is asked about each one'
            var result = Spike.run('podman', 'run', '--rm', Spike.BASE_IMAGE, 'sh', '-lc',
                    'apt-get update -qq >/dev/null 2>&1; ' +
                    REQUIRED.keySet().collect { "echo \"$it=\$(apt-cache policy $it 2>/dev/null | sed -n 's/^  Candidate: //p')\"" }.join('; '))
            result.out.readLines().each { line ->
                var (name, version) = line.tokenize('=') + ['']
                if (!version?.trim()) missing[name] = REQUIRED[name]
            }

        then: 'the query itself succeeded, or the answer below means nothing'
            result.ok

        and: 'nothing the image needs is absent'
            missing.isEmpty() ||
                    { throw new AssertionError("trixie is missing packages the image needs:\n" +
                            missing.collect { k, v -> "  $k: without it, oillamp loses: $v" }.join('\n') +
                            "\n\nFull output:\n${result.describe()}") }()
    }

    def 'The packages that were once in doubt are in Debian trixie, or the planned fallback applies'() {
        reportInfo """
            `wlrctl` and `firefox-esr` were once uncertain in trixie. Without `wlrctl`, `lamp`
            still works: it clicks through `lamp-pointer` and types with `wtype`. Only
            `wlrctl toplevel`, with which the agent waits for, focuses and closes windows, would be
            lost. This scenario records which is the case, and on purpose does not fail when a
            package is absent, because the sandbox still works without it.
        """
        expect: 'the query succeeds, and the report says for each package whether trixie has it'
            var result = Spike.run('podman', 'run', '--rm', Spike.BASE_IMAGE, 'sh', '-lc',
                    'apt-get update -qq >/dev/null 2>&1; ' +
                    DOUBTED.keySet().collect { "echo \"$it=\$(apt-cache policy $it 2>/dev/null | sed -n 's/^  Candidate: //p')\"" }.join('; '))
            result.ok

            result.out.readLines().each { line ->
                var (name, version) = line.tokenize('=') + ['']
                reportInfo(version?.trim()
                        ? "$name is available in trixie (${version.trim()})."
                        : "$name is NOT in trixie. Fallback applies: oillamp loses ${DOUBTED[name]}.")
            }
    }
}
