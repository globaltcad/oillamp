package dev.oillamp;

import sprouts.Tuple;
import sprouts.ValueSet;

/// The package managers oillamp installs host packages with, as the command lines it runs.
///
/// Each has two commands: one that installs packages without asking, run as root, and one that
/// asks which packages are installed, which needs no root rights. [#installedIn] reads the
/// second one's output.
enum PackageManager {
    APT, DNF, ZYPPER, PACMAN;

    /// The program that installs, which names the output it prints in the log.
    public String executable() {
        return switch (this) {
            case APT    -> "apt-get";
            case DNF    -> "dnf";
            case ZYPPER -> "zypper";
            case PACMAN -> "pacman";
        };
    }

    /// The command line that installs `packages` without asking any question.
    public Tuple<String> install(Tuple<String> packages) {
        Tuple<String> command = switch (this) {
            case APT    -> Tuple.of(String.class, "apt-get", "install", "-y", "--no-install-recommends");
            case DNF    -> Tuple.of(String.class, "dnf", "install", "-y");
            case ZYPPER -> Tuple.of(String.class, "zypper", "--non-interactive", "install", "--no-recommends");
            case PACMAN -> Tuple.of(String.class, "pacman", "-S", "--needed", "--noconfirm");
        };
        return command.addAll(packages);
    }

    /// The command a person runs to update the package lists, the usual remedy when an
    /// install fails because they are out of date.
    public String refreshCommand() {
        return switch (this) {
            case APT    -> "sudo apt-get update";
            case DNF    -> "sudo dnf makecache --refresh";
            case ZYPPER -> "sudo zypper refresh";
            case PACMAN -> "sudo pacman -Syu";
        };
    }

    /// The command line that asks which of `packages` are installed.
    public Tuple<String> query(Tuple<String> packages) {
        Tuple<String> command = switch (this) {
            case APT         -> Tuple.of(String.class, "dpkg-query", "-W", "-f=${Package} ${Status}\n");
            case DNF, ZYPPER -> Tuple.of(String.class, "rpm", "-q", "--queryformat", "%{NAME}\n");
            case PACMAN      -> Tuple.of(String.class, "pacman", "-Q");
        };
        return command.addAll(packages);
    }

    /// The installed packages named in the standard output of [#query]. A package that is not
    /// installed makes each of these commands exit non-zero while it still reports the others,
    /// so only the output counts.
    ///
    /// - dpkg-query prints `<name> install ok installed` for an installed package.
    /// - rpm prints the bare name, and `package <name> is not installed` for a missing one.
    /// - pacman prints `<name> <version>`, and reports a missing one on standard error.
    public ValueSet<String> installedIn(String output) {
        ValueSet<String> installed = ValueSet.of(String.class);
        for (String line : output.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            boolean isInstalled = switch (this) {
                case APT         -> trimmed.endsWith("install ok installed");
                case DNF, ZYPPER -> !trimmed.contains(" ");
                case PACMAN      -> true;
            };
            if (isInstalled) installed = installed.add(trimmed.split(" ", 2)[0]);
        }
        return installed;
    }
}
