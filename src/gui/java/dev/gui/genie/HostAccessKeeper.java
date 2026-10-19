package dev.gui.genie;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import dev.gui.model.Genie;
import dev.gui.model.HostAccess;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;
import dev.lamp.LampDirectory;
import dev.lamp.LampFailedException;

/// Reads a genie's host access from its lamp, and writes the rules its page shows into it, with
/// [LampDirectory#hostAccess()] and [LampDirectory#hostAccess(boolean, String)]. Writing needs
/// the genie asleep; the page only offers it then. Also hears the requests the genie's lamp asks
/// the user while it is awake.
///
/// Each call starts an engine process that ends by itself, which takes a moment, so the work is
/// done one piece after the other on a thread of its own, and the calls return at once.
public final class HostAccessKeeper {

    private final Path directory;
    private final LampDirectory lamp;
    private final Consumer<UnaryOperator<Genie>> changes;
    private final ExecutorService work = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("host-access").factory());

    /// @param lamp the genie's lamp, unlit: [Lighter#unlit]
    public HostAccessKeeper(Path directory, LampDirectory lamp, Consumer<UnaryOperator<Genie>> changes) {
        this.directory = directory;
        this.lamp = lamp;
        this.changes = changes;
    }

    /// Reads the switch and the rules from the lamp's file. A genie that never woke has no lamp
    /// yet, and so no rules: its host access is off.
    public void read() {
        work.execute(() -> {
            if (Lamp.agentHome(directory).isEmpty()) {
                change(it -> it.readAs(false, HostAccess.PermissionRules.OFF));
                return;
            }
            try {
                Lamp.HostAccess read = lamp.hostAccess();
                change(it -> it.readAs(true, HostAccess.PermissionRules.of(read.enabled(), read.requirements())));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withProblem("The host access could not be read: " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Writes `rules`, the ones the page shows, into the lamp's file. The page then shows what
    /// the file has, or says why it could not be written.
    public void save(HostAccess.PermissionRules rules) {
        change(it -> it.withBusy(true).withProblem(""));
        work.execute(() -> {
            try {
                Lamp.HostAccess written = lamp.hostAccess(rules.on(), rules.requirements());
                change(it -> it.readAs(true, HostAccess.PermissionRules.of(written.enabled(), written.requirements())));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withBusy(false).withProblem("oillamp could not save it: " + reason(failed)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// While the genie is awake: its lamp asks the user to allow a request.
    void onLampEvent(LampEvent event, Instant now) {
        if (event instanceof LampEvent.HostAccessAsked asked)
            change(it -> it.permissionRequested(new HostAccess.RequestForPermission(asked.tool(), asked.what(), now)));
    }

    /// The genie's lamp went out, and every question it asked went with it.
    void wentOut() {
        change(HostAccess::wentOut);
    }

    private void change(UnaryOperator<HostAccess> change) {
        changes.accept(genie -> genie.withHostAccess(change.apply(genie.hostAccess())));
    }

    private static String reason(Exception failed) {
        if (failed instanceof LampFailedException refused) return refused.problem().whatHappened();
        return Optional.ofNullable(failed.getMessage()).filter(message -> !message.isBlank()).orElse(failed.toString());
    }
}
