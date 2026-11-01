package dev.gui.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import sprouts.HasId;
import sprouts.Tuple;

/// A genie's schedule as its page shows it: the jobs that wake it, the runs they had, and the
/// job being written.
///
/// Read from the genie's lamp, awake or asleep. Jobs only run while the genie is awake.
///
/// @param read    whether it was read from the lamp yet; before that, the page says it is looking
/// @param paused  whether the user paused the whole schedule
/// @param zone    the time zone the jobs' times are on, this computer's
/// @param jobs    in the order they were added
/// @param runs    the runs jobs had over the last week, the most recent last
/// @param running the run a job has going now, if any
/// @param draft   the job being written or changed, while the page shows its editor
/// @param picked  the job whose times the timeline brings forward; empty for none
/// @param earlier whether the timeline shows every run of the last week, not only the last few
/// @param problem what could not be done last, with why in the lamp's words; empty when it could
/// @param busy    whether a change is on its way to the lamp
public record Schedule(
    boolean read,
    boolean paused,
    ZoneId zone,
    Tuple<Job> jobs,
    Tuple<Run> runs,
    Optional<Running> running,
    Optional<JobDraft> draft,
    String picked,
    boolean earlier,
    Optional<Problem> problem,
    boolean busy
) {

    /// What could not be done last, which the page says until the next change. Each says why in
    /// `reason`, in the words of the lamp, which stay as oillamp put them; what could not be done
    /// is said in the language Genies speaks with [#words(Lang)].
    public sealed interface Problem {
        /// Why it failed, as oillamp said.
        String reason();

        /// Reading the jobs and their runs.
        record CouldNotRead(String reason) implements Problem {}
        /// Adding the job the user wrote, or changing one.
        record CouldNotAdd(String reason) implements Problem {}
        /// Taking a job off the schedule.
        record CouldNotRemove(String reason) implements Problem {}
        /// Switching a job on.
        record CouldNotSwitchOn(String reason) implements Problem {}
        /// Switching a job off.
        record CouldNotSwitchOff(String reason) implements Problem {}
        /// Pausing the whole schedule.
        record CouldNotPause(String reason) implements Problem {}
        /// Letting the whole schedule run again.
        record CouldNotResume(String reason) implements Problem {}

        /// What could not be done and why, in `lang`.
        default String words(Lang lang) {
            Lang.SchedulePage words = lang.SCHEDULE_PAGE();
            return switch (this) {
                case CouldNotRead it -> words.THE_SCHEDULE_COULD_NOT_BE_READ(it.reason());
                case CouldNotAdd it -> words.OILLAMP_COULD_NOT_ADD_IT(it.reason());
                case CouldNotRemove it -> words.THE_JOB_COULD_NOT_BE_REMOVED(it.reason());
                case CouldNotSwitchOn it -> words.THE_JOB_COULD_NOT_BE_SWITCHED_ON(it.reason());
                case CouldNotSwitchOff it -> words.THE_JOB_COULD_NOT_BE_SWITCHED_OFF(it.reason());
                case CouldNotPause it -> words.THE_SCHEDULE_COULD_NOT_BE_PAUSED(it.reason());
                case CouldNotResume it -> words.THE_SCHEDULE_COULD_NOT_BE_RESUMED(it.reason());
            };
        }
    }

    /// A job as oillamp lists it, read into the page's terms.
    ///
    /// @param repeats how it repeats; empty for a job that runs once
    /// @param at      when a job that runs once runs; empty for a repeating one
    /// @param next    when it runs next; empty when it is switched off. At or before now, it came
    ///                due and runs as soon as the genie is awake
    /// @param upcoming when it runs over the next seven days, after now, earliest first
    /// @param byGenie whether the genie added it itself
    /// @param expires when it comes off the schedule; empty for never
    public record Job(
        String id,
        Optional<Recurrence> repeats,
        Optional<Instant> at,
        Optional<Instant> next,
        Tuple<Instant> upcoming,
        String prompt,
        boolean byGenie,
        Optional<Instant> expires,
        boolean enabled
    ) implements HasId<String> {

        /// The first line of its prompt, cut at 90 characters.
        public String title() { return firstLine(prompt, 90); }

        /// When it runs, in words, such as "Every weekday at 09:00" or "Once, tomorrow at 09:00".
        public String when(LocalDateTime now, ZoneId zone, Lang lang) {
            if (repeats.isPresent()) return repeats.get().describe(lang);
            return at.map(time -> LocalDateTime.ofInstant(time, zone))
                     .map(time -> lang.SCHEDULE_PAGE().ONCE_AT(
                         DateWordingUtil.dayInSentence(time.toLocalDate(), now.toLocalDate(), lang.DATES()),
                         Recurrence.clock(time.toLocalTime())
                     ))
                     .orElse(lang.SCHEDULE_PAGE().ONCE_WITHOUT_A_TIME());
        }
    }

    /// A job's run that ended, as the lamp's history records it.
    ///
    /// @param id           such as `run-12`
    /// @param job          such as `job-3`
    /// @param ended        when it ended
    /// @param said         the genie's last message in it; empty when it said nothing
    /// @param conversation pi's id for the conversation it had; empty when it did not get that far
    public record Run(String id, String job, Instant ended, Outcome outcome, String said, Optional<String> conversation) {}

    public enum Outcome { FINISHED, FAILED, TIMED_OUT, STOPPED }

    /// The run a job has going now.
    ///
    /// @param prompt the job's prompt, as written
    /// @param since  when it began
    public record Running(String run, String job, String prompt, Instant since) {

        /// The first line of the job's prompt, cut at 90 characters.
        public String title() { return firstLine(prompt, 90); }
    }

    public static Schedule unread(ZoneId zone) {
        return new Schedule(false, false, zone, Tuple.of(Job.class), Tuple.of(Run.class), Optional.empty(),
                            Optional.empty(), "", false, Optional.empty(), false);
    }

    public Schedule withPaused(boolean paused)            { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withJobs(Tuple<Job> jobs)             { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withRunning(Optional<Running> running) { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withDraft(Optional<JobDraft> draft)   { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withPicked(String picked)             { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withEarlier(boolean earlier)          { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }
    public Schedule withProblem(Problem problem)          { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, Optional.of(problem), busy); }
    public Schedule noProblem()                           { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, Optional.empty(), busy); }
    public Schedule withBusy(boolean busy)                { return new Schedule(read, paused, zone, jobs, runs, running, draft, picked, earlier, problem, busy); }

    /// What the lamp holds now. The job being written, and the job picked if it is still there,
    /// stay as they were.
    public Schedule readAs(boolean paused, ZoneId zone, Tuple<Job> jobs, Tuple<Run> runs) {
        String stillPicked = jobs.any(job -> job.id().equals(picked)) ? picked : "";
        return new Schedule(true, paused, zone, jobs, runs, running, draft, stillPicked, earlier, problem, busy);
    }

    public Optional<Job> job(String id) {
        for (Job job : jobs) if (job.id().equals(id)) return Optional.of(job);
        return Optional.empty();
    }

    /// Picks `id`, or unpicks it when it is picked already.
    public Schedule pick(String id) {
        return withPicked(picked.equals(id) ? "" : id);
    }

    /// Opens the editor on a new job.
    public Schedule writeNew(LocalDateTime now) {
        return withDraft(Optional.of(JobDraft.fresh(now))).noProblem();
    }

    /// Opens the editor on job `id`, to change it.
    public Schedule change(String id, LocalDateTime now) {
        return job(id).map(job -> withDraft(Optional.of(JobDraft.of(job, zone, now))).noProblem()).orElse(this);
    }

    /// Closes the editor, and forgets what was written in it.
    public Schedule closeEditor() {
        return withDraft(Optional.empty()).noProblem().withBusy(false);
    }

    static String firstLine(String text, int most) {
        String line = text.strip().lines().findFirst().orElse("").strip();
        return line.length() <= most ? line : line.substring(0, most - 1).stripTrailing() + "…";
    }
}
