package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.Parent;

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
 * Region selector for IPTV channels as a fully-retained screen: a {@link Header} band, a panel frame
 * container that ports the imperative panel chrome (gradient plate, section head, amber anchor cubes),
 * and a {@link ListView} of selectable region rows that owns scrolling, hit-testing and selection.
 */
public final class RegionSelectorScreen extends Screen {

    private static final int ROW_H = 54;
    private static final int ROW_GAP = 8;
    // CACHED, REFERENCE-STABLE KEYBIND LIST — LETS KeybindsBar SKIP ITS REBUILD ON THE STEADY PER-FRAME PATH
    private static final List<Keybind> KEYS = List.of(
            new Keybind("UP/DOWN", "Region"), new Keybind("ENTER", "Select"), new Keybind("ESC", "Back"));

    private final Consumer<HomeScreen.Action> navigator;
    private final List<String> regions = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private int totalChannels;
    private String detectedRegion = "UNKNOWN";

    private ListView<Integer> list;

    public RegionSelectorScreen(final TextRenderer text, final AppContext ctx,
                                final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    @Override
    protected Element<?> build() {
        this.list = new ListView<Integer>()
                .rowHeight(ROW_H)
                .spacing(ROW_GAP)
                .rowFactory((index, pos) -> new RegionRow(this, index))
                .onSelect((index, pos) -> this.openSelected())
                .selectOnHover(true)
                // THE ROWS SELF-DRAW THEIR SELECTION FILL/GLOW, SO SUPPRESS THE LIST'S OWN HIGHLIGHTS
                .selectionColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .hoverColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .width(Element.MAX_PARENT)
                .height(Element.MAX_PARENT);
        return Parent.column()
                .width(Element.MAX_PARENT)
                .height(Element.MAX_PARENT)
                .add(new Header().name("Television").sub("region selector").right("v" + WaterMedia.VERSION))
                .add(new PanelPane().width(Element.MAX_PARENT).weight(1f).add(this.list));
    }

    @Override
    public void onEnter() {
        super.onEnter();
        this.rebuildRegions();
        this.populate();
        this.list.selection(0);
    }

    @Override
    public List<Keybind> keybinds() {
        return KEYS;
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        // THE TREE (LIST ROWS, DIALOGS) GETS THE KEY FIRST; SCREEN NAVIGATION ONLY ON UNCONSUMED RELEASES
        if (super.dispatchKey(key, action)) return true;
        if (action != GLFW_RELEASE) return false;
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
            default -> {
                return false;
            }
        }
        return true;
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

    private int optionCount() {
        // NO DETECTED REGION → NO SYSTEM ROW; OTHERWISE A DUPLICATE "GLOBAL" ROW WOULD APPEAR AT INDEX 1
        return (this.detectedRegion != null ? 2 : 1) + this.regions.size();
    }

    private String regionFor(final int index) {
        if (index == 0) return null;
        final int firstRegion = this.detectedRegion != null ? 2 : 1;
        if (this.detectedRegion != null && index == 1) return this.detectedRegion;
        // OUT-OF-RANGE (INCLUDING AN EMPTY regions LIST) MAPS TO GLOBAL RATHER THAN CLAMPING INTO THE LIST OR THROWING
        final int i = index - firstRegion;
        return i >= 0 && i < this.regions.size() ? this.regions.get(i) : null;
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

    // PANEL FRAME AROUND THE REGION LIST — PORTS THE IMPERATIVE PANEL CHROME (GRADIENT PLATE, HAIRLINE
    // BORDER, AMBER ANCHOR CUBES, SECTION HEAD) AND SIZES/CENTERS THE LIST WITH THE LEGACY RECT MATH.
    private final class PanelPane extends Group<PanelPane> {

        private int panelX;
        private int panelY;
        private int panelW;
        private int panelH;

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            // LEGACY INSETS: 10px CONTENT GAP + 18px STACK MARGIN → 28px ON EACH VERTICAL SIDE OF THIS PANE
            this.panelW = Math.min(760, innerAvailWidth - 64);
            this.panelH = Math.max(260, innerAvailHeight - 56);
            final int rowsW = this.panelW - 48;
            final int rowsH = Math.max(ROW_H, this.panelH - 78);
            for (final Element<?> child: this.children) child.measure(rowsW, rowsH);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            this.panelX = this.innerLeft() + (this.innerWidth() - this.panelW) / 2;
            this.panelY = this.innerTop() + 28;
            for (final Element<?> child: this.children) child.layout(this.panelX + 24, this.panelY + 58);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            canvas.gradientV(this.panelX, this.panelY, this.panelW, this.panelH,
                    AppTheme.alpha(AppTheme.BG_2, 217), AppTheme.alpha(AppTheme.BG_1, 217));
            canvas.stroke(this.panelX, this.panelY, this.panelW, this.panelH, AppTheme.STROKE_BRIGHT, 1f);
            this.cube(canvas, this.panelX - 1, this.panelY - 1);
            this.cube(canvas, this.panelX + this.panelW - 9, this.panelY + this.panelH - 9);

            // SECTION HEAD — NEON TAB + BOLD TITLE + FAINT LIVE COUNT
            final TextRenderer text = canvas.text();
            final int headX = this.panelX + 18;
            final int headY = this.panelY + 21;
            canvas.fill(headX, headY, 4, 21, AppTheme.NEON);
            canvas.glow(headX, headY, 4, 21, 0f, AppTheme.NEON, 0.16f);
            canvas.text("REGIONS", headX + 18,
                    headY + Math.max(0, (21 - text.glyphHeightBold(AppTheme.TEXT_SECTION)) / 2),
                    AppTheme.NEON, AppTheme.TEXT_SECTION, true);
            canvas.text(optionCount() + " AVAILABLE",
                    headX + 30 + text.widthBold("REGIONS", AppTheme.TEXT_SECTION),
                    headY + Math.max(0, (21 - text.glyphHeight(AppTheme.TEXT_BODY)) / 2),
                    AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
            super.onDraw(canvas);
        }

        private void cube(final Canvas canvas, final int x, final int y) {
            canvas.glow(x, y, 10, 10, 0f, AppTheme.AMBER, 0.32f);
            canvas.fill(x, y, 10, 10, AppTheme.AMBER);
        }
    }

    // ONE SELECTABLE REGION ROW — PORTS THE ORIGINAL drawRegionRow ONTO ITS OWN left/top/measured BOX AND
    // READS THE ListView-DRIVEN selected/hovered STATE. index 0 = GLOBAL, THEN THE DETECTED/SYSTEM ROW
    // (ONLY WHEN A REGION WAS DETECTED), THEN THE REMAINING REGIONS.
    private static final class RegionRow extends Element<RegionRow> {

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
            final boolean detected = this.index == 1 && this.screen.detectedRegion != null;
            final boolean fixed = this.index == 0 || detected;
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
