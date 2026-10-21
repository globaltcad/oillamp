package dev.lamp;

/// What the checker decided about one host request, and why.
public record LampHostVerdict(boolean allowed, LampHostRisk risk, String reason) {}
