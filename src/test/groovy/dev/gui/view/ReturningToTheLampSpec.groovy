package dev.gui.view

import dev.gui.Genies
import dev.gui.model.*
import dev.gui.pi.PiEvent
import dev.lamp.Lamp
import oillamp.ScenarioHost
import spock.lang.*
import sprouts.Tuple

import javax.swing.*
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Title('Returning to the lamp when no conversation is selected')
@Narrative('''
The conversation trees are navigation, not the genie's lifecycle. Clearing their selection
should return to the lamp overview, whether the genie sleeps or is awake. It must preserve the
conversation, draft and any answer in progress so selecting the conversation again is harmless.

An awake overview uses the welcome scene continuously, rather than freezing the final frame of
the wake greeting. The same genie stays present while the user decides what to open next.

The tree scenarios open the real Genies application against oillamp's simulated machine.
Navigation between trees reads real pi-format conversation files through the application's
runner and Lamp API; the application interface is never replaced by a stub or mock.
''')
@Subject([GeniesView, Conversations, WakeScene])
class ReturningToTheLampSpec extends Specification {

    @TempDir Path tmp
    Genies app

    def cleanup() {
        var done = new CountDownLatch(1)
        SwingUtilities.invokeAndWait { app?.quit(false, Optional.empty(), { done.countDown() }) }
        if (app != null) assert done.await(30, TimeUnit.SECONDS)
    }

    def 'Clearing the selected conversation returns a #phase genie to its overview'() {
        reportInfo '''
            **Subject:** deselection in the actual conversation-tree binding.

            A transcript can contain messages even when nothing is selected. The lamp overview
            must therefore be an explicit presentation choice, not inferred from an empty
            transcript. Clearing the selected tree row should set that choice and clear both
            trees' visible selections, while retaining the transcript and continuation point.
            This applies to a sleeping genie as well as one that is already awake.
        '''
        given: 'Juniper has a selected conversation with a message and an unsent draft'
            var conversation = new Conversation('chat', 'sessions/chat.jsonl', 'Our conversation',
                    '2026-10-26T12:00:00Z', Tuple.of(Conversation.Step), '')
            var conversations = new Conversations(Tuple.of(Conversation, conversation), new Conversations.Here(conversation.file(), ''))
                    .withChatsFold(Fold.CLOSED.toggled())
            var genie = Genie.named('Juniper').withPhase(phase).withConversations(conversations)
                    .withTranscript(Transcript.empty().you('Hello')).withDraft('Keep this draft')
            var sandbox = new ScenarioHost(tmp)
            sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80)) }
            app = Genies.open(sandbox.home.resolve('genies'), Tuple.of(Genie, Genie.asleep(genie.id(), genie.name())),
                    Settings.defaults(), sandbox.launcher, { Optional.of('sk-key') })
            SwingUtilities.invokeAndWait { app.wake(genie.id()) }
            eventually { app.state().get().genie().phase() == Genie.Phase.READY }
            if (phase == Genie.Phase.ASLEEP) {
                SwingUtilities.invokeAndWait { app.sleep(genie.id()) }
                eventually { app.state().get().genie().phase() == Genie.Phase.ASLEEP }
            }
            var state = app.state()
            SwingUtilities.invokeAndWait { state.update { it.withGenie(genie) } }
            GeniesView view
            SwingUtilities.invokeAndWait { view = new GeniesView(app) }
            var trees = descendants(view).findAll { it instanceof JTree }
            var tree = trees.find { it.selectionCount > 0 } as JTree
            assert tree != null

        when: 'the user clears the selected conversation row'
            SwingUtilities.invokeAndWait { tree.clearSelection() }
            SwingUtilities.invokeAndWait { }

        then: 'the overview is selected without erasing the conversation or draft'
            state.get().genie().conversations().overview()
            state.get().genie().transcript() == genie.transcript()
            state.get().genie().draft() == 'Keep this draft'
            state.get().genie().conversations().here() == conversations.here()
            trees.every { it.selectionCount == 0 }
            !state.get().genie().canSend()

        when: 'the user selects that same conversation again'
            SwingUtilities.invokeAndWait { tree.setSelectionRow(0) }
            SwingUtilities.invokeAndWait { }

        then: 'the overview closes and the preserved conversation is available again'
            !state.get().genie().conversations().overview()
            state.get().genie().transcript() == genie.transcript()

        where:
            phase << [Genie.Phase.ASLEEP, Genie.Phase.READY]
    }

    def 'Moving between user conversations and scheduled runs does not mistake a cleared tree for deselection'() {
        reportInfo '''
            **Subject:** the two conversation trees sharing one visible selection.

            Selecting a scheduled run clears the user-conversation tree, and selecting a user
            conversation clears the scheduled-run tree. Those automatic clears are not requests
            for the overview. Only clearing the tree that owns the current selection should
            return to the lamp; navigation between the trees must leave the chosen conversation
            selected. This distinction prevents a second tree's binding from undoing a click.
        '''
        given: 'Juniper has a user conversation and a scheduled run, with the user conversation selected'
            var mine = new Conversation('mine', '.pi/agent/sessions/--home-agent--/mine.jsonl', 'Our conversation',
                    '2026-10-26T12:00:00Z', Tuple.of(Conversation.Step), '')
            var job = new Conversation('job', '.pi/agent/sessions/--home-agent--/job.jsonl', 'Scheduled run',
                    '2026-10-26T12:00:00Z', Tuple.of(Conversation.Step), 'job-1')
            var conversations = new Conversations(Tuple.of(Conversation, mine, job), new Conversations.Here(mine.file(), ''))
                    .withChatsFold(Fold.CLOSED.toggled()).withJobsFold(Fold.CLOSED.toggled())
            var genie = Genie.named('Juniper').withConversations(conversations)
                    .withTranscript(Transcript.empty().you('Hello'))
            var sandbox = new ScenarioHost(tmp)
            sandbox.machine { it.reallyRuns('ssh-keygen').windowsStayOpenFor(Duration.ofSeconds(80)) }
            app = Genies.open(sandbox.home.resolve('genies'), Tuple.of(Genie, Genie.asleep(genie.id(), genie.name())),
                    Settings.defaults(), sandbox.launcher, { Optional.of('sk-key') })
            SwingUtilities.invokeAndWait { app.wake(genie.id()) }
            eventually { app.state().get().genie().phase() == Genie.Phase.READY }
            var home = Lamp.agentHome(app.lampOf(genie.id())).orElseThrow()
            for (conversation in [mine, job]) {
                var file = home.resolve(conversation.file())
                Files.createDirectories(file.parent)
                Files.writeString(file, """{"type":"session","version":3,"id":"${conversation.id()}","timestamp":"2026-10-26T12:00:00Z","cwd":"/home/agent"}
{"type":"message","id":"q1","parentId":null,"timestamp":"2026-10-26T12:00:01Z","message":{"role":"user","content":[{"type":"text","text":"${conversation.title()}"}]}}
""")
            }
            SwingUtilities.invokeAndWait { app.sleep(genie.id()) }
            eventually { app.state().get().genie().phase() == Genie.Phase.ASLEEP }
            var state = app.state()
            SwingUtilities.invokeAndWait { state.update { it.withGenie(genie) } }
            GeniesView view
            SwingUtilities.invokeAndWait { view = new GeniesView(app) }
            var trees = descendants(view).findAll { it instanceof JTree }
            var chats = trees.find { it.selectionCount > 0 } as JTree
            var jobs = trees.find { it != chats } as JTree

        when: 'the user selects the scheduled run'
            SwingUtilities.invokeAndWait { jobs.setSelectionRow(0) }
            eventually { state.get().genie().conversations().here().file() == job.file() }
            SwingUtilities.invokeAndWait { }

        then: 'the run stays selected after the user tree clears'
            !state.get().genie().conversations().overview()
            state.get().genie().conversations().here().file() == job.file()
            state.get().genie().transcript().entries().first().text() == job.title()
            chats.selectionCount == 0
            jobs.selectionCount == 1

        when: 'the user returns to their own conversation'
            SwingUtilities.invokeAndWait { chats.setSelectionRow(0) }
            eventually { state.get().genie().conversations().here().file() == mine.file() }
            SwingUtilities.invokeAndWait { }

        then: 'their conversation stays selected after the run tree clears'
            !state.get().genie().conversations().overview()
            state.get().genie().conversations().here().file() == mine.file()
            state.get().genie().transcript().entries().first().text() == mine.title()
            chats.selectionCount == 1
            jobs.selectionCount == 0
    }

    def 'An answer continues while the user looks at the lamp overview'() {
        reportInfo '''
            **Subject:** conversation preservation during an answer.

            Deselecting is navigation, not cancellation or starting a new conversation. An
            answer already underway must continue to accumulate in its original transcript.
            New progress and session identifiers must not unexpectedly select the conversation
            again; the overview stays until the user chooses a conversation.
        '''
        given: 'Juniper is answering a question when the user chooses the overview'
            var working = Genie.named('Juniper').waking().awake().withDraft('Hello').send()
            var overview = working.withConversations(working.conversations().withOverview(true))

        when: 'more of the answer and the conversation file arrive'
            var heard = overview.hear(new PiEvent.Said('Hello back'))
                    .hear(new PiEvent.Opened('/home/agent/sessions/chat.jsonl'))

        then: 'the answer is kept and the overview remains selected'
            heard.phase() == Genie.Phase.WORKING
            heard.transcript().entries().last().text() == 'Hello back'
            heard.conversations().overview()
            heard.conversations().selectionIn(heard.conversations().chats()).isEmpty()
    }

    def 'An already awake genie keeps living on the welcome timeline without another emergence'() {
        reportInfo '''
            **Subject:** continuous animation in the awake lamp overview.

            The overview should feel as alive as the welcome screen: waving, floating, blinking
            and cycling through its poses. An already awake genie starts after formation and
            keeps advancing along that same timeline. Changes between ready and working retain
            the clock and appearance, rather than replaying a wake or freezing the greeting.
        '''
        given: 'Juniper is already awake when its overview is shown'
            var genie = Genie.named('Juniper').waking().awake()
            var scene = WakeScene.of(genie)

        when: 'time passes and the genie starts working'
            var later = scene.after(10).follows(genie.withPhase(Genie.Phase.WORKING)).after(10)

        then: 'the shared welcome timeline advances continuously beyond formation'
            scene.moment() > WelcomeScene.FORMED
            later.moment() == scene.moment() + 20
            !later.emerging()
            later.play().pip() == scene.play().pip()
    }

    private static List descendants(Container parent) {
        parent.components.collectMany { it instanceof Container ? [it] + descendants(it) : [it] }
    }

    private static void eventually(Closure<Boolean> condition) {
        var until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < until) Thread.sleep(20)
        assert condition()
    }
}
