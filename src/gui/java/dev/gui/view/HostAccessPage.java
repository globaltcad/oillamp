package dev.gui.view;

import java.awt.Color;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import dev.gui.Genies;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.HostAccess;
import dev.gui.model.Lang;
import dev.gui.model.Palette;

import sprouts.From;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.animation.Animation;
import swingtree.animation.AnimationStatus;
import swingtree.animation.LifeTime;
import swingtree.api.Layout;
import swingtree.layout.FlowCell;
import swingtree.layout.LayoutConstraint;
import swingtree.layout.MigAddConstraint;
import swingtree.style.StyledString;

import static swingtree.UI.*;

/// A genie's host access: whether its agent may reach this computer, the permission rules the
/// checker model judges each of its requests against, who lets it run commands, and whether the
/// user is asked about folders outside their home folder. Genies writes them into the genie's
/// lamp, the rules as JSON, which the checker reads like any words; see [HostAccess].
///
/// What all of it means, and how a request goes from the genie to this computer, the page
/// explains in a panel under its title, which the user unfolds with the `?` beside it.
///
/// The lamp reads them as it wakes, so they can only be changed while the genie sleeps. While it
/// is awake, the page shows them, greyed, and says so in a banner.
///
/// Like the rest of the window, a function of the [GeniesState]: a lens onto the selected genie's
/// [HostAccess], and [Genies] for saving.
final class HostAccessPage {

    /*
     *  A responsive grid. The switch and the note that the feature is experimental share a row
     *  from LARGE up. The two lists of folders stand side by side from LARGE up, with the user's
     *  own words under them; below that, each follows the one before. On a page wider than
     *  THREE_FROM, as on a wide monitor, the three texts stand side by side.
     *
     *                        very small  small  medium  large  very large  from THREE_FROM
     *      what is said on top    12       12     12     12       12            12
     *      the switch             12       12     12      5        5             4
     *      experimental           12       12     12      7        7             8
     *      each list of folders   12       12     12      6        6             4
     *      in your own words      12       12     12     12       12             4
     *      who runs commands      12       12     12      7        7             4
     *      what that choice means 12       12     12      5        5             3
     *      outside the home       12       12      6      7        7             2
     *      what that choice means 12       12      6      5        5             3
     *      how you are asked      12       12     12     12       12            12
     *      saving                 12       12     12     12       12            12
     *
     *  The width decides THREE_FROM, not the size class: the page is laid out at its own width,
     *  so the size class never goes past what fits two texts.
     *
     *  The two switches that say when the user is asked, who lets the genie run commands and
     *  whether it asks about folders outside the home folder, each have their heading above
     *  them, and what their choice means beside them where there is room: both in one row on
     *  a page wider than THREE_FROM. The cell of who runs commands is never narrower than its
     *  three parts in a row, in any language, except on a page narrower than
     *  PARTS_IN_A_ROW_FROM: there they stand one under the other.
     *
     *  Saving is a row of its own, the warning beside the buttons. On a page narrower than
     *  SAVING_IN_ONE_ROW_FROM, the warning would be squeezed into a column a word wide, so the
     *  buttons go under it, to the right, and on a page narrower still, one under the other:
     *
     *      from SAVING_IN_ONE_ROW_FROM     [warning ……………………………  Undo changes  Save]
     *      from BUTTONS_SIDE_BY_SIDE_FROM  [warning ………………………………………………………]
     *                                      [                 Undo changes  Save]
     *      narrower still, where the two   [warning ……………………………]
     *      buttons would not fit beside    [          Save          ]
     *      each other in every language    [      Undo changes      ]
     *
     *  The widths are the page's, as the state has them: [GeniesState#area].
     */
    private static final int REFERENCE = 1100;
    private static final int THREE_FROM = 1200;
    /// How tall each of the three texts is, so that they line up side by side.
    private static final int TEXT_HEIGHT = 170;
    private static final FlowCell WHOLE = span(12, 12, 12, UI.VerticalAlignment.TOP);
    private static final FlowCell SWITCH = span(12, 5, 4, UI.VerticalAlignment.CENTER);
    private static final FlowCell BESIDE_THE_SWITCH = span(12, 7, 8, UI.VerticalAlignment.CENTER);
    private static final FlowCell FOLDERS = span(12, 6, 4, UI.VerticalAlignment.TOP);
    private static final FlowCell OWN_WORDS = span(12, 12, 4, UI.VerticalAlignment.TOP);
    private static final FlowCell WHO_RUNS_COMMANDS = span(12, 12, 7, 4, UI.VerticalAlignment.CENTER);
    private static final FlowCell BESIDE_WHO_RUNS_COMMANDS = span(12, 12, 5, 3, UI.VerticalAlignment.CENTER);
    private static final FlowCell OUTSIDE_THE_HOME_FOLDER = span(12, 6, 7, 2, UI.VerticalAlignment.CENTER);
    private static final FlowCell BESIDE_OUTSIDE_THE_HOME_FOLDER = span(12, 6, 5, 3, UI.VerticalAlignment.CENTER);
    /// From this width of the page, the three parts of who runs commands stand in a row. Their
    /// row is about 325 pixels wide in German, the widest of the languages; the page loses
    /// its padding and the scroll bar, and keeps a margin.
    private static final int PARTS_IN_A_ROW_FROM = 400;
    /// The heading and the switch of who runs commands, in this order.
    private static final Layout PARTS_IN_A_ROW = Layout.mig(
        LayoutConstraint.of("fillx, wrap 1, ins 0, gap 8"), LayoutConstraint.of("[grow]"), LayoutConstraint.of("")
    ).withChildConstraints(MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of(""));
    private static final Layout PARTS_ONE_UNDER_THE_OTHER = Layout.mig(
        LayoutConstraint.of("fillx, wrap 1, ins 0, gap 8"), LayoutConstraint.of("[grow]"), LayoutConstraint.of("")
    ).withChildConstraints(MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of("growx, wmin 0"));
    /// The user's home folder, which the genie's lamp reaches without asking: Genies leaves the
    /// lamp's roots empty, and empty roots are the home folder.
    private static final Path HOME = Path.of(System.getProperty("user.home"));
    private static final int SAVING_IN_ONE_ROW_FROM = 560;
    private static final int BUTTONS_SIDE_BY_SIDE_FROM = 400;
    /// The warning, Undo changes and Save, in this order.
    private static final Layout SAVING_IN_ONE_ROW = savingLayout("[grow][][]", "cell 0 0, growx, wmin 0", "cell 1 0", "cell 2 0");
    private static final Layout SAVING_UNDER_THE_WARNING = savingLayout("[grow][][]", "cell 0 0, span 3, growx, wmin 0", "cell 1 1", "cell 2 1");
    private static final Layout SAVING_ONE_UNDER_THE_OTHER = savingLayout("[grow, fill]", "cell 0 0, wmin 0", "cell 0 2", "cell 0 1");

    private static Layout savingLayout(String columns, String warning, String undo, String save) {
        return
            Layout.mig(LayoutConstraint.of("fillx, ins 0, gap 12, hidemode 3"), LayoutConstraint.of(columns), LayoutConstraint.of(""))
            .withChildConstraints(MigAddConstraint.of(warning), MigAddConstraint.of(undo), MigAddConstraint.of(save));
    }

    /// A cell that spans `narrow` columns up to MEDIUM, `large` from LARGE up, and `wide` on a
    /// page wider than [#THREE_FROM].
    private static FlowCell span(int narrow, int large, int wide, UI.VerticalAlignment align) {
        return span(narrow, narrow, large, wide, align);
    }

    /// A cell that spans `narrow` columns up to SMALL, `medium` on a MEDIUM page, `large` from
    /// LARGE up, and `wide` on a page wider than [#THREE_FROM].
    private static FlowCell span(int narrow, int medium, int large, int wide, UI.VerticalAlignment align) {
        return AUTO_SPAN(it -> {
            int span = it.parentSize().widthOrElse(0) >= UI.scale(THREE_FROM) ? wide : large;
            return
                it.align(align)
                .verySmall(narrow).small(narrow).medium(medium)
                .large(span).veryLarge(span).oversize(span);
        });
    }

    private final Var<GeniesState> state;
    /// The colours and the fonts of the state, and the colours alone.
    private final Val<Theme> theme;
    private final Val<Palette> palette;
    private final Genies app;

    // Lenses and views, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Var<HostAccess> hostAccess;
    private final Var<HostAccess.PermissionRules> draft;
    private final Var<String> mayRead;
    private final Var<String> mayChange;
    private final Var<String> words;
    private final Var<HostAccess.Commands> commands;
    private final Var<Boolean> askOutsideHome;
    /// The words of the page, unlike [#words], which are the user's own.
    private final Val<Lang.HostAccessPage> pageWords;
    /// Whether the rules may be changed now: the genie sleeps, has a lamp, and nothing is on its way.
    private final Val<Boolean> changeable;
    /// Whether the user wants the explanation unfolded, and how far it has unfolded on its way there.
    private final Var<Boolean> explained = Var.of(false);
    private final Var<Unfolding> unfolding = Var.of(Unfolding.FOLDED);
    private boolean unfoldingPlays;

    HostAccessPage(Var<GeniesState> state, Genies app) {
        this.state = state;
        theme = Theme.of(state);
        palette = Theme.paletteOf(state);
        pageWords = Theme.langOf(state).viewAs(Lang.HostAccessPage.class, Lang::HOST_ACCESS_PAGE);
        this.app = app;
        genie      = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        hostAccess = genie.zoomTo(Genie::hostAccess, Genie::withHostAccess);
        draft      = hostAccess.zoomTo(HostAccess::draft, HostAccess::withDraft);
        mayRead    = draft.zoomTo(HostAccess.PermissionRules::mayRead, HostAccess.PermissionRules::withMayRead);
        mayChange  = draft.zoomTo(HostAccess.PermissionRules::mayChange, HostAccess.PermissionRules::withMayChange);
        words      = draft.zoomTo(HostAccess.PermissionRules::words, HostAccess.PermissionRules::withWords);
        commands   = draft.zoomTo(HostAccess.PermissionRules::commands, HostAccess.PermissionRules::withCommands);
        askOutsideHome = draft.zoomTo(HostAccess.PermissionRules::askOutsideHome, HostAccess.PermissionRules::withAskOutsideHome);
        changeable = genie.viewAs(Boolean.class, it -> asleep(it) && it.hostAccess().lamp() && !it.hostAccess().busy());
    }

    UIForAnySwing<?, ?> view(Val<Boolean> visible) {
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            .onResize(it -> state.update(From.VIEW, s -> s.withArea(it.getWidth(), it.getHeight())))
            .add(
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 18, 18)
                .withMinSize(0, 0).withPrefSize(REFERENCE, 0)
                .withStyle(palette, (p, it) -> it
                    .backgroundColor(p.TRANSPARENT())
                    .padding(10 - Look.SEA_ABOVE, 4 - Look.CANAL, 28, 4)
                )
                .add(WHOLE, top())
                .add(SWITCH, onOrOff())
                .add(BESIDE_THE_SWITCH, experimental())
                .add(FOLDERS,
                    text(Lang.HostAccessPage::FOLDERS_IT_MAY_READ, mayRead, Lang.HostAccessPage::ONE_PER_LINE_SUCH_AS_DOCUMENTS)
                )
                .add(FOLDERS,
                    text(
                        Lang.HostAccessPage::FOLDERS_IT_MAY_READ_AND_CHANGE,
                        mayChange,
                        Lang.HostAccessPage::IT_MAY_WRITE_FILES_THERE_AND_COPY_WHAT_IT_MADE_THERE
                    )
                )
                .add(OWN_WORDS,
                    text(Lang.HostAccessPage::ANYTHING_ELSE_IN_YOUR_OWN_WORDS, words, Lang.HostAccessPage::SAY_WHAT_MUST_NOT_HAPPEN)
                )
                .add(WHO_RUNS_COMMANDS, commands())
                .add(BESIDE_WHO_RUNS_COMMANDS, ViewPartsUtil.note(theme, Viewable.of(String.class, commands, pageWords, HostAccessPage::meaning), Val.of(true)))
                .add(OUTSIDE_THE_HOME_FOLDER, outsideTheHomeFolder())
                .add(BESIDE_OUTSIDE_THE_HOME_FOLDER,
                    ViewPartsUtil.note(
                        theme,
                        pageWords.viewAsString(it -> it.IT_CAN_ONLY_REACH_YOUR_HOME_FOLDER_WITH_YES_GENIES_ASKS_YOU_ABOUT_OTHERS(HOME.toString())),
                        Val.of(true)
                    )
                )
                .add(WHOLE, howYouAreAsked())
                .add(WHOLE, saving())
            );
    }

    private static boolean asleep(Genie genie) {
        return genie.phase() == Genie.Phase.ASLEEP || genie.phase() == Genie.Phase.BROKEN;
    }

    // ─── on top: what this is, and why it cannot be changed now ────────────────────────────

    /// Why the rules cannot be changed now, in a sentence; empty when they can.
    private static String locked(Genie genie, Lang.HostAccessPage words) {
        HostAccess access = genie.hostAccess();
        if (!access.read()) return words.READING_THE_HOST_ACCESS();
        if (!access.lamp()) return words.WAKE_IT_ONCE_FIRST_ITS_SETTINGS_ARE_KEPT_IN_ITS_LAMP(genie.name());
        if (!asleep(genie)) return words.SEND_IT_TO_SLEEP_TO_CHANGE_THESE(genie.name());
        return "";
    }

    private UIForAnySwing<?, ?> top() {
        Val<String> lockedBecause = Viewable.of(String.class, genie, pageWords, HostAccessPage::locked);
        Val<String> says = Viewable.of(String.class, genie, pageWords, (it, page) ->
            it.hostAccess().saved().on()
                ? page.MAY_ASK_TO_REACH_THIS_COMPUTER_A_MODEL_JUDGES_EACH_REQUEST(it.name())
                : page.CANNOT_REACH_THIS_COMPUTER(it.name())
        );
        Val<String> problem = Viewable.of(String.class, hostAccess, pageWords, (it, page) -> it.problem().map(why -> why.words(page.lang())).orElse(""));
        return
            box("fill, wrap 1, ins 0, gap 8, hidemode 3", "[grow]")
            // The title, and the explanation that unfolds under it, with no gap of its own
            // between them: the explanation brings its own as it opens.
            .add("growx, wmin 0",
                box("fillx, wrap 1, ins 0, gap 0", "[grow]")
                .add(
                    box("ins 0, gap 10", "[][]push")
                    .add(label(pageWords.viewAsString(Lang.HostAccessPage::HOST_ACCESS)).group(Skin.EMPTY_TITLE))
                    .add("aligny center", whatItMeans())
                )
                .add("growx, wmin 0", explanation())
            )
            .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, says, Palette::SUBTEXT, Val.of(true)))
            // As loud as the banner of a request for permission: the user should not have to
            // find out by typing that nothing they type is kept.
            .add("growx, wmin 0",
                panel("fill, ins 10 14 10 14, gap 10", "[][grow]")
                .isVisibleIf(lockedBecause.viewAs(Boolean.class, it -> !it.isEmpty()))
                .withStyle(palette, (p, it) -> it.backgroundColor(p.YOURS()).borderRadius(12).borderAt(UI.Edge.LEFT, 3, p.BRASS()))
                .add("top, gaptop 2", label("").withStyle(palette, (p, it) -> it.icon(SignSvgUtil.sign(SignSvgUtil.SLEEP, p.BRASS()))))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, lockedBecause, Palette::TEXT, Val.of(true)))
            )
            .add("growx, wmin 0", ViewPartsUtil.wrapped(theme, problem, Palette::TROUBLE, problem.viewAs(Boolean.class, it -> !it.isEmpty())));
    }

    // ─── what it all means ─────────────────────────────────────────────────────────────────

    /// The `?` beside the title, which unfolds the explanation, and folds it away again. Lit
    /// while it is unfolded.
    private UIForAnySwing<?, ?> whatItMeans() {
        return
            button("?").group(Skin.ICON_BUTTON)
            .withTooltip(Viewable.of(String.class, explained, pageWords, (open, page) -> open ? page.FOLD_THE_EXPLANATION_AWAY() : page.HOW_HOST_ACCESS_WORKS()))
            .withStyle(Themed.of(explained, theme), (themed, it) -> {
                boolean open = themed.item();
                Palette p = themed.palette();
                return it
                    .prefSize(22, 22).minSize(22, 22).maxSize(22, 22)
                    .padding(0)
                    .borderRadius(11)
                    .border(1, open ? p.BRASS() : p.BORDER())
                    .backgroundColor(open ? p.RAISED() : p.TRANSPARENT())
                    .cursor(UI.Cursor.HAND)
                    .componentFont(f -> f.family(themed.fonts().FONT()).size(12).weight(2f).color(open ? p.BRASS() : p.SUBTEXT()));
            })
            .onClick(_ -> {
                explained.update(From.VIEW, open -> !open);
                unfold();
            });
    }

    /// The explanation, in a panel that opens as tall as its words, as far as it has unfolded;
    /// the words are laid out as tall as they are all along, and the panel shows as much of
    /// them as it is tall.
    private UIForAnySwing<?, ?> explanation() {
        Val<String> genieName = genie.viewAsString(Genie::name);
        return
            // The gap above the panel opens with it, so that it does not come at once either.
            box().withLayout(unfolding.viewAs(Layout.class, it -> belowTheTitle(it.height())))
            .add(
                // Laid out while folded too, then no taller than nothing, so that its words
                // have their width before it opens. How tall it opens to, its layout asks its
                // words, at the width they have, on every pass: see UnfoldingLayout.
                box().withLayout(unfolding.viewAs(Layout.class, it -> new UnfoldingLayout(it.height())))
                .withStyle(palette, (p, it) -> it.backgroundColor(p.SMOKE()).borderRadius(12))
                .add(
                    box("fillx, wrap 1, ins 14 16 16 16, gap 10", "[grow]")
                    .add("growx, wmin 0", faded(pageWords.viewAsString(Lang.HostAccessPage::HOW_IT_WORKS), 11, 2f, Palette::SUBTEXT))
                    .add("growx, wmin 0",
                        faded(Viewable.of(String.class, pageWords, genieName, Lang.HostAccessPage::THE_MAIN_SWITCH_GIVES_IT_TOOLS_TO_REACH_THIS_COMPUTER_AT_ALL), 12, 1f, Palette::TEXT)
                    )
                    .add("growx, wmin 0",
                        faded(pageWords.viewAsString(Lang.HostAccessPage::A_REQUEST_THE_CHECKER_JUDGES_IT_YOU_DECIDE_WHERE_YOU_ARE_ASKED_IT_HAPPENS), 12, 2f, Palette::BRASS)
                    )
                    .add("growx, wmin 0", faded(pageWords.viewAsString(Lang.HostAccessPage::EVERY_REQUEST_FIRST_GOES_TO_A_SEPARATE_MODEL_THE_CHECKER), 12, 1f, Palette::TEXT))
                    .add("growx, wmin 0", faded(pageWords.viewAsString(Lang.HostAccessPage::YOUR_HOME_FOLDER_IS_A_FENCE_THE_CHECKER_CANNOT_WIDEN), 12, 1f, Palette::TEXT))
                    .add("growx, wmin 0", faded(pageWords.viewAsString(Lang.HostAccessPage::COMMANDS_ARE_DIFFERENT_SO_YOU_CHOOSE_WHO_APPROVES_THEM), 12, 1f, Palette::TEXT))
                    .add("growx, wmin 0",
                        faded(pageWords.viewAsString(Lang.HostAccessPage::WHEN_YOU_ARE_ASKED_THE_QUESTION_APPEARS_AT_THE_TOP_OF_THE_GENIES_PAGES), 12, 1f, Palette::TEXT)
                    )
                )
            );
    }

    /// The room of the explanation under the title: as far below the title as it has opened.
    private static Layout belowTheTitle(double part) {
        return
            Layout.mig(
                LayoutConstraint.of("fillx, ins " + Math.round(part * 8) + " 0 0 0, gap 0"),
                LayoutConstraint.of("[grow, fill]"),
                LayoutConstraint.of("")
            )
            .withChildConstraints(MigAddConstraint.of("growx, wmin 0"));
    }

    /// Words of the explanation, as faded in as it has unfolded, wrapped to the width they get.
    /// A text area that cannot be typed in, rather than words painted by the style engine:
    /// it knows how tall its words are from its width alone, before they were ever painted,
    /// and so the panel knows from the start how far to open.
    private UIForAnySwing<?, ?> faded(Val<String> text, int size, float weight, Function<Palette, Color> colour) {
        return
            textArea(text).peek(ViewPartsUtil::softWrap)
            .isEditableIf(false)
            .isFocusableIf(false)
            .withStyle(Themed.of(unfolding, theme), (themed, it) -> {
                Color shade = ViewPartsUtil.withAlpha(colour.apply(themed.palette()), (int) Math.round(255 * themed.item().words()));
                return it
                    .backgroundColor(themed.palette().TRANSPARENT())
                    .border(0, themed.palette().TRANSPARENT())
                    .padding(0)
                    .cursor(UI.Cursor.DEFAULT)
                    .componentFont(f -> f.family(themed.fonts().FONT()).size(size).weight(weight).color(shade));
            });
    }


    /// Unfolds the explanation, or folds it away, one frame after the other, until it arrived
    /// where the user wants it. The user may change their mind on the way: it turns back from
    /// where it is.
    private void unfold() {
        if (unfoldingPlays || unfolding.get().arrived(explained.get())) return;
        unfoldingPlays = true;
        UI.animateFor(LifeTime.of(1, TimeUnit.SECONDS)).asLongAs(_ -> !unfolding.get().arrived(explained.get())).go(new Animation() {
            private double previous;

            @Override public void run(AnimationStatus status) {
                double elapsed = status.repeats() + status.progress();
                unfolding.update(From.VIEW, it -> it.toward(explained.get(), elapsed - previous));
                previous = elapsed;
            }

            @Override public void finish(AnimationStatus status) {
                unfoldingPlays = false;
                // The user may have changed their mind between the last frame and this callback.
                unfold();
            }
        });
    }

    // ─── the switch, and what it switches ──────────────────────────────────────────────────

    private UIForAnySwing<?, ?> onOrOff() {
        Val<Boolean> on = draft.viewAs(Boolean.class, HostAccess.PermissionRules::on);
        return
            box("fillx, wrap 1, ins 0, gap 8", "[grow]")
            .add("growx, wmin 0", section(Lang.HostAccessPage::MAY_IT_REACH_THIS_COMPUTER))
            .add(ViewPartsUtil.yesOrNo(theme, Theme.langOf(state).viewAs(Lang.Parts.class, Lang::PARTS), on, changeable, yes -> draft.update(From.VIEW, it -> it.withOn(yes))));
    }

    private UIForAnySwing<?, ?> experimental() {
        return
            panel("fill, ins 10 14 10 14, gap 10", "[][grow]")
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TROUBLE_WASH()).borderRadius(12))
            .add("top", label("⚠").withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(14).color(now.palette().TROUBLE()))))
            .add("growx, wmin 0",
                ViewPartsUtil.wrapped(
                    theme,
                    pageWords.viewAsString(Lang.HostAccessPage::EXPERIMENTAL_NEVER_TURN_THIS_ON_ON_A_COMPUTER_WITH_SENSITIVE_DATA),
                    Palette::TEXT,
                    Val.of(true)
                )
            );
    }

    // ─── when the user is asked ────────────────────────────────────────────────────────────

    /// Whoever is asked must be there to answer: the page says so while anything asks them.
    private UIForAnySwing<?, ?> howYouAreAsked() {
        return
            panel("fill, ins 10 14 10 14, gap 10", "[grow]")
            .isVisibleIf(draft.viewAs(Boolean.class, HostAccess.PermissionRules::asksYou))
            .withStyle(palette, (p, it) -> it.backgroundColor(p.YOURS()).borderRadius(12).borderAt(UI.Edge.LEFT, 3, p.BRASS()))
            .add("growx, wmin 0",
                ViewPartsUtil.wrapped(
                    theme,
                    pageWords.viewAsString(Lang.HostAccessPage::GENIES_ASKS_YOU_AT_THE_TOP_OF_THE_GENIES_PAGES_NO_ANSWER_WITHIN_A_MINUTE_COUNTS_AS_NO),
                    Palette::TEXT,
                    Val.of(true)
                )
            );
    }

    /// Who lets it run commands: its heading, and a switch of three parts, each saying what it
    /// means to a pointer that rests on it. What the chosen one means stands beside it.
    private UIForAnySwing<?, ?> commands() {
        Val<Boolean> inARow = state.viewAs(Boolean.class, it -> it.area().width() >= UI.scale(PARTS_IN_A_ROW_FROM));
        return
            box().withLayout(inARow.viewAs(Layout.class, it -> it ? PARTS_IN_A_ROW : PARTS_ONE_UNDER_THE_OTHER))
            .add(section(Lang.HostAccessPage::WHO_LETS_IT_RUN_COMMANDS))
            .add(
                ViewPartsUtil.oneOf(
                    theme,
                    java.util.Arrays.stream(HostAccess.Commands.values())
                        .map(it -> ViewPartsUtil.Choice.of(it, pageWords.viewAsString(page -> name(it, page)), pageWords.viewAsString(page -> meaning(it, page))))
                        .toList(),
                    commands,
                    changeable,
                    inARow,
                    it -> commands.set(From.VIEW, it)
                )
            );
    }

    /// The name of a part of the switch of who lets it run commands. Letting the model that
    /// judges decide alone carries a sign.
    private static String name(HostAccess.Commands commands, Lang.HostAccessPage words) {
        return switch (commands) {
            case NEVER -> words.NOBODY();
            case YOU_EACH_TIME -> words.YOU_EACH_TIME();
            case THE_MODEL_ALONE -> words.THE_MODEL_ALONE() + "  ⚠";
        };
    }

    /// What choosing a part of the switch of who lets it run commands means.
    private static String meaning(HostAccess.Commands commands, Lang.HostAccessPage words) {
        return switch (commands) {
            case NEVER -> words.IT_CANNOT_RUN_COMMANDS_AT_ALL();
            case YOU_EACH_TIME -> words.THE_MODEL_TURNS_AWAY_WHAT_CLEARLY_BREAKS_YOUR_RULES_EVERY_OTHER_COMMAND_COMES_TO_YOU();
            case THE_MODEL_ALONE -> words.COMMANDS_THE_MODEL_ALLOWS_RUN_STRAIGHT_AWAY_SAY_WHICH_ARE_FINE();
        };
    }

    // ─── folders outside the home folder ───────────────────────────────────────────────────

    /// Whether it asks about folders outside the home folder: its heading, and No or Yes. What
    /// that means stands beside it.
    private UIForAnySwing<?, ?> outsideTheHomeFolder() {
        return
            box("fillx, wrap 1, ins 0, gap 8", "[grow]")
            .add("growx, wmin 0", section(Lang.HostAccessPage::ASK_YOU_ABOUT_FOLDERS_OUTSIDE_YOUR_HOME_FOLDER))
            .add(ViewPartsUtil.yesOrNo(theme, Theme.langOf(state).viewAs(Lang.Parts.class, Lang::PARTS), askOutsideHome, changeable, yes -> askOutsideHome.set(From.VIEW, yes)));
    }

    // ─── saving ────────────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> saving() {
        Val<String> warning = Viewable.of(String.class, draft, Theme.langOf(state), (it, lang) -> it.warning(lang, HOME).orElse(""));
        Val<Boolean> changed = hostAccess.viewAs(Boolean.class, HostAccess::changed);
        Val<Boolean> canSave = Viewable.of(Boolean.class, changeable, changed, (a, b) -> a && b);
        Val<Boolean> busy = hostAccess.viewAs(Boolean.class, HostAccess::busy);
        Val<Layout> layout = state.viewAs(Layout.class, it -> {
            int width = it.area().width();
            if (width >= UI.scale(SAVING_IN_ONE_ROW_FROM)) return SAVING_IN_ONE_ROW;
            if (width >= UI.scale(BUTTONS_SIDE_BY_SIDE_FROM)) return SAVING_UNDER_THE_WARNING;
            return SAVING_ONE_UNDER_THE_OTHER;
        });
        return
            box().withLayout(layout)
            .add(ViewPartsUtil.wrapped(theme, warning, Palette::BRASS, warning.viewAs(Boolean.class, it -> !it.isEmpty())))
            .add(
                button(pageWords.viewAsString(Lang.HostAccessPage::UNDO_CHANGES)).group(Skin.QUIET_BUTTON)
                .isEnabledIf(canSave)
                .onClick(_ -> hostAccess.update(From.VIEW, access -> access.withDraft(access.saved())))
            )
            .add(
                button(Viewable.of(String.class, busy, pageWords, (it, page) -> it ? page.SAVING() : page.SAVE())).group(Skin.FLAME_BUTTON)
                .isEnabledIf(canSave)
                .withTooltip(pageWords.viewAsString(Lang.HostAccessPage::WRITE_THESE_RULES_INTO_THE_GENIES_LAMP))
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
                .onClick(_ -> app.saveHostAccess(selected()))
            );
    }

    // ─── small parts ───────────────────────────────────────────────────────────────────────

    private UUID selected() { return state.get().selected(); }

    /// A section's heading, dimmed while nothing under it can be changed. Wrapped, rather than
    /// cut short, on a narrow page.
    private UIForAnySwing<?, ?> section(Function<Lang.HostAccessPage, String> heading) {
        Val<Heading> shown = Viewable.of(Heading.class, pageWords.viewAsString(heading), changeable, Heading::new);
        return
            box().withMinSize(0, 0)
            .withStyle(Themed.of(shown, theme), (themed, it) -> {
                Heading now = themed.item();
                Palette p = themed.palette();
                Color colour = now.changeable() ? p.SUBTEXT() : ViewPartsUtil.dim(p.SUBTEXT());
                return it
                    .padding(1, 0, 1, 0)
                    .text(t ->
                        t.content(StyledString.of(f -> f.family(themed.fonts().FONT()).size(11).weight(2f).spacing(0.14f).color(colour), now.text()))
                        .placement(UI.Placement.TOP_LEFT)
                        .wrapLines(true)
                        .autoPreferredHeight(true)
                    );
            });
    }

    /// A section's heading, and whether what is under it can be changed.
    private record Heading(String text, boolean changeable) {}

    /// One of the three texts: a heading, the text, as tall as the other two so they line up
    /// side by side, and a note under it. While the rules cannot be changed, the text sinks into
    /// the page, greyed, and cannot be typed in.
    private UIForAnySwing<?, ?> text(Function<Lang.HostAccessPage, String> heading, Var<String> text, Function<Lang.HostAccessPage, String> note) {
        return
            box("fillx, wrap 1, ins 0, gap 8", "[grow]")
            .add("growx, wmin 0", section(heading))
            .add("growx, wmin 0, h " + TEXT_HEIGHT + "!",
                scrollPane().withEmptyBorder(0)
                .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                .withStyle(Themed.of(changeable, theme), (themed, it) -> {
                    var can = themed.item();
                    Palette p = themed.palette();
                    return it
                        .backgroundColor(can ? p.RAISED() : p.SMOKE())
                        .border(1, can ? p.BORDER() : p.SMOKE())
                        .borderRadius(12);
                })
                .add(
                    textArea(text).group(Skin.INPUT).peek(ViewPartsUtil::softWrap)
                    .isEnabledIf(changeable)
                    .withStyle(Themed.of(changeable, theme), (themed, it) -> {
                        var can = themed.item();
                        Palette p = themed.palette();
                        return it
                            .backgroundColor(p.TRANSPARENT())
                            .border(0, p.TRANSPARENT())
                            .padding(10, 12, 10, 12)
                            .cursor(can ? UI.Cursor.TEXT : UI.Cursor.DEFAULT)
                            .componentFont(f -> f.family(themed.fonts().FONT()).size(14).color(can ? p.TEXT() : ViewPartsUtil.dim(p.SUBTEXT())));
                    })
                )
            )
            .add("growx, wmin 0", ViewPartsUtil.note(theme, pageWords.viewAsString(note), Val.of(true)));
    }
}
