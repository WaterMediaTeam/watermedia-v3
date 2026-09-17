package org.watermedia;

import org.watermedia.WaterMedia.BootStatus;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.ServerMediaPlayer;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;

import static org.bytedeco.ffmpeg.global.avutil.*;

final class TLSLifecycleProbe {
    static void run(final Path root) throws Exception {
        final TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        X509Certificate anchor = null;
        for (final var manager: factory.getTrustManagers()) {
            if (manager instanceof final X509TrustManager trust && trust.getAcceptedIssuers().length != 0) {
                anchor = trust.getAcceptedIssuers()[0];
                break;
            }
        }
        if (anchor == null) throw new AssertionError("The fixture requires a JVM certificate authority");
        final char[] password = "tls-fixture".toCharArray();
        final Path trusted = root.resolve("trusted.p12");
        final Path empty = root.resolve("empty.p12");
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        try (final var output = Files.newOutputStream(empty)) { store.store(output, password); }
        store.setCertificateEntry("first", anchor);
        store.setCertificateEntry("duplicate", anchor);
        try (final var output = Files.newOutputStream(trusted)) { store.store(output, password); }

        final var properties = new HashMap<String, String>();
        for (final String key: new String[] { "javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType", "javax.net.ssl.trustStorePassword" })
            properties.put(key, System.getProperty(key));
        Path blocked = null;
        Path previous = null;
        try {
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
            System.setProperty("javax.net.ssl.trustStorePassword", new String(password));
            for (int session = 0; session < 3; session++) {
                System.setProperty("javax.net.ssl.trustStore", (session == 1 ? empty : trusted).toString());
                WaterMedia.start("TLS_TEST", root.resolve("tls-session-" + session), root, true);
                final var options = new AVDictionary(null);
                try {
                    if (session == 1) {
                        if (MediaAPI.ffmpegLoaded() || !MediaAPI.ffmpegError())
                            throw new AssertionError("An empty truststore did not refuse native HTTPS initialization");
                        requireClosed(options);
                        WaterMedia.stop();
                        continue;
                    }
                    if (!MediaAPI.ffmpegLoaded()) throw new AssertionError("Patched FFmpeg did not load: " + WaterMedia.status());
                    try {
                        MediaAPI.configureTLS(null);
                        throw new AssertionError("A null TLS dictionary reached the native boundary");
                    } catch (final NullPointerException expected) {
                        if (!"options".equals(expected.getMessage())) throw expected;
                    }
                    av_dict_set(options, "tls_verify", "0", 0);
                    av_dict_set(options, "ca_file", "untrusted-fixture.pem", 0);
                    av_dict_set(options, "verify", "0", 0);
                    av_dict_set(options, "cafile", "untrusted-alias.pem", 0);
                    av_dict_set(options, "verifyhost", "wrong.invalid", 0);
                    av_dict_set(options, "protocol_opts", "tls_verify=0:ca_file=untrusted.pem:verify=0:cafile=untrusted-alias.pem", 0);
                    MediaAPI.configureTLS(options);
                    if (!"1".equals(av_dict_get(options, "tls_verify", null, 0).value().getString()))
                        throw new AssertionError("Input configuration preserved insecure TLS verification");
                    for (final String alias: new String[] { "verify", "cafile", "verifyhost" }) {
                        final var entry = av_dict_get(options, alias, null, 0);
                        if (entry != null && !entry.isNull()) throw new AssertionError("An insecure TLS alias survived configuration");
                    }
                    final String ca = av_dict_get(options, "ca_file", null, 0).value().getString(StandardCharsets.UTF_8);
                    final var nested = new AVDictionary(null);
                    try {
                        final String protocols = av_dict_get(options, "protocol_opts", null, 0).value().getString(StandardCharsets.UTF_8);
                        if (av_dict_parse_string(nested, protocols, "=", ":", 0) < 0 || av_dict_count(nested) != 2
                                || !"1".equals(av_dict_get(nested, "tls_verify", null, 0).value().getString())
                                || !ca.equals(av_dict_get(nested, "ca_file", null, 0).value().getString(StandardCharsets.UTF_8)))
                            throw new AssertionError("Nested connections did not retain the exact trusted CA path and verification policy");
                    } finally { av_dict_free(nested); }
                    final Path bundle = Path.of(ca);
                    if (!Files.isRegularFile(bundle) || bundle.equals(previous))
                        throw new AssertionError("The new TLS session did not create its own CA bundle");
                    try (final var input = Files.newInputStream(bundle)) {
                        final var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
                        if (certificates.size() != 1 || !Arrays.equals(anchor.getEncoded(), certificates.iterator().next().getEncoded()))
                            throw new AssertionError("The PEM bundle did not match the JVM's deduplicated trust anchors");
                    }
                    if (session == 0) {
                        final ServerMediaPlayer player = new ServerMediaPlayer();
                        try {
                            try {
                                WaterMedia.stop();
                                throw new AssertionError("Shutdown accepted a live player");
                            } catch (final IllegalStateException expected) {
                                if (!Files.isRegularFile(bundle)) throw new AssertionError("Refused shutdown deleted the active CA bundle");
                                MediaAPI.configureTLS(options);
                            }
                        } finally { player.release(); }
                    } else {
                        // A NONEMPTY DIRECTORY MAKES DELETE FAIL ON EVERY OS WITHOUT CHANGING PERMISSIONS.
                        Files.delete(bundle);
                        Files.createDirectory(bundle);
                        blocked = bundle.resolve("held");
                        Files.writeString(blocked, "fixture");
                        try {
                            WaterMedia.stop();
                            throw new AssertionError("Shutdown accepted incomplete CA cleanup");
                        } catch (final IllegalStateException expected) {
                            if (WaterMedia.status().state() != BootStatus.State.FAILED || !Files.exists(blocked))
                                throw new AssertionError("Incomplete CA cleanup did not retain the session");
                        }
                        requireClosed(options);
                        Files.delete(blocked);
                        blocked = null;
                    }
                    WaterMedia.stop();
                    if (Files.exists(bundle) || WaterMedia.status().state() != BootStatus.State.STOPPED)
                        throw new AssertionError("Completed shutdown retained its CA bundle");
                    requireClosed(options);
                    previous = bundle;
                } finally { av_dict_free(options); }
            }
        } finally {
            if (blocked != null) Files.deleteIfExists(blocked);
            WaterMedia.stop();
            for (final var entry: properties.entrySet()) {
                if (entry.getValue() == null) System.clearProperty(entry.getKey());
                else System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void requireClosed(final AVDictionary options) {
        try {
            MediaAPI.configureTLS(options);
        } catch (final IllegalStateException expected) { return; }
        throw new AssertionError("A closed TLS session still configured a native connection");
    }
}
