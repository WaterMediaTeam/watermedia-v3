package org.watermedia.api.network;

import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;

public class NetworkAPI extends WaterMediaAPI {
    @Override
    public boolean start(WaterMedia instance) throws Exception {
        return false;
    }

    @Override
    public boolean onlyClient() {
        return false;
    }

    @Override
    public void test() {

    }

    @Override
    public Priority priority() {
        return null;
    }

    @Override
    public void release(WaterMedia instance) {

    }
}
