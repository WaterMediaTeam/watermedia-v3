package org.watermedia.test;

import org.watermedia.WaterMedia;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.VLVideoPlayer;
import org.watermedia.videolan4j.VideoLan4J;
import org.watermedia.videolan4j.discovery.NativeDiscovery;
import org.watermedia.videolan4j.tools.Chroma;

import java.net.URI;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

public class VLPlayerTest implements Executor {
    private final Queue<Runnable> executor = new LinkedList<>();

    // The window handle
    private long window;
    private VLVideoPlayer player;

    // The media loader
    private static final String NAME = "WATERMeDIA: Multimedia API";

    public void run(final URI url) {
        NativeDiscovery.start();

        this.init();
        this.player = new VLVideoPlayer(URI.create("https://files.catbox.moe/3nhndw.mp4"), Chroma.RGBA, Thread.currentThread(), this);
        System.out.println("Using VideoLan4J version: " + VideoLan4J.getVideoLanVersion());
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
            if ( key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE )
                glfwSetWindowShouldClose(window, true); // We will detect this in the rendering loop
            if (key == GLFW_KEY_P) {
//                player.start(URI.create("https://www.youtube.com/watch?v=LlNCDSz5BeE"));
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

        glfwSetWindowSizeCallback(this.window, (window, width, height) -> {
            glViewport(0, 0, width, height);
        });

        // Run the rendering loop until the user has attempted to close
        // the window or has pressed the ESCAPE key.
        while (!glfwWindowShouldClose(this.window)) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the framebuffer

            this.player.preRender();
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

            glfwSwapBuffers(this.window); // swap the color buffers

            // Poll for window events. The key callback above will only be
            // invoked during this call.
            glfwPollEvents();
            if (!this.executor.isEmpty()) this.executor.remove().run();
        }
    }

    public static void main(final String[] args) {
        Arrays.asList("", "");
        final String url = args.length == 0 ? "https://www.mediafire.com/file/f23l3csbeeap9jo/TU_QLO.mp4/file" : args[0];
        new VLPlayerTest().run(URI.create(url));
    }

    @Override
    public void execute(final Runnable command)
    {
        this.executor.add(command);
    }
}