package dev.promo;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/// Where the film looks at the window: the point of the window, in its own units, that is in
/// the middle of the picture, and how many pixels of the film one unit is. A camera that moves
/// in has the window painted finer, so what it shows stays sharp.
///
/// @param x     the unit across the window that is in the middle of the picture
/// @param y     the unit down the window that is there
/// @param scale pixels of the film to a unit of the window
record Camera(double x, double y, double scale) {

    /// The scale at which a window [AppScene#WIDE] units wide sits under a caption with room around it.
    static final double PLAIN = 1.25;

    /// Looks at `place` of the window, so that it fills `room` of the film as far as its shape
    /// allows, its middle in the room's middle.
    static Camera framing(Rectangle2D place, Rectangle2D room) {
        double scale = Math.min(room.getWidth() / place.getWidth(), room.getHeight() / place.getHeight());
        return new Camera(place.getCenterX(), place.getCenterY(), scale).with(room.getCenterX(), room.getCenterY());
    }

    /// Looks at the same place, at `scale`.
    Camera at(double scale) { return new Camera(x, y, scale); }

    /// The same view, moved so that what was in the middle of the picture is at `filmX`, `filmY`.
    Camera with(double filmX, double filmY) {
        return new Camera(x - (filmX - Film.WIDTH / 2.0) / scale, y - (filmY - Film.HEIGHT / 2.0) / scale, scale);
    }

    /// Where the window's point `x`, `y`, in units, is in the film.
    Point2D.Double film(double x, double y) {
        return new Point2D.Double((x - this.x) * scale + Film.WIDTH / 2.0, (y - this.y) * scale + Film.HEIGHT / 2.0);
    }

    /// What of the window the film sees, in units.
    Rectangle2D seen() {
        double w = Film.WIDTH / scale, h = Film.HEIGHT / scale;
        return new Rectangle2D.Double(x - w / 2, y - h / 2, w, h);
    }

    /// On the way from this view to `other`, `progress` of it. The scale changes by ratio, so
    /// that moving in feels as even as moving across.
    Camera toward(Camera other, double progress) {
        double p = Draw.clamp(progress);
        double scale = this.scale * Math.pow(other.scale / this.scale, p);
        // The point in the middle moves evenly on the film, not on the window: while the scale
        // changes, a unit is not always as long.
        double along = Math.abs(other.scale - this.scale) < 1e-9 ? p : (scale - this.scale) / (other.scale - this.scale) * other.scale / scale;
        return new Camera(Draw.mix(x, other.x, along), Draw.mix(y, other.y, along), scale);
    }
}
