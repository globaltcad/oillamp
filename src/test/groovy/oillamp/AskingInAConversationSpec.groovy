package oillamp

import dev.lamp.Lamp
import dev.lamp.LampConversation.Kind
import dev.lamp.LampPrompt
import dev.lamp.LampEvent
import dev.lamp.LampRunOutcome
import dev.lamp.LampDirectory
import dev.lamp.LampConversation
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 *  Asking the agent in one of its conversations: {@code oillamp ask --in}, {@code --after},
 *  {@code --instead-of}, and {@code LampPrompt}.
 *
 *  <p>A chat is more than a string of questions each starting afresh. A person continues a
 *  conversation, goes back to an earlier answer and asks from there, or asks something else
 *  instead of an earlier question. pi keeps every conversation as a tree, so none of that loses
 *  anything: each is a new branch. The session, which holds the agent, opens the conversation,
 *  moves to the right entry, and asks.
 *
 *  <p>The stand-in pi here keeps its conversations the way pi does, in files in the agent's home,
 *  so each scenario reads the result back through {@code Lamp.conversations}, as an application
 *  would: a program built on oillamp, such as a desktop app, that uses oillamp's Java API in
 *  {@code dev.lamp}.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AskingInAConversationSpec extends Specification {

    @TempDir Path tmp
    @Subject ScenarioHost host

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
        // With the schedule on, so that a question that continues a conversation is seen to go
        // without the notes and recent runs a new one gets.
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

    def 'A question asked without naming a conversation starts a new one, and the run says which'() {
        reportInfo """
            Let's consider `oillamp ask` given only a question, with no `--in` to name a
            conversation. The session starts a conversation of its own for it. The event that ends
            the run, `RunFinished`, carries that conversation's id, so that an application (a
            program built on oillamp, such as a desktop app, that uses oillamp's Java API) can
            find it with `Lamp.conversation` and show it. The conversation takes its first
            question as its title.
        """
        when:
            var colourAsked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')

        then:
            colourAsked.succeeded()
            var asked = colourAsked.events().find { it instanceof LampEvent.RunFinished }
            asked.outcome() == LampRunOutcome.FINISHED
            var conversation = Lamp.conversation(lamp, asked.conversation().orElseThrow()).orElseThrow()
            conversation.title() == 'Name a colour'
            questionsAndAnswers(conversation.line()) == ['Name a colour', 'Blue.']
    }

    def 'Asking in a conversation adds the question after its last answer, exactly as written'() {
        reportInfo """
            The next question in a chat goes after the last answer. Here it names the conversation
            with `--in` and only the first 13 characters of its id. What a person asks reaches the
            agent as they wrote it, never wrapped in anything, because that is what the chat shows
            as their message. Only a scheduled job's run gets the agent's notes put in front of
            its prompt; this lamp has its schedule on, so the scenario sees that a question asked
            in a conversation does not.
        """
        given:
            var colourAsked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')
            assert colourAsked.succeeded()
            var colourRun = colourAsked.events().find { it instanceof LampEvent.RunFinished }
            var first = colourRun.conversation().orElseThrow()

        when:
            var second = host.oillamp.run('ask', lamp.toString(), '--in', first.take(13), 'Name a fruit')

        then:
            second.succeeded()
            second.console().contains('An apple.')
            prompts == ['Name a colour', 'Name a fruit']

        and: 'it is the same conversation, one question longer'
            Lamp.conversations(lamp).size() == 1
            questionsAndAnswers(Lamp.conversation(lamp, first).orElseThrow().line()) ==
                    ['Name a colour', 'Blue.', 'Name a fruit', 'An apple.']
    }

    def 'Asking instead of an earlier question keeps the old one as a branch of its own'() {
        reportInfo """
            A person who regrets a question asks something else in its place, here through the
            Java API with `LampPrompt.insteadOf`. Nothing is lost: pi keeps a conversation as a
            tree, so the old question and everything after it stay on a branch of their own, and
            the conversation's current line now runs through the new question.
        """
        given:
            var colourAsked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')
            assert colourAsked.succeeded()
            var colourRun = colourAsked.events().find { it instanceof LampEvent.RunFinished }
            var conversation = colourRun.conversation().orElseThrow()
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a fruit').succeeded()
            var fruit = question(conversation, 'Name a fruit')

        when:
            var asked = running().ask(LampPrompt.insteadOf(conversation, fruit.id(), 'Name an animal'))

        then:
            asked.outcome() == LampRunOutcome.FINISHED
            var tree = Lamp.conversation(lamp, conversation).orElseThrow()
            questionsAndAnswers(tree.line()) == ['Name a colour', 'Blue.', 'Name an animal', 'A fox.']

        and: 'the conversation forks where the old question was'
            tree.children(fruit.parent().orElseThrow())*.text() == ['Name a fruit', 'Name an animal']
    }

    def 'Continuing after an earlier answer starts a branch there'() {
        reportInfo """
            Going back to an earlier answer and asking from there is how a person explores
            another direction. The conversation forks after that answer; the later questions and
            answers stay on their own branch.
        """
        given:
            var colourAsked = host.oillamp.run('ask', lamp.toString(), 'Name a colour')
            assert colourAsked.succeeded()
            var colourRun = colourAsked.events().find { it instanceof LampEvent.RunFinished }
            var conversation = colourRun.conversation().orElseThrow()
            assert host.oillamp.run('ask', lamp.toString(), '--in', conversation, 'Name a fruit').succeeded()
            var blue = answerTo(conversation, 'Name a colour')

        when:
            var asked = host.oillamp.run('ask', lamp.toString(), '--in', conversation, '--after', blue.id(), 'Name a tree')

        then:
            asked.succeeded()
            var tree = Lamp.conversation(lamp, conversation).orElseThrow()
            questionsAndAnswers(tree.line()) == ['Name a colour', 'Blue.', 'Name a tree', 'An oak.']
            tree.children(blue.id())*.text() == ['Name a fruit', 'Name a tree']
    }

    def 'Oillamp refuses a question aimed at a place in a conversation that does not exist or does not fit'() {
        reportInfo """
            `oillamp ask` names where a question goes with `--in` (a conversation's id) and
            `--after` or `--instead-of` (an entry's id in it). Oillamp refuses five wrong places:
            a conversation that is not there, an entry that is not in it (both
            `OIL-CONVERSATION-001`), `--after` a question, where only an answer fits,
            `--instead-of` an answer, where only a question fits, and an entry without `--in`. For
            the two that do not fit, the problem says which option to give instead. Oillamp
            refuses before it saves a snapshot to the lamp's history or wakes the agent, so the
            scenario sees no new prompt and no new snapshot.
        """
        given:
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

        when:
            var noConversation = host.oillamp.run('ask', lamp.toString(), '--in', 'ffffffff', 'x')
            var noEntry = host.oillamp.run('ask', lamp.toString(), '--in', conversation, '--after', 'deadbeef', 'x')
            var afterAQuestion = host.oillamp.run('ask', lamp.toString(), '--in', conversation, '--after', colour.id(), 'x')
            var insteadOfAnAnswer = host.oillamp.run('ask', lamp.toString(), '--in', conversation, '--instead-of', blue.id(), 'x')
            var entryWithoutConversation = host.oillamp.run('ask', lamp.toString(), '--after', blue.id(), 'x')

        then:
            noConversation.reported('OIL-CONVERSATION-001')
            noEntry.reported('OIL-CONVERSATION-001')
            afterAQuestion.problems().first().whatHappened().contains('give --instead-of ' + colour.id())
            insteadOfAnAnswer.problems().first().whatHappened().contains('give --after ' + blue.id())
            !entryWithoutConversation.succeeded()

        and:
            prompts.size() == asked
            snapshotsInTheHistory() == snapshots
    }

    def 'A `LampPrompt` that could never be asked cannot be made'() {
        reportInfo """
            An application, a program built on oillamp's Java API, builds its questions in code as
            `LampPrompt` values. Its constructor throws `IllegalArgumentException` for one that
            could never be asked, such as an entry with no conversation to be in, or a blank
            question, so the mistake shows where it is made, not later in the session.
        """
        when:
            new LampPrompt('x', Optional.empty(), Optional.of('a1'), Optional.empty())

        then:
            thrown(IllegalArgumentException)

        when:
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
