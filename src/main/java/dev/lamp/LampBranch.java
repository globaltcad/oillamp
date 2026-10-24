package dev.lamp;

import java.util.Optional;

/// One branch of a lamp's history: a line of snapshots of its own, which the lamp can be switched
/// to, so that one lamp keeps several alternative states, one active at a time. Each branch has its
/// own agent home, `oillamp.toml` and schedule, as its newest snapshot holds them. The first branch
/// is `main`.
///
/// @param name    such as `main` or `rust-rewrite`
/// @param current whether the lamp is on this branch now: every save goes to it
/// @param newest  the branch's newest snapshot, which `oillamp history <dir> switch` brings the
///                lamp to; empty only for `main` in a lamp that has never been saved
public record LampBranch(String name, boolean current, Optional<LampSave> newest) {}
