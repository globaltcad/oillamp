package dev.gui.model;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import sprouts.Tuple;

/// What a genie's lamp asked the user, or told them, while it was lit, and what became of it.
///
/// Nothing leaves when the user answers, or when the lamp goes out: a question is kept with its
/// answer, a link with whether it still works. The banner under the genie's header shows what
/// still waits for the user, [#waitingAt] and [#links]; the rest is kept for the user to look
/// back on, up to [#MOST].
///
/// @param all every notification, oldest first
public record Notifications(Tuple<Notification> all) {

    public static final Notifications NONE = new Notifications(Tuple.of(Notification.class));

    /// How many notifications a genie keeps. Past that, the oldest that need nothing of the user
    /// any more go.
    public static final int MOST = 200;

    /// How long the lamp waits for the user to answer a question. It refuses the request after that.
    public static final Duration PATIENCE = Duration.ofSeconds(60);

    /// One thing the lamp asked or told the user.
    ///
    /// @param about  what it asked or told
    /// @param at     when
    /// @param status what became of it, as far as Genies knows
    public record Notification(About about, Instant at, Status status) {

        /// What became of it at `now`: a question nobody answered in time was refused by the lamp,
        /// though Genies heard nothing of that.
        public Status statusAt(Instant now) {
            boolean expired = !at.plus(PATIENCE).isAfter(now);
            return status == Status.WAITING && expired ? Status.UNANSWERED : status;
        }

        Notification with(Status status) { return new Notification(about, at, status); }
    }

    /// What the lamp asked or told the user.
    public sealed interface About {}

    /// A request the lamp asks the user to allow. The user's answer names it by `what()`.
    public sealed interface Question extends About {
        /// What is about to happen, in the lamp's words.
        String what();
    }

    /// A request of the agent's to reach this computer with `tool`, such as `host_bash`, which
    /// the checker model allowed already, and which needs the user too.
    public record HostTool(String tool, String what) implements Question {}

    /// Publishing the web server the agent started on `sandboxPort` in its sandbox to this
    /// computer's browser.
    public record WebPort(int sandboxPort, String what) implements Question {}

    /// A web server in the genie's sandbox that its lamp published to this computer's browser.
    ///
    /// @param name what the lamp calls it, such as `opencode` or `port-5173`
    /// @param link what to open in a browser, such as `http://k3v7x2ab.localhost:41237/`
    public record Published(String name, URI link) implements About {}

    /// What became of a notification.
    public enum Status {
        /// A question the lamp waits on the user's answer to.
        WAITING,
        /// A question the user allowed.
        ALLOWED,
        /// A question the user refused.
        REFUSED,
        /// A question nobody answered: the lamp refused it after [#PATIENCE], or went out first.
        UNANSWERED,
        /// A link that works, shown under the genie's header.
        SHOWN,
        /// A link that works, which the user put away from under the header.
        PUT_AWAY,
        /// A link that no longer works: its lamp went out, or published the server anew.
        GONE;

        /// Whether the user can still do something about it: answer it, or open its link.
        public boolean open() { return this == WAITING || this == SHOWN || this == PUT_AWAY; }
    }

    /// The lamp asks `question`, at `at`.
    public Notifications asked(Question question, Instant at) {
        return added(new Notification(question, at, Status.WAITING));
    }

    /// The user allowed or refused the question the lamp described as `what`.
    public Notifications decided(String what, boolean allowed) {
        return new Notifications(all.map(it ->
            it.status() == Status.WAITING && it.about() instanceof Question question && question.what().equals(what)
                ? it.with(allowed ? Status.ALLOWED : Status.REFUSED)
                : it
        ));
    }

    /// The questions the lamp still waits on the user's answer to at `now`, oldest first.
    public Tuple<Notification> waitingAt(Instant now) {
        return all.retainIf(it -> it.statusAt(now) == Status.WAITING);
    }

    /// The lamp published `server`, at `at`. A link it published before under that name no
    /// longer works.
    public Notifications published(Published server, Instant at) {
        Notifications replaced = new Notifications(all.map(it ->
            it.status().open() && it.about() instanceof Published earlier && earlier.name().equals(server.name())
                ? it.with(Status.GONE)
                : it
        ));
        return replaced.added(new Notification(server, at, Status.SHOWN));
    }

    /// The links shown under the genie's header, oldest first.
    public Tuple<Notification> links() {
        return all.retainIf(it -> it.status() == Status.SHOWN);
    }

    /// The user put the link `shown` away from under the genie's header. It still works.
    public Notifications putAway(Notification shown) {
        return new Notifications(all.map(it -> it.equals(shown) && it.status() == Status.SHOWN ? it.with(Status.PUT_AWAY) : it));
    }

    /// The genie's lamp went out: no question of it waits any more, and none of its links works.
    public Notifications wentOut() {
        return new Notifications(all.map(it -> switch (it.status()) {
            case WAITING -> it.with(Status.UNANSWERED);
            case SHOWN, PUT_AWAY -> it.with(Status.GONE);
            default -> it;
        }));
    }

    /// With `added`, and, past [#MOST], without the oldest that are no longer open.
    private Notifications added(Notification added) {
        Tuple<Notification> more = all.add(added);
        int surplus = more.size() - MOST;
        for (int i = 0; surplus > 0 && i < more.size(); ) {
            if (more.get(i).status().open()) i++;
            else { more = more.removeAt(i); surplus--; }
        }
        return new Notifications(more);
    }
}
