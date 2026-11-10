package org.eightdmusic.provider.api;

/** Host-controlled HTTP access. Credentials and redirects are controlled by the host. */
public interface NetworkBroker {
    /** Request and bounded response are versioned JSON, never arbitrary host objects. */
    String request(String requestJson) throws Exception;
}
