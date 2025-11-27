package org.watermedia.tools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DataTool {
    public static int readBytesAsInt(ByteBuffer buffer, int length, ByteOrder order) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            value |= (buffer.get() & 0xFF) << pos((length * 8) - 8, i * 8, order);
        }
        return value;
    }

    public static long readBytesAsLong(ByteBuffer buffer, int length, ByteOrder order) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value |= (long) (buffer.get() & 0xFF) << pos((length * 8) - 8, i * 8, order);
        }
        return value;
    }

    public static long sumArray(final long[] array) {
        int i = 0;
        long result = 0;
        while (++i < array.length) {
            result += array[i];
        }
        return result;
    }

    public static short toShort(byte one, byte two, ByteOrder order) {
        return (short) ((one & 0xFF) << pos(8, 0, order) | (two & 0xFF) << pos(8, 8, order));
    }

    public static int toInt(byte one, byte two, byte three, byte four, ByteOrder order) {
        return (one & 0xFF) << pos(24, 0, order) | (two & 0xFF) << pos(24, 8, order) | (three & 0xFF) << pos(24, 16, order) | (four & 0xFF) << pos(24, 24, order);
    }

    public static long toLong(byte b1, byte b2, byte b3, byte b4, byte b5, byte b6, byte b7, byte b8, ByteOrder order) {
        return ((long)(b1 & 0xFF) << pos(56, 0, order)) |
                ((long)(b2 & 0xFF) << pos(56, 8, order)) |
                ((long)(b3 & 0xFF) << pos(56, 16, order)) |
                ((long)(b4 & 0xFF) << pos(56, 24, order)) |
                ((long)(b5 & 0xFF) << pos(56, 32, order)) |
                ((long)(b6 & 0xFF) << pos(56, 40, order)) |
                ((long)(b7 & 0xFF) << pos(56, 48, order))  |
                ((long)(b8 & 0xFF) << pos(56, 56, order));
    }

    private static int pos(int top, int pos, ByteOrder order) {
        return order == ByteOrder.BIG_ENDIAN ? top - pos : pos;
    }
}
