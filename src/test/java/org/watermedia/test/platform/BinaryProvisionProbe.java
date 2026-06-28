package org.watermedia.test.platform;

import org.watermedia.binaries.BotGuardBinary;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.binaries.YtDlpBinary;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MANUAL harness (not a JUnit test): provisions the latest yt-dlp and rustypipe-botguard binaries from
 * their releases APIs into a fresh temp dir and prints where they landed (yt-dlp also gets a
 * {@code --version} run). Exercises the dynamic latest-release download path end-to-end — releases API
 * lookup, asset selection, download/verify, extraction, atomic install — without booting the full media
 * stack. Run with {@code gradle binariesProbe}.
 */
public final class BinaryProvisionProbe {
    public static void main(final String[] args) throws Exception {
        final Path tmp = Files.createTempDirectory("wm-binprobe");
        System.out.println("base dir: " + tmp);
        WaterMediaBinaries.start("PROBE", tmp, null, true); // REGISTERS THE yt-dlp/botguard CACHE DIRS

        final Path ytdlp = new YtDlpBinary().executable();
        System.out.println("yt-dlp:   " + ytdlp + " (" + Files.size(ytdlp) + " bytes)");
        run(ytdlp.toString(), "--version");

        // A SECOND INSTANCE (LIKE YtDlpPlatform + YouTubePlatform EACH HOLDING ONE) MUST HIT THE CACHE
        // INSTEAD OF RE-DOWNLOADING — EXPECT AN "is up to date" LOG ABOVE THIS LINE
        final Path ytdlpAgain = new YtDlpBinary().executable();
        System.out.println("yt-dlp #2 (cache hit expected): " + ytdlpAgain.equals(ytdlp));

        final Path botguard = new BotGuardBinary().executable();
        System.out.println("botguard: " + botguard + " (" + Files.size(botguard) + " bytes)");

        System.exit(0);
    }

    private static void run(final String... command) throws Exception {
        final Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String out = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor();
        System.out.println("  $ " + String.join(" ", command) + " -> " + out);
    }
}
