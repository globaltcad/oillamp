package dev.gui.model;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import sprouts.Tuple;

/// A genie's schedule laid out in time, day by day, as the schedule page draws it: the runs jobs
/// had, now, and when jobs run over the coming week.
///
/// @param days       each day that has something on it, earliest first; today always
/// @param hiddenRuns how many earlier runs of the last week are left out, while [Schedule#earlier]
///                   is off
public record Timeline(Tuple<Day> days, int hiddenRuns) {

    /// The moments of one day, in the order they happen.
    ///
    /// @param label such as "Today" or "Thursday 2 October"
    public record Day(LocalDate date, String label, Tuple<Moment> moments) {}

    /// A run, now, or a time a job runs: one line of the timeline.
    ///
    /// @param clock        the time of day, `09:00`; for [Kind#REPEATING], which stands for several
    ///                     times, how many, such as `6×`
    /// @param title        what it is about: the job's task on one line
    /// @param detail       more, in a few words, such as how a run ended or how often a job runs
    /// @param job          the job it belongs to; empty for [Kind#NOW]
    /// @param run          the run, for [Kind#RAN] and [Kind#WORKING]
    /// @param conversation pi's id for a run's conversation, when it had one
    /// @param outcome      how a run ended, for [Kind#RAN]
    public record Moment(Kind kind, String clock, String title, String detail, String job, String run,
                         Optional<String> conversation, Optional<Schedule.Outcome> outcome, boolean byGenie,
                         Emphasis emphasis) {}

    public enum Kind {
        /// A job's run that ended.
        RAN,
        /// The present moment, while no job's run is going.
        NOW,
        /// The present moment, with the run a job has going.
        WORKING,
        /// A job whose time has come; it runs as soon as the genie is awake and free.
        DUE,
        /// A time a job runs at.
        PLANNED,
        /// The times one job runs at on one day, when there are too many for a line each. Its
        /// detail names them, or, past [#LISTED_TIMES], the first and the last.
        REPEATING
    }

    /// How much a moment stands out: the picked job's stand forward, and the rest step back.
    public enum Emphasis { PICKED, PLAIN, FADED }

    /// How many runs show before the user asks for the earlier ones.
    static final int RECENT_RUNS = 3;

    /// From how many times on one day a job's times fold into one [Kind#REPEATING] moment.
    static final int FOLD_FROM = 4;

    /// Up to how many times a [Kind#REPEATING] moment's detail names one by one.
    static final int LISTED_TIMES = 8;

    /// Lays out `schedule` around `now`.
    public static Timeline of(Schedule schedule, Instant now, Lang lang) {
        LocalDateTime present = LocalDateTime.ofInstant(now, schedule.zone());
        LocalDate today = present.toLocalDate();
        Lang.SchedulePage words = lang.SCHEDULE_PAGE();
        // Each moment with when it is and its rank among moments at the same time: what ran,
        // then now, then what is due, then what is planned.
        record Placed(LocalDateTime at, int rank, Moment moment) {}
        List<Placed> placed = new ArrayList<>();

        List<Schedule.Run> week = schedule.runs().stream()
                .filter(run -> run.ended().isAfter(now.minus(Duration.ofDays(7))))
                .sorted(Comparator.comparing(Schedule.Run::ended)).toList();
        int shown = schedule.earlier() ? week.size() : Math.min(RECENT_RUNS, week.size());
        for (Schedule.Run run : week.subList(week.size() - shown, week.size())) {
            LocalDateTime at = LocalDateTime.ofInstant(run.ended(), schedule.zone());
            Optional<Schedule.Job> job = schedule.job(run.job());
            String said = Schedule.firstLine(run.said(), 140);
            placed.add(new Placed(at, 0, moment(schedule, Kind.RAN, Recurrence.clock(at.toLocalTime()),
                    job.map(Schedule.Job::title).orElse(said.isEmpty() ? words.A_JOB_THAT_HAS_RUN() : said),
                    outcomeWords(run.outcome(), words) + (job.isPresent() && !said.isEmpty() ? " · " + said : ""),
                    run.job(), run.id(), run.conversation(), Optional.of(run.outcome()), job.map(Schedule.Job::byGenie).orElse(false))));
        }

        String clockNow = Recurrence.clock(present.toLocalTime());
        placed.add(new Placed(present, 1, schedule.running()
                .map(running -> moment(schedule, Kind.WORKING, clockNow,
                        schedule.job(running.job()).map(Schedule.Job::title).orElse(words.A_JOB()),
                        words.WORKING_ON_IT_SINCE(Recurrence.clock(LocalDateTime.ofInstant(running.since(), schedule.zone()).toLocalTime())),
                        running.job(), running.run(), Optional.empty(), Optional.empty(),
                        schedule.job(running.job()).map(Schedule.Job::byGenie).orElse(false)))
                .orElseGet(() -> new Moment(Kind.NOW, clockNow, words.NOW_ON_THE_TIMELINE(), "", "", "", Optional.empty(), Optional.empty(),
                        false, Emphasis.PLAIN))));

        for (Schedule.Job job : schedule.jobs()) {
            boolean runningNow = schedule.running().filter(running -> running.job().equals(job.id())).isPresent();
            if (job.next().filter(next -> !next.isAfter(now)).isPresent() && !runningNow)
                placed.add(new Placed(present, 2, moment(schedule, Kind.DUE, clockNow, job.title(),
                        words.ITS_TIME_CAME_IT_RUNS_AS_SOON_AS_THE_GENIE_IS_AWAKE_AND_FREE(), job.id(), "",
                        Optional.empty(), Optional.empty(), job.byGenie())));
            Map<LocalDate, List<LocalDateTime>> byDay = new TreeMap<>();
            for (Instant time : job.upcoming())
                byDay.computeIfAbsent(LocalDateTime.ofInstant(time, schedule.zone()).toLocalDate(), _ -> new ArrayList<>())
                     .add(LocalDateTime.ofInstant(time, schedule.zone()));
            String how = job.repeats().map(it -> it.describe(lang)).orElse(words.ONCE_WITHOUT_A_TIME());
            for (List<LocalDateTime> times : byDay.values()) {
                if (times.size() >= FOLD_FROM) {
                    LocalDateTime first = times.getFirst();
                    String which = times.size() <= LISTED_TIMES
                            ? String.join(", ", times.stream().map(time -> Recurrence.clock(time.toLocalTime())).toList())
                            : words.TIMES_FROM_TO(times.size(), Recurrence.clock(first.toLocalTime()), Recurrence.clock(times.getLast().toLocalTime()));
                    placed.add(new Placed(first, 3, moment(schedule, Kind.REPEATING, times.size() + "×",
                            job.title(), how + " · " + which, job.id(), "",
                            Optional.empty(), Optional.empty(), job.byGenie())));
                } else {
                    for (LocalDateTime at : times)
                        placed.add(new Placed(at, 3, moment(schedule, Kind.PLANNED, Recurrence.clock(at.toLocalTime()),
                                job.title(), how, job.id(), "", Optional.empty(), Optional.empty(), job.byGenie())));
                }
            }
        }

        placed.sort(Comparator.comparing(Placed::at).thenComparing(Placed::rank));
        Map<LocalDate, Tuple<Moment>> days = new TreeMap<>();
        for (Placed each : placed)
            days.merge(each.at().toLocalDate(), Tuple.of(Moment.class, each.moment()), Tuple::addAll);
        Tuple<Day> laidOut = Tuple.of(Day.class);
        for (Map.Entry<LocalDate, Tuple<Moment>> day : days.entrySet())
            laidOut = laidOut.add(new Day(day.getKey(), DateWordingUtil.day(day.getKey(), today, lang.DATES()), day.getValue()));
        return new Timeline(laidOut, week.size() - shown);
    }

    private static Moment moment(
        final Schedule schedule,
        final Kind kind,
        final String clock,
        final String title,
        final String detail,
        final String job,
        final String run,
        final Optional<String> conversation,
        final Optional<Schedule.Outcome> outcome,
        final boolean byGenie
    ) {
        if (kind == Kind.WORKING && !schedule.picked().equals(job)) return new Moment(kind, clock, title, detail, job, run,
                conversation, outcome, byGenie, Emphasis.PLAIN);
        Emphasis emphasis = schedule.picked().isEmpty() ? (schedule.paused() && kind != Kind.RAN ? Emphasis.FADED : Emphasis.PLAIN)
                          : schedule.picked().equals(job) ? Emphasis.PICKED : Emphasis.FADED;
        return new Moment(kind, clock, title, detail, job, run, conversation, outcome, byGenie, emphasis);
    }

    /// How a run ended, in a word or two.
    static String outcomeWords(Schedule.Outcome outcome, Lang.SchedulePage words) {
        return switch (outcome) {
            case FINISHED  -> words.DONE();
            case FAILED    -> words.FAILED();
            case TIMED_OUT -> words.RAN_OUT_OF_TIME();
            case STOPPED   -> words.STOPPED();
        };
    }
}
