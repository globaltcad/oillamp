package dev.lamp;

/// Who approves a shell command the agent wants to run on the host: `host_access.commands` in a
/// lamp's `oillamp.toml`.
///
/// Running a command is the executing tier, [LampHostRisk#EXECUTING]: a command can do anything
/// the user can, and names no path oillamp could check before it runs. So nobody can approve one,
/// [#OFF], unless the user names one of the other two.
public enum LampHostCommandApproval {
    /// Nobody: there is no `host_bash`, the tool is not offered, and a call is refused.
    OFF,
    /// The checker judges each command, and then the person is asked before it runs.
    ASK,
    /// The checker alone judges each command.
    CHECKER
}
