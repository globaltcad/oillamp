package dev.gui.view;

import java.util.UUID;

import dev.gui.Genies;
import dev.gui.model.Fonts;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.HostAccess;

import sprouts.From;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.layout.FlowCell;

import static dev.gui.model.Palette.DARK;
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
    private final Genies app;

    // Lenses and views, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Var<HostAccess> hostAccess;
    private final Var<HostAccess.PermissionRules> draft;
    private final Var<String> mayRead;
    private final Var<String> mayChange;
    private final Var<String> words;
    /// Whether the rules may be changed now: the genie sleeps, has a lamp, and nothing is on its way.
    private final Val<Boolean> changeable;

    HostAccessPage(Var<GeniesState> state, Genies app) {
        this.state = state;
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
                .withStyle(it -> it.backgroundColor(DARK.TRANSPARENT()).padding(20, 4, 28, 4))
                .add(WHOLE, top())
                .add(SWITCH, onOrOff())
                .add(BESIDE_THE_SWITCH, experimental())
                .add(FOLDERS, text("FOLDERS IT MAY READ", mayRead, "One per line, such as ~/Documents."))
                .add(FOLDERS,
                    text("FOLDERS IT MAY READ AND CHANGE", mayChange, "It may write files there, and copy what it made there.")
                )
                .add(OWN_WORDS,
                    text(
                        "ANYTHING ELSE, IN YOUR OWN WORDS",
                        words,
                        "Say what must not happen, such as \"never open my tax returns\"."
                    )
                )
                .add(WHOLE, saving())
            );
    }

    private static boolean asleep(Genie genie) {
        return genie.phase() == Genie.Phase.ASLEEP || genie.phase() == Genie.Phase.BROKEN;
    }

    // ─── on top: what this is, and why it cannot be changed now ────────────────────────────

    /// Why the rules cannot be changed now, in a sentence; empty when they can.
    private static String locked(Genie genie) {
        HostAccess access = genie.hostAccess();
        if (!access.read()) return "Reading the host access…";
        if (!access.lamp())
            return "Wake " + genie.name() + " once first. Its settings are kept in its lamp, which it gets as it first wakes.";
        if (!asleep(genie))
            return "Send " + genie.name() + " to sleep to change these. While it is awake, it keeps the settings it woke with.";
        return "";
    }

    private UIForAnySwing<?, ?> top() {
        Val<String> lockedBecause = genie.viewAsString(HostAccessPage::locked);
        Val<String> says = genie.viewAsString(it ->
            it.hostAccess().saved().on()
                ? it.name() + " may ask to reach this computer. A model judges each request against these rules before anything happens."
                : it.name() + " cannot reach this computer."
        );
        Val<String> problem = hostAccess.viewAsString(HostAccess::problem);
        return
            box("fill, wrap 1, ins 0, gap 8, hidemode 3", "[grow]")
            .add(label("Host access").group(Skin.EMPTY_TITLE))
            .add("growx, wmin 0", ViewPartsUtil.wrapped(says, DARK.SUBTEXT(), Val.of(true)))
            // As loud as the banner of a request for permission: the user should not have to
            // find out by typing that nothing they type is kept.
            .add("growx, wmin 0",
                panel("fill, ins 10 14 10 14, gap 10", "[][grow]")
                .isVisibleIf(lockedBecause.viewAs(Boolean.class, it -> !it.isEmpty()))
                .withStyle(it -> it.backgroundColor(DARK.YOURS()).borderRadius(12).borderAt(UI.Edge.LEFT, 3, DARK.BRASS()))
                .add("top, gaptop 2", label("").withStyle(it -> it.icon(SignSvgUtil.sign(SignSvgUtil.SLEEP, DARK.BRASS()))))
                .add("growx, wmin 0", ViewPartsUtil.wrapped(lockedBecause, DARK.TEXT(), Val.of(true)))
            )
            .add("growx, wmin 0", ViewPartsUtil.wrapped(problem, DARK.TROUBLE(), problem.viewAs(Boolean.class, it -> !it.isEmpty())));
    }

    // ─── the switch, and what it switches ──────────────────────────────────────────────────

    private UIForAnySwing<?, ?> onOrOff() {
        Val<Boolean> on = draft.viewAs(Boolean.class, HostAccess.PermissionRules::on);
        return
            box("fillx, wrap 1, ins 0, gap 8", "[grow]")
            .add("growx, wmin 0", section("MAY IT REACH THIS COMPUTER?"))
            .add(ViewPartsUtil.yesOrNo(on, changeable, yes -> draft.update(From.VIEW, it -> it.withOn(yes))));
    }

    private UIForAnySwing<?, ?> experimental() {
        return
            panel("fill, ins 10 14 10 14, gap 10", "[][grow]")
            .withStyle(it -> it.backgroundColor(DARK.TROUBLE_WASH()).borderRadius(12))
            .add("top", label("⚠").withStyle(it -> it.componentFont(f -> f.family(Fonts.DEFAULT.FONT()).size(14).color(DARK.TROUBLE()))))
            .add("growx, wmin 0",
                ViewPartsUtil.wrapped(
                    Val.of(
                        "Experimental. Never turn this on on a computer with sensitive data. "
                        + "The model that judges reads these rules with every request, and nothing else."
                    ),
                    DARK.TEXT(),
                    Val.of(true)
                )
            );
    }

    // ─── saving ────────────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> saving() {
        Val<String> warning = draft.viewAsString(it -> it.warning().orElse(""));
        Val<Boolean> changed = hostAccess.viewAs(Boolean.class, HostAccess::changed);
        Val<Boolean> canSave = Viewable.of(Boolean.class, changeable, changed, (a, b) -> a && b);
        Val<Boolean> busy = hostAccess.viewAs(Boolean.class, HostAccess::busy);
        return
            box("fill, ins 0, gap 12, hidemode 3", "[grow][][]")
            .add("growx, wmin 0", ViewPartsUtil.wrapped(warning, DARK.BRASS(), warning.viewAs(Boolean.class, it -> !it.isEmpty())))
            .add("skip 0",
                button("Undo changes").group(Skin.QUIET_BUTTON)
                .isEnabledIf(canSave)
                .onClick(_ -> hostAccess.update(From.VIEW, access -> access.withDraft(access.saved())))
            )
            .add(
                button(busy.viewAsString(it -> it ? "Saving…" : "Save")).group(Skin.FLAME_BUTTON)
                .isEnabledIf(canSave)
                .withTooltip("Write these rules into the genie's lamp")
                .withStyle(canSave, (can, it) ->
                    can
                        ? it
                        : it.backgroundColor(DARK.RAISED())
                          .foregroundColor(DARK.SUBTEXT())
                          .componentFont(f -> f.color(DARK.SUBTEXT()))
                          .cursor(UI.Cursor.DEFAULT)
                )
                .onClick(_ -> app.saveHostAccess(selected()))
            );
    }

    // ─── small parts ───────────────────────────────────────────────────────────────────────

    private UUID selected() { return state.get().selected(); }

    /// A section's heading, dimmed while nothing under it can be changed.
    private UIForAnySwing<?, ?> section(String words) {
        return
            label(words).group(Skin.SECTION)
            .withStyle(changeable, (can, it) -> can ? it : it.componentFont(f -> f.color(ViewPartsUtil.dim(DARK.SUBTEXT()))));
    }

    /// One of the three texts: a heading, the text, as tall as the other two so they line up
    /// side by side, and a note under it. While the rules cannot be changed, the text sinks into
    /// the page, greyed, and cannot be typed in.
    private UIForAnySwing<?, ?> text(String heading, Var<String> text, String note) {
        return
            box("fillx, wrap 1, ins 0, gap 8", "[grow]")
            .add("growx, wmin 0", section(heading))
            .add("growx, wmin 0, h " + TEXT_HEIGHT + "!",
                scrollPane().withEmptyBorder(0)
                .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                .withStyle(changeable, (can, it) -> it
                    .backgroundColor(can ? DARK.RAISED() : DARK.SMOKE())
                    .border(1, can ? DARK.BORDER() : DARK.SMOKE())
                    .borderRadius(12)
                )
                .add(
                    textArea(text).group(Skin.INPUT).peek(ViewPartsUtil::softWrap)
                    .isEnabledIf(changeable)
                    .withStyle(changeable, (can, it) -> it
                        .backgroundColor(DARK.TRANSPARENT())
                        .border(0, DARK.TRANSPARENT())
                        .padding(10, 12, 10, 12)
                        .cursor(can ? UI.Cursor.TEXT : UI.Cursor.DEFAULT)
                        .componentFont(f -> f.family(Fonts.DEFAULT.FONT()).size(14).color(can ? DARK.TEXT() : ViewPartsUtil.dim(DARK.SUBTEXT())))
                    )
                )
            )
            .add("growx, wmin 0", ViewPartsUtil.note(note, Val.of(true)));
    }
}
