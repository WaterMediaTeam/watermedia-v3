package org.watermedia.bootstrap.app.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple title/body/buttons dialog built on the abstract dialog shell.
 */
public class SimpleDialog extends AbstractDialog {

    private final List<Line> lines = new ArrayList<>();
    private final List<UiButton> buttons = new ArrayList<>();

    public SimpleDialog line(final String text, final Color color) {
        this.lines.add(new Line(text, color));
        return this;
    }

    public SimpleDialog button(final String label, final Runnable action) {
        final UiButton button = new UiButton(label).minWidth(96).height(32);
        button.onClick(e -> action.run());
        this.buttons.add(button);
        return this;
    }

    public SimpleDialog clear() {
        this.lines.clear();
        this.buttons.clear();
        return this;
    }

    @Override
    protected void renderBody(final TextRenderer renderer, final Dimension body, final int windowW, final int windowH) {
        int y = body.y();
        for (final Line line: this.lines) {
            renderer.render(line.text, body.x(), y, line.color);
            y += renderer.lineHeight();
        }

        int x = body.right();
        final int buttonY = body.bottom() - 36;
        for (int i = this.buttons.size() - 1; i >= 0; i--) {
            final UiButton button = this.buttons.get(i);
            final Dimension measured = button.calculateBounds(renderer, 0, 0);
            x -= measured.width();
            button.bounds(new Dimension(x, buttonY, measured.width(), measured.height()));
            button.render(renderer, windowW, windowH);
            x -= 8;
        }
    }

    @Override
    public boolean handleClick(final double mx, final double my) {
        if (!this.visible) return false;
        for (final UiButton button: this.buttons) {
            if (button.handleClick(mx, my)) return true;
        }
        return this.bounds.contains(mx, my);
    }

    @Override
    public boolean handleHover(final double mx, final double my) {
        boolean hit = false;
        for (final UiButton button: this.buttons) {
            hit |= button.handleHover(mx, my);
        }
        return hit;
    }

    private record Line(String text, Color color) {
    }
}
