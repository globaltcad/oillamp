package oillamp

import dev.lamp.LampEvent
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The agent shows the user something on its desktop.
 *
 * <p>When the agent has something to show, a chart, a page, a program it wrote, it opens it on its
 * desktop and runs {@code lamp show "what it shows"}. That sends one line to the session on the
 * host, through a socket the sandbox sees as {@code /oillamp/sockets/host/desktop.sock}, and the
 * session reports {@link LampEvent.LookAtDesktop}: an application that shows the desktop (a program
 * built on oillamp's Java API, such as the desktop app Genies) opens it, and the terminal of {@code oillamp at} says so.
 *
 * <p>These scenarios run the real {@code lamp} script against a session on a simulated machine.
 * The socket, the session and the events are real.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@Requires({ Files.isExecutable(Path.of('/usr/bin/python3')) })
class ShowingTheUserTheDesktopSpec extends Specification {

    static final Path LAMP = Path.of('src/main/resources/image/rootfs/usr/local/bin/lamp').toAbsolutePath()

    @TempDir Path tmp
    @Subject ScenarioHost host

    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread session
    def ended

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def cleanup() {
        if (session?.alive) {
            host.oillamp.run('stop', host.lampPath().toString())
            session.join(30_000)
        }
    }

    def 'When the agent runs `lamp show`, the session reports a `LookAtDesktop` event and the terminal prints it'() {
        reportInfo """
            Let's consider an agent that opened a chart on its desktop and runs `lamp show "the
            chart you asked for"` in the sandbox. The `lamp` script sends one line to the session
            on the host, which reports a `LookAtDesktop` event with the agent's words, so that an
            application showing the desktop can open it; the terminal `oillamp at` runs in prints
            it. The agent is told the user was asked; whether they look is up to them.
        """
        given:
            var lamp = aRunningSession()

        when:
            var shown = lampShow('the chart you asked for')
            var look = waitFor(LampEvent.LookAtDesktop)
            assert host.oillamp.run('stop', lamp.toString()).succeeded()
            session.join(30_000)
            assert !session.alive

        then:
            shown.status == 0
            shown.output.strip() == 'The user was asked to look at your desktop. Whether and when they look is up to them.'
            look.what() == 'the chart you asked for'
            ended.console().contains('the agent asks you to look at its desktop: the chart you asked for')
    }

    def 'The session takes control characters out of what the agent asks to show, and cuts it at 200 characters'() {
        reportInfo """
            The words the agent gives `lamp show` are shown to the user, in an application or a
            terminal, through the `LookAtDesktop` event. Escape sequences and other control
            characters could change how a terminal shows everything after them, so the session
            replaces them with spaces; and a few words say what is shown, so the session cuts the
            text at 200 characters, ending it with `…`. Saying nothing is fine too.
        """
        given:
            aRunningSession()

        when:
            agentAsks(op: 'show', what: 'the \u001B[31mred\u001B[0m chart\u0007 ' + 'x' * 500)
            agentAsks(op: 'show')

        then:
            var looks = waitFor(LampEvent.LookAtDesktop, 2)
            looks[0].what().startsWith('the  red  chart  xxx')
            looks[0].what().length() == 200
            looks[0].what().endsWith('x…')
            looks[1].what() == ''
    }

    def 'The session answers any request but `show` on the desktop socket with an error, and reports nothing'() {
        reportInfo """
            `lamp show` reaches the session through the desktop socket, which the sandbox sees as
            `/oillamp/sockets/host/desktop.sock`. The agent can send anything to this socket. The
            session answers anything but `show` with the reason it refuses it, and reports no
            `LookAtDesktop` event.
        """
        given:
            aRunningSession()

        when:
            var answer = agentAsks(op: 'stop')

        then:
            !answer.ok
            answer.error == 'unknown request: stop. The one request here is show.'
            !reported.any { it instanceof LampEvent.LookAtDesktop }
    }

    def 'Without a session that answers, `lamp show` fails and says the user was not told'() {
        reportInfo """
            An agent that believes the user looks at something, while nobody was told, would wait
            for a reaction that is not coming. So when `lamp show` cannot reach the session, it
            says that the user was not told, and exits with an error.
        """
        when:
            var shown = lampShow('anything', tmp.resolve('nobody-listens.sock'))

        then:
            shown.status != 0
            shown.output.contains('the session did not answer')
            shown.output.contains('The user was not told.')
    }

    def 'The session tells applications the desktop\'s own size in its `SessionOpened` event'() {
        reportInfo """
            An application, a program built on oillamp's Java API that shows the sandbox's
            desktop, may give the desktop another size for a while, such as the size of the panel
            it shows it in. It gives it back its own size afterwards, which the session says when
            it opens: display.width and display.height.
        """
        given:
            aRunningSession('[display]\nwidth = 1600\nheight = 900\n')

        when:
            var opened = waitFor(LampEvent.SessionOpened)

        then:
            opened.desktopWidth() == 1600
            opened.desktopHeight() == 900
    }

    def 'The agent\'s guide says how to show something, and whether the desktop may change size'() {
        reportInfo """
            The agent learns about `lamp show` from AGENTS.md in its home. The guide also says that
            the desktop may take the size of the panel the user watches it in, because positions
            from an older screenshot are then wrong; unless the desktop is recorded, which keeps
            it at its size.
        """
        given:
            var lamp = host.lampPath()

        when: 'a lamp whose desktop is not recorded'
            host.givenConfig(lamp, 'schema_version = 1\n')
            host.oillamp.run('at', lamp.toString())
            var guide = Files.readString(home(lamp).resolve('AGENTS.md'))

        then:
            guide.contains('run `lamp show "what it is"`')
            guide.contains('may give your desktop the size of the panel they watch it in')
            guide.contains('a window that\nwould leave the screen is moved')

        when: 'the same lamp, recorded'
            host.givenConfig(lamp, 'schema_version = 1\n[recording]\nenabled = true\n')
            host.oillamp.run('at', lamp.toString())
            guide = Files.readString(home(lamp).resolve('AGENTS.md'))

        then:
            guide.contains('run `lamp show "what it is"`')
            guide.contains('Your desktop is recorded, so it keeps its size.')
            !guide.contains('size of the panel')
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private static Path home(Path lamp) { dev.lamp.Lamp.agentHome(lamp).orElseThrow() }

    private Path desktopSocket() { host.runtime.resolve('oillamp/k3v7x2ab/sockets/host/desktop.sock') }

    private Path aRunningSession(String toml = '') {
        var lamp = host.lampPath()
        host.givenConfig(lamp, 'schema_version = 1\n' + toml)
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(90)) }
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        session = Thread.start { ended = oillamp.run('at', lamp.toString()) }
        waitFor(LampEvent.Summary) { it.title() == 'your session is up' }
        lamp
    }

    private <T extends LampEvent> T waitFor(Class<T> kind, Closure<Boolean> which = { true }) {
        waitFor(kind, 1, which).first()
    }

    private <T extends LampEvent> List<T> waitFor(Class<T> kind, int count, Closure<Boolean> which = { true }) {
        var deadline = System.currentTimeMillis() + 60_000
        while (true) {
            var found = reported.findAll { kind.isInstance(it) && which(it) }
            if (found.size() >= count) return found as List<T>
            assert System.currentTimeMillis() < deadline : "no ${count} ${kind.simpleName} came"
            Thread.sleep(50)
        }
    }

    /** Runs the real `lamp show` with the desktop socket of the session, as the agent does. */
    private Map lampShow(String what, Path socket = desktopSocket()) {
        var process = new ProcessBuilder('bash', LAMP.toString(), 'show', what).redirectErrorStream(true)
        process.environment().put('LAMP_DESKTOP_SOCKET', socket.toString())
        var started = process.start()
        var output = started.inputStream.text
        started.waitFor()
        [status: started.exitValue(), output: output]
    }

    /** One JSON line to the session's desktop socket, one line back. */
    private Map agentAsks(Map request) {
        SocketChannel.open(UnixDomainSocketAddress.of(desktopSocket())).withCloseable { channel ->
            channel.write(ByteBuffer.wrap((JsonOutput.toJson(request) + '\n').getBytes(StandardCharsets.UTF_8)))
            channel.shutdownOutput()
            var answer = new ByteArrayOutputStream()
            var buffer = ByteBuffer.allocate(8192)
            while (channel.read(buffer) >= 0) {
                answer.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            (Map) new JsonSlurper().parseText(answer.toString(StandardCharsets.UTF_8).readLines().first())
        }
    }
}
