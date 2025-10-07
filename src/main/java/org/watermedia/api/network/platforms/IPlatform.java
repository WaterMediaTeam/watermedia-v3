package org.watermedia.api.network.platforms;

public interface IPlatform {

    String name();

    boolean validate(Object url);

    void apply(Object url) throws Exception;
}
