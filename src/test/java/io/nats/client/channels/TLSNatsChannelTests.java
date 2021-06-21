package io.nats.client.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;

import io.nats.client.NatsTestServer;
import io.nats.client.Options;
import io.nats.client.TestSSLUtils;

public class TLSNatsChannelTests {
    static class CommunicationIssueException extends RuntimeException {
        CommunicationIssueException(Exception ex) {
            super(ex);
        }
    }

    static class WrappedException extends RuntimeException {
    }

    static class BaseNatsChannel implements NatsChannel {
        @Override
        public int read(ByteBuffer dst) throws IOException {
            return -1;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return -1;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public void shutdownInput() throws IOException {
        }

    }

    @Test
    public void testTimeoutDuringConnect() {
        NatsChannel wrapped = new BaseNatsChannel() {
            @Override
            public int read(ByteBuffer dst) throws IOException {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ex) {
                    throw new IOException(ex);
                }
                return 0;
            }

            @Override
            public int write(ByteBuffer src) throws IOException {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ex) {
                    throw new IOException(ex);
                }
                return 0;
            }
        };
        CommunicationIssueException got = assertThrows(
            CommunicationIssueException.class,
            () ->
            TLSNatsChannel.wrap(
                wrapped,
                new URI("nats://example.com"),
                new Options.Builder().secure().build(),
                ex -> { throw new CommunicationIssueException(ex); },
                Duration.ofSeconds(1)));
        assertEquals(got.getCause().getClass(), TimeoutException.class);
    }

    @Test
    public void testExceptionDuringConnect() {
        NatsChannel wrapped = new BaseNatsChannel() {
            @Override
            public int read(ByteBuffer dst) throws IOException {
                throw new WrappedException();
            }

            @Override
            public int write(ByteBuffer src) throws IOException {
                throw new WrappedException();
            }
        };
        CommunicationIssueException got = assertThrows(
            CommunicationIssueException.class,
            () ->
            TLSNatsChannel.wrap(
                wrapped,
                new URI("nats://example.com"),
                new Options.Builder().secure().build(),
                ex -> { throw new CommunicationIssueException(ex); },
                Duration.ofSeconds(10)));
        assertEquals(got.getCause().getClass(), WrappedException.class);
    }

    @Test
    public void miscTests() throws Exception {
        try (NatsTestServer ts = new NatsTestServer("src/test/resources/tls.conf", false)) {
            NatsChannel socket = SocketNatsChannel.connect(new URI(ts.getURI()), Duration.ofSeconds(2));
            NatsChannel wrapped = new BaseNatsChannel() {
                @Override
                public boolean isOpen() {
                    throw new WrappedException();
                }

                @Override
                public int read(ByteBuffer dst) throws IOException {
                    return socket.read(dst);
                }
        
                @Override
                public int write(ByteBuffer src) throws IOException {
                    return socket.write(src);
                }
            };
            ByteBuffer info = ByteBuffer.allocate(1024 * 1024);
            socket.read(info);

            SSLContext ctx = TestSSLUtils.createTestSSLContext();
            Options options = new Options.Builder()
                    .server(ts.getURI())
                    .maxReconnects(0)
                    .sslContext(ctx)
                    .build();
            NatsChannel channel = TLSNatsChannel.wrap(
                wrapped,
                new URI("nats://example.com"),
                options,
                ex -> { throw new CommunicationIssueException(ex); },
                Duration.ofSeconds(10));

            // Should NOT call wrapped, since TLS state keeps track of open status.
            assertTrue(channel.isOpen());
            assertTrue(channel.isSecure());
            channel.close();
        }
    }
}