package oillamp

import dev.lamp.LampEvent
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Title('Deciding what the sandbox may reach')
@Narrative('''
The sandbox has no network of its own: podman starts it with `--network=none`. Everything the agent
reaches on the network, it reaches through the *egress proxy*, which oillamp runs on the host. Inside
the sandbox the proxy is `127.0.0.1:3128`, set as `HTTP_PROXY` and `HTTPS_PROXY`; on the host it is a
Unix socket in the lamp. For each connection the proxy asks the lamp's *network policy*, the
`[network]` part of `oillamp.toml`: a list of rules, of which the first that matches decides, and a
default for a connection that no rule matches. The policy that oillamp writes into a new lamp allows
the internet, and refuses the private, internal and loopback address ranges.

These scenarios talk to the proxy as the sandbox does: over the real proxy socket, in real HTTP,
during a real session. On the other side is a real server on loopback, so an allowed connection
really carries bytes, and a refused one really does not. Unless a scenario writes its own rules,
the policy is the one oillamp ships, because what the shipped policy lets an agent touch is the
promise worth checking.
''')
@Subject(OilLamp)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class Deciding_What_The_Sandbox_May_Reach_Spec extends Specification {

    static final String PRIVATE_RANGES = 'block private, internal and loopback ranges'

    @TempDir Path tmp
    ScenarioHost host

    final List<LampEvent> reported = new CopyOnWriteArrayList<>()
    final List<String> requestsSeen = new CopyOnWriteArrayList<>()
    final List<String> headersSeen = new CopyOnWriteArrayList<>()
    Thread session
    volatile OilLamp.Outcome finished
    ServerSocket origin

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
    }

    def cleanup() {
        origin?.close()
        if (session?.alive) {
            host.oillamp.run('stop', host.lampPath().toString())
            session.join(20_000)
        }
    }

    def 'The egress proxy sends an allowed request on to the server, and the server\'s answer back'() {
        reportInfo """
            This is the way through the proxy for a request the policy allows. The proxy parses the
            request, resolves the host name, decides, connects to the server, and sends the request
            on in origin form: with the path alone, `GET /packages`, as a web server expects it,
            instead of the full URL that a client sends to a proxy. Then it copies the server's
            answer back to the sandbox.

            The test server listens on loopback, which the shipped policy refuses on purpose. So
            the lamp here puts one allow rule for the server's port above the rule that refuses
            loopback, as a user does to reach an internal service.
        """
        given: 'a test server, and a lamp whose policy allows its port'
            var port = givenAnOriginServer()
            givenALampAllowing(port)
            startASession()

        when: 'the sandbox asks the proxy for the server, as `curl` with `HTTP_PROXY` set would'
            var answer = askTheProxy("GET http://localhost:${port}/packages HTTP/1.1\r\n\r\n")

        then: 'the proxy allows the request, and the server\'s answer comes back'
            answer.status == 200
            answer.body.contains('hello from the internet')

        and: 'the server received the request in origin form'
            requestsSeen.any { it.startsWith('GET /packages ') }
    }

    def 'The egress proxy refuses a name that resolves to loopback, because it judges the address and not the name'() {
        reportInfo """
            The shipped policy allows the internet, but refuses private and loopback addresses. To
            do that, the proxy decides by the address that a host name resolves to, not by the
            name.

            A name is a claim its owner controls. The owner of `totally-normal.example.com` can
            point it at 127.0.0.1 at any time, and a proxy that trusted names would let the agent
            connect to whatever the user runs on their own machine. Judging the address catches
            that, whatever the name says. Here the name is `localhost`, which resolves to the
            loopback address and is refused.
        """
        given: 'a test server on loopback, and a lamp with the shipped policy'
            var port = givenAnOriginServer()
            startASession()

        when: 'the sandbox asks the proxy for `localhost`'
            var answer = askTheProxy("GET http://localhost:${port}/secrets HTTP/1.1\r\n\r\n")

        then: 'the proxy refuses with 403'
            answer.status == 403

        and: 'its answer names the rule that refused it, so that the agent can report why instead of guessing'
            answer.body.contains('denied by rule')
            answer.body.contains('block private, internal and loopback ranges')

        and: 'nothing reached the server'
            requestsSeen.isEmpty()
    }

    def 'oillamp prints each connection that the egress proxy refuses in the terminal it was started from'() {
        reportInfo """
            With `network.console_denied` on, the default, oillamp prints every connection that
            the proxy refuses in the terminal that `oillamp at` was started from. The agent only
            sees its build fail. The user needs to see which rule refused the connection, so that
            they know what to change.
        """
        given: 'a test server on loopback, and a lamp with the shipped policy'
            var port = givenAnOriginServer()
            startASession()

        when: 'the sandbox asks the proxy for an address on loopback'
            askTheProxy("GET http://127.0.0.1:${port}/x HTTP/1.1\r\n\r\n")

        then: 'the terminal shows the refusal, with the rule that refused it'
            waitUntil { reported.any { it instanceof LampEvent.Info &&
                                       it.text().contains('denied') } }
            reported.find { it instanceof LampEvent.Info && it.text().contains('denied') }
                    .text().contains('block private, internal and loopback ranges')
    }

    def 'The outcome of `oillamp at` holds every refused connection, even when 300 arrive at once'() {
        reportInfo """
            Everything oillamp reports is an event. oillamp prints it, and also keeps it in the
            list of events that is the outcome of the command, here `oillamp at`. During a session
            many threads report at once: each connection through the proxy, each shell, the health
            check and the control socket have their own. An agent that retries a refused address
            in parallel causes many refusals in the same instant. A list of events that several
            threads could not add to at once would lose some of them, or fail inside the thread
            that was reporting.

            The scenario sends 300 refused requests at once, stops the session, and counts 300
            refusals in the outcome.
        """
        given: 'a test server on loopback, and a lamp with the shipped policy'
            var port = givenAnOriginServer()
            startASession()

        when: '300 refused requests arrive at the same moment'
            var requests = (1..300).collect { i ->
                Thread.startVirtualThread { askPersistently("GET http://127.0.0.1:${port}/${i} HTTP/1.1\r\n\r\n") }
            }
            requests*.join()
            waitUntil { reported.count { isDenial(it) } == 300 }

        and: 'the session is stopped'
            host.oillamp.run('stop', host.lampPath().toString())
            session.join(30_000)

        then: 'the outcome of `oillamp at` holds all 300 refusals'
            finished != null
            finished.events().count { isDenial(it) } == 300
    }

    /** Asks again while the proxy's queue of waiting connections is full, as a patient client would. */
    private void askPersistently(String request) {
        for (int attempt = 0; ; attempt++) {
            try {
                askTheProxy(request)
                return
            } catch (IOException full) {
                if (attempt >= 100) throw full
                Thread.sleep(20)
            }
        }
    }

    private static boolean isDenial(LampEvent event) {
        event instanceof LampEvent.Info && event.text().startsWith('denied ')
    }

    def 'The egress proxy answers each of 300 connections that arrive at the same moment'() {
        reportInfo """
            A build opens many connections at once, as a package manager does when it fetches
            dependencies in parallel, or a browser when it loads a page. They all arrive at the
            proxy's socket in the same instant. The kernel holds new connections in a queue until
            the proxy accepts them, and refuses any connection that finds the queue full. The
            agent would see a network error that no rule explains. The kernel's default queue
            holds 50 connections, so oillamp makes the proxy's queue as long as its limit of open
            connections, 512.

            The scenario sends 300 requests at once, none of which tries twice, and expects every
            one to get the proxy's answer: 403, because loopback is refused.
        """
        given: 'a test server on loopback, and a lamp with the shipped policy'
            var port = givenAnOriginServer()
            startASession()

        when: '300 requests arrive at the same moment, and none of them tries twice'
            var answers = new CopyOnWriteArrayList<Integer>()
            var failures = new CopyOnWriteArrayList<String>()
            var requests = (1..300).collect { i ->
                Thread.startVirtualThread {
                    try {
                        answers << askTheProxy("GET http://127.0.0.1:${port}/${i} HTTP/1.1\r\n\r\n").status
                    } catch (IOException refused) {
                        failures << refused.message
                    }
                }
            }
            requests*.join()

        then: 'every one of them got the proxy\'s answer, 403'
            failures.isEmpty()
            answers.size() == 300
            answers.every { it == 403 }
    }

    def 'The egress proxy writes every connection to the session\'s network log'() {
        reportInfo """
            The proxy writes every connection to `.oillamp/logs/network-<session>.jsonl`, one JSON
            object per line, with the host, the port, the address it resolved to, the decision, the
            rule that decided, and how many bytes went each way. It never writes content: the proxy
            copies an encrypted connection without reading it. Afterwards, the log answers the
            question "what did this agent talk to?"
        """
        given: 'a test server, and a lamp whose policy allows its port'
            var port = givenAnOriginServer()
            givenALampAllowing(port)
            startASession()

        when: 'the sandbox asks the proxy for `localhost` on that port'
            askTheProxy("GET http://localhost:${port}/p HTTP/1.1\r\n\r\n")

        then: 'every line of the network log is one JSON object'
            waitUntil { Files.exists(networkLog()) && !Files.readAllLines(networkLog()).isEmpty() }
            var log = Files.readAllLines(networkLog())
            log.every { it.startsWith('{') && it.endsWith('}') }

        and: 'a line records the host, the address it resolved to, the decision to allow it, and the rule that allowed it'
            log.any { it.contains('"host":"localhost"') && it.contains('"decision":"allow"') &&
                      it.contains('"address":"127.0.0.1"') && it.contains('the test origin server') }
    }

    def 'With `network.log_allowed = false`, the egress proxy writes only refused connections to the network log'() {
        reportInfo """
            The proxy writes every connection the sandbox makes to `.oillamp/logs/network-<session>.jsonl`,
            one JSON line each, with `"decision":"allow"` or `"decision":"deny"`. Over a long session that
            is many lines, and together they list every site the agent visited. Let's consider a user who
            only wants the refused connections in that file, because those are the ones they act on. They
            set `log_allowed = false` under `[network]`.

            That setting leaves out the allowed connections and nothing else. A refused connection is
            still written, so the user still sees which rule stopped what.

            In the scenario the lamp allows the test server's port on loopback. One request to that port is
            allowed; then a request to the next port is refused by the deny rule. Once the refusal is in the
            log, the scenario checks that the log has no line with `"decision":"allow"`. The log is written
            in the order the connections end, so an allowed line, had it been written, would already be
            there.
        """
        given: 'a lamp that allows the test server, and logs only refusals'
            var port = givenAnOriginServer()
            givenALampAllowing(port)
            var file = host.lampPath().resolve('oillamp.toml')
            Files.writeString(file, Files.readString(file).replace('schema_version = 1\n',
                    'schema_version = 1\n\n[network]\nlog_allowed = false\n'))
            startASession()

        when: 'one request is allowed, and then one is refused'
            assert askTheProxy("GET http://localhost:${port}/allowed HTTP/1.1\r\n\r\n").status == 200
            assert askTheProxy("GET http://127.0.0.1:${port + 1}/refused HTTP/1.1\r\n\r\n").status == 403

        then: 'the refusal is in the log'
            waitUntil { Files.exists(networkLog()) && Files.readString(networkLog()).contains('"decision":"deny"') }

        and: 'the allowed request is not, although it was made first'
            !Files.readString(networkLog()).contains('"decision":"allow"')
    }

    def 'The egress proxy removes the `Proxy-` headers from a request before it sends the request to the website'() {
        reportInfo """
            In the rare case that a client in the sandbox is configured with a user name and password for
            its proxy, for example with `HTTP_PROXY=http://user:secret@127.0.0.1:3128`, it sends
            `Proxy-Authorization: Basic <user:secret in base64>` with every request. That header is for
            the proxy. The website at the other end has no use for it and must not receive the password.

            So when the proxy sends a plain HTTP request on to the website, it removes every header whose
            name starts with `Proxy-`. All other headers go to the website unchanged.

            In the scenario the request carries `Proxy-Authorization`, `Proxy-Connection` and `Accept`.
            The test server records the headers it received: `Accept` must be among them, and no header
            whose name starts with `Proxy-`.
        """
        given: 'a lamp that allows the test server'
            var port = givenAnOriginServer()
            givenALampAllowing(port)
            startASession()

        when: 'a request arrives carrying headers for the proxy and one for the website'
            var answer = askTheProxy("GET http://localhost:${port}/ HTTP/1.1\r\n" +
                    "Proxy-Authorization: Basic c2VjcmV0OnNlY3JldA==\r\n" +
                    "Proxy-Connection: keep-alive\r\n" +
                    "Accept: text/plain\r\n\r\n")

        then: 'the website answered'
            answer.status == 200

        and: 'it saw its own header, and none of the proxy\'s'
            headersSeen.contains('Accept: text/plain')
            !headersSeen.any { it.toLowerCase().startsWith('proxy-') }
    }

    def 'The egress proxy tells a client that mistakes it for a web server what it is'() {
        reportInfo """
            A request in origin form, with only a path such as `GET /index.html` instead of a full
            URL, comes from a client that has the proxy's address but does not know that it is a
            proxy. A bare 400 would send whoever wrote the client hunting for a bug in their URL.
            So the answer says that this is an egress proxy, and that the client should use it
            through `HTTP_PROXY` instead.
        """
        given: 'a lamp with the shipped policy'
            startASession()

        when: 'a request with only a path arrives at the proxy'
            var answer = askTheProxy("GET /index.html HTTP/1.1\r\nHost: example.test\r\n\r\n")

        then: 'the proxy answers 400, says that it is an egress proxy, and names `HTTP_PROXY`'
            answer.status == 400
            answer.body.contains('egress proxy')
            answer.body.contains('HTTP_PROXY')
    }

    def 'The egress proxy answers a request it cannot act on with what is wrong with it: #why'() {
        reportInfo """
            A client that sends something the proxy cannot act on gets a 400 whose body says what
            was wrong, rather than a closed connection or a bare status. The agent reads that
            sentence. A silent failure would leave it guessing whether the network is down.
        """
        given: 'a lamp with the shipped policy'
            startASession()

        when: 'the request arrives at the proxy'
            var answer = askTheProxy(request)

        then: 'the proxy answers 400, and says what is wrong with the request'
            answer.status == 400
            answer.body.contains(explanation)

        where:
            why                             | request                                                   | explanation
            'CONNECT without a port'        | 'CONNECT example.test HTTP/1.1\r\n\r\n'                  | 'CONNECT needs host:port'
            'CONNECT to IPv6 without a port'| 'CONNECT [::1] HTTP/1.1\r\n\r\n'                         | 'CONNECT needs host:port'
            'a port that is not a number'   | 'CONNECT example.test:https HTTP/1.1\r\n\r\n'            | 'not a number'
            'a port out of range'           | 'GET http://example.test:70000/ HTTP/1.1\r\n\r\n'        | 'not a number'
            'an https URL without CONNECT'  | 'GET https://example.test/ HTTP/1.1\r\n\r\n'             | 'must be sent as CONNECT'
            'a request line with no version'| 'GET http://example.test/\r\n\r\n'                       | 'malformed request line'
    }

    def 'The agent cannot write into the user\'s terminal or break the network log through a request: #part'() {
        reportInfo """
            oillamp prints a refused connection in the terminal it was started from, and writes
            every request to the network log. Both show the request's method and host. A terminal
            treats escape sequences as commands, so a method made of escape sequences, sent to a
            refused address, could clear the user's screen, rewrite what they had read, or, on
            some terminals, set their clipboard. A quote or a line break in a method or host would
            turn its line in the network log into something that no JSON reader accepts.

            No real method or host name contains such characters, so the proxy refuses them like
            any other malformed request. The scenario then asks
            for an ordinary refused address, and checks that every line of the log up to it is
            JSON.
        """
        given: 'a test server, and a lamp with the shipped policy'
            var port = givenAnOriginServer()
            startASession()

        when: 'the request arrives at the proxy'
            var answer = askTheProxy(request.replace('PORT', port.toString()))

        then: 'the proxy answers 400, and says what is not valid'
            answer.status == 400
            answer.body.contains(explanation)

        and: 'nothing reached the server'
            requestsSeen.isEmpty()

        and: 'nothing oillamp reported holds a control character'
            reported.every { !(it.toString() =~ /[\u0000-\u0008\u000B-\u001F\u007F]/) }

        and: 'every line of the network log is JSON, up to an ordinary refusal asked for afterwards'
            askTheProxy("GET http://localhost:${port}/marker HTTP/1.1\r\n\r\n")
            waitUntil { Files.exists(networkLog()) && Files.readString(networkLog()).contains('"host":"localhost"') }
            Files.readAllLines(networkLog()).every { new groovy.json.JsonSlurper().parseText(it) != null }

        where:
            part                    | request                                                             | explanation
            'an escape in the method' | '\u001B]52;c;cm0gLXJmIH4=\u0007\u001B[2J http://127.0.0.1:PORT/ HTTP/1.1\r\n\r\n' | 'not a valid method'
            'a quote in the method'   | 'GET"} http://127.0.0.1:PORT/ HTTP/1.1\r\n\r\n'                     | 'not a valid method'
            'an escape in the host'   | 'GET http://127.0.0.1\u001B[2J:PORT/ HTTP/1.1\r\n\r\n'              | 'not a valid host'
            'an escape in a CONNECT'  | 'CONNECT \u001B[2J127.0.0.1:PORT HTTP/1.1\r\n\r\n'                  | 'not a valid host'
            'a bare carriage return'  | 'GET http://ex\rample.test:PORT/ HTTP/1.1\r\n\r\n'                  | 'not a valid host'
    }

    def 'The egress proxy reads an IPv6 address in a plain HTTP URL, with or without a port: #url'() {
        reportInfo """
            An IPv6 address in a URL is written in brackets, as in `http://[2001:db8::1]/`, because
            the address itself is full of colons. The proxy reads the address between the brackets,
            and the port after them, or 80 if there is none. The colons inside the brackets are not
            taken for the one before a port. The proxy then judges the request by the policy like
            any other.

            All the addresses here are loopback, so the policy refuses each of them, which shows
            that the proxy read the address: a request it could not read would get a 400. The
            refusal names the port it read.
        """
        given: 'a test server listening on every address of this machine, and a lamp with the shipped policy'
            var port = givenAnOriginServer('hello from the internet', '::')
            startASession()

        when: 'the sandbox asks the proxy for the URL'
            var answer = askTheProxy("GET ${url.replace('PORT', port.toString())} HTTP/1.1\r\n\r\n")

        then: 'the policy refuses it, naming the rule and the port, rather than the proxy failing to read it'
            answer.status == 403
            answer.body.contains(PRIVATE_RANGES)
            answer.body.contains(":${url.contains('PORT') ? port : 80} ")

        where:
            url << ['http://[::1]/', 'http://[::1]', 'http://[::1]:PORT/x', 'http://[::ffff:127.0.0.1]/']
    }

    def 'The egress proxy reports a name that does not resolve as unresolved, not as refused'() {
        reportInfo """
            The proxy resolves host names on the host. For a name that does not exist, it answers
            502 and says that the name could not be resolved. It writes the connection to the
            network log with the rule `unresolved`, but oillamp does not print it in the terminal
            as a refusal: no rule refused it, and a refusal would send the user looking for a rule
            to change.
        """
        given: 'a lamp with the shipped policy'
            startASession()

        when: 'the sandbox asks for a tunnel to a name in the `.invalid` domain, which is reserved never to resolve'
            var answer = tunnelThrough(443, '', 'no-such-host.invalid')

        then: 'the proxy answers 502, and says that it cannot resolve the name'
            answer.contains('502')
            answer.contains('cannot resolve no-such-host.invalid')

        and: 'the network log has the connection, with the rule `unresolved`'
            waitUntil { Files.exists(networkLog()) && Files.readString(networkLog()).contains('no-such-host.invalid') }
            Files.readString(networkLog()).contains('"rule":"unresolved"')

        and: 'the terminal shows no refusal'
            !reported.any { isDenial(it) }
    }

    def 'A rule with `ports` applies only to the ports it names'() {
        reportInfo """
            A connection matches a rule only if it matches everything the rule lists. A rule that
            allows one service on one port says nothing about the other ports of the same machine,
            so a connection to another port is judged by the rules below it, here the one that
            refuses loopback.
        """
        given: 'a lamp that allows the test server\'s address on the server\'s port only'
            var port = givenAnOriginServer()
            givenALampAllowing(port)
            var otherPort = new ServerSocket(0).withCloseable { it.localPort }
            startASession()

        when: 'the sandbox asks for the server\'s port, and for another port on the same address'
            var allowed = askTheProxy("GET http://127.0.0.1:${port}/ HTTP/1.1\r\n\r\n")
            var elsewhere = askTheProxy("GET http://127.0.0.1:${otherPort}/ HTTP/1.1\r\n\r\n")

        then: 'the request to the server\'s port reaches it'
            allowed.status == 200

        and: 'the request to the other port is refused by the rule that refuses loopback'
            elsewhere.status == 403
            elsewhere.body.contains(PRIVATE_RANGES)
    }

    def 'With `network.default = "deny"`, only what a rule allows gets through'() {
        reportInfo """
            `default = "deny"` under `[network]` turns the policy into an allow list: the proxy
            refuses every connection that no rule matches. The refusal names `(default)` instead of
            a rule, so that the user knows to add a rule rather than change one.
        """
        given: 'a lamp whose policy refuses by default, and has no rules'
            host.givenConfig(host.lampPath(), """
                schema_version = 1

                [network]
                default = "deny"
                rules   = []
                """.stripIndent())
            startASession()

        when: 'the sandbox asks for a public address, written as a number so that no name has to resolve'
            var answer = tunnelThrough(443, '', '192.0.2.10')

        then: 'the proxy refuses it, naming `(default)` as the rule'
            answer.contains('403')
            answer.contains('denied by rule "(default)"')
    }

    def 'A forward connects the sandbox to its one fixed address, and the network log records each connection'() {
        reportInfo """
            A forward is a fixed tunnel from a port inside the sandbox to one address, written in
            `[[network.forwards]]`. It gives the sandbox one internal service, such as a company's
            language model, without opening the rest of the network. A forward does not go through
            the policy, because its target is fixed in `oillamp.toml`, which the agent cannot
            change. The host makes the connection on the sandbox's behalf, so the host's VPN
            applies, and the network log records the connection like any other.

            The forward here leads to the test server on the host's loopback, which the policy
            would refuse.
        """
        given: 'a lamp with a forward "llm" to the test server'
            var port = givenAnOriginServer('hello from the company model')
            host.givenConfig(host.lampPath(), """
                schema_version = 1

                [[network.forwards]]
                name   = "llm"
                port   = 8000
                target = "127.0.0.1:${port}"
                """.stripIndent())
            startASession()

        when: 'something in the sandbox connects to the forward'
            var answer = throughTheForward('llm', "GET /v1/models HTTP/1.1\r\nHost: llm\r\n\r\n")

        then: 'it reaches the test server, although the server is on the host\'s loopback'
            answer.contains('hello from the company model')
            requestsSeen.any { it.startsWith('GET /v1/models ') }

        and: 'the network log records the connection as the forward\'s'
            waitUntil { Files.exists(networkLog()) && Files.readString(networkLog()).contains('forward:llm') }
    }

    /**
     *  A lamp whose policy allows the scenario's own server.
     *
     *  <p>Allow rules go <em>above</em> the deny rule, because the first match wins. The deny rule
     *  is written out again because a lamp's list of rules replaces the default list instead of
     *  being added to it; listing only the allow rule would drop the deny rule.
     */
    private void givenALampAllowing(int port, String ports = port.toString()) {
        host.givenConfig(host.lampPath(), """
            schema_version = 1

            [[network.rules]]
            label  = "the test origin server"
            action = "allow"
            cidrs  = ["127.0.0.0/8"]
            ports  = [${ports}]

            [[network.rules]]
            label  = "block private, internal and loopback ranges"
            action = "deny"
            cidrs  = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
                      "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8",
                      "::1/128", "fc00::/7", "fe80::/10"]
            """.stripIndent())
    }

    /** A lamp that allows the host names `pattern` matches, on any port, above the shipped deny rule. */
    private void givenALampAllowingHosts(String pattern) {
        host.givenConfig(host.lampPath(), """
            schema_version = 1

            [[network.rules]]
            label  = "the hosts the scenario names"
            action = "allow"
            hosts  = ["${pattern}"]

            [[network.rules]]
            label  = "block private, internal and loopback ranges"
            action = "deny"
            cidrs  = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
                      "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8",
                      "::1/128", "fc00::/7", "fe80::/10"]
            """.stripIndent())
    }

    /** A lamp whose only rule allows every address, as a hand-written policy might. */
    private void givenALampAllowingEverything() {
        host.givenConfig(host.lampPath(), """
            schema_version = 1

            [[network.rules]]
            label  = "anything goes"
            action = "allow"
            """.stripIndent())
    }

    def 'Through a CONNECT tunnel, the egress proxy copies bytes both ways without reading them'() {
        reportInfo """
            A client asks a proxy for an encrypted connection with a CONNECT request, which names a
            host and a port. Once the proxy answers 200, the same connection is a tunnel to that
            host. Most real traffic uses CONNECT, because it is HTTPS: npm, pip, cargo, git over
            HTTPS, and every API an agent calls. The policy judges the host and port in the CONNECT
            line, which is all a proxy can see of an encrypted connection without breaking into
            it. After the 200, the proxy copies bytes in both directions without reading them.

            The scenario sends a plain HTTP request through the tunnel and gets the answer back,
            which shows that the bytes are copied.
        """
        given: 'a test server, and a lamp whose policy allows its port'
            var port = givenAnOriginServer()
            givenALampAllowing(port)
            startASession()

        when: 'the sandbox asks for a tunnel to the server, and sends a request through it'
            var answer = tunnelThrough(port, "GET /through-the-tunnel HTTP/1.1\r\nHost: localhost\r\n\r\n")

        then: 'the proxy opens the tunnel, and the server\'s answer comes back through it'
            answer.contains('200 Connection Established')
            answer.contains('hello from the internet')

        and: 'the server received the request, which the proxy never read'
            requestsSeen.any { it.startsWith('GET /through-the-tunnel ') }
    }

    def 'The egress proxy refuses a CONNECT that its policy denies before it opens any tunnel'() {
        reportInfo """
            A CONNECT request asks the proxy to open a tunnel to a host and port. The refusal has
            to come first. A proxy that answered 200 and then closed the connection would leave the
            agent looking at an encrypted handshake that failed for no stated reason. Refusing with
            403 and the rule's label makes the failure a sentence the agent can report.
        """
        given: 'a test server on loopback, and a lamp with the shipped policy'
            var port = givenAnOriginServer()
            startASession()

        when: 'the sandbox asks for a tunnel to the server'
            var answer = tunnelThrough(port, "GET / HTTP/1.1\r\n\r\n")

        then: 'the proxy refuses with 403 and the rule\'s label, and never answers 200'
            answer.contains('403')
            answer.contains('block private, internal and loopback ranges')
            !answer.contains('200 Connection Established')

        and: 'nothing went through to the server'
            requestsSeen.isEmpty()
    }

    def 'The egress proxy refuses loopback and private addresses however they are written: #target'() {
        reportInfo """
            An address can be written in many ways. 127.0.0.1 is also `::ffff:7f00:1`, the same
            address in IPv6 notation, or `2130706433`, the same number in decimal, and `::` means
            "this machine" to the operating system. A policy that compared only one spelling would
            let the others through to whatever the user runs on their own machine, which is what
            the sandbox exists to prevent.

            So the proxy turns every spelling into one form before the policy judges it, and then
            connects to exactly the address that the policy approved.
        """
        given: 'a test server listening on every address of this machine, as sshd does'
            var port = givenAnOriginServer('hello from the internet', '::')
            startASession()

        when: 'the sandbox asks for a tunnel to the address, written this way'
            var answer = tunnelThrough(port, "GET / HTTP/1.1\r\n\r\n", target)

        then: 'the proxy refuses with 403, and says why'
            answer.contains('403')
            answer.contains(reason)
            !answer.contains('200 Connection Established')

        and: 'nothing reached the server'
            requestsSeen.isEmpty()

        where:
            target                     | reason
            '127.0.0.1'                | PRIVATE_RANGES
            '[::1]'                    | PRIVATE_RANGES
            '[::ffff:7f00:1]'          | PRIVATE_RANGES
            '[::ffff:127.0.0.1]'       | PRIVATE_RANGES
            '[0:0:0:0:0:ffff:7f00:1]'  | PRIVATE_RANGES
            '127.1'                    | PRIVATE_RANGES
            '2130706433'               | PRIVATE_RANGES
            'localhost.'               | PRIVATE_RANGES
            '[::ffff:192.168.1.1]'     | PRIVATE_RANGES
            '[::ffff:a00:1]'           | PRIVATE_RANGES
            '[::]'                     | 'the unspecified address, which means this machine'
            '0.0.0.0'                  | 'the unspecified address, which means this machine'
    }

    def 'Under the shipped policy, the egress proxy refuses connections into every private address range: #network'() {
        reportInfo """
            oillamp's shipped policy allows the internet and refuses the networks the host itself is part
            of. Let's consider oillamp running on a machine at home or in an office. Besides the internet,
            that machine can reach:

            - its local network, usually in 192.168.0.0/16, 10.0.0.0/8 or 172.16.0.0/12, with the router,
              printers and file servers on it;
            - a VPN or Tailscale, which use 10.0.0.0/8 or 100.64.0.0/10;
            - link-local addresses, 169.254.0.0/16 and fe80::/10. On a cloud machine, 169.254.169.254 is
              the metadata service, which gives the machine's credentials to any process that asks;
            - private IPv6 networks, fc00::/7.

            The shipped policy refuses all of them with one deny rule, "block private, internal and
            loopback ranges", which lists each range. If a range were missing from that list, nothing else
            would refuse it, and the agent could connect into that network. So the scenario asks the proxy
            for a CONNECT to one address in each range, and expects a 403 that names the deny rule.

            For 172.16.0.0/12 and fc00::/7 the prefix length is not a multiple of 8. A wrong bit mask for
            such a prefix makes the range smaller, and the addresses in its upper part fall out of it. So
            for these two the scenario uses 172.31.255.254, the last usable address of the range, and
            fd12:3456::1, which is in its upper half.

            The addresses are written as numbers, so no name has to be resolved. Nothing listens on them:
            the proxy decides before it connects, and a refused request never makes a connection.
        """
        given: 'a lamp with the shipped policy'
            startASession()

        when: 'the sandbox asks for a tunnel to an address in the network'
            var answer = tunnelThrough(443, '', address)

        then: 'the proxy refuses with 403, naming the rule that keeps private networks out'
            answer.contains('403')
            answer.contains(PRIVATE_RANGES)
            !answer.contains('200 Connection Established')

        where:
            network                                      | address
            'a home or office network (10/8)'            | '10.20.30.40'
            'a corporate network, near its end (172.16/12)' | '172.31.255.254'
            'a home router (192.168/16)'                 | '192.168.1.1'
            'carrier-grade NAT and Tailscale (100.64/10)' | '100.100.100.100'
            'the cloud metadata service (169.254/16)'    | '169.254.169.254'
            'an IPv6 private network (fc00::/7)'         | '[fd12:3456::1]'
            'an IPv6 link-local address (fe80::/10)'     | '[fe80::1]'
    }

    def 'A `hosts` pattern `*.name` allows only names that end in `.name`: #pattern'() {
        reportInfo """
            A rule's `hosts` list can hold an exact name such as `docs.example.com`, a pattern such as
            `*.example.com`, or `*` for every name. The pattern `*.example.com` allows every name that ends
            in `.example.com`, such as `docs.example.com` and `a.b.example.com`. It does not allow
            `example.com` itself. And it does not allow `notexample.com`, a different domain that anyone
            can register, which only happens to end in the same letters.

            The dot makes that difference. oillamp compares the end of the requested name with
            `.example.com`, dot included. Compared with `example.com` alone, the pattern would also allow
            `notexample.com`.

            The scenario asks for `localhost`, because it resolves on every machine, once for each
            pattern. The lamp has the allow rule first, then the shipped deny rule, which refuses loopback
            addresses. A pattern that allows `localhost` lets the request reach the test server (200); a
            pattern that does not leaves it to the deny rule (403):

            - `localhost` and `*` allow it;
            - `*.localhost` does not, because `localhost` is the domain itself, not a name under it;
            - `*.host` does not, because `localhost` ends in `host`, but not in `.host`.
        """
        given: 'a test server, and a lamp that allows one host pattern, above the shipped rule that refuses loopback'
            var port = givenAnOriginServer()
            givenALampAllowingHosts(pattern)
            startASession()

        when: 'the sandbox asks the proxy for `localhost`'
            var answer = askTheProxy("GET http://localhost:${port}/ HTTP/1.1\r\n\r\n")

        then: 'a pattern that allows `localhost` lets the request reach the server; any other leaves it to the refusing rule'
            answer.status == status
            (status == 200) == !requestsSeen.isEmpty()

        where:
            pattern       || status
            'localhost'   || 200
            '*'           || 200
            '*.localhost' || 403
            '*.host'      || 403
    }

    def 'A `ports` range such as "8000-8100" allows both of its end ports: #range'() {
        reportInfo """
            A rule's `ports` list can hold single ports, such as `443`, and ranges written as strings, such
            as `"8000-8100"`. A range includes both ends: `"8000-8100"` allows 8000, 8100 and every port in
            between.

            Let's consider a user who allows `"8000-8100"` for an internal service that listens on 8100. If
            the last port of a range were left out, their request to 8100 would be refused by a rule that,
            as they wrote it, allows it.

            The scenario allows a range of eleven ports with the test server's port first (`PORT-ABOVE`,
            where ABOVE is the port plus 10), and then a range with the server's port last (`BELOW-PORT`).
            Both times the request must reach the server and get 200.
        """
        given: 'a test server, and a lamp that allows a range of ports with the server\'s port at one end'
            var port = givenAnOriginServer()
            givenALampAllowing(port, '"' + range.replace('PORT', port.toString())
                                                 .replace('BELOW', (port - 10).toString())
                                                 .replace('ABOVE', (port + 10).toString()) + '"')
            startASession()

        when: 'the sandbox asks the proxy for the server'
            var answer = askTheProxy("GET http://127.0.0.1:${port}/ HTTP/1.1\r\n\r\n")

        then: 'the request reaches the server'
            answer.status == 200

        where:
            range << ['PORT-ABOVE', 'BELOW-PORT']
    }

    def 'The egress proxy refuses the unspecified address even when the lamp\'s rules allow everything'() {
        reportInfo """
            The unspecified address, `::` or `0.0.0.0`, means "this machine" to Linux, and is never
            a real destination. The shipped policy refuses it, but every lamp keeps its own copy of
            the rules in `oillamp.toml`, written when the lamp was created, and a rule added to the
            shipped policy later never reaches those copies. So the proxy refuses the unspecified
            address whatever the lamp's rules say, and says that no rule can allow it.

            The lamp here has a single rule, which allows every address.
        """
        given: 'a test server on every address of this machine, and a lamp whose only rule allows everything'
            var port = givenAnOriginServer('hello from the internet', '::')
            givenALampAllowingEverything()
            startASession()

        when: 'the sandbox asks for a tunnel to `[::]`'
            var answer = tunnelThrough(port, "GET / HTTP/1.1\r\n\r\n", '[::]')

        then: 'the proxy refuses with 403, says that no rule can allow it, and nothing reached the server'
            answer.contains('403')
            answer.contains('no rule can allow it')
            requestsSeen.isEmpty()
    }

    // ─── the world on the other side of the proxy ──────────────────────────────────────────

    /** A real HTTP server on loopback, standing in for somewhere out on the web. */
    private int givenAnOriginServer(String body = 'hello from the internet', String bindTo = '127.0.0.1') {
        origin = new ServerSocket(0, 8, InetAddress.getByName(bindTo))
        Thread.startVirtualThread {
            while (!origin.isClosed()) {
                try {
                    var client = origin.accept()
                    var reader = new BufferedReader(new InputStreamReader(client.inputStream))
                    var line = reader.readLine()
                    if (line != null) requestsSeen.add(line)
                    for (var header = reader.readLine(); header; header = reader.readLine())
                        headersSeen.add(header)
                    client.outputStream.write(("HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${body.length()}\r\nConnection: close\r\n\r\n" +
                            body).getBytes(StandardCharsets.UTF_8))
                    client.outputStream.flush()
                    client.close()
                } catch (IOException ignored) {
                    return      // the server was closed by cleanup; nothing to report
                }
            }
        }
        origin.localPort
    }

    // ─── talking to the proxy the way the sandbox does ─────────────────────────────────────

    private record Answer(int status, String body) {}

    /**
     *  Sends one request over the real proxy socket and reads the whole reply.
     *
     *  <p>This is the same socket the socat bridge inside the container connects to, reached the
     *  same way. Nothing about the proxy is stubbed: if this passes, an agent typing `curl` gets
     *  the same answer.
     */
    private Answer askTheProxy(String request) {
        var address = UnixDomainSocketAddress.of(proxySocket())
        try (var channel = SocketChannel.open(address)) {
            channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.ISO_8859_1)))
            var received = new ByteArrayOutputStream()
            var buffer = ByteBuffer.allocate(8192)
            while (channel.read(buffer) >= 0) {
                buffer.flip()
                var bytes = new byte[buffer.remaining()]
                buffer.get(bytes)
                received.write(bytes)
                buffer.clear()
            }
            var text = received.toString(StandardCharsets.UTF_8)
            var split = text.indexOf('\r\n\r\n')
            var head = split < 0 ? text : text.substring(0, split)
            var body = split < 0 ? '' : text.substring(split + 4)
            var status = head.isBlank() ? 0 : Integer.parseInt(head.split(' ')[1])
            new Answer(status, body)
        }
    }

    /**
     *  Asks for a CONNECT tunnel and, if it is granted, speaks through it.
     *
     *  <p>Written as one exchange on one channel because that is what a tunnel is: the same
     *  connection, carrying something oillamp has agreed not to interpret.
     */
    private String tunnelThrough(int port, String throughTheTunnel, String host = 'localhost') {
        var address = UnixDomainSocketAddress.of(proxySocket())
        try (var channel = SocketChannel.open(address)) {
            write(channel, "CONNECT ${host}:${port} HTTP/1.1\r\nHost: ${host}:${port}\r\n\r\n")
            var opening = readSome(channel)
            // A refusal is a whole response and then a close, so it has to be read to the end:
            // a single read can return the head with the body still in flight, and the body is
            // the part naming the rule. Only the 200 case stops reading, because there the
            // channel stays open for the tunnel.
            if (!opening.contains('200')) return opening + readAll(channel)
            write(channel, throughTheTunnel)
            opening + readAll(channel)
        }
    }

    /** Speaks through a forward's socket, as the socat bridge inside the container does. */
    private String throughTheForward(String name, String request) {
        var socket = proxySocket().resolveSibling("fwd-${name}.sock")
        try (var channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            write(channel, request)
            readAll(channel)
        }
    }

    private static void write(SocketChannel channel, String text) {
        channel.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)))
    }

    /** Reads whatever has arrived so far: enough for a status line, without waiting for close. */
    private static String readSome(SocketChannel channel) {
        var buffer = ByteBuffer.allocate(4096)
        var read = channel.read(buffer)
        if (read <= 0) return ''
        buffer.flip()
        var bytes = new byte[buffer.remaining()]
        buffer.get(bytes)
        new String(bytes, StandardCharsets.UTF_8)
    }

    private static String readAll(SocketChannel channel) {
        var received = new ByteArrayOutputStream()
        var buffer = ByteBuffer.allocate(8192)
        while (channel.read(buffer) >= 0) {
            buffer.flip()
            var bytes = new byte[buffer.remaining()]
            buffer.get(bytes)
            received.write(bytes)
            buffer.clear()
        }
        received.toString(StandardCharsets.UTF_8)
    }

    /**
     *  The proxy socket, reached through the short runtime directory. The lamp's own path is
     *  often longer than the kernel's 107-byte limit for a Unix socket path, so the runtime
     *  directory holds a symlink into the lamp, and oillamp always connects through it.
     */
    private Path proxySocket() {
        var agents = Files.list(host.runtime.resolve('oillamp')).toList()
        assert agents.size() == 1 : "expected one agent runtime directory, found ${agents}"
        var socket = agents.first().resolve('sockets/host/proxy.sock')
        assert Files.exists(socket) : "the session never bound a proxy socket at ${socket}"
        socket
    }

    private Path networkLog() {
        Files.list(host.lampPath().resolve('.oillamp/logs'))
             .filter { it.fileName.toString().startsWith('network-') }
             .findFirst()
             .orElse(host.lampPath().resolve('.oillamp/logs/network-none.jsonl'))
    }

    // ─── running a session beside the scenario ─────────────────────────────────────────────

    private void startASession() {
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(60)) }
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        session = Thread.start { finished = oillamp.run('at', host.lampPath().toString()) }
        waitUntil { reported.any { it instanceof LampEvent.Summary &&
                                   it.title() == 'your session is up' } }
    }

    private void waitUntil(Closure<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw new AssertionError("the session never got there: ${condition}" as Object)
    }
}
