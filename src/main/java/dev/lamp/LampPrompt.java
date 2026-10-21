package dev.lamp;

import java.util.Optional;

/// Something to ask the agent, and where in its conversations it goes. [Lamp#ask(LampPrompt)],
/// [Lamp#send] and [LampDirectory#ask] take one. The factory methods cover the four places it
/// can go: a new conversation, the end of one, after an entry, or instead of a question.
///
/// @param prompt       the text, as the agent reads it; never blank
/// @param conversation the conversation it continues, by its id or a unique start of it; empty
///                     for a new conversation
/// @param after        an entry of that conversation to continue after, such as an earlier
///                     answer, which starts a branch there if the conversation went on since
/// @param insteadOf    a question of that conversation to ask this instead of. The old question
///                     and what followed stay, as a branch of their own
public record LampPrompt(
    String prompt,
    Optional<String> conversation,
    Optional<String> after,
    Optional<String> insteadOf
) {
    /// @throws IllegalArgumentException when `prompt` is blank, when `after` or `insteadOf` is
    ///                                  given without `conversation`, or when both are given
    public LampPrompt {
        if (prompt.isBlank()) throw new IllegalArgumentException("a question needs something to ask");
        if ((after.isPresent() || insteadOf.isPresent()) && conversation.isEmpty())
            throw new IllegalArgumentException("an entry belongs to a conversation; name the conversation too");
        if (after.isPresent() && insteadOf.isPresent())
            throw new IllegalArgumentException("a question goes after an entry or instead of a question, not both");
    }

    /// In a new conversation.
    public static LampPrompt fresh(String prompt) {
        return new LampPrompt(prompt, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /// In `conversation`, where it stands: after the entry written last.
    public static LampPrompt in(String conversation, String prompt) {
        return new LampPrompt(prompt, Optional.of(conversation), Optional.empty(), Optional.empty());
    }

    /// In `conversation`, after `entry`, such as an earlier answer. Where the conversation went
    /// on since, this starts a branch there.
    public static LampPrompt after(String conversation, String entry, String prompt) {
        return new LampPrompt(prompt, Optional.of(conversation), Optional.of(entry), Optional.empty());
    }

    /// In `conversation`, instead of the question `entry`. The old question and what followed it
    /// stay, as a branch of their own.
    public static LampPrompt insteadOf(String conversation, String entry, String prompt) {
        return new LampPrompt(prompt, Optional.of(conversation), Optional.empty(), Optional.of(entry));
    }
}
