package org.watermedia.bootstrap.app;

import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.tools.IOTool;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import static org.lwjgl.glfw.GLFW.glfwSetWindowIcon;

/**
 * GPU texture handles for the banner, icon and duck assets. The textures are created against the active
 * render context through {@link RenderSystem}; on an engine hot-swap they are dropped with {@link #dispose()}
 * (the old context owns them) and recreated with {@link #load(AppContext)} against the fresh context.
 */
public final class Assets {
    public int bannerId = -1;
    public int bannerWidth;
    public int bannerHeight;
    public int bannerGlowId = -1;
    public int bannerGlowWidth;
    public int bannerGlowHeight;
    public int iconId = -1;
    public int iconWidth;
    public int iconHeight;
    public int iconGlowId = -1;
    public int iconGlowWidth;
    public int iconGlowHeight;
    public int[] duckFrameIds = new int[0];
    public int duckFrameWidth;
    public int duckFrameHeight;

    /**
     * Decodes the icon, duck frames and banner into GPU textures against the active render engine and
     * installs the window icon on the current window handle. Icon and banner are decoded with ImageIO so
     * they are available before the codecs API loads.
     */
    public void load(final AppContext ctx) {
        this.loadIcon(ctx.windowHandle);
        this.loadDuckFrames();
        this.loadBanner();
    }

    /**
     * Drops every GPU texture handle. The backing textures die with the render context they were created
     * against, so no {@code glDelete} is issued — this only zeroes the handles so nothing draws a stale id
     * before {@link #load(AppContext)} recreates them.
     */
    public void dispose() {
        this.bannerId = -1;
        this.bannerGlowId = -1;
        this.iconId = -1;
        this.iconGlowId = -1;
        this.duckFrameIds = new int[0];
    }

    // Icon and banner are decoded with ImageIO so they're available before
    // CodecsAPI loads — this lets the loading splash render the banner.
    private void loadIcon(final long window) {
        // WINDOW/TASKBAR ICON — THE OS SCALES IT DOWN SMALL, SO USE THE DEDICATED icon.png
        try (final InputStream in = IOTool.jarOpenFile("icon.png")) {
            if (in != null) {
                final BufferedImage img = ImageIO.read(in);
                if (img != null) {
                    final int w = img.getWidth(), h = img.getHeight();
                    final ByteBuffer buffer = argbToRgbaBuffer(img);

                    final GLFWImage.Buffer icons = GLFWImage.malloc(1);
                    icons.position(0).width(w).height(h).pixels(buffer);
                    glfwSetWindowIcon(window, icons);

                    icons.free();
                    MemoryUtil.memFree(buffer);
                }
            }
        } catch (final Exception e) {
            System.err.println("Failed to load window icon: " + e.getMessage());
        }

        // ON-SCREEN LOGO (TITLE BAR, LOADING SPLASH, HOME HERO) — RENDERED LARGE, SO USE THE HIGHER-RES pack.png
        try (final InputStream in = IOTool.jarOpenFile("pack.png")) {
            if (in == null) return;
            final BufferedImage img = ImageIO.read(in);
            if (img == null) return;

            final int w = img.getWidth(), h = img.getHeight();
            final ByteBuffer textureBuffer = argbToRgbaBuffer(img);
            this.iconWidth = w;
            this.iconHeight = h;
            this.iconId = RenderSystem.createTexture(w, h, textureBuffer);
            MemoryUtil.memFree(textureBuffer);

            final TextureData glow = createGlowTexture(img, new Color(110, 168, 255), 12, 0.72f);
            this.iconGlowId = glow.textureId();
            this.iconGlowWidth = glow.width();
            this.iconGlowHeight = glow.height();
        } catch (final Exception e) {
            System.err.println("Failed to load logo texture: " + e.getMessage());
        }
    }

    private void loadDuckFrames() {
        final ArrayList<Integer> frames = new ArrayList<>();
        int frameWidth = 0;
        int frameHeight = 0;

        for (int i = 0; ; i++) {
            final String resource = String.format("assets/duck/%02d.png", i);
            final InputStream stream = IOTool.jarOpenFile(resource);
            if (stream == null) break;

            try (stream) {
                final BufferedImage img = ImageIO.read(stream);
                if (img == null) continue;
                if (frameWidth <= 0 || frameHeight <= 0) {
                    frameWidth = img.getWidth();
                    frameHeight = img.getHeight();
                }

                final ByteBuffer buffer = argbToRgbaBuffer(img);
                frames.add(RenderSystem.createTexture(img.getWidth(), img.getHeight(), buffer));
                MemoryUtil.memFree(buffer);
            } catch (final Exception e) {
                System.err.println("Failed to load duck frame " + resource + ": " + e.getMessage());
            }
        }

        this.duckFrameIds = frames.stream().mapToInt(Integer::intValue).toArray();
        this.duckFrameWidth = frameWidth;
        this.duckFrameHeight = frameHeight;
    }

    private void loadBanner() {
        try (final InputStream in = IOTool.jarOpenFile("banner.png")) {
            final BufferedImage img = ImageIO.read(in);
            if (img == null) return;

            this.bannerWidth = img.getWidth();
            this.bannerHeight = img.getHeight();

            final ByteBuffer buffer = argbToRgbaBuffer(img);
            this.bannerId = RenderSystem.createTexture(this.bannerWidth, this.bannerHeight, buffer);
            MemoryUtil.memFree(buffer);

            final TextureData glow = createGlowTexture(img, new Color(110, 168, 255), 48, 0.8f);
            this.bannerGlowId = glow.textureId();
            this.bannerGlowWidth = glow.width();
            this.bannerGlowHeight = glow.height();
        } catch (final Exception e) {
            System.err.println("Failed to load banner: " + e.getMessage());
        }
    }

    private static TextureData createGlowTexture(final BufferedImage source, final Color color,
                                                 final int radius, final float strength) {
        final BufferedImage glow = createAlphaGlow(source, color, radius, strength);
        final ByteBuffer buffer = argbToRgbaBuffer(glow);
        final int textureId = RenderSystem.createTexture(glow.getWidth(), glow.getHeight(), buffer);
        MemoryUtil.memFree(buffer);
        return new TextureData(textureId, glow.getWidth(), glow.getHeight());
    }

    private static BufferedImage createAlphaGlow(final BufferedImage source, final Color color,
                                                 final int radius, final float strength) {
        final int pad = Math.max(1, radius * 3);
        final int w = source.getWidth() + pad * 2;
        final int h = source.getHeight() + pad * 2;
        int[] alpha = new int[w * h];

        // EXTRAE SOLO LA SILUETA ALFA PARA QUE EL GLOW RESPETE PNGS TRANSPARENTES.
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                alpha[(y + pad) * w + x + pad] = (source.getRGB(x, y) >>> 24) & 0xFF;
            }
        }

        for (int i = 0; i < 3; i++) {
            alpha = boxBlur(alpha, w, h, radius);
        }

        final int[] pixels = new int[w * h];
        final int rgb = color.getRGB() & 0x00FFFFFF;
        for (int i = 0; i < alpha.length; i++) {
            final int a = Math.min(255, Math.round(alpha[i] * strength));
            pixels[i] = (a << 24) | rgb;
        }

        final BufferedImage glow = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        glow.setRGB(0, 0, w, h, pixels, 0, w);
        return glow;
    }

    private static int[] boxBlur(final int[] source, final int w, final int h, final int radius) {
        final int[] horizontal = new int[w * h];
        final int[] output = new int[w * h];
        final int window = radius * 2 + 1;

        // DOS PASADAS CON VENTANA DESLIZANTE: O(W*H) EN VEZ DE O(W*H*R).
        for (int y = 0; y < h; y++) {
            int sum = 0;
            for (int x = -radius; x <= radius; x++) {
                sum += source[y * w + clamp(x, 0, w - 1)];
            }
            for (int x = 0; x < w; x++) {
                horizontal[y * w + x] = sum / window;
                final int removeX = clamp(x - radius, 0, w - 1);
                final int addX = clamp(x + radius + 1, 0, w - 1);
                sum += source[y * w + addX] - source[y * w + removeX];
            }
        }

        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int y = -radius; y <= radius; y++) {
                sum += horizontal[clamp(y, 0, h - 1) * w + x];
            }
            for (int y = 0; y < h; y++) {
                output[y * w + x] = sum / window;
                final int removeY = clamp(y - radius, 0, h - 1);
                final int addY = clamp(y + radius + 1, 0, h - 1);
                sum += horizontal[addY * w + x] - horizontal[removeY * w + x];
            }
        }
        return output;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static ByteBuffer argbToRgbaBuffer(final BufferedImage img) {
        final int w = img.getWidth(), h = img.getHeight();
        final int[] argb = new int[w * h];
        img.getRGB(0, 0, w, h, argb, 0, w);
        final ByteBuffer buffer = MemoryUtil.memAlloc(w * h * 4);
        for (final int p: argb) {
            buffer.put((byte) ((p >> 16) & 0xFF)); // R
            buffer.put((byte) ((p >> 8) & 0xFF));  // G
            buffer.put((byte) (p & 0xFF));         // B
            buffer.put((byte) ((p >> 24) & 0xFF)); // A
        }
        buffer.flip();
        return buffer;
    }

    private record TextureData(int textureId, int width, int height) {
    }
}
