package dev.gui.model;

/// How wide the list of genies is beside the genie, in a window that is not narrow. The user
/// drags the grip at the list's right edge to make it wider or narrower. While they hold it, the
/// list may get narrower than it needs, down to nothing, which shows that letting go hides it.
///
/// @param width the list's width, in the window's units
public record Sidebar(int width) {

    /// The width Genies gives the list until the user drags it.
    public static final Sidebar PICKED = new Sidebar(250);

    /// The least width the list needs. Let go with less than half of it, the list hides; with
    /// less than all of it, the list gets all of it.
    public static final int NARROWEST = 200;
    /// The most width the list takes, so the genie keeps the window's most.
    public static final int WIDEST = 480;
    /// How thick the grip at the list's edge is.
    public static final int GRIP = 9;

    /// The user holds the grip, and drags the list to `width`.
    public Sidebar withWidth(int width) { return new Sidebar(Math.clamp(width, 0, WIDEST)); }

    /// Whether the list, let go of as it is, hides.
    public boolean hides() { return width < NARROWEST / 2; }

    /// The user let go of the grip, and the list gets at least the width it needs.
    public Sidebar released() { return new Sidebar(Math.max(width, NARROWEST)); }
}
