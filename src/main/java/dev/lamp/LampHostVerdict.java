package dev.lamp;

/// What the checker model decided about one host request, and why, as
/// [LampEvent.HostAccessDecided] reports it.
///
/// @param allowed whether the checker allowed it. An allowed request can still be refused
///                after, by the user or by the host, which [LampEvent.HostAccessRefused] reports
/// @param risk    the tier the checker judged it at. Above the tier of the request's tool, the
///                request is refused
/// @param reason  why, in the checker's own words
public record LampHostVerdict(boolean allowed, LampHostRisk risk, String reason) {}
