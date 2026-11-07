package dev.oillamp;

import sprouts.Tuple;

/// How oillamp can run a command as root on this machine, if at all. Installing the host
/// packages and adding a subordinate id range both need it; without it they are reported
/// (`OIL-PKG-002`) instead of carried out. A dry run plans them either way, because it only
/// describes what would be done.
sealed interface RootAccess {

    /// `sudo` works: `sudo -n true` succeeded, or a person at the terminal can type the password.
    record Sudo() implements RootAccess {}

    /// `pkexec` asks for the password in a window of the desktop session. Used only where sudo
    /// needs a password and there is no terminal to type it into, as when a desktop application
    /// such as Genies started oillamp. Exits with 126 when the person closes that window.
    record Pkexec() implements RootAccess {}

    /// oillamp has no way to become root here, for the given reason.
    record Unavailable(String reason) implements RootAccess {}

    /// The command line that runs `command` as root. Where there is no way to do so, it is the
    /// command a person would type at a terminal, which is what a dry run shows.
    default Tuple<String> asRoot(Tuple<String> command) {
        return switch (this) {
            case Sudo _, Unavailable _ -> Tuple.of(String.class, "sudo").addAll(command);
            case Pkexec _              -> Tuple.of(String.class, "pkexec").addAll(command);
        };
    }
}
