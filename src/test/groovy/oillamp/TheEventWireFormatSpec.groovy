package oillamp

import dev.lamp.LampJobAuthor
import dev.lamp.LampEvent
import dev.lamp.LampStartupPhase
import dev.lamp.LampSave
import dev.lamp.LampSaveKind
import dev.lamp.LampJob
import dev.lamp.LampProblem
import dev.lamp.LampStep
import dev.lamp.LampSessionStatus
import dev.lamp.LampRun
import dev.lamp.LampRunOutcome
import dev.lamp.LampAgentProgress
import dev.lamp.LampHostRisk
import dev.lamp.LampHostVerdict
import spock.lang.Specification
import sprouts.Tuple

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 *  How events travel from an embedded oillamp to the application that started it: one line of
 *  JSON each.
 *
 *  <p>The engine writes them in one process and the application reads them in another. If the
 *  two ever disagreed, the application would show the player nonsense or nothing at all, so
 *  every kind of event is written and read back here.
 */
class TheEventWireFormatSpec extends Specification {

    static final LampProblem PROBLEM = new LampProblem(
            new LampProblem.Code('OIL-SANDBOX-002'), LampProblem.Severity.ERROR,
            'The sandbox stopped while starting up',
            'the container exited with code 70',
            'there is no sandbox to work in',
            Tuple.of(LampProblem.Evidence,
                    new LampProblem.Evidence.Command(Tuple.of(String, 'podman', 'run'), 70,
                                                 'entrypoint: missing setting', Duration.ofMillis(1234)),
                    new LampProblem.Evidence.File(Path.of('/tmp/lamp/oillamp.toml'), 'the configuration'),
                    new LampProblem.Evidence.Value('podman', '4.9.3'),
                    new LampProblem.Evidence.Excerpt('container log', 'line one\nline "two"\t\\'),
                    new LampProblem.Evidence.Config(Path.of('/tmp/lamp/oillamp.toml'),
                                                'network.rules[0].cidrs', '"10/8"', 'an address range')),
            Tuple.of(LampProblem.Fix, LampProblem.Fix.of('look at the log'),
                                  LampProblem.Fix.run('stop it', 'oillamp stop /tmp/lamp')),
            Optional.of(Path.of('/tmp/lamp/.oillamp/logs/x.log')))

    static final LampStep STEP = new LampStep('WriteFile', 'write a file', 'because')

    /** One of every kind of event, with every optional part both present and absent somewhere. */
    /** A snapshot with every part present, a message with a line break in it among them. */
    static final LampSave SNAPSHOT = new LampSave(
            '3f2a9c1d00000000000000000000000000000000', Instant.parse('2026-09-29T18:14:03Z'),
            LampSaveKind.BEFORE_RESTORE, 'before the upgrade\nand the "migration"',
            Optional.of('20260929-181200'), Optional.of('run-12'), Optional.of('job-3'),
            Optional.of(LampRunOutcome.TIMED_OUT), Optional.of('0199a3f0-7c1e-7d2b-9a41-5c3e2f1d0b9a'))

    static final LampJob JOB = new LampJob('job-3', '0 9 * * 1-5',
            Optional.of(Instant.parse('2026-09-30T07:00:00Z')), 'Check the build\nand say "why"',
            LampJobAuthor.AGENT, Instant.parse('2026-09-29T18:14:03Z'),
            Optional.of(Instant.parse('2026-10-13T18:14:03Z')), true,
            Tuple.of(Instant, Instant.parse('2026-09-30T07:00:00Z'), Instant.parse('2026-10-01T07:00:00Z')))

    static final dev.lamp.LampConversation CONVERSATION = new dev.lamp.LampConversation(
            '01a0ec69-de1c-7234-914c-b970a862c13e', 'run-12 (job-3)', '.pi/agent/sessions/--home-agent--/x.jsonl',
            Instant.parse('2026-09-29T18:14:03Z'), Instant.parse('2026-09-29T18:15:00Z'),
            Tuple.of(dev.lamp.LampConversation.Entry,
                new dev.lamp.LampConversation.Entry('q1', Optional.empty(), Instant.parse('2026-09-29T18:14:03Z'),
                        dev.lamp.LampConversation.Kind.MESSAGE_TO_AGENT, 'What "now"?\nTell me.', '',
                        Tuple.of(dev.lamp.LampConversation.ToolCall), Optional.empty(), false, Optional.empty()),
                new dev.lamp.LampConversation.Entry('a1', Optional.of('q1'), Instant.parse('2026-09-29T18:15:00Z'),
                        dev.lamp.LampConversation.Kind.MESSAGE_FROM_AGENT, '', 'thinking…',
                        Tuple.of(dev.lamp.LampConversation.ToolCall, new dev.lamp.LampConversation.ToolCall('c1', 'bash', 'ls')),
                        Optional.of('bash'), true, Optional.of('azure/gpt-6.1-sol'))))

    static final LampRun RUN = new LampRun('run-12', Optional.of('job-3'), 'Check the build', Optional.empty())

    static final List<LampEvent> EXAMPLES = [
            new LampEvent.PhaseStarted(LampStartupPhase.SESSION),
            new LampEvent.PhaseFinished(LampStartupPhase.IMAGE, Duration.ofSeconds(3)),
            new LampEvent.Ok('host', 'podman 4.9.3, rootless, crun'),
            new LampEvent.Info('network', 'denied 10.0.0.1:5432 — "block private"'),
            new LampEvent.StepPlanned(STEP),
            new LampEvent.StepStarted(STEP),
            new LampEvent.StepSucceeded(STEP, Duration.ofMillis(15)),
            new LampEvent.StepSkipped(STEP, 'already there'),
            new LampEvent.Output('sway', '[sway] 00:00:01 ✓ ünïcödé'),
            new LampEvent.Answer('multi\nline\nanswer'),
            new LampEvent.SessionStateChanged(new LampSessionStatus('running', 'up', Duration.ofMinutes(2), 1)),
            new LampEvent.SessionOpened('20260928-120000', Tuple.of(String, 'ssh', '-T', 'lamp-k3v7x2ab'),
                                        Path.of('/run/user/1000/oillamp/k3v7x2ab/sockets/infra/vnc.sock'), 1920, 1080),
            new LampEvent.LookAtDesktop('the chart you asked for'),
            new LampEvent.WindowOpened('the desktop viewer', Tuple.of(String, 'vncviewer', '/run/x.sock')),
            new LampEvent.Summary('session 20260928-120000', Tuple.of(String)),
            new LampEvent.Saved(SNAPSHOT, 1234),
            new LampEvent.History(Tuple.of(LampSave, SNAPSHOT,
                    new LampSave('0123456789abcdef0123456789abcdef01234567', Instant.EPOCH,
                                           LampSaveKind.IDLE, '', Optional.empty(), Optional.empty(),
                                           Optional.empty(), Optional.empty(), Optional.empty()))),
            new LampEvent.Restored(SNAPSHOT, new LampSave('fedcba9876543210fedcba9876543210fedcba98',
                    Instant.parse('2026-09-29T19:00:00Z'), LampSaveKind.RESTORE, 'back to 3f2a9c1d',
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())),
            new LampEvent.Schedule(true, false, 'Europe/Berlin', Tuple.of(LampJob, JOB,
                    new LampJob('job-4', 'once at 2026-10-01 09:00', Optional.empty(), 'x',
                                      LampJobAuthor.USER, Instant.EPOCH, Optional.empty(), false, Tuple.of(Instant)))),
            new LampEvent.JobAdded(JOB),
            new LampEvent.JobRemoved(JOB, 'it expired'),
            new LampEvent.ScheduleChanged('the schedule is paused'),
            new LampEvent.RunQueued(new LampRun('run-13', Optional.empty(), 'what now?', Optional.of('01a0ec69-de1c')), 0),
            new LampEvent.RunStarted(RUN),
            new LampEvent.RunFinished(RUN, LampRunOutcome.FINISHED, 'All green.\n\nNothing to do.',
                    Optional.of(SNAPSHOT), Duration.ofSeconds(95), Optional.of('01a0ec69-de1c')),
            new LampEvent.RunFinished(RUN, LampRunOutcome.TIMED_OUT, '', Optional.empty(), Duration.ofMinutes(30), Optional.empty()),
            new LampEvent.RunAccepted(RUN),
            new LampEvent.RunProgress('run-12', new LampAgentProgress.Opened('01a0ec69-de1c')),
            new LampEvent.RunProgress('run-12', new LampAgentProgress.Said('Hel')),
            new LampEvent.RunProgress('run-12', new LampAgentProgress.Thought('hmm\n"quoted"')),
            new LampEvent.RunProgress('run-12', new LampAgentProgress.Answered('Hello.', false, Optional.of('azure/gpt-6.1-sol'))),
            new LampEvent.RunProgress('run-12', new LampAgentProgress.Answered('', true, Optional.empty())),
            new LampEvent.RunProgress('run-12', new LampAgentProgress.ToolStarted('call-1', 'bash', 'ls -la')),
            new LampEvent.RunProgress('run-12', new LampAgentProgress.ToolFinished('call-1', true, 'no such file')),
            new LampEvent.RunProgress('run-12', new LampAgentProgress.Retrying(2, 3, '529 overloaded')),
            new LampEvent.AgentStatus(Optional.of(RUN), Tuple.of(LampRun, RUN)),
            new LampEvent.AgentStatus(Optional.empty(), Tuple.of(LampRun)),
            new LampEvent.Conversations(Tuple.of(dev.lamp.LampConversation, CONVERSATION)),
            new LampEvent.ConversationShown(CONVERSATION),
            new LampEvent.HostAccessEnabled(Tuple.of(String, 'host_read_file', 'host_list_dir'),
                                             Optional.of('azure/gpt-6.1-sol')),
            new LampEvent.HostAccessEnabled(Tuple.of(String), Optional.empty()),
            new LampEvent.HostAccessRequested('run-12', 'host_read_file', '~/Documents/a.md'),
            new LampEvent.HostAccessDecided('run-12', 'host_read_file',
                    new LampHostVerdict(true, LampHostRisk.PASSIVE, 'The user asked about this document.'),
                    Duration.ofMillis(812)),
            new LampEvent.HostAccessDecided('run-12', 'host_write_file',
                    new LampHostVerdict(false, LampHostRisk.ADDITIVE, 'Writes outside the allowed root.'),
                    Duration.ofSeconds(2)),
            new LampEvent.HostAccessRefused('run-12', 'host_read_file', 'that path is outside the lamp\'s roots'),
            new LampEvent.HostAccessAsked('run-12', 'host_run', 'run `inkscape report.svg`, in ~/Documents'),
            new LampEvent.HostAccessSettings(true, '{"may_read": ["~/Documents"]}\nAnd nothing private.'),
            new LampEvent.AutosaveSettings(false, true, true, false),
            new LampEvent.Warning(PROBLEM.withFix(LampProblem.Fix.of('another'))),
            new LampEvent.Failure(new LampProblem(new LampProblem.Code('OIL-LOCK-001'), LampProblem.Severity.ERROR,
                    'busy', '', '', Tuple.of(LampProblem.Evidence), Tuple.of(LampProblem.Fix), Optional.empty())),
            new LampEvent.Failure(PROBLEM),
    ]

    def 'Every event reads back as the same value it was written from'() {
        reportInfo """
            Text with line breaks, quotes, tabs and backslashes, problems with every kind of
            evidence, and parts that are absent: all come back exactly as they went out.
        """
        expect:
            EXAMPLES.every { event -> LampEvent.fromJson(event.toJson()) == Optional.of(event) }
    }

    def 'Each event is one line, so a reader can split the stream on line breaks'() {
        reportInfo """
            The application reads the engine's output one line at a time and treats each line as
            one event. Some events carry text with line breaks in it, such as a container log in
            a problem's evidence. If such a break reached the output as it is, one event would
            arrive as two broken halves, and neither could be read. So a line break inside an
            event is always written as an escape, and only the end of an event ends a line.
        """
        expect:
            EXAMPLES.every { !it.toJson().contains('\n') }
    }

    def 'The examples above cover every kind of event there is'() {
        reportInfo """
            So that a new kind of event cannot be added without being checked here.
        """
        expect:
            LampEvent.permittedSubclasses
                     .findAll { !it.interface }
                     .every { kind -> EXAMPLES.any { kind.isInstance(it) } }
    }

    def 'A line that is not an event this oillamp knows is skipped, not a failure'() {
        reportInfo """
            An application may read from a newer oillamp than it was built with, or receive a line
            that was cut short. Either way it should carry on with the next line.
        """
        expect:
            LampEvent.fromJson(line).isEmpty()

        where:
            line << ['', 'not json', '{}', '{"type":"FromTheFuture","x":1}', '{"type":"Ok","area":"x"}',
                     '{"type":"Failure","problem":{"code":{"value":"nonsense"}}}']
    }

    def 'An answer from an engine that did not yet say which model wrote it reads with no model'() {
        reportInfo """
            An application may join a session that an older oillamp started, and read its events.
            Answers then come without the model that wrote them; they read as answers whose
            model is not known, rather than not at all.
        """
        expect:
            LampEvent.fromJson('{"type":"RunProgress","run":"run-3","progress":{"type":"Answered","text":"Hi.","failed":false}}') ==
                    Optional.of(new LampEvent.RunProgress('run-3', new LampAgentProgress.Answered('Hi.', false, Optional.empty())))
    }
}
