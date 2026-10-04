package com.imagefetch.testutil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 进程内伪 PTP/IP 相机：双 TCP 通道 + 真机同构握手，用于对
 * {@code PtpIpTransport} 的线级契约测试（完全 loopback，无需真实相机）。
 */
public final class FakePtpIpCamera implements Closeable {
    /** 命令通道操作分发；测试按 opcode 决定应答。 */
    public interface OperationHandler {
        void onOperation(Channel channel, int operation, long transaction, long[] parameters)
                throws Exception;
    }

    public static final class Frame {
        public final int type;
        public final byte[] data;

        Frame(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }

        public int u16(int offset) {
            return Bytes.getU16(data, offset);
        }

        public long u32(int offset) {
            return Bytes.getU32(data, offset);
        }

        public int payloadLength() {
            return data.length - 8;
        }
    }

    /** 单向写通道：构造标准 PTP/IP 包（[u32 len][u32 type][payload]）。 */
    public final class Channel {
        private final OutputStream out;
        private final Object lock;

        Channel(OutputStream out, Object lock) {
            this.out = out;
            this.lock = lock;
        }

        public void write(int type, byte[] payload) throws IOException {
            synchronized (lock) {
                out.write(Bytes.u32(payload.length + 8L));
                out.write(Bytes.u32(type));
                out.write(payload);
                out.flush();
            }
        }

        /** OperationResponse：responseCode@payload0、transaction@payload2。 */
        public void respond(long transaction, long responseCode) throws IOException {
            write(7, Bytes.concat(Bytes.u16(responseCode), Bytes.u32(transaction)));
        }

        /** StartData(tx,total) + 分块 Data/EndData + OperationResponse(OK)。 */
        public void sendData(long transaction, byte[] data, int chunkSize) throws IOException {
            write(9, Bytes.concat(Bytes.u32(transaction), Bytes.u64(data.length)));
            if (data.length == 0) {
                write(12, Bytes.u32(transaction));
            } else {
                int offset = 0;
                while (offset < data.length) {
                    int count = Math.min(chunkSize, data.length - offset);
                    byte[] chunk = new byte[count];
                    System.arraycopy(data, offset, chunk, 0, count);
                    offset += count;
                    write(offset >= data.length ? 12 : 10,
                            Bytes.concat(Bytes.u32(transaction), chunk));
                }
            }
            respond(transaction, 0x2001);
        }
    }

    private static final long DEFAULT_CONNECTION_NUMBER = 0x2a2a2a2aL;

    private final ServerSocket server;
    private final String cameraName;
    private final long connectionNumber;
    private final OperationHandler handler;
    private final List<Frame> commandFrames =
            Collections.synchronizedList(new ArrayList<>());
    private final List<Frame> eventFrames =
            Collections.synchronizedList(new ArrayList<>());
    private final Object commandWriteLock = new Object();
    private final Object eventWriteLock = new Object();
    private volatile Socket commandSocket;
    private volatile Socket eventSocket;
    private volatile Channel eventChannel;
    private volatile boolean respondToProbes = true;
    private volatile boolean closed;

    public FakePtpIpCamera(String cameraName, OperationHandler handler) throws IOException {
        this.cameraName = cameraName;
        this.connectionNumber = DEFAULT_CONNECTION_NUMBER;
        this.handler = handler;
        this.server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::serve, "fake-ptpip-camera");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public long connectionNumber() {
        return connectionNumber;
    }

    public String cameraName() {
        return cameraName;
    }

    public List<Frame> commandFrames() {
        synchronized (commandFrames) {
            return new ArrayList<>(commandFrames);
        }
    }

    public List<Frame> eventFrames() {
        synchronized (eventFrames) {
            return new ArrayList<>(eventFrames);
        }
    }

    public List<Frame> awaitCommandFrames(int count, long timeoutMillis)
            throws InterruptedException {
        return awaitFrames(commandFrames, count, timeoutMillis);
    }

    public List<Frame> awaitEventFrames(int count, long timeoutMillis)
            throws InterruptedException {
        return awaitFrames(eventFrames, count, timeoutMillis);
    }

    /** 设为 false 时丢弃 ProbeRequest，用于验证心跳超时。 */
    public void setRespondToProbes(boolean value) {
        this.respondToProbes = value;
    }

    /** 相机主动上报 ObjectAdded(0x4002)：EventCode@8、StorageID@14、ObjectHandle@18。 */
    public void pushObjectAdded(long storageId, long objectHandle) throws IOException {
        Channel channel = eventChannel;
        if (channel == null) throw new IllegalStateException("事件通道尚未建立");
        channel.write(8, Bytes.concat(
                Bytes.u16(0x4002),
                Bytes.u32(0),
                Bytes.u32(storageId),
                Bytes.u32(objectHandle)));
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(server);
        closeQuietly(commandSocket);
        closeQuietly(eventSocket);
    }

    // ── 内部 ──

    private void serve() {
        try {
            Socket command = server.accept();
            commandSocket = command;
            Thread commandThread = new Thread(() -> commandLoop(command), "fake-ptpip-command");
            commandThread.setDaemon(true);
            commandThread.start();

            Socket event = server.accept();
            eventSocket = event;
            Thread eventThread = new Thread(() -> eventLoop(event), "fake-ptpip-event");
            eventThread.setDaemon(true);
            eventThread.start();
        } catch (IOException ignored) {
        }
    }

    private void commandLoop(Socket socket) {
        try (Socket ignored = socket) {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            Channel channel = new Channel(
                    new BufferedOutputStream(socket.getOutputStream()), commandWriteLock);
            while (!closed) {
                Frame frame = readFrame(input);
                if (frame == null) return;
                commandFrames.add(frame);
                if (frame.type == 1) {
                    channel.write(2, initCommandAckPayload());
                } else if (frame.type == 6) {
                    int operation = frame.u16(12);
                    long transaction = frame.u32(14);
                    long[] parameters = parseParameters(frame.data, 18);
                    if (handler != null) {
                        handler.onOperation(channel, operation, transaction, parameters);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void eventLoop(Socket socket) {
        try (Socket ignored = socket) {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            Channel channel = new Channel(
                    new BufferedOutputStream(socket.getOutputStream()), eventWriteLock);
            eventChannel = channel;
            while (!closed) {
                Frame frame = readFrame(input);
                if (frame == null) return;
                eventFrames.add(frame);
                if (frame.type == 3) {
                    // InitEventRequest 走事件通道，必须在此确认 InitEventAck。
                    channel.write(4, new byte[0]);
                } else if (frame.type == 13 && respondToProbes) {
                    channel.write(14, new byte[0]);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private byte[] initCommandAckPayload() {
        byte[] name = Bytes.utf16(cameraName);
        // connectionNumber 在包 offset 8，CameraName 在包 offset 28。
        byte[] payload = new byte[20 + name.length + 2];
        Bytes.putU32(payload, 0, connectionNumber);
        System.arraycopy(name, 0, payload, 20, name.length);
        return payload;
    }

    private static long[] parseParameters(byte[] data, int offset) {
        int count = Math.max(0, (data.length - offset) / 4);
        long[] result = new long[count];
        for (int index = 0; index < count; index++) {
            result[index] = Bytes.getU32(data, offset + index * 4);
        }
        return result;
    }

    private static Frame readFrame(InputStream input) throws IOException {
        byte[] header = new byte[8];
        int offset = 0;
        while (offset < 8) {
            int read = input.read(header, offset, 8 - offset);
            if (read < 0) return null;
            offset += read;
        }
        int length = (int) Bytes.getU32(header, 0);
        byte[] data = new byte[Math.max(8, length)];
        System.arraycopy(header, 0, data, 0, 8);
        int remaining = data.length - 8;
        int filled = 0;
        while (filled < remaining) {
            int read = input.read(data, 8 + filled, remaining - filled);
            if (read < 0) return null;
            filled += read;
        }
        return new Frame((int) Bytes.getU32(header, 4), data);
    }

    private static List<Frame> awaitFrames(List<Frame> frames, int count, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            synchronized (frames) {
                if (frames.size() >= count) return new ArrayList<>(frames);
            }
            Thread.sleep(10);
        }
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
