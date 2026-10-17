// DEPENDENCY MODE ONLY: FORGE 1.21.1+ ENFORCES IT; NEOFORGE, FABRIC, OLDER FORGE AND THE APP IGNORE IT.
// REQUIRES COVER WHAT FORGE RUNS AND STAY STATIC, SO A HOST WITHOUT ONE OF THEM STILL RESOLVES THE LAYER
module org.watermedia {
    requires static transitive com.google.gson;
    requires static transitive java.desktop;
    requires static transitive javafx.graphics;
    requires static transitive org.apache.logging.log4j;
    requires static transitive org.lwjgl.vulkan;
    requires static java.management; // LAUNCHER CHECK READS THE JVM OPTIONS
    requires static java.xml;
    requires static javafx.base;
    requires static jdk.httpserver;
    requires static jdk.unsupported; // SHADED JAVACPP
    requires static net.minecraftforge.fmlloader;
    requires static net.minecraftforge.mergetool.api;
    requires static org.lwjgl;
    requires static org.lwjgl.openal;
    requires static org.lwjgl.opengl;
    requires static org.lwjgl.shaderc;
    requires static waterconfig;
    requires static watermedia_binaries;

    exports org.watermedia;
    exports org.watermedia.api.codecs;
    exports org.watermedia.api.media;
    exports org.watermedia.api.network;
    exports org.watermedia.api.platform;
    exports org.watermedia.api.util;
    exports org.watermedia.tools to watermedia_binaries;
    exports org.watermedia.bootstrap to watermedia_binaries;

    // WATERCONFIG BINDS THE SETTINGS, GSON BINDS PLATFORM RESPONSES AND FML CONSTRUCTS THE ENTRY POINT
    opens org.watermedia to waterconfig;
    opens org.watermedia.api.platform.web to com.google.gson;
    opens org.watermedia.bootstrap;
}
