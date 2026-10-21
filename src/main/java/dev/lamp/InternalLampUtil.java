package dev.lamp;

import sprouts.Tuple;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

class InternalLampUtil {

    private InternalLampUtil() {}


    static LampProblem internal(String what) {
        return new LampProblem(new LampProblem.Code("OIL-INTERNAL-001"), LampProblem.Severity.ERROR,
                "Unexpected internal error (please report)", what,
                "this is a bug in oillamp, not something you did wrong",
                Tuple.of(LampProblem.Evidence.class), Tuple.of(LampProblem.Fix.class), Optional.empty());
    }

    static String[] askCommand(Path directory, LampPrompt question, String... options) {
        List<String> command = new ArrayList<>(List.of("ask", directory.toString()));
        question.conversation().ifPresent(id -> command.addAll(List.of("--in", id)));
        question.after().ifPresent(id -> command.addAll(List.of("--after", id)));
        question.insteadOf().ifPresent(id -> command.addAll(List.of("--instead-of", id)));
        command.addAll(List.of(options));
        command.addAll(List.of("--", question.prompt()));
        return command.toArray(String[]::new);
    }

    /// Like [#runToEnd(Lamp.Launcher , List , String...)], but also sets the environment variables in
    /// `environment` for the engine process. Used to pass texts, such as host-access
    /// requirements, that must not appear on the command line, where every user of the machine
    /// can read them in the process list.
    static Lamp.Ran runToEnd(
        final Lamp.Launcher launcher,
        final List<Consumer<LampEvent>> listeners,
        final Map<String, String> environment,
        final String... command
    ) throws IOException, InterruptedException {
        List<String> arguments = new ArrayList<>(List.of(command));
        // Before `--`, after which the engine takes every argument as text, such as a prompt.
        int literal = arguments.indexOf("--");
        arguments.add(literal < 0 ? arguments.size() : literal, "--embedded");
        Process engine = launcher.launch(List.copyOf(arguments), environment);
        engine.getOutputStream().close();
        List<LampEvent> events = new ArrayList<>();
        try (
            var output = new BufferedReader(new InputStreamReader(engine.getInputStream(), StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = output.readLine()) != null)
                LampEvent.fromJson(line).ifPresent(event -> {
                    events.add(event);
                    passToListeners(listeners, event);
                });
        }
        return new Lamp.Ran(LampExitStatus.ofCode(engine.waitFor()).orElse(LampExitStatus.ERROR), List.copyOf(events));
    }

    /// Gives `event` to each listener. One that throws does not keep it from the others.
    static void passToListeners(List<Consumer<LampEvent>> listeners, LampEvent event) {
        for (Consumer<LampEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException failed) {
                reportToThreadExceptionHandler(failed);
            }
        }
    }

    /// Hands `failed` to this thread's uncaught exception handler, where the application sees
    /// it as it sees any other, and goes on.
    static void reportToThreadExceptionHandler(RuntimeException failed) {
        Thread thread = Thread.currentThread();
        thread.getUncaughtExceptionHandler().uncaughtException(thread, failed);
    }

}
