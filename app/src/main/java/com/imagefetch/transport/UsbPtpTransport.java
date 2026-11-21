/*
 * Derived from ZENCHE (https://github.com/Tauber01/ZENCHE) native/android
 * PtpCamera.java, MIT License, Copyright (c) 2026 Tauber01.
 * See the NOTICE file in the project root.
 *
 * Changes for IMAGE_FETCH:
 *   - 只保留“枚举设备 → 授权 → PTP 会话 → 存储浏览 → 原文件流式下载 → 快门”链路；
 *   - GetObject 改为流式写入 OutputStream，不再把整文件读进 byte[]；
 *   - 移除实时取景、录像、曝光/对焦/参数读写等与取图无关的产品逻辑；
 *   - 快门沿用 ZENCHE 的 Nikon SDRAM 流程（0x9207 + DeviceReady/GetEvent），
 *     返回可直接 GetObject 的句柄；结果仍交给 PhotoTransferWorker 落盘。
 */
package com.imagefetch.transport;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.StorageBrowser;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** USB Host + PTP（ISO 15740）传输。 */
public final class UsbPtpTransport implements CameraTransport {
    /** 首次插入的 USB 授权入口；实现方（Activity）负责弹系统对话框并等待结果。 */
    public interface PermissionGate {
        boolean ensurePermission(UsbManager manager, UsbDevice device) throws Exception;
    }

    static final Set<Integer> SUPPORTED_VENDOR_IDS =
            new HashSet<>(Arrays.asList(0x04b0, 0x054c, 0x04a9));

    private static final CameraProfile[] SUPPORTED_CAMERAS = new CameraProfile[]{
            new CameraProfile("Nikon D500", "Nikon", 0x04b0, 0x043a),
            new CameraProfile("Nikon D7500", "Nikon", 0x04b0, 0x0445),
            new CameraProfile("Nikon D850", "Nikon", 0x04b0, 0x044a),
            new CameraProfile("Nikon Z7", "Nikon", 0x04b0, 0x0442),
            new CameraProfile("Nikon Z6", "Nikon", 0x04b0, 0x0443),
            new CameraProfile("Nikon Z50", "Nikon", 0x04b0, 0x0444),
            new CameraProfile("Nikon D780", "Nikon", 0x04b0, 0x0446),
            new CameraProfile("Nikon D6", "Nikon", 0x04b0, 0x0447),
            new CameraProfile("Nikon Z5", "Nikon", 0x04b0, 0x0448),
            new CameraProfile("Nikon Z7II", "Nikon", 0x04b0, 0x044b),
            new CameraProfile("Nikon Z6II", "Nikon", 0x04b0, 0x044c),
            new CameraProfile("Nikon Z fc", "Nikon", 0x04b0, 0x044f),
            new CameraProfile("Nikon Z30", "Nikon", 0x04b0, 0x0452),
            new CameraProfile("Nikon Z9", "Nikon", 0x04b0, 0x0450),
            new CameraProfile("Nikon Z8", "Nikon", 0x04b0, 0x0451),
            new CameraProfile("Nikon Z f", "Nikon", 0x04b0, 0x0453),
            new CameraProfile("Nikon Z6III", "Nikon", 0x04b0, 0x0454),
            new CameraProfile("Nikon Z50II", "Nikon", 0x04b0, 0x0455),
            new CameraProfile("Nikon Z5II", "Nikon", 0x04b0, 0x0456),
            new CameraProfile("Nikon ZR", "Nikon", 0x04b0, 0x0457),
    };

    static final String SUPPORTED_CAMERA_SUMMARY =
            "Nikon（D500/Z7/Z6/Z50/D7500/D780/D6/Z5/D850/Z7II/Z6II/Z fc/Z9/Z8/Z30/Z f/Z6III/Z50II/Z5II/ZR）"
                    + "以及 Sony α / Canon EOS 的 USB PTP 模式";

    private static final int RESPONSE_OK = 0x2001;
    private static final int RESPONSE_SESSION_ALREADY_OPEN = 0x201e;
    private static final int OPEN_SESSION = 0x1002;
    private static final int CLOSE_SESSION = 0x1003;
    private static final int GET_STORAGE_IDS = 0x1004;
    private static final int GET_STORAGE_INFO = 0x1005;
    private static final int GET_OBJECT_HANDLES = 0x1007;
    private static final int GET_OBJECT_INFO = 0x1008;
    private static final int GET_OBJECT = 0x1009;
    private static final int GET_THUMB = 0x100a;
    /** 标准 PTP 删除对象（ISO 15740）：参数为对象句柄 + objectFormatCode(0)。 */
    private static final int DELETE_OBJECT = 0x100b;
    /** Nikon 扩展：等待机身就绪 / 读取机内事件 / 拍摄到机身内存。 */
    private static final int DEVICE_READY = 0x90c8;
    private static final int GET_EVENT = 0x90c7;
    private static final int CAPTURE_TO_SDRAM = 0x9207;
    private static final int OBJECT_ADDED_IN_SDRAM = 0xc101;
    private static final int USB_REQUEST_CLEAR_FEATURE = 0x01;
    private static final int USB_FEATURE_ENDPOINT_HALT = 0x00;
    private static final int USB_RECIPIENT_INTERFACE = 0x01;
    private static final int USB_RECIPIENT_ENDPOINT = 0x02;
    private static final int PTP_USB_REQUEST_RESET = 0x66;
    private static final int CONNECT_ATTEMPTS = 3;
    /** SDRAM 句柄兜底值：部分机型不发 ObjectAdded，用约定的固定句柄。 */
    private static final long SDRAM_FALLBACK_HANDLE = 0xffff0001L;
    private static final int USB_BULK_CHUNK_BYTES =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? 64 * 1024 : 16 * 1024;

    private final Context context;
    private final PermissionGate permissionGate;

    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface cameraInterface;
    private UsbEndpoint bulkIn;
    private UsbEndpoint bulkOut;
    /**
     * 粘滞同步模式：一旦某次 UsbRequest 失败并成功降级为同步 bulkTransfer，
     * 本会话后续请求直接走 bulkTransfer，避免每条请求都先等一次异步超时。
     */
    private boolean syncTransport;
    private int transaction;
    private String cameraName = "USB/PTP Camera";
    private CameraProfile profile;
    /**
     * 存储卡分页缓存（存储卷 → 句柄，以及「仅 JPG」的筛查结果）：
     * 快速分页翻页时不必重新遍历目录，JPG 筛查结果也只算一遍。
     */
    private final StorageBrowser.PageCache pageCache = new StorageBrowser.PageCache();

    public UsbPtpTransport(Context context, PermissionGate permissionGate) {
        this.context = context.getApplicationContext();
        this.permissionGate = permissionGate;
    }

    /** host/port 对 USB 无意义，仅为满足 {@link CameraTransport} 约定；设备在枚举阶段选定。 */
    @Override
    public synchronized String connect(String host, int port) throws Exception {
        close();
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) throw new IOException("当前设备不支持 USB Host。");

        UsbDevice selected = null;
        UsbDevice unsupported = null;
        for (UsbDevice candidate : manager.getDeviceList().values()) {
            if (!SUPPORTED_VENDOR_IDS.contains(candidate.getVendorId())) continue;
            CameraProfile candidateProfile = profileFor(candidate);
            if (candidateProfile != null) {
                selected = candidate;
                profile = candidateProfile;
                break;
            }
            if (unsupported == null) unsupported = candidate;
        }
        if (selected == null) {
            if (unsupported != null) {
                throw new IOException(String.format(
                        Locale.ROOT,
                        "检测到未支持的 USB 相机设备 %04x:%04x。当前支持 %s。",
                        unsupported.getVendorId(),
                        unsupported.getProductId(),
                        SUPPORTED_CAMERA_SUMMARY));
            }
            throw new IOException("没有检测到支持的 USB 相机。请连接 " + SUPPORTED_CAMERA_SUMMARY + "。");
        }
        if (!manager.hasPermission(selected)
                && (permissionGate == null || !permissionGate.ensurePermission(manager, selected))) {
            throw new IOException("未获得 " + cameraName() + " 的 USB 访问权限。");
        }

        usbManager = manager;
        device = selected;
        cameraName = profile.name;
        transaction = 0;
        try {
            openFreshTransport(selected);
            openSessionWithRecovery();
        } catch (Exception error) {
            closeTransport();
            throw error;
        }
        return cameraName;
    }

    @Override
    public synchronized boolean isConnected() {
        return connection != null;
    }

    @Override
    public synchronized String getCameraName() {
        return cameraName;
    }

    /** USB 无 event 通道；事件发现由 NewPhotoResolver 的句柄差集承担。 */
    @Override
    public void setObjectEventListener(ObjectEventListener listener) {
        // 无操作。
    }

    @Override
    public synchronized CameraStorage.Snapshot listStorage(int maxItems) throws Exception {
        ensureConnected();
        return StorageBrowser.loadAll(this::invokeOperation, maxItems);
    }

    @Override
    public synchronized CameraStorage.Page listStoragePage(
            int pageSize, int pageIndex, boolean jpegOnly) throws Exception {
        ensureConnected();
        return StorageBrowser.loadPage(this::invokeOperation, pageSize, pageIndex, jpegOnly, pageCache);
    }

    @Override
    public synchronized CameraStorage.Page listStoragePhotoPage(int pageSize, int pageIndex)
            throws Exception {
        ensureConnected();
        return StorageBrowser.loadPhotoPage(this::invokeOperation, pageSize, pageIndex, pageCache);
    }

    @Override
    public synchronized CameraStorage.Item findCompanionJpeg(CameraStorage.Item raw)
            throws Exception {
        ensureConnected();
        return StorageBrowser.findCompanionJpeg(this::invokeOperation, raw);
    }

    @Override
    public void invalidateStorageCache() {
        pageCache.clear();
    }

    private byte[] invokeOperation(int operation, long[] parameters) throws Exception {
        int timeout = operation == StorageBrowser.GET_OBJECT_HANDLES ? 30_000 : 15_000;
        return transact(operation, parameters, null, timeout);
    }

    @Override
    public synchronized byte[] getStorageThumbnail(long handle) throws Exception {
        ensureConnected();
        return transact(GET_THUMB, new long[]{handle}, null, 30_000);
    }

    @Override
    public synchronized long downloadStorageObject(
            long handle, OutputStream output, ProgressListener progress) throws Exception {
        ensureConnected();
        if (output == null) throw new IOException("缺少下载输出流");

        int current = ++transaction;
        sendContainer(
                UsbPtpFraming.TYPE_COMMAND,
                GET_OBJECT,
                current,
                UsbPtpFraming.parameterBytes(new long[]{handle}),
                180_000);

        UsbPtpFraming.Header header = readHeader(180_000);
        checkTransaction(header, current);
        long received = 0;
        if (header.type == UsbPtpFraming.TYPE_DATA) {
            received = streamPayload(header.payloadLength(), output, progress, 180_000);
            header = readHeader(180_000);
            checkTransaction(header, current);
        }
        if (header.type != UsbPtpFraming.TYPE_RESPONSE) {
            throw new IOException(cameraName() + " 返回了无效的 PTP 数据容器。");
        }
        drainPayload(header, 180_000);
        if (header.code != RESPONSE_OK) throw new PtpResponseException(header.code);
        return received;
    }

    /**
     * 删除卡内对象（标准 {@code 0x100B DeleteObject}，参数 handle + objectFormatCode=0）。
     * 任意格式都能删，与「只下载 JPEG」的下载限制无关；非 OK 响应码由 {@code transact} 抛
     * {@link PtpResponseException} 交由上层汇报。
     */
    @Override
    public synchronized void deleteObject(long handle) throws Exception {
        ensureConnected();
        transact(DELETE_OBJECT, new long[]{handle, 0}, null, 15_000);
    }

    /**
     * 快门：沿用 ZENCHE 的 Nikon SDRAM 流程——先等机身就绪，再 CaptureToSdram，
     * 然后轮询 DeviceReady/GetEvent 取回新对象句柄；拿不到事件时退回约定句柄。
     * 原片留在机身（存储卡或内存），下载仍由 PhotoTransferWorker 完成。
     */
    @Override
    public synchronized long[] capture() throws Exception {
        ensureConnected();
        waitUntilDeviceReady(6_000);
        transact(CAPTURE_TO_SDRAM, new long[]{0xffff_ffffL, 1}, null, 60_000);

        long handle = 0;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                transact(DEVICE_READY, null, null, 3_000);
                long eventHandle = findSdramObject(transact(GET_EVENT, null, null, 3_000));
                if (eventHandle != 0) {
                    handle = eventHandle;
                    break;
                }
            } catch (Exception ignored) {
                // 机身仍在写卡：继续轮询。
            }
            Thread.sleep(180);
        }
        if (handle == 0) {
            waitUntilDeviceReady(8_000);
            handle = SDRAM_FALLBACK_HANDLE;
        }
        return new long[]{handle};
    }

    @Override
    public synchronized void assertHealthy() throws IOException {
        ensureConnected();
    }

    /** 中断在途传输并释放 USB 句柄；可从任意线程调用（不等待实例 monitor）。 */
    public void abortTransport() {
        UsbDeviceConnection active = connection;
        if (active != null) {
            try {
                active.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                transact(CLOSE_SESSION, null, null, 2_000);
            } catch (Exception ignored) {
            }
        }
        closeTransport();
        transaction = 0;
    }

    // ── 设备发现与传输打开 ──

    private void openFreshTransport(UsbDevice target) throws Exception {
        closeConnectionOnly();
        syncTransport = false;
        if (usbManager == null) throw new IOException("USB 管理器不可用。");
        if (!usbManager.hasPermission(target)
                && (permissionGate == null || !permissionGate.ensurePermission(usbManager, target))) {
            throw new IOException("未获得 " + cameraName() + " 的 USB 访问权限。");
        }

        UsbInterface selectedInterface = null;
        UsbEndpoint selectedInput = null;
        UsbEndpoint selectedOutput = null;
        for (int index = 0; index < target.getInterfaceCount(); index++) {
            UsbInterface candidate = target.getInterface(index);
            if (candidate.getInterfaceClass() != UsbConstants.USB_CLASS_STILL_IMAGE) continue;
            UsbEndpoint input = null;
            UsbEndpoint output = null;
            for (int endpointIndex = 0;
                 endpointIndex < candidate.getEndpointCount();
                 endpointIndex++) {
                UsbEndpoint endpoint = candidate.getEndpoint(endpointIndex);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) input = endpoint;
                else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) output = endpoint;
            }
            if (input != null && output != null) {
                selectedInterface = candidate;
                selectedInput = input;
                selectedOutput = output;
                break;
            }
        }
        if (selectedInterface == null) {
            throw new IOException(cameraName() + " 没有提供可用的 PTP USB 接口。");
        }

        UsbDeviceConnection opened = usbManager.openDevice(target);
        if (opened == null || !opened.claimInterface(selectedInterface, true)) {
            if (opened != null) opened.close();
            throw new IOException("无法打开 " + cameraName() + "。请关闭其它正在占用相机的应用。");
        }
        if (selectedInterface.getAlternateSetting() != 0
                && !opened.setInterface(selectedInterface)) {
            opened.releaseInterface(selectedInterface);
            opened.close();
            throw new IOException(cameraName() + " 无法切换到 PTP USB 接口。");
        }

        connection = opened;
        cameraInterface = selectedInterface;
        bulkIn = selectedInput;
        bulkOut = selectedOutput;
        clearEndpointHalt(bulkIn);
        clearEndpointHalt(bulkOut);
    }

    private void openSessionWithRecovery() throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            transaction = 0;
            try {
                if (attempt == 1) {
                    resetPtpDevice();
                    Thread.sleep(150);
                }
                transact(OPEN_SESSION, new long[]{1}, null, 10_000);
                return;
            } catch (Exception error) {
                lastError = error;
                if (attempt == CONNECT_ATTEMPTS) break;
                recoverUsbTransport(attempt);
            }
        }
        throw new IOException(
                cameraName() + " USB/PTP 会话初始化失败（已重试 " + CONNECT_ATTEMPTS + " 次）："
                        + (lastError == null ? "未知错误" : lastError.getMessage()),
                lastError);
    }

    /** 会话初始化失败后的传输重建：复位设备、重开接口、重新枚举同一机型。 */
    private void recoverUsbTransport(int failedAttempt) throws Exception {
        resetPtpDevice();
        closeConnectionOnly();
        Thread.sleep(350L * failedAttempt);

        UsbDevice refreshed = waitForCurrentCamera(3_000);
        if (refreshed == null) {
            throw new IOException(cameraName() + " 在 USB 恢复期间断开，请重新连接数据线。");
        }
        Exception openError = null;
        long deadline = System.currentTimeMillis() + 3_000;
        do {
            try {
                openFreshTransport(refreshed);
                openError = null;
                break;
            } catch (Exception error) {
                openError = error;
                Thread.sleep(200);
                UsbDevice again = waitForCurrentCamera(400);
                if (again != null) refreshed = again;
            }
        } while (System.currentTimeMillis() < deadline);
        if (openError != null) {
            throw new IOException(
                    cameraName() + " USB 传输重建失败：" + openError.getMessage(), openError);
        }
        Thread.sleep(150);
    }

    private UsbDevice waitForCurrentCamera(long timeoutMillis) throws InterruptedException {
        if (usbManager == null || device == null) return null;
        int productId = device.getProductId();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        do {
            for (UsbDevice candidate : usbManager.getDeviceList().values()) {
                if (SUPPORTED_VENDOR_IDS.contains(candidate.getVendorId())
                        && candidate.getProductId() == productId) {
                    return candidate;
                }
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private void closeTransport() {
        closeConnectionOnly();
        usbManager = null;
        device = null;
        profile = null;
        pageCache.clear();
    }

    private void closeConnectionOnly() {
        syncTransport = false;
        if (connection != null) {
            if (cameraInterface != null) {
                try {
                    connection.releaseInterface(cameraInterface);
                } catch (RuntimeException ignored) {
                }
            }
            try {
                connection.close();
            } catch (RuntimeException ignored) {
            }
        }
        connection = null;
        cameraInterface = null;
        bulkIn = null;
        bulkOut = null;
    }

    // ── PTP 事务 ──

    /**
     * 缓冲式事务（用于数据集类小载荷）。数据阶段一次性读入内存；
     * 大文件请用 {@link #downloadStorageObject}。
     */
    private byte[] transact(int operation, long[] params, byte[] outgoingData, int timeout)
            throws Exception {
        ensureConnectedForOperation(operation);
        int current;
        if (operation == OPEN_SESSION) {
            transaction = 0;
            current = 0;
        } else {
            current = ++transaction;
        }
        sendContainer(
                UsbPtpFraming.TYPE_COMMAND,
                operation,
                current,
                UsbPtpFraming.parameterBytes(params),
                timeout);
        if (outgoingData != null) {
            sendContainer(UsbPtpFraming.TYPE_DATA, operation, current, outgoingData, timeout);
        }

        UsbPtpFraming.Header header = readHeader(timeout);
        byte[] data = new byte[0];
        UsbPtpFraming.Header response = header;
        if (header.type == UsbPtpFraming.TYPE_DATA) {
            data = readPayload(header.payloadLength(), timeout);
            response = readHeader(timeout);
        }
        checkTransaction(header, current);
        checkTransaction(response, current);
        if (response.type != UsbPtpFraming.TYPE_RESPONSE) {
            throw new IOException(cameraName() + " 返回了无效的 PTP 数据容器。");
        }
        drainPayload(response, timeout);
        if (response.code != RESPONSE_OK
                && !(operation == OPEN_SESSION && response.code == RESPONSE_SESSION_ALREADY_OPEN)) {
            throw new PtpResponseException(response.code);
        }
        return data;
    }

    private void sendContainer(int type, int code, int current, byte[] payload, int timeout)
            throws Exception {
        byte[] bytes = UsbPtpFraming.packet(type, code, current, payload);
        int offset = 0;
        boolean recovered = false;
        while (offset < bytes.length) {
            int requested = Math.min(USB_BULK_CHUNK_BYTES, bytes.length - offset);
            int sent;
            try {
                sent = transferWithUsbRequest(bulkOut, bytes, offset, requested, timeout);
            } catch (Exception error) {
                if (!recovered && clearEndpointHalt(bulkOut)) {
                    recovered = true;
                    continue;
                }
                throw new IOException(String.format(
                        Locale.ROOT,
                        "向 %s 发送 USB 数据失败（端点 0x%02X）：%s",
                        cameraName(),
                        bulkOut.getAddress(),
                        error.getMessage()), error);
            }
            if (sent <= 0) {
                if (!recovered && clearEndpointHalt(bulkOut)) {
                    recovered = true;
                    continue;
                }
                throw new IOException(String.format(
                        Locale.ROOT,
                        "向 %s 发送 USB 数据失败（端点 0x%02X，返回 %d）。",
                        cameraName(),
                        bulkOut.getAddress(),
                        sent));
            }
            offset += sent;
        }
    }

    private UsbPtpFraming.Header readHeader(int timeout) throws Exception {
        byte[] header = new byte[UsbPtpFraming.HEADER_BYTES];
        readFully(header, 0, header.length, timeout, "容器头");
        return UsbPtpFraming.parseHeader(header);
    }

    private byte[] readPayload(int length, int timeout) throws Exception {
        byte[] payload = new byte[length];
        readFully(payload, 0, length, timeout, "数据段");
        return payload;
    }

    private void drainPayload(UsbPtpFraming.Header header, int timeout) throws Exception {
        int extra = header.payloadLength();
        if (extra > 0) readFully(new byte[extra], 0, extra, timeout, "响应载荷");
    }

    private long streamPayload(
            int payloadLength, OutputStream output, ProgressListener progress, int timeout)
            throws Exception {
        byte[] chunk = new byte[Math.min(USB_BULK_CHUNK_BYTES, Math.max(1, payloadLength))];
        int remaining = payloadLength;
        long sent = 0;
        while (remaining > 0) {
            int requested = Math.min(chunk.length, remaining);
            readFully(chunk, 0, requested, timeout, "文件数据");
            output.write(chunk, 0, requested);
            remaining -= requested;
            sent += requested;
            if (progress != null) progress.onProgress(sent, payloadLength);
        }
        return sent;
    }

    /** 读到恰好 length 字节；中途端点 stall 时清一次 halt 再继续。 */
    private void readFully(byte[] target, int offset, int length, int timeout, String label)
            throws Exception {
        int received = 0;
        boolean recovered = false;
        while (received < length) {
            int requested = length - received;
            int count;
            try {
                count = transferWithUsbRequest(
                        bulkIn, target, offset + received, requested, timeout);
            } catch (Exception error) {
                if (!recovered && clearEndpointHalt(bulkIn)) {
                    recovered = true;
                    continue;
                }
                throw new IOException(String.format(
                        Locale.ROOT,
                        "读取 %s USB 数据失败（%s，端点 0x%02X，请求 %d 字节）：%s",
                        cameraName(),
                        label,
                        bulkIn.getAddress(),
                        requested,
                        error.getMessage()), error);
            }
            if (count <= 0) {
                if (!recovered && clearEndpointHalt(bulkIn)) {
                    recovered = true;
                    continue;
                }
                throw new IOException(String.format(
                        Locale.ROOT,
                        "读取 %s USB 数据失败（%s，端点 0x%02X，返回 %d）。",
                        cameraName(),
                        label,
                        bulkIn.getAddress(),
                        count));
            }
            received += count;
        }
    }

    private void checkTransaction(UsbPtpFraming.Header header, int expected) throws IOException {
        if (header.transaction != expected) {
            throw new IOException(String.format(
                    Locale.ROOT,
                    "%s 返回了不匹配的 PTP 事务编号（期望 %d，收到 %d）。",
                    cameraName(),
                    expected,
                    header.transaction));
        }
    }

    // ── USB 端点传输（UsbRequest 优先，失败降级同步 bulkTransfer） ──

    private int transferWithUsbRequest(
            UsbEndpoint endpoint, byte[] bytes, int offset, int length, int timeout)
            throws Exception {
        UsbDeviceConnection active = connection;
        if (active == null || endpoint == null) throw new IOException("USB 传输尚未打开。");

        boolean input = endpoint.getDirection() == UsbConstants.USB_DIR_IN;
        if (syncTransport) {
            // 本会话已锁定同步模式：不再重复等待异步超时，也不额外发 control transfer。
            return transferViaBulkTransfer(active, endpoint, bytes, offset, length, timeout, input);
        }
        try {
            return transferViaUsbRequest(active, endpoint, bytes, offset, length, timeout, input);
        } catch (Exception asyncError) {
            if (!isAsyncDegradable(asyncError)) throw asyncError;
            try {
                clearEndpointHalt(endpoint);
                int result = transferViaBulkTransfer(
                        active, endpoint, bytes, offset, length, timeout, input);
                // 降级成功后锁定同步模式，后续请求不必再付异步超时代价。
                syncTransport = true;
                return result;
            } catch (Exception syncError) {
                // 降级本身也失败：不锁定，留给上层重开会话。
                throw asyncError;
            }
        }
    }

    /**
     * 只有四类已知异步失败可安全降级：异步超时、未返回完成结果、
     * 无法初始化/提交异步请求。其它异步错误必须原样上抛。
     */
    private static boolean isAsyncDegradable(Exception error) {
        if (error == null || error.getMessage() == null) return false;
        String message = error.getMessage();
        return message.contains("超时")
                || message.contains("未返回有效的完成结果")
                || message.contains("无法初始化异步 USB 请求")
                || message.contains("无法提交异步 USB 请求");
    }

    private int transferViaUsbRequest(
            UsbDeviceConnection active,
            UsbEndpoint endpoint,
            byte[] bytes,
            int offset,
            int length,
            int timeout,
            boolean input) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocateDirect(length);
        if (!input) {
            buffer.put(bytes, offset, length);
            buffer.flip();
        }

        UsbRequest request = new UsbRequest();
        boolean queued = false;
        boolean completed = false;
        try {
            if (!request.initialize(active, endpoint)) {
                throw new IOException("无法初始化异步 USB 请求。");
            }
            if (!request.queue(buffer)) {
                throw new IOException("无法提交异步 USB 请求。");
            }
            queued = true;

            UsbRequest finished;
            try {
                finished = active.requestWait(timeout);
            } catch (TimeoutException error) {
                throw new IOException("等待异步 USB 请求 " + timeout + " 毫秒后超时。", error);
            }
            if (finished != request) {
                throw new IOException("异步 USB 请求未返回有效的完成结果。");
            }
            completed = true;

            int transferred = buffer.position();
            if (input && transferred > 0) {
                buffer.flip();
                buffer.get(bytes, offset, transferred);
            }
            return transferred;
        } finally {
            if (queued && !completed) {
                try {
                    request.cancel();
                    UsbRequest cancelled = active.requestWait(750);
                    completed = cancelled == request;
                } catch (RuntimeException ignored) {
                } catch (TimeoutException ignored) {
                }
            }
            request.close();
        }
    }

    private int transferViaBulkTransfer(
            UsbDeviceConnection active,
            UsbEndpoint endpoint,
            byte[] bytes,
            int offset,
            int length,
            int timeout,
            boolean input) {
        if (input) {
            byte[] buffer = new byte[length];
            int transferred = active.bulkTransfer(endpoint, buffer, length, timeout);
            if (transferred < 0) {
                throw new RuntimeException("同步 bulkTransfer 读取失败，错误码=" + transferred);
            }
            System.arraycopy(buffer, 0, bytes, offset, transferred);
            return transferred;
        }
        byte[] chunk = new byte[length];
        System.arraycopy(bytes, offset, chunk, 0, length);
        int transferred = active.bulkTransfer(endpoint, chunk, length, timeout);
        if (transferred < 0) {
            throw new RuntimeException("同步 bulkTransfer 写入失败，错误码=" + transferred);
        }
        return transferred;
    }

    private boolean clearEndpointHalt(UsbEndpoint endpoint) {
        UsbDeviceConnection active = connection;
        if (active == null || endpoint == null) return false;
        int requestType = UsbConstants.USB_DIR_OUT
                | UsbConstants.USB_TYPE_STANDARD
                | USB_RECIPIENT_ENDPOINT;
        return active.controlTransfer(
                requestType,
                USB_REQUEST_CLEAR_FEATURE,
                USB_FEATURE_ENDPOINT_HALT,
                endpoint.getAddress(),
                null,
                0,
                1_000) >= 0;
    }

    /** PTP_USB_REQUEST_RESET(0x66)：复位 PTP 状态机，清掉残留事务。 */
    private boolean resetPtpDevice() {
        UsbDeviceConnection active = connection;
        if (active == null || cameraInterface == null) return false;
        int requestType = UsbConstants.USB_DIR_OUT
                | UsbConstants.USB_TYPE_CLASS
                | USB_RECIPIENT_INTERFACE;
        return active.controlTransfer(
                requestType,
                PTP_USB_REQUEST_RESET,
                0,
                cameraInterface.getId(),
                null,
                0,
                2_000) >= 0;
    }

    private void waitUntilDeviceReady(long timeoutMillis) throws Exception {
        Exception finalError = null;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        do {
            try {
                transact(DEVICE_READY, null, null, 3_000);
                return;
            } catch (Exception error) {
                finalError = error;
                Thread.sleep(180);
            }
        } while (System.currentTimeMillis() < deadline);
        throw new IOException(cameraName() + " 在独占操作后未恢复就绪状态。", finalError);
    }

    /** 机内事件流的第一个 OBJECT_ADDED_IN_SDRAM(0xC101) 句柄。 */
    private static long findSdramObject(byte[] events) {
        if (events == null || events.length < 2) return 0;
        ByteBuffer buffer = ByteBuffer.wrap(events).order(ByteOrder.LITTLE_ENDIAN);
        int count = buffer.getShort() & 0xffff;
        for (int index = 0; index < count && buffer.remaining() >= 6; index++) {
            int code = buffer.getShort() & 0xffff;
            long handle = buffer.getInt() & 0xffff_ffffL;
            if (code == OBJECT_ADDED_IN_SDRAM) return handle;
        }
        return 0;
    }

    private void ensureConnected() throws IOException {
        if (connection == null) throw new IOException("请先连接 USB 相机。");
    }

    private void ensureConnectedForOperation(int operation) throws IOException {
        if (operation == OPEN_SESSION) {
            if (connection == null) throw new IOException("无法打开 USB 相机连接。");
        } else {
            ensureConnected();
        }
    }

    private String cameraName() {
        return profile == null ? "相机" : profile.name;
    }

    private static CameraProfile profileFor(UsbDevice device) {
        for (CameraProfile candidate : SUPPORTED_CAMERAS) {
            if (candidate.vendorId == device.getVendorId()
                    && candidate.productId != 0
                    && candidate.productId == device.getProductId()) {
                return candidate;
            }
        }
        return vendorFallbackProfile(device.getVendorId());
    }

    /**
     * Sony / Canon 的 Product ID 随固件代际变化，不逐一硬编码；
     * 只要有稳定影像类接口就用厂商通用名，避免猜错具体型号。
     */
    private static CameraProfile vendorFallbackProfile(int vendorId) {
        if (vendorId == 0x054c) return new CameraProfile("Sony α USB/PTP", "Sony", vendorId, 0);
        if (vendorId == 0x04a9) return new CameraProfile("Canon EOS USB/PTP", "Canon", vendorId, 0);
        return null;
    }

    private static final class CameraProfile {
        final String name;
        final String vendorName;
        final int vendorId;
        final int productId;

        CameraProfile(String name, String vendorName, int vendorId, int productId) {
            this.name = name;
            this.vendorName = vendorName;
            this.vendorId = vendorId;
            this.productId = productId;
        }
    }
}
