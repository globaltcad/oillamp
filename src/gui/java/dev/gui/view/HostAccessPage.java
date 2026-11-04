package dev.gui.view;

import java.util.UUID;
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
import swingtree.api.Layout;
import swingtree.layout.FlowCell;
import swingtree.layout.LayoutConstraint;
import swingtree.layout.MigAddConstraint;

import static swingtree.UI.*;

/// A genie's host access: whether its agent may reach this computer, and the permission rules the
/// checker model judges each of its requests against. Genies writes them into the genie's lamp as
/// JSON, which the checker reads like any words; see [HostAccess].
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
     *      saving                 12       12     12     12       12            12
     *
     *  The width decides THREE_FROM, not the size class: the page is laid out at its own width,
     *  so the size class never goes past what fits two texts.
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
        return AUTO_SPAN(it -> {
            int span = it.parentSize().widthOrElse(0) >= UI.scale(THREE_FROM) ? wide : large;
            return
                it.align(align)
                .verySmall(narrow).small(narrow).medium(narrow)
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
    /// The words of the page, unlike [#words], which are the user's own.
    private final Val<Lang.HostAccessPage> pageWords;
    /// Whether the rules may be changed now: the genie sleeps, has a lamp, and nothing is on its way.
    private final Val<Boolean> changeable;

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
        changeable = genie.viewAs(Boolean.class, it -> asleep(it) && it.hostAccess().lamp() && !it.hostAccess().busy());
    }

    UIForAnySwing<?, ?> view(Val<Boolean> visible) {
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE_SCROLL).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            .onResize(it -> state.update(From.VIEW, s -> s.withArea(it.getWidth(), it.getHeight())))
            .add(
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 18, 18)
                .withMinSize(0, 0).withPrefSize(REFERENCE, 0)
                .withStyle(palette, (p, it) -> it
                    .backgroundColor(p.TRANSPARENT())
                    .padding(10, 4, 28, 4)
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
            .add(label(pageWords.viewAsString(Lang.HostAccessPage::HOST_ACCESS)).group(Skin.EMPTY_TITLE))
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

    // ─── saving ────────────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> saving() {
        Val<String> warning = Viewable.of(String.class, draft, Theme.langOf(state), (it, lang) -> it.warning(lang).orElse(""));
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

    /// A section's heading, dimmed while nothing under it can be changed.
    private UIForAnySwing<?, ?> section(Function<Lang.HostAccessPage, String> heading) {
        return
            label(pageWords.viewAsString(heading)).group(Skin.SECTION)
            .withStyle(Themed.of(changeable, theme), (themed, it) -> {
                var can = themed.item();
                Palette p = themed.palette();
                return can ? it : it.componentFont(f -> f.color(ViewPartsUtil.dim(p.SUBTEXT())));
            });
    }

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
