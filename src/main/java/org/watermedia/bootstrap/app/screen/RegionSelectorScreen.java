package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.view.Canvas;
import org.watermedia.bootstrap.app.view.ListView;
import org.watermedia.bootstrap.app.view.View;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Region selector for IPTV channels, built on the view tree: the window chrome and panel frame are drawn
 * by {@link AppChrome}, and the selectable region rows live in a {@link ListView} that owns scrolling,
 * hit-testing and the selection highlight.
 */
public final class RegionSelectorScreen extends ViewScreen {

    private static final int ROW_H = 54;
    private static final int ROW_GAP = 8;

    private final Consumer<HomeScreen.Action> navigator;
    private final List<String> regions = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private int totalChannels;
    private String detectedRegion = "UNKNOWN";

    private ListView<Integer> list;
    // PANEL ROWS RECT, COMPUTED IN renderChrome AND CONSUMED BY THE CONTENT-RECT OVERRIDES
    private int rowsX;
    private int rowsY;
    private int rowsW;
    private int rowsH;

    public RegionSelectorScreen(final TextRenderer text, final AppContext ctx,
                                final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    @Override
    protected View<?> build() {
        this.list = new ListView<Integer>()
                .rowHeight(ROW_H)
                .spacing(ROW_GAP)
                .rowFactory((index, pos) -> new RegionRow(this, index))
                .onSelect((index, pos) -> this.openSelected())
                .selectOnHover(true)
                // THE ROWS SELF-DRAW THEIR SELECTION FILL/GLOW, SO SUPPRESS THE LIST'S OWN HIGHLIGHTS
                .selectionColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .hoverColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .width(View.MATCH_PARENT)
                .height(View.MATCH_PARENT);
        return this.list;
    }

    @Override
    public void onEnter() {
        super.onEnter();
        this.rebuildRegions();
        this.populate();
        this.list.selection(0);
    }

    private void populate() {
        final List<Integer> options = new ArrayList<>();
        for (int i = 0; i < this.optionCount(); i++) options.add(i);
        this.list.items(options);
    }

    private void rebuildRegions() {
        this.regions.clear();
        this.counts.clear();
        this.totalChannels = 0;
        final TreeSet<String> available = new TreeSet<>();
        for (final AppContext.IptvChannel channel: this.ctx.iptvChannels) {
            if (channel == null || blank(channel.url())) continue;
            this.totalChannels++;
            final String region = cleanRegion(channel.region());
            if (region.isEmpty()) continue;
            available.add(region);
            this.counts.merge(region, 1, Integer::sum);
        }
        this.detectedRegion = this.detectRegion(available);
        for (final String region: available) {
            if (!region.equalsIgnoreCase(this.detectedRegion)) this.regions.add(region);
        }
    }

    @Override
    protected void renderChrome(final int windowW, final int windowH) {
        AppChrome.screen(this.text, this.ctx, windowW, windowH, "Television", "region selector", "v" + WaterMedia.VERSION);

        final int top = AppChrome.contentTop() + 18;
        final int bottom = AppChrome.contentBottom(windowH) - 18;
        final int panelW = Math.min(760, windowW - 64);
        final int panelX = (windowW - panelW) / 2;
        final int panelH = Math.max(260, bottom - top);

        RenderSystem.setupOrtho(windowW, windowH);
        AppChrome.panel(panelX, top, panelW, panelH, true);
        AppChrome.sectionHead(this.text, "Regions", this.optionCount() + " available", panelX + 18, top + 18);

        this.rowsX = panelX + 24;
        this.rowsY = top + 58;
        this.rowsW = panelW - 48;
        this.rowsH = Math.max(ROW_H, top + panelH - this.rowsY - 20);
    }

    @Override
    protected int contentX(final int windowW, final int windowH) {
        return this.rowsX;
    }

    @Override
    protected int contentY(final int windowW, final int windowH) {
        return this.rowsY;
    }

    @Override
    protected int contentW(final int windowW, final int windowH) {
        return this.rowsW;
    }

    @Override
    protected int contentH(final int windowW, final int windowH) {
        return this.rowsH;
    }

    @Override
    protected void onKeyRelease(final int key) {
        switch (key) {
            case GLFW_KEY_UP -> {
                this.list.moveSelection(-1);
                this.ctx.playSelectionSound();
            }
            case GLFW_KEY_DOWN -> {
                this.list.moveSelection(1);
                this.ctx.playSelectionSound();
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.openSelected();
            case GLFW_KEY_ESCAPE -> this.navigator.accept(HomeScreen.Action.BACK);
        }
    }

    private void openSelected() {
        final String region = this.regionFor(this.list.selectedIndex());
        final List<AppContext.IptvChannel> channels = new ArrayList<>();
        for (final AppContext.IptvChannel channel: this.ctx.iptvChannels) {
            if (channel == null || blank(channel.url())) continue;
            if (region == null || region.equalsIgnoreCase(cleanRegion(channel.region()))) {
                channels.add(channel);
            }
        }
        if (channels.isEmpty()) {
            this.ctx.showError("No Channels", "No IPTV channels were found for the selected region.", null);
            return;
        }

        final Set<String> names = new HashSet<>();
        final List<AppContext.TestURI> uris = new ArrayList<>(channels.size());
        this.ctx.groupMRLs.clear();
        for (final AppContext.IptvChannel channel: channels) {
            final String name = this.channelName(channel, names);
            uris.add(new AppContext.TestURI(name, channel.url(), false));
        }

        this.ctx.selectedIptvRegion = region == null ? "GLOBAL" : region;
        final String groupName = region == null ? "TELEVISION" : "TELEVISION " + region.toUpperCase(Locale.ROOT);
        this.ctx.selectedGroup = new AppContext.URIGroup(groupName, uris.toArray(AppContext.TestURI[]::new));
        this.ctx.playSelectionSound();
        this.navigator.accept(HomeScreen.Action.MRL_SELECTOR);
    }

    @Override
    public String instructions() {
        return "UP/DOWN: Region | ENTER: Select | ESC: Back";
    }

    private int optionCount() {
        return 2 + this.regions.size();
    }

    private String regionFor(final int index) {
        if (index == 0) return null;
        if (index == 1) return this.detectedRegion;
        return this.regions.get(Math.max(0, Math.min(this.regions.size() - 1, index - 2)));
    }

    private String detectRegion(final Set<String> available) {
        final Locale locale = Locale.getDefault();
        final String language = locale.getLanguage();
        final String country = locale.getCountry();
        final String exact = language.isBlank() || country.isBlank() ? "" : language + "_" + country;
        if (available.contains(exact)) return exact;
        if (!country.isBlank()) {
            for (final String region: available) {
                if (region.endsWith("_" + country)) return region;
            }
        }
        // ONLY SURFACE A DETECTED REGION THAT ACTUALLY HAS CHANNELS; OTHERWISE FALL BACK TO GLOBAL (NULL)
        return null;
    }

    private String channelName(final AppContext.IptvChannel channel, final Set<String> used) {
        String base = blank(channel.name()) ? blank(channel.tvgId()) ? channel.url() : channel.tvgId() : channel.name();
        base = base.trim();
        String name = base;
        if (used.contains(name) && !blank(channel.group())) name = base + " - " + channel.group().trim();
        int copy = 2;
        while (!used.add(name)) {
            name = base + " #" + copy++;
        }
        return name;
    }

    private static String cleanRegion(final String region) {
        return region == null ? "" : region.trim();
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }

    // ONE SELECTABLE REGION ROW — PORTS THE ORIGINAL drawRegionRow ONTO ITS OWN left/top/measured BOX AND
    // READS THE ListView-DRIVEN selected/hovered STATE. index 0 = GLOBAL, index 1 = DETECTED/SYSTEM, 2+ = REGION.
    private static final class RegionRow extends View<RegionRow> {

        private final RegionSelectorScreen screen;
        private final int index;

        private RegionRow(final RegionSelectorScreen screen, final int index) {
            this.screen = screen;
            this.index = index;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final TextRenderer text = canvas.text();
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            final String region = this.screen.regionFor(this.index);
            final boolean fixed = this.index == 0 || this.index == 1;
            final boolean detected = this.index == 1;
            final Color accent = this.selected ? AppTheme.GREEN : fixed ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT;

            if (this.selected) canvas.glow(x, y, w, h, 0f, AppTheme.GREEN, 0.26f);
            canvas.fill(x, y, w, h, this.selected ? AppTheme.alpha(AppTheme.NEON_DARK, 82) : AppTheme.alpha(AppTheme.BG_2, 220));
            canvas.stroke(x, y, w, h, accent, this.selected ? 2f : 1.5f);
            PixelIcon.draw("tv", x + 16, y + Math.max(0, (h - 24) / 2), 24, this.selected ? AppTheme.GREEN : AppTheme.NEON_LIGHT);

            final String label = this.index == 0 || region == null ? "GLOBAL" : region.toUpperCase(Locale.ROOT);
            final int count = this.index == 0 || region == null ? this.screen.totalChannels : this.screen.counts.getOrDefault(region, 0);
            final String meta = count + (count == 1 ? " CHANNEL" : " CHANNELS");
            final int tagW = Math.max(98, text.width(meta, AppTheme.TEXT_SUBTITLE) + 22);
            final int labelMax = Math.max(80, w - 72 - tagW - (detected ? 96 : 20));
            final int labelY = y + Math.max(0, (h - text.glyphHeightBold(AppTheme.TEXT_BUTTON)) / 2);
            canvas.text(text.truncateToWidth(label, labelMax, AppTheme.TEXT_BUTTON, Font.BOLD),
                    x + 52, labelY, this.selected ? AppTheme.GREEN : AppTheme.TEXT, AppTheme.TEXT_BUTTON, true);

            if (detected) {
                final int badgeX = x + 62 + Math.min(labelMax, text.widthBold(label, AppTheme.TEXT_BUTTON));
                canvas.fill(badgeX, y + 16, 76, 22, AppTheme.alpha(AppTheme.BG_1, 190));
                canvas.stroke(badgeX, y + 16, 76, 22, AppTheme.AMBER, 1f);
                canvas.text("SYSTEM", badgeX + 10, this.centerY(text, y + 16, 22), AppTheme.AMBER, AppTheme.TEXT_SUBTITLE, false);
            }

            final int tagX = x + w - tagW - 12;
            canvas.fill(tagX, y + 16, tagW, 22, AppTheme.alpha(AppTheme.BG_1, 190));
            canvas.stroke(tagX, y + 16, tagW, 22, this.selected ? AppTheme.GREEN : AppTheme.STROKE, 1f);
            canvas.text(meta, tagX + 11, this.centerY(text, y + 16, 22),
                    this.selected ? AppTheme.GREEN : AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
        }

        private int centerY(final TextRenderer text, final int y, final int h) {
            return y + Math.max(0, (h - text.glyphHeight(AppTheme.TEXT_SUBTITLE)) / 2);
        }
    }
}
