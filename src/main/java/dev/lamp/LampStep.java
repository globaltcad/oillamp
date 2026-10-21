package dev.lamp;

/// One unit of work in a [phase][LampStartupPhase], such as installing packages or building the
/// image, as [LampEvent.StepPlanned], [LampEvent.StepStarted], [LampEvent.StepSucceeded] and
/// [LampEvent.StepSkipped] report it.
///
/// @param kind     what sort of step it is, such as `"InstallPackages"`; safe to match on
/// @param describe one line for people, as the console and `--dry-run` show it
/// @param detail   the full story, as the session log keeps it
public record LampStep(String kind, String describe, String detail) {}
