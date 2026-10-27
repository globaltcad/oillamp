package dev.lamp;

import java.nio.file.Path;

/// A sandbox podman is running for a lamp on this machine, whoever started it: an application
/// through [Lamp], or a person with `oillamp at`. Found by the labels oillamp gives every sandbox,
/// not from a list oillamp keeps.
///
/// @param container the container's name, such as `oillamp-k3v7x2ab`
/// @param state     podman's word for its state, such as `running`
/// @param lamp      the lamp's directory, as it was when the session started it
public record LampSandbox(String container, String state, Path lamp) {}
