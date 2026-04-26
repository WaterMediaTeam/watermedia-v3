package org.watermedia.bootstrap.app.ui;

import org.watermedia.tools.DrawTool;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog component with border, title, content and buttons.
 * Navigation uses LEFT/RIGHT for two buttons, UP/DOWN for vertical lists.
 */
public class Dialog {

    private static final int BORDER_WIDTH = 3;
    private static final int DEFAULT_PADDING = 20;

    private String title;
    private Color borderColor = Colors.BLUE;
    private int padding = DEFAULT_PADDING;
    private int minWidth = 300;
    private int minHeight = 100;

    private final List<ContentLine> contentLines = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private int selectedButton = 0;

    private Dimension bounds = Dimension.ZERO;
    private boolean visible = false;
    private Runnable onSelectionChanged;

    public Dialog() {
    }

    public Dialog(final String title) {
        this.title = title;
    }

    // CONFIGURATION
    public Dialog title(final String t) {
        this.title = t;
        return this;
    }

    public Dialog borderColor(final Color c) {
        this.borderColor = c;
        return this;
    }

    public Dialog padding(final int p) {
        this.padding = p;
        return this;
    }

    public Dialog minWidth(final int w) {
        this.minWidth = w;
        return this;
    }

    public Dialog minHeight(final int h) {
        this.minHeight = h;
        return this;
    }

    public Dialog onSelectionChanged(final Runnable r) {
        this.onSelectionChanged = r;
        return this;
    }

    public Dialog clearContent() {
        this.contentLines.clear();
        return this;
    }

    public Dialog addLine(final String text) {
        this.contentLines.add(new ContentLine(text, Colors.GRAY));
        return this;
    }

    public Dialog addLine(final String text, final Color color) {
        this.contentLines.add(new ContentLine(text, color));
        return this;
    }

    public Dialog clearButtons() {
        this.buttons.clear();
        this.selectedButton = 0;
        return this;
    }

    public Dialog addButton(final String text, final Runnable action) {
        this.buttons.add(new Button(text, action));
        return this;
    }

    // STATE
    public boolean visible() {
        return this.visible;
    }

    public void show() {
        this.visible = true;
    }

    public void hide() {
        this.visible = false;
    }

    public Dimension bounds() {
        return this.bounds;
    }

    public int buttonCount() {
        return this.buttons.size();
    }

    public int selectedIndex() {
        return this.selectedButton;
    }

    // NAVIGATION — USE FOR HORIZONTAL BUTTON LAYOUTS (LEFT/RIGHT)
    public void navigateLeft() {
        if (this.buttons.isEmpty()) return;
        final int newIndex = (this.selectedButton - 1 + this.buttons.size()) % this.buttons.size();
        if (newIndex != this.selectedButton) {
            this.selectedButton = newIndex;
            if (this.onSelectionChanged != null) this.onSelectionChanged.run();
        }
    }

    public void navigateRight() {
        if (this.buttons.isEmpty()) return;
        final int newIndex = (this.selectedButton + 1) % this.buttons.size();
        if (newIndex != this.selectedButton) {
            this.selectedButton = newIndex;
            if (this.onSelectionChanged != null) this.onSelectionChanged.run();
        }
    }

    public void setSelectedIndex(final int index) {
        if (index >= 0 && index < this.buttons.size()) {
            this.selectedButton = index;
            // NO CALLBACK — FOR INITIALIZATION
        }
    }

    public void confirm() {
        if (this.selectedButton >= 0 && this.selectedButton < this.buttons.size()) {
            final Button btn = this.buttons.get(this.selectedButton);
            if (btn.action != null) btn.action.run();
        }
    }

    // RENDERING
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        if (!this.visible) return;

        DrawTool.setupOrtho(windowW, windowH);
        DrawTool.dialogBox(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), this.borderColor, BORDER_WIDTH);

        int y = this.bounds.y() + this.padding;
        final int lineH = renderer.lineHeight();

        // TITLE
        if (this.title != null && !this.title.isEmpty()) {
            renderer.render(this.title, this.bounds.x() + this.padding, y, this.borderColor);
            y += lineH + 15;
        }

        // CONTENT
        for (final ContentLine line: this.contentLines) {
            renderer.render(line.text, this.bounds.x() + this.padding, y, line.color);
            y += lineH;
        }

        // BUTTONS
        if (!this.buttons.isEmpty()) {
            y = this.bounds.bottom() - this.padding - lineH;

            if (this.buttons.size() == 2) {
                // TWO BUTTONS: LEFT AND RIGHT ALIGNED
                final Button left = this.buttons.get(0);
                final Button right = this.buttons.get(1);

                final String leftText = (this.selectedButton == 0 ? "> " : "  ") + left.text;
                final String rightText = (this.selectedButton == 1 ? "> " : "  ") + right.text;

                renderer.render(leftText, this.bounds.x() + this.padding, y, this.selectedButton == 0 ? Colors.BLUE : Colors.GRAY);
                left.bounds = new Dimension(this.bounds.x() + this.padding, y, renderer.width(leftText), lineH);

                final int rightX = this.bounds.right() - this.padding - renderer.width(rightText);
                renderer.render(rightText, rightX, y, this.selectedButton == 1 ? Colors.BLUE : Colors.GRAY);
                right.bounds = new Dimension(rightX, y, renderer.width(rightText), lineH);
            } else {
                // SINGLE BUTTON: CENTERED
                final Button btn = this.buttons.get(0);
                final String text = "> " + btn.text;
                final int x = this.bounds.x() + (this.bounds.width() - renderer.width(text)) / 2;
                renderer.render(text, x, y, Colors.BLUE);
                btn.bounds = new Dimension(x, y, renderer.width(text), lineH);
            }
        }

        DrawTool.restoreProjection();
    }

    public Dimension centerIn(final TextRenderer text, final int windowW, final int windowH) {
        final int lineH = text.lineHeight();
        int contentH = 0;
        int contentW = 0;

        // TITLE
        if (this.title != null && !this.title.isEmpty()) {
            contentH += lineH + 15;
            contentW = Math.max(contentW, text.width(this.title));
        }

        // CONTENT LINES
        for (final ContentLine line: this.contentLines) {
            contentH += lineH;
            contentW = Math.max(contentW, text.width(line.text));
        }

        // BUTTONS
        if (!this.buttons.isEmpty()) {
            contentH += lineH + 25;
            if (this.buttons.size() == 2) {
                final int buttonsW = text.width("> " + this.buttons.get(0).text) + 40 + text.width("> " + this.buttons.get(1).text);
                contentW = Math.max(contentW, buttonsW);
            } else {
                contentW = Math.max(contentW, text.width("> " + this.buttons.get(0).text));
            }
        }

        final int w = Math.max(this.minWidth, contentW + this.padding * 2);
        final int h = Math.max(this.minHeight, contentH + this.padding * 2);

        this.bounds = Dimension.centered(windowW, windowH, w, h);
        return this.bounds;
    }

    // MOUSE HANDLING
    public boolean handleClick(final double mx, final double my) {
        if (!this.visible) return false;

        for (int i = 0; i < this.buttons.size(); i++) {
            final Button btn = this.buttons.get(i);
            if (btn.bounds != null && btn.bounds.contains(mx, my)) {
                this.selectedButton = i;
                this.confirm();
                return true;
            }
        }
        return this.bounds.contains(mx, my);
    }

    public boolean handleHover(final double mx, final double my) {
        if (!this.visible) return false;

        for (int i = 0; i < this.buttons.size(); i++) {
            final Button btn = this.buttons.get(i);
            if (btn.bounds != null && btn.bounds.contains(mx, my)) {
                if (this.selectedButton != i) {
                    this.selectedButton = i;
                    if (this.onSelectionChanged != null) this.onSelectionChanged.run();
                }
                return true;
            }
        }
        return false;
    }

    private record ContentLine(String text, Color color) {
    }

    private static class Button {
        String text;
        Runnable action;
        Dimension bounds;

        Button(final String text, final Runnable action) {
            this.text = text;
            this.action = action;
        }
    }
}
