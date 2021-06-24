package io.nats.client.channels;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import java.security.GeneralSecurityException;

import io.nats.client.Options;
import io.nats.client.support.SSLUtils;

import static io.nats.client.support.NatsConstants.TLS_PROTOCOL;
import static io.nats.client.support.NatsConstants.OPENTLS_PROTOCOL;

public class DefaultNatsChannelFactory implements NatsChannelFactory {
    public static final NatsChannelFactory INSTANCE = new DefaultNatsChannelFactory();

    @Override
    public NatsChannel connect(
        URI serverURI,
        Options options,
        Duration timeout) throws IOException
    {
        return SocketNatsChannel.connect(serverURI, timeout);
    }

    @Override
    public SSLContext createSSLContext(URI serverURI) throws GeneralSecurityException {
        if (TLS_PROTOCOL.equals(serverURI.getScheme())) {
            return SSLContext.getDefault();
        }
        else if (OPENTLS_PROTOCOL.equals(serverURI.getScheme())) {
            // Previous code would return null if Exception is thrown... but if that
            // happens then the exception will only be deferred until the SSLContext
            // is required, therefore I believe it is better to NOT catch the
            // exception.
            return SSLUtils.createOpenTLSContext();
        }
        return null;
    }
}
