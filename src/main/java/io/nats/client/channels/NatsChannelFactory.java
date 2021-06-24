package io.nats.client.channels;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import java.security.GeneralSecurityException;

import io.nats.client.Options;

public interface NatsChannelFactory {
    /**
     * Create a new NatsChannel for the given serverURI, options, and remaining timeout in nanoseconds.
     * 
     * @param serverURI is the URI of the server to connect to.
     * 
     * @param options are the NATS Options that are in use.
     * 
     * @param timeout is the max time that should elapse when attempting to connect.
     * 
     * @return a new nats channel which is ready for reading and writing, otherwise an exception
     *     should be thrown to indicate why the connection could not be created.
     * 
     * @throws IOException if any IO error occurs.
     */
    public NatsChannel connect(URI serverURI, Options options, Duration timeout) throws IOException;

    /**
     * Determine if this serverURI would require an SSLContext and if so, then build
     * an appropriate context.
     * 
     * @param serverURI is the URI to check if an SSLContext is required.
     * 
     * @throws GeneralSecurityException if a context can not be built.
     */
    public SSLContext createSSLContext(URI serverURI) throws GeneralSecurityException;
}
