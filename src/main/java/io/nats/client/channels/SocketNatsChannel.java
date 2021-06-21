package io.nats.client.channels;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.Duration;

import static java.net.StandardSocketOptions.TCP_NODELAY;
import static java.net.StandardSocketOptions.SO_RCVBUF;
import static java.net.StandardSocketOptions.SO_SNDBUF;

public class SocketNatsChannel implements NatsChannel {
    private SocketChannel socket;

    public static NatsChannel connect(
        URI uri,
        Duration timeoutDuration)
        throws IOException
    {
        // Code copied from SocketDataPort.connect():
        try {
            String host = uri.getHost();
            int port = uri.getPort();

            SocketChannel socket = SocketChannel.open();
            socket.setOption(TCP_NODELAY, true);
            socket.setOption(SO_RCVBUF, 2 * 1024 * 1024);
            socket.setOption(SO_SNDBUF, 2 * 1024 * 1024);

            connectWithTimeout(socket, new InetSocketAddress(host, port), timeoutDuration);

            return new SocketNatsChannel(socket);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

    private static void connectWithTimeout(SocketChannel socket, SocketAddress address, Duration timeout) throws IOException {
        Selector selector = Selector.open();
        try {
            socket.configureBlocking(false);
            socket.register(selector, SelectionKey.OP_CONNECT);
            if (socket.connect(address)) {
                return;
            }
            if (0 == selector.select(timeout.toMillis())) {
                socket.close();
                throw new SocketTimeoutException("Unable to connect within " + timeout);
            }
            if (!socket.finishConnect()) {
                socket.close();
                throw new SocketTimeoutException("Unexpectedly returned false from SocketChannel.finishConnect() after selecting");
            }
        } finally {
            selector.close();
            socket.configureBlocking(true);
        }
    }

    private SocketNatsChannel(SocketChannel socket) throws IOException {
        this.socket = socket;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return socket.read(dst);
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
}
