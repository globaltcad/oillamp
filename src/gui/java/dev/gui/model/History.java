package dev.gui.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sprouts.HasId;
import sprouts.Tuple;

/// A genie's history as its page shows it: the moments oillamp saved the genie's home at, newest
/// first, on every branch of the history. A branch is a line of moments of its own, with its own
/// home: the genie lives on one of them at a time, and every save goes to that one. The genie can
/// go back to any moment of the branch it lives on.
///
/// Read from the genie's lamp, awake or asleep. Going back, moving to another branch and resetting
/// need the genie asleep. Going back saves how it is first, so it can itself be undone; a reset
/// does not.
///
/// @param read     whether it was read from the lamp yet; before that, the page says it is looking
/// @param reading  the number of the newest reading shown. A reading that started earlier, and
///                 finished later, is older news, and changes nothing
/// @param all      every moment on any branch, newest first, each once
/// @param branches every branch, by name
/// @param line     the branch whose line the page shows with its moments' words; empty for the
///                 branch the genie lives on. Showing a line changes only what the page shows
/// @param picked   the id of the moment whose card is open, with its buttons; empty for none
/// @param earlier  whether every moment of the line is shown, not only the newest [#SHOWN]
/// @param busy     what is on its way to the lamp, such as "Saving…"; empty while nothing is
/// @param note     what the last save came to when it saved nothing; empty otherwise
/// @param problem  why the last change failed, in the lamp's words; empty when it did not
/// @param plan     what a change the user asked for would throw away, as the lamp said, until
///                 the user confirms or drops it; empty otherwise
/// @param autosave when oillamp saves by itself, as the lamp's file says; empty before it was read,
///                 and while the genie has no lamp yet, since one that never woke has none
/// @param maxGrowthGb how far the history may grow beyond its first moment, in GB, as the lamp's
///                 file says; empty before it was read, and while the genie has no lamp yet
/// @param pruned   what oillamp last deleted to keep the history within that size; empty until it did
public record History(
    boolean read,
    long reading,
    Tuple<Moment> all,
    Tuple<Branch> branches,
    String line,
    String picked,
    boolean earlier,
    String busy,
    String note,
    String problem,
    Optional<Plan> plan,
    Optional<Autosave> autosave,
    Optional<Double> maxGrowthGb,
    Optional<Pruned> pruned
) {

    /// When oillamp saves the genie's home by itself, as `[autosave]` in its lamp's `oillamp.toml`
    /// says. A save by hand and the save before going back happen whatever these say. The lamp
    /// reads them as it wakes, so they can only be changed while the genie sleeps.
    ///
    /// @param waking    as its sandbox starts
    /// @param sleeping  after its sandbox stopped
    /// @param beforeRun as it begins on a question or a job, when something changed since the last moment
    /// @param afterRun  as it ends one, with its last words. A job's prompt tells the genie what
    ///                  its last runs said and changed from these
    /// @param chatQuietMinutes how long a chat must have been quiet before it is saved, once for
    ///                  all its questions; 0 saves around each question as around a job
    public record Autosave(boolean waking, boolean sleeping, boolean beforeRun, boolean afterRun, int chatQuietMinutes) {

        public Autosave withWaking(boolean waking)       { return new Autosave(waking, sleeping, beforeRun, afterRun, chatQuietMinutes); }
        public Autosave withSleeping(boolean sleeping)   { return new Autosave(waking, sleeping, beforeRun, afterRun, chatQuietMinutes); }
        public Autosave withBeforeRun(boolean beforeRun) { return new Autosave(waking, sleeping, beforeRun, afterRun, chatQuietMinutes); }
        public Autosave withAfterRun(boolean afterRun)   { return new Autosave(waking, sleeping, beforeRun, afterRun, chatQuietMinutes); }
        public Autosave withChatQuietMinutes(int chatQuietMinutes) { return new Autosave(waking, sleeping, beforeRun, afterRun, chatQuietMinutes); }

        /// When it saves, as the end of a sentence such as "Saved whenever Jafar …": "wakes,
        /// answers or sleeps", or "answers"; empty when it never saves by itself.
        public String when(Lang lang) {
            Lang.HistoryPage words = lang.HISTORY_PAGE();
            List<String> moments = new ArrayList<>();
            if (waking) moments.add(words.WAKES());
            if (afterRun) moments.add(words.ANSWERS());
            else if (beforeRun) moments.add(words.BEGINS_TO_ANSWER());
            if (sleeping) moments.add(words.SLEEPS());
            return words.ONE_OR_ANOTHER(moments);
        }
    }

    /// One saved state of the genie's home.
    ///
    /// @param id           oillamp's id for it, 40 hexadecimal characters
    /// @param message      for [Kind#SAVED], what the user wrote, or nothing; for [Kind#RAN], a
    ///                     line naming the run, then a blank line and the genie's last words; for
    ///                     [Kind#WENT_BACK], `back to ` and the first eight characters of the id of
    ///                     the moment it went back to
    /// @param run          the run, such as `run-12`, for [Kind#BEFORE_RUN] and [Kind#RAN], or
    ///                     the last question's for [Kind#CHATTED]; empty otherwise
    /// @param job          for [Kind#RAN] of a job's run, the job, such as `job-3`; empty otherwise
    /// @param outcome      how the run ended, for [Kind#RAN]
    /// @param conversation for [Kind#RAN] and [Kind#CHATTED], pi's id for the run's conversation;
    ///                     empty when it had none
    /// @param branches     the branches it is on, by name in alphabetical order: the one it was
    ///                     saved on, and every branch started there or later
    public record Moment(
        String id,
        Instant at,
        Kind kind,
        String message,
        String run,
        String job,
        Optional<Schedule.Outcome> outcome,
        String conversation,
        Tuple<String> branches
    ) implements HasId<String> {

        /// For [Kind#RAN], the genie's last words in the run; empty otherwise, or when it said nothing.
        public String said() {
            int body = message.indexOf("\n\n");
            return kind != Kind.RAN || body < 0 ? "" : message.substring(body + 2).strip();
        }

        /// More about it, on one line; empty when there is nothing more to say.
        public String detail(Lang lang) {
            Lang.HistoryPage words = lang.HISTORY_PAGE();
            String said = Schedule.firstLine(said(), 160);
            return switch (kind) {
                case WOKE -> words.SAVED_AS_ITS_SANDBOX_STARTED();
                case SLEPT -> words.SAVED_AFTER_ITS_SANDBOX_STOPPED();
                case SAVED -> message.isBlank() ? "" : words.SAVED_BY_HAND();
                case BEFORE_RUN -> words.SAVED_AS_IT_BEGAN_ON_A_QUESTION_OR_A_JOB();
                case CHATTED -> words.SAVED_ONCE_THE_CHAT_HAD_GONE_QUIET();
                case RAN -> switch (outcome.orElse(Schedule.Outcome.FAILED)) {
                    case FINISHED -> said;
                    case STOPPED -> words.STOPPED_AND_SAID(said);
                    case FAILED -> words.FAILED_AND_SAID(said);
                    case TIMED_OUT -> words.RAN_OUT_OF_TIME_AND_SAID(said);
                };
                case BEFORE_GOING_BACK -> words.KEPT_SO_THAT_GOING_BACK_CAN_BE_UNDONE();
                case WENT_BACK -> "";
                case BEFORE_SWITCHING -> words.KEPT_AS_IT_WAS_WHEN_IT_MOVED_TO_ANOTHER_BRANCH();
            };
        }

        public boolean isOn(String branch) { return branches.contains(branch); }
    }

    public enum Kind {
        /// oillamp saved as the genie's sandbox started.
        WOKE,
        /// oillamp saved after the genie's sandbox stopped.
        SLEPT,
        /// The user saved, from Genies or with `oillamp save`.
        SAVED,
        /// oillamp saved as a run began, because something had changed since the last moment.
        BEFORE_RUN,
        /// oillamp saved as a run ended: an answer to the user, or a job's run.
        RAN,
        /// oillamp saved the user's questions together, once their chat had gone quiet, or as
        /// the sandbox stopped.
        CHATTED,
        /// oillamp saved just before going back, so that going back can be undone.
        BEFORE_GOING_BACK,
        /// The genie went back to an earlier moment.
        WENT_BACK,
        /// oillamp saved just before moving the genie to another branch, on the branch it left,
        /// so that nothing changed since that branch's newest moment is lost.
        BEFORE_SWITCHING
    }

    /// One branch of the history.
    ///
    /// @param name    such as `main` or `rust-rewrite`
    /// @param lampIsOn whether the genie lives on it: its home is this branch's, and every save goes to it
    /// @param newest  the id of the branch's newest moment; empty for a branch with none yet
    public record Branch(String name, boolean lampIsOn, String newest) implements HasId<String> {
        @Override public String id() { return name; }
    }

    /// What a change the user asked for would throw away, as the lamp said when Genies asked it
    /// first. The page shows it, and the change happens only once the user confirms.
    public sealed interface Plan {
        /// Resetting the branch the genie lives on back to the moment `target`, as
        /// `LampDirectory.reset` does.
        ///
        /// @param moments how many moments after `target` would no longer be on the branch
        /// @param unsaved whether the genie's home has changes no moment holds, which would be lost
        record Reset(String target, int moments, boolean unsaved) implements Plan {}
        /// Deleting the branch `branch`.
        ///
        /// @param moments how many moments are on that branch and on no other
        record DeleteBranch(String branch, int moments) implements Plan {}
        /// Deleting the moments saved longest ago, to keep the history within its size.
        ///
        /// @param moments  how many moments would be deleted
        /// @param branches the branches they are on, in alphabetical order
        /// @param bytesFreed how many bytes that frees on disk
        record Prune(int moments, Tuple<String> branches, long bytesFreed) implements Plan {}
    }

    /// What oillamp deleted, the last time it kept the history within its size.
    ///
    /// @param moments        how many moments it deleted
    /// @param bytesFreed     how many bytes that freed on disk
    /// @param remainingBytes how many bytes the history takes now
    /// @param limitBytes     how many it may take: its first moment's, and how far it may grow beyond
    public record Pruned(int moments, long bytesFreed, long remainingBytes, long limitBytes) {}

    /// How many moments of a line are shown while [#earlier] is off.
    public static final int SHOWN = 40;

    /// The first branch, which every lamp has, and the one it lives on before it was ever saved.
    public static final String FIRST_BRANCH = "main";

    /// The longest name a branch can have.
    public static final int LONGEST_BRANCH_NAME = 64;

    public static final History UNREAD = new History(
        false, 0, Tuple.of(Moment.class), Tuple.of(Branch.class), "", "",
        false, "", "", "", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
    );

    public History withPicked(String picked)    { return new History(read, reading, all, branches, line, picked, earlier, busy, note, problem, plan, autosave, maxGrowthGb, pruned); }
    public History withEarlier(boolean earlier) { return new History(read, reading, all, branches, line, picked, earlier, busy, note, problem, plan, autosave, maxGrowthGb, pruned); }
    public History withBusy(String busy)        { return new History(read, reading, all, branches, line, picked, earlier, busy, note, problem, plan, autosave, maxGrowthGb, pruned); }
    public History withNote(String note)        { return new History(read, reading, all, branches, line, picked, earlier, busy, note, problem, plan, autosave, maxGrowthGb, pruned); }
    public History withProblem(String problem)  { return new History(read, reading, all, branches, line, picked, earlier, busy, note, problem, plan, autosave, maxGrowthGb, pruned); }
    public History withPlan(Optional<Plan> plan) { return new History(read, reading, all, branches, line, picked, earlier, busy, note, problem, plan, autosave, maxGrowthGb, pruned); }
    public History withAutosave(Optional<Autosave> autosave) { return new History(read, reading, all, branches, line, picked, earlier, busy, note, problem, plan, autosave, maxGrowthGb, pruned); }
    public History withMaxGrowthGb(Optional<Double> maxGrowthGb) { return new History(read, reading, all, branches, line, picked, earlier, busy, note, problem, plan, autosave, maxGrowthGb, pruned); }
    public History withPruned(Optional<Pruned> pruned) { return new History(read, reading, all, branches, line, picked, earlier, busy, note, problem, plan, autosave, maxGrowthGb, pruned); }

    /// What the lamp held when the reading numbered `reading` started: its branches, and every
    /// moment on them. A reading that started before the one shown changes nothing, so readings
    /// that finish out of order leave the newest. The line shown stays while its branch is there,
    /// and the picked moment while it is on that line. A plan stays only while the history is as
    /// it was when the lamp said what the change would throw away: after a save, say, the numbers
    /// it gave may no longer hold, and the user is asked again.
    public History readAs(long reading, Tuple<Branch> branches, Tuple<Moment> all) {
        if (read && reading <= this.reading) return this;
        History fresh = new History(
            true, reading, all, branches, "", "", earlier, busy, note, problem, Optional.empty(), autosave, maxGrowthGb, pruned
        );
        boolean lineThere = branches.any(it -> it.name().equals(line) && !it.lampIsOn());
        History shown = lineThere ? fresh.withLine(line) : fresh;
        boolean pickedThere = shown.onLine(shown.shownLine()).any(it -> it.id().equals(picked));
        // What the lamp said a change would throw away holds only for the history it said it of.
        boolean unchanged = all.equals(this.all) && branches.equals(this.branches);
        return shown.withPicked(pickedThere ? picked : "").withPlan(unchanged ? plan : Optional.empty());
    }

    private History withLine(String line) { return new History(read, reading, all, branches, line, picked, earlier, busy, note, problem, plan, autosave, maxGrowthGb, pruned); }

    /// Shows the line of the branch `branch`, with its moments' words. The genie stays on the
    /// branch it lives on. The picked moment stays picked while it is on that line too.
    public History showLine(String branch) {
        if (branches.none(it -> it.name().equals(branch))) return this;
        String shown = branch.equals(lampBranch()) ? "" : branch;
        boolean pickedThere = onLine(branch).any(it -> it.id().equals(picked));
        return withLine(shown).withPicked(pickedThere ? picked : "");
    }

    /// The branch the genie lives on: [#FIRST_BRANCH] until the history was read.
    public String lampBranch() {
        return branches.stream().filter(Branch::lampIsOn).map(Branch::name).findFirst().orElse(FIRST_BRANCH);
    }

    /// The branch whose line the page shows with its moments' words.
    public String shownLine() {
        return line.isEmpty() ? lampBranch() : line;
    }

    /// The moments on the branch the genie lives on, newest first: those it can go back to.
    public Tuple<Moment> moments() {
        return onLine(lampBranch());
    }

    /// The moments on the branch `branch`, newest first.
    public Tuple<Moment> onLine(String branch) {
        return all.removeIf(it -> !it.isOn(branch));
    }

    public Optional<Branch> branch(String name) {
        return branches.stream().filter(it -> it.name().equals(name)).findFirst();
    }

    /// Picks `id`, or unpicks it when it is picked already.
    public History pick(String id) {
        return withPicked(picked.equals(id) ? "" : id);
    }

    public Optional<Moment> find(String id) {
        for (Moment moment : all) if (moment.id().equals(id)) return Optional.of(moment);
        return Optional.empty();
    }

    /// How many older moments of the line shown are left out while [#earlier] is off.
    public int hidden() {
        return earlier ? 0 : Math.max(0, onLine(shownLine()).size() - SHOWN);
    }

    /// The lines the page draws, with the moments of the shown one by the day they were saved on
    /// `zone`'s clock, the newest day first. See [Lines].
    public Lines lines(ZoneId zone) {
        return Lines.of(this, zone);
    }

    /// The moment that was the newest when the genie last went back, while going back is the last
    /// thing that changed it: going back there undoes it. That is oillamp's save before going
    /// back, or, when nothing had changed since the moment before, that moment. Waking, sleeping
    /// and what changed before a run do not count; an answer or a save does, and then there is
    /// nothing to undo. Nor is there after an undo: both ways are in the history. Only on the
    /// branch the genie lives on, since it can go back only there.
    public Optional<Moment> undo() {
        Tuple<Moment> moments = moments();
        for (int i = 0; i < moments.size(); i++) {
            switch (moments.get(i).kind()) {
                case WOKE, SLEPT, BEFORE_RUN -> { }
                case WENT_BACK -> {
                    boolean last = i + 1 >= moments.size();
                    return last || undid(moments.get(i)).isPresent() ? Optional.empty() : Optional.of(moments.get(i + 1));
                }
                default -> { return Optional.empty(); }
            }
        }
        return Optional.empty();
    }

    /// What `moment` is about, on one line, such as "Answered: Plot the sales figures" or "Went
    /// back to how it was yesterday at 14:05".
    ///
    /// @param genie whose conversations and jobs give runs their titles
    /// @param now   on the clock of the genie's schedule, against which days are named
    /// @param lang  the words to say it in
    public String title(Moment moment, Genie genie, LocalDateTime now, Lang lang) {
        return title(moment, genie.conversations(), genie.schedule(), now, lang);
    }

    /// What `moment` is about, as [#title(Moment, Genie, LocalDateTime, Lang)] says it, from only
    /// what that reads: the genie's conversations and its schedule.
    public String title(Moment moment, Conversations conversations, Schedule schedule, LocalDateTime now, Lang lang) {
        Lang.HistoryPage words = lang.HISTORY_PAGE();
        return switch (moment.kind()) {
            case WOKE -> words.WOKE_UP();
            case SLEPT -> words.WENT_TO_SLEEP();
            case SAVED -> moment.message().isBlank() ? words.SAVED_BY_HAND() : Schedule.firstLine(moment.message(), 90);
            case BEFORE_RUN -> words.BEFORE_IT_WENT_TO_WORK();
            case RAN -> !moment.job().isEmpty()
                ? schedule.job(moment.job()).map(job -> words.DID_THE_JOB(job.title())).orElse(words.DID_A_SCHEDULED_JOB())
                : conversations.find(moment.conversation()).map(it -> words.ANSWERED(it.title(lang))).orElse(words.ANSWERED_A_QUESTION());
            case CHATTED -> conversations.find(moment.conversation()).map(it -> words.TALKED(it.title(lang))).orElse(words.TALKED_WITH_YOU());
            case BEFORE_GOING_BACK -> words.BEFORE_GOING_BACK();
            case WENT_BACK ->
                undid(moment)
                .flatMap(this::wentBackTo)
                .map(it -> words.UNDID_GOING_BACK_TO_HOW_IT_WAS(when(it.at(), schedule.zone(), now, lang)))
                .or(() -> wentBackTo(moment).map(it -> words.WENT_BACK_TO_HOW_IT_WAS(when(it.at(), schedule.zone(), now, lang))))
                .orElse(words.WENT_BACK_TO_AN_EARLIER_MOMENT());
            case BEFORE_SWITCHING -> words.BEFORE_IT_MOVED_TO_ANOTHER_BRANCH();
        };
    }

    /// The moment a [Kind#WENT_BACK] went back to, while it is in the history.
    public Optional<Moment> wentBackTo(Moment wentBack) {
        String said = wentBack.message().strip();
        if (wentBack.kind() != Kind.WENT_BACK || !said.startsWith("back to ")) return Optional.empty();
        String start = said.substring("back to ".length()).split("\\s", 2)[0];
        if (start.length() < 4) return Optional.empty();
        for (Moment moment : all) if (moment.id().startsWith(start)) return Optional.of(moment);
        return Optional.empty();
    }

    /// The going back that `wentBack` undid: the newest going back before it, while it went back to
    /// the moment that one left, with nothing in between but waking, sleeping and what changed
    /// before a run. Empty for an ordinary going back.
    public Optional<Moment> undid(Moment wentBack) {
        Optional<Moment> target = wentBackTo(wentBack);
        Tuple<Moment> moments = moments();
        boolean older = false;
        for (int i = 0; i < moments.size() && target.isPresent(); i++) {
            Moment moment = moments.get(i);
            if (!older) { older = moment.id().equals(wentBack.id()); continue; }
            switch (moment.kind()) {
                case WOKE, SLEPT, BEFORE_RUN, BEFORE_GOING_BACK, BEFORE_SWITCHING -> { }
                case WENT_BACK -> {
                    boolean leftThere = i + 1 < moments.size() && moments.get(i + 1).id().equals(target.get().id());
                    return leftThere ? Optional.of(moment) : Optional.empty();
                }
                default -> { return Optional.empty(); }
            }
        }
        return Optional.empty();
    }

    /// Why `name` cannot be a new branch's name, in a sentence; empty when it can. These are
    /// oillamp's rules, checked here so that the user learns them as they type rather than from a
    /// refusal: lowercase letters, digits, `-`, `_` and `.`, beginning with a letter or a digit,
    /// at most [#LONGEST_BRANCH_NAME] characters, and not a name a branch has already.
    public Optional<String> branchNameProblem(String name, Lang lang) {
        Lang.HistoryPage words = lang.HISTORY_PAGE();
        if (name.isEmpty())
            return Optional.of(words.A_BRANCH_NEEDS_A_NAME());
        if (name.length() > LONGEST_BRANCH_NAME)
            return Optional.of(words.A_BRANCHS_NAME_IS_AT_MOST_SO_MANY_CHARACTERS_LONG(LONGEST_BRANCH_NAME));
        if (!name.matches("[a-z0-9][a-z0-9._-]*"))
            return Optional.of(words.A_BRANCHS_NAME_IS_MADE_OF_LOWERCASE_LETTERS_DIGITS_AND_SIGNS());
        if (name.contains("..") || name.endsWith(".") || name.endsWith(".lock"))
            return Optional.of(words.A_BRANCHS_NAME_CANNOT_HOLD_TWO_DOTS_OR_END_IN_A_DOT_OR_LOCK());
        if (branches.any(it -> it.name().equals(name)))
            return Optional.of(words.THERE_IS_A_BRANCH_CALLED_IT_ALREADY(name));
        return Optional.empty();
    }

    /// "today at 14:05", "yesterday at 09:30", "on Thursday 1 October at 18:00".
    public static String when(Instant at, ZoneId zone, LocalDateTime now, Lang lang) {
        LocalDateTime then = LocalDateTime.ofInstant(at, zone);
        return lang.DATES().AT(DateWordingUtil.dayInSentence(then.toLocalDate(), now.toLocalDate(), lang.DATES()), Recurrence.clock(then.toLocalTime()));
    }

    /// The day `at` was on, on `zone`'s clock.
    static LocalDate dayOf(Instant at, ZoneId zone) {
        return LocalDate.ofInstant(at, zone);
    }
}
