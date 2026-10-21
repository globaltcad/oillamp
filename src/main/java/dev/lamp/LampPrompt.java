package dev.lamp;

import java.util.Optional;

/// Something to ask the agent, and where in its conversations it goes.
///
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

    public static LampPrompt after(String conversation, String entry, String prompt) {
        return new LampPrompt(prompt, Optional.of(conversation), Optional.of(entry), Optional.empty());
    }

    public static LampPrompt insteadOf(String conversation, String entry, String prompt) {
        return new LampPrompt(prompt, Optional.of(conversation), Optional.empty(), Optional.of(entry));
    }
}
