package org.watermedia.test;

import org.lwjgl.openal.*;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.player.MediaPlayer;
import org.watermedia.api.media.player.VLMediaPlayer;
import org.watermedia.videolan4j.VideoLan4J;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.watermedia.WaterMedia.LOGGER;

public class VLPlayerTest implements Executor {
    private static final String NAME = "WATERMeDIA: Multimedia API";

    // The window handle
    private long window;
    private MediaPlayer player;
    private final Queue<Runnable> executor = new LinkedList<>();

    public static void main(final String[] args) {
        final String url = args.length == 0 ? "https://www.mediafire.com/file/f23l3csbeeap9jo/TU_QLO.mp4/file" : args[0];
        new VLPlayerTest().run(URI.create(url));
    }

    // The media loader
    public void run(final URI url) {

        MediaAPI.initAllMediaStuffAsAWorkarroudnWhileWeHaveNoBootstrap();

        this.init();
        this.player = new VLMediaPlayer(URI.create("https://files.catbox.moe/so3njj.mp4"), Thread.currentThread(), this, true, true);
        System.out.println("Using VideoLan4J version: " + VideoLan4J.getLibVersion());
        this.player.start();
        this.loop();

        // Free the window callbacks and destroy the window
        glfwFreeCallbacks(this.window);
        glfwDestroyWindow(this.window);

        // Terminate GLFW and free the error callback
        glfwTerminate();
        glfwSetErrorCallback(null).close();
        System.exit(0);
    }

    private void init() {
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
        this.window = glfwCreateWindow(1280, 720, NAME, NULL, NULL);
        if (this.window == NULL )
            throw new RuntimeException("Failed to create the GLFW window");

        // Setup a key callback. It will be called every time a key is pressed, repeated or released.
        glfwSetKeyCallback(this.window, (window, key, scancode, action, mods) -> {
            if (action != GLFW_RELEASE) {
                return;
            }

            if (key == GLFW_KEY_ESCAPE)
                glfwSetWindowShouldClose(window, true); // We will detect this in the rendering loop


            if (key == GLFW_KEY_LEFT) {
                this.player.rewind();
            }

            if (key == GLFW_KEY_RIGHT) {
                this.player.foward();
            }

            if (key == GLFW_KEY_SPACE) {
                this.player.togglePlay();
            }

            if (key == GLFW_KEY_UP) {
                this.player.volume(this.player.volume() + 5);
                LOGGER.info("UP VOLUME");
            }

            if (key == GLFW_KEY_DOWN) {
                this.player.volume(this.player.volume() - 5);
                LOGGER.info("DOWN VOLUME");
            }
        });

        // Get the thread stack and push a new frame
        try (final MemoryStack stack = stackPush() ) {
            final IntBuffer pWidth = stack.mallocInt(1); // int*
            final IntBuffer pHeight = stack.mallocInt(1); // int*

            // Get the window size passed to glfwCreateWindow
            glfwGetWindowSize(this.window, pWidth, pHeight);

            // Get the resolution of the primary monitor
            final GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

            // Center the window
            glfwSetWindowPos(
                    this.window,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2
            );
        } // the stack frame is popped automatically

        // Make the OpenGL context current
        glfwMakeContextCurrent(this.window);
        // Enable v-sync
        glfwSwapInterval(1);

        // Make the window visible
        glfwShowWindow(this.window);
        GL.createCapabilities();

        // Abrir dispositivo
        final long device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0L) throw new IllegalStateException("No se pudo abrir dispositivo de audio");

        // Crear contexto
        final long context = ALC10.alcCreateContext(device, (IntBuffer) null);
        ALC10.alcMakeContextCurrent(context);

        // Crear capabilities ALC (dispositivo)
        final ALCCapabilities alcCaps = ALC.createCapabilities(device);

        // Crear capabilities AL (contexto)
        final ALCapabilities alCaps = AL.createCapabilities(alcCaps);
    }

    private void loop() {
        ARBDebugOutput.glDebugMessageCallbackARB((source, type, id, severity, length, message, userParam) -> {
            System.out.println(MemoryUtil.memASCII(message));
        }, 0);

        // Set the clear color
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glfwSetWindowSizeCallback(this.window, (window, width, height) ->
                glViewport(0, 0, width, height));

        // Run the rendering loop until the user has attempted to close
        // the window or has pressed the ESCAPE key.
        while (!glfwWindowShouldClose(this.window) && !this.player.ended()) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the framebuffer

            glBindTexture(GL_TEXTURE_2D, this.player.texture());


            glColor4f(1, 1, 1, 1);

            glBegin(GL_QUADS);
            {
                glTexCoord2f(0, 1); glVertex2f(-1, -1);
                glTexCoord2f(0, 0); glVertex2f(-1, 1);
                glTexCoord2f(1, 0); glVertex2f(1, 1);
                glTexCoord2f(1, 1); glVertex2f(1, -1);
            }
            glEnd();

            if (this.player != null && (!this.player.playing())) {
                glBegin(GL_QUADS);
                {
                    glTexCoord2f(0, 1); glVertex2f(-1, -1);
                    glTexCoord2f(0, 0); glVertex2f(-1, 1);
                    glTexCoord2f(1, 0); glVertex2f(1, 1);
                    glTexCoord2f(1, 1); glVertex2f(1, -1);
                }
                glEnd();
            }

            glfwSwapBuffers(this.window); // swap the color buffers

            // Poll for window events. The key callback above will only be
            // invoked during this call.
            glfwPollEvents();
            if (!this.executor.isEmpty()) this.executor.remove().run();
            LOGGER.info("Duration: {}, Time: {}, Playing: {}, Volume: {}, Status: {}",
                    this.player.duration(), this.player.time(), this.player.playing(), this.player.volume(), this.player.status());
        }
    }

    @Override
    public void execute(final Runnable command) {
        this.executor.add(command);
    }
}