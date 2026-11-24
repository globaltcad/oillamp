package dev.gui.view;

import swingtree.api.Configurator;
import swingtree.style.FontConf;
import swingtree.style.StyledString;

/// A piece of text with its look, before it becomes the [StyledString] SwingTree paints.
///
/// A [StyledString] keeps its look to itself, so nothing can be added to it once it is made.
/// Kept apart until then, a piece can still be cut into parts, and a part get more of a look,
/// as [SearchOverlay] does to mark the places a search found.
record StyledPiece(Configurator<FontConf> look, String text) {

    StyledString styled() {
        return StyledString.of(look, text);
    }
}
