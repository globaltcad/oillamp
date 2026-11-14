package dev.gui.view;

import swingtree.components.JGlassPane;

import javax.swing.JRootPane;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDropEvent;

/// The window's glass pane, which draws what the user drags, such as a file from the outbox,
/// under the pointer. While a drag is under way, it holds the pointer wherever it is.
///
/// SwingTree 1.1.1's glass pane lets the pointer through to a part with a cursor of its own, such
/// as a button, a text field or a grip, so that part's cursor shows. While a drag is under way, it
/// then cannot say where the pointer is, and reckons it from the window instead of from itself.
/// The glass pane begins below the title bar, so the picture drops by that much over such a part
/// (reported in SwingTree as `docs/bugs/JGlassPane-Drag-Overlay-Coordinates.md`). During a drag,
/// the drag sets the cursor, not the parts under it, and AWT still finds the parts that take a
/// drop below a glass pane that takes none.
public final class DragGlassPane extends JGlassPane {

    private boolean dragging;

    public DragGlassPane(JRootPane root) {
        super(root);
        DragSource drags = DragSource.getDefaultDragSource();
        drags.addDragSourceMotionListener(_ -> dragging = true);
        drags.addDragSourceListener(new DragSourceAdapter() {
            @Override public void dragDropEnd(DragSourceDropEvent ended) { dragging = false; }
        });
    }

    @Override public boolean contains(int x, int y) {
        return dragging || super.contains(x, y);
    }
}
