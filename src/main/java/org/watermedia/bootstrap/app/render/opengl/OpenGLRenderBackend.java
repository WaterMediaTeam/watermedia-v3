package org.watermedia.bootstrap.app.render.opengl;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.bootstrap.app.render.DrawMode;
import org.watermedia.bootstrap.app.render.RenderBackend;
import org.watermedia.bootstrap.app.render.TextureHandle;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

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

    private static final int FLOATS_PER_VERTEX = 8;
    private static final int DEFAULT_VERTEX_CAPACITY = 8192;

    private int program;
    private int projectionUniform;
    private int useTextureUniform;
    private int vao;
    private int vbo;
    private FloatBuffer uploadBuffer;
    private FloatBuffer projectionBuffer;
    private boolean initialized;

    @Override
    public void init() {
        if (this.initialized) return;

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

        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
                (long) DEFAULT_VERTEX_CAPACITY * FLOATS_PER_VERTEX * Float.BYTES,
                GL15.GL_STREAM_DRAW);

        final int stride = FLOATS_PER_VERTEX * Float.BYTES;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2L * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 4L * Float.BYTES);
        GL30.glBindVertexArray(0);

        this.uploadBuffer = MemoryUtil.memAllocFloat(DEFAULT_VERTEX_CAPACITY * FLOATS_PER_VERTEX);
        this.projectionBuffer = MemoryUtil.memAllocFloat(16);
        this.initialized = true;
    }

    @Override
    public void cleanup() {
        if (!this.initialized) return;
        GL20.glDeleteProgram(this.program);
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
    }

    @Override
    public void draw(final DrawMode mode, final float[] vertices, final int vertexCount, final boolean textured) {
        this.init();
        if (vertexCount <= 0) return;

        final int floats = vertexCount * FLOATS_PER_VERTEX;
        if (floats > this.uploadBuffer.capacity()) {
            throw new IllegalArgumentException("Too many UI vertices in one draw call: " + vertexCount);
        }

        GL20.glUseProgram(this.program);
        GL20.glUniformMatrix4fv(this.projectionUniform, false, this.projectionBuffer);
        GL20.glUniform1f(this.useTextureUniform, textured ? 1f : 0f);

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);

        this.uploadBuffer.clear();
        this.uploadBuffer.put(vertices, 0, floats);
        this.uploadBuffer.flip();
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, this.uploadBuffer);

        GL11.glDrawArrays(toGLMode(mode), 0, vertexCount);

        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
    }

    @Override
    public void lineWidth(final float width) {
        GL11.glLineWidth(width);
    }

    @Override
    public void enableClip(final int x, final int y, final int width, final int height, final int canvasHeight) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, canvasHeight - y - height, width, height);
    }

    @Override
    public void disableClip() {
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
            case LINE_STRIP -> GL11.GL_LINE_STRIP;
        };
    }
}
