package gui

import dev.gui.model.ChatSearch
import spock.lang.Narrative
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title
import sprouts.Tuple

@Title('Searching a conversation')
@Narrative('''
A user who looks for something said earlier in a long conversation presses Ctrl+F in the
chat and types what they look for. Genies keeps what they typed, and every place it was
found, in a `ChatSearch`. The chat hands it the text of each entry as the chat shows it, one
`ChatSearch.Text` per entry in the order of the conversation, with an empty text for an
entry it does not search, such as a folded thought. One of the matches is in focus: the
chat scrolls to it and marks it apart from the others, and shows the user its position
among them, such as "2 / 4".
''')
@Subject(ChatSearch)
class Searching_A_Conversation_Spec extends Specification {

    static final UUID QUESTION = UUID.fromString('00000000-0000-0000-0000-000000000001')
    static final UUID THOUGHT  = UUID.fromString('00000000-0000-0000-0000-000000000002')
    static final UUID ANSWER   = UUID.fromString('00000000-0000-0000-0000-000000000003')

    static Tuple<ChatSearch.Text> texts(Map<UUID, String> byEntry) {
        Tuple.of(ChatSearch.Text, byEntry.collect { entry, text -> new ChatSearch.Text(entry, text) })
    }

    def 'ChatSearch.find finds every place of the query in the order of the conversation, ignoring case'() {
        reportInfo """
            Let's consider a conversation of a question, a folded thought and an answer. The
            user looks for "build". The question holds it twice, once at its start as "Build";
            the folded thought has an empty text, so nothing in it is found; the answer holds
            it once, as "BUILD". `ChatSearch.find` from `ChatSearch.START` finds three matches,
            each with the entry it is in and its first and last-plus-one character, and puts
            the first one in focus.
        """
        given: 'the texts the chat shows: a question, a folded thought and an answer'
            var shown = texts([
                (QUESTION): 'Build the image, then build it again.',
                (THOUGHT) : '',
                (ANSWER)  : 'BUILD SUCCESSFUL'
            ])

        when: 'the user looks for "build" from the start of the conversation'
            var search = ChatSearch.find('build', shown, ChatSearch.START)

        then: 'the three places are found, in order, whatever the case of their letters'
            search.matches().toList() == [
                new ChatSearch.Match(QUESTION, 0, 5),
                new ChatSearch.Match(QUESTION, 22, 27),
                new ChatSearch.Match(ANSWER, 0, 5)
            ]

        and: 'the first of the three is in focus'
            search.focused() == Optional.of(new ChatSearch.Match(QUESTION, 0, 5))
            search.position() == 1
    }

    def 'A search from a spot in the conversation puts the first match after that spot in focus, and goes round when there is none'() {
        reportInfo """
            When the user opens the search, the chat starts it at the entry it shows, so that the
            match in focus is the one nearest to what the user reads, not the first one of a
            long conversation. `ChatSearch.find` takes that place as a `ChatSearch.Spot`: an
            entry and a character in its text. The match in focus is the first one at that
            spot or after it. When no match comes after it, the search goes round, as a search
            in a web browser does, and the first match of the conversation is in focus.
        """
        given:
            var shown = texts([
                (QUESTION): 'Build the image, then build it again.',
                (ANSWER)  : 'BUILD SUCCESSFUL'
            ])

        expect: 'from the start of the answer, its own match is in focus, the third of three'
            ChatSearch.find('build', shown, new ChatSearch.Spot(ANSWER, 0)).position() == 3

        and: 'from the middle of the question, its second match is in focus'
            ChatSearch.find('build', shown, new ChatSearch.Spot(QUESTION, 10)).position() == 2

        and: 'from past the answer\'s only match, the search goes round to the first match'
            ChatSearch.find('build', shown, new ChatSearch.Spot(ANSWER, 1)).position() == 1
    }

    def 'ChatSearch.next and ChatSearch.previous go from match to match, and round at either end'() {
        reportInfo """
            The user goes from match to match with Return and Shift+Return, or the arrows beside
            the search field. `ChatSearch.next` puts the match after the one in focus in focus,
            and `ChatSearch.previous` the one before it. After the last match comes the first
            again, and before the first the last.
        """
        given: 'three matches, the first one in focus'
            var search = ChatSearch.find('build', texts([
                (QUESTION): 'Build the image, then build it again.',
                (ANSWER)  : 'BUILD SUCCESSFUL'
            ]), ChatSearch.START)

        expect: 'going on from the first reaches the second, the third, and the first again'
            search.next().position() == 2
            search.next().next().position() == 3
            search.next().next().next().position() == 1

        and: 'going back from the first reaches the last'
            search.previous().position() == 3
            search.previous().focused() == Optional.of(new ChatSearch.Match(ANSWER, 0, 5))
    }

    def 'Typing more letters keeps the match in focus while it still matches'() {
        reportInfo """
            The chat searches again on every letter the user types. Let's consider a user who
            went on to the second place of "bu" in a question, and then types an "i", so that
            they look for "bui". `ChatSearch.typed` goes on from the match in focus, rather than
            from the place the chat shows: the second place still matches "bui", so it stays in
            focus, and the chat does not jump back to the first one.
        """
        given: 'the second of the places of "bu" in focus'
            var shown = texts([(QUESTION): 'Build the image, then build it again.'])
            var search = ChatSearch.find('bu', shown, ChatSearch.START).next()

        when: 'the user types "bui"'
            var typed = search.typed('bui', shown, ChatSearch.START)

        then: 'the second place is still in focus'
            typed.focused() == Optional.of(new ChatSearch.Match(QUESTION, 22, 25))
    }

    def 'An answer that streams in keeps the match in focus where it is, while the count grows'() {
        reportInfo """
            The genie may still write while the user searches. Each time an entry's text changes,
            the chat hands `ChatSearch.in` the texts anew, and it searches them for the same
            query. Let's consider the user had the second of two matches in focus when the answer
            below it grows by two more matches. The match in focus stays the same, still the
            second, now of four, so the chat does not move away from what the user reads.
        """
        given: 'the second of two matches in focus'
            var search = ChatSearch.find('build', texts([
                (QUESTION): 'Build the image.',
                (ANSWER)  : 'BUILD SUCCESSFUL'
            ]), ChatSearch.START).next()

        when: 'the answer grows by two more matches'
            var grown = search.in(texts([
                (QUESTION): 'Build the image.',
                (ANSWER)  : 'BUILD SUCCESSFUL. Now the build of the docs, and a build of the site.'
            ]))

        then: 'the same match is in focus, the second of four'
            grown.focused() == Optional.of(new ChatSearch.Match(ANSWER, 0, 5))
            grown.position() == 2
            grown.matches().size() == 4
    }

    def 'Folding away the thought with the match in focus puts the next match after it in focus'() {
        reportInfo """
            The chat searches a thought only while the user has it unfolded. Let's consider the
            match in focus is in an unfolded thought, and the user folds that thought away, so
            the chat now hands `ChatSearch.in` an empty text for it. The match in focus is gone;
            the next match after it, in the answer below the thought, is in focus instead.
        """
        given: 'the match in the unfolded thought in focus'
            var search = ChatSearch.find('image', texts([
                (QUESTION): 'Build the image.',
                (THOUGHT) : 'The image needs a newer base.',
                (ANSWER)  : 'The image is built.'
            ]), new ChatSearch.Spot(THOUGHT, 0))

        when: 'the user folds the thought away'
            var folded = search.in(texts([
                (QUESTION): 'Build the image.',
                (THOUGHT) : '',
                (ANSWER)  : 'The image is built.'
            ]))

        then: 'the match in the answer is in focus, the second of two'
            folded.focused() == Optional.of(new ChatSearch.Match(ANSWER, 4, 9))
            folded.position() == 2
    }

    def 'ChatSearch.byEntry hands each entry the matches in its own text'() {
        reportInfo """
            Each entry of the chat marks the matches in its own text. In a long conversation
            searched for a single letter, there are many of them, and they change with every
            word of an answer that streams in. So rather than every entry picking its own
            matches out of all of them, `ChatSearch.byEntry` hands each entry with any its own
            matches, in their order. An entry without one is not in it.
        """
        given: 'a question with two places of "build", a folded thought, and an answer with one'
            var search = ChatSearch.find('build', texts([
                (QUESTION): 'Build the image, then build it again.',
                (THOUGHT) : '',
                (ANSWER)  : 'BUILD SUCCESSFUL'
            ]), ChatSearch.START)

        expect: 'the question and the answer each have their own matches, the thought none'
            search.byEntry() == [
                (QUESTION): Tuple.of(ChatSearch.Match, new ChatSearch.Match(QUESTION, 0, 5), new ChatSearch.Match(QUESTION, 22, 27)),
                (ANSWER)  : Tuple.of(ChatSearch.Match, new ChatSearch.Match(ANSWER, 0, 5))
            ]
    }

    def 'A blank query finds nothing, and nothing is in focus'() {
        reportInfo """
            While the search field is empty, or holds only spaces, there is nothing to look for:
            `ChatSearch.find` finds no match, `ChatSearch.focused` is empty, and the position
            is 0, so the chat shows "0 / 0". Going to the next or previous match changes nothing.
        """
        given:
            var search = ChatSearch.find('  ', texts([(QUESTION): 'Build  the image.']), ChatSearch.START)

        expect:
            search.matches().isEmpty()
            !search.focused().isPresent()
            search.position() == 0
            search.next() == search
            search.previous() == search
    }
}
