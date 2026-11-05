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
/// back on, up to [#MOST], until they remove it.
///
/// Some of it the lamp only tells, and needs nothing of the user: what the checker model decided
/// about a request of the agent's, a request refused, a warning. Those are [Status#TOLD].
///
/// @param all  every notification, oldest first
/// @param seen until when the user has looked at them: the ones after came since
public record Notifications(Tuple<Notification> all, Instant seen) {

    public static final Notifications NONE = new Notifications(Tuple.of(Notification.class), Instant.EPOCH);

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

        /// Whether it is worth the user's attention when it comes: something it asks of them, or
        /// something that went against what the agent wanted.
        public boolean important() {
            return switch (about) {
                case Question _, Refused _, Warned _ -> true;
                case Judged judged -> !judged.allowed();
                case Published _ -> false;
            };
        }
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

    /// The checker model judged a request of the agent's to reach this computer.
    ///
    /// @param tool    the host tool the agent named, such as `host_read_file`
    /// @param said    what it asked, in one line, as the lamp put it; empty when the lamp did not say
    /// @param allowed whether the checker allowed it
    /// @param risk    the tier the checker judged it at, such as `PASSIVE`, as the lamp names it
    /// @param reason  why, in the checker's own words
    public record Judged(String tool, String said, boolean allowed, String risk, String reason) implements About {}

    /// A request of the agent's to reach this computer was refused other than by the checker's
    /// judgement: by a rule, by the user, or because this computer could not do it.
    ///
    /// @param tool the host tool the agent named
    /// @param why  why, as the lamp put it
    public record Refused(String tool, String why) implements About {}

    /// Something went wrong in the lamp, but it carried on.
    ///
    /// @param title        what, in short, as the lamp put it
    /// @param whatHappened what happened, as the lamp put it
    public record Warned(String title, String whatHappened) implements About {}

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
        GONE,
        /// Something the lamp only told the user, which needs nothing of them.
        TOLD;

        /// Whether the user can still do something about it: answer it, or open its link.
        public boolean open() { return this == WAITING || this == SHOWN || this == PUT_AWAY; }
    }

    /// The lamp asks `question`, at `at`.
    public Notifications asked(Question question, Instant at) {
        return added(new Notification(question, at, Status.WAITING));
    }

    /// The lamp tells the user `about`, at `at`, and needs nothing of them.
    public Notifications told(About about, Instant at) {
        return added(new Notification(about, at, Status.TOLD));
    }

    /// The user allowed or refused the question the lamp described as `what`.
    public Notifications decided(String what, boolean allowed) {
        return new Notifications(all.map(it ->
            it.status() == Status.WAITING && it.about() instanceof Question question && question.what().equals(what)
                ? it.with(allowed ? Status.ALLOWED : Status.REFUSED)
                : it
        ), seen);
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
        ), seen);
        return replaced.added(new Notification(server, at, Status.SHOWN));
    }

    /// The links shown under the genie's header, oldest first.
    public Tuple<Notification> links() {
        return all.retainIf(it -> it.status() == Status.SHOWN);
    }

    /// The user put the link `shown` away from under the genie's header. It still works.
    public Notifications putAway(Notification shown) {
        return new Notifications(all.map(it -> it.equals(shown) && it.status() == Status.SHOWN ? it.with(Status.PUT_AWAY) : it), seen);
    }

    /// The genie's lamp went out: no question of it waits any more, and none of its links works.
    public Notifications wentOut() {
        return new Notifications(all.map(it -> switch (it.status()) {
            case WAITING -> it.with(Status.UNANSWERED);
            case SHOWN, PUT_AWAY -> it.with(Status.GONE);
            default -> it;
        }), seen);
    }

    /// Without `removed`. A question still waiting stays: the lamp waits for its answer.
    public Notifications without(Notification removed) {
        return new Notifications(all.removeIf(it -> it.equals(removed) && it.status() != Status.WAITING), seen);
    }

    /// Without any, but the questions the lamp still waits on the user's answer to at `now`. A
    /// link that still works goes too: the user can no longer open it from here.
    public Notifications cleared(Instant now) {
        return new Notifications(all.retainIf(it -> it.statusAt(now) == Status.WAITING), seen);
    }

    /// The user looked at them at `now`.
    public Notifications seenAt(Instant now) {
        return new Notifications(all, now.isAfter(seen) ? now : seen);
    }

    /// Whether something worth the user's attention came since they last looked, or a question
    /// still waits on them at `now`.
    public boolean needsALook(Instant now) {
        return !waitingAt(now).isEmpty() || all.any(it -> it.at().isAfter(seen) && it.important());
    }

    /// How many came since the user last looked.
    public int unseen() {
        return (int) all.stream().filter(it -> it.at().isAfter(seen)).count();
    }

    /// With `added`, and, past [#MOST], without the oldest that are no longer open.
    private Notifications added(Notification added) {
        Tuple<Notification> more = all.add(added);
        int surplus = more.size() - MOST;
        for (int i = 0; surplus > 0 && i < more.size(); ) {
            if (more.get(i).status().open()) i++;
            else { more = more.removeAt(i); surplus--; }
        }
        return new Notifications(more, seen);
    }
}
