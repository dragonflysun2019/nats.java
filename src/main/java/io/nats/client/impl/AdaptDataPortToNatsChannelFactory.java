package io.nats.client.impl;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;

import io.nats.client.channels.NatsChannel;
import io.nats.client.channels.NatsChannelFactory;
import io.nats.client.support.SSLUtils;
import io.nats.client.Options;

import static io.nats.client.support.NatsConstants.TLS_PROTOCOL;
import static io.nats.client.support.NatsConstants.OPENTLS_PROTOCOL;

/**
 * Adapter for legacy implementations of DataPort.
 * 
 * <p><b>NOTES:</b>
 * <ul>
 *   <li>The NatsConnection passed into connect() is a completely disconnected instance, and thus only the
 *       {@link NatsConnection#getOptions() getOptions()} method may be relied upon.
 *   <li>{@link DataPort#upgradeToSecure() upgradeToSecure()} will never be called, but if there is a
 *       need to upgrade to a secure connection, this adapted DataPort instance will be wrapped automatically,
 *       and thus the TLS upgrade should happen transparently.
 *   <li>{@link DataPort#flush() flush()} will never be called.
 * </ul>
 */
@Deprecated
public class AdaptDataPortToNatsChannelFactory implements NatsChannelFactory {
    private Supplier<DataPort> dataPortSupplier;

    public AdaptDataPortToNatsChannelFactory(Supplier<DataPort> dataPortSupplier) {
        this.dataPortSupplier = dataPortSupplier;
    }

    @Override
    public NatsChannel connect(
        URI serverURI,
        Options options,
        Duration timeout)
        throws IOException
    {
        DataPort dataPort = dataPortSupplier.get();
        dataPort.connect(serverURI.toString(), new NatsConnection(options), timeout.toNanos());
        return new Adapter(dataPort);
    }

    private static class Adapter implements NatsChannel {
        private DataPort dataPort;
        private boolean isOpen = true;

        private Adapter(DataPort dataPort) {
            this.dataPort = dataPort;
        }

        @Override
        public int read(ByteBuffer original) throws IOException {
            ByteBuffer dst = original;
            if (!original.hasArray()) {
                dst = ByteBuffer.allocate(original.remaining());
            }
            int offset = dst.arrayOffset();
            int length = dataPort.read(dst.array(), offset + dst.position(), dst.remaining());
            if (length > 0) {
                dst.position(dst.position() + length);
            }
            if (original != dst) {
                dst.flip();
                original.put(dst);
            }
            return length;
        }

        @Override
        public boolean isOpen() {
            return isOpen;
        }

        @Override
        public void close() throws IOException {
            dataPort.close();
        }

        @Override
        public int write(ByteBuffer original) throws IOException {
            ByteBuffer src = original;
            if (!original.hasArray() ||
                0 != original.arrayOffset() ||
                0 != original.position())
            {
                src = ByteBuffer.allocate(original.remaining());
                src.put(original.slice());
                src.flip();
            }
            int length = src.remaining();
            dataPort.write(src.array(), length);
            if (length > 0) {
                original.position(original.position() + length);
            }

            return length;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public void shutdownInput() throws IOException {
            dataPort.shutdownInput();
        }

        @Override
        public String transformConnectUrl(String connectUrl) {
            return connectUrl;
        }
    }

    @Override
    public SSLContext createSSLContext(URI serverURI) throws GeneralSecurityException {
        // Original data port implementation only supported these uris:
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
