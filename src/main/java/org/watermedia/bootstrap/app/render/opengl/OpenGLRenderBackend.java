package org.watermedia.bootstrap.app.render.opengl;

import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GLDebugMessageARBCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.bootstrap.app.render.DrawMode;
import org.watermedia.bootstrap.app.render.RenderBackend;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.render.TextureHandle;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.*;

/**
 * OpenGL 3.2 core implementation for the app render backend.
 */
public final class OpenGLRenderBackend implements RenderBackend {

    private static final String VERT_SRC = """
            #version 150 core
            in vec2 position;
            in vec2 texCoord;
            in vec4 vertColor;
            uniform mat4 projection;
            out vec2 vTexCoord;
            out vec4 vColor;
            void main() {
                vTexCoord = texCoord;
                vColor = vertColor;
                gl_Position = projection * vec4(position, 0.0, 1.0);
            }
            """;

    private static final String FRAG_SRC = """
            #version 150 core
            in vec2 vTexCoord;
            in vec4 vColor;
            uniform sampler2D tex;
            uniform float useTexture;
            out vec4 fragColor;
            void main() {
                fragColor = useTexture > 0.5 ? texture(tex, vTexCoord) * vColor : vColor;
            }
            """;

    // SDF SOFT-RECT SHADER — DRAWS A ROUNDED RECT WHOSE ALPHA IS SOLID INSIDE AND FADES OVER uSoftness
    // OUTSIDE THE EDGE, REPLACING THE MULTI-PASS GLOW/SHADOW STACK WITH ONE QUAD. READS ONLY position.
    private static final String VERT_SDF = """
            #version 150 core
            in vec2 position;
            uniform mat4 projection;
            out vec2 vPos;
            void main() {
                vPos = position;
                gl_Position = projection * vec4(position, 0.0, 1.0);
            }
            """;

    private static final String FRAG_SDF = """
            #version 150 core
            in vec2 vPos;
            uniform vec2 uCenter;
            uniform vec2 uHalf;
            uniform float uRadius;
            uniform float uSoftness;
            uniform vec4 uColor;
            out vec4 fragColor;
            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + vec2(r);
                return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - r;
            }
            void main() {
                float d = sdRoundBox(vPos - uCenter, uHalf, uRadius);
                // CONCENTRATE THE INTENSITY NEAR THE EDGE (LIKE THE OLD STACKED-FILL GLOW) — A HIGH POWER
                // FALLS OFF FAST WITH A FAINT TAIL, NOT THE FLAT/LINEAR RAMP A smoothstep GIVES
                float t = clamp(d / uSoftness, 0.0, 1.0);
                float falloff = pow(1.0 - t, 2.2);
                fragColor = vec4(uColor.rgb, uColor.a * falloff);
            }
            """;

    private static final int FLOATS_PER_VERTEX = 8;
    // MATCHES RenderEngine.MAX_VERTICES — THE ENGINE VALIDATES DRAWS AGAINST THE SAME CAP; KEEP THEM IN SYNC.
    private static final int MAX_VERTICES = 8192;

    private final long window;
    private int program;
    private int projectionUniform;
    private int useTextureUniform;
    private int sdfProgram;
    private int sdfProjectionUniform;
    private int sdfCenterUniform;
    private int sdfHalfUniform;
    private int sdfRadiusUniform;
    private int sdfSoftnessUniform;
    private int sdfColorUniform;
    private final float[] softQuad = new float[6 * FLOATS_PER_VERTEX];
    private int vao;
    private int vbo;
    private FloatBuffer uploadBuffer;
    private FloatBuffer projectionBuffer;
    private boolean initialized;
    private boolean frameActive;            // BETWEEN beginFrame/present THE PROGRAM+VAO+VBO STAY BOUND FOR THE WHOLE FRAME
    private boolean projectionDirty = true; // RE-UPLOAD THE PROJECTION UNIFORM ONLY AFTER IT ACTUALLY CHANGED
    private float boundUseTexture = -1f;    // LAST useTexture UNIFORM VALUE — SKIPS RE-SETTING IT EVERY DRAW
    private String deviceName = "Unknown";
    private String deviceVersion = "";
    private int glVerMajor;
    private int glVerMinor;
    private GLDebugMessageARBCallback debugCallback; // RETAINED SO THE NATIVE CLOSURE OUTLIVES THE CALL AND IS FREED IN cleanup()

    public OpenGLRenderBackend(final long window) {
        this.window = window;
    }

    // ==========================================================================
    // CONTEXT / CAPABILITIES
    // ==========================================================================
    // MAKES THE GL CONTEXT CURRENT AND LOADS CAPABILITIES; RUN AS THE FIRST STEP OF init() (BEFORE SHADERS).
    private void attachContext() {
        // THE REAL CONTEXT IS PINNED TO 3.2 CORE, WHICH SOME DRIVERS (e.g. NVIDIA) REPORT VERBATIM RATHER
        // THAN THE GPU MAX. PROBE THE DRIVER'S HIGHEST VERSION VIA A THROWAWAY DEFAULT-HINTS CONTEXT FIRST.
        long probe = 0L;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            probe = glfwCreateWindow(1, 1, "wm-gl-probe", 0L, 0L);
            if (probe != 0L) {
                glfwMakeContextCurrent(probe);
                GL.createCapabilities();
                this.captureInfo();
            }
        } catch (final Throwable ignored) {
        } finally {
            // TEAR DOWN THE THROWAWAY CONTEXT ON EVERY PATH, THEN RESTORE DEFAULT HINTS SO LATER WINDOWS RE-HINT CLEANLY.
            glfwMakeContextCurrent(0L);
            if (probe != 0L) glfwDestroyWindow(probe);
            glfwDefaultWindowHints();
        }

        glfwMakeContextCurrent(this.window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        // FALLBACK / GPU NAME FROM THE REAL CONTEXT (captureInfo ONLY RAISES THE VERSION, NEVER LOWERS IT)
        this.captureInfo();
        // ONLY WHEN A DEBUG CONTEXT WAS REQUESTED: SILENCE ITS OUTPUT WITH A RETAINED EMPTY CALLBACK (FREED IN cleanup()).
        // WITHOUT THE DEBUG CONTEXT (THE DEFAULT) THERE IS NOTHING TO SILENCE AND ARB_debug_output MAY BE ABSENT.
        if (RenderSystem.GL_DEBUG) {
            this.debugCallback = GLDebugMessageARBCallback.create((source, type, id, severity, length, message, userParam) -> {
            });
            ARBDebugOutput.glDebugMessageCallbackARB(this.debugCallback, 0);
        }
    }

    // READS GL_RENDERER + THE CURRENT CONTEXT VERSION; KEEPS THE HIGHEST VERSION SEEN ACROSS CONTEXTS.
    private void captureInfo() {
        try {
            final String renderer = GL11.glGetString(GL11.GL_RENDERER);
            if (renderer != null && !renderer.isBlank()) this.deviceName = renderer;
        } catch (final Throwable ignored) {
        }
        try {
            final int major = GL11.glGetInteger(GL30.GL_MAJOR_VERSION);
            final int minor = GL11.glGetInteger(GL30.GL_MINOR_VERSION);
            if (major > 0 && (major > this.glVerMajor || (major == this.glVerMajor && minor > this.glVerMinor))) {
                this.glVerMajor = major;
                this.glVerMinor = minor;
                this.deviceVersion = major + "." + minor;
            }
        } catch (final Throwable ignored) {
        }
    }

    @Override
    public Supplier<GFXEngine> mediaEngineSupplier(final Thread renderThread, final Executor renderExecutor) {
        return () -> new GLEngine.Builder(renderThread, renderExecutor).build();
    }

    // ==========================================================================
    // PER-FRAME FLOW
    // ==========================================================================
    @Override
    public void beginFrame() {
        this.init();
        // BIND THE UI PROGRAM/VAO/VBO ONCE FOR THE WHOLE FRAME INSTEAD OF PER DRAW CALL. present() UNBINDS
        // THEM SO THE MEDIA GLEngine (WHICH SHARES THIS CONTEXT) STILL SEES THE CLEAN STATE IT DID BEFORE.
        GL20.glUseProgram(this.program);
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        this.frameActive = true;
        this.projectionDirty = true;
        this.boundUseTexture = -1f;
    }

    @Override
    public void present() {
        if (this.frameActive) {
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
            this.frameActive = false;
        }
        glfwSwapBuffers(this.window);
    }

    @Override
    public String deviceName() {
        return this.deviceName;
    }

    @Override
    public String deviceVersion() {
        return this.deviceVersion;
    }

    // ==========================================================================
    // LIFECYCLE (SHADERS / BUFFERS)
    // ==========================================================================
    @Override
    public void init() {
        if (this.initialized) return;
        this.attachContext(); // MAKE THE GL CONTEXT CURRENT + LOAD CAPABILITIES BEFORE COMPILING SHADERS

        final int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, VERT_SRC);
        final int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, FRAG_SRC);

        this.program = GL20.glCreateProgram();
        GL20.glAttachShader(this.program, vertexShader);
        GL20.glAttachShader(this.program, fragmentShader);
        GL20.glBindAttribLocation(this.program, 0, "position");
        GL20.glBindAttribLocation(this.program, 1, "texCoord");
        GL20.glBindAttribLocation(this.program, 2, "vertColor");
        GL20.glLinkProgram(this.program);
        if (GL20.glGetProgrami(this.program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("OpenGL renderer link failed: " + GL20.glGetProgramInfoLog(this.program, 2048));
        }

        GL20.glDetachShader(this.program, vertexShader);
        GL20.glDetachShader(this.program, fragmentShader);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);

        this.projectionUniform = GL20.glGetUniformLocation(this.program, "projection");
        this.useTextureUniform = GL20.glGetUniformLocation(this.program, "useTexture");

        GL20.glUseProgram(this.program);
        GL20.glUniform1i(GL20.glGetUniformLocation(this.program, "tex"), 0);
        GL20.glUseProgram(0);

        // SDF SOFT-RECT PROGRAM — GLOW / SHADOW IN ONE PASS (SHARES THE VAO; READS ONLY THE position ATTRIB)
        final int sdfVertex = compileShader(GL20.GL_VERTEX_SHADER, VERT_SDF);
        final int sdfFragment = compileShader(GL20.GL_FRAGMENT_SHADER, FRAG_SDF);
        this.sdfProgram = GL20.glCreateProgram();
        GL20.glAttachShader(this.sdfProgram, sdfVertex);
        GL20.glAttachShader(this.sdfProgram, sdfFragment);
        GL20.glBindAttribLocation(this.sdfProgram, 0, "position");
        GL20.glLinkProgram(this.sdfProgram);
        if (GL20.glGetProgrami(this.sdfProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("OpenGL SDF program link failed: " + GL20.glGetProgramInfoLog(this.sdfProgram, 2048));
        }
        GL20.glDetachShader(this.sdfProgram, sdfVertex);
        GL20.glDetachShader(this.sdfProgram, sdfFragment);
        GL20.glDeleteShader(sdfVertex);
        GL20.glDeleteShader(sdfFragment);
        this.sdfProjectionUniform = GL20.glGetUniformLocation(this.sdfProgram, "projection");
        this.sdfCenterUniform = GL20.glGetUniformLocation(this.sdfProgram, "uCenter");
        this.sdfHalfUniform = GL20.glGetUniformLocation(this.sdfProgram, "uHalf");
        this.sdfRadiusUniform = GL20.glGetUniformLocation(this.sdfProgram, "uRadius");
        this.sdfSoftnessUniform = GL20.glGetUniformLocation(this.sdfProgram, "uSoftness");
        this.sdfColorUniform = GL20.glGetUniformLocation(this.sdfProgram, "uColor");

        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
                (long) MAX_VERTICES * FLOATS_PER_VERTEX * Float.BYTES,
                GL15.GL_STREAM_DRAW);

        final int stride = FLOATS_PER_VERTEX * Float.BYTES;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2L * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 4L * Float.BYTES);
        GL30.glBindVertexArray(0);

        this.uploadBuffer = MemoryUtil.memAllocFloat(MAX_VERTICES * FLOATS_PER_VERTEX);
        this.projectionBuffer = MemoryUtil.memAllocFloat(16);
        this.initialized = true;
    }

    @Override
    public void cleanup() {
        // FREE THE RETAINED DEBUG CLOSURE FIRST (EVEN IF init() FAILED AFTER ATTACHING IT) — LEAKING IT WOULD
        // OUTLIVE THE CONTEXT, AND THE DRIVER CALLING A COLLECTED CLOSURE CAN CRASH THE JVM.
        if (this.debugCallback != null) {
            this.debugCallback.free();
            this.debugCallback = null;
        }
        if (!this.initialized) return;
        GL20.glDeleteProgram(this.program);
        GL20.glDeleteProgram(this.sdfProgram);
        GL30.glDeleteVertexArrays(this.vao);
        GL15.glDeleteBuffers(this.vbo);
        MemoryUtil.memFree(this.uploadBuffer);
        MemoryUtil.memFree(this.projectionBuffer);
        this.initialized = false;
    }

    @Override
    public void configureFrameState() {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    @Override
    public void clear(final float r, final float g, final float b, final float a) {
        GL11.glClearColor(r, g, b, a);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    @Override
    public void viewport(final int width, final int height) {
        GL11.glViewport(0, 0, width, height);
    }

    @Override
    public void disableDepthTest() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    // ==========================================================================
    // TEXTURES
    // ==========================================================================
    @Override
    public TextureHandle createTexture(final int width, final int height, final ByteBuffer rgba) {
        this.init();

        final int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, width);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height,
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        // RESTORE THE DEFAULT ROW LENGTH SO THE SHARED GL STATE DOES NOT MIS-FEED A LATER CONSUMER (M-04/L-04)
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        return new TextureHandle(id, width, height);
    }

    @Override
    public void deleteTexture(final TextureHandle texture) {
        if (texture != null && texture.id() > 0) {
            GL11.glDeleteTextures(texture.id());
        }
    }

    @Override
    public void bindTexture(final int textureId) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
    }

    @Override
    public void useProjection(final Matrix4f projection) {
        this.init();
        this.projectionBuffer.clear();
        projection.get(this.projectionBuffer);
        this.projectionDirty = true;
    }

    // ==========================================================================
    // DRAWING
    // ==========================================================================
    @Override
    public void draw(final DrawMode mode, final float[] vertices, final int vertexCount, final boolean textured) {
        this.init();
        if (vertexCount <= 0) return;

        final int floats = vertexCount * FLOATS_PER_VERTEX;
        if (floats > this.uploadBuffer.capacity()) {
            throw new IllegalArgumentException("Too many UI vertices in one draw call: " + vertexCount);
        }

        // OUTSIDE A FRAME (E.G. A FLUSH TRIGGERED BY createTexture DURING LOADING) BIND/UNBIND LOCALLY;
        // INSIDE A FRAME beginFrame ALREADY BOUND THE PROGRAM/VAO/VBO AND present() WILL UNBIND THEM.
        final boolean standalone = !this.frameActive;
        if (standalone) {
            GL20.glUseProgram(this.program);
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            this.projectionDirty = true;
            this.boundUseTexture = -1f;
        }

        if (this.projectionDirty) {
            GL20.glUniformMatrix4fv(this.projectionUniform, false, this.projectionBuffer);
            this.projectionDirty = false;
        }
        final float useTexture = textured ? 1f : 0f;
        if (useTexture != this.boundUseTexture) {
            GL20.glUniform1f(this.useTextureUniform, useTexture);
            this.boundUseTexture = useTexture;
        }

        this.uploadBuffer.clear();
        this.uploadBuffer.put(vertices, 0, floats);
        this.uploadBuffer.flip();
        // ORPHAN + UPLOAD IN ONE CALL: RESPECIFYING THE STORE HANDS BACK FRESH DRIVER MEMORY INSTEAD OF STALLING
        // ON THE PRIOR DRAW STILL READING THE SAME REGION (THE UNSYNCHRONIZED-STREAMING HAZARD OF SUBDATA-AT-0).
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.uploadBuffer, GL15.GL_STREAM_DRAW);

        GL11.glDrawArrays(toGLMode(mode), 0, vertexCount);

        if (standalone) {
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
        }
    }

    @Override
    public boolean supportsSoftRect() {
        return true;
    }

    @Override
    public void softRect(final float x, final float y, final float w, final float h, final float radius,
                         final float r, final float g, final float b, final float a, final float softness) {
        this.init();
        final float soft = Math.max(0.5f, softness);
        final float x0 = x - soft;
        final float y0 = y - soft;
        final float x1 = x + w + soft;
        final float y1 = y + h + soft;
        // A QUAD (2 TRIS) COVERING THE RECT PLUS THE SOFTNESS MARGIN — THE SDF SHADER READS ONLY position
        this.softVertex(0, x0, y0);
        this.softVertex(1, x1, y0);
        this.softVertex(2, x1, y1);
        this.softVertex(3, x0, y0);
        this.softVertex(4, x1, y1);
        this.softVertex(5, x0, y1);

        GL20.glUseProgram(this.sdfProgram);
        GL20.glUniformMatrix4fv(this.sdfProjectionUniform, false, this.projectionBuffer);
        GL20.glUniform2f(this.sdfCenterUniform, x + w / 2f, y + h / 2f);
        GL20.glUniform2f(this.sdfHalfUniform, w / 2f, h / 2f);
        GL20.glUniform1f(this.sdfRadiusUniform, Math.max(0f, radius));
        GL20.glUniform1f(this.sdfSoftnessUniform, soft);
        GL20.glUniform4f(this.sdfColorUniform, r, g, b, a);

        if (!this.frameActive) {
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        }
        this.uploadBuffer.clear();
        this.uploadBuffer.put(this.softQuad, 0, this.softQuad.length);
        this.uploadBuffer.flip();
        // ORPHAN + UPLOAD (SEE draw()): AVOID STALLING ON THE PRIOR DRAW STILL READING THE SAME VBO REGION.
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.uploadBuffer, GL15.GL_STREAM_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

        if (this.frameActive) {
            // BACK TO THE UI PROGRAM FOR THE FOLLOWING BATCHED DRAWS; FORCE ITS UNIFORMS TO BE RE-SET NEXT DRAW
            GL20.glUseProgram(this.program);
            this.projectionDirty = true;
            this.boundUseTexture = -1f;
        } else {
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
        }
    }

    private void softVertex(final int index, final float x, final float y) {
        final int off = index * FLOATS_PER_VERTEX;
        this.softQuad[off] = x;
        this.softQuad[off + 1] = y;
        this.softQuad[off + 2] = 0f;
        this.softQuad[off + 3] = 0f;
        this.softQuad[off + 4] = 0f;
        this.softQuad[off + 5] = 0f;
        this.softQuad[off + 6] = 0f;
        this.softQuad[off + 7] = 0f;
    }

    @Override
    public void lineWidth(final float width) {
        GL11.glLineWidth(width);
    }

    @Override
    public void clip(final int x, final int y, final int width, final int height, final int canvasHeight) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, canvasHeight - y - height, width, height);
    }

    @Override
    public void clearClip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private static int compileShader(final int type, final String source) {
        final int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            final String log = GL20.glGetShaderInfoLog(shader, 2048);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("OpenGL renderer shader failed: " + log);
        }
        return shader;
    }

    private static int toGLMode(final DrawMode mode) {
        return switch (mode) {
            case TRIANGLES -> GL11.GL_TRIANGLES;
            case TRIANGLE_FAN -> GL11.GL_TRIANGLE_FAN;
            case LINES -> GL11.GL_LINES;
            case LINE_LOOP -> GL11.GL_LINE_LOOP;
        };
    }
}
