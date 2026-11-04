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
/// [LampDirectory#hostAccess()] and [LampDirectory#hostAccess(Lamp.HostAccess)]. Writing needs
/// the genie asleep; the page only offers it then. Also hears the requests the genie's lamp asks
/// the user while it is awake, and the web servers it published.
///
/// Each call starts an engine process that ends by itself, which takes a moment, so the work is
/// done one piece after the other on a thread of its own, and the calls return at once.
public final class HostAccessKeeper {

    private final Path directory;
    private final LampDirectory lamp;
    private final Consumer<UnaryOperator<Genie>> changes;
    private final ExecutorService work = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("host-access").factory());

    /// @param lamp the genie's lamp, unlit: [LampLighter#unlit]
    public HostAccessKeeper(Path directory, LampDirectory lamp, Consumer<UnaryOperator<Genie>> changes) {
        this.directory = directory;
        this.lamp = lamp;
        this.changes = changes;
    }

    /// Reads the switch, the rules, and when the user is asked, from the lamp's file. A genie
    /// that never woke has no lamp yet, and so no rules: its host access is off.
    public void read() {
        work.execute(() -> {
            if (Lamp.agentHome(directory).isEmpty()) {
                change(it -> it.readAs(false, HostAccess.PermissionRules.OFF));
                return;
            }
            try {
                HostAccess.PermissionRules read = LampApiConversionUtil.permissionRules(lamp.hostAccess());
                change(it -> it.readAs(true, read));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withProblem(new HostAccess.Problem.CouldNotRead(reason(failed))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Writes `rules`, the ones the page shows, into the lamp's file. The page then shows what
    /// the file has, or says why it could not be written.
    public void save(HostAccess.PermissionRules rules) {
        change(it -> it.withBusy(true).noProblem());
        work.execute(() -> {
            try {
                HostAccess.PermissionRules written = LampApiConversionUtil.permissionRules(lamp.hostAccess(LampApiConversionUtil.hostAccess(rules)));
                change(it -> it.readAs(true, written));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withBusy(false).withProblem(new HostAccess.Problem.CouldNotSave(reason(failed))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// While the genie is awake: its lamp asks the user to allow a request, a host tool's or
    /// publishing a web server, or tells them it published one.
    void onLampEvent(LampEvent event, Instant now) {
        switch (event) {
            case LampEvent.HostAccessAsked asked -> change(it -> it.permissionRequested(
                new HostAccess.RequestForPermission(new HostAccess.RequestForPermission.About.HostTool(asked.tool()), asked.what(), now)
            ));
            case LampEvent.WebPortAsked asked -> change(it -> it.permissionRequested(
                new HostAccess.RequestForPermission(new HostAccess.RequestForPermission.About.WebPort(asked.sandboxPort()), asked.what(), now)
            ));
            case LampEvent.WebPublished published -> change(it -> it.webPublished(
                new HostAccess.Published(published.port().name(), published.port().link())
            ));
            default -> {}
        }
    }

    /// The genie's lamp went out, and every question it asked, and every web server it
    /// published, went with it.
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
