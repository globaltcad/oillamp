package dev.gui.view;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import javax.swing.AbstractAction;
import javax.swing.BoundedRangeModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dev.gui.model.ChatSearch;
import dev.gui.model.Entry;
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
import swingtree.UIForButton;
import swingtree.animation.Animation;
import swingtree.animation.AnimationStatus;
import swingtree.animation.LifeTime;
import swingtree.api.Configurator;
import swingtree.components.JBox;
import swingtree.style.FontConf;
import swingtree.style.StyledString;

import static swingtree.UI.*;

/// Ctrl+F for the chat: a magnifying glass that floats at the top right of the conversation,
/// over it rather than in its layout, and opens into a field. What the user types there is found
/// in the conversation as the chat shows it. Every place found is marked, the one in focus apart
/// from the others, and the chat glides to put that one in its middle. Return and Shift+Return,
/// or the arrows beside the field, go from one to the next; the count beside them says where
/// the user is, such as "2 / 4".
///
/// The search itself is a [ChatSearch], in the model. What is here is how the chat shows it. It
/// is a stopgap, and kept in this one class so that it can be replaced as a whole:
///
/// - SwingTree 1.1.2 paints one selection in a text at most, and does not tell where a character
///   of its text lands. So the places found are not painted over the text: each row cuts its
///   [StyledPiece]s at them and gives those parts a background of their own ([#marked]). Marked
///   that way, they have square corners, unlike SwingTree's selection.
/// - For the same reason, the chat cannot know which line a match is on. [#reveal] guesses it
///   from how far into its text the match is, as if the text were evenly spread over its height.
///   A match in a long answer with short lines, headings or code may end up somewhat above or
///   below the middle of the chat, though it is on screen.
///
/// Once SwingTree can highlight several ranges of a text, or tell where a range of it is, both
/// belong in its text styles, and this class gets much simpler.
final class SearchOverlay {

    /// The client property, on the component that paints an entry's text, that holds the entry's
    /// id: how the search finds where a match is shown. [ChatRows] puts it there.
    static final String SHOWS_ENTRY = "genies.search.shows-entry";

    /// The longest text in the clipboard that the search takes as what to find when it opens, in
    /// characters: a word or a phrase, not a whole answer the user copied.
    private static final int CLIPBOARD_AT_MOST = 100;

    /// How far the bar floats from the top and right edges of the chat's island, in the window's units.
    private static final int MARGIN = 8;

    /// How long the box takes to unfold into the bar, or to fold back.
    private static final LifeTime UNFOLD = LifeTime.of(0.25, TimeUnit.SECONDS);

    /// The places found in the text of one entry, in their order, and the index among them of
    /// the one in focus, or -1 when that is in another entry or there is none.
    record Marks(Tuple<ChatSearch.Match> matches, int focused) {
        static final Marks NONE = new Marks(Tuple.of(ChatSearch.Match.class), -1);
    }

    /// The text the chat shows for an entry, kept with the entry and language it was made from,
    /// so that an answer's Markdown is parsed again only once it changed.
    private record Shown(Entry entry, Lang lang, String text) {}

    private record Grouped(ChatSearch search, Map<UUID, Tuple<ChatSearch.Match>> byEntry) {}

    private final Val<Theme> theme;
    private final Val<Palette> palette;
    private final Val<Lang> lang;
    private final Val<Lang.Chat> words;
    private final Val<Tuple<Entry>> entries;
    private final Var<ChatSearch> search = Var.of(ChatSearch.NONE);
    /// The search with its matches grouped by entry once, for the rows to look theirs up in.
    private final Val<Grouped> grouped = search.viewAs(Grouped.class, it -> new Grouped(it, it.byEntry()));
    private final Var<Boolean> open = Var.of(false);
    /// Whether the field, the count and the arrows are shown: from when the search opens until
    /// it has folded away again after it closed.
    private final Var<Boolean> partsShown = Var.of(false);
    /// How far the box is unfolded: 0 with only the magnifying glass in it, 1 with all of the bar.
    private double unfolded = 0;
    /// Counts the unfoldings started; one moves the box only while it is the last one started.
    private int unfoldings = 0;
    private Optional<Stack> stack = Optional.empty();
    private Map<UUID, Shown> shown = Map.of();
    private Optional<JScrollPane> chat = Optional.empty();
    private Optional<FollowTheEnd> follow = Optional.empty();
    private Optional<JTextField> field = Optional.empty();
    /// The box the bar is in, which the keyboard leaves when the search closes.
    private Optional<JComponent> box = Optional.empty();
    /// What had the keyboard before the search opened, which gets it back when it closes.
    private Optional<Component> focusedBefore = Optional.empty();
    /// Whether the search is setting the field's text, which then is not searched for as typed.
    private boolean settingField = false;

    /// @param theme   the colours and the fonts of the state
    /// @param lang    the words of the state, which notices in the chat are worded in
    /// @param entries the entries of the conversation the chat shows, in their order
    SearchOverlay(Val<Theme> theme, Val<Lang> lang, Val<Tuple<Entry>> entries) {
        this.theme = theme;
        this.palette = theme.viewAs(Palette.class, Theme::palette);
        this.lang = lang;
        this.words = lang.viewAs(Lang.Chat.class, Lang::CHAT);
        this.entries = entries;
        // While an answer streams in, or the user unfolds a thought, the texts change; the search
        // follows them without moving the chat.
        Viewable.cast(entries).onChange(From.ALL, _ -> searchAgain());
        Viewable.cast(lang).onChange(From.ALL, _ -> searchAgain());
    }

    // ─── what the rows need ────────────────────────────────────────────────────────────────

    /// The places found in the text of `entry`, following the search.
    Val<Marks> marksOf(UUID entry) {
        return grouped.viewAs(Marks.class, it -> {
            Tuple<ChatSearch.Match> matches = it.byEntry().getOrDefault(entry, Marks.NONE.matches());
            if (matches.isEmpty()) return Marks.NONE;
            return new Marks(matches, it.search().focused().filter(f -> f.entry().equals(entry)).map(matches.toList()::indexOf).orElse(-1));
        });
    }

    /// `pieces`, as SwingTree paints them, the places in `marks` given a background: the flame
    /// behind the one in focus, with the words on it in the colour words on the flame have, and a
    /// thin brass behind the others. `marks` count characters from the start of the first piece.
    static Tuple<StyledString> marked(Tuple<StyledPiece> pieces, Marks marks, Palette p) {
        Tuple<StyledString> out = Tuple.of(StyledString.class);
        Tuple<ChatSearch.Match> matches = marks.matches();
        Configurator<FontConf> found = f -> f.backgroundColor(ViewPartsUtil.withAlpha(p.BRASS(), p.isDark() ? 96 : 72));
        Configurator<FontConf> focused = f -> f.backgroundColor(p.FLAME()).color(p.ON_FLAME());
        int position = 0;
        // The first match that does not end before the character looked at: matches are in order,
        // and do not overlap.
        int next = 0;
        for (StyledPiece piece : pieces) {
            String text = piece.text();
            int from = 0;
            while (from < text.length()) {
                int at = position + from;
                while (next < matches.size() && matches.get(next).end() <= at) next++;
                int to = text.length();
                Optional<Configurator<FontConf>> mark = Optional.empty();
                if (next < matches.size()) {
                    ChatSearch.Match match = matches.get(next);
                    if (match.start() <= at) {
                        to = Math.min(to, match.end() - position);
                        mark = Optional.of(next == marks.focused() ? focused : found);
                    } else {
                        to = Math.min(to, match.start() - position);
                    }
                }
                String part = text.substring(from, to);
                out = out.add(mark.map(it -> StyledString.of(piece.look().andThen(it), part)).orElseGet(() -> StyledString.of(piece.look(), part)));
                from = to;
            }
            position += text.length();
        }
        return out;
    }

    // ─── the chat with the search over it ──────────────────────────────────────────────────

    /// `chat`, the conversation's scroll pane, with the search floating over its top right.
    /// `follow` keeps the chat's scroll position, which the search moves. `sea`, in the window's
    /// units, is how far the chat reaches beyond its island, which the bar keeps its distance from.
    UIForBox<Stack> around(JScrollPane chat, FollowTheEnd follow, Val<Insets> sea) {
        this.chat = Optional.of(chat);
        this.follow = Optional.of(follow);
        JComponent bar = bar().get(JBox.class);
        // The rounded box the bar is shown in, as much of it as is unfolded: the bar slides out of
        // it to the left, at its full size, rather than growing.
        JComponent box =
            UI.of(new JBox(new Unfolding()))
            // Padded, rather than spaced by the bar's own layout: the box must know how wide the
            // magnifying glass is with all around it, to be a square round it while folded.
            .withStyle(palette, (p, it) -> it.backgroundColor(p.RAISED()).border(1, p.BORDER()).borderRadius(9).padding(1))
            .get(JBox.class);
        box.add(bar);
        this.box = Optional.of(box);
        // The box hides the chat under it, but not from the mouse wheel.
        box.addMouseWheelListener(event -> chat.dispatchEvent(SwingUtilities.convertMouseEvent(box, event, chat)));
        Stack stack = new Stack(box, bar, chat, () -> unfolded, sea::get);
        this.stack = Optional.of(stack);
        Viewable.cast(sea).onChange(From.ALL, _ -> stack.revalidate());
        // Wherever the keyboard is in the window, as long as the chat is shown; a component that
        // takes the keys itself, such as the genie's desktop, keeps them.
        stack.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "find");
        stack.getActionMap().put("find", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { open(); }
        });
        // Escape closes the search wherever the keyboard is in the window, such as in an answer
        // the user clicked into. Only while it is open: otherwise Escape is left to others.
        AbstractAction closing = new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { close(); }
        };
        closing.setEnabled(false);
        Viewable.cast(open).onChange(From.ALL, it -> closing.setEnabled(it.currentValue().orElse(false)));
        stack.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close the search");
        stack.getActionMap().put("close the search", closing);
        return UI.of(stack).withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()));
    }

    /// The magnifying glass in a rounded box, and, while the search is open, the field, the count
    /// and the arrows in the same box before it:
    ///
    ///     ┌──────────────────────────────────┐
    ///     │ [build              ]  2 / 4  ˄ ˅ ⌕ │
    ///     └──────────────────────────────────┘
    private UIForBox<JBox> bar() {
        Val<Boolean> anyFound = search.viewAs(Boolean.class, it -> !it.matches().isEmpty());
        Val<Boolean> noneFound = search.viewAs(Boolean.class, it -> it.matches().isEmpty() && !it.query().isBlank());
        return
            box("ins 0, gap 0, hidemode 3, aligny center")
            .withStyle(palette, (p, it) -> it.backgroundColor(p.TRANSPARENT()))
            // Where the chat is narrow, the field gives up its width, and the rest keeps theirs.
            .add("w 0:180:180, gapright 2", field())
            .add("wmin 40, gapright 2",
                label(search.viewAsString(it -> it.position() + " / " + it.matches().size()))
                .withHorizontalAlignment(UI.HorizontalAlignment.CENTER)
                .isVisibleIf(partsShown)
                // Red when nothing was found for what the user typed.
                .withStyle(Themed.of(noneFound, theme), (themed, it) -> it.componentFont(f -> f
                    .family(themed.fonts().FONT()).size(11)
                    .color(themed.item() ? themed.palette().TROUBLE() : themed.palette().SUBTEXT())
                ))
            )
            .add(
                arrow(SignSvgUtil.FOLD, anyFound)
                .withTooltip(words.viewAsString(Lang.Chat::PREVIOUS_MATCH_SHIFT_AND_RETURN))
                .onClick(_ -> step(ChatSearch::previous))
            )
            .add(
                arrow(SignSvgUtil.UNFOLD, anyFound)
                .withTooltip(words.viewAsString(Lang.Chat::NEXT_MATCH_RETURN))
                .onClick(_ -> step(ChatSearch::next))
            )
            .add(
                button("").group(Skin.ICON_BUTTON)
                .withTooltip(Viewable.of(String.class, open, words, (it, chat) ->
                    it ? chat.CLOSE_THE_SEARCH_ESCAPE() : chat.FIND_IN_THE_CONVERSATION_CTRL_F()
                ))
                // Lit with brass while the search is open, as the bell is while it shows the notifications.
                .withStyle(Themed.of(open, theme), (themed, it) -> it
                    .prefSize(24, 24).minSize(24, 24).maxSize(24, 24)
                    .padding(0)
                    .borderRadius(7)
                    .icon(SignSvgUtil.sign(SignSvgUtil.SEARCH, themed.item() ? themed.palette().BRASS() : themed.palette().SUBTEXT()))
                )
                // Clicked, it leaves the keyboard where it was, to give it back there on closing.
                .peek(it -> it.setFocusable(false))
                .onClick(_ -> {
                    if (open.get()) close();
                    else open();
                })
            );
    }

    /// Where the user types what to find. Return goes to the next match, Shift and Return to the
    /// one before, and Escape closes the search.
    private UIForAnySwing<?, ?> field() {
        return
            textField("").group(Skin.INPUT)
            .isVisibleIf(partsShown)
            .withTooltip(words.viewAsString(Lang.Chat::WHAT_TO_FIND_IN_THE_CONVERSATION))
            // The bar is in a box; the field in it needs no second one.
            .withStyle(theme, (now, it) -> it
                .backgroundColor(now.palette().TRANSPARENT()).border(0, now.palette().TRANSPARENT()).padding(2, 6, 2, 6)
                .componentFont(f -> f.family(now.fonts().FONT()).size(12).color(now.palette().TEXT()))
            )
            .onTextChange(it -> {
                if (!settingField) typed(it.getComponent().getText());
            })
            .onKeyPress(it -> {
                KeyEvent key = it.getEvent();
                if (key.getKeyCode() == KeyEvent.VK_ENTER) {
                    key.consume();
                    step(key.isShiftDown() ? ChatSearch::previous : ChatSearch::next);
                } else if (key.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    key.consume();
                    close();
                }
            })
            .peek(it -> field = Optional.of(it));
    }

    /// A small button with `sign` on it, to go from match to match, shown while the search is open.
    private UIForButton<JButton> arrow(String sign, Val<Boolean> enabled) {
        return
            button("").group(Skin.ICON_BUTTON)
            .isVisibleIf(partsShown)
            .isEnabledIf(enabled)
            // Clicked, it leaves the keyboard in the field.
            .peek(it -> it.setFocusable(false))
            .withStyle(palette, (p, it) -> it
                .prefSize(20, 24).minSize(20, 24)
                .padding(0)
                .borderRadius(7)
                .icon(SignSvgUtil.sign(sign, p.SUBTEXT()))
            );
    }

    // ─── what the user does ────────────────────────────────────────────────────────────────

    /// Opens the search, or, while it is open, puts the keyboard in its field. A word or a phrase
    /// in the clipboard is what it opens with; anything else, and it opens with what the user
    /// looked for last. That is searched for at once from the top of what the chat shows, and
    /// selected, so that typing replaces it.
    private void open() {
        if (!open.get()) {
            focusedBefore = Optional.ofNullable(KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner())
                .filter(it -> box.map(inside -> !SwingUtilities.isDescendingFrom(it, inside)).orElse(true));
            open.set(From.VIEW, true);
            partsShown.set(From.VIEW, true);
            unfold(1);
            String query = clipboardWords().orElseGet(() -> field.map(JTextField::getText).orElse(""));
            field.ifPresent(it -> {
                settingField = true;
                it.setText(query);
                settingField = false;
            });
            search.set(From.VIEW, ChatSearch.find(query, texts(), shownSpot()));
            reveal();
        }
        field.ifPresent(it -> SwingUtilities.invokeLater(() -> {
            it.selectAll();
            it.requestFocusInWindow();
        }));
    }

    /// Closes the search and takes its marks off the chat. What the user typed stays in the field,
    /// for the next time. The keyboard, if in the field, goes back to where it was before the
    /// search opened, rather than to wherever Swing puts it once the field is hidden.
    private void close() {
        open.set(From.VIEW, false);
        search.set(From.VIEW, ChatSearch.NONE);
        unfold(0);
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner != null && box.filter(it -> SwingUtilities.isDescendingFrom(owner, it)).isPresent())
            focusedBefore.filter(Component::isShowing).ifPresent(Component::requestFocusInWindow);
        focusedBefore = Optional.empty();
    }

    /// Unfolds the box into the bar, to `to` 1, or folds it back, to 0, from where it is, easing in
    /// and out. Folded back, the bar's field, count and arrows are hidden.
    private void unfold(double to) {
        int unfolding = ++unfoldings;
        double from = unfolded;
        UI.animateFor(UNFOLD).go(new Animation() {
            @Override public void run(AnimationStatus status) {
                if (unfolding == unfoldings) moveTo(from + (to - from) * status.fadeIn());
            }

            @Override public void finish(AnimationStatus status) {
                if (unfolding != unfoldings) return;
                moveTo(to);
                if (to == 0) partsShown.set(From.VIEW, false);
            }
        });
    }

    private void moveTo(double unfolded) {
        this.unfolded = unfolded;
        // Only the box moves, within the stack: the layout around the stack stays as it is.
        stack.ifPresent(it -> {
            it.doLayout();
            it.repaint();
        });
    }

    private void typed(String query) {
        // Closed, though the field is still shown while it folds away.
        if (!open.get()) return;
        search.set(From.VIEW, search.get().typed(query, texts(), shownSpot()));
        reveal();
    }

    private void step(UnaryOperator<ChatSearch> how) {
        if (!open.get()) return;
        search.set(From.VIEW, how.apply(search.get()));
        reveal();
    }

    /// Searches the conversation again, after it changed, keeping the match in focus. The chat
    /// stays where it is: it moves only for what the user does.
    private void searchAgain() {
        if (open.get() && !search.get().query().isBlank())
            search.set(From.VIEW, search.get().in(texts()));
    }

    /// The text in the clipboard, if it is a word or a phrase: one line, not blank and not longer
    /// than [#CLIPBOARD_AT_MOST], without the spaces around it.
    private static Optional<String> clipboardWords() {
        try {
            if (Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor) instanceof String text) {
                String words = text.strip();
                if (!words.isEmpty() && words.length() <= CLIPBOARD_AT_MOST && words.lines().count() == 1)
                    return Optional.of(words);
            }
        } catch (UnsupportedFlavorException | IOException | IllegalStateException _) {
            // No text in the clipboard, or another application holds it: the search opens without.
        }
        return Optional.empty();
    }

    // ─── where things are in the chat ──────────────────────────────────────────────────────

    /// The text of each entry of the conversation as the chat shows it, in their order.
    private Tuple<ChatSearch.Text> texts() {
        Lang now = lang.get();
        Map<UUID, Shown> kept = new HashMap<>();
        List<ChatSearch.Text> texts = new ArrayList<>();
        for (Entry entry : entries.get()) {
            Shown before = shown.get(entry.id());
            Shown text = before != null && before.lang() == now && before.entry().equals(entry)
                ? before
                : new Shown(entry, now, ChatRows.searchedText(entry, now));
            kept.put(entry.id(), text);
            texts.add(new ChatSearch.Text(entry.id(), text.text()));
        }
        shown = kept;
        return Tuple.of(ChatSearch.Text.class, texts);
    }

    /// Glides the chat to the match in focus, so that it is in the middle of the chat, or as near
    /// as the chat can scroll. Which line of its text the match is on is a guess: see the class's
    /// description.
    private void reveal() {
        search.get().focused().ifPresent(match -> chat.ifPresent(pane -> follow.ifPresent(scroll -> {
            if (!(pane.getViewport().getView() instanceof JComponent view)) return;
            textsShown(view).stream()
                .filter(it -> match.entry().equals(it.getClientProperty(SHOWS_ENTRY)))
                .findFirst()
                .ifPresent(text -> {
                    Shown entry = shown.get(match.entry());
                    int length = Math.max(1, entry == null ? 1 : entry.text().length());
                    double along = Math.min(1, (match.start() + match.end()) / 2.0 / length);
                    Insets insets = text.getInsets();
                    int height = Math.max(0, text.getHeight() - insets.top - insets.bottom);
                    Point at = SwingUtilities.convertPoint(text, 0, insets.top + (int) Math.round(height * along), view);
                    BoundedRangeModel range = pane.getVerticalScrollBar().getModel();
                    int top = at.y - range.getExtent() / 2;
                    scroll.glideTo(Math.max(range.getMinimum(), Math.min(range.getMaximum() - range.getExtent(), top)));
                });
        })));
    }

    /// Where the chat is, as a place to search from: the first text that reaches into what the
    /// chat shows, from as far into it as is scrolled past its top. The start of the conversation
    /// while the chat is not shown.
    private ChatSearch.Spot shownSpot() {
        return chat.flatMap(pane -> {
            if (!(pane.getViewport().getView() instanceof JComponent view)) return Optional.empty();
            Rectangle visible = pane.getViewport().getViewRect();
            Optional<JComponent> first = Optional.empty();
            Rectangle firstBounds = new Rectangle();
            for (JComponent text : textsShown(view)) {
                Rectangle bounds = SwingUtilities.convertRectangle(text.getParent(), text.getBounds(), view);
                if (bounds.y + bounds.height <= visible.y) continue;
                if (first.isEmpty() || bounds.y < firstBounds.y) {
                    first = Optional.of(text);
                    firstBounds = bounds;
                }
            }
            Rectangle bounds = firstBounds;
            return first.map(text -> {
                UUID entry = (UUID) text.getClientProperty(SHOWS_ENTRY);
                Shown it = shown.get(entry);
                int length = it == null ? 0 : it.text().length();
                double scrolledPast = bounds.height <= 0 ? 0 : Math.max(0, (double) (visible.y - bounds.y) / bounds.height);
                return new ChatSearch.Spot(entry, (int) (length * Math.min(1, scrolledPast)));
            });
        }).orElse(ChatSearch.START);
    }

    /// The components in `view` that show an entry's text, and are shown.
    private static List<JComponent> textsShown(Container view) {
        List<JComponent> found = new ArrayList<>();
        for (Component child : view.getComponents()) {
            if (!child.isVisible() || !(child instanceof JComponent component)) continue;
            if (component.getClientProperty(SHOWS_ENTRY) instanceof UUID) found.add(component);
            else found.addAll(textsShown(component));
        }
        return found;
    }

    // ─── the chat and the bar over it ──────────────────────────────────────────────────────

    /// The chat, filling the stack, with the bar floating over it, at its top right, left of its
    /// scroll bar, as large as the bar likes to be.
    static final class Stack extends JBox {

        Stack(JComponent box, JComponent bar, JScrollPane chat, DoubleSupplier unfolded, Supplier<Insets> sea) {
            super(new Floating(box, bar, chat, unfolded, sea));
            // The first child is painted last, over the others, and gets the mouse first.
            add(box);
            add(chat);
        }

        /// The bar overlaps the chat, so Swing must not paint the chat alone, over the bar, when
        /// only the chat changed, nor copy the chat's pixels with the bar on them as it scrolls.
        @Override public boolean isOptimizedDrawingEnabled() {
            return false;
        }
    }

    /// Lays out the chat and the box with the bar in it, as far as it is `unfolded`. The bar is
    /// right in the box, as wide as it likes to be, or as the chat has room for, and the box as
    /// wide as the magnifying glass at the bar's right end, and as much of the rest as unfolded.
    /// The box's own layout, which leaves the bar where [Floating] put it.
    private static final class Unfolding implements LayoutManager {
        @Override public void addLayoutComponent(String name, Component component) {}
        @Override public void removeLayoutComponent(Component component) {}
        @Override public Dimension preferredLayoutSize(Container parent) { return parent.getSize(); }
        @Override public Dimension minimumLayoutSize(Container parent) { return new Dimension(); }
        @Override public void layoutContainer(Container parent) {}
    }

    private record Floating(JComponent box, JComponent bar, JScrollPane chat, DoubleSupplier unfolded, Supplier<Insets> sea) implements LayoutManager {

        @Override public void addLayoutComponent(String name, Component component) {}

        @Override public void removeLayoutComponent(Component component) {}

        @Override public Dimension preferredLayoutSize(Container parent) {
            return withInsets(parent, chat.getPreferredSize());
        }

        @Override public Dimension minimumLayoutSize(Container parent) {
            return withInsets(parent, chat.getMinimumSize());
        }

        @Override public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int width = Math.max(0, parent.getWidth() - insets.left - insets.right);
            int height = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
            chat.setBounds(insets.left, insets.top, width, height);
            Insets beyond = sea.get();
            int top = UI.scale(beyond.top + MARGIN), right = UI.scale(beyond.right + MARGIN);
            Insets rim = box.getInsets();
            Dimension wanted = bar.getPreferredSize();
            int barWidth = Math.min(wanted.width, Math.max(0, width - 2 * right - rim.left - rim.right));
            Component glass = bar.getComponent(bar.getComponentCount() - 1);
            int folded = Math.min(barWidth, glass.getPreferredSize().width + bar.getInsets().left + bar.getInsets().right);
            int shown = folded + (int) Math.round((barWidth - folded) * unfolded.getAsDouble());
            int boxWidth = shown + rim.left + rim.right;
            int boxHeight = wanted.height + rim.top + rim.bottom;
            box.setBounds(insets.left + width - right - boxWidth, insets.top + top, boxWidth, boxHeight);
            bar.setBounds(rim.left + shown - barWidth, rim.top, barWidth, wanted.height);
        }

        private static Dimension withInsets(Container parent, Dimension size) {
            Insets insets = parent.getInsets();
            return new Dimension(size.width + insets.left + insets.right, size.height + insets.top + insets.bottom);
        }
    }
}
