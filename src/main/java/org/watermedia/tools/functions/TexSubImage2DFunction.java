package org.watermedia.tools.functions;

import java.nio.ByteBuffer;

@FunctionalInterface
public interface TexSubImage2DFunction {
    void apply(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels);
}
