package dev.gui.view;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import dev.gui.Genies;
import dev.gui.model.Conversations;
import dev.gui.model.DateWordingUtil;
import dev.gui.model.Genie;
import dev.gui.model.Lang;
import dev.gui.model.GeniesState;
import dev.gui.model.History;
import dev.gui.model.Lines;
import dev.gui.model.Palette;
import dev.gui.model.Recurrence;
import dev.gui.model.Schedule;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForButton;
import swingtree.UIForPanel;
import swingtree.animation.LifeTime;
import swingtree.api.Layout;
import swingtree.api.Painter;
import swingtree.dialogs.ConfirmAnswer;
import swingtree.layout.FlowCell;
import swingtree.style.StyledString;

import static swingtree.UI.*;

/// A genie's history: the moments oillamp saved its home at, newest first on a rail as the
/// schedule's timeline draws its week, the way back to any of them, its branches, how large it may
/// grow, and when oillamp saves by itself. The lamp reads its settings as it wakes, so they can
/// only be changed while the genie sleeps.
///
/// With several branches, the line shown has its moments' words, and every other branch is a
/// line of dots to its left, in a colour of its own; see [Lines]. Clicking a line, or a branch
/// above them, shows it: that only changes what the page shows. Moving the genie to another
/// branch is a button of its own, offered while the genie sleeps.
///
/// Like the rest of the window, a function of the [GeniesState]: a lens onto the selected genie's
/// [History], and [Genies] for saving, going back and the branches.
final class HistoryPage {

    /*
     *  A responsive grid, like the schedule's: from LARGE up the moments and what a moment holds
     *  stand side by side; below that, the second follows the first.
     *
     *                        very small  small  medium  large  very large  oversize
     *      what is said on top    12       12     12      8        8          8
     *      its buttons            12       12     12      4        4          4
     *      the moments            12       12     12      7        7          7
     *      what a moment holds    12       12     12      5        5          5
     *
     *  When oillamp saves by itself, and how large the history may grow, stand with what a moment
     *  holds, above it.
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

    /// The rail's columns and where a node sits, as on the schedule's timeline. Between the clock
    /// and the rail stand the lanes of the other branches, a lane each.
    private static final int CLOCK = 52;
    private static final int RAIL = 24;
    private static final int LANE = 14;
    private static final int NODE_Y = 17;
    /// How far apart the dots of a row between two moments are at most, and how tall such a row
    /// grows at most: past that, they move closer together.
    private static final int SLOT = 10;
    private static final int TALLEST_BETWEEN = 72;
    /// How tall the area the lines scroll in may be at most, whatever the page's height.
    private static final int FEWEST_LINES = 260;
    private static final int MOST_LINES = 900;
    /// How long the lines take to settle after another one is shown.
    private static final LifeTime SETTLING = LifeTime.of(0.22, TimeUnit.SECONDS);
    /// The sizes the page offers for how far the history may grow, in GB; 0 never deletes a moment.
    private static final Tuple<Double> GROWTH_CHOICES = Tuple.of(Double.class, 0.5, 1.0, 2.0, 5.0, 10.0, 0.0);

    private final Var<GeniesState> state;
    /// The colours and the fonts of the state, and the colours alone.
    private final Val<Theme> theme;
    private final Val<Palette> palette;
    private final Val<Lang.HistoryPage> words;
    private final Genies app;
    private final Look look;

    // Lenses and views, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Var<History> history;
    private final Val<Boolean> wide;
    /// Why the genie cannot go back now, in a few words; empty when it can.
    private final Val<String> notNow;
    /// Why the genie's home or its lamp's file cannot change now, in a sentence; empty while it
    /// sleeps and nothing is on its way: moving it to another branch, resetting a branch, deleting
    /// old moments and changing its settings wait for that.
    private final Val<String> homeLocked;
    /// When oillamp saves by itself, as the page shows it: what the lamp's file has, or what a
    /// lamp has unless it says otherwise while there is none.
    private final Val<History.Autosave> autosave;
    /// Why the history's settings cannot be changed now, in a sentence; empty when they can.
    private final Val<String> settingsLocked;
    /// The lines the page draws, and the branch whose line has the words. Drawn anew only when
    /// the history or its clock's zone changes, not as the genie writes an answer.
    private final Val<ZoneId> zone;
    private final Val<Lines> lines;
    /// What the page words moments from. Equal while the genie only writes an answer, so the
    /// titles, tooltips and banners worded from it are not worded anew with each piece of it.
    private final Val<Wording> wording;
    private final Val<String> shownLine;

    HistoryPage(Var<GeniesState> state, Genies app, Look look) {
        this.state = state;
        theme = Theme.of(state);
        palette = Theme.paletteOf(state);
        words = Theme.langOf(state).viewAs(Lang.HistoryPage.class, Lang::HISTORY_PAGE);
        this.app = app;
        this.look = look;
        genie   = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        history = genie.zoomTo(Genie::history, Genie::withHistory);
        wide    = state.viewAs(Boolean.class, GeniesState::sideBySide);
        notNow  = Viewable.of(String.class, genie, words, (it, page) ->
            !it.history().busy().isEmpty() ? it.history().busy()
            : it.phase() == Genie.Phase.WAKING ? page.IS_WAKING_GO_BACK_ONCE_IT_IS_AWAKE().of(it.name())
            : it.phase() == Genie.Phase.WORKING ? page.IS_WORKING_GO_BACK_ONCE_IT_IS_DONE().of(it.name())
            : it.schedule().running().isPresent() ? page.A_JOB_IS_RUNNING_GO_BACK_ONCE_IT_IS_DONE()
            : ""
        );
        homeLocked = Viewable.of(String.class, genie, words, (it, page) ->
            !it.history().busy().isEmpty() ? it.history().busy()
            : it.phase().isAwake() || it.phase() == Genie.Phase.WAKING
                ? page.SEND_IT_TO_SLEEP_FIRST_ITS_HOME_CHANGES_ONLY_WHILE_IT_SLEEPS().of(it.name())
            : ""
        );
        autosave = history.viewAs(History.Autosave.class, it -> it.autosave().orElse(UNLESS_SAID));
        settingsLocked = genie.viewAsString(HistoryPage::settingsLocked);
        zone = genie.viewAs(ZoneId.class, it -> it.schedule().zone());
        lines = Viewable.of(Lines.class, history, zone, History::lines);
        wording = Viewable.of(Wording.class, genie, state, (it, s) ->
            new Wording(it.name(), it.history(), it.conversations(), it.schedule(), s.localNow().toLocalDate(), s.lang().HISTORY_PAGE())
        );
        shownLine = history.viewAsString(History::shownLine);
    }

    /// What the words of moments are made of: the genie's name, its history, its conversations
    /// and jobs, which give runs their titles, the day, against which "today" is said, and the
    /// words of the page.
    private record Wording(
        String name,
        History history,
        Conversations conversations,
        Schedule schedule,
        LocalDate today,
        Lang.HistoryPage words
    ) {

        String title(History.Moment moment) {
            return history.title(moment, conversations, schedule, today.atStartOfDay());
        }

        String when(History.Moment moment) {
            return History.when(moment.at(), schedule.zone(), today.atStartOfDay());
        }
    }

    UIForAnySwing<?, ?> view(Val<Boolean> visible) {
        return
            scrollPane(conf -> conf.fitWidth(true))
            .group(Skin.PAGE_SCROLL)
            .withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            .onResize(it -> state.update(From.VIEW, s -> s.withArea(it.getWidth(), it.getHeight())))
            .add(
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 18)
                .withMinSize(0, 0)
                .withPrefSize(REFERENCE, 0)
                .withStyle(palette, (p, it) -> it
                    .backgroundColor(p.TRANSPARENT())
                    .padding(20, 22, 28, 22)
                )
                .add(WHOLE, header())
                .add(WHOLE, undoBanner())
                .add(WHOLE, planBanner())
                .add(WIDE_SIDE, momentsColumn())
                .add(NARROW_SIDE, sideColumn())
            );
    }

    // ─── on top: what is saved when, and saving now ────────────────────────────────────────

    /// Whether the genie can go back now, as a coloured dot and a sentence.
    private record Status(Color colour, String words) {}

    /// Whether the selected genie can go back now, in the colours and the words of `state`.
    private static Status status(GeniesState state) {
        Genie genie = state.genie();
        Palette p = state.palette();
        Lang.HistoryPage words = state.lang().HISTORY_PAGE();
        if (!genie.history().busy().isEmpty()) return new Status(p.BRASS(), genie.history().busy());
        String when = genie.history().autosave().orElse(UNLESS_SAID).when();
        String saved =
            (
                when.isEmpty()
                    ? words.IS_SAVED_ONLY_WHEN_YOU_SAVE_IT_BY_HAND().of(genie.name())
                    : words.SAVED_WHENEVER().of(genie.name(), when)
            )
            + " " + words.GOING_BACK_KEEPS_HOW_IT_IS_NOW_SO_YOU_CAN_ALWAYS_RETURN();
        return switch (genie.phase()) {
            case READY -> new Status(p.CONTENT(), saved);
            case WORKING -> new Status(p.BRASS(), words.IS_WORKING_YOU_CAN_GO_BACK_ONCE_IT_IS_DONE().of(genie.name()));
            case WAKING -> new Status(p.BRASS(), words.IS_WAKING_YOU_CAN_GO_BACK_ONCE_IT_IS_AWAKE().of(genie.name()));
            case ASLEEP, BROKEN -> new Status(p.SUBTEXT(), saved);
        };
    }

    private UIForAnySwing<?, ?> header() {
        Val<Status> status = state.viewAs(Status.class, HistoryPage::status);
        Val<String> problem = history.viewAsString(History::problem);
        Val<String> note = history.viewAsString(History::note);
        Val<Boolean> idle = history.viewAs(Boolean.class, it -> it.busy().isEmpty());
        Val<Boolean> branchable = history.viewAs(Boolean.class, it -> it.busy().isEmpty() && !it.moments().isEmpty());
        return
            panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 10)
            .withMinSize(0, 0)
            .withPrefSize(REFERENCE, 0)
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            .add(SAYING,
                box("fill, wrap 1, ins 0, gap 6, hidemode 3", "[grow]")
                .add(label(words.viewAsString(Lang.HistoryPage::HISTORY)).group(Skin.EMPTY_TITLE))
                .add("growx, wmin 0",
                    box("fill, ins 0, gap 8", "[10!][grow]")
                    .add("top, gaptop 5", ViewPartsUtil.dot(status.viewAs(Color.class, Status::colour), 8))
                    .add("growx, wmin 0",
                        ViewPartsUtil.wrapped(theme, status.viewAsString(Status::words), Palette::SUBTEXT, Val.of(true))
                    )
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(theme, note, Palette::SUBTEXT, note.viewAs(Boolean.class, it -> !it.isEmpty()))
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(theme, problem, Palette::TROUBLE, problem.viewAs(Boolean.class, it -> !it.isEmpty()))
                )
            )
            .add(BUTTONS,
                // Right, beside what is said; left, under it, when there is no room beside it.
                panel(wide.viewAs(Layout.class, isWide ->
                    Layout.flow(isWide ? UI.HorizontalAlignment.RIGHT : UI.HorizontalAlignment.LEFT, 8, 4)
                ))
                .withStyle(Themed.of(wide, theme), (themed, it) -> {
                    var isWide = themed.item();
                    Palette p = themed.palette();
                    return it
                        .backgroundColor(p.TRANSPARENT())
                        .padding(isWide ? 4 : 0, 0, 0, 0);
                })
                .add(
                    button(words.viewAsString(it -> "⑂  " + it.NEW_BRANCH_ELLIPSIS()))
                    .group(Skin.QUIET_BUTTON)
                    .isEnabledIf(branchable)
                    .withTooltip(Viewable.of(String.class, genie, words, (it, page) ->
                        page.KEEP_HOW_IT_WAS_LAST_SAVED_AS_A_BRANCH_OF_ITS_OWN().of(it.name())
                    ))
                    .onClick(it -> startBranch(it.getComponent(), ""))
                )
                .add(
                    button(words.viewAsString(it -> "＋  " + it.SAVE_NOW_ELLIPSIS()))
                    .group(Skin.FLAME_BUTTON)
                    .isEnabledIf(idle)
                    .withTooltip(words.viewAsString(Lang.HistoryPage::KEEP_HOW_THE_GENIE_IS_NOW_AS_A_MOMENT_TO_COME_BACK_TO))
                    .onClick(it -> save(it.getComponent()))
                )
            );
    }

    /// Right after going back: what happened, and the way to undo it.
    private UIForAnySwing<?, ?> undoBanner() {
        // The newest going back: a genie woken again after it may have saved a moment since.
        Val<String> wentBack = wording.viewAsString(it ->
            it.history()
            .moments()
            .stream()
            .filter(moment -> moment.kind() == History.Kind.WENT_BACK)
            .findFirst()
            .map(it::title)
            .orElse("")
        );
        Val<Boolean> shown = history.viewAs(Boolean.class, it ->
            it.busy().isEmpty() && it.plan().isEmpty() && it.undo().isPresent()
        );
        return
            panel("fill, ins 10 14 10 14, gap 12, hidemode 3", "[][grow][]")
            .isVisibleIf(shown)
            .withStyle(palette, (p, it) -> it
                .backgroundColor(p.YOURS())
                .borderRadius(12)
                .borderAt(UI.Edge.LEFT, 3, p.FLAME())
            )
            .add("top",
                label("↺")
                .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(16).color(now.palette().FLAME())))
            )
            .add("growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 2", "[grow]")
                .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, wentBack.viewAsString(it -> it + "."), Palette::TEXT, Val.of(true)))
                .add("growx, wmin 0", ViewPartsUtil.note(theme, words.viewAsString(Lang.HistoryPage::HOW_IT_WAS_BEFORE_IS_KEPT_SO_YOU_CAN_RETURN_TO_IT), Val.of(true)))
            )
            .add("top",
                button(words.viewAsString(it -> "↶  " + it.UNDO()))
                .group(Skin.QUIET_BUTTON)
                .isEnabledIf(notNow.viewAs(Boolean.class, String::isEmpty))
                .withStyle(Themed.of(notNow, theme), (themed, it) -> {
                    var why = themed.item();
                    Palette p = themed.palette();
                    return why.isEmpty() ? it : it.backgroundColor(p.SMOKE()).cursor(UI.Cursor.DEFAULT);
                })
                .withTooltip(Viewable.of(String.class, notNow, words, (it, page) -> it.isEmpty() ? page.GO_BACK_TO_HOW_IT_WAS_BEFORE() : it))
                .onClick(_ -> history.get().undo().ifPresent(before -> app.goBack(selected(), before.id())))
            );
    }

    // ─── asking first: what a change would throw away ──────────────────────────────────────

    /// A plan as the banner says it: what would happen, the button that makes it happen, and
    /// whether it cannot be undone, which the banner's edge and that button say in the colour of
    /// trouble, on the same warm ground as the banner that offers to undo: clear, not alarming.
    private record Asked(String question, String consequences, String confirm, boolean forGood, boolean insteadGoBack) {}

    private static Asked asked(Wording wording) {
        History history = wording.history();
        Lang.HistoryPage words = wording.words();
        return history.plan().map(plan -> switch (plan) {
            case History.Plan.Reset reset -> {
                String when = history.find(reset.target()).map(wording::when).orElse(words.THEN());
                String branch = history.lampBranch();
                String unsaved = reset.unsaved()
                    ? words.WHAT_CHANGED_SINCE_THE_NEWEST_MOMENT_WAS_NEVER_SAVED_AND_IS_LOST().of(wording.name()) + " "
                    : "";
                yield new Asked(
                    words.RESET_TO_HOW_IT_WAS().of(branch, when),
                    words.MOMENTS_AFTER_IT_LEAVE().of(reset.moments(), branch) + " "
                    + unsaved
                    + words.A_RESET_KEEPS_NO_COPY_SO_IT_CANNOT_BE_UNDONE(),
                    words.RESET().of(branch),
                    true,
                    true
                );
            }
            case History.Plan.DeleteBranch delete -> new Asked(
                words.DELETE_THE_BRANCH().of(delete.branch()),
                words.MOMENTS_ON_IT_ALONE_GO_WITH_IT().of(delete.moments(), delete.branch()),
                words.DELETE().of(delete.branch()),
                delete.moments() > 0,
                false
            );
            case History.Plan.Prune prune -> new Asked(
                words.DELETE_THE_OLDEST_MOMENTS().of(prune.moments()),
                words.THEY_DO_NOT_FIT_WITHIN_HOW_FAR_THE_HISTORY_MAY_GROW().of(
                    prune.moments(),
                    words.NAMES_LISTED().of(prune.branches().toList()),
                    bytes(prune.bytesFreed())
                ),
                words.DELETE_THEM().of(prune.moments()),
                true,
                false
            );
        }).orElse(new Asked("", "", "", false, false));
    }

    /// Before a change that throws something away: what the lamp said it would, and the buttons
    /// that make it happen or drop it.
    private UIForAnySwing<?, ?> planBanner() {
        Val<Asked> asked = wording.viewAs(Asked.class, HistoryPage::asked);
        Val<Boolean> shown = history.viewAs(Boolean.class, it -> it.busy().isEmpty() && it.plan().isPresent());
        return
            panel("fill, ins 12 14 12 14, gap 12, hidemode 3", "[grow][]")
            .isVisibleIf(shown)
            .withStyle(Themed.of(asked, theme), (themed, style) -> style
                .backgroundColor(themed.palette().YOURS())
                .borderRadius(12)
                .borderAt(UI.Edge.LEFT, 3, themed.item().forGood() ? themed.palette().TROUBLE() : themed.palette().BRASS())
            )
            .add("growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 4", "[grow]")
                .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, asked.viewAsString(Asked::question), Palette::TEXT, Val.of(true)))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, asked.viewAsString(Asked::consequences), Palette::SUBTEXT, Val.of(true)))
            )
            .add("top",
                panel().withFlowLayout(UI.HorizontalAlignment.RIGHT, 8, 4)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .add(
                    button(words.viewAsString(Lang.HistoryPage::CANCEL))
                    .group(Skin.QUIET_BUTTON)
                    .onClick(_ -> history.update(From.VIEW, it -> it.withPlan(Optional.empty())))
                )
                .add(
                    button(words.viewAsString(it -> "↺  " + it.GO_BACK_INSTEAD()))
                    .group(Skin.QUIET_BUTTON)
                    .isVisibleIf(asked.viewAs(Boolean.class, Asked::insteadGoBack))
                    .withTooltip(words.viewAsString(Lang.HistoryPage::BRING_IT_BACK_TO_THAT_MOMENT_AND_KEEP_HOW_IT_IS_NOW))
                    .onClick(_ -> goBackInstead())
                )
                .add(
                    button(asked.viewAsString(Asked::confirm))
                    .group(Skin.QUIET_BUTTON)
                    .withStyle(Themed.of(asked, theme), (themed, style) -> {
                        Palette p = themed.palette();
                        String font = themed.fonts().FONT();
                        return themed.item().forGood()
                            ? style.border(1, p.TROUBLE()).componentFont(f -> f.family(font).size(12).weight(2f).color(p.TROUBLE()))
                            : style.componentFont(f -> f.family(font).size(12).weight(2f).color(p.TEXT()));
                    })
                    .onClick(_ -> confirmPlan())
                )
            );
    }

    private void confirmPlan() {
        UUID id = selected();
        history.get().plan().ifPresent(plan -> {
            switch (plan) {
                case History.Plan.Reset reset -> app.reset(id, reset.target());
                case History.Plan.DeleteBranch delete -> app.deleteBranch(id, delete.branch());
                case History.Plan.Prune _ -> app.prune(id);
            }
        });
    }

    private void goBackInstead() {
        History shown = history.get();
        if (!(shown.plan().orElse(null) instanceof History.Plan.Reset reset)) return;
        history.update(From.VIEW, it -> it.withPlan(Optional.empty()));
        app.goBack(selected(), reset.target());
    }

    // ─── the moments, on their lines ───────────────────────────────────────────────────────

    /// A row as the page draws it, with the lanes it draws them in.
    private record Drawn(Tuple<Lines.Lane> lanes, Lines.Row row) {}

    private UIForAnySwing<?, ?> momentsColumn() {
        Val<Tuple<Drawn>> rows = lines.viewAs(
            Tuple.classTyped(Drawn.class),
            it -> it.rows().mapTo(Drawn.class, row -> new Drawn(it.lanes(), row))
        );
        Val<Integer> lanes = lines.viewAs(Integer.class, it -> it.lanes().size());
        Val<Integer> hidden = history.viewAs(Integer.class, History::hidden);
        Val<Boolean> earlier = history.viewAs(Boolean.class, History::earlier);
        Val<Boolean> reading = history.viewAs(Boolean.class, it -> !it.read());
        Val<Boolean> none = history.viewAs(Boolean.class, it -> it.read() && it.all().isEmpty());
        Val<String> earlierButtonText = Viewable.of(String.class, hidden, earlier, (more, shown) ->
            shown
                ? "Show only the newest moments"
                : "Show " + more + " earlier " + (more == 1 ? "moment" : "moments")
        );
        Val<Boolean> earlierButtonShown = Viewable.of(Boolean.class, hidden, earlier, (more, shown) -> more > 0 || shown);
        return
            box("fillx, wrap 1, ins 0, gap 8, hidemode 3", "[grow]")
            .withStyle(wide, (isWide, it) -> it.padding(0, isWide ? 14 : 0, 0, 0))
            .add("growx, wmin 0", label("MOMENTS").group(Skin.SECTION))
            .add("growx, wmin 0", branchesBar())
            .add("growx, wmin 0", label("Reading the history…").group(Skin.META).isVisibleIf(reading))
            .add("growx, wmin 0",
                panel("fill, wrap 1, ins 22 18 22 18, gap 8", "[grow, center]")
                .group(Skin.TILE)
                .isVisibleIf(none)
                .add(ViewPartsUtil.lamp(Val.of(Genie.Phase.ASLEEP), 56))
                .add("growx, wmin 0",
                    ViewPartsUtil.words(theme, "Nothing saved yet", 15, 2f, Palette::TEXT)
                    .withStyle(it -> it.text(t -> t.placement(UI.Placement.TOP)))
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(
                        theme,
                        genie.viewAsString(it ->
                            it.name() + "'s home is saved the first time it wakes, and from then on "
                            + "whenever something changed. Each save is a moment you can bring it back to."
                        ),
                        Palette::SUBTEXT,
                        Val.of(true)
                    )
                )
            )
            // The lines scroll in an area of their own, as tall as a share of the page at most,
            // so what stands below them, such as the settings in a narrow window, is never far.
            .add("growx, wmin 0, hidemode 3",
                scrollPane(conf -> conf.fitWidth(true))
                .group(Skin.PAGE_SCROLL)
                .withEmptyBorder(0)
                .isVisibleIf(history.viewAs(Boolean.class, it -> !it.all().isEmpty()))
                .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                .withVerticalScrollIncrement(24)
                .withMaxHeight(state.viewAs(Integer.class, HistoryPage::tallestLines))
                .onMouseWheelMove(it -> passTheWheelOn(it.getComponent(), it.getEvent()))
                .add(
                    box("fillx, wrap 1, ins 0, gap 8, hidemode 3", "[grow]")
                    .add("growx, wmin 0", linesPanel(rows, lanes))
                    .add("left",
                        button(earlierButtonText)
                        .group(Skin.ICON_BUTTON)
                        .isVisibleIf(earlierButtonShown)
                        .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(12).color(now.palette().BRASS())))
                        .onClick(_ -> history.update(From.VIEW, h -> h.withEarlier(!h.earlier())))
                    )
                )
            );
    }

    /// How tall the area of the lines grows at most: three fifths of the page, but never less
    /// than [#FEWEST_LINES] units, nor more than [#MOST_LINES].
    private static int tallestLines(GeniesState state) {
        return Math.clamp(state.area().height() * 3 / 5, FEWEST_LINES, MOST_LINES);
    }

    /// Hands the wheel on to the page around the area of the lines once that area cannot scroll
    /// further that way, so a turn of the wheel never stops dead at either of its ends.
    private static void passTheWheelOn(JScrollPane lines, MouseWheelEvent event) {
        JScrollBar bar = lines.getVerticalScrollBar();
        boolean down = event.getWheelRotation() > 0 || event.getPreciseWheelRotation() > 0;
        boolean atTheEnd = down
            ? bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum()
            : bar.getValue() <= bar.getMinimum();
        if (!atTheEnd || !(SwingUtilities.getAncestorOfClass(JScrollPane.class, lines) instanceof JScrollPane page)) return;
        page.dispatchEvent(SwingUtilities.convertMouseEvent(lines, event, page));
    }

    /// The rows of the lines, on one rail.
    private UIForPanel<JPanel> linesPanel(Val<Tuple<Drawn>> rows, Val<Integer> lanes) {
        return
            panel("fill, wrap 1, ins 0, gap 0", "[grow]")
            .withMinSize(0, 0)
            // One rail down the whole line shown; each moment puts its node on it. The other
            // branches' lanes are drawn by the rows they pass through.
            .withStyle(Themed.of(lanes, theme), (themed, it) -> {
                var count = themed.item();
                Palette p = themed.palette();
                return it
                    .backgroundColor(p.TRANSPARENT())
                    .painter(UI.Layer.BACKGROUND, Painter.of(List.of(it.componentHeight(), count, p), rail(p, it.componentHeight(), count)));
            })
            // When another line is shown, the rows settle in from the night behind them: the
            // palette's, read as the style is gathered, which it is anew when the palette changes.
            .withStyle(shownLine, SETTLING, (_, status, it) -> it
                .painter(UI.Layer.FOREGROUND, "settling", settling(palette.get(), it.componentWidth(), it.componentHeight(), status.progress()))
            )
            .addAll("growx, wmin 0", rows, drawn -> UI.of(UI.use(look, () -> rowView(drawn).get(JPanel.class))));
    }

    private static Painter rail(Palette p, int height, int lanes) {
        return g -> {
            g.setColor(p.BORDER());
            g.fillRect(CLOCK + lanes * LANE + RAIL / 2 - 1, 10, 2, Math.max(0, height - 20));
        };
    }

    /// The night laid over the rows as they settle, gone at `progress` 1.
    private static Painter settling(Palette p, int width, int height, double progress) {
        return g -> {
            if (progress >= 1) return;
            g.setColor(ViewPartsUtil.withAlpha(p.NIGHT(), (int) (190 * (1 - progress))));
            g.fillRect(0, 0, width, height);
        };
    }

    private UIForPanel<JPanel> rowView(Drawn drawn) {
        return switch (drawn.row()) {
            case Lines.Row.Now now -> nowRow(drawn.lanes(), now);
            case Lines.Row.Day day -> dayRow(drawn.lanes(), day);
            case Lines.Row.Shown shown -> momentRow(drawn.lanes(), shown);
            case Lines.Row.Between between -> betweenRow(drawn.lanes(), between);
        };
    }

    private static String columns(Tuple<Lines.Lane> lanes) {
        return "[" + CLOCK + "!][" + railsWidth(lanes) + "!][grow]";
    }

    private static int railsWidth(Tuple<Lines.Lane> lanes) { return lanes.size() * LANE + RAIL; }

    /// Now, at the top: where going back starts from, on the line of the branch the genie lives on.
    private UIForPanel<JPanel> nowRow(Tuple<Lines.Lane> lanes, Lines.Row.Now now) {
        Val<String> words = genie.viewAsString(it ->
            "How " + it.name() + " is now" + (now.lampLane().isPresent() ? ", on " + it.history().lampBranch() : "")
        );
        return
            panel("fill, ins 0, gap 0", columns(lanes))
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            .add("top, gaptop 9, right",
                label("now")
                .group(Skin.CLOCK)
                .withStyle(palette, (p, it) -> it.componentFont(f -> f.color(p.FLAME()).weight(2f)))
            )
            .add("top, growy, w " + railsWidth(lanes) + "!", rails(lanes, now, 32, false))
            .add("growx, wmin 0, gaptop 8, gapleft 10",
                label(words).group(Skin.META)
            );
    }

    private UIForPanel<JPanel> dayRow(Tuple<Lines.Lane> lanes, Lines.Row.Day day) {
        LocalDate today = state.get().localNow().toLocalDate();
        String label = DateWordingUtil.day(day.date(), today);
        boolean near = label.equals("Today") || label.equals("Yesterday");
        return
            panel("fill, ins 0, gap 0", columns(lanes))
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            .add("skip 1, growy, w " + railsWidth(lanes) + "!", rails(lanes, day, 34, day.date().equals(today)))
            .add("growx, wmin 0, gapleft 8, gaptop 12, gapbottom 4",
                ViewPartsUtil.words(theme, label + (near ? "  ·  " + DateWordingUtil.shortDay(day.date()) : ""), 13, 2f, Palette::TEXT)
            );
    }

    private UIForPanel<JPanel> momentRow(Tuple<Lines.Lane> lanes, Lines.Row.Shown shown) {
        History.Moment moment = shown.moment();
        ZoneId zone = genie.get().schedule().zone();
        Val<Boolean> picked = history.viewAs(Boolean.class, it -> it.picked().equals(moment.id()));
        return
            panel("fill, ins 0, gap 0", columns(lanes))
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            .add("top, gaptop 9, right",
                label(Recurrence.clock(LocalDateTime.ofInstant(moment.at(), zone).toLocalTime())).group(Skin.CLOCK)
            )
            .add("top, growy, w " + railsWidth(lanes) + "!", rails(lanes, shown, 32, picked))
            .add("growx, wmin 0, gaptop 2, gapbottom 2", momentCard(shown, picked));
    }

    /// The other branches' moments saved between two of the shown line's: dots on their lanes.
    private UIForPanel<JPanel> betweenRow(Tuple<Lines.Lane> lanes, Lines.Row.Between between) {
        int height = (int) Math.ceil(8 + between.slots() * spacing(between.slots()));
        return
            panel("fill, ins 0, gap 0", columns(lanes))
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            .add("skip 1, growy, w " + railsWidth(lanes) + "!", rails(lanes, between, height, false));
    }

    /// The cell a row draws its part of the lines in: the lanes, and the node on the shown line.
    /// Clicking a lane shows its branch's line, and a dot picks its moment there.
    private UIForAnySwing<?, ?> rails(Tuple<Lines.Lane> lanes, Lines.Row row, int height, boolean lit) {
        return rails(lanes, row, height, Val.of(lit));
    }

    private UIForAnySwing<?, ?> rails(Tuple<Lines.Lane> lanes, Lines.Row row, int height, Val<Boolean> lit) {
        Val<String> tooltip = lanes.isEmpty()
            ? Val.of("")
            : wording.viewAsString(it -> lanesTooltip(lanes, row, it));
        return
            box()
            .withPrefSize(railsWidth(lanes), height)
            .withMinSize(railsWidth(lanes), height)
            .withCursor(lanes.isEmpty() ? UI.Cursor.DEFAULT : UI.Cursor.HAND)
            .withTooltip(tooltip)
            .withStyle(Themed.of(lit, theme), (themed, it) -> {
                var isLit = themed.item();
                Palette p = themed.palette();
                return it
                    .backgroundColor(p.TRANSPARENT())
                    .painter(UI.Layer.BACKGROUND,
                        Painter.of(List.of(lanes, row, it.componentHeight(), isLit, p), lines(p, lanes, row, it.componentHeight(), isLit))
                    );
            })
            .onMouseClick(it -> clickedLane(lanes, row, it.mouseX(), it.mouseY()));
    }

    /// The lane under `x` shows its branch's line; in a row between moments, the dot nearest to
    /// `y` is picked there too.
    private void clickedLane(Tuple<Lines.Lane> lanes, Lines.Row row, int x, int y) {
        int lane = x / LANE;
        if (lane < 0 || lane >= lanes.size()) return;
        Lines.Strand strand = row.strands().get(lane);
        int slots = row instanceof Lines.Row.Between between ? between.slots() : 0;
        Optional<String> dot =
            strand.dots().stream()
            .min((a, b) -> Double.compare(Math.abs(slotY(a.slot(), slots) - y), Math.abs(slotY(b.slot(), slots) - y)))
            .map(it -> it.moment().id());
        String branch = lanes.get(lane).branch();
        history.update(From.VIEW, it -> {
            History shown = it.showLine(branch);
            return dot.map(shown::withPicked).orElse(shown);
        });
    }

    private static String lanesTooltip(Tuple<Lines.Lane> lanes, Lines.Row row, Wording wording) {
        List<String> said = new ArrayList<>();
        for (int l = 0; l < lanes.size(); l++) {
            Lines.Strand strand = row.strands().get(l);
            String branch = lanes.get(l).branch() + (lanes.get(l).lampIsOn() ? " (" + wording.name() + " lives here)" : "");
            if (strand.dots().isEmpty() && !strand.isEmpty()) said.add(branch);
            for (Lines.Dot dot : strand.dots())
                said.add(branch + ": " + wording.title(dot.moment()) + ", " + wording.when(dot.moment()));
        }
        if (said.isEmpty()) return "";
        return
            String.join("\n", said.subList(0, Math.min(8, said.size())))
            + (said.size() > 8 ? "\nand " + (said.size() - 8) + " more" : "")
            + "\nClick a line to show it here";
    }

    private static double spacing(int slots) {
        return slots == 0 ? SLOT : Math.min(SLOT, (TALLEST_BETWEEN - 8) / (double) slots);
    }

    private static double slotY(int slot, int slots) {
        return 4 + (slot + 0.5) * spacing(slots);
    }

    private static double laneX(int lane) { return lane * LANE + LANE / 2.0; }

    /// The lanes passing through `row`, in each branch's colour, the one the genie lives on lit by
    /// its flame; and the node of the shown line, as the line of a history of one branch has it.
    private static Painter lines(Palette p, Tuple<Lines.Lane> lanes, Lines.Row row, int height, boolean lit) {
        return g -> {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double main = lanes.size() * LANE + RAIL / 2.0;
            int slots = row instanceof Lines.Row.Between between ? between.slots() : 0;
            Optional<Integer> lampLane = row instanceof Lines.Row.Now now ? now.lampLane() : Optional.empty();
            for (int l = 0; l < lanes.size(); l++) {
                Lines.Strand strand = row.strands().get(l);
                if (strand.isEmpty()) continue;
                double x = laneX(l);
                boolean nowHere = lampLane.equals(Optional.of(l));
                double first = nowHere ? NODE_Y : strand.dots().isEmpty() ? 0 : slotY(strand.dots().first().slot(), slots);
                double last = nowHere ? NODE_Y : strand.dots().isEmpty() ? 0 : slotY(strand.dots().last().slot(), slots);
                Path2D path = new Path2D.Double();
                path.moveTo(x, strand.above() ? 0 : first);
                path.lineTo(x, strand.below() ? height : last);
                strand.end().ifPresent(end -> {
                    double toX = end instanceof Lines.End.OnLane onLane ? laneX(onLane.lane()) : main;
                    double toY = end instanceof Lines.End.OnLane onLane ? slotY(onLane.slot(), slots) : NODE_Y;
                    path.moveTo(x, last);
                    path.curveTo(x, last + (toY - last) * 0.7, toX - (toX - x) * 0.3, toY, toX, toY);
                });
                Color colour = p.branch(lanes.get(l).colour());
                if (lanes.get(l).lampIsOn()) {
                    g.setColor(ViewPartsUtil.withAlpha(p.FLAME(), 36));
                    g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(path);
                }
                g.setColor(ViewPartsUtil.withAlpha(colour, 150));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(path);
                double radius = Math.min(3.5, spacing(slots) * 0.38);
                for (Lines.Dot dot : strand.dots()) {
                    double y = slotY(dot.slot(), slots);
                    g.setColor(p.NIGHT());
                    g.fill(new Ellipse2D.Double(x - radius - 1.5, y - radius - 1.5, 2 * radius + 3, 2 * radius + 3));
                    g.setColor(colour);
                    g.fill(new Ellipse2D.Double(x - radius, y - radius, 2 * radius, 2 * radius));
                }
                if (nowHere) nowNode(p, g, x);
            }
            switch (row) {
                case Lines.Row.Now now -> { if (now.lampLane().isEmpty()) nowNode(p, g, main); }
                case Lines.Row.Day _ -> {
                    g.setColor(lit ? p.FLAME() : p.SUBTEXT());
                    double y = (height + 8) / 2.0;
                    g.fill(new Ellipse2D.Double(main - 2.5, y - 2.5, 5, 5));
                }
                case Lines.Row.Shown shown -> railNode(p, g, main, shown.moment(), lit);
                case Lines.Row.Between _ -> { }
            }
        };
    }

    /// Where the genie is now: the flame, glowing.
    private static void nowNode(Palette p, Graphics2D g, double x) {
        g.setColor(ViewPartsUtil.withAlpha(p.FLAME(), 60));
        g.fill(new Ellipse2D.Double(x - 9, NODE_Y - 9, 18, 18));
        g.setColor(p.FLAME());
        g.fill(new Ellipse2D.Double(x - 5, NODE_Y - 5, 10, 10));
    }

    /// The node on the rail: the flame for a save by hand, the colour of how it ended for an
    /// answer or a job, brass for going back and moving to another branch, and a quiet ring for
    /// waking and sleeping.
    private static void railNode(Palette p, Graphics2D g, double x, History.Moment moment, boolean picked) {
        if (picked) {
            g.setColor(ViewPartsUtil.withAlpha(p.FLAME(), 60));
            g.fill(new Ellipse2D.Double(x - 9, NODE_Y - 9, 18, 18));
        }
        g.setColor(p.NIGHT());
        g.fill(new Ellipse2D.Double(x - 7, NODE_Y - 7, 14, 14));
        switch (moment.kind()) {
            case SAVED -> {
                g.setColor(p.FLAME());
                g.fill(new Ellipse2D.Double(x - 5, NODE_Y - 5, 10, 10));
            }
            case RAN -> {
                g.setColor(moment.outcome().map(outcome -> switch (outcome) {
                    case FINISHED -> p.CONTENT();
                    case FAILED, TIMED_OUT -> p.TROUBLE();
                    case STOPPED -> p.SUBTEXT();
                }).orElse(p.SUBTEXT()));
                g.fill(new Ellipse2D.Double(x - 4.5, NODE_Y - 4.5, 9, 9));
            }
            case CHATTED -> {
                g.setColor(p.CONTENT());
                g.fill(new Ellipse2D.Double(x - 4.5, NODE_Y - 4.5, 9, 9));
            }
            case BEFORE_GOING_BACK, WENT_BACK, BEFORE_SWITCHING -> {
                g.setColor(p.BRASS());
                g.fill(new Ellipse2D.Double(x - 4.5, NODE_Y - 4.5, 9, 9));
            }
            case WOKE, SLEPT -> {
                g.setColor(p.SUBTEXT());
                g.setStroke(new BasicStroke(1.6f));
                g.draw(new Ellipse2D.Double(x - 4, NODE_Y - 4, 8, 8));
            }
            case BEFORE_RUN -> {
                g.setColor(p.SUBTEXT());
                g.fill(new Ellipse2D.Double(x - 2.5, NODE_Y - 2.5, 5, 5));
            }
        }
    }

    /// A moment as a card: what it was, and more in a line below. Clicking it picks it, which
    /// shows the way back to it, a branch from it, and its conversation.
    private UIForPanel<JPanel> momentCard(Lines.Row.Shown shown, Val<Boolean> picked) {
        History.Moment moment = shown.moment();
        Var<Boolean> hovered = Var.of(false);
        boolean quiet =
            moment.kind() == History.Kind.WOKE ||
            moment.kind() == History.Kind.SLEPT ||
            moment.kind() == History.Kind.BEFORE_RUN;
        boolean failed =
            moment.outcome()
            .filter(it -> it == Schedule.Outcome.FAILED || it == Schedule.Outcome.TIMED_OUT)
            .isPresent();
        String detail = moment.detail();
        String standsHere = shown.newestOf().isEmpty()
            ? ""
            : "⑂  " + words(shown.newestOf()) + (shown.newestOf().size() == 1 ? " stands here" : " stand here");
        Val<String> title = wording.viewAsString(it -> it.title(moment));
        Val<Boolean> canOpen = genie.viewAs(Boolean.class, it ->
            !moment.conversation().isEmpty() &&
            it.conversations().find(moment.conversation()).isPresent()
        );
        // Going back and resetting stay on the branch the genie lives on.
        Val<Boolean> whereItLives = history.viewAs(Boolean.class, it -> moment.isOn(it.lampBranch()));
        Val<Boolean> idle = history.viewAs(Boolean.class, it -> it.busy().isEmpty());
        Val<Boolean> hot = Viewable.of(Boolean.class, picked, hovered, (isPicked, isHovered) -> isPicked || isHovered);
        Val<String> tooltipText = picked.viewAsString(it ->
            it ? "Click again to close" : "Click to go back to this moment, start a branch from it, or open its conversation"
        );
        return
            panel("fill, wrap 1, ins 6 10 8 10, gap 2, hidemode 3", "[grow]")
            .withMinSize(0, 0)
            .withCursor(UI.Cursor.HAND)
            .withTooltip(tooltipText)
            // A transitional style follows no item: it reads the palette as it is gathered, which
            // it is anew whenever the palette changes, as every style the style sheet reaches.
            .withTransitionalStyle(hot, LifeTime.of(0.14, TimeUnit.SECONDS), (status, it) -> {
                Palette p = palette.get();
                return it
                    .backgroundColor(
                        picked.get()
                            ? ViewPartsUtil.withAlpha(p.FLAME(), 26 + (int) (20 * status.progress()))
                            : ViewPartsUtil.withAlpha(p.RAISED(), (int) (200 * status.progress()))
                    )
                    .borderRadius(10)
                    .borderAt(UI.Edge.LEFT, 2, picked.get() ? p.FLAME() : p.TRANSPARENT());
            })
            .onMouseEnter(_ -> hovered.set(true))
            .onMouseExit(_ -> hovered.set(false))
            .onMouseClick(_ -> history.update(From.VIEW, h -> h.pick(moment.id())))
            .add("growx, wmin 0",
                box()
                .withMinSize(0, 0)
                .withStyle(Themed.of(title, theme), (themed, it) -> {
                    var words = themed.item();
                    Palette p = themed.palette();
                    return it
                        .padding(1, 0, 1, 0)
                        .text(t -> t
                            .content(StyledString.of(f -> f
                                .family(themed.fonts().FONT())
                                .size(13)
                                .weight(moment.kind() == History.Kind.SAVED ? 2f : 1.5f)
                                .color(quiet ? p.SUBTEXT() : p.TEXT()),
                                words
                            ))
                            .placement(UI.Placement.TOP_LEFT)
                            .wrapLines(true)
                            .autoPreferredHeight(true)
                        );
                })
            )
            .add("growx, wmin 0, hidemode 3",
                ViewPartsUtil.words(theme, detail, 12, 1f, p -> failed ? p.TROUBLE() : p.SUBTEXT())
                .isVisibleIf(!detail.isEmpty())
            )
            .add("growx, wmin 0, hidemode 3",
                ViewPartsUtil.words(theme, standsHere, 11, 1f, Palette::SUBTEXT)
                .isVisibleIf(!standsHere.isEmpty())
            )
            .add("growx, wmin 0, gaptop 4, hidemode 3",
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 10, 4)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .isVisibleIf(picked)
                .add(
                    button("↺  Go back to this moment…")
                    .group(Skin.QUIET_BUTTON)
                    .isVisibleIf(whereItLives)
                    .isEnabledIf(notNow.viewAs(Boolean.class, String::isEmpty))
                    // Not now, it steps back, as the Send button does with nothing to send; its
                    // words take the look's colour for what cannot be used.
                    .withStyle(Themed.of(notNow, theme), (themed, it) -> {
                        var why = themed.item();
                        Palette p = themed.palette();
                        return why.isEmpty() ? it : it.backgroundColor(p.SMOKE()).cursor(UI.Cursor.DEFAULT);
                    })
                    .withTooltip(notNow.viewAsString(it ->
                        it.isEmpty() ? "Bring its files and conversations back to how they were then" : it
                    ))
                    .onClick(_ -> confirmGoBack(moment))
                )
                .add(
                    button("⑂  Start a branch here…")
                    .group(Skin.QUIET_BUTTON)
                    .isEnabledIf(idle)
                    .withTooltip(genie.viewAsString(it ->
                        "Keep this moment as a branch of its own, to try something "
                        + "else from here. " + it.name() + " stays where it lives"
                    ))
                    .onClick(it -> startBranch(it.getComponent(), moment.id()))
                )
                .add(
                    button("Reset to here…")
                    .group(Skin.QUIET_BUTTON)
                    .isVisibleIf(whereItLives)
                    .isEnabledIf(homeLocked.viewAs(Boolean.class, String::isEmpty))
                    .withStyle(Themed.of(homeLocked, theme), (themed, it) -> {
                        var why = themed.item();
                        Palette p = themed.palette();
                        return why.isEmpty() ? it : it.backgroundColor(p.SMOKE()).cursor(UI.Cursor.DEFAULT);
                    })
                    .withTooltip(homeLocked.viewAsString(it ->
                        it.isEmpty() ? "Take every moment after this one off the branch, for good. You are asked first" : it
                    ))
                    .onClick(_ -> app.planReset(selected(), moment.id()))
                )
                .add(
                    ViewPartsUtil.link(theme, Val.of("Open the conversation  →"))
                    .isVisibleIf(canOpen)
                    .onClick(_ -> app.openConversation(selected(), moment.conversation()))
                )
            );
    }

    // ─── the branches, above the lines ─────────────────────────────────────────────────────

    /// A branch as the bar above the lines shows it.
    private record Chip(String branch, int colour, boolean lampIsOn, boolean shown) {}

    /// With several branches: one chip each, and what the line shown is, with moving the genie to
    /// it and deleting it. Shown branch and lamp branch are told apart: the first is lit, the
    /// second has the genie's lamp and a flame-coloured edge.
    private UIForAnySwing<?, ?> branchesBar() {
        Val<Tuple<Chip>> chips = history.viewAs(Tuple.classTyped(Chip.class), it ->
            it.branches().mapTo(Chip.class, branch ->
                new Chip(
                    branch.name(),
                    Lines.colour(it.branches(), branch.name()),
                    branch.lampIsOn(),
                    branch.name().equals(it.shownLine())
                )
            )
        );
        Val<Boolean> several = history.viewAs(Boolean.class, it -> it.branches().size() > 1);
        Val<Boolean> elsewhere = history.viewAs(Boolean.class, it -> !it.shownLine().equals(it.lampBranch()));
        Val<Boolean> idle = history.viewAs(Boolean.class, it -> it.busy().isEmpty());
        Val<String> about = genie.viewAsString(it ->
            it.history().shownLine().equals(it.history().lampBranch())
                ? it.name() + " lives on " + it.history().lampBranch() + ", and every save goes there. "
                  + "Click another branch, or its line, to look at it."
                : "You are looking at " + it.history().shownLine() + ". " + it.name() + " lives on "
                  + it.history().lampBranch() + ", and stays there until you move it."
        );
        return
            box("fillx, wrap 1, ins 0 0 6 0, gap 6, hidemode 3", "[grow]")
            .isVisibleIf(several)
            .add("growx, wmin 0",
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 6, 4)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .addAll(chips, chip -> UI.of(UI.use(look, () -> chipView(chip).get(JPanel.class))))
            )
            .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, about, Palette::SUBTEXT, Val.of(true)))
            .add("growx, wmin 0, hidemode 3",
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 8, 4)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .isVisibleIf(elsewhere)
                .add(
                    button(genie.viewAsString(it -> "⇄  Move " + it.name() + " to " + it.history().shownLine() + "…"))
                    .group(Skin.QUIET_BUTTON)
                    .isEnabledIf(homeLocked.viewAs(Boolean.class, String::isEmpty))
                    .withStyle(Themed.of(homeLocked, theme), (themed, it) -> {
                        var why = themed.item();
                        Palette p = themed.palette();
                        return why.isEmpty() ? it : it.backgroundColor(p.SMOKE()).cursor(UI.Cursor.DEFAULT);
                    })
                    .withTooltip(homeLocked.viewAsString(it ->
                        it.isEmpty()
                            ? "Make this branch the genie's home: its files and conversations become this branch's newest moment"
                            : it
                    ))
                    .onClick(_ -> confirmSwitch())
                )
                .add(
                    button(history.viewAsString(it -> "Delete " + it.shownLine() + "…"))
                    .group(Skin.QUIET_BUTTON)
                    .isEnabledIf(idle)
                    .withTooltip("Delete this branch. You are told first how many moments only it holds")
                    .onClick(_ -> app.planDeletingBranch(selected(), history.get().shownLine()))
                )
            );
    }

    private UIForPanel<JPanel> chipView(Chip chip) {
        String tooltip =
            (chip.lampIsOn() ? genie.get().name() + " lives on this branch. " : "")
            + (chip.shown() ? "Its line is the one with words" : "Click to look at its line");
        var view =
            panel("ins 3 10 3 10, gap 6, aligny center")
            .withCursor(UI.Cursor.HAND)
            .withTooltip(tooltip)
            .withStyle(palette, (p, it) -> it
                .borderRadius(999)
                .backgroundColor(chip.shown() ? p.RAISED() : p.TRANSPARENT())
                .border(1, chip.lampIsOn() ? p.FLAME() : chip.shown() ? p.BORDER() : p.TRANSPARENT())
            )
            .onMouseClick(_ -> history.update(From.VIEW, it -> it.showLine(chip.branch())))
            .add("aligny center", ViewPartsUtil.dot(palette.viewAs(Color.class, p -> p.branch(chip.colour())), 8))
            .add("aligny center",
                label(chip.branch())
                .withStyle(theme, (now, it) -> it.componentFont(f -> f
                    .family(now.fonts().FONT()).size(12).weight(chip.shown() ? 2f : 1f).color(chip.shown() ? now.palette().TEXT() : now.palette().SUBTEXT())
                ))
            );
        // The genie's own lamp, on the branch it lives on.
        return chip.lampIsOn() ? view.add(ViewPartsUtil.lamp(genie.viewAs(Genie.Phase.class, Genie::phase), 16)) : view;
    }

    // ─── beside the moments: when it saves, how large it grows, and what a moment holds ────

    /// What a lamp's file has unless it says otherwise.
    private static final History.Autosave UNLESS_SAID = new History.Autosave(false, false, true, true);

    private static String settingsLocked(Genie genie) {
        if (genie.history().autosave().isEmpty())
            return genie.history().read()
                ? "Wake " + genie.name() + " once first. Its settings are kept in its lamp, which it gets as it first wakes."
                : "Reading its settings…";
        if (genie.phase().isAwake() || genie.phase() == Genie.Phase.WAKING)
            return "Send " + genie.name() + " to sleep to change these. While it is awake, it keeps what it woke with.";
        return "";
    }

    private UIForAnySwing<?, ?> sideColumn() {
        Val<Boolean> changeable = settingsLocked.viewAs(Boolean.class, String::isEmpty);
        return
            box("fillx, wrap 1, ins 0, gap 10, hidemode 3", "[grow]")
            .withStyle(wide, (isWide, it) -> it.padding(isWide ? 0 : 12, 0, 0, isWide ? 14 : 0))
            .add("growx, wmin 0",
                label("WHEN IT SAVES BY ITSELF")
                .group(Skin.SECTION)
                .withStyle(Themed.of(changeable, theme), (themed, it) -> {
                    var can = themed.item();
                    Palette p = themed.palette();
                    return can ? it : it.componentFont(f -> f.color(ViewPartsUtil.dim(p.SUBTEXT())));
                })
            )
            .add("growx, wmin 0",
                panel("fill, wrap 2, gap 8 10, hidemode 3", "[grow][]")
                .group(Skin.TILE)
                .add("span 2, growx, wmin 0",
                    ViewPartsUtil.wrapped(theme, settingsLocked, Palette::BRASS, changeable.viewAs(Boolean.class, it -> !it))
                )
                .add("growx, wmin 0", ViewPartsUtil.words(theme, "As it wakes", 13, 1f, Palette::TEXT))
                .add("top, right", autosaveSwitch(History.Autosave::waking, History.Autosave::withWaking, changeable))
                .add("growx, wmin 0", ViewPartsUtil.words(theme, "After it went to sleep", 13, 1f, Palette::TEXT))
                .add("top, right", autosaveSwitch(History.Autosave::sleeping, History.Autosave::withSleeping, changeable))
                .add("growx, wmin 0", ViewPartsUtil.words(theme, "As it begins on a question or a job", 13, 1f, Palette::TEXT))
                .add("top, right", autosaveSwitch(History.Autosave::beforeRun, History.Autosave::withBeforeRun, changeable))
                .add("growx, wmin 0", ViewPartsUtil.words(theme, "As it answers, or ends a job", 13, 1f, Palette::TEXT))
                .add("top, right", autosaveSwitch(History.Autosave::afterRun, History.Autosave::withAfterRun, changeable))
                .add("span 2, growx, wmin 0",
                    ViewPartsUtil.note(
                        theme,
                        "A job is told what its last runs said and changed from "
                        + "what was saved as they ended. Saving by hand works whatever these say.",
                        Val.of(true)
                    )
                )
            )
            .add("growx, wmin 0, gaptop 8",
                label("HOW LARGE IT MAY GROW")
                .group(Skin.SECTION)
                .withStyle(Themed.of(changeable, theme), (themed, it) -> {
                    var can = themed.item();
                    Palette p = themed.palette();
                    return can ? it : it.componentFont(f -> f.color(ViewPartsUtil.dim(p.SUBTEXT())));
                })
            )
            .add("growx, wmin 0", sizeTile(changeable))
            .add("growx, wmin 0, gaptop 8", label("WHAT A MOMENT HOLDS").group(Skin.SECTION))
            .add("growx, wmin 0",
                panel("fill, wrap 1, gap 8", "[grow]")
                .group(Skin.TILE)
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(
                        theme,
                        genie.viewAsString(it ->
                            it.name() + "'s home: the files it made, its notes and its conversations, "
                            + "and the settings of its lamp. Going back brings all of these back together."
                        ),
                        Palette::TEXT,
                        Val.of(true)
                    )
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(
                        theme,
                        Val.of(
                            "Its schedule stays as it is, and so does everything "
                            + "on this computer outside the genie's lamp."
                        ),
                        Palette::SUBTEXT,
                        Val.of(true)
                    )
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(
                        theme,
                        genie.viewAsString(it ->
                            "A branch is a line of moments with a home of its own, schedule and settings "
                            + "included, to try something without losing the rest. " + it.name() + " lives on one at a time."
                        ),
                        Palette::SUBTEXT,
                        Val.of(true)
                    )
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(
                        theme,
                        Val.of(
                            "Save by hand before something you might want to undo, "
                            + "such as a big change to a project."
                        ),
                        Palette::SUBTEXT,
                        Val.of(true)
                    )
                )
            );
    }

    /// One of the sizes the page offers for how far the history may grow, and whether the lamp's
    /// file has it.
    private record Growth(double gb, boolean chosen) {}

    /// How far the history may grow beyond its first moment, the sizes to choose from, what
    /// oillamp last deleted to stay within it, and deleting what does not fit now.
    private UIForAnySwing<?, ?> sizeTile(Val<Boolean> changeable) {
        Val<Tuple<Growth>> choices = history.viewAs(Tuple.classTyped(Growth.class), it -> {
            Tuple<Double> offered =
                it.maxGrowthGb()
                .filter(gb -> !GROWTH_CHOICES.contains(gb))
                .map(gb -> GROWTH_CHOICES.addAt(GROWTH_CHOICES.size() - 1, gb))
                .orElse(GROWTH_CHOICES);
            return offered.mapTo(Growth.class, gb -> new Growth(gb, it.maxGrowthGb().equals(Optional.of(gb))));
        });
        Val<String> about = genie.viewAsString(it ->
            it.history().maxGrowthGb()
            .map(gb ->
                gb == 0
                    ? "Nothing is ever deleted: " + it.name() + "'s history may grow without end."
                    : it.name() + "'s history may grow " + gigabytes(gb) + " beyond its first moment, on all branches "
                      + "together. Past that, each save deletes the moments saved longest ago; each branch keeps its newest."
            )
            .orElse("")
        );
        Val<String> pruned = history.viewAsString(it ->
            it.pruned()
            .map(last ->
                "The last time, oillamp deleted " + (last.moments() == 1 ? "one old moment" : last.moments() + " old moments")
                + " to stay within it, which freed " + bytes(last.bytesFreed()) + ". The history now takes "
                + bytes(last.remainingBytes()) + " of the " + bytes(last.limitBytes()) + " it may, its first moment included."
            )
            .orElse("")
        );
        return
            panel("fill, wrap 1, gap 8, hidemode 3", "[grow]")
            .group(Skin.TILE)
            .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, about, Palette::TEXT, about.viewAs(Boolean.class, it -> !it.isEmpty())))
            .add("growx, wmin 0",
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 6, 6)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .isVisibleIf(history.viewAs(Boolean.class, it -> it.maxGrowthGb().isPresent()))
                .addAll(choices, growth -> UI.of(UI.use(look, () -> growthChip(growth, changeable).get(JButton.class))))
            )
            .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, pruned, Palette::SUBTEXT, pruned.viewAs(Boolean.class, it -> !it.isEmpty())))
            .add("left",
                button("Tidy up now…")
                .group(Skin.QUIET_BUTTON)
                .isVisibleIf(history.viewAs(Boolean.class, it -> it.maxGrowthGb().filter(gb -> gb > 0).isPresent()))
                .isEnabledIf(homeLocked.viewAs(Boolean.class, String::isEmpty))
                .withStyle(Themed.of(homeLocked, theme), (themed, it) -> {
                    var why = themed.item();
                    Palette p = themed.palette();
                    return why.isEmpty() ? it : it.backgroundColor(p.SMOKE()).cursor(UI.Cursor.DEFAULT);
                })
                .withTooltip(homeLocked.viewAsString(it ->
                    it.isEmpty()
                        ? "Delete what does not fit now, rather than at the next save. You are told first what that is"
                        : it
                ))
                .onClick(_ -> app.planPruning(selected()))
            );
    }

    private UIForButton<JButton> growthChip(Growth growth, Val<Boolean> changeable) {
        return
            button(growth.gb() == 0 ? "Never delete" : gigabytes(growth.gb()))
            .group(Skin.CHIP)
            .isEnabledIf(changeable)
            .withStyle(palette, (p, it) ->
                growth.chosen() ? it.backgroundColor(p.YOURS()).border(1, p.FLAME()).componentFont(f -> f.color(p.TEXT()).weight(2f)) : it
            )
            .onClick(_ -> app.maxGrowth(selected(), growth.gb()));
    }

    /// `gb` as the page writes a size in GB, such as "2 GB" or "0.5 GB".
    private static String gigabytes(double gb) {
        return BigDecimal.valueOf(gb).stripTrailingZeros().toPlainString() + " GB";
    }

    /// `bytes` in the unit that reads best, such as "640 KB", "12 MB" or "1.4 GB", of 1024 each.
    static String bytes(long bytes) {
        String[] units = {"bytes", "KB", "MB", "GB", "TB"};
        double size = bytes;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        String number = unit == 0 || size >= 10 ? String.valueOf(Math.round(size)) : String.format(Locale.ROOT, "%.1f", size);
        return number + " " + units[unit];
    }

    /// Names as a sentence lists them: "main", "main and docs", "a, b and c".
    private static String words(Tuple<String> names) {
        if (names.size() < 2) return String.join("", names);
        return String.join(", ", names.slice(0, names.size() - 1)) + " and " + names.last();
    }

    /// The switch for one of the moments oillamp saves at by itself, which it writes into the
    /// lamp at once.
    private UIForAnySwing<?, ?> autosaveSwitch(
        final Function<History.Autosave, Boolean> on,
        final BiFunction<History.Autosave, Boolean, History.Autosave> with,
        final Val<Boolean> changeable
    ) {
        return ViewPartsUtil.yesOrNo(
            theme,
            Theme.langOf(state).viewAs(Lang.Parts.class, Lang::PARTS),
            autosave.viewAs(Boolean.class, on),
            changeable,
            yes -> app.autosave(selected(), with.apply(autosave.get(), yes))
        );
    }

    // ─── dialogs ───────────────────────────────────────────────────────────────────────────

    /// Asks what to remember the moment by, then saves; leaving the words out is fine.
    void save(Component parent) {
        Genie shown = genie.get();
        Object answer = JOptionPane.showInputDialog(
            parent,
            "What should this moment be remembered by? You may leave it empty.",
            "Save " + shown.name() + " as it is now",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            ""
        );
        if (answer instanceof String words) app.save(shown.id(), words.strip());
    }

    /// Asks what to call a new branch, until it is a name oillamp keeps or the user gives up,
    /// then starts it at `moment`, or at the newest moment of the branch the genie lives on.
    private void startBranch(Component parent, String moment) {
        Genie shown = genie.get();
        String rules = "Lowercase letters, digits, -, _ and ., such as rust-rewrite.";
        String name = "";
        String problem = "";
        while (true) {
            Object answer = JOptionPane.showInputDialog(
                parent,
                (problem.isEmpty() ? "" : problem + "\n\n") + "What should the new branch be called? " + rules + "\n"
                + shown.name() + " stays on " + shown.history().lampBranch() + "; you can move it later, while it sleeps.",
                moment.isEmpty() ? "Start a branch from the newest moment" : "Start a branch from this moment",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                name
            );
            if (!(answer instanceof String words)) return;
            name = words.strip();
            Optional<String> wrong = genie.get().history().branchNameProblem(name);
            if (wrong.isEmpty()) {
                app.startBranch(shown.id(), name, moment);
                return;
            }
            problem = wrong.get();
        }
    }

    private void confirmGoBack(History.Moment moment) {
        Genie shown = genie.get();
        String when = History.when(moment.at(), shown.schedule().zone(), state.get().localNow());
        ConfirmAnswer answer =
            UI.confirmation(
                "Bring " + shown.name() + " back to how it was " + when + "?\n"
                + "Its files and conversations become what they were then. How it is now is kept, so you can return."
                + (shown.phase().isAwake() ? "\n" + shown.name() + " sleeps while it goes back, and wakes again after." : "")
            )
            .titled("Go back in time")
            .yesOption("Go back").noOption("Stay").cancelOption("")
            .show();
        if (answer == ConfirmAnswer.YES) app.goBack(shown.id(), moment.id());
    }

    private void confirmSwitch() {
        Genie shown = genie.get();
        String branch = shown.history().shownLine();
        String from = shown.history().lampBranch();
        ConfirmAnswer answer =
            UI.confirmation(
                "Move " + shown.name() + " to " + branch + "?\n"
                + "Its files and conversations become those of the newest moment on " + branch
                + ", and every save goes to " + branch + " from now on.\n"
                + "What changed since the newest moment on " + from + " is saved there first, so nothing is lost."
            )
            .titled("Move to another branch")
            .yesOption("Move").noOption("Stay").cancelOption("")
            .show();
        if (answer == ConfirmAnswer.YES) app.switchBranch(shown.id(), branch);
    }

    private UUID selected() { return state.get().selected(); }
}
