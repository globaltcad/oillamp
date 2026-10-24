package dev.lamp;

import sprouts.Tuple;

/// A snapshot, with the branches it is on. What `oillamp history <dir> list --all` lists.
///
/// Each snapshot names the one before it. A snapshot is on a branch when it is the branch's newest
/// snapshot, or is reached from that one by going back from each snapshot to the one before it. So
/// the snapshots from before a branch was started are on the branch it was started from and on
/// the new one alike.
///
/// @param branches the names of those branches, in alphabetical order
public record LampSaveOnBranches(LampSave snapshot, Tuple<String> branches) {}
