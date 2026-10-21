package dev.oillamp;

import java.time.Instant;
import java.util.Optional;

import dev.lamp.LampExitStatus;
import dev.lamp.LampProblem;

import sprouts.Tuple;

/// Where a session has got to.
///
/// The states differ in what an event means. In `AwaitingTerminal` the container is up but
/// nobody is connected, so a terminal that never connects must end the session. In `Running`,
/// shell windows come and go as the user likes; only Ctrl-C (or closing the terminal oillamp was
/// started from), `oillamp stop` or the sandbox dying ends it.
///
/// Only the supervisor's event loop changes the state, and only by replacing it with a new value.
/// [SessionMachine] decides each change. Users see a description of it as
/// [LampSessionStatus].
sealed interface SessionState {

    /// The container has been asked to start; nothing is known about it yet.
    // Every live state carries the runs' logs: what each run was asked and did, which the
    // host-access gate reads from here rather than keeping a mutable copy of its own. They stay
    // empty in a lamp without host access: the session records them only while it is on.

    record Starting(Instant since, Tuple<HostAccess.RunLog> runs) implements SessionState {}

    /// The sandbox answered on both sockets; the terminal window has been asked to open.
    record AwaitingTerminal(Instant since, ReadyInfo ready, Tuple<HostAccess.RunLog> runs)
            implements SessionState {}

    /// The user's shell connected, so the session is up.
    ///
    /// @param shellWindowOpen whether the shell window oillamp opened is still open. Closing it
    ///                        ends nothing; it is tracked only so that `oillamp status` is right
    /// @param extraShells     how many `oillamp shell` sessions are attached
    /// @param asked           the host-access question the person has not answered yet; empty
    ///                        when there is none, which is almost always
    record Running(Instant since, ReadyInfo ready, boolean shellWindowOpen, int extraShells,
                   Optional<String> asked, Tuple<HostAccess.RunLog> runs) implements SessionState {}

    /// The shutdown sequence is running; further events are ignored.
    record ShuttingDown(Instant since, ShutdownReason reason, Tuple<HostAccess.RunLog> runs)
            implements SessionState {}

    /// Final. Carries the exit code the process will leave with.
    record Stopped(ShutdownReason reason, LampExitStatus exit) implements SessionState {}

    /// Why a session is ending. Both the exit code and the closing summary are derived from it, so
    /// they cannot disagree.
    sealed interface ShutdownReason {

        /// Ctrl-C, SIGTERM or SIGHUP. SIGHUP is what the terminal oillamp was started from sends
        /// when it is closed, so closing that terminal ends the session too.
        ///
        /// `wasRunning` is recorded when the interrupt happens, because it decides the exit
        /// code: interrupting a working session is a normal way to end it (0), interrupting one that
        /// never reached `Running` is a failed start (130).
        record UserInterrupt(String signal, boolean wasRunning) implements ShutdownReason {}

        /// `oillamp stop`, or another front end through the control socket.
        record StopCommand(String source) implements ShutdownReason {}

        /// The container exited on its own — always a failure, whatever its exit code says.
        record ContainerDied(int exitCode) implements ShutdownReason {}

        /// The session never came up. Carries the problem that already explained why.
        record StartupFailed(LampProblem problem) implements ShutdownReason {}

        /// oillamp itself failed while it ran the session. Carries the problem that reported it.
        record Crashed(LampProblem problem) implements ShutdownReason {}

        /// The process exit code for this reason.
        default LampExitStatus exitStatus() {
            return switch (this) {
                case StopCommand ignored    -> LampExitStatus.SUCCESS;
                case UserInterrupt interrupt -> interrupt.wasRunning()
                        ? LampExitStatus.SUCCESS : LampExitStatus.INTERRUPTED;
                case ContainerDied ignored  -> LampExitStatus.SESSION_FAILED;
                case StartupFailed ignored  -> LampExitStatus.SESSION_FAILED;
                case Crashed ignored        -> LampExitStatus.ERROR;
            };
        }

        /// One line for the summary, in the terms the user would use.
        default String describe() {
            return switch (this) {
                case UserInterrupt interrupt -> "interrupted (" + interrupt.signal() + ")";
                case StopCommand stop        -> "asked to stop by " + stop.source();
                case ContainerDied died      -> "the sandbox container exited (code "
                                              + died.exitCode() + ")";
                case StartupFailed failed    -> "the session could not be started — "
                                              + failed.problem().code();
                case Crashed crashed         -> "oillamp itself failed — " + crashed.problem().code();
            };
        }
    }

    /// The state's name as `oillamp status` prints it.
    default String name() {
        return switch (this) {
            case Starting ignored         -> "starting";
            case AwaitingTerminal ignored -> "awaiting-terminal";
            case Running ignored          -> "running";
            case ShuttingDown ignored     -> "shutting-down";
            case Stopped ignored          -> "stopped";
        };
    }

    /// True once the supervisor has stopped reacting to anything.
    default boolean isFinal() { return this instanceof Stopped; }

    /// True while the shutdown sequence has not begun — the states an event can still change.
    default boolean isLive() {
        return this instanceof Starting || this instanceof AwaitingTerminal || this instanceof Running;
    }

    /// What each run was asked and did, kept in the state so that the host-access gate reads it
    /// rather than keeping its own copy.
    default Tuple<HostAccess.RunLog> runs() {
        return switch (this) {
            case Starting starting -> starting.runs();
            case AwaitingTerminal awaiting -> awaiting.runs();
            case Running running -> running.runs();
            case ShuttingDown shuttingDown -> shuttingDown.runs();
            case Stopped ignored -> Tuple.of(HostAccess.RunLog.class);
        };
    }

    /// How many extra `oillamp shell` sessions are attached.
    default int extraShells() {
        return this instanceof Running running ? running.extraShells() : 0;
    }

    /// When the session entered this state, not when the session began.
    ///
    /// The terminal timeout counts from here: "not connected for 60 seconds" means 60 seconds
    /// since the terminal was asked to open. The session's own start time is kept by the supervisor
    /// and used for the uptime.
    default Optional<Instant> enteredAt() {
        return switch (this) {
            case Starting s         -> Optional.of(s.since());
            case AwaitingTerminal s -> Optional.of(s.since());
            case Running s          -> Optional.of(s.since());
            case ShuttingDown s     -> Optional.of(s.since());
            case Stopped ignored    -> Optional.empty();
        };
    }
}
