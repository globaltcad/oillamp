package dev.gui.view;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import dev.gui.Genies;
import dev.gui.model.DateWordingUtil;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.JobDraft;
import dev.gui.model.Lang;
import dev.gui.model.Palette;
import dev.gui.model.Recurrence;
import dev.gui.model.Schedule;
import dev.gui.model.Timeline;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForBox;
import swingtree.UIForButton;
import swingtree.UIForPanel;
import swingtree.components.JBox;
import swingtree.animation.LifeTime;
import swingtree.api.Layout;
import swingtree.api.Painter;
import swingtree.dialogs.ConfirmAnswer;
import swingtree.input.Keyboard;
import swingtree.layout.FlowCell;
import swingtree.style.StyledString;

import static swingtree.UI.*;

/// A genie's schedule: when its jobs wake it, drawn as a timeline of the week around now, the
/// jobs themselves as tiles beside it, and an editor with room to write a job's task.
///
/// Like the rest of the window, a function of the [GeniesState]: every field is a lens onto the
/// selected genie's [Schedule], and anything that changes the lamp goes through [Genies].
final class SchedulePage {

    /*
     *  The page is a responsive grid, like the chat's. From LARGE up (three fifths of the
     *  reference width, the same 660 at which the chat puts the desktop beside itself) the
     *  timeline and the jobs stand side by side; below that, the jobs follow the timeline. The
     *  editor, in its own card, puts the task beside when it runs the same way.
     *
     *                        very small  small  medium  large  very large  oversize
     *      timeline, task         12       12     12      7        7          7
     *      jobs, when             12       12     12      5        5          5
     *      what is said on top    12       12     12      8        8          8
     *      its buttons            12       12     12      4        4          4
     */
    private static final int REFERENCE = 1100;
    private static final FlowCell WHOLE = AUTO_SPAN(it ->
        it.verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(12)
    );
    private static final FlowCell WIDE_SIDE = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP)
        .verySmall(12).small(12).medium(12).large(7).veryLarge(7).oversize(7)
    );
    private static final FlowCell NARROW_SIDE = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP)
        .verySmall(12).small(12).medium(12).large(5).veryLarge(5).oversize(5)
    );
    private static final FlowCell SAYING = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP)
        .verySmall(12).small(12).medium(12).large(8).veryLarge(8).oversize(8)
    );
    private static final FlowCell BUTTONS = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP)
        .verySmall(12).small(12).medium(12).large(4).veryLarge(4).oversize(4)
    );

    /// The timeline's columns: the time of day, and the rail with a node for each moment.
    private static final int CLOCK = 52;
    private static final int RAIL = 24;
    /// Where a node sits in its row: level with the middle of the row's first line.
    private static final int NODE_Y = 15;

    private final Var<GeniesState> state;
    /// The colours and the fonts of the state, and the colours alone.
    private final Val<Theme> theme;
    private final Val<Palette> palette;
    private final Val<Lang.SchedulePage> words;
    private final Genies app;
    private final Look look;

    // Lenses and views, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Var<Schedule> schedule;
    private final Var<Tuple<Schedule.Job>> jobs;
    private final Var<JobDraft> draft;
    private final Var<String> prompt;
    private final Var<Boolean> repeats;
    private final Var<String> time;
    private final Var<JobDraft.Repeat> repeat;
    private final Var<String> minute;
    private final Var<String> cron;
    private final Val<Timeline> timeline;
    private final Val<Boolean> editing;
    private final Val<Boolean> overview;
    private final Val<Boolean> wide;

    SchedulePage(Var<GeniesState> state, Genies app, Look look) {
        this.state = state;
        theme = Theme.of(state);
        palette = Theme.paletteOf(state);
        words = Theme.langOf(state).viewAs(Lang.SchedulePage.class, Lang::SCHEDULE_PAGE);
        this.app = app;
        this.look = look;
        genie    = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        schedule = genie.zoomTo(Genie::schedule, Genie::withSchedule);
        jobs     = schedule.zoomTo(Schedule::jobs, Schedule::withJobs);
        // The editor shows only while there is a draft, so the stand-in is never on screen.
        JobDraft standIn = JobDraft.fresh(LocalDateTime.of(2000, 1, 1, 0, 0));
        draft    = schedule.zoomTo(
            it -> it.draft().orElse(standIn),
            (it, changed) -> it.draft().isPresent() ? it.withDraft(Optional.of(changed)) : it
        );
        prompt   = draft.zoomTo(JobDraft::prompt, JobDraft::withPrompt);
        repeats  = draft.zoomTo(JobDraft::repeats, JobDraft::withRepeats);
        time     = draft.zoomTo(JobDraft::time, JobDraft::withTime);
        repeat   = draft.zoomTo(JobDraft::repeat, JobDraft::withRepeat);
        minute   = draft.zoomTo(JobDraft::minute, JobDraft::withMinute);
        cron     = draft.zoomTo(JobDraft::cron, JobDraft::withCron);
        timeline = state.viewAs(Timeline.class, GeniesState::timeline);
        editing  = schedule.viewAs(Boolean.class, it -> it.draft().isPresent());
        overview = editing.viewAs(Boolean.class, it -> !it);
        wide     = state.viewAs(Boolean.class, GeniesState::sideBySide);
    }

    UIForAnySwing<?, ?> view(Val<Boolean> visible) {
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE_SCROLL).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            .onResize(it -> state.update(From.VIEW, s -> s.withArea(it.getWidth(), it.getHeight())))
            .add(
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 18)
                .withMinSize(0, 0).withPrefSize(REFERENCE, 0)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()).padding(20, 22, 28, 22))
                .add(WHOLE, top())
                .add(WIDE_SIDE, timelineColumn())
                .add(NARROW_SIDE, jobsColumn())
                .add(WHOLE, editor())
            );
    }

    // ─── on top: whether jobs run, and what can be done ────────────────────────────────────

    /// Whether the schedule runs, as a coloured dot and a sentence.
    private record Status(Color colour, String words) {}

    /// The status of the selected genie's schedule, in the colours and the words of `state`.
    private static Status status(GeniesState state) {
        Genie genie = state.genie();
        Palette p = state.palette();
        Lang.SchedulePage words = state.lang().SCHEDULE_PAGE();
        if (genie.schedule().paused())
            return new Status(p.BRASS(), words.PAUSED_NO_JOB_RUNS_UNTIL_YOU_RESUME_THE_SCHEDULE());
        return switch (genie.phase()) {
            case READY, WORKING -> new Status(p.CONTENT(), words.ON_JOBS_WAKE_IT_AT_THEIR_TIMES().of(genie.name()));
            case WAKING -> new Status(p.BRASS(), words.IS_WAKING_JOBS_RUN_ONCE_IT_IS_AWAKE().of(genie.name()));
            case ASLEEP, BROKEN -> new Status(p.SUBTEXT(), words.IS_ASLEEP_JOBS_RUN_ONLY_WHILE_IT_IS_AWAKE().of(genie.name()));
        };
    }

    private UIForAnySwing<?, ?> top() {
        Val<Status> status = state.viewAs(Status.class, SchedulePage::status);
        Val<Boolean> paused = schedule.viewAs(Boolean.class, Schedule::paused);
        Val<Boolean> asleep = genie.viewAs(Boolean.class, it -> it.phase() == Genie.Phase.ASLEEP || it.phase() == Genie.Phase.BROKEN);
        Val<String> problem = schedule.viewAsString(it -> it.draft().isPresent() ? "" : it.problem());
        return
            panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 10)
            .isVisibleIf(overview)
            .withMinSize(0, 0).withPrefSize(REFERENCE, 0)
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            .add(SAYING,
                box("fill, wrap 1, ins 0, gap 6, hidemode 3", "[grow]")
                .add(label(words.viewAsString(Lang.SchedulePage::SCHEDULE)).group(Skin.EMPTY_TITLE))
                .add("growx, wmin 0",
                    box("fill, ins 0, gap 8", "[10!][grow]")
                    .add("top, gaptop 5", ViewPartsUtil.dot(status.viewAs(Color.class, Status::colour), 8))
                    .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, status.viewAsString(Status::words), Palette::SUBTEXT, Val.of(true)))
                )
                .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, problem, Palette::TROUBLE, problem.viewAs(Boolean.class, it -> !it.isEmpty())))
            )
            .add(BUTTONS,
                // Right, beside what is said; left, under it, when there is no room beside it.
                panel(wide.viewAs(Layout.class, isWide ->
                    Layout.flow(isWide ? UI.HorizontalAlignment.RIGHT : UI.HorizontalAlignment.LEFT, 8, 4)
                ))
                .withStyle(Themed.of(wide, theme), (themed, it) -> {
                    var isWide = themed.item();
                    Palette p = themed.palette();
                    return it.backgroundColor(p.TRANSPARENT()).padding(isWide ? 4 : 0, 0, 0, 0);
                })
                .add(
                    button(words.viewAsString(it -> "✦  " + it.WAKE())).group(Skin.QUIET_BUTTON).isVisibleIf(asleep)
                    .withTooltip(words.viewAsString(Lang.SchedulePage::JOBS_RUN_ONLY_WHILE_THE_GENIE_IS_AWAKE))
                    .onClick(_ -> app.wake(selected()))
                )
                .add(
                    button(Viewable.of(String.class, paused, words, (it, page) -> it ? "▶  " + page.RESUME() : "❚❚  " + page.PAUSE()))
                    .group(Skin.QUIET_BUTTON)
                    .isVisibleIf(schedule.viewAs(Boolean.class, it -> it.paused() || !it.jobs().isEmpty()))
                    .withTooltip(Viewable.of(String.class, paused, words, (it, page) ->
                        it ? page.LET_THE_JOBS_RUN_AGAIN() : page.HOLD_EVERY_JOB_UNTIL_YOU_RESUME_NOTHING_IS_LOST()
                    ))
                    .onClick(_ -> app.pauseSchedule(selected(), !schedule.get().paused()))
                )
                .add(
                    button(words.viewAsString(it -> "＋  " + it.NEW_JOB())).group(Skin.FLAME_BUTTON)
                    .onClick(_ -> schedule.update(From.VIEW, s -> s.writeNew(state.get().localNow())))
                )
            );
    }

    // ─── the timeline ──────────────────────────────────────────────────────────────────────

    /// How many runs before the last ones the timeline hides, and whether it shows them anyway.
    private record EarlierRuns(int hidden, boolean shown) {}

    private UIForAnySwing<?, ?> timelineColumn() {
        Val<Tuple<Timeline.Day>> days = timeline.viewAs(Tuple.classTyped(Timeline.Day.class), Timeline::days);
        Val<Integer> hidden = timeline.viewAs(Integer.class, Timeline::hiddenRuns);
        Val<Boolean> earlier = schedule.viewAs(Boolean.class, Schedule::earlier);
        Val<Boolean> canFold = Viewable.of(Boolean.class, hidden, earlier, (more, shown) -> more > 0 || shown);
        Val<Boolean> reading = schedule.viewAs(Boolean.class, it -> !it.read());
        return
            box("fillx, wrap 1, ins 0, gap 8, hidemode 3", "[grow]")
            .isVisibleIf(overview)
            .withStyle(wide, (isWide, it) -> it.padding(0, isWide ? 14 : 0, 0, 0))
            .add("growx, wmin 0",
                box("fill, ins 0, gap 8, hidemode 3", "[grow][]")
                .add("wmin 0", label(words.viewAsString(Lang.SchedulePage::THE_WEEK)).group(Skin.SECTION))
                .add(
                    button(Viewable.of(String.class, Viewable.of(EarlierRuns.class, hidden, earlier, EarlierRuns::new), words, (it, page) ->
                        it.shown() ? page.SHOW_ONLY_THE_LAST_RUNS() : page.SHOW_EARLIER_RUNS().of(it.hidden())
                    ))
                    .group(Skin.ICON_BUTTON).isVisibleIf(canFold)
                    .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(12).color(now.palette().BRASS())))
                    .onClick(_ -> schedule.update(From.VIEW, s -> s.withEarlier(!s.earlier())))
                )
            )
            .add("growx, wmin 0", label(words.viewAsString(Lang.SchedulePage::READING_THE_SCHEDULE)).group(Skin.META).isVisibleIf(reading))
            .add("growx, wmin 0",
                label(words.viewAsString(Lang.SchedulePage::NOTHING_RAN_THIS_WEEK_AND_NOTHING_IS_PLANNED)).group(Skin.META)
                .isVisibleIf(Viewable.of(Boolean.class, timeline, reading, (it, isReading) ->
                    !isReading &&
                    it.days().stream().allMatch(day -> day.moments().stream().allMatch(m -> m.kind() == Timeline.Kind.NOW))
                ))
            )
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 0, gap 0", "[grow]")
                .withMinSize(0, 0)
                // One rail down the whole week; each moment puts its node on it.
                .withStyle(palette, (p, it) ->
                    it.backgroundColor(p.TRANSPARENT())
                    .painter(UI.Layer.BACKGROUND, Painter.of(List.of(it.componentHeight(), p), rail(p, it.componentHeight())))
                )
                .addAll("growx, wmin 0", days, day -> UI.of(UI.use(look, () -> dayView(day).get(JPanel.class))))
            );
    }

    private static Painter rail(Palette p, int height) {
        return g -> {
            g.setColor(p.BORDER());
            g.fillRect(CLOCK + RAIL / 2 - 1, 10, 2, Math.max(0, height - 20));
        };
    }

    private UIForPanel<JPanel> dayView(Timeline.Day day) {
        LocalDate today = state.get().localNow().toLocalDate();
        boolean near = day.label().equals("Today") || day.label().equals("Tomorrow") || day.label().equals("Yesterday");
        var view =
            panel("fill, wrap 1, ins 0, gap 0", "[grow]")
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            .add("growx, wmin 0",
                panel("fill, ins 12 0 4 0, gap 0", "[" + CLOCK + "!][" + RAIL + "!][grow]")
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .add("skip 1, center", ViewPartsUtil.dot(palette.viewAs(Color.class, p -> day.date().equals(today) ? p.FLAME() : p.SUBTEXT()), 5))
                .add("growx, wmin 0, gapleft 8",
                    ViewPartsUtil.words(
                        theme,
                        day.label() + (near ? "  ·  " + DateWordingUtil.shortDay(day.date()) : ""),
                        13,
                        2f,
                        p -> day.date().isBefore(today) ? p.SUBTEXT() : p.TEXT()
                    )
                )
            );
        for (Timeline.Moment moment : day.moments()) view = view.add("growx, wmin 0", momentView(moment));
        return view;
    }

    private UIForPanel<JPanel> momentView(Timeline.Moment moment) {
        boolean now = moment.kind() == Timeline.Kind.NOW || moment.kind() == Timeline.Kind.WORKING;
        return
            panel("fill, ins 2 0 2 0, gap 0", "[" + CLOCK + "!][" + RAIL + "!][grow]")
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            .add("top, gaptop 7, right",
                label(moment.clock()).group(Skin.CLOCK)
                .withStyle(palette, (p, it) ->
                    now
                        ? it.componentFont(f -> f.color(p.FLAME()).weight(2f))
                        : moment.emphasis() == Timeline.Emphasis.FADED
                            ? it.componentFont(f -> f.color(ViewPartsUtil.dim(p.SUBTEXT())))
                            : it
                )
            )
            .add("top, growy, w " + RAIL + "!",
                box().withPrefSize(RAIL, 28)
                .withStyle(palette, (p, it) ->
                    it.backgroundColor(p.TRANSPARENT())
                    .painter(UI.Layer.BACKGROUND, Painter.of(List.of(moment.kind(), moment.outcome(), moment.emphasis(), p), node(p, moment)))
                )
            )
            .add("growx, wmin 0", moment.kind() == Timeline.Kind.NOW ? nowLine() : card(moment));
    }

    /// The node on the rail: hollow for what is still to come, filled for what ran, in the
    /// colour of how it ended, and the flame for now.
    private static Painter node(Palette p, Timeline.Moment moment) {
        return g -> {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double x = RAIL / 2.0;
            boolean faded = moment.emphasis() == Timeline.Emphasis.FADED;
            boolean picked = moment.emphasis() == Timeline.Emphasis.PICKED;
            switch (moment.kind()) {
                case NOW, WORKING -> {
                    g.setColor(ViewPartsUtil.withAlpha(p.FLAME(), 60));
                    g.fill(new Ellipse2D.Double(x - 9, NODE_Y - 9, 18, 18));
                    g.setColor(p.FLAME());
                    g.fill(new Ellipse2D.Double(x - 5, NODE_Y - 5, 10, 10));
                }
                case RAN -> {
                    Color colour = moment.outcome().map(outcome -> switch (outcome) {
                        case FINISHED -> p.CONTENT();
                        case FAILED, TIMED_OUT -> p.TROUBLE();
                        case STOPPED -> p.SUBTEXT();
                    }).orElse(p.SUBTEXT());
                    g.setColor(p.NIGHT());
                    g.fill(new Ellipse2D.Double(x - 7, NODE_Y - 7, 14, 14));
                    g.setColor(faded ? ViewPartsUtil.dim(colour) : colour);
                    g.fill(new Ellipse2D.Double(x - 4.5, NODE_Y - 4.5, 9, 9));
                }
                case DUE -> {
                    g.setColor(p.NIGHT());
                    g.fill(new Ellipse2D.Double(x - 7, NODE_Y - 7, 14, 14));
                    g.setColor(faded ? ViewPartsUtil.dim(p.BRASS()) : p.BRASS());
                    g.fill(new Ellipse2D.Double(x - 5, NODE_Y - 5, 10, 10));
                }
                case PLANNED, REPEATING -> {
                    Color colour = picked ? p.FLAME() : faded ? ViewPartsUtil.dim(p.BRASS()) : p.BRASS();
                    g.setColor(p.NIGHT());
                    g.fill(new Ellipse2D.Double(x - 7, NODE_Y - 7, 14, 14));
                    g.setColor(colour);
                    g.setStroke(new BasicStroke(picked ? 2.4f : 1.8f));
                    g.draw(new Ellipse2D.Double(x - 4.5, NODE_Y - 4.5, 9, 9));
                    // Several times on one day: a dot in the ring.
                    if (moment.kind() == Timeline.Kind.REPEATING)
                        g.fill(new Ellipse2D.Double(x - 2, NODE_Y - 2, 4, 4));
                }
            }
        };
    }

    /// Now, while no job runs: the flame's line across the week.
    private UIForBox<JBox> nowLine() {
        return
            box().withPrefSize(10, 28).withMinSize(0, 28)
            .withStyle(palette, (p, it) ->
                it.backgroundColor(p.TRANSPARENT())
                .painter(UI.Layer.BACKGROUND, Painter.of(List.of(it.componentWidth(), p), g -> {
                    int width = it.componentWidth();
                    g.setPaint(new GradientPaint(0, 0, p.FLAME(), Math.max(1, width), 0, p.TRANSPARENT()));
                    g.fillRect(0, NODE_Y - 1, width, 2);
                }))
            );
    }

    /// A moment of the timeline as a card: what it is about, and more in a line below. Clicking
    /// it picks its job, so the job's other times stand out and its tile says more.
    private UIForPanel<JPanel> card(Timeline.Moment moment) {
        Var<Boolean> hovered = Var.of(false);
        boolean picked = moment.emphasis() == Timeline.Emphasis.PICKED;
        boolean faded = moment.emphasis() == Timeline.Emphasis.FADED;
        boolean working = moment.kind() == Timeline.Kind.WORKING;
        Function<Palette, Color> detail = switch (moment.kind()) {
            case DUE -> Palette::BRASS;
            case RAN ->
                moment.outcome().filter(it -> it == Schedule.Outcome.FAILED || it == Schedule.Outcome.TIMED_OUT).isPresent()
                    ? Palette::TROUBLE
                    : Palette::SUBTEXT;
            case WORKING -> Palette::FLAME;
            default -> Palette::SUBTEXT;
        };
        return
            panel("fill, wrap 1, ins 6 10 8 10, gap 2, hidemode 3", "[grow]")
            .withMinSize(0, 0)
            .withCursor(UI.Cursor.HAND)
            .withTooltip(words.viewAsString(page ->
                moment.job().isEmpty()
                    ? ""
                    : picked ? page.CLICK_AGAIN_TO_SEE_EVERY_JOB() : page.CLICK_TO_BRING_THIS_JOBS_TIMES_FORWARD()
            ))
            // A transitional style follows no item: it reads the palette as it is gathered, which
            // it is anew whenever the palette changes, as every style the style sheet reaches.
            .withTransitionalStyle(hovered, LifeTime.of(0.14, TimeUnit.SECONDS), (status, it) -> {
                Palette p = palette.get();
                return it
                    .backgroundColor(
                        working
                            ? p.YOURS()
                            : picked
                                ? ViewPartsUtil.withAlpha(p.FLAME(), 26 + (int) (20 * status.progress()))
                                : ViewPartsUtil.withAlpha(p.RAISED(), (int) (200 * status.progress()))
                    )
                    .borderRadius(10)
                    .borderAt(UI.Edge.LEFT, 2, picked || working ? p.FLAME() : p.TRANSPARENT());
            })
            .onMouseEnter(_ -> hovered.set(true))
            .onMouseExit(_ -> hovered.set(false))
            .onMouseClick(_ -> {
                if (!moment.job().isEmpty()) schedule.update(From.VIEW, s -> s.pick(moment.job()));
            })
            .add("growx, wmin 0",
                ViewPartsUtil.words(theme, moment.title(), 13, moment.kind() == Timeline.Kind.RAN ? 1f : 1.5f, p -> faded ? p.SUBTEXT() : p.TEXT())
            )
            .add("growx, wmin 0",
                box("fill, ins 0, gap 8, hidemode 3", "[grow][]")
                .add("growx, wmin 0", ViewPartsUtil.words(theme, moment.detail(), 12, 1f, p -> faded ? ViewPartsUtil.dim(detail.apply(p)) : detail.apply(p)))
                .add("top",
                    ViewPartsUtil.tag(theme, state.viewAsString(it -> it.lang().SCHEDULE_PAGE().ADDED_BY().of(it.genie().name())))
                    .isVisibleIf(moment.byGenie())
                )
            )
            .add("left, hidemode 3",
                box("ins 0, gap 6, hidemode 3")
                .isVisibleIf(moment.conversation().isPresent() || working)
                .add(
                    ViewPartsUtil.link(theme, words.viewAsString(it -> it.OPEN_THE_CONVERSATION() + "  →")).isVisibleIf(moment.conversation().isPresent())
                    .onClick(_ -> moment.conversation().ifPresent(id -> app.openConversation(selected(), id)))
                )
                .add(
                    button(words.viewAsString(Lang.SchedulePage::STOP)).group(Skin.QUIET_BUTTON).isVisibleIf(working)
                    .withStyle(palette, (p, it) -> it.icon(SignSvgUtil.sign(SignSvgUtil.STOP, p.TEXT())))
                    .withTooltip(words.viewAsString(Lang.SchedulePage::STOP_THIS_RUN_WHAT_THE_GENIE_DID_SO_FAR_IS_KEPT))
                    .onClick(_ -> app.stopRun(selected(), moment.run()))
                )
            );
    }

    // ─── the jobs ──────────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> jobsColumn() {
        Val<Boolean> none = schedule.viewAs(Boolean.class, it -> it.read() && it.jobs().isEmpty());
        return
            box("fillx, wrap 1, ins 0, gap 10, hidemode 3", "[grow]")
            .isVisibleIf(overview)
            .withStyle(wide, (isWide, it) -> it.padding(isWide ? 0 : 12, 0, 0, isWide ? 14 : 0))
            .add("growx, wmin 0",
                label(Viewable.of(String.class, jobs, words, (it, page) -> page.JOBS().of(it.size()))).group(Skin.SECTION)
            )
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 22 18 22 18, gap 8", "[grow, center]").group(Skin.TILE)
                .isVisibleIf(none)
                .add(ViewPartsUtil.lamp(Val.of(Genie.Phase.ASLEEP), 56))
                .add("growx, wmin 0",
                    ViewPartsUtil.words(theme, words.viewAsString(Lang.SchedulePage::NOTHING_ON_THE_SCHEDULE_YET), 15, 2f, Palette::TEXT)
                    .withStyle(it -> it.text(t -> t.placement(UI.Placement.TOP)))
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(
                        theme,
                        Viewable.of(String.class, genie, words, (it, page) -> page.GIVE_IT_A_TASK_AND_A_TIME().of(it.name())),
                        Palette::SUBTEXT,
                        Val.of(true)
                    )
                )
                .add("gaptop 6",
                    button(words.viewAsString(it -> "＋  " + it.NEW_JOB())).group(Skin.FLAME_BUTTON)
                    .onClick(_ -> schedule.update(From.VIEW, s -> s.writeNew(state.get().localNow())))
                )
            )
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 0, gap 10", "[grow]")
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .addAll("growx, wmin 0", jobs, (Var<Schedule.Job> job) -> UI.of(UI.use(look, () -> tile(job).get(JPanel.class))))
            );
    }

    /// One job as a tile: how often it runs, its task, when it runs next, and a switch.
    /// Clicking the tile picks the job, as clicking one of its times does.
    private UIForPanel<JPanel> tile(Var<Schedule.Job> job) {
        String id = job.get().id();
        Val<Boolean> picked = schedule.viewAs(Boolean.class, it -> it.picked().equals(id));
        Val<Boolean> on = job.viewAs(Boolean.class, Schedule.Job::enabled);
        Val<String> when = Viewable.of(String.class, job, state, (it, s) -> it.when(s.localNow(), s.genie().schedule().zone()));
        Val<String> next = Viewable.of(String.class, job, state, SchedulePage::nextWords);
        return
            panel("fill, wrap 1, gap 6, hidemode 3", "[grow]").group(Skin.TILE)
            .withMinSize(0, 0)
            .withCursor(UI.Cursor.HAND)
            .withStyle(Themed.of(picked, theme), (themed, it) -> {
                var isPicked = themed.item();
                Palette p = themed.palette();
                return isPicked ? it.border(1, p.FLAME()).borderAt(UI.Edge.LEFT, 3, p.FLAME()) : it;
            })
            .onMouseClick(it -> {
                if (it.clickCount() == 2) schedule.update(From.VIEW, s -> s.change(id, state.get().localNow()));
                else schedule.update(From.VIEW, s -> s.pick(id));
            })
            .add("growx, wmin 0",
                box("fill, ins 0, gap 10", "[][grow][]")
                .add("top, gaptop 1", onOff(on, () -> app.switchJob(selected(), id, !job.get().enabled())))
                .add("growx, wmin 0",
                    words(Viewable.of(Words.class, when, on, Words::new), 13, 2f)
                )
                .add("top",
                    button("").group(Skin.ICON_BUTTON).withTooltip(words.viewAsString(Lang.SchedulePage::CHANGE_SWITCH_OFF_OR_REMOVE_THIS_JOB))
                    .withStyle(palette, (p, it) -> it.icon(SignSvgUtil.sign(SignSvgUtil.MORE, p.SUBTEXT())))
                    .onMousePress(it -> ViewPartsUtil.toggleBelow(it.getEvent(), () -> menu(id)))
                    .onPressed(Keyboard.Key.SPACE, it -> ViewPartsUtil.toggleBelow(it.getEvent(), () -> menu(id)))
                )
            )
            .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, job.viewAsString(it -> shortened(it.prompt(), 280)), Palette::SUBTEXT, Val.of(true)))
            .add("growx, wmin 0",
                box("ins 0, gap 6, hidemode 3")
                .add(ViewPartsUtil.tag(theme, next).isVisibleIf(next.viewAs(Boolean.class, it -> !it.isEmpty())))
                .add(
                    ViewPartsUtil.tag(theme, Viewable.of(String.class, job, words, (it, page) ->
                        it.expires()
                        .map(end -> page.UNTIL().of(DateWordingUtil.shortDay(end.minusSeconds(1).atZone(schedule.get().zone()).toLocalDate())))
                        .orElse("")
                    ))
                    .isVisibleIf(job.viewAs(Boolean.class, it -> it.expires().isPresent()))
                )
                .add(
                    ViewPartsUtil.tag(theme, Viewable.of(String.class, genie, words, (it, page) -> page.ADDED_BY().of(it.name())))
                    .isVisibleIf(job.viewAs(Boolean.class, Schedule.Job::byGenie))
                )
            );
    }

    /// When a job runs next, in a few words, for its tile.
    /// Empty for a job that runs once, whose time its heading says already.
    private static String nextWords(Schedule.Job job, GeniesState state) {
        Lang.SchedulePage words = state.lang().SCHEDULE_PAGE();
        if (!job.enabled()) return words.SWITCHED_OFF();
        if (job.next().isEmpty()) return words.WILL_NOT_RUN_AGAIN();
        Instant next = job.next().get();
        if (!next.isAfter(state.now())) return words.DUE_NOW();
        if (job.repeats().isEmpty()) return "";
        LocalDateTime at = LocalDateTime.ofInstant(next, state.genie().schedule().zone());
        LocalDate today = state.localNow().toLocalDate();
        String day =
            at.toLocalDate().equals(today)
                ? words.TODAY()
                : at.toLocalDate().equals(today.plusDays(1))
                    ? words.TOMORROW()
                    : DateWordingUtil.shortDay(at.toLocalDate());
        return words.NEXT().of(day, Recurrence.clock(at.toLocalTime()));
    }

    private JPopupMenu menu(String id) {
        JPopupMenu menu = new JPopupMenu();
        schedule.get().job(id).ifPresent(job -> {
            // The menu is built as it opens, in the words and the palette of that moment.
            Lang.SchedulePage page = words.get();
            menu.add(ViewPartsUtil.item(page.CHANGE_ELLIPSIS(), true, () -> schedule.update(From.VIEW, s -> s.change(id, state.get().localNow()))));
            menu.add(ViewPartsUtil.item(job.enabled() ? page.SWITCH_OFF() : page.SWITCH_ON(), true, () -> app.switchJob(selected(), id, !job.enabled())));
            menu.addSeparator();
            JMenuItem remove = ViewPartsUtil.item(page.REMOVE_ELLIPSIS(), true, () -> confirmRemove(job));
            remove.setForeground(palette.get().TROUBLE());
            menu.add(remove);
        });
        return menu;
    }

    private void confirmRemove(Schedule.Job job) {
        Lang.SchedulePage page = words.get();
        ConfirmAnswer answer =
            UI.confirmation(page.TAKE_IT_OFF_THE_SCHEDULE_WHAT_ITS_RUNS_DID_STAYS_IN_THE_HISTORY().of(job.title()))
            .titled(page.REMOVE_A_JOB())
            .yesOption(page.REMOVE()).noOption(page.KEEP()).cancelOption("")
            .show();
        if (answer == ConfirmAnswer.YES) app.removeJob(selected(), job.id());
    }

    // ─── the editor ────────────────────────────────────────────────────────────────────────

    /// What the editor's button to save says by: whether it changes a job, and whether saving is
    /// on its way.
    private record SaveButton(boolean changing, boolean busy) {}

    private UIForAnySwing<?, ?> editor() {
        Val<Boolean> changing = draft.viewAs(Boolean.class, it -> it.replaces().isPresent());
        Val<String> blocked = Viewable.of(String.class, draft, state, (it, s) -> it.problem(s.localNow()).orElse(""));
        Val<Boolean> busy = schedule.viewAs(Boolean.class, Schedule::busy);
        Val<Boolean> canSave = Viewable.of(Boolean.class, blocked, busy, (problem, isBusy) -> problem.isEmpty() && !isBusy);
        Val<String> refused = schedule.viewAsString(Schedule::problem);
        return
            panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 16).group(Skin.CARD)
            .isVisibleIf(editing)
            .withMinSize(0, 0).withPrefSize(REFERENCE, 0)
            .add(WHOLE,
                box("fill, ins 0, gap 12", "[grow][]")
                .add("growx, wmin 0",
                    box("fill, wrap 1, ins 0, gap 4")
                    .add("growx, wmin 0",
                        label(Viewable.of(String.class, changing, state, (isChange, it) ->
                            isChange ? it.lang().SCHEDULE_PAGE().CHANGE_A_JOB() : it.lang().SCHEDULE_PAGE().A_NEW_JOB_FOR().of(it.genie().name())
                        ))
                        .group(Skin.EMPTY_TITLE)
                    )
                    .add("growx, wmin 0",
                        ViewPartsUtil.note(
                            theme,
                            words.viewAsString(Lang.SchedulePage::IT_GETS_THE_TASK_AT_THE_TIME_YOU_CHOOSE_IN_A_CONVERSATION_OF_ITS_OWN),
                            Val.of(true)
                        )
                    )
                )
                .add("top",
                    button("✕").group(Skin.ICON_BUTTON).withTooltip(words.viewAsString(Lang.SchedulePage::CLOSE_WITHOUT_SAVING))
                    .onClick(_ -> schedule.update(From.VIEW, Schedule::closeEditor))
                )
            )
            .add(WIDE_SIDE, task())
            .add(NARROW_SIDE, when())
            .add(WHOLE,
                box("fill, ins 0, gap 12, hidemode 3", "[grow][][]")
                .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, refused, Palette::TROUBLE, refused.viewAs(Boolean.class, it -> !it.isEmpty())))
                .add("skip 0",
                    button(words.viewAsString(Lang.SchedulePage::CANCEL)).group(Skin.QUIET_BUTTON)
                    .onClick(_ -> schedule.update(From.VIEW, Schedule::closeEditor))
                )
                .add(
                    button(Viewable.of(String.class, Viewable.of(SaveButton.class, changing, busy, SaveButton::new), words, (it, page) ->
                        it.busy() ? page.SAVING() : it.changing() ? page.SAVE_THE_JOB() : page.ADD_TO_THE_SCHEDULE()
                    ))
                    .group(Skin.FLAME_BUTTON).isEnabledIf(canSave)
                    .withTooltip(Viewable.of(String.class, blocked, words, (it, page) -> it.isEmpty() ? page.CTRL_AND_RETURN_SAVE_TOO() : it))
                    .withStyle(Themed.of(canSave, theme), (themed, it) -> {
                        var can = themed.item();
                        Palette p = themed.palette();
                        return can
                            ? it
                            : it.backgroundColor(p.RAISED())
                              .foregroundColor(p.SUBTEXT())
                              .componentFont(f -> f.color(p.SUBTEXT()))
                              .cursor(UI.Cursor.DEFAULT);
                    })
                    .onClick(_ -> app.saveJob(selected()))
                )
            );
    }

    /// The task, with room to write a proper brief.
    private UIForAnySwing<?, ?> task() {
        Val<Integer> length = prompt.viewAs(Integer.class, it -> it.strip().length());
        return
            box("fill, wrap 1, ins 0, gap 8", "[grow]", "[][grow][]")
            .withStyle(wide, (isWide, it) -> it.padding(0, isWide ? 18 : 0, 0, 0))
            .add("growx", label(words.viewAsString(Lang.SchedulePage::THE_TASK)).group(Skin.SECTION))
            .add("grow, push, wmin 0, hmin 220",
                scrollPane().withEmptyBorder(0).withMinSize(0, 220).withPrefSize(520, 380)
                .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.RAISED()).border(1, p.BORDER()).borderRadius(12))
                .add(
                    textArea(prompt).group(Skin.INPUT).peek(ViewPartsUtil::softWrap)
                    .withStyle(theme, (now, it) -> it
                        .backgroundColor(now.palette().TRANSPARENT())
                        .border(0, now.palette().TRANSPARENT())
                        .padding(10, 12, 10, 12)
                        .componentFont(f -> f.family(now.fonts().FONT()).size(14).color(now.palette().TEXT()))
                    )
                    .onKeyPress(it -> {
                        KeyEvent key = it.getEvent();
                        if (key.getKeyCode() == KeyEvent.VK_ENTER && key.isControlDown()) {
                            key.consume();
                            app.saveJob(selected());
                        }
                    })
                )
            )
            .add("growx, wmin 0",
                box("fill, ins 0, gap 12", "[grow][]")
                .add("growx, wmin 0",
                    ViewPartsUtil.note(
                        theme,
                        words.viewAsString(Lang.SchedulePage::BRIEF_IT_AS_YOU_WOULD_A_COLLEAGUE),
                        Val.of(true)
                    )
                )
                .add("top",
                    label(length.viewAsString(it -> String.format("%,d / %,d", it, JobDraft.MOST_PROMPT))).group(Skin.META)
                    .withStyle(Themed.of(length, theme), (themed, it) -> {
                        var count = themed.item();
                        Palette p = themed.palette();
                        return count > JobDraft.MOST_PROMPT ? it.componentFont(f -> f.color(p.TROUBLE())) : it;
                    })
                )
            );
    }

    /// When it runs: once, on a day and at a time, or again and again.
    private UIForAnySwing<?, ?> when() {
        Val<Boolean> once = repeats.viewAs(Boolean.class, it -> !it);
        Val<Boolean> clocked = Viewable.of(Boolean.class, repeats, repeat, (isRepeating, how) ->
            !isRepeating ||
            how == JobDraft.Repeat.DAILY ||
            how == JobDraft.Repeat.WEEKDAYS ||
            how == JobDraft.Repeat.WEEKLY
        );
        Val<Boolean> calendarShown = draft.viewAs(Boolean.class, it -> !it.repeats() || it.endsOn().isPresent());
        Val<String> summary = Viewable.of(String.class, draft, state, (it, s) -> it.summary(s.localNow()));
        Val<String> timing = Viewable.of(String.class, draft, state, (it, s) -> it.timingProblem(s.localNow()).orElse(""));
        return
            box("fill, wrap 1, ins 0, gap 10, hidemode 3", "[grow]")
            .withStyle(wide, (isWide, it) -> it.padding(isWide ? 0 : 6, 0, 0, 0))
            .add("growx", label(words.viewAsString(Lang.SchedulePage::WHEN)).group(Skin.SECTION))
            .add(
                box("ins 0, gap 6")
                .add(chip(words.viewAsString(Lang.SchedulePage::ONCE), once, () -> repeats.set(From.VIEW, false)))
                .add(chip(words.viewAsString(Lang.SchedulePage::AGAIN_AND_AGAIN), repeats, () -> repeats.set(From.VIEW, true)))
            )

            // ── once: quick picks, then a day and a time ──
            .add("growx, wmin 0",
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 6, 6).isVisibleIf(once)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .add(quickPick(Lang.SchedulePage::IN_AN_HOUR, now ->
                    now.plusHours(1).withSecond(0).withNano(0).withMinute((now.getMinute() / 5) * 5)
                ))
                .add(quickPick(Lang.SchedulePage::THIS_EVENING, now -> now.toLocalDate().atTime(now.getHour() >= 18 ? 21 : 18, 0)))
                .add(quickPick(Lang.SchedulePage::TOMORROW_MORNING, now -> now.toLocalDate().plusDays(1).atTime(9, 0)))
                .add(quickPick(Lang.SchedulePage::MONDAY_MORNING, now ->
                    now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(9, 0)
                ))
            )

            // ── again and again: how often ──
            .add("growx, wmin 0",
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 6, 6).isVisibleIf(repeats)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .add(chip(JobDraft.Repeat.HOURLY))
                .add(chip(JobDraft.Repeat.DAILY))
                .add(chip(JobDraft.Repeat.WEEKDAYS))
                .add(chip(JobDraft.Repeat.WEEKLY))
                .add(chip(JobDraft.Repeat.CUSTOM))
            )
            .add("growx, wmin 0",
                weekdays()
                .isVisibleIf(
                    repeat.viewAs(Boolean.class, it -> it == JobDraft.Repeat.WEEKLY)
                    .viewAs(Boolean.class, it -> it && repeats.get())
                )
            )
            .add("growx, wmin 0",
                box("fill, ins 0, gap 8, hidemode 3", "[][90!][grow]")
                .isVisibleIf(Viewable.of(Boolean.class, repeats, repeat, (isRepeating, how) ->
                    isRepeating && how == JobDraft.Repeat.HOURLY
                ))
                .add(label(words.viewAsString(Lang.SchedulePage::AT)))
                .add("growx", textField(minute).group(Skin.INPUT).withTooltip(words.viewAsString(Lang.SchedulePage::A_NUMBER_FROM_0_TO_59)))
                .add("wmin 0", label(words.viewAsString(Lang.SchedulePage::MINUTES_PAST_EACH_HOUR)).group(Skin.META))
            )
            .add("growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 4, hidemode 3")
                .isVisibleIf(Viewable.of(Boolean.class, repeats, repeat, (isRepeating, how) ->
                    isRepeating && how == JobDraft.Repeat.CUSTOM
                ))
                .add("growx, wmin 0",
                    textField(cron).group(Skin.INPUT)
                    .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().MONO()).size(13).color(now.palette().TEXT())))
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.note(
                        theme,
                        words.viewAsString(Lang.SchedulePage::FIVE_FIELDS_AS_CRON_HAS_THEM),
                        Val.of(true)
                    )
                )
            )

            // ── the time of day ──
            .add("growx, wmin 0",
                box("fill, ins 0, gap 8, hidemode 3", "[][90!][grow]")
                .isVisibleIf(clocked)
                .add(label(words.viewAsString(Lang.SchedulePage::AT)))
                .add("growx",
                    textField(time).group(Skin.INPUT).withTooltip(words.viewAsString(Lang.SchedulePage::SUCH_AS_09_00_OR_9_30))
                    .withStyle(Themed.of(draft.viewAs(Boolean.class, it -> it.clock().isPresent()), theme), (themed, it) -> {
                        var readable = themed.item();
                        Palette p = themed.palette();
                        return readable ? it : it.border(1, p.TROUBLE());
                    })
                )
                .add("growx, wmin 0",
                    box("ins 0, gap 4")
                    .add(timeChip("09:00"))
                    .add(timeChip("13:00"))
                    .add(timeChip("18:00"))
                )
            )

            // ── until when ──
            .add("growx, wmin 0",
                box("ins 0, gap 6, hidemode 3").isVisibleIf(repeats)
                .add(label(words.viewAsString(Lang.SchedulePage::ENDS)).withStyle(it -> it.padding(0, 0, 0, 0)))
                .add(
                    chip(
                        words.viewAsString(Lang.SchedulePage::NEVER),
                        draft.viewAs(Boolean.class, it -> it.endsOn().isEmpty()),
                        () -> draft.update(From.VIEW, it -> it.withEndsOn(Optional.empty()))
                    )
                )
                .add(
                    chip(
                        words.viewAsString(Lang.SchedulePage::ON_A_DAY),
                        draft.viewAs(Boolean.class, it -> it.endsOn().isPresent()),
                        () -> draft.update(From.VIEW, it ->
                            it.endsOn().isPresent()
                                ? it
                                : it.withEndsOn(Optional.of(state.get().localNow().toLocalDate().plusWeeks(2)))
                                  .withMonth(YearMonth.from(state.get().localNow().plusWeeks(2)))
                        )
                    )
                )
            )

            .add("growx, wmin 0",
                panel("ins 0, gap 0, hidemode 3", "[grow]").isVisibleIf(calendarShown)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .add("growx, wmin 0", state.viewAs(Month.class, SchedulePage::month), month ->
                    UI.of(UI.use(look, () -> calendar(month).get(JPanel.class)))
                )
            )

            // ── what it comes to ──
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 10 12 10 12, gap 4, hidemode 3", "[grow]")
                .withStyle(palette, (p, it) -> it.backgroundColor(p.SMOKE()).border(1, p.BORDER()).borderRadius(12))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, summary, Palette::TEXT, summary.viewAs(Boolean.class, it -> !it.isEmpty())))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, timing, Palette::TROUBLE, timing.viewAs(Boolean.class, it -> !it.isEmpty())))
            );
    }

    /// What the calendar shows: a month, the day picked in it, and from which day on days can be
    /// picked. For a repeating job, the days up to its last one are marked as a stretch.
    private record Month(YearMonth month, Optional<LocalDate> picked, LocalDate today, boolean stretch) {}

    private static Month month(GeniesState state) {
        Optional<JobDraft> draft = state.genie().schedule().draft();
        LocalDate today = state.localNow().toLocalDate();
        if (draft.isEmpty()) return new Month(YearMonth.from(today), Optional.empty(), today, false);
        JobDraft it = draft.get();
        return new Month(it.month(), it.repeats() ? it.endsOn() : Optional.of(it.day()), today, it.repeats());
    }

    /// A month of days, seven to a row, Monday first.
    private UIForPanel<JPanel> calendar(Month month) {
        Val<String> title = words.viewAsString(it -> it.MONTH_AND_YEAR().of(month.month()));
        var grid =
            panel("wrap 7, ins 10 10 8 10, gap 3 3", "[34!][34!][34!][34!][34!][34!][34!]")
            .withStyle(palette, (p, it) -> it.backgroundColor(p.SMOKE()).border(1, p.BORDER()).borderRadius(12))
            .add("span 7, growx",
                box("fill, ins 0 2 4 0, gap 2", "[grow][][]")
                .add("growx, wmin 0", ViewPartsUtil.words(theme, title, 13, 2f, Palette::TEXT))
                .add(
                    button("‹").group(Skin.ICON_BUTTON).withTooltip(words.viewAsString(Lang.SchedulePage::THE_MONTH_BEFORE))
                    .isEnabledIf(month.month().isAfter(YearMonth.from(month.today())))
                    .onClick(_ -> draft.update(From.VIEW, d -> d.withMonth(d.month().minusMonths(1))))
                )
                .add(
                    button("›").group(Skin.ICON_BUTTON).withTooltip(words.viewAsString(Lang.SchedulePage::THE_MONTH_AFTER))
                    .onClick(_ -> draft.update(From.VIEW, d -> d.withMonth(d.month().plusMonths(1))))
                )
            );
        for (DayOfWeek day : DayOfWeek.values())
            grid = grid.add("center", label(words.viewAsString(it -> it.WEEKDAY_IN_TWO_LETTERS().of(day))).group(Skin.META));
        int blank = month.month().atDay(1).getDayOfWeek().getValue() - 1;
        for (int i = 0; i < blank; i++) grid = grid.add(box());
        for (int number = 1; number <= month.month().lengthOfMonth(); number++)
            grid = grid.add("w 34!, h 30!", dayButton(month, month.month().atDay(number)));
        return grid;
    }

    private UIForButton<JButton> dayButton(Month month, LocalDate day) {
        boolean picked = month.picked().filter(day::equals).isPresent();
        boolean past = day.isBefore(month.today());
        boolean inStretch =
            month.stretch() &&
            month.picked().isPresent() &&
            !day.isBefore(month.today()) &&
            day.isBefore(month.picked().get());
        boolean today = day.equals(month.today());
        return
            button(String.valueOf(day.getDayOfMonth())).group(Skin.DAY)
            .isEnabledIf(!past)
            .withTooltip(DateWordingUtil.day(day, month.today()))
            .withStyle(palette, (p, it) -> it
                .backgroundColor(picked ? p.FLAME() : inStretch ? p.YOURS() : p.TRANSPARENT())
                .border(1, today && !picked ? p.BRASS() : p.TRANSPARENT())
                .componentFont(f -> f
                    .color(picked ? p.ON_FLAME() : past ? ViewPartsUtil.dim(p.SUBTEXT()) : p.TEXT())
                    .weight(picked || today ? 2f : 1f)
                )
            )
            .onClick(_ -> draft.update(From.VIEW, d -> d.pick(day)));
    }

    /// The seven days of the week, each a round toggle, for a job that runs on some of them.
    private UIForBox<JBox> weekdays() {
        var row = box("ins 0, gap 6");
        for (DayOfWeek day : DayOfWeek.values()) {
            Val<Boolean> on = draft.viewAs(Boolean.class, it -> it.days().contains(day));
            row = row.add("w 36!, h 36!",
                button(words.viewAsString(it -> it.WEEKDAY_IN_TWO_LETTERS().of(day))).group(Skin.DAY)
                .withTooltip(words.viewAsString(it -> it.WEEKDAY().of(day)))
                .withStyle(Themed.of(on, theme), (themed, it) -> {
                    var isOn = themed.item();
                    Palette p = themed.palette();
                    return it
                        .borderRadius(18)
                        .backgroundColor(isOn ? p.FLAME() : p.TRANSPARENT())
                        .border(1, isOn ? p.FLAME() : p.BORDER())
                        .componentFont(f -> f.color(isOn ? p.ON_FLAME() : p.TEXT()).weight(isOn ? 2f : 1f));
                })
                .onClick(_ -> draft.update(From.VIEW, d -> d.toggle(day)))
            );
        }
        return row;
    }

    // ─── small parts ───────────────────────────────────────────────────────────────────────

    private UUID selected() { return state.get().selected(); }

    /// A choice among several, as a rounded chip that is lit while chosen.
    private UIForButton<JButton> chip(Val<String> text, Val<Boolean> chosen, Runnable choose) {
        return
            button(text).group(Skin.CHIP)
            .withStyle(Themed.of(chosen, theme), (themed, it) -> {
                var on = themed.item();
                Palette p = themed.palette();
                return on ? it.backgroundColor(p.YOURS()).border(1, p.FLAME()).componentFont(f -> f.color(p.TEXT()).weight(2f)) : it;
            })
            .onClick(_ -> choose.run());
    }

    private UIForButton<JButton> chip(JobDraft.Repeat how) {
        return chip(Val.of(how.label()), repeat.viewAs(Boolean.class, it -> it == how), () -> repeat.set(From.VIEW, how));
    }

    /// A chip that sets the day and the time of a job that runs once, from now.
    private UIForButton<JButton> quickPick(Function<Lang.SchedulePage, String> text, UnaryOperator<LocalDateTime> from) {
        return
            button(words.viewAsString(text)).group(Skin.CHIP)
            .onClick(_ -> {
                LocalDateTime at = from.apply(state.get().localNow());
                draft.update(From.VIEW, d ->
                    d.withDay(at.toLocalDate())
                    .withTime(Recurrence.clock(at.toLocalTime()))
                    .withMonth(YearMonth.from(at))
                );
            });
    }

    private UIForButton<JButton> timeChip(String clock) {
        return chip(Val.of(clock), time.viewAs(Boolean.class, it -> it.strip().equals(clock)), () -> time.set(From.VIEW, clock));
    }

    /// A switch, drawn as a small track with a knob that slides across as it turns on.
    private UIForBox<JBox> onOff(Val<Boolean> on, Runnable flip) {
        return
            box().withPrefSize(34, 20).withMinSize(34, 20).withMaxSize(34, 20)
            .withCursor(UI.Cursor.HAND)
            .withTooltip(Viewable.of(String.class, on, words, (it, page) ->
                it ? page.ON_CLICK_TO_SWITCH_IT_OFF_KEEPING_IT_ON_THE_SCHEDULE() : page.OFF_CLICK_TO_SWITCH_IT_ON()
            ))
            // Read as the style is gathered, as on a card of the timeline.
            .withTransitionalStyle(on, LifeTime.of(0.18, TimeUnit.SECONDS), (status, it) -> {
                Palette p = palette.get();
                return it
                    .backgroundColor(p.TRANSPARENT())
                    .painter(UI.Layer.BACKGROUND, Painter.of(List.of(status.progress(), p), g -> {
                        double progress = status.progress();
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(mix(p.RAISED(), p.FLAME(), progress));
                        g.fill(new RoundRectangle2D.Double(0, 1, 34, 18, 18, 18));
                        g.setColor(mix(p.BORDER(), p.FLAME(), progress));
                        g.draw(new RoundRectangle2D.Double(0.5, 1.5, 33, 17, 17, 17));
                        g.setColor(mix(p.SUBTEXT(), p.ON_FLAME(), progress));
                        g.fill(new Ellipse2D.Double(3 + 14 * progress, 4, 12, 12));
                    }));
            })
            .onMouseClick(_ -> flip.run());
    }

    /// Words, in the text's colour while `on`, and the subtext's otherwise.
    private record Words(String text, boolean on) {}

    private UIForBox<JBox> words(Val<Words> words, int size, float weight) {
        return
            box().withMinSize(0, 0)
            .withStyle(Themed.of(words, theme), (themed, it) -> {
                var shown = themed.item();
                Palette p = themed.palette();
                return it.padding(1, 0, 1, 0)
                .text(t ->
                    t.content(StyledString.of(f -> f.family(themed.fonts().FONT()).size(size).weight(weight).color(shown.on() ? p.TEXT() : p.SUBTEXT()), shown.text()))
                    .placement(UI.Placement.TOP_LEFT)
                    .wrapLines(true)
                    .autoPreferredHeight(true)
                );
            });
    }

    private static String shortened(String text, int most) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= most ? flat : flat.substring(0, most - 1).stripTrailing() + "…";
    }

    private static Color mix(Color from, Color to, double progress) {
        double p = Math.max(0, Math.min(1, progress));
        return new Color(
            (int) (from.getRed() + (to.getRed() - from.getRed()) * p),
            (int) (from.getGreen() + (to.getGreen() - from.getGreen()) * p),
            (int) (from.getBlue() + (to.getBlue() - from.getBlue()) * p)
        );
    }
}
