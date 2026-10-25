package oillamp

import dev.lamp.Lamp
import dev.lamp.LampEvent
import dev.lamp.LampFailedException
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Title('Serving the host tools over MCP')
@Narrative('''
MCP, the Model Context Protocol, is how an agent harness such as pi finds tools it does not have
built in, and calls them. oillamp serves the host tools as one MCP server. pi reaches it as it
reaches any local MCP server, by starting a program and speaking to its standard input and
output. The program is `socat`, which copies those bytes to the session's socket
`sockets/host/host-access.sock`, where the engine answers.

```
 pi ── stdin/stdout ── socat ── host-access.sock ──▶ the engine on the host
```

On that connection, every message is one line of JSON-RPC 2.0. A client first sends
`initialize`; after that it may ask for `tools/list` and make `tools/call`s, as many as it
likes, on the one connection, which stays open for the whole pi session. Each request carries
an `id`, and its answer carries the same `id`. A notification, such as
`notifications/cancelled`, has no `id` and gets no answer.

Only what host access needs is served: tools. There are no MCP resources or prompts, because a
resource read would be a second way to the host's files, past the checker.

## How the scenarios see it

The engine runs in this JVM against the simulated machine. The scenarios speak MCP to the
session's socket directly, as `socat` would, and a stand-in model service on loopback plays the
checker.
''')
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ServingTheHostToolsOverMcpSpec extends Specification {

    static final String MODEL = 'azure/gpt-6.1-sol'

    @TempDir Path tmp
    @Subject ScenarioHost host
    StandInChecker checker
    final List<LampEvent> events = new CopyOnWriteArrayList<>()
    Lamp lamp
    Path project

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen', 'env', 'bash') }
        checker = new StandInChecker()
        project = Files.createDirectories(host.home.resolve('project'))
        Files.writeString(project.resolve('notes.md'), 'Ship on Friday.\n')
    }

    def cleanup() {
        lamp?.close()
        checker?.close()
    }

    def 'pi introduces itself with initialize, and the server answers with a protocol version both speak, and that it has tools'() {
        reportInfo """
            Let's consider pi connecting to the server as a session starts. Its first message is
            `initialize`, naming the newest MCP version pi speaks in `protocolVersion`. The server
            answers with the same version when it speaks it too, and otherwise with the newest it
            does speak, `2025-11-25`, which a client may accept or hang up on. The answer's
            `capabilities` say what the server offers: `tools`, and nothing else.
        """
        given:
            givenHostAccess()
            start()

        when: 'one client asks for a version the server speaks, and another for one it does not'
            Map known
            Map unknown
            McpClient.connect(hostAccessSocket()).withCloseable {
                known = it.request('initialize', [protocolVersion: '2025-06-18', capabilities: [:], clientInfo: [name: 'pi', version: '1.4.0']])
            }
            McpClient.connect(hostAccessSocket()).withCloseable {
                unknown = it.request('initialize', [protocolVersion: '1999-01-01', capabilities: [:], clientInfo: [name: 'old', version: '0']])
            }

        then: 'the first gets its own version, the second the newest the server speaks'
            known.result.protocolVersion == '2025-06-18'
            unknown.result.protocolVersion == '2025-11-25'

        and: 'the server offers tools, and no resources or prompts'
            (known.result.capabilities as Map).keySet() == ['tools'] as Set
            known.result.serverInfo.name == 'oillamp'
    }

    def 'The server lists each tool with the JSON Schema its arguments are held to'() {
        reportInfo """
            `tools/list` answers with each tool this lamp offers: its name, a description for the
            model, and `inputSchema`, a JSON Schema of its arguments. pi shows the model that
            schema, and the server holds every call to the same one: the argument names it lists
            and no others (`additionalProperties` is false), with the JSON types it names. A
            number of bytes is a JSON integer, not a string with digits in it.

            Each tool also carries MCP's hints, such as `readOnlyHint` for a tool that only reads.
            They describe the tool for a client that wants to show them; they decide nothing.
        """
        given:
            givenHostAccess()
            start()

        when: 'pi lists the tools'
            Map listed = McpClient.initialized(hostAccessSocket()).withCloseable { it.request('tools/list', [:]) }
            Map read = (listed.result.tools as List<Map>).find { it.name == 'host_read_file' }

        then: 'reading a file takes a path, and optionally where to start and how much, as numbers'
            read.inputSchema.type == 'object'
            ((read.inputSchema as Map).get('properties') as Map).collectEntries { name, property -> [name, property['type']] } ==
                    [path: 'string', offset: 'integer', max_bytes: 'integer']
            read.inputSchema.required == ['path']
            read.inputSchema.additionalProperties == false
            read.annotations.readOnlyHint == true
    }

    def 'The server answers a call before initialize, a method it does not have, and a line that is not JSON with JSON-RPC errors'() {
        reportInfo """
            JSON-RPC, the message format MCP is made of, has numbered errors for messages a server
            cannot act on. The server answers with them and keeps the connection open: a client
            that sent one bad line can send a good one next.

            - A `tools/list` before `initialize`: `-32600`, an invalid request.
            - A method the server does not have, such as `resources/list`: `-32601`.
            - A line that is not JSON at all: `-32700`, a parse error, with no `id`, since none
              could be read.
        """
        given:
            givenHostAccess()
            start()
            var client = McpClient.connect(hostAccessSocket())

        when: 'a client lists tools before it initialized'
            var early = client.request('tools/list', [:])

        and: 'initializes, then asks for resources, then sends a line that is not JSON'
            client.request('initialize', [protocolVersion: '2025-06-18', capabilities: [:], clientInfo: [name: 'pi', version: '1.4.0']])
            var resources = client.request('resources/list', [:])
            client.sendLine('this is not json')
            var garbled = client.awaitAnswer('no id')

        and: 'then lists the tools after all'
            var listed = client.request('tools/list', [:])

        then:
            early.error.code == -32600
            resources.error.code == -32601
            garbled.error.code == -32700
            garbled.id == null
            listed.result.tools.size() > 0

        cleanup:
            client?.close()
    }

    def 'A call of a tool there is not, or with arguments that do not fit, is refused without asking the checker'() {
        reportInfo """
            A `tools/call` names a tool and gives its arguments. When there is no tool by that
            name, or the arguments do not fit the tool's schema, there is no request to judge,
            so the checker is not asked. The server answers with the JSON-RPC error `-32602`,
            invalid params, whose message says what did not fit, so that the model can correct
            the call. The session reports each as a `HostAccessRefused` event all the same, so an
            application can show the user that the agent tried.
        """
        given:
            givenHostAccess()
            start()

        when: 'the agent calls a tool there is not, and a read with a path that is a number'
            Map unknown
            Map misfit
            McpClient.initialized(hostAccessSocket()).withCloseable { client ->
                unknown = client.request('tools/call', [name: 'host_take_screenshot', arguments: [:]])
                misfit = client.request('tools/call', [name: 'host_read_file', arguments: [path: 42]])
            }

        then: 'both are invalid params, saying why, and the checker was never asked'
            unknown.error.code == -32602
            unknown.error.message.contains('host_take_screenshot')
            misfit.error.code == -32602
            misfit.error.message.contains('path: a string')
            checker.checked.isEmpty()

        and: 'the user hears of both'
            events.findAll { it instanceof LampEvent.HostAccessRefused }*.tool() == ['host_take_screenshot', 'host_read_file']
    }

    def 'Two calls on one connection are answered each by its id, the second while the first waits for the person'() {
        reportInfo """
            Each call is judged on its own, on a thread of its own, so one waiting does not hold
            up another on the same connection. Here the lamp's `host_access.commands = "ask"`
            makes a command wait for the person, and meanwhile the agent reads a file. The read
            is answered at once, under its own `id`; the command is answered once the person
            said yes.
        """
        given: 'a lamp that asks the person about every command'
            givenHostAccess(commands: 'ask')
            checker.verdict = '{"decision":"allow","risk":"executing","reason":"Fine."}'
            start()
            var client = McpClient.initialized(hostAccessSocket())

        when: 'the agent asks to run a command, and while the person is asked, reads a file'
            int command = client.send('tools/call', [name: 'host_bash', arguments: [command: 'echo built', cwd: '~/project']])
            var asked = waitFor(LampEvent.HostAccessAsked) { true }
            checker.verdict = StandInChecker.ALLOW_PASSIVE
            var read = client.request('tools/call', [name: 'host_read_file', arguments: [path: '~/project/notes.md']])

        then: 'the read is answered while the command still waits'
            McpClient.text(read.result as Map).contains('Ship on Friday.')
            !client.answered(command)

        when: 'the person says yes'
            lamp.decidePermission(asked.what(), true)

        then: 'the command is answered, under its own id'
            McpClient.text(client.awaitAnswer(command).result as Map).contains('built')

        cleanup:
            client?.close()
    }

    def 'When pi\'s connection closes, a call waiting for the person is withdrawn, and does not happen'() {
        reportInfo """
            pi stops the MCP server it started when its session ends, and with it `socat` and the
            connection. Nobody is left to read the answer to a call still waiting, so the
            engine withdraws every call of that connection. A call waiting for the person is
            refused as withdrawn, the question to the person is closed, and its command never
            runs, even when an answer comes in afterwards.
        """
        given: 'a lamp that asks the person about every command'
            givenHostAccess(commands: 'ask')
            checker.verdict = '{"decision":"allow","risk":"executing","reason":"Fine."}'
            start()
            var client = McpClient.initialized(hostAccessSocket())

        when: 'the agent asks to run a command, and pi\'s connection closes while the person is asked'
            client.send('tools/call', [name: 'host_bash', arguments: [command: 'touch too-late', cwd: '~/project']])
            var asked = waitFor(LampEvent.HostAccessAsked) { true }
            client.close()

        then: 'the call is refused as withdrawn'
            waitFor(LampEvent.HostAccessRefused) { it.why().contains('withdrew') }

        when: 'the application gives the person\'s yes anyway'
            lamp.decidePermission(asked.what(), true)

        then: 'the session says no question waits with that text, and the command never ran'
            var closed = thrown(LampFailedException)
            closed.message.contains('no host-access question waits with that text')
            !Files.exists(project.resolve('too-late'))
    }

    def 'When the session ends, the server closes its connections and its socket is gone'() {
        reportInfo """
            The socket exists only while a session with host access runs. When the session ends,
            it closes every connection, so pi sees its MCP server go away, and removes the socket
            file, so that nothing can connect to a session that is gone.
        """
        given:
            givenHostAccess()
            start()
            var socket = hostAccessSocket()
            var client = McpClient.initialized(socket)

        when: 'the application ends the session'
            lamp.close()
            lamp = null

        then: 'the socket file is gone, and the open connection was closed'
            !Files.exists(socket)
            client.closedByTheServer()

        cleanup:
            client?.close()
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private void givenHostAccess(Map options = [:]) {
        host.givenConfig(host.lampPath(), """
            schema_version = 1

            [host_access]
            enabled = true
            roots = ["${project}"]
            commands = "${options.commands ?: 'off'}"
            requirements = '''
            You may read and build ~/project.
            '''
        """.stripIndent())
    }

    private void start() {
        lamp = Lamp.at(host.lampPath()).onEvent { events << it }
                   .modelService(checker.address())
                   .model(MODEL)
                   .modelKey('sk-0123456789')
                   .launchedBy(host.launcher).start()
        assert lamp.awaitRunning(Duration.ofSeconds(30))
    }

    /** The host-access socket of the running lamp, under the short runtime directory. */
    private Path hostAccessSocket() {
        Files.list(host.runtime.resolve('oillamp')).withCloseable { lamps ->
            lamps.map { it.resolve('sockets/host/host-access.sock') }.filter { Files.exists(it) }.findFirst().orElseThrow() }
    }

    private <T extends LampEvent> T waitFor(Class<T> type, Closure<Boolean> which) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < until) {
            var found = events.find { type.isInstance(it) && which(it) }
            if (found != null) return (T) found
            Thread.sleep(20)
        }
        throw new AssertionError("no ${type.simpleName} within 30 seconds; heard ${events.collect { it.toString().take(200) }}")
    }
}
