package org.watermedia.api.codecs.common.bc;

import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;

/**
 * Native block-compression (BCn) codec — the engine behind the BC reader/writer.
 *
 * <p>This class owns the GPU texture-compression family the same way the WebP VP8/VP8L decoders
 * own WebP: it is the <i>codec</i>, independent of the DDS <i>container</i> that stores its output
 * (see {@link org.watermedia.api.codecs.common.dds.DDSHeader}). Availability is resolved exactly
 * once, from {@link CodecsAPI#start(org.watermedia.WaterMedia)} via {@link #init()}; codecs are not
 * pluggable at runtime, so callers must treat the probed result as fixed for the session.
 *
 * <p>Versions are ranked BC7 &gt; BC3 &gt; BC1 by quality. The encoder picks the {@link #best()}
 * available version unless a specific one is requested; either way the caller validates with
 * {@link #available(String)} before encoding.
 */
public final class BCCodec {

    // RESOLVED ONCE BY init(); volatile SO PLAYER/DECODE THREADS SEE THE PROBED STATE.
    private static volatile boolean bc7;
    private static volatile boolean bc3;
    private static volatile boolean bc1;

    private BCCodec() {}

    /**
     * Probes the native block-compression library. Called once from {@code CodecsAPI.start()}.
     *
     * <p>The JNI bindings are pending, so no version is reported available yet. When the native
     * library ships, load it here and set each flag from its capability query — the rest of the
     * pipeline (availability, reader/writer, codec cache) activates automatically.
     */
    public static void init() {
        // NATIVE BC BINDINGS NOT YET LINKED — NOTHING AVAILABLE.
        bc7 = false;
        bc3 = false;
        bc1 = false;
    }

    /** Whether a specific BC version ({@link CodecsAPI#CODEC_BC7}/{@code BC3}/{@code BC1}) is available. */
    public static boolean available(final String version) {
        if (version == null) return false;
        return switch (version) {
            case CodecsAPI.CODEC_BC7 -> bc7;
            case CodecsAPI.CODEC_BC3 -> bc3;
            case CodecsAPI.CODEC_BC1 -> bc1;
            default -> false;
        };
    }

    /** Whether any BC version is available. */
    public static boolean any() {
        return bc7 || bc3 || bc1;
    }

    /** Highest-quality available version, or {@code null} when no native BC support is present. */
    public static String best() {
        if (bc7) return CodecsAPI.CODEC_BC7;
        if (bc3) return CodecsAPI.CODEC_BC3;
        if (bc1) return CodecsAPI.CODEC_BC1;
        return null;
    }

    /**
     * Encodes one colour frame into row-major 4x4 blocks for {@code version}.
     *
     * <p>The frame is read from {@code frame.position()} and holds {@code width * height} pixels in
     * {@code format}; dimensions that are not multiples of four are padded internally to the block
     * grid. The frame's position is left unchanged.
     *
     * @return a buffer of {@code blocksPerFrame * blockBytes} bytes positioned at zero
     */
    public static ByteBuffer encode(final String version, final ByteBuffer frame,
                                    final int width, final int height, final PixelFormat format) {
        // NATIVE ENCODE SEAM. GUARDED BY available(version): THE BC READER/WRITER VALIDATE IN THEIR
        // CONSTRUCTORS, SO THIS IS ONLY REACHED ONCE THE NATIVE LIBRARY IS LINKED.
        throw new UnsupportedOperationException("Native BC encoder unavailable: " + version);
    }
}
