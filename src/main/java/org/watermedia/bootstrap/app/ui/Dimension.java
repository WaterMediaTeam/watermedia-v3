package org.watermedia.bootstrap.app.ui;

/**
 * Represents dimensions with position and size.
 */
public record Dimension(int x, int y, int width, int height) {

    public static final Dimension ZERO = new Dimension(0, 0, 0, 0);

    public Dimension withX(final int x) {
        return new Dimension(x, this.y, this.width, this.height);
    }

    public Dimension withY(final int y) {
        return new Dimension(this.x, y, this.width, this.height);
    }

    public Dimension withPos(final int x, final int y) {
        return new Dimension(x, y, this.width, this.height);
    }

    public Dimension withSize(final int w, final int h) {
        return new Dimension(this.x, this.y, w, h);
    }

    public int right() {
        return this.x + this.width;
    }

    public int bottom() {
        return this.y + this.height;
    }

    public int centerX() {
        return this.x + this.width / 2;
    }

    public int centerY() {
        return this.y + this.height / 2;
    }

    public boolean contains(final double mx, final double my) {
        return mx >= this.x && mx <= this.right() && my >= this.y && my <= this.bottom();
    }

    public static Dimension centered(final int containerW, final int containerH, final int w, final int h) {
        return new Dimension((containerW - w) / 2, (containerH - h) / 2, w, h);
    }
}
