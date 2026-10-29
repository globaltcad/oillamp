package dev.gui.view;

import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.InputEvent;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import dev.gui.model.Fonts;
import dev.gui.model.Genie;

import sprouts.Val;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForBox;
import swingtree.UIForButton;
import swingtree.UIForLabel;
import swingtree.components.JBox;
import swingtree.style.StyledString;

import static dev.gui.model.Palette.DARK;
import static swingtree.UI.*;

/// Small pieces the views share.
final class ViewPartsUtil {

    private ViewPartsUtil() {}

    /// The lamp of a genie in `phase`, drawn from SVG at `size`.
    static UIForAnySwing<?, ?> lamp(Val<Genie.Phase> phase, int size) {
        return
            box().withPrefSize(size, size).withMinSize(size, size)
            .withStyle(phase.viewAsString(LampSvgUtil::lamp), (svg, it) ->
                it.image(img -> img.svg(svg).fitMode(UI.FitComponent.MIN_DIM))
            );
    }

    /// A few lines of small print, wrapped to the width they are given.
    static UIForAnySwing<?, ?> note(String text, Val<Boolean> shown) {
        return
            box().withMinSize(0, 0).isVisibleIf(shown)
            // Some padding, on purpose: text painted by the style engine sets its component's
            // height when it is painted, and a component of no height is never painted.
            .withStyle(it ->
                it.padding(2, 0, 2, 0)
                .text(t ->
                    t.content(StyledString.of(f -> f.family(Fonts.DEFAULT.FONT()).size(11).color(DARK.SUBTEXT()), text))
                    .placement(UI.Placement.TOP_LEFT)
                    .wrapLines(true)
                    .autoPreferredHeight(true)
                )
            );
    }

    /// Text that changes, such as a problem, wrapped to the width it is given rather than cut
    /// short: the end of a message is often the part that says what to do.
    static UIForAnySwing<?, ?> wrapped(Val<String> text, Color colour, Val<Boolean> shown) {
        return
            box().withMinSize(0, 0).isVisibleIf(shown)
            .withStyle(text, (words, it) ->
                it.padding(2, 0, 2, 0)
                .text(t ->
                    t.content(StyledString.of(f -> f.family(Fonts.DEFAULT.FONT()).size(12).color(colour), words))
                    .placement(UI.Placement.TOP_LEFT)
                    .wrapLines(true)
                    .autoPreferredHeight(true)
                )
            );
    }

    /// Line wrapping for a text area, which has no SwingTree method, so this one Swing setter is
    /// called directly.
    static void softWrap(JTextArea area) {
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
    }

    /// Opens the `menu` of the button `pressed` came from, under it, its right edge on the
    /// button's: such a button is near the window's right edge, and a menu opening rightwards
    /// would leave the window. Pressing the button again closes the menu.
    ///
    /// Swing closes an open menu on a press anywhere outside it, before the button hears of the
    /// press. So the button notes when its menu closed, and the press that closed it opens nothing.
    static void toggleBelow(InputEvent pressed, Supplier<JPopupMenu> menu) {
        if (!(pressed.getComponent() instanceof JComponent button)) return;
        if (Objects.equals(button.getClientProperty(MENU_CLOSED_AT), pressed.getWhen())) return;
        JPopupMenu shown = menu.get();
        shown.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent event) {}
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                button.putClientProperty(MENU_CLOSED_AT, EventQueue.getMostRecentEventTime());
            }
            @Override public void popupMenuCanceled(PopupMenuEvent event) {}
        });
        shown.show(button, button.getWidth() - shown.getPreferredSize().width, button.getHeight());
    }

    private static final String MENU_CLOSED_AT = "genies.menuClosedAt";

    static JMenuItem item(String text, boolean enabled, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setEnabled(enabled);
        item.addActionListener(_ -> action.run());
        return item;
    }

    /// A round dot of `size`.
    static UIForBox<JBox> dot(Val<Color> colour, int size) {
        return
            box().withPrefSize(size, size).withMinSize(size, size).withMaxSize(size, size)
            .withStyle(colour, (c, it) -> it.backgroundColor(c).borderRadius(size));
    }

    /// Text painted by the style engine, wrapped to the width it gets.
    static UIForBox<JBox> words(String text, int size, float weight, Color colour) {
        return
            box().withMinSize(0, 0)
            .withStyle(it ->
                it.padding(1, 0, 1, 0)
                .text(t ->
                    t.content(StyledString.of(f -> f.family(Fonts.DEFAULT.FONT()).size(size).weight(weight).color(colour), text))
                    .placement(UI.Placement.TOP_LEFT)
                    .wrapLines(true)
                    .autoPreferredHeight(true)
                )
            );
    }

    /// A few words on a small rounded patch, such as "added by Rex".
    static UIForLabel<JLabel> tag(Val<String> text) {
        return
            label(text)
            .withStyle(it -> it
                .backgroundColor(DARK.RAISED())
                .borderRadius(8)
                .padding(2, 8, 2, 8)
                .componentFont(f -> f.family(Fonts.DEFAULT.FONT()).size(11).color(DARK.SUBTEXT()))
            );
    }

    /// A button that reads as a link, in brass.
    static UIForButton<JButton> link(String text) {
        return
            button(text).group(Skin.ICON_BUTTON)
            .withStyle(it -> it.padding(2, 0, 2, 0).componentFont(f -> f.family(Fonts.DEFAULT.FONT()).size(12).color(DARK.BRASS())));
    }

    /// A switch of two parts, No and Yes, as the one between a genie's pages in the header. The
    /// chosen part is lit; both are greyed while it cannot be changed.
    static UIForAnySwing<?, ?> yesOrNo(Val<Boolean> yes, Val<Boolean> changeable, Consumer<Boolean> choose) {
        return
            box("ins 2, gap 2")
            .withStyle(changeable, (can, it) -> it.backgroundColor(DARK.SMOKE()).border(1, can ? DARK.BORDER() : DARK.SMOKE()).borderRadius(11))
            .add(part("No", yes.viewAs(Boolean.class, it -> !it), changeable, () -> choose.accept(false)))
            .add(part("Yes", yes, changeable, () -> choose.accept(true)));
    }

    /// How a part of a switch looks: whether it is chosen, and whether it can be changed.
    private record Part(boolean chosen, boolean changeable) {}

    /// One part of a switch: lit while chosen, and greyed, chosen or not, while it cannot be changed.
    private static UIForButton<JButton> part(String text, Val<Boolean> chosen, Val<Boolean> changeable, Runnable choose) {
        Val<Part> looks = Viewable.of(Part.class, chosen, changeable, Part::new);
        return
            button(text).group(Skin.ICON_BUTTON)
            .isEnabledIf(changeable)
            .withStyle(looks, (part, it) -> it
                .borderRadius(9)
                .padding(4, 12, 4, 12)
                .backgroundColor(!part.chosen() ? DARK.TRANSPARENT() : part.changeable() ? DARK.RAISED() : DARK.CARD())
                .cursor(part.changeable() ? UI.Cursor.HAND : UI.Cursor.DEFAULT)
                .componentFont(f -> f
                    .family(Fonts.DEFAULT.FONT())
                    .size(12)
                    .weight(part.chosen() ? 2f : 1f)
                    .color(part.changeable() ? (part.chosen() ? DARK.TEXT() : DARK.SUBTEXT()) : dim(DARK.SUBTEXT()))
                )
            )
            .onClick(_ -> choose.run());
    }

    /// `colour`, faded to step back.
    static Color dim(Color colour) {
        return withAlpha(colour, 110);
    }

    static Color withAlpha(Color colour, int alpha) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
}
