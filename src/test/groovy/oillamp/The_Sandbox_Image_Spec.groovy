package oillamp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Narrative
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

@Title('The sandbox image')
@Narrative('''
Static checks of the files the sandbox image is built from, in `src/main/resources/image`.

These files are shipped as resources and run only inside a container, so a typo in one of them
shows as a container that dies during startup, with a message nobody sees. Building the image to
find that out takes minutes. These scenarios take milliseconds, and find what can be found without
a container: a script that does not parse, a file the Containerfile copies that nobody wrote, a
reference to something a design decision removed.

They cannot check that the image works; the spikes do that.
''')
class The_Sandbox_Image_Spec extends Specification {

    static final Path IMAGE = Path.of('src/main/resources/image')

    @TempDir
    Path temp

    def 'Every shell script in the image parses: #script.fileName'() {
        reportInfo """
            `bash -n` is the cheapest check there is, and it finds the mistake that happens when
            someone edits a 150-line entrypoint: a quote left open, or a missing `fi`, twenty lines
            from where it was typed. Inside a container, that shows as process 1 exiting at once,
            which the host reports as "the sandbox did not become ready", which is true, but does
            not say what is wrong.
        """
        expect: 'the script exists, and `bash -n` finds no syntax error in it'
            script.toFile().exists()
            var check = Spike.run('bash', '-n', script.toString())
            assert check.ok, "shell syntax error in $script:\n${check.describe()}"

        where:
            script << [
                IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'),
                IMAGE.resolve('rootfs/usr/local/bin/lamp'),
                IMAGE.resolve('build/install-node.sh'),
                IMAGE.resolve('build/install-agent-tools.sh'),
                IMAGE.resolve('build/install-sdkman.sh'),
                IMAGE.resolve('rootfs/etc/profile.d/oillamp.sh'),
            ]
    }

    def 'Everything the Containerfile copies exists'() {
        given: 'the Containerfile'
            var containerfile = Files.readString(IMAGE.resolve('Containerfile'))

        expect: 'the two trees it copies are present'
            Files.isDirectory(IMAGE.resolve('rootfs'))
            Files.isDirectory(IMAGE.resolve('build'))
            containerfile.contains('COPY rootfs/ /')
            containerfile.contains('COPY build/ /tmp/oillamp-build/')

        and: 'and the files it then runs or chmods are in them'
            Files.isExecutable(IMAGE.resolve('build/install-node.sh'))
            Files.isExecutable(IMAGE.resolve('build/install-agent-tools.sh'))
            Files.isExecutable(IMAGE.resolve('build/install-sdkman.sh'))
            Files.exists(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            Files.exists(IMAGE.resolve('rootfs/usr/local/bin/lamp'))
            Files.exists(IMAGE.resolve('rootfs/usr/local/lib/oillamp/lamp-dock'))
    }

    def 'The image carries no trace of the Java desktop helper that a shell script replaced'() {
        reportInfo """
            An earlier design had a Java desktop helper, `oillamp-rfb` or `lamp-helper`. The
            `lamp` shell script replaced it: the agent can drive the desktop with the ordinary
            Wayland tools, and a script it can read is better than a program it cannot. This
            scenario makes sure that nothing of the old helper is left in the image's files.
        """
        expect: 'no file in the image mentions either, text or otherwise'
            allImageFiles.every { file ->
                // Bytes rather than readString: the image carries a PNG, and decoding that as
                // text throws. Searching the raw bytes also catches a reference embedded in
                // something that is not a text file at all.
                !containsAscii(file, 'lamp-helper') && !containsAscii(file, 'oillamp-rfb')
            }
    }

    /** True when the file's bytes contain this ASCII sequence, whatever kind of file it is. */
    private static boolean containsAscii(Path file, String needle) {
        var haystack = Files.readAllBytes(file)
        var target = needle.getBytes('US-ASCII')
        for (int start = 0; start <= haystack.length - target.length; start++) {
            var matched = true
            for (int i = 0; i < target.length; i++)
                if (haystack[start + i] != target[i]) { matched = false; break }
            if (matched) return true
        }
        false
    }

    def 'The compositor\'s configuration binds no key to running a command'() {
        reportInfo """
            sway runs as `lamp`, the user that owns the recording and the VNC server, and the
            agent can type into the desktop. A single `bindsym … exec …` would therefore give the
            agent a way to run commands as `lamp`, and to end the recording unnoticed, which is
            the one guarantee the human relies on.

            The configuration says this in a comment; this scenario enforces it.

            `kill` is allowed, for the user's Ctrl+Super+Q. In sway it does not end a process: it
            asks the focused window to close, as its close button would. Every window belongs to
            a program the agent started, which the agent can close anyway.
        """
        when: 'the key bindings are read from sway\'s configuration'
            var config = Files.readString(IMAGE.resolve('rootfs/etc/oillamp/sway/config'))
            var bindings = config.readLines()
                    .findAll { it.trim().startsWith('bindsym') }

        then: 'there are bindings, or this scenario is checking nothing'
            !bindings.isEmpty()

        and: 'not one of them runs anything'
            bindings.every { line ->
                !(line =~ /\b(exec|exit|reload)\b/)
            }
    }

    def 'The dock runs as the agent, so that its buttons run nothing the agent could not run itself'() {
        reportInfo """
            Every button on the dock runs a command the agent wrote, whenever the user clicks it.
            Started as the agent, that is no more than the agent can already do. Started as
            `lamp`, the user that owns the recording, one click would let the agent's text end
            the recording. So the entrypoint must start it as the agent and never as `lamp`.
        """
        when: 'the entrypoint\'s lines that start the dock are read'
            var entrypoint = Files.readString(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            var dockLines = entrypoint.readLines().findAll { it.contains('lamp-dock') || it.contains(' dock ') }

        then: 'the entrypoint starts the dock as the agent, and never as `lamp`'
            entrypoint.contains('drop agent 077 dock ')
            dockLines.every { !it.contains('drop lamp') }
    }

    def 'The entrypoint serves each published web port on a socket the host can reach, as the infra user, and clears old ones first'() {
        reportInfo """
            Let's consider a lamp that publishes opencode's port 4096 to the person's browser.
            `runtime.env` names the port in `OILLAMP_WEB`, as `opencode:4096`. For each entry the
            entrypoint starts socat as the infra user `lamp`, never as the agent: it listens on
            `/oillamp/sockets/infra/web-<name>.sock` and connects each connection to
            `127.0.0.1:<port>`, where the agent's server listens. The agent cannot replace or
            remove a socket in that directory, so it cannot put something of its own in the
            bridge's place.

            The host reaches the socket as it reaches the desktop's `vnc.sock` beside it: the
            directory belongs to the infra user and anyone may enter it, and the socket must be
            writable by everyone, `mode=666`, because to the infra user the host user is an
            outsider. The lamp's `.oillamp` directory on the host, mode 0700, keeps every other
            user of the machine out.

            The socket directory outlives the container, so the entrypoint first removes every
            `web-*.sock` an earlier session left. A file left for a port the lamp no longer lists
            would otherwise look as if that port were still served.

            A web bridge is a helper, not a critical process. If it stops, only its link stops
            working, so the session goes on, and the line the bridge leaves in the sandbox's log
            says that it stopped. The proxy and model bridges stay critical: without them the
            agent has no network and no model, and the session ends.
        """
        when: 'the entrypoint is read, and in it the loop that starts the web bridges'
            var entrypoint = Files.readString(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            var loop = entrypoint.substring(entrypoint.indexOf('for published in $OILLAMP_WEB'))
            var bridge = loop.substring(0, loop.indexOf('\ndone'))

        then: 'it starts one bridge per entry of `OILLAMP_WEB`, as `lamp`, from `web-<name>.sock` to `127.0.0.1:<port>`, at mode 666'
            entrypoint.contains('for published in $OILLAMP_WEB; do')
            bridge.contains('drop lamp 077 "web-$name" ')
            bridge.contains('"UNIX-LISTEN:/oillamp/sockets/infra/web-${name}.sock,fork,unlink-early,mode=666"')
            bridge.contains('"TCP:127.0.0.1:${port}"')

        and: 'each web bridge is a helper, whose stopping is logged and does not end the session, while the proxy bridge stays critical'
            bridge.contains('HELPER_PIDS+=("$LAST_PID")')
            !bridge.contains('BRIDGE_PIDS')
            bridge.contains('the web bridge stopped')
            entrypoint.contains('CRITICAL=("$SWAY_PID" "$VNC_PID" "${BRIDGE_PIDS[@]}")')
            entrypoint.substring(entrypoint.indexOf('drop lamp 077 proxy socat')).readLines().take(3).any { it.contains('BRIDGE_PIDS+=') }

        and: 'it removes the `web-*.sock` files of earlier sessions before it starts anything'
            entrypoint.indexOf('/oillamp/sockets/infra/web-*.sock') < entrypoint.indexOf('for published in $OILLAMP_WEB')
    }

    def 'If the dock cannot be installed, the image still builds, and only the dock is missing'() {
        reportInfo """
            The dock is a convenience on top of the desktop, ssh and the recording. If Debian ever
            stops shipping one of its GTK packages, the image must still build, and the dock
            reports that it cannot run instead of being started again every few seconds.
        """
        when: 'the Containerfile, the entrypoint and the dock are read'
            var containerfile = Files.readString(IMAGE.resolve('Containerfile'))
            var entrypoint = Files.readString(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            var dock = Files.readString(IMAGE.resolve('rootfs/usr/local/lib/oillamp/lamp-dock'))

        then: 'the packages are installed on their own, and a failure only says so'
            containerfile.contains('gir1.2-gtklayershell-0.1 \\\n      || echo "GTK for Python unavailable')

        and: 'the dock says it cannot run, and the entrypoint then stops starting it'
            dock.contains('CANNOT_RUN = 78')
            entrypoint.contains('[ "$status" = 78 ] && exit 0')
    }

    def 'The agent is allowed on the X11 display, so that Swing applications can open windows'() {
        reportInfo """
            X11 applications such as Java Swing draw through Xwayland, which sway starts as the
            infra user `lamp`. Xwayland only accepts its own user, so the agent was refused with
            "Authorization required" until the entrypoint started granting it access.

            Three things have to hold together. sway must start Xwayland at once and keep it
            running, because a new Xwayland forgets the grant. The entrypoint must grant it. And
            the image must contain `xhost`, the program that grants it.
        """
        when: 'sway\'s configuration, the entrypoint and the Containerfile are read'
            var sway = Files.readString(IMAGE.resolve('rootfs/etc/oillamp/sway/config'))
            var entrypoint = Files.readString(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            var containerfile = Files.readString(IMAGE.resolve('Containerfile'))

        then: 'Xwayland starts with sway and stays running'
            sway.readLines().any { it.trim() == 'xwayland force' }

        and: 'the entrypoint lets the agent user connect'
            entrypoint.contains('xhost +si:localuser:agent')
            entrypoint.contains('install -d -o root -g root -m 1777 /tmp/.X11-unix')

        and: 'the image contains xhost'
            containerfile.contains('x11-xserver-utils')
    }

    def 'The sandbox\'s sshd is configured so that an ssh connection cannot become a tunnel out'() {
        reportInfo """
            Every outbound connection from the sandbox goes through the egress proxy. With
            forwarding left on, ssh into the sandbox would be a way past it: `ssh -L` would reach
            anything the container can reach, and the proxy would never see it. These five options
            are what make the network policy hold.
        """
        when: '`sshd_config` is read'
            var sshd = Files.readString(IMAGE.resolve('rootfs/etc/oillamp/sshd_config'))

        then: 'it sets each forwarding option to `no`'
            forbidden.each { option ->
                assert sshd.contains("$option no"), "sshd_config must set `$option no`"
            }

        and: 'and password login is off, since the only credential is the per-lamp key'
            sshd.contains('PasswordAuthentication no')
            sshd.contains('PubkeyAuthentication yes')

        where:
            forbidden = ['AllowTcpForwarding', 'AllowStreamLocalForwarding',
                         'AllowAgentForwarding', 'X11Forwarding', 'PermitTunnel']
    }

    def 'The agent\'s shell is told where the proxy is, in every form a tool might read'() {
        reportInfo """
            There is no DNS in the sandbox. A tool that does not honour the proxy variables fails,
            so the tools that do must find them already set. They disagree about capitals, so
            the profile exports both spellings.
        """
        when: 'the profile, `/etc/profile.d/oillamp.sh`, is read'
            var profile = Files.readString(IMAGE.resolve('rootfs/etc/profile.d/oillamp.sh'))

        then: 'it sets the proxy variables in capitals and in small letters'
            ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy', 'NO_PROXY', 'no_proxy']
                    .every { profile.contains("export $it") || profile.contains("$it=") }

        and: 'and the JVM, which reads none of them, is told separately'
            profile.contains('JAVA_TOOL_OPTIONS')
            profile.contains('-Dhttps.proxyHost=127.0.0.1')
    }

    def 'Java in the agent\'s shell finds native libraries in `~/libs` and in the system directories'() {
        reportInfo """
            The agent's guide promises that `System.loadLibrary` finds libraries in `~/libs`, with
            no extra flags. Java must still find the ones installed with the system: the shell
            once set `-Djava.library.path=~/libs`, which replaced Java's list instead of adding to
            it. This scenario loads the real profile into bash, and asks a real JVM for its
            library path.
        """
        given: 'a home directory with a small Java program that prints its library path'
            var home = Files.createTempDirectory('oillamp-agent-home')
            var probe = home.resolve('LibraryPath.java')
            Files.writeString(probe, 'class LibraryPath { public static void main(String[] a) { ' +
                    'System.out.println(System.getProperty("java.library.path")); } }')
            var java = Path.of(System.getProperty('java.home'), 'bin', 'java').toString()

        when: 'a shell reads the profile and runs Java'
            var process = new ProcessBuilder('bash', '-c',
                    '. "$PROFILE" && exec "$JAVA" "$HOME/LibraryPath.java"')
            process.environment().putAll(HOME: home.toString(), JAVA: java, LD_LIBRARY_PATH: '',
                    PROFILE: IMAGE.resolve('rootfs/etc/profile.d/oillamp.sh').toString())
            var started = process.redirectErrorStream(false).start()
            var paths = started.inputStream.text.trim().split(':') as List
            started.waitFor()

        then: 'the agent\'s own directory comes first, and the system directories are still there'
            paths.first() == home.resolve('libs').toString()
            paths.contains('/usr/lib')
    }

    def 'The agent harnesses are installed into the image, so that they are there from the first session'() {
        reportInfo """
            The harnesses are installed when the image is built, so the agent has them the moment
            it logs in, without waiting for downloads. They could also be installed later, through
            the egress proxy. That is why the build installs them, and why the default is not
            empty.

            The names a user writes in `oillamp.toml` are not package names, and the mapping
            between the two goes out of date: a project renames itself, a scope changes, and the
            sandbox ships without a harness, without an error. So the mapping is pinned here.
        """
        when: 'the installer script is read'
            var script = Files.readString(IMAGE.resolve('build/install-agent-tools.sh'))

        then: 'the two harnesses map to the packages that actually publish them'
            script.contains('@earendil-works/pi-coding-agent')
            script.contains('opencode) echo "@opencode/cli"')

        and: 'and the image asks for both by default, so a plain build is usable'
            Files.readString(IMAGE.resolve('Containerfile')).contains('ARG AGENT_TOOLS="opencode pi"')
    }

    def 'An ordinary image build goes on when a harness fails to install, but an explicit update fails'() {
        reportInfo """
            A harness is a convenience; the desktop, the shell, the recording and ssh are what the
            sandbox is for. Losing all of them because a package registry was briefly unreachable
            would be a bad trade, made without asking. The user asked for this explicitly.

            So ordinary builds report the failure and go on. An explicit update is different: it
            must fail, rather than claim that tools or a model catalog were updated when they
            were not.
        """
        when: 'the installer script is read'
            var script = Files.readString(IMAGE.resolve('build/install-agent-tools.sh'))

        then: 'the script succeeds by default but has a strict mode for updates'
            script.contains('exit 0')
            script.contains('${AGENT_TOOLS_STRICT:-false}')
            script.contains('exit 1')

        and: 'and each individual tool is attempted independently, not as one all-or-nothing step'
            script.contains('WARNING: could not install')
    }

    def 'The image puts the pi extension where a bind-mounted home cannot hide it'() {
        reportInfo """
            pi reads its extensions from its agent directory, which is in the agent's home, and
            that home is a bind mount from the lamp. So anything the image writes there at build
            time is hidden the moment the container starts.

            So the extension is built into `/usr/local/share`, and copied into the home once per
            session, and, like everything else about the tools, it may not stop the sandbox.
        """
        when: 'the installer script and the entrypoint are read'
            var installer = Files.readString(IMAGE.resolve('build/install-agent-tools.sh'))
            var entrypoint = Files.readString(
                    IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))

        then: 'the build installs it outside the home, using pi\'s own relocation variable'
            installer.contains('PI_CODING_AGENT_DIR')
            installer.contains('/usr/local/share/oillamp/pi')
            installer.contains('git:github.com/edenai/pi-edenai')

        and: 'and the entrypoint copies the whole agent directory across, not just an extensions dir'
            entrypoint.contains('seed_pi_agent_dir')
            entrypoint.contains('target="$HOME/.pi/agent"')

        and: 'never over anything the agent already has'
            entrypoint.contains('[ -e "$target/$name" ] && continue')

        and: 'and cannot take the session down with it'
            entrypoint.contains('seed_pi_agent_dir || true')
    }

    def 'Both harnesses reach Eden AI through oillamp\'s model relay, hold no key, and are offered only EU models'() {
        reportInfo """
            The harnesses, pi and opencode, never hold the model key. They send their requests to
            oillamp's model relay at an address inside the sandbox, `127.0.0.1:3129`, and oillamp
            adds the key on the host. The address is the same in every sandbox; where it leads is
            decided outside.

            pi's Eden AI extension reads its endpoint from `EDENAI_BASE_URL` and its key from
            `EDENAI_API_KEY`, which must not be empty, so the profile sets a placeholder. With
            `EDENAI_EU_ONLY` it offers only the models served in the EU. Oillamp decides on the
            host whether that applies, on for Eden AI and off for a model server of the user's
            own, and says so in `OILLAMP_MODEL_EU_ONLY`; it is on unless oillamp said otherwise.
            The profile sets all three after reading `runtime.env`.

            opencode has Eden AI built in, but pointed at the global endpoint, and with a model
            list mostly made of models the EU endpoint does not serve. The build writes a
            configuration file that points it at the relay instead, and the profile names that file
            in `OPENCODE_CONFIG`. The model list in it comes from the EU endpoint's own catalog,
            which needs no key, and only EU models are kept from it.

            Inside the container, a bridge run by the infrastructure user connects the relay's
            port to the socket on which oillamp listens.
        """
        when: 'the profile, the installer script and the opencode configuration writer are read'
            var profile = Files.readString(IMAGE.resolve('rootfs/etc/profile.d/oillamp.sh'))
            var installer = Files.readString(IMAGE.resolve('build/install-agent-tools.sh'))
            var writer = Files.readString(IMAGE.resolve('build/write-opencode-config.mjs'))

        then: 'pi is told the relay and a placeholder key, after the session\'s own variables are read'
            profile.contains('export EDENAI_BASE_URL=http://127.0.0.1:${OILLAMP_MODEL_PORT:-3129}/v3')
            profile.contains('export EDENAI_API_KEY=held-by-oillamp-on-the-host')

        and: 'EU only unless oillamp said otherwise, as the profile\'s own line decides it in bash'
            var euLine = profile.lines().filter { it.contains('OILLAMP_MODEL_EU_ONLY') && !it.startsWith('#') }.findFirst().orElseThrow()
            euOnlyWith(euLine, null) == '1'
            euOnlyWith(euLine, '1') == '1'
            euOnlyWith(euLine, '0') == ''
            profile.indexOf('EDENAI_BASE_URL=') > profile.indexOf('runtime.env && set +a')
            profile.indexOf('EDENAI_API_KEY=held') > profile.indexOf('runtime.env && set +a')

        and: 'the relay\'s port leads to oillamp\'s socket on the host'
            var entrypoint = Files.readString(IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            entrypoint.contains('drop lamp 077 model socat "TCP-LISTEN:${OILLAMP_MODEL_PORT},bind=127.0.0.1,reuseaddr,fork"')
            entrypoint.contains('UNIX-CONNECT:/oillamp/sockets/host/model.sock')

        and: 'opencode is given its configuration, which the build writes'
            profile.contains('export OPENCODE_CONFIG=/usr/local/share/oillamp/opencode/opencode.json')
            installer.contains('OPENCODE_CONFIG_FILE=/usr/local/share/oillamp/opencode/opencode.json')
            installer.contains('write-opencode-config.mjs')

        and: 'that configuration sends opencode to the relay, and takes its models from the EU catalog'
            writer.contains('const RELAY_BASE_URL = "http://127.0.0.1:3129/v3"')
            writer.contains('baseURL: RELAY_BASE_URL')
            writer.contains('fetch(`${EU_BASE_URL}/models`')
            writer.contains('region?.code?.toLowerCase() === "eu"')

        and: 'and if it cannot be written, the build goes on'
            installer.contains('could not configure opencode')
    }

    def 'opencode\'s configuration has opencode load the skills the agent writes in `~/.genie/skills`'() {
        reportInfo """
            The agent keeps the skills it writes for itself in `~/.genie/skills`, one directory
            with a `SKILL.md` each. opencode looks for skills in directories of its own, such as
            `~/.config/opencode/skills`, and in the directories its configuration lists, where it
            expands a leading `~/` to the home directory: opencode 1 under `skills.paths`, opencode
            2 in `skills` itself. The build writes opencode's configuration into the image with
            `write-opencode-config.mjs`, and the profile names that file in `OPENCODE_CONFIG`. So
            that file lists `~/.genie/skills`. The file is in the image, not in the agent's home,
            so every lamp has it from its next start on, and the agent cannot change it.
        """
        when: 'the opencode configuration writer is read'
            var writer = Files.readString(IMAGE.resolve('build/write-opencode-config.mjs'))

        then: 'the configuration it writes lists `~/.genie/skills`, under `skills.paths` for opencode 1 and in `skills` for opencode 2'
            writer.contains('const GENIE_SKILLS = "~/.genie/skills";')
            writer.contains('skills: { paths: [GENIE_SKILLS] },')
            writer.contains('config.skills = [GENIE_SKILLS];')
    }

    @Requires({ Spike.run('node', '--version').ok })
    def 'opencode\'s configuration limits each Eden AI model\'s answers to what the EU catalog gives that model, and to 8192 tokens where it gives nothing'() {
        reportInfo """
            Every answer of a model is limited in length, thinking included. opencode reads the
            limit of each model from `limit.output` in its configuration, and asks the model for
            at most that many tokens. An answer that reaches the limit stops in the middle, often
            while the agent writes a large file. (opencode 1 also had a cap of its own, 32000
            tokens, which it applied whatever `limit.output` said. opencode 2 has none.)

            The build writes opencode's configuration into the image with
            `write-opencode-config.mjs`. The script takes the models from the catalog of Eden AI's
            EU endpoint, `https://api.eu.edenai.run/v3/models`, whose entry for a model gives that
            model's own limit in `max_output_tokens`, and copies it into `limit.output`. Oillamp
            does not choose these limits itself: Eden publishes them, and they change as Eden adds
            and updates models. The catalog is read when the image is built, so a lamp gets new
            limits with `oillamp update`.

            Some entries give no `max_output_tokens`. For those models the script pins
            `limit.output` to 8192 tokens, the default of pi's Eden AI extension. A higher guess
            is not safe: a model asked for more tokens than it can give may refuse the request
            with 400 instead of giving a shorter answer, and then every request to that model
            fails.

            Here the script runs with Node.js, and `fetch` answers from a stand-in for Eden's
            catalog with three EU models: one whose entry gives 128000 tokens, one whose entry
            gives 4096, and one whose entry has no `max_output_tokens`. The script writes the
            configuration for opencode 2, whose models are under the provider `edenai-eu`.
        """
        given: 'a module that Node.js loads first, and that makes `fetch` answer with a catalog of three EU models'
            var model = { String id, Integer maxOutputTokens ->
                [id: id, regions: [[code: 'eu', name: 'Europe']], capabilities: [output_modalities: ['text']]] +
                        (maxOutputTokens == null ? [:] : [max_output_tokens: maxOutputTokens])
            }
            var catalog = JsonOutput.toJson([data: [
                    model('example/gives-128000', 128000),
                    model('example/gives-4096', 4096),
                    model('example/gives-none', null),
            ]])
            var standIn = temp.resolve('stand-in-catalog.mjs')
            Files.writeString(standIn, "globalThis.fetch = async () => new Response(${JsonOutput.toJson(catalog)});\n")

        when: '`write-opencode-config.mjs` writes opencode\'s configuration from that catalog'
            var configuration = temp.resolve('opencode.json')
            var run = Spike.run('node', '--import', standIn.toUri().toString(),
                    IMAGE.resolve('build/write-opencode-config.mjs').toString(), configuration.toString())
            assert run.ok, run.describe()
            var models = new JsonSlurper().parse(configuration.toFile()).providers['edenai-eu'].models

        then: 'the configuration lists the three models'
            models.keySet() == ['example/gives-128000', 'example/gives-4096', 'example/gives-none'] as Set

        and: 'each model\'s `limit.output` is the `max_output_tokens` of its catalog entry, and 8192 for the entry without one'
            models['example/gives-128000'].limit.output == 128000
            models['example/gives-4096'].limit.output == 4096
            models['example/gives-none'].limit.output == 8192
    }

    @Requires({ Spike.run('node', '--version').ok })
    def 'opencode 2 is offered only the EU models, under oillamp\'s own provider `edenai-eu`, and its built-in provider `edenai` is turned off'() {
        reportInfo """
            opencode 2 brings a catalog of models of its own, and Eden AI is in it, under the
            built-in provider ID `edenai`. Most of those models are not served by Eden AI's EU
            endpoint. opencode 2 adds its catalog's models to any provider whose ID the catalog
            knows, and it ignores `whitelist`, with which opencode 1 kept them out. A model that
            only that catalog lists would reach the model relay, and Eden's EU endpoint would
            refuse it.

            So `write-opencode-config.mjs` puts the models of Eden's EU catalog under a provider
            ID that opencode's catalog does not know, `edenai-eu`, and adds the policy statement
            `provider.use` `edenai` `deny`, which removes the built-in provider from opencode's
            model selection. The provider `edenai-eu` sends its requests to the model relay, with
            the placeholder key from `EDENAI_API_KEY`, and puts each answer's token limit into
            `max_tokens`, the field opencode 1 sent.

            Here the script runs with Node.js, for opencode 2, and `fetch` answers from a stand-in
            for Eden's catalog with two models: `example/served-in-eu`, whose entry lists the
            region `eu`, and `example/served-in-us`, whose entry lists only `us`.
        """
        given: 'a stand-in for Eden\'s catalog with one model served in the EU and one served only in the US'
            var catalog = [euModel('example/served-in-eu'), usModel('example/served-in-us')]

        when: '`write-opencode-config.mjs` writes the configuration for opencode 2'
            var configuration = writeOpencodeConfiguration(catalog, '2')

        then: 'the only provider is `edenai-eu`, and it lists only the EU model'
            configuration.providers.keySet() == ['edenai-eu'] as Set
            var provider = configuration.providers['edenai-eu']
            provider.models.keySet() == ['example/served-in-eu'] as Set

        and: 'it sends requests to the relay, with the key from `EDENAI_API_KEY` and the limit in `max_tokens`'
            provider.settings.baseURL == 'http://127.0.0.1:3129/v3'
            provider.env == ['EDENAI_API_KEY']
            provider.compatibility.maxTokensField == 'max_tokens'

        and: 'a policy turns the built-in provider `edenai` off'
            configuration.policies == [[action: 'provider.use', resource: 'edenai', effect: 'deny']]
    }

    @Requires({ Spike.run('node', '--version').ok })
    def 'opencode 2 in the sandbox does not update itself, so `oillamp update` decides its version'() {
        reportInfo """
            A lamp chooses its opencode with `agent_tools.versions.opencode`, and `oillamp update`
            installs that version into a new image. opencode 2 can update itself, and would put
            the newer copy where the agent can write, in its home, where it outlasts the session and
            is found before the image's copy. Then the lamp's setting and `oillamp update` would
            no longer decide which opencode the agent runs. So the configuration sets `update` to
            `disable`.

            Here `write-opencode-config.mjs` runs with Node.js, for opencode 2, with a stand-in
            for Eden's catalog that lists one EU model.
        """
        when: '`write-opencode-config.mjs` writes the configuration for opencode 2'
            var configuration = writeOpencodeConfiguration([euModel('example/served-in-eu')], '2')

        then: 'its `update` is `disable`'
            configuration.update == 'disable'
    }

    @Requires({ Spike.run('node', '--version').ok })
    def 'For a lamp that pins opencode 1, the configuration has the shape opencode 1 reads: the provider `edenai`, with a `whitelist` of the EU models'() {
        reportInfo """
            A lamp made before opencode 2 may pin an opencode 1 release in
            `agent_tools.versions.opencode`, and opencode 1 does not read the configuration
            opencode 2 reads. The installer passes `write-opencode-config.mjs` the major version
            of the opencode it installed, and for opencode 1 the script writes the shape opencode
            1 reads: the built-in provider `edenai`, pointed at the model relay with
            `options.baseURL`, its models from Eden's EU catalog, and a `whitelist` with their IDs,
            which keeps opencode 1's own catalog models out of the list.

            Here the script runs with Node.js, for opencode 1, and `fetch` answers from a stand-in
            for Eden's catalog with two models: `example/served-in-eu`, whose entry lists the
            region `eu`, and `example/served-in-us`, whose entry lists only `us`.
        """
        given: 'a stand-in for Eden\'s catalog with one model served in the EU and one served only in the US'
            var catalog = [euModel('example/served-in-eu'), usModel('example/served-in-us')]

        when: '`write-opencode-config.mjs` writes the configuration for opencode 1'
            var configuration = writeOpencodeConfiguration(catalog, '1')

        then: 'it has the provider `edenai` under `provider`, pointed at the relay, and no `providers`'
            configuration.provider.keySet() == ['edenai'] as Set
            configuration.provider.edenai.options.baseURL == 'http://127.0.0.1:3129/v3'
            configuration.providers == null

        and: 'the provider lists only the EU model, and so does its `whitelist`'
            configuration.provider.edenai.models.keySet() == ['example/served-in-eu'] as Set
            configuration.provider.edenai.whitelist == ['example/served-in-eu']
    }

    def 'The image installs SDKMAN where it can be written to, which is not where it is built'() {
        reportInfo """
            SDKMAN is how a JVM project gets the JDK, Groovy or Gradle it asks for, and it is the
            only way to get one here: there is no sudo, no apt, and the root filesystem is
            read-only.

            The read-only filesystem is also why SDKMAN cannot be used from `/usr/local`. It writes
            while it works, candidates, caches and a lock file, so the copy that is used must be
            somewhere writable. The agent's home is writable and outlives the session, so a JDK
            installed in one session is still there in the next.

            The copy is made as `agent`, not as root. `/home/agent` is a bind mount from the lamp,
            and uid 1000 inside the container is the human outside it, while root inside is a
            subordinate id whose files they would find hard to delete afterwards.
        """
        when: 'the installer script, the entrypoint and the profile are read'
            var installer = Files.readString(IMAGE.resolve('build/install-sdkman.sh'))
            var entrypoint = Files.readString(
                    IMAGE.resolve('rootfs/usr/local/lib/oillamp/entrypoint'))
            var profile = Files.readString(IMAGE.resolve('rootfs/etc/profile.d/oillamp.sh'))

        then: 'the build puts it outside the home, where a bind mount cannot hide it'
            installer.contains('SDKMAN_DIR=/usr/local/share/oillamp/sdkman')

        and: 'and does not let the installer edit a shell profile of its own choosing'
            installer.contains('rcupdate=false')

        and: 'prompts are turned off, because an agent over ssh cannot answer one'
            installer.contains('sdkman_auto_answer=true')

        and: 'and nothing about it can fail the build'
            installer.contains('exit 0')
            installer.readLines().any { it.startsWith('main ||') }

        then: 'the entrypoint copies it into the home as the agent, once'
            entrypoint.contains('seed_sdkman')
            entrypoint.contains('setpriv --reuid=agent --regid=agent')
            entrypoint.contains('cp -a "$source" /home/agent/.sdkman')

        and: 'leaving an existing one alone, candidates and all'
            entrypoint.contains('[ -e /home/agent/.sdkman ] && return 0')

        and: 'and cannot take the session down with it'
            entrypoint.contains('seed_sdkman || true')

        then: 'the login shell sources the copy in the home, not the one in the image'
            profile.contains('export SDKMAN_DIR="$HOME/.sdkman"')
            profile.contains('. "$SDKMAN_DIR/bin/sdkman-init.sh"')

        and: 'guarded, because sdkman-init.sh is bash and /bin/sh here is not'
            profile.contains('[ -n "${BASH_VERSION:-}" ]')
    }

    private static List<Path> getAllImageFiles() {
        Files.walk(IMAGE).filter { Files.isRegularFile(it) }.toList()
    }

    /** What EDENAI_EU_ONLY is after running `line` in bash with OILLAMP_MODEL_EU_ONLY set to `value`. */
    private static String euOnlyWith(String line, String value) {
        var bash = new ProcessBuilder('bash', '-c', line + '\nprintf %s "${EDENAI_EU_ONLY:-}"')
        bash.environment().remove('EDENAI_EU_ONLY')
        if (value != null) bash.environment().put('OILLAMP_MODEL_EU_ONLY', value)
        else bash.environment().remove('OILLAMP_MODEL_EU_ONLY')
        var process = bash.start()
        var out = process.inputStream.text
        process.waitFor()
        out
    }

    /** An entry of Eden's model catalog for a text model served in the EU. */
    private static Map euModel(String id) {
        [id: id, regions: [[code: 'eu', name: 'Europe']], capabilities: [output_modalities: ['text']]]
    }

    /** An entry of Eden's model catalog for a text model served only in the US. */
    private static Map usModel(String id) {
        [id: id, regions: [[code: 'us', name: 'United States']], capabilities: [output_modalities: ['text']]]
    }

    /**
     * Runs `write-opencode-config.mjs` for the given opencode major version, with `fetch` answering
     * from a stand-in that lists `models` as Eden's catalog, and returns the configuration it wrote.
     */
    private Object writeOpencodeConfiguration(List<Map> models, String major) {
        var standIn = temp.resolve('stand-in-catalog-' + UUID.randomUUID() + '.mjs')
        Files.writeString(standIn, "globalThis.fetch = async () => new Response(${JsonOutput.toJson(JsonOutput.toJson([data: models]))});\n")
        var configuration = temp.resolve('opencode-' + UUID.randomUUID() + '.json')
        var run = Spike.run('node', '--import', standIn.toUri().toString(),
                IMAGE.resolve('build/write-opencode-config.mjs').toString(), configuration.toString(), major)
        assert run.ok, run.describe()
        new JsonSlurper().parse(configuration.toFile())
    }
}
