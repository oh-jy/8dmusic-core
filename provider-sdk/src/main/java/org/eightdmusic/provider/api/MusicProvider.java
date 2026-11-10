package org.eightdmusic.provider.api;

/** Implementations run outside the host process and must not perform their own I/O. */
public interface MusicProvider {
    /** Versioned JSON protocol; network access is available only through the supplied broker. */
    String invoke(String requestJson, NetworkBroker network) throws Exception;
}
