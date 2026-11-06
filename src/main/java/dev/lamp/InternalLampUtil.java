package dev.lamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sprouts.Tuple;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

class InternalLampUtil {

    private static final Logger LOG = LoggerFactory.getLogger(InternalLampUtil.class);

    private InternalLampUtil() {}


    static LampProblem internal(String what) {
        return new LampProblem(new LampProblem.Code("OIL-INTERNAL-001"), LampProblem.Severity.ERROR,
                "Unexpected internal error (please report)", what,
                "this is a bug in oillamp, not something you did wrong",
                Tuple.of(LampProblem.Evidence.class), Tuple.of(LampProblem.Fix.class), Optional.empty());
    }

    /// What an engine that failed without reporting a problem leaves behind: its exit code, and
    /// what it wrote to its error output. The usual cause is that it never got as far as
    /// reporting anything, such as a Java runtime that could not be found or did not start.
    static LampProblem engineEnded(int code, String errorOutput) {
        String last = errorOutput.lines().reduce((_, later) -> later).orElse("").strip();
        Tuple<LampProblem.Evidence> evidence = Tuple.of(LampProblem.Evidence.class,
                new LampProblem.Evidence.Value("exit code", String.valueOf(code)));
        if (!errorOutput.isBlank())
            evidence = evidence.add(new LampProblem.Evidence.Excerpt("the engine's error output", errorOutput));
        return new LampProblem(new LampProblem.Code("OIL-ENGINE-001"), LampProblem.Severity.ERROR,
                "oillamp's engine ended without saying why",
                "it exited with code " + code + (last.isEmpty()
                        ? " and wrote nothing to its error output"
                        : ", and the last thing it wrote to its error output was: " + last),
                "the lamp did not do what was asked; what the engine wrote is the best clue to why",
                evidence, Tuple.of(LampProblem.Fix.class), Optional.empty());
    }

    /// Whether an engine that exited with `code` failed without telling why: it did not succeed,
    /// was not interrupted, and reported no [LampEvent.Failure]. [LampExitStatus#USAGE] is left
    /// out, as a command not confirmed with `--yes` exits with it after saying what it would do.
    static boolean endedUnexplained(int code, boolean failureReported) {
        return !failureReported
                && code != LampExitStatus.SUCCESS.code()
                && code != LampExitStatus.USAGE.code()
                && code != LampExitStatus.INTERRUPTED.code();
    }

    /// The last lines a process wrote to its error output. They are read on a thread of their
    /// own, so that the process never stops on a full pipe, and logged as warnings, so that the
    /// application's own logging shows what the engine wrote.
    static final class ErrorOutput {

        /// How many of the last lines are kept, and how long each may be.
        static final int MOST_LINES = 40;
        static final int LONGEST_LINE = 500;
        /// How long [#text()] waits for the last lines once the process has ended. A process the
        /// engine started may still hold the pipe open; the lines read by then are enough.
        static final Duration LAST_LINES = Duration.ofSeconds(1);

        private final ArrayDeque<String> lines = new ArrayDeque<>();
        private final Thread reader;

        ErrorOutput(Process process, String name) {
            InputStream stream = process.getErrorStream();
            reader = Thread.ofVirtual().name(name).start(() -> read(stream));
        }

        private void read(InputStream stream) {
            try (var input = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    LOG.warn("engine: {}", line);
                    String cut = line.length() > LONGEST_LINE ? line.substring(0, LONGEST_LINE) + " …" : line;
                    synchronized (lines) {
                        lines.addLast(cut);
                        if (lines.size() > MOST_LINES) lines.removeFirst();
                    }
                }
            } catch (IOException closed) {
                // The pipe broke off; the lines read before are kept.
            }
        }

        /// The last lines, one per line, after waiting a moment for the process's last words.
        String text() {
            try {
                reader.join(LAST_LINES);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            synchronized (lines) {
                return String.join("\n", lines);
            }
        }
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

    /// Like [#runToEnd(LampLauncher , List , String...)], but also sets the environment variables in
    /// `environment` for the engine process. Used to pass texts, such as host-access
    /// requirements, that must not appear on the command line, where every user of the machine
    /// can read them in the process list.
    static Lamp.Ran runToEnd(
        final LampLauncher launcher,
        final List<Consumer<LampEvent>> listeners,
        final Map<String, String> environment,
        final String... command
    ) throws IOException, InterruptedException {
        List<String> arguments = new ArrayList<>(List.of(command));
        // Before `--`, after which the engine takes every argument as text, such as a prompt.
        int literal = arguments.indexOf("--");
        arguments.add(literal < 0 ? arguments.size() : literal, "--embedded");
        Process engine = launcher.launch(List.copyOf(arguments), environment);
        ErrorOutput errors = new ErrorOutput(engine, "lamp-engine-errors");
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
        int code = engine.waitFor();
        if (endedUnexplained(code, events.stream().anyMatch(LampEvent.Failure.class::isInstance))) {
            LampEvent failure = new LampEvent.Failure(engineEnded(code, errors.text()));
            events.add(failure);
            passToListeners(listeners, failure);
        }
        return new Lamp.Ran(LampExitStatus.ofCode(code).orElse(LampExitStatus.ERROR), List.copyOf(events));
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
