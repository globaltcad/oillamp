package dev.lamp;

/// How a tool call is summed up for a list of what the agent did: the `summary()` of
/// [LampAgentProgress.ToolStarted] and of [LampConversation.ToolCall], worked out from their input.
final class InternalToolSummaryUtil {

    private InternalToolSummaryUtil() {}

    /// How long a summary may be, in characters.
    private static final int LENGTH = 160;

    /// `input` on one line, each line break with the space around it shown as " ⏎ ", and cut
    /// short with "…" when it is longer than [#LENGTH].
    static String summaryOf(String input) {
        String line = input.strip().replaceAll("\\s*\\n\\s*", " ⏎ ");
        return line.length() <= LENGTH ? line : line.substring(0, LENGTH - 1) + "…";
    }
}
