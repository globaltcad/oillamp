/// oillamp's client API: what a Java application uses to run lamps from its own code, rather
/// than from a shell with `oillamp`.
///
/// The engine, `dev.oillamp`, runs the sandbox in a process of its own. This package starts that
/// process, talks to it and receives what it reports. The way in is [dev.lamp.Lamp#at]:
///
/// - [dev.lamp.LampDirectory]: a lamp's directory. Says how a session starts, starts or joins
///   one, and acts on the lamp at rest: its snapshots, schedule, conversations and settings
/// - [dev.lamp.Lamp]: a session the application started or joined, until it closes it
/// - [dev.lamp.LampEvent]: everything the engine reports, as values, passed to the listeners
/// - [dev.lamp.LampProblem], [dev.lamp.LampFailedException] and [dev.lamp.LampExitStatus]: what
///   went wrong, and how an engine process ended
/// - the values these carry, such as [dev.lamp.LampSave], [dev.lamp.LampBranch], [dev.lamp.LampJob],
///   [dev.lamp.LampRun], [dev.lamp.LampConversation] and [dev.lamp.LampPrompt]
///
/// Every public type here starts with `Lamp` and every other one with `Internal`, so that a name
/// says whether it is a promise to applications. The engine uses these types too, but this
/// package never imports `dev.oillamp`: an application depends on the engine's words, not on the
/// engine. `TheShapeOfTheCodeSpec` enforces both.
///
/// `docs/DECISIONS.md`, "Other applications embed oillamp through `dev.lamp`", says why the engine
/// runs in a process of its own.
package dev.lamp;
