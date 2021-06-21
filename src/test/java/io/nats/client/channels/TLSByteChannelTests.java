package io.nats.client.channels;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.junit.jupiter.api.Test;

import io.nats.client.NatsTestServer;
import io.nats.client.TestSSLUtils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

public class TLSByteChannelTests {
    @Test
    public void testShortRead() throws Exception {
        // Scenario: Net read TLS frame which is larger than the read buffer.
        try (NatsTestServer ts = new NatsTestServer("src/test/resources/tls.conf", false)) {
            URI uri = new URI(ts.getURI());
            NatsChannel socket = SocketNatsChannel.connect(uri, Duration.ofSeconds(2));
            ByteBuffer info = ByteBuffer.allocate(1024 * 1024);
            socket.read(info);
    
            TLSByteChannel tls = new TLSByteChannel(socket, createSSLEngine(uri));

            write(tls, "CONNECT {}\r\n");

            ByteBuffer oneByte = ByteBuffer.allocate(1);
            assertEquals(1, tls.read(oneByte));
            assertEquals(1, oneByte.position());
            assertEquals((byte)'+', oneByte.get(0)); // got 0?
            oneByte.clear();

            assertEquals(1, tls.read(oneByte));
            assertEquals((byte)'O', oneByte.get(0));
            oneByte.clear();

            assertEquals(1, tls.read(oneByte));
            assertEquals((byte)'K', oneByte.get(0));
            oneByte.clear();

            // Follow up with a larger buffer read,
            // ...to ensure that we don't block on
            // a net read:
            info.clear();
            int result = tls.read(info);
            assertEquals(2, result);
            assertEquals(2, info.position());
            assertEquals((byte)'\r', info.get(0));
            assertEquals((byte)'\n', info.get(1));
            oneByte.clear();

            assertTrue(tls.isOpen());
            assertTrue(socket.isOpen());
            tls.close();
            assertFalse(tls.isOpen());
            assertFalse(socket.isOpen());
        }
    }

    @Test
    public void testImmediateClose() throws Exception {
        // Scenario: Net read TLS frame which is larger than the read buffer.
        try (NatsTestServer ts = new NatsTestServer("src/test/resources/tls.conf", false)) {
            URI uri = new URI(ts.getURI());
            NatsChannel socket = SocketNatsChannel.connect(uri, Duration.ofSeconds(2));
            ByteBuffer info = ByteBuffer.allocate(1024 * 1024);
            socket.read(info);
    
            TLSByteChannel tls = new TLSByteChannel(socket, createSSLEngine(uri));

            assertTrue(tls.isOpen());
            assertTrue(socket.isOpen());
            tls.close();
            assertFalse(tls.isOpen());
            assertFalse(socket.isOpen());
        }
    }

    @Test
    public void testShortNetRead() throws Exception {
        // Scenario: Net read TLS frame which is larger than the read buffer.
        try (NatsTestServer ts = new NatsTestServer("src/test/resources/tls.conf", false)) {
            URI uri = new URI(ts.getURI());
            NatsChannel socket = SocketNatsChannel.connect(uri, Duration.ofSeconds(2));

            AtomicBoolean readOneByteAtATime = new AtomicBoolean(true);
            NatsChannel wrapper = new NatsChannel() {
                ByteBuffer readBuffer = ByteBuffer.allocate(1);

                @Override
                public int read(ByteBuffer dst) throws IOException {
                    if (!readOneByteAtATime.get()) {
                        return socket.read(dst);
                    }
                    readBuffer.clear();
                    int result = socket.read(readBuffer);
                    if (result <= 0) {
                        return result;
                    }
                    readBuffer.flip();
                    dst.put(readBuffer);
                    return result;
                }

                @Override
                public boolean isOpen() {
                    return socket.isOpen();
                }

                @Override
                public void close() throws IOException {
                    socket.close();
                }

                @Override
                public int write(ByteBuffer src) throws IOException {
                    return socket.write(src);
                }

                @Override
                public boolean isSecure() {
                    return false;
                }

                @Override
                public void shutdownInput() throws IOException {
                    socket.shutdownInput();
                }

            };

            ByteBuffer info = ByteBuffer.allocate(1024 * 1024);
            socket.read(info);

            TLSByteChannel tls = new TLSByteChannel(wrapper, createSSLEngine(uri));

            // Peform handshake:
            tls.read(ByteBuffer.allocate(0));

            // Send connect & ping, but turn off one-byte at a time for readint PONG:
            readOneByteAtATime.set(false);
            write(tls, "CONNECT {}\r\nPING\r\n");

            info.clear();
            tls.read(info);
            info.flip();

            assertEquals(
                ByteBuffer.wrap(
                    "+OK\r\nPONG\r\n"
                    .getBytes(UTF_8)),
                info);

            tls.close();
        }
    }

    private static SSLEngine createSSLEngine(URI uri) throws Exception {
        SSLContext ctx = TestSSLUtils.createTestSSLContext();

        SSLEngine engine = ctx.createSSLEngine(uri.getHost(), uri.getPort());
        engine.setUseClientMode(true);

        return engine;
    }
    private static void write(ByteChannel channel, String str) throws IOException {
        channel.write(ByteBuffer.wrap(str.getBytes(UTF_8)));
    }
}