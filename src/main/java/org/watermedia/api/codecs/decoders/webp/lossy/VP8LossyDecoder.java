package org.watermedia.api.codecs.decoders.webp.lossy;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.util.MathUtil;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.codecs.decoders.webp.lossy.VP8Tables.*;

// RFC6386 VP8 LOSSY DECODER
// COEFFICIENT TYPES: 0=Y_AFTER_Y2, 1=Y2, 2=UV, 3=Y_I4X4
public final class VP8LossyDecoder {
    static final Marker IT = MarkerManager.getMarker(VP8LossyDecoder.class.getSimpleName());

    public static int[] decode(final ByteBuffer data, final int expW, final int expH) throws XCodecException {
        final ByteBuffer buf = data.slice().order(ByteOrder.LITTLE_ENDIAN);

        // RFC6386 SECTION 9.1 - PARSE UNCOMPRESSED FRAME HEADER
        final FrameInfo frm = parseFrameHeader(buf);
        if (!frm.keyFrame) throw new XCodecException("Only VP8 key frames supported");

        // PARTITION 0 - COMPRESSED HEADER
        final ByteBuffer p0 = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        p0.position(frm.hdrSz);
        p0.limit(frm.hdrSz + frm.p0Sz);
        final VP8BoolDecoder hdrBr = new VP8BoolDecoder(p0.slice().order(ByteOrder.LITTLE_ENDIAN));

        // RFC6386 SECTION 9.2-9.11 - PARSE COMPRESSED HEADER
        final State st = parseCompressedHeader(hdrBr, frm);

        // RFC6386 SECTION 9.5 - TOKEN PARTITION SIZES
        buf.position(frm.hdrSz + frm.p0Sz);
        final int[] partSz = new int[st.numParts];
        if (st.numParts > 1) {
            for (int i = 0; i < st.numParts - 1; i++)
                partSz[i] = (buf.get() & 0xFF) | ((buf.get() & 0xFF) << 8) | ((buf.get() & 0xFF) << 16);
        }
        int rem = buf.remaining();
        for (int i = 0; i < st.numParts - 1; i++) rem -= partSz[i];
        partSz[st.numParts - 1] = rem;

        // CREATE TOKEN PARTITION DECODERS
        final VP8BoolDecoder[] tokBr = new VP8BoolDecoder[st.numParts];
        int pos = buf.position();
        for (int i = 0; i < st.numParts; i++) {
            final ByteBuffer pb = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            pb.position(pos);
            pb.limit(pos + partSz[i]);
            tokBr[i] = new VP8BoolDecoder(pb.slice().order(ByteOrder.LITTLE_ENDIAN));
            pos += partSz[i];
        }

        // ALLOCATE YUV PLANES
        final int mbW = (frm.w + 15) >> 4;
        final int mbH = (frm.h + 15) >> 4;
        final int yStr = mbW * 16;
        final int uvStr = mbW * 8;

        final byte[] yPln = new byte[yStr * mbH * 16];
        final byte[] uPln = new byte[uvStr * mbH * 8];
        final byte[] vPln = new byte[uvStr * mbH * 8];
        Arrays.fill(yPln, (byte) 128);
        Arrays.fill(uPln, (byte) 128);
        Arrays.fill(vPln, (byte) 128);

        // ALLOCATE PER-MB INFO FOR LOOP FILTER
        st.mbSeg = new int[mbW * mbH];
        st.mbIsI4x4 = new int[mbW * mbH];
        st.mbFInner = new int[mbW * mbH];

        // RFC6386 SECTIONS 11-14 - DECODE ALL MACROBLOCKS
        decodeMBs(hdrBr, tokBr, st, yPln, uPln, vPln, yStr, uvStr, mbW, mbH);

        LOGGER.info(IT, "Frame: {}x{} = {}x{} MBs, {} parts, loopLvl={}, skipProb={}", frm.w, frm.h, mbW, mbH, st.numParts, st.loopLvl, st.skipProb);

        // RFC6386 SECTION 15 - APPLY LOOP FILTER
        applyLoopFilter(st, yPln, uPln, vPln, yStr, uvStr, mbW, mbH, frm.keyFrame);

        return DataTool.yuvToBgra(yPln, uPln, vPln, frm.w, frm.h, yStr, uvStr);
    }

    // DECODE VP8 LOSSY DIRECTLY TO BGRA BYTEBUFFER (EFFICIENT PATH FOR PURE LOSSY WITHOUT ALPHA)
    public static ByteBuffer decodeToBgra(final ByteBuffer data, final int expW, final int expH) throws XCodecException {
        final ByteBuffer buf = data.slice().order(ByteOrder.LITTLE_ENDIAN);

        // RFC6386 SECTION 9.1 - PARSE UNCOMPRESSED FRAME HEADER
        final FrameInfo frm = parseFrameHeader(buf);
        if (!frm.keyFrame) throw new XCodecException("Only VP8 key frames supported");

        // PARTITION 0 - COMPRESSED HEADER
        final ByteBuffer p0 = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        p0.position(frm.hdrSz);
        p0.limit(frm.hdrSz + frm.p0Sz);
        final VP8BoolDecoder hdrBr = new VP8BoolDecoder(p0.slice().order(ByteOrder.LITTLE_ENDIAN));

        // RFC6386 SECTION 9.2-9.11 - PARSE COMPRESSED HEADER
        final State st = parseCompressedHeader(hdrBr, frm);

        // RFC6386 SECTION 9.5 - TOKEN PARTITION SIZES
        buf.position(frm.hdrSz + frm.p0Sz);
        final int[] partSz = new int[st.numParts];
        if (st.numParts > 1) {
            for (int i = 0; i < st.numParts - 1; i++)
                partSz[i] = (buf.get() & 0xFF) | ((buf.get() & 0xFF) << 8) | ((buf.get() & 0xFF) << 16);
        }
        int rem = buf.remaining();
        for (int i = 0; i < st.numParts - 1; i++) rem -= partSz[i];
        partSz[st.numParts - 1] = rem;

        // CREATE TOKEN PARTITION DECODERS
        final VP8BoolDecoder[] tokBr = new VP8BoolDecoder[st.numParts];
        int pos = buf.position();
        for (int i = 0; i < st.numParts; i++) {
            final ByteBuffer pb = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            pb.position(pos);
            pb.limit(pos + partSz[i]);
            tokBr[i] = new VP8BoolDecoder(pb.slice().order(ByteOrder.LITTLE_ENDIAN));
            pos += partSz[i];
        }

        // ALLOCATE YUV PLANES
        final int mbW = (frm.w + 15) >> 4;
        final int mbH = (frm.h + 15) >> 4;
        final int yStr = mbW * 16;
        final int uvStr = mbW * 8;

        final byte[] yPln = new byte[yStr * mbH * 16];
        final byte[] uPln = new byte[uvStr * mbH * 8];
        final byte[] vPln = new byte[uvStr * mbH * 8];
        Arrays.fill(yPln, (byte) 128);
        Arrays.fill(uPln, (byte) 128);
        Arrays.fill(vPln, (byte) 128);

        // ALLOCATE PER-MB INFO FOR LOOP FILTER
        st.mbSeg = new int[mbW * mbH];
        st.mbIsI4x4 = new int[mbW * mbH];
        st.mbFInner = new int[mbW * mbH];

        // RFC6386 SECTIONS 11-14 - DECODE ALL MACROBLOCKS
        decodeMBs(hdrBr, tokBr, st, yPln, uPln, vPln, yStr, uvStr, mbW, mbH);

        LOGGER.info(IT, "Frame: {}x{} = {}x{} MBs, {} parts, loopLvl={}, skipProb={}", frm.w, frm.h, mbW, mbH, st.numParts, st.loopLvl, st.skipProb);

        // RFC6386 SECTION 15 - APPLY LOOP FILTER
        applyLoopFilter(st, yPln, uPln, vPln, yStr, uvStr, mbW, mbH, frm.keyFrame);

        // CONVERT DIRECTLY TO BGRA BYTEBUFFER (SKIPS INTERMEDIATE INT ARRAY)
        return DataTool.yuvToBgraBuf(yPln, uPln, vPln, frm.w, frm.h, yStr, uvStr);
    }

    // RFC6386 SECTION 9 - FRAME HEADER
    // RFC6386 SECTION 9.1 - UNCOMPRESSED DATA CHUNK
    private static FrameInfo parseFrameHeader(final ByteBuffer buf) throws XCodecException {
        if (buf.remaining() < 10) throw new XCodecException("VP8 frame too small");

        final FrameInfo f = new FrameInfo();
        final int b0 = buf.get() & 0xFF;
        final int b1 = buf.get() & 0xFF;
        final int b2 = buf.get() & 0xFF;

        f.keyFrame = (b0 & 1) == 0;
        f.p0Sz = ((b0 >> 5) & 7) | (b1 << 3) | (b2 << 11);

        if (f.keyFrame) {
            // KEY FRAME START CODE: 0x9D 0x01 0x2A
            if ((buf.get() & 0xFF) != 0x9D || (buf.get() & 0xFF) != 0x01 || (buf.get() & 0xFF) != 0x2A)
                throw new XCodecException("Invalid VP8 start code");
            f.w = buf.getShort() & 0x3FFF;
            f.h = buf.getShort() & 0x3FFF;
            f.hdrSz = 10;
        } else {
            f.hdrSz = 3;
        }
        return f;
    }

    // RFC6386 SECTIONS 9.2-9.11 - COMPRESSED HEADER
    private static State parseCompressedHeader(final VP8BoolDecoder br, final FrameInfo frm) throws XCodecException {
        final State st = new State();

        // RFC6386 SECTION 9.2 - COLOR SPACE AND PIXEL TYPE (KEY FRAMES)
        if (frm.keyFrame) {
            br.readBool();
            br.readBool();
        }

        // RFC6386 SECTION 9.3 - SEGMENT-BASED ADJUSTMENTS
        st.segEnabled = br.readBool();
        if (st.segEnabled) {
            st.segUpdMap = br.readBool();
            if (br.readBool()) {
                st.segAbsMode = br.readBool();
                for (int i = 0; i < 4; i++) if (br.readBool()) st.segQuant[i] = br.readSigned(7);
                for (int i = 0; i < 4; i++) if (br.readBool()) st.segLoop[i] = br.readSigned(6);
            }
            if (st.segUpdMap)
                for (int i = 0; i < 3; i++) if (br.readBool()) st.segProbs[i] = br.readLit(8);
        }

        // RFC6386 SECTION 9.4 - LOOP FILTER TYPE AND LEVELS
        st.filterSimple = br.readBool();
        st.loopLvl = br.readLit(6);
        st.sharpLvl = br.readLit(3);
        st.useLfDelta = br.readBool();
        if (st.useLfDelta && br.readBool()) {
            for (int i = 0; i < 4; i++) if (br.readBool()) st.refLfDelta[i] = br.readSigned(6);
            for (int i = 0; i < 4; i++) if (br.readBool()) st.modeLfDelta[i] = br.readSigned(6);
        }

        // RFC6386 SECTION 9.5 - TOKEN PARTITION COUNT
        st.numParts = 1 << br.readLit(2);

        // RFC6386 SECTION 9.6 - DEQUANTIZATION INDICES
        final int yacIdx = br.readLit(7);
        final int ydcD = br.readBool() ? br.readSigned(4) : 0;
        final int y2dcD = br.readBool() ? br.readSigned(4) : 0;
        final int y2acD = br.readBool() ? br.readSigned(4) : 0;
        final int uvdcD = br.readBool() ? br.readSigned(4) : 0;
        final int uvacD = br.readBool() ? br.readSigned(4) : 0;

        // CALCULATE QUANTIZERS FOR EACH SEGMENT
        for (int seg = 0; seg < 4; seg++) {
            int base = yacIdx;
            if (st.segEnabled && st.segAbsMode) base = st.segQuant[seg];
            else if (st.segEnabled) base += st.segQuant[seg];

            st.segQ[seg][0] = DC_QUANT[MathUtil.clamp(base + ydcD, 0, 127)];
            st.segQ[seg][1] = AC_QUANT[MathUtil.clamp(base, 0, 127)];
            st.segQ[seg][2] = DC_QUANT[MathUtil.clamp(base + y2dcD, 0, 127)] * 2;
            st.segQ[seg][3] = Math.max(8, AC_QUANT[MathUtil.clamp(base + y2acD, 0, 127)] * 155 / 100);
            st.segQ[seg][4] = DC_QUANT[MathUtil.clamp(base + uvdcD, 0, 117)];
            st.segQ[seg][5] = AC_QUANT[MathUtil.clamp(base + uvacD, 0, 127)];
        }

        // RFC6386 SECTION 13.5 - INITIALIZE COEFFICIENT PROBABILITIES
        st.coeffProbs = new int[4][8][3][11];
        for (int t = 0; t < 4; t++)
            for (int b = 0; b < 8; b++)
                for (int c = 0; c < 3; c++)
                    System.arraycopy(DEFAULT_COEFF_PROBS[t][b][c], 0, st.coeffProbs[t][b][c], 0, 11);

        // RFC6386 SECTION 9.11 - KEY FRAME: REFRESH_ENTROPY_PROBS
        if (frm.keyFrame) {
            br.readBool();
        }

        // RFC6386 SECTION 9.9/13.4 - DCT COEFFICIENT PROBABILITY UPDATE
        for (int t = 0; t < 4; t++)
            for (int b = 0; b < 8; b++)
                for (int c = 0; c < 3; c++)
                    for (int p = 0; p < 11; p++)
                        if (br.readBool(COEFF_UPDATE_PROBS[t][b][c][p]))
                            st.coeffProbs[t][b][c][p] = br.readLit(8);

        // RFC6386 SECTION 11.1 - MB_SKIP_COEFF
        st.useSkip = br.readBool();
        if (st.useSkip) st.skipProb = br.readLit(8);

        return st;
    }

    // RFC6386 SECTIONS 11-14 - MACROBLOCK DECODING
    private static void decodeMBs(final VP8BoolDecoder hdrBr, final VP8BoolDecoder[] tokBr, final State st,
                                  final byte[] yPln, final byte[] uPln, final byte[] vPln,
                                  final int yStr, final int uvStr, final int mbW, final int mbH) throws XCodecException {

        // NON-ZERO COEFFICIENT TRACKING FOR CONTEXT
        final int[] topNzY = new int[mbW * 4];
        final int[] topNzU = new int[mbW * 2];
        final int[] topNzV = new int[mbW * 2];
        final int[] topNzDc = new int[mbW];
        final short[] coeffs = new short[16];

        // RFC6386 SECTION 11.3 - SUBBLOCK MODE CONTEXT
        final SubMode[] topSubModes = new SubMode[mbW * 4];
        final SubMode[] leftSubModes = new SubMode[4];
        Arrays.fill(topSubModes, SubMode.B_DC);

        for (int mbY = 0; mbY < mbH; mbY++) {
            final VP8BoolDecoder tbr = tokBr[mbY % st.numParts];
            int leftNzY = 0, leftNzU = 0, leftNzV = 0, leftNzDc = 0;
            Arrays.fill(leftSubModes, SubMode.B_DC);

            for (int mbX = 0; mbX < mbW; mbX++) {
                // RFC6386 SECTION 10 - SEGMENT ID
                int seg = 0;
                if (st.segEnabled && st.segUpdMap) {
                    if (!hdrBr.readBool(st.segProbs[0])) seg = hdrBr.readBool(st.segProbs[1]) ? 1 : 0;
                    else seg = hdrBr.readBool(st.segProbs[2]) ? 3 : 2;
                }

                final int y1Dc = st.segQ[seg][0];
                final int y1Ac = st.segQ[seg][1];
                final int y2Dc = st.segQ[seg][2];
                final int y2Ac = st.segQ[seg][3];
                final int uvDc = st.segQ[seg][4];
                final int uvAc = st.segQ[seg][5];

                // RFC6386 SECTION 11.1-11.2 - SKIP AND LUMA MODE
                final boolean skip = st.useSkip && hdrBr.readBool(st.skipProb);
                final boolean isI4x4 = !hdrBr.readBool(145);

                final MBMode yMode;
                SubMode[] subModes = null;
                if (isI4x4) {
                    // RFC6386 SECTION 11.2-11.3 - I4X4 SUBBLOCK MODES
                    yMode = null;
                    subModes = new SubMode[16];
                    for (int j = 0; j < 4; j++)
                        for (int i = 0; i < 4; i++) {
                            final SubMode above = (j > 0) ? subModes[(j - 1) * 4 + i] : topSubModes[mbX * 4 + i];
                            final SubMode left = (i > 0) ? subModes[j * 4 + i - 1] : leftSubModes[j];
                            subModes[j * 4 + i] = readSubMode(hdrBr, above, left);
                        }

                    // UPDATE SUBBLOCK MODE CONTEXT
                    System.arraycopy(subModes, 12, topSubModes, mbX * 4, 4);
                    for (int j = 0; j < 4; j++) leftSubModes[j] = subModes[j * 4 + 3];
                } else {
                    // RFC6386 SECTION 11.2 - 16X16 LUMA MODE TREE
                    final int modeIdx;
                    if (!hdrBr.readBool(156)) modeIdx = hdrBr.readBool(163) ? 1 : 0;
                    else modeIdx = hdrBr.readBool(128) ? 3 : 2;
                    yMode = MBMode.VALUES[modeIdx];

                    // UPDATE SUBBLOCK MODE CONTEXT
                    final SubMode derived = SubMode.fromMBMode(yMode);
                    for (int i = 0; i < 4; i++) topSubModes[mbX * 4 + i] = derived;
                    Arrays.fill(leftSubModes, derived);
                }

                // RFC6386 SECTION 11.4 - CHROMA MODE
                final MBMode uvMode;
                final int uvModeIdx;
                if (!hdrBr.readBool(142)) uvModeIdx = 0;
                else if (!hdrBr.readBool(114)) uvModeIdx = 1;
                else uvModeIdx = hdrBr.readBool(183) ? 3 : 2;
                uvMode = MBMode.VALUES[uvModeIdx];

                // STORE PER-MB INFO FOR LOOP FILTER
                final int mbIdx = mbY * mbW + mbX;
                st.mbSeg[mbIdx] = seg;
                st.mbIsI4x4[mbIdx] = isI4x4 ? 1 : 0;
                st.mbFInner[mbIdx] = (isI4x4 || !skip) ? 1 : 0;

                // LOGGER.debug(IT, "MB({},{}) seg={} skip={} i4x4={} yMode={} uvMode={}", mbX, mbY, seg, skip, isI4x4, yMode, uvMode);

                final int yOff = mbY * 16 * yStr + mbX * 16;
                final int uvOff = mbY * 8 * uvStr + mbX * 8;

                // GET PREDICTION CONTEXT PIXELS
                // LIBWEBP BORDER VALUES: 127 FOR TOP (mbY=0), 129 FOR LEFT (mbX=0)
                final boolean hasAbove = mbY > 0, hasLeft = mbX > 0;
                final byte[] aboveY = hasAbove ? getAbove(yPln, yOff, yStr, 16) : borderFill(16, 127);
                final byte[] leftY = hasLeft ? getLeft(yPln, yOff, yStr, 16) : borderFill(16, 129);
                final int tlY = hasAbove ? (hasLeft ? (yPln[yOff - yStr - 1] & 0xFF) : 129) : 127;

                final byte[] aboveU = hasAbove ? getAbove(uPln, uvOff, uvStr, 8) : borderFill(8, 127);
                final byte[] leftU = hasLeft ? getLeft(uPln, uvOff, uvStr, 8) : borderFill(8, 129);
                final int tlU = hasAbove ? (hasLeft ? (uPln[uvOff - uvStr - 1] & 0xFF) : 129) : 127;

                final byte[] aboveV = hasAbove ? getAbove(vPln, uvOff, uvStr, 8) : borderFill(8, 127);
                final byte[] leftV = hasLeft ? getLeft(vPln, uvOff, uvStr, 8) : borderFill(8, 129);
                final int tlV = hasAbove ? (hasLeft ? (vPln[uvOff - uvStr - 1] & 0xFF) : 129) : 127;

                // RFC6386 SECTION 12 - INTRAFRAME PREDICTION
                if (!isI4x4) {
                    predict16(yMode, yPln, yOff, yStr, aboveY, leftY, tlY, hasAbove, hasLeft);
                } else if (skip) {
                    for (int j = 0; j < 4; j++)
                        for (int i = 0; i < 4; i++) {
                            final int sOff = yOff + j * 4 * yStr + i * 4;
                            final boolean rightEdge = (i == 3) && (mbX == mbW - 1);
                            final byte[] sAbove = (mbY > 0 || j > 0) ? getAbove8Sub(yPln, sOff, yStr, rightEdge, mbY, j, i, yOff) : borderFill(8, 127);
                            final byte[] sLeft = (mbX > 0 || i > 0) ? getLeft(yPln, sOff, yStr, 4) : borderFill(4, 129);
                            final int sTl = subTl(mbX, mbY, i, j, yPln, sOff, yStr);
                            predict4(subModes[j * 4 + i], yPln, sOff, yStr, sAbove, sLeft, sTl);
                        }
                }

                predict8(uvMode, uPln, uvOff, uvStr, aboveU, leftU, tlU, hasAbove, hasLeft);
                predict8(uvMode, vPln, uvOff, uvStr, aboveV, leftV, tlV, hasAbove, hasLeft);

                // RFC6386 SECTION 13 - DCT COEFFICIENT DECODING
                if (!skip) {
                    short[] y2dc = null;

                    if (!isI4x4) {
                        final int ctx = leftNzDc + topNzDc[mbX];
                        Arrays.fill(coeffs, (short) 0);
                        final int nz = decodeCoeffs(tbr, coeffs, st.coeffProbs, 1, 0, y2Dc, y2Ac, ctx);
                        leftNzDc = nz > 0 ? 1 : 0;
                        topNzDc[mbX] = leftNzDc;
                        if (nz > 0) {
                            y2dc = new short[16];
                            inverseWHT(coeffs, y2dc);
                        }
                    }

                    final int firstCoef = isI4x4 ? 0 : 1;
                    final int yType = isI4x4 ? 3 : 0;

                    for (int j = 0; j < 4; j++)
                        for (int i = 0; i < 4; i++) {
                            final int sOff = yOff + j * 4 * yStr + i * 4;

                            if (isI4x4) {
                                final boolean rightEdge = (i == 3) && (mbX == mbW - 1);
                                final byte[] sAbove = (mbY > 0 || j > 0) ? getAbove8Sub(yPln, sOff, yStr, rightEdge, mbY, j, i, yOff) : borderFill(8, 127);
                                final byte[] sLeft = (mbX > 0 || i > 0) ? getLeft(yPln, sOff, yStr, 4) : borderFill(4, 129);
                                final int sTl = subTl(mbX, mbY, i, j, yPln, sOff, yStr);
                                predict4(subModes[j * 4 + i], yPln, sOff, yStr, sAbove, sLeft, sTl);
                            }

                            final int ctx = ((leftNzY >> j) & 1) + topNzY[mbX * 4 + i];
                            Arrays.fill(coeffs, (short) 0);
                            if (y2dc != null) coeffs[0] = y2dc[j * 4 + i];
                            final int nz = decodeCoeffs(tbr, coeffs, st.coeffProbs, yType, firstCoef, y1Dc, y1Ac, ctx);
                            if (nz > 0 || (y2dc != null && y2dc[j * 4 + i] != 0))
                                inverseDCT(coeffs, yPln, sOff, yStr);
                            leftNzY = (leftNzY & ~(1 << j)) | ((nz > 0 ? 1 : 0) << j);
                            topNzY[mbX * 4 + i] = nz > 0 ? 1 : 0;
                        }

                    for (int j = 0; j < 2; j++)
                        for (int i = 0; i < 2; i++) {
                            final int ctx = ((leftNzU >> j) & 1) + topNzU[mbX * 2 + i];
                            Arrays.fill(coeffs, (short) 0);
                            final int nz = decodeCoeffs(tbr, coeffs, st.coeffProbs, 2, 0, uvDc, uvAc, ctx);
                            if (nz > 0) inverseDCT(coeffs, uPln, uvOff + j * 4 * uvStr + i * 4, uvStr);
                            leftNzU = (leftNzU & ~(1 << j)) | ((nz > 0 ? 1 : 0) << j);
                            topNzU[mbX * 2 + i] = nz > 0 ? 1 : 0;
                        }

                    for (int j = 0; j < 2; j++)
                        for (int i = 0; i < 2; i++) {
                            final int ctx = ((leftNzV >> j) & 1) + topNzV[mbX * 2 + i];
                            Arrays.fill(coeffs, (short) 0);
                            final int nz = decodeCoeffs(tbr, coeffs, st.coeffProbs, 2, 0, uvDc, uvAc, ctx);
                            if (nz > 0) inverseDCT(coeffs, vPln, uvOff + j * 4 * uvStr + i * 4, uvStr);
                            leftNzV = (leftNzV & ~(1 << j)) | ((nz > 0 ? 1 : 0) << j);
                            topNzV[mbX * 2 + i] = nz > 0 ? 1 : 0;
                        }
                } else {
                    leftNzY = 0;
                    for (int i = 0; i < 4; i++) topNzY[mbX * 4 + i] = 0;
                    leftNzU = 0;
                    for (int i = 0; i < 2; i++) topNzU[mbX * 2 + i] = 0;
                    leftNzV = 0;
                    for (int i = 0; i < 2; i++) topNzV[mbX * 2 + i] = 0;
                    if (!isI4x4) {
                        leftNzDc = 0;
                        topNzDc[mbX] = 0;
                    }
                }
            }
        }
    }

    // RFC6386 SECTION 13 - COEFFICIENT DECODING
    private static int decodeCoeffs(final VP8BoolDecoder br, final short[] coeffs, final int[][][][] probs,
                                    final int type, final int first, final int dcQ, final int acQ, int ctx) throws XCodecException {
        int nz = 0;
        boolean prevZero = false;

        for (int n = first; n < COEFF_BANDS.length; n++) {
            final int band = COEFF_BANDS[n];
            final int[] p = probs[type][band][Math.min(ctx, 2)];

            final int token;
            if (prevZero) {
                if (!br.readBool(p[1])) token = 0;
                else token = decodeTokenVal(br, p);
            } else {
                if (!br.readBool(p[0])) break;
                if (!br.readBool(p[1])) token = 0;
                else token = decodeTokenVal(br, p);
            }

            final int absVal;
            if (token == 0) {
                absVal = 0;
                prevZero = true;
            } else {
                absVal = token;
                final int sign = br.readBool(128) ? -1 : 1;
                final int quantized = absVal * (n == 0 ? dcQ : acQ) * sign;
                coeffs[ZIGZAG[n]] = (short) quantized;
                nz++;
                prevZero = false;
            }

            if (absVal == 0) ctx = 0;
            else if (absVal == 1) ctx = 1;
            else ctx = 2;
        }
        return nz;
    }

    private static int decodeTokenVal(final VP8BoolDecoder br, final int[] p) throws XCodecException {
        if (!br.readBool(p[2])) return 1;
        if (!br.readBool(p[3])) {
            if (!br.readBool(p[4])) return 2;
            return br.readBool(p[5]) ? 4 : 3;
        }
        if (!br.readBool(p[6])) {
            if (!br.readBool(p[7])) return 5 + readExtra(br, PCAT1);
            return 7 + readExtra(br, PCAT2);
        }
        final int b1 = br.readBool(p[8]) ? 1 : 0;
        final int b0 = br.readBool(p[9 + b1]) ? 1 : 0;
        final int cat = 2 * b1 + b0;
        return CATEGORY_BASE[cat + 2] + readExtra(br, CATEGORY_PROBS[cat + 2]);
    }

    private static int readExtra(final VP8BoolDecoder br, final int[] probs) throws XCodecException {
        int v = 0;
        for (final int prob : probs) {
            if (prob == 0) break;
            v = (v << 1) | (br.readBool(prob) ? 1 : 0);
        }
        return v;
    }

    // RFC6386 SECTION 11.2 - B-MODE TREE
    private static SubMode readSubMode(final VP8BoolDecoder br, final SubMode above, final SubMode left) throws XCodecException {
        final int[] p = KF_BMODE_PROB[above.ordinal()][left.ordinal()];
        if (!br.readBool(p[0])) return SubMode.B_DC;
        if (!br.readBool(p[1])) return SubMode.B_TM;
        if (!br.readBool(p[2])) return SubMode.B_VE;
        if (!br.readBool(p[3])) {
            if (!br.readBool(p[4])) return SubMode.B_HE;
            return br.readBool(p[5]) ? SubMode.B_VR : SubMode.B_RD;
        }
        if (!br.readBool(p[6])) return SubMode.B_LD;
        if (!br.readBool(p[7])) return SubMode.B_VL;
        return br.readBool(p[8]) ? SubMode.B_HU : SubMode.B_HD;
    }

    // RFC6386 SECTION 14 - DCT/WHT INVERSION
    private static void inverseWHT(final short[] in, final short[] out) {
        final int[] t = new int[16];

        for (int i = 0; i < 4; i++) {
            final int a1 = in[i] + in[12 + i];
            final int b1 = in[4 + i] + in[8 + i];
            final int c1 = in[4 + i] - in[8 + i];
            final int d1 = in[i] - in[12 + i];
            t[i] = a1 + b1;
            t[4 + i] = c1 + d1;
            t[8 + i] = a1 - b1;
            t[12 + i] = d1 - c1;
        }

        for (int i = 0; i < 4; i++) {
            final int a1 = t[i * 4] + t[i * 4 + 3];
            final int b1 = t[i * 4 + 1] + t[i * 4 + 2];
            final int c1 = t[i * 4 + 1] - t[i * 4 + 2];
            final int d1 = t[i * 4] - t[i * 4 + 3];
            final int a2 = a1 + b1;
            final int b2 = c1 + d1;
            final int c2 = a1 - b1;
            final int d2 = d1 - c1;
            out[i * 4] = (short) ((a2 + 3) >> 3);
            out[i * 4 + 1] = (short) ((b2 + 3) >> 3);
            out[i * 4 + 2] = (short) ((c2 + 3) >> 3);
            out[i * 4 + 3] = (short) ((d2 + 3) >> 3);
        }
    }

    private static void inverseDCT(final short[] c, final byte[] dst, final int off, final int str) {
        final int[] t = new int[16];

        for (int i = 0; i < 4; i++) {
            final int c0 = c[i], c1 = c[4 + i], c2 = c[8 + i], c3 = c[12 + i];
            final int a = c0 + c2;
            final int b = c0 - c2;
            int tmp1 = (c1 * SINPI8SQRT2) >> 16;
            int tmp2 = c3 + ((c3 * COSPI8SQRT2MINUS1) >> 16);
            final int tt = tmp1 - tmp2;
            tmp1 = c1 + ((c1 * COSPI8SQRT2MINUS1) >> 16);
            tmp2 = (c3 * SINPI8SQRT2) >> 16;
            final int d = tmp1 + tmp2;
            t[i] = a + d;
            t[12 + i] = a - d;
            t[4 + i] = b + tt;
            t[8 + i] = b - tt;
        }

        for (int i = 0; i < 4; i++) {
            final int t0 = t[i * 4], t1 = t[i * 4 + 1], t2 = t[i * 4 + 2], t3 = t[i * 4 + 3];
            final int a = t0 + t2;
            final int b = t0 - t2;
            int tmp1 = (t1 * SINPI8SQRT2) >> 16;
            int tmp2 = t3 + ((t3 * COSPI8SQRT2MINUS1) >> 16);
            final int tt = tmp1 - tmp2;
            tmp1 = t1 + ((t1 * COSPI8SQRT2MINUS1) >> 16);
            tmp2 = (t3 * SINPI8SQRT2) >> 16;
            final int d = tmp1 + tmp2;
            dst[off + i * str] = (byte) MathUtil.clip255((dst[off + i * str] & 0xFF) + ((a + d + 4) >> 3));
            dst[off + i * str + 3] = (byte) MathUtil.clip255((dst[off + i * str + 3] & 0xFF) + ((a - d + 4) >> 3));
            dst[off + i * str + 1] = (byte) MathUtil.clip255((dst[off + i * str + 1] & 0xFF) + ((b + tt + 4) >> 3));
            dst[off + i * str + 2] = (byte) MathUtil.clip255((dst[off + i * str + 2] & 0xFF) + ((b - tt + 4) >> 3));
        }
    }

    // RFC6386 SECTION 12 - INTRAFRAME PREDICTION
    // LIBWEBP BORDER VALUES: 127 FOR TOP ROW (mbY=0), 129 FOR LEFT COLUMN (mbX=0)
    private static byte[] borderFill(final int sz, final int val) {
        final byte[] r = new byte[sz];
        Arrays.fill(r, (byte) val);
        return r;
    }

    // COMPUTE TOP-LEFT PIXEL FOR I4X4 SUBBLOCK, MATCHING LIBWEBP BORDER INIT
    private static int subTl(final int mbX, final int mbY, final int i, final int j, final byte[] pln, final int sOff, final int str) {
        final boolean hasLeft = (mbX > 0 || i > 0);
        final boolean hasAbove = (mbY > 0 || j > 0);
        if (hasLeft && hasAbove) return pln[sOff - str - 1] & 0xFF;
        if (!hasAbove) return 127;  // TOP BORDER (mbY=0, j=0)
        return 129;                 // LEFT BORDER (mbX=0, i=0)
    }

    private static byte[] getAbove(final byte[] pln, final int off, final int str, final int sz) {
        final byte[] r = new byte[sz];
        System.arraycopy(pln, off - str, r, 0, sz);
        return r;
    }

    private static byte[] getAbove8Sub(final byte[] pln, final int off, final int str, final boolean rightEdgeMB, final int mbY, final int sbRow, final int sbCol, final int yOff) {
        final byte[] r = new byte[8];
        System.arraycopy(pln, off - str, r, 0, 4);

        if (sbCol == 3) {
            if (mbY == 0) {
                r[4] = r[5] = r[6] = r[7] = 127;
            } else if (!rightEdgeMB) {
                System.arraycopy(pln, yOff - str + 16, r, 4, 4);
            } else {
                final byte val = pln[yOff - str + 15];
                r[4] = r[5] = r[6] = r[7] = val;
            }
        } else {
            System.arraycopy(pln, off - str + 4, r, 4, 4);
        }
        return r;
    }

    private static byte[] getLeft(final byte[] pln, final int off, final int str, final int sz) {
        final byte[] r = new byte[sz];
        for (int i = 0; i < sz; i++) r[i] = pln[off + i * str - 1];
        return r;
    }

    private static void predict16(final MBMode mode, final byte[] dst, final int off, final int str,
                                  final byte[] above, final byte[] left, final int tl,
                                  final boolean hasAbove, final boolean hasLeft) {
        switch (mode) {
            case DC -> {
                // LIBWEBP CheckMode: DC USES ONLY REAL NEIGHBORS, NOT BORDER VALUES
                int sum = 0, cnt = 0;
                if (hasAbove) {
                    for (int i = 0; i < 16; i++) sum += above[i] & 0xFF;
                    cnt += 16;
                }
                if (hasLeft) {
                    for (int i = 0; i < 16; i++) sum += left[i] & 0xFF;
                    cnt += 16;
                }
                fill(dst, off, str, 16, (byte) (cnt > 0 ? (sum + cnt / 2) / cnt : 128));
            }
            case V -> {
                for (int y = 0; y < 16; y++) System.arraycopy(above, 0, dst, off + y * str, 16);
            }
            case H -> {
                for (int y = 0; y < 16; y++) {
                    final byte v = left[y];
                    for (int x = 0; x < 16; x++) dst[off + y * str + x] = v;
                }
            }
            case TM -> {
                for (int y = 0; y < 16; y++)
                    for (int x = 0; x < 16; x++)
                        dst[off + y * str + x] = (byte) MathUtil.clip255((left[y] & 0xFF) + (above[x] & 0xFF) - tl);
            }
        }
    }

    private static void predict8(final MBMode mode, final byte[] dst, final int off, final int str,
                                 final byte[] above, final byte[] left, final int tl,
                                 final boolean hasAbove, final boolean hasLeft) {
        switch (mode) {
            case DC -> {
                // LIBWEBP CheckMode: DC USES ONLY REAL NEIGHBORS, NOT BORDER VALUES
                int sum = 0, cnt = 0;
                if (hasAbove) {
                    for (int i = 0; i < 8; i++) sum += above[i] & 0xFF;
                    cnt += 8;
                }
                if (hasLeft) {
                    for (int i = 0; i < 8; i++) sum += left[i] & 0xFF;
                    cnt += 8;
                }
                fill(dst, off, str, 8, (byte) (cnt > 0 ? (sum + cnt / 2) / cnt : 128));
            }
            case V -> {
                for (int y = 0; y < 8; y++) System.arraycopy(above, 0, dst, off + y * str, 8);
            }
            case H -> {
                for (int y = 0; y < 8; y++) {
                    final byte v = left[y];
                    for (int x = 0; x < 8; x++) dst[off + y * str + x] = v;
                }
            }
            case TM -> {
                for (int y = 0; y < 8; y++)
                    for (int x = 0; x < 8; x++)
                        dst[off + y * str + x] = (byte) MathUtil.clip255((left[y] & 0xFF) + (above[x] & 0xFF) - tl);
            }
        }
    }

    private static void predict4(final SubMode mode, final byte[] dst, final int off, final int str, final byte[] above, final byte[] left, final int tl) {
        switch (mode) {
            case B_DC -> predDC4(dst, off, str, above, left);
            case B_TM -> predTM4(dst, off, str, above, left, tl);
            case B_VE -> predVE4(dst, off, str, above, tl);
            case B_HE -> predHE4(dst, off, str, left, tl);
            case B_LD -> predLD4(dst, off, str, above);
            case B_RD -> predRD4(dst, off, str, above, left, tl);
            case B_VR -> predVR4(dst, off, str, above, left, tl);
            case B_VL -> predVL4(dst, off, str, above);
            case B_HD -> predHD4(dst, off, str, above, left, tl);
            case B_HU -> predHU4(dst, off, str, left);
        }
    }

    private static void predDC4(final byte[] dst, final int off, final int str, final byte[] above, final byte[] left) {
        int sum = 0, cnt = 0;
        if (above != null) {
            for (int i = 0; i < 4; i++) sum += above[i] & 0xFF;
            cnt += 4;
        }
        if (left != null) {
            for (int i = 0; i < 4; i++) sum += left[i] & 0xFF;
            cnt += 4;
        }
        fill(dst, off, str, 4, (byte) (cnt > 0 ? (sum + cnt / 2) / cnt : 128));
    }

    private static void predTM4(final byte[] dst, final int off, final int str, final byte[] above, final byte[] left, final int tl) {
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
                final int av = above != null ? (above[x] & 0xFF) : 128;
                final int lv = left != null ? (left[y] & 0xFF) : 128;
                dst[off + y * str + x] = (byte) MathUtil.clip255(av + lv - tl);
            }
    }

    private static void predVE4(final byte[] dst, final int off, final int str, final byte[] above, final int tl) {
        if (above == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final byte[] s = new byte[4];
        s[0] = (byte) avg3(tl, above[0] & 0xFF, above[1] & 0xFF);
        s[1] = (byte) avg3(above[0] & 0xFF, above[1] & 0xFF, above[2] & 0xFF);
        s[2] = (byte) avg3(above[1] & 0xFF, above[2] & 0xFF, above[3] & 0xFF);
        s[3] = (byte) avg3(above[2] & 0xFF, above[3] & 0xFF, above.length > 4 ? (above[4] & 0xFF) : (above[3] & 0xFF));
        for (int y = 0; y < 4; y++) System.arraycopy(s, 0, dst, off + y * str, 4);
    }

    private static void predHE4(final byte[] dst, final int off, final int str, final byte[] left, final int tl) {
        if (left == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final byte[] s = new byte[4];
        s[0] = (byte) avg3(tl, left[0] & 0xFF, left[1] & 0xFF);
        s[1] = (byte) avg3(left[0] & 0xFF, left[1] & 0xFF, left[2] & 0xFF);
        s[2] = (byte) avg3(left[1] & 0xFF, left[2] & 0xFF, left[3] & 0xFF);
        s[3] = (byte) avg3(left[2] & 0xFF, left[3] & 0xFF, left[3] & 0xFF);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) dst[off + y * str + x] = s[y];
    }

    private static void predLD4(final byte[] dst, final int off, final int str, final byte[] above) {
        if (above == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final int[] a = new int[8];
        for (int i = 0; i < Math.min(8, above.length); i++) a[i] = above[i] & 0xFF;
        final int last = above[Math.min(7, above.length - 1)] & 0xFF;
        for (int i = above.length; i < 8; i++) a[i] = last;
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
                final int idx = x + y;
                dst[off + y * str + x] = (byte) avg3(a[idx], a[Math.min(idx + 1, 7)], a[Math.min(idx + 2, 7)]);
            }
    }

    private static void predRD4(final byte[] dst, final int off, final int str, final byte[] above, final byte[] left, final int tl) {
        if (above == null || left == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final int[] p = {left[3] & 0xFF, left[2] & 0xFF, left[1] & 0xFF, left[0] & 0xFF, tl,
                above[0] & 0xFF, above[1] & 0xFF, above[2] & 0xFF, above[3] & 0xFF};
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
                final int idx = 3 - y + x;
                dst[off + y * str + x] = (byte) avg3(p[idx], p[idx + 1], p[Math.min(idx + 2, 8)]);
            }
    }

    private static void predVR4(final byte[] dst, final int off, final int str, final byte[] above, final byte[] left, final int tl) {
        if (above == null || left == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final int l0 = left[0] & 0xFF, l1 = left[1] & 0xFF, l2 = left[2] & 0xFF;
        final int a0 = above[0] & 0xFF, a1 = above[1] & 0xFF, a2 = above[2] & 0xFF, a3 = above[3] & 0xFF;
        dst[off] = (byte) avg2(tl, a0);
        dst[off + 1] = (byte) avg2(a0, a1);
        dst[off + 2] = (byte) avg2(a1, a2);
        dst[off + 3] = (byte) avg2(a2, a3);
        dst[off + str] = (byte) avg3(l0, tl, a0);
        dst[off + str + 1] = (byte) avg3(tl, a0, a1);
        dst[off + str + 2] = (byte) avg3(a0, a1, a2);
        dst[off + str + 3] = (byte) avg3(a1, a2, a3);
        dst[off + 2 * str] = (byte) avg3(l1, l0, tl);
        dst[off + 2 * str + 1] = (byte) avg2(tl, a0);
        dst[off + 2 * str + 2] = (byte) avg2(a0, a1);
        dst[off + 2 * str + 3] = (byte) avg2(a1, a2);
        dst[off + 3 * str] = (byte) avg3(l2, l1, l0);
        dst[off + 3 * str + 1] = (byte) avg3(l0, tl, a0);
        dst[off + 3 * str + 2] = (byte) avg3(tl, a0, a1);
        dst[off + 3 * str + 3] = (byte) avg3(a0, a1, a2);
    }

    private static void predVL4(final byte[] dst, final int off, final int str, final byte[] above) {
        if (above == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final int a0 = above[0] & 0xFF, a1 = above[1] & 0xFF, a2 = above[2] & 0xFF, a3 = above[3] & 0xFF;
        final int a4 = above.length > 4 ? (above[4] & 0xFF) : a3;
        final int a5 = above.length > 5 ? (above[5] & 0xFF) : a4;
        final int a6 = above.length > 6 ? (above[6] & 0xFF) : a5;
        final int a7 = above.length > 7 ? (above[7] & 0xFF) : a6;
        dst[off] = (byte) avg2(a0, a1);
        dst[off + 1] = (byte) avg2(a1, a2);
        dst[off + 2] = (byte) avg2(a2, a3);
        dst[off + 3] = (byte) avg2(a3, a4);
        dst[off + str] = (byte) avg3(a0, a1, a2);
        dst[off + str + 1] = (byte) avg3(a1, a2, a3);
        dst[off + str + 2] = (byte) avg3(a2, a3, a4);
        dst[off + str + 3] = (byte) avg3(a3, a4, a5);
        dst[off + 2 * str] = (byte) avg2(a1, a2);
        dst[off + 2 * str + 1] = (byte) avg2(a2, a3);
        dst[off + 2 * str + 2] = (byte) avg2(a3, a4);
        dst[off + 2 * str + 3] = (byte) avg3(a4, a5, a6);
        dst[off + 3 * str] = (byte) avg3(a1, a2, a3);
        dst[off + 3 * str + 1] = (byte) avg3(a2, a3, a4);
        dst[off + 3 * str + 2] = (byte) avg3(a3, a4, a5);
        dst[off + 3 * str + 3] = (byte) avg3(a5, a6, a7);
    }

    private static void predHD4(final byte[] dst, final int off, final int str, final byte[] above, final byte[] left, final int tl) {
        if (above == null || left == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final int l0 = left[0] & 0xFF, l1 = left[1] & 0xFF, l2 = left[2] & 0xFF, l3 = left[3] & 0xFF;
        final int a0 = above[0] & 0xFF, a1 = above[1] & 0xFF, a2 = above[2] & 0xFF;
        dst[off] = (byte) avg2(tl, l0);
        dst[off + 1] = (byte) avg3(a0, tl, l0);
        dst[off + 2] = (byte) avg3(tl, a0, a1);
        dst[off + 3] = (byte) avg3(a0, a1, a2);
        dst[off + str] = (byte) avg2(l0, l1);
        dst[off + str + 1] = (byte) avg3(tl, l0, l1);
        dst[off + str + 2] = (byte) avg2(tl, l0);
        dst[off + str + 3] = (byte) avg3(a0, tl, l0);
        dst[off + 2 * str] = (byte) avg2(l1, l2);
        dst[off + 2 * str + 1] = (byte) avg3(l0, l1, l2);
        dst[off + 2 * str + 2] = (byte) avg2(l0, l1);
        dst[off + 2 * str + 3] = (byte) avg3(tl, l0, l1);
        dst[off + 3 * str] = (byte) avg2(l2, l3);
        dst[off + 3 * str + 1] = (byte) avg3(l1, l2, l3);
        dst[off + 3 * str + 2] = (byte) avg2(l1, l2);
        dst[off + 3 * str + 3] = (byte) avg3(l0, l1, l2);
    }

    private static void predHU4(final byte[] dst, final int off, final int str, final byte[] left) {
        if (left == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final int l0 = left[0] & 0xFF, l1 = left[1] & 0xFF, l2 = left[2] & 0xFF, l3 = left[3] & 0xFF;
        dst[off] = (byte) avg2(l0, l1);
        dst[off + 1] = (byte) avg3(l0, l1, l2);
        dst[off + 2] = (byte) avg2(l1, l2);
        dst[off + 3] = (byte) avg3(l1, l2, l3);
        dst[off + str] = (byte) avg2(l1, l2);
        dst[off + str + 1] = (byte) avg3(l1, l2, l3);
        dst[off + str + 2] = (byte) avg2(l2, l3);
        dst[off + str + 3] = (byte) avg3(l2, l3, l3);
        dst[off + 2 * str] = (byte) avg2(l2, l3);
        dst[off + 2 * str + 1] = (byte) avg3(l2, l3, l3);
        dst[off + 2 * str + 2] = (byte) l3;
        dst[off + 2 * str + 3] = (byte) l3;
        dst[off + 3 * str] = (byte) l3;
        dst[off + 3 * str + 1] = (byte) l3;
        dst[off + 3 * str + 2] = (byte) l3;
        dst[off + 3 * str + 3] = (byte) l3;
    }

    private static void fill(final byte[] dst, final int off, final int str, final int sz, final byte v) {
        for (int y = 0; y < sz; y++)
            for (int x = 0; x < sz; x++)
                dst[off + y * str + x] = v;
    }

    private static int avg2(final int a, final int b) {
        return (a + b + 1) >> 1;
    }

    private static int avg3(final int a, final int b, final int c) {
        return (a + 2 * b + c + 2) >> 2;
    }

    // RFC6386 SECTION 15 - LOOP FILTER
    private static void applyLoopFilter(final State st, final byte[] yPln, final byte[] uPln, final byte[] vPln,
                                        final int yStr, final int uvStr, final int mbW, final int mbH, final boolean keyFrame) {
        for (int mbY = 0; mbY < mbH; mbY++) {
            for (int mbX = 0; mbX < mbW; mbX++) {
                final int mbIdx = mbY * mbW + mbX;
                final int seg = st.mbSeg[mbIdx];
                final int isI4x4 = st.mbIsI4x4[mbIdx];
                final boolean fInner = st.mbFInner[mbIdx] != 0;

                // COMPUTE PER-SEGMENT FILTER LEVEL (MATCHING LIBWEBP PrecomputeFilterStrengths)
                int level;
                if (st.segEnabled) {
                    level = st.segLoop[seg];
                    if (!st.segAbsMode) level += st.loopLvl;
                } else {
                    level = st.loopLvl;
                }
                if (st.useLfDelta) {
                    level += st.refLfDelta[0]; // refLfDelta[0] FOR INTRA FRAME
                    if (isI4x4 != 0) level += st.modeLfDelta[0];
                }
                level = Math.max(0, Math.min(63, level));
                if (level == 0) continue;

                int iLim = level;
                final int shrp = st.sharpLvl;
                if (shrp > 0) {
                    iLim >>= (shrp > 4) ? 2 : 1;
                    if (iLim > 9 - shrp) iLim = 9 - shrp;
                }
                if (iLim < 1) iLim = 1;

                final int limit = 2 * level + iLim;
                final int mbLim = limit + 4;
                final int hevThr = keyFrame ? ((level >= 40) ? 2 : (level >= 15) ? 1 : 0)
                        : ((level >= 40) ? 3 : (level >= 20) ? 2 : (level >= 15) ? 1 : 0);

                final int yOff = mbY * 16 * yStr + mbX * 16;
                final int uvOff = mbY * 8 * uvStr + mbX * 8;

                if (st.filterSimple) {
                    if (mbX > 0) filterSimpleV(yPln, yOff, yStr, mbLim);
                    if (fInner) {
                        filterSimpleV(yPln, yOff + 4, yStr, limit);
                        filterSimpleV(yPln, yOff + 8, yStr, limit);
                        filterSimpleV(yPln, yOff + 12, yStr, limit);
                    }
                    if (mbY > 0) filterSimpleH(yPln, yOff, yStr, 16, mbLim);
                    if (fInner) {
                        filterSimpleH(yPln, yOff + 4 * yStr, yStr, 16, limit);
                        filterSimpleH(yPln, yOff + 8 * yStr, yStr, 16, limit);
                        filterSimpleH(yPln, yOff + 12 * yStr, yStr, 16, limit);
                    }
                } else {
                    if (mbX > 0) filterMBV(yPln, yOff, yStr, 16, iLim, mbLim, hevThr);
                    if (fInner) {
                        filterSubV(yPln, yOff + 4, yStr, 16, iLim, limit, hevThr);
                        filterSubV(yPln, yOff + 8, yStr, 16, iLim, limit, hevThr);
                        filterSubV(yPln, yOff + 12, yStr, 16, iLim, limit, hevThr);
                    }
                    if (mbY > 0) filterMBH(yPln, yOff, yStr, 16, iLim, mbLim, hevThr);
                    if (fInner) {
                        filterSubH(yPln, yOff + 4 * yStr, yStr, 16, iLim, limit, hevThr);
                        filterSubH(yPln, yOff + 8 * yStr, yStr, 16, iLim, limit, hevThr);
                        filterSubH(yPln, yOff + 12 * yStr, yStr, 16, iLim, limit, hevThr);
                    }

                    if (mbX > 0) filterMBV(uPln, uvOff, uvStr, 8, iLim, mbLim, hevThr);
                    if (fInner) filterSubV(uPln, uvOff + 4, uvStr, 8, iLim, limit, hevThr);
                    if (mbY > 0) filterMBH(uPln, uvOff, uvStr, 8, iLim, mbLim, hevThr);
                    if (fInner) filterSubH(uPln, uvOff + 4 * uvStr, uvStr, 8, iLim, limit, hevThr);

                    if (mbX > 0) filterMBV(vPln, uvOff, uvStr, 8, iLim, mbLim, hevThr);
                    if (fInner) filterSubV(vPln, uvOff + 4, uvStr, 8, iLim, limit, hevThr);
                    if (mbY > 0) filterMBH(vPln, uvOff, uvStr, 8, iLim, mbLim, hevThr);
                    if (fInner) filterSubH(vPln, uvOff + 4 * uvStr, uvStr, 8, iLim, limit, hevThr);
                }
            }
        }
    }

    private static void filterSimpleV(final byte[] p, final int off, final int str, final int lim) {
        for (int i = 0; i < 16; i++) {
            final int idx = off + i * str;
            final int p1 = p[idx - 2] & 0xFF, p0 = p[idx - 1] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + 1] & 0xFF;
            if ((Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2) <= lim) {
                final int a = clampS8(p1 - q1) + 3 * (q0 - p0);
                final int a1 = clampS8((a + 4) >> 3);
                final int a2 = clampS8((a + 3) >> 3);
                p[idx - 1] = (byte) MathUtil.clip255(p0 + a2);
                p[idx] = (byte) MathUtil.clip255(q0 - a1);
            }
        }
    }

    private static void filterSimpleH(final byte[] p, final int off, final int str, final int len, final int lim) {
        for (int i = 0; i < len; i++) {
            final int idx = off + i;
            final int p1 = p[idx - 2 * str] & 0xFF, p0 = p[idx - str] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + str] & 0xFF;
            if ((Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2) <= lim) {
                final int a = clampS8(p1 - q1) + 3 * (q0 - p0);
                final int a1 = clampS8((a + 4) >> 3);
                final int a2 = clampS8((a + 3) >> 3);
                p[idx - str] = (byte) MathUtil.clip255(p0 + a2);
                p[idx] = (byte) MathUtil.clip255(q0 - a1);
            }
        }
    }

    private static boolean filterYes(final int iLim, final int eLim, final int p3, final int p2, final int p1, final int p0, final int q0, final int q1, final int q2, final int q3) {
        return (Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2) <= eLim
                && Math.abs(p3 - p2) <= iLim && Math.abs(p2 - p1) <= iLim && Math.abs(p1 - p0) <= iLim
                && Math.abs(q3 - q2) <= iLim && Math.abs(q2 - q1) <= iLim && Math.abs(q1 - q0) <= iLim;
    }

    private static boolean hev(final int thr, final int p1, final int p0, final int q0, final int q1) {
        return Math.abs(p1 - p0) > thr || Math.abs(q1 - q0) > thr;
    }

    private static int clampS8(final int v) {
        return v < -128 ? -128 : (Math.min(v, 127));
    }

    private static int commonAdj(final boolean useOuter, final int p1, final int[] p0, final int[] q0, final int q1) {
        // LIBWEBP: a = 3*(q0-p0) + sclip1(p1-q1); a1 = sclip2((a+4)>>3); a2 = sclip2((a+3)>>3)
        final int a = (useOuter ? clampS8(p1 - q1) : 0) + 3 * (q0[0] - p0[0]);
        final int a1 = clampS8((a + 4) >> 3);
        final int a2 = clampS8((a + 3) >> 3);
        q0[0] = MathUtil.clip255(q0[0] - a1);
        p0[0] = MathUtil.clip255(p0[0] + a2);
        return a1;
    }

    private static void filterSubV(final byte[] p, final int off, final int str, final int len, final int iLim, final int eLim, final int hevThr) {
        for (int i = 0; i < len; i++) {
            final int idx = off + i * str;
            final int p3 = p[idx - 4] & 0xFF, p2 = p[idx - 3] & 0xFF, p1 = p[idx - 2] & 0xFF, p0 = p[idx - 1] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + 1] & 0xFF, q2 = p[idx + 2] & 0xFF, q3 = p[idx + 3] & 0xFF;
            if (filterYes(iLim, eLim, p3, p2, p1, p0, q0, q1, q2, q3)) {
                final boolean hv = hev(hevThr, p1, p0, q0, q1);
                final int[] p0a = {p0}, q0a = {q0};
                final int a = (commonAdj(hv, p1, p0a, q0a, q1) + 1) >> 1;
                p[idx - 1] = (byte) p0a[0];
                p[idx] = (byte) q0a[0];
                if (!hv) {
                    p[idx - 2] = (byte) MathUtil.clip255(p1 + a);
                    p[idx + 1] = (byte) MathUtil.clip255(q1 - a);
                }
            }
        }
    }

    private static void filterSubH(final byte[] p, final int off, final int str, final int len, final int iLim, final int eLim, final int hevThr) {
        for (int i = 0; i < len; i++) {
            final int idx = off + i;
            final int p3 = p[idx - 4 * str] & 0xFF, p2 = p[idx - 3 * str] & 0xFF, p1 = p[idx - 2 * str] & 0xFF, p0 = p[idx - str] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + str] & 0xFF, q2 = p[idx + 2 * str] & 0xFF, q3 = p[idx + 3 * str] & 0xFF;
            if (filterYes(iLim, eLim, p3, p2, p1, p0, q0, q1, q2, q3)) {
                final boolean hv = hev(hevThr, p1, p0, q0, q1);
                final int[] p0a = {p0}, q0a = {q0};
                final int a = (commonAdj(hv, p1, p0a, q0a, q1) + 1) >> 1;
                p[idx - str] = (byte) p0a[0];
                p[idx] = (byte) q0a[0];
                if (!hv) {
                    p[idx - 2 * str] = (byte) MathUtil.clip255(p1 + a);
                    p[idx + str] = (byte) MathUtil.clip255(q1 - a);
                }
            }
        }
    }

    private static void filterMBV(final byte[] p, final int off, final int str, final int len, final int iLim, final int eLim, final int hevThr) {
        for (int i = 0; i < len; i++) {
            final int idx = off + i * str;
            final int p3 = p[idx - 4] & 0xFF, p2 = p[idx - 3] & 0xFF, p1 = p[idx - 2] & 0xFF, p0 = p[idx - 1] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + 1] & 0xFF, q2 = p[idx + 2] & 0xFF, q3 = p[idx + 3] & 0xFF;
            if (filterYes(iLim, eLim, p3, p2, p1, p0, q0, q1, q2, q3)) {
                if (!hev(hevThr, p1, p0, q0, q1)) {
                    final int p0s = p0 - 128, p1s = p1 - 128, q0s = q0 - 128, q1s = q1 - 128;
                    final int w = clampS8(clampS8(p1s - q1s) + 3 * (q0s - p0s));
                    int a = clampS8((27 * w + 63) >> 7);
                    p[idx - 1] = (byte) MathUtil.clip255((p0s + a) + 128);
                    p[idx] = (byte) MathUtil.clip255((q0s - a) + 128);
                    a = clampS8((18 * w + 63) >> 7);
                    p[idx - 2] = (byte) MathUtil.clip255((p1s + a) + 128);
                    p[idx + 1] = (byte) MathUtil.clip255((q1s - a) + 128);
                    a = clampS8((9 * w + 63) >> 7);
                    p[idx - 3] = (byte) MathUtil.clip255((p2 - 128 + a) + 128);
                    p[idx + 2] = (byte) MathUtil.clip255((q2 - 128 - a) + 128);
                } else {
                    final int[] p0a = {p0}, q0a = {q0};
                    commonAdj(true, p1, p0a, q0a, q1);
                    p[idx - 1] = (byte) p0a[0];
                    p[idx] = (byte) q0a[0];
                }
            }
        }
    }

    private static void filterMBH(final byte[] p, final int off, final int str, final int len, final int iLim, final int eLim, final int hevThr) {
        for (int i = 0; i < len; i++) {
            final int idx = off + i;
            final int p3 = p[idx - 4 * str] & 0xFF, p2 = p[idx - 3 * str] & 0xFF, p1 = p[idx - 2 * str] & 0xFF, p0 = p[idx - str] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + str] & 0xFF, q2 = p[idx + 2 * str] & 0xFF, q3 = p[idx + 3 * str] & 0xFF;
            if (filterYes(iLim, eLim, p3, p2, p1, p0, q0, q1, q2, q3)) {
                if (!hev(hevThr, p1, p0, q0, q1)) {
                    final int p0s = p0 - 128, p1s = p1 - 128, q0s = q0 - 128, q1s = q1 - 128;
                    final int w = clampS8(clampS8(p1s - q1s) + 3 * (q0s - p0s));
                    int a = clampS8((27 * w + 63) >> 7);
                    p[idx - str] = (byte) MathUtil.clip255((p0s + a) + 128);
                    p[idx] = (byte) MathUtil.clip255((q0s - a) + 128);
                    a = clampS8((18 * w + 63) >> 7);
                    p[idx - 2 * str] = (byte) MathUtil.clip255((p1s + a) + 128);
                    p[idx + str] = (byte) MathUtil.clip255((q1s - a) + 128);
                    a = clampS8((9 * w + 63) >> 7);
                    p[idx - 3 * str] = (byte) MathUtil.clip255((p2 - 128 + a) + 128);
                    p[idx + 2 * str] = (byte) MathUtil.clip255((q2 - 128 - a) + 128);
                } else {
                    final int[] p0a = {p0}, q0a = {q0};
                    commonAdj(true, p1, p0a, q0a, q1);
                    p[idx - str] = (byte) p0a[0];
                    p[idx] = (byte) q0a[0];
                }
            }
        }
    }

    // YUV TO BGRA CONVERSION
    // RFC6386 SECTION 12 - PREDICTION MODES
    // RFC6386 SECTION 12.1 - 16X16 LUMA AND 8X8 CHROMA MODES
    enum MBMode {
        DC, V, H, TM;

        static final MBMode[] VALUES = values();
    }

    // RFC6386 SECTION 12.3 - 4X4 SUBBLOCK MODES
    enum SubMode {
        B_DC, B_TM, B_VE, B_HE, B_LD, B_RD, B_VR, B_VL, B_HD, B_HU;

        static final SubMode[] VALUES = values();

        static SubMode fromMBMode(final MBMode mode) {
            return switch (mode) {
                case DC -> B_DC;
                case V -> B_VE;
                case H -> B_HE;
                case TM -> B_TM;
            };
        }
    }

    // INTERNAL STATE CLASSES
    private static final class FrameInfo {
        boolean keyFrame;
        int p0Sz, w, h, hdrSz;
    }

    private static final class State {
        int[][] segQ = new int[4][6];
        int[][][][] coeffProbs;
        boolean segEnabled, segUpdMap, segAbsMode, useSkip;
        int[] segQuant = new int[4];
        int[] segLoop = new int[4];
        int[] segProbs = {255, 255, 255};
        int skipProb, numParts;
        boolean filterSimple, useLfDelta;
        int loopLvl, sharpLvl;
        int[] refLfDelta = new int[4];
        int[] modeLfDelta = new int[4];
        // PER-MB INFO FOR LOOP FILTER (STORED DURING DECODE, USED DURING FILTER)
        int[] mbSeg, mbIsI4x4, mbFInner;
    }

}