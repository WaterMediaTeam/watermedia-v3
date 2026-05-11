package org.watermedia.api.platform;

import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;

import java.net.URI;
import java.util.List;

/**
 * A single media item carrying every available variant (different
 * resolutions/bitrates) plus the metadata you'd need to display it without
 * downloading. Devs can read {@link DataQuality#width()}/{@link DataQuality#height()}
 * and {@link #metadata()} directly to pick a link.
 */
public record DataSource(MediaType type, URI thumbnail, Metadata metadata, RequestHeaders headers, DataQuality[] variants, List<DataSlave> audioSlaves, List<DataSlave> subSlaves) {

    public DataSource {
        if (variants == null || variants.length == 0) throw new IllegalArgumentException("Entry constructed with no variants");
        if (headers == null) headers = new RequestHeaders();

        audioSlaves = audioSlaves == null ? List.of() : List.copyOf(audioSlaves);
        subSlaves = subSlaves == null ? List.of() : List.copyOf(subSlaves);
    }

}
