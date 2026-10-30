package dev.gui.view;

import dev.gui.model.Fonts;
import dev.gui.model.Palette;

import sprouts.Val;
import sprouts.Viewable;

/// An item a style follows, such as the words of a label or whether a button is chosen,
/// together with the colours and the fonts it is painted in: what a style follows that depends
/// on another property as well as on the [Theme], as one property, so that a single styler
/// follows both.
record Themed<T>(T item, Palette palette, Fonts fonts) {

    /// `item` in `theme`, changing whenever either does.
    @SuppressWarnings("unchecked")
    static <T> Val<Themed<T>> of(Val<T> item, Val<Theme> theme) {
        return Viewable.of(
            (Class<Themed<T>>) (Class<?>) Themed.class,
            item,
            theme,
            (it, now) -> new Themed<>(it, now.palette(), now.fonts())
        );
    }
}
