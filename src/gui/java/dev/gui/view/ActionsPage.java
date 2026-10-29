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

import static swingtree.UI.*;

/// What can be done with a genie as a whole: renaming it, starting a new conversation with it,
/// saving its home, sending it to sleep or waking it, linking its folders into the user's, and
/// deleting it. Each is a tile with its
/// sign, a sentence on what it does, and a button large enough for a finger.
///
/// A right-click on the genie's card offers the same as a menu, for those with a mouse. This page
/// is the way for everyone else, and the only one in a narrow window, whose header has no room
/// for a Sleep button.
///
/// Like the rest of the window, a function of the [GeniesState], but for one thing of its own: a
/// name the user is typing, which is no genie's until they keep it. The genie's name stands in a
/// field that becomes editable on Rename; Enter or Save keeps what was typed, Escape or Cancel the
/// name it had. Saving and deleting ask the user first, in the same dialogs as everywhere else,
/// so the window lends them.
final class ActionsPage {

    /*
     *  A responsive grid of tiles: two side by side from LARGE up, one under the other below.
     *  The genie's folders follow, the whole width, as a tree needs it, with a grid of its own
     *  inside (see HomeFolders). Deleting the genie comes last, the whole width, away from the rest.
     *
     *                 very small  small  medium  large  very large  oversize
     *      on top          12       12     12     12       12          12
     *      a tile          12       12     12      6        6           6
     *      the folders     12       12     12     12       12          12
     *      deleting        12       12     12     12       12          12
     */
    private static final int REFERENCE = 900;
    private static final FlowCell WHOLE = AUTO_SPAN(it ->
        it.verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(12)
    );
    private static final FlowCell TILE = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP).fill(true)
        .verySmall(12).small(12).medium(12).large(6).veryLarge(6).oversize(6)
    );

    private final Var<GeniesState> state;
    /// The colours and the fonts of the state, and the colours alone.
    private final Val<Theme> theme;
    private final Val<Palette> palette;
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

    /// @param saveNow asks the user what to remember the selected genie's home by, and saves it
    /// @param delete  asks the user whether to delete a genie for good, and does so
    ActionsPage(Var<GeniesState> state, Genies app, Runnable saveNow, Consumer<UUID> delete) {
        this.state = state;
        theme = Theme.of(state);
        palette = Theme.paletteOf(state);
        this.app = app;
        this.saveNow = saveNow;
        this.delete = delete;
        genie = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        phase = genie.viewAs(Genie.Phase.class, Genie::phase);
        name = genie.viewAsString(Genie::name);
        selected = state.viewAs(UUID.class, GeniesState::selected);
        newName = Var.of(name.get());
        folders = new HomeFolders(state, app);
        // Renamed elsewhere, the field follows, unless the user is typing in it.
        Viewable.cast(name).onChange(From.ALL, it -> { if (!renaming.get()) newName.set(name.get()); });
        // Another genie chosen: what was typed was meant for the one before.
        Viewable.cast(selected).onChange(From.ALL, _ -> stopRenaming());
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
                .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()).padding(20, 4, 28, 4))
                .add(WHOLE, top())
                .add(TILE, renaming())
                .add(TILE, startingAfresh())
                .add(TILE, saving())
                .add(TILE, sleepingOrWaking())
                .add(WHOLE, folders())
                .add(WHOLE, deleting())
            );
    }

    private UIForAnySwing<?, ?> top() {
        return
            box("fill, wrap 1, ins 0, gap 8", "[grow]")
            .add(label("Actions").group(Skin.EMPTY_TITLE))
            .add("growx, wmin 0",
                ViewPartsUtil.wrapped(
                    theme,
                    genie.viewAsString(it ->
                        "What can be done with " + it.name()
                        + " as a whole. A right-click on its card in your list offers the same."
                    ),
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
            Val.of("Name"),
            renaming.viewAsString(on ->
                on ? "Enter keeps the new name, Escape the one it had." : "What it is called in your list and above its chat."
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
                big(button("Rename").group(Skin.QUIET_BUTTON))
                .isVisibleIfNot(renaming)
                .onClick(_ -> startRenaming())
            )
            .add(
                big(button("Cancel").group(Skin.QUIET_BUTTON))
                .isVisibleIf(renaming)
                .onClick(_ -> stopRenaming())
            )
            .add(
                big(button("Save").group(Skin.FLAME_BUTTON))
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
        Val<Boolean> idle = phase.viewAs(Boolean.class, it -> it != Genie.Phase.WORKING && it != Genie.Phase.WAKING);
        return tile(
            SignSvgUtil.NEW,
            Palette::TEXT,
            Val.of("New conversation"),
            genie.viewAsString(it ->
                "Start afresh with " + it.name() + ", waking it first if it sleeps. "
                + "This conversation is kept, to go back to."
            ),
            big(button("Start a new conversation").group(Skin.QUIET_BUTTON))
            .isEnabledIf(idle)
            .onClick(_ -> app.startAfresh(selected()))
        );
    }

    private UIForAnySwing<?, ?> saving() {
        return tile(
            SignSvgUtil.HISTORY,
            Palette::TEXT,
            Val.of("Save now"),
            genie.viewAsString(it -> "Save " + it.name() + "'s home as it is now: a moment in its history to go back to."),
            big(button("Save now…").group(Skin.QUIET_BUTTON))
            .isEnabledIf(genie.viewAs(Boolean.class, it -> it.history().busy().isEmpty()))
            .onClick(_ -> saveNow.run())
        );
    }

    /// Sleep while the genie is awake, and wake while it is not, as the genie's menu has it.
    private UIForAnySwing<?, ?> sleepingOrWaking() {
        return tile(
            phase.viewAs(Boolean.class, Genie.Phase::isAwake),
            Palette::TEXT,
            phase.viewAsString(it -> it.isAwake() ? "Sleep" : "Wake"),
            genie.viewAsString(it -> switch (it.phase()) {
                case ASLEEP -> it.name() + " is in the lamp. Waking it starts its sandbox, with its home as it left it.";
                case WAKING -> it.name() + " is waking…";
                case BROKEN -> it.name() + " did not wake. Its home is kept, so it can try again.";
                default -> it.fromElsewhere()
                    ? "End " + it.name() + "'s sandbox, for whoever lit it with `oillamp at` too. Its home and this conversation are kept."
                    : "End " + it.name() + "'s sandbox. Its home and this conversation are kept.";
            }),
            big(button(phase.viewAsString(it -> switch (it) {
                case ASLEEP -> "Wake";
                case WAKING -> "Waking…";
                case BROKEN -> "Try waking again";
                default -> "Sleep";
            })))
            .group(Skin.QUIET_BUTTON)
            .isEnabledIf(phase.viewAs(Boolean.class, it -> it != Genie.Phase.WAKING))
            .onClick(_ -> {
                if (phase.get().isAwake()) app.sleep(selected());
                else app.wake(selected());
            })
        );
    }

    private UIForAnySwing<?, ?> folders() {
        return tile(SignSvgUtil.FOLDER, Palette::TEXT, Val.of("Folders"), folders.says(), folders.view());
    }

    /// A lamp from elsewhere is not Genies' to delete, so it is only removed from Genies.
    private UIForAnySwing<?, ?> deleting() {
        return tile(
            SignSvgUtil.DELETE,
            Palette::TROUBLE,
            genie.viewAsString(it -> it.fromElsewhere() ? "Remove" : "Delete"),
            genie.viewAsString(it ->
                it.fromElsewhere()
                    ? "Remove " + it.name() + " from Genies. Its lamp was made elsewhere, with `oillamp at`, and stays "
                      + "where it is, as it is, running if it runs. You are asked first."
                    : "Delete " + it.name() + " for good: its home, everything it made and your "
                      + "conversations with it. You are asked first."
            ),
            big(
                button(genie.viewAsString(it -> it.fromElsewhere() ? "Remove from Genies…" : "Delete " + it.name() + "…"))
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
            panel("fill, ins 4 4 4 4, gap 6 12, hidemode 3", "[][grow]").group(Skin.TILE)
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
