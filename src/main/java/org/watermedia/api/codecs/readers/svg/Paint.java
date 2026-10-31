package org.watermedia.api.codecs.readers.svg;

/** Per-pixel colour source of the rasterizer: a straight {@code 0xAARRGGBB} value for a device-space pixel centre. */
@FunctionalInterface
interface Paint {
    int argb(double px, double py);
}
