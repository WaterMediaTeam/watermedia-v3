package org.watermedia.api.decode.formats.webp.common;

import org.watermedia.api.decode.DecoderException;

// WEBP LOSSLESS COLOR CACHE
// STORES RECENTLY USED COLORS FOR EFFICIENT ENCODING/DECODING
// SIZE IS POWER OF 2, INDEXED BY HASH OF ARGB VALUE
public final class ColorCache {
    // HASH MULTIPLIER FOR COLOR INDEX CALCULATION
    private static final int HASH_MUL = 0x1E35A7BD;

    private final int[] colors;    // COLOR STORAGE ARRAY
    private final int hashShift;   // SHIFT FOR HASH CALCULATION
    private final int hashMask;    // MASK FOR INDEX BOUNDS

    // CACHE_BITS: 1-11, CREATES 2^CACHE_BITS ENTRIES
    public ColorCache(final int cacheBits) throws DecoderException {
        if (cacheBits < 1 || cacheBits > 11) {
            throw new DecoderException("Invalid color cache bits: " + cacheBits);
        }
        final int sz = 1 << cacheBits;
        this.colors = new int[sz];
        this.hashShift = 32 - cacheBits;
        this.hashMask = sz - 1;
    }

    // INSERT COLOR INTO CACHE AT HASHED POSITION
    public void insert(final int argb) {
        final int idx = (argb * HASH_MUL) >>> this.hashShift;
        this.colors[idx] = argb;
    }

    // LOOKUP COLOR BY INDEX (MASKED TO VALID RANGE)
    public int lookup(final int idx) {
        return this.colors[idx & this.hashMask];
    }

    public int size() {
        return this.colors.length;
    }
}