package dev.gui.view;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

import javax.swing.JComponent;

import swingtree.api.Layout;

/// The layout of a panel that unfolds: its one child laid out as tall as it wants to be, at the
/// width the panel has, and the panel only `part` of that tall, from 0, nothing, to 1, all of
/// it; the rest of the child is cut off at the panel's lower edge.
///
/// The height the panel opens towards is the child's own, asked for anew on every layout pass,
/// so it is always the one for the width the child has now, in whatever language and scale.
/// Nothing is measured and kept.
///
/// A value, as the other layouts of SwingTree are: a panel's layout follows a `Val` of it, and
/// installing a different `part` lays the panel out anew.
///
/// @param part how much of the child's height the panel has, from 0 to 1
record UnfoldingLayout(double part) implements Layout {

    @Override public void installFor(JComponent component) {
        if (component.getLayout() instanceof Manager installed && installed.part == part) return;
        component.setLayout(new Manager(part));
        component.revalidate();
    }

    private static final class Manager implements LayoutManager {

        private final double part;

        Manager(double part) { this.part = part; }

        /// As wide as the panel is given, and `part` as tall as its child at that width.
        @Override public Dimension preferredLayoutSize(Container panel) {
            Insets edges = panel.getInsets();
            int tall = panel.getComponentCount() == 0 ? 0 : panel.getComponent(0).getPreferredSize().height;
            return new Dimension(edges.left + edges.right, edges.top + edges.bottom + (int) Math.round(part * tall));
        }

        @Override public Dimension minimumLayoutSize(Container panel) { return preferredLayoutSize(panel); }

        /// The child across the panel, as tall as it wants to be, however tall the panel is.
        @Override public void layoutContainer(Container panel) {
            if (panel.getComponentCount() == 0) return;
            Insets edges = panel.getInsets();
            Component child = panel.getComponent(0);
            child.setBounds(edges.left, edges.top, Math.max(0, panel.getWidth() - edges.left - edges.right), child.getPreferredSize().height);
        }

        @Override public void addLayoutComponent(String name, Component child) {}

        @Override public void removeLayoutComponent(Component child) {}
    }
}
