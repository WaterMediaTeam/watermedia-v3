package org.watermedia.api.codecs.readers.netpbm;

/** Netpbm variants in magic-number order: plain ASCII {@code P1}..{@code P3}, then binary {@code P4}..{@code P7}. */
public enum NetpbmType {
    PBM_PLAIN,
    PGM_PLAIN,
    PPM_PLAIN,
    PBM,
    PGM,
    PPM,
    PAM
}
