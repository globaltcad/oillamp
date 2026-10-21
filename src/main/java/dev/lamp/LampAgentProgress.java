package dev.lamp;

import java.util.Optional;

/// What the agent did during a run, as pi reports it, carried by [LampEvent.RunProgress].
///
/// In one run: [Opened] once, then per message of the agent's, [Thought] and [Said] pieces and
/// an [Answered], followed by a [ToolStarted] and a [ToolFinished] for each tool it calls.
public sealed interface LampAgentProgress {
    /// The conversation the run happens in.
    record Opened(String conversation) implements LampAgentProgress {}
    /// Part of the agent's message. The parts, joined, are the text [Answered] reports.
    record Said(String text) implements LampAgentProgress {}
    /// Part of the model's thinking, for models that show it.
    record Thought(String text) implements LampAgentProgress {}
    /// A message of the agent's is complete. The last one of a run is its answer.
    ///
    /// @param text   the message; when it failed, the error
    /// @param failed the model failed, or the message was stopped
    /// @param model  the model that wrote it, or failed to, as the service names it, such as
    ///               `azure/gpt-6.1-sol`; empty when pi did not say
    record Answered(String text, boolean failed, Optional<String> model) implements LampAgentProgress {}
    /// A tool call began.
    ///
    /// @param call    the id the matching [ToolFinished] has
    /// @param summary the command for `bash`, the path for the file tools, otherwise the
    ///                arguments as JSON; one line, at most 160 characters
    record ToolStarted(String call, String tool, String summary) implements LampAgentProgress {}
    /// A tool call ended.
    ///
    /// @param failed the tool reported an error
    /// @param output the first 4,000 characters of the output
    record ToolFinished(String call, boolean failed, String output) implements LampAgentProgress {}
    /// A request to the model failed, and pi tries it again.
    ///
    /// @param attempt the try this is, from 1
    /// @param most    the tries pi makes before the run fails
    /// @param why     what the model service said
    record Retrying(int attempt, int most, String why) implements LampAgentProgress {}
}
