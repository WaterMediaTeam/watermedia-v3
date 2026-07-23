package org.watermedia.api.codecs.readers.netpbm;

/**
 * Binary Netpbm variants mapped by their magic version token ({@code P4}..{@code P7}).
 */
public enum NetpbmType {
    PBM("P4"),
    PGM("P5"),
    PPM("P6"),
    PAM("P7");

    public final String version;

    NetpbmType(final String version) {
        this.version = version;
    }

    /**
     * The type matching a {@code Pn} version token, or {@code null} when unsupported.
     */
    public static NetpbmType fromVersion(final String version) {
        for (final NetpbmType type: values()) {
            if (type.version.equals(version)) return type;
        }
        return null;
    }
}
