package org.watermedia.api.codecs.readers.webp.lossless;

record HuffmanGroup(
        HuffmanTable green,    // GREEN + LENGTH + COLOR CACHE
        HuffmanTable red,      // RED
        HuffmanTable blue,     // BLUE
        HuffmanTable alpha,    // ALPHA
        HuffmanTable dist      // DISTANCE
) {
}
