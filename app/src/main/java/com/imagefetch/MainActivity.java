package com.imagefetch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.imagefetch.capture.FocusBracketCalculator;
import com.imagefetch.capture.FocusBracketPlan;
import com.imagefetch.capture.FocusStackBatch;
import com.imagefetch.capture.HdrPlan;
import com.imagefetch.connect.CanonBleHandover;
import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.CanonExposure;
import com.imagefetch.ptp.CanonImageQuality;
import com.imagefetch.ptp.CapabilityReport;
import com.imagefetch.ptp.ColorTemperature;
import com.imagefetch.ptp.LiveViewSize;
import com.imagefetch.ptp.VendorProperty;
import com.imagefetch.ptp.VendorPropertySnapshot;
import com.imagefetch.ptp.WhiteBalanceChoices;
import com.imagefetch.storage.GalleryExporter;
import com.imagefetch.storage.JpegOrientation;
import com.imagefetch.ui.FocusBoxOverlayView;
import com.imagefetch.ui.FilmSimulationDialog;
import com.imagefetch.ui.PagedGridScroll;
import com.imagefetch.ui.PhotoGridAdapter;
import com.imagefetch.ui.PhotoViewerDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** IMAGE_FETCH 单页界面：连接（Wi‑Fi/USB）→ 拍摄并取回 → 浏览/预览/手动下载。 */
public final class MainActivity extends Activity implements CameraSessionCoordinator.Listener {
    private static final String USB_PERMISSION_ACTION = "com.imagefetch.USB_PERMISSION";
    private static final long USB_PERMISSION_TIMEOUT_MS = 35_000L;
    private static final int REQUEST_WRITE_STORAGE = 0x70;
    private static final int REQUEST_NOTIFICATIONS = 0x71;
    private static final int REQUEST_NEARBY_WIFI = 0x72;
    private static final int REQUEST_WIFI_PICKER = 0x73;
    /** 手动挑照片做景深合成（系统文件选择器，多选）。 */
    private static final int REQUEST_PICK_STACK = 0x74;

    /**
     * BLE 交接开关。已按官方顺序补齐会话初始化：00010006 写 App 名等回执 [2]、读 0001000B、
     * 身份三连写（UUID/昵称/类型）、REQUEST_AP_CONFIG、按能力位选择 START。
     * 打开后连接会先走蓝牙唤醒相机热点；出问题可随时改回 false 只走已保存的 Wi-Fi 凭据。
     */
    private static final boolean BLE_HANDOVER_ENABLED = true;

    /**
     * BLE 交接在 START 之前就失败（蓝牙关闭、扫不到广播）：相机根本没被要求开热点，
     * 回退入网只给一次短等待，避免「25s + 2s + 25s」干等一分钟。
     */
    private static final long BLE_FAILED_QUICK_SPECIFIER_TIMEOUT_MS = 12_000L;
    /** BLE 交接在 START 之后失败：热点可能已在启动，按常规首试时长等一次，仍不做自动重试。 */
    private static final long BLE_FAILED_STARTED_SPECIFIER_TIMEOUT_MS = 25_000L;

    private static final String PREFS_NAME = "image_fetch_prefs";
    private static final String PREF_EXPORT_TO_GALLERY = "export_to_gallery";
    private static final String PREF_PAGE_SIZE = "page_size";
    private static final String PREF_JPEG_ONLY = "jpeg_only";
    private static final String PREF_CAMERA_SSID = "camera_ssid";
    private static final String PREF_CAMERA_PASSWORD = "camera_password";
    /** 上次成功连接的 BSSID，与 camera_ssid 配套；换了热点就作废。 */
    private static final String PREF_CAMERA_BSSID = "camera_bssid";
    /** 旧版按 SSID 分键保存的 BSSID 前缀，成功写入新记录后清掉。 */
    private static final String PREF_LEGACY_BSSID_PREFIX = "wifi_bssid_";
    private static final String PREF_LIVE_VIEW_SIZE = "live_view_size";
    private static final String PREF_LIVE_VIEW_GRID = "live_view_grid";
    /** 机身参数基线快照（{@link VendorPropertySnapshot#toText()} 的文本）；空串表示没有基线。 */
    private static final String PREF_PROPERTY_BASELINE = "property_baseline";
    private static final int[] PAGE_SIZE_OPTIONS = {20, 50, 100};
    private static final int DEFAULT_PAGE_SIZE = PAGE_SIZE_OPTIONS[0];

    private CameraSessionCoordinator coordinator;
    private SharedPreferences preferences;
    private EditText hostField;
    private EditText portField;
    /**
     * 程序自动回填进地址框的相机 IP（发现相机时写入）。断开后这个值还留在框里，
     * 但它不是用户手填的——连接时不能因为「框里有字」就跳过 BLE 交接，
     * 否则相机会话结束后热点已关，Specifier 只会干等一个不存在的网络。
     */
    private String autoFilledHost = "";
    /**
     * 相机 Wi‑Fi 凭据只持久化、不在界面上显示：用户到系统 Wi‑Fi 页面自己选网，
     * 名称回来后自动记下。为空表示不改网络，仍用系统已连上的那条。
     */
    private Button pickWifiButton;
    /** 是否刚跳去过系统 Wi‑Fi 设置：回来时据此做一次名称回填并给出提示。 */
    private boolean wifiPickerLaunched;
    /** 因缺「定位」权限而暂缓的「选择 Wi‑Fi」跳转，授权回调后补发。 */
    private boolean pendingWifiPicker;
    /** 因缺「附近的设备」权限而暂缓的 Wi‑Fi 连接，授权回调后补发。 */
    private boolean pendingWifiConnect;
    /** 自动连接：检测到「无互联网的 Wi‑Fi」（相机 AP 的典型形态）就自动发现并连。 */
    private ConnectivityManager autoConnectManager;
    private ConnectivityManager.NetworkCallback autoConnectCallback;
    /** 已经为哪个网络自动连过：防止同一网络反复触发，直到网络丢失/重连。 */
    private Network autoConnectLastNetwork;
    /** 用户手动断开或改走 USB 后，本次运行不再自动连；再点连接即解除。 */
    private boolean autoConnectSuppressed;
    /** 蓝牙唤醒相机 Wi‑Fi（官方 App 的 BLE handover）：连接期间保持，断开时释放。 */
    private CanonBleHandover bleHandover;
    /** 本次会话是否由 Wi‑Fi 发起（连接成功后据此反写 Wi‑Fi 名称；USB 会话与本机 Wi‑Fi 无关）。 */
    private boolean wifiSession;
    /** 本次发起连接时用的凭据：只有真正连上相机才写进偏好，保证只留最后一次成功记录。 */
    private String pendingWifiSsid = "";
    private String pendingWifiPassword = "";
    private Button connectButton;
    private Button connectUsbButton;
    private Button captureButton;
    private Button refreshButton;
    private Button downloadButton;
    private Button deleteButton;
    private CheckBox exportToGalleryBox;
    private Button prevPageButton;
    private Button nextPageButton;
    private Button fullScanButton;
    private Spinner pageSizeSpinner;
    private Spinner formatFilterSpinner;
    private TextView pageInfoView;
    private TextView outputDirView;
    private ProgressBar progressBar;
    private TextView statusView;
    private TextView selectionCountView;
    private PhotoGridAdapter gridAdapter;
    /** 横向翻页的缩略图网格容器。 */
    private PagedGridScroll gridPager;
    private LinearLayout gridPagerContent;
    private TextView gridIndexView;
    /** 每屏能放几张（3 列 × N 行），用于把照片序号换算成屏号。 */
    private int gridPageCapacity;
    /** 网格每行的格子数（列数）与每屏行数：把滚动位置换算成实际可见的首尾照片。 */
    private int gridColumns;
    private int gridRowsPerScreen;
    /** 图片栏代数：换页/断链时自增，用来丢弃迟到的铺格任务与缩略图解码结果。 */
    private int gridGeneration;
    /** 句柄 → 网格单元：缩略图/选中状态到达时就地更新，不重建整页。 */
    private final Map<Long, View> gridCells = new HashMap<>();
    /** 全屏看图页；不持有图片引用，关掉即释放。 */
    private PhotoViewerDialog viewerDialog;
    /** 胶片模拟独立页；关闭后置空。 */
    private FilmSimulationDialog filmDialog;
    /** 侧栏里的「胶片模拟」项：可只用手机图片，所以不随连接状态置灰。 */
    private TextView filmItem;

    /** 遥控拍摄面板（点「拍摄并取回」打开）；关闭后置空。 */
    private AlertDialog captureDialog;
    /** P0 能力探测报告弹窗（侧栏项 / 长按状态文字打开）；关闭后置空。 */
    private AlertDialog capabilityDialog;
    private TextView capabilityMessageView;
    /** 侧栏（「拍摄选项」）里的「能力探测」项；未连接时置灰。 */
    private TextView capabilityItem;
    /** 侧栏里的「属性差分」项；未连接时置灰。 */
    private TextView diffItem;
    /** 侧栏里的「拍摄」项（基础拍摄入口）；未连接时置灰。 */
    private TextView captureItem;
    /** 侧栏里的「对焦包围」项；未连接时置灰。 */
    private TextView bracketItem;
    /** 侧栏里的「HDR 拍摄」项；未连接时置灰。 */
    private TextView hdrItem;
    /** HDR 设置面板（0xD13D）；关闭后置空。 */
    private AlertDialog hdrDialog;
    private TextView hdrReportView;
    private EditText hdrModeField;
    /**
     * 最近一次从相机读回的 0xD13D 原始 24 字节。写入时以它为底稿，只覆盖已证实的偏移 4，
     * 其余 20 字节原样带回相机。没有底稿就不允许写入。
     */
    private byte[] hdrBlock;
    /** 刚写下去的 24 字节，用于写入后自动回读核对；null 表示这次读取不是回读。 */
    private byte[] pendingHdrWrite;
    /** 对焦包围设置面板（0xD208）；关闭后置空。 */
    private AlertDialog focusBracketDialog;
    private TextView focusReportView;
    private EditText focusEnabledField;
    private EditText focusCountField;
    private EditText focusIncrementField;
    private EditText focusSmoothingField;
    /** 对焦包围参数计算器面板；关闭后置空。它叠在对焦包围面板之上，关掉它不影响下面的面板。 */
    private AlertDialog focusCalculatorDialog;
    private TextView calcReportView;
    private EditText calcFocalField;
    private EditText calcApertureField;
    private EditText calcNearField;
    private EditText calcFarField;
    private EditText calcOverlapField;
    /** 最近一次成功的计算结果；「填入对焦包围面板」用它。 */
    private FocusBracketCalculator.Result calcResult;
    /**
     * 最近一次从相机读回的 0xD208 原始 24 字节。写入时以它为底稿，只覆盖已证实的偏移 8
     * 与旁证的偏移 12/16/20，其余 8 字节原样带回相机。没有底稿就不允许写入。
     */
    private byte[] focusBracketBlock;
    /** 刚写下去的 0xD208 的 24 字节，用于写入后自动回读核对；null 表示这次读取不是回读。 */
    private byte[] pendingFocusBracketWrite;
    /** 对焦包围面板的「拍摄并取回」：写入成功后立刻收起包围面板并开拍。 */
    private boolean pendingShootAfterWrite;
    /** 拍摄面板上的「预计张数」输入框；面板关闭后置空。 */
    private EditText captureShotsField;
    /** 上次用过的预计张数，跨面板开关保留；对焦包围/HDR 包围下省得每次重填。 */
    private int expectedShots = 1;
    /**
     * 侧栏里的「景深合成自检」项（临时验证入口）。
     * 它跑的是手机本地的 Python/OpenCV，与相机无关，所以不随连接状态置灰。
     */
    private TextView stackCheckItem;
    /** 侧栏里的「选照片做景深合成」项：拿相册里的旧照片试合成，同样不随连接状态置灰。 */
    private TextView stackPickItem;
    /** 景深合成自检面板；关闭后置空。 */
    private AlertDialog stackDialog;
    private TextView stackReportView;
    private ImageView stackImageView;
    /** 本次对焦包围批次的落盘文件收集器；每次拍摄前开窗，批次结束关窗。 */
    private final FocusStackBatch stackBatch = new FocusStackBatch();
    /** 合成进行中：避免连拍/重入同时跑两份 Python 合成。 */
    private boolean stackRunning;
    /** 最近一次成功写出的合成图；结果面板的「再次导出到相册」用它。 */
    private File lastStackResultFile;
    /** 景深合成结果面板；关闭后置空。 */
    private AlertDialog stackResultDialog;
    private TextView stackResultReportView;
    private ImageView stackResultImageView;
    /** 侧栏容器与遮罩；未打开时均为 GONE。零 AndroidX 工程不能用 DrawerLayout，手写滑出。 */
    private View drawerPanel;
    private View drawerScrim;
    private boolean drawerOpen;
    /** 属性差分弹窗（机身改设置前后对比）；关闭后置空。 */
    private AlertDialog diffDialog;
    private TextView diffMessageView;
    /** 差分基线快照；null 表示还没取。 */
    private VendorPropertySnapshot diffBaseline;
    /** 下一次快照回调是「取基线」还是「对比」——回调本身分不清这两件事。 */
    private boolean snapshotIsBaseline;
    /** 下一次快照回调是为了「恢复基线」前的差异统计（数出将写回几个属性）。 */
    private boolean awaitingRestoreCount;
    private ImageView liveViewImage;
    /** 取景预览容器：只靠它给画面定高，让方框覆盖层与 ImageView 等大叠放。 */
    private FrameLayout liveViewFrame;
    /** 对焦点小方框覆盖层；面板关闭后置空。 */
    private FocusBoxOverlayView focusBoxOverlay;
    /** 取景三分线开关；面板关闭后置空。 */
    private CheckBox liveViewGridCheck;
    /** 最近一次下发的对焦点帧坐标；NaN 表示还没点过（面板重开时复位）。 */
    private float lastFocusFrameX = Float.NaN;
    private float lastFocusFrameY = Float.NaN;
    private TextView liveViewStatusView;
    private Button captureShootButton;
    /** 面板上的「自动对焦」按钮；对焦进行中禁用，结束后恢复。关闭面板后置空。 */
    private Button captureAutofocusButton;
    /** 取景画面尺寸下拉框（相机端推流分辨率）；面板关闭后置空。 */
    private Spinner liveViewSizeSpinner;
    /** 对焦方式（0xD1BA）下拉框；面板关闭后置空。 */
    private Spinner afModeSpinner;
    /** 对焦方式下拉框各项对应的机上编码值（与选项同序）；未读到时为 null。 */
    private int[] afModeValues;
    /**
     * 下拉框当前由程序设定的对焦方式编码值。选中回调可能是异步到达的（setAdapter/setSelection
     * 之后才触发），用它把「程序填充」与「用户点选」区分开——否则刚读回来的当前值会被当成
     * 用户选择又写回相机。
     */
    private int lastAppliedAfValue;
    /** 画质（0xD120）下拉框；面板关闭后置空。 */
    private Spinner imageQualitySpinner;
    /** 画质下拉框各项（与相机给的选项同序，带显示名与写回用原始字节）；未读到时为 null。 */
    private List<CanonImageQuality.Option> imageQualityOptions;
    /** 相机当前画质是否会产生 JPEG；未知时为 true（不能因为读不到就把快门拦下）。 */
    private boolean imageQualityCurrentHasJpeg = true;
    /**
     * 下拉框当前由程序设定的画质选项下标。-1 表示未设定。用来区分「程序填充」与「用户点选」，
     * 写法与 {@link #lastAppliedAfValue} 一致（选中回调可能是 setAdapter/setSelection 之后才到）。
     */
    private int lastAppliedImageQualityIndex = -1;
    /** 画质不含 JPG 时的琥珀色警告行；面板关闭后置空。 */
    private TextView imageQualityWarnView;
    /** 对焦包围批次进度行；面板关闭后置空。 */
    private TextView stackProgressView;
    /** 对焦包围批次单张预览；面板关闭后置空。 */
    private ImageView stackPreviewView;
    /** 对焦包围预览缩略图（面板关闭后回收）。 */
    private Bitmap stackPreviewBitmap;
    /** 缩略图解码线程池：单线程串行下采样解码，绝不把整图读进 Java 堆。 */
    private final ExecutorService thumbnailExecutor = Executors.newSingleThreadExecutor();
    private final List<ExposureRow> exposureRows = new ArrayList<>();
    /** 当前显示的取景帧；换帧后延迟回收，避免渲染线程仍在读它。 */
    private Bitmap liveViewBitmap;
    /** 拍摄取回进行中：面板上的快门按钮据此防重复触发。 */
    private boolean captureRunning;
    /** 「切到含 JPG 的画质并继续」这条路上，等画质写入成功后再跑的真正拍摄。 */
    private Runnable pendingShootAfterQualityWrite;
    /** 面板上的「相机设置」按钮；面板关闭后置空。 */
    private Button captureCameraMenuButton;
    /** 「相机设置」菜单弹窗（白平衡等）；拍摄面板关闭时一并收起。 */
    private AlertDialog cameraMenuDialog;
    /** 白平衡（0xD109）下拉框；菜单关闭后置空。 */
    private Spinner whiteBalanceSpinner;
    /** 菜单里的状态行（读取/切换结果都写这里）；菜单关闭后置空。 */
    private TextView cameraMenuStatusView;
    /** 白平衡下拉框各项对应的机上编码值（与选项同序）；未读到时为 null。 */
    private int[] whiteBalanceValues;
    /**
     * 下拉框当前由程序设定的白平衡编码值。语义同 {@link #lastAppliedAfValue}：
     * 把「程序填充」与「用户点选」区分开，且<b>写入成功后才推进</b>，失败后能原地重试。
     */
    private int lastAppliedWhiteBalanceValue = Integer.MIN_VALUE;
    /** 色温输入小节（仅白平衡切到「色温」档时可见）；菜单关闭后置空。 */
    private View colorTemperatureSection;
    /** 色温 K 值输入框；菜单关闭后置空。 */
    private EditText colorTemperatureInput;
    /**
     * 色温小节当前是否已可见。用它把「首次切到色温档读一次相机」与「反复选中同一档」
     * 区分开，避免每次选中回调都多读一次相机。
     */
    private boolean colorTemperatureSectionShown;
    /** 曝光写入的合并窗口：连点 −/+ 只把最终值发给相机一次。 */
    private static final long EXPOSURE_WRITE_DEBOUNCE_MS = 250L;
    private final Handler deferredRecycle = new Handler(Looper.getMainLooper());
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());

    /** 当前页序号（从 0 开始）与是否处于“完整扫描”模式。 */
    private int currentPage;
    private boolean fullScanMode;
    /** 图片栏是否只显示 JPG（默认开：只有 JPG 能下载，RAW 显示出来也没法用）。
     *  同时决定分页范围——「仅 JPG」时每页就是 {@code pageSize} 张 JPG。 */
    private boolean jpegOnly = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hostField = findViewById(R.id.host);
        portField = findViewById(R.id.port);
        pickWifiButton = findViewById(R.id.pickWifi);
        connectButton = findViewById(R.id.connect);
        connectUsbButton = findViewById(R.id.connectUsb);
        captureButton = findViewById(R.id.capture);
        refreshButton = findViewById(R.id.refresh);
        downloadButton = findViewById(R.id.download);
        deleteButton = findViewById(R.id.delete);
        exportToGalleryBox = findViewById(R.id.exportToGallery);
        prevPageButton = findViewById(R.id.prevPage);
        nextPageButton = findViewById(R.id.nextPage);
        fullScanButton = findViewById(R.id.fullScan);
        pageSizeSpinner = findViewById(R.id.pageSize);
        formatFilterSpinner = findViewById(R.id.formatFilter);
        pageInfoView = findViewById(R.id.pageInfo);
        progressBar = findViewById(R.id.progress);
        statusView = findViewById(R.id.status);
        // 能力探测 / 属性差分收进侧栏（主页面底部「拍摄选项」打开）；长按状态文字仍是备用入口。
        statusView.setOnLongClickListener(view -> {
            showCapabilityReport();
            return true;
        });
        setUpShotPanel();
        applySystemBarInsets();
        selectionCountView = findViewById(R.id.selectionCount);
        gridPager = findViewById(R.id.gridPager);
        gridPagerContent = findViewById(R.id.gridPagerContent);
        gridIndexView = findViewById(R.id.gridIndex);
        gridPager.setOnScreenSettledListener(this::updateGridIndex);

        gridAdapter = new PhotoGridAdapter(this);
        gridAdapter.setSelectionListener(count -> {
            selectionCountView.setText(
                    count == 0 ? getString(R.string.selected_count) : "已选 " + count + " 张");
            refreshGridSelection();
        });

        coordinator = new CameraSessionCoordinator(
                this, this::ensureUsbPermission, this);

        outputDirView = findViewById(R.id.outputDir);

        // 开关与每页张数在本次启动前先恢复，再挂监听（避免恢复动作自身触发写盘）。
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // 上次的机身参数基线快照（若存过）：重开 App 后仍能直接对比/恢复。
        diffBaseline = VendorPropertySnapshot.fromText(
                preferences.getString(PREF_PROPERTY_BASELINE, null));
        pickWifiButton.setOnClickListener(view -> showCameraWifiDialog());
        boolean export = preferences.getBoolean(PREF_EXPORT_TO_GALLERY, true);
        int configuredPageSize = preferences.getInt(PREF_PAGE_SIZE, DEFAULT_PAGE_SIZE);
        setUpPageSizeSpinner(configuredPageSize);
        setUpFormatFilterSpinner(preferences.getBoolean(PREF_JPEG_ONLY, true));

        exportToGalleryBox.setChecked(export);
        coordinator.setExportToGallery(export);
        updateStorageLabels();
        exportToGalleryBox.setOnCheckedChangeListener((view, checked) -> {
            coordinator.setExportToGallery(checked);
            preferences.edit().putBoolean(PREF_EXPORT_TO_GALLERY, checked).apply();
            updateStorageLabels();
            if (checked) requestLegacyStoragePermissionIfNeeded();
        });
        if (export) requestLegacyStoragePermissionIfNeeded();

        prevPageButton.setOnClickListener(view -> goToPage(currentPage - 1));
        nextPageButton.setOnClickListener(view -> goToPage(currentPage + 1));
        fullScanButton.setOnClickListener(view -> {
            if (!coordinator.isConnected()) {
                showError("请先连接相机。", null);
                return;
            }
            // 完整扫描：整卡枚举后内存分页，作为“快速分页可能漏图”的兜底。
            fullScanMode = true;
            coordinator.invalidateStorageCache();
            goToPage(0);
        });

        connectButton.setOnClickListener(view -> {
            if (coordinator.isConnected()) {
                // 用户明确断开：本次运行不再自动连，避免刚断就被系统网络回调拉回去。
                autoConnectSuppressed = true;
                coordinator.disconnect();
                showReconnectHint();
                return;
            }
            // 手动连接解除抑制，并允许重新为当前网络自动发现。
            autoConnectSuppressed = false;
            autoConnectLastNetwork = null;
            int port = parsePort(portField.getText().toString().trim());
            // 地址留空表示自动发现：连上相机 Wi‑Fi 后按网段探测 15740。
            if (port <= 0) {
                showError("请输入有效的端口。", null);
                return;
            }
            connectWifi(false);
        });

        connectUsbButton.setOnClickListener(view -> {
            if (coordinator.isConnected()) {
                coordinator.disconnect();
                return;
            }
            // 改走 USB 时不要再被 Wi‑Fi 自动连接打断。
            autoConnectSuppressed = true;
            setBusy(true);
            updateConnectButton(false);
            wifiSession = false;
            coordinator.connect(CameraSessionCoordinator.TransportKind.USB, "", 0);
        });

        // 拍摄取回在遥控面板里触发：面板同时提供实时取景与快门/光圈/ISO 调整。
        // 主界面按钮与侧栏「拍摄」项共用这一个入口。
        captureButton.setOnClickListener(view -> openCapturePanel());

        refreshButton.setOnClickListener(view -> {
            if (!coordinator.isConnected()) {
                showError("请先连接相机。", null);
                return;
            }
            // 手动刷新：卡内对象可能已变，句柄与完整扫描缓存都要作废。
            coordinator.invalidateStorageCache();
            goToPage(currentPage);
        });

        downloadButton.setOnClickListener(view -> {
            List<CameraStorage.Item> selected = gridAdapter.selectedItems();
            if (selected.isEmpty()) {
                showError("请先在图片栏点选要下载的图片。", null);
                return;
            }
            progressBar.setProgress(0);
            setBusy(true);
            coordinator.download(selected);
        });

        deleteButton.setOnClickListener(view -> confirmDeleteSelected());
    }

    /**
     * 删除所选：删除的是相机存储卡上的文件，删掉不可恢复，因此必须先二次确认。
     * 与下载不同，<b>任意格式</b>都可删（CR3/RAW/视频也能选、也能删）。
     */
    private void confirmDeleteSelected() {
        if (!coordinator.isConnected()) {
            showError("请先连接相机。", null);
            return;
        }
        final List<CameraStorage.Item> selected = gridAdapter.selectedItems();
        if (selected.isEmpty()) {
            showError("请先在图片栏点选要删除的图片。", null);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(
                        R.string.delete_confirm_message, selected.size(), previewNames(selected)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_confirm_action, (dialog, which) -> {
                    statusView.setText("正在删除…");
                    setBusy(true);
                    coordinator.deleteObjects(selected);
                })
                .show();
    }

    /** 确认弹窗里只列前几个文件名，避免选中很多时弹窗被撑爆。 */
    private static String previewNames(List<CameraStorage.Item> items) {
        StringBuilder text = new StringBuilder();
        int limit = Math.min(items.size(), 5);
        for (int i = 0; i < limit; i++) {
            if (text.length() > 0) text.append("\n");
            text.append(items.get(i).filename);
        }
        if (items.size() > limit) {
            text.append("\n… 等共 ").append(items.size()).append(" 个文件");
        }
        return text.toString();
    }

    /** 按当前模式读取指定页；快速分页只读该页，完整扫描首次整卡枚举后内存分页。 */
    private void goToPage(int pageIndex) {
        final int target = Math.max(0, pageIndex);
        setBusy(true);
        statusView.setText("正在读取存储卡…");
        coordinator.loadStoragePage(
                target,
                fullScanMode,
                jpegOnly,
                new CameraSessionCoordinator.StoragePageCallback() {
                    @Override public void onLoaded(CameraStorage.Page page) {
                        setBusy(false);
                        currentPage = page.pageIndex;
                        populate(page);
                        updatePager(page);
                        statusView.setText("读取完成 · " + scopeLabel(page)
                                + (page.complete ? "（完整扫描）" : "（快速分页）"));
                    }

                    @Override public void onError(String message, Throwable error) {
                        setBusy(false);
                        statusView.setText("读取失败 · " + message);
                    }
                });
    }

    /** 分页范围的说明文案：JPG 模式下总数是 JPG 张数，未筛查完时说明还可能更多。 */
    private String scopeLabel(CameraStorage.Page page) {
        if (!jpegOnly) return "共 " + page.totalCount + " 张";
        return page.moreUnscanned
                ? "JPG 已筛出 " + page.totalCount + " 张（还有对象未筛查）"
                : "JPG 共 " + page.totalCount + " 张";
    }

    private void updatePager(CameraStorage.Page page) {
        pageInfoView.setText(String.format(
                Locale.ROOT,
                "第 %d / %d 页 · %s%s",
                page.pageIndex + 1,
                page.pageCount(),
                scopeLabel(page),
                page.complete ? "（完整扫描）" : ""));
        prevPageButton.setEnabled(page.hasPrevious());
        nextPageButton.setEnabled(page.hasNext());
    }

    /**
     * 全屏看图：交给独立的 PhotoViewerDialog，先立即显示已缓存的缩略图，
     * 再异步向相机取原图替换（RAW/视频或取图失败时保持缩略图）。
     */
    private void showPreview(int position) {
        if (position < 0 || position >= gridAdapter.getCount()) return;
        viewerDialog = new PhotoViewerDialog(this, gridAdapter, position,
                (item, result) -> coordinator.loadPreview(item,
                        (handle, bitmap) -> result.onPreview(handle, bitmap)));
        viewerDialog.setOnPageChangedListener(this::scrollToPhoto);
        viewerDialog.setActionListener(new PhotoViewerDialog.ActionListener() {
            @Override public void onDownload(CameraStorage.Item item) {
                downloadFromViewer(item);
            }

            @Override public void onDelete(CameraStorage.Item item) {
                confirmDeleteFromViewer(item);
            }
        });
        viewerDialog.setOnDismissListener(ignored -> viewerDialog = null);
        viewerDialog.show();
    }

    /**
     * 全屏看图里直接下载当前这张：只处理 JPEG；发起后收起看图页，
     * 让主界面显示传输进度与最终的「已保存」结果。
     */
    private void downloadFromViewer(CameraStorage.Item item) {
        if (!coordinator.isConnected()) {
            showError("请先连接相机。", null);
            return;
        }
        if (!item.isJpeg()) {
            showError("当前只支持取回 JPG；RAW 或视频请在相机上处理。", null);
            return;
        }
        if (viewerDialog != null && viewerDialog.isShowing()) viewerDialog.dismiss();
        progressBar.setProgress(0);
        setBusy(true);
        coordinator.download(Collections.singletonList(item));
    }

    /** 全屏看图里直接删除当前这张：与批量删除走同一条确认与删除流程，只是范围缩到一张。 */
    private void confirmDeleteFromViewer(CameraStorage.Item item) {
        if (!coordinator.isConnected()) {
            showError("请先连接相机。", null);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_confirm_message, 1, item.filename))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_confirm_action, (dialog, which) -> {
                    if (viewerDialog != null && viewerDialog.isShowing()) viewerDialog.dismiss();
                    statusView.setText("正在删除…");
                    setBusy(true);
                    coordinator.deleteObjects(Collections.singletonList(item));
                })
                .show();
    }

    /** 打开「胶片模拟」独立页：页面自己读 RAW 列表、下载、模拟与导出。 */
    private void openFilmSimulation() {
        if (filmDialog != null) {
            filmDialog.dismiss();
            filmDialog = null;
        }
        FilmSimulationDialog dialog = new FilmSimulationDialog(this, coordinator);
        dialog.setOnDismissListener(ignored -> filmDialog = null);
        filmDialog = dialog;
        dialog.show();
    }

    // ── 遥控拍摄面板（实时取景 + 快门/光圈/ISO） ──

    /** 基础拍摄入口（主界面「拍摄并取回」按钮与侧栏「拍摄」项共用）。未连接时只提示。 */
    private void openCapturePanel() {
        if (coordinator == null || !coordinator.isConnected()) {
            showError("请先连接相机。", null);
            return;
        }
        showCapturePanel();
    }

    /**
     * 打开遥控拍摄面板：实时取景预览 + 三档曝光参数 + 「拍摄并取回」。
     * 打开即开取景并读三档当前值；关闭（含链路断开）时停取景并回收最后一帧。
     */
    private void showCapturePanel() {
        if (captureDialog != null) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_capture, null);
        liveViewImage = content.findViewById(R.id.liveViewImage);
        liveViewFrame = content.findViewById(R.id.liveViewFrame);
        focusBoxOverlay = content.findViewById(R.id.focusBoxOverlay);
        liveViewGridCheck = content.findViewById(R.id.liveViewGrid);
        lastFocusFrameX = Float.NaN;
        lastFocusFrameY = Float.NaN;
        liveViewStatusView = content.findViewById(R.id.liveViewStatus);
        captureShootButton = content.findViewById(R.id.captureShoot);
        captureCameraMenuButton = content.findViewById(R.id.captureCameraMenu);
        captureAutofocusButton = content.findViewById(R.id.captureAutofocus);
        liveViewSizeSpinner = content.findViewById(R.id.liveViewSize);
        afModeSpinner = content.findViewById(R.id.afMode);
        imageQualitySpinner = content.findViewById(R.id.imageQuality);
        imageQualityWarnView = content.findViewById(R.id.imageQualityWarn);
        stackProgressView = content.findViewById(R.id.stackProgress);
        stackPreviewView = content.findViewById(R.id.stackPreview);
        captureShotsField = content.findViewById(R.id.captureShots);
        captureShotsField.setText(String.valueOf(expectedShots));

        // 取景是 3:2 画面：给黑底定一个固定高度，换帧时弹窗高度才不会跟着抖动。
        // 比例不能太大——预览是弹窗里最高的一块，占满后下面的档位行和拍摄按钮会被挤出
        // 可视区（表现为「按钮被压缩」）。高度设在容器上，让方框覆盖层与画面同尺寸。
        ViewGroup.LayoutParams frameParams = liveViewFrame.getLayoutParams();
        frameParams.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.28f);
        liveViewFrame.setLayoutParams(frameParams);

        // 三分线开关：状态记在本机，下次打开拍摄面板沿用。
        boolean showGrid = preferences.getBoolean(PREF_LIVE_VIEW_GRID, false);
        liveViewGridCheck.setChecked(showGrid);
        focusBoxOverlay.setGridVisible(showGrid);
        liveViewGridCheck.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(PREF_LIVE_VIEW_GRID, checked).apply();
            if (focusBoxOverlay != null) focusBoxOverlay.setGridVisible(checked);
        });

        // 点按预览只移动对焦框、不触发对焦（用户明确要求：对焦另走「自动对焦」按钮）。
        liveViewImage.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                moveFocusPointAt(event.getX(), event.getY());
            }
            return true;
        });

        exposureRows.clear();
        exposureRows.add(bindExposureRow(
                content, R.id.shutterRow, CanonExposure.Setting.SHUTTER_SPEED));
        exposureRows.add(bindExposureRow(
                content, R.id.apertureRow, CanonExposure.Setting.APERTURE));
        exposureRows.add(bindExposureRow(content, R.id.isoRow, CanonExposure.Setting.ISO));

        captureShootButton.setOnClickListener(view -> shootFromPanel());
        captureCameraMenuButton.setOnClickListener(view -> openCameraMenu());
        captureAutofocusButton.setOnClickListener(view -> {
            // 对焦要等相机执行完才回，期间禁用按钮避免连点排队。
            captureAutofocusButton.setEnabled(false);
            // 已有落点时按用户点选的那一点对焦；没点过则退回相机的当前对焦点。
            if (!Float.isNaN(lastFocusFrameX) && !Float.isNaN(lastFocusFrameY)) {
                coordinator.autofocusAtPoint((int) lastFocusFrameX, (int) lastFocusFrameY);
            } else {
                coordinator.autofocus();
            }
        });
        setUpLiveViewSizeSpinner();
        setUpAfModeSpinner();
        setUpImageQualitySpinner();

        captureDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.capture_panel_title)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .create();
        captureDialog.setOnDismissListener(ignored -> closeCapturePanel());
        captureDialog.show();

        // 先读三档当前值与对焦方式再开取景：开取景的退避重试最多会占用 executor 数秒，
        // 排在后面会让参数迟迟显示不出来。
        for (ExposureRow row : exposureRows) coordinator.readExposure(row.setting);
        coordinator.readFocusArea();
        coordinator.readImageQuality();
        coordinator.startLiveView();
    }

    /** 面板收起：停取景、丢掉画面引用（否则整个 Activity 会被这几张位图拖住）。 */
    private void closeCapturePanel() {
        // 「相机设置」菜单挂在拍摄面板上，面板收起时一并关掉（菜单的 onDismiss 会清引用）。
        if (cameraMenuDialog != null && cameraMenuDialog.isShowing()) cameraMenuDialog.dismiss();
        // 菜单没在显示时上面的 dismiss 不会触发 onDismiss，这里兜底清一遍。
        clearCameraMenuRefs();
        captureCameraMenuButton = null;
        // 还有合并中没发出去的档位调整：立刻补发，别让用户以为改好了其实没生效。
        for (ExposureRow row : exposureRows) flushPendingExposureWrite(row);
        // 拆取景必须排在补发之后：补发档位要趁相机还在遥控态（写属性需要这个状态），
        // 反了的话档位会写进已经收回画面的相机，白写一次。
        // 这里刻意不拆会话：真机（EOS RP）实测「手机能调参、机身同时也能操作」，用户要求
        // 关面板时保持连接，不再重连。
        coordinator.stopLiveView();
        debounceHandler.removeCallbacksAndMessages(null);
        captureDialog = null;
        liveViewImage = null;
        liveViewFrame = null;
        if (focusBoxOverlay != null) focusBoxOverlay.clear();
        focusBoxOverlay = null;
        lastFocusFrameX = Float.NaN;
        lastFocusFrameY = Float.NaN;
        liveViewStatusView = null;
        liveViewGridCheck = null;
        captureShootButton = null;
        captureAutofocusButton = null;
        liveViewSizeSpinner = null;
        afModeSpinner = null;
        imageQualitySpinner = null;
        imageQualityOptions = null;
        imageQualityWarnView = null;
        lastAppliedImageQualityIndex = -1;
        imageQualityCurrentHasJpeg = true;
        captureShotsField = null;
        afModeValues = null;
        lastAppliedAfValue = 0;
        pendingShootAfterQualityWrite = null;
        resetStackProgressUi();
        exposureRows.clear();
        // 先撤掉延迟回收任务，再回收当前帧；被撤掉的旧帧交给 GC 即可。
        deferredRecycle.removeCallbacksAndMessages(null);
        recycleLiveViewBitmap();
    }

    /** 链路断开/重连时主动收起面板；已经收起则什么都不做。 */
    private void dismissCapturePanel() {
        if (captureDialog != null && captureDialog.isShowing()) captureDialog.dismiss();
    }

    /**
     * 取景画面尺寸下拉框：把档位序号持久化，切换后立刻改相机端推流分辨率
     * （写 0xD1B0 的输出位，取景进行中下一帧生效）。
     */
    private void setUpLiveViewSizeSpinner() {
        LiveViewSize[] sizes = LiveViewSize.values();
        String[] labels = new String[sizes.length];
        for (int i = 0; i < sizes.length; i++) labels[i] = sizes[i].label;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        liveViewSizeSpinner.setAdapter(adapter);
        LiveViewSize current = LiveViewSize.of(preferences.getInt(PREF_LIVE_VIEW_SIZE, 0));
        liveViewSizeSpinner.setSelection(current.ordinal());
        // 先把档位交给协调器，再打开取景：开取景时直接按这个尺寸推流。
        coordinator.setLiveViewSize(current);
        liveViewSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                LiveViewSize size = LiveViewSize.of(position);
                preferences.edit().putInt(PREF_LIVE_VIEW_SIZE, size.ordinal()).apply();
                coordinator.setLiveViewSize(size);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private ExposureRow bindExposureRow(View parent, int rowId, CanonExposure.Setting setting) {
        View row = parent.findViewById(rowId);
        ((TextView) row.findViewById(R.id.rowLabel)).setText(setting.label);
        // 先按全表显示，读到相机允许值列表（onExposureChoices）后再收窄。
        List<CanonExposure.Entry> entries = CanonExposure.entries(setting);
        ExposureRow result = new ExposureRow(setting, row.findViewById(R.id.rowValue), entries);
        // 先按约定默认档显示，读到相机真实值后再覆盖。
        int fallback = CanonExposure.defaultIndex(setting);
        result.index = fallback < 0 ? 0 : fallback;
        result.value.setText(CanonExposure.display(setting, entries.get(result.index).label));
        result.value.setOnClickListener(view -> showExposurePicker(result));
        result.value.setContentDescription(setting.label + " 当前档位，点按可选档");
        row.findViewById(R.id.rowMinus).setOnClickListener(view -> stepExposure(result, -1));
        row.findViewById(R.id.rowPlus).setOnClickListener(view -> stepExposure(result, 1));
        return result;
    }

    /**
     * 对焦方式下拉框：选项来自相机上报的 0xD1BA 允许值列表（读完才填），切换后写回 0xD1BA。
     * 标签按 libgphoto2 的取值名表给出，见 {@link #afModeLabel(int)}。
     */
    private void setUpAfModeSpinner() {
        if (afModeSpinner == null) return;
        // 先放一个占位项，等 onFocusAreaResult 拿到真实列表再替换。
        setAfSpinnerPlaceholder("读取中…");
        afModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (afModeValues == null) return;
                if (position < 0 || position >= afModeValues.length) return;
                int value = afModeValues[position];
                // 与程序当前设定一致说明只是填充/重复选中，不需要写回相机。
                if (value == lastAppliedAfValue) return;
                lastAppliedAfValue = value;
                coordinator.writeFocusArea(value);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /**
     * 用真实可选值填充下拉框。{@code fromCamera=false} 表示列表是用候选全表降级的
     * （相机没上报允许值），逐项加「候选」标注，免得被当成相机确认过的取值。
     */
    private void setAfSpinnerItems(int[] values, int selectedIndex, boolean fromCamera) {
        if (afModeSpinner == null) return;
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = afModeLabel(values[i]) + (fromCamera ? "" : AF_METHOD_CANDIDATE_SUFFIX);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        afModeSpinner.setAdapter(adapter);
        int selected = (selectedIndex >= 0 && selectedIndex < values.length) ? selectedIndex : 0;
        afModeSpinner.setSelection(selected);
        lastAppliedAfValue = values[selected];
    }

    /** 相机没上报允许值时的候选项标注。 */
    private static final String AF_METHOD_CANDIDATE_SUFFIX = "（候选）";

    /** 下拉框占位/不可用项（此时不设 afModeValues，选中回调会直接忽略）。 */
    private void setAfSpinnerPlaceholder(String label) {
        if (afModeSpinner == null) return;
        afModeValues = null;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, new String[]{label});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        afModeSpinner.setAdapter(adapter);
    }

    /**
     * 画质（{@code 0xD120}）下拉框：选项来自相机上报的允许列表（读完才填），切换后写回 0xD120。
     * 与「对焦方式」不同，这里<b>不发明候选值</b>——写错画质会改变相机的存储行为，比留空更糟。
     */
    private void setUpImageQualitySpinner() {
        if (imageQualitySpinner == null) return;
        setImageQualityPlaceholder(getString(R.string.live_view_starting));
        imageQualitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (imageQualityOptions == null) return;
                if (position < 0 || position >= imageQualityOptions.size()) return;
                // 与程序当前设定一致说明只是填充/重复选中，不需要写回相机。
                if (position == lastAppliedImageQualityIndex) return;
                // 直接把选项的原始字节交给相机，不按下标让协调器重查允许列表：
                // 两次读取之间列表/当前值可能变化，下标会错位到别的画质上。
                CanonImageQuality.Option option = imageQualityOptions.get(position);
                // 这里不推进 lastAppliedImageQualityIndex：写入还没定论，提前推进会让
                // 「失败后重选同一项」被上面的早退忽略，用户无法原地重试。
                coordinator.writeImageQualityRaw(option.raw, option.label);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 用相机给的画质列表填充下拉框；{@code selectedIndex} 为当前值下标。 */
    private void setImageQualityItems(List<CanonImageQuality.Option> options, int selectedIndex) {
        if (imageQualitySpinner == null) return;
        imageQualityOptions = options;
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) labels[i] = options.get(i).label;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        imageQualitySpinner.setAdapter(adapter);
        int selected = (selectedIndex >= 0 && selectedIndex < options.size()) ? selectedIndex : 0;
        imageQualitySpinner.setSelection(selected);
        lastAppliedImageQualityIndex = selected;
    }

    /** 画质下拉框占位/不可用项（此时不设 imageQualityOptions，选中回调会直接忽略）。 */
    private void setImageQualityPlaceholder(String label) {
        if (imageQualitySpinner == null) return;
        imageQualityOptions = null;
        lastAppliedImageQualityIndex = -1;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, new String[]{label});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        imageQualitySpinner.setAdapter(adapter);
    }

    /** 按当前画质是否含 JPG 切换琥珀色警告行的可见性。 */
    private void updateImageQualityWarn(boolean hasJpeg) {
        if (imageQualityWarnView != null) {
            imageQualityWarnView.setVisibility(hasJpeg ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 打开「相机设置」菜单。菜单挂在拍摄面板之上，目前有白平衡（+ 选中「色温」档后露出的
     * 色温输入行）；后续新增设置加进 dialog_camera_menu.xml 的 {@code cameraMenuItems} 容器即可。
     */
    private void openCameraMenu() {
        if (cameraMenuDialog != null && cameraMenuDialog.isShowing()) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_camera_menu, null);
        whiteBalanceSpinner = content.findViewById(R.id.whiteBalance);
        cameraMenuStatusView = content.findViewById(R.id.cameraMenuStatus);
        colorTemperatureSection = content.findViewById(R.id.colorTemperatureSection);
        colorTemperatureInput = content.findViewById(R.id.colorTemperatureInput);
        colorTemperatureSectionShown = false;
        Button applyColorTemperature = content.findViewById(R.id.colorTemperatureApply);
        applyColorTemperature.setOnClickListener(view -> applyColorTemperature());
        setUpWhiteBalanceSpinner();
        cameraMenuDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.camera_menu_title)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .create();
        cameraMenuDialog.setOnDismissListener(ignored -> clearCameraMenuRefs());
        cameraMenuDialog.show();
        coordinator.readWhiteBalance();
    }

    /** 菜单关闭时清掉挂在它上面的控件引用与状态（弹窗销毁后这些控件没有别处引用）。 */
    private void clearCameraMenuRefs() {
        cameraMenuDialog = null;
        whiteBalanceSpinner = null;
        cameraMenuStatusView = null;
        whiteBalanceValues = null;
        lastAppliedWhiteBalanceValue = Integer.MIN_VALUE;
        colorTemperatureSection = null;
        colorTemperatureInput = null;
        colorTemperatureSectionShown = false;
    }

    /**
     * 白平衡（{@code 0xD109}）下拉框：切换后按<b>编码</b>写回相机。传编码而不是下标，
     * 协调器那侧就不必重查允许列表（画质那条路踩过下标错位的坑，这里不重复）。
     */
    private void setUpWhiteBalanceSpinner() {
        if (whiteBalanceSpinner == null) return;
        setWhiteBalancePlaceholder(getString(R.string.live_view_starting));
        whiteBalanceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (whiteBalanceValues == null) return;
                if (position < 0 || position >= whiteBalanceValues.length) return;
                int value = whiteBalanceValues[position];
                // 色温输入行的显隐必须先于下面的写入判断更新：程序回填相机当前档位时
                // 会走「与相机值相同」的早退，若把显隐放在早退之后，切到色温档就永远露不出来。
                updateColorTemperatureSection(value);
                // 与相机当前值一致说明只是填充/重复选中，不需要写回。
                if (value == lastAppliedWhiteBalanceValue) return;
                // 不在这里推进 lastAppliedWhiteBalanceValue：写入还没定论，提前推进会让
                // 「失败后重选同一档」被上面的早退忽略；成功后由回读统一校正。
                coordinator.writeWhiteBalance(value);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 用相机给的档位填白平衡下拉框；{@code selectedIndex} 为当前值下标。 */
    private void setWhiteBalanceItems(int[] values, int selectedIndex, boolean fromCamera) {
        if (whiteBalanceSpinner == null) return;
        whiteBalanceValues = values;
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = WhiteBalanceChoices.label(values[i]);
        int selected = (selectedIndex >= 0 && selectedIndex < values.length) ? selectedIndex : 0;
        // 先记下相机当前值，再换 adapter/选中项：否则选中回调会拿旧值比对，
        // 把这次填充当成用户切换、对着相机白写一次。
        lastAppliedWhiteBalanceValue = values[selected];
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        whiteBalanceSpinner.setAdapter(adapter);
        whiteBalanceSpinner.setSelection(selected);
        if (cameraMenuStatusView != null) {
            cameraMenuStatusView.setText(fromCamera
                    ? "档位来自相机上报。"
                    : "相机未上报档位，使用常见档位兜底（未获相机确认），切换可能被拒。");
        }
    }

    /** 白平衡下拉框占位（读取中/读不到）；此时 {@code whiteBalanceValues} 为 null，选中回调直接忽略。 */
    private void setWhiteBalancePlaceholder(String label) {
        if (whiteBalanceSpinner == null) return;
        whiteBalanceValues = null;
        lastAppliedWhiteBalanceValue = Integer.MIN_VALUE;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, new String[]{label});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        whiteBalanceSpinner.setAdapter(adapter);
    }

    /**
     * 按当前白平衡档位显隐色温输入行：只有「色温」档（编码 {@link WhiteBalanceChoices#COLOR_TEMPERATURE}）
     * 才露出，其他档位不显示（用户要求「其他的可以先不弹」）。首次露出时读一次相机的 K 值填进输入框。
     */
    private void updateColorTemperatureSection(int whiteBalanceCode) {
        if (colorTemperatureSection == null) return;
        boolean show = whiteBalanceCode == WhiteBalanceChoices.COLOR_TEMPERATURE;
        if (show == colorTemperatureSectionShown) return;
        colorTemperatureSectionShown = show;
        colorTemperatureSection.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) coordinator.readColorTemperature();
    }

    /**
     * 「应用」按钮：校验输入 → 对齐到 100 K 刻度 → 写入相机。传具体 K 值而不是档位，
     * 与白平衡一致，避免下标错位。
     */
    private void applyColorTemperature() {
        if (colorTemperatureInput == null) return;
        Integer kelvin = ColorTemperature.parse(colorTemperatureInput.getText().toString());
        if (kelvin == null) {
            if (cameraMenuStatusView != null) {
                cameraMenuStatusView.setText(R.string.color_temperature_invalid);
            }
            return;
        }
        int aligned = ColorTemperature.align(kelvin);
        // 把对齐后的值回填输入框，让用户看到实际要写入的刻度（例如 5234 → 5200）。
        colorTemperatureInput.setText(String.valueOf(aligned));
        coordinator.writeColorTemperature(aligned);
    }

    /** 复位对焦包围进度与预览（开拍、关面板时都要做，避免上一批的残影）。 */
    private void resetStackProgressUi() {
        if (stackProgressView != null) {
            stackProgressView.setVisibility(View.GONE);
            stackProgressView.setText("");
        }
        if (stackPreviewView != null) {
            stackPreviewView.setVisibility(View.GONE);
            stackPreviewView.setImageDrawable(null);
        }
        if (stackPreviewBitmap != null && !stackPreviewBitmap.isRecycled()) {
            stackPreviewBitmap.recycle();
        }
        stackPreviewBitmap = null;
    }

    /**
     * 0xD1BA 取值的中文标签，下标即机型编码。<br>
     * 取值名表取自 libgphoto2 {@code camlibs/ptp2/config.c:2977-2994 canon_eos_afmethod}
     * （0..14，配置项名 {@code "AF Method"}，config.c:12231），括号里保留该表的原始 token
     * 便于对照；中文措辞按佳能机身菜单习惯翻译。M50 Mark II 上报的可选值为
     * {@code {2, 10, 1, 4}}（LiveFace / LiveSpotAF / Live / LiveZone），与机身菜单的四项一致。
     */
    private static final String[] AF_METHOD_LABELS = {
            "快速对焦（Quick）",                        // 0
            "单点自动对焦（Live）",                      // 1
            "面部+追踪（LiveFace）",                     // 2
            "多点自动对焦（LiveMulti）",                 // 3
            "区域自动对焦（LiveZone）",                  // 4
            "单点扩展·十字（LiveSingleExpandCross）",     // 5
            "单点扩展·周围（LiveSingleExpandSurround）",  // 6
            "大区域·横（LiveZoneLargeH）",               // 7
            "大区域·纵（LiveZoneLargeV）",               // 8
            "捕捉对焦（LiveCatchAF）",                   // 9
            "定点自动对焦（LiveSpotAF）",                // 10
            "灵活区域 1（FlexibleZoneAF1）",             // 11
            "灵活区域 2（FlexibleZoneAF2）",             // 12
            "灵活区域 3（FlexibleZoneAF3）",             // 13
            "全区域自动对焦（WholeAreaAF）",             // 14
    };

    /** 对焦方式选项标签；陌生编码如实显示十六进制，不猜名字。 */
    private static String afModeLabel(int value) {
        int code = value & 0xffff;
        if (code < AF_METHOD_LABELS.length) return AF_METHOD_LABELS[code];
        return "未知方式 0x" + String.format(Locale.ROOT, "%04X", code);
    }

    /** −/+ 逐档微调：本地档位立刻推进，写入合并到停止点击之后。 */
    private void stepExposure(ExposureRow row, int delta) {
        if (!coordinator.isConnected()) {
            showError("请先连接相机。", null);
            return;
        }
        int next = row.index + delta;
        if (next < 0 || next >= row.count) return;
        applyExposureSelection(row, next, true);
    }

    /**
     * 选中一个档位。
     * {@code debounce} 为真（−/+ 连点）时，写入合并到 250ms 后的最后一次点击——
     * 跨 6 档只产生 1 次 0x9110 事务，不至于和取景取帧抢同一个单线程队列；
     * 从档位列表直选时立即写入。
     */
    private void applyExposureSelection(ExposureRow row, int index, boolean debounce) {
        if (index < 0 || index >= row.entries.size()) return;
        row.index = index;
        // 用户手选的档位是权威值：立刻钉住，之后任何读取结果都不得把它刷回「—」。
        row.pinned = true;
        row.value.setText(CanonExposure.display(row.setting, row.entries.get(index).label));
        // 上一次还没发出去的合并写入作废：只有最后一次点击算数。
        if (row.pendingWrite != null) {
            debounceHandler.removeCallbacks(row.pendingWrite);
            row.pendingWrite = null;
        }
        if (!debounce) {
            coordinator.writeExposure(row.setting, index);
            return;
        }
        Runnable write = () -> {
            row.pendingWrite = null;
            coordinator.writeExposure(row.setting, row.index);
        };
        row.pendingWrite = write;
        debounceHandler.postDelayed(write, EXPOSURE_WRITE_DEBOUNCE_MS);
    }

    /** 关闭面板前把还没发出去的档位补发掉，否则用户以为改好了其实没生效。 */
    private void flushPendingExposureWrite(ExposureRow row) {
        if (row.pendingWrite == null) return;
        debounceHandler.removeCallbacks(row.pendingWrite);
        row.pendingWrite = null;
        coordinator.writeExposure(row.setting, row.index);
    }

    /** 档位列表直选：跨多档比连点 −/+ 快，且只产生一次写入。 */
    private void showExposurePicker(ExposureRow row) {
        List<CanonExposure.Entry> entries = row.entries;
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            labels[i] = CanonExposure.display(row.setting, entries.get(i).label);
        }
        new AlertDialog.Builder(this)
                .setTitle(row.setting.label)
                .setSingleChoiceItems(labels, row.index, (dialog, which) -> {
                    applyExposureSelection(row, which, false);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** 面板上的快门：走与主界面完全相同的一次拍摄取回流程。 */
    private void shootFromPanel() {
        if (!coordinator.isConnected()) {
            showError("请先连接相机。", null);
            return;
        }
        if (captureRunning) return;
        // 先把张数读出来并记住：机内包围一次快门出多张，之后的收集要按这个数等。
        expectedShots = readExpectedShots();
        // 画质不含 JPG 时先给一次前置提示（可一键切到含 JPG 的画质）；读不到画质不拦。
        ensureJpegCapableQualityThen(this::startPanelCapture);
    }

    /**
     * 画质不含 JPG 时的前置闸门：先让用户选择「切到含 JPG 的画质再拍」或「仍按现状拍」。
     * 读不到画质（相机未给列表）时不拦——不能因为读不到就让快门按不下去。这条闸门是唯一的开拍入口，
     * 单张拍摄、对焦包围、以及对焦包围面板进入的拍摄（最终都汇到这里）都受它覆盖。
     */
    private void ensureJpegCapableQualityThen(Runnable shoot) {
        if (imageQualityCurrentHasJpeg || imageQualityOptions == null) {
            shoot.run();
            return;
        }
        CanonImageQuality.Option fallback = CanonImageQuality.firstJpegOption(imageQualityOptions);
        if (fallback == null) {
            // 相机给的可选画质里一个含 JPG 的都没有：无从兜底，不强行拦下用户。
            shoot.run();
            return;
        }
        String message = getString(R.string.image_quality_switch_message, fallback.label);
        if (expectedShots > 1) {
            message = message + "\n\n" + getString(R.string.image_quality_switch_extra_stack);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.image_quality_switch_title)
                .setMessage(message)
                .setPositiveButton(
                        getString(R.string.image_quality_switch_and_shoot, fallback.label),
                        (dialog, which) -> {
                            pendingShootAfterQualityWrite = shoot;
                            coordinator.writeImageQualityRaw(fallback.raw, fallback.label);
                        })
                .setNeutralButton(R.string.image_quality_shoot_anyway,
                        (dialog, which) -> shoot.run())
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** 真正发起一次拍摄取回（画质闸门通过后调用）。 */
    private void startPanelCapture() {
        resetStackProgressUi();
        // 多张（对焦包围/HDR 包围）这一批要留给景深合成：先开收集窗，并让协调器在导出相册后
        // 保留应用内副本——否则原图导出成功即被清理，合成就没得读了。
        // 张数 = 1 时顺带把上一批残留取走丢掉，免得旧文件被之后的下载路径误拿去合成。
        if (expectedShots > 1) {
            stackBatch.armForAppCapture();
            coordinator.setRetainLocalCopiesForStack(true);
        } else {
            stackBatch.consume();
            coordinator.setRetainLocalCopiesForStack(false);
        }
        captureRunning = true;
        if (captureShootButton != null) captureShootButton.setEnabled(false);
        if (liveViewStatusView != null) liveViewStatusView.setText(R.string.capture_running);
        progressBar.setProgress(0);
        setBusy(true);
        coordinator.captureAndFetch(expectedShots);
    }

    /** 拍摄面板上的「预计张数」。填得不合法就按 1 张——不能因为一个输入框让快门按不下去。 */
    private int readExpectedShots() {
        // 输入框还没装配好（例如从对焦包围面板直接开拍）时，用已记住的张数，
        // 别把刚写入的张数吞成 1。
        if (captureShotsField == null) return Math.max(1, expectedShots);
        try {
            return Math.max(1, Integer.parseInt(captureShotsField.getText().toString().trim()));
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private ExposureRow rowFor(CanonExposure.Setting setting) {
        for (ExposureRow row : exposureRows) {
            if (row.setting == setting) return row;
        }
        return null;
    }

    private void recycleLiveViewBitmap() {
        Bitmap frame = liveViewBitmap;
        liveViewBitmap = null;
        if (frame != null && !frame.isRecycled()) frame.recycle();
    }

    /** 面板上一行曝光参数：档位是取值表下标，不是机上编码值。 */
    private static final class ExposureRow {
        final CanonExposure.Setting setting;
        final TextView value;
        /**
         * 该档当前可选的取值列表。先按全表显示，收到相机允许值列表（onExposureChoices）后
         * 收窄到机身支持的子集；{@code index} 就是它的下标（与协调器一致）。
         */
        List<CanonExposure.Entry> entries;
        /** 可选档位数，跟随 {@link #entries}；−/+ 的边界判定用它。 */
        int count;
        int index;
        /** 已排期但还没发给相机的写入（−/+ 合并用）；空闲时为 null。 */
        Runnable pendingWrite;
        /**
         * 该档的显示值是否已「钉住」：读到过真实值、或用户手动选过档。
         * 钉住之后，后续读取失败不再把值刷成「—」——否则用户每调一次档，
         * 三个要素就一起变回「—」，看着像调整没生效。
         */
        boolean pinned;

        ExposureRow(
                CanonExposure.Setting setting,
                TextView value,
                List<CanonExposure.Entry> entries) {
            this.setting = setting;
            this.value = value;
            this.entries = entries;
            this.count = entries.size();
        }
    }

    /**
     * targetSdk 35 起系统强制边到边显示，内容默认会伸进状态栏/导航栏下方。
     * 把两条系统栏的尺寸补进主内容与侧栏的内边距，让顶部连接卡片与底部按钮不被系统栏压住。
     * 计算以 XML 里的原始内边距为基准，避免系统多次回调时重复叠加。
     */
    private void applySystemBarInsets() {
        final View content = findViewById(R.id.main_content);
        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    baseLeft + insets.getSystemWindowInsetLeft(),
                    baseTop + insets.getSystemWindowInsetTop(),
                    baseRight + insets.getSystemWindowInsetRight(),
                    baseBottom + insets.getSystemWindowInsetBottom());
            return insets;
        });

        // 侧栏是贴左边缘滑出的整屏面板，只补上下；左右留给它自己贴边。
        final View drawer = findViewById(R.id.drawerPanel);
        final int drawerTop = drawer.getPaddingTop();
        final int drawerBottom = drawer.getPaddingBottom();
        drawer.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    view.getPaddingLeft(),
                    drawerTop + insets.getSystemWindowInsetTop(),
                    view.getPaddingRight(),
                    drawerBottom + insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    /** 明确区分两处落点：应用内暂存（下载中转，导出后清理）与系统相册可见位置。 */
    private void updateStorageLabels() {
        String album = exportToGalleryBox.isChecked()
                ? GalleryExporter.RELATIVE_PATH
                : getString(R.string.album_dir_disabled);
        outputDirView.setText(getString(R.string.output_dir_label) + "："
                + coordinator.outputDirectory().getAbsolutePath()
                + (exportToGalleryBox.isChecked() ? "（导出成功后自动清理）" : "（导出关闭时保留）")
                + "\n" + getString(R.string.album_dir_label) + "：" + album);
    }

    /** 每页张数选择器：恢复上次选择，切换后立即生效并持久化。 */
    private void setUpPageSizeSpinner(int configured) {
        int index = 0;
        for (int i = 0; i < PAGE_SIZE_OPTIONS.length; i++) {
            if (PAGE_SIZE_OPTIONS[i] == configured) index = i;
        }
        final int selectedIndex = index;
        String[] labels = new String[PAGE_SIZE_OPTIONS.length];
        for (int i = 0; i < PAGE_SIZE_OPTIONS.length; i++) {
            labels[i] = String.valueOf(PAGE_SIZE_OPTIONS[i]);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pageSizeSpinner.setAdapter(adapter);
        pageSizeSpinner.setSelection(selectedIndex);
        coordinator.setPageSize(PAGE_SIZE_OPTIONS[selectedIndex]);
        pageSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                int size = PAGE_SIZE_OPTIONS[position];
                if (size == coordinator.pageSize()) return;
                coordinator.setPageSize(size);
                preferences.edit().putInt(PREF_PAGE_SIZE, size).apply();
                // 页边界随页大小改变：回到第一页，避免停在可能为空的尾页。
                if (coordinator.isConnected()) goToPage(0);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /**
     * 格式过滤器：切换的是<b>分页范围</b>（「仅 JPG」时每页就是 pageSize 张 JPG），
     * 所以必须重读相机——本地重渲染只会把已按另一种范围切好的页重新筛一遍，页码对不上。
     */
    private void setUpFormatFilterSpinner(boolean jpegOnlyConfigured) {
        String[] labels = {
            getString(R.string.format_filter_jpeg), getString(R.string.format_filter_all)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatFilterSpinner.setAdapter(adapter);
        jpegOnly = jpegOnlyConfigured;
        formatFilterSpinner.setSelection(jpegOnly ? 0 : 1);
        formatFilterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                boolean only = position == 0;
                if (only == jpegOnly) return;
                jpegOnly = only;
                preferences.edit().putBoolean(PREF_JPEG_ONLY, only).apply();
                // 分页范围变了：回到第一页重读，页码/总数都按新范围算。
                if (coordinator.isConnected()) goToPage(0);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 回到前台：重新武装保活探测（HOLD 三态里 SUSPEND → HOLD）。
        if (coordinator != null) coordinator.resumeHold();
        startAutoConnectWatch();
        // 冷启动时相机 Wi‑Fi 可能早就连着了：网络回调不一定会再补一次 onAvailable。
        maybeAutoConnect(currentCameraWifiNetwork());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统 Wi‑Fi 设置返回：尽力把刚连上的网络名称回填进输入框；
        // Android 13+ 未授予定位权限时读不到（系统脱敏为 <unknown ssid>），此时给一句提示。
        if (!wifiPickerLaunched) return;
        wifiPickerLaunched = false;
        if (!writeBackCameraSsid() && !coordinator.isConnected()) {
            statusView.setText(R.string.pick_wifi_hint);
        }
    }

    @Override
    protected void onStop() {
        // 退到后台：停心跳探测省电、也避免后台一次网络抖动就误判离线；
        // 链路、WifiLock 与正在进行的传输/拍摄/取景都不受影响。
        stopAutoConnectWatch();
        if (coordinator != null) coordinator.suspendHold();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelBleHandover();
        // 先收起遥控面板：它持有取景位图，且停取景要在 executor 关闭之前发出。
        if (viewerDialog != null) {
            viewerDialog.dismiss();
            viewerDialog = null;
        }
        if (filmDialog != null) {
            filmDialog.dismiss();
            filmDialog = null;
        }
        dismissCapturePanel();
        deferredRecycle.removeCallbacksAndMessages(null);
        debounceHandler.removeCallbacksAndMessages(null);
        thumbnailExecutor.shutdownNow();
        recycleLiveViewBitmap();
        if (coordinator != null) coordinator.shutdown();
        // 退出应用即结束会话，前台服务随之停止。
        CameraConnectionService.stop(this);
        super.onDestroy();
    }

    /** 侧栏展开时返回键先收侧栏，不直接退出应用。 */
    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeShotPanel();
            return;
        }
        super.onBackPressed();
    }

    // ── 协调器回调（均已切回主线程） ──

    @Override
    public void onStatus(String message) {
        runOnUiThread(() -> statusView.setText(message));
    }

    @Override
    public void onCameraAddress(String address) {
        // 发现即回填：让用户先看到「相机在哪」，握手结果随后再更新状态。
        runOnUiThread(() -> {
            if (address == null || address.trim().isEmpty()) return;
            if (!address.equals(hostField.getText().toString().trim())) {
                autoFilledHost = address;
                hostField.setText(address);
                hostField.setSelection(hostField.getText().length());
            }
            statusView.setText("已发现相机 " + address + "，正在握手…");
        });
    }

    @Override
    public void onConnected(String cameraName) {
        runOnUiThread(() -> {
            setBusy(false);
            updateConnectButton(true);
            statusView.setText("已连接 · " + cameraName);
            // 会话保活（M2）：连接成功即前台化，退到后台/息屏也不会被系统回收。
            requestNotificationPermissionIfNeeded();
            CameraConnectionService.start(
                    this, getString(R.string.session_text_connected, cameraName));
            // 主流程：连上就读卡并显示图片栏第一页。
            fullScanMode = false;
            // 官方连上后立即进遥控/事件模式：既让相机保持活跃，也便于后续取景/快门。
            coordinator.enterRemoteMode();
            goToPage(0);
            // Wi‑Fi 会话连上后，把实际所用的网络名称/密码/BSSID 记成「上一次成功连接」。
            if (wifiSession) {
                writeBackCameraAddress();
                persistSuccessfulWifiRecord();
            }
            // 能力探测只在用户显式查看时执行：它会长时间占用 PTP 传输层的锁，
            // 自动执行会把 5 秒心跳堵到 40 秒以上，相机按空闲超时直接关热点。
        });
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(() -> {
            setBusy(false);
            updateConnectButton(false);
            cancelBleHandover();
            // 链路已断：遥控面板里的取景与参数都没了意义，先收起。
            dismissCapturePanel();
            // 链路已断：能力报告里的按钮已无法执行，一并收起。
            if (capabilityDialog != null) {
                capabilityDialog.dismiss();
            }
            if (diffDialog != null) {
                diffDialog.dismiss();
            }
            // 差分基线是一条会话里的事件缓存快照，链路断了就不再可比。
            diffBaseline = null;
            // 链路已断：清掉图片栏，避免展示已经无法下载/预览的旧缩略图。
            gridAdapter.reset();
            clearGridPager();
            statusView.setText(R.string.status_idle);
            // 手动断开即结束会话，前台服务随之停止。
            CameraConnectionService.stop(this);
        });
    }

    @Override
    public void onReconnecting(int attempt, long delayMillis) {
        runOnUiThread(() -> {
            setBusy(false);
            updateConnectButton(false);
            dismissCapturePanel();
            // 链路丢失即清空图片栏（重连成功后 onConnected 会重新读卡填充第一页）。
            gridAdapter.reset();
            clearGridPager();
            statusView.setText("链路丢失，自动重连第 " + attempt + " 次，"
                    + (delayMillis / 1000) + " 秒后重试…");
            // 重连期间保持前台服务运行：进程一旦被回收，退避重连就再也跑不完了。
            CameraConnectionService.start(
                    this, getString(R.string.session_text_reconnecting, attempt));
        });
    }

    @Override
    public void onProgress(long received, long total) {
        runOnUiThread(() -> {
            if (total > 0) {
                progressBar.setProgress((int) Math.min(100, received * 100 / total));
                statusView.setText(String.format(
                        Locale.ROOT, "传输中 %d / %d 字节", received, total));
            } else {
                statusView.setText(String.format(
                        Locale.ROOT, "传输中 %d 字节（长度未知）", received));
            }
        });
    }

    @Override
    public void onFileSaved(File file, long bytes, long elapsedMillis, String note) {
        runOnUiThread(() -> {
            // 对焦包围批次逐张收拢，等这一批全部取回后再统一做景深合成（见 onCaptureFinished）。
            stackBatch.add(file);
            // 包围期间取景是暂停的，这里给「已取回 N/M 张」与单张缩略图做可视反馈，缓解卡死观感。
            updateStackProgress(file);
            setBusy(false);
            progressBar.setProgress(100);
            // note 必须带上：否则相册导出结果会被这条消息覆盖掉，用户无从判断。
            statusView.setText("已保存 " + file.getName() + " · " + bytes
                    + " 字节 · " + elapsedMillis + " ms" + (note == null ? "" : note));
        });
    }

    /** 包围批次逐张更新进度行与单张预览；单张拍摄没有「N/M 张」进度，但也显示刚取回的缩略图。 */
    private void updateStackProgress(File file) {
        if (expectedShots <= 1) {
            // 单张拍完只有一行状态文字太干：把刚取回的这一张直接贴进面板，便于立刻确认构图与对焦。
            showStackPreview(file);
            return;
        }
        if (stackProgressView != null) {
            stackProgressView.setVisibility(View.VISIBLE);
            stackProgressView.setText(
                    getString(R.string.stack_progress_format, stackBatch.size(), expectedShots));
        }
        showStackPreview(file);
    }

    /** 后台下采样解码一张缩略图（目标宽约 320px，绝不整图进 Java 堆），再贴到预览位。 */
    private void showStackPreview(File file) {
        if (stackPreviewView == null) return;
        thumbnailExecutor.submit(() -> {
            Bitmap bitmap = decodeThumbnail(file);
            runOnUiThread(() -> {
                if (stackPreviewView == null) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    return;
                }
                if (bitmap == null) return;
                Bitmap previous = stackPreviewBitmap;
                stackPreviewBitmap = bitmap;
                stackPreviewView.setVisibility(View.VISIBLE);
                stackPreviewView.setImageBitmap(bitmap);
                if (previous != null && !previous.isRecycled()) previous.recycle();
            });
        });
    }

    /** 按目标宽度下采样解码；文件不可读时返回 {@code null}（预览缺一张不影响拍摄）。 */
    private static Bitmap decodeThumbnail(File file) {
        final int targetWidth = 320;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        // 与全屏看图一致：竖拍照片按 EXIF 摆正，免得刚拍完的缩略图横躺。
        return JpegOrientation.applyFromFile(file, decoded);
    }

    @Override
    public void onDownloadFinished() {
        runOnUiThread(() -> {
            setBusy(false);
            // 下载已结束：清掉图片栏里之前的选中状态，避免看起来像还没下载。
            gridAdapter.clearSelection();
            // 手动/批量下载一律不合成：这里只把可能残留的批次取走丢掉并复位保留开关，
            // 免得旧文件被误拿去合成（自动合成只在 onCaptureFinished 里做）。
            stackBatch.consume();
            coordinator.setRetainLocalCopiesForStack(false);
        });
    }

    @Override
    public void onDeleteFinished(int deleted, int failed, String failedNames) {
        runOnUiThread(() -> {
            setBusy(false);
            gridAdapter.clearSelection();
            if (deleted > 0) {
                statusView.setText("已删除 " + deleted + " 个文件"
                        + (failed > 0 ? "，失败 " + failed + " 个" : ""));
            }
            if (failed > 0) {
                showError("有 " + failed + " 个文件删除失败：\n" + failedNames, null);
            }
            // 卡内已变：重读当前页，让删掉的文件从列表消失。
            if (deleted > 0 && coordinator.isConnected()) goToPage(currentPage);
        });
    }

    @Override
    public void onLiveViewFrame(Bitmap frame) {
        runOnUiThread(() -> {
            if (liveViewImage == null) {
                // 面板已收起，相机还在推的帧直接丢掉。
                if (!frame.isRecycled()) frame.recycle();
                return;
            }
            Bitmap previous = liveViewBitmap;
            liveViewBitmap = frame;
            liveViewImage.setImageBitmap(frame);
            // 换帧后重新映射方框：图传尺寸/比例变了，旧控件坐标就不对了。
            updateFocusBox();
            // 约 10fps、每帧 MB 级：延迟回收上一帧，既不攒垃圾也不打断渲染线程的绘制。
            if (previous != null && !previous.isRecycled()) {
                deferredRecycle.postDelayed(previous::recycle, 300L);
            }
        });
    }

    @Override
    public void onLiveViewStatus(String message) {
        runOnUiThread(() -> {
            // 面板已经收起时，取景状态（含「交还机身」的收尾结果）改发到主界面状态栏——
            // 否则「面板关了但相机还在遥控态」这类提示会落在已经消失的视图上，用户看不到。
            if (liveViewStatusView != null) {
                liveViewStatusView.setText(message);
            } else if (statusView != null) {
                statusView.setText(message);
            }
        });
    }

    @Override
    public void onExposureResult(
            CanonExposure.Setting setting, int index, String display, String error) {
        runOnUiThread(() -> {
            ExposureRow row = rowFor(setting);
            if (row == null) return;
            // 这一档已经有排期中的写入：此时到达的结果只反映相机旧值，
            // 让它盖掉本地选择会把用户刚做的调整静默吞掉。
            if (row.pendingWrite != null) return;
            if (error != null) {
                // 值一旦钉住（读到过真实值、或用户手动选过档）就不再回退成「—」：
                // 读取失败只说明「问不到相机」，不代表用户刚设的值不对。
                // 底层报错（例如 EOS 对标准属性读回 0x2005）不进取景状态栏 —— 那儿是取景状态的
                // 显示位，三档互相覆盖只会留下一条吓人的协议错误。
                if (row.pinned) {
                    row.value.setContentDescription(
                            setting.label + "：" + row.value.getText() + "（本次未能从相机复核）");
                    return;
                }
                row.value.setText(R.string.exposure_unknown);
                row.value.setContentDescription(setting.label + "当前档位未知，可点按手动选择");
                return;
            }
            row.index = index;
            row.pinned = true;
            row.value.setText(display);
        });
    }

    @Override
    public void onExposureChoices(
            CanonExposure.Setting setting, List<CanonExposure.Entry> choices) {
        runOnUiThread(() -> {
            ExposureRow row = rowFor(setting);
            if (row == null || choices == null || choices.isEmpty()) return;
            // 换列表前先记住当前下标指向的「机上编码值」：收窄后下标含义变了，
            // 必须按值重新定位，否则之后的 −/+ 与直选会写到另一个档位。
            int currentValue = (row.index >= 0 && row.index < row.entries.size())
                    ? row.entries.get(row.index).value
                    : -1;
            row.entries = choices;
            row.count = choices.size();
            int mapped = -1;
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i).value == currentValue) {
                    mapped = i;
                    break;
                }
            }
            row.index = mapped >= 0 ? mapped : Math.min(Math.max(row.index, 0), row.count - 1);
            // 已有本地选择（用户手选过，或有排期中写入）时只换列表不改显示，
            // 与 onExposureResult 的「不盖掉用户选择」保持一致。
            if (row.pinned || row.pendingWrite != null) return;
            row.value.setText(CanonExposure.display(setting, choices.get(row.index).label));
        });
    }

    @Override
    public void onWhiteBalanceResult(int[] values, int currentIndex, boolean fromCamera, String error) {
        runOnUiThread(() -> {
            if (whiteBalanceSpinner == null) return;
            if (error != null || values == null || values.length == 0) {
                setWhiteBalancePlaceholder(getString(R.string.white_balance_unavailable));
                if (cameraMenuStatusView != null) {
                    cameraMenuStatusView.setText(error == null ? "" : "读取白平衡失败：" + error);
                }
                return;
            }
            setWhiteBalanceItems(values, currentIndex, fromCamera);
        });
    }

    @Override
    public void onWhiteBalanceWritten(boolean success, int value, String error) {
        runOnUiThread(() -> {
            if (success) {
                if (cameraMenuStatusView != null) {
                    cameraMenuStatusView.setText("白平衡已切换为 " + WhiteBalanceChoices.label(value));
                }
                // 回读一次：下拉框与 lastAppliedWhiteBalanceValue 都按相机真实当前值校正。
                coordinator.readWhiteBalance();
                return;
            }
            if (cameraMenuStatusView != null) {
                cameraMenuStatusView.setText("切换白平衡失败："
                        + (error == null ? "相机未正常应答" : error));
            }
        });
    }

    @Override
    public void onColorTemperatureResult(long kelvin, String error) {
        runOnUiThread(() -> {
            if (colorTemperatureInput == null) return;
            if (error != null || kelvin <= 0) {
                if (cameraMenuStatusView != null) {
                    cameraMenuStatusView.setText("读取色温失败："
                            + (error == null ? getString(R.string.color_temperature_unavailable) : error));
                }
                return;
            }
            int aligned = ColorTemperature.align((int) kelvin);
            colorTemperatureInput.setText(String.valueOf(aligned));
            colorTemperatureInput.setSelection(colorTemperatureInput.getText().length());
        });
    }

    @Override
    public void onColorTemperatureWritten(boolean success, int kelvin, String error) {
        runOnUiThread(() -> {
            if (cameraMenuStatusView != null) {
                cameraMenuStatusView.setText(success
                        ? "色温已写入 " + kelvin + "K（写入被相机受理，未回读确认）"
                        : "色温写入失败：" + (error == null ? "相机未正常应答" : error));
            }
        });
    }

    @Override
    public void onFocusAreaResult(int[] values, int currentIndex, boolean fromCamera, String error) {
        runOnUiThread(() -> {
            if (afModeSpinner == null) return;
            if (error != null || values == null || values.length == 0) {
                setAfSpinnerPlaceholder("（不可用）");
                return;
            }
            afModeValues = values;
            setAfSpinnerItems(values, currentIndex, fromCamera);
            if (!fromCamera && liveViewStatusView != null) {
                liveViewStatusView.setText("对焦方式列表非相机上报，为候选全表（未获相机确认），选中后写入可能被拒。");
            }
        });
    }

    @Override
    public void onImageQualityResult(
            List<CanonImageQuality.Option> options, int currentIndex,
            boolean currentHasJpeg, boolean fromCamera, String error) {
        runOnUiThread(() -> {
            if (imageQualitySpinner == null) return;
            if (error != null || options == null || options.isEmpty()) {
                setImageQualityPlaceholder(getString(R.string.image_quality_unavailable));
                // 读不到就当作未知：当前是否含 JPG 无从判断，不显示警告、也不拦快门。
                imageQualityCurrentHasJpeg = true;
                updateImageQualityWarn(true);
                if (error != null && liveViewStatusView != null) {
                    liveViewStatusView.setText("读取画质失败：" + error);
                }
                return;
            }
            setImageQualityItems(options, currentIndex);
            imageQualityCurrentHasJpeg = currentHasJpeg;
            updateImageQualityWarn(currentHasJpeg);
        });
    }

    @Override
    public void onImageQualityWritten(boolean success, String label, String error) {
        runOnUiThread(() -> {
            if (success) {
                if (liveViewStatusView != null) {
                    liveViewStatusView.setText("画质已切换：" + (label == null ? "（未知）" : label));
                }
                // 回读一次让下拉框与相机真实当前值对齐：兜底路径的选项不在下拉框里，
                // 只改警告会让 Spinner 停在旧值上（界面显示 RAW、警告却消失，自相矛盾）。
                // 这次回读同时也把 lastAppliedImageQualityIndex 校正到相机实际值上。
                coordinator.readImageQuality();
                // 回读先提交、拍摄后提交：两者走同一个单线程 executor，顺序即执行顺序。
                Runnable pending = pendingShootAfterQualityWrite;
                if (pending != null) {
                    pendingShootAfterQualityWrite = null;
                    pending.run();
                }
                return;
            }
            // 写入失败：清掉挂起的拍摄，绝不「画质没切成还硬拍」。
            pendingShootAfterQualityWrite = null;
            if (liveViewStatusView != null) {
                liveViewStatusView.setText("切换画质失败："
                        + (error == null ? "相机未正常应答" : error));
            }
        });
    }

    @Override
    public void onCapabilityReport(CapabilityReport report, String error) {
        runOnUiThread(() -> {
            if (error != null || report == null) {
                // 探测失败不影响任何既有功能，只在报告弹窗开着时把原因显示出来。
                if (capabilityDialog != null && capabilityDialog.isShowing()) {
                    capabilityMessageView.setText("探测失败：" + error);
                }
                return;
            }
            String text = report.describe();
            if (capabilityDialog != null && capabilityDialog.isShowing()) {
                capabilityMessageView.setText(text);
            }
        });
    }

    @Override
    public void onVendorPropertySnapshot(VendorPropertySnapshot snapshot, String error) {
        runOnUiThread(() -> {
            if (snapshot == null) {
                if (diffMessageView != null) diffMessageView.setText("快照失败：" + error);
                awaitingRestoreCount = false;
                return;
            }
            if (diffMessageView == null) {
                awaitingRestoreCount = false;
                return;
            }
            // 「恢复基线」前的差异统计：数出将与基线不同的属性个数，再弹二次确认。
            if (awaitingRestoreCount) {
                awaitingRestoreCount = false;
                showRestoreConfirm(countRestorable(snapshot, diffBaseline));
                return;
            }
            if (snapshotIsBaseline) {
                diffBaseline = snapshot;
                // 基线持久化：重开 App 后仍能对比/恢复（原始字节是恢复的唯一依据，必须存下）。
                preferences.edit()
                        .putString(PREF_PROPERTY_BASELINE, snapshot.toText())
                        .apply();
                diffMessageView.setText("基线已取：" + snapshot.size() + " 个属性。\n\n"
                        + "现在去机身上改一项设置（比如「对焦包围」或「间隔定时拍摄」），"
                        + "改完回到这里点「对比」。\n\n"
                        + snapshot.describe());
            } else {
                diffMessageView.setText(snapshot.describeDiff(diffBaseline));
            }
        });
    }

    /**
     * 厂商属性差分工作台：机身改设置 → 前后字节比对。这是反推 {@code 0xD134}/{@code 0xD13D}
     * 那 24 字节字段含义、以及在 RP 上找出「对焦包围」挂在哪个属性码上的唯一手段
     * （libgphoto2 对这三条属性一条记录都没有，Canon 也完全没有对焦包围属性）。纯读，不写相机。
     */
    private void showPropertyDiff() {
        if (coordinator == null || !coordinator.isConnected()) {
            statusView.setText("请先连接相机，再看属性差分。");
            return;
        }
        if (diffDialog != null && diffDialog.isShowing()) return;
        diffMessageView = new TextView(this);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        diffMessageView.setPadding(padding, padding, padding, padding);
        diffMessageView.setTextIsSelectable(true);
        diffMessageView.setTextSize(12f);
        diffMessageView.setTypeface(Typeface.MONOSPACE);
        diffMessageView.setText(diffBaseline == null
                ? "还没有基线快照。\n\n顺序：先点「取基线快照」→ 去机身上改一项设置 → 回来点「对比」。"
                : "已有一份基线快照（" + diffBaseline.size() + " 个属性）。改完机身设置后点「对比」。");
        // 恢复入口做成内容区里的一颗按钮：AlertDialog 只有正/中/负三个按钮位，
        // 取基线、对比已经占了两个，再挤进去一个就没法「关闭」了。
        Button restoreButton = new Button(this);
        restoreButton.setText(R.string.property_restore_to_camera);
        restoreButton.setEnabled(diffBaseline != null);
        restoreButton.setOnClickListener(view -> confirmRestoreBaseline());
        LinearLayout contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.addView(diffMessageView);
        contentBox.addView(restoreButton);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(contentBox);
        diffDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.property_diff_title)
                .setView(scroll)
                .setPositiveButton(R.string.property_diff_compare, null)
                .setNeutralButton(R.string.property_diff_snapshot, null)
                .setNegativeButton(R.string.close, null)
                .create();
        diffDialog.setOnShowListener(dialog -> {
            diffDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                snapshotIsBaseline = true;
                diffMessageView.setText("正在取基线快照…（只读）");
                coordinator.snapshotVendorProperties();
            });
            diffDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                snapshotIsBaseline = false;
                diffMessageView.setText("正在取新快照并对比…（只读）");
                coordinator.snapshotVendorProperties();
            });
        });
        diffDialog.setOnDismissListener(dialog -> {
            diffDialog = null;
            diffMessageView = null;
        });
        diffDialog.show();
    }

    /**
     * 「恢复基线到相机」的二次确认：先取一次当前快照，数出与基线不同的属性个数再让用户确认。
     * 不做「一键无声写回」——写错机身参数比多确认一次麻烦得多。
     */
    private void confirmRestoreBaseline() {
        if (diffBaseline == null) {
            if (diffMessageView != null) diffMessageView.setText(R.string.property_restore_none);
            return;
        }
        if (coordinator == null || !coordinator.isConnected()) {
            if (diffMessageView != null) diffMessageView.setText("请先连接相机，再恢复基线。");
            return;
        }
        awaitingRestoreCount = true;
        if (diffMessageView != null) diffMessageView.setText("正在取当前快照，统计与基线的差异…");
        coordinator.snapshotVendorProperties();
    }

    /** 差异统计回来后弹确认框；用户确认才真正调用协调器写回。 */
    private void showRestoreConfirm(int changedCount) {
        if (diffMessageView == null) return;
        if (changedCount <= 0) {
            diffMessageView.setText(R.string.property_restore_none);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.property_restore_confirm_title)
                .setMessage(getString(R.string.property_restore_confirm_message, changedCount))
                .setPositiveButton(R.string.property_restore_to_camera, (dialog, which) -> {
                    if (diffMessageView != null) diffMessageView.setText("正在把基线写回相机…");
                    coordinator.restoreVendorProperties(diffBaseline);
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** 与基线确实不同的属性个数（只算有原始字节、能写回的项），与协调器的写回口径一致。 */
    private static int countRestorable(VendorPropertySnapshot current, VendorPropertySnapshot baseline) {
        if (current == null || baseline == null) return 0;
        int count = 0;
        for (VendorPropertySnapshot.Diff diff : current.diff(baseline)) {
            if (diff.kind == VendorPropertySnapshot.Diff.CHANGED
                    && diff.before != null && diff.before.hasRaw()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void onVendorPropertyRestoreFinished(int total, int written, String failedCodes) {
        runOnUiThread(() -> {
            String message = getString(R.string.property_restore_done, written, total);
            if (failedCodes != null && !failedCodes.isEmpty()) {
                message = message + "\n\n" + getString(R.string.property_restore_failed, failedCodes);
            }
            if (diffMessageView != null) diffMessageView.setText(message);
            statusView.setText(message.replace('\n', ' '));
        });
    }

    /**
     * 厂商属性「整块读取」的回调（HDR 与对焦包围两个面板共用）。写入后会自动再读一次做回读核对，
     * 所以这里要能分清「用户点的读取」和「写入后的核对」——靠各自的 pending 缓冲是否为空。
     * 两个功能读的是不同属性码，先按 propertyCode 分流，再各自判断面板还在不在。
     */
    @Override
    public void onVendorPropertyBlockRead(int propertyCode, byte[] block, String error) {
        runOnUiThread(() -> {
            if (propertyCode == VendorProperty.FOCUS_SHIFT_SETTING) {
                onFocusBracketBlockRead(block, error);
                return;
            }
            // 面板已关或回调迟到：直接丢弃，不要写进别的界面。
            if (hdrReportView == null) return;
            if (error != null || block == null) {
                hdrReportView.setText("读取 " + VendorProperty.hex(propertyCode) + " 失败：" + error
                        + "\n\n写入必须以相机自己的 24 字节为底稿，读不到就不能写。");
                pendingHdrWrite = null;
                return;
            }
            byte[] previous = hdrBlock;
            hdrBlock = block;
            String check = takeHdrWriteCheck(block, previous);
            showHdrBlock(
                    "0xD13D EOS_HDRSetting 当前值"
                            + (check.isEmpty() ? "（只读）" : "（写入后自动回读）"),
                    block,
                    check.isEmpty()
                            ? "\n偏移 4 的模式由差分实测证实（0 = 关闭、1 = 自动）；"
                            + "其余字节原样保留相机给的内容。"
                            : check);
        });
    }

    /** 厂商属性「整块写入」的回调。写入被受理后立刻回读一次核对，不把「回了 OK」当成结论。 */
    @Override
    public void onVendorPropertyBlockWritten(int propertyCode, int code, String error) {
        runOnUiThread(() -> {
            if (propertyCode == VendorProperty.FOCUS_SHIFT_SETTING) {
                onFocusBracketBlockWritten(code, error);
                return;
            }
            if (hdrReportView == null) {
                pendingHdrWrite = null;
                return;
            }
            if (error != null || code == 0) {
                pendingHdrWrite = null;
                hdrReportView.setText("写入 " + VendorProperty.hex(propertyCode) + " 失败："
                        + (error == null ? "相机未正常应答" : error));
                return;
            }
            hdrReportView.setText("写入 " + VendorProperty.hex(propertyCode) + " 回 "
                    + VendorProperty.hex(code) + "，正在回读核对…");
            coordinator.readVendorPropertyBlock(propertyCode);
        });
    }

    /**
     * 「对焦包围」设置面板：机内 0xD208（{@code PTP_DPC_CANON_EOS_FocusShiftSetting}）
     * 的读改回写。之前只做「按张数收图」，机身没配好就拍不出包围——缺的正是这一块参数界面。
     *
     * <p>0xD208 里只有「偏移 8 = 开关」是差分实测证实的；张数 / 对焦增量 / 曝光平滑化
     * （偏移 12 / 16 / 20）只是旁证。可写性在 RP 上完全未验证（同类属性 {@code 0xD11D}
     * 就是被拒 {@code 0x2019} 的先例），所以写入失败要如实报错，不能让界面崩。
     */
    private void openBracketCapture() {
        if (coordinator == null || !coordinator.isConnected()) {
            statusView.setText("请先连接相机，再设置对焦包围。");
            return;
        }
        if (focusBracketDialog != null && focusBracketDialog.isShowing()) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_focus_bracket, null);
        focusReportView = content.findViewById(R.id.focusReport);
        focusEnabledField = content.findViewById(R.id.focusEnabled);
        focusCountField = content.findViewById(R.id.focusCount);
        focusIncrementField = content.findViewById(R.id.focusIncrement);
        focusSmoothingField = content.findViewById(R.id.focusSmoothing);
        if (focusBracketBlock == null) {
            focusReportView.setText("正在读取 0xD208…（只读）");
        } else {
            // 先把上次那份字节立即摆出来（进面板就有东西可看、可改），随后的一次默认读取会覆盖它。
            showFocusBracketBlock("上次读到的 0xD208（正在刷新…）", focusBracketBlock, "");
        }
        content.findViewById(R.id.focusEnabledOff).setOnClickListener(view -> focusEnabledField.setText("0"));
        content.findViewById(R.id.focusEnabledOn).setOnClickListener(view -> focusEnabledField.setText("1"));
        content.findViewById(R.id.focusRead).setOnClickListener(view -> {
            pendingFocusBracketWrite = null;
            focusReportView.setText("正在读取 0xD208…（只读）");
            coordinator.readVendorPropertyBlock(VendorProperty.FOCUS_SHIFT_SETTING);
        });
        content.findViewById(R.id.focusWrite).setOnClickListener(view -> writeFocusBracket());
        // 「写入后打开拍摄」：写完参数后收起本面板并打开拍摄面板（张数已预填），不再自动开拍。
        content.findViewById(R.id.focusShoot).setOnClickListener(view -> {
            pendingShootAfterWrite = true;
            writeFocusBracket();
        });
        content.findViewById(R.id.focusCalculator).setOnClickListener(view -> openFocusCalculator());
        focusBracketDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.focus_title)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .create();
        focusBracketDialog.setOnDismissListener(dialog -> {
            focusBracketDialog = null;
            focusReportView = null;
            focusEnabledField = null;
            focusCountField = null;
            focusIncrementField = null;
            focusSmoothingField = null;
            pendingFocusBracketWrite = null;
            pendingShootAfterWrite = false;
        });
        focusBracketDialog.show();
        // 进面板就默认读一次：省掉「先点读取当前值」这一步；用户手动读取时会再清一次 pending。
        pendingFocusBracketWrite = null;
        coordinator.readVendorPropertyBlock(VendorProperty.FOCUS_SHIFT_SETTING);
    }

    /** 把读到的 24 字节摆进报告，并把四个字段预填进输入框。 */
    private void showFocusBracketBlock(String title, byte[] block, String footer) {
        if (focusReportView == null) return;
        focusReportView.setText(title + "\n\n" + FocusBracketPlan.describeBlock(block) + footer);
        try {
            FocusBracketPlan plan = FocusBracketPlan.fromBlock(block);
            focusEnabledField.setText(String.valueOf(plan.enabled()));
            focusCountField.setText(String.valueOf(plan.count()));
            focusIncrementField.setText(String.valueOf(plan.increment()));
            focusSmoothingField.setText(String.valueOf(plan.smoothing()));
        } catch (IllegalArgumentException ignored) {
            // 长度不是 24 的机型：原始字节照常展示，四个值留给用户自己填。
        }
    }

    /** 对焦包围面板的「整块读取」回调（含写入后的自动回读）。 */
    private void onFocusBracketBlockRead(byte[] block, String error) {
        if (focusReportView == null) return;
        if (error != null || block == null) {
            focusReportView.setText("读取 0xD208 失败：" + error
                    + "\n\n写入必须以相机自己的 24 字节为底稿，读不到就不能写。");
            pendingFocusBracketWrite = null;
            return;
        }
        byte[] previous = focusBracketBlock;
        focusBracketBlock = block;
        String check = takeFocusBracketWriteCheck(block, previous);
        showFocusBracketBlock(
                "0xD208 EOS_FocusShiftSetting 当前值"
                        + (check.isEmpty() ? "（只读）" : "（写入后自动回读）"),
                block,
                check.isEmpty()
                        ? "\n偏移 8 的开关由差分实测证实（0 = 关闭、1 = 开启）；"
                        + "偏移 12 / 16 / 20 只是旁证，其余字节原样保留。"
                        : check);
    }

    /** 对焦包围面板的「整块写入」回调。写入被受理后立刻回读一次核对。 */
    private void onFocusBracketBlockWritten(int code, String error) {
        if (focusReportView == null) {
            pendingFocusBracketWrite = null;
            pendingShootAfterWrite = false;
            return;
        }
        if (error != null || code == 0) {
            pendingFocusBracketWrite = null;
            // 写失败不去拍：张数没写进相机，拍了也收不到包围序列。
            pendingShootAfterWrite = false;
            focusReportView.setText("写入 0xD208 失败："
                    + (error == null ? "相机未正常应答" : error)
                    + "\n\n若为 0x2019（参数不受支持），说明这条属性在您这台机身上不可写，"
                    + "请改用机身菜单设置对焦包围。");
            return;
        }
        focusReportView.setText("写入 0xD208 回 " + VendorProperty.hex(code) + "，正在回读核对…");
        // 开关是「开」时，把张数同步成拍摄面板的「预计张数」——否则用户还要在两个面板各填一次，
        // 填漏了就会「等了半天只出一张」。
        if (focusEnabledField != null && focusCountField != null
                && "1".equals(focusEnabledField.getText().toString().trim())) {
            try {
                expectedShots = Math.max(1, Integer.parseInt(focusCountField.getText().toString().trim()));
            } catch (NumberFormatException ignored) {
                // 张数不是整数：保留原来的预计张数，拍摄面板的输入框仍可手填。
            }
        }
        // 「写入后打开拍摄」：只收起本面板、打开拍摄面板（张数已预填），让取景先起来、
        // 由用户自己在面板里按快门。绝不在开面板的同时立刻拍摄——开取景是异步的，背靠背
        // 触发拍摄会先暂停还没起来的取景，表现为黑屏，并在用户未操作时就误拍、误取回、误合成。
        if (pendingShootAfterWrite) {
            pendingShootAfterWrite = false;
            if (focusBracketDialog != null) focusBracketDialog.dismiss();
            openCapturePanel();
            return;
        }
        coordinator.readVendorPropertyBlock(VendorProperty.FOCUS_SHIFT_SETTING);
    }

    /**
     * 写入后回读的那一次读取，用刚写下去的字节与相机现在报的字节逐字节比对。
     * 与 HDR 面板同样的三档判词：一致 / 相机没回报新值 / 相机改写了字段——分开报才分得清
     * 「读不到」与「字段布局不对」。
     *
     * @param previous 写入前的底稿，用来区分「相机改写了字段」与「相机压根没回报新值」
     * @return 结论文案；本次读取不是回读时返回空串
     */
    private String takeFocusBracketWriteCheck(byte[] block, byte[] previous) {
        byte[] expected = pendingFocusBracketWrite;
        if (expected == null) return "";
        pendingFocusBracketWrite = null;
        if (Arrays.equals(expected, block)) {
            return "\n\n回读核对：与刚写入的 24 字节完全一致，相机已收下。"
                    + "\n但这只说明收下了，是否真的启用（以及张数、增量是否正确）请到机身屏幕核对。";
        }
        if (previous != null && Arrays.equals(previous, block)) {
            return "\n\n回读核对：读回的仍是写入前那份字节——相机这次没有回报新值，"
                    + "无法判断写入是否生效。以机身屏幕为准，或重连后再读一次。";
        }
        return "\n\n回读核对：与刚写入的字节不一致——相机改写了其中一部分。"
                + "这是有价值的结论：说明该字段的布局与设想不同，别照这次的推断继续用。";
    }

    /** 把输入框里的四个字段叠到读回的 24 字节底稿上写回相机。没有底稿就不写。 */
    private void writeFocusBracket() {
        if (focusReportView == null) return;
        if (focusBracketBlock == null) {
            // 早退一律清掉「写完就拍」的标记：写都没写成就不能去拍。
            pendingShootAfterWrite = false;
            focusReportView.setText("还没有 0xD208 的底稿，请先点「读取当前值」。");
            return;
        }
        long enabled;
        long count;
        long increment;
        long smoothing;
        try {
            enabled = Long.parseLong(focusEnabledField.getText().toString().trim());
            count = Long.parseLong(focusCountField.getText().toString().trim());
            increment = Long.parseLong(focusIncrementField.getText().toString().trim());
            smoothing = Long.parseLong(focusSmoothingField.getText().toString().trim());
        } catch (NumberFormatException exception) {
            pendingShootAfterWrite = false;
            focusReportView.setText("开关 / 张数 / 对焦增量 / 曝光平滑化都要填整数。"
                    + "\n开关：0 = 关闭，1 = 开启。"
                    + "\n张数：" + FocusBracketPlan.MIN_COUNT + "–" + FocusBracketPlan.MAX_COUNT
                    + "（开启时）。对焦增量：" + FocusBracketPlan.MIN_INCREMENT + "–"
                    + FocusBracketPlan.MAX_INCREMENT + "。曝光平滑化：0 = 关闭、1 = 启用。");
            return;
        }
        FocusBracketPlan plan;
        try {
            plan = FocusBracketPlan.forWrite(enabled, count, increment, smoothing);
        } catch (IllegalArgumentException exception) {
            pendingShootAfterWrite = false;
            focusReportView.setText("参数无效：" + exception.getMessage());
            return;
        }
        byte[] payload = plan.applyTo(focusBracketBlock);
        pendingFocusBracketWrite = payload;
        focusReportView.setText("正在写入 0xD208…（" + plan.describe() + "）\n\n"
                + FocusBracketPlan.describeBlock(payload));
        coordinator.writeVendorPropertyBlock(VendorProperty.FOCUS_SHIFT_SETTING, payload);
    }

    // ── 对焦包围参数计算器（纯本地计算，不碰相机） ──

    /**
     * 打开参数计算器面板。它叠在「对焦包围设置」面板之上；关掉它只关自己，下面的面板还在，
     * 所以「填入」时 focusCountField 等字段一定有效。
     */
    private void openFocusCalculator() {
        if (focusCalculatorDialog != null && focusCalculatorDialog.isShowing()) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_focus_calculator, null);
        calcReportView = content.findViewById(R.id.calcReport);
        calcFocalField = content.findViewById(R.id.calcFocal);
        calcApertureField = content.findViewById(R.id.calcAperture);
        calcNearField = content.findViewById(R.id.calcNear);
        calcFarField = content.findViewById(R.id.calcFar);
        calcOverlapField = content.findViewById(R.id.calcOverlap);

        // 默认按「产品」场景起步：打开就有可算的数，用户再按实际改。
        FocusBracketCalculator.Scene scene = FocusBracketCalculator.Scene.PRODUCT;
        calcFocalField.setText("50");
        calcApertureField.setText("8");
        calcNearField.setText(wholeNumber(scene.defaultNearMm()));
        calcFarField.setText(wholeNumber(scene.defaultFarMm()));
        calcOverlapField.setText(String.valueOf(scene.defaultOverlapPercent()));

        content.findViewById(R.id.calcSceneMacro).setOnClickListener(
                view -> applyCalculatorScene(FocusBracketCalculator.Scene.MACRO));
        content.findViewById(R.id.calcSceneProduct).setOnClickListener(
                view -> applyCalculatorScene(FocusBracketCalculator.Scene.PRODUCT));
        content.findViewById(R.id.calcSceneLandscape).setOnClickListener(
                view -> applyCalculatorScene(FocusBracketCalculator.Scene.LANDSCAPE));
        content.findViewById(R.id.calcSceneArchitecture).setOnClickListener(
                view -> applyCalculatorScene(FocusBracketCalculator.Scene.ARCHITECTURE));
        content.findViewById(R.id.calcApply).setOnClickListener(view -> applyCalculatorResult());

        // 五个输入框挂同一个监听：任何一格变动都立即重算，做到「边改边看」。
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                recomputeCalculator();
            }
        };
        calcFocalField.addTextChangedListener(watcher);
        calcApertureField.addTextChangedListener(watcher);
        calcNearField.addTextChangedListener(watcher);
        calcFarField.addTextChangedListener(watcher);
        calcOverlapField.addTextChangedListener(watcher);

        focusCalculatorDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.calc_title)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .create();
        focusCalculatorDialog.setOnDismissListener(dialog -> {
            focusCalculatorDialog = null;
            calcReportView = null;
            calcFocalField = null;
            calcApertureField = null;
            calcNearField = null;
            calcFarField = null;
            calcOverlapField = null;
            calcResult = null;
        });
        focusCalculatorDialog.show();
        recomputeCalculator();
    }

    /** 点场景按钮：把该场景的默认距离与重叠率填进输入框，随后的即时重算会刷新报告。 */
    private void applyCalculatorScene(FocusBracketCalculator.Scene scene) {
        if (calcNearField == null || calcFarField == null || calcOverlapField == null) return;
        calcNearField.setText(wholeNumber(scene.defaultNearMm()));
        calcFarField.setText(wholeNumber(scene.defaultFarMm()));
        calcOverlapField.setText(String.valueOf(scene.defaultOverlapPercent()));
    }

    /**
     * 即时重算。输入不完整或越界时安静返回——正在输入「1」想打成「1000」的中间态很常见，
     * 每次都报错只会挡住输入；把上一份报告留在屏上，等数字填全了自然会刷新。
     */
    private void recomputeCalculator() {
        if (calcReportView == null) return;
        Double focal = readNumber(calcFocalField);
        Double aperture = readNumber(calcApertureField);
        Double near = readNumber(calcNearField);
        Double far = readNumber(calcFarField);
        Double overlap = readNumber(calcOverlapField);
        if (focal == null || aperture == null || near == null || far == null || overlap == null) {
            return;
        }
        try {
            calcResult = FocusBracketCalculator.compute(
                    focal, aperture, near, far, (int) Math.round(overlap));
        } catch (IllegalArgumentException exception) {
            calcResult = null;
            calcReportView.setText(getString(R.string.calc_invalid) + "\n\n" + exception.getMessage());
            return;
        }
        calcReportView.setText(calcResult.report());
    }

    /** 把计算结果填进对焦包围面板的张数 / 增量 / 平滑化，并把开关置为「开」。 */
    private void applyCalculatorResult() {
        if (calcResult == null) {
            if (calcReportView != null) calcReportView.setText(R.string.calc_invalid);
            return;
        }
        if (focusCountField == null || focusIncrementField == null
                || focusSmoothingField == null || focusEnabledField == null) {
            statusView.setText("对焦包围面板已关闭，无法填入。");
            return;
        }
        focusEnabledField.setText("1");
        focusCountField.setText(String.valueOf(calcResult.count));
        focusIncrementField.setText(String.valueOf(calcResult.incrementPrimary));
        focusSmoothingField.setText(String.valueOf(calcResult.smoothing));
        if (focusReportView != null) {
            focusReportView.setText("已从计算器填入：" + calcResult.summary()
                    + "\n\n确认无误后点「写入设置」发给相机。");
        }
        statusView.setText("计算器已填入：" + calcResult.summary());
        if (focusCalculatorDialog != null) focusCalculatorDialog.dismiss();
    }

    /** 读一个输入框里的数字；空或非数字返回 null（保持安静，不打断输入）。 */
    private static Double readNumber(EditText field) {
        if (field == null) return null;
        String text = field.getText().toString().trim();
        if (text.isEmpty()) return null;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 把整数值写成不带小数点的字符串，便于填进输入框。 */
    private static String wholeNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * 「HDR 拍摄」设置面板：机内 0xD13D 的读改回写。这是能力探测之后第一条真正写 0xD1xx
     * 厂商属性的功能，所以界面把「原始字节」摆在最显眼处，模式只是叠加在相机自己那份 24 字节
     * 之上的偏移 4。写入后自动回读核对，让「相机收下了」与「真的启用了」分得开。
     */
    private void openHdrDialog() {
        if (coordinator == null || !coordinator.isConnected()) {
            statusView.setText("请先连接相机，再设置 HDR。");
            return;
        }
        if (hdrDialog != null && hdrDialog.isShowing()) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_hdr, null);
        hdrReportView = content.findViewById(R.id.hdrReport);
        hdrModeField = content.findViewById(R.id.hdrMode);
        if (hdrBlock == null) {
            hdrReportView.setText("正在读取 0xD13D…（只读）");
        } else {
            // 先把上次那份字节立即摆出来（进面板就有东西可看、可改），随后的一次默认读取会覆盖它。
            showHdrBlock("上次读到的 0xD13D（正在刷新…）", hdrBlock, "");
        }
        content.findViewById(R.id.hdrModeOff).setOnClickListener(view -> hdrModeField.setText("0"));
        content.findViewById(R.id.hdrModeAuto).setOnClickListener(view -> hdrModeField.setText("1"));
        content.findViewById(R.id.hdrRead).setOnClickListener(view -> {
            pendingHdrWrite = null;
            hdrReportView.setText("正在读取 0xD13D…（只读）");
            coordinator.readVendorPropertyBlock(VendorProperty.HDR_SETTING);
        });
        content.findViewById(R.id.hdrWrite).setOnClickListener(view -> writeHdrMode());
        hdrDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.hdr_title)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .create();
        hdrDialog.setOnDismissListener(dialog -> {
            hdrDialog = null;
            hdrReportView = null;
            hdrModeField = null;
            pendingHdrWrite = null;
        });
        hdrDialog.show();
        // 进面板就默认读一次：省掉「先点读取当前值」这一步；用户手动读取时会再清一次 pending。
        pendingHdrWrite = null;
        coordinator.readVendorPropertyBlock(VendorProperty.HDR_SETTING);
    }

    /**
     * 「景深合成自检」：在手机上启动 Chaquopy，导入 numpy/cv2 并跑一遍景深合成演示，
     * 结果直接显示在面板里——不依赖 adb，也不用连接相机。
     *
     * <p>这是临时验证入口，要回答的是「Python 运行时与 OpenCV 原生库在真机上能不能加载、
     * 算子能不能算出正确结果」。它与相机的 PTP 会话毫无关系，所以自带线程、
     * 不占用 CameraSessionCoordinator 的那个单线程 executor。
     */
    private void openStackCheck() {
        if (stackDialog != null && stackDialog.isShowing()) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_focusstack_check, null);
        stackReportView = content.findViewById(R.id.stackReport);
        stackImageView = content.findViewById(R.id.stackImage);
        content.findViewById(R.id.stackRerun).setOnClickListener(view -> runStackCheck());
        stackDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.stack_check_title)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .create();
        stackDialog.setOnDismissListener(dialog -> {
            stackDialog = null;
            stackReportView = null;
            stackImageView = null;
        });
        stackDialog.show();
        runStackCheck();
    }

    /** 跑自检。Python 首次启动要解包运行时，可能要等几秒，所以放独立线程。 */
    private void runStackCheck() {
        if (stackReportView != null) stackReportView.setText(R.string.stack_check_running);
        if (stackImageView != null) {
            stackImageView.setVisibility(View.GONE);
            stackImageView.setImageDrawable(null);
        }
        new Thread(() -> {
            String report;
            String demoText;
            byte[] png = null;
            try {
                ensurePython();
                PyObject module = Python.getInstance().getModule("focusstack_probe");
                report = module.callAttr("report").toString();
                List<PyObject> demo = module.callAttr("demo").asList();
                png = demo.get(0).toJava(byte[].class);
                demoText = demo.get(1).toString();
            } catch (Throwable error) {
                postStackResult("自检失败：\n" + stackFailureTrace(error), null);
                return;
            }
            postStackResult(report + "\n\n" + demoText, png);
        }, "focusstack-check").start();
    }

    /** 幂等启动 Chaquopy 运行时。首次启动要解包，可能要几秒，必须在后台线程调用。 */
    private void ensurePython() {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(getApplicationContext()));
        }
    }

    /**
     * 合成/自检失败时把异常连同堆栈整段显示出来。
     * 这里最容易出问题的是原生库加载（.so 缺失/ABI 不匹配），只显示「失败」两个字没法定位。
     */
    private static String stackFailureTrace(Throwable error) {
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    /** 把结果贴回界面；合成图解码失败也要说清楚，不能悄悄空着。 */
    private void postStackResult(String text, byte[] png) {
        runOnUiThread(() -> {
            if (stackReportView != null) stackReportView.setText(text);
            if (png == null || stackImageView == null) return;
            Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bitmap == null) {
                if (stackReportView != null) {
                    stackReportView.setText(text + "\n\n（合成图解码失败，收到 " + png.length + " 字节）");
                }
                return;
            }
            stackImageView.setImageBitmap(bitmap);
            stackImageView.setVisibility(View.VISIBLE);
        });
    }

    // ── 真实照片景深合成（对焦包围批次取回后自动触发） ──

    /**
     * 对一批已落盘的对焦包围照片做景深合成，并把结果与报告显示在面板里。
     *
     * <p>合成要跑 Python/OpenCV，绝不能占用 CameraSessionCoordinator 的那个单线程 executor
     * （会冻住 PTP 会话与心跳），所以这里起独立线程；结果回来再切回主线程贴到面板上。
     * 合成图同时写进应用内目录，并按设置导出到系统相册。
     */
    private void startFocusStack(List<File> files) {
        startFocusStack(files, false);
    }

    /**
     * 同上，但 {@code manualPick} 为真时是「手动挑的旧照片」这一路：
     * 那些照片是用户的成品，绝不能像对焦包围批次那样在合成后被清理——合成本身只读它们，
     * 唯一会被删的是我们自己复制出来的临时副本。
     */
    private void startFocusStack(List<File> files, boolean manualPick) {
        if (stackRunning) {
            statusView.setText("上一次景深合成还没结束，本次先跳过。");
            return;
        }
        stackRunning = true;
        statusView.setText("正在对 " + files.size() + " 张照片做景深合成…"
                + "（首次运行要启动 Python，请稍候）");
        showStackResult("正在合成…（全分辨率，2600 万像素级别可能要几分钟）", null);

        // 路径用 java.util.List 交给 Chaquopy；注意它并不会把 List 转成 Python 的 list，
        // 传过去的是 Java 代理对象（Python 侧按 size()/get() 取值，见 focusstack.stack）。
        // 第二个参数是进度回调，Python 侧每推进一步就调一次（Consumer 同样是代理，
        // 只能走 accept()，Python 侧已做兼容）。
        List<String> paths = new ArrayList<>();
        for (File file : files) paths.add(file.getAbsolutePath());
        java.util.function.Consumer<String> progress = message ->
                runOnUiThread(() -> {
                    if (stackResultReportView != null) {
                        stackResultReportView.setText(message + "\n\n（合成进行中，请勿关闭本面板）");
                    }
                    statusView.setText(message);
                });

        new Thread(() -> {
            String report;
            byte[] fullJpeg = null;
            byte[] preview = null;
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(getApplicationContext()));
                }
                PyObject module = Python.getInstance().getModule("focusstack");
                List<PyObject> result = module.callAttr("stack", paths, progress).asList();
                fullJpeg = result.get(0).toJava(byte[].class);
                preview = result.get(1).toJava(byte[].class);
                report = result.get(2).toString();
                if (manualPick) {
                    report = "来源：手动选择的 " + files.size() + " 张照片"
                            + "（合成只读这些照片，原件不会被动到）。\n\n" + report;
                }

                File output = focusStackOutputFile();
                try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) {
                    stream.write(fullJpeg);
                }
                lastStackResultFile = output;
                report = report + "\n\n合成图已写入：" + output.getName()
                        + "（" + (fullJpeg.length / 1024) + " KB）";
                report = report + exportStackToGallery(output);
                if (manualPick) {
                    report = report + cleanPickScratch();
                } else {
                    // 合成已经成功、结果也拿到手了，这一批的应用内原图副本没必要再占空间。
                    report = report + cleanStackCopies();
                }
            } catch (Throwable error) {
                report = "景深合成失败：\n" + stackFailureTrace(error);
                report = report + (manualPick
                        ? "\n\n选中的照片原封不动，临时副本也已清理，重新挑一次即可再试。"
                        : "\n\n原图副本没有清理——合成没成，先留着，方便重试或手动处理。");
                if (manualPick) cleanPickScratch();
                preview = null;
            }
            final String text = report;
            final byte[] thumb = preview;
            runOnUiThread(() -> {
                stackRunning = false;
                // 贴进面板的是 Python 另外给的小预览图：直接解码 2600 万像素的合成图会把 Java 堆撑爆。
                showStackResult(text, thumb);
            });
        }, "focusstack").start();
    }

    // ── 手动挑照片做景深合成 ──

    /**
     * 打开系统文件选择器多选图片。用 SAF（{@code ACTION_OPEN_DOCUMENT}）而不是直接读相册：
     * 不需要申请任何存储权限，用户翻到哪个目录都行——包括本应用以前导出到
     * DCIM/IMAGE_FETCH 的旧照片。
     *
     * <p>选中的照片会在 {@link #onActivityResult} 里复制成应用内的临时副本，因为
     * Python/OpenCV 只认文件路径，读不了 {@code content://} 这种 URI。原件全程只读，
     * 合成结束时清理的也只有这些我们自己复制出来的副本。
     */
    private void pickStackPhotos() {
        if (stackRunning) {
            statusView.setText("上一次景深合成还没结束，等它跑完再挑照片。");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_TITLE, getString(R.string.stack_pick_title));
        try {
            startActivityForResult(intent, REQUEST_PICK_STACK);
        } catch (Exception error) {
            showError("打不开系统文件选择器，这台设备上可能没有可用的文件管理应用。", error);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // 胶片页的「从手机选图」结果交回给它自己处理，一个对话框只选一张。
        if (requestCode == FilmSimulationDialog.REQUEST_PICK_LOCAL_IMAGE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && filmDialog != null) {
                filmDialog.onLocalImagePicked(data.getData());
            }
            return;
        }
        // 运行时权限申请走 onRequestPermissionsResult，与这里互不干扰；不认识的请求码交回父类。
        if (requestCode != REQUEST_PICK_STACK) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != RESULT_OK || data == null) return;

        List<Uri> picked = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                Uri uri = clip.getItemAt(index).getUri();
                if (uri != null) picked.add(uri);
            }
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (picked.size() < 2) {
            statusView.setText(R.string.stack_pick_need_two);
            return;
        }

        statusView.setText(R.string.stack_pick_copying);
        new Thread(() -> {
            List<File> copies;
            try {
                copies = copyPickedToScratch(picked);
            } catch (Throwable error) {
                String note = getString(R.string.stack_pick_failed) + "\n"
                        + stackFailureTrace(error);
                runOnUiThread(() -> {
                    statusView.setText(R.string.stack_pick_failed);
                    showStackResult(note, null);
                });
                return;
            }
            runOnUiThread(() -> startFocusStack(copies, true));
        }, "stack-pick").start();
    }

    /**
     * 把选中的 URI 逐个复制成应用私有的临时副本，返回按文件名排好序的副本清单。
     *
     * <p>排序是因为文件选择器给的是「用户点选的顺序」，而相机连拍的文件名天然零填充
     * （IMG_0001、IMG_0002…），按名排序就等于按拍摄顺序，也就是先近后远——
     * 这正好是景深合成想要的顺序。
     */
    private List<File> copyPickedToScratch(List<Uri> uris) throws IOException {
        File directory = pickScratchDirectory();
        List<PickedPhoto> photos = new ArrayList<>();
        for (Uri uri : uris) photos.add(new PickedPhoto(queryDisplayName(uri), uri));
        Collections.sort(photos, (left, right) ->
                String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name));

        List<File> copies = new ArrayList<>();
        for (int index = 0; index < photos.size(); index++) {
            PickedPhoto photo = photos.get(index);
            File target = new File(directory, scratchName(directory, index, photo.name));
            try (InputStream source = getContentResolver().openInputStream(photo.uri);
                 OutputStream sink = new FileOutputStream(target)) {
                if (source == null) throw new IOException("读不到 " + photo.uri);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = source.read(buffer)) > 0) sink.write(buffer, 0, read);
            }
            copies.add(target);
        }
        return copies;
    }

    /** 临时副本目录：cache 下的独立子目录，每次挑照片前清空。 */
    private File pickScratchDirectory() {
        File directory = new File(getCacheDir(), "stackpick");
        deleteRecursively(directory);
        if (directory.mkdirs() || directory.isDirectory()) return directory;
        // 建不出子目录就退回 cache 根目录——不让一个目录问题挡住合成。
        return getCacheDir();
    }

    /** 清掉手动挑照片留下的临时副本。选中的原件始终没动，所以这里删的只可能是我们自己复制的。 */
    private String cleanPickScratch() {
        File directory = new File(getCacheDir(), "stackpick");
        File[] children = directory.listFiles();
        int removed = 0;
        long freed = 0;
        if (children != null) {
            for (File child : children) {
                long size = child.length();
                deleteRecursively(child);
                if (!child.exists()) {
                    removed++;
                    freed += size;
                }
            }
        }
        return "\n\n临时副本已清理 " + removed + " 个（" + (freed / (1024 * 1024))
                + " MB）；选中的原始照片一张都没动。";
    }

    /** 临时副本的文件名：尽量沿用原文件名（报告里认得出来），重名时加序号。 */
    private static String scratchName(File directory, int index, String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        // 文件系统里不能出现路径分隔符；其余字符（含中文）照留，报告里才看得懂。
        name = name.replace('/', '_').replace('\\', '_').replace('\0', '_');
        if (name.isEmpty()) name = "pick_" + index + ".jpg";
        return new File(directory, name).exists() ? index + "_" + name : name;
    }

    /** 问系统要这张图在文件管理器里显示的名字（SAF 的 URI 本身不带文件名）。 */
    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column)) return cursor.getString(column);
            }
        } catch (Exception ignored) {
            // 问不到名字不算错：退回按序号命名，合成照跑。
        }
        return null;
    }

    private static void deleteRecursively(File target) {
        if (target == null || !target.exists()) return;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        target.delete();
    }

    /** 一个待复制的选中项：显示名 + 源 URI（排序要按名字，复制要按 URI，所以得配成对）。 */
    private static final class PickedPhoto {
        final String name;
        final Uri uri;

        PickedPhoto(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    /** 合成图落盘位置：与下载原图同一个目录，前缀区分，便于用户一并找到。 */
    private File focusStackOutputFile() {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                .format(new java.util.Date());
        return new File(coordinator.outputDirectory(), "focusstack_" + stamp + ".jpg");
    }

    /** 按「保存到系统相册」设置导出合成图；失败只上报，不影响面板里已经看到的结果。 */
    private String exportStackToGallery(File file) {
        if (!exportToGalleryBox.isChecked()) return " · 未勾选「保存到系统相册」";
        try {
            new GalleryExporter(this).export(file, file.getName());
            return " · 已存入系统相册 " + GalleryExporter.RELATIVE_PATH;
        } catch (Exception error) {
            return " · 相册导出失败：" + (error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    /**
     * 清理这一批的应用内原图副本（合成成功后才调）。
     *
     * <p>清单由协调器给：只有<b>已确认导出到相册</b>的副本才在里面。导出关掉或导出失败的
     * 副本不在其中——那种情况下应用内这份就是唯一原件，删掉照片就没了。
     */
    private String cleanStackCopies() {
        List<File> cleanable = coordinator.takeStackCleanableCopies();
        if (cleanable.isEmpty()) {
            return "\n\n原图副本已保留：这一批没有导出到相册，应用内这份就是唯一原件。";
        }
        int removed = 0;
        long freed = 0;
        for (File file : cleanable) {
            long size = file.length();
            if (file.delete()) {
                removed++;
                freed += size;
            }
        }
        return "\n\n已清理 " + removed + " / " + cleanable.size() + " 张应用内原图副本，释放 "
                + (freed / (1024 * 1024)) + " MB；原件仍在相册 "
                + GalleryExporter.RELATIVE_PATH + " 里。";
    }

    /**
     * 把上一次的合成图再导一次到相册。原图副本在合成成功后已按设置清理，
     * 所以这里不再重跑合成——重导成品图既便宜又不依赖那批原图。
     */
    private void reexportLastStackResult() {
        File result = lastStackResultFile;
        if (result == null || !result.isFile()) {
            if (stackResultReportView != null) {
                stackResultReportView.setText("还没有可导出的合成图。");
            }
            return;
        }
        String note = exportStackToGallery(result);
        if (stackResultReportView != null) {
            stackResultReportView.setText("合成图 " + result.getName() + note);
        }
    }

    /** 结果面板：报告 + 合成图预览 + 再次导出成品图。重复调用复用同一个面板。 */
    private void showStackResult(String text, byte[] png) {
        if (stackResultDialog == null) {
            View content = getLayoutInflater().inflate(R.layout.dialog_focusstack_result, null);
            stackResultReportView = content.findViewById(R.id.stackResultReport);
            stackResultImageView = content.findViewById(R.id.stackResultImage);
            Button rerun = content.findViewById(R.id.stackResultRerun);
            rerun.setOnClickListener(view -> reexportLastStackResult());
            stackResultDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.stack_result_title)
                    .setView(content)
                    .setNegativeButton(R.string.close, null)
                    .create();
            stackResultDialog.setOnDismissListener(dialog -> {
                stackResultDialog = null;
                stackResultReportView = null;
                stackResultImageView = null;
            });
            stackResultDialog.show();
        }
        stackResultReportView.setText(text);
        if (png == null) {
            stackResultImageView.setVisibility(View.GONE);
            stackResultImageView.setImageDrawable(null);
            return;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bitmap == null) {
            stackResultReportView.setText(text + "\n\n（合成图解码失败，收到 " + png.length + " 字节）");
            stackResultImageView.setVisibility(View.GONE);
            return;
        }
        stackResultImageView.setImageBitmap(bitmap);
        stackResultImageView.setVisibility(View.VISIBLE);
    }

    /** 把读到的 24 字节摆进报告，并把偏移 4 的模式预填进输入框。 */
    private void showHdrBlock(String title, byte[] block, String footer) {
        if (hdrReportView == null) return;
        hdrReportView.setText(title + "\n\n" + HdrPlan.describeBlock(block) + footer);
        try {
            hdrModeField.setText(String.valueOf(HdrPlan.fromBlock(block).mode()));
        } catch (IllegalArgumentException ignored) {
            // 长度不是 24 的机型：原始字节照常展示，模式值留给用户自己填。
        }
    }

    /**
     * 写入后回读的那一次读取，用刚写下去的字节与相机现在报的字节逐字节比对。
     * 不一致是有价值的结论（说明字段布局与设想不同），必须如实报出来，不能只报「写入成功」。
     *
     * @param previous 写入前的底稿，用来区分「相机改写了字段」与「相机压根没回报新值」
     * @return 结论文案；本次读取不是回读时返回空串
     */
    private String takeHdrWriteCheck(byte[] block, byte[] previous) {
        byte[] expected = pendingHdrWrite;
        if (expected == null) return "";
        pendingHdrWrite = null;
        if (Arrays.equals(expected, block)) {
            return "\n\n回读核对：与刚写入的 24 字节完全一致，相机已收下。"
                    + "\n但这只说明收下了，是否真的启用（以及是哪一档）请到机身屏幕核对。";
        }
        if (previous != null && Arrays.equals(previous, block)) {
            // 相机这次没把缓存换成新值（多半是没推 0xC189），不是它改写了字段。
            // 这两种情形必须分开报：混在一起会把「读不到」误判成「字段布局不对」。
            return "\n\n回读核对：读回的仍是写入前那份字节——相机这次没有回报新值，"
                    + "无法判断写入是否生效。以机身屏幕为准，或重连后再读一次。";
        }
        return "\n\n回读核对：与刚写入的字节不一致——相机改写了其中一部分。"
                + "这是有价值的结论：说明该字段的布局与设想不同，别照这次的推断继续用。";
    }

    /** 把输入框里的模式叠到读回的 24 字节底稿上写回相机。没有底稿就不写。 */
    private void writeHdrMode() {
        if (hdrReportView == null) return;
        if (hdrBlock == null) {
            hdrReportView.setText("还没有 0xD13D 的底稿，请先点「读取当前值」。");
            return;
        }
        long mode;
        try {
            mode = Long.parseLong(hdrModeField.getText().toString().trim());
        } catch (NumberFormatException exception) {
            hdrReportView.setText("模式要填整数：0 = 关闭，1 = 自动；其余取值没有证据。");
            return;
        }
        HdrPlan plan;
        try {
            plan = new HdrPlan(mode);
        } catch (IllegalArgumentException exception) {
            hdrReportView.setText("模式取值无效：" + exception.getMessage());
            return;
        }
        byte[] payload = plan.applyTo(hdrBlock);
        pendingHdrWrite = payload;
        hdrReportView.setText("正在写入 0xD13D…（" + plan.describe() + "）\n\n"
                + HdrPlan.describeBlock(payload));
        coordinator.writeVendorPropertyBlock(VendorProperty.HDR_SETTING, payload);
    }

    /**
     * P0 能力探测报告（状态行右侧「能力探测」按钮，或长按状态文字）。内容按需填进一个可滚动的
     * TextView。「写入验证」按钮做的是原值回写——虽然写的是相机自己刚报的值，但真的会写相机，
     * 所以只在用户点击后才跑。
     */
    private void showCapabilityReport() {
        if (coordinator == null || !coordinator.isConnected()) {
            statusView.setText("请先连接相机，再看能力报告。");
            return;
        }
        capabilityMessageView = new TextView(this);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        capabilityMessageView.setPadding(padding, padding, padding, padding);
        capabilityMessageView.setTextIsSelectable(true);
        capabilityMessageView.setTextSize(12f);
        // 报告主体是十六进制字节，等宽字体才看得出来对齐与字段边界。
        capabilityMessageView.setTypeface(Typeface.MONOSPACE);
        CapabilityReport cached = coordinator.lastCapabilityReport();
        capabilityMessageView.setText(cached == null
                ? "正在探测…（连接后会自动跑一次只读探测）"
                : cached.describe());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(capabilityMessageView);
        capabilityDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.capability_report_title)
                .setView(scroll)
                .setPositiveButton(R.string.capability_verify_write, null)
                .setNeutralButton(R.string.capability_probe_readonly, null)
                .setNegativeButton(R.string.close, null)
                .create();
        capabilityDialog.setOnShowListener(dialog -> {
            // 自己接管点击：默认行为会关掉弹窗，那样探测结果回来时就没地方显示了。
            Button verify = capabilityDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            verify.setOnClickListener(view -> {
                capabilityMessageView.setText("正在做原值回写验证…（会真的写一次相机属性）");
                coordinator.probeVendorCapabilities(true);
            });
            Button relist = capabilityDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            relist.setOnClickListener(view -> {
                capabilityMessageView.setText("正在重新探测…");
                coordinator.probeVendorCapabilities(false);
            });
        });
        capabilityDialog.setOnDismissListener(dialog -> {
            capabilityDialog = null;
            capabilityMessageView = null;
        });
        capabilityDialog.show();
    }

    /**
     * 把预览控件上的点按位置换算成取景帧像素坐标并下发 0x915B，只移动对焦框、不触发对焦。
     *
     * <p>预览用 {@code fitCenter}：画面按比例缩放后居中，控件坐标 ≠ 帧像素坐标。这里把
     * ImageView 的显示矩阵求逆（image→view 反过来），把点映射回帧坐标系；映射后落在帧外的
     * （黑边）直接忽略，避免把对焦框推到画面外。
     */
    private void moveFocusPointAt(float viewX, float viewY) {
        if (liveViewImage == null || liveViewBitmap == null) return;
        Matrix inverse = new Matrix();
        if (!liveViewImage.getImageMatrix().invert(inverse)) return;
        float[] point = {viewX, viewY};
        inverse.mapPoints(point);
        int frameWidth = liveViewBitmap.getWidth();
        int frameHeight = liveViewBitmap.getHeight();
        int x = Math.round(point[0]);
        int y = Math.round(point[1]);
        // 落在图像外（fitCenter 的黑边）时忽略：相机只认画面内的坐标。
        if (x < 0 || y < 0 || x >= frameWidth || y >= frameHeight) return;
        // 记住落点并在预览上画出小方框：否则「切了没效果」时看不出到底是没下发还是没响应。
        lastFocusFrameX = x;
        lastFocusFrameY = y;
        updateFocusBox();
        coordinator.touchAfPosition(x, y);
    }

    /**
     * 把最近一次落点按当前显示矩阵重映射到控件坐标，喂给方框覆盖层。
     * 每帧都重映射一次：图传尺寸改变后 src→view 的比例会变，缓存控件坐标就会错位。
     */
    private void updateFocusBox() {
        if (focusBoxOverlay == null || liveViewImage == null || liveViewBitmap == null) return;
        // 三分线只覆盖真实画面区域：取景容器与 3:2 帧比例不完全一致时会留黑边。
        int viewWidth = liveViewImage.getWidth();
        int viewHeight = liveViewImage.getHeight();
        int frameWidth = liveViewBitmap.getWidth();
        int frameHeight = liveViewBitmap.getHeight();
        if (viewWidth > 0 && viewHeight > 0 && frameWidth > 0 && frameHeight > 0) {
            float scale = Math.min(viewWidth / (float) frameWidth, viewHeight / (float) frameHeight);
            float shownWidth = frameWidth * scale;
            float shownHeight = frameHeight * scale;
            float left = (viewWidth - shownWidth) * 0.5f;
            float top = (viewHeight - shownHeight) * 0.5f;
            focusBoxOverlay.setContentRect(left, top, left + shownWidth, top + shownHeight);
        }
        if (Float.isNaN(lastFocusFrameX) || Float.isNaN(lastFocusFrameY)) {
            focusBoxOverlay.clear();
            return;
        }
        // 方框边长取帧宽的约 6%（"小一点"），并夹在 [48, 192] 像素之间，免得极端尺寸下离谱。
        float half = Math.min(96f, Math.max(24f, liveViewBitmap.getWidth() * 0.03f));
        float[] pts = {
                lastFocusFrameX - half, lastFocusFrameY - half,
                lastFocusFrameX + half, lastFocusFrameY + half,
        };
        liveViewImage.getImageMatrix().mapPoints(pts);
        focusBoxOverlay.setBoxRect(pts[0], pts[1], pts[2], pts[3]);
    }

    @Override
    public void onCaptureFinished() {
        runOnUiThread(() -> {
            captureRunning = false;
            if (captureShootButton != null) captureShootButton.setEnabled(true);
            // 「本次未发现 JPEG」既落盘也不报错，只有这条回调能把按钮恢复成可用。
            setBusy(false);
            // 单张拍摄 / 未发现 JPEG 的路径没有逐张回调，面板上的「正在拍摄并取回…」会一直挂着；
            // 流程结束这里收掉忙碌提示。只替换仍是忙碌的那条，不动取景过程中报出的其它状态。
            if (liveViewStatusView != null
                    && getString(R.string.capture_running).contentEquals(liveViewStatusView.getText())) {
                liveViewStatusView.setText(R.string.capture_finished);
            }
            // 自动合成的正确挂点：只有 App 发起的拍摄这一批才合成；机身自己拍（App 无操作）
            // 批次窗口根本没开，consume() 取到 appInitiated=false，什么都不做。
            // onFileSaved 的 post 排在本条之前，同一 main looper 队列按序执行，这里拿到的是完整批次。
            FocusStackBatch.Batch batch = stackBatch.consume();
            coordinator.setRetainLocalCopiesForStack(false);
            if (batch.appInitiated() && batch.files().size() >= FocusStackBatch.MIN_SHOTS) {
                if (stackProgressView != null) {
                    stackProgressView.setVisibility(View.VISIBLE);
                    stackProgressView.setText(
                            getString(R.string.stack_progress_done, batch.files().size()));
                }
                startFocusStack(batch.files());
            } else if (batch.appInitiated() && !batch.files().isEmpty()) {
                if (stackProgressView != null) {
                    stackProgressView.setVisibility(View.VISIBLE);
                    stackProgressView.setText(
                            getString(R.string.stack_progress_short, batch.files().size()));
                }
                statusView.setText("本批只取回 " + batch.files().size() + " 张，不足以做景深合成（至少 "
                        + FocusStackBatch.MIN_SHOTS + " 张）。");
            }
        });
    }

    @Override
    public void onAutofocusFinished() {
        runOnUiThread(() -> {
            if (captureAutofocusButton != null) captureAutofocusButton.setEnabled(true);
        });
    }

    @Override
    public void onError(String message, Throwable error) {
        runOnUiThread(() -> {
            setBusy(false);
            showError(message, error);
        });
    }

    // ── 内部 ──

    /** 填充图片栏，并异步逐张拉取缩略图（与相机事务串行，不会与下载交错）。 */
    private void populate(CameraStorage.Page page) {
        gridAdapter.setItems(page.items);
        final List<CameraStorage.Item> items = new ArrayList<>(page.items);
        final int generation = ++gridGeneration;
        // 铺格要为整页每张图 inflate 视图：排到下一轮消息再建，别堵住紧随其后的相机回调。
        gridPager.post(() -> {
            if (generation != gridGeneration) return;
            rebuildPagedGrid(items);
        });
        coordinator.loadThumbnails(items, (handle, jpeg) -> {
            // 一页最多 100 张，解码全部放主线程会和相机结果回调抢同一个消息队列。
            if (isDestroyed()) return;
            if (jpeg == null) {
                android.util.Log.w("ImageFetch", "thumbnail callback null handle=0x"
                        + Long.toHexString(handle));
                return;
            }
            thumbnailExecutor.submit(() -> {
                Bitmap bitmap = PhotoGridAdapter.decodeJpeg(
                        jpeg, PhotoGridAdapter.THUMBNAIL_TARGET_PX);
                if (bitmap == null) {
                    android.util.Log.w("ImageFetch", "thumbnail decode failed handle=0x"
                            + Long.toHexString(handle) + " bytes=" + jpeg.length);
                    return;
                }
                runOnUiThread(() -> {
                    if (generation != gridGeneration) {
                        bitmap.recycle();
                        return;
                    }
                    gridAdapter.putThumbnailBitmap(handle, bitmap);
                    refreshGridThumbnail(handle);
                });
            });
        });
    }

    /**
     * 把本页照片铺成横向翻页的网格：每屏 3 列 × N 行（N 由可用高度决定），
     * 先向下排满一列再往右开下一列，左右滑动整屏翻页。
     */
    private void rebuildPagedGrid(final List<CameraStorage.Item> items) {
        gridPagerContent.removeAllViews();
        gridCells.clear();
        int width = gridPager.getWidth();
        int height = gridPager.getHeight();
        if (width <= 0 || height <= 0) {
            // 首次填充可能早于布局完成：等测量出来再建。
            gridPager.post(() -> rebuildPagedGrid(items));
            return;
        }

        // 3 列：缩略图宽度比 4 列大约三分之一，能看清内容；格子高度目标 96dp，
        // 屏幕高度富余时自动放大（行数 = 高度 ÷ 目标），保证粗估 2~3 行。
        int columnsPerScreen = 3;
        int cellWidth = width / columnsPerScreen;
        int cellHeightDp = 96;
        int rows = Math.max(1, Math.round(height / (float) dp(cellHeightDp)));
        int cellHeight = height / rows;
        gridPageCapacity = columnsPerScreen * rows;
        gridColumns = columnsPerScreen;
        gridRowsPerScreen = rows;

        LinearLayout column = null;
        for (int index = 0; index < items.size(); index++) {
            if (index % rows == 0) {
                column = new LinearLayout(this);
                column.setOrientation(LinearLayout.VERTICAL);
                column.setLayoutParams(new LinearLayout.LayoutParams(cellWidth, height));
                gridPagerContent.addView(column);
            }
            CameraStorage.Item item = items.get(index);
            final int position = index;
            View cell = gridAdapter.getView(index, null, column);
            // 格子之间留 2dp 间隙：宽度/高度按去掉两侧间隙后的尺寸分配。
            int gap = Math.round(dp(2));
            LinearLayout.LayoutParams cellParams =
                    new LinearLayout.LayoutParams(cellWidth - gap * 2, cellHeight - gap * 2);
            cellParams.setMargins(gap, gap, gap, gap);
            cell.setLayoutParams(cellParams);
            // 点缩略图（右上角选中点除外）进入全屏看图。
            cell.setOnClickListener(view -> showPreview(position));
            gridCells.put(item.handle, cell);
            column.addView(cell);
        }

        gridPager.scrollTo(0, 0);
        gridPager.post(() -> updateGridIndex(0, gridScreenCount()));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private int gridScreenCount() {
        int width = Math.max(1, gridPager.getWidth());
        return Math.max(1, (gridPagerContent.getWidth() + width - 1) / width);
    }

    /** 停稳在某屏后更新右下角「第 x-y 张 / 共 n 张」。 */
    private void updateGridIndex(int screen, int screenCount) {
        int total = gridAdapter.getCount();
        if (total == 0 || gridPageCapacity <= 0 || gridColumns <= 0) {
            gridIndexView.setText("");
            gridIndexView.setVisibility(View.GONE);
            return;
        }
        // 最后一屏会与上一屏重叠（内容宽度不是整屏的整数倍），按真实滚动位置
        // 算可见的首列，避免序号和画面对不上。
        int cellWidth = Math.max(1, gridPager.getWidth() / gridColumns);
        int firstColumn = Math.max(0,
                Math.round(gridPager.getScrollX() / (float) cellWidth));
        int start = Math.min(total, firstColumn * gridRowsPerScreen + 1);
        int end = Math.min(total, (firstColumn + gridColumns) * gridRowsPerScreen);
        String text = String.format(Locale.ROOT, "第 %d-%d 张 / 共 %d 张", start, end, total);
        if (screenCount > 1) {
            text += String.format(Locale.ROOT, "（%d/%d 屏）", screen + 1, screenCount);
        }
        gridIndexView.setText(text);
        gridIndexView.setVisibility(View.VISIBLE);
    }

    /** 某张缩略图就绪后只更新那一格，避免整页重建导致滑动位置跳动。 */
    private void refreshGridThumbnail(long handle) {
        View cell = gridCells.get(handle);
        if (cell == null) return;
        Bitmap bitmap = gridAdapter.thumbnail(handle);
        if (bitmap == null) return;
        ((ImageView) cell.findViewById(R.id.thumb)).setImageBitmap(bitmap);
    }

    /** 选中状态变化时同步网格里每格的指示点。 */
    private void refreshGridSelection() {
        for (Map.Entry<Long, View> entry : gridCells.entrySet()) {
            TextView indicator = entry.getValue().findViewById(R.id.selectIndicator);
            boolean checked = gridAdapter.isSelected(entry.getKey());
            indicator.setSelected(checked);
            indicator.setText(checked ? getString(R.string.selected_mark) : "");
        }
    }

    /** 全屏看图翻到某张时，主界面网格跟着滑到那张所在的屏。 */
    private void scrollToPhoto(int position) {
        if (gridPageCapacity <= 0) return;
        int screen = position / gridPageCapacity;
        gridPager.smoothScrollTo(screen * gridPager.getWidth(), 0);
    }

    /** 链路断开时清空网格与序号。 */
    private void clearGridPager() {
        // 代数自增：把还没执行的铺格任务和在途缩略图解码结果一并作废。
        gridGeneration++;
        gridPagerContent.removeAllViews();
        gridCells.clear();
        gridPageCapacity = 0;
        gridColumns = 0;
        gridRowsPerScreen = 0;
        gridIndexView.setText("");
        gridIndexView.setVisibility(View.GONE);
    }

    private void setBusy(boolean busy) {
        // 进度条只在有传输/读取时占位，平时收起，把高度留给缩略图网格。
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        captureButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        downloadButton.setEnabled(!busy);
        deleteButton.setEnabled(!busy);
        connectUsbButton.setEnabled(!busy);
        fullScanButton.setEnabled(!busy);
        pageSizeSpinner.setEnabled(!busy);
        formatFilterSpinner.setEnabled(!busy);
        if (busy) {
            prevPageButton.setEnabled(false);
            nextPageButton.setEnabled(false);
        }
    }

    /**
     * 侧栏（「拍摄选项」）的搭建与开合。
     *
     * <p>零 AndroidX 工程不能用 DrawerLayout，所以侧栏就是「面板 + 遮罩」两个兄弟视图。
     * 面板在 layout_gravity=start 下左边缘本来就贴着屏幕左边，要藏到屏幕外得往<b>负</b>方向平移
     * 一个自身宽度（往正方向平移会横在屏幕中间）。
     *
     * <p>面板保持 INVISIBLE（不是 GONE）而不是初始隐藏：GONE 的视图不会被测量，
     * {@code getWidth()} 会返回 0，滑动距离就算不出来了。
     */
    private void setUpShotPanel() {
        drawerPanel = findViewById(R.id.drawerPanel);
        drawerScrim = findViewById(R.id.drawerScrim);
        capabilityItem = findViewById(R.id.shotCapability);
        diffItem = findViewById(R.id.shotDiff);
        captureItem = findViewById(R.id.shotCapture);
        bracketItem = findViewById(R.id.shotBracket);
        hdrItem = findViewById(R.id.shotHdr);
        stackCheckItem = findViewById(R.id.shotStackCheck);
        stackPickItem = findViewById(R.id.shotStackPick);
        filmItem = findViewById(R.id.shotFilm);

        Button openButton = findViewById(R.id.shotOptions);
        openButton.setOnClickListener(view -> openShotPanel());
        findViewById(R.id.shotClose).setOnClickListener(view -> closeShotPanel());
        drawerScrim.setOnClickListener(view -> closeShotPanel());
        captureItem.setOnClickListener(view -> {
            closeShotPanel();
            openCapturePanel();
        });
        bracketItem.setOnClickListener(view -> {
            closeShotPanel();
            openBracketCapture();
        });
        hdrItem.setOnClickListener(view -> {
            closeShotPanel();
            openHdrDialog();
        });
        stackCheckItem.setOnClickListener(view -> {
            closeShotPanel();
            openStackCheck();
        });
        stackPickItem.setOnClickListener(view -> {
            closeShotPanel();
            pickStackPhotos();
        });
        // 胶片模拟是独立页面：未连接也能打开（可直接用手机里的图片）。
        filmItem.setOnClickListener(view -> {
            closeShotPanel();
            openFilmSimulation();
        });
        capabilityItem.setOnClickListener(view -> {
            closeShotPanel();
            showCapabilityReport();
        });
        diffItem.setOnClickListener(view -> {
            closeShotPanel();
            showPropertyDiff();
        });
        setShotPanelItemsEnabled(false);
        drawerPanel.post(() -> drawerPanel.setTranslationX(-drawerPanel.getWidth()));
    }

    private void openShotPanel() {
        if (drawerOpen) return;
        drawerOpen = true;
        drawerScrim.setVisibility(View.VISIBLE);
        drawerPanel.setVisibility(View.VISIBLE);
        drawerPanel.animate().translationX(0f).setDuration(180).start();
    }

    private void closeShotPanel() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawerScrim.setVisibility(View.GONE);
        // 滑出后转 INVISIBLE，而非 GONE——GONE 会让宽度归零，下次滑出就没有距离可算了。
        drawerPanel.animate()
                .translationX(-drawerPanel.getWidth())
                .setDuration(180)
                .withEndAction(() -> drawerPanel.setVisibility(View.INVISIBLE))
                .start();
    }

    /** 侧栏里需要连上相机才有意义的项：断开时置灰并停掉点击。 */
    private void setShotPanelItemsEnabled(boolean enabled) {
        for (TextView item : new TextView[]{
                captureItem, bracketItem, hdrItem, capabilityItem, diffItem}) {
            if (item == null) continue;
            item.setEnabled(enabled);
            item.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    private void updateConnectButton(boolean connected) {
        connectButton.setText(connected ? R.string.disconnect : R.string.connect);
        connectUsbButton.setText(connected ? R.string.disconnect : R.string.connect_usb);
        // 能力探测要连上相机才有意义，断开时置灰（避免用户点了以为坏了）。
        setShotPanelItemsEnabled(connected);
    }

    /**
     * USB 首次访问授权（移植自 ZENCHE MainActivity.ensureUsbPermission）：
     * 以 UsbManager.hasPermission 为准，广播只用来提前唤醒等待。
     */
    private boolean ensureUsbPermission(UsbManager manager, UsbDevice device) throws Exception {
        if (manager.hasPermission(device)) return true;
        String permissionAction = USB_PERMISSION_ACTION + "." + device.getDeviceId()
                + "." + UUID.randomUUID();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean granted = new AtomicBoolean(false);
        AtomicBoolean registered = new AtomicBoolean(false);
        AtomicReference<RuntimeException> requestError = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!permissionAction.equals(intent.getAction())) return;
                granted.set(manager.hasPermission(device));
                latch.countDown();
            }
        };

        runOnUiThread(() -> {
            try {
                IntentFilter filter = new IntentFilter(permissionAction);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    registerReceiver(receiver, filter);
                }
                registered.set(true);
                PendingIntent permissionIntent = PendingIntent.getBroadcast(
                        this,
                        device.getDeviceId(),
                        new Intent(permissionAction).setPackage(getPackageName()),
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                manager.requestPermission(device, permissionIntent);
            } catch (RuntimeException error) {
                requestError.set(error);
                latch.countDown();
            }
        });

        boolean finished = false;
        long deadline = System.currentTimeMillis() + USB_PERMISSION_TIMEOUT_MS;
        while (!finished && System.currentTimeMillis() < deadline) {
            if (manager.hasPermission(device)) {
                granted.set(true);
                break;
            }
            long remaining = deadline - System.currentTimeMillis();
            finished = latch.await(Math.min(250, Math.max(1, remaining)), TimeUnit.MILLISECONDS);
        }
        if (manager.hasPermission(device)) granted.set(true);
        runOnUiThread(() -> {
            if (!registered.get()) return;
            try {
                unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
        });
        if (requestError.get() != null) {
            throw new Exception("无法请求 USB 访问权限：" + requestError.get().getMessage());
        }
        return granted.get();
    }

    /**
     * Wi‑Fi 连接入口。若填了相机 Wi‑Fi 名称，加入网络需要「附近的设备」运行时权限，
     * 先要权限再连，避免首次必然走到回退路径。
     *
     * @param permissionAlreadyRequested 本次点击是否已经走过一次权限申请（避免
     *     「申请 → 回调 → 再申请」的死循环：用户拒绝后系统不会再弹窗）
     */
    private void connectWifi(boolean permissionAlreadyRequested) {
        int port = parsePort(portField.getText().toString().trim());
        // 地址留空 = 自动发现（推荐）；端口仍必须是有效值。
        if (port <= 0) {
            showError("请输入有效的端口。", null);
            return;
        }
        if (!permissionAlreadyRequested) {
            // 只有「地址留空」才需要蓝牙（BLE 交接）；手填地址时只补加入 Wi‑Fi 的权限。
            boolean includeBle = manualHost().isEmpty();
            String[] missing = missingConnectPermissions(includeBle);
            if (missing.length > 0) {
                pendingWifiConnect = true;
                requestPermissions(missing, REQUEST_NEARBY_WIFI);
                return;
            }
        }
        beginBleThenConnect(port);
    }

    /**
     * 连接前置流程：地址留空且有蓝牙权限时，先走官方同款 BLE 交接——
     * 相机自己打开 Wi‑Fi 并把 SSID/密码/IP 交回来；失败或没权限再回退到
     * 「已连相机 Wi‑Fi + 自动发现 IP」。
     */
    private void beginBleThenConnect(int port) {
        String typedHost = manualHost();
        String storedSsid = preferences.getString(PREF_CAMERA_SSID, "");
        String storedPassword = preferences.getString(PREF_CAMERA_PASSWORD, "");
        if (!typedHost.isEmpty() || !BLE_HANDOVER_ENABLED || !hasBlePermissions()) {
            performWifiConnect(typedHost, port, storedSsid, storedPassword);
            return;
        }
        if (bleHandover != null) return;
        setBusy(true);
        updateConnectButton(false);
        statusView.setText(R.string.ble_handover_scanning);
        bleHandover = new CanonBleHandover(this, new CanonBleHandover.Callback() {
            @Override public void onStatus(String message) {
                runOnUiThread(() -> statusView.setText(message));
            }

            @Override public void onReady(CanonBleHandover.Result result) {
                runOnUiThread(() -> {
                    // 保留 bleHandover：交接完成后仍要靠它维持 BLE 会话保活，
                    // 断开/退出时由 cancelBleHandover() 统一关闭。
                    // 交接拿到的是「相机将要使用」的参数，先只用于本次入网；
                    // 真正连上 PTP 后由 persistSuccessfulWifiRecord 落盘，失败不覆盖上次成功记录。
                    // 相机要求等 AP 真正起来（WTIME）再入网；实测热点可见比 WTIME 还慢，
                    // 再多给 8s，避免第一次扫描窗口扑空后一路重试失败。
                    long wait = Math.max(800L, result.waitMillis) + 8000L;
                    statusView.setText(R.string.ble_handover_ready);
                    connectButton.postDelayed(() -> {
                        boolean sameSsid = result.ssid != null && !result.ssid.isEmpty()
                                && result.ssid.equals(connectedWifiSsid());
                        if (sameSsid) {
                            // 已经在这个 AP 上：不再弹「加入网络」对话框，直接用交接来的 IP。
                            performWifiConnect(result.ip, port, "", "", "");
                        } else {
                            // 官方同样用交接返回的 BSSID 钉住热点；隐藏 SSID 只靠定向探测不稳。
                            performWifiConnect(result.ip, port, result.ssid, result.password,
                                    result.bssid);
                        }
                    }, wait);
                });
            }

            @Override public void onFailed(String message) {
                // 交接有没有走到 START，决定回退入网给多长等待：没走到 START 时相机不会开热点。
                boolean startRequested = bleHandover != null && bleHandover.isWifiStartRequested();
                long fallbackTimeout = startRequested
                        ? BLE_FAILED_STARTED_SPECIFIER_TIMEOUT_MS
                        : BLE_FAILED_QUICK_SPECIFIER_TIMEOUT_MS;
                runOnUiThread(() -> {
                    bleHandover = null;
                    statusView.setText(message + "，改用已保存的 Wi‑Fi 直连…");
                    // 回退必须带上已保存的 SSID/密码，否则带密码的相机热点永远进不去。
                    performWifiConnect("", port, storedSsid, storedPassword, "", fallbackTimeout);
                });
            }
        });
        bleHandover.start();
    }

    /** 蓝牙交接连接是会话级资源：断开/退出时释放。 */
    private void cancelBleHandover() {
        CanonBleHandover handover = bleHandover;
        bleHandover = null;
        if (handover != null) handover.close();
    }

    /** 真正发起 Wi‑Fi 连接；填了 SSID 时由协调器尝试加入，失败会自行回退自动选网。 */
    private void performWifiConnect(String host, int port, String ssid, String password) {
        performWifiConnect(host, port, ssid, password, "");
    }

    /** @param bssid BLE 交接返回的相机 AP BSSID；非空时入网会钉住它（官方同样做法）。 */
    private void performWifiConnect(
            String host, int port, String ssid, String password, String bssid) {
        performWifiConnect(host, port, ssid, password, bssid, 0L);
    }

    /**
     * @param fallbackSpecifierTimeoutMs BLE 交接失败后的回退入网：只做一次指定时长的 Specifier
     *     尝试、不做自动重试。0 表示常规流程（25s 首试 + 自动重试）。
     */
    private void performWifiConnect(
            String host, int port, String ssid, String password, String bssid,
            long fallbackSpecifierTimeoutMs) {
        // BLE 交接完成与自动连接可能几乎同时触发；重复发起会取消系统入网请求。
        if (coordinator.isConnecting()) {
            android.util.Log.i("ImageFetch", "skip duplicate wifi connect");
            return;
        }
        android.util.Log.i("ImageFetch", "performWifiConnect host=" + host
                + " ssid=" + (ssid == null || ssid.isEmpty() ? "(none)" : ssid)
                + " passLen=" + (password == null ? 0 : password.length())
                + " fallbackTimeout=" + fallbackSpecifierTimeoutMs);
        pendingWifiSsid = ssid == null ? "" : ssid;
        pendingWifiPassword = password == null ? "" : password;
        coordinator.setCameraWifiBssid(bssid);
        coordinator.setCameraWifiCredentials(ssid, password);
        coordinator.setCameraWifiFallbackTimeout(fallbackSpecifierTimeoutMs);
        wifiSession = true;
        setBusy(true);
        updateConnectButton(false);
        coordinator.connect(CameraSessionCoordinator.TransportKind.WIFI, host, port);
    }

    /**
     * 自动连接：相机 AP 在系统里通常表现为「Wi‑Fi 但没有互联网」，监听这类网络出现即发现并连接。
     * 只在应用前台注册，避免后台偷偷改网络；用户手动断开或改走 USB 后不再自动触发。
     */
    private void startAutoConnectWatch() {
        if (autoConnectCallback != null) return;
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return;
        autoConnectManager = manager;
        autoConnectCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                runOnUiThread(() -> maybeAutoConnect(network));
            }

            @Override public void onLost(Network network) {
                runOnUiThread(() -> {
                    if (network.equals(autoConnectLastNetwork)) autoConnectLastNetwork = null;
                });
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        try {
            manager.registerNetworkCallback(request, autoConnectCallback);
        } catch (Exception ignored) {
            autoConnectCallback = null;
        }
    }

    private void stopAutoConnectWatch() {
        ConnectivityManager manager = autoConnectManager;
        ConnectivityManager.NetworkCallback callback = autoConnectCallback;
        autoConnectManager = null;
        autoConnectCallback = null;
        if (manager == null || callback == null) return;
        try {
            manager.unregisterNetworkCallback(callback);
        } catch (Exception ignored) {
        }
    }

    /** 当前是否已经连着一个「无互联网的 Wi‑Fi」——相机直连 AP 的典型形态。 */
    private Network currentCameraWifiNetwork() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return null;
        try {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null
                        || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    continue;
                }
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return network;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 检测到相机 Wi‑Fi 就自动发现并连接；同一网络只尝试一次，避免失败后反复弹错。 */
    private void maybeAutoConnect(Network candidate) {
        if (autoConnectSuppressed || coordinator == null || coordinator.isConnected()
                || coordinator.isConnecting()) {
            return;
        }
        Network cameraNetwork = candidate != null ? candidate : currentCameraWifiNetwork();
        if (cameraNetwork == null || cameraNetwork.equals(autoConnectLastNetwork)) return;
        // 记过相机 SSID 且能读到当前 SSID 时，只在名称匹配时才自动连，避免误连其它无网 Wi‑Fi。
        String storedSsid = preferences.getString(PREF_CAMERA_SSID, "");
        String storedPassword = preferences.getString(PREF_CAMERA_PASSWORD, "");
        if (!storedSsid.isEmpty()) {
            String currentSsid = connectedWifiSsid();
            if (!currentSsid.isEmpty() && !storedSsid.equals(currentSsid)) return;
        }
        autoConnectLastNetwork = cameraNetwork;
        int port = parsePort(portField.getText().toString().trim());
        if (port <= 0) port = 15740;
        statusView.setText(R.string.auto_connect_detected);
        // 有蓝牙权限时先尝试官方同款 BLE 交接，否则用已记住的凭据直接入网（没有就沿用当前网络）。
        if (BLE_HANDOVER_ENABLED && hasBlePermissions()) {
            beginBleThenConnect(port);
        } else {
            performWifiConnect("", port, storedSsid, storedPassword);
        }
    }

    /**
     * 相机 Wi‑Fi 凭据：SSID + 密码只存本机。有了它，协调器会用 WifiNetworkSpecifier
     * 把相机热点变成「仅本应用使用」的网络——系统不会因为「无互联网」把连接切走。
     * 同时保留原来的「去系统设置选网」入口。
     */
    private void showCameraWifiDialog() {
        int parsedPort = parsePort(portField.getText().toString().trim());
        final int port = parsedPort > 0 ? parsedPort : 15740;

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int horizontal = Math.round(dp(20));
        form.setPadding(horizontal, Math.round(dp(4)), horizontal, 0);

        final EditText ssidField = new EditText(this);
        ssidField.setHint(R.string.camera_ssid_hint);
        ssidField.setSingleLine(true);
        ssidField.setText(preferences.getString(PREF_CAMERA_SSID, ""));
        form.addView(ssidField, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final EditText passwordField = new EditText(this);
        passwordField.setHint(R.string.camera_wifi_password_hint);
        passwordField.setSingleLine(true);
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setText(preferences.getString(PREF_CAMERA_PASSWORD, ""));
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        passwordParams.topMargin = Math.round(dp(8));
        form.addView(passwordField, passwordParams);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.camera_wifi_title)
                .setMessage(R.string.camera_wifi_message)
                .setView(form)
                .setPositiveButton(R.string.camera_wifi_save, null)
                .setNeutralButton(R.string.camera_wifi_open_settings,
                        (ignored, which) -> openWifiPicker())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        // 自己接管「保存并连接」：SSID 为空时提示而不是直接关掉对话框。
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String ssid = ssidField.getText().toString().trim();
                    String password = passwordField.getText().toString();
                    if (ssid.isEmpty()) {
                        showError(getString(R.string.camera_ssid_required), null);
                        return;
                    }
                    // 先不落盘：等真正连上相机再保存，失败/密码错不会顶掉上一次成功记录。
                    dialog.dismiss();
                    autoConnectSuppressed = false;
                    autoConnectLastNetwork = null;
                    // 选中的是「上次成功连接」的相机热点：断开后相机通常已把热点关掉，
                    // 直接 Specifier 会一直等不到；先走 BLE 交接让相机重开热点，
                    // 交接失败会回退到已保存凭据直连（见 beginBleThenConnect）。
                    String lastSsid = preferences.getString(PREF_CAMERA_SSID, "");
                    if (BLE_HANDOVER_ENABLED && hasBlePermissions()
                            && !lastSsid.isEmpty() && lastSsid.equals(ssid)) {
                        beginBleThenConnect(port);
                        return;
                    }
                    performWifiConnect("", port, ssid, password);
                }));
        dialog.show();
    }

    /**
     * 打开系统 Wi‑Fi 设置页让用户自己选择相机热点。
     *
     * <p>之所以不走「App 自动读取当前 SSID 反写」：Android 13+ 读 {@link WifiInfo#getSSID()}
     * 仍要求 {@code ACCESS_FINE_LOCATION}，而清单里它被 {@code maxSdkVersion="32"} 限定
     * （13+ 只用 {@code NEARBY_WIFI_DEVICES(neverForLocation)}），必然读到 {@code <unknown ssid>}。
     * 交给系统页面选网既不需要额外权限，选网成功与否也一目了然。
     *
     * <p>这里用完整的 {@link Settings#ACTION_WIFI_SETTINGS} 而不是 API 29+ 的
     * {@code Settings.Panel.ACTION_WIFI} 浮层面板：后者只列最近几条热门网络、且不能滚动，
     * 相机热点通常排在后面，根本选不到。
     */
    private void openWifiPicker() {
        if (hasSsidPermissions()) {
            launchWifiSettings();
            return;
        }
        // 读当前 Wi‑Fi 名称在 13+ 仍要定位权限；先补齐再跳，回来才能把名称钉进输入框。
        pendingWifiPicker = true;
        requestPermissions(ssidPermissions(), REQUEST_WIFI_PICKER);
    }

    /** 跳系统 Wi‑Fi 设置页（完整的列表页，可滚动、能看到全部网络）。 */
    private void launchWifiSettings() {
        wifiPickerLaunched = true;
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception error) {
            wifiPickerLaunched = false;
            showError("无法打开系统 Wi‑Fi 设置页。", error);
        }
    }

    /** 读 SSID 所需权限：定位（14 起为「附近的设备」+定位）；NEARBY_WIFI_DEVICES 只够连网。 */
    private String[] ssidPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasSsidPermissions() {
        for (String permission : ssidPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /**
     * 把「系统当前所连的 Wi‑Fi 名称」记进偏好（尽力而为），下次进来就直接用它。
     *
     * <p>密码无法回填：Android 不提供读回已保存 Wi‑Fi 密码的接口。读不到名称（无定位权限、
     * 非 Wi‑Fi 连接、系统返回 {@code <unknown ssid>}）时保持已记下的名称不动，返回 {@code false}。
     *
     * @return 是否确实读到并写入了名称
     */
    private boolean writeBackCameraSsid() {
        String ssid = connectedWifiSsid();
        if (ssid.isEmpty() || ssid.equals(preferences.getString(PREF_CAMERA_SSID, ""))) return false;
        // 换了热点，旧 BSSID 不再属于这条记录，一并作废。
        preferences.edit()
                .putString(PREF_CAMERA_SSID, ssid)
                .remove(PREF_CAMERA_BSSID)
                .apply();
        return true;
    }

    /**
     * 只保留「上一次成功连接」的相机 Wi‑Fi 记录：SSID、密码、BSSID 一起写入。
     * 连接失败时不会走到这里，上一次成功记录得以保留。
     */
    private void persistSuccessfulWifiRecord() {
        String ssid = connectedWifiSsid();
        if (ssid.isEmpty()) ssid = pendingWifiSsid;
        if (ssid.isEmpty()) return;
        boolean sameSsid = ssid.equals(preferences.getString(PREF_CAMERA_SSID, ""));
        SharedPreferences.Editor edit = preferences.edit()
                .putString(PREF_CAMERA_SSID, ssid);
        // 密码只能用本次连接的入参：系统不提供读回接口。
        if (ssid.equals(pendingWifiSsid) && !pendingWifiPassword.isEmpty()) {
            edit.putString(PREF_CAMERA_PASSWORD, pendingWifiPassword);
        }
        String bssid = connectedWifiBssid();
        if (!bssid.isEmpty()) {
            edit.putString(PREF_CAMERA_BSSID, bssid);
        } else if (!sameSsid) {
            // 换了热点又读不到新 BSSID：宁可下次不限定热点，也别拿旧 BSSID 去匹配。
            edit.remove(PREF_CAMERA_BSSID);
        }
        edit.apply();
        purgeLegacyBssidPrefs();
    }

    /** 把旧版按 SSID 分键（wifi_bssid_<ssid>）保存的 BSSID 记录清掉，只留新单条记录。 */
    private void purgeLegacyBssidPrefs() {
        SharedPreferences.Editor edit = preferences.edit();
        boolean changed = false;
        for (String key : new ArrayList<>(preferences.getAll().keySet())) {
            if (key.startsWith(PREF_LEGACY_BSSID_PREFIX)) {
                edit.remove(key);
                changed = true;
            }
        }
        if (changed) edit.apply();
    }

    /** 把实际连上的相机 IP 反写进地址框：自动发现的结果可见，下次也可直接复用。 */
    private void writeBackCameraAddress() {
        String address = coordinator.cameraAddress();
        if (address == null || address.trim().isEmpty()) return;
        if (address.equals(hostField.getText().toString().trim())) return;
        autoFilledHost = address;
        hostField.setText(address);
        hostField.setSelection(hostField.getText().length());
    }

    /**
     * 用户手填的相机地址。自动回填的那个 IP 不算：它只是上次连接留下的痕迹，
     * 断开后相机热点已关，拿它当「手填地址」会跳过 BLE 交接、干等一个不存在的网络。
     */
    private String manualHost() {
        String typed = hostField.getText().toString().trim();
        return typed.equals(autoFilledHost) ? "" : typed;
    }

    /** 系统当前所连 Wi‑Fi 的名称；读不到时返回空串（原样带引号的系统格式会去掉引号）。 */
    private String connectedWifiSsid() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (manager == null) return "";
            WifiInfo info = manager.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid == null || ssid.isEmpty() || ssid.startsWith("<")) return "";
            if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid;
        } catch (Exception ignored) {
            // 缺权限/系统限制时（SecurityException 等）静默跳过：这只影响一次便利性反写。
            return "";
        }
    }

    /** 当前所连热点的 BSSID；读不到（权限/脱敏）时返回空串。 */
    private String connectedWifiBssid() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (manager == null) return "";
            WifiInfo info = manager.getConnectionInfo();
            String bssid = info == null ? null : info.getBSSID();
            if (bssid == null || bssid.isEmpty() || "02:00:00:00:00:00".equals(bssid)) return "";
            return bssid;
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 加入相机 Wi‑Fi 所需的运行时权限：API 33+ 是「附近的设备」，
     * 更早的版本是精确位置（清单里已用 maxSdkVersion=32 限定）。
     */
    private static String nearbyWifiPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private boolean hasNearbyWifiPermission() {
        return checkSelfPermission(nearbyWifiPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    /** 本次连接需要的运行时权限：BLE 扫描/连接 + 加入相机 Wi‑Fi（13+ 是「附近的设备」）。 */
    private String[] missingConnectPermissions(boolean includeBle) {
        java.util.LinkedHashSet<String> needed = new java.util.LinkedHashSet<>();
        if (includeBle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        needed.add(nearbyWifiPermission());
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        for (String permission : needed) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing.toArray(new String[0]);
    }

    /** 蓝牙是否可用：12+ 需要扫描与连接两项运行时权限，11 及以下靠定位权限。 */
    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNearbyWifiPermission() {
        requestPermissions(new String[]{nearbyWifiPermission()}, REQUEST_NEARBY_WIFI);
    }

    /**
     * Android 13+ 需要运行时授权才会显示前台服务通知。被拒不影响服务运行
     * （只是通知不展示），所以这里不阻断任何流程。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    /**
     * Android 9 及以下导出到相册需要 WRITE_EXTERNAL_STORAGE；
     * Android 10+ 走分区存储，不需要任何存储权限。
     */
    private void requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_WRITE_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WIFI_PICKER) {
            pendingWifiPicker = false;
            // 没授权也照跳：选网本身不需要权限，只是回来读不到名称，靠手填。
            launchWifiSettings();
            return;
        }
        if (requestCode == REQUEST_NEARBY_WIFI) {
            if (!pendingWifiConnect) return;
            pendingWifiConnect = false;
            // 未授权也继续连接：协调器会退回到「用系统已连网络」的自动选网路径。
            connectWifi(true);
            return;
        }
        if (requestCode == REQUEST_NOTIFICATIONS) return;
        // 胶片页自己发起的存储权限申请：结果交回它，授予后续做那次导出。
        if (requestCode == FilmSimulationDialog.REQUEST_WRITE_STORAGE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (filmDialog != null) filmDialog.onStoragePermissionResult(granted);
            return;
        }
        if (requestCode != REQUEST_WRITE_STORAGE) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) return;
        // 未授权不阻断功能：关闭相册导出，照片仍保存到应用内目录。
        exportToGalleryBox.setChecked(false);
        coordinator.setExportToGallery(false);
        showError("未授予存储权限，已关闭“保存到系统相册”；照片仍会保存到应用内目录。", null);
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /**
     * 断开后的提示：相机的 PTP/IP 连接只保留一个槽位，App 断开后相机不会立刻释放，
     * 期间新连接可能被 InitFail 拒绝。App 现在会自己等相机回收槽位（约 2 分钟内多次退避
     * 重试），因此先让用户直接重连；只有反复失败时才需要去相机侧删连接。
     */
    private void showReconnectHint() {
        new AlertDialog.Builder(this)
                .setTitle("已断开")
                .setMessage("已断开与相机的连接。\n\n"
                        + "再次使用：直接点「连接」即可，App 会自动等待相机释放旧连接槽位并重试"
                        + "（最长约 2 分钟）。\n\n"
                        + "若多次重试仍失败，再在相机上删除（断开）当前连接目标，"
                        + "重新进入「连接智能手机」模式后点「连接」。")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showError(String message, Throwable error) {
        String detail = message == null ? "未知错误" : message;
        if (error != null && error.getCause() != null
                && error.getCause().getMessage() != null) {
            detail += "\n\n" + error.getCause().getMessage();
        }
        new AlertDialog.Builder(this)
                // 带上版本号：便于确认报错出自哪一次构建。
                .setTitle("出错了 · v" + versionName())
                .setMessage(detail)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String versionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
        } catch (Exception ignored) {
            return "?";
        }
    }
}
