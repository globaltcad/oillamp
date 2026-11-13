package dev.gui.view;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import swingtree.UI;

/// A shelf for a sideways scroll pane: its children side by side, in reading order, wrapped
/// into new rows where the width the scroll pane shows ends, as text is, but into at most
/// `rows` rows. Children that would need more rows than that make the shelf as much wider as
/// they need to fit into `rows` rows, and the scroll pane shows a scroll bar below them, to
/// scroll sideways to the rest.
///
/// The shelf tells the scroll pane how much of it to show: as wide as the scroll pane is, and as
/// tall as its rows. So the scroll pane, which leaves room for its scroll bar when the shelf is
/// wider than that, puts the bar below the rows, never over them.
///
/// The rows are wrapped at the width the scroll pane shows now. When that changes, in a wider
/// or a narrower window, the shelf and what holds it are laid out again, so the rows follow.
/// Nothing is measured and kept but that width, to notice that it changed.
final class Shelf extends JPanel implements Scrollable {

    private final int rows;
    private final int gap;
    /// The width shown by the scroll pane when the shelf last said how big it is.
    private int toldFor = -1;

    /// @param rows the most rows the children are wrapped into
    /// @param gap  the room between two children, beside and above each other, in the window's units
    Shelf(int rows, int gap) {
        this.rows = rows;
        this.gap = gap;
        setOpaque(false);
        setLayout(new Rows());
    }

    @Override public void addNotify() {
        super.addNotify();
        if (getParent() instanceof JViewport viewport) viewport.addComponentListener(resizes);
    }

    @Override public void removeNotify() {
        if (getParent() instanceof JViewport viewport) viewport.removeComponentListener(resizes);
        super.removeNotify();
    }

    /// Lays the shelf and what holds it out again, when the scroll pane shows another width
    /// than the rows were wrapped at.
    private final ComponentAdapter resizes = new ComponentAdapter() {
        @Override public void componentResized(ComponentEvent event) {
            if (shown() == toldFor) return;
            // The scroll pane is laid out on its own, as a root of its own layouts; but it
            // may need another height now, which what holds it decides.
            invalidate();
            if (SwingUtilities.getAncestorOfClass(JScrollPane.class, Shelf.this) instanceof JScrollPane pane
                && pane.getParent() instanceof JComponent holder) holder.revalidate();
            else revalidate();
        }
    };

    /// The width the scroll pane shows, or the shelf's own outside one.
    private int shown() { return getParent() instanceof JViewport viewport ? viewport.getWidth() : getWidth(); }

    @Override public Dimension getPreferredScrollableViewportSize() {
        Dimension size = getPreferredSize();
        return new Dimension(Math.min(size.width, Math.max(shown(), 0)), size.height);
    }

    @Override public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport viewport && getPreferredSize().width <= viewport.getWidth();
    }

    @Override public boolean getScrollableTracksViewportHeight() { return false; }

    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return UI.scale(24);
    }

    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.HORIZONTAL ? Math.max(visible.width - UI.scale(48), UI.scale(24)) : visible.height;
    }

    /// The children wrapped into rows, as described at the [Shelf].
    private final class Rows implements LayoutManager {

        /// As wide as the scroll pane shows, or wider if the children need it to fit into the
        /// rows, and as tall as the rows they then take.
        @Override public Dimension preferredLayoutSize(Container shelf) {
            Insets edges = shelf.getInsets();
            int shown = shown();
            toldFor = shown;
            int width = widthFor(Math.max(0, shown - edges.left - edges.right));
            return new Dimension(width + edges.left + edges.right, place(false, width, 0, 0)[1] + edges.top + edges.bottom);
        }

        @Override public Dimension minimumLayoutSize(Container shelf) { return preferredLayoutSize(shelf); }

        @Override public void layoutContainer(Container shelf) {
            Insets edges = shelf.getInsets();
            place(true, Math.max(0, shelf.getWidth() - edges.left - edges.right), edges.left, edges.top);
        }

        /// The least width, from `shown` on, at which the children fit into the rows.
        private int widthFor(int shown) {
            if (place(false, shown, 0, 0)[0] <= rows) return shown;
            int narrow = shown, wide = Math.max(shown, inOneRow());
            // Fewer rows at more width, never more, so the least width is found by halving.
            while (wide - narrow > 1) {
                int middle = (narrow + wide) / 2;
                if (place(false, middle, 0, 0)[0] <= rows) wide = middle; else narrow = middle;
            }
            return wide;
        }

        /// How wide the children are all in one row.
        private int inOneRow() {
            int width = 0;
            for (Component child : getComponents())
                if (child.isVisible()) width += (width == 0 ? 0 : UI.scale(gap)) + child.getPreferredSize().width;
            return width;
        }

        /// Wraps the children into rows at `width`, puts them there if `placing`, and says how
        /// many rows they took and how tall those are.
        private int[] place(boolean placing, int width, int left, int top) {
            int gap = UI.scale(Shelf.this.gap);
            int taken = 0, x = 0, y = 0, tallest = 0;
            for (Component child : getComponents()) {
                if (!child.isVisible()) continue;
                Dimension size = child.getPreferredSize();
                if (taken == 0) taken = 1;
                else if (x + gap + size.width > width) {
                    taken++;
                    y += tallest + gap;
                    x = 0;
                    tallest = 0;
                }
                else x += gap;
                if (placing) child.setBounds(left + x, top + y, size.width, size.height);
                x += size.width;
                tallest = Math.max(tallest, size.height);
            }
            return new int[] { taken, taken == 0 ? 0 : y + tallest };
        }

        @Override public void addLayoutComponent(String name, Component child) {}

        @Override public void removeLayoutComponent(Component child) {}
    }
}
