package org.watermedia.bootstrap.app.ui;

import org.lwjgl.opengl.GL11;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.tools.DrawTool;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glScissor;

/**
 * A grid layout component for displaying items as cards with borders,
 * icons, hover expansion animation, click feedback, and optional section headers.
 *
 * @param <T> Type of items in the grid
 */
public class Grid<T> {

    /**
     * Available icon types for grid cells.
     */
    public enum Icon {
        PLAY, UPLOAD, CLEANUP, FOLDER, EXIT, CUSTOM,
        STATUS_READY, STATUS_ERROR, STATUS_LOADING, STATUS_UNKNOWN, STATUS_NULL
    }

    // Data
    private final List<GridEntry<T>> entries = new ArrayList<>();
    private final List<T> selectableItems = new ArrayList<>();

    // Configuration
    private int columns = 4;
    private int cellGap = 12;
    private int innerPadding = 14;
    private int cellHeight = 120;
    private float borderWidth = 4f;
    private int sectionGap = 8;

    // Selection
    private int selectedIndex = 0;

    // Animation state
    private float[] hoverAnim = new float[64];
    private float[] clickAnim = new float[64];
    private static final float HOVER_SPEED = 0.12f;
    private static final float CLICK_SPEED = 0.15f;
    private static final float HOVER_EXPAND = 6f;
    private static final float CLICK_SHRINK = 3f;

    // Scroll
    private int scrollOffset = 0;
    private int maxHeight = -1;
    private int totalContentHeight = 0;
    private int visibleHeight = 0;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int SCROLL_AMOUNT = 40;

    // Providers
    private Function<T, String> labelProvider = Object::toString;
    private Function<T, String> sublabelProvider;
    private Function<T, Color> borderColorProvider;
    private Function<T, Icon> iconProvider;
    private Function<T, Color> iconColorProvider;
    private Function<T, MediaPlayer> thumbnailProvider;

    // Callbacks
    private BiConsumer<Integer, T> onSelect;
    private Runnable onSelectionChanged;

    // Title
    private String title;

    // Layout cache
    private int startX, startY;
    private int computedCellWidth;
    private int titleHeight;

    // Precomputed positions (set in calculateBounds)
    private float[] cellPosX = new float[0];
    private float[] cellPosY = new float[0];
    private int[] cellGridRow = new int[0];
    private int[] cellGridCol = new int[0];
    private float[] sectionPosY = new float[0];
    private String[] sectionLabelCache = new String[0];

    // FLUENT CONFIGURATION
    public Grid<T> columns(final int c) { this.columns = c; return this; }
    public Grid<T> cellGap(final int g) { this.cellGap = g; return this; }
    public Grid<T> innerPadding(final int p) { this.innerPadding = p; return this; }
    public Grid<T> cellHeight(final int h) { this.cellHeight = h; return this; }
    public Grid<T> borderWidth(final float w) { this.borderWidth = w; return this; }
    public Grid<T> maxHeight(final int h) { this.maxHeight = h; return this; }
    public Grid<T> title(final String t) { this.title = t; return this; }
    public Grid<T> labelProvider(final Function<T, String> p) { this.labelProvider = p; return this; }
    public Grid<T> sublabelProvider(final Function<T, String> p) { this.sublabelProvider = p; return this; }
    public Grid<T> borderColorProvider(final Function<T, Color> p) { this.borderColorProvider = p; return this; }
    public Grid<T> iconProvider(final Function<T, Icon> p) { this.iconProvider = p; return this; }
    public Grid<T> iconColorProvider(final Function<T, Color> p) { this.iconColorProvider = p; return this; }
    public Grid<T> thumbnailProvider(final Function<T, MediaPlayer> p) { this.thumbnailProvider = p; return this; }
    public Grid<T> onSelect(final BiConsumer<Integer, T> h) { this.onSelect = h; return this; }
    public Grid<T> onSelectionChanged(final Runnable h) { this.onSelectionChanged = h; return this; }

    // DATA MANAGEMENT
    public Grid<T> clear() {
        this.entries.clear();
        this.selectableItems.clear();
        this.selectedIndex = 0;
        this.scrollOffset = 0;
        Arrays.fill(this.hoverAnim, 0f);
        Arrays.fill(this.clickAnim, 0f);
        return this;
    }

    public Grid<T> section(final String header) {
        this.entries.add(new GridEntry<>(header, null, true));
        return this;
    }

    public Grid<T> item(final T item) {
        this.entries.add(new GridEntry<>(null, item, false));
        this.selectableItems.add(item);
        return this;
    }

    public Grid<T> items(final List<T> list) {
        for (final T item : list) {
            this.item(item);
        }
        return this;
    }

    public int itemCount() {
        return this.selectableItems.size();
    }

    // NAVIGATION
    public void moveUp() {
        if (this.selectableItems.isEmpty() || this.cellGridRow.length == 0) return;
        final int targetRow = this.cellGridRow[this.selectedIndex] - 1;
        final int best = this.findClosestInRow(targetRow, this.cellGridCol[this.selectedIndex]);
        if (best >= 0 && best != this.selectedIndex) {
            this.selectedIndex = best;
            if (this.onSelectionChanged != null) this.onSelectionChanged.run();
            this.ensureSelectedVisible();
        }
    }

    public void moveDown() {
        if (this.selectableItems.isEmpty() || this.cellGridRow.length == 0) return;
        final int targetRow = this.cellGridRow[this.selectedIndex] + 1;
        final int best = this.findClosestInRow(targetRow, this.cellGridCol[this.selectedIndex]);
        if (best >= 0 && best != this.selectedIndex) {
            this.selectedIndex = best;
            if (this.onSelectionChanged != null) this.onSelectionChanged.run();
            this.ensureSelectedVisible();
        }
    }

    public void moveLeft() {
        if (this.selectableItems.isEmpty() || this.cellGridRow.length == 0) return;
        final int myRow = this.cellGridRow[this.selectedIndex];
        final int myCol = this.cellGridCol[this.selectedIndex] - 1;
        for (int i = 0; i < this.selectableItems.size(); i++) {
            if (this.cellGridRow[i] == myRow && this.cellGridCol[i] == myCol) {
                this.selectedIndex = i;
                if (this.onSelectionChanged != null) this.onSelectionChanged.run();
                this.ensureSelectedVisible();
                return;
            }
        }
    }

    public void moveRight() {
        if (this.selectableItems.isEmpty() || this.cellGridRow.length == 0) return;
        final int myRow = this.cellGridRow[this.selectedIndex];
        final int myCol = this.cellGridCol[this.selectedIndex] + 1;
        for (int i = 0; i < this.selectableItems.size(); i++) {
            if (this.cellGridRow[i] == myRow && this.cellGridCol[i] == myCol) {
                this.selectedIndex = i;
                if (this.onSelectionChanged != null) this.onSelectionChanged.run();
                this.ensureSelectedVisible();
                return;
            }
        }
    }

    private int findClosestInRow(final int targetRow, final int preferredCol) {
        int bestIdx = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < this.selectableItems.size(); i++) {
            if (this.cellGridRow[i] == targetRow) {
                final int dist = Math.abs(this.cellGridCol[i] - preferredCol);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestIdx = i;
                }
            }
        }
        return bestIdx;
    }

    public void confirm() {
        if (this.selectableItems.isEmpty() || this.selectedIndex < 0 || this.selectedIndex >= this.selectableItems.size()) return;
        this.ensureAnimArrays();
        this.clickAnim[this.selectedIndex] = 1.0f;
        if (this.onSelect != null) {
            this.onSelect.accept(this.selectedIndex, this.selectableItems.get(this.selectedIndex));
        }
    }

    public void handleScroll(final double yOffset) {
        if (this.maxHeight <= 0 || this.totalContentHeight <= this.visibleHeight) return;
        final int maxScroll = this.totalContentHeight - this.visibleHeight;
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset + (int) (-yOffset * SCROLL_AMOUNT)));
    }

    private void ensureSelectedVisible() {
        if (this.maxHeight <= 0 || this.totalContentHeight <= this.maxHeight) return;
        if (this.selectedIndex < 0 || this.selectedIndex >= this.cellPosY.length) return;
        final float itemTop = this.cellPosY[this.selectedIndex];
        final float itemBottom = itemTop + this.cellHeight;

        if (itemTop < this.scrollOffset) {
            this.scrollOffset = Math.max(0, (int) (itemTop - this.cellGap));
        } else if (itemBottom > this.scrollOffset + this.visibleHeight) {
            this.scrollOffset = (int) (itemBottom - this.visibleHeight + this.cellGap);
        }
    }

    // LAYOUT CALCULATION
    public void calculateBounds(final TextRenderer text, final int x, final int y, final int availableWidth) {
        this.startX = x;
        this.startY = y;

        this.titleHeight = 0;
        if (this.title != null && !this.title.isEmpty()) {
            this.titleHeight = text.lineHeight() + 10;
        }

        final int usableWidth = availableWidth - (this.columns - 1) * this.cellGap;
        this.computedCellWidth = usableWidth / this.columns;

        final int selCount = this.selectableItems.size();
        this.cellPosX = new float[selCount];
        this.cellPosY = new float[selCount];
        this.cellGridRow = new int[selCount];
        this.cellGridCol = new int[selCount];

        final List<Float> secYList = new ArrayList<>();
        final List<String> secLblList = new ArrayList<>();

        final int lineH = text.lineHeight();
        float contentY = this.titleHeight;
        int currentCol = 0;
        int currentRow = 0;
        int itemIdx = 0;

        for (final GridEntry<T> entry : this.entries) {
            if (entry.header()) {
                // FINISH PARTIAL ROW IF ITEMS EXIST
                if (currentCol > 0) {
                    contentY += this.cellHeight + this.cellGap;
                    currentCol = 0;
                    currentRow++;
                }
                secYList.add(contentY);
                secLblList.add(entry.label());
                contentY += lineH + this.sectionGap;
            } else {
                this.cellPosX[itemIdx] = this.startX + currentCol * (this.computedCellWidth + this.cellGap);
                this.cellPosY[itemIdx] = contentY;
                this.cellGridRow[itemIdx] = currentRow;
                this.cellGridCol[itemIdx] = currentCol;

                itemIdx++;
                currentCol++;
                if (currentCol >= this.columns) {
                    currentCol = 0;
                    currentRow++;
                    contentY += this.cellHeight + this.cellGap;
                }
            }
        }

        // FINISH LAST PARTIAL ROW
        if (currentCol > 0) {
            contentY += this.cellHeight;
        }

        this.totalContentHeight = (int) contentY;

        this.sectionPosY = new float[secYList.size()];
        this.sectionLabelCache = new String[secLblList.size()];
        for (int i = 0; i < secYList.size(); i++) {
            this.sectionPosY[i] = secYList.get(i);
            this.sectionLabelCache[i] = secLblList.get(i);
        }

        this.visibleHeight = this.maxHeight > 0 ? Math.min(this.maxHeight, this.totalContentHeight) : this.totalContentHeight;
        if (this.maxHeight > 0 && this.totalContentHeight > this.maxHeight) {
            final int maxScroll = this.totalContentHeight - this.visibleHeight;
            this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset));
        } else {
            this.scrollOffset = 0;
        }

        this.ensureAnimArrays();
    }

    private void ensureAnimArrays() {
        final int needed = this.selectableItems.size();
        if (this.hoverAnim.length < needed) {
            final float[] nh = new float[needed + 16];
            System.arraycopy(this.hoverAnim, 0, nh, 0, this.hoverAnim.length);
            this.hoverAnim = nh;
            final float[] nc = new float[needed + 16];
            System.arraycopy(this.clickAnim, 0, nc, 0, this.clickAnim.length);
            this.clickAnim = nc;
        }
    }

    // RENDERING
    public void render(final TextRenderer text, final int windowW, final int windowH) {
        if (this.selectableItems.isEmpty()) return;

        DrawTool.setupOrtho(windowW, windowH);
        final boolean useScissor = this.maxHeight > 0 && this.totalContentHeight > this.maxHeight;

        this.updateAnimations();

        // TITLE (RENDERED BEFORE SCISSOR)
        if (this.title != null && !this.title.isEmpty()) {
            text.render(this.title, this.startX, this.startY - this.scrollOffset, Colors.BLUE);
        }

        if (useScissor) {
            glEnable(GL_SCISSOR_TEST);
            final int scissorBottom = windowH - this.startY - this.visibleHeight;
            glScissor(0, scissorBottom, windowW, this.visibleHeight);
        }

        // SECTION HEADERS
        for (int j = 0; j < this.sectionLabelCache.length; j++) {
            final float sy = this.startY + this.sectionPosY[j] - this.scrollOffset;
            text.render(this.sectionLabelCache[j], this.startX, (int) sy, Colors.BLUE);
        }

        final int lineH = text.lineHeight();

        // NON-SELECTED CELLS FIRST
        for (int i = 0; i < this.selectableItems.size(); i++) {
            if (i == this.selectedIndex) continue;
            final float sx = this.cellPosX[i];
            final float sy = this.startY + this.cellPosY[i] - this.scrollOffset;
            this.renderCell(text, i, sx, sy, lineH);
        }

        // SELECTED CELL ON TOP
        if (this.selectedIndex >= 0 && this.selectedIndex < this.selectableItems.size()) {
            final float sx = this.cellPosX[this.selectedIndex];
            final float sy = this.startY + this.cellPosY[this.selectedIndex] - this.scrollOffset;
            this.renderCell(text, this.selectedIndex, sx, sy, lineH);
        }

        if (useScissor) {
            glDisable(GL_SCISSOR_TEST);
            this.renderScrollbar(windowW);
        }

        DrawTool.restoreProjection();
    }

    private void renderCell(final TextRenderer text, final int index, final float baseX, final float baseY, final int lineH) {
        final T item = this.selectableItems.get(index);
        final boolean selected = index == this.selectedIndex;

        // ANIMATION OFFSETS
        final float hover = this.hoverAnim[index];
        final float click = this.clickAnim[index];
        final float expand = hover * HOVER_EXPAND - click * CLICK_SHRINK;

        final float x = baseX - expand;
        final float y = baseY - expand;
        final float w = this.computedCellWidth + expand * 2;
        final float h = this.cellHeight + expand * 2;

        // BORDER COLOR
        Color borderColor = Colors.BLUE;
        if (this.borderColorProvider != null) {
            final Color c = this.borderColorProvider.apply(item);
            if (c != null) borderColor = c;
        }

        // CHECK FOR THUMBNAIL
        boolean hasThumbnail = false;
        if (this.thumbnailProvider != null) {
            final MediaPlayer player = this.thumbnailProvider.apply(item);
            if (player != null && player.playing() && player.texture() > 0 && player.width() > 0) {
                hasThumbnail = true;

                // SCISSOR CLIP TO CELL BOUNDS SO COVER-CROP DOES NOT OVERFLOW
                glEnable(GL_SCISSOR_TEST);
                final int[] viewport = new int[4];
                glGetIntegerv(GL_VIEWPORT, viewport);
                final int vpH = viewport[3];
                glScissor((int) x, vpH - (int) y - (int) h, (int) w, (int) h);

                // FILL CELL BACKGROUND BLACK
                DrawTool.disableTextures();
                DrawTool.fill(x, y, w, h, 0f, 0f, 0f, 1f);

                // COMPUTE ASPECT-RATIO-PRESERVING BLIT REGION (COVER, CROP TO FILL)
                final float imgAspect = (float) player.width() / player.height();
                final float cellAspect = w / h;
                float blitX = x, blitY = y, blitW = w, blitH = h;
                if (imgAspect > cellAspect) {
                    blitW = h * imgAspect;
                    blitX = x + (w - blitW) / 2f;
                } else {
                    blitH = w / imgAspect;
                    blitY = y + (h - blitH) / 2f;
                }

                // RENDER THUMBNAIL TEXTURE CLIPPED TO CELL
                DrawTool.enableTextures();
                DrawTool.bindTexture((int) player.texture());
                DrawTool.color(1f, 1f, 1f, 1f);
                DrawTool.blit(blitX, blitY, blitW, blitH);

                glDisable(GL_SCISSOR_TEST);

                // DARK GRADIENT OVERLAY FOR TEXT LEGIBILITY
                DrawTool.disableTextures();
                DrawTool.fillGradientV(x, y, w, h * 0.35f,
                        0f, 0f, 0f, 0.6f,
                        0f, 0f, 0f, 0.1f);
                DrawTool.fillGradientV(x, y + h * 0.35f, w, h * 0.65f,
                        0f, 0f, 0f, 0.1f,
                        0f, 0f, 0f, 0.92f);
            }
        }

        if (!hasThumbnail) {
            // Default solid background
            DrawTool.disableTextures();
            final float bgAlpha = 0.08f + (selected ? 0.07f : 0f) + hover * 0.07f;
            DrawTool.fill(x, y, w, h,
                    borderColor.getRed() / 255f * 0.3f,
                    borderColor.getGreen() / 255f * 0.3f,
                    borderColor.getBlue() / 255f * 0.3f,
                    bgAlpha);
        }

        // BORDER (NON-ROUNDED, FIXED WIDTH MATCHING SEPARATOR BAR)
        DrawTool.disableTextures();
        final float bAlpha = selected ? 1f : (0.5f + hover * 0.5f);
        DrawTool.rect(x, y, w, h,
                borderColor.getRed() / 255f,
                borderColor.getGreen() / 255f,
                borderColor.getBlue() / 255f,
                bAlpha, this.borderWidth);

        // CONTENT LAYOUT
        final float iconSize = Math.min(h * 0.3f, 34f);
        final float gapAfterIcon = 8f;
        final float sublabelScale = 0.6f;
        final float sublabelH = lineH * sublabelScale;

        String sublabel = null;
        if (this.sublabelProvider != null) {
            sublabel = this.sublabelProvider.apply(item);
            if (sublabel != null && sublabel.isEmpty()) sublabel = null;
        }

        // TOTAL CONTENT HEIGHT FOR VERTICAL CENTERING
        float contentH = iconSize + gapAfterIcon + lineH;
        if (sublabel != null) {
            contentH += 2 + sublabelH;
        }
        final float contentStartY = y + (h - contentH) / 2f;

        // ICON
        final float iconCx = x + w / 2f;
        final float iconCy = contentStartY + iconSize / 2f;

        Color iconColor = Colors.WHITE;
        if (this.iconColorProvider != null) {
            final Color c = this.iconColorProvider.apply(item);
            if (c != null) iconColor = c;
        }

        if (this.iconProvider != null) {
            renderIcon(this.iconProvider.apply(item), iconCx, iconCy, iconSize, iconColor);
        }

        DrawTool.enableTextures();

        // LABEL TEXT
        String label = this.labelProvider.apply(item);
        final int contentWidth = (int) (w - this.innerPadding * 2);
        label = text.truncateToWidth(label, contentWidth);

        final float labelY = contentStartY + iconSize + gapAfterIcon;
        final int labelWidth = text.width(label);
        final float labelX = x + (w - labelWidth) / 2f;
        text.render(label, (int) labelX, (int) labelY, (selected || hasThumbnail) ? Colors.WHITE : Colors.GRAY);

        // SUBLABEL TEXT (SCALED SMALLER)
        if (sublabel != null) {
            final int maxSubWidth = (int) (contentWidth / sublabelScale);
            sublabel = text.truncateToWidth(sublabel, maxSubWidth);

            final float subY = labelY + lineH + 2;
            final int subWidth = (int) (text.width(sublabel) * sublabelScale);
            final float subX = x + (w - subWidth) / 2f;

            text.render(sublabel, subX, subY, hasThumbnail ? Colors.DARK_GRAY : Colors.GRAY, sublabelScale);
        }
    }

    private void updateAnimations() {
        for (int i = 0; i < this.selectableItems.size(); i++) {
            if (i == this.selectedIndex) {
                this.hoverAnim[i] = Math.min(1f, this.hoverAnim[i] + HOVER_SPEED);
            } else {
                this.hoverAnim[i] = Math.max(0f, this.hoverAnim[i] - HOVER_SPEED);
            }

            if (this.clickAnim[i] > 0) {
                this.clickAnim[i] = Math.max(0f, this.clickAnim[i] - CLICK_SPEED);
            }
        }
    }

    private void renderScrollbar(final int windowW) {
        final int scrollbarX = windowW - SCROLLBAR_WIDTH - 10;
        final int scrollbarY = this.startY;
        final int scrollbarH = this.visibleHeight;

        DrawTool.disableTextures();
        DrawTool.fill(scrollbarX, scrollbarY, SCROLLBAR_WIDTH, scrollbarH, 0.15f, 0.15f, 0.15f, 0.8f);

        final float thumbRatio = (float) this.visibleHeight / this.totalContentHeight;
        final int thumbH = Math.max(20, (int) (scrollbarH * thumbRatio));
        final float scrollRatio = (float) this.scrollOffset / (this.totalContentHeight - this.visibleHeight);
        final int thumbY = scrollbarY + (int) ((scrollbarH - thumbH) * scrollRatio);

        DrawTool.fillRounded(scrollbarX, thumbY, SCROLLBAR_WIDTH, thumbH, 4f, 0.31f, 0.71f, 1f, 0.9f);
        DrawTool.enableTextures();
    }

    // MOUSE HANDLING
    public boolean handleHover(final double mx, final double my) {
        for (int i = 0; i < this.selectableItems.size(); i++) {
            final float sx = this.cellPosX[i];
            final float sy = this.startY + this.cellPosY[i] - this.scrollOffset;
            if (mx >= sx && mx <= sx + this.computedCellWidth &&
                    my >= sy && my <= sy + this.cellHeight) {
                if (this.selectedIndex != i) {
                    this.selectedIndex = i;
                    if (this.onSelectionChanged != null) this.onSelectionChanged.run();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    public boolean handleClick(final double mx, final double my) {
        for (int i = 0; i < this.selectableItems.size(); i++) {
            final float sx = this.cellPosX[i];
            final float sy = this.startY + this.cellPosY[i] - this.scrollOffset;
            if (mx >= sx && mx <= sx + this.computedCellWidth &&
                    my >= sy && my <= sy + this.cellHeight) {
                this.selectedIndex = i;
                this.confirm();
                return true;
            }
        }
        return false;
    }

    // ICON RENDERING
    private static void renderIcon(final Icon icon, final float cx, final float cy,
                                    final float size, final Color tint) {
        if (icon == null) return;

        final float half = size / 2f;
        final float r = tint.getRed() / 255f;
        final float g = tint.getGreen() / 255f;
        final float b = tint.getBlue() / 255f;

        switch (icon) {
            case PLAY -> DrawTool.fillTriangle(
                    cx - half * 0.5f, cy - half * 0.7f,
                    cx + half * 0.7f, cy,
                    cx - half * 0.5f, cy + half * 0.7f,
                    r, g, b, 1f);

            case UPLOAD -> {
                final float shaftW = size * 0.2f;
                DrawTool.fill(cx - shaftW / 2, cy - half * 0.3f, shaftW, half * 0.8f, r, g, b, 1f);
                DrawTool.fillTriangle(
                        cx, cy - half * 0.7f,
                        cx - half * 0.6f, cy - half * 0.1f,
                        cx + half * 0.6f, cy - half * 0.1f,
                        r, g, b, 1f);
                DrawTool.fill(cx - half * 0.5f, cy + half * 0.6f, size * 0.5f, 2f, r, g, b, 0.7f);
            }

            case CLEANUP -> {
                DrawTool.color(r, g, b, 1f);
                GL11.glLineWidth(2.5f);
                DrawTool.line(cx, cy - half * 0.7f, cx, cy + half * 0.3f);
                for (int i = -2; i <= 2; i++) {
                    DrawTool.line(cx + i * size * 0.1f, cy + half * 0.3f,
                                  cx + i * size * 0.12f, cy + half * 0.7f);
                }
            }

            case FOLDER -> {
                final float fw = size * 0.8f;
                final float fh = size * 0.5f;
                final float tabW = fw * 0.35f;
                final float tabH = fh * 0.22f;
                final float fx = cx - fw / 2;
                final float fy = cy - fh / 2 + tabH / 2;
                DrawTool.fill(fx, fy - tabH, tabW, tabH + 2, r, g, b, 0.9f);
                DrawTool.fill(fx, fy, fw, fh, r, g, b, 0.8f);
            }

            case EXIT -> {
                DrawTool.fillCircle(cx, cy, half * 0.75f, r * 0.2f, g * 0.2f, b * 0.2f, 0.5f);
                DrawTool.color(r, g, b, 1f);
                GL11.glLineWidth(3f);
                DrawTool.line(cx - half * 0.35f, cy - half * 0.35f, cx + half * 0.35f, cy + half * 0.35f);
                DrawTool.line(cx + half * 0.35f, cy - half * 0.35f, cx - half * 0.35f, cy + half * 0.35f);
            }

            case CUSTOM -> {
                final float thickness = 3f;
                DrawTool.fill(cx - half * 0.4f, cy - thickness / 2, size * 0.4f, thickness, r, g, b, 1f);
                DrawTool.fill(cx - thickness / 2, cy - half * 0.4f, thickness, size * 0.4f, r, g, b, 1f);
            }

            case STATUS_READY -> {
                DrawTool.color(r, g, b, 1f);
                GL11.glLineWidth(3f);
                DrawTool.lineStrip(new float[] {
                    cx - half * 0.4f,  cy + half * 0.05f,
                    cx - half * 0.1f,  cy + half * 0.35f,
                    cx + half * 0.45f, cy - half * 0.35f
                });
            }

            case STATUS_ERROR -> {
                DrawTool.color(r, g, b, 1f);
                GL11.glLineWidth(3f);
                DrawTool.line(cx - half * 0.35f, cy - half * 0.35f, cx + half * 0.35f, cy + half * 0.35f);
                DrawTool.line(cx + half * 0.35f, cy - half * 0.35f, cx - half * 0.35f, cy + half * 0.35f);
            }

            case STATUS_LOADING -> {
                final long time = System.currentTimeMillis();
                final float baseAngle = (time % 1200) / 1200f * (float) (2 * Math.PI);
                final float orbitR = half * 0.45f;
                final float dotR = size * 0.07f;
                for (int i = 0; i < 3; i++) {
                    final float angle = baseAngle + i * (float) (2 * Math.PI / 3);
                    final float alpha = 1f - i * 0.25f;
                    DrawTool.fillCircle(
                            cx + (float) Math.cos(angle) * orbitR,
                            cy + (float) Math.sin(angle) * orbitR,
                            dotR, r, g, b, alpha);
                }
            }

            case STATUS_UNKNOWN -> {
                DrawTool.color(r, g, b, 0.7f);
                GL11.glLineWidth(2f);
                final float[] circle = new float[32];
                for (int i = 0; i < 16; i++) {
                    final float angle = (float) (i * 2 * Math.PI / 16);
                    circle[i * 2] = cx + (float) Math.cos(angle) * half * 0.4f;
                    circle[i * 2 + 1] = cy + (float) Math.sin(angle) * half * 0.4f;
                }
                DrawTool.lineLoop(circle);
                DrawTool.fillCircle(cx, cy, size * 0.06f, r, g, b, 0.7f);
            }

            case STATUS_NULL -> DrawTool.fill(cx - half * 0.3f, cy - 1.5f, size * 0.3f, 3f, r, g, b, 0.6f);
        }
    }

    // INTERNAL
    private record GridEntry<T>(String label, T item, boolean header) {
    }
}
