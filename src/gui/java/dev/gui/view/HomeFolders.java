package dev.gui.view;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.TreePath;

import org.jspecify.annotations.Nullable;

import dev.gui.Genies;
import dev.gui.model.Fonts;
import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.Home;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.layout.FlowCell;

import static dev.gui.model.Palette.DARK;
import static swingtree.UI.*;

/// A genie's home on its Actions page: where it is on this computer, and its folders as a tree,
/// any of which the user links into a folder of their own, such as their documents.
///
/// The tree shows the home with everything folded but the way to the genie's workspace, which
/// is chosen. The user chooses another folder, names the link, which is "Juniper - workspace"
/// until they do, and links it into their documents with one button, or anywhere with the other,
/// which asks where. Or they drag the folder out of the tree into their file manager, which then
/// makes a link of its own, named as the folder is.
///
/// The folders are read from the home as the user opens them, and again when the page comes on
/// show. A folder they open is read two levels deep, so that each folder shown in it knows
/// whether it can be opened in turn.
final class HomeFolders {

    /*
     *  A responsive grid inside the tile: the tree, and what links the folder chosen in it, side
     *  by side from LARGE up; below, the tree on top.
     *
     *                  very small  small  medium  large  very large  oversize
     *      the tree        12        12     12      7        7          7
     *      linking         12        12     12      5        5          5
     *
     *  The grid sits in the tile's layout, which asks it how tall it is at its width; a preferred
     *  size set on it would answer instead. So the two parts' own widths make the width the
     *  size classes are measured against: TREE_WIDTH and LINKING_WIDTH, side by side.
     */
    private static final int TREE_WIDTH = 420;
    private static final int LINKING_WIDTH = 300;
    private static final int TREE_HEIGHT = 250;
    private static final FlowCell TREE = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP).fill(true)
        .verySmall(12).small(12).medium(12).large(7).veryLarge(7).oversize(7)
    );
    private static final FlowCell LINKING = AUTO_SPAN(it ->
        it.align(UI.VerticalAlignment.TOP)
        .verySmall(12).small(12).medium(12).large(5).veryLarge(5).oversize(5)
    );

    /// What the last link said: where it is, or why there is none.
    private record Outcome(String words, boolean trouble) {
        static final Outcome NONE = new Outcome("", false);
    }

    private final Genies app;

    // Lenses and views, held as fields: a lens is observed only weakly by its parent.
    private final Var<Genie> genie;
    private final Val<UUID> selected;
    private final Val<Home> home;
    private final Val<Home.Folder> root;
    /// The folder the user chose, by names below the home: the workspace until they choose another.
    private final Var<Tuple<String>> chosen = Var.of(Tuple.classTyped(String.class), Home.WORKSPACE);
    /// The tree's selection, by names from its root, the home, down. The tree empties it while a
    /// folder it shows is not read yet, and again while it is built anew; [#chosen] remembers.
    private final Var<Tuple<String>> inTree = Var.of(Tuple.classTyped(String.class), fromRoot(Home.WORKSPACE));
    /// What the link is called unless the user names it, which follows the folder and the genie.
    private final Val<String> usualName;
    private final Var<String> linkName;
    private final Var<Outcome> outcome = Var.of(Outcome.NONE);
    /// Whether the chosen folder is in the home, which a folder chosen but deleted since is not.
    private final Val<Boolean> linkable;
    /// The folders whose folders were read since they were last opened, by names from the root.
    /// Opening one reads it; when the tree is built anew it opens each again, which reads nothing.
    private final Set<Tuple<String>> opened = new HashSet<>();
    /// The row under the pointer, or -1 for none, which brightens across the tree's width.
    private final Var<Integer> pointed = Var.of(-1);
    /// The tree, once it exists.
    private Optional<JTree> shownTree = Optional.empty();

    HomeFolders(Var<GeniesState> state, Genies app) {
        this.app = app;
        genie = state.zoomTo(GeniesState::genie, GeniesState::withGenie);
        selected = state.viewAs(UUID.class, GeniesState::selected);
        home = genie.viewAs(Home.class, Genie::home);
        root = home.viewAs(Home.Folder.class, Home::root);
        usualName = Viewable.of(String.class, genie.viewAsString(Genie::name), chosen, Home::linkName);
        linkName = Var.of(usualName.get());
        linkable = Viewable.of(Boolean.class, home, chosen, (it, folder) -> it.where().isPresent() && it.root().at(folder).isPresent());
        // A folder chosen in the tree is the one to link. An empty selection is the tree's own
        // doing, not the user's: they cannot unchoose a folder.
        Viewable.cast(inTree).onChange(From.ALL, it -> {
            Tuple<String> path = it.currentValue().orElse(Tuple.of(String.class));
            if (!path.isEmpty()) chosen.set(path.removeFirst());
        });
        Viewable.cast(usualName).onChange(From.ALL, it -> linkName.set(it.currentValue().orElse("")));
        Viewable.cast(chosen).onChange(From.ALL, _ -> outcome.set(Outcome.NONE));
        // Another genie: its workspace, in a tree folded anew.
        Viewable.cast(selected).onChange(From.ALL, _ -> {
            opened.clear();
            outcome.set(Outcome.NONE);
            chosen.set(Home.WORKSPACE);
            inTree.set(fromRoot(Home.WORKSPACE));
        });
        // Read anew, the tree may show the chosen folder now, as when the home's first reading
        // brings the workspace. Once the tree took the new folders, it is selected again: the
        // selection is told anew even where it did not change, since the tree had no such folder
        // to select when it was told last.
        Viewable.cast(root).onChange(From.ALL, _ -> SwingUtilities.invokeLater(() -> {
            Tuple<String> wanted = fromRoot(chosen.get());
            if (root.get().at(chosen.get()).isEmpty()) return;
            if (!inTree.get().equals(wanted)) inTree.set(wanted);
            else if (shownTree.map(it -> it.getSelectionCount() == 0).orElse(false)) inTree.fireChange(From.VIEW_MODEL);
        }));
    }

    /// What the tile says on top: what a home is here, and what a link to it does.
    Val<String> says() {
        return genie.viewAsString(it ->
            it.home().read() && it.home().where().isEmpty()
                ? it.name() + " has no home yet. It gets one as it first wakes."
                : it.name() + "'s home is a folder on this computer. Link one of its folders into yours, to find what "
                  + it.name() + " made in your file manager. A link is no copy: a change on either side is one on both."
        );
    }

    UIForAnySwing<?, ?> view() {
        // The tile asks the grid how tall it is at the width it has, which it gives the grid
        // after. At a new width, the grid's parts may stand side by side or one under the other,
        // so the tile asks again.
        ComponentAdapter relayout = new ComponentAdapter() {
            private int width = -1;
            @Override public void componentResized(ComponentEvent event) {
                if (event.getComponent().getWidth() == width) return;
                width = event.getComponent().getWidth();
                event.getComponent().getParent().revalidate();
            }
        };
        return
            panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 14, 14)
            .withMinSize(0, 0)
            .peek(it -> it.addComponentListener(relayout))
            // The grid's gaps are at its edges too; outside the tile's own edges, they line up
            // with the words above.
            .withStyle(it -> it.backgroundColor(DARK.TRANSPARENT()).margin(-14))
            .add(TREE, tree())
            .add(LINKING, linking());
    }

    // ─── the tree ──────────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> tree() {
        JTree[] shown = new JTree[1];
        return
            scrollPane(conf -> conf.fitWidth(true)).withEmptyBorder(0)
            .withMinSize(0, TREE_HEIGHT).withPrefSize(TREE_WIDTH, TREE_HEIGHT)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(16)
            .withStyle(it -> it.backgroundColor(DARK.RAISED()).border(1, DARK.BORDER()).borderRadius(12).padding(6))
            .add(
                // In a panel of its own: on its own, a tree asks its scroll pane for room for
                // twenty rows, however many it has.
                panel("fill, ins 0").withStyle(it -> it.backgroundColor(DARK.TRANSPARENT()))
                .add("grow, wmin 0",
                    UI.tree(root, conf -> conf
                        .nodesOf(Home.Folder.class, it -> it
                            .children(Home.Folder::folders)
                            .text(Home.Folder::name)
                            .icon(_ -> SignSvgUtil.sign(SignSvgUtil.FOLDER, DARK.BRASS()))
                        )
                        .leafWhenEmpty(true)
                    )
                    .withRootVisible(true)
                    // Rows a finger can hit.
                    .withRowHeight(30)
                    .withSelection(inTree)
                    .peek(tree -> {
                        shown[0] = tree;
                        shownTree = Optional.of(tree);
                        tree.addTreeExpansionListener(new TreeExpansionListener() {
                            @Override public void treeExpanded(TreeExpansionEvent event) { opening(tree, event.getPath()); }
                            @Override public void treeCollapsed(TreeExpansionEvent event) { opened.remove(namesOf(tree, event.getPath())); }
                        });
                        tree.setDragEnabled(true);
                        tree.setTransferHandler(new Dragging());
                    })
                    .onMouseMove(it -> {
                        JTree tree = it.getComponent();
                        // In the tree's own pixels, as its rows are: mouseX() and mouseY() are unscaled.
                        int x = it.getEvent().getX(), y = it.getEvent().getY();
                        int row = tree.getClosestRowForLocation(x, y);
                        Rectangle bounds = tree.getRowBounds(row);
                        boolean onRow = bounds != null && y >= bounds.y && y < bounds.y + bounds.height;
                        pointed.set(onRow ? row : -1);
                        tree.setCursor(Cursor.getPredefinedCursor(onRow ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    })
                    .onMouseExit(_ -> pointed.set(-1))
                    .withStyle(pointed, (row, it) -> it
                        .backgroundColor(DARK.TRANSPARENT())
                        .componentFont(f -> f.family(Fonts.DEFAULT.FONT()).size(13).color(DARK.TEXT()))
                        .painter(UI.Layer.BACKGROUND, g -> {
                            JTree tree = shown[0];
                            Rectangle bounds = row < 0 || tree == null ? null : tree.getRowBounds(row);
                            if (bounds == null) return;
                            // SwingTree scales a painter's units to pixels; the tree's rows are in pixels.
                            g.scale(1 / UI.scale(), 1 / UI.scale());
                            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                            g.setColor(ViewPartsUtil.withAlpha(DARK.BORDER(), 170));
                            g.fillRoundRect(0, bounds.y, tree.getWidth(), bounds.height, UI.scale(8), UI.scale(8));
                        })
                    )
                )
            );
    }

    /// The user opened the folder at `path`: its folders, and theirs, are read, unless they were
    /// since it was last opened.
    private void opening(JTree tree, TreePath path) {
        Tuple<String> names = namesOf(tree, path);
        if (opened.add(names)) app.readHome(selected.get(), names.removeFirst());
    }

    /// The folder a row of the tree shows, by names from the root down. A row's path holds the
    /// tree's own handles, not the folders; but each step of it is the folder at the same place
    /// among its parent's, and the tree shows [#root] as it is.
    private Tuple<String> namesOf(JTree tree, TreePath path) {
        Home.Folder at = root.get();
        Tuple<String> names = Tuple.of(String.class, at.name());
        for (int step = 1; step < path.getPathCount(); step++) {
            int index = tree.getModel().getIndexOfChild(path.getPathComponent(step - 1), path.getPathComponent(step));
            if (index < 0 || index >= at.folders().size()) break;
            at = at.folders().get(index);
            names = names.add(at.name());
        }
        return names;
    }

    private static Tuple<String> fromRoot(Tuple<String> belowHome) {
        return Tuple.of(String.class, Home.HOME).addAll(belowHome);
    }

    /// A folder dragged out of the tree is offered to wherever it is dropped as the folder
    /// itself, to be linked: a file manager makes a link to it there. Nothing else is offered,
    /// so that none copies a whole workspace.
    private final class Dragging extends TransferHandler {
        @Override public int getSourceActions(JComponent component) { return LINK; }

        @Override protected @Nullable Transferable createTransferable(JComponent component) {
            if (!(component instanceof JTree tree) || tree.getSelectionPath() == null) return null;
            Tuple<String> names = namesOf(tree, tree.getSelectionPath()).removeFirst();
            return app.folderOf(selected.get(), names).map(FolderToLink::new).orElse(null);
        }
    }

    /// A folder, as a list of one file, which Java offers to the desktop as a `text/uri-list`
    /// too, and as that list's text.
    private record FolderToLink(Path folder) implements Transferable {
        private static final DataFlavor URI_LIST = uriList();

        private static DataFlavor uriList() {
            try {
                return new DataFlavor("text/uri-list;class=java.lang.String");
            } catch (ClassNotFoundException cannotBe) {
                throw new IllegalStateException(cannotBe);
            }
        }

        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] { DataFlavor.javaFileListFlavor, URI_LIST }; }

        @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
            return flavor.equals(DataFlavor.javaFileListFlavor) || flavor.equals(URI_LIST);
        }

        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (flavor.equals(DataFlavor.javaFileListFlavor)) return List.of(folder.toFile());
            if (flavor.equals(URI_LIST)) return folder.toUri() + "\r\n";
            throw new UnsupportedFlavorException(flavor);
        }
    }

    // ─── linking ───────────────────────────────────────────────────────────────────────────

    private UIForAnySwing<?, ?> linking() {
        Val<String> where = Viewable.of(String.class, home, chosen, (it, folder) ->
            it.where()
            .map(at -> folder.stream().reduce(at, Path::resolve, (a, _) -> a).toString())
            .orElse("")
        );
        Val<String> documentsName = Val.of(String.valueOf(app.documents().getFileName()));
        Val<Boolean> nameable = Viewable.of(Boolean.class, linkable, linkName, (can, name) -> can && !name.isBlank());
        Val<String> said = outcome.viewAsString(Outcome::words);
        return
            box("fillx, wrap 1, ins 0, gap 8, hidemode 3", "[0:" + LINKING_WIDTH + ", grow]")
            .add("growx, wmin 0", label("ON THIS COMPUTER").group(Skin.SECTION))
            // A field nobody types in, but that the path can be selected and copied from.
            .add("growx, wmin 0",
                textField(where).group(Skin.INPUT)
                .isEditableIf(false)
                .withTooltip(where)
                .withStyle(it -> it
                    .backgroundColor(DARK.TRANSPARENT())
                    .border(1, DARK.TRANSPARENT())
                    .padding(4, 0, 4, 0)
                    .componentFont(f -> f.family(Fonts.DEFAULT.MONO()).size(12).color(DARK.SUBTEXT()))
                )
            )
            .add(
                ViewPartsUtil.link("Open in your file manager")
                .isEnabledIf(linkable)
                .onClick(_ -> app.showInFileManager(selected.get(), chosen.get()))
            )
            .add("growx, wmin 0, gaptop 6", label("THE LINK'S NAME").group(Skin.SECTION))
            .add("growx, wmin 0",
                textField(linkName).group(Skin.INPUT)
                .isEnabledIf(linkable)
                .withStyle(it -> it.padding(8, 10, 8, 10))
            )
            .add("growx, wmin 0",
                panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 8, 8)
                .withStyle(it -> it.backgroundColor(DARK.TRANSPARENT()).margin(-8, -8, 0, -8))
                .add(
                    button(documentsName.viewAsString(it -> "Link into " + it)).group(Skin.FLAME_BUTTON)
                    .isEnabledIf(nameable)
                    .withTooltip(app.documents().toString())
                    .withStyle(it -> it.padding(9, 16, 9, 16))
                    .onClick(_ -> link(app.documents()))
                )
                .add(
                    button("Link into…").group(Skin.QUIET_BUTTON)
                    .isEnabledIf(nameable)
                    .withStyle(it -> it.padding(9, 16, 9, 16))
                    .onClick(it -> chooseWhere(it.getComponent()).ifPresent(this::link))
                )
            )
            .add("growx, wmin 0",
                ViewPartsUtil.wrapped(said, DARK.CONTENT(), outcome.viewAs(Boolean.class, it -> !it.words().isEmpty() && !it.trouble()))
            )
            .add("growx, wmin 0", ViewPartsUtil.wrapped(said, DARK.TROUBLE(), outcome.viewAs(Boolean.class, Outcome::trouble)))
            .add("growx, wmin 0",
                ViewPartsUtil.wrapped(
                    genie.viewAsString(it ->
                        "Or drag a folder from the tree into your file manager. "
                        + "Deleting " + it.name() + " leaves its links behind, leading nowhere."
                    ),
                    DARK.SUBTEXT(),
                    Val.of(true)
                )
            );
    }

    /// Asks the user which of their folders to put the link into.
    private Optional<Path> chooseWhere(Component button) {
        JFileChooser chooser = new JFileChooser(app.documents().toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Where to put the link \"" + linkName.get().strip() + "\"");
        return chooser.showDialog(button, "Link here") == JFileChooser.APPROVE_OPTION
            ? Optional.of(chooser.getSelectedFile().toPath())
            : Optional.empty();
    }

    private void link(Path into) {
        try {
            Home.Link link = app.link(selected.get(), chosen.get(), into, linkName.get());
            outcome.set(new Outcome((link.madeNow() ? "Linked: " : "It was linked already: ") + link.at(), false));
        } catch (IOException failed) {
            outcome.set(new Outcome(String.valueOf(failed.getMessage()), true));
        }
    }
}
