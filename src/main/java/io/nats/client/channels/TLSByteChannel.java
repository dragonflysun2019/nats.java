package io.nats.client.channels;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;

/**
 * It blows my mind that JDK doesn't provide this functionality by default.
 * 
 * This is an implementation of ByteChannel which uses an SSLEngine to encrypt data sent
 * to a ByteChannel that is being wrapped, and then decrypts received data.
 */
public class TLSByteChannel implements ByteChannel {
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    // NOTE: Care should be taken to ensure readLock is acquired BEFORE writeLock
    // in the case where operations perform on both.
    private final Lock readLock = new ReentrantLock();
    private final Lock writeLock = new ReentrantLock();

    private final ByteChannel wrap;
    private final SSLEngine engine;

    private final ByteBuffer outNetBuffer; // ready for write

    private final ByteBuffer inNetBuffer; // ready for read
    private final ByteBuffer inAppBuffer; // ready for read or write depending on inAppBufferHasData

    private State state = State.HANDSHAKING;
    private Thread readThread = null;
    private boolean inAppBufferHasData = false;

    private enum State {
        HANDSHAKING,
        OPEN,
        CLOSING,
        CLOSED;
    }

    public TLSByteChannel(ByteChannel wrap, SSLEngine engine) throws IOException {
        this.wrap = wrap;
        this.engine = engine;

        int netBufferSize = engine.getSession().getPacketBufferSize();
        int appBufferSize = engine.getSession().getApplicationBufferSize();

        outNetBuffer = ByteBuffer.allocate(netBufferSize);
        outNetBuffer.flip();

        inNetBuffer = ByteBuffer.allocate(netBufferSize);
        inAppBuffer = ByteBuffer.allocate(appBufferSize);

        engine.beginHandshake();
    }

    /**
     * Gracefully close the TLS session and the underlying wrap'ed socket.
     */
    @Override
    public void close() throws IOException {
        readLock.lock();
        if (null != readThread) {
            readThread.interrupt();
        }
        try {
            writeLock.lock();
            try {
                closeImpl();
            } finally {
                writeLock.unlock();
            }
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Read plaintext by decrypting the underlying wrap'ed sockets encrypted bytes.
     * 
     * @param dst is the buffer to populate between position and limit.
     * @return the number of bytes populated or -1 to indicate end of stream,
     *     and the dst position will also be incremented appropriately.
     */
    @Override
    public int read(ByteBuffer dst) throws IOException {
        maybeHandshake();
        readLock.lock();
        try {
            if (State.HANDSHAKING == state) {
                writeLock.lock();
                try {
                    handshakeImpl(true);
                } finally {
                    writeLock.unlock();
                }
            }
            if (!dst.hasRemaining()) {
                return 0;
            }
            return readImpl(dst, true);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Write plaintext by encrypting and writing this to the underlying wrap'ed socket.
     * 
     * @param src is the buffer content to write between the position and limit.
     * @return the number of bytes that got written or -1 to indicate end of
     *     stream and the src position will also be incremented appropriately.
     */
    @Override
    public int write(ByteBuffer src) throws IOException {
        while (true) {
            maybeHandshake();
            writeLock.lock();
            try {
                if (State.HANDSHAKING == state) {
                    continue;
                }
                if (!src.hasRemaining()) {
                    return 0;
                }
                return writeImpl(src, true);
            } finally {
                writeLock.unlock();
            }
        }
    }

    /**
     * @return true if this channel has transitioned into the closed or closing state.
     */
    @Override
    public boolean isOpen() {
        switch (state) {
        case HANDSHAKING:
        case OPEN:
            return true;
        case CLOSED:
        case CLOSING:
            return false;
        }
        throw new IllegalStateException("Unexpected state: " + state);
    }

    /**
     * Checks the state to see if we need to handshake. If so,
     * aquire the read & write locks and perform handshake.
     * 
     * Postcondition: state is NOT HANDSHAKING.
     */
    private void maybeHandshake() throws IOException {
        if (State.HANDSHAKING != state) {
            return;
        }
        readLock.lock();
        try {
            writeLock.lock();
            try {
                handshakeImpl(true);
            } finally {
                writeLock.unlock();
            }
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Perform the handshake loop, if state change is true,
     * the post condition will transition the state to OPEN.
     * 
     * @param stateChange should be true if state should be updated,
     *     otherwise false (like in the case when called from close())
     * 
     * Precondition: read & write locks are acquired
     */
    private void handshakeImpl(boolean stateChange) throws IOException {
        while (true) {
            switch (engine.getHandshakeStatus()) {
            case NEED_TASK:
                executeTasks();
                break;

            case NEED_UNWRAP:
                if ( readImpl(inAppBuffer, false) < 0 ) {
                    return;
                }
                break;

            case NEED_WRAP:
                writeImpl(EMPTY, false);
                break;

            case FINISHED:
            case NOT_HANDSHAKING:
                if (stateChange) {
                    state = State.OPEN;
                }
                return;
            default:
                throw new IllegalStateException("Unexpected SSLEngine.HandshakeStatus=" + engine.getHandshakeStatus());
            }
        }
    }

    /**
     * While there are delegatedTasks to run, run them.
     */
    private void executeTasks() {
        while (true) {
            Runnable runnable = engine.getDelegatedTask();
            if (null == runnable) {
                break;
            }
            runnable.run();
        }
    }

    /**
     * Implement the close procedure by finishing any pending handshake
     * and then transitioning to close state.
     * 
     * Precondition: read & write locks are acquired
     * 
     * Postcondition: state is CLOSED
     */
    private void closeImpl() throws IOException {
        switch (state) {
        case HANDSHAKING:
            // Finish the handshake, then close:
            state = State.CLOSING;
            handshakeImpl(false);
            // intentionally, no break!
        case OPEN:
            transitionToClosed();
        default:
        }
    }

    /**
     * Implement the transition from CLOSING to CLOSED state.
     * 
     * Precondition: read & write locks are acquired
     * 
     * Postcondition: state is CLOSED
     */
    private void transitionToClosed() throws IOException {
        state = State.CLOSING;
        try {
            // NOTE: unread data may be lost. However, we assume this is desired
            // since we are transitioning to closing:
            inAppBuffer.clear();
            inAppBufferHasData = false;
            flushNetWrite();
            engine.closeOutbound();
            try {
                while (!engine.isOutboundDone()) {
                    writeImpl(EMPTY, false);
                }
                while (!engine.isInboundDone()) {
                    readImpl(EMPTY, false);
                }
                engine.closeInbound();
            } catch (ClosedChannelException ex) {
                // already closed, ignore.
            }
        } finally {
            try {
                // No matter what happens, we need to close the
                // wrapped channel:
                wrap.close();
            } finally {
                // ...and no matter what happens, we need to
                // indicate that we are in a CLOSED state:
                state = State.CLOSED;
            }
        }
    }

    /**
     * Implement a read into dst buffer, potientially transitioning to
     * HANDSHAKING or CLOSED state only if stateChange is true.
     * 
     * @param dst is the buffer to write into, if it is empty, an attempt to read
     *     the wrap'ed channel will still be made and this network data will still
     *     be propegated to the SSLEngine. This is so this method can be used in
     *     the handshake process.
     * 
     * @param stateChange should be set to true if transitioning into HANDSHAKING
     *     or CLOSED state is okay.
     * 
     * @return the number of bytes written into the dst buffer.
     * 
     * Precondition: read lock is acquired
     */
    private int readImpl(ByteBuffer dst, boolean stateChange) throws IOException {
        // Satisfy read via inAppBuffer?
        int count = readFromInAppBuffer(dst);
        if (count > 0) {
            return count;
        }

        // Go to the network, but only if inNetBuffer is empty!
        if (inNetBuffer.position() == 0) {
            count = readFromNetwork(dst, stateChange);
            if (count != 0) {
                return count;
            }
        }

        return decryptInNetBuffer(dst, stateChange);
    }

    /**
     * Attempts to use the internal inAppBuffer to populate the read request.
     * @param dst is the destination to populate.
     * @return the number of bytes populated, never negative.
     */
    private int readFromInAppBuffer(ByteBuffer dst) {
        if (inAppBufferHasData && dst != inAppBuffer) {
            int count = safePut(inAppBuffer, dst);
            inAppBufferHasData = inAppBuffer.hasRemaining();
            return count;
        }
        return 0;
    }

    /**
     * Peforms the call to SSLEngine.unwrap() by trying to decrypt bytes from inNetBuffer
     * to dst.
     * 
     * @param dst the destination to place decrypted bytes into.
     * @param stateChange if the internal `state` variable is allowed to change.
     * @return the number of decrypted bytes or -1 to indicate end of stream.
     */
    private int decryptInNetBuffer(ByteBuffer dst, boolean stateChange) throws IOException {
        while (true) {
            inNetBuffer.flip();
            SSLEngineResult result = engine.unwrap(inNetBuffer, dst);
            inNetBuffer.compact();

            // Possibly update handshake status:
            if (stateChange && isHandshaking(result.getHandshakeStatus())) {
                state = State.HANDSHAKING;
            }

            switch (result.getStatus()) {
            case BUFFER_OVERFLOW:
                assert dst != inAppBuffer;
                assert !inAppBufferHasData;

                // Not enough space in dst, so buffer it into inAppBuffer:
                inAppBuffer.clear();
                decryptInNetBuffer(inAppBuffer, stateChange);
                inAppBuffer.flip();
                inAppBufferHasData = true;

                return readFromInAppBuffer(dst);

            case BUFFER_UNDERFLOW:
                // Need more data from network:
                int count = readFromNetwork(dst, stateChange);
                if (0 != count) {
                    return count;
                }
                break; // retry unwrap with more inNetBuffer

            case CLOSED:
                if (stateChange) {
                    try {
                        wrap.close();
                    } finally {
                        state = State.CLOSED;
                    }
                }
                return -1;

            case OK:
                return result.bytesProduced();

            default:
                throw new IllegalStateException("Unexpected status=" + result.getStatus());
            }
        }
    }

    /**
     * Attempts to populate inNetBuffer by reading from the network, but also handles
     * the case when the read lock is released and the inAppBuffer is populated.
     * 
     * @param dst is the destination to place decrypted bytes, NOT the network read results.
     * @param interruptable if true, then the read lock is released.
     * @return the number of decrypted bytes or -1 to indicate end of stream. 
     */
    private int readFromNetwork(ByteBuffer dst, boolean interruptable) throws IOException {
        int count = tryNetRead(interruptable);
        if (count < 1) {
            return count;
        }
        // tryNetRead may release the read lock, so try to satisfy read from
        // inAppBuffer:
        return readFromInAppBuffer(dst);
    }

    /**
     * Try to read bytes into the inNetBuffer.
     * 
     * @param interruptable should be set to true if the read lock should
     *     be temporarily released during the wrap'ed call to read().
     * 
     * Precondition: read lock is acquired.
     */
    private int tryNetRead(boolean interruptable) throws IOException {
        if (!inNetBuffer.hasRemaining()) {
            // No capacity to read any more.
            return 0;
        }
        int result;
        if (interruptable) {
            readThread = Thread.currentThread();
            readLock.unlock();
            try {
                result = wrap.read(inNetBuffer);
            } finally {
                readLock.lock();
                readThread = null;
            }
        } else {
            result = wrap.read(inNetBuffer);
        }
        return result;
    }

    /**
     * Implement a write operation.
     * 
     * @param src is the source buffer to write
     * @param stateChange should be set to true if the internal state is
     *     allowed to be altered during this call.
     * @return the number of bytes written or -1 if end of stream.
     * 
     * Precondition: write lock is acquired.
     */
    private int writeImpl(ByteBuffer src, boolean stateChange) throws IOException {
        if (!flushNetWrite()) {
            // Still waiting for the wrapped byte channel to consume outNetBuffer.
            return 0;
        }
        int count = 0;

        do {
            int position = src.position();
            SSLEngineResult result;
            try {
                outNetBuffer.compact();
                result = engine.wrap(src, outNetBuffer);
            } finally {
                outNetBuffer.flip();
            }
            count += src.position() - position;

            if (stateChange && isHandshaking(result.getHandshakeStatus())) {
                state = State.HANDSHAKING;
            }

            switch (result.getStatus()) {
            case BUFFER_OVERFLOW:
                if (!flushNetWrite()) {
                    return count;
                }
                break;
            case BUFFER_UNDERFLOW:
                throw new IllegalStateException("SSLEngine.wrap() should never return BUFFER_UNDERFLOW");
            case CLOSED:
                flushNetWrite();
                if (stateChange) {
                    try {
                        wrap.close();
                    } finally {
                        state = State.CLOSED;
                    }
                }
                return count;
            case OK:
                flushNetWrite();
                return count;

            default:
                throw new IllegalStateException("Unexpected status=" + result.getStatus());
            }
        } while (src.hasRemaining());
        return count;
    }

    /**
     * Write the outNetBuffer to the wrapped ByteChannel.
     * 
     * @return false if no capacity remains in the outNetBuffer
     * 
     * Precondition: write lock is acquired.
     */
    private boolean flushNetWrite() throws IOException {
        if (!outNetBuffer.hasRemaining()) {
            return true; // nothing to flush
        }
        wrap.write(outNetBuffer);
        if (outNetBuffer.remaining() == outNetBuffer.capacity()) {
            // Must not have written anything, and the net buffer is full:
            return false;
        }
        return true;
    }

    /**
     * @param status is the handshake status to test.
     * @return true if HandshakeStatus indicates that a handshake is still in progress.
     */
    private static boolean isHandshaking(HandshakeStatus status) {
        switch (status) {
        case NOT_HANDSHAKING:
        case FINISHED:
            return false;
        default:
            return true;
        }
    }

    /**
     * Acts like {@link ByteBuffer.put(ByteBuffer)} but puts as many bytes
     * as possible into dst and returns false if a BufferOverflowException
     * would occur.
     * 
     * @param src is the source buffer to read from.
     * @param dst is the destination buffer to put into the source.
     * @return number of bytes written to dst.
     */
    private static int safePut(ByteBuffer src, ByteBuffer dst) {
        int srcRemaining = src.remaining();
        int dstRemaining = dst.remaining();
        if (srcRemaining <= dstRemaining) {
            dst.put(src);
            return srcRemaining;
        }
        // temporarily adjust the src limit to match dst remaining:
        int srcLimit = src.limit();
        src.limit(src.position() + dstRemaining);
        dst.put(src);
        src.limit(srcLimit);
        return dstRemaining;
    }
}
