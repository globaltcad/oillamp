package dev.gui.model;

/// The words of Genies: every text the window shows, in one language. A value, as [Palette] is,
/// so that Genies can be shown in another language as it can in other colours.
///
/// A text is a component named after what it says in English, such as `YOUR_GENIES`. A text that
/// is assembled from something else, such as a count, is a function from that to the words: an
/// interface declared here, as languages differ in how they assemble one. Each language is a
/// single constant, such as [#ENGLISH], so that its functions are the same objects whenever the
/// window compares two of them, and a view follows the words only when the language changes.
public record Lang() {

    public static final Lang ENGLISH = new Lang();
}
