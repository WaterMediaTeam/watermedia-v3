package org.watermedia.test;

import org.lwjgl.openal.*;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.media.players.MediaPlayer;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

public class MediaPlayerWindowTest {
    private static final String NAME = "WATERMeDIA: Multimedia API";
    private static final URI MEDIA_GIF = URI.create("https://blog.phonehouse.es/wp-content/uploads/2018/01/giphy-1-1.gif");
    private static final URI MEDIA_STREAMABLE = URI.create("https://streamable.com/6yszde");
    private static final URI MEDIA_VIDEO = URI.create("https://www.mediafire.com/file/f23l3csbeeap9jo/TU_QLO.mp4/file");
    private static final URI MEDIA_VIDEO_STATIC = URI.create("https://cdn-cf-east.streamable.com/video/mp4/6yszde.mp4?Expires=1759724603795&Key-Pair-Id=APKAIEYUVEN4EVB2OKEQ&Signature=XQf9-YLrvJNaipIPfiWIscrCfimWBx4FDQk-84IN37zvqpiswcrL3ODtsHgmV2KIRbXdllaq7SWXr580~t1eF3EKwLLIRNvW4Zg4scBqaWykjF2eymLqrDdiRQ7wh95zcIGmL-yyB4mUFD7dZz-mSKaQ3YFmTiSNfClYNbkHVzce2QVUqeFnRARdrzHT~LYNRZSKDhKglq014cW2nLj22pDFVQdv1uVmjmyxVaxnJNqV-59ssq01wMYeYhScALLTOgYQTHqz84~WU1WOwlizHYjrX4ptq--konfQWTJCmuSby4yYZEc-c0~uKzRRHxqQxp07vFH~b5-oLTD3jwGCJw__");
    private static final URI MEDIA_VIDEO_STATIC2 = URI.create("https://files.catbox.moe/uxypnp.mp4");
    private static final URI MEDIA_VIDEO_STATIC3 = URI.create("https://files.catbox.moe/1n0jn9.mp4");
    private static final URI MEDIA_VIDEO_STATIC4 = URI.create("https://lf-tk-sg.ibytedtos.com/obj/tcs-client-sg/resources/hevc_4k25P_main_1.mp4");
    private static final URI MEDIA_H265 = new File("C:\\Users\\J-RAP\\Downloads\\hevc.mp4").toURI();

    private static final DateFormat FORMAT = new SimpleDateFormat("HH:mm:ss");
    static {
        FORMAT.setTimeZone(TimeZone.getTimeZone("GMT-00:00"));
    }

    // THE WINDOW HANDLE
    private static long window;
    private static MediaPlayer player;
    private static final Queue<Runnable> executor = new ConcurrentLinkedQueue<>();
    private static Thread thread;

    // TEXT RENDERING SYSTEM
    private static final Map<Character, CharTexture> charTextureCache = new HashMap<>();
    private static Font textFont;
    private static final int FONT_SIZE = 24;

    // CHAR TEXTURE DATA HOLDER
    private static class CharTexture {
        final int textureId;
        final int width;
        final int height;

        CharTexture(final int textureId, final int width, final int height) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
        }
    }

    public static void main(final String... args) {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        WaterMedia.start("Java Test", null, null, true);
        init();
        player = new FFMediaPlayer(MEDIA_H265, Thread.currentThread(), MediaPlayerWindowTest::execute, null, null, true, true);
        player.start();
        // Make the window visible
        glfwShowWindow(window);
        loop();

        // Free the window callbacks and destroy the window
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);

        // Terminate GLFW and free the error callback
        glfwTerminate();
        glfwSetErrorCallback(null).close();
        System.exit(0);
    }

    private static void init() {
        // Setup an error callback. The default implementation
        // will print the error message in System.err.
        GLFWErrorCallback.createPrint(System.err).set();

        // Initialize GLFW. Most GLFW functions will not work before doing this.
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        // Configure GLFW
        glfwDefaultWindowHints(); // optional, the current window hints are already the default
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // the window will stay hidden after creation
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // the window will be resizable
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);

        // Create the window
        window = glfwCreateWindow(1280, 720, NAME, NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");

        // Setup a key callback. It will be called every time a key is pressed, repeated or released.
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if (action != GLFW_RELEASE) return;
            switch (key) {
                case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(window, true); // We will detect this in the rendering loop
                case GLFW_KEY_SPACE -> player.togglePlay();
                case GLFW_KEY_LEFT -> player.rewind();
                case GLFW_KEY_RIGHT -> player.foward();
                case GLFW_KEY_UP -> player.volume(player.volume() + 5);
                case GLFW_KEY_DOWN -> player.volume(player.volume() - 5);
                case GLFW_KEY_S -> player.stop();
                case GLFW_KEY_0, GLFW_KEY_KP_0 -> player.seek(0);
            }
        });

        // Get the thread stack and push a new frame
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer pWidth = stack.mallocInt(1); // int*
            final IntBuffer pHeight = stack.mallocInt(1); // int*

            // Get the window size passed to glfwCreateWindow
            glfwGetWindowSize(window, pWidth, pHeight);

            // Get the resolution of the primary monitor
            final GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

            // Center the window
            glfwSetWindowPos(window, (vidmode.width() - pWidth.get(0)) / 2, (vidmode.height() - pHeight.get(0)) / 2);
        } // the stack frame is popped automatically

        // Make the OpenGL context current
        glfwMakeContextCurrent(window);
        // Enable v-sync
        glfwSwapInterval(1);

        GL.createCapabilities();

        // Open Audio Device
        final long device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0L) throw new IllegalStateException("Failed to open a new Audio Device");

        // Crear contexto
        final long context = ALC10.alcCreateContext(device, (IntBuffer) null);
        ALC10.alcMakeContextCurrent(context);

        // Crear capabilities ALC (dispositivo)
        final ALCCapabilities alcCaps = ALC.createCapabilities(device);

        // Crear capabilities AL (contexto)
        final ALCapabilities alCaps = AL.createCapabilities(alcCaps);

        thread = Thread.currentThread();

        // INITIALIZE TEXT RENDERING SYSTEM
        textFont = new Font("Consolas", Font.PLAIN, FONT_SIZE);
    }

    private static void loop() {
        ARBDebugOutput.glDebugMessageCallbackARB((source, type, id, severity, length, message, userParam) -> {
            System.out.println(MemoryUtil.memASCII(message));
        }, 0);

        // Set the clear color
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glfwSetWindowSizeCallback(window, (window, width, height) -> glViewport(0, 0, width, height));

        // Run the rendering loop until the user has attempted to close
        // the window or has pressed the ESCAPE key.
        while (!glfwWindowShouldClose(window) && !player.ended() && !player.stopped()) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the framebuffer

            glBindTexture(GL_TEXTURE_2D, player.texture());

            glColor4f(1, 1, 1, 1);

            glBegin(GL_QUADS);
            {
                glTexCoord2f(0, 1); glVertex2f(-1, -1);
                glTexCoord2f(0, 0); glVertex2f(-1, 1);
                glTexCoord2f(1, 0); glVertex2f(1, 1);
                glTexCoord2f(1, 1); glVertex2f(1, -1);
            }
            glEnd();

            if (player != null && (!player.playing())) {
                glBegin(GL_QUADS);
                {
                    glTexCoord2f(0, 1); glVertex2f(-1, -1);
                    glTexCoord2f(0, 0); glVertex2f(-1, 1);
                    glTexCoord2f(1, 0); glVertex2f(1, 1);
                    glTexCoord2f(1, 1); glVertex2f(1, -1);
                }
                glEnd();
            }

            // ============================================================
            // TEXT RENDERING - AFTER LINE 180
            // ============================================================

            // GET WINDOW DIMENSIONS FOR PROPER TEXT POSITIONING
            final IntBuffer widthBuffer = MemoryUtil.memAllocInt(1);
            final IntBuffer heightBuffer = MemoryUtil.memAllocInt(1);
            glfwGetWindowSize(window, widthBuffer, heightBuffer);
            final int windowWidth = widthBuffer.get(0);
            final int windowHeight = heightBuffer.get(0);
            MemoryUtil.memFree(widthBuffer);
            MemoryUtil.memFree(heightBuffer);

            // PREPARE TEXT LINES TO RENDER
            final String[] textLines = {
                    String.format("Status: %s", player.status()),
                    String.format("Time: %s (%s) / %s (%s)", FORMAT.format(new Date(player.time())), player.time(), FORMAT.format(new Date(player.duration())), player.duration()),
                    String.format("Speed: %.2f", player.speed()),
                    String.format("Is Live: %s", player.liveSource()),
                    String.format("Volume: %d%%", player.volume()),
            };

            // SETUP ORTHOGRAPHIC PROJECTION FOR 2D TEXT RENDERING
            glMatrixMode(GL_PROJECTION);
            glPushMatrix();
            glLoadIdentity();
            glOrtho(0, windowWidth, windowHeight, 0, -1, 1);
            glMatrixMode(GL_MODELVIEW);
            glPushMatrix();
            glLoadIdentity();

            // DISABLE DEPTH TEST FOR TEXT OVERLAY
            glDisable(GL_DEPTH_TEST);

            // CALCULATE VERTICAL CENTERING
            final int lineHeight = FONT_SIZE + 5; // FONT SIZE + SPACING
            final int totalTextHeight = textLines.length * lineHeight;
            final int startY = (windowHeight - totalTextHeight) / 2;

            // RENDER EACH LINE OF TEXT
            final int textX = 20; // LEFT MARGIN
            int currentY = startY;

            for (final String line : textLines) {
                renderText(line, textX, currentY, Color.WHITE);
                currentY += lineHeight;
            }

            // RESTORE PREVIOUS PROJECTION AND MODELVIEW MATRICES
            glMatrixMode(GL_PROJECTION);
            glPopMatrix();
            glMatrixMode(GL_MODELVIEW);
            glPopMatrix();

            // RE-ENABLE DEPTH TEST
            glEnable(GL_DEPTH_TEST);

            // ============================================================
            // END TEXT RENDERING
            // ============================================================

            glfwSwapBuffers(window); // swap the color buffers

            // Poll for window events. The key callback above will only be
            // invoked during this call.
            glfwPollEvents();
            while (!executor.isEmpty()) {
                executor.poll().run();
            }
//            LOGGER.info("Duration: {}, Time: {}, Playing: {}, Volume: {}, Status: {}",
//                    this.player.duration(), this.player.time(), this.player.playing(), this.player.volume(), this.player.status());
        }
    }

    /**
     * RENDER TEXT AT SPECIFIED POSITION WITH GIVEN COLOR
     */
    private static void renderText(final String text, final int x, final int y, final Color color) {
        if (text == null || text.isEmpty()) return;

        glColor4f(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, color.getAlpha() / 255.0f);

        int currentX = x;

        for (final char c : text.toCharArray()) {
            final CharTexture charTex = getCharTexture(c);
            if (charTex == null) continue;

            glBindTexture(GL_TEXTURE_2D, charTex.textureId);

            glBegin(GL_QUADS);
            {
                glTexCoord2f(0, 0); glVertex2f(currentX, y);
                glTexCoord2f(1, 0); glVertex2f(currentX + charTex.width, y);
                glTexCoord2f(1, 1); glVertex2f(currentX + charTex.width, y + charTex.height);
                glTexCoord2f(0, 1); glVertex2f(currentX, y + charTex.height);
            }
            glEnd();

            currentX += charTex.width;
        }
    }

    /**
     * GET OR CREATE TEXTURE FOR A CHARACTER
     */
    private static CharTexture getCharTexture(final char c) {
        // CHECK CACHE FIRST
        if (charTextureCache.containsKey(c)) {
            return charTextureCache.get(c);
        }

        // CREATE NEW TEXTURE FOR CHARACTER
        final BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g2d = img.createGraphics();

        g2d.setFont(textFont);
        FontRenderContext frc = g2d.getFontRenderContext();
        Rectangle2D bounds = textFont.getStringBounds(String.valueOf(c), frc);

        final int charWidth = (int)Math.ceil(bounds.getWidth());
        final int charHeight = (int)Math.ceil(bounds.getHeight());

        g2d.dispose();

        if (charWidth == 0 || charHeight == 0) {
            return null;
        }

        // CREATE IMAGE WITH PROPER SIZE
        final BufferedImage charImage = new BufferedImage(charWidth, charHeight, BufferedImage.TYPE_INT_ARGB);
        g2d = charImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        g2d.setFont(textFont);
        g2d.setColor(Color.WHITE);
        final int x = -(int)bounds.getX();
        final int y = -(int)bounds.getY();
        g2d.drawString(String.valueOf(c), x, y);
        g2d.dispose();

        // CONVERT IMAGE TO OPENGL TEXTURE
        final int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);

        // SETUP TEXTURE PARAMETERS
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);

        // CONVERT BUFFEREDIMAGE TO BYTEBUFFER
        final int[] pixels = new int[charWidth * charHeight];
        charImage.getRGB(0, 0, charWidth, charHeight, pixels, 0, charWidth);

        final ByteBuffer buffer = MemoryUtil.memAlloc(charWidth * charHeight * 4);
        for (int i = 0; i < charHeight; i++) {
            for (int j = 0; j < charWidth; j++) {
                final int pixel = pixels[i * charWidth + j];
                buffer.put((byte) ((pixel >> 16) & 0xFF)); // RED
                buffer.put((byte) ((pixel >> 8) & 0xFF));  // GREEN
                buffer.put((byte) (pixel & 0xFF));         // BLUE
                buffer.put((byte) ((pixel >> 24) & 0xFF)); // ALPHA
            }
        }
        buffer.flip();

        // UPLOAD TEXTURE TO GPU
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, charWidth, charHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        MemoryUtil.memFree(buffer);

        // CACHE THE TEXTURE
        final CharTexture charTex = new CharTexture(textureId, charWidth, charHeight);
        charTextureCache.put(c, charTex);

        return charTex;
    }

    public static void execute(final Runnable command) {
        if (command == null)
            throw new IllegalArgumentException("Command cannot be null");
        executor.add(command);
    }
}