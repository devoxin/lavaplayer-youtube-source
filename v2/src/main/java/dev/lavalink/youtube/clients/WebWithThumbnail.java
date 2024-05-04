package dev.lavalink.youtube.clients;

import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.skeleton.ThumbnailStreamingNonMusicClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WebWithThumbnail extends ThumbnailStreamingNonMusicClient {
    private static final Logger log = LoggerFactory.getLogger(WebWithThumbnail.class);
    protected static Pattern CONFIG_REGEX = Pattern.compile("ytcfg\\.set\\((\\{.+})\\);");

    protected volatile long lastConfigUpdate = -1;

    protected void fetchClientConfig(@NotNull HttpInterface httpInterface) {
        try (CloseableHttpResponse response = httpInterface.execute(new HttpGet("https://www.youtube.com"))) {
            HttpClientTools.assertSuccessWithContent(response, "client config fetch");
            lastConfigUpdate = System.currentTimeMillis();

            String page = EntityUtils.toString(response.getEntity());
            Matcher m = CONFIG_REGEX.matcher(page);

            if (!m.find()) {
                log.warn("Unable to find youtube client config in base page, html: " + page);
                return;
            }

            JsonBrowser json = JsonBrowser.parse(m.group(1));
            JsonBrowser client = json.get("INNERTUBE_CONTEXT").get("client");
            String apiKey = json.get("INNERTUBE_API_KEY").text();

            if (!apiKey.isEmpty()) {
                Web.BASE_CONFIG.withApiKey(apiKey);
            }

            if (!client.isNull()) {
                /*
                 * "client": {
                 *   "hl": "en-GB",
                 *   "gl": "GB",
                 *   "remoteHost": "<ip>",
                 *   "deviceMake": "",
                 *   "deviceModel": "",
                 *   "visitorData": "<base64>",
                 *   "userAgent": "...",
                 *   "clientName": "WEB",
                 *   "clientVersion": "2.20240401.05.00",
                 *   "osVersion": "",
                 *   "originalUrl": "https://www.youtube.com/",
                 *   "platform": "DESKTOP",
                 *   "clientFormFactor": "UNKNOWN_FORM_FACTOR",
                 *   ...
                 */
                String clientVersion = client.get("clientVersion").text();

                if (!clientVersion.isEmpty()) {
                    // overwrite baseConfig version so we're always up-to-date
                    Web.BASE_CONFIG.withClientField("clientVersion", clientVersion);
                }

//                String visitorData = client.get("visitorData").text();
//
//                if (!visitorData.isEmpty()) {
//                    baseConfig.withClientField("visitorData", visitorData);
//                }
            }
        } catch (IOException e) {
            throw ExceptionTools.toRuntimeException(e);
        }
    }

    @Override
    @NotNull
    public ClientConfig getBaseClientConfig(@NotNull HttpInterface httpInterface) {
        if (lastConfigUpdate == -1) {
            synchronized (this) {
                if (lastConfigUpdate == -1) {
                    fetchClientConfig(httpInterface);
                }
            }
        }

        return Web.BASE_CONFIG.copy();
    }

    @Override
    @NotNull
    protected List<AudioTrack> extractSearchResults(@NotNull YoutubeAudioSourceManager source,
                                                    @NotNull JsonBrowser json) {
        return json.get("contents")
            .get("twoColumnSearchResultsRenderer")
            .get("primaryContents")
            .get("sectionListRenderer")
            .get("contents")
            .values() // .index(0)
            .stream()
            .flatMap(item -> item.get("itemSectionRenderer").get("contents").values().stream()) // actual results
            .map(item -> extractAudioTrack(item.get("videoRenderer"), source))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    @Override
    @NotNull
    protected JsonBrowser extractMixPlaylistData(@NotNull JsonBrowser json) {
        return json.get("contents")
            .get("twoColumnWatchNextResults")
            .get("playlist") // this doesn't exist if mix is not found
            .get("playlist");
    }

    @Override
    @Nullable
    protected String extractPlaylistName(@NotNull JsonBrowser json) {
        return json.get("metadata").get("playlistMetadataRenderer").get("title").text();
    }

    @Override
    @NotNull
    protected JsonBrowser extractPlaylistVideoList(@NotNull JsonBrowser json) {
        return json.get("contents")
            .get("twoColumnBrowseResultsRenderer")
            .get("tabs")
            .index(0)
            .get("tabRenderer")
            .get("content")
            .get("sectionListRenderer")
            .get("contents")
            .index(0)
            .get("itemSectionRenderer")
            .get("contents")
            .index(0)
            .get("playlistVideoListRenderer");
    }

    @Override
    @Nullable
    protected String extractPlaylistContinuationToken(@NotNull JsonBrowser videoList) {
        // WEB continuations seem to be slightly inconsistent.
        JsonBrowser contents = videoList.get("contents");

        if (!contents.isNull()) {
            videoList = contents;
        }

        return videoList.values()
            .stream()
            .filter(item -> !item.get("continuationItemRenderer").isNull())
            .findFirst()
            .map(item -> item.get("continuationItemRenderer").get("continuationEndpoint").get("continuationCommand").get("token").text())
            .orElse(null);
    }

    @Override
    @NotNull
    protected JsonBrowser extractPlaylistContinuationVideos(@NotNull JsonBrowser continuationJson) {
        return continuationJson.get("onResponseReceivedActions")
            .index(0)
            .get("appendContinuationItemsAction")
            .get("continuationItems");
    }

    @Override
    @NotNull
    public String getPlayerParams() {
        return WEB_PLAYER_PARAMS;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return Web.BASE_CONFIG.getName();
    }
}
