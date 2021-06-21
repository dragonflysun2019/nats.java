package io.nats.client.channels;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import io.nats.client.Options;

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
}
