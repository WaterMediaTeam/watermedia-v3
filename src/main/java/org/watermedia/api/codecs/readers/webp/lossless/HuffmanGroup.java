package org.watermedia.api.codecs.readers.webp.lossless;

// THE FIVE PREFIX CODES OF ONE VP8L META GROUP
record HuffmanGroup(
        HuffmanTable green,    // GREEN + LENGTH + COLOR CACHE
        HuffmanTable red,
        HuffmanTable blue,
        HuffmanTable alpha,
        HuffmanTable dist      // DISTANCE
) {}
