package org.watermedia.bootstrap.app.screen;

import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.Colors;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.DrawTool;

import java.awt.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Console screen for displaying logs and operation progress.
 */
public class ConsoleScreen extends Screen {

    private final Consumer<HomeScreen.Action> navigator;
    private final List<ConsoleLine> lines = new CopyOnWriteArrayList<>();

    private String title = "Console";
    private int scrollOffset;
    private boolean waitingForKey;
    private Runnable onClose;

    public ConsoleScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    public void open(final String title, final Runnable onClose) {
        this.title = title;
        this.onClose = onClose;
        this.lines.clear();
        this.scrollOffset = 0;
        this.waitingForKey = false;
    }

    public void print(final String text) {
        this.print(text, Colors.WHITE);
    }

    public void print(final String text, final Color color) {
        this.lines.add(new ConsoleLine(text, color));
        this.autoScroll();
    }

    public void info(final String text) {
        this.print(text, Colors.BLUE);
    }

    public void success(final String text) {
        this.print(text, Colors.GREEN);
    }

    public void error(final String text) {
        this.print(text, Colors.RED);
    }

    public void warning(final String text) {
        this.print(text, Colors.YELLOW);
    }

    public void waitForKey() {
        this.print("");
        this.print("Press any key to continue...", Colors.YELLOW);
        this.waitingForKey = true;
    }

    private void autoScroll() {
        final int maxVisible = this.maxVisibleLines();
        if (this.lines.size() > maxVisible) {
            this.scrollOffset = this.lines.size() - maxVisible;
        }
    }

    private int maxVisibleLines() {
        return (this.ctx.windowHeight - 150) / (this.text.lineHeight() - 4);
    }

    @Override
    public void render(final int windowW, final int windowH) {
        DrawTool.setupOrtho(windowW, windowH);

        // Background
        DrawTool.disableTextures();
        DrawTool.fill(0, 0, windowW, windowH, 0.05f, 0.05f, 0.1f, 1f);
        DrawTool.enableTextures();

        // Title bar
        DrawTool.disableTextures();
        DrawTool.fill(0, 0, windowW, 40, 0.1f, 0.1f, 0.2f, 1f);
        DrawTool.enableTextures();
        this.text.render(this.title, AppContext.PADDING, 10, Colors.BLUE);

        // Console content
        final int startY = 50;
        final int lineH = this.text.lineHeight() - 4;
        final int maxVisible = this.maxVisibleLines();

        for (int i = 0; i < maxVisible && (i + this.scrollOffset) < this.lines.size(); i++) {
            final ConsoleLine line = this.lines.get(i + this.scrollOffset);
            this.text.render(line.text, AppContext.PADDING, startY + i * lineH, line.color);
        }

        // Scroll indicator
        if (this.lines.size() > maxVisible) {
            final String scrollInfo = "Lines " + (this.scrollOffset + 1) + "-" +
                    Math.min(this.scrollOffset + maxVisible, this.lines.size()) + " of " + this.lines.size();
            this.text.render(scrollInfo, windowW - this.text.width(scrollInfo) - AppContext.PADDING, 10, Colors.GRAY);
        }

        DrawTool.restoreProjection();
    }

    @Override
    protected void onKeyRelease(final int key) {
        if (this.waitingForKey) {
            this.waitingForKey = false;
            if (this.onClose != null) {
                this.onClose.run();
            } else {
                this.navigator.accept(HomeScreen.Action.BACK);
            }
            return;
        }

        final int maxVisible = this.maxVisibleLines();
        switch (key) {
            case GLFW_KEY_UP -> this.scrollOffset = Math.max(0, this.scrollOffset - 1);
            case GLFW_KEY_DOWN -> this.scrollOffset = Math.min(Math.max(0, this.lines.size() - maxVisible), this.scrollOffset + 1);
            case GLFW_KEY_PAGE_UP -> this.scrollOffset = Math.max(0, this.scrollOffset - maxVisible);
            case GLFW_KEY_PAGE_DOWN -> this.scrollOffset = Math.min(Math.max(0, this.lines.size() - maxVisible), this.scrollOffset + maxVisible);
            case GLFW_KEY_ESCAPE -> {
                if (this.onClose != null) {
                    this.onClose.run();
                } else {
                    this.navigator.accept(HomeScreen.Action.BACK);
                }
            }
        }
    }

    @Override
    public String instructions() {
        return this.waitingForKey
                ? "Press any key to continue..."
                : "UP/DOWN: Scroll | PgUp/PgDn: Fast Scroll | ESC: Close";
    }

    private record ConsoleLine(String text, Color color) {
    }
}
