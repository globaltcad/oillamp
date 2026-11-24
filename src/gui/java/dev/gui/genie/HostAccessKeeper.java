package dev.gui.genie;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import dev.gui.model.Genie;
import dev.gui.model.HostAccess;
import dev.gui.model.Notifications;
import dev.lamp.Lamp;
import dev.lamp.LampEvent;
import dev.lamp.LampDirectory;
import dev.lamp.LampFailedException;
import dev.lamp.LampHostVerdict;

import sprouts.Tuple;

/// Reads a genie's host access from its lamp, and writes the rules its page shows into it, with
/// [LampDirectory#hostAccess()] and [LampDirectory#hostAccess(Lamp.HostAccess)] for
/// `[host_access]`, and [LampDirectory#web()] and [LampDirectory#web(Lamp.Web)] for `[web]`.
/// Writing needs the genie asleep; the page only offers it then. Also hears the requests the
/// genie's lamp asks the user while it is awake, and the web servers it published.
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

    /// Reads the rules from the lamp's file: what the genie may do, its guards, its allowlists,
    /// the checker instructions, and which web servers the browser may open. A genie that never
    /// woke has no lamp yet, and so no file: its host access is off, as oillamp's defaults have it.
    public void read() {
        work.execute(() -> {
            if (Lamp.agentHome(directory).isEmpty()) {
                change(it -> it.readAs(false, HostAccess.Rules.DEFAULTS));
                return;
            }
            try {
                HostAccess.Rules read = LampApiConversionUtil.rules(lamp.hostAccess(), LampApiConversionUtil.web(lamp.web()));
                change(it -> it.readAs(true, read));
            } catch (IOException | LampFailedException failed) {
                change(it -> it.withProblem(new HostAccess.Problem.CouldNotRead(reason(failed))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// Writes `wanted`, the rules the page shows, into the lamp's file: each of its two tables
    /// that differs from `saved`, the rules the file has. A table left as it was is not written,
    /// so that a value the file takes from `~/.config/oillamp/config.toml`, for want of one of
    /// its own, keeps following that.
    ///
    /// `[web]` goes first: oillamp may refuse a port the user typed, and then nothing is written.
    /// Should oillamp then refuse `[host_access]`, the file keeps the `[web]` table it was given,
    /// and the page shows that table as saved, beside why the rest could not be. The page then
    /// shows what the file has, or says why it could not be written.
    public void save(HostAccess.Rules saved, HostAccess.Rules wanted) {
        boolean webChanged = !wanted.web().equals(saved.web());
        boolean hostAccessChanged = !wanted.withWeb(saved.web()).equals(saved);
        change(it -> it.withBusy(true).noProblem());
        work.execute(() -> {
            HostAccess.Rules written = saved;
            try {
                if (webChanged)
                    written = written.withWeb(LampApiConversionUtil.web(lamp.web(LampApiConversionUtil.web(wanted.web()))));
                if (hostAccessChanged)
                    written = LampApiConversionUtil.rules(lamp.hostAccess(LampApiConversionUtil.hostAccess(wanted)), written.web());
                HostAccess.Rules all = written;
                change(it -> it.readAs(true, all));
            } catch (IOException | LampFailedException failed) {
                HostAccess.Rules part = written;
                change(it -> it.withSaved(part).withBusy(false).withProblem(new HostAccess.Problem.CouldNotSave(reason(failed))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /// What the agent asked of each tool in each run, as [LampEvent.HostAccessRequested] said,
    /// until the checker decided: [LampEvent.HostAccessDecided] does not say it again. A lamp
    /// with no checker among its guards decides nothing, so there each tool's next request in
    /// the run takes the place of the one before; the lamp going out forgets them all.
    private final Map<String, String> requested = new ConcurrentHashMap<>();

    /// While the genie is awake: its lamp asks the user to allow a request, a host tool's or
    /// publishing a web server, or tells them it published one, what the checker model decided
    /// about a request, that one was refused, or that something went wrong. Each is kept in the
    /// genie's [Notifications]. Its session says which web servers are open whenever that
    /// changes; the host access page lists them as it said last.
    void onLampEvent(LampEvent event, Instant now) {
        switch (event) {
            case LampEvent.SessionStateChanged changed -> change(it -> it.withWebPorts(LampApiConversionUtil.webPorts(changed.status().webPorts())));
            case LampEvent.HostAccessRequested asked -> requested.put(asked.run() + " " + asked.tool(), asked.said());
            case LampEvent.HostAccessDecided decided -> {
                String said = Optional.ofNullable(requested.remove(decided.run() + " " + decided.tool())).orElse("");
                LampHostVerdict verdict = decided.verdict();
                changeNotifications(it -> it.told(new Notifications.Judged(decided.tool(), said, verdict.allowed(), verdict.risk().name(), verdict.reason()), now));
            }
            case LampEvent.HostAccessRefused refused -> changeNotifications(it -> it.told(new Notifications.Refused(refused.tool(), refused.why()), now));
            case LampEvent.Warning warning -> changeNotifications(it -> it.told(new Notifications.Warned(warning.problem().title(), warning.problem().whatHappened()), now));
            case LampEvent.HostAccessAsked asked -> changeNotifications(it -> it.asked(new Notifications.HostTool(asked.tool(), asked.what()), now));
            case LampEvent.WebPortAsked asked -> changeNotifications(it -> it.asked(new Notifications.WebPort(asked.sandboxPort(), asked.what()), now));
            case LampEvent.WebPublished published -> changeNotifications(it -> it.published(new Notifications.Published(
                    published.port().name(), published.port().sandboxPort(), published.port().link(), published.openedByTheAgent()), now));
            default -> {}
        }
    }

    /// The genie's lamp went out: no question it asked waits any more, and no web server it
    /// published can be opened.
    void wentOut() {
        requested.clear();
        changeNotifications(Notifications::wentOut);
        change(it -> it.withWebPorts(Tuple.of(HostAccess.WebPort.class)));
    }

    private void change(UnaryOperator<HostAccess> change) {
        changes.accept(genie -> genie.withHostAccess(change.apply(genie.hostAccess())));
    }

    private void changeNotifications(UnaryOperator<Notifications> change) {
        changes.accept(genie -> genie.withNotifications(change.apply(genie.notifications())));
    }

    private static String reason(Exception failed) {
        if (failed instanceof LampFailedException refused) return refused.problem().whatHappened();
        return Optional.ofNullable(failed.getMessage()).filter(message -> !message.isBlank()).orElse(failed.toString());
    }
}
