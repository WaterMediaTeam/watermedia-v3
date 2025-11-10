package org.watermedia.test;

import org.lwjgl.openal.*;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.media.players.MediaPlayer;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

public class MediaPlayerWindowTest {
    private static final String NAME = "WATERMeDIA: Multimedia API";
    private static final URI MEDIA_GIF = URI.create("https://blog.phonehouse.es/wp-content/uploads/2018/01/giphy-1-1.gif");
    private static final URI MEDIA_VIDEO = URI.create("https://www.mediafire.com/file/f23l3csbeeap9jo/TU_QLO.mp4/file");
    private static final URI MEDIA_VIDEO_STATIC = URI.create("https://cdn-cf-east.streamable.com/video/mp4/6yszde.mp4?Expires=1759724603795&Key-Pair-Id=APKAIEYUVEN4EVB2OKEQ&Signature=XQf9-YLrvJNaipIPfiWIscrCfimWBx4FDQk-84IN37zvqpiswcrL3ODtsHgmV2KIRbXdllaq7SWXr580~t1eF3EKwLLIRNvW4Zg4scBqaWykjF2eymLqrDdiRQ7wh95zcIGmL-yyB4mUFD7dZz-mSKaQ3YFmTiSNfClYNbkHVzce2QVUqeFnRARdrzHT~LYNRZSKDhKglq014cW2nLj22pDFVQdv1uVmjmyxVaxnJNqV-59ssq01wMYeYhScALLTOgYQTHqz84~WU1WOwlizHYjrX4ptq--konfQWTJCmuSby4yYZEc-c0~uKzRRHxqQxp07vFH~b5-oLTD3jwGCJw__");

    // The window handle
    private static long window;
    private static MediaPlayer player;
    private static final Queue<Runnable> executor = new ConcurrentLinkedQueue<>();
    private static Thread thread;

    public static void main(final String... args) {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        WaterMedia.start("Java Test", null, null, true);
        init();
        player = new FFMediaPlayer(MEDIA_VIDEO_STATIC, Thread.currentThread(), MediaPlayerWindowTest::execute, null, null, true, true);
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
        while (!glfwWindowShouldClose(window) && !player.ended()) {
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

    public static void execute(final Runnable command) {
        if (command == null)
            throw new IllegalArgumentException("Command cannot be null");
        executor.add(command);
    }
}