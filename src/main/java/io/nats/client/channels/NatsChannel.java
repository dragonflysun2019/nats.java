package io.nats.client.channels;

import java.io.IOException;
import java.nio.channels.ByteChannel;

/**
 * Low-level API for establishing a connection. This allows us to support the
 * "decorator" design pattern to support TLS, Websockets, and HTTP Proxy support.
 */
public interface NatsChannel extends ByteChannel {
    /**
     * When performing the NATS INFO/CONNECT handshake, we may need to
     * upgrade to a secure connection, but if this connection is already
     * secured, it should be a no-op.
     * 
     * @return true if the connection is already secured.
     */
    boolean isSecure();

    /**
     * Shutdown the reader side of the channel.
     * 
     * @throws IOException if an IO error occurs.
     */
    void shutdownInput() throws IOException;
}
