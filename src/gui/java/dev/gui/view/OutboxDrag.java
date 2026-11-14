package dev.gui.view;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// A file from the genie's outbox on its way out of the chat, as the user drags it onto their
/// desktop or into a folder of their file manager, which copies it to where it is let go of.
///
/// The file is in the genie's lamp, not on this computer. A copy of it is fetched as the drag
/// begins, `copy`; when the file manager asks for the file, it is handed that copy, once it is
/// complete. Most files are, long before the user lets go.
final class OutboxDrag implements Transferable {

    /// How long a file manager waits for a copy that is not complete yet, as long as fetching
    /// it may take at most.
    private static final long WAIT_MINUTES = 10;

    private final CompletableFuture<Path> copy;

    OutboxDrag(CompletableFuture<Path> copy) {
        this.copy = copy;
    }

    @Override public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[] { DataFlavor.javaFileListFlavor };
    }

    @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
        return flavor.equals(DataFlavor.javaFileListFlavor);
    }

    /// The copy, as a list of one file, which Java offers a file manager as a `text/uri-list`.
    @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
        if (!flavor.equals(DataFlavor.javaFileListFlavor)) throw new UnsupportedFlavorException(flavor);
        try {
            return List.of(copy.get(WAIT_MINUTES, TimeUnit.MINUTES).toFile());
        } catch (ExecutionException failed) {
            // The chat has told the user why already.
            throw new IOException("the file could not be fetched from the outbox", failed.getCause());
        } catch (TimeoutException tooLong) {
            throw new IOException("fetching the file from the outbox took too long", tooLong);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IOException("stopped waiting for the file from the outbox", stopped);
        }
    }
}
