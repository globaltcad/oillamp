package dev.lamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sprouts.Tuple;

/// One conversation the agent had, as pi keeps it in the agent's home.
///
/// A conversation is a tree of entries: every entry names the one before it. Most of the time
/// that makes a line, a question, its answer and the next question. Asking something else
/// instead of an earlier question starts a second line from the same entry, and both are kept.
/// The conversation stands at its [leaf][#leaf()]: the entry written last. Asking in it
/// continues from there, unless the question says where else it goes.
///
/// @param id       pi's id for it, such as `01a0ec69-de1c-7234-914c-b970a862c13e`. Any unique
///                 beginning of it of at least four characters names it too
/// @param name     the name it was given, such as `run-12 (job-3)`, or empty
/// @param file     where pi keeps it, relative to the agent's home
/// @param modified when its last entry was written
/// @param entries  every entry, in the order pi wrote them
public record LampConversation(
    String id,
    String name,
    String file,
    Instant started,
    Instant modified,
    Tuple<Entry> entries
) {
    /// How the engine names a job run's conversation. Written out, because this package must
    /// not depend on the engine.
    private static final Pattern JOB_RUN_NAME = Pattern.compile("run-\\d+ \\((job-\\d+)\\)");

    /// One line of pi's session file.
    ///
    /// @param id       unique within the conversation; what [LampPrompt#after] and
    ///                 [LampPrompt#insteadOf] take
    /// @param parent   the entry before this one; empty for the first. Entries with the same
    ///                 parent are where the conversation forks
    /// @param text     for a question, the message sent; for an answer, the agent's text, or the
    ///                 error when it failed; for a tool's output, the output; for a summary, the
    ///                 summary; otherwise empty
    /// @param thinking for an answer, the model's thinking; otherwise empty
    /// @param calls    for an answer, the tools it called; otherwise empty
    /// @param tool     for a tool's output, the tool's name; otherwise empty
    /// @param failed   for an answer, the model failed or was stopped; for a tool's output, the
    ///                 tool reported an error
    /// @param model    for an answer, the model that wrote it, as the service names it, such as
    ///                 `azure/gpt-6.1-sol`; otherwise empty. pi records it with every answer,
    ///                 so a conversation that changed model says which answer came from which
    public record Entry(String id, Optional<String> parent, Instant at, Kind kind, String text,
                        String thinking, Tuple<ToolCall> calls, Optional<String> tool, boolean failed,
                        Optional<String> model) {}

    public enum Kind {
        /// A message to the agent: a person's question, or a job's prompt.
        MESSAGE_TO_AGENT,
        /// A message from the agent.
        MESSAGE_FROM_AGENT,
        /// The output of one tool call.
        TOOL_OUTPUT,
        /// pi's summary of entries it took out of the model's context.
        SUMMARY,
        /// Anything else, such as pi's instructions to the model or a change of model.
        OTHER
    }

    /// A tool an answer called.
    ///
    /// @param summary the command for `bash`, the path for the file tools, otherwise the
    ///                arguments as JSON; one line, at most 160 characters
    public record ToolCall(String id, String name, String summary) {}

    /// How the conversation is shown: its name, or its first question.
    public String title() {
        if (!name.isBlank()) return oneLine(name);
        for (Entry entry : entries)
            if (entry.kind() == Kind.MESSAGE_TO_AGENT && !entry.text().isBlank()) return oneLine(entry.text());
        return "New conversation";
    }

    /// The job whose run had this conversation, such as `job-3`, or nothing for one someone
    /// asked. The engine names a job's conversation after the run and the job, `run-12 (job-3)`,
    /// and pi's file keeps nothing else that says so.
    public Optional<String> job() {
        Matcher named = JOB_RUN_NAME.matcher(name);
        return named.matches() ? Optional.of(named.group(1)) : Optional.empty();
    }

    /// The entry it stands at: the last one written. Empty for a conversation with none.
    public Optional<String> leaf() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.last().id());
    }

    public Optional<Entry> entry(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /// The line the conversation stands on: every entry from the first to the leaf.
    public Tuple<Entry> line() {
        return leaf().map(this::lineTo).orElse(Tuple.of(Entry.class));
    }

    /// Every entry from the first up to and including `id`. Empty when there is no such entry.
    public Tuple<Entry> lineTo(String id) {
        Map<String, Entry> byId = new HashMap<>();
        for (Entry entry : entries) byId.put(entry.id(), entry);
        List<Entry> line = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Entry at = byId.get(id); at != null && seen.add(at.id()); at = at.parent().map(byId::get).orElse(null))
            line.add(at);
        return Tuple.of(Entry.class, line.reversed());
    }

    /// The entries that follow `id` directly: one, or several where the conversation forks.
    public Tuple<Entry> children(String id) {
        return entries.retainIf(entry -> entry.parent().filter(id::equals).isPresent());
    }

    /// The first eighteen characters of [#id()], which is how oillamp shows a conversation.
    /// pi's ids begin with the time, so a shorter beginning is not always unique.
    public String shortId() { return id.substring(0, Math.min(18, id.length())); }

    private static String oneLine(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= 60 ? line : line.substring(0, 59) + "…";
    }
}
