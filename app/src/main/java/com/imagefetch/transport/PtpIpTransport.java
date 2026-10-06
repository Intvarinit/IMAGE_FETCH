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
    /** 相机侧已存在会话（我们固定用 sessionID=1）：视为可复用的同一会话，而非致命错误。 */
    private static final int RESPONSE_SESSION_ALREADY_OPEN = 0x201e;
    /** 本工程固定使用的 PTP 会话号（与 USB 侧一致）。 */
    private static final long SESSION_ID = 1L;

    private static final int OPERATION_GET_DEVICE_INFO = 0x1001;
    private static final int OPERATION_OPEN_SESSION = 0x1002;
    private static final int OPERATION_GET_STORAGE_IDS = 0x1004;
    private static final int OPERATION_GET_STORAGE_INFO = 0x1005;
    private static final int OPERATION_GET_OBJECT_HANDLES = 0x1007;
    private static final int OPERATION_GET_OBJECT_INFO = 0x1008;
    private static final int OPERATION_GET_OBJECT = 0x1009;
    private static final int OPERATION_GET_THUMB = 0x100a;
    /** 标准 PTP 快门（ISO 15740）。EOS 机型普遍不支持，回 0x2005。 */
    private static final int OPERATION_CAPTURE = 0x100e;
    /**
     * Canon EOS 厂商遥控快门（opcode 见 libgphoto2 camlibs/ptp2/ptp.h，并经其调试日志复核）：
     * 0x9114 SetRemoteMode → 0x9115 SetEventMode → 0x9128 RemoteReleaseOn
     * → 0x9129 RemoteReleaseOff。EOS 只接受这条路径，标准 0x100E 会回 0x2005。
     *
     * <p>注意 0x9116 是 EOS_GetEvent（回事件数据块）而非快门：曾误用它，结果每次
     * 都「回 0x2001 但相机毫无反应」——那只是把待读事件取走了。
     */
    private static final int CANON_SET_REMOTE_MODE = 0x9114;
    private static final int CANON_SET_EVENT_MODE = 0x9115;
    private static final int CANON_REMOTE_RELEASE_ON = 0x9128;
    private static final int CANON_REMOTE_RELEASE_OFF = 0x9129;
    /** 操作不被支持：0x100E 在 EOS 上的典型应答，也是此处区分报错文案的依据。 */
    private static final int RESPONSE_OPERATION_NOT_SUPPORTED = 0x2005;
    /** 参数不被支持：厂商快门参数形态不一致时的典型应答，用于决定是否换参数重试。 */
    private static final int RESPONSE_PARAMETER_NOT_SUPPORTED = 0x2006;
    /** Canon 半按编码：实测相机在此步测光/对焦，但不会释放快门。 */
    private static final long[] HALF_PRESS = {1, 0};
    /**
     * 半按到全按之间留给测光/对焦的时间。不能太短：间隔不足会在合焦前就按下，
     * 表现为「相机亮过对焦灯但没拍照」。
     */
    private static final long CANON_RELEASE_HOLD_MS = 1000L;
    /** Canon 全按编码。libgphoto2 调试日志中全按即 {@code remotereleaseon(params, 2, 0)}。 */
    private static final long[] FULL_PRESS = {2, 0};
    /** 全按后判定「是否真的拍到」的探测窗口与间隔。 */
    private static final long CANON_SHOT_VERIFY_MS = 5000L;
    private static final long CANON_SHOT_VERIFY_INTERVAL_MS = 500L;

    /** 标准 PTP ObjectAdded 事件；厂商等价事件（如 Canon EOS）TBC-awaiting-hardware。 */
    private static final int EVENT_OBJECT_ADDED = 0x4002;

    /** 单文件累计上限（与 ZENCHE 口径一致）；接收器单包上限 64 MiB。 */
    private static final long MAX_TRANSFER_BYTES = 512L * 1024 * 1024;
    private static final int MAX_PACKET_BYTES = 64 * 1024 * 1024;

    /** 保活参数：单次 PING 探测超时 3s；命令复核超时 3s。 */
    public static final int PROBE_TIMEOUT_MS = 3000;
    private static final int VERIFY_TIMEOUT_MS = 3000;

    /**
     * 握手重试：覆盖 InitFail（相机仍持有旧会话）与 ECONNREFUSED（相机 PTP 服务刚随上一个
     * 连接结束而重启，需几秒才重新监听 15740）。按 3s / 5s / 8s 退避共试 4 次，
     * 给相机约 16s 的恢复窗口。
     */
    private static final long[] CONNECT_RETRY_DELAYS_MS = {3000L, 5000L, 8000L};
    private static final int CONNECT_ATTEMPTS = CONNECT_RETRY_DELAYS_MS.length + 1;

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
    /** 最近一次成功建链用的本机出口地址:端口（真机排查「走的哪个网卡」靠它）。 */
    private volatile String lastLocalEndpoint = "未建立";
    /** 最近一次建链的网络选择结果（绑定相机 Wi‑Fi / 回退默认路由）。 */
    private volatile String lastNetworkBinding = "未建立";
    /** 最近一次 connect() 的逐次尝试记录，供报错弹窗直接展示。 */
    private final List<String> connectDiagnostics =
            Collections.synchronizedList(new ArrayList<>());
    // 默认走系统路由；协调器在相机 Wi‑Fi 非默认网络时注入
    // Network.getSocketFactory()，把 PTP/IP socket 绑定到相机所在 Wi‑Fi。
    private volatile SocketFactory socketFactory = SocketFactory.getDefault();
    /** 是否正在按相机 Wi‑Fi 绑定 socket（决定连接失败时能否回退默认路由）。 */
    private volatile boolean bindToCameraNetwork;
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
    /** Canon 遥控/事件模式是否已在本会话设置过（每次重连复位）。 */
    private boolean canonRemoteModeReady;
    /** 最近一次 capture() 的厂商快门逐步诊断（每步响应码与应答载荷）。 */
    private final StringBuilder captureSteps = new StringBuilder();
    private volatile ObjectEventListener objectEventListener;
    /** 存储卡文件句柄缓存（存储卷 → 句柄）：快速分页翻页时不必重新遍历目录。 */
    private final Map<Long, List<Long>> handleCache =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private final String initiatorName;
    /**
     * 发起方 GUID（InitCommandRequest 的 16 字节）：持久化后每次握手复用同一身份，
     * 相机有机会把重连识别为「同一设备」而非新设备，从而允许替换旧连接。
     * 为 null 时退回每次随机（原行为）。
     */
    private volatile byte[] initiatorGuid;

    public PtpIpTransport() {
        this("IMAGE_FETCH Android");
    }

    public PtpIpTransport(String initiatorName) {
        this.initiatorName = initiatorName;
    }

    /** 注入持久化的发起方 GUID（长度必须为 16；传 null 恢复每次随机）。 */
    public void setInitiatorGuid(byte[] guid) {
        initiatorGuid = (guid != null && guid.length == 16) ? guid.clone() : null;
    }

    /** 当前握手用的 GUID 十六进制串（诊断用）。 */
    public String initiatorGuidHex() {
        byte[] guid = initiatorGuid;
        if (guid == null) return "每次随机（未持久化）";
        StringBuilder text = new StringBuilder(32);
        for (byte b : guid) text.append(String.format(Locale.ROOT, "%02X", b));
        return text.toString();
    }

    /** 注入 socket 工厂（传 null 恢复默认路由）；须在 connect() 前调用。 */
    public synchronized void setSocketFactory(SocketFactory factory) {
        bindToCameraNetwork = factory != null;
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
        String target = host.trim();
        connectDiagnostics.clear();
        lastLocalEndpoint = "未建立";
        lastNetworkBinding = bindToCameraNetwork ? "相机 Wi‑Fi（待绑定）" : "系统默认路由（未绑定）";
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            long startedAt = System.currentTimeMillis();
            try {
                String name = connectOnce(target, port);
                connectDiagnostics.add(String.format(
                        Locale.ROOT,
                        "第 %d/%d 次握手成功（%d ms），本机出口 %s",
                        attempt,
                        CONNECT_ATTEMPTS,
                        System.currentTimeMillis() - startedAt,
                        lastLocalEndpoint));
                return name;
            } catch (InitRejectedException rejection) {
                // 相机侧仍持有旧会话：稍等后重新握手即可，用户不必去重开相机 Wi‑Fi。
                lastFailure = rejection;
                recordConnectFailure(attempt, startedAt, rejection, true);
            } catch (Exception refused) {
                // ECONNREFUSED：网络可达但 15740 无人监听——相机的 PTP 服务很可能刚随
                // 上一个连接结束而重启，给它几秒。其它失败（EHOSTUNREACH 等）不重试。
                if (!isConnectionRefused(refused)) {
                    recordConnectFailure(attempt, startedAt, refused, false);
                    throw refused;
                }
                lastFailure = refused;
                recordConnectFailure(attempt, startedAt, refused, true);
            }
            if (attempt == CONNECT_ATTEMPTS) break;
            sleepQuietly(CONNECT_RETRY_DELAYS_MS[attempt - 1]);
        }
        if (lastFailure instanceof InitRejectedException) {
            throw new IOException(
                    "相机拒绝了新连接（InitFail）：相机侧仍持有上一次的会话（已重试 "
                            + CONNECT_ATTEMPTS + " 次）。请在相机上关闭再打开 Wi‑Fi 立即释放。",
                    lastFailure);
        }
        throw lastFailure;
    }

    /** ECONNREFUSED：目标可达但端口无监听（与 EHOSTUNREACH「主机不可达」相反）。 */
    private static boolean isConnectionRefused(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            if (message.contains("ECONNREFUSED") || message.contains("Connection refused")) {
                return true;
            }
        }
        return false;
    }

    private void recordConnectFailure(
            int attempt, long startedAt, Throwable error, boolean willRetry) {
        connectDiagnostics.add(String.format(
                Locale.ROOT,
                "第 %d/%d 次握手失败（%d ms，%s）：%s",
                attempt,
                CONNECT_ATTEMPTS,
                System.currentTimeMillis() - startedAt,
                willRetry ? "将重试" : "不重试",
                describe(error)));
    }

    /** 展开异常链：根因（ECONNABORTED / EPERM / ECONNREFUSED）常被包在内层，报错时要看得到。 */
    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (text.length() > 0) text.append(" ← ");
            text.append(cause.getClass().getSimpleName())
                    .append(": ")
                    .append(cause.getMessage());
        }
        return text.toString();
    }

    /** 最近一次连接尝试的完整诊断（网络选择 + 本机出口 + 逐次失败的异常链）。 */
    public String connectDiagnostics() {
        StringBuilder text = new StringBuilder();
        text.append("网络选择：").append(lastNetworkBinding);
        text.append("\n本机出口：").append(lastLocalEndpoint);
        text.append("\n发起方 GUID：").append(initiatorGuidHex());
        List<String> lines;
        synchronized (connectDiagnostics) {
            lines = new ArrayList<>(connectDiagnostics);
        }
        for (String line : lines) text.append('\n').append(line);
        return text.toString();
    }

    /** 单次 PTP/IP 握手 + OpenSession；InitFail 交给 connect() 决定是否重试。 */
    private String connectOnce(String host, int port) throws Exception {
        final long generation = beginTransportAttempt();
        try {
            Socket pendingCommandSocket = open(host, port, generation, true);
            InputStream pendingCommandInput = new BufferedInputStream(
                    pendingCommandSocket.getInputStream());
            OutputStream pendingCommandOutput = new BufferedOutputStream(
                    pendingCommandSocket.getOutputStream());

            ByteWriter initialization = new ByteWriter();
            byte[] guid = initiatorGuid;
            if (guid == null) {
                // 未持久化时退回每次随机（旧行为）。
                guid = new byte[16];
                new SecureRandom().nextBytes(guid);
            }
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
                throw new InitRejectedException("相机拒绝了新连接（InitFail）");
            }
            if (commandAck.type != PACKET_INIT_COMMAND_ACK || commandAck.data.length < 28) {
                throw new IOException(
                        "相机返回了无效的 PTP/IP 握手数据（type=" + commandAck.type + "）");
            }
            long connectionNumber = u32(commandAck.data, 8);
            String announcedName = utf16(commandAck.data, 28);
            if (!announcedName.isEmpty()) cameraName = announcedName;

            Socket pendingEventSocket = open(host, port, generation, false);
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

            int response = command(OPERATION_OPEN_SESSION, 0, new long[]{SESSION_ID});
            // 相机侧只维持一个会话：我们固定用 sessionID=1，若它仍持有我们上次的会话，
            // 视为成功直接复用（与 USB 侧一致），而不是报错逼用户重开相机 Wi‑Fi。
            if (response != RESPONSE_OK && response != RESPONSE_SESSION_ALREADY_OPEN) {
                throw rejected(response);
            }
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

    @Override
    public synchronized long[] capture() throws Exception {
        ensureConnected();
        synchronized (captureSteps) {
            captureSteps.setLength(0);
        }
        // 厂商识别此前只在测试里调用，从未接入实连路径：不识别就无法决定走标准快门
        // 还是厂商遥控快门。先用握手名称零成本判断，判不出来才发 GetDeviceInfo（多一次往返）。
        if (vendor == CameraVendor.UNKNOWN) {
            vendor = vendorForName(cameraName);
        }
        if (vendor == CameraVendor.UNKNOWN) {
            try {
                detectVendor();
            } catch (Exception ignored) {
                // 识别失败按标准路径尝试，实际响应码会出现在最终报错里。
            }
        }
        String vendorFailure = "";
        if (vendor == CameraVendor.CANON) {
            try {
                canonRemoteRelease();
                // 原图写入机身存储卡，交由 NewPhotoResolver 通过句柄差集发现。
                return new long[0];
            } catch (Exception vendorError) {
                vendorFailure = "Canon 遥控快门（0x9114/0x9115/0x9128）失败："
                        + describe(vendorError);
            }
        }
        int response = command(OPERATION_CAPTURE, transactionId++, new long[]{0, 0});
        recordCaptureStep("0x100E InitiateCapture(0,0) → " + hexCode(response));
        if (response == RESPONSE_OK) {
            return new long[0];
        }
        String standard = String.format(
                Locale.ROOT, "标准 Capture(0x100E) 返回 0x%04X", response);
        if (response == RESPONSE_OPERATION_NOT_SUPPORTED) {
            throw new IOException(
                    "相机不支持标准快门 InitiateCapture(0x100E)。"
                            + (vendorFailure.isEmpty() ? "" : vendorFailure + "；")
                            + standard
                            + "。EOS 机型只接受厂商遥控快门；请确认相机在拍照模式"
                            + "（非回放/录像）、未在写卡，且镜头为 AF 或 MF 均可。"
                            + captureStepsSuffix(),
                    rejected(response));
        }
        if (vendorFailure.isEmpty()) {
            throw new IOException(
                    "相机拒绝了快门操作。" + standard + captureStepsSuffix(),
                    rejected(response));
        }
        throw new IOException(
                vendorFailure + "；" + standard + captureStepsSuffix(), rejected(response));
    }

    /** 把本轮快门各步的响应码拼到错误/超时文案后面，便于判断是谁没成。 */
    private String captureStepsSuffix() {
        String steps = captureDiagnostics();
        return steps.isEmpty() ? "" : "\n快门诊断：\n" + steps;
    }

    /**
     * Canon EOS 遥控快门：进入遥控/事件模式后按下-释放快门。
     * 遥控模式每个会话只需设置一次；这些命令失败也继续尝试释放（部分机型连上即遥控态）。
     */
    private void canonRemoteRelease() throws Exception {
        if (!canonRemoteModeReady) {
            // 这两步的响应码不能忽略：若遥控模式没设置成功，后面的释放命令即使回 OK
            // 相机也不会真的拍。以前丢掉了返回值，导致「回 OK 但没拍照」无从判断。
            int remoteMode = command(CANON_SET_REMOTE_MODE, transactionId++, new long[]{1});
            recordCaptureStep("0x9114 SetRemoteMode(1) → " + hexCode(remoteMode));
            int eventMode = command(CANON_SET_EVENT_MODE, transactionId++, new long[]{1});
            recordCaptureStep("0x9115 SetEventMode(1) → " + hexCode(eventMode));
            canonRemoteModeReady = true;
        }
        // 半按：实测相机在此步会测光/对焦（屏幕里对焦成功）但不拍，因此后面必须补全按。
        ReleaseResult half = releaseCommand(CANON_REMOTE_RELEASE_ON, HALF_PRESS);
        recordCaptureStep(stepText("0x9128 RemoteReleaseOn(1,0) 半按", half));
        if (half.code != RESPONSE_OK) throw rejected(half.code);
        sleepQuietly(CANON_RELEASE_HOLD_MS);

        // 全按：只发一次 libgphoto2 实证过的 (2, 0)。
        long baseline = probeMaxObjectHandle();
        ReleaseResult press = releaseCommand(CANON_REMOTE_RELEASE_ON, FULL_PRESS);
        recordCaptureStep(stepText("0x9128 RemoteReleaseOn(2,0) 全按", press));
        // 全按后必须立刻松开：0x9128 等价于「按住快门不放」，机身驱动模式是连拍时，
        // 按住多久就一直拍多久——原先要等 waitForNewObject 探到对象才松手，慢速连拍下
        // 一次点击会拍出好几张。现在先松手，再等对象出现（等待不影响已拍张数）。
        try {
            ReleaseResult released = releaseCommand(CANON_REMOTE_RELEASE_OFF, FULL_PRESS);
            recordCaptureStep(stepText("0x9129 RemoteReleaseOff(2,0)", released));
        } catch (Exception ignored) {
            // 释放失败不影响本次已触发的拍摄；下次拍摄会重新按下-释放。
        }
        if (press.code != RESPONSE_OK) {
            throw new IOException(
                    "相机拒绝了 Canon 遥控快门 0x9128 RemoteReleaseOn(2,0)。"
                            + captureStepsSuffix(),
                    rejected(press.code));
        }
        if (baseline >= 0 && waitForNewObject(baseline)) {
            recordCaptureStep("已确认产生新对象");
        }
    }

    /**
     * 轮询存储卡句柄最大值，判断这次全按是否真的产生了新对象。
     * 这是「相机回了 OK 但没拍」唯一能自证的办法。
     */
    private boolean waitForNewObject(long baselineHandle) {
        long deadline = System.currentTimeMillis() + CANON_SHOT_VERIFY_MS;
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(CANON_SHOT_VERIFY_INTERVAL_MS);
            long current = probeMaxObjectHandle();
            if (current > baselineHandle) return true;
        }
        return false;
    }

    /** 存储卡上最大的对象句柄；探测失败返回 -1（不抛，避免掩盖真正的快门问题）。 */
    private long probeMaxObjectHandle() {
        try {
            // 拍摄会新增对象，必须让分页句柄缓存失效后再枚举，否则看不到新句柄。
            invalidateStorageCache();
            StorageBrowser.loadPage(this::invokeOperation, 1, 0, handleCache);
            long max = 0;
            synchronized (handleCache) {
                for (List<Long> handles : handleCache.values()) {
                    for (Long handle : handles) {
                        if (handle != null && handle > max) max = handle;
                    }
                }
            }
            return max;
        } catch (Exception error) {
            recordCaptureStep("句柄探测失败（不影响发快门）：" + describe(error));
            return -1;
        }
    }

    /**
     * 发送快门命令并返回响应码与应答载荷。
     * 0x9128/0x9129 通常只回纯响应包，但个别固件会带回数据阶段；直接用 {@code command()}
     * 会因固定按纯响应包偏移解析而误报「无效响应」。
     * 这里复用 {@code commandWithData()}：有数据段就正常收完，
     * 只回纯响应时它抛出的响应码正好就是我们要的返回值。
     */
    private ReleaseResult releaseCommand(int operation, long[] parameters) throws Exception {
        try {
            return new ReleaseResult(
                    RESPONSE_OK, commandWithData(operation, transactionId++, parameters));
        } catch (PtpResponseException rejection) {
            return new ReleaseResult(rejection.responseCode, new byte[0]);
        }
    }

    /** 一次厂商快门命令的结果：响应码 + 应答载荷（载荷可能带新对象信息）。 */
    private static final class ReleaseResult {
        final int code;
        final byte[] payload;

        ReleaseResult(int code, byte[] payload) {
            this.code = code;
            this.payload = payload;
        }
    }

    @Override
    public String captureDiagnostics() {
        synchronized (captureSteps) {
            return captureSteps.toString();
        }
    }

    /** 记录一步厂商快门诊断（同一轮 capture 内累积，供「拍摄超时」时展示）。 */
    private void recordCaptureStep(String text) {
        synchronized (captureSteps) {
            if (captureSteps.length() > 0) captureSteps.append('\n');
            captureSteps.append(text);
        }
    }

    private static String stepText(String label, ReleaseResult result) {
        return label + " → " + hexCode(result.code) + payloadSummary(result.payload);
    }

    private static String hexCode(int code) {
        return String.format(Locale.ROOT, "0x%04X", code);
    }

    /** 应答载荷摘要：EOS 快门应答里可能带新对象信息，先记前 16 字节供定位。 */
    private static String payloadSummary(byte[] payload) {
        if (payload == null || payload.length == 0) return "（无数据段）";
        StringBuilder text = new StringBuilder("，数据 ")
                .append(payload.length).append("B：");
        for (int index = 0; index < Math.min(payload.length, 16); index++) {
            text.append(String.format(Locale.ROOT, "%02X ", payload[index]));
        }
        return text.toString();
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
        // EOS 机型的握手名称通常是「EOS RP」这类，不含 "canon"；此时也要判为 Canon，
        // 否则会去走它并不支持的标准快门 0x100E。
        if (text.contains("canon") || text.contains("eos")) return CameraVendor.CANON;
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
        InetSocketAddress endpoint = new InetSocketAddress(host, port);
        // 绑定相机 Wi‑Fi 失败时允许回退一次默认路由（见下方 isNetworkBindFailure）。
        boolean allowFallback = bindToCameraNetwork;
        SocketFactory factory = socketFactory;
        while (true) {
            Socket socket = null;
            try {
                socket = factory.createSocket();
                synchronized (transportLock) {
                    ensureTransportGenerationLocked(generation);
                    if (commandChannel) connectingCommandSocket = socket;
                    else connectingEventSocket = socket;
                }
                // 相机刚从 Wi‑Fi 休眠中醒来时 TCP 握手可能明显变慢，超时放宽到 15s。
                socket.connect(endpoint, 15000);
                ensureTransportGeneration(generation);
                socket.setSoTimeout(12000);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                ensureTransportGeneration(generation);
                // 记下真实出口：真机排查「走的是相机 Wi‑Fi 还是默认路由」全靠这两个字段。
                lastLocalEndpoint = String.valueOf(socket.getLocalSocketAddress());
                lastNetworkBinding = !bindToCameraNetwork
                        ? "系统默认路由（未绑定相机 Wi‑Fi）"
                        : (allowFallback
                                ? "相机 Wi‑Fi（已绑定）"
                                : "相机 Wi‑Fi（绑定被拒，已回退默认路由）");
                return socket;
            } catch (Exception failure) {
                if (socket != null) {
                    forgetConnectingSocket(socket, commandChannel);
                    closeQuietly(socket);
                }
                // 绑定相机 Wi‑Fi 被系统拒绝（Android 10+ 对非自有网络抛 EPERM，消息形如
                // 「Binding socket to network N failed: EPERM (Operation not permitted)」）。
                // 绑定可能发生在 createSocket() 或 connect() 内部，且异常不一定包装成
                // IOException，因此这里统一按消息判定；命中就回退默认路由再试一次：
                // 手机连着相机 Wi‑Fi 时 192.168.1.0/24 本就走该接口。其它失败原样抛出。
                if (!allowFallback || !isNetworkBindFailure(failure)) {
                    throw failure instanceof IOException
                            ? (IOException) failure
                            : new IOException(String.valueOf(failure.getMessage()), failure);
                }
                allowFallback = false;
                factory = SocketFactory.getDefault();
            }
        }
    }

    /** 连接失败后解除「连接中 socket」登记，避免 abortTransport 去关一个已废弃的 socket。 */
    private void forgetConnectingSocket(Socket socket, boolean commandChannel) {
        synchronized (transportLock) {
            if (commandChannel && connectingCommandSocket == socket) {
                connectingCommandSocket = null;
            } else if (!commandChannel && connectingEventSocket == socket) {
                connectingEventSocket = null;
            }
        }
    }

    /**
     * 是否是「绑定到相机 Wi‑Fi」被系统拒绝（Android 10+ 对应用不拥有的网络抛 EPERM）。
     * 该错误在 socket.connect() 内部抛出，消息形如
     * 「Binding socket to network 2843 failed: EPERM (Operation not permitted)」。
     */
    private static boolean isNetworkBindFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            if (message.contains("Binding socket to network")
                    || message.contains("EPERM")
                    || message.contains("Operation not permitted")) {
                return true;
            }
        }
        return false;
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
        // 不主动发 CloseSession：实测那样做会让相机随后关掉自己的 PTP/IP 监听
        // （下一次连接直接 ECONNREFUSED），且与「相机侧只保留一次会话、手机侧多次连接」
        // 的目标相反。改为保留相机会话，重连时由 OpenSession 的 SessionAlreadyOpen 复用。
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
        canonRemoteModeReady = false;
        handleCache.clear();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 相机以 InitFail(type 5) 拒绝了握手：仍持有旧会话，可由 connect() 重试。 */
    private static final class InitRejectedException extends IOException {
        InitRejectedException(String message) {
            super(message);
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
