package dev.gui.view;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreePath;

import dev.gui.model.Palette;
import sprouts.Val;
import swingtree.UI;

/// Shows a tree row's tooltip over the row itself, in place of Swing's tooltip under the
/// pointer: the tooltip's text begins exactly where the row's text does, in the row's font and
/// colour, and runs on to the right, as far as the window allows. So it reads as the row,
/// revealing what the tree's width cuts off.
///
/// The text is the tooltip the tree's `TreeConf` declares for the row's node. Where it sits is
/// worked out from the row's renderer and from the reveal's own label, both in pixels, the way
/// their look-and-feel lays out a label's text; so it lines up at any scale and in any
/// look-and-feel.
///
/// The reveal is a label on the window's popup layer with no mouse listeners of its own, so the
/// mouse goes through it to the tree: rows are still pointed at and clicked through it.
///
/// It comes after Swing's tooltip delay, follows the pointer from row to row at once, and goes
/// when the pointer leaves the rows, a button is pressed, the tree scrolls or its rows change.
final class RowReveal {

    /// How close to the window's right edge the reveal may come, in the window's units.
    private static final int MARGIN = 8;

    private final JTree tree;
    private final JLabel label;
    private final Timer delay;
    /// The row the pointer is on, or -1.
    private int pointed = -1;
    /// The row the reveal shows, or -1 while it is hidden.
    private int shown = -1;
    /// A row on which a press dismissed the reveal: it stays away until the pointer leaves it.
    private int dismissed = -1;

    private RowReveal(JTree tree, Val<Palette> palette) {
        this.tree = tree;
        // Its own words take the place of Swing's tooltip.
        ToolTipManager.sharedInstance().unregisterComponent(tree);
        this.label =
            UI.label("")
            .withStyle(palette, (p, it) -> it
                .backgroundColor(p.RAISED())
                .border(1, p.BORDER())
                .borderRadius(6)
                .padding(1, 4, 1, 6)
            )
            .get(JLabel.class);
        this.label.setVisible(false);
        this.delay = new Timer(ToolTipManager.sharedInstance().getInitialDelay(), _ -> show(pointed));
        this.delay.setRepeats(false);

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent event)   { pointAt(rowAt(tree, event.getX(), event.getY())); }
            @Override public void mouseExited(MouseEvent event)  { pointAt(-1); }
            @Override public void mousePressed(MouseEvent event) {
                dismissed = pointed;
                hide();
            }
        };
        tree.addMouseListener(mouse);
        tree.addMouseMotionListener(mouse);
        // Scrolling moves the tree under the reveal; going away takes the reveal with it.
        tree.addAncestorListener(new AncestorListener() {
            @Override public void ancestorAdded(AncestorEvent event)   { }
            @Override public void ancestorMoved(AncestorEvent event)   { hide(); }
            @Override public void ancestorRemoved(AncestorEvent event) { hide(); }
        });
        tree.getModel().addTreeModelListener(new TreeModelListener() {
            @Override public void treeNodesChanged(TreeModelEvent event)     { hide(); }
            @Override public void treeNodesInserted(TreeModelEvent event)    { hide(); }
            @Override public void treeNodesRemoved(TreeModelEvent event)     { hide(); }
            @Override public void treeStructureChanged(TreeModelEvent event) { hide(); }
        });
    }

    /// Shows the tooltips of `tree`'s rows over the rows, in a label styled after `palette`.
    /// The listeners and the placement this needs have no SwingTree methods.
    static RowReveal on(JTree tree, Val<Palette> palette) { return new RowReveal(tree, palette); }

    /// The row of `tree` at `x`, `y` in its pixels, or -1 between and below the rows.
    static int rowAt(JTree tree, int x, int y) {
        int row = tree.getClosestRowForLocation(x, y);
        Rectangle bounds = tree.getRowBounds(row);
        return bounds != null && y >= bounds.y && y < bounds.y + bounds.height ? row : -1;
    }

    private void pointAt(int row) {
        if (row == pointed) return;
        pointed = row;
        if (row != dismissed) dismissed = -1;
        if (row < 0 || row == dismissed) {
            hide();
        } else if (shown >= 0) {
            // Already showing: it follows the pointer to the next row without waiting again.
            show(row);
        } else {
            delay.restart();
        }
    }

    private void show(int row) {
        delay.stop();
        JRootPane root = SwingUtilities.getRootPane(tree);
        Rectangle bounds = row < 0 ? null : tree.getRowBounds(row);
        TreePath path = row < 0 ? null : tree.getPathForRow(row);
        if (root == null || bounds == null || path == null) { hide(); return; }
        // The row as the tree paints it, unselected: the reveal has a background of its own.
        Object node = path.getLastPathComponent();
        Component cell = tree.getCellRenderer().getTreeCellRendererComponent(
            tree, node, false, tree.isExpanded(row), tree.getModel().isLeaf(node), row, false
        );
        if (!(cell instanceof JLabel rendered) || rendered.getToolTipText() == null || rendered.getToolTipText().isBlank()) {
            hide();
            return;
        }
        Rectangle rowText = textIn(rendered, bounds.width, bounds.height);

        label.setText(rendered.getToolTipText());
        label.setFont(rendered.getFont());
        label.setForeground(rendered.getForeground());
        label.setComponentOrientation(rendered.getComponentOrientation());
        label.setCursor(tree.getCursor());
        JLayeredPane layers = root.getLayeredPane();
        if (label.getParent() != layers) {
            if (label.getParent() != null) label.getParent().remove(label);
            layers.add(label, JLayeredPane.POPUP_LAYER);
        }
        // Where the row's text begins, on the layer.
        Point origin = SwingUtilities.convertPoint(tree, bounds.x + rowText.x, bounds.y + rowText.y, layers);
        Dimension wanted = label.getPreferredSize();
        // The label's own text sits inside its border and padding: it goes where the row's is.
        Rectangle ownText = textIn(label, wanted.width, wanted.height);
        int x = origin.x - ownText.x;
        int y = origin.y - ownText.y;
        // As wide as its words, but not past the window's edge, where the label cuts them short.
        int width = Math.min(wanted.width, layers.getWidth() - UI.scale(MARGIN) - x);
        Rectangle before = label.isVisible() ? label.getBounds() : null;
        label.setBounds(x, y, Math.max(width, 0), wanted.height);
        label.setVisible(true);
        label.revalidate();
        if (before != null) layers.repaint(before);
        label.repaint();
        shown = row;
    }

    private void hide() {
        delay.stop();
        shown = -1;
        if (label.isVisible()) {
            Rectangle before = label.getBounds();
            label.setVisible(false);
            if (label.getParent() != null) label.getParent().repaint(before.x, before.y, before.width, before.height);
        }
    }

    /// Where `label`'s text goes when it is `width` by `height` pixels, as its look-and-feel
    /// lays it out: inside its insets, after its icon.
    private static Rectangle textIn(JLabel label, int width, int height) {
        Insets insets = label.getInsets();
        Rectangle inside = new Rectangle(
            insets.left, insets.top,
            width - insets.left - insets.right, height - insets.top - insets.bottom
        );
        Rectangle icon = new Rectangle();
        Rectangle text = new Rectangle();
        SwingUtilities.layoutCompoundLabel(
            label, label.getFontMetrics(label.getFont()), label.getText(), label.getIcon(),
            label.getVerticalAlignment(), label.getHorizontalAlignment(),
            label.getVerticalTextPosition(), label.getHorizontalTextPosition(),
            inside, icon, text, label.getIconTextGap()
        );
        return text;
    }
}
