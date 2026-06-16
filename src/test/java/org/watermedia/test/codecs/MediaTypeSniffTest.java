package org.watermedia.test.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.Fixtures;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Verifies {@link CodecsAPI#getMediaType} classifies streams from their leading bytes. Real
 * on-disk fixtures cover the decodable image formats; synthetic minimal headers cover the
 * container/codec signatures (video, audio, subtitles) that have no fixture.
 */
@DisplayName("MediaType sniffing")
public class MediaTypeSniffTest {

    @TestFactory
    @DisplayName("On-disk fixtures")
    Iterable<DynamicTest> sniffsFixtures() {
        final List<DynamicTest> tests = new ArrayList<>();

        record FileCase(Path path, MediaType expected) {}
        for (final FileCase c: List.of(
                new FileCase(Fixtures.PNG_STATIC, MediaType.IMAGE),
                new FileCase(Fixtures.PNG_ANIMATED, MediaType.IMAGE),
                new FileCase(Fixtures.JPEG_BASELINE, MediaType.IMAGE),
                new FileCase(Fixtures.GIF_ANIMATED, MediaType.IMAGE),
                new FileCase(Fixtures.WEBP_LOSSLESS, MediaType.IMAGE),
                new FileCase(Fixtures.WEBP_ANIMATED, MediaType.IMAGE),
                new FileCase(Fixtures.NETPBM_PPM, MediaType.IMAGE)
        )) {
            tests.add(dynamicTest("fixture " + c.path().getFileName(), () ->
                    assertEquals(c.expected(), sniff(Fixtures.readAll(c.path())))));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Synthetic signatures")
    Iterable<DynamicTest> sniffsSignatures() {
        final List<DynamicTest> tests = new ArrayList<>();

        record ByteCase(String name, byte[] data, MediaType expected) {}
        for (final ByteCase c: List.of(
                // IMAGES
                new ByteCase("bmp", ascii("BM") , MediaType.IMAGE),
                new ByteCase("tiff-le", bytes(0x49, 0x49, 0x2A, 0x00), MediaType.IMAGE),
                new ByteCase("ico", bytes(0x00, 0x00, 0x01, 0x00), MediaType.IMAGE),
                new ByteCase("heif-mp4", ftyp("mif1"), MediaType.IMAGE),
                new ByteCase("avif", ftyp("avif"), MediaType.IMAGE),
                new ByteCase("qoi", ascii("qoif"), MediaType.IMAGE),
                // VIDEO
                new ByteCase("mp4", ftyp("isom"), MediaType.VIDEO),
                new ByteCase("mov", ftyp("qt  "), MediaType.VIDEO),
                new ByteCase("3gp", ftyp("3gp4"), MediaType.VIDEO),
                new ByteCase("mkv-webm", bytes(0x1A, 0x45, 0xDF, 0xA3), MediaType.VIDEO),
                new ByteCase("flv", ascii("FLV"), MediaType.VIDEO),
                new ByteCase("avi", riff("AVI "), MediaType.VIDEO),
                new ByteCase("mpeg-ps", bytes(0x00, 0x00, 0x01, 0xBA), MediaType.VIDEO),
                new ByteCase("ts", ts(), MediaType.VIDEO),
                new ByteCase("ogg-theora", ogg("theora"), MediaType.VIDEO),
                // AUDIO
                new ByteCase("mp3-id3", ascii("ID3"), MediaType.AUDIO),
                new ByteCase("mp3-sync", bytes(0xFF, 0xFB), MediaType.AUDIO),
                new ByteCase("aac-adts", bytes(0xFF, 0xF1), MediaType.AUDIO),
                new ByteCase("flac", ascii("fLaC"), MediaType.AUDIO),
                new ByteCase("wav", riff("WAVE"), MediaType.AUDIO),
                new ByteCase("m4a", ftyp("M4A "), MediaType.AUDIO),
                new ByteCase("ogg-vorbis", ogg("vorbis"), MediaType.AUDIO),
                new ByteCase("ac3", bytes(0x0B, 0x77, 0x00, 0x00), MediaType.AUDIO),
                // SUBTITLES
                new ByteCase("webvtt", ascii("WEBVTT\n"), MediaType.SUBTITLES),
                new ByteCase("ass", ascii("[Script Info]\n"), MediaType.SUBTITLES),
                // NEGATIVE
                new ByteCase("random", bytes(0xDE, 0xAD, 0xBE, 0xEF), MediaType.UNKNOWN),
                new ByteCase("empty", new byte[0], MediaType.UNKNOWN)
        )) {
            tests.add(dynamicTest("signature " + c.name(), () ->
                    assertEquals(c.expected(), sniff(c.data()))));
        }
        return tests;
    }

    private static MediaType sniff(final byte[] data) throws Exception {
        try (final ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            return CodecsAPI.getMediaType(in);
        }
    }

    private static byte[] ascii(final String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(final int... v) {
        final byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    // ISO BMFF: [size][ftyp][major brand]
    private static byte[] ftyp(final String brand) {
        final byte[] out = new byte[12];
        out[3] = 0x18;
        System.arraycopy(ascii("ftyp"), 0, out, 4, 4);
        System.arraycopy(ascii(brand), 0, out, 8, 4);
        return out;
    }

    // RIFF: ["RIFF"][size][form type]
    private static byte[] riff(final String form) {
        final byte[] out = new byte[12];
        System.arraycopy(ascii("RIFF"), 0, out, 0, 4);
        System.arraycopy(ascii(form), 0, out, 8, 4);
        return out;
    }

    // OGG FIRST PAGE WITH THE CODEC ID NAME WITHIN THE PROBE WINDOW
    private static byte[] ogg(final String codec) {
        final byte[] out = new byte[40];
        System.arraycopy(ascii("OggS"), 0, out, 0, 4);
        System.arraycopy(ascii(codec), 0, out, 29, codec.length());
        return out;
    }

    // MPEG-TS: 0x47 SYNC BYTE AT 0 AND AT 188
    private static byte[] ts() {
        final byte[] out = new byte[189];
        out[0] = 0x47;
        out[188] = 0x47;
        return out;
    }
}
