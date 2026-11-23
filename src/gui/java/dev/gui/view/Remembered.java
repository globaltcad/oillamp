package dev.gui.view;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/// The last thing a style made, together with what it made it from, so that the style, which
/// SwingTree gathers anew whenever it repaints or lays out its component, such as on every step
/// of a resize, makes it again only when what it is made from changed. A style captures one of
/// these per component, made where the component is built.
///
/// Handing back the very same thing also tells SwingTree that nothing changed, where a newly
/// made one, equal in looks but holding new lambdas, would not be equal to the old one.
///
/// @param <K> what the thing is made from; compared with `equals`, so a record of the inputs
/// @param <V> the thing made
final class Remembered<K, V> {

    private record Made<K, V>(K from, V thing) {}

    private final AtomicReference<Made<K, V>> last = new AtomicReference<>();

    /// What `make` makes from `from`: the last thing made, if it was made from an equal `from`.
    V of(K from, Function<K, V> make) {
        Made<K, V> made = last.get();
        if (made != null && Objects.equals(made.from(), from)) return made.thing();
        V thing = make.apply(from);
        last.set(new Made<>(from, thing));
        return thing;
    }
}
