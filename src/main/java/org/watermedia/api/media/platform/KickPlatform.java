package org.watermedia.api.media.platform;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import org.watermedia.api.media.MRL;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.URI;
import java.util.Map;

public class KickPlatform implements IPlatform {
    private static final String API_URL = "https://kick.com/api/v2/";
    private static final Gson GSON = new Gson();


    @Override
    public String name() {
        return "Kick";
    }

    @Override
    public boolean validate(URI uri) {
        final String host = uri.getHost();
        return host != null && (host.endsWith(".kick.com") || host.equals("kick.com"));
    }

    @Override
    public MRL[] getSources(URI uri) throws Exception {
        try {
            if (uri.getPath().contains("/videos/")) {
                final String[] split = uri.getPath().split("/");
                final String videoID = split[split.length - 1];
                final KickVideo video = getVideoInfo(videoID);

                return new MRL[] {
                  new MRL(MRL.MediaType.VIDEO, Map.of(video., URI.create(video.url)));
                };
            } else {
                String streamerName = uri.getPath().replace("/", "");
                KickChannel channel = getChannelInfo(streamerName);
                if (channel.livestream == null || !channel.livestream.isStreaming) throw new ConnectException("Streamer is not online");
                return new Result(new URI(channel.url), true, true);
            }
        } catch (Exception e) {
            throw new FixingURLException(uri.toString(), e);
        }

        return new MRL[0];
    }

    private KickChannel getChannelInfo(String channel) throws Exception {
        try (InputStreamReader in = new InputStreamReader(getInputStream(new URI(API_URL + "channels/" + channel)))) {
            return GSON.fromJson(in, KickChannel.class);
        }
    }

    private KickVideo getVideoInfo(String videoId) throws Exception {
        try (InputStreamReader in = new InputStreamReader(getInputStream(new URI(API_URL + "video/" + videoId)))) {
            return GSON.fromJson(in, KickVideo.class);
        }
    }

    private InputStream getInputStream(URI url) throws IOException {
        HttpURLConnection conn = NetTool.connectToHTTP(url, "GET");
        conn.setRequestProperty("User-Agent", WaterMedia.USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new ConnectException(String.format("Server url %s response with status code (%s): %s", url, conn.getResponseCode(), conn.getResponseMessage()));
            }
            return new ByteArrayInputStream(DataTool.readAllBytes(conn.getInputStream()));
        } finally {
            conn.disconnect();
        }
    }

    private static class KickChannel implements Serializable {

        @SerializedName("id")
        @Expose
        public int id;

        @SerializedName("user_id")
        @Expose
        public int userId;

        @SerializedName("slug")
        @Expose
        public String username;

        @SerializedName("playback_url")
        @Expose
        public String url;

        @SerializedName("livestream")
        @Expose
        public isLive livestream;

        public static class isLive implements Serializable {
            @SerializedName("id")
            @Expose
            public int id;

            @SerializedName("is_live")
            @Expose
            public boolean isStreaming;
        }
    }
    private class KickVideo implements Serializable {
        @SerializedName("id")
        @Expose
        public int id;

        @SerializedName("live_stream_id")
        @Expose
        public int streamId;

        @SerializedName("source")
        @Expose
        public String url;
    }
}
