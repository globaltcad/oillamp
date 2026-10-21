package dev.lamp;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/// How a lamp starts the engine's process, and the commands [Lamp#exec] runs in its sandbox. The
/// default runs `java` from the application's own JVM, with its classpath and the engine's main
/// class, and runs the commands with the engine's ssh command, on this machine.
///
/// Given a replacement with [LampDirectory#launchedBy], for tests, or for an application that
/// runs the engine some other way.
@FunctionalInterface
public interface LampLauncher {
    /// Starts the engine, with `arguments` after its main class and `environment` added to the
    /// environment it inherits. The lamp keeps the process's standard input open for as long as
    /// it wants the session, and reads the engine's events from its standard output.
    Process launch(List<String> arguments, Map<String, String> environment) throws IOException;

    /// Starts `commandLine`, the engine's ssh command into the sandbox followed by the command
    /// to run there, as [Lamp#commandLine] makes it. The process's standard input, output and
    /// error are the command's.
    default Process exec(List<String> commandLine) throws IOException {
        return new ProcessBuilder(commandLine).start();
    }
}
