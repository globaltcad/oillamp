package dev.oillamp

import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Title

import sprouts.Tuple

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant

@Title('The rules the host-access gate judges by')
@Narrative('''
The host-access gate has a handful of small rules with large consequences. Each is a value, and
each is easier to see on its own than through a whole session:

- **What the checker is told about a run.** The session remembers what the last few runs were
  asked and did, and keeps the one that just did something.
- **How the checker's answer is read off the wire.** As bytes, so a reason in any language
  arrives whole.
- **Where a lamp without roots reaches.** The user's home, all of it, and nothing beside it.
- **Which files "show" may open.** Documents, never something the desktop would run.
- **Where a copy out reads from.** The sandbox's own home, and nowhere else.
- **When the person is asked.** One question at a time, and only while the session runs.

```
 a request that needs the person
        │ SessionEvent.HostAccessAsked
        ▼
 SessionMachine:  running, and nothing asked yet?   yes ─▶ AskTheUser       (the person sees it)
                                                     no  ─▶ DeclineQuestion  (refused at once, with why)
        │
        ▼ the answer, a timeout, or the session ending
 SessionEvent.UserAnswered  ─▶  the question is closed, and the next one may be asked
```

These scenarios use the package-private code directly, with no session around it.
''')
class JudgingHostRequestsSpec extends Specification {

    def 'The session remembers the runs that changed last, and forgets the one changed longest ago'() {
        reportInfo """
            The checker is told what the run a request came from was asked and did. The session
            keeps that for a few runs only. The run that just did something is the one a request
            is about to come from, so it is kept first, and the one that has been quiet longest
            is the one forgotten.
        """
        given:
            Tuple<HostAccess.RunLog> known = Tuple.of(HostAccess.RunLog)
            for (String run : ['run-1', 'run-2', 'run-3', 'run-4', 'run-5'])
                known = HostAccess.rememberRun(known, new HostAccess.RunLog(run, "asked in $run", '', Tuple.of(String)))

        expect: 'the latest four, newest first'
            known.toList()*.run() == ['run-5', 'run-4', 'run-3', 'run-2']

        when: 'an older run does something again'
            known = HostAccess.rememberRun(known, HostAccess.withToolCall(HostAccess.logOf(known, 'run-2'), 'bash', 'ls'))

        then: 'it comes first, and keeps what it was asked'
            known.toList()*.run() == ['run-2', 'run-5', 'run-4', 'run-3']
            HostAccess.logOf(known, 'run-2').instruction() == 'asked in run-2'
            HostAccess.logOf(known, 'run-2').recentToolCalls().toList() == ['bash: ls']

        and: 'a forgotten run is judged on the request alone'
            HostAccess.logOf(known, 'run-1').instruction() == ''
    }

    def 'A lamp that names no roots reaches the user\'s home, and only that'() {
        reportInfo """
            An empty `host_access.roots` means the home directory, which is what Genies and the
            lamp template leave it as. The home itself is inside, and so is anything under it;
            a neighbour whose name only starts the same way is not.
        """
        given:
            var home = File.createTempDir('home', '').toPath()
            Files.createDirectories(home.resolve('Documents'))
            var none = Tuple.of(String)

        expect:
            HostPaths.within(HostPaths.locate('~/', home).value().hostPath(), home, none)
            HostPaths.within(HostPaths.locate('~/Documents/q3.md', home).value().hostPath(), home, none)
            !HostPaths.within(Path.of(home.toRealPath().toString() + '-other'), home, none)
            !HostPaths.within(Path.of('/etc/passwd'), home, none)

        cleanup:
            home.toFile().deleteDir()
    }

    def 'The checker\'s answer is read as bytes, so a reason in any language arrives whole'() {
        reportInfo """
            A model service may send its answer in chunks, each with its size in bytes. A reason
            with a dash or an accent in it has more bytes than characters, and reading the sizes
            against characters would put every later chunk in the wrong place and turn an allow
            into an unreadable answer.
        """
        given:
            byte[] reason = 'Lecture autorisée — dans ~/Documents'.getBytes(StandardCharsets.UTF_8)
            byte[] first = Arrays.copyOfRange(reason, 0, 20)
            byte[] second = Arrays.copyOfRange(reason, 20, reason.length)
            var wire = new ByteArrayOutputStream()
            wire.write("${Integer.toHexString(first.length)}\r\n".getBytes(StandardCharsets.ISO_8859_1))
            wire.write(first)
            wire.write("\r\n${Integer.toHexString(second.length)};ext=1\r\n".getBytes(StandardCharsets.ISO_8859_1))
            wire.write(second)
            wire.write('\r\n0\r\n\r\n'.getBytes(StandardCharsets.ISO_8859_1))

        expect:
            new String(HostAccessChecker.decodeBody('HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked', wire.toByteArray()),
                       StandardCharsets.UTF_8) == 'Lecture autorisée — dans ~/Documents'

        when: 'the answer stops in the middle of a chunk'
            HostAccessChecker.decodeBody('HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked', '40\r\nshort'.bytes)

        then: 'it is no answer at all'
            thrown(IOException)
    }

    def 'Showing a file opens it, and never runs it'() {
        reportInfo """
            "Show" hands a file to the user's default application. For a document that is
            passive. For a launcher, a script or anything marked executable, the default
            application is the thing that runs it, which would let a written file and a "show"
            together run a program while the executing tier is closed. Those are refused.
        """
        given:
            var dir = File.createTempDir('show', '').toPath()
            var report = Files.writeString(dir.resolve('report.pdf'), '%PDF')
            var launcher = Files.writeString(dir.resolve('Report.desktop'), '[Desktop Entry]\nExec=sh -c id\n')
            var script = Files.writeString(dir.resolve('notes'), '#!/bin/sh\nid\n')
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString('rwxr--r--'))

        expect:
            HostAccessTools.whyNotShown(report).empty
            HostAccessTools.whyNotShown(dir).empty
            HostAccessTools.whyNotShown(launcher).get().contains('runs rather than shows')
            HostAccessTools.whyNotShown(script).get().contains('executable')
            HostAccessTools.whyNotShown(dir.resolve('missing.pdf')).present

        cleanup:
            dir.toFile().deleteDir()
    }

    def 'A copy out reads only from the sandbox\'s own home'() {
        reportInfo """
            The file being copied out is named as the agent sees it, in its home in the sandbox.
            oillamp reads it from the lamp's copy of that home, without following a link, and a
            path that leaves the home is not one.
        """
        expect:
            (HostAccessTools.inAgentHome('host_copy_out', 'from', from) instanceof Result.Ok) == ok
            !ok || (HostAccessTools.inAgentHome('host_copy_out', 'from', from) as Result.Ok).value() == Path.of(relative)

        where:
            from                               | ok    | relative
            '~/workspace/report.pdf'           | true  | 'workspace/report.pdf'
            '/home/agent/workspace/report.pdf' | true  | 'workspace/report.pdf'
            '~/../dev/.ssh/id_ed25519'         | false | ''
            '~/workspace/./report.pdf'         | false | ''
            '/etc/passwd'                      | false | ''
            'workspace/report.pdf'             | false | ''
    }

    def 'The person is asked one question at a time, and only while the session runs'() {
        reportInfo """
            A request that needs the person waits on the session for an answer. A running session
            shows one question at a time; another one arriving meanwhile is declined at once, with
            the reason, rather than left to wait out its minute for a person who never sees it.
            Once the first is answered, or timed out, the next can be asked.
        """
        given:
            var rules = new SessionMachine(new SessionMachine.Settings(Duration.ofSeconds(60), false, false, '/lamp', false))
            var now = Instant.parse('2026-10-19T09:00:00Z')
            SessionState running = new SessionState.Running(now, ReadyInfo.unknown(), false, 0, Optional.empty(),
                                                            Tuple.of(HostAccess.RunLog))

        when: 'one question arrives'
            var first = rules.step(running, new SessionEvent.HostAccessAsked('run-1', 'host_bash', 'run inkscape'), now)

        then: 'it is asked'
            first.actions().toList() == [new SessionAction.AskTheUser('run-1', 'host_bash', 'run inkscape')]

        when: 'a second arrives before the first is answered'
            var second = rules.step(first.next(), new SessionEvent.HostAccessAsked('run-1', 'host_bash', 'run gimp'), now)

        then: 'it is declined, and the first still waits'
            second.actions().first() instanceof SessionAction.DeclineQuestion
            (second.next() as SessionState.Running).asked() == Optional.of('run inkscape')

        when: 'the first is answered, and another comes'
            var answered = rules.step(second.next(), new SessionEvent.UserAnswered('run inkscape', true), now)
            var third = rules.step(answered.next(), new SessionEvent.HostAccessAsked('run-1', 'host_bash', 'run gimp'), now)

        then: 'that one is asked'
            third.actions().toList() == [new SessionAction.AskTheUser('run-1', 'host_bash', 'run gimp')]

        when: 'a question arrives while the session is still starting'
            var early = rules.step(new SessionState.Starting(now, Tuple.of(HostAccess.RunLog)),
                                   new SessionEvent.HostAccessAsked('run-1', 'host_bash', 'run inkscape'), now)

        then: 'there is nobody to ask, so it is declined'
            early.actions().first() instanceof SessionAction.DeclineQuestion
    }

    def 'What a run did before the desktop was up is not forgotten when it comes up'() {
        given:
            var rules = new SessionMachine(new SessionMachine.Settings(Duration.ofSeconds(60), false, false, '/lamp', false))
            var now = Instant.parse('2026-10-19T09:00:00Z')
            var runs = Tuple.of(HostAccess.RunLog, new HostAccess.RunLog('run-1', 'tidy my notes', '', Tuple.of(String)))

        when:
            var up = rules.step(new SessionState.Starting(now, runs), new SessionEvent.ContainerReady(ReadyInfo.unknown()), now)

        then:
            up.next().runs() == runs
    }
}
