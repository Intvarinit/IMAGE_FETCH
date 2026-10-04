/*
 * Derived from ZENCHE (https://github.com/Tauber01/ZENCHE) native/android
 * PtpIpCamera.java, MIT License, Copyright (c) 2026 Tauber01.
 * See the NOTICE file in the project root.
 *
 * Changes for IMAGE_FETCH:
 *   - 事件通道 type 8 由“排空”改为派发 ObjectAdded(0x4002) 给业务层；
 *   - GetObject(0x1009) 改为流式写入 OutputStream，不再整文件载入内存；
 *   - 移除实时取景/录像/参数读写等与“拍后取图”闭环无关的厂商能力。
 */
package com.imagefetch.transport;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.StorageBrowser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.SocketFactory;

/** Wi‑Fi / PTP‑IP 传输（CIPA DC-X005 双 TCP 通道）。 */
public final class PtpIpTransport implements CameraTransport {
    private static final int PACKET_INIT_COMMAND_REQUEST = 1;
    private static final int PACKET_INIT_COMMAND_ACK = 2;
    private static final int PACKET_INIT_EVENT_REQUEST = 3;
    private static final int PACKET_INIT_EVENT_ACK = 4;
    /** 相机侧已有会话/忙时对 InitCommandRequest 的应答：type 5。 */
    private static final int PACKET_INIT_FAIL = 5;
    private static final int PACKET_COMMAND_REQUEST = 6;
    private static final int PACKET_COMMAND_RESPONSE = 7;
    private static final int PACKET_EVENT = 8;
    private static final int PACKET_START_DATA = 9;
    private static final int PACKET_DATA = 10;
    private static final int PACKET_END_DATA = 12;
    private static final int PACKET_PROBE_REQUEST = 13;
    private static final int PACKET_PROBE_RESPONSE = 14;

    private static final int RESPONSE_OK = 0x2001;

    private static final int OPERATION_GET_DEVICE_INFO = 0x1001;
    private static final int OPERATION_OPEN_SESSION = 0x1002;
    private static final int OPERATION_CLOSE_SESSION = 0x1003;
    private static final int OPERATION_GET_STORAGE_IDS = 0x1004;
    private static final int OPERATION_GET_STORAGE_INFO = 0x1005;
    private static final int OPERATION_GET_OBJECT_HANDLES = 0x1007;
    private static final int OPERATION_GET_OBJECT_INFO = 0x1008;
    private static final int OPERATION_GET_OBJECT = 0x1009;
    private static final int OPERATION_GET_THUMB = 0x100a;
    private static final int OPERATION_CAPTURE = 0x100e;

    /** 标准 PTP ObjectAdded 事件；厂商等价事件（如 Canon EOS）TBC-awaiting-hardware。 */
    private static final int EVENT_OBJECT_ADDED = 0x4002;

    /** 单文件累计上限（与 ZENCHE 口径一致）；接收器单包上限 64 MiB。 */
    private static final long MAX_TRANSFER_BYTES = 512L * 1024 * 1024;
    private static final int MAX_PACKET_BYTES = 64 * 1024 * 1024;

    /** 保活参数：单次 PING 探测超时 3s；命令复核超时 3s。 */
    public static final int PROBE_TIMEOUT_MS = 3000;
    private static final int VERIFY_TIMEOUT_MS = 3000;

    /** 已连 PTP/IP 相机的厂商分类（detectVendor 结果，断连清零）。 */
    public enum CameraVendor {
        UNKNOWN,
        NIKON,
        CANON,
        SONY
    }

    private volatile Socket commandSocket;
    private volatile Socket eventSocket;
    private volatile Socket connectingCommandSocket;
    private volatile Socket connectingEventSocket;
    // 默认走系统路由；协调器在相机 Wi‑Fi 非默认网络时注入
    // Network.getSocketFactory()，把 PTP/IP socket 绑定到相机所在 Wi‑Fi。
    private volatile SocketFactory socketFactory = SocketFactory.getDefault();
    private final Object transportLock = new Object();
    private long transportGeneration;
    private InputStream commandInput;
    private OutputStream commandOutput;
    private InputStream eventInput;
    private OutputStream eventOutput;
    private final Object eventOutputLock = new Object();
    private final Object probeMonitor = new Object();
    private volatile boolean eventReaderRunning;
    private IOException eventReaderFailure;
    private volatile IOException commandChannelFailure;
    private long probeResponseSequence;
    /** 相机是否回 PONG；实测不回时置 false，保活改用真实命令往返（跨重连保持）。 */
    private volatile boolean pingKeepAliveSupported = true;
    private Thread eventReaderThread;
    private int transactionId = 1;
    private String cameraName = "PTP/IP Camera";
    private CameraVendor vendor = CameraVendor.UNKNOWN;
    private volatile ObjectEventListener objectEventListener;
    /** 存储卡文件句柄缓存（存储卷 → 句柄）：快速分页翻页时不必重新遍历目录。 */
    private final Map<Long, List<Long>> handleCache =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private final String initiatorName;

    public PtpIpTransport() {
        this("IMAGE_FETCH Android");
    }

    public PtpIpTransport(String initiatorName) {
        this.initiatorName = initiatorName;
    }

    /** 注入 socket 工厂（传 null 恢复默认路由）；须在 connect() 前调用。 */
    public synchronized void setSocketFactory(SocketFactory factory) {
        socketFactory = factory != null ? factory : SocketFactory.getDefault();
    }

    @Override
    public void setObjectEventListener(ObjectEventListener listener) {
        objectEventListener = listener;
    }

    @Override
    public synchronized String connect(String host, int port) throws Exception {
        close();
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
            throw new IOException("Wi‑Fi 相机地址或端口无效");
        }
        final long generation = beginTransportAttempt();
        try {
            Socket pendingCommandSocket = open(host.trim(), port, generation, true);
            InputStream pendingCommandInput = new BufferedInputStream(
                    pendingCommandSocket.getInputStream());
            OutputStream pendingCommandOutput = new BufferedOutputStream(
                    pendingCommandSocket.getOutputStream());

            ByteWriter initialization = new ByteWriter();
            byte[] guid = new byte[16];
            new SecureRandom().nextBytes(guid);
            initialization.bytes(guid);
            initialization.utf16(initiatorName);
            initialization.u32(0x00010000L);
            writePacket(
                    pendingCommandOutput,
                    PACKET_INIT_COMMAND_REQUEST,
                    initialization.data());
            ensureTransportGeneration(generation);
            Packet commandAck = readPacket(pendingCommandInput);
            ensureTransportGeneration(generation);
            if (commandAck.type == PACKET_INIT_FAIL) {
                throw new IOException(
                        "相机拒绝了新连接（InitFail）：相机侧仍持有上一次的会话。"
                                + "等待约 30 秒让相机会话超时后重试，"
                                + "或在相机上关闭再打开 Wi‑Fi 立即释放。");
            }
            if (commandAck.type != PACKET_INIT_COMMAND_ACK || commandAck.data.length < 28) {
                throw new IOException(
                        "相机返回了无效的 PTP/IP 握手数据（type=" + commandAck.type + "）");
            }
            long connectionNumber = u32(commandAck.data, 8);
            String announcedName = utf16(commandAck.data, 28);
            if (!announcedName.isEmpty()) cameraName = announcedName;

            Socket pendingEventSocket = open(host.trim(), port, generation, false);
            InputStream pendingEventInput = new BufferedInputStream(
                    pendingEventSocket.getInputStream());
            OutputStream pendingEventOutput = new BufferedOutputStream(
                    pendingEventSocket.getOutputStream());
            ByteWriter eventInitialization = new ByteWriter();
            eventInitialization.u32(connectionNumber);
            writePacket(
                    pendingEventOutput,
                    PACKET_INIT_EVENT_REQUEST,
                    eventInitialization.data());
            ensureTransportGeneration(generation);
            Packet eventAck = readPacket(pendingEventInput);
            ensureTransportGeneration(generation);
            if (eventAck.type != PACKET_INIT_EVENT_ACK) {
                throw new IOException("相机未确认 PTP/IP 事件通道");
            }

            synchronized (transportLock) {
                ensureTransportGenerationLocked(generation);
                if (connectingCommandSocket != pendingCommandSocket
                        || connectingEventSocket != pendingEventSocket) {
                    throw new EOFException("PTP/IP 连接尝试已失效");
                }
                commandSocket = pendingCommandSocket;
                commandInput = pendingCommandInput;
                commandOutput = pendingCommandOutput;
                eventSocket = pendingEventSocket;
                eventInput = pendingEventInput;
                eventOutput = pendingEventOutput;
                connectingCommandSocket = null;
                connectingEventSocket = null;
            }
            startEventReader(
                    pendingCommandSocket,
                    pendingEventSocket,
                    pendingEventInput,
                    pendingEventOutput,
                    generation);

            int response = command(OPERATION_OPEN_SESSION, 0, new long[]{1});
            if (response != RESPONSE_OK) throw rejected(response);
            ensureTransportGeneration(generation);
            transactionId = 1;
            return cameraName;
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    @Override
    public boolean supportsHeartbeat() {
        return true;
    }

    /**
     * 尽力发送 CloseSession(0x1003)，让相机立即释放本次会话。
     * 不做这件事的直接后果：相机会一直持有旧会话，下一次 InitCommandRequest 直接被
     * 回 InitFail(type 5)，表现为「第一次能连、之后每次连接都失败」。
     * 只做一次短超时尝试，失败即放弃——socket 随后会被关闭，相机将按自己的超时回收。
     */
    @Override
    public synchronized void releaseSession() {
        Socket socket = commandSocket;
        InputStream input = commandInput;
        OutputStream output = commandOutput;
        if (socket == null || input == null || output == null || socket.isClosed()) return;
        synchronized (probeMonitor) {
            // 命令通道已判死（或已中止）时不再尝试，避免把断链状态误当会话问题。
            if (commandChannelFailure != null) return;
        }
        int previousTimeout = -1;
        try {
            previousTimeout = socket.getSoTimeout();
            socket.setSoTimeout(1200);
            ByteWriter request = new ByteWriter();
            request.u32(1);
            request.u16(OPERATION_CLOSE_SESSION);
            request.u32(transactionId++);
            writePacket(output, PACKET_COMMAND_REQUEST, request.data());
            readPacket(input); // 应答码不作为成败依据：会话本就即将关闭。
        } catch (Exception ignored) {
        } finally {
            try {
                socket.setSoTimeout(previousTimeout);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public synchronized long[] capture() throws Exception {
        ensureConnected();
        int response = command(OPERATION_CAPTURE, transactionId++, new long[]{0, 0});
        if (response != RESPONSE_OK) {
            if (vendor == CameraVendor.CANON) {
                throw new IOException(
                        "相机拒绝了标准 Capture(0x100E)；Canon EOS 厂商快门扩展尚未实现"
                                + "（TBC-awaiting-hardware）。");
            }
            throw rejected(response);
        }
        // 原图写入机身存储卡，交由 NewPhotoResolver 通过句柄差集发现。
        return new long[0];
    }

    /**
     * 保活探测。
     * 原始实现把「PING 无应答」直接当成链路断开，但相当多的相机只单向下发 PING、
     * 并不回应发起方的 PING——于是空闲十几秒就会被误判掉线，触发一次无谓的拆链重连
     * （真机表现为「下载完一个文件、等一会儿就提示重连」）。
     * 现在改为：PING 无应答时用一次真实 PTP 往返复核，复核通过即认定链路存活，
     * 并永久降级为命令探测；只有两种方式都失败才判定离线。
     */
    public synchronized void probe() throws Exception {
        if (!pingKeepAliveSupported) {
            verifyAlive();
            return;
        }
        Exception pingFailure;
        try {
            probeWithPing();
            return;
        } catch (Exception failure) {
            pingFailure = failure;
        }
        synchronized (probeMonitor) {
            // 通道已因真实 IO 错误判死时没有复核的必要，直接失败。
            if (eventReaderFailure != null || commandChannelFailure != null) throw pingFailure;
        }
        verifyAlive();
        pingKeepAliveSupported = false;
    }

    /** 相机是否会对发起方的 PING 回 PONG；为 false 时保活走真实命令往返。 */
    public boolean pingKeepAliveSupported() {
        return pingKeepAliveSupported;
    }

    /**
     * 真实往返探活：GetDeviceInfo 带数据阶段，必须整段读完，否则命令通道会残留数据而串包
     * （与连接时识别厂商走的是同一条路径）。读超时临时压到 3s：健康的相机毫秒级返回。
     */
    private void verifyAlive() throws Exception {
        Socket socket = commandSocket;
        int previousTimeout = -1;
        if (socket != null) {
            try {
                previousTimeout = socket.getSoTimeout();
                socket.setSoTimeout(VERIFY_TIMEOUT_MS);
            } catch (Exception ignored) {
            }
        }
        try {
            commandWithData(OPERATION_GET_DEVICE_INFO, transactionId++, new long[0]);
        } finally {
            if (socket != null && previousTimeout > 0) {
                try {
                    socket.setSoTimeout(previousTimeout);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void probeWithPing() throws Exception {
        ensureConnected();
        Socket expectedSocket = eventSocket;
        OutputStream output = eventOutput;
        if (expectedSocket == null || output == null || !eventReaderRunning) {
            throw new IOException("PTP/IP 事件通道未连接");
        }

        long baseline;
        synchronized (probeMonitor) {
            if (commandChannelFailure != null) throw commandChannelFailure;
            if (eventReaderFailure != null) throw eventReaderFailure;
            baseline = probeResponseSequence;
        }
        synchronized (eventOutputLock) {
            writePacket(output, PACKET_PROBE_REQUEST, new byte[0]);
        }

        long deadline = System.nanoTime() + PROBE_TIMEOUT_MS * 1_000_000L;
        synchronized (probeMonitor) {
            while (probeResponseSequence == baseline
                    && eventReaderFailure == null
                    && eventReaderRunning) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                long milliseconds = remaining / 1_000_000L;
                int nanoseconds = (int) (remaining % 1_000_000L);
                try {
                    probeMonitor.wait(milliseconds, nanoseconds);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("心跳探测被取消", interrupted);
                }
            }
            if (eventReaderFailure != null) throw eventReaderFailure;
            if (!eventReaderRunning || eventSocket != expectedSocket) {
                throw new IOException("PTP/IP 事件通道已断开");
            }
            if (probeResponseSequence == baseline) {
                throw new SocketTimeoutException("心跳探测超时");
            }
        }
    }

    @Override
    public synchronized CameraStorage.Snapshot listStorage(int maxItems) throws Exception {
        ensureConnected();
        return StorageBrowser.loadAll(this::invokeOperation, maxItems);
    }

    @Override
    public synchronized CameraStorage.Page listStoragePage(int pageSize, int pageIndex)
            throws Exception {
        ensureConnected();
        return StorageBrowser.loadPage(this::invokeOperation, pageSize, pageIndex, handleCache);
    }

    @Override
    public void invalidateStorageCache() {
        synchronized (handleCache) {
            handleCache.clear();
        }
    }

    private byte[] invokeOperation(int operation, long[] parameters) throws Exception {
        return commandWithData(operation, transactionId++, parameters);
    }

    @Override
    public synchronized byte[] getStorageThumbnail(long handle) throws Exception {
        return commandWithData(OPERATION_GET_THUMB, transactionId++, new long[]{handle});
    }

    @Override
    public synchronized long downloadStorageObject(
            long handle,
            OutputStream output,
            ProgressListener progress) throws Exception {
        ensureConnected();
        if (output == null) throw new IOException("缺少下载输出流");
        return dataRequestToStream(
                OPERATION_GET_OBJECT,
                transactionId++,
                new long[]{handle},
                output,
                progress);
    }

    /**
     * data-in 请求（DataPhaseInfo=1）：OperationRequest → StartData(tx,total) →
     * Data/EndData 分块 → OperationResponse，数据边收边写，不整文件驻留内存。
     */
    private long dataRequestToStream(
            int operation,
            int transaction,
            long[] parameters,
            OutputStream output,
            ProgressListener progress) throws Exception {
        ensureConnected();
        ByteWriter request = new ByteWriter();
        request.u32(1);
        request.u16(operation);
        request.u32(transaction);
        for (long parameter : parameters) request.u32(parameter);
        writeCommandPacket(PACKET_COMMAND_REQUEST, request.data());

        Packet first = readCommandPacket();
        if (first.type == PACKET_COMMAND_RESPONSE) {
            if (first.data.length < 14
                    || u32(first.data, 10) != (transaction & 0xffff_ffffL)) {
                throw commandChannelFailed(
                        new IOException("相机返回了无效的 PTP/IP 响应"));
            }
            throw rejected(u16(first.data, 8));
        }
        if (first.type != PACKET_START_DATA || first.data.length < 20
                || u32(first.data, 8) != (transaction & 0xffff_ffffL)) {
            throw commandChannelFailed(
                    new IOException("相机返回了无效的 PTP/IP 数据阶段"));
        }
        long totalLength = u64(first.data, 12);
        boolean hasDeclaredLength = u32(first.data, 12) != 0xffff_ffffL
                || u32(first.data, 16) != 0xffff_ffffL;
        if (hasDeclaredLength && totalLength > MAX_TRANSFER_BYTES) {
            throw commandChannelFailed(
                    new IOException("机内文件超过当前 512 MB 单文件传输上限"));
        }

        long received = 0;
        while (true) {
            Packet packet = readCommandPacket();
            if ((packet.type != PACKET_DATA && packet.type != PACKET_END_DATA)
                    || packet.data.length < 12
                    || u32(packet.data, 8) != (transaction & 0xffff_ffffL)) {
                throw commandChannelFailed(
                        new IOException("相机返回了无效的 PTP/IP 文件数据包"));
            }
            int chunk = packet.data.length - 12;
            output.write(packet.data, 12, chunk);
            received += chunk;
            if (received > MAX_TRANSFER_BYTES) {
                throw commandChannelFailed(
                        new IOException("机内文件超过当前 512 MB 单文件传输上限"));
            }
            if (hasDeclaredLength && received > totalLength) {
                throw commandChannelFailed(
                        new IOException("相机返回的数据超过 StartData 声明长度"));
            }
            if (progress != null) {
                progress.onProgress(received, hasDeclaredLength ? totalLength : -1);
            }
            if (packet.type == PACKET_END_DATA) break;
        }
        if (hasDeclaredLength && received != totalLength) {
            throw commandChannelFailed(
                    new IOException("相机返回的数据与 StartData 声明长度不一致"));
        }
        Packet response = readCommandPacket();
        if (response.type != PACKET_COMMAND_RESPONSE
                || response.data.length < 14
                || u32(response.data, 10) != (transaction & 0xffff_ffffL)) {
            throw commandChannelFailed(
                    new IOException("相机没有完成 PTP/IP 文件事务"));
        }
        int code = u16(response.data, 8);
        if (code != RESPONSE_OK) throw rejected(code);
        return received;
    }

    // ── 厂商识别（GetDeviceInfo 0x1001 + 握手名称启发式） ──

    public synchronized CameraVendor getVendor() {
        return vendor;
    }

    /**
     * 识别已连机型厂商：优先解析 GetDeviceInfo(0x1001) 数据段 Manufacturer
     * （ISO 15740；0x1002 实为 OpenSession，勿混），退回握手相机名启发式。
     * 结果按会话缓存（connect 后调用），断连清零。
     */
    public synchronized CameraVendor detectVendor() throws Exception {
        if (vendor != CameraVendor.UNKNOWN) return vendor;
        CameraVendor nameBased = vendorForName(cameraName);
        CameraVendor resolved = nameBased;
        try {
            byte[] info = commandWithData(
                    OPERATION_GET_DEVICE_INFO, transactionId++, new long[0]);
            String manufacturer = deviceInfoManufacturer(info);
            if (manufacturer != null && !manufacturer.trim().isEmpty()) {
                resolved = vendorForManufacturer(manufacturer, nameBased);
            }
        } catch (PtpResponseException responseError) {
            if (!isVendorDiscoveryFallback(responseError.responseCode)) {
                throw responseError;
            }
            // 部分机型对 0x1001 直接回响应（无数据段），退回名称启发式。
        }
        vendor = resolved;
        return resolved;
    }

    /** GetDeviceInfo 数据段 Manufacturer 解析（PTP STR + AUINT16）。 */
    private static String deviceInfoManufacturer(byte[] data) {
        if (data.length < 8) return null;
        int[] offset = new int[]{8};
        if (readPtpString(data, offset) == null) return null; // VendorExtensionDesc
        if (offset[0] + 2 > data.length) return null;
        offset[0] += 2; // FunctionalMode
        for (int index = 0; index < 5; index++) {
            if (offset[0] + 4 > data.length) return null;
            long count = u32(data, offset[0]);
            offset[0] += 4;
            if (count > (data.length - offset[0]) / 2L) return null;
            offset[0] += (int) count * 2;
        }
        return readPtpString(data, offset);
    }

    private static String readPtpString(byte[] data, int[] offsetRef) {
        int offset = offsetRef[0];
        if (offset >= data.length) return null;
        int characterCount = data[offset] & 0xff;
        offset++;
        if (characterCount == 0) {
            offsetRef[0] = offset;
            return "";
        }
        int byteCount = characterCount * 2;
        if (byteCount > data.length - offset
                || data[offset + byteCount - 2] != 0
                || data[offset + byteCount - 1] != 0) return null;
        String text = new String(
                data,
                offset,
                byteCount - 2,
                StandardCharsets.UTF_16LE);
        offsetRef[0] = offset + byteCount;
        return text;
    }

    private static CameraVendor vendorForManufacturer(
            String manufacturer,
            CameraVendor fallback) {
        String text = manufacturer.toLowerCase(Locale.ROOT);
        if (text.contains("nikon")) return CameraVendor.NIKON;
        if (text.contains("canon")) return CameraVendor.CANON;
        if (text.contains("sony")) return CameraVendor.SONY;
        return fallback;
    }

    private static CameraVendor vendorForName(String name) {
        String text = name.toLowerCase(Locale.ROOT);
        if (text.contains("nikon")) return CameraVendor.NIKON;
        if (text.contains("canon")) return CameraVendor.CANON;
        if (text.contains("sony") || text.contains("ilce") || text.contains("alpha")) {
            return CameraVendor.SONY;
        }
        return CameraVendor.UNKNOWN;
    }

    // ── command 通道 ──

    private int command(int operation, int transaction, long[] parameters)
            throws Exception {
        ensureConnected();
        ByteWriter request = new ByteWriter();
        request.u32(1);
        request.u16(operation);
        request.u32(transaction);
        for (long parameter : parameters) request.u32(parameter);
        writeCommandPacket(PACKET_COMMAND_REQUEST, request.data());
        Packet response = readCommandPacket();
        if (response.type != PACKET_COMMAND_RESPONSE
                || response.data.length < 14
                || u32(response.data, 10) != (transaction & 0xffff_ffffL)) {
            // 带上实收内容，便于区分「相机回错事务」与「命令通道串包」。
            throw commandChannelFailed(new IOException(String.format(
                    Locale.ROOT,
                    "相机返回了无效的 PTP/IP 响应（op=0x%04X type=%d 实收事务=%s 期望=%d）",
                    operation,
                    response.type,
                    response.data.length >= 14
                            ? String.valueOf(u32(response.data, 10))
                            : "缺失",
                    transaction & 0xffff_ffffL)));
        }
        return u16(response.data, 8);
    }

    private byte[] commandWithData(int operation, int transaction, long[] parameters)
            throws Exception {
        ensureConnected();
        ByteWriter request = new ByteWriter();
        request.u32(1);
        request.u16(operation);
        request.u32(transaction);
        for (long parameter : parameters) request.u32(parameter);
        writeCommandPacket(PACKET_COMMAND_REQUEST, request.data());

        Packet first = readCommandPacket();
        if (first.type == PACKET_COMMAND_RESPONSE) {
            if (first.data.length < 14
                    || u32(first.data, 10) != (transaction & 0xffff_ffffL)) {
                throw commandChannelFailed(
                        new IOException("相机返回了无效的 PTP/IP 响应"));
            }
            throw rejected(u16(first.data, 8));
        }
        if (first.type != PACKET_START_DATA || first.data.length < 20
                || u32(first.data, 8) != (transaction & 0xffff_ffffL)) {
            throw commandChannelFailed(
                    new IOException("相机返回了无效的 PTP/IP 数据阶段"));
        }
        long totalLength = u64(first.data, 12);
        boolean hasDeclaredLength = u32(first.data, 12) != 0xffff_ffffL
                || u32(first.data, 16) != 0xffff_ffffL;
        if (hasDeclaredLength && totalLength > MAX_TRANSFER_BYTES) {
            throw commandChannelFailed(
                    new IOException("机内文件超过当前 512 MB 单文件传输上限"));
        }
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream(
                (int) Math.min(
                        hasDeclaredLength ? totalLength : 8L * 1024 * 1024,
                        8L * 1024 * 1024));
        while (true) {
            Packet packet = readCommandPacket();
            if ((packet.type != PACKET_DATA && packet.type != PACKET_END_DATA)
                    || packet.data.length < 12
                    || u32(packet.data, 8) != (transaction & 0xffff_ffffL)) {
                throw commandChannelFailed(
                        new IOException("相机返回了无效的 PTP/IP 文件数据包"));
            }
            data.write(packet.data, 12, packet.data.length - 12);
            if (data.size() > MAX_TRANSFER_BYTES) {
                throw commandChannelFailed(
                        new IOException("机内文件超过当前 512 MB 单文件传输上限"));
            }
            if (hasDeclaredLength && data.size() > totalLength) {
                throw commandChannelFailed(
                        new IOException("相机返回的数据超过 StartData 声明长度"));
            }
            if (packet.type == PACKET_END_DATA) break;
        }
        if (hasDeclaredLength && data.size() != totalLength) {
            throw commandChannelFailed(
                    new IOException("相机返回的数据与 StartData 声明长度不一致"));
        }
        Packet response = readCommandPacket();
        if (response.type != PACKET_COMMAND_RESPONSE
                || response.data.length < 14
                || u32(response.data, 10) != (transaction & 0xffff_ffffL)) {
            throw commandChannelFailed(
                    new IOException("相机没有完成 PTP/IP 文件事务"));
        }
        int code = u16(response.data, 8);
        if (code != RESPONSE_OK) throw rejected(code);
        return data.toByteArray();
    }

    // ── 事件通道 ──

    private void startEventReader(
            Socket expectedCommandSocket,
            Socket expectedEventSocket,
            InputStream input,
            OutputStream output,
            long generation) throws IOException {
        synchronized (transportLock) {
            ensureEventReaderSessionLocked(
                    expectedCommandSocket,
                    expectedEventSocket,
                    generation);
            synchronized (probeMonitor) {
                eventReaderFailure = null;
                probeResponseSequence = 0;
                eventReaderRunning = true;
            }
        }
        Thread reader = new Thread(
                () -> runEventReader(
                        expectedCommandSocket,
                        expectedEventSocket,
                        input,
                        output,
                        generation),
                "imagefetch-ptpip-events");
        reader.setDaemon(true);
        eventReaderThread = reader;
        reader.start();
    }

    private void runEventReader(
            Socket expectedCommandSocket,
            Socket expectedEventSocket,
            InputStream input,
            OutputStream output,
            long generation) {
        IOException failure = null;
        try {
            while (isCurrentEventReader(
                    expectedCommandSocket,
                    expectedEventSocket,
                    generation)) {
                Packet packet;
                try {
                    packet = readPacket(input);
                } catch (SocketTimeoutException idle) {
                    continue;
                }
                if (!isCurrentEventReader(
                        expectedCommandSocket,
                        expectedEventSocket,
                        generation)) {
                    return;
                }
                if (packet.type == PACKET_PROBE_REQUEST) {
                    synchronized (eventOutputLock) {
                        if (!isCurrentEventReader(
                                expectedCommandSocket,
                                expectedEventSocket,
                                generation)) {
                            return;
                        }
                        writePacket(output, PACKET_PROBE_RESPONSE, new byte[0]);
                    }
                } else if (packet.type == PACKET_PROBE_RESPONSE) {
                    synchronized (transportLock) {
                        if (!isCurrentEventReaderLocked(
                                expectedCommandSocket,
                                expectedEventSocket,
                                generation)) {
                            return;
                        }
                        synchronized (probeMonitor) {
                            probeResponseSequence++;
                            probeMonitor.notifyAll();
                        }
                    }
                } else if (packet.type == PACKET_EVENT) {
                    // 事件已从 TCP 流中完整取走；派发给业务层（ObjectAdded 等）。
                    dispatchEvent(packet.data);
                }
            }
        } catch (IOException error) {
            failure = error;
        } finally {
            boolean ownsSession = false;
            synchronized (transportLock) {
                if (isCurrentEventReaderLocked(
                        expectedCommandSocket,
                        expectedEventSocket,
                        generation)) {
                    ownsSession = true;
                    eventReaderRunning = false;
                    synchronized (probeMonitor) {
                        eventReaderFailure = failure != null
                                ? failure
                                : new EOFException("PTP/IP 事件通道已断开");
                        probeMonitor.notifyAll();
                    }
                }
            }
            if (ownsSession) {
                // 只关闭 reader 捕获的配对 command socket；绝不读取可能已经
                // 指向新会话的共享字段。
                closeQuietly(expectedCommandSocket);
            }
        }
    }

    /** 解析 type 8 事件包：EventCode@8(u16)、TransactionID@10(u32)、参数@14 起。 */
    private void dispatchEvent(byte[] data) {
        if (data == null || data.length < 14) return;
        int eventCode = u16(data, 8);
        if (eventCode != EVENT_OBJECT_ADDED) return;
        if (data.length < 22) return; // 需要 StorageID + ObjectHandle 两个参数
        ObjectEventListener listener = objectEventListener;
        if (listener == null) return;
        long storageId = u32(data, 14);
        long objectHandle = u32(data, 18);
        try {
            listener.onObjectAdded(storageId, objectHandle);
        } catch (RuntimeException ignored) {
            // 业务层异常不得终止 event reader。
        }
    }

    private boolean isCurrentEventReader(
            Socket expectedCommandSocket,
            Socket expectedEventSocket,
            long generation) {
        synchronized (transportLock) {
            return isCurrentEventReaderLocked(
                    expectedCommandSocket,
                    expectedEventSocket,
                    generation);
        }
    }

    private boolean isCurrentEventReaderLocked(
            Socket expectedCommandSocket,
            Socket expectedEventSocket,
            long generation) {
        return eventReaderRunning
                && transportGeneration == generation
                && commandSocket == expectedCommandSocket
                && eventSocket == expectedEventSocket;
    }

    private void ensureEventReaderSessionLocked(
            Socket expectedCommandSocket,
            Socket expectedEventSocket,
            long generation) throws IOException {
        ensureTransportGenerationLocked(generation);
        if (commandSocket != expectedCommandSocket
                || eventSocket != expectedEventSocket) {
            throw new EOFException("PTP/IP 事件 reader 会话已失效");
        }
    }

    // ── 健康与生命周期 ──

    @Override
    public synchronized boolean isConnected() {
        return commandSocket != null && commandSocket.isConnected()
                && !commandSocket.isClosed();
    }

    @Override
    public synchronized void assertHealthy() throws IOException {
        ensureConnected();
        synchronized (probeMonitor) {
            if (commandChannelFailure != null) throw commandChannelFailure;
            if (eventReaderFailure != null) throw eventReaderFailure;
            if (!eventReaderRunning || eventSocket == null || eventSocket.isClosed()) {
                throw new EOFException("PTP/IP 事件通道已断开");
            }
        }
    }

    @Override
    public synchronized String getCameraName() {
        return cameraName;
    }

    private void ensureConnected() throws IOException {
        if (commandChannelFailure != null) throw commandChannelFailure;
        if (!isConnected() || commandInput == null || commandOutput == null) {
            throw new IOException("请先连接 Wi‑Fi 相机");
        }
    }

    private long beginTransportAttempt() {
        synchronized (transportLock) {
            transportGeneration++;
            return transportGeneration;
        }
    }

    private void ensureTransportGeneration(long generation) throws IOException {
        synchronized (transportLock) {
            ensureTransportGenerationLocked(generation);
        }
    }

    private void ensureTransportGenerationLocked(long generation) throws IOException {
        if (generation != transportGeneration) {
            throw new EOFException("PTP/IP 连接尝试已失效");
        }
    }

    private Socket open(
            String host,
            int port,
            long generation,
            boolean commandChannel) throws IOException {
        Socket socket = socketFactory.createSocket();
        synchronized (transportLock) {
            ensureTransportGenerationLocked(generation);
            if (commandChannel) connectingCommandSocket = socket;
            else connectingEventSocket = socket;
        }
        try {
            // 相机刚从 Wi‑Fi 休眠中醒来时 TCP 握手可能明显变慢，超时放宽到 15s。
            socket.connect(new InetSocketAddress(host, port), 15000);
            ensureTransportGeneration(generation);
            socket.setSoTimeout(12000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            ensureTransportGeneration(generation);
            return socket;
        } catch (IOException error) {
            synchronized (transportLock) {
                if (commandChannel && connectingCommandSocket == socket) {
                    connectingCommandSocket = null;
                } else if (!commandChannel && connectingEventSocket == socket) {
                    connectingEventSocket = null;
                }
            }
            closeQuietly(socket);
            throw error;
        }
    }

    private void writeCommandPacket(int type, byte[] payload) throws IOException {
        try {
            writePacket(commandOutput, type, payload);
        } catch (IOException error) {
            throw commandChannelFailed(error);
        }
    }

    private Packet readCommandPacket() throws IOException {
        try {
            return readPacket(commandInput);
        } catch (IOException error) {
            throw commandChannelFailed(error);
        }
    }

    private IOException commandChannelFailed(IOException error) {
        synchronized (probeMonitor) {
            commandChannelFailure = error;
            probeMonitor.notifyAll();
        }
        return error;
    }

    private static void writePacket(OutputStream output, int type, byte[] payload)
            throws IOException {
        ByteWriter packet = new ByteWriter();
        packet.u32(payload.length + 8L);
        packet.u32(type);
        packet.bytes(payload);
        output.write(packet.data());
        output.flush();
    }

    private static Packet readPacket(InputStream input) throws IOException {
        // 只有在尚未消费任何新包字节时，事件 reader 才能把超时视为空闲。
        // 半个 header 或已完整读取 header 后的 payload 超时都必须终止该会话，
        // 否则下一轮会从包中间误当成新 header，永久流失步。
        byte[] header = readExactly(input, 8, true);
        long length = u32(header, 0);
        if (length < 8 || length > MAX_PACKET_BYTES) {
            throw new IOException("PTP/IP 数据包长度无效");
        }
        byte[] payload = readExactly(input, (int) length - 8, false);
        byte[] data = new byte[(int) length];
        System.arraycopy(header, 0, data, 0, 8);
        System.arraycopy(payload, 0, data, 8, payload.length);
        return new Packet((int) u32(header, 4), data);
    }

    private static byte[] readExactly(
            InputStream input,
            int count,
            boolean allowIdleTimeout) throws IOException {
        byte[] data = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read;
            try {
                read = input.read(data, offset, count - offset);
            } catch (SocketTimeoutException timeout) {
                if (allowIdleTimeout && offset == 0) throw timeout;
                throw new IOException("PTP/IP 数据包在读取中途超时", timeout);
            }
            if (read < 0) throw new EOFException("相机提前关闭了连接");
            offset += read;
        }
        return data;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }

    private static long u64(byte[] data, int offset) {
        long low = u32(data, offset);
        long high = u32(data, offset + 4);
        if (high > 0x7fff_ffffL) return Long.MAX_VALUE;
        long value = (high << 32) | low;
        return value < 0 ? Long.MAX_VALUE : value;
    }

    private static String utf16(byte[] data, int offset) {
        int end = offset;
        while (end + 1 < data.length && (data[end] != 0 || data[end + 1] != 0)) {
            end += 2;
        }
        if (end <= offset) return "";
        return new String(data, offset, end - offset, StandardCharsets.UTF_16LE);
    }

    private PtpResponseException rejected(int response) {
        PtpResponseException error = new PtpResponseException(response);
        if (error.isSessionFatal()) {
            commandChannelFailed(error);
        }
        return error;
    }

    private static boolean isVendorDiscoveryFallback(int response) {
        return response == RESPONSE_OK // 无数据段的兼容 responder
                || response == 0x2005 // OperationNotSupported
                || response == 0x200a // DevicePropNotSupported
                || response == 0x2019; // DeviceBusy：会话仍健康，退回握手名称
    }

    /**
     * 不等待实例 monitor，立即关闭底层 socket 以打断阻塞中的同步读写。
     * 完整字段复位仍由随后排队的 close() 完成。
     */
    public void abortTransport() {
        eventReaderRunning = false;
        IOException failure = new EOFException("PTP/IP 连接已中止");
        Socket pendingCommand;
        Socket pendingEvent;
        Socket activeCommand;
        Socket activeEvent;
        synchronized (transportLock) {
            transportGeneration++;
            pendingCommand = connectingCommandSocket;
            pendingEvent = connectingEventSocket;
            activeCommand = commandSocket;
            activeEvent = eventSocket;
            connectingCommandSocket = null;
            connectingEventSocket = null;
            commandSocket = null;
            eventSocket = null;
        }
        synchronized (probeMonitor) {
            commandChannelFailure = failure;
            eventReaderFailure = failure;
            probeMonitor.notifyAll();
        }
        closeQuietly(pendingCommand);
        closeQuietly(pendingEvent);
        if (activeCommand != pendingCommand) closeQuietly(activeCommand);
        if (activeEvent != pendingEvent) closeQuietly(activeEvent);
    }

    @Override
    public synchronized void close() {
        Thread reader = eventReaderThread;
        eventReaderThread = null;
        abortTransport();
        if (reader != null && reader != Thread.currentThread()) reader.interrupt();
        commandInput = null;
        commandOutput = null;
        eventInput = null;
        eventOutput = null;
        synchronized (probeMonitor) {
            commandChannelFailure = null;
            eventReaderFailure = new EOFException("PTP/IP 事件通道已断开");
            probeMonitor.notifyAll();
        }
        transactionId = 1;
        cameraName = "PTP/IP Camera";
        vendor = CameraVendor.UNKNOWN;
        handleCache.clear();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Packet {
        final int type;
        final byte[] data;

        Packet(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    private static final class ByteWriter {
        private byte[] data = new byte[64];
        private int size;

        void u16(long value) {
            ensure(2);
            data[size++] = (byte) value;
            data[size++] = (byte) (value >> 8);
        }

        void u32(long value) {
            ensure(4);
            data[size++] = (byte) value;
            data[size++] = (byte) (value >> 8);
            data[size++] = (byte) (value >> 16);
            data[size++] = (byte) (value >> 24);
        }

        void u64(long value) {
            u32(value & 0xffff_ffffL);
            u32((value >>> 32) & 0xffff_ffffL);
        }

        void utf16(String value) {
            bytes(value.getBytes(StandardCharsets.UTF_16LE));
            u16(0);
        }

        void bytes(byte[] value) {
            ensure(value.length);
            System.arraycopy(value, 0, data, size, value.length);
            size += value.length;
        }

        byte[] data() {
            byte[] result = new byte[size];
            System.arraycopy(data, 0, result, 0, size);
            return result;
        }

        private void ensure(int count) {
            if (size + count <= data.length) return;
            byte[] replacement = new byte[Math.max(data.length * 2, size + count)];
            System.arraycopy(data, 0, replacement, 0, size);
            data = replacement;
        }
    }
}
