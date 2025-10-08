package org.watermedia.tools.functions;

import java.nio.ByteBuffer;

@FunctionalInterface
public interface TexImage2DFunction {
    void apply(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels);
}
