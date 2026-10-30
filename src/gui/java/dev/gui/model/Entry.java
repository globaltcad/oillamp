package dev.gui.model;

import java.util.UUID;

import sprouts.HasId;

/// One entry of a conversation with a genie: something the user wrote, something the genie
/// answered, a tool it used, a file it handed over, or a notice from the app.
///
/// @param id    stable while the entry changes, so the chat keeps one row per entry as an answer
///              streams in
/// @param kind  who or what the entry is from
/// @param title a short heading: the tool's name, the file's name, or nothing
/// @param text  the message, or what a tool does, such as the command it runs
/// @param detail what a tool printed, or nothing
/// @param ref   pi's id for a tool call, which the call's result refers to; for a question of the
///              user's, pi's id for it, once known; or nothing
/// @param state whether it is still being written, done, or failed
/// @param expanded whether its detail, such as what a tool printed, is shown in the chat
/// @param beforeTools for an answer, whether the genie went on to use tools after it: it said what
///              it was doing, rather than answering
/// @param model for an answer, or a failed one, the model that wrote it, as the service names it,
///              such as `azure/gpt-6.1-sol`; otherwise, or when not known, nothing
public record Entry(
    UUID id,
    Kind kind,
    String title,
    String text,
    String detail,
    String ref,
    State state,
    boolean expanded,
    boolean beforeTools,
    String model
) implements HasId<UUID> {

    public enum Kind { YOU, GENIE, THINKING, TOOL, FILE, NOTICE }

    public enum State { WRITING, DONE, FAILED }

    public static Entry of(Kind kind, String text) {
        return new Entry(UUID.randomUUID(), kind, "", text, "", "", State.DONE, false, false, "");
    }

    public Entry withTitle(String title)   { return new Entry(id, kind, title, text, detail, ref, state, expanded, beforeTools, model); }
    public Entry withText(String text)     { return new Entry(id, kind, title, text, detail, ref, state, expanded, beforeTools, model); }
    public Entry withDetail(String detail) { return new Entry(id, kind, title, text, detail, ref, state, expanded, beforeTools, model); }
    public Entry withRef(String ref)       { return new Entry(id, kind, title, text, detail, ref, state, expanded, beforeTools, model); }
    public Entry withState(State state)    { return new Entry(id, kind, title, text, detail, ref, state, expanded, beforeTools, model); }

    public Entry withExpanded(boolean expanded) { return new Entry(id, kind, title, text, detail, ref, state, expanded, beforeTools, model); }

    public Entry withBeforeTools(boolean beforeTools) { return new Entry(id, kind, title, text, detail, ref, state, expanded, beforeTools, model); }

    public Entry withModel(String model) { return new Entry(id, kind, title, text, detail, ref, state, expanded, beforeTools, model); }

    /// What the chat says about the entry when the user points at it: for an answer, which model
    /// wrote it. Nothing when that is not known, so that nothing is shown.
    public String writtenBy(Lang lang) {
        return kind == Kind.GENIE && !model.isBlank() ? lang.CHAT().WRITTEN_BY(model) : "";
    }

    public boolean isWriting() { return state == State.WRITING; }

    public boolean isFailed() { return state == State.FAILED; }

    /// An answer the genie has started but not yet put a word into: it is thinking.
    public boolean isEmptyAnswer() { return kind == Kind.GENIE && text.isEmpty(); }
}
