package dev.oillamp;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// What oillamp writes into pi's own files in the agent's home, so that pi works the way the
/// session was asked to: the default model in `~/.pi/agent/settings.json`, and the instructions
/// pi adds to its system prompt, `~/.pi/agent/APPEND_SYSTEM.md`.
///
/// pi reads both whenever it starts a conversation or opens one again, not only when its process
/// starts. A new conversation starts on the default model; an opened one goes back to the model
/// it was last on, whatever the default says, which is why [Harness] still checks the model
/// before every run.
///
/// [Harness] writes the files, through [FilesystemUtil#writeInAgentHome], which never follows a
/// link the agent may have planted. This class only decides what goes into them.
final class PiSetupUtil {

    private PiSetupUtil() {}

    /// pi's provider for the lamp's model service: the one the pi-edenai extension adds, which the
    /// image installs. Inside the sandbox it reaches whatever service the lamp uses, Eden AI or
    /// Ollama or any other, because the model relay on the host stands behind its address. So it
    /// is the only provider pi in a lamp needs, and every model is named with it.
    static final String PROVIDER = "edenai";

    /// The longest instructions a session takes, in bytes of UTF-8: far more than any useful
    /// instructions, and well below what an environment variable can hold (128 KiB on Linux).
    static final int LONGEST_INSTRUCTIONS = 64 * 1024;

    /// pi's settings, `settings`, with `model` as the default for new conversations: its
    /// `defaultProvider` and `defaultModel`. Every other setting stays as it was, such as the
    /// packages pi loads, among them the pi-edenai extension itself.
    ///
    /// @param settings what the settings file holds now, or empty when there is none
    /// @return the new file's text; empty when the file holds something other than a JSON
    ///         object, which pi cannot read either, and which oillamp then leaves alone rather
    ///         than throw away what someone wrote there
    static Optional<String> withDefaultModel(Optional<String> settings, String model) {
        ObjectNode merged;
        if (settings.isEmpty() || settings.get().isBlank()) {
            merged = JsonUtil.object();
        } else {
            Optional<JsonNode> read = JsonUtil.parse(settings.get());
            if (read.isEmpty() || !(read.get() instanceof ObjectNode object)) return Optional.empty();
            merged = object.deepCopy();
        }
        merged.put("defaultProvider", PROVIDER).put("defaultModel", model);
        return Optional.of(JsonUtil.readable(merged));
    }

    /// Why `instructions` cannot be a session's instructions, or empty if they can.
    static Optional<String> instructionsProblem(String instructions) {
        if (instructions.isBlank()) return Optional.of("the instructions are empty");
        if (instructions.getBytes(StandardCharsets.UTF_8).length > LONGEST_INSTRUCTIONS)
            return Optional.of("the instructions are longer than " + LONGEST_INSTRUCTIONS / 1024 + " KiB");
        return Optional.empty();
    }
}
