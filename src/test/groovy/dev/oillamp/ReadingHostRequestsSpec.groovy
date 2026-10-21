package dev.oillamp

import dev.lamp.LampHostRisk
import dev.lamp.LampHostVerdict
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Title


import java.nio.file.Path

@Title('Reading a host request')
@Narrative('''
Before a request from the sandbox can reach the host, it has to be read. Reading turns one line
of JSON into the tool it names, with its arguments, or refuses it on the spot.

```
 {"tool":"host_read_file","args":{"path":"~/Documents/q3.md","offset":"10"}}
        │
        ▼ HostTool.read
 ReadFile(path "~/Documents/q3.md", offset 10, maxBytes 0)     or a refusal, with a code
        │
        ▼ HostTool.closedBecause                                 is the tier open in this lamp?
        ▼ HostTool.verdict, later                                the checker's answer, read as strictly
```

Each tool belongs to a tier: **passive** tools read and return, **additive** tools change the
host or send something out of it, and the **executing** tool runs a program. The lamp's settings
open or close whole tiers, and the checker may place a request lower than its tool's tier, never
higher.

These scenarios are about values only: no socket, no process, no file.
''')
class ReadingHostRequestsSpec extends Specification {

    def 'A request becomes the tool it names, with its arguments read as they were written'() {
        reportInfo """
            The sandbox sends one JSON object per request: a tool name and a table of arguments,
            each of them a string. Reading turns it into the record for that tool, so the rest of
            oillamp deals in values rather than in JSON.

            Nothing is coerced quietly. `offset` and `max_bytes` are numbers in the tool, so a
            caller that sends `"offset": "first"` gets a refusal rather than a read from the
            beginning.
        """
        given:
            var line = '{"tool":"host_read_file","args":{"path":"~/Documents/q3.md","offset":"10","max_bytes":"4000"}}'

        when:
            var read = HostTool.read(line)

        then:
            read instanceof Result.Ok
            var call = (read as Result.Ok).value() as HostTool.Call
            call.tool() instanceof HostTool.ReadFile
            (call.tool() as HostTool.ReadFile).path() == '~/Documents/q3.md'
            (call.tool() as HostTool.ReadFile).offset() == 10
            (call.tool() as HostTool.ReadFile).maxBytes() == 4000
    }

    def 'A malformed request never reaches a model or a path'() {
        reportInfo """
            The socket is inside the sandbox, so anything can be written to it, including by the
            agent itself. A line that is not exactly one request is refused on the spot, with a
            code of its own, rather than being passed on: a model asked about nonsense costs
            money, and a path parsed from nonsense is a bug waiting to happen.
        """
        when:
            var read = HostTool.read(line)

        then:
            read instanceof Result.Err
            (read as Result.Err).problems().first().code().value() == code

        where:
            line                                                                     | code
            'not json at all'                                                        | 'OIL-HOSTACCESS-011'
            '{"tool":"host_read_file"}'                                              | 'OIL-HOSTACCESS-011'
            '{"tool":"host_read_file","args":{"path":42}}'                           | 'OIL-HOSTACCESS-011'
            '{"tool":"host_take_screenshot","args":{}}'                              | 'OIL-HOSTACCESS-001'
            '{"tool":"host_read_file","args":{}}'                                    | 'OIL-HOSTACCESS-002'
            '{"tool":"host_open_url","args":{"url":"file:///etc/passwd"}}'           | 'OIL-HOSTACCESS-002'
            '{"tool":"host_run","args":{"executable":"bash"}}'                       | 'OIL-HOSTACCESS-002'
    }

    def 'A closed tier is not offered, and cannot be reached by asking anyway'() {
        reportInfo """
            Whether a whole class of tools exists is the user's setting, not a model's opinion.
            A read-only lamp offers the reading tools and nothing that writes; a lamp that names
            no programs offers no way to run one. Asking for a closed tool is refused by name,
            before the checker is consulted.
        """
        given: 'a lamp that allows additive tools but names no programs'
            var access = new LampConfig.HostAccess(true, 'do not read ~/.ssh', 'azure/gpt-6.1-sol', sprouts.Tuple.of(String), 65536,
                    sprouts.Tuple.of(String), true, false)

        expect: 'the reading and writing tools are offered, the running one is not'
            HostTool.offeredBy(access).contains('host_read_file')
            HostTool.offeredBy(access).contains('host_write_file')
            !HostTool.offeredBy(access).contains('host_run')

        and: 'asking for a program anyway is refused, and says why'
            var refused = HostTool.closedBecause(new HostTool.Run('bash', sprouts.Tuple.of(String, '-c', 'id')), access)
            refused.present
            refused.get().contains('does not let the agent run programs')

        when: 'the lamp is read-only'
            var readOnly = new LampConfig.HostAccess(true, 'do not read ~/.ssh', 'azure/gpt-6.1-sol', sprouts.Tuple.of(String), 65536,
                    sprouts.Tuple.of(String), false, false)

        then: 'nothing that changes the host, or sends anything out of it, is offered'
            HostTool.offeredBy(readOnly).toList() == ['host_list_dir', 'host_stat', 'host_read_file', 'host_show']

        and: 'opening a page is a way out, not a read, so a read-only lamp refuses it too'
            HostTool.tierOf(new HostTool.OpenUrl(URI.create('https://example.org/?q=secret'))) == LampHostRisk.ADDITIVE
            HostTool.closedBecause(new HostTool.OpenUrl(URI.create('https://example.org/')), readOnly).present

        and: 'the clipboard tools are not built, so no lamp offers them'
            !HostTool.offeredBy(access).contains('host_clipboard_read')
            !HostTool.offeredBy(access).contains('host_clipboard_write')
    }

    def 'A copy out names the file in the sandbox and where it goes on the host'() {
        reportInfo """
            Copying out reads a file in the agent's own home and writes it on the host, so it
            takes two paths: where the file is in the sandbox, and where it goes. Only the second
            is a host path, and only the second goes through the roots.
        """
        when:
            var read = HostTool.read('{"tool":"host_copy_out","args":{"from":"~/workspace/report.pdf","path":"~/Downloads/report.pdf"}}')

        then:
            var copy = ((read as Result.Ok).value() as HostTool.Call).tool() as HostTool.CopyOut
            copy.from() == '~/workspace/report.pdf'
            copy.path() == '~/Downloads/report.pdf'
            HostTool.pathsOf(copy).toList() == ['~/Downloads/report.pdf']

        and: 'without either, it is not a request'
            HostTool.read('{"tool":"host_copy_out","args":{"path":"~/Downloads/report.pdf"}}') instanceof Result.Err
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
}
