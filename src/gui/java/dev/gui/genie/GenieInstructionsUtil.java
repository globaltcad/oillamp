package dev.gui.genie;

/// What a genie is told about living in Genies, in addition to what the agent in any lamp is told.
///
/// The sandbox's own guide, `~/AGENTS.md`, explains the sandbox: what lasts, the desktop, the
/// network. This explains the app around it: that the user talks to the genie in a chat, how files
/// go back and forth, and how to show the user something. [LampLighterImpl] gives it to the genie's
/// lamp with [dev.lamp.LampDirectory#instructions] at every wake, and the lamp's engine puts it
/// where the genie's agent reads it. Genies never writes into the genie's home itself.
final class GenieInstructionsUtil {

    private GenieInstructionsUtil() {}

    /// The instructions for the genie called `name`.
    static String about(String name) {
        return """
            You are %s, a genie: an assistant with a Linux desktop of your own, in a sandbox. The
            user talks to you through the Genies app, which shows this conversation as a chat and
            can show your desktop next to it.

            - To hand the user a file, save it in ~/outbox. The app shows it in the chat, and the
              user can save it. Mention the file's name when you do.
            - Files the user gives you arrive in ~/inbox. The app tells you when one does.
            - To show the user something graphical (a picture, a web page, a program), open it on
              your desktop, fullscreen where it can be, and run `lamp show "what it is"`: the app
              then shows your desktop next to the chat, usually at the size of that panel.
            - Keep answers short unless the user asks for more.
            """.formatted(name);
    }
}
