package dev.oillamp;

import dev.lamp.LampEvent;

import sprouts.Association;
import sprouts.Tuple;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/// The image and sandbox phases: builds the image if needed, then starts the container and waits
/// until it is ready.
///
/// Like the other phases, it plans before it acts, so `--dry-run` shows the exact
/// `podman run` command. Those flags are the sandbox's security settings, and it should be
/// possible to check them without reading the source.
final class SandboxPhase {

    private final Machine machine;
    private final Context context;

    public SandboxPhase(Machine machine, Context context) {
        this.machine = machine;
        this.context = context;
    }

    /// What the sandbox turned out to be, once it was running.
    public record Running(ContainerName container, ImageTag image, String readyJson) {}

    public Result<Running> start(LampPhase.Prepared prepared, HostFacts host) {
        LampLayout layout = prepared.layout();
        ContainerName container = ContainerName.of(layout.agentId());
        // With the revision of the lamp's last `oillamp update`, so this start finds the image
        // that update built.
        SortedMap<String, String> buildArguments = buildArgumentsFor(prepared.config(),
                FilesystemUtil.readString(layout.toolRevision()).orElse("").strip());
        ImageTag image = ImageTag.ofHash(SandboxImageFilesUtil.hashOf(buildArguments));

        Tuple<Step> steps = Tuple.of(Step.class);

        // A container with the same name, left by a session that was killed, would make
        // `podman run` fail with a confusing name clash. Remove it first.
        if (containerExists(container))
            steps = steps.add(new Step.RemoveContainer(container, "left over from an earlier session"));

        // The tag is a hash of the image's inputs, so an image with this tag was built from exactly
        // these inputs and does not need building again.
        boolean imagePresent = imageExists(image);
        if (!imagePresent) {
            steps = steps.add(new Step.ExtractImageContext(layout.imageContext()));
            steps = steps.add(new Step.BuildImage(image, layout.imageContext(),
                                                  asAssociation(buildArguments), false));
        }

        StepRunner runner = new StepRunner(machine, context);

        // Two plans, reported as separate phases, because "the image could not be built" and
        // "the sandbox would not start" are different problems.
        if (!steps.isEmpty()) {
            Result<Plan> image_ = runner.run(Plan.of(LampEvent.Phase.IMAGE, steps));
            if (image_ instanceof Result.Err<Plan> failure) return Result.err(failure.problems());
            if (!imagePresent && !context.options().dryRun())
                context.sink().accept(new LampEvent.Warning(ProblemCatalogUtil.imageLeftovers(image.value())));
        }
        if (imagePresent) {
            context.sink().accept(new LampEvent.Ok("image", "sandbox image ready — " + image));
            imageAge(image).filter(age -> age.compareTo(OLD_IMAGE) > 0).ifPresent(age ->
                    context.sink().accept(new LampEvent.Warning(
                            ProblemCatalogUtil.imageOld(layout.root(), image.value(), age.toDays()))));
        }

        Tuple<Step> session = Tuple.of(Step.class,
                new Step.DeleteContainerOwnedFiles(staleSessionFiles(layout),
                        "left behind by the previous session on this lamp"),
                new Step.RunContainer(container, image, containerArgv(container, image, prepared, host)),
                new Step.AwaitReady(container, layout.readyFile(), prepared.session(),
                                    readyTimeout(prepared.config(), imagePresent)),
                new Step.CheckEndpoints(container, Tuple.of(Step.Endpoint.class,
                        new Step.Endpoint("the desktop (VNC)", layout.vncSocket()),
                        new Step.Endpoint("the shell (SSH)", layout.agentSshSocket()))));

        Result<Plan> started = runner.run(Plan.of(LampEvent.Phase.SESSION, session));
        if (started instanceof Result.Err<Plan> failure) return Result.err(failure.problems());
        if (context.options().dryRun())
            return Result.ok(new Running(container, image, "{}"), started.warnings());

        String ready = FilesystemUtil.readString(layout.readyFile()).orElse("{}");
        return Result.ok(new Running(container, image, ready), started.warnings());
    }

    /// The `podman run` arguments that make the container a sandbox. The spike tests check
    /// that these flags behave as described.
    ///
    /// - `--network=none`: no network interface except loopback, no route, no DNS.
    ///   Outbound traffic goes through the proxy socket instead.
    /// - `--read-only`: the image cannot be changed, so anything a session installs outside
    ///   the agent's home is gone when it ends.
    /// - `--userns=keep-id:uid=1000,gid=1000`: the host user becomes container uid 1000,
    ///   so the agent's files belong to the host user, and the infra user (1001) is a
    ///   subordinate id the agent cannot become.
    /// - `--user 0:0`: the entrypoint starts as container root to create each user's
    ///   runtime directories. Container root is a subordinate id on the host with no rights
    ///   there. The entrypoint starts every long-running process without capabilities; no
    ///   podman flag removes them.
    private Tuple<String> containerArgv(ContainerName container, ImageTag image,
                                        LampPhase.Prepared prepared, HostFacts host) {
        LampLayout layout = prepared.layout();
        LampConfig config = prepared.config();
        Tuple<String> argv = Tuple.of(String.class,
                "--detach",
                "--name", container.value(),
                "--network=none",
                "--read-only",
                "--user", "0:0",
                "--userns=keep-id:uid=1000,gid=1000",
                "--tmpfs", "/run:rw,mode=755",
                "--tmpfs", "/tmp:rw,mode=1777",
                "--memory", config.limits().memory(),
                "--cpus", String.valueOf(config.limits().resolveCpus(host.cpuCount())),
                "--pids-limit", String.valueOf(config.limits().pids()),
                // Labels let `oillamp list` and `oillamp remove` find oillamp's containers by asking
                // podman, without keeping a separate list that could go out of date.
                "--label", "oillamp.agent-id=" + layout.agentId(),
                "--label", "oillamp.lamp=" + layout.root(),
                "--label", "oillamp.session=" + prepared.session());

        argv = argv.addAll(Tuple.of(String.class,
                "--volume", layout.sessionDir() + ":/oillamp/session:ro",
                // Each socket directory on its own, never their parent. The parent belongs to the
                // user the agent runs as, so attached, it would let the agent move infra/ aside and
                // serve its own desktop, or leave a link for the host to follow. host/ is read-only:
                // the sandbox only connects to the proxy sockets in it.
                "--volume", layout.hostSocketsDir() + ":/oillamp/sockets/host:ro",
                "--volume", layout.agentSocketsDir() + ":/oillamp/sockets/agent",
                "--volume", layout.infraSocketsDir() + ":/oillamp/sockets/infra",
                "--volume", layout.recordingsDir() + ":/oillamp/recordings",
                "--volume", layout.agentDir() + ":/home/agent"));

        // Only when the GPU is used. `keep-groups` carries the host's render group into the
        // container. It works with crun but not runc, which is why crun is a required package.
        if (prepared.gpu() instanceof DesktopRendererUtil.Decision.Hardware hardware)
            argv = argv.addAll(Tuple.of(String.class,
                    "--device", hardware.node().path().toString(),
                    "--group-add", "keep-groups"));

        return argv.add(image.value());
    }

    /// The files the previous session left in the socket directory.
    ///
    /// That directory is on the host, so it outlives the container. wayvnc cannot bind
    /// `vnc.sock` if the old file is still there, and the host would read an old
    /// `ready.json` as this session's answer.
    ///
    /// They belong to the infra user, so they are deleted with `podman unshare` rather than
    /// an ordinary delete, which would be refused.
    private static Tuple<Path> staleSessionFiles(LampLayout layout) {
        return Tuple.of(layout.readyFile(),
                        layout.infraSocketsDir().resolve("vnc.sock"),
                        layout.agentSocketsDir().resolve("ssh.sock"));
    }

    /// How long to wait for `ready.json`: `timeouts.container_ready_seconds`, and twice that
    /// straight after a build, because the first start of a new image is slower (nothing is cached
    /// yet).
    private static Duration readyTimeout(LampConfig config, boolean imageWasAlreadyPresent) {
        Duration configured = config.timeouts().containerReady();
        return imageWasAlreadyPresent ? configured : configured.multipliedBy(2);
    }

    /// The Containerfile's build arguments. They are all part of the image's hash.
    ///
    /// `revision` is the lamp's last `oillamp update`, or empty if it was never updated.
    /// `AGENT_TOOLS_STRICT` is not here: `update` adds it to its own build only, outside the
    /// hash, so that later starts find the image it built and still forgive failed downloads
    /// when they build one themselves.
    static SortedMap<String, String> buildArgumentsFor(LampConfig config, String revision) {
        SortedMap<String, String> arguments = new TreeMap<>();
        arguments.put("BASE_IMAGE", config.image().base());
        arguments.put("JDK_PACKAGE", config.image().jdkPackage());
        arguments.put("NODE_MAJOR", config.image().nodeVersion());
        arguments.put("EXTRA_APT_PACKAGES", String.join(" ", config.image().extraAptPackages()));
        // Passing the harness list as a build argument also makes it part of the image hash, so
        // changing `agent_tools.install` rebuilds the image with the new list.
        arguments.put("AGENT_TOOLS", config.agentTools().install().mapTo(String.class, tool -> switch (tool) {
            case "opencode" -> "opencode-ai@" + config.agentTools().versions().get(tool).orElse("latest");
            case "pi" -> "@earendil-works/pi-coding-agent@" + config.agentTools().versions().get(tool).orElse("latest");
            default -> tool;
        }).join(" "));
        if (!revision.isEmpty()) arguments.put("AGENT_TOOLS_REVISION", revision);
        return arguments;
    }

    static Association<String, String> asAssociation(SortedMap<String, String> map) {
        Association<String, String> out = Association.betweenSorted(String.class, String.class);
        for (var entry : map.entrySet()) out = out.put(entry.getKey(), entry.getValue());
        return out;
    }

    private boolean imageExists(ImageTag tag) {
        return succeeded("podman image exists", "podman", "image", "exists", tag.value());
    }

    /// How old an image may be before a start warns about it. Debian publishes security fixes
    /// every few weeks, and none of them reaches an image that is not built again.
    static final Duration OLD_IMAGE = Duration.ofDays(30);

    /// How long ago the image was built, or empty if podman does not say.
    ///
    /// Asks for podman's JSON rather than a `--format` template: the JSON's `Created` is an
    /// RFC 3339 time in every podman version, while the template's output depends on Go's
    /// default time format.
    private Optional<Duration> imageAge(ImageTag tag) {
        Machine.Outcome outcome = machine.run(Machine.Command
                .of("podman", "image", "inspect", tag.value())
                .withTimeout(Duration.ofSeconds(20)).labelled("podman image inspect"));
        if (!outcome.succeeded()) return Optional.empty();
        return JsonUtil.parse(outcome.output())
                .map(json -> json.isArray() ? json.path(0) : json)
                .map(image -> image.path("Created").asText(""))
                .flatMap(created -> {
                    try {
                        return Optional.of(Duration.between(OffsetDateTime.parse(created).toInstant(), machine.now()));
                    } catch (DateTimeParseException e) {
                        return Optional.empty();
                    }
                });
    }

    private boolean containerExists(ContainerName name) {
        return succeeded("podman container exists", "podman", "container", "exists", name.value());
    }

    private boolean succeeded(String label, String... argv) {
        Machine.Outcome outcome = machine.run(Machine.Command.of(argv)
                .withTimeout(Duration.ofSeconds(20)).labelled(label));
        return outcome instanceof Machine.Outcome.Finished finished && finished.exitCode() == 0;
    }
}
