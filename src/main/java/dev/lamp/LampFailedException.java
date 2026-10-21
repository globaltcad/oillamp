package dev.lamp;

/// The engine said no, or could not do what was asked. [#problem()] says why, in full.
public final class LampFailedException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient LampProblem problem;
    private final LampExitStatus status;

    LampFailedException(LampExitStatus status, LampProblem problem) {
        super(problem.code() + " " + problem.title() + ": " + problem.whatHappened());
        this.problem = problem;
        this.status = status;
    }

    public LampProblem problem() {
        return problem;
    }

    /// The engine's exit status: [LampExitStatus#LAMP_BUSY] for a lamp whose sandbox is running,
    /// [LampExitStatus#USAGE] for a snapshot that does not exist.
    public LampExitStatus status() {
        return status;
    }
}
