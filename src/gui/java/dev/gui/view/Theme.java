package dev.gui.view;

import dev.gui.model.Fonts;
import dev.gui.model.GeniesState;
import dev.gui.model.Lang;
import dev.gui.model.Palette;

import sprouts.Val;

/// The colours and the fonts of the state, together: what a style follows that needs both, as
/// one property, so that a single styler follows them.
///
/// The views never paint with a palette or fonts of their own. They read them through views of
/// the state, this one or one of its [Palette] alone, and so follow the state when it changes
/// them. Their words, likewise, come from the [Lang] of the state, through [#langOf].
record Theme(Palette palette, Fonts fonts) {

    /// The colours and the fonts of `state`, changing only when either of them does.
    static Val<Theme> of(Val<GeniesState> state) {
        return state.viewAs(Theme.class, it -> new Theme(it.palette(), it.fonts()));
    }

    /// The colours of `state`, changing only when they do.
    static Val<Palette> paletteOf(Val<GeniesState> state) {
        return state.viewAs(Palette.class, GeniesState::palette);
    }

    /// The words of `state`, changing only when they do.
    static Val<Lang> langOf(Val<GeniesState> state) {
        return state.viewAs(Lang.class, GeniesState::lang);
    }
}
