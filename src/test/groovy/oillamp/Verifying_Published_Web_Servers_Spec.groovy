package oillamp

import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampWebPort
import spock.lang.Narrative
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Subject
import spock.lang.Tag
import spock.lang.Title

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

import static oillamp.RealLamps.*

@Title('Verifying published web servers')
@Narrative('''
Web servers inside a real sandbox, opened from this machine, with real podman and the real image.

A lamp's `[web]` section publishes web servers that run in the sandbox to the browser on this
machine. The data path crosses three programs that the fast scenarios can only simulate:

```
 HTTP client ──▶ 127.0.0.1:<host port>          the session's relay, on this machine
                   │
                   ▼
                 sockets/infra/web-<name>.sock   socat as the infra user, in the sandbox
                   │
                   ▼
                 127.0.0.1:<port>                the agent's web server, in the sandbox
```

With `publish = "any"`, the session also finds a server the agent starts on a port nobody listed.
It reads the sandbox's `/proc/net/tcp` with `podman exec --user lamp`, keeps the listening sockets
of the agent's uid (1000 inside the container), and starts the bridge for the new port with
`podman exec --detach --user lamp socat`. Whether `/proc/net/tcp` shows the container's own uids,
and whether such a bridge can listen where the host can connect, are beliefs about podman that
only a real container can confirm.

This is a spike. Its scenarios follow one lamp, in order: each relies on what the one before it
left behind. The servers are Python's `http.server`, started as the agent with `Lamp.exec`, and
the client is a plain HTTP request over a socket, with the `Host` header a browser would send for
the link.
''')
@Subject(Lamp)
@Tag('spike')
@Stepwise
@Requires({ Spike.containerNetworkWorks() })
class Verifying_Published_Web_Servers_Spec extends Specification {

    @Shared Path directory = newLampPath('web')
    @Shared Lamp lamp
    @Shared List<LampEvent> heard = new CopyOnWriteArrayList<>()
    @Shared List<Process> servers = []

    def cleanupSpec() {
        servers.each { it.destroy() }
        lamp?.close()
        remove(directory)
    }

    def 'A session of a lamp with `publish = "any"` publishes opencode\'s port 4096 from the start'() {
        reportInfo """
            Let's consider a lamp whose `oillamp.toml` sets `publish = "any"` under `[web]` and lists
            no ports of its own, so that it publishes the default, opencode's port 4096, from the
            start. When the session is up, it reports the link as `LampEvent.WebPublished`, on a port
            of this machine's loopback address that the session picked.
        """
        given: 'a lamp whose `oillamp.toml` sets `publish = "any"`'
            Files.createDirectories(directory)
            Files.writeString(directory.resolve('oillamp.toml'), 'schema_version = 1\n\n[web]\npublish = "any"\n')

        when: 'the lamp is started with `Lamp.start`'
            lamp = Lamp.at(directory).onEvent { heard << it }.start()

        then: 'the session comes up, and reports `opencode` published, port 4096 in the sandbox'
            lamp.awaitRunning(FIRST_START)
            eventually(Duration.ofSeconds(30)) { published('opencode') != null }
            published('opencode').sandboxPort() == 4096
            published('opencode').link().host == "${agentId(directory)}.localhost"
    }

    def 'A server the agent starts on 127.0.0.1:4096 answers a request to the published port on this machine'() {
        reportInfo """
            Let's consider a person who starts a web server in the sandbox's shell on port 4096,
            where `opencode serve --port 4096` would listen, and opens the link oillamp printed. The
            request goes to 127.0.0.1 on the link's port, through the session's relay, through the
            infra user's socat on `web-opencode.sock`, to the server. Here the server is Python's
            `http.server`, started as the agent, and the request names the link's host, as a browser
            would.
        """
        given: 'Python\'s `http.server` started as the agent on 127.0.0.1:4096'
            servers << lamp.exec('python3', '-m', 'http.server', '4096', '--bind', '127.0.0.1')
            assert eventually(Duration.ofSeconds(30)) { inSandbox(lamp, 'bash', '-c', 'exec 3<>/dev/tcp/127.0.0.1/4096').ok },
                   'the server in the sandbox did not start listening'

        when: 'an HTTP GET is sent to 127.0.0.1 on the published port, naming the link\'s host'
            String answer = get(published('opencode'))

        then: 'the server in the sandbox answers with 200'
            answer.startsWith('HTTP/1.0 200')
    }

    def 'A server the agent starts on port 5173 is found, published as `port-5173`, and answers a request'() {
        reportInfo """
            Let's consider an agent that starts a development server on 127.0.0.1, port 5173, which
            no setting lists. With `publish = "any"`, the session finds it within a few seconds in
            the sandbox's `/proc/net/tcp`, by the agent's uid, starts a bridge for it in the sandbox
            with `podman exec`, opens a relay on this machine, and reports `port-5173`. This checks
            each belief on the way: that `/proc/net/tcp`, read with
            `podman exec --user lamp`, shows the listener with uid 1000, the agent's uid inside the
            container; that the bridge started that way listens where the host can connect; and that
            a request reaches the server.
        """
        given: 'Python\'s `http.server` started as the agent on 127.0.0.1:5173'
            servers << lamp.exec('python3', '-m', 'http.server', '5173', '--bind', '127.0.0.1')
            assert eventually(Duration.ofSeconds(30)) { inSandbox(lamp, 'bash', '-c', 'exec 3<>/dev/tcp/127.0.0.1/5173').ok },
                   'the server in the sandbox did not start listening'

        expect: 'the sandbox\'s `/proc/net/tcp`, read as the infra user, lists the listener on 5173 (`1435`) with uid 1000'
            var table = Spike.run('podman', 'exec', '--user', 'lamp', containerOf(directory),
                                  'cat', '/proc/net/tcp', '/proc/net/tcp6').out
            table.readLines().any { line ->
                var fields = line.trim().split(/\s+/)
                fields.size() > 7 && fields[1].endsWith(':1435') && fields[3] == '0A' && fields[7] == '1000'
            }

        when: 'the session has had time to look into the sandbox'
            eventually(Duration.ofSeconds(30)) { published('port-5173') != null }

        then: 'it reported `port-5173`, port 5173 in the sandbox'
            published('port-5173') != null
            published('port-5173').sandboxPort() == 5173

        and: 'an HTTP GET to its port on this machine is answered with 200 by the server in the sandbox'
            eventually(Duration.ofSeconds(10)) { get(published('port-5173')).startsWith('HTTP/1.0 200') }
    }

    /** The port the session reported published under `name`, or null. */
    private LampWebPort published(String name) {
        heard.findAll { it instanceof LampEvent.WebPublished }*.port().find { it.name() == name }
    }

    /** An HTTP GET of `/` on 127.0.0.1 at the port's host port, with the link's host; the whole answer. */
    private static String get(LampWebPort port) {
        try (var socket = new Socket('127.0.0.1', port.hostPort())) {
            socket.soTimeout = 10_000
            socket.outputStream.write(("GET / HTTP/1.1\r\nHost: ${port.link().authority}\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII))
            return new String(socket.inputStream.readAllBytes(), StandardCharsets.ISO_8859_1)
        } catch (IOException e) {
            return "no answer: $e"
        }
    }
}
