package dev.gui.view;

import dev.gui.Genies;
import dev.gui.model.*;
import sprouts.*;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForBox;
import swingtree.UIForButton;
import swingtree.UIForLabel;
import swingtree.UIForPanel;
import swingtree.animation.Animation;
import swingtree.animation.AnimationStatus;
import swingtree.animation.LifeTime;
import swingtree.components.JBox;
import swingtree.api.Layout;
import swingtree.dialogs.ConfirmAnswer;
import swingtree.input.Keyboard;
import swingtree.layout.LayoutConstraint;
import swingtree.layout.MigAddConstraint;
import swingtree.layout.Size;
import swingtree.style.SvgIcon;

import javax.swing.*;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntSupplier;

import static swingtree.UI.*;

/// The Genies window: the genies on the left, the conversation with the selected one in the
/// middle, and its desktop next to it when the user wants to watch. In a narrow window, as on a
/// phone, everything is one column: the genies on top, the selected one below.
///
/// Everything shown is a function of one [GeniesState], reached through lenses. Typing, picking
/// and toggling change that state directly; anything that starts, stops or moves something goes
/// through [Genies]. The view keeps no state of its own, except the connection of the
/// [DesktopScreen].
public final class GeniesView extends JPanel {

    /// How long one wave of the flame's light is, in units, as it runs through the status of a
    /// genie that wakes. It runs its length in one loop of the pulse.
    private static final int WAVE_LENGTH = 120;

    private final Var<GeniesState> state;
    private final Genies app;
    /// The colours and the fonts of the state, and the colours alone.
    private final Val<Theme> theme;
    private final Val<Palette> palette;
    /// The words of the window around the genies.
    private final Val<Lang.Window> windowWords;
    /// The words of the header above a genie's pages.
    private final Val<Lang.Header> headerWords;
    private final Look look;
    private final DesktopScreen desktop;

    // Lenses and views, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Var<String> draft;
    private final Var<String> name;
    private final Var<Boolean> desktopShown;
    private final Var<GeniesState.Page> page;
    private final Var<Fold> genieList;
    private final Var<Boolean> settingsShown;
    private final Var<Tuple<Genie>> genies;
    private final Var<Tuple<Entry>> entries;
    private final Val<Tuple<Handout>> handouts;
    private final Val<Genie.Phase> phase;
    private final Val<UUID> selected;
    private final Val<UUID> watched;
    private final Var<DesktopZoom> zoom;
    /// Loops from 0 to 1 while any genie works or wakes: it moves the thinking bars, the genies
    /// that think or work, and the wave through the status of a genie that wakes.
    private final Var<Double> pulse = Var.of(0.0);
    /// Whether the pulse loops, so that only one loop ever sets it.
    private boolean breathing = false;
    private final ChatRows rows;
    private final SchedulePage schedulePage;
    private final HistoryPage historyPage;
    private final HostAccessPage hostAccessPage;
    private final ActionsPage actionsPage;
    private final WakePicture wakePicture;
    /// Whether the draft is setting the composer's text, which then is not written back into it.
    private boolean settingDraft = false;
    /// Set once the conversation's scroll pane exists.
    private Optional<FollowTheEnd> follow = Optional.empty();

    public GeniesView(Genies app) {
        this.state = app.state();
        this.app = app;
        theme   = Theme.of(state);
        palette = Theme.paletteOf(state);
        windowWords = Theme.langOf(state).viewAs(Lang.Window.class, Lang::WINDOW);
        headerWords = Theme.langOf(state).viewAs(Lang.Header.class, Lang::HEADER);
        look    = new Look(theme);
        desktop = new DesktopScreen(palette);
        // What the style sheet does not paint follows the theme too: the look-and-feel is set up
        // in it anew, and every window takes it.
        Viewable.cast(theme).onChange(From.ALL, it -> it.currentValue().ifPresent(now -> {
            lookAndFeelIn(now.palette(), now.fonts());
            com.formdev.flatlaf.FlatLaf.updateUI();
        }));
        genie        = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        draft        = genie.zoomTo(Genie::draft, Genie::withDraft);
        name         = genie.zoomTo(Genie::name, Genie::withName);
        desktopShown = genie.zoomTo(Genie::desktopShown, Genie::withDesktopShown);
        page         = state.zoomTo(GeniesState::page, GeniesState::withPage);
        genieList    = state.zoomTo(GeniesState::genieList, GeniesState::withGenieList);
        // Leaving the settings goes back to the chat, as their Done does.
        settingsShown = page.zoomTo(
            it -> it == GeniesState.Page.SETTINGS,
            (it, shown) ->
                shown
                    ? GeniesState.Page.SETTINGS
                    : it == GeniesState.Page.SETTINGS ? GeniesState.Page.CHAT : it
        );
        // Genies and entries keep their id while they change, so their rows are bound through a
        // lens onto each one: a row built from a plain value would never be redrawn.
        genies       = state.zoomTo(GeniesState::genies, GeniesState::withGenies);
        entries      = genie.zoomTo(it -> it.transcript().entries(), (it, changed) -> it.withTranscript(new Transcript(changed)));
        handouts     = genie.viewAs(Tuple.classTyped(Handout.class), Genie::handouts);
        phase        = genie.viewAs(Genie.Phase.class, Genie::phase);
        selected     = state.viewAs(UUID.class, GeniesState::selected);
        // The genie whose desktop is on screen: the selected one, while it is awake and asked
        // for; nobody otherwise.
        watched      = genie.viewAs(UUID.class, it -> it.phase().isAwake() && it.desktopShown() ? it.id() : GeniesState.NONE);
        Viewable.cast(watched).onChange(From.ALL, it -> {
            UUID id = it.currentValue().orElse(GeniesState.NONE);
            desktop.show(id.equals(GeniesState.NONE) ? Optional.empty() : app.desktopOf(id));
        });
        zoom = state.zoomTo(GeniesState::zoom, GeniesState::withZoom);
        Viewable.cast(zoom).onChange(From.ALL, it -> desktop.zoom(it.currentValue().orElse(DesktopZoom.FIXED).scale()));
        desktop.zoom(zoom.get().scale());
        desktop.onZoomSteps(steps -> zoom.update(From.VIEW, it -> steps > 0 ? it.in(desktop.fitScale()) : it.out(desktop.fitScale())));
        Viewable.cast(genies).onChange(From.ALL, _ -> {
            if (anyWorksOrWakes()) breathe();
        });
        rows = new ChatRows(
            look,
            theme,
            genie,
            this::saveHandout,
            pulse,
            this::askInstead,
            phase.viewAs(Boolean.class, it -> it == Genie.Phase.READY),
            this::showLeaf
        );
        schedulePage = new SchedulePage(state, app, look);
        historyPage = new HistoryPage(state, app, look);
        hostAccessPage = new HostAccessPage(state, app);
        actionsPage = new ActionsPage(state, app, () -> historyPage.save(this), this::confirmDelete);
        wakePicture = new WakePicture(genie, palette);

        UI.use(look, () ->
            of(this).group(Skin.FRAME)
            .withLayout(state.viewAs(Layout.class, it -> it.narrow() ? ONE_COLUMN : SIDE_BY_SIDE))
            .withPrefSize(1280, 820)
            .withMinSize(0, 0)
            .onResize(it -> state.update(From.VIEW, s -> s.withViewWidth(it.getWidth())))
            .add(sidebar())
            .add(mainArea())
        );
    }

    /// The genies beside the selected one, or, in a narrow window, above it. The genie keeps
    /// some height whatever the list's, so the list cannot push it out of the window.
    private static final Layout SIDE_BY_SIDE = Layout.mig(
        "fill, ins 0, gap 0, hidemode 3",
        MigAddConstraint.of("growy, width 250!"),
        MigAddConstraint.of("grow, push, wmin 0")
    );
    private static final Layout ONE_COLUMN = Layout.mig(
        "fill, wrap 1, ins 0, gap 0, hidemode 3",
        MigAddConstraint.of("growx, wmin 0"),
        MigAddConstraint.of("grow, push, wmin 0, hmin 160")
    );

    // ─── the sidebar: every genie ──────────────────────────────────────────────────────────

    /// Lets go of the desktop on show, which gets its own size back if it had the panel's. The
    /// thread doing that, for an application that is about to end and must wait for it.
    public Optional<Thread> letGoOfTheDesktop() {
        return desktop.letGo();
    }

    /*
     *  The list of genies has two arrangements of the same parts, chosen by the window's width:
     *
     *      beside the genie                    above it, in a narrow window
     *
     *      ┌──────────────────┐                ┌──────────────────────────────────────┐
     *      │ [+ New genie]    │                │ [+ New genie                 ]  [⚙]  │
     *      │ YOUR GENIES      │                │ ┌──────────────────────────────────┐ │
     *      │ ┌──────────────┐ │                │ │ the genies' cards, scrolling,    │ │
     *      │ │ the genies'  │ │                │ │ as tall as the user dragged      │ │
     *      │ │ cards        │ │                │ └──────────────────────────────────┘ │
     *      │ │              │ │                │ ────────────── grip ──────────────── │
     *      │ └──────────────┘ │                │ what went wrong, if anything         │
     *      │ what went wrong  │                └──────────────────────────────────────┘
     *      │ [⚙ Settings]     │
     *      └──────────────────┘
     *
     *  Each part is placed in a cell of the grid, so the parts are made once, and moving between
     *  the two is a change of the layout's value. The height above is the list's fold: dragging
     *  the grip to the top folds the list away, as does ☰.
     *
     *  ☰ shows and hides the list. It is in the window's title bar, above both, with the lamp and
     *  "Genies" ([#titleBar]). So it stays under the pointer, and a second press undoes the first.
     */
    private static final Layout BESIDE_THE_GENIE = Layout.mig(
        "fill, ins 0, gap 10, hidemode 3",
        MigAddConstraint.of("cell 0 0, growx"),                      // a new genie
        MigAddConstraint.of("cell 0 5, growx"),                      // the settings
        MigAddConstraint.of("cell 0 1, growx, gaptop 6"),            // "YOUR GENIES"
        MigAddConstraint.of("cell 0 2, grow, push, wmin 0, hmin 0"), // the cards
        MigAddConstraint.of("cell 0 3"),                             // the grip, hidden
        MigAddConstraint.of("cell 0 4, growx, wmin 0")               // what went wrong
    );

    /// The same parts, in the same order, as [#BESIDE_THE_GENIE].
    ///
    /// @param height the most the cards' area takes: the list's fold
    private static Layout aboveTheGenie(int height) {
        return
            Layout.mig(
                LayoutConstraint.of("fill, ins 0, gap 8 6, hidemode 3"),
                LayoutConstraint.of("[grow][]"),
                LayoutConstraint.of("")
            )
            .withChildConstraints(
                MigAddConstraint.of("cell 0 0, growx, wmin 0"),
                MigAddConstraint.of("cell 1 0"),
                MigAddConstraint.of("cell 0 4"),
                MigAddConstraint.of("cell 0 1, span 2, growx, wmin 0, h 0:pref:" + height),
                MigAddConstraint.of("cell 0 2, span 2, growx, h 9!"),
                MigAddConstraint.of("cell 0 3, span 2, growx, wmin 0")
            );
    }

    private UIForAnySwing<?, ?> sidebar() {
        Val<Boolean> narrow = state.viewAs(Boolean.class, GeniesState::narrow);
        Val<Boolean> wide = narrow.viewAs(Boolean.class, it -> !it);
        JScrollPane[] cards = new JScrollPane[1];
        return
            panel().group(Skin.SIDEBAR)
            .withLayout(state.viewAs(Layout.class, it -> it.narrow() ? aboveTheGenie(it.genieList().height()) : BESIDE_THE_GENIE))
            .withStyle(Themed.of(narrow, theme), (themed, it) -> {
                var on = themed.item();
                Palette p = themed.palette();
                return on
                    ? it.borderAt(UI.Edge.RIGHT, 0, p.TRANSPARENT()).borderAt(UI.Edge.BOTTOM, 1, p.BORDER()).padding(12, 12, 2, 12)
                    : it;
            })
            // Without genies, the window is the settings alone, which welcome the user.
            .isVisibleIf(state.viewAs(Boolean.class, it -> it.genieList().shown() && it.hasGenies()))
            .add(
                button(windowWords.viewAsString(Lang.Window::NEW_GENIE)).group(Skin.FLAME_BUTTON).withIconTextGap(6)
                .withStyle(palette, (p, it) -> it.icon(SignSvgUtil.sign(SignSvgUtil.NEW, p.ON_FLAME())))
                .withTooltip(windowWords.viewAsString(Lang.Window::A_NEW_GENIE_WITH_A_SANDBOXED_DESKTOP_OF_ITS_OWN))
                .onClick(_ -> app.newGenie())
            )
            .add(
                toggleButton(Viewable.of(String.class, narrow, windowWords, (it, words) -> it ? "" : words.SETTINGS()), settingsShown)
                .group(Skin.QUIET_BUTTON).withIconTextGap(6)
                .withStyle(palette, (p, it) -> it.icon(SignSvgUtil.sign(SignSvgUtil.SETTINGS, p.TEXT())))
                .withTooltip(windowWords.viewAsString(Lang.Window::SETTINGS_THE_MODEL_EVERY_GENIE_USES))
            )
            .add(label(windowWords.viewAsString(Lang.Window::YOUR_GENIES)).group(Skin.SECTION).isVisibleIf(wide))
            .add(
                // As tall as the cards, so that above the genie a few of them take only their room.
                scrollPanels(conf -> conf
                    .fitWidth(true).unitIncrement(16).prefSize(Size.of(conf.view().getPreferredSize()))
                )
                .group(Skin.PAGE_SCROLL).withMinSize(0, 0).withEmptyBorder(0)
                .peek(it -> cards[0] = it)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .addAll(genies, this::genieChip)
            )
            .add(
                grip(
                    genieList,
                    () -> UI.unscale(cards[0].getHeight()),
                    narrow,
                    windowWords.viewAsString(Lang.Window::DRAG_TO_SEE_MORE_OF_YOUR_GENIES)
                )
            )
            .add(
                box("fill, wrap 1, ins 0, gap 6, hidemode 3")
                .add("left",
                    ViewPartsUtil.link(theme, windowWords.viewAsString(Lang.Window::ADD_A_LAMP_FROM_ELSEWHERE))
                    .withTooltip(windowWords.viewAsString(Lang.Window::SHOW_A_LAMP_YOU_MADE_WITH_OILLAMP_AT_HERE_TOO))
                    .onClick(_ -> addLamp())
                )
                .add("growx",
                    button(state.viewAsString(it -> "⚠  " + it.lang().WINDOW().THINGS_WENT_WRONG().of(it.troubles().size()))).group(Skin.QUIET_BUTTON)
                    .withStyle(palette, (p, it) -> it
                        .backgroundColor(p.TROUBLE_WASH())
                        .border(1, p.TROUBLE())
                        .componentFont(f -> f.color(p.TROUBLE()))
                    )
                    .isVisibleIf(state.viewAs(Boolean.class, it -> !it.troubles().isEmpty()))
                    .withTooltip(windowWords.viewAsString(Lang.Window::GENIES_CARRIED_ON_CLICK_TO_SEE_WHAT_HAPPENED))
                    .onClick(_ -> showTroubles())
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(
                        theme,
                        state.viewAsString(it -> it.settingsProblem().orElse("")),
                        Palette::TROUBLE,
                        state.viewAs(Boolean.class, it -> it.settingsProblem().isPresent())
                    )
                )
            );
    }

    /// Built later than the constructor, so it enters the style sheet again.
    ///
    /// The lamps from elsewhere follow Genies' own genies, under a heading of their own, which
    /// stands above the first of them.
    private UIForAnySwing<?, ?> genieChip(Var<Genie> shown) {
        UUID id = shown.get().id();
        Val<Boolean> firstFromElsewhere = state.viewAs(Boolean.class, it ->
            it.genies().stream()
            .filter(Genie::fromElsewhere)
            .findFirst()
            .map(first -> first.id().equals(id))
            .orElse(false)
        );
        return UI.of(UI.use(look, () ->
            panel("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]")
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            .add("growx, wmin 0, gaptop 14, gapbottom 4",
                label(windowWords.viewAsString(Lang.Window::LAMPS_FROM_ELSEWHERE)).group(Skin.SECTION)
                .isVisibleIf(firstFromElsewhere)
                .withTooltip(windowWords.viewAsString(Lang.Window::LAMPS_SOMEONE_LIT_WITH_OILLAMP_AT_OUTSIDE_GENIES))
            )
            .add("growx, wmin 0", genieChipBody(shown))
            .get(JPanel.class)
        ));
    }

    /// Where a lamp from elsewhere is, with `~` for the user's home, as a person types it.
    private static String whereFrom(Genie genie) {
        String home = System.getProperty("user.home");
        return
            genie.elsewhere()
            .map(Path::toString)
            .map(it -> it.startsWith(home + "/") ? "~" + it.substring(home.length()) : it)
            .orElse("");
    }

    /// [#whereFrom], short enough for a card: a long path keeps its end, such as `…/lamps/west`,
    /// which tells lamps apart, where its start would say the same for each.
    private static String whereFromInShort(Genie genie) {
        String where = whereFrom(genie);
        if (where.length() <= 28) return where;
        Path path = Path.of(where);
        int parts = path.getNameCount();
        return parts < 2 ? where : "…/" + path.subpath(parts - 2, parts);
    }

    private UIForPanel<JPanel> genieChipBody(Var<Genie> shown) {
        UUID id = shown.get().id();
        // A lamp from elsewhere is marked in brass, the colour of the lamp, rather than the flame.
        Function<Palette, Color> accent = shown.get().fromElsewhere() ? Palette::BRASS : Palette::FLAME;
        Val<Boolean> isSelected = selected.viewAs(Boolean.class, it -> it.equals(id));
        // What the genie shows on its desktop, said here until the user is at its chat to see it.
        Val<Boolean> atItsChat = state.viewAs(Boolean.class, it -> it.selected().equals(id) && it.page() == GeniesState.Page.CHAT);
        Val<Boolean> showsElsewhere = Viewable.of(Boolean.class, shown, atItsChat, (it, there) -> !it.showing().isEmpty() && !there);
        // Awake, the genie is out of its lamp, in the card's top right corner beside its name,
        // twice its twenty pixels. Asleep, waking or broken, it is in the lamp, so not shown, and
        // that corner has an arrow that folds the genie's conversations away, or shows them again.
        Val<Boolean> awakeAndOut = shown.viewAs(Boolean.class, it -> it.phase().isAwake());
        Var<Boolean> folded =
            shown.zoomTo(Genie::conversations, Genie::withConversations)
            .zoomTo(Conversations::folded, Conversations::withFolded);
        Var<Boolean> hovered = Var.of(false);
        Val<GenieSvgUtil.Picture> picture = Viewable.of(GenieSvgUtil.Picture.class, shown, pulse, (it, progress) -> {
            if (!it.phase().isAwake()) return GenieSvgUtil.Picture.NOBODY;
            GenieSvgUtil.Pose pose = GenieSvgUtil.poseOf(it);
            return GenieSvgUtil.Picture.of(GenieSvgUtil.appearanceOf(it.id()), pose, GenieSvgUtil.frameAt(pose, progress));
        });
        return
            panel("fill, ins 7 8 7 8, gap 8, hidemode 3", "[grow][]")
            .withStyle(Themed.of(isSelected, theme), (themed, it) -> {
                var on = themed.item();
                Palette p = themed.palette();
                return it
                    .backgroundColor(on ? p.RAISED() : p.TRANSPARENT())
                    .border(1, on ? p.BORDER() : p.TRANSPARENT())
                    // Always there, lit when selected, so selecting a genie moves nothing.
                    .borderAt(UI.Edge.LEFT, 3, on ? accent.apply(p) : p.TRANSPARENT())
                    .borderRadius(10)
                    // A thin gap between one card's border and the next.
                    .margin(2, 0, 2, 0);
            })
            // Under the pointer, a card that is not selected yet lights halfway to selected. A
            // transitional style follows no item: it reads the palette as it is gathered, which it
            // is anew whenever the palette changes, as every style the style sheet reaches.
            .withTransitionalStyle(hovered, LifeTime.of(0.14, TimeUnit.SECONDS), (status, it) ->
                isSelected.get()
                    ? it
                    : it.backgroundColor(ViewPartsUtil.withAlpha(palette.get().RAISED(), (int) Math.round(150 * status.progress())))
                      .border(1, ViewPartsUtil.withAlpha(palette.get().BORDER(), (int) Math.round(255 * status.progress())))
            )
            .onMouseEnter(_ -> hovered.set(true))
            .onMouseExit(_ -> hovered.set(false))
            // Painted on the card rather than added to it, so it moves none of the card's parts.
            // Outlined for the sidebar, which a card's own ground hardly differs from.
            .withStyle(Themed.of(picture, theme), (themed, it) -> it
                .image(img -> img
                    .image(themed.item().on(themed.palette().SIDEBAR()))
                    .placement(UI.Placement.TOP_RIGHT)
                    .size(46, 46)
                    .padding(5, 6, 1, 0)
                )
            )
            .withCursor(UI.Cursor.HAND)
            .withTooltip(
                Viewable.of(String.class, shown, windowWords, (it, words) ->
                    words.GENIE_STATUS_AND_WHAT_IT_SHOWS_RIGHT_CLICK_FOR_MORE().of(it.name(), it.status(), it.showing())
                )
            )
            .onMousePress(it -> {
                state.update(From.VIEW, s -> s.select(id));
                if ( it.isRightMouseButton() )
                    genieMenu(id).show(it.getComponent(), it.getEvent().getX(), it.getEvent().getY());
            })
            .add("growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 0, hidemode 3")
                // Room for the genie, while it is out, so a long name ends before it.
                .withStyle(awakeAndOut, (room, it) -> it
                    .padding(0, room ? 42 : 0, 0, 0)
                )
                .add("growx, wmin 0",
                    label(shown.viewAsString(Genie::name))
                    .withStyle(theme, (now, it) -> it
                        .componentFont(f -> f
                            .family(now.fonts().FONT())
                            .size(13)
                            .weight(2f)
                            .color(now.palette().TEXT())
                        )
                    )
                )
                .add("growx, wmin 0",
                    waving(
                        label(shown.viewAsString(Genie::status)).group(Skin.META),
                        shown
                    )
                )
                .add("growx, wmin 0",
                    label(shown.viewAsString(GeniesView::whereFromInShort))
                    .isVisibleIf(shown.viewAs(Boolean.class, Genie::fromElsewhere))
                    .withTooltip(shown.viewAsString(it -> it.elsewhere().map(Path::toString).orElse("")))
                    .withStyle(theme, (now, it) -> it
                        .componentFont(f -> f
                            .family(now.fonts().MONO())
                            .size(11)
                            .color(now.palette().BRASS())
                        )
                    )
                )
                .add("growx, wmin 0",
                    html(
                        Viewable.of(String.class, shown, state, (it, s) ->
                            "⌂  " + (
                                !it.hostAccess().waitingAt(s.now()).isEmpty()
                                    ? s.lang().WINDOW().ASKS_YOU_TO_ALLOW_SOMETHING()
                                    : s.lang().WINDOW().MAY_REACH_THIS_COMPUTER()
                            )
                        )
                    )
                    .isVisibleIf(Viewable.of(Boolean.class, shown, state, (it, s) ->
                        it.hostAccess().saved().on() || !it.hostAccess().waitingAt(s.now()).isEmpty()
                    ))
                    .withStyle(theme, (now, it) -> it
                        .componentFont(f -> f
                            .family(now.fonts().FONT())
                            .size(11)
                            .color(now.palette().BRASS())
                        )
                    )
                )
                .add("growx, wmin 0",
                    label(Viewable.of(String.class, shown, windowWords, (it, words) -> "▣  " + words.SHOWS_YOU().of(it.showing())))
                    .isVisibleIf(showsElsewhere)
                    .withStyle(theme, (now, it) -> it
                        .componentFont(f -> f
                            .family(now.fonts().FONT())
                            .size(11)
                            .color(now.palette().FLAME())
                        )
                    )
                )
            )
            .add("top",
                button("").group(Skin.ICON_BUTTON)
                .isVisibleIf(awakeAndOut.viewAs(Boolean.class, it -> !it))
                .withStyle(Themed.of(folded, theme), (themed, it) -> {
                    var on = themed.item();
                    Palette p = themed.palette();
                    return it
                        .icon(SignSvgUtil.sign(on ? SignSvgUtil.UNFOLD : SignSvgUtil.FOLD, p.SUBTEXT()))
                        .padding(3, 5, 3, 5);
                })
                .withTooltip(Viewable.of(String.class, folded, windowWords, (on, words) ->
                    on ? words.SHOW_THIS_GENIES_CONVERSATIONS() : words.FOLD_THIS_GENIES_CONVERSATIONS_AWAY()
                ))
                .onClick(_ -> folded.update(From.VIEW, on -> !on))
            )
            // On a line of its own, whether the arrow is there or not.
            .add("newline, span 2, growx, wmin 0",
                conversationsOf(shown)
            );
    }

    // ─── a genie's conversations ───────────────────────────────────────────────────────────

    /// The genie's conversations, under its card, as two trees: those the runs of its scheduled
    /// jobs had, and the user's own, with ＋ on its line for a new one. Each opens from a line
    /// saying how many. A conversation is a row, and below it are its branches, one for each
    /// question asked differently. Clicking a row goes there; the row the genie is on is
    /// selected. Under both trees, since it can be in either, the one it is on can be deleted.
    /// While the genie is not awake, the user can fold all of this away into its card.
    private UIForAnySwing<?, ?> conversationsOf(Var<Genie> shown) {
        UUID id = shown.get().id();
        Var<Conversations> conversations = shown.zoomTo(Genie::conversations, Genie::withConversations);
        Var<Fold> chats = conversations.zoomTo(Conversations::chatsFold, Conversations::withChatsFold);
        Var<Fold> jobs = conversations.zoomTo(Conversations::jobsFold, Conversations::withJobsFold);
        // The trees can be gone through while the genie answers; a new conversation waits for the answer.
        Val<Boolean> browsable = shown.viewAs(Boolean.class, it -> it.phase() != Genie.Phase.WAKING);
        // A new conversation needs the genie awake; while it answers, the new one waits.
        Val<Boolean> awake = shown.viewAs(Boolean.class, it -> it.phase().isAwake());
        Val<Boolean> idle = shown.viewAs(Boolean.class, it -> it.phase() != Genie.Phase.WORKING);
        Val<Boolean> canForget = shown.viewAs(Boolean.class, it ->
            it.phase() != Genie.Phase.WORKING &&
            it.phase() != Genie.Phase.WAKING &&
            it.conversations().current().isPresent()
        );
        Val<Boolean> eitherOpen = Viewable.of(Boolean.class, chats, jobs, (c, j) -> c.shown() || j.shown());
        return
            box("fill, wrap 1, ins 0, gap 2, hidemode 3", "[grow]")
            .isVisibleIf(shown.viewAs(Boolean.class, Genie::showsConversations))
            .add("growx, wmin 0",
                tree(
                    id,
                    jobs,
                    Viewable.of(String.class, conversations, windowWords, (it, words) -> words.SCHEDULED_RUNS().of(it.jobCount())),
                    windowWords.viewAsString(Lang.Window::SHOW_OR_HIDE_THE_CONVERSATIONS_OF_SCHEDULED_JOBS),
                    conversations.viewAs(Boolean.class, it -> it.jobCount() > 0),
                    conversations.viewAs(Tuple.classTyped(Talk.class), Conversations::jobRuns),
                    browsable,
                    Optional.empty()
                )
            )
            .add("growx, wmin 0, gaptop 4",
                tree(
                    id,
                    chats,
                    Viewable.of(String.class, conversations, windowWords, (it, words) -> words.CONVERSATIONS().of(it.chatCount())),
                    windowWords.viewAsString(Lang.Window::SHOW_OR_HIDE_YOUR_CONVERSATIONS_WITH_THIS_GENIE),
                    Val.of(true),
                    conversations.viewAs(Tuple.classTyped(Talk.class), Conversations::chats),
                    browsable,
                    Optional.of(
                        button("").group(Skin.ICON_BUTTON).isVisibleIf(awake).isEnabledIf(idle)
                        .withStyle(palette, (p, it) -> it
                            .icon(SignSvgUtil.sign(SignSvgUtil.NEW, p.SUBTEXT()))
                            .padding(1, 6, 1, 6)
                        )
                        .withTooltip(windowWords.viewAsString(Lang.Window::START_A_NEW_CONVERSATION_WITH_THIS_GENIE))
                        .onClick(_ -> app.startAfresh(id))
                    )
                )
            )
            .add("left, gaptop 2",
                button(windowWords.viewAsString(Lang.Window::DELETE_ELLIPSIS)).group(Skin.QUIET_BUTTON).isEnabledIf(canForget)
                .isVisibleIf(eitherOpen)
                .withStyle(palette, (p, it) -> it.icon(SignSvgUtil.sign(SignSvgUtil.DELETE, p.SUBTEXT())))
                .withTooltip(windowWords.viewAsString(Lang.Window::DELETE_THE_CONVERSATION_THIS_GENIE_IS_IN))
                .onClick(_ -> confirmForget(id))
            );
    }

    /// One tree of conversations: the line that opens it, then the tree in an area of the fold's
    /// height, which scrolls when the tree is taller, and a grip under it that the user drags to
    /// make the area taller or shorter.
    ///
    /// @param beside at the end of the line that opens it, such as a button that adds a row
    private UIForAnySwing<?, ?> tree(
        final UUID id,
        final Var<Fold> fold,
        final Val<String> count,
        final Val<String> tip,
        final Val<Boolean> present,
        final Val<Tuple<Talk>> rows,
        final Val<Boolean> browsable,
        final Optional<UIForAnySwing<?, ?>> beside
    ) {
        Val<Boolean> open = fold.viewAs(Boolean.class, Fold::shown);
        Val<Tuple<String>> here = Viewable.of(
            Tuple.classTyped(String.class),
            rows,
            state.viewAs(Conversations.class, it -> it.find(id).map(Genie::conversations).orElse(Conversations.NONE)),
            (shownRows, conversations) -> conversations.selectionIn(shownRows)
        );
        // The line that opens the tree brightens under the pointer, and so does the tree's row
        // under it, across the tree's width: the number of that row, or -1 for none.
        Var<Boolean> lineHovered = Var.of(false);
        Var<Integer> pointed = Var.of(-1);
        JScrollPane[] area = new JScrollPane[1];
        // A scroll pane lays out only what is inside it: when the tree grows, shrinks or is shown
        // or hidden, Swing marks the card as needing a new layout but never gives it one. A later
        // layout may then still reserve the tree's old room. Told here, the card is laid out anew.
        ComponentAdapter relayout = new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { area[0].getParent().revalidate(); }
            @Override public void componentShown(ComponentEvent event)   { area[0].getParent().revalidate(); }
            @Override public void componentHidden(ComponentEvent event)  { area[0].getParent().revalidate(); }
        };
        var treeFoldSymbolText = Viewable.of(String.class, open, count, (on, words) -> (on ? "▾  " : "▸  ") + words);
        return
            box("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]")
            .isVisibleIf(present)
            .add("growx, wmin 0",
                box("fill, ins 0, gap 0", "[grow][]")
                .add("growx, wmin 0",
                    label(treeFoldSymbolText)
                    .group(Skin.META).withCursor(UI.Cursor.HAND)
                    .withStyle(Themed.of(lineHovered, theme), (themed, it) -> {
                        var on = themed.item();
                        Palette p = themed.palette();
                        return on ? it.componentFont(f -> f.color(p.TEXT())) : it;
                    })
                    .withTooltip(tip)
                    .onMouseEnter(_ -> lineHovered.set(true))
                    .onMouseExit(_ -> lineHovered.set(false))
                    .onMouseClick(_ -> fold.update(From.VIEW, Fold::toggled))
                )
                .applyIfPresent(beside.map(it -> line -> line.add(it)))
            )
            .add("growx, wmin 0, hmin 0",
                scrollPane(conf -> conf.fitWidth(true))
                .withEmptyBorder(0).withMinSize(0, 0)
                .peek(it -> area[0] = it)
                .peek(it -> it.addComponentListener(relayout))
                .isVisibleIf(open)
                .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                .withVerticalScrollIncrement(16)
                // At most the fold's height; a tree of a few rows takes only what it needs.
                .withMaxHeight(fold.viewAs(Integer.class, Fold::height))
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .add(
                    // In a panel of its own: on its own, a tree asks its scroll pane for room for
                    // twenty rows, however many it has.
                    panel("fill, ins 0").withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                    .peek(it -> it.addComponentListener(relayout))
                    .add("grow, wmin 0",
                        UI.trees(rows, conf -> conf
                            .nodesOf(Talk.Chat.class, it -> it
                                .children(Talk.Chat::branches)
                                .text(Talk.Chat::title)
                                .toolTip(chat ->
                                    chat.title() + " — " + (
                                        chat.turns() == 0
                                            ? windowWords.get().ITS_FIRST_QUESTION_WAS_ASKED_DIFFERENTLY()
                                            : questions(chat.turns(), chat.branches())
                                    )
                                )
                            )
                            .nodesOf(Talk.Branch.class, it -> it
                                .children(Talk.Branch::forks)
                                .text(Talk.Branch::title)
                                .toolTip(branch -> branch.title() + " — " + questions(branch.turns(), branch.forks()))
                            )
                            .leafWhenEmpty(true)
                        )
                        .isEnabledIf(browsable)
                        .withSelection(here)
                        .onSelection(it -> {
                            if (it.selection().isEmpty()) {
                                // The other tree is also cleared by the selection binding. Only
                                // clearing this tree's current selection asks for the overview.
                                if (!here.get().isEmpty())
                                    state.update(From.VIEW, s ->
                                        s.update(id, g -> g.withConversations(g.conversations().withOverview(true)))
                                    );
                            }
                            else
                                goTo(id, it.leadPath(), it.lead());
                        })
                        .onMouseMove(it -> {
                            JTree tree = it.getComponent();
                            // In the tree's own pixels, as its rows are: mouseX() and mouseY() are unscaled.
                            int x = it.getEvent().getX(), y = it.getEvent().getY();
                            int row = tree.getClosestRowForLocation(x, y);
                            Rectangle bounds = tree.getRowBounds(row);
                            boolean onRow = bounds != null && y >= bounds.y && y < bounds.y + bounds.height;
                            pointed.set(onRow ? row : -1);
                            boolean clickable = onRow && tree.isEnabled();
                            tree.setCursor(Cursor.getPredefinedCursor(clickable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                        })
                        .onMouseExit(_ -> pointed.set(-1))
                        .withStyle(Themed.of(pointed, theme), (themed, it) -> {
                            var row = themed.item();
                            Palette p = themed.palette();
                            JTree tree = it.component();
                            return
                                it.backgroundColor(p.TRANSPARENT())
                                .componentFont(f -> f.family(themed.fonts().FONT()).size(12).color(p.TEXT()))
                                .painter(UI.Layer.BACKGROUND, g -> {
                                    Rectangle bounds = row < 0 || !tree.isEnabled() ? null : tree.getRowBounds(row);
                                    if (bounds == null) return;
                                    // SwingTree scales a painter's units to pixels; the tree's rows are in pixels.
                                    g.scale(1 / UI.scale(), 1 / UI.scale());
                                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                                    g.setColor(ViewPartsUtil.withAlpha(p.BORDER(), 170));
                                    g.fillRoundRect(0, bounds.y, tree.getWidth(), bounds.height, UI.scale(8), UI.scale(8));
                                });
                        })
                    )
                )
            )
            .add("growx, wmin 0, h 9!",
                grip(fold, () -> UI.unscale(area[0].getHeight()), open, windowWords.viewAsString(Lang.Window::DRAG_TO_MAKE_THIS_LIST_TALLER_OR_SHORTER))
            );
    }

    /// The grip under a list that folds: the user drags it to make the list's area taller or
    /// shorter, and all the way up to fold the list away.
    ///
    /// @param areaHeight how tall the list's area is now, which for a short list is less than
    ///                   the fold's height
    private UIForAnySwing<?, ?> grip(
        final Var<Fold> fold,
        final IntSupplier areaHeight,
        final Val<Boolean> shown,
        final Val<String> tip
    ) {
        // Where a drag began: the pointer's height on the screen, the area's, and the fold's.
        int[] dragFrom = new int[3];
        return
            panel().withCursor(UI.Cursor.RESIZE_BOTTOM)
            .isVisibleIf(shown)
            .withTooltip(tip)
            .withStyle(palette, (p, it) -> {
                int width = it.componentWidth(), height = it.componentHeight();
                return it.backgroundColor(p.TRANSPARENT()).painter(UI.Layer.CONTENT, g -> grip(p, g, width, height));
            })
            .onMousePress(it -> {
                dragFrom[0] = UI.unscale(it.mouseYOnScreen());
                dragFrom[1] = areaHeight.getAsInt();
                dragFrom[2] = fold.get().height();
            })
            .onMouseDrag(it ->
                fold.update(From.VIEW, f -> f
                    .withHeight(dragFrom[1] + UI.unscale(it.mouseYOnScreen()) - dragFrom[0])
                )
            )
            .onMouseRelease(_ ->
                fold.update(From.VIEW, f -> f.released(dragFrom[2]))
            );
    }

    /// A grip: a thin line along its length, like a split pane's divider, with a short raised
    /// handle in its middle. Across a wide grip, such as one under a tree; down a tall one, such
    /// as the one between the chat and the desktop.
    private static void grip(final Palette p, final Graphics2D g, final int width, final int height) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (width < height) g.rotate(Math.PI / 2, width / 2.0, width / 2.0);
        int length = Math.max(width, height), middle = Math.min(width, height) / 2;
        g.setColor(p.BORDER());
        g.fillRect(0, middle, length, 1);
        g.setColor(p.SUBTEXT());
        g.fillRoundRect(length / 2 - 14, middle - 1, 28, 3, 3, 3);
    }

    /// How long a conversation or a branch is, with `forks` the branches it splits into: the
    /// first of them is the one it goes on in, every other one a question asked differently.
    private String questions(int turns, Tuple<Talk.Branch> forks) {
        return windowWords.get().QUESTIONS_THEN_ASKED_DIFFERENTLY().of(turns, Math.max(0, forks.size() - 1));
    }

    /// The user clicked a row of a genie's tree, and goes to the row's last entry. The row the
    /// genie is on already, which the tree selects by itself, goes nowhere.
    private void goTo(UUID id, Tuple<String> path, Optional<Talk> row) {
        state.get().find(id).ifPresent(genie -> {
            if (path.isEmpty() || row.isEmpty())
                return;
            if (path.equals(genie.conversations().herePath())) {
                state.update(From.VIEW, s ->
                    s.select(id).update(id, g -> g.withConversations(g.conversations().withOverview(false)))
                );
                return;
            }
            genie.conversations().fileOf(path.first()).ifPresent(file -> {
                follow.ifPresent(FollowTheEnd::toEnd);
                app.goTo(id, file, row.get().leaf());
            });
        });
    }

    private void confirmForget(UUID id) {
        state.get().find(id).flatMap(genie -> genie.conversations().current()).ifPresent(doomed -> {
            ConfirmAnswer answer =
                UI.confirmation("Delete the conversation \"" + doomed.title() + "\" for good, with all its branches?")
                .titled("Delete a conversation")
                .yesOption("Delete").noOption("Keep").cancelOption("")
                .show();
            if (answer == ConfirmAnswer.YES) app.forget(id, doomed.file());
        });
    }

    // ─── the main area: a conversation, a schedule, a history, or the settings ─────────────

    private UIForAnySwing<?, ?> mainArea() {
        Val<Boolean> onChat = page.viewAs(Boolean.class, it -> it == GeniesState.Page.CHAT);
        Val<Boolean> onSchedule = page.viewAs(Boolean.class, it -> it == GeniesState.Page.SCHEDULE);
        Val<Boolean> onHistory = page.viewAs(Boolean.class, it -> it == GeniesState.Page.HISTORY);
        Val<Boolean> onHostAccess = page.viewAs(Boolean.class, it -> it == GeniesState.Page.HOST_ACCESS);
        Val<Boolean> onActions = page.viewAs(Boolean.class, it -> it == GeniesState.Page.ACTIONS);
        Val<Boolean> onSettings = page.viewAs(Boolean.class, it -> it == GeniesState.Page.SETTINGS);
        Val<Boolean> hasGenies = state.viewAs(Boolean.class, GeniesState::hasGenies);
        return
            box("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]")
            .add("growx, wmin 0", header(Viewable.of(Boolean.class, onSettings, hasGenies, (a, b) -> !a && b)))
            .add("growx, wmin 0", requestForPermission(Viewable.of(Boolean.class, onSettings, hasGenies, (a, b) -> !a && b)))
            .add("grow, push, wmin 0", conversation(Viewable.of(Boolean.class, onChat, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", schedulePage.view(Viewable.of(Boolean.class, onSchedule, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", historyPage.view(Viewable.of(Boolean.class, onHistory, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", hostAccessPage.view(Viewable.of(Boolean.class, onHostAccess, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", actionsPage.view(Viewable.of(Boolean.class, onActions, hasGenies, (a, b) -> a && b)))
            .add("grow, push, wmin 0", SettingsPage.of(state, app, Viewable.of(Boolean.class, onSettings, hasGenies, (a, b) -> a || !b)));
    }

    private UIForAnySwing<?, ?> header(Val<Boolean> visible) {
        Val<Boolean> onChat = page.viewAs(Boolean.class, it -> it == GeniesState.Page.CHAT);
        Val<Boolean> awake = Viewable.of(Boolean.class, phase, onChat, (it, chat) -> it.isAwake() && chat);
        Val<Boolean> working = phase.viewAs(Boolean.class, it -> it == Genie.Phase.WORKING);
        Val<Boolean> wide = state.viewAs(Boolean.class, GeniesState::roomForWords);
        // A quick way to sleep, while the header has room; in a narrow one, the genie's actions have it.
        Val<Boolean> canSleepHere = Viewable.of(Boolean.class, phase, state, (it, s) -> it.isAwake() && s.roomForPages());
        // The list of genies says when something went wrong; while it is hidden, the header does.
        Val<Boolean> hiddenTroubles = state.viewAs(Boolean.class, it -> !it.genieList().shown() && !it.troubles().isEmpty());
        return
            panel("fill, ins 0, gap 10, hidemode 3", "[]0[30!][grow][]").group(Skin.HEADER)
            .isVisibleIf(visible)
            // Each part has its cell, and the hidden ones' gaps go with them, so the others keep
            // their places.
            // Not this genie's: what went wrong anywhere in Genies, so it stays first, under ☰.
            .add("cell 0 0, gapright 10",
                button(state.viewAsString(it -> "⚠ " + it.troubles().size())).group(Skin.QUIET_BUTTON)
                .isVisibleIf(hiddenTroubles)
                .withStyle(palette, (p, it) -> it
                    .backgroundColor(p.TROUBLE_WASH())
                    .border(1, p.TROUBLE())
                    .padding(3, 8, 3, 8)
                    .componentFont(f -> f.color(p.TROUBLE()))
                )
                .withTooltip(headerWords.viewAsString(Lang.Header::SOMETHING_WENT_WRONG_IN_GENIES_NOT_ONLY_IN_THIS_GENIE))
                .onClick(_ -> showTroubles())
            )
            .add("cell 1 0", ViewPartsUtil.lamp(phase, 30))
            .add("cell 2 0, growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 0")
                .add("growx, wmin 0",
                    label(name).group(Skin.TITLE)
                    .withTooltip(headerWords.viewAsString(Lang.Header::DOUBLE_CLICK_TO_RENAME))
                    .onMouseClick(it -> { if (it.clickCount() == 2) rename(genie.get().id()); })
                )
                .add("growx, wmin 0", waving(label(genie.viewAsString(Genie::status)).group(Skin.SUBTITLE), genie))
            )
            // One group on the right, so buttons that are hidden leave no gap behind. The pages
            // come last, at the edge: the buttons before them come and go with the genie's phase
            // and page, and the token count grows, and the name's cell gives or takes that room,
            // so the pages never move.
            .add("cell 3 0",
                box("ins 0, gap 10, hidemode 3, aligny center")
                .add(
                    label(Viewable.of(String.class, genie, headerWords, (it, words) -> words.TOKENS().of(it.tokens())))
                    .group(Skin.META)
                    .isVisibleIf(wide)
                    .withTooltip(headerWords.viewAsString(Lang.Header::TOKENS_THE_MODEL_COUNTED_FOR_THIS_GENIE_SINCE_GENIES_STARTED))
                )
                .add(
                    toggleButton(worded(Lang.Header::DESKTOP), desktopShown).group(Skin.QUIET_BUTTON).isVisibleIf(awake)
                    .withStyle(palette, (p, it) -> it.icon(SignSvgUtil.sign(SignSvgUtil.DESKTOP, p.TEXT())))
                    .withTooltip(headerWords.viewAsString(Lang.Header::DESKTOP_WATCH_THE_GENIES_DESKTOP_AND_USE_IT))
                )
                .add(
                    button(worded(Lang.Header::STOP)).group(Skin.QUIET_BUTTON).isVisibleIf(working)
                    .withStyle(palette, (p, it) -> it.icon(SignSvgUtil.sign(SignSvgUtil.STOP, p.TEXT())))
                    .withTooltip(headerWords.viewAsString(Lang.Header::STOP_WHAT_THE_GENIE_IS_DOING))
                    .onClick(_ -> app.stop(genie.get().id()))
                )
                .add(
                    button(worded(Lang.Header::SLEEP)).group(Skin.QUIET_BUTTON).isVisibleIf(canSleepHere)
                    .withStyle(palette, (p, it) -> it.icon(SignSvgUtil.sign(SignSvgUtil.SLEEP, p.TEXT())))
                    .withTooltip(headerWords.viewAsString(Lang.Header::SLEEP_END_THE_GENIES_SANDBOX))
                    .onClick(_ -> app.sleep(genie.get().id()))
                )
                .add(pages())
            )
            .add("cell 0 1, span 4, growx, wmin 0", pagesBelow());
    }

    /// A request the selected genie's lamp asks the user to allow, such as running a program,
    /// under the header on every page of the genie, with Allow and Refuse next to it. The oldest
    /// first; the next one, if any, takes its place once the user decided. The lamp refuses a
    /// request nobody decided about after [HostAccess#PATIENCE], and it goes away then.
    private UIForAnySwing<?, ?> requestForPermission(Val<Boolean> visible) {
        Val<String> request = Viewable.of(String.class, genie, state, (it, s) ->
            it.hostAccess()
            .waitingAt(s.now())
            .stream()
            .findFirst()
            .map(HostAccess.RequestForPermission::what)
            .orElse("")
        );
        Val<Boolean> shown = Viewable.of(Boolean.class, visible, request, (on, what) -> on && !what.isEmpty());
        return
            panel("fill, ins 10 14 10 14, gap 12, hidemode 3", "[grow][][]")
            .isVisibleIf(shown)
            .withStyle(palette, (p, it) -> it.backgroundColor(p.YOURS()).borderAt(UI.Edge.LEFT, 3, p.BRASS()).borderAt(UI.Edge.BOTTOM, 1, p.BORDER()))
            .add("growx, wmin 0",
                box("fill, wrap 1, ins 0, gap 2", "[grow]")
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(
                        theme,
                        Viewable.of(String.class, state, request, (s, what) -> s.lang().HEADER().ASKS_YOU_TO_ALLOW().of(s.genie().name(), what)),
                        Palette::TEXT,
                        Val.of(true)
                    )
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.note(
                        theme,
                        headerWords.viewAsString(Lang.Header::A_MODEL_ALLOWED_IT_ALREADY_THIS_ONE_NEEDS_YOU_TOO),
                        Val.of(true)
                    )
                )
            )
            .add(
                button(headerWords.viewAsString(Lang.Header::REFUSE)).group(Skin.QUIET_BUTTON)
                .onClick(_ -> app.decidePermission(genie.get().id(), request.get(), false))
            )
            .add(
                button(headerWords.viewAsString(Lang.Header::ALLOW)).group(Skin.FLAME_BUTTON)
                .onClick(_ -> app.decidePermission(genie.get().id(), request.get(), true))
            );
    }

    /*
     *  The pages of a genie as one switch in the header. The header makes room in steps as it
     *  narrows: first the parts drop their words and keep their signs, as its other buttons do;
     *  then the switch nests, into Chat and Manage, and the pages Manage gathers come as a row of
     *  their own under the header, while the user is on one of them:
     *
     *      wide        [💬 Chat | ⚡ Actions | 📅 Schedule 3 | 🕘 History | 🏠 Access]
     *      narrower    [💬 | ⚡ | 📅 3 | 🕘 | 🏠]
     *      narrow      [💬 | ⫶]               on the chat
     *                  [💬 | ⫶]               on any other of its pages, lit on Manage
     *                  [⚡ Actions | 📅 Schedule 3 | 🕘 History | 🏠 Access]
     *
     *  Which of these shows is the state's, [GeniesState#roomForPages] and [GeniesState#pagesBelow];
     *  each is made once, and shown or hidden.
     */
    private UIForAnySwing<?, ?> pages() {
        Val<Boolean> wide = state.viewAs(Boolean.class, GeniesState::roomForWords);
        Val<Boolean> roomForPages = state.viewAs(Boolean.class, GeniesState::roomForPages);
        Val<Boolean> managing = page.viewAs(Boolean.class, GeniesState.Page::managing);
        return
            box("ins 0, gap 0, hidemode 3")
            .add(
                switchOfPages().isVisibleIf(roomForPages)
                .add(part(GeniesState.Page.CHAT, wide))
                .add(part(GeniesState.Page.ACTIONS, wide))
                .add(part(GeniesState.Page.SCHEDULE, wide))
                .add(part(GeniesState.Page.HISTORY, wide))
                .add(part(GeniesState.Page.HOST_ACCESS, wide))
            )
            .add(
                switchOfPages().isVisibleIf(roomForPages.viewAs(Boolean.class, it -> !it))
                .add(part(GeniesState.Page.CHAT, wide))
                .add(
                    half(Val.of(""), SignSvgUtil.MANAGE, managing)
                    .withTooltip("Manage: the genie's actions, schedule, history and host access")
                    .onClick(_ -> page.update(From.VIEW, it -> it.managing() ? it : GeniesState.Page.ACTIONS))
                )
            );
    }

    /// The row under a narrow header with the pages Manage gathers, each part as wide as the next,
    /// so they reach across the window, easy to hit with a finger.
    private UIForAnySwing<?, ?> pagesBelow() {
        Val<Boolean> words = state.viewAs(Boolean.class, GeniesState::roomForWordsBelow);
        return
            switchOfPages()
            .withLayout("fillx, ins 2, gap 2", "[grow, sg, fill][grow, sg, fill][grow, sg, fill][grow, sg, fill]")
            .isVisibleIf(state.viewAs(Boolean.class, GeniesState::pagesBelow))
            .add(part(GeniesState.Page.ACTIONS, words))
            .add(part(GeniesState.Page.SCHEDULE, words))
            .add(part(GeniesState.Page.HISTORY, words))
            .add(part(GeniesState.Page.HOST_ACCESS, words));
    }

    /// The well a switch between pages stands in, whose lit part stands out of it.
    private UIForBox<JBox> switchOfPages() {
        return box("ins 2, gap 2").withStyle(palette, (p, it) -> it.backgroundColor(p.SMOKE()).border(1, p.BORDER()).borderRadius(11));
    }

    /// The part of a switch that goes to `to`, with its words while `words` holds. The schedule's
    /// says how many jobs it has, once they were read.
    private UIForButton<JButton> part(GeniesState.Page to, Val<Boolean> words) {
        Val<String> text = Viewable.of(String.class, genie, words, (shown, worded) -> switch (to) {
            case SCHEDULE -> {
                int jobs = shown.schedule().jobs().size();
                yield (worded ? "Schedule  " : "") + (jobs == 0 ? "" : jobs);
            }
            case HISTORY -> worded ? "History" : "";
            case HOST_ACCESS -> worded ? "Access" : "";
            case ACTIONS -> worded ? "Actions" : "";
            default -> worded ? "Chat" : "";
        });
        String sign = switch (to) {
            case SCHEDULE -> SignSvgUtil.SCHEDULE;
            case HISTORY -> SignSvgUtil.HISTORY;
            case HOST_ACCESS -> SignSvgUtil.HOST_ACCESS;
            case ACTIONS -> SignSvgUtil.ACTIONS;
            default -> SignSvgUtil.CHAT;
        };
        String tooltip = switch (to) {
            case SCHEDULE -> "Schedule: when jobs wake the genie, and what they did";
            case HISTORY -> "History: the moments the genie's home was saved at, and going back to one";
            case HOST_ACCESS -> "Host access: whether the genie may reach this computer, and its rules";
            case ACTIONS -> "Actions: rename, save, wake or sleep, link its folders, or delete the genie";
            default -> "Chat: talk with the genie";
        };
        return
            half(text, sign, page.viewAs(Boolean.class, it -> it == to))
            .withTooltip(tooltip)
            .onClick(_ -> page.set(From.VIEW, to));
    }

    /// One part of the switch between pages: its sign, and its words while there is room.
    private UIForButton<JButton> half(Val<String> text, String sign, Val<Boolean> shown) {
        return
            button(text).group(Skin.ICON_BUTTON).withIconTextGap(6)
            .withStyle(Themed.of(shown, theme), (themed, it) -> {
                var on = themed.item();
                Palette p = themed.palette();
                return it
                    .borderRadius(9)
                    .padding(4, 10, 4, 10)
                    .icon(SignSvgUtil.sign(sign, on ? p.TEXT() : p.SUBTEXT()))
                    .backgroundColor(on ? p.RAISED() : p.TRANSPARENT())
                    .componentFont(f -> f.family(themed.fonts().FONT()).size(12).weight(on ? 2f : 1f).color(on ? p.TEXT() : p.SUBTEXT()));
            });
    }

    /*
     *  The chat and the genie's desktop share the area, with a grip between them that the user
     *  drags to give one more room and the other less:
     *
     *      side by side, from SIDE_BY_SIDE_FROM wide       one under the other, narrower
     *
     *      ┌────────────┬─┬──────────────────────┐        ┌────────────────────┐
     *      │            │ │                      │        │ the chat           │
     *      │  the chat  │┃│  the desktop         │        ├──────── ━━ ────────┤
     *      │            │ │                      │        │ the desktop        │
     *      └────────────┴─┴──────────────────────┘        └────────────────────┘
     *        chatPart   GRIP  the rest                       chatPart, GRIP, desktopPart
     *
     *  How long each is comes from the state's Split. Nothing is rebuilt when the user drags or
     *  the arrangement changes: the layout is a value, made from the state. Inside a scroll pane,
     *  nothing stretches to the window's height, so the heights are stated too.
     */
    private static Layout arrangement(GeniesState it) {
        LayoutConstraint fill = LayoutConstraint.of("fill, ins 0, gap 0, hidemode 3");
        if (!it.desktopOnScreen())
            return
                Layout.mig(fill, LayoutConstraint.of("[grow]"), LayoutConstraint.of("[" + it.height() + "!]"))
                .withChildConstraints(MigAddConstraint.of("cell 0 0, grow, wmin 0"));
        if (it.sideBySide())
            return
                Layout.mig(
                    fill,
                    LayoutConstraint.of("[" + it.chatPart() + "!][" + Split.GRIP + "!][grow]"),
                    LayoutConstraint.of("[" + it.height() + "!]")
                )
                .withChildConstraints(
                    MigAddConstraint.of("cell 0 0, grow, wmin 0"),
                    MigAddConstraint.of("cell 1 0, grow"),
                    MigAddConstraint.of("cell 2 0, grow, wmin 0")
                );
        return
            Layout.mig(
                fill,
                LayoutConstraint.of("[grow]"),
                LayoutConstraint.of("[" + it.chatPart() + "!][" + Split.GRIP + "!][" + it.desktopPart() + "!]")
            )
            .withChildConstraints(
                MigAddConstraint.of("cell 0 0, grow, wmin 0"),
                MigAddConstraint.of("cell 0 1, grow"),
                MigAddConstraint.of("cell 0 2, grow, wmin 0")
            );
    }

    /// The chat, and with it the genie's desktop when the user watches.
    private UIForAnySwing<?, ?> conversation(Val<Boolean> visible) {
        Val<Boolean> shown = state.viewAs(Boolean.class, GeniesState::desktopOnScreen);
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE_SCROLL).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            // The area measures itself: its width decides the arrangement, its height the heights.
            .onResize(it -> state.update(From.VIEW, s -> s.withArea(it.getWidth(), it.getHeight())))
            .add(
                panel(state.viewAs(Layout.class, GeniesView::arrangement)).withMinSize(0, 0)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .add(
                    panel("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]", "[grow][][]")
                    .withMinSize(0, 0)
                    .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                    .add("grow, push, wmin 0", transcript())
                    .add("growx, wmin 0", outbox())
                    .add("growx, wmin 0", bottomBar())
                )
                .add(splitGrip(shown))
                .add(desktopPane(shown))
            );
    }

    /// The grip between the chat and the desktop, a line along it like the grips under the
    /// lists. The user drags it to give one more room and the other less, and all the way toward
    /// the desktop to close it. A double click lets Genies pick the share again.
    private UIForAnySwing<?, ?> splitGrip(Val<Boolean> shown) {
        Val<Boolean> beside = state.viewAs(Boolean.class, GeniesState::sideBySide);
        // Where a drag began: the pointer's place along the split on the screen, and the chat's part.
        int[] dragFrom = new int[2];
        Split[] before = { Split.PICKED };
        return
            panel()
            .isVisibleIf(shown)
            .withCursor(beside.viewAs(UI.Cursor.class, it -> it ? UI.Cursor.RESIZE_RIGHT : UI.Cursor.RESIZE_BOTTOM))
            .withTooltip(
                "Drag to share the room between the chat and the desktop, or all the way to close the desktop."
                + " Double-click to let Genies share it"
            )
            .withStyle(palette, (p, it) -> {
                int width = it.componentWidth(), height = it.componentHeight();
                return it.backgroundColor(p.TRANSPARENT()).painter(UI.Layer.CONTENT, g -> grip(p, g, width, height));
            })
            .onMousePress(it -> {
                dragFrom[0] = UI.unscale(beside.get() ? it.mouseXOnScreen() : it.mouseYOnScreen());
                dragFrom[1] = state.get().chatPart();
                before[0] = state.get().split();
            })
            .onMouseDrag(it -> {
                int along = UI.unscale(beside.get() ? it.mouseXOnScreen() : it.mouseYOnScreen());
                state.update(From.VIEW, s -> s.withChatPart(dragFrom[1] + along - dragFrom[0]));
            })
            .onMouseRelease(_ -> state.update(From.VIEW, s -> s.splitReleased(before[0])))
            .onMouseClick(it -> { if (it.clickCount() == 2) state.update(From.VIEW, s -> s.withSplit(Split.PICKED)); });
    }

    /// The genie's desktop, in a scroll pane so a large desktop never blocks the layout, with
    /// how it is shown above it: at the size of this panel, which the desktop then takes; fitted
    /// into the panel at its own size; or at a scale at which small text is readable. Above that,
    /// what the genie said it shows there.
    private UIForAnySwing<?, ?> desktopPane(Val<Boolean> shown) {
        Val<Boolean> showing = genie.viewAs(Boolean.class, it -> !it.showing().isEmpty());
        // A recorded desktop keeps its size; at the panel's size, that needs saying, and it is
        // drawn fitted, as with Fit.
        Val<Boolean> keeps = Viewable.of(Boolean.class, zoom, desktop.keepsItsSize(), (it, kept) -> it.isFit() && kept);
        Val<Boolean> zooms = Viewable.of(Boolean.class, zoom, keeps, (it, kept) -> !it.isFit() || kept);
        Val<String> panelSize = Viewable.of(String.class, keeps, desktop.desktopSize(), (kept, size) -> kept ? "fitted" : size);
        Val<String> scale = Viewable.of(String.class, zoom, panelSize, (it, size) ->
            it.isFit() ? size : it.isFixed() ? "fitted" : it.label()
        );
        Val<String> hint = Viewable.of(String.class, keeps, state.viewAs(Boolean.class, GeniesState::narrow), (kept, narrow) ->
            kept ? "Recorded, so it keeps its own size" : narrow ? "" : "Click the desktop to use it"
        );
        return
            // No row constraints: the line on what the genie shows is hidden at times, which moves
            // the rows under it up. Only the desktop's own "push" makes its row grow.
            panel("fill, wrap 1, ins 8 12 12 12, gap 6, hidemode 3", "[grow]")
            .isVisibleIf(shown)
            .withMinSize(0, 0)
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            .add("growx, wmin 0",
                label(genie.viewAsString(it -> "✦  " + it.name() + " shows you: " + it.showing()))
                .isVisibleIf(showing)
                .withTooltip(genie.viewAsString(Genie::showing))
                .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(13).weight(2f).color(now.palette().FLAME())))
            )
            .add("growx, wmin 0",
                box("fill, ins 0, gap 2, hidemode 3")
                .add(
                    modeButton(
                        "⤢  Fit",
                        "Give the desktop the size of this panel, so what the genie shows fills it."
                        + " It gets its own size back when you close it.",
                        zoom.viewAs(Boolean.class, DesktopZoom::isFit)
                    )
                    .onClick(_ -> zoom.set(From.VIEW, DesktopZoom.FIT))
                )
                .add(
                    modeButton(
                        "⊡  Fixed",
                        "Show the whole desktop at its own size, shrunk into this panel",
                        zoom.viewAs(Boolean.class, DesktopZoom::isFixed)
                    )
                    .onClick(_ -> zoom.set(From.VIEW, DesktopZoom.FIXED))
                )
                // At the panel's size, the desktop is drawn pixel for pixel: there is nothing to
                // zoom, and between these the desktop's size could pass for something they change.
                // A desktop that keeps its own size is drawn fitted, and zooms as from Fit.
                .add(
                    zoomButton("−", "Smaller (or Ctrl and the mouse wheel on the desktop)").isVisibleIf(zooms)
                    .onClick(_ -> zoom.update(From.VIEW, z -> z.out(desktop.fitScale())))
                )
                .add(
                    label(scale).group(Skin.META)
                    .withMinSize(70, 0).withHorizontalAlignment(UI.HorizontalAlignment.CENTER)
                )
                .add(
                    zoomButton("+", "Larger (or Ctrl and the mouse wheel on the desktop)").isVisibleIf(zooms)
                    .onClick(_ -> zoom.update(From.VIEW, z -> z.in(desktop.fitScale())))
                )
                .add("wmin 0, pushx, alignx right", label(hint).group(Skin.META))
                .add(
                    button(state.viewAsString(it -> it.narrow() ? "" : "Keys")).group(Skin.ICON_BUTTON).withIconTextGap(6)
                    .withStyle(theme, (now, it) -> it
                        .icon(SignSvgUtil.sign(SignSvgUtil.KEYS, now.palette().SUBTEXT()))
                        .componentFont(f -> f.family(now.fonts().FONT()).size(12).color(now.palette().SUBTEXT()))
                    )
                    .withTooltip("The keys that make a window on the desktop fullscreen, close it or minimize it")
                    .onMousePress(it -> ViewPartsUtil.toggleBelow(it.getEvent(), this::desktopKeys))
                    .onPressed(Keyboard.Key.SPACE, it -> ViewPartsUtil.toggleBelow(it.getEvent(), this::desktopKeys))
                )
            )
            .add("grow, push, wmin 0, hmin 0",
                scrollPane()
                .withEmptyBorder(0).withMinSize(0, 0)
                .withStyle(palette, (p, it) -> it.backgroundColor(p.SMOKE()).border(1, p.BORDER()).borderRadius(10))
                .add(UI.of(desktop))
            );
    }

    /// The keys sway answers on the desktop, behind "Keys" above it. sway draws no buttons on a
    /// window's title bar, so these are the only way to close, minimize or fullscreen a window
    /// whose program offers none.
    private JPopupMenu desktopKeys() {
        return UI.use(look, () ->
            UI.popupMenu()
            .add(
                panel("wrap 2, ins 12 14 12 14, gap 14 7", "[][]")
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                .add("span 2",
                    label("Keys for the desktop")
                    .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(13).weight(2f).color(now.palette().TEXT())))
                )
                .add("span 2, gapbottom 4", label("Click a window first, so that it has the keyboard.").group(Skin.META))
                .add(keyCaps("F"))
                .add(keyLabel("Fullscreen, and back"))
                .add(keyCaps("Q"))
                .add(keyLabel("Close the window"))
                .add(keyCaps("M"))
                .add(keyLabel("Minimize the window"))
                .add(keyCaps("N"))
                .add(keyLabel("Bring every minimized window back"))
                .add("span 2, gaptop 4", label("Super is the key with the Windows logo.").group(Skin.META))
            )
            .get(JPopupMenu.class)
        );
    }

    /// What a key of the desktop does, beside its [#keyCaps].
    private UIForLabel<JLabel> keyLabel(String text) {
        return label(text).withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(12).color(now.palette().TEXT())));
    }

    /// Ctrl + Super + `letter`, each drawn as a key on a keyboard.
    private UIForBox<JBox> keyCaps(String letter) {
        UIForBox<JBox> caps = box("ins 0, gap 4, aligny center");
        for (String key : List.of("Ctrl", "Super", letter)) {
            if (!key.equals("Ctrl"))
                caps = caps.add(label("+").withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(11).color(now.palette().SUBTEXT()))));
            caps = caps.add(
                label(key).withHorizontalAlignment(UI.HorizontalAlignment.CENTER)
                .withStyle(theme, (now, it) -> it
                    .backgroundColor(now.palette().RAISED())
                    .border(1, now.palette().BORDER())
                    .borderRadius(5)
                    .padding(1, 7, 2, 7)
                    .componentFont(f -> f.family(now.fonts().FONT()).size(11).weight(2f).color(now.palette().TEXT()))
                )
            );
        }
        return caps;
    }

    private UIForAnySwing<?, ?> transcript() {
        Val<Boolean> empty = entries.viewAs(Boolean.class, Tuple::isEmpty);
        Val<Boolean> wakingPicture = Viewable.of(Boolean.class, phase, wakePicture.emerging(), (it, emerging) ->
            it == Genie.Phase.WAKING || emerging
        );
        Val<Boolean> overview = genie.viewAs(Boolean.class, it -> it.conversations().overview());
        Val<Boolean> emptyOrOverview = Viewable.of(Boolean.class, empty, overview, (it, home) -> it || home);
        Val<Boolean> pictureShown = Viewable.of(Boolean.class, emptyOrOverview, wakingPicture, (it, waking) -> it || waking);
        Val<Boolean> livePicture = Viewable.of(Boolean.class, pictureShown, page, (shown, where) -> shown && where == GeniesState.Page.CHAT);
        // Working, with nothing streaming in: a bar says what the user waits for. The pulse, which
        // moves while the genie works, also moves on the time a job has run.
        Val<Boolean> waiting = genie.viewAs(Boolean.class, it ->
            it.phase() == Genie.Phase.WORKING &&
            it.conversations().aside().isEmpty() &&
            (it.transcript().isEmpty() || !it.transcript().entries().last().isWriting())
        );
        Val<Genie.Waiting> waitingOn = Viewable.of(Genie.Waiting.class, genie, pulse, (it, _) -> it.waitingOn(Instant.now()));
        return
            box("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow]", "[grow][]")
            .add("grow, push, wmin 0",
                scrollPanels().group(Skin.PAGE_SCROLL).withMinSize(0, 0).withPrefSize(560, 500).withEmptyBorder(0)
                .isVisibleIf(pictureShown.viewAs(Boolean.class, it -> !it))
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()).padding(12, 18, 12, 18))
                .addAll(entries, rows::row)
                // Follows the conversation while the user is at its end, and leaves them be
                // while they scroll back to read. Another genie's conversation starts at its end.
                .peek(pane -> follow = Optional.of(FollowTheEnd.on(pane)))
                .onView(Viewable.cast(selected), _ -> follow.ifPresent(FollowTheEnd::toEnd))
            )
            .add("growx, wmin 0", rows.waiting(waitingOn, waiting))
            .add("grow, push, wmin 0, align center",
                box("wrap 1, ins 30, gap 8, align center center", "[center, grow, fill]")
                .isVisibleIf(pictureShown)
                .add("growx, wmin 0", wakePicture.view(livePicture))
                .add("growx, wmin 0",
                    label(genie.viewAsString(it -> switch (it.phase()) {
                        case READY, WORKING -> it.name() + " is listening.";
                        case WAKING -> it.name() + " is waking.";
                        case ASLEEP -> it.name() + " is in the lamp.";
                        case BROKEN -> it.name() + " could not wake.";
                    }))
                    .group(Skin.EMPTY_TITLE)
                    .withHorizontalAlignment(UI.HorizontalAlignment.CENTER)
                )
                .add("growx, wmin 0",
                    html(genie.viewAsString(it -> switch (it.phase()) {
                        case READY, WORKING -> "Ask for anything. It has a desktop and a shell of its own, and hands you files it makes.";
                        case WAKING -> "Its sandbox is starting. The very first time, the sandbox is made, which takes several minutes.";
                        case ASLEEP -> "Wake it to talk. It keeps its own desktop, safely away from yours.";
                        case BROKEN -> "What went wrong is said below. Try again once that is sorted out.";
                    }))
                    .group(Skin.EMPTY_TEXT)
                    .withHorizontalAlignment(UI.HorizontalAlignment.CENTER)
                )
            );
    }

    /// The files in the genie's outbox, any of which the user can save.
    private UIForAnySwing<?, ?> outbox() {
        return
            box("fill, ins 4 18 4 18, gap 8, hidemode 3", "[][grow]")
            .isVisibleIf(handouts.viewAs(Boolean.class, it -> !it.isEmpty()))
            .add("top, gaptop 6", label("OUTBOX").group(Skin.SECTION))
            .add("growx, wmin 0",
                box("ins 0, gap 6").withMinSize(0, 0)
                .addAll(handouts, file -> UI.of(UI.use(look, () ->
                    button("⤓ " + file.name() + "  ·  " + file.readableSize()).group(Skin.QUIET_BUTTON)
                    .withTooltip("Save " + file.name() + " somewhere on this computer")
                    .onClick(_ -> saveHandout(file.name()))
                    .get(JButton.class)
                )))
            );
    }

    /// The composer while the genie is awake; otherwise a bar that wakes it, and says how
    /// waking goes.
    private UIForAnySwing<?, ?> bottomBar() {
        Val<Boolean> awake = phase.viewAs(Boolean.class, Genie.Phase::isAwake);
        Val<Boolean> overview = genie.viewAs(Boolean.class, it -> it.conversations().overview());
        Val<Boolean> composing = Viewable.of(Boolean.class, awake, overview, (on, home) -> on && !home);
        Val<Boolean> canWake = phase.viewAs(Boolean.class, it -> it == Genie.Phase.ASLEEP || it == Genie.Phase.BROKEN);
        Val<Boolean> canSend = genie.viewAs(Boolean.class, Genie::canSend);
        Val<Boolean> answeringElsewhere = genie.viewAs(Boolean.class, it -> it.conversations().aside().isPresent());
        return
            box("fill, wrap 1, ins 6 18 16 18, gap 0, hidemode 3", "[grow]")
            .add("growx, wmin 0, gapbottom 6",
                ViewPartsUtil.wrapped(
                    theme,
                    genie.viewAsString(it -> it.name() + " is answering in another conversation. Write here once it is done."),
                    Palette::SUBTEXT,
                    answeringElsewhere
                )
            )
            .add("growx, wmin 0",
                panel("fill, ins 0, gap 8", "[][grow][]", "[bottom]").group(Skin.COMPOSER).isVisibleIf(composing)
                .add(
                    button("＋").group(Skin.ICON_BUTTON).withTooltip("Give the genie a file; it lands in ~/inbox")
                    .onClick(_ -> giveFile())
                )
                .add("growx, wmin 0, hmin 36, hmax 160",
                    scrollPane().withEmptyBorder(0).withMinSize(0, 36)
                    .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                    .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
                    .add(
                        // Bound here rather than with textArea(draft): when the draft changes,
                        // SwingTree 1.0.0 sets the text with Swing's own listeners taken off the
                        // text, so the caret and the drawn lines keep the old text, and letters typed
                        // after sending are not drawn. Here, Swing's listeners stay; only the
                        // writing back into the draft is held off while the draft sets the text.
                        textArea(draft.get()).group(Skin.INPUT).peek(ViewPartsUtil::softWrap)
                        .peek(area -> Viewable.cast(draft).onChange(From.ALL, it -> {
                            String text = it.currentValue().orElse("");
                            if (area.getText().equals(text)) return;
                            settingDraft = true;
                            area.setText(text);
                            settingDraft = false;
                        }))
                        .onTextChange(it -> {
                            if (!settingDraft) draft.set(From.VIEW, it.getComponent().getText());
                        })
                        // The composer is the box; the text in it needs no second one.
                        .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()).border(0, p.TRANSPARENT()))
                        .withTooltip("Return sends; Shift and Return starts a new line")
                        // Swing stops laying out at the scroll pane, so the composer never learns
                        // that the text grew or shrank; told here, it grows up to its hmax, then scrolls.
                        .onTextChange(it ->
                            SwingUtilities.getAncestorOfClass(JScrollPane.class, it.getComponent()).getParent().revalidate()
                        )
                        .onKeyPress(it -> {
                            KeyEvent key = it.getEvent();
                            if (key.getKeyCode() != KeyEvent.VK_ENTER) return;
                            key.consume();
                            // Swing makes a new line only of a plain Return, so Shift and Return
                            // would do nothing at all; the line is put in here.
                            if (key.isShiftDown()) it.getComponent().replaceSelection("\n");
                            else send();
                        })
                    )
                )
                .add(
                    button("Send  ➤").group(Skin.FLAME_BUTTON).isEnabledIf(canSend)
                    // With nothing to send, it steps back rather than glowing half-lit.
                    .withStyle(Themed.of(canSend, theme), (themed, it) -> {
                        var on = themed.item();
                        Palette p = themed.palette();
                        return on
                            ? it
                            : it.backgroundColor(p.RAISED())
                              .foregroundColor(p.SUBTEXT())
                              .componentFont(f -> f.color(p.SUBTEXT()))
                              .cursor(UI.Cursor.DEFAULT);
                    })
                    .onClick(_ -> send())
                )
            )
            .add("growx, wmin 0",
                button("New conversation").group(Skin.FLAME_BUTTON)
                .isVisibleIf(Viewable.of(Boolean.class, awake, overview, (on, home) -> on && home))
                .isEnabledIf(phase.viewAs(Boolean.class, it -> it == Genie.Phase.READY))
                .withTooltip("Start a new conversation, or select a conversation in the tree to return to it")
                .onClick(_ -> app.startAfresh(genie.get().id()))
            )
            .add("growx, wmin 0",
                panel("fill, ins 4 8 4 4, gap 12, hidemode 3", "[grow][][]").group(Skin.COMPOSER)
                .isVisibleIf(awake.viewAs(Boolean.class, it -> !it))
                .add("growx, wmin 0",
                    waving(
                        label(genie.viewAsString(it -> switch (it.phase()) {
                            case WAKING -> "Waking " + it.name() + ": " + it.activity() + "…";
                            default -> it.name() + " is asleep. Its sandbox is off, its home and conversation are kept.";
                        }))
                        .group(Skin.SUBTITLE),
                        genie
                    )
                    .isVisibleIf(phase.viewAs(Boolean.class, it -> it != Genie.Phase.BROKEN))
                )
                .add("growx, wmin 0",
                    ViewPartsUtil.wrapped(
                        theme,
                        genie.viewAsString(it -> it.name() + " could not wake: " + it.activity()),
                        Palette::TROUBLE,
                        phase.viewAs(Boolean.class, it -> it == Genie.Phase.BROKEN)
                    )
                )
                .add(
                    button(genie.viewAsString(it -> it.phase() == Genie.Phase.BROKEN ? "Try again" : "✦  Wake"))
                    .group(Skin.FLAME_BUTTON).isVisibleIf(canWake)
                    .onClick(_ -> app.wake(genie.get().id()))
                )
            );
    }

    // ─── small parts ───────────────────────────────────────────────────────────────────────

    /// Sets up the look before any window opens: the fonts of `state`, Genies' own unless the
    /// desktop has them, then the look-and-feel in its colours, dark or light as they are. Those also reach what the
    /// style sheet does not paint: dialogs, the tree of conversations, lists of choices, scroll
    /// bars.
    public static void setUpLook(GeniesState state) {
        lookAndFeelIn(state.palette(), state.fonts());
        // On Linux the desktop draws title bars, unless asked not to; FlatLaf then draws them, and
        // the desktop still moves and resizes the window.
        JFrame.setDefaultLookAndFeelDecorated(true);
        JDialog.setDefaultLookAndFeelDecorated(true);
    }

    /// The look-and-feel in the colours `p` and the `fonts`, set up anew.
    private static void lookAndFeelIn(Palette p, Fonts fonts) {
        com.formdev.flatlaf.FlatLaf.setPreferredFontFamily(fonts.FONT());
        com.formdev.flatlaf.FlatLaf.setPreferredMonospacedFontFamily(fonts.MONO());
        com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(Map.ofEntries(
            Map.entry("@background", hex(p.CARD())),
            Map.entry("@foreground", hex(p.TEXT())),
            // Halfway between the subtext and the borders, so what cannot be used now steps back.
            Map.entry("@disabledForeground", hex(halfway(p.SUBTEXT(), p.BORDER()))),
            Map.entry("@accentColor", hex(p.FLAME())),
            Map.entry("@selectionBackground", hex(p.YOURS())),
            Map.entry("@selectionForeground", hex(p.TEXT())),
            Map.entry("@selectionInactiveBackground", hex(p.YOURS())),
            Map.entry("@selectionInactiveForeground", hex(p.TEXT())),
            Map.entry("Component.borderColor", hex(p.BORDER())),
            Map.entry("Component.focusColor", hex(p.FLAME())),
            Map.entry("Component.focusedBorderColor", hex(p.BRASS())),
            Map.entry("Component.arc", "10"),
            Map.entry("Button.arc", "10"),
            Map.entry("TextComponent.arc", "10"),
            Map.entry("ComboBox.background", hex(p.RAISED())),
            Map.entry("ComboBox.editableBackground", hex(p.RAISED())),
            Map.entry("ComboBox.buttonBackground", hex(p.RAISED())),
            Map.entry("ComboBox.buttonEditableBackground", hex(p.RAISED())),
            Map.entry("ComboBox.popupBackground", hex(p.CARD())),
            Map.entry("TextField.background", hex(p.RAISED())),
            Map.entry("PasswordField.background", hex(p.RAISED())),
            Map.entry("TextArea.background", hex(p.RAISED())),
            Map.entry("Tree.selectionArc", "6"),
            Map.entry("CheckBox.icon.selectedBackground", hex(p.FLAME())),
            Map.entry("CheckBox.icon.selectedBorderColor", hex(p.FLAME())),
            Map.entry("CheckBox.icon.checkmarkColor", hex(p.ON_FLAME())),
            Map.entry("CheckBox.icon.focusedSelectedBackground", hex(p.FLAME())),
            Map.entry("CheckBox.icon.hoverSelectedBackground", hex(p.FLAME())),
            Map.entry("ScrollBar.width", "10"),
            Map.entry("ScrollBar.showButtons", "false"),
            Map.entry("ScrollBar.track", hex(p.NIGHT())),
            Map.entry("ScrollBar.thumb", hex(p.BORDER())),
            Map.entry("ScrollBar.hoverThumbColor", hex(p.SUBTEXT())),
            Map.entry("ScrollBar.pressedThumbColor", hex(p.BRASS())),
            Map.entry("ScrollBar.thumbArc", "999"),
            Map.entry("ScrollBar.thumbInsets", "2,2,2,2"),
            Map.entry("ToolTip.background", hex(p.RAISED())),
            Map.entry("ToolTip.foreground", hex(p.TEXT())),
            // The title bars Genies draws itself, of its window and its dialogs, in the sidebar's
            // colour, a line under them as under the genie's header.
            Map.entry("TitlePane.background", hex(p.SIDEBAR())),
            Map.entry("TitlePane.inactiveBackground", hex(p.SIDEBAR())),
            Map.entry("TitlePane.foreground", hex(p.TEXT())),
            Map.entry("TitlePane.inactiveForeground", hex(p.SUBTEXT())),
            Map.entry("TitlePane.embeddedForeground", hex(p.TEXT())),
            Map.entry("TitlePane.borderColor", hex(p.BORDER())),
            Map.entry("TitlePane.buttonHoverBackground", hex(p.RAISED())),
            Map.entry("TitlePane.buttonPressedBackground", hex(p.BORDER())),
            Map.entry("TitlePane.closeHoverBackground", hex(p.TROUBLE())),
            Map.entry("TitlePane.closePressedBackground", hex(p.TROUBLE_WASH())),
            Map.entry("MenuBar.background", hex(p.SIDEBAR())),
            Map.entry("MenuBar.borderColor", hex(p.SIDEBAR())),
            Map.entry("RootPane.activeBorderColor", hex(p.BORDER())),
            Map.entry("RootPane.inactiveBorderColor", hex(p.BORDER()))
        ));
        if (p.isDark()) com.formdev.flatlaf.FlatDarkLaf.setup();
        else com.formdev.flatlaf.FlatLightLaf.setup();
    }

    private static Color halfway(Color one, Color other) {
        return new Color(
            (one.getRed() + other.getRed()) / 2,
            (one.getGreen() + other.getGreen()) / 2,
            (one.getBlue() + other.getBlue()) / 2
        );
    }

    private static String hex(Color colour) {
        return String.format("#%02x%02x%02x", colour.getRed(), colour.getGreen(), colour.getBlue());
    }

    /// The window's title bar, drawn by Genies rather than the desktop, in its colours: ☰, the
    /// lamp and "Genies" on the left, and the window's own buttons, which FlatLaf draws, on the
    /// right. FlatLaf embeds it as the window's menu bar; where it shows nothing, dragging moves
    /// the window, and a double-click maximizes it.
    ///
    /// ☰ is here so that it stays in the same corner whether the list of genies is shown or not,
    /// and above it in a narrow window. Without genies there is no list, and no ☰.
    ///
    /// @param title the window's title, such as "Genies", which the bar says beside the lamp
    public JMenuBar titleBar(Val<String> title) {
        JMenuBar bar = new JMenuBar();
        UI.use(look, () ->
            UI.of(bar).withLayout("ins 0 6 0 0, gap 8, aligny center")
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()).border(0, p.TRANSPARENT()))
            .add(
                button("").group(Skin.ICON_BUTTON)
                .isVisibleIf(state.viewAs(Boolean.class, GeniesState::hasGenies))
                .withStyle(palette, (p, it) -> it.icon(SignSvgUtil.sign(SignSvgUtil.GENIES, p.SUBTEXT())))
                .withTooltip(genieList.viewAsString(it -> it.shown() ? "Hide your genies" : "Show your genies"))
                .onClick(_ -> genieList.update(From.VIEW, Fold::toggled))
            )
            .add(ViewPartsUtil.lamp(Val.of(Genie.Phase.READY), 26))
            .add(label(title).group(Skin.BRAND).withStyle(it -> it.componentFont(f -> f.size(15))))
        );
        return bar;
    }

    /// The window's icon: Genies' icon, which the packages also install for the desktop's menus.
    public static Image windowIcon() {
        return SvgIcon.at("/dev/gui/genies.svg").withIconSize(128, 128).getImage();
    }

    /// One of the ways to show the desktop, lit while it is the one in use.
    private UIForButton<JButton> modeButton(String text, String tip, Val<Boolean> on) {
        return
            button(text).group(Skin.ICON_BUTTON).withTooltip(tip)
            .withStyle(Themed.of(on, theme), (themed, it) -> {
                var lit = themed.item();
                Palette p = themed.palette();
                return it
                    .borderRadius(9)
                    .padding(3, 10, 3, 10)
                    .backgroundColor(lit ? p.RAISED() : p.TRANSPARENT())
                    .componentFont(f -> f.family(themed.fonts().FONT()).size(12).weight(lit ? 2f : 1f).color(lit ? p.TEXT() : p.SUBTEXT()));
            });
    }

    private UIForButton<JButton> zoomButton(String text, String tip) {
        return
            button(text).group(Skin.ICON_BUTTON).withTooltip(tip)
            .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(12).color(now.palette().TEXT())));
    }

    /// A button's words while the header has room for them; its sign alone says it otherwise.
    private Val<String> worded(Function<Lang.Header, String> words) {
        return state.viewAsString(it -> it.roomForWords() ? words.apply(it.lang().HEADER()) : "");
    }

    /// What can be done with a genie beyond its everyday buttons, as on its Actions page: a
    /// right-click on its card, which selects the genie first. Deleting it is last, away from the
    /// rest.
    private JPopupMenu genieMenu(UUID id) {
        return
            UI.popupMenu()
            .applyIfPresent(state.get().find(id).map(shown -> ui -> {
                Genie.Phase now = shown.phase();
                boolean idle = now != Genie.Phase.WORKING && now != Genie.Phase.WAKING;
                String wake = now == Genie.Phase.BROKEN ? "Try waking again" : "Wake";
                String delete = (shown.fromElsewhere() ? "Remove from Genies" : "Delete " + shown.name()) + "…";
                ui
                .add(ViewPartsUtil.item("Rename…", true, () -> rename(id)))
                .add(ViewPartsUtil.item("New conversation", idle, () -> app.startAfresh(id)))
                .add(ViewPartsUtil.item("Save now…", shown.history().busy().isEmpty(), () -> historyPage.save(this)))
                .add(ViewPartsUtil.item("Link its workspace into " + app.documents().getFileName(), true, () -> linkWorkspace(id)))
                .peek(JPopupMenu::addSeparator)
                .applyIf(now.isAwake(), ui1 -> ui1
                    .add(ViewPartsUtil.item("Sleep", true, () -> app.sleep(id)))
                )
                .applyIf(!now.isAwake(), ui1 -> ui1
                    .add(ViewPartsUtil.item(wake, now != Genie.Phase.WAKING, () -> app.wake(id)))
                )
                .peek(JPopupMenu::addSeparator)
                .add(
                    // The menu is built as it opens, in the palette of that moment.
                    UI.of(ViewPartsUtil.item(delete, true, () -> confirmDelete(id)))
                    .withForeground(palette.get().TROUBLE())
                );
            }))
            .get(JPopupMenu.class);
    }

    private void rename(UUID id) {
        state.get().find(id).ifPresent(named -> {
            Object answer = JOptionPane.showInputDialog(
                this,
                "A new name for " + named.name() + ":",
                "Rename a genie",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                named.name()
            );
            if (answer instanceof String text && !text.isBlank())
                state.update(From.VIEW, it -> it.update(id, genie -> genie.withName(text.strip())));
        });
    }

    /// Links the genie's workspace into the user's documents, called as the Actions page calls it
    /// unless the user names it, and says where the link is, or why there is none.
    private void linkWorkspace(UUID id) {
        state.get().find(id).ifPresent(genie -> {
            try {
                Home.Link link = app.link(id, Home.WORKSPACE, app.documents(), Home.linkName(genie.name(), Home.WORKSPACE));
                JOptionPane.showMessageDialog(
                    this,
                    genie.name() + "'s workspace " + (link.madeNow() ? "is" : "was")
                    + " in your " + app.documents().getFileName() + (link.madeNow() ? " now" : " already")
                    + ", as \"" + link.at().getFileName() + "\".\nIt is a link, not a copy: " + genie.name()
                    + " sees what you change there.",
                    "Linked",
                    JOptionPane.INFORMATION_MESSAGE
                );
            } catch (IOException failed) {
                JOptionPane.showMessageDialog(this, failed.getMessage(), "Could not link", JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    /// The troubles, newest first, each with its stack trace, which the user can select and copy.
    /// Once seen, they are off the window; the error log keeps them.
    private void showTroubles() {
        Tuple<Trouble> seen = state.get().troubles();
        if (seen.isEmpty()) return;
        StringBuilder details = new StringBuilder();
        for (int i = seen.size() - 1; i >= 0; i--) details.append(seen.get(i).details()).append('\n');
        String what = seen.size() == 1
            ? "Something went wrong that Genies did not expect."
            : seen.size() + " things went wrong that Genies did not expect.";
        UI.dialog(SwingUtilities.getWindowAncestor(this), "What went wrong")
            .withOnCloseOperation(UI.OnWindowClose.DISPOSE)
            .onClosed(_ -> state.update(From.VIEW, now -> now.withoutTroubles(seen)))
            .add(UI.use(look, () ->
                panel("fill, wrap 1, ins 16 18 16 18, gap 10", "[grow]", "[][][grow][]").group(Skin.FRAME)
                .add("growx, wmin 0", label(what).group(Skin.TITLE))
                .add("growx, wmin 0",
                    label("Genies carried on. All of it is kept in " + app.errorLog() + ", which helps whoever fixes it.")
                    .group(Skin.META)
                )
                .add("grow, push, w 760, h 380",
                    scrollPane()
                    .add(
                        textArea(details.toString()).group(Skin.INPUT)
                        .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().MONO()).size(12)))
                        .peek(area -> { area.setEditable(false); area.setCaretPosition(0); })
                    )
                )
                .add("right",
                    button("Close").group(Skin.QUIET_BUTTON)
                    .onClick(it -> SwingUtilities.getWindowAncestor(it.getComponent()).dispose())
                )
                .get(JPanel.class)
            ))
            .show();
    }

    /// Asks before a genie is deleted for good. A lamp from elsewhere is not deleted, only
    /// removed from Genies, so the question says that instead.
    private void confirmDelete(UUID id) {
        state.get().find(id).ifPresent(doomed -> {
            ConfirmAnswer answer = doomed.fromElsewhere()
                ? UI.confirmation(
                      "Remove " + doomed.name() + " from Genies? Its lamp stays where it is, at "
                      + whereFrom(doomed) + ", as it is, running if it runs.\nAdd it again with \"Add a lamp from elsewhere…\"."
                  )
                  .titled("Remove a lamp from Genies")
                  .yesOption("Remove").noOption("Keep").cancelOption("")
                  .show()
                : UI.confirmation(
                      "Delete " + doomed.name() + " for good? Its home, everything it made "
                      + "and your conversations with it are deleted."
                  )
                  .titled("Delete a genie")
                  .yesOption("Delete").noOption("Keep").cancelOption("")
                  .show();
            if (answer == ConfirmAnswer.YES) app.delete(id);
        });
    }

    /// Asks which lamp to add, and adds it, or says why it cannot.
    private void addLamp() {
        JFileChooser chooser = new JFileChooser(System.getProperty("user.home"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Add a lamp made with `oillamp at`");
        if (chooser.showDialog(this, "Add") != JFileChooser.APPROVE_OPTION) return;
        try {
            app.addLamp(chooser.getSelectedFile().toPath());
        } catch (IOException failed) {
            JOptionPane.showMessageDialog(this, failed.getMessage(), "Not a lamp", JOptionPane.WARNING_MESSAGE);
        }
    }

    /// Lets the user change a question they asked, in a dialog holding the question as it was.
    private void askInstead(Entry question) {
        JTextArea text = new JTextArea(question.text(), 6, 48);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        int answer = JOptionPane.showConfirmDialog(
            this,
            new JScrollPane(text),
            "Ask " + genie.get().name() + " differently",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );
        String changed = text.getText().strip();
        if (answer == JOptionPane.OK_OPTION && !changed.isEmpty() && !changed.equals(question.text().strip())) {
            follow.ifPresent(FollowTheEnd::toEnd);
            app.askInstead(question.ref(), changed);
        }
    }

    /// Sends the draft and scrolls the chat to the bottom, where the sent message appears.
    private void send() {
        follow.ifPresent(FollowTheEnd::toEnd);
        app.send();
    }

    /// Shows the current conversation up to the entry `leaf`. The arrows under an edited user
    /// message call this to switch conversation versions.
    private void showLeaf(String leaf) {
        Genie shown = genie.get();
        shown.conversations().current().ifPresent(it -> app.goTo(shown.id(), it.file(), leaf));
    }

    private void giveFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Give " + genie.get().name() + " a file");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            app.giveFile(genie.get().id(), chooser.getSelectedFile().toPath());
    }

    private void saveHandout(String file) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save " + file);
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), file));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path target = chooser.getSelectedFile().toPath();
            app.saveOutboxFile(genie.get().id(), file, target);
        }
    }

    /// Loops the pulse for as long as any genie works or wakes.
    private void breathe() {
        if (breathing) return;
        breathing = true;
        UI.animateFor(1.2, TimeUnit.SECONDS)
        .asLongAs(_ -> anyWorksOrWakes())
        .go(new Animation() {
            @Override public void run(AnimationStatus status) { pulse.set(status.progress()); }
            @Override public void finish(AnimationStatus status) { breathing = false; }
        });
    }

    private boolean anyWorksOrWakes() {
        return genies.get().stream().anyMatch(it -> it.phase() == Genie.Phase.WORKING || it.phase() == Genie.Phase.WAKING);
    }

    /// `status`, through whose words a wave of the flame's light runs while `genie` wakes. Making
    /// a sandbox can take minutes with the same words shown; the wave shows that it goes on.
    private UIForLabel<JLabel> waving(UIForLabel<JLabel> status, Val<Genie> genie) {
        return status.withStyle(Themed.of(Viewable.of(Wave.class, genie, pulse, (it, at) -> it.phase() == Genie.Phase.WAKING ? new Wave(true, at) : Wave.NONE), theme), (themed, it) -> {
            var wave = themed.item();
            Palette p = themed.palette();
            return !wave.on()
                    ? it
                    : it.componentFont(f -> f.gradient(g -> g
                        .type(UI.GradientType.LINEAR)
                        .span(UI.Span.LEFT_TO_RIGHT)
                        .cycle(UI.Cycle.REPEAT)
                        .colors(p.SUBTEXT(), p.FLAME(), p.SUBTEXT())
                        .size(WAVE_LENGTH)
                        .offset(wave.at() * WAVE_LENGTH, 0)
                    ));
        });
    }

    /// Whether a wave runs through a status, and where it is, from 0 to 1 of its length.
    private record Wave(boolean on, double at) {
        /// No wave; the same however the pulse goes, so a status that does not wave is not
        /// styled anew at each step of it.
        static final Wave NONE = new Wave(false, 0);
    }
}
