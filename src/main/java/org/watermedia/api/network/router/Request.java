package org.watermedia.api.network.router;

import java.util.Map;

public record Request(String method, String path, Map<String, String> headers, String body) {
}
