package org.watermedia.api.media.sources;

public interface IPlatform {

    String name();

    boolean validate(Object url);

    void apply(Object url) throws Exception;
}
