package dev.lamp;

/// A planned or executed unit of work, projected for display.
///
/// @param kind     the step's type name, for example `"InstallPackages"`; safe to match on
/// @param describe one line, for the console and for `--dry-run`
/// @param detail   the full story, for the session log
public record LampStep(String kind, String describe, String detail) {}
