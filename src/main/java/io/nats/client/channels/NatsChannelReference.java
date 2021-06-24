package io.nats.client.channels;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps a NatsChannel so the reference can be modified at a later point in time.
 */
public class NatsChannelReference implements NatsChannel {
    private AtomicReference<NatsChannel> ref;

    public NatsChannelReference(NatsChannel natsChannel) {
        this.ref = new AtomicReference<>(natsChannel);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return ref.get().read(dst);
    }

    @Override
    public boolean isOpen() {
        return ref.get().isOpen();
    }

    @Override
    public void close() throws IOException {
        ref.get().close();
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        return ref.get().write(src);
    }

    @Override
    public boolean isSecure() {
        return ref.get().isSecure();
    }

    @Override
    public void shutdownInput() throws IOException {
        ref.get().shutdownInput();
    }

    @Override
    public String transformConnectUrl(String connectUrl) {
        return ref.get().transformConnectUrl(connectUrl);
    }

    public void set(NatsChannel natsChannel) {
        this.ref.set(natsChannel);
    }

    public NatsChannel get() {
        return ref.get();
    }
}
