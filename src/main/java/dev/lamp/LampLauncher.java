package dev.lamp;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/// Starts the engine as a separate process, with `arguments` after the engine's main class
/// and `environment` added to the environment it inherits.
///
/// Given a replacement with [LampStartupConf#launchedBy], for tests, or for an application that
/// runs the engine some other way.
@FunctionalInterface
public interface LampLauncher {
    Process launch(List<String> arguments, Map<String, String> environment) throws IOException;
}
