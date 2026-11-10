package dev.gui.model;

/// A window of its own on the user's desktop, outside Genies, onto an awake genie's sandbox. Its
/// lamp opens it, as the `oillamp` command of the same name does at a terminal.
public enum LampWindow {
    /// A terminal with a shell in the sandbox, as the genie has one: `oillamp shell`.
    SHELL,
    /// The sandbox's desktop in a viewer: `oillamp view`.
    DESKTOP,
    /// A terminal that says what the lamp reports, as it happens: `oillamp follow`.
    FOLLOWING;

    /// The command that opens it at a terminal, for the lamp in `lamp`.
    public String command(String lamp) {
        return "oillamp " + switch (this) {
            case SHELL -> "shell";
            case DESKTOP -> "view";
            case FOLLOWING -> "follow";
        } + " " + lamp;
    }
}
