package dev.gui.view;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sprouts.From;
import sprouts.Val;
import sprouts.Var;
import swingtree.DragDropComponentConf;

import javax.swing.JComponent;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/// Files the user drags from their file manager onto a genie's conversation, each of which the
/// genie is given when they let go, as through the composer's ＋.
///
/// Every part of the conversation that should take files is made a drop site with [#on]. AWT
/// hands a drag to the innermost component under the pointer that has a site, and Swing gives
/// one to every component with a transfer handler: a text area, for text, and an answer whose
/// text can be selected, for copying. Over such a part, the drag would leave the conversation
/// and be refused. The composer and the answers are therefore sites of this kind too; the
/// composer still takes text in where it is dropped.
///
/// While files are over a site that takes them, [#catching()] is true, for the conversation to
/// show that letting go gives them. A drag that moves from one site into another leaves the one
/// and enters the other, and AWT tells the sites in either order; it is caught for as long as
/// it is over any of them.
final class FileDrop {

    private static final Logger LOG = LoggerFactory.getLogger(FileDrop.class);

    private final Val<Boolean> open;
    private final Consumer<Path> give;
    private final Var<Boolean> catching = Var.of(false);
    /// The sites the dragged files are over, which take them.
    private final Set<Component> over = new HashSet<>();

    /// @param open whether the genie can be given files now: awake, in a conversation
    /// @param give gives the genie one file, or tells the user why it cannot
    FileDrop(Val<Boolean> open, Consumer<Path> give) {
        this.open = open;
        this.give = give;
    }

    /// Whether files are dragged over a site that would take them.
    Val<Boolean> catching() {
        return catching;
    }

    /// Makes `site` take the files dropped on it, and a text area the text too.
    <C extends JComponent> DragDropComponentConf<C> on(DragDropComponentConf<C> site) {
        return site
            .onDragEnter(it -> consider(it.getComponent(), it.getEvent()))
            .onDragOver(it -> consider(it.getComponent(), it.getEvent()))
            .onDropActionChanged(it -> consider(it.getComponent(), it.getEvent()))
            .onDragExit(it -> leave(it.getComponent()))
            .onDrop(it -> drop(it.getComponent(), it.getEvent()));
    }

    private void consider(Component site, DropTargetDragEvent drag) {
        if (open.get() && offersFiles(drag.getCurrentDataFlavors())) {
            drag.acceptDrag(DnDConstants.ACTION_COPY);
            over.add(site);
            catching.set(From.VIEW, true);
        } else {
            if (takesText(site) && drag.isDataFlavorSupported(DataFlavor.stringFlavor))
                drag.acceptDrag(DnDConstants.ACTION_COPY);
            else
                drag.rejectDrag();
            leave(site);
        }
    }

    private void leave(Component site) {
        over.remove(site);
        catching.set(From.VIEW, !over.isEmpty());
    }

    /// Gives the genie the files dropped, or puts the text dropped on a text area where it fell.
    /// A drag that offered files but brings none, such as a link from a browser, can still be
    /// text.
    private void drop(Component site, DropTargetDropEvent drop) {
        over.clear();
        catching.set(From.VIEW, false);
        boolean files = open.get() && offersFiles(drop.getCurrentDataFlavors());
        boolean text = takesText(site) && drop.isDataFlavorSupported(DataFlavor.stringFlavor);
        if (!files && !text) {
            drop.rejectDrop();
            return;
        }
        drop.acceptDrop(DnDConstants.ACTION_COPY);
        try {
            Transferable dropped = drop.getTransferable();
            List<Path> given = files ? filesIn(dropped) : List.of();
            if (!given.isEmpty()) {
                given.forEach(give);
                drop.dropComplete(true);
            } else if (text) {
                putText((JTextComponent) site, drop.getLocation(), (String) dropped.getTransferData(DataFlavor.stringFlavor));
                drop.dropComplete(true);
            } else {
                drop.dropComplete(false);
            }
        } catch (UnsupportedFlavorException | IOException | RuntimeException unreadable) {
            LOG.warn("Could not read what was dropped on the conversation", unreadable);
            drop.dropComplete(false);
        }
    }

    private static boolean takesText(Component site) {
        return site instanceof JTextComponent text && text.isEditable() && text.isEnabled();
    }

    private static boolean offersFiles(DataFlavor[] flavors) {
        for (DataFlavor flavor : flavors)
            if (flavor.equals(DataFlavor.javaFileListFlavor) || isUriList(flavor)) return true;
        return false;
    }

    /// A list of URIs, one per line, which file managers offer for the files they drag, as text.
    /// Java turns it into a [DataFlavor#javaFileListFlavor] on most desktops; it is read when
    /// Java does not.
    private static boolean isUriList(DataFlavor flavor) {
        return flavor.isMimeTypeEqual("text/uri-list") && flavor.getRepresentationClass() == String.class;
    }

    /// The local files among what was dropped, in the order they came.
    private static List<Path> filesIn(Transferable dropped) throws UnsupportedFlavorException, IOException {
        if (dropped.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            List<Path> files = new ArrayList<>();
            for (Object file : (List<?>) dropped.getTransferData(DataFlavor.javaFileListFlavor))
                if (file instanceof File it) files.add(it.toPath());
            return files;
        }
        for (DataFlavor flavor : dropped.getTransferDataFlavors())
            if (isUriList(flavor)) return filesIn((String) dropped.getTransferData(flavor));
        return List.of();
    }

    /// The local files a `text/uri-list` names: one URI a line, comments starting with `#`.
    /// Anything that is not a `file:` URI is left out.
    static List<Path> filesIn(String uriList) {
        List<Path> files = new ArrayList<>();
        for (String line : uriList.lines().map(String::strip).toList()) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            try {
                URI uri = new URI(line);
                if ("file".equalsIgnoreCase(uri.getScheme())) files.add(Path.of(uri));
            } catch (Exception notALocalFile) {
                // Left out.
            }
        }
        return files;
    }

    /// Puts `text` into `area` where it was dropped, as the text area's own drop would.
    private static void putText(JTextComponent area, Point at, @Nullable String text) {
        if (text == null) return;
        int position = area.viewToModel2D(at);
        if (position >= 0) area.setCaretPosition(position);
        area.replaceSelection(text);
        area.requestFocusInWindow();
    }
}
