package dev.gui.genie;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import dev.gui.desktop.Desktop;
import dev.gui.model.Settings;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;
import dev.lamp.LampFailedException;
import dev.lamp.LampPrompt;
import dev.lamp.LampRun;
import dev.lamp.LampDirectory;

/// Lights a genie's lamp: starts its sandbox and waits until it runs.
///
/// [LampLighterImpl] does this with oillamp's [dev.lamp.Lamp].
@FunctionalInterface
public interface Lighter {

    /// Starts the lamp in `directory` and returns once its sandbox runs.
    ///
    /// @param name     the genie's name, which it is told in its instructions
    /// @param settings the model service and the model the genie answers with
    /// @param key      the model key, which the lamp's engine keeps on the host
    /// @param progress receives what the lamp is doing, in a few words, while it starts
    /// @param events   receives every event of the lamp's session, from any thread, including
    ///                 the progress of the agent's runs
    /// @throws IOException when the lamp did not start; the message says why, for the user
    Lit light(Path directory, String name, Settings settings, String key, Consumer<String> progress,
              Consumer<LampEvent> events)
            throws IOException, InterruptedException;

    /// The lamp in `directory`, not lit, for what works without its sandbox: its schedule and
    /// its history.
    default LampDirectory unlit(Path directory) {
        return Lamp.at(directory);
    }

    /// Whether the lamp in `directory` is lit already: left running when the app last closed.
    default boolean isLit(Path directory) {
        return unlit(directory).isRunning();
    }

    /// Joins the lamp in `directory`, lit already, and returns once its sandbox is reached.
    ///
    /// @param events receives every event of the lamp's session, as for [#light]: first what the
    ///               session is doing now, ending with a [LampEvent.AgentStatus], then the news
    /// @return empty when no lamp is lit there, or it cannot be reached
    default Optional<Lit> join(Path directory, Consumer<LampEvent> events) throws IOException, InterruptedException {
        return Optional.empty();
    }

    /// A lamp whose sandbox runs.
    interface Lit extends AutoCloseable {

        /// Runs a command in the sandbox, as the agent, with the agent's environment.
        Process exec(String... command) throws IOException;

        /// The sandbox's desktop.
        Desktop desktop();

        /// See [Lamp#send].
        LampRun send(LampPrompt question) throws IOException, InterruptedException, LampFailedException;

        /// See [Lamp#cancel(String)].
        void cancel(String run) throws IOException, InterruptedException, LampFailedException;

        /// See [Lamp#useModel]: the genie answers with `model` from its next run on.
        void useModel(String model) throws IOException, InterruptedException, LampFailedException;

        /// See [Lamp#decidePermission]: the user allows or refuses a request the lamp asked them about.
        void decidePermission(String request, boolean allowed) throws IOException, InterruptedException, LampFailedException;

        /// Ends the sandbox, and returns once it is gone.
        @Override void close();

        /// Lets the sandbox run on after the app has closed, until the genie is put to sleep.
        /// [Lighter#join] finds it again. A lamp that cannot be left running is put out instead,
        /// so no sandbox is left running by mistake.
        default void leaveRunning() { close(); }
    }
}
