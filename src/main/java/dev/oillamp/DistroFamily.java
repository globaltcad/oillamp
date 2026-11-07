package dev.oillamp;

import java.util.Optional;

/// Families of Linux distributions, grouped by package manager. On [#UNKNOWN] oillamp cannot
/// install host packages, and lists them for the user to install.
enum DistroFamily {
    /// Debian, Ubuntu and their derivatives.
    DEBIAN,
    /// Fedora, Red Hat Enterprise Linux, and the distributions built like it, such as Rocky.
    FEDORA,
    /// openSUSE and SUSE Linux Enterprise.
    SUSE,
    /// Arch Linux and its derivatives, such as Manjaro.
    ARCH,
    UNKNOWN;

    public Optional<PackageManager> packageManager() {
        return switch (this) {
            case DEBIAN  -> Optional.of(PackageManager.APT);
            case FEDORA  -> Optional.of(PackageManager.DNF);
            case SUSE    -> Optional.of(PackageManager.ZYPPER);
            case ARCH    -> Optional.of(PackageManager.PACMAN);
            case UNKNOWN -> Optional.empty();
        };
    }
}
