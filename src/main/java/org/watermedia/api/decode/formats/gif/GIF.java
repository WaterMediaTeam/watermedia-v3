package org.watermedia.api.decode.formats.gif;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.decode.Image;
import org.watermedia.api.decode.Decoder;
import org.watermedia.api.decode.formats.gif.packets.ColorTable;
import org.watermedia.api.decode.formats.gif.packets.GraphicExtension;
import org.watermedia.api.decode.formats.gif.packets.ImageDescriptor;
import org.watermedia.api.decode.formats.gif.packets.ScreenDescriptor;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public class GIF extends Decoder {
    private static final Marker IT = MarkerManager.getMarker("GIFDecoder");

    // GIF BLOCK IDENTIFIERS
    private static final long GIF87A = 0x474946383761L; // "GIF87a"
    private static final long GIF89A = 0x474946383961L; // "GIF89a"
    private static final ByteOrder DEFAULT_BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;
    private static final int IMAGE_SEPARATOR = 0x2C;
    private static final int EXTENSION_INTRODUCER = 0x21;
    private static final int TRAILER = 0x3B;
    private static final int APPLICATION_EXTENSION_LABEL = 0xFF;
    private static final int COMMENT_EXTENSION_LABEL = 0xFE;

    // LZW CONSTANTS
    private static final int MIN_LZW_CODE_SIZE = 2;
    private static final int MAX_LZW_CODE_SIZE = 8;
    private static final int MAX_STACK_SIZE = 4096;
    private static final int STACK_BUFFER_SIZE = 4097;

    // INTERLACED RENDERING CONSTANTS
    private static final int[] PASS_STARTS = {0, 4, 2, 1};
    private static final int[] PASS_INCREMENTS = {8, 8, 4, 2};

    // ANIMATION CONSTANTS
    private static final long DEFAULT_FRAME_DELAY = 10L;
    private static final int DELAY_TIME_MULTIPLIER = 10;

    // COLOR CONSTANTS
    private static final int OPAQUE_BLACK = 0xFF000000;

    // NETSCAPE EXTENSION CONSTANTS
    private static final long NETSCAPE_EXT_ID = 0x4E45545343415045L; // "NETSCAPE"
    private static final int NETSCAPE_AUTH_CODE = 0x20322E30; // " 2.0"
    private static final int NETSCAPE_BLOCK_SIZE = 11;
    private static final int NETSCAPE_SUB_BLOCK_SIZE = 3;
    private static final int NETSCAPE_SUB_BLOCK_ID = 1;

    @Override
    public boolean supported(final ByteBuffer buffer) {
        if (buffer.remaining() < 6) return false;

        final ByteOrder ogOrder = buffer.order();
        buffer.order(DEFAULT_BYTE_ORDER);

        final long signature = DataTool.readBytesAsLong(buffer, 6, ByteOrder.BIG_ENDIAN);

        if (signature != GIF87A && signature != GIF89A) {

            buffer.rewind();
            buffer.order(ogOrder);
            return false;
        }

        return true;
    }

    @Override
    public Image decode(final ByteBuffer buffer) throws IOException {
        buffer.order(DEFAULT_BYTE_ORDER);

        // PHASE 1: READ HEADER AND GLOBAL METADATA
        final ScreenDescriptor lsd = ScreenDescriptor.read(buffer);

        ColorTable globalColorTable = null;
        if (lsd.globalColorTableFlag()) {
            globalColorTable = ColorTable.read(1 << (lsd.globalColorTableSize() + 1), buffer);
        }

        // PHASE 2: INITIALIZE DECODING STATE
        final List<ByteBuffer> frames = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        int repeatCount = Image.NO_REPEAT;

        GraphicExtension currentGce = null;
        int[] previousFrame = null;
        int backgroundColor = OPAQUE_BLACK;

        if (globalColorTable != null && lsd.backgroundColorIndex() < globalColorTable.colors().length) {
            backgroundColor = globalColorTable.colors()[lsd.backgroundColorIndex()];
        }

        // PHASE 3: PROCESS DATA STREAM
        mainLoop: while (buffer.hasRemaining()) {
            final int introducer = Byte.toUnsignedInt(buffer.get());

            switch (introducer) {
                case IMAGE_SEPARATOR -> {
                    // PHASE 3A: DECODE IMAGE FRAME
                    final ImageDescriptor id = ImageDescriptor.read(buffer);

                    ColorTable activeColorTable = globalColorTable;
                    if (id.localColorTableFlag()) {
                        activeColorTable = ColorTable.read(id.getLocalColorTableSize(), buffer);
                        LOGGER.debug(IT, "Local Color Table Size: {}", activeColorTable.size());
                    }

                    if (activeColorTable == null) {
                        throw new IOException("No color table available for image frame.");
                    }

                    // DECOMPRESS LZW DATA
                    final int[] decompressedIndices = this.decompress(id, buffer);

                    // PREPARE CANVAS WITH DISPOSAL METHOD
                    final int[] currentFramePixels = frames.isEmpty()
                            ? this.createNewCanvas(lsd.width(), lsd.height(), backgroundColor)
                            : this.disposal(previousFrame, currentGce, lsd, backgroundColor, id);

                    // RENDER DECOMPRESSED DATA TO CANVAS
                    this.renderImage(decompressedIndices, currentFramePixels, id, lsd, activeColorTable, currentGce);

                    // CREATE OUTPUT BUFFER
                    final ByteBuffer frameBuffer = ByteBuffer.allocateDirect(lsd.width() * lsd.height() * 4);
                    frameBuffer.order(DEFAULT_BYTE_ORDER);
                    frameBuffer.asIntBuffer().put(currentFramePixels);
                    frameBuffer.flip();

                    frames.add(frameBuffer);
                    previousFrame = currentFramePixels.clone();

                    // CALCULATE FRAME DELAY
                    final long delay = (currentGce != null && currentGce.delayTime() > 0)
                            ? (long) currentGce.delayTime() * DELAY_TIME_MULTIPLIER : DEFAULT_FRAME_DELAY;
                    delays.add(delay);

                    currentGce = null;
                }

                case EXTENSION_INTRODUCER -> {
                    // PHASE 3B: PROCESS EXTENSIONS
                    final int label = Byte.toUnsignedInt(buffer.get());
                    switch (label) {
                        case GraphicExtension.GCE_LABEL -> currentGce = GraphicExtension.read(buffer);
                        case APPLICATION_EXTENSION_LABEL -> {
                            final int loopCount = this.readAppExtension(buffer);
                            if (loopCount >= 0) {
                                repeatCount = loopCount;
                            }
                        }
                        default -> this.skipSubBlocks(buffer);
                    }
                }

                case TRAILER -> { break mainLoop; }

                default -> { } // CONTINUE PARSING
            }
        }

        // PHASE 4: VALIDATE AND RETURN DECODED IMAGE
        if (frames.isEmpty()) {
            throw new IOException("GIF stream contains no image data.");
        }

        final long[] delayArray = delays.stream().mapToLong(Long::longValue).toArray();
        return new Image(frames.toArray(ByteBuffer[]::new), lsd.width(), lsd.height(), delayArray, repeatCount);
    }

    @Override
    public boolean test() {
        return true;
    }

    private void renderImage(final int[] indexes, final int[] canvas, final ImageDescriptor id, final ScreenDescriptor lsd, final ColorTable colorTable, final GraphicExtension gce) {
        final int transparentIndex = (gce != null && gce.transparentColorFlag()) ? gce.transparentColorIndex() : -1;

        int srcIdx = 0;
        if (id.interlacedFlag()) {
            // INTERLACED RENDERING - 4 PASS ALGORITHM
            for (int pass = 0; pass < PASS_STARTS.length; pass++) {
                for (int y = PASS_STARTS[pass]; y < id.height(); y += PASS_INCREMENTS[pass]) {
                    for (int x = 0; x < id.width(); x++) {
                        if (srcIdx >= indexes.length) return;

                        final int colorIndex = indexes[srcIdx++];
                        if (colorIndex == transparentIndex) continue;

                        final int destX = id.left() + x;
                        final int destY = id.top() + y;

                        if (destX >= 0 && destX < lsd.width() && destY >= 0 && destY < lsd.height() &&
                                colorIndex < colorTable.colors().length) {
                            canvas[destY * lsd.width() + destX] = colorTable.colors()[colorIndex];
                        }
                    }
                }
            }
        } else {
            // NORMAL SEQUENTIAL RENDERING
            for (int y = 0; y < id.height(); y++) {
                for (int x = 0; x < id.width(); x++) {
                    if (srcIdx >= indexes.length) return;

                    final int colorIndex = indexes[srcIdx++];
                    if (colorIndex == transparentIndex) continue;

                    final int destX = id.left() + x;
                    final int destY = id.top() + y;

                    if (destX >= 0 && destX < lsd.width() && destY >= 0 && destY < lsd.height() && colorIndex < colorTable.colors().length) {
                        canvas[destY * lsd.width() + destX] = colorTable.colors()[colorIndex];
                    }
                }
            }
        }
    }

    private int[] createNewCanvas(final int width, final int height, final int backgroundColor) {
        final int[] canvas = new int[width * height];
        Arrays.fill(canvas, backgroundColor);
        return canvas;
    }

    private int[] decompress(final ImageDescriptor id, final ByteBuffer buffer) throws IOException {
        final int lzwMinCodeSize = Byte.toUnsignedInt(buffer.get());

        // VALIDATE LZW CODE SIZE
        if (lzwMinCodeSize < MIN_LZW_CODE_SIZE || lzwMinCodeSize > MAX_LZW_CODE_SIZE) {
            throw new IOException("Invalid LZW minimum code size: " + lzwMinCodeSize);
        }

        final int clearCode = 1 << lzwMinCodeSize;
        final int endOfInfoCode = clearCode + 1;
        final ByteBuffer data = this.readSubBlocks(buffer);

        final int expectedSize = id.width() * id.height();
        final int[] output = new int[expectedSize];

        // INITIALIZE LZW TABLES
        final short[] prefix = new short[MAX_STACK_SIZE];
        final byte[] suffix = new byte[MAX_STACK_SIZE];
        final byte[] pixelStack = new byte[STACK_BUFFER_SIZE];

        for (int i = 0; i < clearCode; i++) {
            prefix[i] = 0;
            suffix[i] = (byte) i;
        }

        // LZW DECODER STATE
        int codeSize = lzwMinCodeSize + 1;
        int codeMask = (1 << codeSize) - 1;
        int available = clearCode + 2;

        int datum = 0;
        int bits = 0;
        int oldCode = -1;
        int first = 0;
        int top = 0;
        int pi = 0;

        // MAIN DECOMPRESSION LOOP
        for (int i = 0; i < expectedSize; ) {
            if (top == 0) {
                // LOAD MORE BITS IF NEEDED
                if (bits < codeSize) {
                    if (!data.hasRemaining()) break;
                    datum += (data.get() & 0xFF) << bits;
                    bits += 8;
                    continue;
                }

                // EXTRACT NEXT CODE
                int code = datum & codeMask;
                datum >>= codeSize;
                bits -= codeSize;

                // HANDLE SPECIAL CODES
                if (code > available || code == endOfInfoCode) break;

                if (code == clearCode) {
                    // RESET DECODER STATE
                    codeSize = lzwMinCodeSize + 1;
                    codeMask = (1 << codeSize) - 1;
                    available = clearCode + 2;
                    oldCode = -1;
                    continue;
                }

                if (oldCode == -1) {
                    // FIRST CODE AFTER CLEAR
                    pixelStack[top++] = suffix[code];
                    oldCode = code;
                    first = code;
                    continue;
                }

                final int inCode = code;

                // HANDLE SPECIAL CASE: KwKwK
                if (code == available) {
                    pixelStack[top++] = (byte) first;
                    code = oldCode;
                }

                // DECOMPRESS STRING
                while (code > clearCode) {
                    if (top >= MAX_STACK_SIZE) {
                        throw new IOException("LZW stack overflow");
                    }
                    pixelStack[top++] = suffix[code];
                    code = prefix[code];
                }

                first = suffix[code] & 0xFF;

                // ADD NEW ENTRY TO TABLE
                if (available >= MAX_STACK_SIZE) {
                    pixelStack[top++] = (byte) first;
                } else {
                    pixelStack[top++] = (byte) first;
                    prefix[available] = (short) oldCode;
                    suffix[available] = (byte) first;
                    available++;

                    // INCREASE CODE SIZE WHEN NEEDED (LEGACY ALGORITHM)
                    if (((available & codeMask) == 0) && (available < MAX_STACK_SIZE)) {
                        codeSize++;
                        codeMask += available;
                    }
                }

                oldCode = inCode;
            }

            // POP PIXELS FROM STACK
            top--;
            output[pi++] = pixelStack[top] & 0xFF;
            i++;
        }

        return output;
    }

    private int[] disposal(final int[] prevFrame, final GraphicExtension gce, final ScreenDescriptor lsd, final int background, final ImageDescriptor id) {
        if (prevFrame == null) return this.createNewCanvas(lsd.width(), lsd.height(), background);

        final int disposal = gce != null ? gce.disposalMethod() : 0;
        return switch (disposal) {
            case 2 -> { // RESTORE TO BACKGROUND
                final int[] frame = prevFrame.clone();
                for (int y = 0; y < id.height(); y++) {
                    for (int x = 0; x < id.width(); x++) {
                        final int canvasX = id.left() + x;
                        final int canvasY = id.top() + y;
                        if (canvasX >= 0 && canvasX < lsd.width() && canvasY >= 0 && canvasY < lsd.height()) {
                            frame[canvasY * lsd.width() + canvasX] = background;
                        }
                    }
                }
                yield frame;
            }
            default -> prevFrame.clone(); // 0 NO DISPOSAL, 1 DO NOT DISPOSE, 3 RESTORE TO PREVIOUS
        };
    }

    private int readAppExtension(final ByteBuffer buffer) {
        final byte blockSize = buffer.get();
        if (blockSize != NETSCAPE_BLOCK_SIZE) {
            buffer.position(buffer.position() + blockSize);
            this.skipSubBlocks(buffer);
            return -1;
        }

        // READ APPLICATION IDENTIFIER AND AUTHENTICATION CODE
        final long id = DataTool.readBytesAsLong(buffer, 8, DEFAULT_BYTE_ORDER); // Identifier
        final int auth = DataTool.readBytesAsInt(buffer, 3, DEFAULT_BYTE_ORDER); // Authentication Code

        // CHECK FOR NETSCAPE 2.0 EXTENSION
        if (id == NETSCAPE_EXT_ID && auth == NETSCAPE_AUTH_CODE) {
            // READ LOOP COUNT
            if (NETSCAPE_SUB_BLOCK_SIZE == buffer.get() && NETSCAPE_SUB_BLOCK_ID == buffer.get()) {
                int loopCount = Short.toUnsignedInt(buffer.getShort());
                if (loopCount == 0) loopCount = Image.REPEAT_FOREVER;

                // SKIP REMAINING SUB-BLOCKS
                this.skipSubBlocks(buffer);
                LOGGER.debug(IT, "Found NETSCAPE 2.0 extension with loop count: {}", loopCount);
                return loopCount;
            }
        } else {
            LOGGER.debug(IT, "Unknown application extension: ID={} AUTH={}", Long.toHexString(id), Integer.toHexString(auth));
        }

        // NOT A NETSCAPE EXTENSION OR INVALID FORMAT
        this.skipSubBlocks(buffer);
        return -1;
    }

    private void skipSubBlocks(final ByteBuffer buffer) {
        int blockSize;
        while ((blockSize = Byte.toUnsignedInt(buffer.get())) > 0) {
            buffer.position(buffer.position() + blockSize);
        }
        LOGGER.trace(IT, "Skipped sub-blocks");
    }

    private ByteBuffer readSubBlocks(final ByteBuffer buffer) {
        final var blocks = new ArrayList<byte[]>(255);
        int totalSize = 0;

        int blockSize;
        while ((blockSize = Byte.toUnsignedInt(buffer.get())) > 0) {
            final byte[] block = new byte[blockSize];
            buffer.get(block);
            blocks.add(block);
            totalSize += blockSize;
        }

        final ByteBuffer result = ByteBuffer.allocate(totalSize);
        result.order(DEFAULT_BYTE_ORDER);
        blocks.forEach(result::put);
        result.flip();
        return result;
    }
}