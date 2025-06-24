package org.watermedia.tools;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MemTool {
    private static final ConcurrentLinkedQueue<Object> GC_SAVIOR = new ConcurrentLinkedQueue<>();

    public static void storeInGCSavior(Object obj) {
        if (obj == null) return;
        synchronized (GC_SAVIOR) {
            GC_SAVIOR.add(obj);
        }
    }

    public static void removeFromGCSavior(Object obj) {
        if (obj == null) return;
        synchronized (GC_SAVIOR) {
            GC_SAVIOR.remove(obj);
        }
    }

    public static void clearGCSavior() {
        synchronized (GC_SAVIOR) {
            GC_SAVIOR.clear();
        }
    }
}
