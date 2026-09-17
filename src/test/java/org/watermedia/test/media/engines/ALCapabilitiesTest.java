package org.watermedia.test.media.engines;

import org.junit.jupiter.api.Test;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.SFXEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ALCapabilitiesTest {
    @Test
    void extensionLimitedContextsAdvertiseOnlyTheirNativeFormats() throws Exception {
        final var table = ALEngine.class.getDeclaredMethod("channelTable", boolean.class, boolean.class, boolean.class, boolean.class);
        table.setAccessible(true);
        for (int mask = 0; mask < 8; mask++) {
            final boolean floating = (mask & 1) != 0;
            final boolean doubles = (mask & 2) != 0;
            final boolean multichannel = (mask & 4) != 0;
            for (final boolean spatial: new boolean[] { false, true }) {
                final var channels = (SFXEngine.ChannelSupport[]) table.invoke(null, floating, doubles, multichannel, spatial);
                assertEquals(spatial ? 1 : multichannel ? 6 : 2, channels.length);
                for (final var entry: channels) {
                    assertTrue(entry.supports(SFXEngine.SampleType.U8));
                    assertTrue(entry.supports(SFXEngine.SampleType.S16));
                    assertFalse(entry.supports(SFXEngine.SampleType.S32));
                    assertEquals(entry.channels() > 2 || floating, entry.supports(SFXEngine.SampleType.FLT));
                    assertEquals(entry.channels() <= 2 && doubles, entry.supports(SFXEngine.SampleType.DBL));
                }
            }
        }
    }
}
