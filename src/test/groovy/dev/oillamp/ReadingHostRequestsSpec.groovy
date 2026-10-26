package dev.oillamp

import dev.lamp.LampHostRisk
import dev.lamp.LampHostVerdict
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Title


import java.nio.file.Path

@Title('Reading a host request')
@Narrative('''
Before a request from the sandbox can reach the host, it has to be read. The sandbox calls a tool
over MCP with a `tools/call`, which names the tool and gives its arguments as one JSON object.
Reading turns that object into the record for the tool, or refuses it on the spot.

```
 tools/call  name "host_read_file"   arguments {"path":"~/Documents/q3.md","offset":10}
        │
        ▼ HostToolKind.named, then HostTool.read
 ReadFile(path "~/Documents/q3.md", offset 10, maxBytes 0)     or a refusal, with a code
        │
        ▼ HostTool.closedBecause                                 is the tier open in this lamp?
        ▼ HostTool.verdict, later                                the checker's answer, read as strictly
```

Each tool belongs to a tier: **passive** tools read and return, **additive** tools change the
host or send something out of it, and the **executing** tool, `host_bash`, runs a command. The
lamp's settings open or close whole tiers, and the checker may place a request lower than its
tool's tier, never higher.

These scenarios are about values only: no socket, no process, no file.
''')
class ReadingHostRequestsSpec extends Specification {

    def 'A call becomes the tool it names, with its arguments read as the JSON values they are'() {
        reportInfo """
            The sandbox calls a tool by name, with its arguments as one JSON object. Reading turns
            it into the record for that tool, so the rest of oillamp deals in values rather than
            in JSON. The schema the agent was shown says `offset` and `max_bytes` are whole
            numbers, so they arrive as JSON numbers, and are read as numbers.
        """
        when:
            var read = HostTool.read(HostToolKind.READ_FILE, json('{"path":"~/Documents/q3.md","offset":10,"max_bytes":4000}'))

        then:
            read instanceof Result.Ok
            var tool = (read as Result.Ok).value() as HostTool.ReadFile
            tool.path() == '~/Documents/q3.md'
            tool.offset() == 10
            tool.maxBytes() == 4000
    }

    def 'Arguments that do not fit the tool never reach a model or a path'() {
        reportInfo """
            The socket is inside the sandbox, so anything can be written to it, including by the
            agent itself. Every argument is checked against the parameters the tool lists: one
            the tool does not take, one of the wrong JSON type, a required one left out, or a
            value the tool cannot use, is refused with `OIL-HOSTACCESS-002` and the name of what
            did not fit. Nothing is coerced quietly: `"offset": "10"` is a string, not a number.
        """
        when:
            var read = HostTool.read(kind, json(arguments))

        then:
            read instanceof Result.Err
            var problem = (read as Result.Err).problems().first()
            problem.code().value() == 'OIL-HOSTACCESS-002'
            problem.whatHappened().contains(named)

        where:
            kind                     | arguments                                                | named
            HostToolKind.READ_FILE   | '{"path":42}'                                            | 'path: a string'
            HostToolKind.READ_FILE   | '{}'                                                     | 'path: a string'
            HostToolKind.READ_FILE   | '{"path":"~/a.md","offset":"10"}'                        | 'offset: a whole number'
            HostToolKind.READ_FILE   | '{"path":"~/a.md","run":"run-999"}'                      | 'not run'
            HostToolKind.READ_FILE   | '["~/a.md"]'                                             | 'one JSON object'
            HostToolKind.OPEN_URL    | '{"url":"file:///etc/passwd"}'                           | 'url'
            HostToolKind.EDIT_FILE   | '{"path":"~/a.md","old_text":"","new_text":"b"}'         | 'old_text'
            HostToolKind.BASH        | '{"command":"make"}'                                     | 'cwd'
            HostToolKind.BASH        | '{"command":"make","cwd":"~/p","timeout_seconds":601}'   | 'timeout_seconds: from 1 to 600'
    }

    def 'A command is read as one string, with the directory it runs in and how long it may take'() {
        reportInfo """
            `host_bash` takes the command as the agent would type it into bash, and runs it with
            `bash -c`, so the command stays one string from the call to bash. It must name the
            directory it runs in, `cwd`, which is the one host path it names. Without
            `timeout_seconds`, it may take two minutes.
        """
        when:
            var read = HostTool.read(HostToolKind.BASH, json('{"command":"./gradlew test | tail -5","cwd":"~/project"}'))

        then:
            var bash = (read as Result.Ok).value() as HostTool.Bash
            bash.command() == './gradlew test | tail -5'
            bash.cwd() == '~/project'
            bash.timeout() == java.time.Duration.ofMinutes(2)
            HostTool.pathsOf(bash).toList() == ['~/project']
            HostTool.tierOf(bash) == LampHostRisk.EXECUTING
    }

    def 'A closed tier is not offered, and cannot be reached by asking anyway'() {
        reportInfo """
            Whether a whole class of tools exists is the user's setting, not a model's opinion.
            A read-only lamp offers the reading tools and nothing that writes; a lamp whose
            `host_access.commands` is `off` offers no way to run a command. Asking for a closed
            tool is refused by name, before the checker is consulted.
        """
        given: 'a lamp that allows additive tools but no commands'
            var access = new LampConfig.HostAccess(true, 'do not read ~/.ssh', 'azure/gpt-6.1-sol', sprouts.Tuple.of(String), 65536,
                    LampConfig.HostAccess.Commands.OFF, true, false)

        expect: 'the reading and writing tools are offered, the command tool is not'
            HostTool.offeredBy(access).contains(HostToolKind.READ_FILE)
            HostTool.offeredBy(access).contains(HostToolKind.EDIT_FILE)
            !HostTool.offeredBy(access).contains(HostToolKind.BASH)

        and: 'asking for a command anyway is refused, and says why'
            var refused = HostTool.closedBecause(HostToolKind.BASH, access)
            refused.present
            refused.get().contains('host_access.commands is "off"')

        when: 'the lamp is read-only'
            var readOnly = new LampConfig.HostAccess(true, 'do not read ~/.ssh', 'azure/gpt-6.1-sol', sprouts.Tuple.of(String), 65536,
                    LampConfig.HostAccess.Commands.OFF, false, false)

        then: 'nothing that changes the host, or sends anything out of it, is offered'
            HostTool.offeredBy(readOnly).toList()*.toolName == ['host_list_dir', 'host_stat', 'host_read_file', 'host_copy_in', 'host_show']

        and: 'opening a page is a way out, not a read, so a read-only lamp refuses it too'
            HostTool.tierOf(new HostTool.OpenUrl(URI.create('https://example.org/?q=secret'))) == LampHostRisk.ADDITIVE
            HostTool.closedBecause(HostToolKind.OPEN_URL, readOnly).present

        and: 'the clipboard tools are not built, so no lamp offers them'
            !HostTool.offeredBy(access).contains(HostToolKind.CLIPBOARD_READ)
            !HostTool.offeredBy(access).contains(HostToolKind.CLIPBOARD_WRITE)
    }

    def 'Whether the person is asked about a command is the lamp\'s setting'() {
        reportInfo """
            `host_access.commands` has three values. With `off` there is no `host_bash`. With
            `ask`, the checker judges each command and then the person is asked before it runs.
            With `checker`, the checker's allow is enough. Nothing else is ever asked of the
            person for its tool alone.
        """
        expect:
            HostTool.needsThePerson(new HostTool.Bash('make', '~/p', java.time.Duration.ofMinutes(1)), lampWhose(commands)) == asked
            !HostTool.needsThePerson(new HostTool.WriteFile('~/a.md', 'a'), lampWhose(commands))

        where:
            commands                                  | asked
            LampConfig.HostAccess.Commands.ASK        | true
            LampConfig.HostAccess.Commands.CHECKER    | false
    }

    def 'A copy out names the file in the sandbox and where it goes on the host'() {
        reportInfo """
            Copying out reads a file in the agent's own home and writes it on the host, so it
            takes two paths: where the file is in the sandbox, and where it goes. Only the second
            is a host path, and only the second goes through the roots.
        """
        when:
            var read = HostTool.read(HostToolKind.COPY_OUT, json('{"from":"~/workspace/report.pdf","path":"~/Downloads/report.pdf"}'))

        then:
            var copy = (read as Result.Ok).value() as HostTool.CopyOut
            copy.from() == '~/workspace/report.pdf'
            copy.path() == '~/Downloads/report.pdf'
            HostTool.pathsOf(copy).toList() == ['~/Downloads/report.pdf']

        and: 'without either, it is not a request'
            HostTool.read(HostToolKind.COPY_OUT, json('{"path":"~/Downloads/report.pdf"}')) instanceof Result.Err
    }

    def 'The checker may lower the risk of a request, and a verdict is read strictly'() {
        reportInfo """
            The checker answers with one JSON object: allow or deny, the risk tier it saw, and a
            reason. The tiers are a ceiling, not a suggestion: a request cannot be judged more
            permissive than the tool allows, so a checker that says "allow, executing" about a
            file read is not believed about the tier.

            An answer oillamp cannot read is a refusal. It is never permission, and the checker is
            not asked again: asking until the answer is convenient would make the whole gate
            meaningless.
        """
        expect:
            var allowed = HostTool.verdict('{"decision":"allow","risk":"passive","reason":"The user asked for it.","concerns":[]}')
            allowed instanceof Result.Ok
            ((allowed as Result.Ok).value() as LampHostVerdict).allowed()

        and: 'a fenced answer is read, since it is still one object'
            HostTool.verdict('```json\n{"decision":"deny","risk":"additive","reason":"outside the notes"}\n```') instanceof Result.Ok

        and: 'anything else is refused'
            HostTool.verdict('{"decision":"maybe","risk":"passive","reason":"hmm"}') instanceof Result.Err
            HostTool.verdict('{"decision":"allow","risk":"enormous","reason":"sure"}') instanceof Result.Err
            HostTool.verdict('{"decision":"allow","risk":"passive"}') instanceof Result.Err
            HostTool.verdict('{"decision":"allow","risk":"passive","reason":["a list"]}') instanceof Result.Err
            HostTool.verdict('{"decision":"allow","risk":"passive","reason":"   "}') instanceof Result.Err
            HostTool.verdict('I think it is fine.') instanceof Result.Err
            HostTool.verdict('') instanceof Result.Err

        and: 'a refusal keeps the reason, because the agent is told it and the user reads it'
            var denied = (HostTool.verdict('{"decision":"deny","risk":"additive","reason":"This writes outside ~/Notes."}') as Result.Ok).value() as LampHostVerdict
            !denied.allowed()
            denied.reason() == 'This writes outside ~/Notes.'
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String text) {
        JsonUtil.parse(text).orElseThrow()
    }

    private static LampConfig.HostAccess lampWhose(LampConfig.HostAccess.Commands commands) {
        new LampConfig.HostAccess(true, 'rules', '', sprouts.Tuple.of(String), 65536, commands, true, false)
    }
}
