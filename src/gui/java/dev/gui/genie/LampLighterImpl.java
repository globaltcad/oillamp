package dev.gui.genie;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import dev.gui.desktop.Desktop;
import dev.gui.model.Settings;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;
import dev.lamp.LampDirectory;
import dev.lamp.LampFailedException;
import dev.lamp.LampLauncher;
import dev.lamp.LampPrompt;
import dev.lamp.LampRun;
import dev.lamp.LampSandbox;

/// Lights genies' lamps with oillamp, the way any application does: through [Lamp], and nothing
/// else.
///
/// The model service and key from the settings go to the lamp's engine, which keeps the key on
/// the host. The sandbox gets only its fixed relay address and a placeholder. The model from the
/// settings goes to the engine too, which has every run answered by it, in old conversations as
/// in new ones; [Lit#useModel] changes it while the lamp is lit. And the genie's instructions,
/// [GenieInstructionsUtil#about], go to the engine, which puts them where the genie's agent
/// reads them. Which agent that is, and where it keeps its settings, is the engine's business.
///
/// Every lamp is lit with its schedule on, so the jobs on it wake the genie while it is awake.
public final class LampLighterImpl implements LampLighter {

    private final Optional<LampLauncher> launcher;

    /// Starts each lamp's engine in a process of its own, from this application's classpath.
    public LampLighterImpl() { this(Optional.empty()); }

    /// Starts each lamp's engine with `launcher`, as the scenarios do, to run it in their own JVM.
    public LampLighterImpl(LampLauncher launcher) { this(Optional.of(launcher)); }

    private LampLighterImpl(Optional<LampLauncher> launcher) {
        this.launcher = launcher;
    }

    /// The first start of any lamp builds the sandbox image, which takes several minutes, and
    /// longer on a slow network.
    static final Duration WAKING_TIME = Duration.ofMinutes(45);

    @Override
    public Lit light(
        final Path directory,
        final String name,
        final Settings settings,
        final String key,
        final Consumer<String> progress,
        final Consumer<LampEvent> events
    ) throws IOException, InterruptedException {
        LampDirectory starting;
        try {
            starting = unlit(directory).model(settings.model().strip());
        } catch (IllegalArgumentException wrong) {
            throw new IOException("\"" + settings.model().strip() + "\" cannot be a model's name: a model's name has no spaces, such as azure/gpt-6.1-sol", wrong);
        }
        return light(starting.instructions(GenieInstructionsUtil.about(name)).enableScheduling(), settings, key, progress, events);
    }

    /// Its own model, instructions and schedule, as its `oillamp.toml` and its agent's home
    /// have them; Genies' model service and key, which are not written anywhere.
    @Override
    public Lit lightAsItIs(
        final Path directory,
        final Settings settings,
        final String key,
        final Consumer<String> progress,
        final Consumer<LampEvent> events
    ) throws IOException, InterruptedException {
        return light(unlit(directory), settings, key, progress, events);
    }

    private Lit light(
        final LampDirectory starting,
        final Settings settings,
        final String key,
        final Consumer<String> progress,
        final Consumer<LampEvent> events
    ) throws IOException, InterruptedException {
        AtomicReference<String> problem = new AtomicReference<>("");
        Lamp lamp = starting
                .modelService(URI.create(settings.service().strip()))
                .modelKey(key)
                .onEvent(event -> {
                    if (event instanceof LampEvent.Failure failure)
                        problem.set(failure.problem().title() + ": " + failure.problem().whatHappened());
                    describe(event).ifPresent(progress);
                    events.accept(event);
                })
                .start();
        if (!lamp.awaitRunning(WAKING_TIME)) {
            lamp.close();
            String why = Optional.ofNullable(problem.get()).orElse("");
            throw new IOException(why.isEmpty()
                    ? "The genie's lamp did not start" + lamp.exitStatus().map(status -> " (" + status + ")").orElse(" in time")
                    : why);
        }
        return lit(lamp);
    }

    /// How long joining may take. The session answers at once; this allows for a busy machine.
    static final Duration JOINING_TIME = Duration.ofSeconds(30);

    @Override
    public Optional<Lit> join(Path directory, Consumer<LampEvent> events) throws IOException, InterruptedException {
        LampDirectory starting = unlit(directory);
        if (!starting.isRunning()) return Optional.empty();
        Lamp lamp = starting.onEvent(events).join();
        if (!lamp.awaitRunning(JOINING_TIME)) {
            lamp.close();
            return Optional.empty();
        }
        return Optional.of(lit(lamp));
    }

    /// A lamp this app started or joined. Putting out one it joined ends its session, as for one
    /// it started.
    private static Lit lit(Lamp lamp) {
        return new Lit() {
            @Override public Process exec(String... command) throws IOException { return lamp.exec(command); }
            @Override public Desktop desktop() {
                LampEvent.SessionOpened session = lamp.session();
                return new Desktop(session.desktop(), session.desktopWidth(), session.desktopHeight());
            }
            @Override public LampRun send(LampPrompt question)
                    throws IOException, InterruptedException, LampFailedException { return lamp.send(question); }
            @Override public void cancel(String run)
                    throws IOException, InterruptedException, LampFailedException { lamp.cancel(run); }
            @Override public void useModel(String model)
                    throws IOException, InterruptedException, LampFailedException { lamp.useModel(model); }
            @Override public void decidePermission(String request, boolean allowed)
                    throws IOException, InterruptedException, LampFailedException { lamp.decidePermission(request, allowed); }
            @Override public void close() {
                if (lamp.holds()) {
                    lamp.close();
                    return;
                }
                try {
                    lamp.stop();
                } catch (IOException | LampFailedException alreadyOut) {
                    // Nothing is running that could be stopped; following ends below all the same.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    lamp.close();
                }
            }
            @Override public void leaveRunning() { lamp.leaveRunning(); }
        };
    }

    @Override
    public List<Path> running() throws IOException, InterruptedException {
        try {
            List<LampSandbox> sandboxes = launcher.isPresent() ? Lamp.running(launcher.get()) : Lamp.running();
            return sandboxes.stream().map(LampSandbox::lamp).toList();
        } catch (LampFailedException failed) {
            throw new IOException(failed.problem().title() + ": " + failed.problem().whatHappened(), failed);
        }
    }

    @Override
    public LampDirectory unlit(Path directory) {
        LampDirectory lamp = Lamp.at(directory);
        return launcher.isPresent() ? lamp.launchedBy(launcher.get()) : lamp;
    }

    /// What the lamp is doing, in the few words the genie's status has room for.
    static Optional<String> describe(LampEvent event) {
        return switch (event) {
            case LampEvent.PhaseStarted started -> Optional.of(switch (started.phase()) {
                case HOST -> "checking this computer";
                case LAMP -> "preparing the lamp";
                case IMAGE -> "making the sandbox; the first time takes several minutes";
                default -> "starting the sandbox";
            });
            case LampEvent.StepStarted step -> Optional.of(step.step().describe());
            case LampEvent.Ok ok -> Optional.of(ok.text());
            case LampEvent.Warning warning -> Optional.of(warning.problem().title());
            default -> Optional.empty();
        };
    }
}
