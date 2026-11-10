package dev.gui.view;

import java.awt.Color;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import dev.gui.Genies;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.LampWindow;
import dev.gui.model.Lang;
import dev.gui.model.Palette;

import sprouts.From;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForButton;
import swingtree.input.Keyboard;
import swingtree.layout.FlowCell;
import swingtree.layout.Size;

import static swingtree.UI.*;

/// What can be done with a genie as a whole: renaming it, starting a new conversation with it,
/// saving its home, sending it to sleep or waking it, linking its folders into the user's, looking
/// into its sandbox in windows of their own, and deleting it. Each is a tile with its
/// sign, a sentence on what it does, and a button large enough for a finger.
///
/// A right-click on the genie's card offers the same as a menu, for those with a mouse. This page
/// is the way for everyone else, and the only one in a narrow window, whose header has no room
/// for a Sleep button.
///
/// Like the rest of the window, a function of the [GeniesState], but for one thing of its own: a
/// name the user is typing, which is no genie's until they keep it. The genie's name stands in a
/// field that becomes editable on Rename; Enter or Save keeps what was typed, Escape or Cancel the
/// name it had. And for why a window onto the sandbox did not open, which the tile that was asked
/// for it says. Saving and deleting ask the user first, in the same dialogs as everywhere else,
/// so the window lends them.
final class ActionsPage {

    /*
     *  A responsive grid of tiles: all four in one row once the page is wider than its
     *  reference, as in a full-screen window, two by two from MEDIUM up, and one under the other
     *  below. The genie's folders follow, the whole width, as a tree needs it, with a grid of its
     *  own inside (see HomeFolders). Last come the two tiles away from the rest, in a grid of their
     *  own: looking behind the scenes, and deleting the genie, which is always the last. That grid
     *  takes the whole width, but stands beside the folders, as wide as a tile, when the tiles are
     *  in one row.
     *
     *                 very small  small  medium  large  very large  oversize
     *      on top          12       12     12     12       12          12
     *      a tile          12       12      6      6        6           3
     *      the folders     12       12     12     12       12           9
     *      the last two    12       12     12     12       12           3
     *
     *  Within it, the two stand side by side wherever the tiles above stand two by two, and one
     *  under the other otherwise: in a narrow page, and in the column beside the folders. The
     *  grid cannot tell the two apart by its own width, as that column of a very wide page is
     *  wider than a narrow page. So it is told by its reference, which follows the page's width:
     *  the page's own reference, or, for the column, one so wide that the grid is always very
     *  small against it.
     *
     *                 very small  small  medium  large  very large  oversize
     *      one of two      12       12      6      6        6           6
     */
    private static final int REFERENCE = 1300;
    private static final int NEVER_REACHED = 100 * REFERENCE;
    private static final FlowCell WHOLE = AUTO_SPAN(it ->
        it.verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(12)
    );
    private static final FlowCell FOLDERS = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP).fill(true)
        .verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(9)
    );
    private static final FlowCell LAST_TWO = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP).fill(true)
        .verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(3)
    );
    private static final FlowCell ONE_OF_TWO = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP).fill(true)
        .verySmall(12).small(12).medium(6).large(6).veryLarge(6).oversize(6)
    );
    private static final FlowCell TILE = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP).fill(true)
        .verySmall(12).small(12).medium(6).large(6).veryLarge(6).oversize(3)
    );

    private final Var<GeniesState> state;
    /// The colours and the fonts of the state, and the colours alone.
    private final Val<Theme> theme;
    private final Val<Palette> palette;
    private final Val<Lang.ActionsPage> words;
    private final Genies app;
    private final Runnable saveNow;
    private final Consumer<UUID> delete;

    // Lenses and views, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Val<Genie.Phase> phase;
    private final Val<String> name;
    private final Val<UUID> selected;
    /// Whether the user is renaming the genie, and the name they are typing. Otherwise, the name
    /// it has.
    private final Var<Boolean> renaming = Var.of(false);
    private final Var<String> newName;
    /// Set once the field with the name exists, which takes the keyboard on Rename.
    private Optional<JTextField> nameField = Optional.empty();
    private final HomeFolders folders;
    /// What the grid of the last two tiles measures itself against: see the table above.
    private final Val<Size> referenceOfTheLastTwo;
    /// Why the window the user last asked for did not open, as oillamp said it; empty when it
    /// did, and until one is asked for.
    private final Var<String> noWindow = Var.of("");

    /// @param saveNow asks the user what to remember the selected genie's home by, and saves it
    /// @param delete  asks the user whether to delete a genie for good, and does so
    ActionsPage(Var<GeniesState> state, Genies app, Runnable saveNow, Consumer<UUID> delete) {
        this.state = state;
        theme = Theme.of(state);
        palette = Theme.paletteOf(state);
        words = Theme.langOf(state).viewAs(Lang.ActionsPage.class, Lang::ACTIONS_PAGE);
        this.app = app;
        this.saveNow = saveNow;
        this.delete = delete;
        genie = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        phase = genie.viewAs(Genie.Phase.class, Genie::phase);
        name = genie.viewAsString(Genie::name);
        selected = state.viewAs(UUID.class, GeniesState::selected);
        newName = Var.of(name.get());
        folders = new HomeFolders(state, app);
        referenceOfTheLastTwo = state.viewAs(Size.class, it ->
            Size.of(it.area().width() >= REFERENCE ? NEVER_REACHED : REFERENCE, 0)
        );
        // Renamed elsewhere, the field follows, unless the user is typing in it.
        Viewable.cast(name).onChange(From.ALL, it -> { if (!renaming.get()) newName.set(name.get()); });
        // Another genie chosen: what was typed was meant for the one before.
        Viewable.cast(selected).onChange(From.ALL, _ -> {
            stopRenaming();
            noWindow.set("");
        });
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
                .add(TILE, renaming())
                .add(TILE, startingAfresh())
                .add(TILE, saving())
                .add(TILE, sleepingOrWaking())
                .add(FOLDERS, folders())
                .add(LAST_TWO, lastTwo())
            );
    }

    /// Looking behind the scenes and deleting, in a grid of their own.
    private UIForAnySwing<?, ?> lastTwo() {
        return
            panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 18, 18)
            .withMinSize(0, 0).withPrefSize(referenceOfTheLastTwo)
            .peek(it -> it.addComponentListener(HomeFolders.relayoutAtNewWidth()))
            // The grid's gaps are at its edges too; outside, its tiles line up with the others.
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()).margin(-18))
            .add(ONE_OF_TWO, behindTheScenes())
            .add(ONE_OF_TWO, deleting());
    }

    private UIForAnySwing<?, ?> top() {
        return
            box("fill, wrap 1, ins 0, gap 8", "[grow]")
            .add(label(words.viewAsString(Lang.ActionsPage::ACTIONS)).group(Skin.EMPTY_TITLE))
            .add("growx, wmin 0",
                ViewPartsUtil.wrapped(
                    theme,
                    Viewable.of(String.class, name, words, (it, page) -> page.WHAT_CAN_BE_DONE_WITH_IT_AS_A_WHOLE(it)),
                    Palette::SUBTEXT,
                    Val.of(true)
                )
            );
    }

    // ─── the tiles ─────────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> renaming() {
        Val<Boolean> keepable = newName.viewAs(Boolean.class, it -> !it.isBlank());
        return tile(
            SignSvgUtil.RENAME,
            Palette::TEXT,
            words.viewAsString(Lang.ActionsPage::NAME),
            Viewable.of(String.class, renaming, words, (on, page) ->
                on ? page.ENTER_KEEPS_THE_NEW_NAME_ESCAPE_THE_ONE_IT_HAD() : page.WHAT_IT_IS_CALLED_IN_YOUR_LIST_AND_ABOVE_ITS_CHAT()
            ),
            box("fill, ins 0, gap 8, hidemode 3", "[grow][][]")
            .add("growx, wmin 0",
                textField(newName).group(Skin.INPUT)
                .isEditableIf(renaming)
                .isFocusableIf(renaming)
                // Not renaming, it reads as the name; renaming, as a field to type in.
                .withStyle(Themed.of(renaming, theme), (on, it) ->
                    on.item()
                        ? it.padding(8, 10, 8, 10)
                        : it.backgroundColor(on.palette().TRANSPARENT())
                          .border(1, on.palette().TRANSPARENT())
                          .padding(8, 0, 8, 0)
                          .cursor(UI.Cursor.DEFAULT)
                )
                .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(15).weight(2f).color(now.palette().TEXT())))
                .peek(it -> nameField = Optional.of(it))
                .onEnter(_ -> keepName())
                .onPressed(Keyboard.Key.ESCAPE, _ -> stopRenaming())
            )
            .add(
                big(button(words.viewAsString(Lang.ActionsPage::RENAME)).group(Skin.QUIET_BUTTON))
                .isVisibleIfNot(renaming)
                .onClick(_ -> startRenaming())
            )
            .add(
                big(button(words.viewAsString(Lang.ActionsPage::CANCEL)).group(Skin.QUIET_BUTTON))
                .isVisibleIf(renaming)
                .onClick(_ -> stopRenaming())
            )
            .add(
                big(button(words.viewAsString(Lang.ActionsPage::SAVE)).group(Skin.FLAME_BUTTON))
                .isVisibleIf(renaming)
                .isEnabledIf(keepable)
                .onClick(_ -> keepName())
            )
        );
    }

    private void startRenaming() {
        newName.set(name.get());
        renaming.set(true);
        // Once the field is editable, it takes the keyboard, with the name selected to type over.
        SwingUtilities.invokeLater(() -> nameField.ifPresent(field -> {
            field.requestFocusInWindow();
            field.selectAll();
        }));
    }

    private void stopRenaming() {
        renaming.set(false);
        newName.set(name.get());
    }

    /// Keeps the typed name, unless it is blank, which no genie can be called.
    private void keepName() {
        String typed = newName.get().strip();
        if (typed.isEmpty()) return;
        genie.update(From.VIEW, it -> it.withName(typed));
        stopRenaming();
    }

    /// A button large enough for a finger.
    private static UIForButton<JButton> big(UIForButton<JButton> button) {
        return button.withStyle(it -> it.padding(9, 16, 9, 16));
    }

    private UIForAnySwing<?, ?> startingAfresh() {
        Val<Boolean> idle = phase.viewAs(Boolean.class, it -> it != Genie.Phase.WORKING && !it.isBetween());
        return tile(
            SignSvgUtil.NEW,
            Palette::TEXT,
            words.viewAsString(Lang.ActionsPage::NEW_CONVERSATION),
            Viewable.of(String.class, name, words, (it, page) -> page.START_AFRESH_WITH_IT_THIS_CONVERSATION_IS_KEPT(it)),
            big(button(words.viewAsString(Lang.ActionsPage::START_A_NEW_CONVERSATION)).group(Skin.QUIET_BUTTON))
            .isEnabledIf(idle)
            .onClick(_ -> app.startAfresh(selected()))
        );
    }

    private UIForAnySwing<?, ?> saving() {
        return tile(
            SignSvgUtil.HISTORY,
            Palette::TEXT,
            words.viewAsString(Lang.ActionsPage::SAVE_NOW),
            Viewable.of(String.class, name, words, (it, page) -> page.SAVE_ITS_HOME_AS_IT_IS_NOW(it)),
            big(button(words.viewAsString(Lang.ActionsPage::SAVE_NOW_ELLIPSIS)).group(Skin.QUIET_BUTTON))
            .isEnabledIf(genie.viewAs(Boolean.class, it -> it.history().busy().isEmpty()))
            .onClick(_ -> saveNow.run())
        );
    }

    /// Sleep while the genie is awake, and wake while it is not, as the genie's menu has it.
    private UIForAnySwing<?, ?> sleepingOrWaking() {
        return tile(
            phase.viewAs(Boolean.class, Genie.Phase::isAwake),
            Palette::TEXT,
            Viewable.of(String.class, phase, words, (it, page) -> it.isAwake() ? page.SLEEP() : page.WAKE()),
            Viewable.of(String.class, genie, words, (it, page) -> switch (it.phase()) {
                case ASLEEP -> page.IS_IN_THE_LAMP_WAKING_IT_STARTS_ITS_SANDBOX(it.name());
                case WAKING -> page.IS_WAKING(it.name());
                case FALLING_ASLEEP -> page.IS_GOING_BACK_INTO_THE_LAMP_ITS_SANDBOX_IS_STOPPING(it.name());
                case BROKEN -> page.DID_NOT_WAKE_ITS_HOME_IS_KEPT(it.name());
                default -> it.fromElsewhere()
                    ? page.END_ITS_SANDBOX_FOR_WHOEVER_LIT_IT_TOO(it.name())
                    : page.END_ITS_SANDBOX(it.name());
            }),
            big(button(Viewable.of(String.class, phase, words, (it, page) -> switch (it) {
                case ASLEEP -> page.WAKE();
                case WAKING -> page.WAKING();
                case FALLING_ASLEEP -> page.FALLING_ASLEEP();
                case BROKEN -> page.TRY_WAKING_AGAIN();
                default -> page.SLEEP();
            })))
            .group(Skin.QUIET_BUTTON)
            .isEnabledIf(phase.viewAs(Boolean.class, it -> !it.isBetween()))
            .onClick(_ -> {
                if (phase.get().isAwake()) app.sleep(selected());
                else app.wake(selected());
            })
        );
    }

    private UIForAnySwing<?, ?> folders() {
        return tile(SignSvgUtil.FOLDER, Palette::TEXT, words.viewAsString(Lang.ActionsPage::FOLDERS), folders.says(), folders.view());
    }

    /// What `oillamp shell`, `oillamp view` and `oillamp follow` do at a terminal, each a button:
    /// small things, for those who want to see for themselves, so they share one tile. Only an
    /// awake genie has a sandbox to look into.
    private UIForAnySwing<?, ?> behindTheScenes() {
        Val<Boolean> awake = phase.viewAs(Boolean.class, Genie.Phase::isAwake);
        return tile(
            SignSvgUtil.TERMINAL,
            Palette::TEXT,
            words.viewAsString(Lang.ActionsPage::BEHIND_THE_SCENES),
            Viewable.of(String.class, genie, words, (it, page) ->
                it.phase() == Genie.Phase.ASLEEP || it.phase() == Genie.Phase.BROKEN
                    ? page.IS_IN_THE_LAMP_WAKE_IT_TO_LOOK_INTO_ITS_SANDBOX(it.name())
                    : page.LOOK_INTO_ITS_SANDBOX_YOURSELF_EACH_IN_A_WINDOW_OF_ITS_OWN(it.name())
            ),
            box("fillx, wrap 1, ins 0, gap 6, hidemode 3", "[0:0, grow]")
            .add("growx, wmin 0",
                // Side by side while there is room, and one under the other in a narrow tile.
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 8, 8)
                .withMinSize(0, 0)
                .peek(it -> it.addComponentListener(HomeFolders.relayoutAtNewWidth()))
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()).margin(-8, -8, -8, -8))
                .add(
                    opening(LampWindow.SHELL, words.viewAsString(Lang.ActionsPage::SHELL), awake)
                    .withTooltip(Viewable.of(String.class, selected, words, (_, page) ->
                        page.A_TERMINAL_WITH_A_SHELL_IN_ITS_SANDBOX(commandOf(LampWindow.SHELL))
                    ))
                )
                .add(
                    opening(LampWindow.DESKTOP, words.viewAsString(Lang.ActionsPage::DESKTOP), awake)
                    .withTooltip(Viewable.of(String.class, selected, words, (_, page) ->
                        page.ITS_DESKTOP_IN_A_VIEWER_WINDOW(commandOf(LampWindow.DESKTOP))
                    ))
                )
                .add(
                    opening(LampWindow.FOLLOWING, words.viewAsString(Lang.ActionsPage::FOLLOW), awake)
                    .withTooltip(Viewable.of(String.class, selected, words, (_, page) ->
                        page.A_TERMINAL_THAT_SAYS_WHAT_ITS_LAMP_REPORTS(commandOf(LampWindow.FOLLOWING))
                    ))
                )
            )
            .add("growx, wmin 0",
                ViewPartsUtil.wrapped(
                    theme,
                    Viewable.of(String.class, noWindow, words, (why, page) -> page.NO_WINDOW_OPENED(why)),
                    Palette::TROUBLE,
                    noWindow.viewAs(Boolean.class, why -> !why.isEmpty())
                )
            )
        );
    }

    /// The button that has the selected genie's lamp open `window`.
    private UIForButton<JButton> opening(LampWindow window, Val<String> called, Val<Boolean> awake) {
        return
            big(button(called).group(Skin.QUIET_BUTTON))
            .isEnabledIf(awake)
            .onClick(_ -> {
                UUID asked = selected();
                noWindow.set("");
                // Another genie chosen meanwhile: the reason is not for its tile.
                app.open(asked, window, why -> { if (asked.equals(selected())) noWindow.set(why); });
            });
    }

    /// The command that opens `window` onto the selected genie's sandbox at a terminal.
    private String commandOf(LampWindow window) {
        return window.command(app.lampOf(selected()).toString());
    }

    /// A lamp from elsewhere is not Genies' to delete, so it is only removed from Genies.
    private UIForAnySwing<?, ?> deleting() {
        return tile(
            SignSvgUtil.DELETE,
            Palette::TROUBLE,
            Viewable.of(String.class, genie, words, (it, page) -> it.fromElsewhere() ? page.REMOVE() : page.DELETE()),
            Viewable.of(String.class, genie, words, (it, page) ->
                it.fromElsewhere()
                    ? page.REMOVE_FROM_GENIES_ITS_LAMP_WAS_MADE_ELSEWHERE(it.name())
                    : page.DELETE_FOR_GOOD_YOU_ARE_ASKED_FIRST(it.name())
            ),
            big(
                button(Viewable.of(String.class, genie, words, (it, page) ->
                    it.fromElsewhere() ? page.REMOVE_FROM_GENIES_ELLIPSIS() : page.DELETE_GENIE_ELLIPSIS(it.name())
                ))
                .group(Skin.QUIET_BUTTON)
            )
            .withStyle(palette, (p, it) -> it.border(1, p.TROUBLE()).componentFont(f -> f.color(p.TROUBLE())))
            .onClick(_ -> delete.accept(selected()))
        );
    }

    // ─── small parts ───────────────────────────────────────────────────────────────────────

    private UUID selected() { return state.get().selected(); }

    /// A tile whose sign is that of sleep while `asleepSign` holds, and of waking otherwise.
    private UIForAnySwing<?, ?> tile(
        final Val<Boolean> asleepSign,
        final Function<Palette, Color> colour,
        final Val<String> title,
        final Val<String> says,
        final UIForAnySwing<?, ?> doing
    ) {
        return tile(
            label("").withStyle(Themed.of(asleepSign, theme), (sleep, it) ->
                it.icon(SignSvgUtil.sign(sleep.item() ? SignSvgUtil.SLEEP : SignSvgUtil.WAKE, colour.apply(sleep.palette())))
            ),
            title,
            says,
            doing
        );
    }

    private UIForAnySwing<?, ?> tile(
        final String sign,
        final Function<Palette, Color> colour,
        final Val<String> title,
        final Val<String> says,
        final UIForAnySwing<?, ?> doing
    ) {
        return tile(label("").withStyle(palette, (p, it) -> it.icon(SignSvgUtil.sign(sign, colour.apply(p)))), title, says, doing);
    }

    /// One action: its sign beside its title, a sentence on what it does, and its button, or
    /// whatever else does it.
    private UIForAnySwing<?, ?> tile(
        final UIForAnySwing<?, ?> sign,
        final Val<String> title,
        final Val<String> says,
        final UIForAnySwing<?, ?> doing
    ) {
        return
            // Stretched to the height of its row, a tile keeps what it says at the top.
            panel("fillx, ins 4 4 4 4, gap 6 12, hidemode 3", "[][grow]").group(Skin.TILE)
            .add("cell 0 0, top, gaptop 2", sign)
            .add("cell 1 0, growx, wmin 0",
                label(title)
                .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(14).weight(2f).color(now.palette().TEXT())))
            )
            .add("cell 1 1, growx, wmin 0", ViewPartsUtil.wrapped(theme, says, Palette::SUBTEXT, Val.of(true)))
            // A button keeps its own width; anything else, such as the name's field, takes the row.
            .add(doing instanceof UIForButton<?> ? "cell 1 2, gaptop 6" : "cell 1 2, growx, wmin 0, gaptop 6", doing);
    }
}
