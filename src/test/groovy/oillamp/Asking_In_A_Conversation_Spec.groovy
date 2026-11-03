package oillamp

import dev.lamp.Lamp
import dev.lamp.LampConversation.Kind
import dev.lamp.LampPrompt
import dev.lamp.LampEvent
import dev.lamp.LampRunOutcome
import dev.lamp.LampDirectory
import dev.lamp.LampConversation
import dev.oillamp.OilLamp
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

@Title('Asking in a conversation')
@Narrative('''
A chat is more than a string of questions, each starting afresh. Someone continues a conversation,
goes back to an earlier answer and asks from there, or asks something else instead of an earlier
question. pi, the harness that runs the agent, keeps every conversation as a tree of *entries*,
the questions and the answers, so none of that loses anything: each is a new branch of the tree.
The session opens the conversation, moves to the right entry, and asks.

- `oillamp ask <lamp> <question>` starts a new conversation.
- `--in <conversation>` continues one, after its last answer.
- `--after <answer>` asks from an earlier answer, and `--instead-of <question>` in place of an
  earlier question.
- An application built on oillamp's Java API does the same with `LampPrompt`.

The stand-in pi here keeps its conversations as pi does, in files in the agent's home, and
answers each question in a few words, such as "Blue." to "Name a colour". Each scenario reads the
conversations back with `Lamp.conversation`, as an application would.
''')
@Subject([OilLamp, Lamp])
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class Asking_In_A_Conversation_Spec extends Specification {

    @TempDir Path tmp
    ScenarioHost host

    Path lamp
    final List<LampEvent> reported = new java.util.concurrent.CopyOnWriteArrayList<>()
    final List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>()
    Thread session

    /** An agent that answers each question with one word, so the tree is easy to read. */
    static final Map<String, String> ANSWERS = [
            'Name a colour'  : 'Blue.',
            'Name a fruit'   : 'An apple.',
            'Name an animal' : 'A fox.',
            'Name a tree'    : 'An oak.',
            'Name a river'   : 'The Danube.']

    def setup() {
        host = new ScenarioHost(tmp)
        host.machine { it.reallyRuns('ssh-keygen') }
        lamp = host.lampPath()
        // With the schedule on, a job's prompt carries the agent's notes and recent runs. A
        // question wrapped in them as well would show in `prompts`.
        host.givenConfig(lamp, 'schema_version = 1\n[schedule]\nenabled = true\n')
        assert host.oillamp.run('at', lamp.toString()).succeeded()
        host.machine { it.agent { String prompt -> prompts << prompt; ANSWERS.find { prompt.contains(it.key) }?.value ?: 'Hm.' } }
        startASession()
    }

    def cleanup() {
        if (session?.alive) {
            host.oillamp.run('stop', lamp.toString())
            session.join(30_000)
        }
    }

    def '`oillamp ask` without `--in` starts a new conversation, and its run says which'() {
        reportInfo """
            Let's consider `oillamp ask` given only a question, with no `--in` naming a
            conversation. The session starts a new conversation for it, which takes the question as
            its title. The event that ends the run, `RunFinished`, carries the conversation's id, so
            that an application built on oillamp's Java API can find the conversation with
            `Lamp.conversation` and show it.
        """
        when: '`oillamp ask <lamp> "Name a colour"` is run'
            var colourAsked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')

        then: 'the run finished, and the conversation its `RunFinished` event names has the question as its title, then the answer'
            colourAsked.succeeded()
            var asked = colourAsked.events().find { it instanceof LampEvent.RunFinished }
            asked.outcome() == LampRunOutcome.FINISHED
            var conversation = Lamp.conversation(lamp, asked.conversation().orElseThrow()).orElseThrow()
            conversation.title() == 'Name a colour'
            questionsAndAnswers(conversation.line()) == ['Name a colour', 'Blue.']
    }

    def '`oillamp ask --in` adds the question after the conversation\'s last answer, exactly as written'() {
        reportInfo """
            The next question in a chat goes after the last answer. Here `oillamp ask --in` names
            the conversation by only the first 13 characters of its id. What is asked reaches the
            agent exactly as written, never wrapped in anything, because the chat shows it as the
            asker's message. Only a scheduled job's run gets the agent's notes and recent runs put
            in front of its prompt. This lamp has its schedule on, so the scenario would see them
            if a question were wrapped.
        """
        given: 'a conversation started with "Name a colour"'
            var colourAsked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')
            assert colourAsked.succeeded()
            var colourRun = colourAsked.events().find { it instanceof LampEvent.RunFinished }
            var first = colourRun.conversation().orElseThrow()

        when: '`oillamp ask --in <first 13 characters of its id> "Name a fruit"` is run'
            var second = host.oillamp.run('ask', lamp.toString(), '--in', first.take(13), 'Name a fruit')

        then: 'it prints the answer, and the agent received both questions exactly as written'
            second.succeeded()
            second.console().contains('An apple.')
            prompts == ['Name a colour', 'Name a fruit']

        and: 'it is the same conversation, one question longer'
            Lamp.conversations(lamp).size() == 1
            questionsAndAnswers(Lamp.conversation(lamp, first).orElseThrow().line()) ==
                    ['Name a colour', 'Blue.', 'Name a fruit', 'An apple.']
    }

    def '`LampPrompt.insteadOf` asks in place of an earlier question, and keeps the old one on a branch of its own'() {
        reportInfo """
            Let's consider someone who regrets a question and asks something else in its place, here
            through the Java API: `LampDirectory.ask` with `LampPrompt.insteadOf`, which names the
            conversation and the question to replace. Nothing is lost. The old question and
            everything after it stay on a branch of their own, and the conversation's current line
            now runs through the new question.
        """
        given: 'a conversation of "Name a colour", then "Name a fruit"'
            var colourAsked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')
            assert colourAsked.succeeded()
            var colourRun = colourAsked.events().find { it instanceof LampEvent.RunFinished }
            var conversation = colourRun.conversation().orElseThrow()
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a fruit').succeeded()
            var fruit = question(conversation, 'Name a fruit')

        when: '`LampDirectory.ask` asks "Name an animal" instead of "Name a fruit"'
            var asked = running().ask(LampPrompt.insteadOf(conversation, fruit.id(), 'Name an animal'))

        then: 'the run finished, and the conversation\'s current line runs through the new question'
            asked.outcome() == LampRunOutcome.FINISHED
            var tree = Lamp.conversation(lamp, conversation).orElseThrow()
            questionsAndAnswers(tree.line()) == ['Name a colour', 'Blue.', 'Name an animal', 'A fox.']

        and: 'the conversation forks where "Name a fruit" was: both questions follow "Blue."'
            tree.children(fruit.parent().orElseThrow())*.text() == ['Name a fruit', 'Name an animal']
    }

    def '`oillamp ask --after` an earlier answer starts a branch there'() {
        reportInfo """
            Going back to an earlier answer and asking from there is how someone explores another
            direction. Let's consider a conversation of "Name a colour", answered "Blue.", then
            "Name a fruit". `oillamp ask --in <conversation> --after <answer>`, with the id of
            "Blue.", asks "Name a tree" from there. The conversation forks after "Blue.": its
            current line runs through the new question, and "Name a fruit" with its answer stays on
            a branch of its own.
        """
        given: 'a conversation of "Name a colour", then "Name a fruit"'
            var colourAsked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')
            assert colourAsked.succeeded()
            var colourRun = colourAsked.events().find { it instanceof LampEvent.RunFinished }
            var conversation = colourRun.conversation().orElseThrow()
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a fruit').succeeded()
            var blue = answerTo(conversation, 'Name a colour')

        when: '`oillamp ask --in <conversation> --after <id of "Blue."> "Name a tree"` is run'
            var asked = host.oillamp.run('ask', lamp.toString(), '--in', conversation, '--after', blue.id(), 'Name a tree')

        then: 'the current line runs through "Name a tree", and both questions follow "Blue."'
            asked.succeeded()
            var tree = Lamp.conversation(lamp, conversation).orElseThrow()
            questionsAndAnswers(tree.line()) == ['Name a colour', 'Blue.', 'Name a tree', 'An oak.']
            tree.children(blue.id())*.text() == ['Name a fruit', 'Name a tree']
    }

    def '`oillamp ask` refuses a place in a conversation that does not exist, or where a question does not fit'() {
        reportInfo """
            `oillamp ask` names where a question goes with `--in`, a conversation's id, and with
            `--after` or `--instead-of`, the id of an entry in it. Oillamp refuses five wrong
            places. A conversation that is not there, and an entry that is not in the
            conversation, are both refused with `OIL-CONVERSATION-001`. `--after` a question, where
            only an answer fits, and `--instead-of` an answer, where only a question fits, are
            refused with a problem that says which option to give instead. An entry without `--in`
            is refused too.

            Oillamp refuses before it saves a snapshot to the lamp's history or wakes the agent, so
            the scenario sees no new prompt and no new snapshot.
        """
        given: 'a conversation of "Name a colour", answered "Blue."'
            var colourAsked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')
            assert colourAsked.succeeded()
            var colourRun = colourAsked.events().find { it instanceof LampEvent.RunFinished }
            var conversation = colourRun.conversation().orElseThrow()
            var colour = question(conversation, 'Name a colour')
            var blue = answerTo(conversation, 'Name a colour')
            var snapshotsInTheHistory = {
                var listed = host.oillamp.run('history', lamp.toString())
                assert listed.succeeded()
                listed.events().find { it instanceof LampEvent.History }.snapshots().size()
            }
            var snapshots = snapshotsInTheHistory()
            var asked = prompts.size()

        when: 'each of the five wrong places is given to `oillamp ask`'
            var noConversation = host.oillamp.run('ask', lamp.toString(), '--in', 'ffffffff', 'x')
            var noEntry = host.oillamp.run('ask', lamp.toString(), '--in', conversation, '--after', 'deadbeef', 'x')
            var afterAQuestion = host.oillamp.run('ask', lamp.toString(), '--in', conversation, '--after', colour.id(), 'x')
            var insteadOfAnAnswer = host.oillamp.run('ask', lamp.toString(), '--in', conversation, '--instead-of', blue.id(), 'x')
            var entryWithoutConversation = host.oillamp.run('ask', lamp.toString(), '--after', blue.id(), 'x')

        then: 'each is refused, and the two that do not fit name the option to give instead'
            noConversation.reported('OIL-CONVERSATION-001')
            noEntry.reported('OIL-CONVERSATION-001')
            afterAQuestion.problems().first().whatHappened().contains('give --instead-of ' + colour.id())
            insteadOfAnAnswer.problems().first().whatHappened().contains('give --after ' + blue.id())
            !entryWithoutConversation.succeeded()

        and: 'the agent received no new prompt, and the history has no new snapshot'
            prompts.size() == asked
            snapshotsInTheHistory() == snapshots
    }

    def 'A `LampPrompt` that could never be asked cannot be made'() {
        reportInfo """
            An application built on oillamp's Java API builds its questions in code, as `LampPrompt`
            values. The constructor throws `IllegalArgumentException` for one that could never be
            asked, such as one that names an entry but no conversation to find it in, or a blank
            question, so that the mistake shows where it is made, not later in the session.
        """
        when: 'a `LampPrompt` names an entry but no conversation'
            new LampPrompt('x', Optional.empty(), Optional.of('a1'), Optional.empty())

        then:
            thrown(IllegalArgumentException)

        when: '`LampPrompt.fresh` is given a blank question'
            LampPrompt.fresh('  ')

        then:
            thrown(IllegalArgumentException)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    /** The lamp, as an application sees it. The session holding it was started from the command line. */
    private LampDirectory running() {
        Lamp.at(lamp).launchedBy(host.launcher)
    }

    private LampConversation.Entry question(String conversation, String text) {
        Lamp.conversation(lamp, conversation).orElseThrow().entries().find { it.kind() == Kind.MESSAGE_TO_AGENT && it.text() == text }
    }

    private LampConversation.Entry answerTo(String conversation, String text) {
        var tree = Lamp.conversation(lamp, conversation).orElseThrow()
        tree.children(question(conversation, text).id()).first()
    }

    private static List<String> questionsAndAnswers(Iterable<LampConversation.Entry> line) {
        line.findAll { it.kind() in [Kind.MESSAGE_TO_AGENT, Kind.MESSAGE_FROM_AGENT] }*.text()
    }

    private void startASession() {
        host.machine { it.windowsStayOpenFor(Duration.ofSeconds(110)) }
        var oillamp = host.oillamp.observedBy { reported.add(it) }
        session = Thread.start { oillamp.run('at', lamp.toString()) }
        var deadline = System.currentTimeMillis() + 30_000
        while (!reported.any { it instanceof LampEvent.Summary && it.title() == 'your session is up' }) {
            assert System.currentTimeMillis() < deadline : 'the session never came up'
            Thread.sleep(50)
        }
    }
}
