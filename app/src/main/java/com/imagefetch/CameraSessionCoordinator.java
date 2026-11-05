/*
 * Wi‑Fi 网络绑定、心跳保活与指数退避重连逻辑移植自 ZENCHE
 * (https://github.com/Tauber01/ZENCHE) native/android MainActivity.java，
 * MIT License, Copyright (c) 2026 Tauber01. See the NOTICE file.
 */
package com.imagefetch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.imagefetch.capture.CaptureWaitPolicy;
import com.imagefetch.ptp.AfMethodChoices;
import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.CanonExposure;
import com.imagefetch.ptp.CanonImageQuality;
import com.imagefetch.ptp.CapabilityReport;
import com.imagefetch.ptp.LiveViewSize;
import com.imagefetch.ptp.PtpOperationService;
import com.imagefetch.ptp.SensorPointMapper;
import com.imagefetch.ptp.VendorProperty;
import com.imagefetch.ptp.VendorPropertySnapshot;
import com.imagefetch.ptp.WhiteBalanceChoices;
import com.imagefetch.storage.GalleryExporter;
import com.imagefetch.storage.JpegOrientation;
import com.imagefetch.storage.PhotoRepository;
import com.imagefetch.transfer.HeartbeatMonitor;
import com.imagefetch.transfer.NewPhotoResolver;
import com.imagefetch.transfer.PhotoTransferWorker;
import com.imagefetch.transfer.ReconnectPolicy;
import com.imagefetch.transfer.TransferControl;
import com.imagefetch.transport.CameraTransport;
import com.imagefetch.transport.PtpIpTransport;
import com.imagefetch.transport.PtpResponseException;
import com.imagefetch.transport.UsbPtpTransport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话编排：连接（含相机 Wi‑Fi 精确路由）→ 拍摄 → 发现新对象 → 流式取回 → 落盘去重；
 * 并负责 event 通道心跳保活与连续失败后的指数退避自动重连。
 * 所有相机 IO 都在单线程 executor 上串行执行，UI 回调切回主线程。
 */
public final class CameraSessionCoordinator {
    /** 传输方式：Wi‑Fi/PTP‑IP 或 USB Host/PTP。 */
    public enum TransportKind {
        WIFI,
        USB
    }

    /**
     * 连接保活的三态（M2）。刻意做成<b>派生值</b>而不是独立字段：真正的状态仍由
     * {@code connected}/{@code reconnecting}/{@code manualDisconnect}/{@code suspended}
     * 承载，避免出现两份互相打架的真相。
     *
     * <ul>
     *   <li>{@link #HOLD}：应用在前台，心跳正常保活。</li>
     *   <li>{@link #SUSPEND}：应用退到后台，停掉心跳探测省电，但保留 TCP socket
     *       与相机侧会话；回到前台立刻补一次探测。</li>
     *   <li>{@link #RELEASE}：用户手动断开或退出应用，彻底释放。</li>
     * </ul>
     */
    public enum HoldState {
        HOLD,
        SUSPEND,
        RELEASE
    }

    /**
     * HOLD 状态推导（纯函数，便于契约测试）。
     * {@code manualDisconnect} 优先级最高；既没连上也不在重连 → 视为已释放。
     */
    static HoldState holdStateOf(
            boolean connected, boolean reconnecting, boolean manualDisconnect, boolean suspended) {
        if (manualDisconnect || (!connected && !reconnecting)) return HoldState.RELEASE;
        return suspended ? HoldState.SUSPEND : HoldState.HOLD;
    }

    public interface Listener {
        void onStatus(String message);

        void onConnected(String cameraName);

        /** 链路丢失、自动重连进行中（attempt 从 1 开始，delayMillis 为本次退避）。 */
        void onReconnecting(int attempt, long delayMillis);

        void onDisconnected();

        void onProgress(long received, long total);

        /** note 为相册导出结果（含失败原因）；界面上必须与文件名一起显示，否则会被覆盖掉。 */
        void onFileSaved(File file, long bytes, long elapsedMillis, String note);

        /** 一批下载结束（含失败/取消）：界面据此清掉图片栏里已下载图片的选中状态。 */
        void onDownloadFinished();

        /**
         * 一批删除结束：{@code deleted} 为成功数、{@code failed} 为失败数，
         * {@code failedNames} 是失败文件与其原因（多行，无失败时为空串）。
         * 界面据此恢复按钮、清掉选中并重读当前页。给默认空实现，便于既有实现/测试桩不必改动。
         */
        default void onDeleteFinished(int deleted, int failed, String failedNames) {
        }

        /** 实时取景的一帧（已解码）；界面替换画面时应回收上一帧。 */
        void onLiveViewFrame(Bitmap frame);

        /** 实时取景状态（已开启/已关闭/中断原因）。 */
        void onLiveViewStatus(String message);

        /**
         * 曝光参数结果：读取或写入成功时 {@code index >= 0}、{@code display} 为文案；
         * 失败时 {@code index < 0}、{@code error} 为原因。
         */
        void onExposureResult(CanonExposure.Setting setting, int index, String display, String error);

        /**
         * 某档相机上报的可用取值（来自 0xC18A 允许值列表，已按 {@link CanonExposure#restrict}
         * 收窄）。读取该档时先于 {@link #onExposureResult} 触发；界面据此换掉档位列表。
         * 给默认空实现，便于既有实现/测试桩不必改动。
         */
        default void onExposureChoices(
                CanonExposure.Setting setting, List<CanonExposure.Entry> choices) {
        }

        /**
         * 对焦方式（0xD1BA）的可选值与当前值：{@code values} 为下拉框选项（相机顺序，
         * 相机没给允许列表时为候选全表），{@code currentIndex} 为当前值在其中的下标（未知为 -1）；
         * {@code fromCamera} 表示 {@code values} 是否来自相机上报的允许列表；失败时
         * {@code values} 为 null、{@code error} 为原因。给默认空实现。
         */
        default void onFocusAreaResult(int[] values, int currentIndex, boolean fromCamera, String error) {
        }

        /**
         * 白平衡（0xD109）的可选值与当前值：语义与 {@link #onFocusAreaResult} 相同，
         * 但 {@code values} 是机上编码（界面直接回传编码写入，不经过下标）。
         */
        default void onWhiteBalanceResult(int[] values, int currentIndex, boolean fromCamera, String error) {
        }

        /**
         * 白平衡写入结果：{@code success} 为真时相机受理了（响应码 {@code 0x2001}），
         * {@code value} 是写入的机上编码值，失败时 {@code error} 为原因。
         */
        default void onWhiteBalanceWritten(boolean success, int value, String error) {
        }

        /**
         * 色温（0xD10A）当前值：成功时 {@code kelvin} 为相机上报的开尔文值、{@code error} 为 null；
         * 失败时 {@code kelvin} 为 -1、{@code error} 为原因。
         */
        default void onColorTemperatureResult(long kelvin, String error) {
        }

        /**
         * 色温写入结果：{@code success} 为真时相机受理了，{@code kelvin} 是写入的 K 值，
         * 失败时 {@code error} 为原因。
         */
        default void onColorTemperatureWritten(boolean success, int kelvin, String error) {
        }

        /**
         * P0 能力探测结果：成功时 {@code report} 非空、{@code error} 为 null；失败时相反。
         * 给默认空实现，便于既有实现/测试桩不必改动。
         */
        default void onCapabilityReport(CapabilityReport report, String error) {
        }

        /**
         * 厂商属性快照结果（差分法用）：成功时 {@code snapshot} 非空、{@code error} 为 null；失败时相反。
         */
        default void onVendorPropertySnapshot(VendorPropertySnapshot snapshot, String error) {
        }

        /**
         * 厂商属性值区域读取结果（{@code propertyCode} 见 {@link VendorProperty}）：
         * 成功时 {@code block} 非空、{@code error} 为 null；失败时相反。
         */
        default void onVendorPropertyBlockRead(int propertyCode, byte[] block, String error) {
        }

        /**
         * 厂商属性值区域写入结果：{@code code} 是相机响应码（{@code 0x2001} 为成功）。
         * 传输层抛异常时 {@code code} 为 0、{@code error} 非 null。
         */
        default void onVendorPropertyBlockWritten(int propertyCode, int code, String error) {
        }

        /**
         * 画质（{@code 0xD120}）下拉框内容：{@code options} 为可选画质（保持相机给的顺序，
         * 每项带显示名与写回用的原始字节），{@code currentIndex} 为当前值在其中的下标（未知为 -1），
         * {@code currentHasJpeg} 表示当前画质是否会在卡上产生 JPEG，{@code fromCamera} 表示列表是否
         * 来自相机上报；相机没给列表时 {@code options} 为 null（界面显示不可用占位，不去发明候选值）。
         */
        default void onImageQualityResult(
                List<CanonImageQuality.Option> options, int currentIndex,
                boolean currentHasJpeg, boolean fromCamera, String error) {
        }

        /**
         * 画质写入结果：{@code success} 为真时相机受理了（响应码 {@code 0x2001}），
         * {@code label} 是写入的选项名（可能为 null），失败时 {@code error} 为原因。
         */
        default void onImageQualityWritten(boolean success, String label, String error) {
        }

        /**
         * 「恢复机身参数到基线」的结果：{@code total} 是需要写回的属性数（仅算确实与基线不同的），
         * {@code written} 是其中写成功的个数，{@code failedCodes} 是失败属性码的清单（空串表示全成功）。
         */
        default void onVendorPropertyRestoreFinished(int total, int written, String failedCodes) {
        }

        /**
         * 一次「拍摄并取回」流程结束（含未取到 JPEG 与失败）。
         * 「未发现 JPEG」路径既不落盘也不报错，只发 onStatus，
         * 单靠 onFileSaved/onError 会把拍摄按钮永久留在禁用态。
         */
        void onCaptureFinished();

        /** 一次自动对焦流程结束（成功或失败）；界面据此恢复「自动对焦」按钮。 */
        void onAutofocusFinished();

        void onError(String message, Throwable error);
    }

    /** 单张拍摄的收图轮询间隔；多张批次见 {@link #CAPTURE_BATCH_POLL_MS}。 */
    private static final long CAPTURE_POLL_INTERVAL_MS = 1000L;
    /** 多张批次用更细的轮询粒度：每张之间只隔几百毫秒，1 s 的粒度容易错过写卡完成时刻。 */
    private static final long CAPTURE_BATCH_POLL_MS = 500L;
    /** 胶片模拟页的图片来源分页大小：只给下拉框用，不需要跟主界面的每页张数一致。 */
    private static final int FILM_PAGE_SIZE = 10;
    /** Canon EOS 对焦方式属性码（0xD1BA EOS_LvAfSystem），与 transport 侧一致。 */
    private static final int CANON_PROP_AF_METHOD = 0xd1ba;
    /** 厂商属性写入被相机受理的成功响应码（标准 PTP ResponseOK）。 */
    private static final int PROPERTY_WRITE_OK = 0x2001;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final PtpIpTransport wifiTransport = new PtpIpTransport();
    private final UsbPtpTransport usbTransport;
    /** 当前会话的传输实现；连接前为 null。 */
    private volatile CameraTransport transport;
    private volatile PtpOperationService operations;
    private final PhotoRepository repository;
    private final GalleryExporter galleryExporter;
    /** 落盘后是否再导出到系统相册（默认开；关闭时只保留应用内副本）。 */
    private volatile boolean exportToGallery = true;
    /** 导出相册后是否保留应用内副本（默认不保留）。对焦包围批次要留给景深合成读，才置为真。 */
    private volatile boolean retainLocalCopiesForStack;
    /**
     * 本批「已确认导出到相册」的应用内副本。景深合成用完后可以安全删掉它们——相册里有原件。
     * <p>只记成功导出的：导出关掉或失败时，应用内副本是唯一一份，绝不能删。
     */
    private final List<File> stackExportedCopies = Collections.synchronizedList(new ArrayList<>());
    /** 每页张数（快速分页与完整扫描共用）；由设置界面调整。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 500;
    /** 放大预览的原图解码目标边长（像素）。 */
    private static final int PREVIEW_MAX_PX = 1600;
    /** 拍摄前基线只需覆盖最新的若干张，不必整卡枚举。 */
    private static final int CAPTURE_BASELINE_ITEMS = 60;
    /** 实时取景取帧间隔（约 10fps，与 libgphoto2 的取景循环同量级）。 */
    private static final long LIVE_VIEW_FRAME_INTERVAL_MS = 100L;
    /** 相机「这一帧还没准备好」时的退避间隔。 */
    private static final long LIVE_VIEW_RETRY_INTERVAL_MS = 300L;
    /** 取景被「拍摄序列」挂起时的轮询间隔：只检查标志位，不碰相机。 */
    private static final long LIVE_VIEW_SUSPEND_POLL_MS = 50L;
    /**
     * 取景开启序列的失败重试次数/退避步长。实测相机（刚连上或刚拍完时）会拒绝 EVF 属性写入，
     * 重试几次才真正切进取景；早先只试一次，失败后面板就一直黑着，非拍一张不能恢复。
     */
    private static final int LIVE_VIEW_ENABLE_ATTEMPTS = 5;
    private static final long LIVE_VIEW_ENABLE_BACKOFF_MS = 400L;
    /** 连续多少次取帧抛异常才判定取景真的断了（单次异常多为相机忙，重试即可）。 */
    private static final int LIVE_VIEW_MAX_CONSECUTIVE_ERRORS = 3;
    /**
     * 这么久一帧都拿不到就重发一次开启序列：相机可能对写入回了 OK 却没真正切到取景输出，
     * 只靠重复取帧永远不会出画面。
     */
    private static final long LIVE_VIEW_STALL_RESTART_MS = 4000L;
    /** 相机不回 PONG 时的保活周期：真实命令往返比 PING 重，放慢到 30s。 */
    private static final long COMMAND_PROBE_INTERVAL_MS = 30000L;
    /** 传输结束后的冷却期：相机可能仍在写卡，期间不做保活判定。 */
    private static final long TRANSFER_COOLDOWN_MS = 10000L;
    /** 等待用户在弹出的系统对话框里加入相机 Wi‑Fi 的上限（含用户取消/密码错）。 */
    private static final long SPECIFIER_TIMEOUT_MS = 45_000L;
    /** 最近一次传输结束时刻（含失败），用于心跳冷却。 */
    private volatile long lastTransferFinishedAt;
    /** 完整扫描结果缓存：同一会话内翻页复用，避免每页重扫整卡。 */
    private volatile List<CameraStorage.Item> fullScanItems;
    private volatile long thumbnailGeneration;
    private volatile long previewGeneration;
    /** 最近一次能力探测结果（P0）；未探测或失败时为 null。 */
    private volatile CapabilityReport lastCapabilityReport;
    private volatile int pageSize = DEFAULT_PAGE_SIZE;

    /** 缩略图缓存：按张数计，避免来回滚动时重复向相机取图。 */
    private final LruCache<Long, byte[]> thumbnailCache =
            new LruCache<Long, byte[]>(64) {
                @Override protected int sizeOf(Long key, byte[] value) {
                    return 1;
                }
            };

    /** 原图预览缓存：按字节数计（一张解码后的原图可达数 MB）。 */
    private final LruCache<Long, Bitmap> previewCache;
    private final File previewCacheDir;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback wifiNetworkCallback;
    /** WifiNetworkSpecifier 的请求句柄：与 wifiNetworkCallback 互斥，两者不同时注册。 */
    private volatile ConnectivityManager.NetworkCallback specifierNetworkCallback;
    /** 等待系统对话框结果的闸门；executor 线程阻塞在此，回调在主线程放行。 */
    private volatile CountDownLatch specifierLatch;
    /** specifier 回调加入成功时写入的网络；未加入时为 null。 */
    private final AtomicReference<Network> specifierResult = new AtomicReference<>();
    private Network cameraWifiNetwork;
    private Network sessionNetwork;
    /** 相机 Wi‑Fi 名称/密码；SSID 为空表示不改网络，沿用系统已连的那条。 */
    private volatile String cameraSsid = "";
    private volatile String cameraPassphrase = "";
    /** 会话期间持有：避免息屏/待机时 Wi‑Fi 无线电休眠导致传输中断。 */
    private final WifiManager wifiManager;
    private WifiManager.WifiLock wifiLock;

    private volatile long connectionGeneration;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean reconnecting;
    private volatile boolean manualDisconnect;
    /** 应用已退到后台：停心跳探测但保留链路与传输（见 {@link HoldState#SUSPEND}）。 */
    private volatile boolean suspended;
    private final HeartbeatMonitor heartbeat =
            new HeartbeatMonitor(ReconnectPolicy.OFFLINE_THRESHOLD);
    private volatile int reconnectAttempt;
    private volatile String cameraName = "";
    private volatile String cameraHost = "";
    private volatile int cameraPort = 15740;

    private volatile NewPhotoResolver activeResolver;
    private volatile TransferControl control;
    /** 实时取景是否正在取帧；断开/关闭时置 false。 */
    private volatile boolean liveViewActive;
    /** 取景循环代际：每次开/关都自增，让在途的取帧任务自然退出。 */
    private volatile long liveViewGeneration;
    /** 当前取景画面尺寸（相机端推流分辨率）；由面板下拉框切换。 */
    private volatile LiveViewSize liveViewSize = LiveViewSize.LARGE;
    /** 最近一帧取景画面解出来的像素宽/高（0 表示还没解出）；用于把点选坐标换算成感光器像素。 */
    private volatile int liveViewFrameWidth;
    private volatile int liveViewFrameHeight;
    /** 正在跑的取景循环；拍摄期间会暂时断开取景，拍完用它重置「无画面」计时，避免误报停滞。 */
    private volatile LiveViewPump activePump;
    /**
     * 拍摄序列进行中：取帧先让开，别在暂停取景与按快门之间往相机插帧请求。
     * 取帧循环本身跑在独占线程上（见 {@link #startLiveView}），刻意不复用
     * {@link #executor}——那条单线程还要跑界面操作与拍摄，取帧任务一旦排在前面，
     * 每次点击都得先等一帧拉完，这就是「面板打开时整体都卡」的根因。
     */
    private volatile boolean liveViewSuspended;
    /** 相册导出失败是否已提示过（每次拍摄/下载开始时复位）。 */
    private volatile boolean exportFailureReported;
    /** 最近一次手动断开时相机侧会话的释放结果（诊断用，会随下一次连接失败一起展示）。 */
    private volatile String lastSessionRelease = "";

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override public void run() {
            // suspended：应用在后台，本 tick 不探测也不重排 → 后台零保活流量。
            if (!connected || reconnecting || manualDisconnect || suspended) return;
            final CameraTransport active = transport;
            if (active == null || !active.supportsHeartbeat()) return;
            if (!(active instanceof PtpIpTransport)) return;
            final PtpIpTransport ptpIp = (PtpIpTransport) active;
            // 拍摄/传输进行中，以及刚传完的冷却期：本 tick 跳过，避免保活探测与会话事务相互等待
            // （相机写完卡前响应变慢属正常，此时判活容易误报离线）。
            if (control != null
                    || System.currentTimeMillis() - lastTransferFinishedAt
                            < TRANSFER_COOLDOWN_MS) {
                startHeartbeat();
                return;
            }
            final long heartbeatGeneration = connectionGeneration;
            executor.submit(() -> {
                boolean ok = false;
                try {
                    ptpIp.probe();
                    ok = true;
                } catch (Exception ignored) {
                    // 心跳失败：计入连续失败计数。
                }
                final boolean alive = ok;
                mainHandler.post(() -> {
                    // suspended：挂起瞬间可能还有一帧在途的探测结果，此时不能据此判离线。
                    if (!connected || manualDisconnect || suspended
                            || heartbeatGeneration != connectionGeneration) return;
                    if (heartbeat.recordProbeResult(alive)) {
                        enterReconnecting();
                        return;
                    }
                    // 相机不回 PONG 时走真实命令探测，代价高，周期放长。
                    startHeartbeat(ptpIp.pingKeepAliveSupported()
                            ? ReconnectPolicy.HEARTBEAT_INTERVAL_MS
                            : COMMAND_PROBE_INTERVAL_MS);
                });
            });
        }
    };

    private final Runnable reconnectRunnable = new Runnable() {
        @Override public void run() {
            if (!reconnecting || manualDisconnect) return;
            connecting = true;
            final long generation = ++connectionGeneration;
            executor.submit(() -> {
                try {
                    CameraTransport active = transport;
                    if (active instanceof PtpIpTransport) {
                        bindWifiNetwork((PtpIpTransport) active, generation, true);
                        if (!connectAttemptActive(generation, true)) {
                            closeQuietly(active);
                            return;
                        }
                    }
                    String name = active.connect(cameraHost, cameraPort);
                    if (!connectAttemptActive(generation, true)) {
                        closeQuietly(active);
                        return;
                    }
                    active.assertHealthy();
                    if (!connectAttemptActive(generation, true)) {
                        closeQuietly(active);
                        return;
                    }
                    mainHandler.post(() -> {
                        if (!connectAttemptActive(generation, true)) return;
                        reconnecting = false;
                        connecting = false;
                        connected = true;
                        cameraName = name;
                        reconnectAttempt = 0;
                        listener.onStatus("已自动重连 · " + name);
                        listener.onConnected(name);
                        acquireWifiLock();
                        startHeartbeat();
                    });
                } catch (Exception error) {
                    CameraTransport active = transport;
                    closeQuietly(active);
                    if (!connectAttemptActive(generation, true)) return;
                    mainHandler.post(() -> {
                        if (!connectAttemptActive(generation, true)) return;
                        connecting = false;
                        scheduleReconnect();
                    });
                }
            });
        }
    };

    public CameraSessionCoordinator(
            Context context,
            UsbPtpTransport.PermissionGate usbPermissionGate,
            Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.repository = new PhotoRepository(this.context);
        this.galleryExporter = new GalleryExporter(this.context);
        this.previewCacheDir = new File(this.context.getCacheDir(), "preview");
        long previewCacheBudget = Math.max(4L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
        this.previewCache = new LruCache<Long, Bitmap>((int) previewCacheBudget) {
            @Override protected int sizeOf(Long key, Bitmap value) {
                return value.getByteCount();
            }
        };
        this.usbTransport = new UsbPtpTransport(this.context, usbPermissionGate);
        this.connectivityManager =
                (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.wifiManager =
                (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        // 持久化发起方 GUID：同一台手机每次握手复用同一身份，相机有机会把它识别为
        // 「同一设备」从而允许替换旧连接（P6 实验）。首次生成后固定不变。
        wifiTransport.setInitiatorGuid(loadOrCreateInitiatorGuid(this.context));
        // 只有 PTP/IP 有 event 通道；USB 的新对象发现走句柄差集轮询。
        wifiTransport.setObjectEventListener((storageId, objectHandle) -> {
            NewPhotoResolver resolver = activeResolver;
            if (resolver != null) resolver.onObjectAdded(storageId, objectHandle);
        });
    }

    /** 与 MainActivity 共用的偏好文件名。 */
    private static final String PREFS_NAME = "image_fetch_prefs";
    /** 持久化发起方 GUID 的键（存 32 位十六进制串）。 */
    private static final String PREF_INITIATOR_GUID = "ptpip_initiator_guid";

    /**
     * 读取持久化的 PTP/IP 发起方 GUID；首次运行时随机生成 16 字节并落盘。
     * 落盘失败（如磁盘不可写）不致命：本次仍复用内存中的值，仅退化为「本次运行内固定」。
     */
    private static byte[] loadOrCreateInitiatorGuid(Context context) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(PREF_INITIATOR_GUID, null);
        if (stored != null && stored.length() == 32) {
            byte[] guid = new byte[16];
            boolean valid = true;
            for (int i = 0; i < 16; i++) {
                try {
                    guid[i] = (byte) Integer.parseInt(stored.substring(i * 2, i * 2 + 2), 16);
                } catch (NumberFormatException bad) {
                    valid = false;
                    break;
                }
            }
            if (valid) return guid;
        }
        byte[] guid = new byte[16];
        new java.security.SecureRandom().nextBytes(guid);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : guid) hex.append(String.format(Locale.ROOT, "%02X", b));
        prefs.edit().putString(PREF_INITIATOR_GUID, hex.toString()).apply();
        return guid;
    }

    public File outputDirectory() {
        return repository.outputDirectory();
    }

    /** 是否在落盘后再导出到系统相册（关闭时只保留应用内副本）。 */
    public void setExportToGallery(boolean value) {
        exportToGallery = value;
    }

    /**
     * 本批是否保留应用内副本。景深合成要读原图，而导出相册成功后默认会删掉私有副本，
     * 所以对焦包围这一批必须先把这个开关打开，合成才有文件可读。
     *
     * <p>只影响「导出成功后是否删除副本」这一处；导出本身照常进行，用户在系统图库里一样能看到原图。
     * 打开时顺带清掉上一批的「可清理副本」记录，避免跨批误删。
     */
    public void setRetainLocalCopiesForStack(boolean value) {
        retainLocalCopiesForStack = value;
        if (value) stackExportedCopies.clear();
    }

    /**
     * 取走本批「已导出到相册、可以安全删除」的应用内副本，并清空记录。
     * 景深合成成功之后调用：相册里已有原件，应用内这份就没必要再占空间了。
     * 未成功导出相册的副本不在其中，所以调用方不必再判一次安全性。
     */
    public List<File> takeStackCleanableCopies() {
        synchronized (stackExportedCopies) {
            List<File> result = new ArrayList<>(stackExportedCopies);
            stackExportedCopies.clear();
            return result;
        }
    }

    /** 每页张数。 */
    public int pageSize() {
        return pageSize;
    }

    /** 设置每页张数（1..500）；调用方负责持久化并在需要时回到第一页。 */
    public void setPageSize(int value) {
        pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, value));
    }

    /** 存储卡结构变化后（拍摄、手动刷新）清掉句柄缓存与完整扫描缓存。 */
    public void invalidateStorageCache() {
        fullScanItems = null;
        PtpOperationService session = operations;
        if (session != null) session.invalidateStorageCache();
    }

    /** 一页读取结果回调。 */
    public interface StoragePageCallback {
        void onLoaded(CameraStorage.Page page);

        void onError(String message, Throwable error);
    }

    /**
     * 读取一页。
     *
     * @param fullScan true = 完整扫描整卡后在内存分页（无目录深度假设，首次较慢，
     *                 结果按会话缓存，翻页瞬时）；false = 快速分页，只读当前页。
     * @param jpegOnly true = 按 JPG 分页：每页 {@code pageSize} 张 JPG，总数也是 JPG 张数
     *                 （不再先按全部对象切片、再由界面过滤掉 RAW/视频把页面撑空）。
     */
    public void loadStoragePage(
            final int pageIndex,
            final boolean fullScan,
            final boolean jpegOnly,
            final StoragePageCallback callback) {
        executor.submit(() -> {
            final PtpOperationService session = operations;
            if (session == null || !isConnected()) {
                mainHandler.post(() -> callback.onError("请先连接相机。", null));
                return;
            }
            try {
                CameraStorage.Page page;
                if (fullScan) {
                    List<CameraStorage.Item> all = fullScanItems;
                    if (all == null) {
                        listener.onStatus("完整扫描存储卡（整卡枚举，可能较慢）…");
                        all = session.snapshot(0).items;
                        fullScanItems = all;
                    }
                    // 完整扫描的结果是全集，JPG 分页只需在内存里先筛出 JPG 再切片。
                    List<CameraStorage.Item> source = jpegOnly ? jpegItems(all) : all;
                    page = CameraStorage.Page.ofItems(source, pageSize, pageIndex);
                } else {
                    if (jpegOnly) listener.onStatus("正在筛查 JPG（只按 JPG 分页）…");
                    page = session.page(pageSize, pageIndex, jpegOnly);
                }
                final CameraStorage.Page result = page;
                mainHandler.post(() -> callback.onLoaded(result));
            } catch (Exception error) {
                mainHandler.post(() -> callback.onError(
                        error.getMessage() == null ? "读取存储卡失败" : error.getMessage(), error));
            }
        });
    }

    private static List<CameraStorage.Item> jpegItems(List<CameraStorage.Item> items) {
        List<CameraStorage.Item> result = new ArrayList<>();
        for (CameraStorage.Item item : items) {
            if (item.isJpeg()) result.add(item);
        }
        return result;
    }

    /** 单张缩略图就绪回调；jpeg 为 null 表示该对象无缩略图或读取失败。 */
    public interface ThumbnailCallback {
        void onThumbnail(long handle, byte[] jpeg);
    }

    /**
     * 逐张读取缩略图。与其它相机事务共用同一 executor，因此不会与下载/拍摄交错；
     * 命中缓存直接回调。每次调用产生新的 generation，旧批次会被放弃。
     */
    public void loadThumbnails(
            final List<CameraStorage.Item> items, final ThumbnailCallback callback) {
        final long generation = ++thumbnailGeneration;
        final PtpOperationService session = operations;
        if (session == null || items == null || items.isEmpty()) return;
        executor.submit(() -> {
            for (CameraStorage.Item item : items) {
                if (generation != thumbnailGeneration) return;
                byte[] cached = thumbnailCache.get(item.handle);
                if (cached != null) {
                    postThumbnail(generation, callback, item.handle, cached);
                    continue;
                }
                byte[] jpeg = null;
                try {
                    byte[] read = session.thumbnail(item.handle);
                    if (read != null && read.length > 0) {
                        jpeg = read;
                        thumbnailCache.put(item.handle, read);
                    }
                } catch (Exception ignored) {
                    // 单个对象没有缩略图（RAW/视频/机型不支持）属正常，不中断整批。
                }
                if (generation != thumbnailGeneration) return;
                postThumbnail(generation, callback, item.handle, jpeg);
            }
        });
    }

    private void postThumbnail(
            long generation, ThumbnailCallback callback, long handle, byte[] jpeg) {
        mainHandler.post(() -> {
            if (generation == thumbnailGeneration) callback.onThumbnail(handle, jpeg);
        });
    }

    /** 放大预览回调；bitmap 为 null 表示取不到原图（RAW/视频/相机不支持），沿用缩略图。 */
    public interface PreviewCallback {
        void onPreview(long handle, Bitmap bitmap);
    }

    /**
     * 读取一张原图用于放大预览。只有 JPEG 才向相机取原图（RAW/视频不适用），
     * 解码后按句柄缓存；与其它相机事务共用 executor，不会与下载/翻页交错。
     * 连续点更多张时，排队的旧请求会被放弃，不重复占用带宽。
     */
    public void loadPreview(final CameraStorage.Item item, final PreviewCallback callback) {
        if (item == null) return;
        final long generation = ++previewGeneration;
        executor.submit(() -> {
            Bitmap bitmap = previewCache.get(item.handle);
            if (bitmap == null && item.isJpeg() && generation == previewGeneration) {
                bitmap = fetchPreview(item.handle);
            }
            if (generation != previewGeneration) return;
            final Bitmap result = bitmap;
            mainHandler.post(() -> callback.onPreview(item.handle, result));
        });
    }

    /** 原图落盘到缓存目录 → 降采样解码 → 删除临时文件；失败返回 null（回落缩略图）。 */
    private Bitmap fetchPreview(long handle) {
        final PtpOperationService session = operations;
        if (session == null || !isConnected()) return null;
        File temp = null;
        try {
            if (!previewCacheDir.exists() && !previewCacheDir.mkdirs()) return null;
            temp = new File(previewCacheDir, handle + ".jpg");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                session.download(handle, output, null);
                output.flush();
            }
            Bitmap bitmap = decodeFile(temp, PREVIEW_MAX_PX);
            if (bitmap != null) previewCache.put(handle, bitmap);
            return bitmap;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (temp != null && temp.exists()) temp.delete();
        }
    }

    /** 两遍解码：先读尺寸算 inSampleSize，避免整张原图位图撑爆内存。 */
    private static Bitmap decodeFile(File file, int targetPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (targetPx > 0 && longest / sample > targetPx * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        // 相机竖拍 JPEG 只在 EXIF 里记方向：按标记摆正，否则全屏看图里长宽会颠倒横躺。
        return JpegOrientation.applyFromFile(file, decoded);
    }

    public boolean isConnected() {
        CameraTransport active = transport;
        return connected && active != null && active.isConnected();
    }

    public boolean isReconnecting() {
        return reconnecting;
    }

    public String cameraName() {
        return cameraName;
    }

    /** 退避序列（纯函数，便于契约测试）：1/2/4/8/16 封顶 30s。 */
    static long backoffDelayMs(int attempt) {
        return ReconnectPolicy.backoffDelayMs(attempt);
    }

    /**
     * 连接相机。Wi‑Fi 用 host/port；USB 忽略两者，按已连接的 USB 相机建立会话。
     * 切换传输方式时会先释放上一条会话。
     */
    public void connect(TransportKind requestedKind, String host, int port) {
        manualDisconnect = false;
        // 新会话从活跃态起步：上一次退后台留下的挂起标记不能带过来。
        suspended = false;
        reconnecting = false;
        connecting = true;
        heartbeat.reset();
        reconnectAttempt = 0;
        // 用户主动连接优先于任何在途的自动重连。
        mainHandler.removeCallbacks(heartbeatRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        cameraHost = host;
        cameraPort = port;
        final long generation = ++connectionGeneration;
        executor.submit(() -> {
            try {
                closeCurrentTransport();
                CameraTransport active = selectTransport(requestedKind);
                if (requestedKind == TransportKind.USB) {
                    listener.onStatus("枚举 USB 相机…");
                } else {
                    listener.onStatus("枚举 Wi‑Fi 网络…");
                    bindWifiNetwork((PtpIpTransport) active, generation, false);
                    if (!connectAttemptActive(generation, false)) {
                        closeQuietly(active);
                        return;
                    }
                    listener.onStatus("建立 PTP/IP 双通道…");
                }
                String name = active.connect(host, port);
                if (!connectAttemptActive(generation, false)) {
                    closeQuietly(active);
                    return;
                }
                active.assertHealthy();
                if (!connectAttemptActive(generation, false)) {
                    closeQuietly(active);
                    return;
                }
                mainHandler.post(() -> {
                    if (!connectAttemptActive(generation, false)) return;
                    connected = true;
                    connecting = false;
                    cameraName = name;
                    reconnectAttempt = 0;
                    listener.onStatus("已连接 · " + name);
                    listener.onConnected(name);
                    acquireWifiLock();
                    startHeartbeat();
                });
            } catch (Exception error) {
                CameraTransport active = transport;
                closeQuietly(active);
                if (!connectAttemptActive(generation, false)) return;
                final String detail = connectFailureMessage(requestedKind, host, port, error);
                mainHandler.post(() -> {
                    if (!connectAttemptActive(generation, false)) return;
                    connected = false;
                    connecting = false;
                    listener.onError(detail, error);
                });
            }
        });
    }

    /**
     * 连接失败提示 = 用户可读的原因与建议 + 真机诊断。
     * 诊断段是排查唯一依据：网络选择、本机出口、逐次握手的异常链、上次会话释放结果，
     * 都要能在一次真机报错里看到，不必再连 logcat。
     */
    private String connectFailureMessage(
            TransportKind kind, String host, int port, Throwable error) {
        StringBuilder text = new StringBuilder();
        text.append(error.getMessage() != null ? error.getMessage() : "连接失败");
        text.append(unreachableHint(error));
        text.append("\n\n——— 诊断 ———");
        text.append("\n传输：").append(kind == TransportKind.USB
                ? "USB"
                : "Wi‑Fi " + host + ":" + port);
        text.append("\n系统：Android ").append(Build.VERSION.RELEASE)
                .append("（SDK ").append(Build.VERSION.SDK_INT).append("）");
        text.append("\n上次断开：")
                .append(lastSessionRelease.isEmpty() ? "未记录" : lastSessionRelease);
        CameraTransport active = transport;
        if (active instanceof PtpIpTransport) {
            text.append('\n').append(((PtpIpTransport) active).connectDiagnostics());
        }
        return text.toString();
    }

    /** 「拍摄超时」时附上厂商快门逐步诊断，区分「相机没拍」与「拍了没被发现」。 */
    private String captureDiagnosticsSuffix() {
        PtpOperationService active = operations;
        if (active == null) return "";
        String steps = active.transport().captureDiagnostics();
        return steps.isEmpty() ? "" : "\n快门诊断：\n" + steps;
    }

    /**
     * 相机不可达（EHOSTUNREACH / No route to host）几乎都不是协议问题，而是网络路径断了：
     * 手机被系统切回移动数据或其它 Wi‑Fi、相机休眠/自动关机、或相机自身超时断开了 Wi‑Fi。
     * 这类失败靠重试无法自愈，只能给出可操作的排查方向。
     */
    static String unreachableHint(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String text = cause.getMessage();
            if (text != null && text.contains("ECONNABORTED")) {
                return "\n\n连接过程被中断（ECONNABORTED）：TCP 握手还没完成，链路就断了。\n"
                        + "1) 相机很可能已退出「连接智能手机」模式或进入休眠——"
                        + "在相机上重新进入该模式；\n"
                        + "2) 相机设置里的 Wi‑Fi 节能 / 空闲断开 / 自动关机是否开启（开启就关掉）；\n"
                        + "3) 手机是否在连接瞬间从相机 Wi‑Fi 切回了移动数据。";
            }
            if (text != null
                    && (text.contains("ECONNREFUSED") || text.contains("Connection refused"))) {
                return "\n\n相机 Wi‑Fi 通着，但相机没有在 15740 端口监听（PTP 服务没起来）。"
                        + "常见原因：\n"
                        + "1) 相机刚结束上一次连接，PTP 服务还在重启——等几秒再点「连接」；\n"
                        + "2) 相机已退出「连接智能手机」模式（相机屏上可能显示「未找到连接目标」），"
                        + "需要在相机上重新进入该模式；\n"
                        + "3) 部分机型需在相机菜单里确认已开启 PTP/IP（无线连接）服务。";
            }
            boolean unreachable = cause instanceof NoRouteToHostException
                    || cause instanceof UnknownHostException
                    || (cause.getMessage() != null
                            && (cause.getMessage().contains("EHOSTUNREACH")
                                    || cause.getMessage().contains("No route to host")));
            if (!unreachable) continue;
            return "\n\n相机在当前网络里不可达。请依次检查：\n"
                    + "1) 手机是否仍连着相机的 Wi‑Fi（系统常会自动切回移动数据或已记住的其它 Wi‑Fi）；\n"
                    + "2) 相机是否已休眠/自动关机（相机设置里关闭「自动关机」等省电项）；\n"
                    + "3) 相机的 Wi‑Fi 节能/空闲断开选项是否开启。\n"
                    + "以上任一项都会让相机从网络中消失，必须手动恢复后再连接。";
        }
        return "";
    }

    /** 选定传输实现并重建操作门面（executor 内串行调用，无并发切换）。 */
    private CameraTransport selectTransport(TransportKind requestedKind) {
        CameraTransport active = requestedKind == TransportKind.USB ? usbTransport : wifiTransport;
        transport = active;
        operations = new PtpOperationService(active);
        // 换传输即换会话，取景状态不能再沿用。
        invalidateLiveView();
        // 句柄在同一相机的不同会话间可复用，跨会话沿用旧缓存可能张冠李戴。
        thumbnailCache.evictAll();
        previewCache.evictAll();
        fullScanItems = null;
        return active;
    }

    private void closeCurrentTransport() {
        closeQuietly(transport);
    }

    private static void closeQuietly(CameraTransport transport) {
        if (transport == null) return;
        try {
            transport.close();
        } catch (IOException ignored) {
            // 关闭阶段的 IO 错误不影响会话状态复位。
        }
    }

    /**
     * 把已落盘的原图导出到系统相册（DCIM/IMAGE_FETCH）。失败只上报、不回滚，也不中断后续传输：
     * 应用文件库中的副本始终是可用结果，相册导出是附加可见性。
     * 失败信息每批操作只弹一次，避免批量下载时反复打断。
     */
    private String exportToGalleryIfEnabled(File file) {
        // 明确写出“没导出”的原因，避免与“导出失败”混淆（状态栏文字是唯一线索）。
        if (!exportToGallery) return " · 未勾选「保存到系统相册」";
        try {
            galleryExporter.export(file, file.getName());
            // 景深合成批次要读这份原图，合成没跑完之前不能删（删了就只能从相册取回，代价大得多）。
            // 记下来：合成成功后由 MainActivity 删掉——那时相册里已经有原件了。
            if (retainLocalCopiesForStack) {
                stackExportedCopies.add(file);
                return " · 已存入系统相册 " + GalleryExporter.RELATIVE_PATH + "（并保留应用内副本供景深合成）";
            }
            // 只留相册：导出成功后清掉私有目录副本，避免同一张照片占两份空间。
            // 去重索引（.imported-index）按对象身份记录，不依赖副本存在，跳过重复下载的能力不受影响。
            if (!file.delete()) {
                return " · 已存入系统相册 " + GalleryExporter.RELATIVE_PATH + "（副本未能删除）";
            }
            return " · 已存入系统相册 " + GalleryExporter.RELATIVE_PATH;
        } catch (Exception error) {
            String reason = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            if (!exportFailureReported) {
                exportFailureReported = true;
                mainHandler.post(() -> listener.onError(
                        "照片已保存到应用内副本，但导出到系统相册失败：" + reason, null));
            }
            return " · 相册导出失败：" + reason;
        }
    }

    // ── 连接保活 HOLD 三态（前台活跃 / 后台暂缓 / 释放） ──

    /** 当前 HOLD 状态（派生值，语义见 {@link HoldState}）。 */
    public HoldState holdState() {
        return holdStateOf(connected, reconnecting, manualDisconnect, suspended);
    }

    /**
     * 应用退到后台：停掉心跳探测（省电，也避免后台一次网络抖动就误判离线），
     * 但不拆链路——socket、相机侧会话、WifiLock 全部保留，传输/拍摄/取景照常继续。
     * 回到前台由 {@link #resumeHold()} 重新武装探测。
     */
    public void suspendHold() {
        if (manualDisconnect) return;
        suspended = true;
        mainHandler.removeCallbacks(heartbeatRunnable);
    }

    /**
     * 应用回到前台：立刻补一次探测（0 延迟复用既有心跳，不新增探测路径）。
     * 不显式重置心跳计数：探测成功自身会清零，失败则与挂起前的历史合并，能更快判定真离线。
     */
    public void resumeHold() {
        if (!suspended) return;
        suspended = false;
        if (connected && !reconnecting && !manualDisconnect) startHeartbeat(0L);
    }

    /** 手动断开：不触发自动重连。 */
    public void disconnect() {
        connectionGeneration++;
        // 取景循环必须先停：断开后相机会退出取景，残留的取帧任务只会反复报错。
        invalidateLiveView();
        manualDisconnect = true;
        suspended = false;
        reconnecting = false;
        connecting = false;
        mainHandler.removeCallbacks(heartbeatRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        unregisterWifiNetworkCallback();
        unregisterSpecifierCallback();
        releaseWifiLock();
        cameraWifiNetwork = null;
        sessionNetwork = null;
        final CameraTransport session = transport;
        TransferControl current = control;
        if (current != null) {
            current.cancel("用户断开连接");
            // 传输中：必须立刻打断阻塞中的读写。
            if (session != null) session.abortTransport();
            lastSessionRelease = "传输中被强制中断（未主动释放相机侧会话）";
        } else {
            lastSessionRelease = "未主动释放相机侧会话（统一策略：断开只拆 TCP，保留槽位）";
        }
        executor.submit(() -> {
            // 统一策略：Wi‑Fi 断开一律不发 CloseSession(0x1003)。
            // 实测发 CloseSession 后相机会关掉自己的 PTP/IP 监听（下次连接 ECONNREFUSED），
            // 且 InitFail 是连接层拒绝，CloseSession 也动不了它。断开只拆 TCP，把会话槽位
            // 留给相机的超时回收；重连时由 OpenSession 的 0x201E 复用。诊断见 lastSessionRelease。
            closeQuietly(session);
            connected = false;
            cameraName = "";
            heartbeat.reset();
            mainHandler.post(listener::onDisconnected);
        });
    }

    public void shutdown() {
        manualDisconnect = true;
        suspended = false;
        invalidateLiveView();
        mainHandler.removeCallbacks(heartbeatRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        CameraTransport session = transport;
        TransferControl current = control;
        if (current != null) {
            current.cancel("应用退出");
            // 传输中：先打断阻塞读写，否则 close() 取实例锁时会干等（可能触发 ANR），
            // 且通道正忙时也无法优雅释放会话。
            if (session != null) session.abortTransport();
        }
        closeQuietly(session);
        unregisterWifiNetworkCallback();
        unregisterSpecifierCallback();
        releaseWifiLock();
        executor.shutdownNow();
    }

    public void cancelCurrent() {
        TransferControl current = control;
        if (current != null) current.cancel("用户取消");
    }

    // ── 实时取景与曝光参数（Canon EOS） ──

    /**
     * 作废正在跑的取景循环（断开、重连、切换传输、退出应用）。
     * 直接改标志位而不经 executor：这些路径可能正被在途的相机 IO 占着线程，
     * 排队等它反而会拖住断开；{@code liveViewGeneration} 自增即可让在途帧任务自行退出。
     */
    private void invalidateLiveView() {
        liveViewActive = false;
        liveViewGeneration++;
        activePump = null;
        liveViewSuspended = false;
    }

    /**
     * 开启实时取景预览。取帧循环跑在独占线程上（见下），与界面/拍摄共用的是传输层的
     * 命令锁：谁先拿到锁谁发命令，不会交错。
     *
     * <p>开启序列要退避重试几次：相机刚连上或刚拍完时可能拒绝 EVF 属性写入，只试一次会让
     * 面板一直黑着（实测非要先拍一张才能出画面）。
     */
    public void startLiveView() {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onLiveViewStatus("请先连接相机。"));
                return;
            }
            if (liveViewActive) return;
            long generation = liveViewGeneration;
            LiveViewSize size = liveViewSize;
            Exception lastError = null;
            for (int attempt = 1; attempt <= LIVE_VIEW_ENABLE_ATTEMPTS; attempt++) {
                if (!isConnected() || generation != liveViewGeneration) return;
                try {
                    operations.setLiveViewEnabled(true, size);
                    lastError = null;
                    break;
                } catch (Exception error) {
                    lastError = error;
                    if (attempt < LIVE_VIEW_ENABLE_ATTEMPTS) {
                        sleepQuietly(LIVE_VIEW_ENABLE_BACKOFF_MS * attempt);
                    }
                }
            }
            if (lastError != null) {
                String message = lastError.getMessage() != null
                        ? lastError.getMessage() : "实时取景开启失败";
                mainHandler.post(() -> listener.onLiveViewStatus(message));
                return;
            }
            liveViewActive = true;
            long activeGeneration = ++liveViewGeneration;
            mainHandler.post(() -> listener.onLiveViewStatus("实时取景已开启"));
            LiveViewPump pump = new LiveViewPump();
            activePump = pump;
            // 取帧循环走独占线程：绝不再往共享 executor 里递归提交，否则每条界面命令
            // 都要排在待取的那一帧后面。停止靠 liveViewActive/generation 标志位。
            Thread thread = new Thread(
                    () -> pumpLiveViewLoop(activeGeneration, pump), "imagefetch-liveview");
            thread.setDaemon(true);
            thread.start();
        });
    }

    /**
     * 切换取景画面尺寸（相机端推流分辨率）。取景进行中时立刻改写 0xD1B0 的输出位，
     * 下一帧起就是新分辨率；未开取景时只记下来，下次开取景生效。
     */
    public void setLiveViewSize(LiveViewSize size) {
        if (size == null || size == liveViewSize) return;
        liveViewSize = size;
        if (!liveViewActive) return;
        executor.submit(() -> {
            if (!liveViewActive || !isConnected()) return;
            try {
                operations.setLiveViewEnabled(true, size);
            } catch (Exception ignored) {
                // 改尺寸失败不影响正在进行的取景：继续按原分辨率推画面。
            }
        });
    }

    /**
     * 关闭实时取景。可从主线程直接调用（例如对话框关闭时）。
     *
     * <p>即使取景没开着也照发一次关闭序列：把画面输出从 PC 收回机身屏
     * （EVFOutputDevice=0x01，见 PtpIpTransport#stopLiveViewOutput），让取景干净结束。
     * 该序列**不退出遥控态**——真机（EOS RP）实测留在遥控态时手机能调参、机身也能操作，
     * 所以关面板时保持连接、不拆会话。
     */
    public void stopLiveView() {
        invalidateLiveView();
        executor.submit(() -> {
            try {
                operations.setLiveViewEnabled(false, liveViewSize);
            } catch (Exception ignored) {
                // 关闭失败不影响会话；断开连接时相机会自行退出取景。
            }
            // 关面板只收回画面输出、留在遥控态（见 PtpIpTransport#stopLiveViewOutput）：
            // 真机实测这样机身按键照样可用，手机也能继续调参，所以这里如实说「已关闭」即可。
            mainHandler.post(() -> listener.onLiveViewStatus("实时取景已关闭"));
        });
    }

    /** 取帧循环的可变状态；同一时刻只有一个循环在跑（由 generation 保证）。 */
    private static final class LiveViewPump {
        int consecutiveErrors;
        /** 已经因为「收不到帧」重发过几次开启序列。 */
        int stallRestarts;
        /** 上一帧还在 UI 线程处理时置 true：丢弃新帧，避免取景帧在消息队列里堆起来。 */
        final AtomicBoolean framePending = new AtomicBoolean();
        long lastFrameAt = System.currentTimeMillis();
        long lastRestartAt = System.currentTimeMillis();
    }

    /**
     * 取帧循环主体（独占线程）：只要代际没被作废就一直取帧。
     *
     * <p>被「拍摄序列」挂起时只轮询标志位，完全不碰相机——暂停取景与按快门之间插一帧
     * 请求只会让相机更忙。
     */
    private void pumpLiveViewLoop(long generation, LiveViewPump pump) {
        while (liveViewActive && generation == liveViewGeneration) {
            if (liveViewSuspended) {
                if (!sleepQuietly(LIVE_VIEW_SUSPEND_POLL_MS)) return;
                continue;
            }
            if (!pumpLiveViewFrame(generation, pump)) return;
        }
    }

    /**
     * 取一帧并休眠。用 {@code generation} 判断自己是否已被关闭/重连作废；
     * 返回 {@code false} 表示循环该结束了。
     *
     * <p>不把单次取帧异常当成断开：相机忙时抛一次很常见，连续
     * {@code LIVE_VIEW_MAX_CONSECUTIVE_ERRORS} 次才算真的中断。若长时间一帧都拿不到
     * （相机对开启写入回了 OK 却没真正切开取景输出），重发一次开启序列。
     */
    private boolean pumpLiveViewFrame(long generation, LiveViewPump pump) {
        if (!liveViewActive || generation != liveViewGeneration) return false;
        long delay = LIVE_VIEW_FRAME_INTERVAL_MS;
        boolean gotFrame = false;
        try {
            byte[] jpeg = operations.liveViewFrame();
            if (jpeg == null) {
                // 相机还没准备好这一帧：正常情况，退避后再来。
                delay = LIVE_VIEW_RETRY_INTERVAL_MS;
            } else {
                gotFrame = true;
                pump.consecutiveErrors = 0;
                Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (frame != null) {
                    liveViewFrameWidth = frame.getWidth();
                    liveViewFrameHeight = frame.getHeight();
                    // UI 线程一时忙不过来时只保留最新一帧：每帧 MB 级，堆十几帧既涨内存也越拖越慢。
                    if (pump.framePending.compareAndSet(false, true)) {
                        mainHandler.post(() -> {
                            try {
                                listener.onLiveViewFrame(frame);
                            } finally {
                                pump.framePending.set(false);
                            }
                        });
                    } else if (!frame.isRecycled()) {
                        frame.recycle();
                    }
                }
            }
        } catch (Exception error) {
            pump.consecutiveErrors++;
            if (pump.consecutiveErrors >= LIVE_VIEW_MAX_CONSECUTIVE_ERRORS) {
                // 连续失败基本等同于链路故障：停掉循环，把原因交给界面，重连由心跳负责。
                liveViewActive = false;
                String message = error.getMessage() != null ? error.getMessage() : "实时取景中断";
                mainHandler.post(() -> listener.onLiveViewStatus("实时取景中断：" + message));
                return false;
            }
            delay = LIVE_VIEW_RETRY_INTERVAL_MS;
        }

        long now = System.currentTimeMillis();
        if (gotFrame) {
            pump.lastFrameAt = now;
        } else if (liveViewActive && generation == liveViewGeneration
                && now - pump.lastFrameAt >= LIVE_VIEW_STALL_RESTART_MS
                && now - pump.lastRestartAt >= LIVE_VIEW_STALL_RESTART_MS) {
            // 先确认循环仍有效：面板关闭（stopLiveView）会把 liveViewActive 置 false 并作废代际，
            // 但在途的这一帧还要等 operations.liveViewFrame() 返回才走到这里。若不复查就重发
            // 开启序列，它会经 ensureCanonRemoteMode() 把相机**重新拽回遥控态**——那就正好抵消
            // 了 stopLiveView 里刚做完的「交还机身」收尾，表现为「关了面板相机还是不能自由使用」。
            //
            // 一帧都没出：相机可能对开启写入回了 OK 却没真正切到取景输出。先重发开启序列；
            // 仍不出图就再补一次半按唤醒——实测「先拍一张后取景才出画面」，说明取景输出要经过
            // 一次释放周期才真正启动，而半按只测光/对焦、不会拍照。
            pump.lastRestartAt = now;
            pump.lastFrameAt = now;
            pump.stallRestarts++;
            try {
                operations.setLiveViewEnabled(true, liveViewSize);
                if (pump.stallRestarts > 1) operations.wakeForLiveView();
            } catch (Exception ignored) {
                // 重发失败不额外报错：下面的取帧会继续退避重试。
            }
            String hint = pump.stallRestarts > 1
                    ? "实时取景无画面，已尝试半按唤醒相机…"
                    : "实时取景无画面，正在重发开启序列…";
            mainHandler.post(() -> listener.onLiveViewStatus(hint));
        }

        if (!sleepQuietly(delay)) {
            // 退出应用/断开时会打断取景线程，直接结束循环即可。
            return false;
        }
        return liveViewActive && generation == liveViewGeneration;
    }

    /** 睡一会儿；被打断返回 false（取景循环据此退出）。 */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            return false;
        }
    }

    /** 读取某个曝光参数的当前档位；读不到时回调里给出原因（相机可能不推送该值）。 */
    public void readExposure(final CanonExposure.Setting setting) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onExposureResult(
                        setting, -1, null, "请先连接相机。"));
                return;
            }
            try {
                long raw = operations.readExposure(setting);
                // 相机上报的允许值列表（0xC18A）把可选档收窄到机身真正支持的子集；缓存为空时
                // restrict 原样交回全表，行为与旧版一致。下标语义必须与界面/写入一致，都基于此列表。
                List<CanonExposure.Entry> choices = CanonExposure.restrict(
                        setting, operations.supportedValues(setting.vendorProperty));
                int index = indexOfEntry(choices, (int) raw);
                if (index < 0) {
                    // 缓存里没这个值：退回全表按标准读数兜底换算；那个下标是全表的，列表也必须
                    // 一并退回全表，否则界面拿到的下标会错位。
                    int fallback = CanonExposure.matchStandard(setting, raw);
                    if (fallback >= 0) {
                        choices = CanonExposure.entries(setting);
                        index = fallback;
                    }
                }
                if (index < 0) {
                    mainHandler.post(() -> listener.onExposureResult(
                            setting, -1, null, "相机回的档位不在可选列表里"));
                    return;
                }
                final List<CanonExposure.Entry> resolvedChoices = choices;
                final int resolved = index;
                String display = CanonExposure.display(setting, choices.get(index).label);
                // 先换档位列表、再报当前值：两者都在主线程按序执行，界面拿到一致的下标。
                mainHandler.post(() -> listener.onExposureChoices(setting, resolvedChoices));
                mainHandler.post(() -> listener.onExposureResult(setting, resolved, display, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "读取失败";
                mainHandler.post(() -> listener.onExposureResult(setting, -1, null, message));
            }
        });
    }

    /** 在给定档位列表里按机上编码值找下标；找不到返回 -1。 */
    private static int indexOfEntry(List<CanonExposure.Entry> entries, int value) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).value == value) return i;
        }
        return -1;
    }

    /**
     * 写入某个曝光参数的档位（{@code index} 为「相机允许值收窄后列表」的下标，与
     * {@link #readExposure} 及界面一致）。
     */
    public void writeExposure(final CanonExposure.Setting setting, final int index) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onExposureResult(
                        setting, -1, null, "请先连接相机。"));
                return;
            }
            // 下标语义与 readExposure/界面一致：都基于「相机允许值收窄后的列表」。
            List<CanonExposure.Entry> entries = CanonExposure.restrict(
                    setting, operations.supportedValues(setting.vendorProperty));
            if (index < 0 || index >= entries.size()) return;
            final CanonExposure.Entry entry = entries.get(index);
            try {
                operations.writeExposure(setting, entry.value);
                String display = CanonExposure.display(setting, entry.label);
                mainHandler.post(() -> listener.onExposureResult(setting, index, display, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "写入失败";
                // 写入失败必须让用户看见：界面上档位会保持在用户选的值（不回退成「—」），
                // 如果不额外报一句，失败就被静默吞掉了。
                mainHandler.post(() -> listener.onLiveViewStatus(setting.label + "写入失败：" + message));
                mainHandler.post(() -> listener.onExposureResult(setting, -1, null, message));
            }
        });
    }

    /**
     * 读取白平衡（0xD109）：可选值列表（0xC18A 允许值）+ 当前值，交给设置面板的下拉框。
     * 相机没给允许列表时降级为候选表（见 {@link WhiteBalanceChoices}），并用
     * {@code fromCamera=false} 标注，不把下拉框留空或只留当前值一项。
     */
    public void readWhiteBalance() {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onWhiteBalanceResult(null, -1, false, "请先连接相机。"));
                return;
            }
            try {
                int raw = (int) operations.readWhiteBalance();
                int[] allowed = operations.supportedValues(VendorProperty.WHITE_BALANCE);
                WhiteBalanceChoices.Choices choices = WhiteBalanceChoices.resolve(allowed, raw);
                final int[] values = choices.values;
                final int current = choices.currentIndex;
                final boolean fromCamera = choices.fromCamera;
                mainHandler.post(() -> listener.onWhiteBalanceResult(values, current, fromCamera, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "读取白平衡失败";
                mainHandler.post(() -> listener.onWhiteBalanceResult(null, -1, false, message));
            }
        });
    }

    /**
     * 写入白平衡。{@code value} 是机上编码值，直接取自定义面下拉框的选项（{@link #readWhiteBalance()}
     * 回调里的列表）——传编码而不是下标，协调器这侧就不需要重查列表、也就没有下标错位的余地。
     */
    public void writeWhiteBalance(final int value) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onWhiteBalanceWritten(false, value, "请先连接相机。"));
                return;
            }
            try {
                operations.writeWhiteBalance(value);
                mainHandler.post(() -> listener.onWhiteBalanceWritten(true, value, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "白平衡写入失败";
                mainHandler.post(() -> listener.onWhiteBalanceWritten(false, value, message));
            }
        });
    }

    /**
     * 读取色温（0xD10A，单位 K）。这个属性没有允许值列表，只回当前值；
     * 界面在「色温」档被选中时才需要它。
     */
    public void readColorTemperature() {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onColorTemperatureResult(-1, "请先连接相机。"));
                return;
            }
            try {
                int kelvin = (int) operations.readColorTemperature();
                mainHandler.post(() -> listener.onColorTemperatureResult(kelvin, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "读取色温失败";
                mainHandler.post(() -> listener.onColorTemperatureResult(-1, message));
            }
        });
    }

    /**
     * 写入色温（单位 K）。{@code kelvin} 由界面校验/对齐后传入，协调器不再加工——
     * 传具体值而不是档位，与白平衡一致。
     */
    public void writeColorTemperature(final int kelvin) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onColorTemperatureWritten(false, kelvin, "请先连接相机。"));
                return;
            }
            try {
                operations.writeColorTemperature(kelvin);
                mainHandler.post(() -> listener.onColorTemperatureWritten(true, kelvin, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "色温写入失败";
                mainHandler.post(() -> listener.onColorTemperatureWritten(false, kelvin, message));
            }
        });
    }

    /**
     * 读取对焦方式（0xD1BA）：可选值列表（0xC18A 允许值）+ 当前值，交给面板的下拉框。
     * 相机没给允许列表时降级为候选全表（见 {@link AfMethodChoices}），并在回调里用
     * {@code fromCamera=false} 标注，不把下拉框留空或只留当前值一项。
     */
    public void readFocusArea() {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onFocusAreaResult(null, -1, false, "请先连接相机。"));
                return;
            }
            try {
                int raw = (int) operations.readFocusArea();
                int[] allowed = operations.supportedValues(CANON_PROP_AF_METHOD);
                AfMethodChoices.Choices choices = AfMethodChoices.resolve(allowed, raw);
                final int[] values = choices.values;
                final int current = choices.currentIndex;
                final boolean fromCamera = choices.fromCamera;
                mainHandler.post(() -> listener.onFocusAreaResult(values, current, fromCamera, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "读取对焦方式失败";
                mainHandler.post(() -> listener.onFocusAreaResult(null, -1, false, message));
            }
        });
    }

    /**
     * 写入对焦方式。{@code value} 是机上编码值，取自 {@link #readFocusArea()} 回调里的列表
     * （界面上是下拉框选项对应的原始值）。
     */
    public void writeFocusArea(final int value) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onLiveViewStatus("请先连接相机。"));
                return;
            }
            try {
                operations.writeFocusArea(value);
                mainHandler.post(() -> listener.onLiveViewStatus(
                        "对焦方式已切换为 0x" + String.format(Locale.ROOT, "%04X", value & 0xffff)));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "对焦方式写入失败";
                mainHandler.post(() -> listener.onLiveViewStatus("对焦方式写入失败：" + message));
            }
        });
    }

    /**
     * P0 能力探测：在单线程 executor 上串行跑，与取景/拍摄互斥。
     * {@code verifyWrite=true} 会真的把相机刚报的原值写回去一次，只应由用户显式触发。
     */
    public void probeVendorCapabilities(final boolean verifyWrite) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onCapabilityReport(null, "请先连接相机。"));
                return;
            }
            try {
                CapabilityReport report = operations.probeVendorCapabilities(verifyWrite);
                lastCapabilityReport = report;
                mainHandler.post(() -> listener.onCapabilityReport(report, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "能力探测失败";
                mainHandler.post(() -> listener.onCapabilityReport(null, message));
            }
        });
    }

    /** 最近一次能力探测结果；未探测或失败时为 null。 */
    public CapabilityReport lastCapabilityReport() {
        return lastCapabilityReport;
    }

    /**
     * 取一份厂商属性只读快照（差分法用）。纯读、不写相机；同样在单线程 executor 上串行，
     * 与取景/拍摄互斥。
     */
    public void snapshotVendorProperties() {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onVendorPropertySnapshot(null, "请先连接相机。"));
                return;
            }
            try {
                VendorPropertySnapshot snapshot = operations.snapshotVendorProperties();
                mainHandler.post(() -> listener.onVendorPropertySnapshot(snapshot, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "属性快照失败";
                mainHandler.post(() -> listener.onVendorPropertySnapshot(null, message));
            }
        });
    }

    /**
     * 读一个厂商属性的原始值区域（只读，差分/参数解读用）。走单线程 executor，与取景、拍摄互斥。
     */
    public void readVendorPropertyBlock(int propertyCode) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onVendorPropertyBlockRead(propertyCode, null, "请先连接相机。"));
                return;
            }
            try {
                byte[] block = operations.readVendorPropertyBlock(propertyCode);
                mainHandler.post(() -> listener.onVendorPropertyBlockRead(propertyCode, block, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "读取属性失败";
                mainHandler.post(() -> listener.onVendorPropertyBlockRead(propertyCode, null, message));
            }
        });
    }

    /**
     * 整体回写一个厂商属性的值区域。<b>会真的写相机</b>，只应由用户显式点击触发，
     * 且上层传进来的 {@code block} 必须是「从相机读回来的字节 + 明确要改的字段」。
     */
    public void writeVendorPropertyBlock(int propertyCode, byte[] block) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onVendorPropertyBlockWritten(propertyCode, 0, "请先连接相机。"));
                return;
            }
            try {
                int code = operations.writeVendorPropertyBlock(propertyCode, block);
                mainHandler.post(() -> listener.onVendorPropertyBlockWritten(propertyCode, code, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "写入属性失败";
                mainHandler.post(() -> listener.onVendorPropertyBlockWritten(propertyCode, 0, message));
            }
        });
    }

    /**
     * 读画质（{@code 0xD120 EOS_ImageFormat}）：可选列表（{@code 0xC18A} 原始字节）与当前值
     * 交给 {@link CanonImageQuality} 解析后下发。那条属性的值区域是变长 u32 序列，不能用
     * {@link #readExposure} 那一套通用解析。相机没给允许列表时不发明候选值——写错画质会改变
     * 相机存储的既有行为，界面上宁可显示「不可用」。
     */
    public void readImageQuality() {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onImageQualityResult(null, -1, false, false, "请先连接相机。"));
                return;
            }
            try {
                byte[] current = operations.readVendorPropertyBlock(CanonImageQuality.IMAGE_FORMAT);
                byte[] allowed = operations.readVendorPropertyEnumsRaw(CanonImageQuality.IMAGE_FORMAT);
                CanonImageQuality.Choices choices = CanonImageQuality.resolve(allowed, current);
                // 相机没给允许列表时不下发选项（fromCamera=false），让界面显示不可用占位。
                final List<CanonImageQuality.Option> options =
                        choices.fromCamera ? choices.options : null;
                mainHandler.post(() -> listener.onImageQualityResult(
                        options, choices.currentIndex, choices.currentHasJpeg, choices.fromCamera, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "读取画质失败";
                mainHandler.post(() -> listener.onImageQualityResult(null, -1, false, false, message));
            }
        });
    }

    /**
     * 直接按选项的原始字节写画质（界面下拉框的选项与「自动 JPG 兜底」选项都走这条）。
     *
     * <p>为什么不让界面传下标：下标要在协调器这侧重新 resolve 一次允许列表才能映射回字节，
     * 而「当前值不在列表里就追加到末尾」这条规则会让列表长度/末位随两次读取之间的相机状态
     * 变化——末位下标就可能落到别的画质上。选项自带原始字节，直接回放没有这层歧义。
     */
    public void writeImageQualityRaw(final byte[] raw, final String label) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onImageQualityWritten(false, label, "请先连接相机。"));
                return;
            }
            try {
                int code = operations.writeVendorPropertyBlock(CanonImageQuality.IMAGE_FORMAT, raw);
                postImageQualityWritten(code, label, null);
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "画质写入失败";
                mainHandler.post(() -> listener.onImageQualityWritten(false, label, message));
            }
        });
    }

    private void postImageQualityWritten(int code, String label, String error) {
        final boolean ok = error == null && code == PROPERTY_WRITE_OK;
        final String failure = error != null ? error
                : "相机拒绝写入（响应码 " + String.format(Locale.ROOT, "0x%04X", code) + "）";
        mainHandler.post(() -> listener.onImageQualityWritten(ok, label, ok ? null : failure));
    }

    /**
     * 「恢复机身参数到基线」：把基线里<b>与当前值确实不同</b>的属性逐个写回。
     *
     * <p>为什么不复用 {@link #writeVendorPropertyBlock(int, byte[])}：那条路每写一个属性都会自动
     * 回读一次并回调 {@code onVendorPropertyBlockRead}，上层是按属性码分流给 HDR/对焦包围面板的，
     * 批量恢复会把这些面板刷成无关内容。这里走 {@code operations} 直写并只汇总一条结果。
     *
     * <p>只写「变了」的那些：既避免无谓地动相机（每次写入都要排空事件、停对焦、等一拍），
     * 也不会去写那些本次本来就没被改动过的属性。写失败不中断，逐个记下来一起报。
     */
    public void restoreVendorProperties(final VendorPropertySnapshot baseline) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onVendorPropertyRestoreFinished(0, 0, "请先连接相机。"));
                return;
            }
            if (baseline == null) {
                mainHandler.post(() -> listener.onVendorPropertyRestoreFinished(0, 0, "还没有基线快照。"));
                return;
            }
            try {
                VendorPropertySnapshot current = operations.snapshotVendorProperties();
                List<VendorPropertySnapshot.Diff> diffs = current.diff(baseline);
                int total = 0;
                int written = 0;
                StringBuilder failed = new StringBuilder();
                for (VendorPropertySnapshot.Diff diff : diffs) {
                    if (diff.kind != VendorPropertySnapshot.Diff.CHANGED
                            || diff.before == null || !diff.before.hasRaw()) {
                        continue;
                    }
                    total++;
                    try {
                        int code = operations.writeVendorPropertyBlock(
                                diff.before.propertyCode, diff.before.raw);
                        if (code == PROPERTY_WRITE_OK) {
                            written++;
                        } else {
                            appendFailed(failed, diff.before.propertyCode, code);
                        }
                    } catch (Exception error) {
                        appendFailed(failed, diff.before.propertyCode, 0);
                    }
                }
                // 画质可能被恢复成仅 RAW（或反之），卡上的可见文件集合也会随之变化。
                invalidateStorageCache();
                final int totalWritten = total;
                final int succeeded = written;
                final String failureList = failed.toString();
                mainHandler.post(() -> listener.onVendorPropertyRestoreFinished(
                        totalWritten, succeeded, failureList));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "恢复机身参数失败";
                mainHandler.post(() -> listener.onVendorPropertyRestoreFinished(0, 0, message));
            }
        });
    }

    private static void appendFailed(StringBuilder failed, int propertyCode, int code) {
        if (failed.length() > 0) failed.append('、');
        failed.append(VendorProperty.hex(propertyCode));
        if (code != 0) failed.append("（0x").append(String.format(Locale.ROOT, "%04X", code)).append('）');
    }

    /**
     * 把取景画面里的一个点设为对焦框（0x915B，<b>只移动对焦框、不触发对焦</b>）。
     * {@code x}/{@code y} 为取景帧像素坐标；这里按感光器分辨率换算成相机要求的感光器像素再下发，
     * 否则落点会差一个推流倍率（相机端表现为对焦框几乎没动）。
     */
    public void touchAfPosition(final int x, final int y) {
        executor.submit(() -> {
            if (!isConnected() || !liveViewActive) {
                mainHandler.post(() -> listener.onLiveViewStatus("实时取景未运行，无法移动对焦框。"));
                return;
            }
            int[] sensor = SensorPointMapper.toSensor(
                    x, y, operations.liveViewSensorSize(), liveViewFrameWidth, liveViewFrameHeight);
            try {
                if (sensor == null) {
                    // 没拿到感光器分辨率：只能按原坐标下发，落点大概率不对，明确告知用户并附段类型。
                    operations.touchAfPosition(x, y);
                    String segments = operations.liveViewSegmentTypes();
                    mainHandler.post(() -> listener.onLiveViewStatus(
                            "对焦框未换算（相机未上报感光器分辨率，取景段类型："
                                    + (segments == null ? "无" : segments)
                                    + "），已按取景帧坐标下发 (" + x + ", " + y + ")。"));
                    return;
                }
                operations.touchAfPosition(sensor[0], sensor[1]);
                final int sensorX = sensor[0];
                final int sensorY = sensor[1];
                mainHandler.post(() -> listener.onLiveViewStatus(
                        "对焦框已移到感光器像素 (" + sensorX + ", " + sensorY + ")：取景帧 ("
                                + x + ", " + y + ") 换算而来（0x915B；需相机为单点对焦方式才会响应）"));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "移动对焦框失败";
                mainHandler.post(() -> listener.onLiveViewStatus("移动对焦框失败：" + message));
            }
        });
    }

    /**
     * 先按取景帧像素坐标把对焦框移到用户点选的那一点，再触发一次对焦——「自动对焦」落在用户点的
     * 位置上，而不是相机当前的对焦点。取不到感光器分辨率时退化为 {@link #autofocus()}，
     * 并在状态栏说明没按取景点对焦。
     */
    public void autofocusAtPoint(final int x, final int y) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onLiveViewStatus("请先连接相机。"));
                mainHandler.post(listener::onAutofocusFinished);
                return;
            }
            mainHandler.post(() -> listener.onLiveViewStatus("正在按取景点自动对焦…"));
            int[] sensor = SensorPointMapper.toSensor(
                    x, y, operations.liveViewSensorSize(), liveViewFrameWidth, liveViewFrameHeight);
            boolean mapped = sensor != null;
            String failure = null;
            try {
                if (mapped) {
                    operations.autofocusAt(sensor[0], sensor[1]);
                } else {
                    operations.autofocus();
                }
            } catch (Exception error) {
                failure = error.getMessage() != null ? error.getMessage() : "对焦失败";
            }
            final String result = failure;
            if (result == null) {
                mainHandler.post(() -> listener.onLiveViewStatus(
                        mapped ? "已按取景点自动对焦完成" : "自动对焦完成（无感光器分辨率，未按取景点）"));
            } else {
                mainHandler.post(() -> listener.onLiveViewStatus("自动对焦失败：" + result));
            }
            mainHandler.post(listener::onAutofocusFinished);
        });
    }

    /**
     * 触发一次单次自动对焦（Canon EOS {@code 0x9154 EOS_DoAf}）。面板上的「自动对焦」
     * 按钮调用；对焦需要取景已开启，所以只在面板打开、取景运行时才有意义。
     */
    public void autofocus() {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onLiveViewStatus("请先连接相机。"));
                mainHandler.post(listener::onAutofocusFinished);
                return;
            }
            mainHandler.post(() -> listener.onLiveViewStatus("正在自动对焦…"));
            String failure = null;
            try {
                operations.autofocus();
            } catch (Exception error) {
                failure = error.getMessage() != null ? error.getMessage() : "对焦失败";
            }
            final String result = failure;
            mainHandler.post(() -> listener.onLiveViewStatus(
                    result == null ? "自动对焦完成" : "自动对焦失败：" + result));
            mainHandler.post(listener::onAutofocusFinished);
        });
    }

    /** 触发一次拍摄并自动取回新产生的 JPEG 原图（无 JPEG 时跳过，不保存 RAW）。 */
    public void captureAndFetch() {
        captureAndFetch(1);
    }

    /**
     * 触发一次拍摄并取回，适用于「一次快门产出多张」的机内连续拍摄。
     *
     * @param expectedShots 预计张数。1 走原来的「发现第一张就返回」；&gt;1 时收满或等相机
     *                      不再出新对象为止（见 {@code NewPhotoResolver#resolveBatch}）。
     *                      机内对焦包围/HDR 包围的张数由机身设定，App 这边只负责按它对量收图。
     */
    public void captureAndFetch(int expectedShots) {
        int shots = Math.max(1, expectedShots);
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onError("请先连接相机。", null));
                return;
            }
            TransferControl current = new TransferControl();
            control = current;
            exportFailureReported = false;
            // 实时取景开着时机身把画面独占给 App，此时全按会被拒（实测 0x2019 DeviceBusy）。
            // 触发快门前先退出取景，拍完再恢复；libgphoto2 的内存拍摄路径同样是在
            // 关掉取景输出后才让相机真正释放快门的。
            boolean resumeLiveView = liveViewActive;
            // 恢复取景是否已经完成。快门一响就立刻恢复（见下面），finally 只兜底，
            // 免得「暂停」状态跨过整个取回流程——那期间相机拒绝一切 0x9110 属性写入。
            boolean liveViewResumed = false;
            try {
                if (resumeLiveView) {
                    // 取帧先让开：暂停取景与按快门之间不该再往相机插帧请求。
                    liveViewSuspended = true;
                    listener.onStatus("暂停实时取景以释放快门…");
                    try {
                        operations.pauseLiveViewForCapture(liveViewSize);
                    } catch (Exception ignored) {
                        // 退出取景失败也照拍：真失败会体现在快门响应码里。
                    }
                }
                listener.onStatus("读取拍摄前句柄快照…");
                CameraStorage.Snapshot baseline = operations.snapshot(CAPTURE_BASELINE_ITEMS);
                NewPhotoResolver resolver = new NewPhotoResolver(
                        operations.cameraIdentity(), repository::isImported);
                resolver.recordBaseline(baseline);
                activeResolver = resolver;

                listener.onStatus("触发相机快门…");
                long[] directHandles = operations.capture();
                boolean multiShot = shots > 1;

                // 快门已经松开，取景不必再等文件取回：暂停期间相机停在 EVFOutputDevice=0
                // 的状态，此时任何 0x9110 属性写入（快门/光圈/ISO）都会被回 0x2019——
                // 「刚拍完就改参数报忙」就是这么来的。下载要花好几秒，必须先把取景还回去。
                // 但多张包围期间不得提前还取景：恢复取景（重发 0x9151/0xD1B0）会打断机内
                // 仍在跑的连拍序列（实测 8 张只剩 4 张）。这时留到取回结束再恢复。
                // 相机仍在忙时不能碰取景输出：机内 HDR/合成/连拍写卡期间重发 EVF 开启序列
                // （0x9151/0xD1B0）会打断剩余曝光，表现就是「只拍了一张、相机一直 busy、
                // 卡里也找不到成品」。先探一次，忙就等取回流程结束再恢复取景。
                if (resumeLiveView && (!multiShot || directHandles.length > 0)
                        && !isCameraStillBusy()) {
                    liveViewResumed = resumeLiveViewAfterCapture(1) == null;
                }

                if (directHandles.length > 0) {
                    // 相机把图像直接放进机身内存（Nikon USB SDRAM 路径）：句柄已知，
                    // 不需要句柄差集发现，但仍复用同一流式落盘与去重流程。
                    // 去重键带本次拍摄令牌：SDRAM 兜底句柄是固定值，不能跨拍摄复用。
                    String captureToken = "capture:" + System.nanoTime();
                    for (long handle : directHandles) {
                        current.throwIfCancelled();
                        CameraStorage.Item item = syntheticCaptureItem(handle);
                        listener.onStatus("取回机身内存中的新图像 " + item.filename + " …");
                        PhotoTransferWorker worker =
                                new PhotoTransferWorker(operations, repository);
                        PhotoTransferWorker.Result result = worker.transfer(
                                item,
                                (received, total) -> listener.onProgress(received, total),
                                current,
                                captureToken + ":" + handle);
                        if (result.alreadyImported) {
                            listener.onStatus(item.filename + " 已入库，跳过重复落盘。");
                            continue;
                        }
                        String note = exportToGalleryIfEnabled(result.file);
                        listener.onStatus("已保存 " + result.file.getName()
                                + " · " + result.bytes + " 字节 · "
                                + result.elapsedMillis + " ms" + note);
                        mainHandler.post(() -> listener.onFileSaved(
                                result.file, result.bytes, result.elapsedMillis, note));
                    }
                    return;
                }

                listener.onStatus(shots > 1
                        ? "等待相机写卡并按 " + shots + " 张收集新对象…"
                        : "等待相机写卡并发现新对象…");
                NewPhotoResolver.Resolution resolution = resolver.resolveBatch(
                        operations,
                        CaptureWaitPolicy.budgetMillisFor(shots),
                        multiShot ? CAPTURE_BATCH_POLL_MS : CAPTURE_POLL_INTERVAL_MS,
                        CAPTURE_BASELINE_ITEMS,
                        shots,
                        CaptureWaitPolicy.settleMillisFor(shots),
                        item -> !item.isVideo(),
                        current);

                // 多张批次的取景留到此刻才恢复（前面为了不打断连拍刻意没还）。
                // 单张这一路在上面「快门一响就恢复」已做完，这里不重复。
                if (resumeLiveView && !liveViewResumed) {
                    liveViewResumed = resumeLiveViewAfterCapture(1) == null;
                }

                // 只保存 JPEG：RAW（.cr2/.cr3/.nef…）等不落盘。
                // 相机设为仅 RAW 时只提示、不报错——这不算失败，只是没有可取回的格式。
                List<CameraStorage.Item> jpegs = new ArrayList<>();
                List<CameraStorage.Item> skipped = new ArrayList<>();
                for (CameraStorage.Item item : resolution.newItems) {
                    if (item.isJpeg()) jpegs.add(item);
                    else skipped.add(item);
                }
                if (jpegs.isEmpty()) {
                    if (!skipped.isEmpty()) {
                        listener.onStatus("本次未发现 JPEG，仅有 " + names(skipped)
                                + "；按设置（无 JPEG 不保存）跳过。");
                        return;
                    }
                    throw new Exception(
                            "超时未发现本次拍摄的新对象（可能仍在写卡、事件缺失或返回了不受支持的格式，"
                                    + "也可能是相机根本没执行快门）。"
                                    + captureDiagnosticsSuffix());
                }
                if (!skipped.isEmpty()) {
                    listener.onStatus("跳过非 JPEG 对象：" + names(skipped));
                }

                listener.onStatus("发现新照片："
                        + (resolution.discoveredByEvent ? "事件通道" : "轮询差集")
                        + " · " + names(jpegs));
                for (CameraStorage.Item item : jpegs) {
                    current.throwIfCancelled();
                    listener.onStatus("下载 " + item.filename
                            + "（声明 " + item.sizeBytes + " 字节）…");
                    PhotoTransferWorker worker =
                            new PhotoTransferWorker(operations, repository);
                    PhotoTransferWorker.Result result = worker.transfer(
                            item,
                            (received, total) -> listener.onProgress(received, total),
                            current);
                    if (result.alreadyImported) {
                        listener.onStatus(item.filename + " 已入库，跳过重复落盘。");
                        continue;
                    }
                    String note = exportToGalleryIfEnabled(result.file);
                    listener.onStatus("已保存 " + result.file.getName()
                            + " · " + result.bytes + " 字节 · "
                            + result.elapsedMillis + " ms" + note);
                    mainHandler.post(() -> listener.onFileSaved(
                            result.file, result.bytes, result.elapsedMillis, note));
                }
            } catch (InterruptedException interrupted) {
                mainHandler.post(() -> listener.onStatus(interruptedMessage(current)));
            } catch (Exception error) {
                mainHandler.post(() -> listener.onError(
                        error.getMessage() != null ? error.getMessage() : "拍摄取回失败", error));
            } finally {
                activeResolver = null;
                control = null;
                lastTransferFinishedAt = System.currentTimeMillis();
                // 快门可能已改变卡内对象，句柄缓存与完整扫描缓存都不能再用。
                invalidateStorageCache();
                // 兜底恢复：正常情况下上面「快门一响就恢复」已经做完了，只有那次失败
                // （或快门抛异常没走到那一步）才轮到这里重试一次。失败必须报出来——
                // 相机停在 EVFOutputDevice=0 时不接受任何 0x9110 属性写入。
                if (resumeLiveView && !liveViewResumed && liveViewActive && isConnected()) {
                    String failure = resumeLiveViewAfterCapture(2);
                    if (failure != null) {
                        String message = failure;
                        mainHandler.post(() -> listener.onStatus(
                                "恢复实时取景失败，参数调整可能被相机拒绝：" + message));
                    }
                }
                LiveViewPump pump = activePump;
                if (pump != null) pump.lastFrameAt = System.currentTimeMillis();
                // 恢复序列走完才放开取帧，免得帧请求插在相机切回取景输出的中间。
                liveViewSuspended = false;
                // 「未发现 JPEG」等分支不落盘也不报错，必须靠这条回调让界面恢复按钮。
                mainHandler.post(listener::onCaptureFinished);
            }
        });
    }

    /**
     * 快门后相机是否仍处于忙态（机内合成 / 写卡）。
     *
     * <p>用一次轻量句柄读取探路：回可重试的忙码说明快门序列还没走完，此时恢复取景
     * 会反过来打断它；其它异常按「不忙」处理，交给后续取回流程去报错。
     */
    private boolean isCameraStillBusy() {
        try {
            operations.snapshot(1);
            return false;
        } catch (PtpResponseException busy) {
            return busy.isTransient();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 拍摄后把实时取景还回去，失败返回原因（调用方报给用户）。
     *
     * <p>这一步不能静默吞掉：{@code pauseLiveViewForCapture} 会把 {@code EVFOutputDevice}
     * 清成 0，相机停在那个状态时会拒绝一切 {@code 0x9110} 属性写入（快门/光圈/ISO 三档
     * 全报 {@code 0x2019}），必须把「没恢复成功」告诉用户，而不是让他对着「参数写不进去」
     * 猜原因。调用方在快门刚响时只试一次（那时相机在写卡，试多了纯等），全部取回结束后
     * 再兜底多试一轮。
     *
     * @param attempts 最多尝试几次
     */
    private String resumeLiveViewAfterCapture(int attempts) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                operations.setLiveViewEnabled(true, liveViewSize);
                return null;
            } catch (Exception error) {
                if (attempt == attempts) {
                    return error.getMessage() != null ? error.getMessage() : "相机拒绝了取景开启";
                }
                sleepQuietly(LIVE_VIEW_ENABLE_BACKOFF_MS);
            }
        }
        return null;
    }

    /** 手动下载选中的相机对象；与拍摄路径一致，仅 JPEG 可下载，RAW/其他格式一律跳过。 */
    public void download(List<CameraStorage.Item> items) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onError("请先连接相机。", null));
                return;
            }
            if (items == null || items.isEmpty()) {
                mainHandler.post(() -> listener.onError("未选择要下载的对象。", null));
                return;
            }
            TransferControl current = new TransferControl();
            control = current;
            exportFailureReported = false;
            try {
                List<CameraStorage.Item> jpegs = new ArrayList<>();
                List<CameraStorage.Item> skipped = new ArrayList<>();
                for (CameraStorage.Item item : items) {
                    if (item.isJpeg()) jpegs.add(item);
                    else skipped.add(item);
                }
                if (jpegs.isEmpty()) {
                    listener.onStatus("所选对象不是 JPEG（" + names(skipped)
                            + "）；按设置（无 JPEG 不保存）已跳过，未下载。");
                    return;
                }
                if (!skipped.isEmpty()) {
                    listener.onStatus("仅 JPEG 可下载，跳过：" + names(skipped));
                }
                for (CameraStorage.Item item : jpegs) {
                    current.throwIfCancelled();
                    listener.onStatus("下载 " + item.filename + " …");
                    PhotoTransferWorker worker =
                            new PhotoTransferWorker(operations, repository);
                    PhotoTransferWorker.Result result = worker.transfer(
                            item,
                            (received, total) -> listener.onProgress(received, total),
                            current);
                    if (result.alreadyImported) {
                        listener.onStatus(item.filename + " 已入库，跳过。");
                        continue;
                    }
                    // 与拍摄路径一致：落盘成功后按用户开关导出到系统相册。
                    String note = exportToGalleryIfEnabled(result.file);
                    listener.onStatus("已保存 " + result.file.getName()
                            + " · " + result.bytes + " 字节 · " + result.elapsedMillis + " ms"
                            + note);
                    mainHandler.post(() -> listener.onFileSaved(
                            result.file, result.bytes, result.elapsedMillis, note));
                }
            } catch (InterruptedException interrupted) {
                mainHandler.post(() -> listener.onStatus(interruptedMessage(current)));
            } catch (Exception error) {
                mainHandler.post(() -> listener.onError(
                        error.getMessage() != null ? error.getMessage() : "下载失败", error));
            } finally {
                control = null;
                lastTransferFinishedAt = System.currentTimeMillis();
                mainHandler.post(listener::onDownloadFinished);
            }
        });
    }

    /**
     * 读取一页图片来源（JPG + RAW）（胶片模拟页专用）。
     *
     * <p>与 {@link #loadStoragePage} 共用执行器与句柄缓存，因此不会和其它相机事务交错；
     * 页大小固定 {@link #FILM_PAGE_SIZE}，避免胶片页的下拉框被整页几十项撑爆。
     */
    public void loadFilmPhotoPage(final int pageIndex, final StoragePageCallback callback) {
        executor.submit(() -> {
            final PtpOperationService session = operations;
            if (session == null || !isConnected()) {
                mainHandler.post(() -> callback.onError("请先连接相机。", null));
                return;
            }
            try {
                final CameraStorage.Page page = session.photoPage(FILM_PAGE_SIZE, pageIndex);
                mainHandler.post(() -> callback.onLoaded(page));
            } catch (Exception error) {
                mainHandler.post(() -> callback.onError(
                        error.getMessage() == null ? "读取照片列表失败" : error.getMessage(), error));
            }
        });
    }

    /** 图片来源取回回调：进度与结果都已切回主线程。 */
    public interface FilmSourceCallback {
        void onProgress(long received, long total);

        void onReady(File file);

        void onError(String message, Throwable error);
    }

    /**
     * 把一张相机照片（JPEG 或 RAW）取回到应用缓存目录，供胶片模拟读取。
     *
     * <p>RAW 有几十兆，JPEG 一般几兆，都不进 {@link PhotoRepository}（那是给可入库的 JPG 用的），
     * 只落在 {@code cacheDir/film_src} 下，下一次取回会替换同名句柄文件，
     * 系统空间紧张时也可以直接回收。相机未声明长度的按实际字节数收下。
     */
    public void fetchFilmSource(final CameraStorage.Item item, final FilmSourceCallback callback) {
        executor.submit(() -> {
            final PtpOperationService session = operations;
            if (session == null || !isConnected()) {
                mainHandler.post(() -> callback.onError("请先连接相机。", null));
                return;
            }
            if (item == null || (!item.isRaw() && !item.isJpeg())) {
                mainHandler.post(() -> callback.onError("所选对象既不是 JPEG 也不是 RAW。", null));
                return;
            }
            File staging = null;
            try {
                File directory = new File(context.getCacheDir(), "film_src");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("无法创建胶片缓存目录。");
                }
                File target = new File(directory, filmCacheName(item));
                staging = new File(directory, target.getName() + ".part");
                long received;
                try (FileOutputStream output = new FileOutputStream(staging)) {
                    received = session.download(
                            item.handle,
                            output,
                            (bytes, total) -> mainHandler.post(
                                    () -> callback.onProgress(bytes, total)));
                    output.flush();
                }
                if (item.sizeBytes > 0 && received != item.sizeBytes) {
                    throw new IOException("文件长度与相机声明不一致：声明 "
                            + item.sizeBytes + " 字节，实际 " + received + " 字节");
                }
                if (received <= 0) throw new IOException("相机返回了空文件。");
                if (target.exists() && !target.delete()) {
                    throw new IOException("无法覆盖同名胶片缓存文件。");
                }
                if (!staging.renameTo(target)) {
                    throw new IOException("无法提交胶片缓存文件。");
                }
                final File ready = target;
                mainHandler.post(() -> callback.onReady(ready));
            } catch (Exception error) {
                if (staging != null) staging.delete();
                final String message = error.getMessage() == null ? "照片取回失败" : error.getMessage();
                mainHandler.post(() -> callback.onError(message, error));
            }
        });
    }

    /** 胶片缓存文件名：句柄唯一且保留扩展名，模拟器按扩展名选择解码方式。 */
    private static String filmCacheName(CameraStorage.Item item) {
        String name = item.filename == null ? "photo" : item.filename;
        String safe = name.replaceAll("[\\\\/:?%*|\"<>]", "_");
        return item.handle + "-" + safe;
    }

    private static String interruptedMessage(TransferControl control) {
        String reason = control == null ? null : control.reason();
        return reason == null ? "已取消当前操作。" : "操作已中断：" + reason;
    }

    /**
     * 删除相机存储卡上的一组对象（<b>任意格式</b>都可删，与只下载 JPEG 的限制无关）。
     * 逐张调用标准 {@code 0x100B DeleteObject}；单张失败不中断整批，结束后清掉句柄缓存
     * （卡内已变）并回调 {@link Listener#onDeleteFinished}。
     */
    public void deleteObjects(final List<CameraStorage.Item> items) {
        executor.submit(() -> {
            final PtpOperationService session = operations;
            if (session == null || !isConnected()) {
                mainHandler.post(() -> listener.onError("请先连接相机。", null));
                mainHandler.post(() -> listener.onDeleteFinished(0, 0, ""));
                return;
            }
            if (items == null || items.isEmpty()) {
                mainHandler.post(() -> listener.onError("未选择要删除的对象。", null));
                mainHandler.post(() -> listener.onDeleteFinished(0, 0, ""));
                return;
            }
            int deleted = 0;
            List<String> failures = new ArrayList<>();
            final String cameraIdentity = session.cameraIdentity();
            for (CameraStorage.Item item : items) {
                try {
                    listener.onStatus("删除 " + item.filename + " …");
                    session.delete(item.handle);
                    // 句柄可能被相机复用给新照片：不丢掉旧去重键，新照片会被当成「已入库」跳过。
                    repository.forget(item.objectKey(cameraIdentity));
                    deleted++;
                } catch (Exception error) {
                    // 单张失败（受保护、写卡中、机型不支持）不中断整批，最后统一汇报。
                    failures.add(item.filename + "（" + describeError(error) + "）");
                }
            }
            // 卡内结构已变：句柄缓存与完整扫描缓存都要作废，否则重读会拿到已删对象。
            invalidateStorageCache();
            final int deletedCount = deleted;
            final String failedNames = String.join("\n", failures);
            mainHandler.post(() -> listener.onDeleteFinished(
                    deletedCount, failures.size(), failedNames));
        });
    }

    /** 异常转一句可读文案（无 message 时退回类名）。 */
    private static String describeError(Exception error) {
        String message = error == null ? null : error.getMessage();
        if (message != null && !message.isEmpty()) return message;
        return error == null ? "未知错误" : error.getClass().getSimpleName();
    }

    private static String names(List<CameraStorage.Item> items) {
        StringBuilder builder = new StringBuilder();
        for (CameraStorage.Item item : items) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(item.filename);
        }
        return builder.toString();
    }

    /**
     * 为机内内存对象（SDRAM 句柄）构造一个合成条目：这类句柄没有卡内 ObjectInfo，
     * 因此尺寸未知（-1，落盘时按实际字节数校验）、存储卷未知（0）。
     * 文件名带时间戳，便于人工辨认。
     */
    private static CameraStorage.Item syntheticCaptureItem(long handle) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        return new CameraStorage.Item(
                handle,
                0L,
                0,
                "CAPTURE_" + stamp + ".JPG",
                -1L,
                0,
                0,
                stamp,
                false);
    }

    // ── 心跳与自动重连 ──

    private void startHeartbeat() {
        startHeartbeat(ReconnectPolicy.HEARTBEAT_INTERVAL_MS);
    }

    private void startHeartbeat(long intervalMillis) {
        mainHandler.removeCallbacks(heartbeatRunnable);
        mainHandler.postDelayed(heartbeatRunnable, intervalMillis);
    }

    /** 连续 3 次心跳无响应 / 绑定网络丢失 → 判离线，进入重连态。 */
    private void enterReconnecting() {
        if (!connected || reconnecting || manualDisconnect) return;
        connectionGeneration++;
        // 重连期间相机会退出取景：先停循环，重连成功后由界面重新开启。
        invalidateLiveView();
        reconnecting = true;
        connected = false;
        heartbeat.reset();
        reconnectAttempt = 0;
        mainHandler.removeCallbacks(heartbeatRunnable);
        unregisterWifiNetworkCallback();
        unregisterSpecifierCallback();
        // 绑定网络随会话作废；候选缓存保留，供紧接的自动重连复用。
        sessionNetwork = null;
        TransferControl current = control;
        if (current != null) current.cancel("相机链路丢失");
        final CameraTransport session = transport;
        if (session != null) {
            session.abortTransport();
            executor.submit(() -> closeQuietly(session));
        }
        listener.onStatus("相机链路丢失，进入自动重连…");
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!reconnecting || manualDisconnect) return;
        reconnectAttempt++;
        long delay = backoffDelayMs(reconnectAttempt);
        mainHandler.post(() -> {
            if (!reconnecting || manualDisconnect) return;
            listener.onReconnecting(reconnectAttempt, delay);
        });
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private boolean connectAttemptActive(long generation, boolean reconnect) {
        if (manualDisconnect || !connecting || generation != connectionGeneration) {
            return false;
        }
        return reconnect == reconnecting;
    }

    // ── 相机 Wi‑Fi 精确路由（移植自 ZENCHE MainActivity） ──

    /** 写入相机 Wi‑Fi 凭据；SSID 为空表示不改网络，沿用系统已连的那条（connect 前调用）。 */
    public void setCameraWifiCredentials(String ssid, String passphrase) {
        cameraSsid = ssid == null ? "" : ssid.trim();
        cameraPassphrase = passphrase == null ? "" : passphrase;
    }

    /**
     * 是否用 {@link WifiNetworkSpecifier} 精确加入相机 AP（纯函数，便于契约测试）：
     * 仅 SDK 29+ 且填写了非空 SSID 时才走系统对话框，低版本 / 留空回退自动选网。
     */
    static boolean shouldUseSpecifier(int sdkInt, String ssid) {
        return sdkInt >= Build.VERSION_CODES.Q && isValidSsid(ssid);
    }

    /** SSID 是否有效：非 null 且去掉首尾空白后非空。 */
    static boolean isValidSsid(String ssid) {
        return ssid != null && !ssid.trim().isEmpty();
    }

    /**
     * 为当前会话绑定相机 Wi‑Fi 网络，并把 socket 工厂注入传输层。
     * 填了 SSID 且系统支持时优先弹「加入网络」对话框精确加入；用户取消 / 超时 / 密码错
     * 只回退到自动选网，不判失败。两条路径都只在拿到「首选网络」时注入其 SocketFactory，
     * 拿不到就交由传输层走默认路由。
     *
     * <p>在 executor 线程上执行；等待系统对话框会短暂阻塞该线程。
     */
    private void bindWifiNetwork(PtpIpTransport active, long generation, boolean reconnect) {
        if (shouldUseSpecifier(Build.VERSION.SDK_INT, cameraSsid)
                && connectAttemptActive(generation, reconnect)) {
            listener.onStatus("正在加入相机 Wi‑Fi「" + cameraSsid + "」…");
            // 先清掉上一次会话可能残留的回调/闸门，再武装本次的闸门。
            unregisterSpecifierCallback();
            CountDownLatch latch = new CountDownLatch(1);
            specifierResult.set(null);
            specifierLatch = latch;
            mainHandler.post(() -> requestSpecifierNetwork(latch));
            Network joined = awaitSpecifierNetwork(latch, SPECIFIER_TIMEOUT_MS);
            specifierLatch = null;
            if (joined != null) {
                // cameraWifiNetwork 已由 onAvailable 置好；这里只补会话绑定与 socket 工厂。
                sessionNetwork = joined;
                active.setSocketFactory(joined.getSocketFactory());
                return;
            }
            unregisterSpecifierCallback();
            if (!connectAttemptActive(generation, reconnect)) return;
            listener.onStatus("未加入相机 Wi‑Fi，改用系统已连网络…");
        }
        seedCameraWifiNetwork();
        registerWifiNetworkCallback();
        Network network = liveCameraWifiNetwork();
        sessionNetwork = network;
        active.setSocketFactory(network != null ? network.getSocketFactory() : null);
    }

    /** 弹出系统「加入相机 Wi‑Fi」对话框并发起请求；必须在主线程调用。 */
    private void requestSpecifierNetwork(CountDownLatch latch) {
        ConnectivityManager manager = connectivityManager;
        if (manager == null || manualDisconnect) {
            latch.countDown();
            return;
        }
        final long callbackGeneration = connectionGeneration;
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                // 只有仍是本次会话、且回调未过期时才认领这个网络；否则让 bindWifiNetwork 走回退。
                if (callbackGeneration == connectionGeneration
                        && specifierNetworkCallback == this) {
                    cameraWifiNetwork = network;
                    sessionNetwork = network;
                    specifierResult.set(network);
                }
                latch.countDown();
            }

            @Override public void onUnavailable() {
                // 用户取消 / 超时 / 密码错：由 bindWifiNetwork 走回退，不在此判失败。
                latch.countDown();
            }

            @Override public void onLost(Network network) {
                // specifier 模式下不注册通用回调，网络丢失只能靠这里感知。
                if (callbackGeneration != connectionGeneration) return;
                mainHandler.post(() -> {
                    Network bound = sessionNetwork != null ? sessionNetwork : cameraWifiNetwork;
                    if (cameraWifiNetwork != null && cameraWifiNetwork.equals(network)) {
                        cameraWifiNetwork = null;
                    }
                    if (bound == null || !bound.equals(network)) return;
                    if (specifierNetworkCallback == this && connected && !manualDisconnect) {
                        listener.onStatus("相机 Wi‑Fi 网络已丢失。");
                        enterReconnecting();
                    }
                });
            }
        };
        specifierNetworkCallback = callback;
        NetworkRequest request;
        try {
            WifiNetworkSpecifier.Builder specifier =
                    new WifiNetworkSpecifier.Builder().setSsid(cameraSsid);
            // 空密码视为开放网络；密码格式不合法时 setWpa2Passphrase 会抛，交由外层回退。
            if (!cameraPassphrase.isEmpty()) specifier.setWpa2Passphrase(cameraPassphrase);
            request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier.build())
                    .build();
        } catch (Exception ignored) {
            specifierNetworkCallback = null;
            latch.countDown();
            return;
        }
        try {
            // 三参重载自 API 26 起可用：超时后系统回调 onUnavailable，无需自己计时。
            manager.requestNetwork(request, callback, (int) SPECIFIER_TIMEOUT_MS);
        } catch (Exception ignored) {
            specifierNetworkCallback = null;
            latch.countDown();
        }
    }

    /** 等系统对话框结果（executor 线程阻塞，回调在主线程放行）；超时/中断返回 null。 */
    private Network awaitSpecifierNetwork(CountDownLatch latch, long timeoutMillis) {
        try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        return specifierResult.get();
    }

    private void registerWifiNetworkCallback() {
        if (wifiNetworkCallback != null || connectivityManager == null) return;
        final long callbackGeneration = connectionGeneration;
        wifiNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                mainHandler.post(() -> {
                    if (wifiNetworkCallback == this
                            && callbackGeneration == connectionGeneration) {
                        cameraWifiNetwork = network;
                    }
                });
            }

            @Override public void onLost(Network network) {
                mainHandler.post(() -> {
                    Network bound = sessionNetwork != null ? sessionNetwork : cameraWifiNetwork;
                    if (cameraWifiNetwork != null && cameraWifiNetwork.equals(network)) {
                        cameraWifiNetwork = null;
                    }
                    if (bound == null || !bound.equals(network)) return;
                    if (wifiNetworkCallback == this
                            && connected
                            && !manualDisconnect) {
                        listener.onStatus("相机 Wi‑Fi 网络已丢失。");
                        enterReconnecting();
                    }
                });
            }
        };
        try {
            connectivityManager.registerNetworkCallback(
                    new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .build(),
                    wifiNetworkCallback);
        } catch (Exception ignored) {
            wifiNetworkCallback = null;
        }
    }

    /** 同步种子化现有 Wi‑Fi 候选网络：onAvailable 是异步回调，不能假设先于 connect 执行。 */
    private void seedCameraWifiNetwork() {
        if (connectivityManager == null) return;
        Network fallback = null;
        try {
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities capabilities =
                        connectivityManager.getNetworkCapabilities(network);
                if (capabilities == null
                        || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    continue;
                }
                // 优先无互联网的 Wi‑Fi（相机 AP 典型形态）。
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    cameraWifiNetwork = network;
                    return;
                }
                fallback = network;
            }
        } catch (Exception ignored) {
            return;
        }
        if (fallback != null) cameraWifiNetwork = fallback;
    }

    /**
     * 取当前仍然有效的相机 Wi‑Fi 网络。网络可能已被系统切走（此时 Network 句柄失效），
     * 再拿它 getSocketFactory().createSocket() 绑定会直接抛 EPERM；先校验一次可避免。
     */
    private Network liveCameraWifiNetwork() {
        Network network = cameraWifiNetwork;
        if (network == null || connectivityManager == null) return null;
        try {
            return connectivityManager.getNetworkCapabilities(network) != null ? network : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void unregisterWifiNetworkCallback() {
        ConnectivityManager.NetworkCallback callback = wifiNetworkCallback;
        wifiNetworkCallback = null;
        if (callback == null || connectivityManager == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(callback);
        } catch (Exception ignored) {
        }
    }

    private void unregisterSpecifierCallback() {
        ConnectivityManager.NetworkCallback callback = specifierNetworkCallback;
        specifierNetworkCallback = null;
        if (callback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(callback);
            } catch (Exception ignored) {
            }
        }
        // 可能正有 connectAttempt 阻塞在闸门上：立刻放行，避免断开/退出被拖住。
        CountDownLatch latch = specifierLatch;
        specifierLatch = null;
        if (latch != null) latch.countDown();
    }

    /**
     * 连接期间持有 WifiLock：系统默认在息屏待机时可能让 Wi‑Fi 无线电休眠，
     * 大文件传输就会在中途断开。自动重连期间不释放——链路恢复需要无线电保持清醒。
     */
    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        if (wifiManager == null) return;
        try {
            if (wifiLock == null) {
                wifiLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "imagefetch:camera");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception ignored) {
            // 拿不到锁不阻断会话：只是失去息屏保活这一层保护。
        }
    }

    private void releaseWifiLock() {
        WifiManager.WifiLock lock = wifiLock;
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
        } catch (Exception ignored) {
        }
    }
}
