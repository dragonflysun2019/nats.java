package io.nats.client.channels;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.net.ssl.SSLEngine;

import io.nats.client.Options;

public class TLSNatsChannel implements NatsChannel {
    private static final Exception DUMMY_EXCEPTION = new Exception();
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private NatsChannel wrap;
    private TLSByteChannel byteChannel;

    public static NatsChannel wrap(
        NatsChannel natsChannel,
        URI uri,
        Options options,
        Consumer<Exception> handleCommunicationIssue,
        Duration timeout)
        throws IOException
    {
        try {
            return new TLSNatsChannel(natsChannel, uri, options, handleCommunicationIssue, timeout);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        }
    }

    private TLSNatsChannel(
        NatsChannel wrap,
        URI uri,
        Options options,
        Consumer<Exception> handleCommunicationIssue,
        Duration timeout)
        throws IOException, InterruptedException
    {
        this.wrap = wrap;
        SSLEngine engine = options.getSslContext().createSSLEngine(uri.getHost(), uri.getPort());
        engine.setUseClientMode(true);
        this.byteChannel = new TLSByteChannel(wrap, engine);

        AtomicReference<Exception> handshakeException = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread handshakeThread = new Thread(() -> {
            try {
                this.byteChannel.write(EMPTY);
            } catch (Exception ex) {
                if (null != handshakeException.getAndSet(ex)) {
                    ex.printStackTrace();
                }
            } finally {
                done.countDown();
            }
        });
        handshakeThread.start();
        if (!done.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            handshakeThread.interrupt();
            if (!done.await(10, TimeUnit.MILLISECONDS)) {
                // Force printStackTrace() to be called, since we can
                // no longer capture the exception due to timeout.
                handshakeException.set(DUMMY_EXCEPTION);
            }
            handleCommunicationIssue.accept(
                new TimeoutException("Timeout waiting for TLS handshake"));
        }
        Exception ex = handshakeException.get();
        if (null != ex && ex != DUMMY_EXCEPTION) {
            handleCommunicationIssue.accept(ex);
        }
    }

    @Override
    public boolean isSecure() {
        return true;
    }

    @Override
    public void shutdownInput() throws IOException {
        // cannot call shutdownInput on sslSocket
    }

    @Override
    public void close() throws IOException {
        byteChannel.close(); // auto closes the underlying socket
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return byteChannel.read(dst);
    }

    @Override
    public boolean isOpen() {
        return byteChannel.isOpen();
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        return byteChannel.write(src);
    }
}
