package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Dimension;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.awt.Color;
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
 * Region selector for IPTV channels.
 */
public final class RegionSelectorScreen extends Screen {

    private static final int ROW_H = 54;
    private static final int ROW_STEP = 62;

    private final Consumer<HomeScreen.Action> navigator;
    private final List<String> regions = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private int totalChannels;
    private int selectedIndex;
    private int scrollOffset;
    private int visibleRows = 1;
    private String detectedRegion = "UNKNOWN";

    public RegionSelectorScreen(final TextRenderer text, final AppContext ctx,
                                final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    @Override
    public void onEnter() {
        this.rebuildRegions();
        this.selectedIndex = 0;
        this.scrollOffset = 0;
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
    public void render(final int windowW, final int windowH) {
        AppChrome.screen(this.text, this.ctx, windowW, windowH, "Television", "region selector", "v" + WaterMedia.VERSION);
        this.hits.clear();

        final int top = AppChrome.contentTop() + 18;
        final int bottom = AppChrome.contentBottom(windowH) - 18;
        final int panelW = Math.min(760, windowW - 64);
        final int panelX = (windowW - panelW) / 2;
        final int panelH = Math.max(260, bottom - top);

        RenderSystem.setupOrtho(windowW, windowH);
        AppChrome.panel(panelX, top, panelW, panelH, true);
        AppChrome.sectionHead(this.text, "Regions", this.optionCount() + " available", panelX + 18, top + 18);

        final int rowX = panelX + 24;
        final int rowW = panelW - 48;
        int rowY = top + 58;
        this.drawRegionRow(rowX, rowY, rowW, ROW_H, 0, true);
        rowY += ROW_STEP;
        this.drawRegionRow(rowX, rowY, rowW, ROW_H, 1, true);
        rowY += ROW_STEP + 10;
        RenderSystem.lineH(rowX, rowY - 10, rowW, AppTheme.STROKE_BRIGHT, 1f);

        final int listY = rowY;
        final int listH = Math.max(ROW_H, top + panelH - listY - 20);
        this.visibleRows = Math.max(1, listH / ROW_STEP);
        this.ensureVisible(this.visibleRows);
        RenderSystem.clip(rowX, listY, rowW, listH, windowH);
        for (int i = 0; i < this.visibleRows && i + this.scrollOffset < this.regions.size(); i++) {
            this.drawRegionRow(rowX, listY + i * ROW_STEP, rowW, ROW_H, i + this.scrollOffset + 2, false);
        }
        RenderSystem.clearClip();
        if (this.regions.size() > this.visibleRows) {
            this.renderScrollBar(panelX + panelW - 14, listY, listH, this.visibleRows);
        }
        RenderSystem.restoreProjection();
    }

    private void drawRegionRow(final int x, final int y, final int w, final int h,
                               final int index, final boolean fixed) {
        final String region = this.regionFor(index);
        final boolean selected = this.selectedIndex == index;
        final boolean detected = index == 1;
        final Color accent = selected ? AppTheme.GREEN : fixed ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT;
        if (selected) RenderSystem.glowRect(x, y, w, h, 0f, AppTheme.GREEN, 0.26f);
        RenderSystem.fill(x, y, w, h, selected ? AppTheme.alpha(AppTheme.NEON_DARK, 82) : AppTheme.alpha(AppTheme.BG_2, 220));
        RenderSystem.rect(x, y, w, h, accent, selected ? 2f : 1.5f);
        PixelIcon.draw("tv", x + 16, y + Math.max(0, (h - 24) / 2), 24, selected ? AppTheme.GREEN : AppTheme.NEON_LIGHT);

        final String label = index == 0 ? "GLOBAL" : region.toUpperCase(Locale.ROOT);
        final int count = index == 0 ? this.totalChannels : this.counts.getOrDefault(region, 0);
        final String meta = count + (count == 1 ? " CHANNEL" : " CHANNELS");
        final int tagW = Math.max(98, this.text.width(meta, AppTheme.TEXT_SUBTITLE) + 22);
        final int labelMax = Math.max(80, w - 72 - tagW - (detected ? 96 : 20));
        this.text.renderBold(this.text.truncateToWidth(label, labelMax, AppTheme.TEXT_BUTTON, java.awt.Font.BOLD),
                x + 52, this.centerBoldTextY(y, h, AppTheme.TEXT_BUTTON), selected ? AppTheme.GREEN : AppTheme.TEXT, AppTheme.TEXT_BUTTON);

        if (detected) {
            final int badgeX = x + 62 + Math.min(labelMax, this.text.widthBold(label, AppTheme.TEXT_BUTTON));
            RenderSystem.fill(badgeX, y + 16, 76, 22, AppTheme.alpha(AppTheme.BG_1, 190));
            RenderSystem.rect(badgeX, y + 16, 76, 22, AppTheme.AMBER, 1f);
            this.text.render("SYSTEM", badgeX + 10, this.centerTextY(y + 16, 22, AppTheme.TEXT_SUBTITLE), AppTheme.AMBER, AppTheme.TEXT_SUBTITLE);
        }

        final int tagX = x + w - tagW - 12;
        RenderSystem.fill(tagX, y + 16, tagW, 22, AppTheme.alpha(AppTheme.BG_1, 190));
        RenderSystem.rect(tagX, y + 16, tagW, 22, selected ? AppTheme.GREEN : AppTheme.STROKE, 1f);
        this.text.render(meta, tagX + 11, this.centerTextY(y + 16, 22, AppTheme.TEXT_SUBTITLE),
                selected ? AppTheme.GREEN : AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        this.hits.add(new Hit(new Dimension(x, y, w, h), index));
    }

    private void openSelected() {
        final String region = this.regionFor(this.selectedIndex);
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
        this.navigator.accept(HomeScreen.Action.MRL_SELECTOR);
    }

    @Override
    protected void onKeyRelease(final int key) {
        switch (key) {
            case GLFW_KEY_UP -> this.move(-1);
            case GLFW_KEY_DOWN -> this.move(1);
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.openSelected();
            case GLFW_KEY_ESCAPE -> this.navigator.accept(HomeScreen.Action.BACK);
        }
    }

    @Override
    public void handleMouseMove(final double mx, final double my) {
        for (final Hit hit: this.hits) {
            if (!hit.bounds.contains(mx, my)) continue;
            if (this.selectedIndex != hit.index) {
                this.selectedIndex = hit.index;
                this.ctx.playSelectionSound();
                this.ctx.requestRender();
            }
            return;
        }
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        for (final Hit hit: this.hits) {
            if (!hit.bounds.contains(mx, my)) continue;
            this.selectedIndex = hit.index;
            this.openSelected();
            this.ctx.playSelectionSound();
            return;
        }
    }

    @Override
    public void handleScroll(final double yOffset) {
        final int max = Math.max(0, this.regions.size() - this.visibleRows);
        this.scrollOffset = Math.max(0, Math.min(max, this.scrollOffset - (int) Math.signum(yOffset)));
        this.ctx.requestRender();
    }

    @Override
    public String instructions() {
        return "UP/DOWN: Region | ENTER: Select | ESC: Back";
    }

    private void move(final int delta) {
        this.selectedIndex = Math.max(0, Math.min(this.optionCount() - 1, this.selectedIndex + delta));
        this.ctx.playSelectionSound();
        this.ctx.requestRender();
    }

    private void ensureVisible(final int visibleRows) {
        if (this.selectedIndex < 2) return;
        final int listIndex = this.selectedIndex - 2;
        if (listIndex < this.scrollOffset) this.scrollOffset = listIndex;
        if (listIndex >= this.scrollOffset + visibleRows) this.scrollOffset = listIndex - visibleRows + 1;
        this.scrollOffset = Math.max(0, Math.min(Math.max(0, this.regions.size() - visibleRows), this.scrollOffset));
    }

    private void renderScrollBar(final int x, final int y, final int h, final int visibleRows) {
        RenderSystem.fill(x, y, 4, h, AppTheme.alpha(AppTheme.BG_3, 190));
        final int thumbH = Math.max(24, Math.round(h * (visibleRows / (float) this.regions.size())));
        final int maxScroll = Math.max(1, this.regions.size() - visibleRows);
        final int thumbY = y + Math.round((h - thumbH) * (this.scrollOffset / (float) maxScroll));
        RenderSystem.fill(x, thumbY, 4, thumbH, AppTheme.NEON);
        RenderSystem.glowRect(x, thumbY, 4, thumbH, 0f, AppTheme.NEON, 0.24f);
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
        return exact.isBlank() ? "UNKNOWN" : exact;
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

    private int centerTextY(final int y, final int h, final float scale) {
        return y + Math.max(0, (h - this.text.glyphHeight(scale)) / 2);
    }

    private int centerBoldTextY(final int y, final int h, final float scale) {
        return y + Math.max(0, (h - this.text.glyphHeightBold(scale)) / 2);
    }

    private record Hit(Dimension bounds, int index) {
    }
}
