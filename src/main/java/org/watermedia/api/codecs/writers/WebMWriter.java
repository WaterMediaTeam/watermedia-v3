package org.watermedia.api.codecs.writers;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.watermedia.WaterMedia;
import org.watermedia.api.codecs.ImageWriter;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.FFTool;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link ImageWriter} that encodes frames as VP9 video inside a WebM (Matroska) container using
 * FFmpeg. Pixel frames go in; a complete, seekable WebM comes out through the wrapped
 * {@link OutputStream}.
 *
 * <p>Every frame is converted to {@code YUV420P} (VP9 profile 0) with {@code libswscale} before it
 * reaches the encoder, so any raw {@link PixelFormat} the writer accepts (packed RGB/BGRA,
 * grayscale, packed or planar YUV) is valid input; block-compressed and {@code GBRA} layouts are
 * not. Alpha is flattened — VP9-in-WebM alpha is out of scope.
 *
 * <p>Frame timing follows the {@link #writeFrame(ByteBuffer, long) delay} of each frame when one is
 * given, otherwise the constructor frame rate; quality is a VP9 CRF (0-63, lower is better) in
 * constant-quality mode. Availability is validated up front: the constructor throws when FFmpeg or
 * a VP9 encoder is missing, so a {@code WebMWriter} that exists is always usable.
 *
 * <p>Matroska writes its {@code Duration} and seek index ({@code Cues}) by seeking back over the
 * header once every frame is in, so the encode is muxed to a private, seekable temp file and the
 * finished container is streamed to the wrapped {@link OutputStream} on {@link #close()}. A
 * non-seekable stream would otherwise omit the duration and cues, and players could neither report
 * length nor seek until they had read the whole file.
 */
public final class WebMWriter extends ImageWriter {
    // DEFAULTS — 30 FPS AND A MID VP9 CRF (CONSTANT-QUALITY SWEET SPOT)
    private static final int DEFAULT_FPS = 30;
    private static final int DEFAULT_CRF = 31;
    private static final int MAX_CRF = 63;

    private final int crf;
    private final int srcFormat;      // SOURCE AV_PIX_FMT MAPPED FROM pixelFormat
    private final double frameDurMs;  // DEFAULT PER-FRAME ADVANCE = 1000 / fps
    private final AVRational timeBase; // 1/1000 (MS) — CODEC UNITS, KEPT FOR PACKET RESCALE

    // FFMPEG PIPELINE
    private AVFormatContext muxer;
    private AVCodecContext encoder;
    private AVStream stream;
    private SwsContext scaler;
    private AVFrame srcFrame;   // BINDS THE CALLER'S BUFFER (NO STORAGE OF ITS OWN)
    private AVFrame yuvFrame;   // YUV420P ENCODER INPUT
    private AVPacket packet;
    private Path tempFile;      // SEEKABLE MUX TARGET; COPIED TO out AND DELETED ON close()

    // STREAMING STATE
    private double ptsMs;
    private long lastPts = -1;
    private ByteBuffer directCopy; // REUSED DIRECT MIRROR FOR HEAP INPUTS
    private int frames;
    private boolean closed;

    /** Opens a writer at the default frame rate (30fps) and quality (CRF 31). */
    public WebMWriter(final OutputStream out, final int width, final int height, final PixelFormat pixelFormat) throws IOException {
        this(out, width, height, pixelFormat, DEFAULT_FPS, DEFAULT_CRF);
    }

    /**
     * Opens a writer with an explicit frame rate and VP9 constant-quality level.
     *
     * @param fps default frames per second used when a frame carries no explicit delay
     * @param crf VP9 constant-quality factor (0-63, lower is better)
     * @throws IllegalArgumentException when {@code fps <= 0}, {@code crf} is out of range or the
     *                                  pixel format cannot feed a VP9 encoder
     * @throws IOException              when FFmpeg or a VP9 encoder is unavailable, or setup fails
     */
    public WebMWriter(final OutputStream out, final int width, final int height,
                      final PixelFormat pixelFormat, final int fps, final int crf) throws IOException {
        super(out, width, height, pixelFormat);
        WaterMedia.LOGGER.warn("WebMWriter (encoder) is experimental and runs on a unadapted API, usage might cause issues.");
        if (fps <= 0) throw new IllegalArgumentException("fps must be positive: " + fps);
        if (crf < 0 || crf > MAX_CRF) throw new IllegalArgumentException("crf out of range [0," + MAX_CRF + "]: " + crf);
        if (!MediaAPI.ffmpegLoaded()) throw new IOException("FFmpeg is not available");
        this.crf = crf;
        this.frameDurMs = 1000.0 / fps;
        this.srcFormat = FFTool.avPixelFormat(pixelFormat);
        this.timeBase = new AVRational().num(1).den(1000);

        try {
            // VP9 ENCODER — PREFER libvpx-vp9, FALL BACK TO WHATEVER REGISTERED FOR THE CODEC ID
            AVCodec codec = avcodec.avcodec_find_encoder_by_name("libvpx-vp9");
            if (FFTool.isNull(codec)) codec = avcodec.avcodec_find_encoder(avcodec.AV_CODEC_ID_VP9);
            if (FFTool.isNull(codec)) throw new IOException("VP9 encoder unavailable");

            // OUTPUT CONTAINER (WEBM)
            this.muxer = new AVFormatContext(null);
            if (avformat.avformat_alloc_output_context2(this.muxer, null, "webm", null) < 0 || this.muxer.isNull())
                throw new IOException("Failed to allocate WebM muxer");

            // ENCODER CONFIG — YUV420P, MS TIMEBASE, CONSTANT-QUALITY VP9
            this.encoder = avcodec.avcodec_alloc_context3(codec);
            if (FFTool.isNull(this.encoder)) throw new IOException("Failed to allocate VP9 encoder");
            this.encoder.width(width);
            this.encoder.height(height);
            this.encoder.pix_fmt(avutil.AV_PIX_FMT_YUV420P);
            this.encoder.time_base(this.timeBase);
            this.encoder.framerate(new AVRational().num(fps).den(1));
            this.encoder.bit_rate(0); // 0 BITRATE + CRF => CONSTANT QUALITY
            // WEBM WANTS THE CODEC EXTRADATA IN THE CONTAINER HEADER, NOT INLINE PER PACKET
            if ((this.muxer.oformat().flags() & avformat.AVFMT_GLOBALHEADER) != 0)
                this.encoder.flags(this.encoder.flags() | avcodec.AV_CODEC_FLAG_GLOBAL_HEADER);
            final Pointer opts = this.encoder.priv_data();
            avutil.av_opt_set(opts, "crf", String.valueOf(crf), 0);
            avutil.av_opt_set(opts, "deadline", "good", 0);
            avutil.av_opt_set(opts, "cpu-used", "4", 0);
            avutil.av_opt_set(opts, "row-mt", "1", 0);
            final int opened = avcodec.avcodec_open2(this.encoder, codec, (PointerPointer<?>) null);
            if (opened < 0) throw FFTool.failure("avcodec_open2", opened);

            // VIDEO STREAM CARRYING THE ENCODER PARAMETERS
            this.stream = avformat.avformat_new_stream(this.muxer, null);
            if (FFTool.isNull(this.stream)) throw new IOException("Failed to create WebM stream");
            this.stream.time_base(this.timeBase);
            final int par = avcodec.avcodec_parameters_from_context(this.stream.codecpar(), this.encoder);
            if (par < 0) throw FFTool.failure("avcodec_parameters_from_context", par);

            // CONVERSION + FRAME SCRATCH (SAME SIZE IN/OUT — PURE FORMAT CONVERSION)
            this.scaler = swscale.sws_getContext(width, height, this.srcFormat, width, height,
                    avutil.AV_PIX_FMT_YUV420P, swscale.SWS_BILINEAR, null, null, (double[]) null);
            if (FFTool.isNull(this.scaler)) throw new IOException("Failed to create pixel converter");
            this.srcFrame = avutil.av_frame_alloc();
            this.yuvFrame = avutil.av_frame_alloc();
            if (FFTool.isNull(this.srcFrame) || FFTool.isNull(this.yuvFrame))
                throw new IOException("Failed to allocate frames");
            this.yuvFrame.format(avutil.AV_PIX_FMT_YUV420P);
            this.yuvFrame.width(width);
            this.yuvFrame.height(height);
            if (avutil.av_frame_get_buffer(this.yuvFrame, 32) < 0) throw new IOException("Failed to allocate YUV frame buffer");
            this.packet = avcodec.av_packet_alloc();
            if (FFTool.isNull(this.packet)) throw new IOException("Failed to allocate packet");

            // SEEKABLE MUX TARGET — MATROSKA SEEKS BACK TO PATCH Duration + Cues, WHICH A FORWARD-ONLY
            // OutputStream CANNOT DO; THE FINISHED FILE IS STREAMED TO out ON close()
            this.tempFile = Files.createTempFile("wm-webm-", ".webm");
            final AVIOContext pb = new AVIOContext(null);
            final int io = avformat.avio_open(pb, this.tempFile.toString(), avformat.AVIO_FLAG_WRITE);
            if (io < 0) throw FFTool.failure("avio_open", io);
            this.muxer.pb(pb);
            final int header = avformat.avformat_write_header(this.muxer, (PointerPointer<?>) null);
            if (header < 0) throw FFTool.failure("avformat_write_header", header);
        } catch (final IOException | RuntimeException e) {
            this.freeNative();
            throw e;
        }
    }

    @Override
    public void writeFrame(final ByteBuffer frame) throws IOException {
        this.writeFrame(frame, 0L);
    }

    @Override
    public void writeFrame(final ByteBuffer frame, final long delayMs) throws IOException {
        if (this.closed) throw new IOException("WebMWriter is closed");
        if (frame == null) throw new IllegalArgumentException("frame is null");
        final int expected = avutil.av_image_get_buffer_size(this.srcFormat, this.width, this.height, 1);
        if (frame.remaining() != expected)
            throw new IOException("Frame has " + frame.remaining() + " bytes, expected " + expected
                    + " for " + this.width + "x" + this.height + " " + this.pixelFormat);

        // BIND THE CALLER'S PIXELS AS A NATIVE POINTER — ZERO-COPY WHEN DIRECT, ELSE A REUSED DIRECT MIRROR
        final BytePointer src;
        if (frame.isDirect()) {
            src = new BytePointer(frame);
        } else {
            if (this.directCopy == null || this.directCopy.capacity() < expected) this.directCopy = ByteBuffer.allocateDirect(expected);
            this.directCopy.clear();
            this.directCopy.put(frame.duplicate());
            this.directCopy.flip();
            src = new BytePointer(this.directCopy);
        }

        // CONVERT INTO THE YUV420P ENCODER FRAME
        if (avutil.av_image_fill_arrays(this.srcFrame.data(), this.srcFrame.linesize(), src, this.srcFormat, this.width, this.height, 1) < 0)
            throw new IOException("Failed to bind source pixels");
        if (avutil.av_frame_make_writable(this.yuvFrame) < 0)
            throw new IOException("Failed to prepare encoder frame");
        swscale.sws_scale(this.scaler, this.srcFrame.data(), this.srcFrame.linesize(), 0, this.height,
                this.yuvFrame.data(), this.yuvFrame.linesize());

        // STRICTLY MONOTONIC MS TIMESTAMPS; FRACTIONAL ACCUMULATOR AVOIDS FIXED-FPS DRIFT
        long pts = Math.round(this.ptsMs);
        if (pts <= this.lastPts) pts = this.lastPts + 1;
        this.lastPts = pts;
        this.yuvFrame.pts(pts);
        this.ptsMs += (delayMs > 0 ? delayMs : this.frameDurMs);

        final int sent = avcodec.avcodec_send_frame(this.encoder, this.yuvFrame);
        if (sent < 0) throw FFTool.failure("avcodec_send_frame", sent);
        this.drain();
        this.frames++;
    }

    /** VP9 constant-quality factor this writer encodes with (0-63, lower is better). */
    public int crf() { return this.crf; }

    /** Number of frames written so far. */
    public int frameCount() { return this.frames; }

    @Override
    public void close() throws IOException {
        // GUARD SO A SECOND close() DOES NOT DOUBLE-FREE THE NATIVE PIPELINE OR RE-FLUSH
        if (this.closed) return;
        this.closed = true;
        try {
            if (this.muxer != null && this.encoder != null) {
                // FLUSH THE ENCODER LOOKAHEAD, THEN FINALIZE THE CONTAINER (PATCHES Duration + Cues)
                if (avcodec.avcodec_send_frame(this.encoder, (AVFrame) null) >= 0) this.drain();
                final int trailer = avformat.av_write_trailer(this.muxer);
                if (trailer < 0) throw FFTool.failure("av_write_trailer", trailer);
            }
            // CLOSE THE MUXER FILE SO EVERY BYTE (INCLUDING THE PATCHED HEADER) IS FLUSHED, THEN
            // HAND THE FINISHED, SEEKABLE WEBM TO THE CALLER
            final AVIOContext pb = this.muxer != null ? this.muxer.pb() : null;
            if (!FFTool.isNull(pb)) {
                avformat.avio_close(pb);
                this.muxer.pb((AVIOContext) null);
            }
            if (this.tempFile != null && Files.exists(this.tempFile)) Files.copy(this.tempFile, this.out);
        } finally {
            this.freeNative();
            super.close();
        }
    }

    // RECEIVES AND MUXES EVERY PACKET THE ENCODER HAS READY. A NEGATIVE receive_packet IS
    // EAGAIN (NEEDS MORE INPUT) OR EOF (FULLY FLUSHED) — BOTH MEAN "NOTHING MORE FOR NOW".
    private void drain() throws IOException {
        while (avcodec.avcodec_receive_packet(this.encoder, this.packet) >= 0) {
            this.packet.stream_index(0);
            avcodec.av_packet_rescale_ts(this.packet, this.timeBase, this.stream.time_base());
            final int wrote = avformat.av_interleaved_write_frame(this.muxer, this.packet); // TAKES + UNREFS THE PACKET
            if (wrote < 0) throw FFTool.failure("av_interleaved_write_frame", wrote);
        }
    }

    // FREES THE WHOLE NATIVE PIPELINE AND THE TEMP FILE. THE MUXER I/O IS CLOSED HERE ONLY WHEN
    // close() DID NOT ALREADY DO IT (E.G. A CONSTRUCTOR FAILURE AFTER avio_open).
    private void freeNative() {
        if (this.scaler != null) { swscale.sws_freeContext(this.scaler); this.scaler = null; }
        if (this.packet != null) { avcodec.av_packet_free(this.packet); this.packet = null; }
        if (this.srcFrame != null) { avutil.av_frame_free(this.srcFrame); this.srcFrame = null; }
        if (this.yuvFrame != null) { avutil.av_frame_free(this.yuvFrame); this.yuvFrame = null; }
        if (this.encoder != null) { avcodec.avcodec_free_context(this.encoder); this.encoder = null; }
        if (this.muxer != null) {
            final AVIOContext pb = this.muxer.pb();
            if (!FFTool.isNull(pb)) { avformat.avio_close(pb); this.muxer.pb((AVIOContext) null); }
            avformat.avformat_free_context(this.muxer);
            this.muxer = null;
        }
        if (this.tempFile != null) {
            try { Files.deleteIfExists(this.tempFile); } catch (final IOException ignored) {}
            this.tempFile = null;
        }
    }
}
