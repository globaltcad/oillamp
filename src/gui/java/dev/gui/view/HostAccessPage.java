package dev.gui.view;

import java.awt.Color;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.swing.JPanel;

import dev.gui.Genies;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.HostAccess;
import dev.gui.model.Lang;
import dev.gui.model.Palette;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForBox;
import swingtree.UIForPanel;
import swingtree.components.JBox;
import swingtree.animation.Animation;
import swingtree.animation.AnimationStatus;
import swingtree.animation.LifeTime;
import swingtree.api.Layout;
import swingtree.layout.FlowCell;
import swingtree.layout.LayoutConstraint;
import swingtree.layout.MigAddConstraint;
import swingtree.style.StyledString;

import static swingtree.UI.*;

/// A genie's host access, in the order of the `[host_access]` table of its lamp's file: what its
/// agent may do on this computer, what guards each of its requests, the folders it may read, the
/// folders it may read and change, and the instructions for the checker. Then, from its `[web]`
/// table, which web servers in its sandbox this computer's browser may open. Genies writes them
/// into the genie's lamp as they are; see [HostAccess].
///
/// How a request goes from the genie to this computer, the page explains in a panel under its
/// title, which the user unfolds with the `?` beside it.
///
/// The lamp reads them as it wakes, so they can only be changed while the genie sleeps. While it
/// is awake, the page shows them, greyed, and says so in a banner; and under the web servers
/// its lamp lists, the ones its agent opened since it woke, each with the link that opens it.
///
/// Like the rest of the window, a function of the [GeniesState]: a lens onto the selected genie's
/// [HostAccess], and [Genies] for saving.
final class HostAccessPage {

    /*
     *  A responsive grid, read top to bottom as the lamp's file is. Every part takes the whole
     *  row, except the two allowlists, which stand side by side from LARGE up. What it may do
     *  and what guards it share their row too, side by side on a page at least
     *  TIERS_SIDE_BY_SIDE_FROM wide, and one under the other on a narrower one:
     *
     *                          very small  small  medium  large  very large
     *      on top, the caution     12        12     12      12       12
     *      what it may do, and
     *      what guards it          12        12     12      12       12
     *      each allowlist          12        12     12       6        6
     *      checker instructions    12        12     12      12       12
     *      web servers             12        12     12      12       12
     *      saving                  12        12     12      12       12
     *
     *  Each switch has its heading above it and what the chosen part means under it. Its parts
     *  stand in a row on a page at least as wide as their row, in the widest of the languages,
     *  and one under the other on a narrower one.
     *
     *  Saving is a row of its own, the mistake beside the buttons. On a page narrower than
     *  SAVING_IN_ONE_ROW_FROM, the mistake would be squeezed into a column a word wide, so the
     *  buttons go under it, to the right, and on a page narrower still, one under the other:
     *
     *      from SAVING_IN_ONE_ROW_FROM     [mistake ……………………………  Undo changes  Save]
     *      from BUTTONS_SIDE_BY_SIDE_FROM  [mistake ………………………………………………………]
     *                                      [                 Undo changes  Save]
     *      narrower still, where the two   [mistake ……………………………]
     *      buttons would not fit beside    [          Save          ]
     *      each other in every language    [      Undo changes      ]
     *
     *  The web servers are a switch, like the others, and under it the ports the lamp's file
     *  lists, and, while the genie is awake, the ones its agent opened. Each port is a row: its
     *  name, its port in the sandbox, and while the lamp is lit, the link on this computer; and
     *  under them, what serves a port the agent opened. On a page narrower than
     *  PORT_IN_ONE_LINE_FROM, the link goes under the name, as the name may be long:
     *
     *      from PORT_IN_ONE_LINE_FROM      [opencode  4096  →  http://k3v7x2ab.localhost:41237/    ]
     *      narrower                        [opencode  4096                                         ]
     *                                      [→  http://k3v7x2ab.localhost:41237/                    ]
     *
     *  While the genie sleeps, a listed port has no link, and a × at the end of its line removes
     *  it from the list.
     *
     *  Under the ports the lamp's file lists, while the genie sleeps, a row adds one, its fields
     *  side by side from ADDING_IN_ONE_LINE_FROM, and one under the other on a narrower page.
     *
     *  The widths are the page's, as the state has them: [GeniesState#area].
     */
    private static final int REFERENCE = 1100;
    private static final FlowCell WHOLE = AUTO_SPAN(it -> it.align(UI.VerticalAlignment.TOP).verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(12));
    private static final FlowCell ALLOWLIST = AUTO_SPAN(it -> it.align(UI.VerticalAlignment.TOP).verySmall(12).small(12).medium(12).large(6).veryLarge(6).oversize(6));
    /// How tall each allowlist is, so that the two line up side by side, and the checker
    /// instructions, which may say a lot more.
    private static final int ALLOWLIST_HEIGHT = 92;
    private static final int INSTRUCTIONS_HEIGHT = 150;
    /// From these widths of the page, the parts of a switch stand in a row. With its longest
    /// part chosen, so in bold, the row of what it may do is 469 units wide in German, the
    /// widest of the languages for it, and the row of what guards it 454 in Japanese; the page
    /// adds 40 of padding around it, and the scroll bar 12 at most.
    private static final int MAY_IN_A_ROW_FROM = 525;
    private static final int GUARDS_IN_A_ROW_FROM = 510;
    /// From this width of the page, what it may do and what guards it stand side by side, each
    /// in a column as wide as the other: both switches still fit their columns in a row, with
    /// the gap of 18 between the columns.
    private static final int TIERS_SIDE_BY_SIDE_FROM = 1010;
    /// What it may do and what guards it, in this order: side by side, each at the top of its
    /// column, or one under the other.
    private static final Layout TIERS_SIDE_BY_SIDE = Layout.mig(
        LayoutConstraint.of("fillx, ins 0, gap 18 18"), LayoutConstraint.of("[0:0, grow, fill, sg tier][0:0, grow, fill, sg tier]"), LayoutConstraint.of("")
    ).withChildConstraints(MigAddConstraint.of("growx, aligny top, wmin 0"), MigAddConstraint.of("growx, aligny top, wmin 0"));
    private static final Layout TIERS_ONE_UNDER_THE_OTHER = Layout.mig(
        LayoutConstraint.of("fillx, wrap 1, ins 0, gap 18 18"), LayoutConstraint.of("[grow, fill]"), LayoutConstraint.of("")
    ).withChildConstraints(MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of("growx, wmin 0"));
    private static final int SAVING_IN_ONE_ROW_FROM = 560;
    private static final int BUTTONS_SIDE_BY_SIDE_FROM = 400;
    /// The mistake, Undo changes and Save, in this order.
    private static final Layout SAVING_IN_ONE_ROW = savingLayout("[grow][][]", "cell 0 0, growx, wmin 0", "cell 1 0", "cell 2 0");
    private static final Layout SAVING_UNDER_THE_MISTAKE = savingLayout("[grow][][]", "cell 0 0, span 3, growx, wmin 0", "cell 1 1", "cell 2 1");
    private static final Layout SAVING_ONE_UNDER_THE_OTHER = savingLayout("[grow, fill]", "cell 0 0, wmin 0", "cell 0 2", "cell 0 1");

    /// From this width of the page, the four parts of which web servers it may open stand in a
    /// row: with its longest part chosen, the row is 324 units wide in Spanish, the widest of the
    /// languages for it; the page adds 40 of padding around it, and the scroll bar 12 at most.
    private static final int PUBLISH_IN_A_ROW_FROM = 380;
    private static final int PORT_IN_ONE_LINE_FROM = 640;
    private static final int ADDING_IN_ONE_LINE_FROM = 520;
    /// A port's name and its port in the sandbox, its link, the button that removes it, and the
    /// program serving it, in this order, which stand as the picture above shows.
    private static final Layout PORT_IN_ONE_LINE = portLayout("cell 1 0, growx, wmin 0", "cell 0 1, span 3, growx, wmin 0");
    private static final Layout PORT_IN_TWO_LINES = portLayout("cell 0 1, span 2, growx, wmin 0", "cell 0 2, span 3, growx, wmin 0");
    /// Name, its field, Port, its field and Add, in this order: in one line, or each label over
    /// its field, and Add under them, to the right.
    private static final Layout ADDING_IN_ONE_LINE = Layout.mig(
        LayoutConstraint.of("fillx, ins 0, gap 8"), LayoutConstraint.of("[][220!][][90!][]push"), LayoutConstraint.of("")
    ).withChildConstraints(
        MigAddConstraint.of("cell 0 0"), MigAddConstraint.of("cell 1 0, growx"), MigAddConstraint.of("cell 2 0"),
        MigAddConstraint.of("cell 3 0, growx"), MigAddConstraint.of("cell 4 0")
    );
    private static final Layout ADDING_ONE_UNDER_THE_OTHER = Layout.mig(
        LayoutConstraint.of("fillx, wrap 1, ins 0, gap 4"), LayoutConstraint.of("[grow, fill]"), LayoutConstraint.of("")
    ).withChildConstraints(
        MigAddConstraint.of("wmin 0"), MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of("wmin 0, gaptop 4"),
        MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of("right, growx 0, gaptop 4")
    );

    private static Layout portLayout(String link, String program) {
        return
            Layout.mig(LayoutConstraint.of("fillx, ins 6 12 6 8, gap 12 2, hidemode 3"), LayoutConstraint.of("[][grow][]"), LayoutConstraint.of(""))
            .withChildConstraints(
                MigAddConstraint.of("cell 0 0"), MigAddConstraint.of(link), MigAddConstraint.of("cell 2 0, right"), MigAddConstraint.of(program)
            );
    }

    private static Layout savingLayout(String columns, String mistake, String undo, String save) {
        return
            Layout.mig(LayoutConstraint.of("fillx, ins 0, gap 12, hidemode 3"), LayoutConstraint.of(columns), LayoutConstraint.of(""))
            .withChildConstraints(MigAddConstraint.of(mistake), MigAddConstraint.of(undo), MigAddConstraint.of(save));
    }

    private final Var<GeniesState> state;
    /// The colours and the fonts of the state, and the colours alone.
    private final Val<Theme> theme;
    private final Val<Palette> palette;
    private final Genies app;
    private final Look look;

    // Lenses and views, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Var<HostAccess> hostAccess;
    private final Var<HostAccess.Rules> draft;
    private final Var<HostAccess.May> may;
    private final Var<HostAccess.Guards> guards;
    private final Var<String> readAllowlist;
    private final Var<String> changeAllowlist;
    private final Var<String> checkerInstructions;
    private final Var<HostAccess.Web> web;
    private final Var<HostAccess.Publish> publish;
    private final Val<String> genieName;
    /// The words of the page, unlike [#checkerInstructions], which are the user's own.
    private final Val<Lang.HostAccessPage> pageWords;
    private final Val<Lang.Header> headerWords;
    /// Whether the rules may be changed now: the genie sleeps, has a lamp, and nothing is on its way.
    private final Val<Boolean> changeable;
    /// Whether a checker reads the instructions: not with the allowlists alone.
    private final Val<Boolean> checked;
    /// Whether the genie is awake, or on its way there or back: its lamp may have web servers open.
    private final Val<Boolean> awake;
    /// The rows of the ports the lamp's file lists, and of the ones its agent opened this session.
    private final Val<Tuple<PortRow>> listedRows;
    private final Val<Tuple<PortRow>> openedRows;
    /// Whether a port's link stands beside its name, rather than under it.
    private final Val<Boolean> portInOneLine;
    /// The port the user types to add it to the list, before they add it.
    private final Var<String> newName = Var.of("");
    private final Var<String> newPort = Var.of("");
    /// Whether the user wants the explanation unfolded, and how far it has unfolded on its way there.
    private final Var<Boolean> explained = Var.of(false);
    private final Var<Unfolding> unfolding = Var.of(Unfolding.FOLDED);
    private boolean unfoldingPlays;

    HostAccessPage(Var<GeniesState> state, Genies app, Look look) {
        this.state = state;
        theme = Theme.of(state);
        palette = Theme.paletteOf(state);
        pageWords = Theme.langOf(state).viewAs(Lang.HostAccessPage.class, Lang::HOST_ACCESS_PAGE);
        headerWords = Theme.langOf(state).viewAs(Lang.Header.class, Lang::HEADER);
        this.app = app;
        this.look = look;
        genie           = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        hostAccess      = genie.zoomTo(Genie::hostAccess, Genie::withHostAccess);
        draft           = hostAccess.zoomTo(HostAccess::draft, HostAccess::withDraft);
        may             = draft.zoomTo(HostAccess.Rules::may, HostAccess.Rules::withMay);
        guards          = draft.zoomTo(HostAccess.Rules::guards, HostAccess.Rules::withGuards);
        readAllowlist   = draft.zoomTo(HostAccess.Rules::readAllowlist, HostAccess.Rules::withReadAllowlist);
        changeAllowlist = draft.zoomTo(HostAccess.Rules::changeAllowlist, HostAccess.Rules::withChangeAllowlist);
        checkerInstructions = draft.zoomTo(HostAccess.Rules::checkerInstructions, HostAccess.Rules::withCheckerInstructions);
        web             = draft.zoomTo(HostAccess.Rules::web, HostAccess.Rules::withWeb);
        publish         = web.zoomTo(HostAccess.Web::publish, HostAccess.Web::withPublish);
        genieName  = genie.viewAsString(Genie::name);
        changeable = genie.viewAs(Boolean.class, it -> asleep(it) && it.hostAccess().lamp() && !it.hostAccess().busy());
        checked    = guards.viewAs(Boolean.class, HostAccess.Guards::checks);
        awake      = genie.viewAs(Boolean.class, it -> !asleep(it));
        listedRows = genie.viewAs(Tuple.classTyped(PortRow.class), HostAccessPage::listed);
        openedRows = hostAccess.viewAs(Tuple.classTyped(PortRow.class), it ->
            it.openedThisSession().mapTo(PortRow.class, port -> new PortRow("", port.sandboxPort(), port.program(), Optional.of(port.link()), true))
        );
        portInOneLine = state.viewAs(Boolean.class, it -> it.area().width() >= PORT_IN_ONE_LINE_FROM);
    }

    UIForAnySwing<?, ?> view(Val<Boolean> visible) {
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            .onResize(it ->
                state.update(From.VIEW, s -> s.withArea(it.getWidth(), it.getHeight()))
            )
            .add(
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 18, 18)
                .withMinSize(0, 0).withPrefSize(REFERENCE, 0)
                .withStyle(palette, (p, it) -> it
                    .backgroundColor(p.TRANSPARENT())
                    .padding(10 - Look.SEA_ABOVE, 4 - Look.CANAL, 28, 4)
                )
                .add(WHOLE, top())
                .add(WHOLE,
                    box().withLayout(state.viewAs(Layout.class, it ->
                        it.area().width() >= TIERS_SIDE_BY_SIDE_FROM
                                ? TIERS_SIDE_BY_SIDE
                                : TIERS_ONE_UNDER_THE_OTHER
                    ))
                    .add(whatItMayDo())
                    .add(whatGuardsIt())
                )
                .add(ALLOWLIST,
                    text(
                        pageWords.viewAsString(Lang.HostAccessPage::FOLDERS_IT_MAY_READ),
                        readAllowlist,
                        pageWords.viewAsString(Lang.HostAccessPage::ITS_READ_ALLOWLIST_ONE_FOLDER_PER_LINE_EACH_WITH_EVERYTHING_BELOW_IT),
                        Val.of(true),
                        ALLOWLIST_HEIGHT
                    )
                )
                .add(ALLOWLIST,
                    text(
                        pageWords.viewAsString(Lang.HostAccessPage::FOLDERS_IT_MAY_READ_AND_CHANGE),
                        changeAllowlist,
                        pageWords.viewAsString(Lang.HostAccessPage::ITS_CHANGE_ALLOWLIST_COMMANDS_START_IN_THESE_TOO),
                        Val.of(true),
                        ALLOWLIST_HEIGHT
                    )
                )
                .add(WHOLE,
                    text(
                        pageWords.viewAsString(Lang.HostAccessPage::INSTRUCTIONS_FOR_THE_CHECKER),
                        checkerInstructions,
                        Viewable.of(String.class, checked, pageWords, (reads, page) ->
                            reads ? page.THE_CHECKER_IS_ANOTHER_AI_MODEL_OILLAMP_ASKS_IT_ABOUT_EVERY_REQUEST()
                                  : page.UNUSED_WITH_THE_ALLOWLISTS_ALONE_NO_CHECKER_READS_THESE()
                        ),
                        checked,
                        INSTRUCTIONS_HEIGHT
                    )
                )
                .add(WHOLE, webServers())
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
            .add("growx, wmin 0", experimental())
            // As loud as the banner of a request for permission: the user should not have to
            // find out by typing that nothing they type is kept.
            .add("growx, wmin 0",
                panel("fill, ins 10 14 10 14, gap 10", "[][grow]")
                .isVisibleIf(lockedBecause.viewAs(Boolean.class, it -> !it.isEmpty()))
                .withStyle(palette, (p, it) -> it
                    .backgroundColor(p.YOURS())
                    .borderRadius(12)
                    .borderAt(UI.Edge.LEFT, 3, p.BRASS())
                )
                .add("top, gaptop 2",
                    label("")
                    .withStyle(palette, (p, it) -> it
                        .icon(SignSvgUtil.sign(SignSvgUtil.SLEEP, p.BRASS()))
                    )
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(theme, lockedBecause, Palette::TEXT, Val.of(true))
                )
            )
            .add("growx, wmin 0",
                ViewPartsUtil.wrapped(theme, problem, Palette::TROUBLE, problem.viewAs(Boolean.class, it -> !it.isEmpty()))
            );
    }

    private UIForAnySwing<?, ?> experimental() {
        return
            panel("fill, ins 8 14 8 14, gap 10", "[][grow]")
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TROUBLE_WASH()).borderRadius(12))
            .add("top", label("⚠").withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(14).color(now.palette().TROUBLE()))))
            .add("growx, wmin 0",
                ViewPartsUtil.wrapped(
                    theme,
                    pageWords.viewAsString(Lang.HostAccessPage::EXPERIMENTAL_NEVER_ALLOW_ANYTHING_ON_A_COMPUTER_WITH_SENSITIVE_DATA),
                    Palette::TEXT,
                    Val.of(true)
                )
            );
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
    /// them as it is tall. It follows a request along the chain of guards, in their order.
    private UIForAnySwing<?, ?> explanation() {
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
                        faded(pageWords.viewAsString(Lang.HostAccessPage::A_REQUEST_THE_ALLOWLISTS_THE_CHECKER_YOU_WHERE_ASKED_IT_HAPPENS), 12, 2f, Palette::BRASS)
                    )
                    .add("growx, wmin 0", faded(pageWords.viewAsString(Lang.HostAccessPage::THE_ALLOWLISTS_ARE_CHECKED_BY_OILLAMP_ITSELF), 12, 1f, Palette::TEXT))
                    .add("growx, wmin 0",
                        faded(
                            Viewable.of(String.class, pageWords, genieName, Lang.HostAccessPage::THE_CHECKER_IS_ANOTHER_AI_MODEL_IT_SEES_YOUR_INSTRUCTIONS_AND_WHAT_THE_GENIE_DOES),
                            12, 1f, Palette::TEXT
                        )
                    )
                    .add("growx, wmin 0",
                        faded(pageWords.viewAsString(Lang.HostAccessPage::WHEN_YOU_ARE_ASKED_THE_QUESTION_APPEARS_AT_THE_TOP_OF_THE_GENIES_PAGES), 12, 1f, Palette::TEXT)
                    )
                    .add("growx, wmin 0",
                        faded(pageWords.viewAsString(Lang.HostAccessPage::A_COMMAND_ONCE_ALLOWED_IS_NOT_FENCED_BY_THE_ALLOWLISTS_BEYOND_WHERE_IT_STARTS), 12, 1f, Palette::TEXT)
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

    // ─── what it may do, and what guards it ────────────────────────────────────────────────

    /// What it may do: its heading, a switch of the four steps of the ladder, each saying what it
    /// means to a pointer that rests on it, and what the chosen one means under it.
    private UIForAnySwing<?, ?> whatItMayDo() {
        return
            choice(
                Viewable.of(String.class, pageWords, genieName, (page, name) -> page.WHAT_MAY_IT_DO_ON_THIS_COMPUTER(name.toUpperCase(Locale.ROOT))),
                Arrays.stream(HostAccess.May.values())
                    .map(it -> ViewPartsUtil.Choice.of(
                        it,
                        pageWords.viewAsString(page -> name(it, page)),
                        Viewable.of(String.class, pageWords, genieName, (page, name) -> meaning(it, page, name))
                    ))
                    .toList(),
                may,
                MAY_IN_A_ROW_FROM,
                Viewable.of(String.class, genie, pageWords, (it, page) -> meaning(it.hostAccess().draft().may(), page, it.name()))
            );
    }

    private static String name(HostAccess.May may, Lang.HostAccessPage words) {
        return switch (may) {
            case NOTHING -> words.NOTHING();
            case READ -> words.READ();
            case READ_CHANGE -> words.READ_AND_CHANGE();
            case READ_CHANGE_RUN -> words.READ_CHANGE_AND_RUN_COMMANDS();
        };
    }

    /// What choosing a step of what it may do means, for the genie called `genie`.
    private static String meaning(HostAccess.May may, Lang.HostAccessPage words, String genie) {
        return switch (may) {
            case NOTHING -> words.IT_HAS_NO_TOOLS_TO_REACH_THIS_COMPUTER(genie);
            case READ -> words.IT_MAY_LIST_FOLDERS_READ_FILES_AND_COPY_ONE_INTO_ITS_SANDBOX();
            case READ_CHANGE -> words.IT_MAY_ALSO_WRITE_AND_EDIT_FILES_COPY_ITS_WORK_OVER_AND_OPEN_A_WEB_PAGE();
            case READ_CHANGE_RUN -> words.IT_MAY_ALSO_RUN_COMMANDS_WHICH_CAN_DO_ANYTHING_YOU_CAN();
        };
    }

    /// What guards each request: its heading, a switch of the three links of the chain, what the
    /// chosen one means, and, while commands would run with nothing but the allowlists in their
    /// way, a warning nobody can miss. The page still saves it: it is the user's computer.
    private UIForAnySwing<?, ?> whatGuardsIt() {
        Val<Boolean> unchecked = draft.viewAs(Boolean.class, HostAccess.Rules::runsCommandsUnchecked);
        return
            choice(
                pageWords.viewAsString(Lang.HostAccessPage::WHAT_GUARDS_EACH_REQUEST),
                Arrays.stream(HostAccess.Guards.values())
                    .map(it -> ViewPartsUtil.Choice.of(it, pageWords.viewAsString(page -> name(it, page)), pageWords.viewAsString(page -> meaning(it, page))))
                    .toList(),
                guards,
                GUARDS_IN_A_ROW_FROM,
                Viewable.of(String.class, guards, pageWords, HostAccessPage::meaning)
            )
            .add(
                panel("fill, wrap 2, ins 12 14 12 14, gap 10 4", "[][grow]")
                .isVisibleIf(unchecked)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TROUBLE_WASH()).border(2, p.TROUBLE()).borderRadius(12))
                .add("top, spany 2", label("⚠").withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(20).color(now.palette().TROUBLE()))))
                .add("growx, wmin 0", ViewPartsUtil.words(theme, pageWords.viewAsString(Lang.HostAccessPage::COMMANDS_RUN_UNCHECKED), 13, 2f, Palette::TROUBLE))
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(
                        theme,
                        Viewable.of(String.class, pageWords, genieName, Lang.HostAccessPage::NOTHING_JUDGES_A_COMMAND_AND_NOBODY_ASKS_YOU),
                        Palette::TEXT,
                        Val.of(true)
                    )
                )
            );
    }

    private static String name(HostAccess.Guards guards, Lang.HostAccessPage words) {
        return switch (guards) {
            case ALLOWLISTS -> words.ALLOWLISTS();
            case ALLOWLISTS_CHECKER -> words.ALLOWLISTS_AND_CHECKER();
            case ALLOWLISTS_CHECKER_USER -> words.ALLOWLISTS_CHECKER_AND_YOU();
        };
    }

    /// What choosing a link of the chain of guards means.
    private static String meaning(HostAccess.Guards guards, Lang.HostAccessPage words) {
        return switch (guards) {
            case ALLOWLISTS -> words.ONLY_THE_ALLOWLISTS_NO_MODEL_JUDGES_AND_NOBODY_ASKS_YOU();
            case ALLOWLISTS_CHECKER -> words.THEN_THE_CHECKER_ANOTHER_AI_MODEL_ALLOWS_OR_REFUSES_EACH_REQUEST();
            case ALLOWLISTS_CHECKER_USER -> words.THEN_YOU_ABOUT_EVERY_COMMAND_AND_A_PATH_OUTSIDE_THE_ALLOWLISTS();
        };
    }

    /// A choice of the page: its heading, a switch of parts, and what the chosen part means, one
    /// under the other. The parts stand in a row on a page at least `inARowFrom` wide, and one
    /// under the other, as wide as the page, on a narrower one.
    private <T> UIForBox<JBox> choice(Val<String> heading, List<ViewPartsUtil.Choice<T>> choices, Var<T> chosen, int inARowFrom, Val<String> meaning) {
        Val<Boolean> inARow = state.viewAs(Boolean.class, it -> it.area().width() >= inARowFrom);
        return
            box().withLayout(inARow.viewAs(Layout.class, it -> it ? PARTS_IN_A_ROW : PARTS_ONE_UNDER_THE_OTHER))
            .add(section(heading, Val.of(true)))
            .add(ViewPartsUtil.oneOf(theme, choices, chosen, changeable, inARow, it -> chosen.set(From.VIEW, it)))
            .add(ViewPartsUtil.note(theme, meaning, Val.of(true)));
    }

    /// The heading, the switch and what the chosen part means, and a warning that may follow them. The
    /// switch is as wide as its parts while they stand in a row, and as wide as the page while
    /// they stand one under the other.
    private static final Layout PARTS_IN_A_ROW = Layout.mig(
        LayoutConstraint.of("fillx, wrap 1, ins 0, gap 8, hidemode 3"), LayoutConstraint.of("[grow]"), LayoutConstraint.of("")
    ).withChildConstraints(MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of(""), MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of("growx, wmin 0, gaptop 4"));
    private static final Layout PARTS_ONE_UNDER_THE_OTHER = Layout.mig(
        LayoutConstraint.of("fillx, wrap 1, ins 0, gap 8, hidemode 3"), LayoutConstraint.of("[grow]"), LayoutConstraint.of("")
    ).withChildConstraints(MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of("growx, wmin 0"), MigAddConstraint.of("growx, wmin 0, gaptop 4"));

    // ─── which web servers the browser may open ────────────────────────────────────────────

    /// Which web servers it may publish: a switch of the four steps of the ladder, each saying
    /// what it means to a pointer that rests on it, and what the chosen one means under it. Then
    /// the ports the lamp's file lists, and, while the genie is awake, the ones its agent opened.
    private UIForAnySwing<?, ?> webServers() {
        Val<Boolean> listedNone = listedRows.viewAs(Boolean.class, Tuple::isEmpty);
        Val<Boolean> openedNone = openedRows.viewAs(Boolean.class, Tuple::isEmpty);
        return
            box("fillx, wrap 1, ins 0, gap 8, hidemode 3", "[grow]")
            .add("growx, wmin 0",
                choice(
                    pageWords.viewAsString(Lang.HostAccessPage::WHICH_WEB_SERVERS_IN_ITS_SANDBOX_MAY_YOUR_BROWSER_OPEN),
                    Arrays.stream(HostAccess.Publish.values())
                        .map(it -> ViewPartsUtil.Choice.of(
                            it,
                            pageWords.viewAsString(page -> name(it, page)),
                            Viewable.of(String.class, pageWords, genieName, (page, name) -> meaning(it, page, name))
                        ))
                        .toList(),
                    publish,
                    PUBLISH_IN_A_ROW_FROM,
                    Viewable.of(String.class, genie, pageWords, (it, page) -> meaning(it.hostAccess().draft().web().publish(), page, it.name()))
                )
            )
            .add("growx, wmin 0, gaptop 10", section(pageWords.viewAsString(Lang.HostAccessPage::LISTED_IN_ITS_LAMP), Val.of(true)))
            .add("growx, wmin 0",
                ports(listedRows, pageWords.viewAsString(Lang.HostAccessPage::NOTHING_IS_LISTED), listedNone)
                .add("growx, wmin 0", adding().isVisibleIf(awake.viewAs(Boolean.class, it -> !it)))
            )
            .add("growx, wmin 0",
                ViewPartsUtil.note(
                    theme,
                    Viewable.of(String.class, genie, pageWords, (it, page) -> asleep(it)
                        ? page.EACH_A_NAME_AND_ITS_PORT_IN_THE_SANDBOX_PUBLISHED_EACH_TIME_IT_WAKES(it.name())
                        : page.PUBLISHED_AS_IT_WOKE_EACH_PORT_IN_THE_SANDBOX_AND_THE_LINK_THAT_OPENS_IT(it.name())
                    ),
                    Val.of(true)
                )
            )
            // What the agent opened is not a setting, and is not greyed with them.
            .add("growx, wmin 0, gaptop 10", heading(pageWords.viewAsString(Lang.HostAccessPage::OPENED_THIS_SESSION), Val.of(true)).isVisibleIf(awake))
            .add("growx, wmin 0", ports(openedRows, pageWords.viewAsString(Lang.HostAccessPage::NOTHING_WAS_OPENED_YET), openedNone).isVisibleIf(awake))
            .add("growx, wmin 0",
                ViewPartsUtil.note(
                    theme,
                    Viewable.of(String.class, pageWords, genieName, Lang.HostAccessPage::WEB_SERVERS_IT_STARTED_SINCE_IT_WOKE_WITH_THE_PROGRAM_SERVING_EACH),
                    awake
                )
            );
    }

    private static String name(HostAccess.Publish publish, Lang.HostAccessPage words) {
        return switch (publish) {
            case OFF -> words.NONE();
            case LISTED -> words.LISTED();
            case ASK -> words.ASK();
            case ANY -> words.ANY();
        };
    }

    /// What choosing a step of which web servers it may open means, for the genie called `genie`.
    private static String meaning(HostAccess.Publish publish, Lang.HostAccessPage words, String genie) {
        return switch (publish) {
            case OFF -> words.YOUR_BROWSER_OPENS_NOTHING_IN_ITS_SANDBOX_WHATEVER_IS_LISTED(genie);
            case LISTED -> words.THE_PORTS_LISTED_BELOW_EACH_TIME_IT_WAKES_AND_NO_OTHERS(genie);
            case ASK -> words.THE_LISTED_PORTS_AND_EACH_WEB_SERVER_IT_STARTS_ONCE_YOU_ALLOW_IT(genie);
            case ANY -> words.THE_LISTED_PORTS_AND_EVERY_WEB_SERVER_IT_STARTS_UNASKED(genie);
        };
    }

    /// The rows of the ports the lamp's file lists. While the genie sleeps, the ones the page
    /// shows, which the user may change; while it is awake, the ones its lamp woke with, each
    /// with its link while the lamp published it.
    private static Tuple<PortRow> listed(Genie genie) {
        HostAccess access = genie.hostAccess();
        if (asleep(genie))
            return access.draft().web().ports().mapTo(PortRow.class, port -> new PortRow(port.name(), port.sandboxPort(), "", Optional.empty(), false));
        return access.saved().web().ports().mapTo(PortRow.class, port ->
            new PortRow(port.name(), port.sandboxPort(), "", access.published(port).map(HostAccess.WebPort::link), true)
        );
    }

    /// One row of a list of ports.
    ///
    /// @param name        what the lamp's file lists it as; empty for a port the agent opened,
    ///                    which its program says better than the name the lamp gave it
    /// @param sandboxPort its port in the sandbox
    /// @param program     what serves a port the agent opened, when the lamp could tell
    /// @param link        where this computer's browser opens it, while it is open
    /// @param lit         whether the genie's lamp is lit: then a listed port without a link is
    ///                    not published, and none can be removed
    private record PortRow(String name, int sandboxPort, String program, Optional<URI> link, boolean lit) {}

    /// A list of ports, in a well, as the texts are: raised while what is in it can be changed,
    /// and sunk into the page otherwise. Says `empty` while there are none.
    private UIForPanel<JPanel> ports(Val<Tuple<PortRow>> rows, Val<String> empty, Val<Boolean> none) {
        Val<Boolean> raised = Viewable.of(Boolean.class, changeable, awake, (can, lit) -> can && !lit);
        return
            panel("fillx, wrap 1, ins 4 0 4 0, gap 0, hidemode 3", "[grow]")
            .withStyle(Themed.of(raised, theme), (themed, it) -> {
                Palette p = themed.palette();
                return it
                    .backgroundColor(themed.item() ? p.RAISED() : p.SMOKE())
                    .border(1, themed.item() ? p.BORDER() : p.SMOKE())
                    .borderRadius(12);
            })
            .add("growx, wmin 0, gapleft 12, gapright 12, gaptop 4, gapbottom 4", ViewPartsUtil.note(theme, empty, none))
            .add("growx, wmin 0",
                panel("fillx, wrap 1, ins 0, gap 0", "[grow]")
                .withMinSize(0, 0)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .addAll("growx, wmin 0", rows, row -> UI.of(UI.use(look, () -> portRow(row).get(JPanel.class))))
            );
    }

    /// A port: its name, its port in the sandbox, and its link while it is open, or that it is
    /// not published while the lamp is lit; the program serving it, when known; and, while the
    /// genie sleeps, the button that removes it from the list.
    private UIForPanel<JPanel> portRow(PortRow row) {
        return
            panel().withLayout(portInOneLine.viewAs(Layout.class, it -> it ? PORT_IN_ONE_LINE : PORT_IN_TWO_LINES))
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            .add(
                box("ins 0, gap 12, hidemode 3")
                .add(
                    label(row.name()).isVisibleIf(!row.name().isEmpty())
                    .withStyle(Themed.of(changeable, theme), (themed, it) -> it.componentFont(f -> f
                        .family(themed.fonts().MONO()).size(12)
                        .color(row.lit() || themed.item() ? themed.palette().TEXT() : ViewPartsUtil.dim(themed.palette().SUBTEXT()))
                    ))
                )
                .add(label(String.valueOf(row.sandboxPort())).withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().MONO()).size(12).color(now.palette().SUBTEXT()))))
            )
            .add(link(row))
            .add(
                button("×").group(Skin.ICON_BUTTON)
                .isVisibleIf(!row.lit())
                .isEnabledIf(changeable)
                .withTooltip(pageWords.viewAsString(Lang.HostAccessPage::REMOVE_IT_FROM_THE_LIST))
                .withStyle(theme, (now, it) -> it.padding(0, 6, 0, 6).componentFont(f -> f.family(now.fonts().FONT()).size(14).color(now.palette().SUBTEXT())))
                .onClick(_ -> web.update(From.VIEW, it -> it.without(new HostAccess.ListedPort(row.name(), row.sandboxPort()))))
            )
            .add(
                label(row.program()).isVisibleIf(!row.program().isEmpty())
                .withTooltip(row.program())
                .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().MONO()).size(11).color(now.palette().SUBTEXT())))
            );
    }

    /// Where the port leads on this computer, which opens it in the browser; or, for a listed
    /// port the lamp did not publish, that it is not. Nothing while the lamp is out.
    private UIForAnySwing<?, ?> link(PortRow row) {
        if (row.link().isPresent()) {
            URI link = row.link().get();
            return
                box("ins 0, gap 8")
                .add(label("→").withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(12).color(now.palette().SUBTEXT()))))
                .add("wmin 0",
                    ViewPartsUtil.link(theme, Val.of(link.toString()))
                    .withTooltip(headerWords.viewAsString(Lang.Header::OPEN_IT_IN_YOUR_BROWSER))
                    .onClick(_ -> app.openInBrowser(link))
                );
        }
        return
            box("ins 0")
            .isVisibleIf(row.lit())
            .add(ViewPartsUtil.tag(theme, pageWords.viewAsString(Lang.HostAccessPage::NOT_PUBLISHED)));
    }

    /// The fields of a port to add to the list, and Add, which adds it once both say something a
    /// port can be: see [HostAccess.ListedPort#typed].
    private UIForBox<JBox> adding() {
        Val<Boolean> typed = Viewable.of(Boolean.class, newName, newPort, (name, port) -> HostAccess.ListedPort.typed(name, port).isPresent());
        Val<Boolean> canAdd = Viewable.of(Boolean.class, changeable, typed, (a, b) -> a && b);
        return
            box().withLayout(
                state.viewAs(Layout.class, it ->
                        it.area().width() >= ADDING_IN_ONE_LINE_FROM
                                ? ADDING_IN_ONE_LINE
                                : ADDING_ONE_UNDER_THE_OTHER
                )
            )
            .withStyle(palette, (p, it) -> it
                .padding(6, 8, 4, 12)
                .borderAt(UI.Edge.TOP, 1, ViewPartsUtil.withAlpha(p.BORDER(), 140))
            )
            .add(label(pageWords.viewAsString(Lang.HostAccessPage::NAME)).group(Skin.META))
            .add(
                textField(newName).group(Skin.INPUT)
                .isEnabledIf(changeable)
                .withTooltip(pageWords.viewAsString(Lang.HostAccessPage::WHAT_IT_IS_FOR_SUCH_AS_SITE))
                .withStyle(it -> it.padding(4, 8, 4, 8))
            )
            .add(label(pageWords.viewAsString(Lang.HostAccessPage::PORT)).group(Skin.META))
            .add(
                textField(newPort).group(Skin.INPUT)
                .isEnabledIf(changeable)
                .withTooltip(pageWords.viewAsString(Lang.HostAccessPage::ITS_PORT_IN_THE_SANDBOX_SUCH_AS_8080))
                .withStyle(it -> it.padding(4, 8, 4, 8))
            )
            .add(
                button(pageWords.viewAsString(Lang.HostAccessPage::ADD)).group(Skin.QUIET_BUTTON)
                .isEnabledIf(canAdd)
                .onClick(_ -> HostAccess.ListedPort.typed(newName.get(), newPort.get()).ifPresent(port -> {
                    web.update(From.VIEW, it -> it.with(port));
                    newName.set(From.VIEW, "");
                    newPort.set(From.VIEW, "");
                }))
            );
    }

    // ─── saving ────────────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> saving() {
        Val<String> mistake = Viewable.of(String.class, draft, Theme.langOf(state), (it, lang) -> it.mistake().map(wrong -> wrong.words(lang)).orElse(""));
        Val<Boolean> changed = hostAccess.viewAs(Boolean.class, HostAccess::changed);
        Val<Boolean> canUndo = Viewable.of(Boolean.class, changeable, changed, (a, b) -> a && b);
        Val<Boolean> canSave = Viewable.of(Boolean.class, canUndo, mistake, (can, wrong) -> can && wrong.isEmpty());
        Val<Boolean> busy = hostAccess.viewAs(Boolean.class, HostAccess::busy);
        Val<Layout> layout = state.viewAs(Layout.class, it -> {
            int width = it.area().width();
            if (width >= SAVING_IN_ONE_ROW_FROM) return SAVING_IN_ONE_ROW;
            if (width >= BUTTONS_SIDE_BY_SIDE_FROM) return SAVING_UNDER_THE_MISTAKE;
            return SAVING_ONE_UNDER_THE_OTHER;
        });
        return
            box().withLayout(layout)
            .add(ViewPartsUtil.wrapped(theme, mistake, Palette::TROUBLE, mistake.viewAs(Boolean.class, it -> !it.isEmpty())))
            .add(
                button(pageWords.viewAsString(Lang.HostAccessPage::UNDO_CHANGES)).group(Skin.QUIET_BUTTON)
                .isEnabledIf(canUndo)
                .onClick(_ -> hostAccess.update(From.VIEW, access -> access.withDraft(access.saved())))
            )
            .add(
                button(Viewable.of(String.class, busy, pageWords, (it, page) -> it ? page.SAVING() : page.SAVE())).group(Skin.FLAME_BUTTON)
                .isEnabledIf(canSave)
                .withTooltip(pageWords.viewAsString(Lang.HostAccessPage::WRITE_THESE_SETTINGS_INTO_THE_GENIES_LAMP))
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

    /// A section's heading, dimmed while nothing under it can be changed, or while what is under
    /// it is not `used`. Wrapped, rather than cut short, on a narrow page.
    private UIForAnySwing<?, ?> section(Val<String> heading, Val<Boolean> used) {
        return heading(heading, Viewable.of(Boolean.class, changeable, used, (a, b) -> a && b));
    }

    /// A section's heading, dimmed unless `lit`.
    private UIForBox<JBox> heading(Val<String> heading, Val<Boolean> lit) {
        Val<Heading> shown = Viewable.of(Heading.class, heading, lit, Heading::new);
        return
            box().withMinSize(0, 0)
            .withStyle(Themed.of(shown, theme), (themed, it) -> {
                Heading now = themed.item();
                Palette p = themed.palette();
                Color colour = now.lit() ? p.SUBTEXT() : ViewPartsUtil.dim(p.SUBTEXT());
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

    /// A section's heading, and whether it is lit.
    private record Heading(String text, boolean lit) {}

    /// How a text looks: whether it can be typed in, and whether what it says is used.
    private record Field(boolean changeable, boolean used) {}

    /// One of the texts: a heading, the text, `height` tall, and a note under it. While the rules
    /// cannot be changed, the text sinks into the page, greyed, and cannot be typed in; while what
    /// it says is not `used`, it sinks in too and its words are dimmed, but it can be typed in.
    private UIForAnySwing<?, ?> text(Val<String> heading, Var<String> text, Val<String> note, Val<Boolean> used, int height) {
        Val<Field> looks = Viewable.of(Field.class, changeable, used, Field::new);
        return
            box("fillx, wrap 1, ins 0, gap 8", "[grow]")
            .add("growx, wmin 0", section(heading, used))
            .add("growx, wmin 0, h " + height + "!",
                scrollPane().withEmptyBorder(0)
                .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                .withStyle(Themed.of(looks, theme), (themed, it) -> {
                    Field field = themed.item();
                    boolean raised = field.changeable() && field.used();
                    Palette p = themed.palette();
                    return it
                        .backgroundColor(raised ? p.RAISED() : p.SMOKE())
                        .border(1, field.changeable() ? p.BORDER() : p.SMOKE())
                        .borderRadius(12);
                })
                .add(
                    textArea(text).group(Skin.INPUT).peek(ViewPartsUtil::softWrap)
                    .isEnabledIf(changeable)
                    .withStyle(Themed.of(looks, theme), (themed, it) -> {
                        Field field = themed.item();
                        Palette p = themed.palette();
                        Color words = !field.changeable() ? ViewPartsUtil.dim(p.SUBTEXT()) : field.used() ? p.TEXT() : p.SUBTEXT();
                        return it
                            .backgroundColor(p.TRANSPARENT())
                            .border(0, p.TRANSPARENT())
                            .padding(8, 12, 8, 12)
                            .cursor(field.changeable() ? UI.Cursor.TEXT : UI.Cursor.DEFAULT)
                            .componentFont(f -> f.family(themed.fonts().FONT()).size(14).color(words));
                    })
                )
            )
            .add("growx, wmin 0", ViewPartsUtil.note(theme, note, Val.of(true)));
    }
}
