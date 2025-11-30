package org.watermedia.api.media.engines;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.watermedia.WaterMedia;
import org.watermedia.tools.functions.BiIntConsumer;
import org.watermedia.tools.functions.TexImage2DFunction;
import org.watermedia.tools.functions.TexSubImage2DFunction;
import org.watermedia.tools.functions.TriIntConsumer;

import java.nio.ByteBuffer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public record GLEngine(IntSupplier genTexture, BiIntConsumer bindTexture, TriIntConsumer texParameter,
                       BiIntConsumer pixelStore, IntConsumer delTexture, TexImage2DFunction texImage2D, TexSubImage2DFunction texSubImage2D) {

    public GLEngine {
        WaterMedia.checkIsClientSideOrThrow(GLEngine.class); // Ensure client-side only

        if (genTexture == null || bindTexture == null || texParameter == null || pixelStore == null
                || delTexture == null || texImage2D == null || texSubImage2D == null) {
            throw new IllegalArgumentException("All parameters must be non-null");
        }
    }

    public int createTexture() {
        final int texture = this.genTexture.getAsInt();

        // Bind
        this.bindTexture.accept(GL11.GL_TEXTURE_2D, texture);

        // Setup wrap mode
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        // Setup texture scaling filtering (no dark textures)
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        // Unbind
        this.bindTexture.accept(GL11.GL_TEXTURE_2D, 0);  // Unbind

        return texture;
    }

    public void uploadTexture(final int texture, final ByteBuffer buffers, final int stride, final int width, final int height, final int format, final boolean firstFrame) {
        this.bindTexture.accept(GL11.GL_TEXTURE_2D, texture);

        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, stride);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_ZERO);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_ZERO);

        if (firstFrame) {
            // NOTE: INTERNAL FORMAT CANNOT BE GL_RGBA HERE, OTHERWISE IT CAUSES ISSUES ON SOME GPUS (SPECIALLY MACOS)
            this.texImage2D.apply(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, format, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffers);
        } else {
            this.texSubImage2D.apply(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, format, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffers);
        }
    }

    public void deleteTexture(final int texture) {
        this.delTexture.accept(texture);
    }

    /**
     * Constructs a custom GLManager builder.
     * TODO: javadocs
     */
    public static class Builder {
        private IntSupplier genTexture = GL11::glGenTextures;
        private BiIntConsumer bindTexture = GL11::glBindTexture;
        private TriIntConsumer texParameter = GL11::glTexParameteri;
        private BiIntConsumer pixelStore = GL11::glPixelStorei;
        private IntConsumer delTexture = GL11::glDeleteTextures;
        private TexImage2DFunction texImage2D = GL11::glTexImage2D;
        private TexSubImage2DFunction texSubImage2D = GL11::glTexSubImage2D;

        public Builder setGenTexture(final IntSupplier genTexture) {
            this.genTexture = genTexture;
            return this;
        }

        public Builder setBindTexture(final BiIntConsumer bindTexture) {
            this.bindTexture = bindTexture;
            return this;
        }

        public Builder setTexParameter(final TriIntConsumer texParameter) {
            this.texParameter = texParameter;
            return this;
        }

        public Builder setPixelStore(final BiIntConsumer pixelStore) {
            this.pixelStore = pixelStore;
            return this;
        }

        public Builder setDelTexture(final IntConsumer delTexture) {
            this.delTexture = delTexture;
            return this;
        }

        public Builder setTexImage2D(final TexImage2DFunction texImage2D) {
            this.texImage2D = texImage2D;
            return this;
        }

        public Builder setTexSubImage2D(final TexSubImage2DFunction texSubImage2D) {
            this.texSubImage2D = texSubImage2D;
            return this;
        }

        public GLEngine build() {
            return new GLEngine(this.genTexture, this.bindTexture, this.texParameter, this.pixelStore, this.delTexture, this.texImage2D, this.texSubImage2D);
        }
    }
}
