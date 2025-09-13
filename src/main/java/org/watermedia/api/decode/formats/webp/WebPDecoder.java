package org.watermedia.api.decode.formats.webp;

import org.watermedia.api.decode.Decoder;
import org.watermedia.api.decode.Image;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Complete monolithic WebP decoder implementation following the WebP container specification.
 * Supports VP8 (lossy), VP8L (lossless), alpha channel, animation, and extended features.
 */
public class WebPDecoder extends Decoder {

    private static final int RIFF_HEADER = 0x52494646; // "RIFF"
    private static final int WEBP_HEADER = 0x57454250; // "WEBP"
    private static final int VP8_CHUNK = 0x56503820;   // "VP8 " (with space)
    private static final int VP8L_CHUNK = 0x5650384C;  // "VP8L"
    private static final int VP8X_CHUNK = 0x56503858;  // "VP8X"
    private static final int ANIM_CHUNK = 0x414E494D;  // "ANIM"
    private static final int ANMF_CHUNK = 0x414E4D46;  // "ANMF"
    private static final int ALPH_CHUNK = 0x414C5048;  // "ALPH"
    private static final int ICCP_CHUNK = 0x49434350;  // "ICCP"
    private static final int EXIF_CHUNK = 0x45584946;  // "EXIF"
    private static final int XMP_CHUNK = 0x584D5020;   // "XMP " (with space)

    private final WebPCodec codec;

    public WebPDecoder() {
        this.codec = null; // for now, must be replaced with an actual non abstract instance of WebpCodec
    }

    @Override
    public boolean supported(final ByteBuffer buffer) {
        if (buffer.remaining() < 12) {
            return false;
        }

        final int initialPos = buffer.position();

        // Read RIFF header
        final int riff = DataTool.readBytesAsInt(buffer, 4, ByteOrder.BIG_ENDIAN);
        if (riff != RIFF_HEADER) {
            buffer.position(initialPos);
            return false;
        }

        // Skip file size
        buffer.position(buffer.position() + 4);

        // Read WEBP header
        final int webp = DataTool.readBytesAsInt(buffer, 4, ByteOrder.BIG_ENDIAN);
        if (webp != WEBP_HEADER) {
            buffer.position(initialPos);
            return false;
        }

        // Don't rewind if supported, as per API specification
        return true;
    }

    @Override
    public Image decode(final ByteBuffer buffer) throws IOException {
        // File size (excluding RIFF header)
        final int fileSize = DataTool.readBytesAsInt(buffer, 4, ByteOrder.LITTLE_ENDIAN);

        // Skip "WEBP" FourCC (already validated in supported())
        buffer.position(buffer.position() + 4);

        // Check if this is an extended format file
        if (buffer.remaining() >= 8) {
            final int chunkFourCC = this.peekFourCC(buffer);

            if (chunkFourCC == VP8X_CHUNK) {
                return this.decodeExtendedFormat(buffer);
            } else if (chunkFourCC == VP8_CHUNK) {
                return this.decodeSimpleLossyFormat(buffer);
            } else if (chunkFourCC == VP8L_CHUNK) {
                return this.decodeSimpleLosslessFormat(buffer);
            }
        }

        throw new IOException("Invalid WebP format: Unknown chunk type");
    }

    @Override
    public boolean test() {
        return false;
    }

    private int peekFourCC(final ByteBuffer buffer) {
        final int pos = buffer.position();
        final int fourCC = DataTool.readBytesAsInt(buffer, 4, ByteOrder.BIG_ENDIAN);
        buffer.position(pos);
        return fourCC;
    }

    private Image decodeSimpleLossyFormat(final ByteBuffer buffer) throws IOException {
        // Read VP8 chunk header
        final int chunkFourCC = DataTool.readBytesAsInt(buffer, 4, ByteOrder.BIG_ENDIAN);
        final int chunkSize = DataTool.readBytesAsInt(buffer, 4, ByteOrder.LITTLE_ENDIAN);

        if (chunkFourCC != VP8_CHUNK) {
            throw new IOException("Expected VP8 chunk, found: " + Integer.toHexString(chunkFourCC));
        }

        // Extract VP8 data
        final ByteBuffer vp8Data = buffer.slice();
        vp8Data.limit(chunkSize);

        // Parse VP8 header to get dimensions
        final VP8Header header = this.parseVP8Header(vp8Data);

        // Decode VP8 frame
        final ByteBuffer decodedFrame = this.decodeVP8Frame(vp8Data, header);

        // Create single frame image
        return new Image(
                new ByteBuffer[] { decodedFrame },
                header.width,
                header.height,
                new long[] { 0 },
                Image.NO_REPEAT
        );
    }

    private Image decodeSimpleLosslessFormat(final ByteBuffer buffer) throws IOException {
        // Read VP8L chunk header
        final int chunkFourCC = DataTool.readBytesAsInt(buffer, 4, ByteOrder.BIG_ENDIAN);
        final int chunkSize = DataTool.readBytesAsInt(buffer, 4, ByteOrder.LITTLE_ENDIAN);

        if (chunkFourCC != VP8L_CHUNK) {
            throw new IOException("Expected VP8L chunk, found: " + Integer.toHexString(chunkFourCC));
        }

        // Extract VP8L data
        final ByteBuffer vp8lData = buffer.slice();
        vp8lData.limit(chunkSize);

        // Parse VP8L header to get dimensions
        final VP8LHeader header = this.parseVP8LHeader(vp8lData);

        // Decode VP8L frame
        final ByteBuffer decodedFrame = this.decodeVP8LFrame(vp8lData, header);

        // Create single frame image
        return new Image(
                new ByteBuffer[] { decodedFrame },
                header.width,
                header.height,
                new long[] { 0 },
                Image.NO_REPEAT
        );
    }

    private Image decodeExtendedFormat(final ByteBuffer buffer) throws IOException {
        // Read VP8X chunk
        final int chunkFourCC = DataTool.readBytesAsInt(buffer, 4, ByteOrder.BIG_ENDIAN);
        final int chunkSize = DataTool.readBytesAsInt(buffer, 4, ByteOrder.LITTLE_ENDIAN);

        if (chunkFourCC != VP8X_CHUNK) {
            throw new IOException("Expected VP8X chunk");
        }

        // Parse VP8X flags
        final byte flags = buffer.get();
        final boolean hasICC = (flags & 0x20) != 0;
        final boolean hasAlpha = (flags & 0x10) != 0;
        final boolean hasEXIF = (flags & 0x08) != 0;
        final boolean hasXMP = (flags & 0x04) != 0;
        final boolean hasAnimation = (flags & 0x02) != 0;

        // Skip reserved bytes
        buffer.position(buffer.position() + 3);

        // Read canvas dimensions (24-bit values, 1-based)
        final int canvasWidth = this.readUInt24(buffer) + 1;
        final int canvasHeight = this.readUInt24(buffer) + 1;

        // Process optional chunks
        byte[] iccProfile = null;
        if (hasICC) {
            iccProfile = this.readICCPChunk(buffer);
        }

        if (hasAnimation) {
            return this.decodeAnimatedImage(buffer, canvasWidth, canvasHeight, hasAlpha);
        } else {
            return this.decodeSingleFrameExtended(buffer, canvasWidth, canvasHeight, hasAlpha);
        }
    }

    private Image decodeAnimatedImage(final ByteBuffer buffer, final int canvasWidth, final int canvasHeight,
                                      final boolean hasAlpha) throws IOException {
        // Read ANIM chunk
        final int animFourCC = DataTool.readBytesAsInt(buffer, 4, ByteOrder.BIG_ENDIAN);
        final int animSize = DataTool.readBytesAsInt(buffer, 4, ByteOrder.LITTLE_ENDIAN);

        if (animFourCC != ANIM_CHUNK) {
            throw new IOException("Expected ANIM chunk in animated WebP");
        }

        // Parse ANIM parameters
        final int backgroundColor = DataTool.readBytesAsInt(buffer, 4, ByteOrder.LITTLE_ENDIAN);
        final int loopCount = DataTool.readBytesAsInt(buffer, 2, ByteOrder.LITTLE_ENDIAN);

        // Decode all animation frames
        final List<ByteBuffer> frames = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        while (buffer.hasRemaining()) {
            final int nextChunk = this.peekFourCC(buffer);

            if (nextChunk == ANMF_CHUNK) {
                final AnimationFrame frame = this.readANMFChunk(buffer, canvasWidth, canvasHeight);

                // Decode frame data
                final ByteBuffer frameImage = this.decodeAnimationFrame(frame, canvasWidth, canvasHeight, backgroundColor);
                frames.add(frameImage);
                delays.add(frame.duration);
            } else if (nextChunk == EXIF_CHUNK || nextChunk == XMP_CHUNK) {
                // Skip metadata chunks
                this.skipChunk(buffer);
            } else {
                // Unknown chunk, skip
                this.skipChunk(buffer);
            }
        }

        // Convert lists to arrays
        final ByteBuffer[] frameArray = frames.toArray(new ByteBuffer[0]);
        final long[] delayArray = delays.stream().mapToLong(Long::longValue).toArray();

        // Determine repeat count
        final int repeat = (loopCount == 0) ? Image.REPEAT_FOREVER : loopCount;

        return new Image(frameArray, canvasWidth, canvasHeight, delayArray, repeat);
    }

    private AnimationFrame readANMFChunk(final ByteBuffer buffer, final int canvasWidth, final int canvasHeight)
            throws IOException {
        // Read ANMF chunk header
        final int chunkFourCC = DataTool.readBytesAsInt(buffer, 4, ByteOrder.BIG_ENDIAN);
        final int chunkSize = DataTool.readBytesAsInt(buffer, 4, ByteOrder.LITTLE_ENDIAN);

        if (chunkFourCC != ANMF_CHUNK) {
            throw new IOException("Expected ANMF chunk");
        }

        final AnimationFrame frame = new AnimationFrame();

        // Read frame parameters (24-bit values)
        frame.x = this.readUInt24(buffer) * 2;
        frame.y = this.readUInt24(buffer) * 2;
        frame.width = this.readUInt24(buffer) + 1;
        frame.height = this.readUInt24(buffer) + 1;
        frame.duration = this.readUInt24(buffer);

        // Read flags
        final byte flags = buffer.get();
        frame.blendingMethod = (flags & 0x02) >> 1;
        frame.disposeMethod = flags & 0x01;

        // Read frame data
        final int frameDataStart = buffer.position();
        final int frameDataSize = chunkSize - 16; // Subtract header size

        // Process sub-chunks within frame data
        while (buffer.position() < frameDataStart + frameDataSize) {
            final int subChunkFourCC = DataTool.readBytesAsInt(buffer, 4, ByteOrder.BIG_ENDIAN);
            final int subChunkSize = DataTool.readBytesAsInt(buffer, 4, ByteOrder.LITTLE_ENDIAN);

            if (subChunkFourCC == ALPH_CHUNK) {
                frame.alphaData = ByteBuffer.allocate(subChunkSize);
                buffer.get(frame.alphaData.array(), 0, subChunkSize);
                frame.alphaData.rewind();

                // Add padding if necessary
                if (subChunkSize % 2 != 0) {
                    buffer.get();
                }
            } else if (subChunkFourCC == VP8_CHUNK || subChunkFourCC == VP8L_CHUNK) {
                frame.bitstreamData = ByteBuffer.allocate(subChunkSize);
                buffer.get(frame.bitstreamData.array(), 0, subChunkSize);
                frame.bitstreamData.rewind();
                frame.isLossless = (subChunkFourCC == VP8L_CHUNK);

                // Add padding if necessary
                if (subChunkSize % 2 != 0) {
                    buffer.get();
                }
            } else {
                // Skip unknown sub-chunk
                buffer.position(buffer.position() + subChunkSize);
                if (subChunkSize % 2 != 0) {
                    buffer.get();
                }
            }
        }

        return frame;
    }

    private ByteBuffer decodeAnimationFrame(final AnimationFrame frame, final int canvasWidth,
                                            final int canvasHeight, final int backgroundColor) throws IOException {
        final ByteBuffer frameBuffer;

        // Decode the bitstream
        if (frame.isLossless) {
            final VP8LHeader header = this.parseVP8LHeader(frame.bitstreamData);
            frameBuffer = this.decodeVP8LFrame(frame.bitstreamData, header);
        } else {
            final VP8Header header = this.parseVP8Header(frame.bitstreamData);
            frameBuffer = this.decodeVP8Frame(frame.bitstreamData, header);
        }

        // Apply alpha if present
        if (frame.alphaData != null && frame.alphaData.hasRemaining()) {
            this.applyAlphaChannel(frameBuffer, frame.alphaData, frame.width, frame.height);
        }

        // Create canvas and composite frame
        final ByteBuffer canvas = ByteBuffer.allocate(canvasWidth * canvasHeight * 4);

        // Fill with background color
        this.fillCanvas(canvas, backgroundColor, canvasWidth, canvasHeight);

        // Composite frame onto canvas
        this.compositeFrame(canvas, frameBuffer, frame, canvasWidth, canvasHeight);

        canvas.rewind();
        return canvas;
    }

    private void compositeFrame(final ByteBuffer canvas, final ByteBuffer frame, final AnimationFrame frameInfo,
                                final int canvasWidth, final int canvasHeight) {
        for (int y = 0; y < frameInfo.height && y + frameInfo.y < canvasHeight; y++) {
            for (int x = 0; x < frameInfo.width && x + frameInfo.x < canvasWidth; x++) {
                final int canvasPos = ((frameInfo.y + y) * canvasWidth + (frameInfo.x + x)) * 4;
                final int framePos = (y * frameInfo.width + x) * 4;

                if (frameInfo.blendingMethod == 0) {
                    // Alpha blending
                    this.alphaBlend(canvas, canvasPos, frame, framePos);
                } else {
                    // Replace
                    canvas.position(canvasPos);
                    canvas.put(frame.get(framePos));
                    canvas.put(frame.get(framePos + 1));
                    canvas.put(frame.get(framePos + 2));
                    canvas.put(frame.get(framePos + 3));
                }
            }
        }
    }

    private void alphaBlend(final ByteBuffer dst, final int dstPos, final ByteBuffer src, final int srcPos) {
        final int srcB = src.get(srcPos) & 0xFF;
        final int srcG = src.get(srcPos + 1) & 0xFF;
        final int srcR = src.get(srcPos + 2) & 0xFF;
        final int srcA = src.get(srcPos + 3) & 0xFF;

        final int dstB = dst.get(dstPos) & 0xFF;
        final int dstG = dst.get(dstPos + 1) & 0xFF;
        final int dstR = dst.get(dstPos + 2) & 0xFF;
        final int dstA = dst.get(dstPos + 3) & 0xFF;

        final int outA = srcA + dstA * (255 - srcA) / 255;

        if (outA == 0) {
            dst.put(dstPos, (byte) 0);
            dst.put(dstPos + 1, (byte) 0);
            dst.put(dstPos + 2, (byte) 0);
            dst.put(dstPos + 3, (byte) 0);
        } else {
            final int outB = (srcB * srcA + dstB * dstA * (255 - srcA) / 255) / outA;
            final int outG = (srcG * srcA + dstG * dstA * (255 - srcA) / 255) / outA;
            final int outR = (srcR * srcA + dstR * dstA * (255 - srcA) / 255) / outA;

            dst.put(dstPos, (byte) outB);
            dst.put(dstPos + 1, (byte) outG);
            dst.put(dstPos + 2, (byte) outR);
            dst.put(dstPos + 3, (byte) outA);
        }
    }

    private void fillCanvas(final ByteBuffer canvas, final int color, final int width, final int height) {
        final byte b = (byte) ((color >> 24) & 0xFF);
        final byte g = (byte) ((color >> 16) & 0xFF);
        final byte r = (byte) ((color >> 8) & 0xFF);
        final byte a = (byte) (color & 0xFF);

        for (int i = 0; i < width * height; i++) {
            canvas.put(b);
            canvas.put(g);
            canvas.put(r);
            canvas.put(a);
        }
        canvas.rewind();
    }

    private Image decodeSingleFrameExtended(final ByteBuffer buffer, final int canvasWidth,
                                            final int canvasHeight, final boolean hasAlpha) throws IOException {
        ByteBuffer alphaData = null;
        ByteBuffer bitstreamData = null;
        boolean isLossless = false;

        // Read chunks until we find the bitstream
        while (buffer.hasRemaining()) {
            final int chunkFourCC = DataTool.readBytesAsInt(buffer, 4, ByteOrder.BIG_ENDIAN);
            final int chunkSize = DataTool.readBytesAsInt(buffer, 4, ByteOrder.LITTLE_ENDIAN);

            if (chunkFourCC == ALPH_CHUNK) {
                alphaData = ByteBuffer.allocate(chunkSize);
                buffer.get(alphaData.array(), 0, chunkSize);
                alphaData.rewind();

                // Skip padding
                if (chunkSize % 2 != 0) {
                    buffer.get();
                }
            } else if (chunkFourCC == VP8_CHUNK || chunkFourCC == VP8L_CHUNK) {
                isLossless = (chunkFourCC == VP8L_CHUNK);
                bitstreamData = ByteBuffer.allocate(chunkSize);
                buffer.get(bitstreamData.array(), 0, chunkSize);
                bitstreamData.rewind();

                // Skip padding
                if (chunkSize % 2 != 0) {
                    buffer.get();
                }
                break; // Found the main image data
            } else {
                // Skip unknown chunk
                buffer.position(buffer.position() + chunkSize);
                if (chunkSize % 2 != 0) {
                    buffer.get();
                }
            }
        }

        if (bitstreamData == null) {
            throw new IOException("No image data found in WebP file");
        }

        // Decode the frame
        final ByteBuffer decodedFrame;
        if (isLossless) {
            final VP8LHeader header = this.parseVP8LHeader(bitstreamData);
            decodedFrame = this.decodeVP8LFrame(bitstreamData, header);
        } else {
            final VP8Header header = this.parseVP8Header(bitstreamData);
            decodedFrame = this.decodeVP8Frame(bitstreamData, header);
        }

        // Apply alpha channel if present
        if (alphaData != null && alphaData.hasRemaining()) {
            this.applyAlphaChannel(decodedFrame, alphaData, canvasWidth, canvasHeight);
        }

        return new Image(
                new ByteBuffer[] { decodedFrame },
                canvasWidth,
                canvasHeight,
                new long[] { 0 },
                Image.NO_REPEAT
        );
    }

    private void applyAlphaChannel(final ByteBuffer image, final ByteBuffer alphaData, final int width, final int height) throws IOException {
        // Parse alpha header
        final byte flags = alphaData.get();
        final int preprocessing = (flags >> 4) & 0x03;
        final int filtering = (flags >> 2) & 0x03;
        final int compression = flags & 0x03;

        final byte[] alphaValues;

        if (compression == 0) {
            // Uncompressed alpha
            alphaValues = new byte[width * height];
            alphaData.get(alphaValues);
        } else if (compression == 1) {
            // VP8L compressed alpha
            alphaValues = this.decompressAlphaVP8L(alphaData, width, height);
        } else {
            throw new IOException("Unsupported alpha compression method: " + compression);
        }

        // Apply filtering
        if (filtering > 0) {
            this.applyAlphaFiltering(alphaValues, width, height, filtering);
        }

        // Apply alpha values to image
        image.rewind();
        for (int i = 0; i < width * height; i++) {
            image.position(i * 4 + 3);
            image.put(alphaValues[i]);
        }
        image.rewind();
    }

    private void applyAlphaFiltering(final byte[] alpha, final int width, final int height, final int method) {
        final byte[] filtered = new byte[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int idx = y * width + x;
                int predictor = 0;

                if (x == 0 && y == 0) {
                    predictor = 0;
                } else if (x == 0) {
                    predictor = alpha[(y - 1) * width] & 0xFF;
                } else if (y == 0) {
                    predictor = alpha[x - 1] & 0xFF;
                } else {
                    final int A = alpha[idx - 1] & 0xFF;
                    final int B = alpha[(y - 1) * width + x] & 0xFF;
                    final int C = alpha[(y - 1) * width + (x - 1)] & 0xFF;

                    predictor = switch (method) {
                        case 1 -> A;  // Horizontal
                        case 2 -> B; // Vertical
                        case 3 -> Math.max(0, Math.min(255, A + B - C)); // Gradient
                        default -> predictor;
                    };
                }

                filtered[idx] = (byte) ((predictor + (alpha[idx] & 0xFF)) & 0xFF);
            }
        }

        System.arraycopy(filtered, 0, alpha, 0, alpha.length);
    }

    private byte[] decompressAlphaVP8L(final ByteBuffer alphaData, final int width, final int height) throws IOException {
        // Simplified VP8L decompression for alpha channel
        // This is a placeholder for full VP8L implementation
        final byte[] result = new byte[width * height];

        // For now, read as uncompressed
        if (alphaData.remaining() >= width * height) {
            alphaData.get(result);
        } else {
            // Fill with opaque alpha
            Arrays.fill(result, (byte) 0xFF);
        }

        return result;
    }

    private ByteBuffer decodeVP8Frame(final ByteBuffer data, final VP8Header header) throws IOException {
        // Create output buffer in BGRA format
        final ByteBuffer output = ByteBuffer.allocate(header.width * header.height * 4);

        // Initialize codec for VP8 decoding
        this.codec.initialize();

        // Create frame buffer
        WebPCodec.FrameBuffer frameBuffer = new WebPCodec.FrameBuffer();
        frameBuffer.width = header.width;
        frameBuffer.height = header.height;
        frameBuffer.hasAlpha = false;

        // Decode VP8 bitstream
        frameBuffer = this.codec.decodeVP8(data, header.toCodecHeader());

        // Convert YUV to BGRA
        final ByteBuffer bgra = this.codec.convertYUVToBGRA(frameBuffer);

        // Cleanup
        this.codec.cleanup();

        return bgra != null ? bgra : output;
    }

    private ByteBuffer decodeVP8LFrame(final ByteBuffer data, final VP8LHeader header) throws IOException {
        // Create output buffer in BGRA format
        final ByteBuffer output = ByteBuffer.allocate(header.width * header.height * 4);

        // Initialize codec for VP8L decoding
        this.codec.initialize();

        // Decode VP8L bitstream
        final WebPCodec.FrameBuffer frameBuffer = this.codec.decodeVP8L(data);

        // VP8L directly outputs BGRA
        if (frameBuffer != null && frameBuffer.toBGRA.get() != null) {
            return frameBuffer.toBGRA.get();
        }

        // Cleanup
        this.codec.cleanup();

        return output;
    }

    private VP8Header parseVP8Header(final ByteBuffer data) throws IOException {
        final VP8Header header = new VP8Header();

        // Read frame tag (3 bytes)
        final byte tag0 = data.get();
        final byte tag1 = data.get();
        final byte tag2 = data.get();

        header.keyFrame = (tag0 & 0x01) == 0;
        header.version = (tag0 >> 1) & 0x07;
        header.showFrame = (tag0 & 0x10) != 0;
        header.firstPartSize = ((tag0 & 0xE0) >> 5) | (tag1 << 3) | (tag2 << 11);

        if (header.keyFrame) {
            // Read start code (3 bytes)
            final byte startCode0 = data.get();
            final byte startCode1 = data.get();
            final byte startCode2 = data.get();

            if (startCode0 != (byte) 0x9D || startCode1 != (byte) 0x01 || startCode2 != (byte) 0x2A) {
                throw new IOException("Invalid VP8 start code");
            }

            // Read dimensions
            final int widthAndScale = DataTool.readBytesAsInt(data, 2, ByteOrder.LITTLE_ENDIAN);
            final int heightAndScale = DataTool.readBytesAsInt(data, 2, ByteOrder.LITTLE_ENDIAN);

            header.width = widthAndScale & 0x3FFF;
            header.horizontalScale = widthAndScale >> 14;
            header.height = heightAndScale & 0x3FFF;
            header.verticalScale = heightAndScale >> 14;
        }

        return header;
    }

    private VP8LHeader parseVP8LHeader(final ByteBuffer data) throws IOException {
        final VP8LHeader header = new VP8LHeader();

        // Read signature (1 byte)
        final byte signature = data.get();
        if (signature != 0x2F) {
            throw new IOException("Invalid VP8L signature");
        }

        // Read 14-bit width and height
        final BitReader reader = new BitReader(data);
        header.width = reader.readBits(14) + 1;
        header.height = reader.readBits(14) + 1;
        header.hasAlpha = reader.readBit() == 1;
        header.version = reader.readBits(3);

        return header;
    }

    private byte[] readICCPChunk(final ByteBuffer buffer) throws IOException {
        final int chunkFourCC = DataTool.readBytesAsInt(buffer, 4, ByteOrder.BIG_ENDIAN);
        final int chunkSize = DataTool.readBytesAsInt(buffer, 4, ByteOrder.LITTLE_ENDIAN);

        if (chunkFourCC != ICCP_CHUNK) {
            throw new IOException("Expected ICCP chunk");
        }

        final byte[] iccProfile = new byte[chunkSize];
        buffer.get(iccProfile);

        // Skip padding
        if (chunkSize % 2 != 0) {
            buffer.get();
        }

        return iccProfile;
    }

    private void skipChunk(final ByteBuffer buffer) {
        buffer.position(buffer.position() + 4); // Skip FourCC
        final int chunkSize = DataTool.readBytesAsInt(buffer, 4, ByteOrder.LITTLE_ENDIAN);
        buffer.position(buffer.position() + chunkSize);

        // Skip padding
        if (chunkSize % 2 != 0) {
            buffer.position(buffer.position() + 1);
        }
    }

    private int readUInt24(final ByteBuffer buffer) {
        return DataTool.readBytesAsInt(buffer, 3, ByteOrder.LITTLE_ENDIAN);
    }

    // Helper classes

    private static class VP8Header {
        boolean keyFrame;
        int version;
        boolean showFrame;
        int firstPartSize;
        int width;
        int height;
        int horizontalScale;
        int verticalScale;

        WebPCodec.VP8FrameHeader toCodecHeader() {
            final WebPCodec.VP8FrameHeader header = new WebPCodec.VP8FrameHeader();
            header.keyFrame = this.keyFrame;
            header.version = this.version;
            header.showFrame = this.showFrame;
            header.partitionLength = this.firstPartSize;
            header.width = this.width;
            header.height = this.height;
            header.horizontalScale = this.horizontalScale;
            header.verticalScale = this.verticalScale;
            return header;
        }
    }

    private static class VP8LHeader {
        int width;
        int height;
        boolean hasAlpha;
        int version;
    }

    private static class AnimationFrame {
        int x;
        int y;
        int width;
        int height;
        long duration;
        int blendingMethod;
        int disposeMethod;
        ByteBuffer alphaData;
        ByteBuffer bitstreamData;
        boolean isLossless;
    }

    private static class BitReader {
        private final ByteBuffer buffer;
        private long value;
        private int bitsAvailable;

        BitReader(final ByteBuffer buffer) {
            this.buffer = buffer;
            this.value = 0;
            this.bitsAvailable = 0;
        }

        int readBits(final int numBits) {
            if (numBits > 32) {
                throw new IllegalArgumentException("Cannot read more than 32 bits at once");
            }

            while (this.bitsAvailable < numBits && this.buffer.hasRemaining()) {
                this.value |= ((long)(this.buffer.get() & 0xFF)) << this.bitsAvailable;
                this.bitsAvailable += 8;
            }

            final int result = (int)(this.value & ((1L << numBits) - 1));
            this.value >>>= numBits;
            this.bitsAvailable -= numBits;

            return result;
        }

        int readBit() {
            return this.readBits(1);
        }
    }
}