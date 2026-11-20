package dev.lamp;

import java.net.URI;

/// A web server inside a lamp's sandbox that the host's browser can open, as `[web]` in the
/// lamp's `oillamp.toml` publishes it.
///
/// The session listens on a TCP port on this machine's loopback address, picked anew each
/// session, and copies each connection to the port inside the sandbox. Nothing needs to listen
/// there yet: until a server does, a connection to the link is closed at once.
///
/// @param name        the name `web.ports` gives it, such as `opencode`
/// @param sandboxPort the port inside the sandbox, such as `4096`
/// @param hostPort    the port on 127.0.0.1 on this machine
/// @param link        what to open in a browser, such as `http://k3v7x2ab.localhost:41237/`. The
///                    host name is the lamp's id under `localhost`, which browsers send to the
///                    loopback address, so that each lamp's pages keep their own cookies
/// @param program     the command line of the program that listened on the port inside the
///                    sandbox when the session found it, such as `python3 -m http.server 8000`;
///                    known only for a port the agent opened. The agent controls that program, so
///                    this is its text, not oillamp's: one line, without control characters, at
///                    most 120 characters, ending in `…` where it was cut. Empty for a listed port,
///                    whose name says what it is for, and when the session could not tell
public record LampWebPort(String name, int sandboxPort, int hostPort, URI link, String program) {}
