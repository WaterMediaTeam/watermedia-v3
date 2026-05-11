package org.watermedia.api.platform;

import java.net.URI;

/**
 * One URL variant of a {@link DataSource}. The platform reports the source
 * {@code width}/{@code height} it knows for the rendition; bucketing those
 * dimensions into a quality level is {@code MRL}'s responsibility and is not
 * exposed to the platform layer.
 * <p>
 * If a platform has no resolution data, pass {@code 0}/{@code 0}. If only one
 * of the dimensions is known (typical for HLS rendition tags that only report
 * height), pass {@code 0} for the missing one.
 */
public record DataQuality(URI uri, int width, int height) {

}
