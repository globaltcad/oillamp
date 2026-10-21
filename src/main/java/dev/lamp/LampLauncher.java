package dev.lamp;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/// How a lamp starts the engine's process. The default runs `java` from the application's own
/// JVM, with its classpath and the engine's main class.
///
/// Given a replacement with [LampDirectory#launchedBy], for tests, or for an application that
/// runs the engine some other way.
@FunctionalInterface
public interface LampLauncher {
    /// Starts the engine, with `arguments` after its main class and `environment` added to the
    /// environment it inherits. The lamp keeps the process's standard input open for as long as
    /// it wants the session, and reads the engine's events from its standard output.
    Process launch(List<String> arguments, Map<String, String> environment) throws IOException;
}
