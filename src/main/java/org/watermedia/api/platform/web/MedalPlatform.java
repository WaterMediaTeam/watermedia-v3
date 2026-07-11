package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.NetRequest;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.JsonTool;
import org.watermedia.tools.MPEGTool;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Resolves Medal.tv game clips. A clip page such as
 * {@code https://medal.tv/es/games/gta-v/clips/<id>} is resolved through Medal's public
 * content endpoint {@code https://medal.tv/api/content/<id>}, which needs no authentication
 * for public clips and returns every rendition plus metadata. The locale prefix ({@code /es/},
 * {@code /en/}, …) is irrelevant — only the clip id feeds the API.
 * <p>
 * Playback prefers the adaptive HLS ladder ({@code contentUrlHls}) expanded into per-rendition
 * quality variants, exactly like {@link KickPlatform}. The progressive source MP4
 * ({@code contentUrl}) and the pre-rendered social-share MP4 ({@code socialMediaVideo}) are
 * ordered fallbacks for clips Medal never packaged as HLS. The {@code contentUrl<height>p} rungs
 * are deliberately ignored: when Medal has not transcoded a real ladder it hands back the source
 * file for every one of them (identical bytes, differing only by a {@code &t=<height>p&…&missing}
 * marker), so they are duplicates rather than genuine qualities — the HLS master is Medal's only
 * trustworthy quality ladder.
 */
public class MedalPlatform implements IPlatform {
    public static final String NAME = "Medal";
    private static final Marker IT = MarkerManager.getMarker(MedalPlatform.class.getSimpleName());
    private static final String API_URL = "https://medal.tv/api/content/%s";
    // MEDAL SWAPS THE REAL CLIP FOR THIS PLACEHOLDER FILE WHEN A GUEST IS NOT ALLOWED TO WATCH IT
    private static final String PRIVACY_PROTECTED = "video/privacy-protected-guest";
    private static final String[] HOSTS = { "medal.tv", "www.medal.tv" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        // MEDAL HAS MANY PAGE TYPES (PROFILES, GAME HUBS, THE HOME FEED); ONLY A CLIP URL CARRIES AN ID WE CAN RESOLVE
        final String clipId = clipId(uri);
        if (clipId == null) return null;

        LOGGER.debug(IT, "Medal resolving clip '{}' from {}", clipId, uri);
        // A REMOVED, PRIVATE OR MALFORMED ID ANSWERS 4xx (BAD IDS COME BACK AS 400, NOT 404)
        final Content content = NetRequest.fetchJson(MedalPlatform.class, String.format(API_URL, clipId), Content.class);

        // GATE ON AVAILABILITY BEFORE TOUCHING URLS — A TAKEN-DOWN OR LOGIN-WALLED CLIP ONLY EXPOSES PLACEHOLDERS
        if (content.dmcaTakedown)
            throw new PlatformException(MedalPlatform.class, "Clip '" + clipId + "' was removed by a DMCA takedown");
        if (content.requireLogin)
            throw new PlatformException(MedalPlatform.class, "Clip '" + clipId + "' requires login (private or restricted)");

        // PREFERRED: THE HLS MASTER IS MEDAL'S ONLY REAL LADDER (source/540p/360p/240p). MPEGTool NEVER THROWS —
        // ON A FETCH/PARSE HICCUP IT FALLS BACK TO THE MASTER URL ITSELF SO FFmpeg CAN STILL PROBE IT.
        final List<DataQuality> variants = new ArrayList<>();
        final URI hls = JsonTool.uri(content.contentUrlHls);
        if (hls != null) {
            for (final MPEGTool.Variant v: MPEGTool.qualities(hls)) {
                if (!privacyProtected(v.uri())) variants.add(new DataQuality(v.uri(), v.width(), v.height()));
            }
        }
        // FALLBACK: A PROGRESSIVE SOURCE MP4 FOR CLIPS MEDAL NEVER PACKAGED AS HLS (CARRIES REAL SOURCE DIMENSIONS)
        if (variants.isEmpty()) {
            final URI progressive = JsonTool.uri(content.contentUrl);
            if (progressive != null && !privacyProtected(progressive))
                variants.add(new DataQuality(progressive, content.sourceWidth, content.sourceHeight));
        }
        // LAST RESORT: THE PRE-RENDERED SOCIAL-SHARE MP4 (NO RELIABLE DIMENSIONS — LET FFMediaPlayer PROBE)
        if (variants.isEmpty()) {
            final URI social = JsonTool.uri(content.socialMediaVideo);
            if (social != null && !privacyProtected(social))
                variants.add(new DataQuality(social, 0, 0));
        }
        if (variants.isEmpty())
            throw new PlatformException(MedalPlatform.class, "Clip '" + clipId + "' exposes no playable source (login-walled, region-locked, or still processing)");

        final String title = content.contentTitle != null && !content.contentTitle.isBlank() ? content.contentTitle : null;
        final String desc = content.contentDescription != null && !content.contentDescription.isBlank() ? content.contentDescription : null;
        final Instant postedAt = content.created > 0 ? Instant.ofEpochMilli(content.created) : null;
        final long durationMs = (long) (content.videoLengthSeconds * 1000L);
        final String author = content.poster != null
                ? (content.poster.displayName != null ? content.poster.displayName : content.poster.userName)
                : null;
        if (author == null) LOGGER.warn(IT, "Medal clip '{}' has no resolvable author", clipId);

        final Metadata metadata = new Metadata(title, desc, postedAt, durationMs, author);
        final URI thumbnail = JsonTool.uri(content.thumbnailUrl);

        // MEDAL SIGNS EVERY CDN URL WITH ONE SHARED EXPIRY; HONOR IT SO MRL RE-RESOLVES BEFORE THE LINKS DIE
        final Instant expires = content.urlsExpireAt > 0 ? Instant.ofEpochMilli(content.urlsExpireAt) : null;

        LOGGER.info(IT, "Medal resolved clip '{}' with {} variant(s)", clipId, variants.size());
        final var entry = new DataSource(MediaType.VIDEO, thumbnail, metadata,
                RequestHeaders.defaults(uri),
                variants.toArray(DataQuality[]::new),
                null, null);
        return new PlatformData(expires, entry);
    }

    // RESOLVES THE CLIP ID FROM EVERY KNOWN MEDAL SHAPE: /games/<slug>/clips/<id>, /<locale>/games/<slug>/clips/<id>,
    // THE SHORT /clip|/clips/<id> REDIRECTS, AND THE /?contentId=<id> QUERY. RETURNS NULL FOR NON-CLIP MEDAL URLS.
    private static String clipId(final URI uri) {
        final String path = uri.getPath();
        if (path != null && path.length() > 1) {
            final String[] seg = path.substring(1).split("/");
            for (int i = 0; i < seg.length - 1; i++) {
                if ((seg[i].equalsIgnoreCase("clips") || seg[i].equalsIgnoreCase("clip")) && !seg[i + 1].isEmpty())
                    return seg[i + 1];
            }
        }
        final String query = uri.getQuery();
        if (query != null) {
            for (final String part: query.split("&")) {
                if (part.startsWith("contentId=") && part.length() > "contentId=".length())
                    return part.substring("contentId=".length());
            }
        }
        return null;
    }

    private static boolean privacyProtected(final URI uri) {
        return uri.toString().contains(PRIVACY_PROTECTED);
    }

    // ONLY THE FIELDS ACTUALLY CONSUMED ARE BOUND — GSON IGNORES THE REST OF MEDAL'S LARGE PAYLOAD
    private record Content(String contentTitle, String contentDescription, String contentUrl, String contentUrlHls,
                           String socialMediaVideo, String thumbnailUrl, int sourceWidth, int sourceHeight,
                           double videoLengthSeconds, long created, long urlsExpireAt, boolean requireLogin,
                           boolean dmcaTakedown, Poster poster) {
    }

    private record Poster(String displayName, String userName) {
    }
}
