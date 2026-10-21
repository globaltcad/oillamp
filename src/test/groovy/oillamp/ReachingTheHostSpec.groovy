package oillamp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.lamp.Lamp
import dev.lamp.LampPrompt
import dev.lamp.LampEvent
import dev.lamp.LampFailedException
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Title('Reaching the host: every request judged before it happens')
@Narrative('''
An application lets its agent reach the user's own machine: read a document, write a file where
the user will find it, copy out what it made, open a page. The agent never gets a way out of the
sandbox of its own. It gets tools, and each call of one is a request that oillamp, on the host,
judges first and then carries out, or refuses.

## A request, on its way out

```
 sandbox: /home/agent              engine (host)                           the host
 ────────────────────              ─────────────                           ────────
 pi calls host_read_file
   { path: "~/Documents/q3.md" }
        │ one JSON line
        ▼
 the session's socket  ─────────▶  sockets/host/host-access.sock
                                   │
                                   ├ 1. read: a request, or nothing at all
                                   ├ 2. the tier: closed is closed
                                   ├ 3. the paths: ~ is the user's home,
                                   │    no "..", never inside the lamp,
                                   │    under host_access.roots
                                   ├ 4. what the tool rules out by itself
                                   │
                                   ├ 5. the checker ─────────────────────▶ the model service
                                   │    the requirements, the run, the      (its own
                                   │    request; never the agent's          conversation)
                                   │    messages                     ◀──── a verdict
                                   │
                                   ├ 6. the person, for a program or a
                                   │    path past the roots
                                   │
                                   ├ 7. the paths once more, then ────────▶ ~/Documents/q3.md
                                   │    the effect
                                   └ logs/host-access-<session>.jsonl
        ◀──────────────────────────  the result, or the refusal and why
```

Steps 1 to 4 need no model and cost nothing, so a refusal there never reaches the checker. The
checker is a separate model call with the user's requirements as its only authority. It is
made on the model the agent answers with, and follows it when the application changes it, unless
the lamp's file names a `checker_model` of its own. Which run a request belongs to is something
the engine knows, not something the request says, so the checker always reads the instruction of
the run the agent is really in.

The feature is off unless the lamp's `oillamp.toml` turns it on under `[host_access]`. An
application may give its own requirements with `LampDirectory.security`, but only for a lamp
whose file already turned it on.

## How the scenarios see it

The engine runs in this JVM against the simulated machine, connected to the lamp by real pipes.
A stand-in model service on loopback plays the checker: it records what it was shown, and
answers with the verdict the scenario sets. The scenarios speak to the session's host-access
socket exactly as the extension in the sandbox does, and give the person's decisions with
`Lamp.decidePermission`, as an application would. So what is checked is the real gate, from the
request's first byte to the file on the host.
''')
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ReachingTheHostSpec extends Specification {

    /** The model the agent answers with, and so the one that judges, unless the file names another. */
    static final String MODEL = 'azure/gpt-6.1-sol'
    static final String REQUIREMENTS = 'You may read ~/Documents. Never read ~/.ssh.'
    static final String ALLOW = '{"decision":"allow","risk":"passive","reason":"The user asked about it."}'

    @TempDir Path tmp
    @Subject ScenarioHost host
    HttpServer service
    /** The body of each request the checker model received. */
    final List<String> checked = new CopyOnWriteArrayList<>()
    final List<LampEvent> events = new CopyOnWriteArrayList<>()
    Lamp lamp
    /** What the stand-in checker answers. */
    volatile String verdict = ALLOW

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
        service = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        service.createContext('/') { HttpExchange exchange ->
            String body = new String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            checked << body
            String answer = '{"choices":[{"message":{"role":"assistant","content":' \
                    + groovy.json.JsonOutput.toJson((String) verdict) + '}}]}'
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.set('Content-Type', 'application/json')
            exchange.sendResponseHeaders(200, bytes.length)
            OutputStream out = exchange.responseBody
            out.write(bytes)
            out.close()
        }
        // One request per connection is answered on a thread of its own; a single-threaded
        // server would hold the connection the client is still writing on.
        service.executor = java.util.concurrent.Executors.newCachedThreadPool()
        service.start()
        Files.createDirectories(host.home.resolve('Documents'))
    }

    def cleanup() {
        lamp?.close()
        service?.stop(0)
        service?.executor?.shutdownNow()
        service = null
        checked.clear()
        events.clear()
    }

    def 'A lamp with host access on gives the agent the tools, and every request is judged first'() {
        reportInfo """
            The feature is off unless the lamp's file turns it on, and then the session serves
            the socket and the agent sees the tools — only those this lamp offers. A file read is
            the simplest request there is: the agent names a path the way a person would, from
            the home directory, oillamp resolves it on the host, asks the checker, and only then
            hands back what the file holds.

            The checker is asked in a conversation of its own. What it is shown has exactly three
            parts: the user's requirements, the run, and the request. There is no place in it for
            the agent's messages, because those are what an injection would have poisoned.
        """
        given: 'a lamp that lets the agent read its Documents, and a file there'
            Files.writeString(host.home.resolve('Documents/q3.md'), '# Q3\nRevenue is up.\n')
            givenHostAccess(roots: [host.home.resolve('Documents')])

        when: 'the application starts the lamp, with a model service it can reach'
            start()

        then: 'the session says the way out is open, and the agent has the reading tools and no others'
            var enabled = events.find { it instanceof LampEvent.HostAccessEnabled } as LampEvent.HostAccessEnabled
            enabled.tools().contains('host_read_file')
            !enabled.tools().contains('host_run')
            enabled.checker() == Optional.of(MODEL)
            var extension = Files.readString(Lamp.agentHome(host.lampPath()).orElseThrow()
                    .resolve('.pi/agent/extensions/oillamp-host-access.js'))
            var offered = extension.lines().filter { it.startsWith('const OFFERED') }.findFirst().orElseThrow()
            offered.contains('"host_list_dir", "host_stat", "host_read_file"')
            !offered.contains('host_run')
            !offered.contains('host_clipboard')
            extension.contains('net.createConnection(SOCKET)')

        and: 'the agent\'s guide says it can reach the host, with which tools and under which folder'
            var guide = Files.readString(Lamp.agentHome(host.lampPath()).orElseThrow().resolve('AGENTS.md'))
            guide.contains("## Reaching the user's computer")
            guide.contains('| `host_read_file` |')
            !guide.contains('`host_run`')
            guide.contains("`${host.home.resolve('Documents')}`")

        when: 'the agent reads a document under the root, naming it from the home directory'
            var reply = ask('host_read_file', [path: '~/Documents/q3.md'])

        then: 'the request was allowed, and the agent was given the file'
            reply['ok']
            reply['result'].contains('Revenue is up.')

        and: 'the checker was asked once, with the requirements and the resolved request, and nothing else'
            checked.size() == 1
            var seen = judged(checked.first())
            seen.keySet() == ['requirements', 'run', 'request'] as Set
            seen.requirements.text.contains(REQUIREMENTS)
            seen.request.tool == 'host_read_file'
            seen.request.resolved_paths*.host == [host.home.resolve('Documents/q3.md').toRealPath().toString()]
            events.any { it instanceof LampEvent.HostAccessDecided }

        and: 'the session\'s log has the request, in a file of its own'
            var logs = Files.list(host.lampPath().resolve('.oillamp/logs')).withCloseable { it.toList() }
                    .findAll { it.fileName.toString() ==~ /host-access-\d{8}-\d{6}\.jsonl/ }
            logs.size() == 1
            var entry = new groovy.json.JsonSlurper().parseText(Files.readAllLines(logs.first()).first()) as Map
            entry.outcome == 'done'
            entry.arguments.path == '~/Documents/q3.md'
    }

    def 'The checker reads what the run was asked, and the sandbox cannot say which run it is'() {
        reportInfo """
            Intent is what the checker judges best, so it is shown the instruction of the run the
            request comes from. Which run that is, the session knows: it is the one the agent is
            working on. The request itself does not get a say, because a request that could name
            another run could borrow that run's instruction.
        """
        given: 'an agent that reads a document while it works on a question'
            Files.writeString(host.home.resolve('Documents/q3.md'), '# Q3')
            givenHostAccess(roots: [host.home.resolve('Documents')])
            Map answered = [:]
            host.machine { it.agent { String prompt ->
                answered.putAll(ask('host_read_file', [path: '~/Documents/q3.md'], [run: 'run-999']))
                'I read it.'
            } }
            start()

        when: 'the user asks'
            var run = lamp.send(LampPrompt.fresh('Summarise my Q3 notes'))
            waitFor(LampEvent.RunFinished) { it.run().id() == run.id() }

        then: 'the checker saw this run, and what the user asked in it'
            answered['ok']
            var seen = judged(checked.first())
            seen.run.id == run.id()
            seen.run.instruction == 'Summarise my Q3 notes'
    }

    def 'The checker refuses, so the file is never read'() {
        reportInfo """
            A refusal is the feature working. The checker said no, so nothing touched the file,
            and the agent is told the checker's reason, word for word: it is the one thing that
            helps the agent do something else instead.
        """
        given: 'a lamp whose checker refuses'
            Files.writeString(host.home.resolve('Documents/passwords.md'), 'hunter2')
            givenHostAccess(roots: [host.home.resolve('Documents')])
            verdict = '{"decision":"deny","risk":"passive","reason":"The file looks like it holds passwords."}'

        when:
            start()
            var reply = ask('host_read_file', [path: '~/Documents/passwords.md'])

        then: 'the agent is told no, with the reason, and is given nothing of the file'
            !reply['ok']
            reply['error'].contains('The file looks like it holds passwords.')
            !reply.containsKey('result')
            checked.size() == 1
    }

    def 'An answer from the checker that is not a verdict, or claims more than the tool can do, is a refusal'() {
        reportInfo """
            The gate fails closed. A checker that rambles, or that allows a read while calling it
            a program run, has not given a verdict oillamp can stand on, so nothing happens.
        """
        given:
            Files.writeString(host.home.resolve('Documents/q3.md'), '# Q3')
            givenHostAccess(roots: [host.home.resolve('Documents')])
            start()

        when:
            verdict = answer
            var reply = ask('host_read_file', [path: '~/Documents/q3.md'])

        then:
            !reply['ok']
            reply['error'].contains(why)

        where:
            answer                                                                    | why
            'Sure, that looks fine to me.'                                            | 'not one JSON verdict'
            '{"decision":"allow","risk":"executing","reason":"Fine."}'                | 'more than host_read_file can do'
    }

    def 'Paths outside the roots, inside the lamp, or walking back with .. are refused without asking any model'() {
        reportInfo """
            The roots are the outer bound, and they are the one thing no model is asked about.
            A path outside them is a mistake or an attempt, not a judgement call. The lamp's own
            state — its keys, its history, the agent's home — is never reachable, also when the
            lamp was opened through a link. And a path with `..` in it is refused before it is
            resolved, so the path judged is the path used.
        """
        given: 'a lamp opened through a link to the lamps directory, reaching the whole home'
            Files.createSymbolicLink(host.home.resolve('linked'), Files.createDirectories(host.home.resolve('lamps')))
            givenHostAccess([:], host.home.resolve('linked/feature-x'))
            start(host.home.resolve('linked/feature-x'))

        when:
            var reply = ask('host_read_file', [path: asked])

        then: 'it is refused, and the checker was never asked'
            !reply['ok']
            reply['error'].contains(why)
            checked.isEmpty()

        and: 'the refusal is an event, so the user sees the attempt'
            events.any { it instanceof LampEvent.HostAccessRefused }

        where:
            asked                                        | why
            '/etc/hostname'                              | 'not under'
            '~/lamps/feature-x/.oillamp/keys/client'     | 'inside the lamp'
            '~/lamps/feature-x/oillamp.toml'             | 'inside the lamp'
            '~/Documents/new/../../.ssh/id_ed25519'      | 'contains ".."'
    }

    def 'The agent writes a file on the host, and it is never left executable'() {
        reportInfo """
            Writing is the additive tier: it changes the host, in a way the user can see and
            undo. The file and the directories on the way are made, the file is written
            atomically, and whatever was there before, the result is plain data.
        """
        given:
            givenHostAccess(roots: [host.home.resolve('Documents')])
            verdict = '{"decision":"allow","risk":"additive","reason":"The user asked for notes in Documents."}'
            var old = Files.writeString(host.home.resolve('Documents/run-me.md'), 'old')
            Files.setPosixFilePermissions(old, PosixFilePermissions.fromString('rwxr-xr-x'))
            start()

        when:
            var made = ask('host_write_file', [path: '~/Documents/notes/today.md', text: 'Café at ten.\n'])
            var replaced = ask('host_write_file', [path: '~/Documents/run-me.md', text: 'new'])

        then:
            made['ok'] && replaced['ok']
            Files.readString(host.home.resolve('Documents/notes/today.md')) == 'Café at ten.\n'
            Files.readString(old) == 'new'
            PosixFilePermissions.toString(Files.getPosixFilePermissions(old)) == 'rw-r--r--'
    }

    def 'A file is copied out of the sandbox, and a link in the agent\'s home is not followed'() {
        reportInfo """
            Copying out reads a file in the agent's home — any kind of file — and writes it on the
            host. The agent can put links in its home, so the file is read without following
            one: a link to the user's keys copies nothing.
        """
        given:
            givenHostAccess(roots: [host.home.resolve('Documents')])
            verdict = '{"decision":"allow","risk":"additive","reason":"A report the user asked for."}'
            start()
            var agentHome = Lamp.agentHome(host.lampPath()).orElseThrow()
            Files.write(agentHome.resolve('workspace/report.pdf'), [0x25, 0x50, 0x44, 0x46, 0x00, 0xff] as byte[])
            Files.createDirectories(host.home.resolve('.ssh'))
            Files.writeString(host.home.resolve('.ssh/id_ed25519'), 'private')
            Files.createSymbolicLink(agentHome.resolve('workspace/key'), host.home.resolve('.ssh/id_ed25519'))

        when:
            var copied = ask('host_copy_out', [from: '~/workspace/report.pdf', path: '~/Documents/report.pdf'])
            var linked = ask('host_copy_out', [from: '~/workspace/key', path: '~/Documents/key.txt'])

        then: 'the report arrived byte for byte'
            copied['ok']
            Files.readAllBytes(host.home.resolve('Documents/report.pdf')) == [0x25, 0x50, 0x44, 0x46, 0x00, 0xff] as byte[]

        and: 'the link was not followed'
            !linked['ok']
            linked['error'].contains('link')
            !Files.exists(host.home.resolve('Documents/key.txt'))
    }

    def 'A closed tier, and a file the desktop would run, are refused before any model is asked'() {
        reportInfo """
            Whether the host may be changed at all is the user's setting, not a model's opinion:
            a read-only lamp refuses a write by name. And "show" is for documents: a launcher
            shown with the user's default application would be run by it, which would be a way to
            run a program while running programs is closed.
        """
        given:
            Files.writeString(host.home.resolve('Documents/Report.desktop'), '[Desktop Entry]\nExec=sh -c id\n')
            givenHostAccess(roots: [host.home.resolve('Documents')], allowAdditive: false)
            start()

        when:
            var written = ask('host_write_file', [path: '~/Documents/x.md', text: 'x'])
            var shown = ask('host_show', [path: '~/Documents/Report.desktop'])
            var opened = ask('host_open_url', [url: 'https://example.org/?notes=secret'])

        then:
            !written['ok'] && written['error'].contains('allow_additive is false')
            !shown['ok'] && shown['error'].contains('runs rather than shows')
            !opened['ok'] && opened['error'].contains('allow_additive is false')
            checked.isEmpty()
    }

    def 'Past the roots, the person decides, and is asked again for the next request'() {
        reportInfo """
            With `ask_the_user`, a request the checker allows that reaches past the roots goes to
            the person instead of being refused. The session shows the question as an event, and
            the application gives the person's decision with `Lamp.decidePermission`. Each question is its own: a yes once
            is not a yes for the next one.
        """
        given:
            Files.createDirectories(host.home.resolve('Notes'))
            Files.writeString(host.home.resolve('Notes/today.md'), 'Call the bank.')
            givenHostAccess(roots: [host.home.resolve('Documents')], askTheUser: true)
            start()

        when: 'the agent reads a note outside the roots, and the person says yes'
            var first = CompletableFuture.supplyAsync { ask('host_read_file', [path: '~/Notes/today.md']) }
            var asked = waitFor(LampEvent.HostAccessAsked) { true }
            answer(asked.what(), true)

        then: 'the person was told what, and the read happened'
            asked.tool() == 'host_read_file'
            asked.what().contains('outside the lamp\'s roots')
            first.get(30, TimeUnit.SECONDS)['ok']

        when: 'it reads it again, and this time the person says no'
            events.clear()
            var second = CompletableFuture.supplyAsync { ask('host_read_file', [path: '~/Notes/today.md']) }
            answer(waitFor(LampEvent.HostAccessAsked) { true }.what(), false)

        then:
            var refused = second.get(30, TimeUnit.SECONDS)
            !refused['ok']
            refused['error'].contains('said no')
    }

    def 'An application gives the requirements, and the file must already say yes'() {
        reportInfo """
            An application with its own settings screen hands over the user's rules, exactly as it
            hands over the model key. The text goes to the engine in its environment, not on its
            command line, and it wins over the file. The model that judges is the one the
            application gave the agent.

            What the application cannot do is turn the feature on: a lamp whose file says no
            stays closed — no socket, no extension, no tools — and the application is told its
            rules guard nothing.
        """
        given: 'a lamp with the feature turned on, but its own placeholder rules'
            Files.writeString(host.home.resolve('Documents/today.md'), 'today')
            givenHostAccess(roots: [host.home.resolve('Documents')], requirements: "the file's rules")

        when:
            lamp = Lamp.at(host.lampPath()).onEvent { events << it }
                       .modelService(standInService()).modelKey('sk-0123456789').model(MODEL)
                       .security('Only ~/Documents, and nothing private.')
                       .launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))
            var reply = ask('host_read_file', [path: '~/Documents/today.md'])

        then: 'the application\'s rules are the ones the checker saw, on the agent\'s model'
            reply['ok']
            judged(checked.first()).requirements.text == 'Only ~/Documents, and nothing private.'
            modelOf(checked.first()) == MODEL

        when: 'an application asks for host access on a lamp whose file has it off'
            var closed = host.lampPath('closed')
            host.givenConfig(closed, 'schema_version = 1\n')
            List<LampEvent> heard = new CopyOnWriteArrayList<>()
            var second = Lamp.at(closed).onEvent { heard << it }
                            .modelService(standInService()).modelKey('sk-0123456789').model(MODEL)
                            .security('Read anything.')
                            .launchedBy(host.launcher).start()
            second.awaitRunning(Duration.ofSeconds(30))

        then: 'the lamp stays closed, and the application hears that its rules are unused'
            !Files.exists(Lamp.agentHome(closed).orElseThrow().resolve('.pi/agent/extensions/oillamp-host-access.js'))
            !Files.readString(Lamp.agentHome(closed).orElseThrow().resolve('AGENTS.md')).contains("## Reaching the user's computer")
            !heard.any { it instanceof LampEvent.HostAccessEnabled }
            heard.any { it instanceof LampEvent.Warning && it.problem().code().value() == 'OIL-HOSTACCESS-014' }
            Files.exists(closed.resolve('.oillamp/sockets/host/model.sock'))
            !Files.exists(closed.resolve('.oillamp/sockets/host/host-access.sock'))

        cleanup:
            second?.close()
    }

    def 'An application writes the switch and the requirements into the lamp\'s file while it sleeps'() {
        reportInfo """
            An application with a settings page for this, such as Genies, writes the user's choices
            into the lamp's own `oillamp.toml` through the Lamp API, so the file stays what decides.
            Only `enabled` and `requirements` under `[host_access]` change; the rest of the file,
            the user's comments too, stays as it was. The requirements are free text: Genies puts
            the JSON its page builds there, and the checker reads it as it would any words.

            A running session read the file as it started and would not see a change, so writing
            is refused while the lamp is awake.
        """
        given: 'a lamp with host access off, and a comment of the user\'s'
            Files.writeString(host.home.resolve('Documents/today.md'), 'today')
            host.givenConfig(host.lampPath(), """\
                schema_version = 1
                # my own note
                [host_access]
                enabled = false
                requirements = \'\'\'
                \'\'\'
                roots = ["${host.home.resolve('Documents')}"]
                """.stripIndent())
            var settings = Lamp.at(host.lampPath()).launchedBy(host.launcher)
            var rules = '{\n  "may_read": ["~/Documents"],\n  "never": "anything with \'\'\' in it"\n}'
            start()

        when: 'the application writes while the lamp is awake'
            settings.hostAccess(true, rules)

        then: 'it is refused, and the file is as it was'
            var busy = thrown(LampFailedException)
            busy.status() == dev.lamp.LampExitStatus.LAMP_BUSY
            !settings.hostAccess().enabled()

        when: 'the lamp sleeps, and the application writes again'
            lamp.close()
            lamp = null
            var written = settings.hostAccess(true, rules)

        then: 'the file says it, and nothing else in it changed'
            written == new Lamp.HostAccess(true, rules)
            settings.hostAccess() == written
            var file = Files.readString(host.lampPath().resolve('oillamp.toml'))
            file.startsWith('schema_version = 1\n# my own note\n[host_access]\nenabled = true\n')
            file.contains("roots = [\"${host.home.resolve('Documents')}\"]")

        when: 'the lamp wakes, and the agent reads a document'
            start()
            var reply = ask('host_read_file', [path: '~/Documents/today.md'])

        then: 'the checker judged it against what the application wrote'
            reply['ok']
            judged(checked.first()).requirements.text == rules

        when: 'the lamp sleeps, and the application turns it on with no requirements'
            lamp.close()
            lamp = null
            settings.hostAccess(true, '')

        then: 'that is refused as the file would be, and the file keeps its rules'
            thrown(LampFailedException)
            settings.hostAccess() == written
    }

    def 'The checker judges with the model the agent answers with, and follows it when it changes'() {
        reportInfo """
            The user picked one model for the lamp, and that is the model they trust with it. So
            a request is judged by the model the agent answers with: the one the application
            started the lamp with, and, once the application changes it, the new one, from the
            next request on. A lamp whose file names a checker_model of its own keeps that one,
            whatever the agent answers with.
        """
        given:
            Files.writeString(host.home.resolve('Documents/today.md'), 'today')
            givenHostAccess(roots: [host.home.resolve('Documents')])
            start()

        when: 'the agent reads a document'
            ask('host_read_file', [path: '~/Documents/today.md'])

        then: 'the checker was asked on the agent\'s model'
            modelOf(checked.last()) == MODEL

        when: 'the application gives the agent another model, and the agent reads again'
            lamp.useModel('mistral/mistral-large-3')
            ask('host_read_file', [path: '~/Documents/today.md'])

        then: 'the checker moved with it, and the log says which model judged each request'
            modelOf(checked.last()) == 'mistral/mistral-large-3'
            var logs = Files.list(host.lampPath().resolve('.oillamp/logs')).withCloseable { it.toList() }
                    .findAll { it.fileName.toString().startsWith('host-access-') }
            Files.readAllLines(logs.first()).collect { (new groovy.json.JsonSlurper().parseText(it) as Map).checker } ==
                    [MODEL, 'mistral/mistral-large-3']

        when: 'the lamp\'s file names a checker of its own'
            lamp.close()
            lamp = null
            checked.clear()
            givenHostAccess(roots: [host.home.resolve('Documents')], checker: 'azure/gpt-6.1-guard')
            start()
            ask('host_read_file', [path: '~/Documents/today.md'])
            lamp.useModel('mistral/mistral-large-3')
            ask('host_read_file', [path: '~/Documents/today.md'])

        then: 'that one judges, whatever the agent answers with'
            checked.collect { modelOf(it) } == ['azure/gpt-6.1-guard', 'azure/gpt-6.1-guard']
    }

    def 'A lamp with host access on, but no model to judge with, does not start'() {
        reportInfo """
            Every request is judged by a model before it happens. A lamp whose file turns host
            access on and names no checker, started without a model for the agent, has none, and
            oillamp says so rather than start a session whose way to the host could never open.
        """
        given:
            givenHostAccess(roots: [host.home.resolve('Documents')])

        when:
            lamp = Lamp.at(host.lampPath()).onEvent { events << it }
                       .modelService(standInService()).modelKey('sk-0123456789')
                       .launchedBy(host.launcher).start()

        then:
            !lamp.awaitRunning(Duration.ofSeconds(30))
            events.any { it instanceof LampEvent.Failure && it.problem().code().value() == 'OIL-HOSTACCESS-015' }
            checked.isEmpty()
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private void givenHostAccess(Map options, Path lampDir = host.lampPath()) {
        String roots = (options.roots ?: []).collect { "\"$it\"" }.join(', ')
        host.givenConfig(lampDir, """
            schema_version = 1

            [host_access]
            enabled = true
            ${options.checker ? "checker_model = \"${options.checker}\"" : ''}
            roots = [${roots}]
            allow_additive = ${options.allowAdditive ?: options.allowAdditive == null}
            ask_the_user = ${options.askTheUser ?: false}
            requirements = '''
            ${options.requirements ?: REQUIREMENTS}
            '''
        """.stripIndent())
    }

    private void start(Path lampDir = host.lampPath()) {
        lamp = Lamp.at(lampDir).onEvent { events << it }
                   .modelService(standInService())
                   .model(MODEL)
                   .modelKey('sk-0123456789')
                   .launchedBy(host.launcher).start()
        assert lamp.awaitRunning(Duration.ofSeconds(30))
    }

    private URI standInService() { URI.create("http://127.0.0.1:${service.address.port}/v1") }

    /** The model a checker request asked for. */
    private static String modelOf(String body) {
        (new groovy.json.JsonSlurper().parseText(body) as Map).model
    }

    /** What the checker was shown about the request: the JSON after the message's first line. */
    private static Map judged(String body) {
        var request = new groovy.json.JsonSlurper().parseText(body) as Map
        String content = request.messages.find { it.role == 'user' }.content
        new groovy.json.JsonSlurper().parseText(content.substring(content.indexOf('{'))) as Map
    }

    /** One request on the session's host-access socket, as the sandbox's extension makes it. */
    private Map ask(String tool, Map args, Map extra = [:]) {
        Path socket = sockets('sockets/host/host-access.sock').first()
        String request = groovy.json.JsonOutput.toJson([op: 'host'] + extra
                + [request: groovy.json.JsonOutput.toJson([tool: tool, args: args])])
        exchange(socket, request)
    }

    /** The person's decision, as an application gives it. */
    private void answer(String request, boolean allowed) {
        lamp.decidePermission(request, allowed)
    }

    /** The sockets of that name the running lamps have, under the short runtime directory. */
    private List<Path> sockets(String name) {
        Files.list(host.runtime.resolve('oillamp')).withCloseable { lamps ->
            lamps.map { it.resolve(name) }.filter { Files.exists(it) }.toList() }
    }

    private static Map exchange(Path socket, String line) {
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(socket))
            channel.write(ByteBuffer.wrap((line + '\n').getBytes(StandardCharsets.UTF_8)))
            String answer = new String(Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8)
            new groovy.json.JsonSlurper().parseText(answer.split('\n')[0]) as Map
        } finally {
            channel.close()
        }
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
