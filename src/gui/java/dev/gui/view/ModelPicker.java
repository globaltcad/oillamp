package dev.gui.view;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import dev.gui.model.ModelSearch;
import dev.gui.model.Palette;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForBox;
import swingtree.components.JBox;
import swingtree.UIForLabel;

import static swingtree.UI.*;

/// A field for a model's name, which suggests the models that match what the user types, in a
/// list that floats under it ([ModelSearch]): the settings' one way to choose a model, for
/// Ollama and for any other model service alike.
///
/// The field holds the name itself, so a model the list does not know can be typed out whole.
/// The list opens as the user types, clicks into the field or presses the down arrow. The arrows
/// go through it, Return takes the model marked, a click takes the one clicked, and Escape, or
/// leaving the field, closes it. The field keeps the keyboard all along: the list cannot take it.
///
/// The list is declared once, and follows two properties: the suggestions, and which of them
/// is marked. Only showing it and hiding it are done by hand, as Swing shows a popup.
final class ModelPicker {

    /// What a suggestion says of its model: its name, a tag beside it, and a cell for each of
    /// the list's [Column]s after the first.
    ///
    /// @param model the model, as the service names it
    /// @param tag   a word beside the name, such as that the model is downloaded; empty for none
    /// @param cells what it says of the model in each column, such as its size
    record Row(String model, String tag, Tuple<Cell> cells) {
        static Row of(String model) { return new Row(model, "", Tuple.of(Cell.class)); }
    }

    /// What a suggestion says of its model in one column, in the colour of `tone`.
    record Cell(String text, Tone tone) {}

    /// A column of the list: what its header says, and how wide it is, in units. The first is
    /// the models' names, and takes the width the others leave.
    record Column(String title, int width) {}

    /// How well a suggested model suits, as its detail's colour says.
    enum Tone {
        GOOD, PLAIN, SLOW, TROUBLE;

        Color colour(Palette p) {
            return switch (this) {
                case GOOD -> p.CONTENT();
                case PLAIN -> p.SUBTEXT();
                case SLOW -> p.BRASS();
                case TROUBLE -> p.TROUBLE();
            };
        }
    }

    /// A suggestion at its place in the list.
    private record Item(int index, Row row) {}

    /// The most rows the list shows before it scrolls.
    private static final int ROWS_SHOWN = 8;

    /// The height of a row, in units.
    private static final int ROW_HEIGHT = 30;

    /// The height of the header, and of the hint, in units.
    private static final int HEADER_HEIGHT = 24;
    private static final int HINT_HEIGHT = 28;

    /// The space between the columns, and at either end of a row, in units.
    private static final int GAP = 8;
    private static final int INSET = 8;

    private final Val<Tuple<String>> offered;
    private final Val<Tuple<String>> variants;
    private final Function<String, Row> rowOf;
    private final Consumer<String> typing;
    private final Consumer<Tuple<String>> showing;
    /// The suggestions in the list, and which of them is marked; -1 for none.
    private final Var<Tuple<Item>> items = Var.of(Tuple.of(Item.class));
    private final Var<Integer> marked = Var.of(-1);
    /// Whether the user typed a tag, after which the hint how to find more is no use.
    private final Var<Boolean> tagTyped = Var.of(false);
    /// Whether there are more rows than the list shows at once, so that it scrolls.
    private final Var<Boolean> scrolls = Var.of(false);
    private Optional<JScrollPane> scrolled = Optional.empty();
    private Val<String> hint = Val.of("");
    private Val<Tuple<Column>> columns = Val.of(Tuple.of(Column.class));
    private Optional<JTextField> field = Optional.empty();
    private Optional<JPopupMenu> popup = Optional.empty();
    private Optional<JComponent> rows = Optional.empty();
    /// Whether the user typed since the list opened: until then it shows every model offered.
    private boolean filtering = false;
    /// Whether the picker is setting the field's text, which then is not taken as typed.
    private boolean picking = false;

    private ModelPicker(Val<Tuple<String>> offered, Val<Tuple<String>> variants, Function<String, Row> rowOf,
                        Consumer<String> typing, Consumer<Tuple<String>> showing) {
        this.offered = offered;
        this.variants = variants;
        this.rowOf = rowOf;
        this.typing = typing;
        this.showing = showing;
    }

    /// A field that edits `model`, suggesting models of `offered`, and of `variants` once the user
    /// types a tag. The list is as wide as the row the field is in, so as to span a button
    /// beside the field too.
    ///
    /// @param changed  changes whenever what `rowOf` says may have, which the list then shows anew
    /// @param rowOf    what the suggestion of a model says of it
    /// @param typing   told what the user types, as they type it
    /// @param showing  told which models the list shows, as it shows them
    /// @param hint     a line under the suggestions, such as how to find more; empty for none
    /// @param none     what the list says when no model matches
    /// @param columns  the columns of the list, which its header names: the name, then one for
    ///                 each of a row's cells; with the name alone, the list has no header
    /// @param enabled  whether the field can be edited
    static UIForAnySwing<?, ?> of(
        Val<Theme> theme,
        Var<String> model,
        Val<Tuple<String>> offered,
        Val<Tuple<String>> variants,
        Val<?> changed,
        Function<String, Row> rowOf,
        Consumer<String> typing,
        Consumer<Tuple<String>> showing,
        Val<String> hint,
        Val<String> none,
        Val<Tuple<Column>> columns,
        Val<Boolean> enabled
    ) {
        ModelPicker picker = new ModelPicker(offered, variants, rowOf, typing, showing);
        picker.hint = hint;
        picker.columns = columns;
        picker.popup = Optional.of(picker.list(theme, hint, none, columns));
        Viewable.cast(offered).onChange(From.ALL, _ -> picker.refreshIfOpen());
        Viewable.cast(variants).onChange(From.ALL, _ -> picker.refreshIfOpen());
        Viewable.cast(changed).onChange(From.ALL, _ -> picker.refreshIfOpen());
        return
            textField(model).group(Skin.INPUT)
            .isEnabledIf(enabled)
            .peek(it -> picker.field = Optional.of(it))
            .onTextChange(_ -> picker.typed())
            .onKeyPress(it -> picker.pressed(it.getEvent()))
            .onMouseClick(_ -> picker.open(false))
            .onFocusLoss(_ -> picker.close());
    }

    // ─── how it looks ──────────────────────────────────────────────────────────────────────

    /// The list, in a raised box like the field's: a row for each suggestion, or a line when none
    /// matches; under them, the header that names the columns, and the hint. The header is at
    /// the bottom, so that nothing stands between what the user types and the best suggestion.
    /// It never takes the keyboard.
    private JPopupMenu list(Val<Theme> theme, Val<String> hint, Val<String> none, Val<Tuple<Column>> columns) {
        Val<Boolean> noneMatch = items.viewAs(Boolean.class, Tuple::isEmpty);
        Val<Boolean> hinted = Viewable.of(Boolean.class, hint, tagTyped, (text, tag) -> !text.isBlank() && !tag);
        Val<Boolean> headed = Viewable.of(Boolean.class, columns, items, (them, found) -> them.size() > 1 && !found.isEmpty());
        JPopupMenu menu =
            UI.popupMenu()
            .withStyle(theme, (now, it) -> it.backgroundColor(now.palette().RAISED()).border(1, now.palette().BORDER()).padding(5))
            .withLayout("fill, wrap 1, ins 0, gap 0, hidemode 3", "[grow, fill]", "[grow][][]")
            .add("grow, wmin 0, hmin 0",
                scrollPane(conf -> conf.fitWidth(true))
                .withHorizontalScrollBarPolicy(UI.Active.NEVER)
                .withVerticalScrollIncrement(ROW_HEIGHT)
                .withEmptyBorder(0)
                .withStyle(theme, (now, it) -> it.backgroundColor(now.palette().RAISED()))
                .peek(it -> scrolled = Optional.of(it))
                .add(
                    box("wrap 1, fillx, ins 0, gap 0, hidemode 3", "[grow, fill]")
                    .withStyle(theme, (now, it) -> it.backgroundColor(now.palette().RAISED()))
                    // The rows alone in a box of their own, as SwingTree binds a box to a tuple.
                    .add("growx, wmin 0",
                        box("wrap 1, fillx, ins 0, gap 0", "[grow, fill]")
                        .withStyle(theme, (now, it) -> it.backgroundColor(now.palette().RAISED()))
                        .peek(it -> rows = Optional.of(it))
                        .addAll("growx, wmin 0, h " + ROW_HEIGHT + "!", items, item -> row(theme, item, columns.get()))
                    )
                    .add("growx, wmin 0", message(theme, none).isVisibleIf(noneMatch))
                )
            )
            .add("growx, wmin 0, h " + HEADER_HEIGHT + "!", header(theme, columns).isVisibleIf(headed))
            .add("growx, wmin 0, h " + HINT_HEIGHT + "!", message(theme, hint).isVisibleIf(hinted))
            .get(JPopupMenu.class);
        menu.setFocusable(false);
        return menu;
    }

    /// How the cells of a row, and of the header, are laid out: the name takes what is left of
    /// the width, and each other column its own, right-aligned.
    private static String cellsOf(Tuple<Column> columns) {
        StringBuilder cells = new StringBuilder("[grow, fill]");
        for (int i = 1; i < columns.size(); i++) cells.append("[").append(columns.get(i).width()).append("!, right]");
        return cells.toString();
    }

    /// The names of the columns, as a line across the bottom of the list. While the rows scroll,
    /// it keeps out of the way of their scroll bar, so its columns stay over theirs.
    private UIForBox<JBox> header(Val<Theme> theme, Val<Tuple<Column>> columns) {
        return
            box("fill, ins 0, gap 0")
            .withStyle(Themed.of(scrolls, theme), (themed, it) -> it
                .borderAt(UI.Edge.TOP, 1, themed.palette().BORDER())
                .padding(0, themed.item() ? scrollBarWidth() : 0, 0, 0)
            )
            .add("grow, wmin 0", columns, them -> {
                var line = box("fill, ins 0 " + INSET + " 0 " + INSET + ", gap " + GAP, cellsOf(them), "[center]");
                for (Column column : them) line = line.add("wmin 0", title(theme, column.title()));
                return line;
            });
    }

    /// The width of the list's scroll bar, in units.
    private int scrollBarWidth() {
        return scrolled.map(it -> UI.unscale(it.getVerticalScrollBar().getPreferredSize().width)).orElse(0);
    }

    /// The suggestion `item`: its model's name with its tag, and a cell for each further column,
    /// highlighted while it is marked, by the arrows or the mouse.
    private UIForAnySwing<?, ?> row(Val<Theme> theme, Item item, Tuple<Column> columns) {
        Row row = item.row();
        var line =
            box("fill, ins 0 " + INSET + " 0 " + INSET + ", gap " + GAP, cellsOf(columns), "[center]")
            .withStyle(Themed.of(marked.viewAs(Boolean.class, it -> it == item.index()), theme), (themed, it) -> it
                .backgroundColor(themed.item() ? themed.palette().CARD() : themed.palette().RAISED())
                .borderRadius(7)
                .cursor(UI.Cursor.HAND)
            )
            .onMouseEnter(_ -> marked.set(item.index()))
            .onMouseClick(_ -> pick(row.model()))
            .add("wmin 0",
                // The name whole; the tag beside it gives way first.
                box("ins 0, gap 8, hidemode 3", "[][shrink 200]", "[center]")
                .add(
                    label(row.model())
                    .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(13).color(now.palette().TEXT())))
                )
                .add("wmin 0",
                    label(row.tag())
                    .isVisibleIf(!row.tag().isEmpty())
                    .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(10).color(now.palette().CONTENT())))
                )
            );
        for (int i = 0; i + 1 < columns.size(); i++) {
            Cell cell = i < row.cells().size() ? row.cells().get(i) : new Cell("", Tone.PLAIN);
            line = line.add("wmin 0",
                label(cell.text())
                .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(11).color(cell.tone().colour(now.palette()))))
            );
        }
        return line;
    }

    /// The name of a column, in the header.
    private static UIForAnySwing<?, ?> title(Val<Theme> theme, String text) {
        return
            label(text)
            .withStyle(theme, (now, it) -> it.componentFont(f -> f.family(now.fonts().FONT()).size(10).weight(1.5f).color(now.palette().SUBTEXT())));
    }

    /// A line in the list that is no model, such as how to find more.
    private static UIForLabel<JLabel> message(Val<Theme> theme, Val<String> text) {
        return
            label(text)
            .withStyle(theme, (now, it) -> it.padding(6, 8, 6, 8).componentFont(f -> f.family(now.fonts().FONT()).size(11).color(now.palette().SUBTEXT())));
    }

    // ─── what the user does ────────────────────────────────────────────────────────────────

    private void typed() {
        if (picking) return;
        field.ifPresent(it -> typing.accept(it.getText()));
        open(true);
    }

    private void pressed(KeyEvent key) {
        boolean shown = isOpen();
        int last = items.get().size() - 1;
        switch (key.getKeyCode()) {
            case KeyEvent.VK_DOWN -> {
                key.consume();
                if (shown) mark(Math.min(last, marked.get() + 1));
                else open(false);
            }
            case KeyEvent.VK_UP -> {
                if (!shown) return;
                key.consume();
                mark(Math.max(0, marked.get() - 1));
            }
            case KeyEvent.VK_ENTER -> {
                if (!shown) return;
                key.consume();
                int at = marked.get();
                if (at >= 0 && at <= last) pick(items.get().get(at).row().model());
                else close();
            }
            case KeyEvent.VK_ESCAPE -> {
                if (!shown) return;
                key.consume();
                close();
            }
            default -> {}
        }
    }

    /// Opens the list, or shows it anew while it is open: filtered by what the user typed, or,
    /// opened without typing, with every model offered and the one in the field marked.
    private void open(boolean typed) {
        if (typed) filtering = true;
        else if (!isOpen()) filtering = false;
        refresh(false);
    }

    /// Shows the list anew, as what its rows say changed, such as when Genies learned what a
    /// model is made of. The model marked stays marked, wherever it is now, and the list does
    /// not scroll: the user may be going through it.
    private void refreshIfOpen() {
        if (isOpen()) refresh(true);
    }

    /// Shows the list for what the field holds. With `keep`, the model marked stays marked;
    /// otherwise the best match is, or, opened without typing, the model in the field.
    private void refresh(boolean keep) {
        if (field.isEmpty() || popup.isEmpty()) return;
        JTextField at = field.get();
        // Only for a field that is on the screen and has the keyboard: otherwise the list would
        // float where nobody types.
        if (!at.isShowing() || !at.isEnabled() || !at.hasFocus()) {
            close();
            return;
        }
        String text = at.getText();
        Tuple<String> matches = ModelSearch.matching(filtering ? text : "", offered.get(), variants.get());
        Tuple<Item> found = Tuple.of(Item.class);
        int current = -1;
        for (int i = 0; i < matches.size(); i++) {
            found = found.add(new Item(i, rowOf.apply(matches.get(i))));
            if (matches.get(i).equalsIgnoreCase(text.strip())) current = i;
        }
        int before = marked.get();
        Optional<String> wasMarked = before >= 0 && before < items.get().size() ? Optional.of(items.get().get(before).row().model()) : Optional.empty();
        int kept = wasMarked.map(model -> {
            for (int i = 0; i < matches.size(); i++) if (matches.get(i).equalsIgnoreCase(model)) return i;
            return -1;
        }).orElse(-1);
        items.set(found);
        tagTyped.set(text.contains(":"));
        scrolls.set(matches.size() > ROWS_SHOWN);
        marked.set(keep && kept >= 0 ? kept : filtering ? (matches.isEmpty() ? -1 : 0) : current);
        JPopupMenu list = popup.get();
        // As high as its rows, up to as many as it shows at once, with the header and the hint
        // if they are shown, and its padding and border; as wide as the row the field is in.
        boolean hinted = !hint.get().isBlank() && !text.contains(":");
        boolean headed = columns.get().size() > 1 && !matches.isEmpty();
        int height = Math.min(ROWS_SHOWN, Math.max(1, matches.size())) * ROW_HEIGHT
                   + (headed ? HEADER_HEIGHT : 0) + (hinted ? HINT_HEIGHT : 0) + 12;
        JComponent span = at.getParent() instanceof JComponent row ? row : at;
        int from = span == at ? 0 : -at.getX();
        list.setPopupSize(span.getWidth(), UI.scale(height));
        if (!list.isVisible()) list.show(at, from, at.getHeight() + UI.scale(4));
        list.revalidate();
        list.repaint();
        if (!keep) mark(marked.get());
    }

    /// Marks the suggestion at `index`, scrolls the list to it, and tells which are in view.
    private void mark(int index) {
        Tuple<Item> all = items.get();
        if (index >= 0 && index < all.size()) {
            marked.set(index);
            rows.ifPresent(list -> SwingUtilities.invokeLater(() -> {
                if (index < list.getComponentCount() && list.getComponent(index) instanceof JComponent row)
                    row.scrollRectToVisible(new Rectangle(0, 0, row.getWidth(), row.getHeight()));
            }));
        }
        int top = Math.max(0, Math.min(index - ROWS_SHOWN / 2, all.size() - ROWS_SHOWN));
        showing.accept(all.slice(top, Math.min(all.size(), top + ROWS_SHOWN)).mapTo(String.class, it -> it.row().model()));
    }

    private void pick(String model) {
        field.ifPresent(it -> {
            picking = true;
            try {
                it.setText(model);
            } finally {
                picking = false;
            }
            typing.accept(model);
        });
        close();
    }

    private void close() {
        popup.ifPresent(it -> it.setVisible(false));
        filtering = false;
    }

    private boolean isOpen() {
        return popup.map(JPopupMenu::isVisible).orElse(false);
    }
}
