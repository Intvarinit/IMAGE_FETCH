/*
 * Derived from ZENCHE (https://github.com/Tauber01/ZENCHE) native/android
 * PtpIpCamera.java, MIT License, Copyright (c) 2026 Tauber01.
 * See the NOTICE file in the project root.
 *
 * Changes for IMAGE_FETCH:
 *   - 事件通道 type 8 由“排空”改为派发 ObjectAdded(0x4002) 给业务层；
 *   - GetObject(0x1009) 改为流式写入 OutputStream，不再整文件载入内存；
 *   - 快门走 Canon EOS 厂商路径（0x9114/0x9115/0x9128/0x9129）；
 *   - 实时取景（0x9153）与曝光参数（0x9110）按 libgphoto2 的序列重新接入。
 */
package com.imagefetch.transport;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.CanonExposure;
import com.imagefetch.ptp.CanonImageQuality;
import com.imagefetch.ptp.CapabilityReport;
import com.imagefetch.ptp.LiveViewSize;
import com.imagefetch.ptp.StorageBrowser;
import com.imagefetch.ptp.VendorProperty;
import com.imagefetch.ptp.VendorPropertySnapshot;

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
    /** 标准 PTP 删除对象（ISO 15740）：参数为对象句柄 + objectFormatCode(0)。 */
    private static final int OPERATION_DELETE_OBJECT = 0x100b;
    /** 标准 PTP 快门（ISO 15740）。EOS 机型普遍不支持，回 0x2005。 */
    private static final int OPERATION_CAPTURE = 0x100e;
    /**
     * Canon EOS 厂商遥控快门（opcode 见 libgphoto2 camlibs/ptp2/ptp.h，并经其调试日志复核）：
     * 0x9114 SetRemoteMode → 0x9115 SetEventMode → 0x9128 RemoteReleaseOn
     * → 0x9129 RemoteReleaseOff。EOS 只接受这条路径，标准 0x100E 会回 0x2005。
     *
     * <p>注意 0x9116 是 EOS_GetEvent（回事件数据块）而非快门：曾误用它当快门，结果每次
     * 都「回 0x2001 但相机毫无反应」——那只是把待读事件取走了。但它本身是拍摄流程必需的：
     * libgphoto2 在按快门前明确要求「Get all pending event data from the camera,
     * otherwise capture might return busy」（library.c:6106-6107）。我们开了 SetEventMode(1)
     * 却从不排空，事件积压后全按就会被回以 DeviceBusy(0x2019)——这正是本机实测的现象。
     */
    private static final int CANON_SET_REMOTE_MODE = 0x9114;
    private static final int CANON_SET_EVENT_MODE = 0x9115;
    private static final int CANON_GET_EVENT = 0x9116;
    private static final int CANON_REMOTE_RELEASE_ON = 0x9128;
    private static final int CANON_REMOTE_RELEASE_OFF = 0x9129;
    /** 解锁机身 UI（无参数）。libgphoto2 退出时用它把遥控态锁住的机身操作还回去。 */
    private static final int CANON_EOS_RESET_UI_LOCK = 0x911c;
    /** 禁用/恢复机身模式转盘（1 个参数，0 = 恢复）。同属交还机身的收尾（library.c:3294-3297）。 */
    private static final int CANON_SET_MODE_DIAL_DISABLE = 0x9088;
    /**
     * 排空事件的自保上限（轮数 + 时间预算）。一次 {@code 0x9116 GetEvent} 会取回**一整批**
     * 待读事件（数据段是 TLV 记录序列，libgphoto2 {@code ptp_unpack_EOS_events} 一次解析多条，
     * ptp-pack.c:1869-1899），所以正常一两轮就见底——这两个上限只是防御相机一直回非空块，
     * 不是排空的正常终点。libgphoto2 的做法是纯粹 {@code while (1)} 直到相机回零条事件
     * （ptp.c:3737-3743）；这里加预算是不想在最坏情况下把命令锁占死。
     */
    private static final int CANON_EVENT_DRAIN_MAX_ROUNDS = 64;
    private static final long CANON_EVENT_DRAIN_BUDGET_MS = 1000L;

    /**
     * Canon EOS 扩展属性写入：数据段固定 12 字节 LE {@code [u32 长度=12][u32 属性码][u32 值]}。
     * 与 libgphoto2 {@code ptp_canon_eos_setdevicepropvalue} 一致（UINT16 属性只填低 2 字节，
     * 高位为 0，故统一按 u32 写小值）。
     */
    private static final int CANON_SET_DEVICE_PROP_VALUE_EX = 0x9110;
    /** Canon EOS 保活（无参数）：不进实时取景时相机会自动关机。 */
    private static final int CANON_KEEP_DEVICE_ON = 0x911d;
    /** Canon EOS 开启取景（无参数）。libgphoto2 开取景的首选命令（config.c:9177-9180）。 */
    private static final int CANON_INITIATE_VIEWFINDER = 0x9151;
    /**
     * Canon EOS 结束取景（无参数）。libgphoto2 只在能力集里有它时才发
     * （config.c:9183-9186 先查 {@code ptp_operation_issupported}）。本机型能力集里没有
     * 0x9152，实测固定回 {@code 0x2005 OperationNotSupported}——所以真正退出取景靠属性
     * 回退路径 {@code 0xD1B0 EVFOutputDevice=0} + {@code 0xD1B1 EVFMode=0}
     * （config.c:9189-9193 的兜底写法）。
     */
    private static final int CANON_TERMINATE_VIEWFINDER = 0x9152;
    /** Canon EOS 单次自动对焦（无参数，ptp.h:5057 EOS_DoAf）。需取景已开启。 */
    private static final int CANON_DO_AF = 0x9154;
    /**
     * 停止正在进行的自动对焦（无参数，ptp.h:440 附近 {@code EOS_AfCancel}）。
     *
     * <p>libgphoto2 在拆掉拍摄准备时的**第一步**就是它，注释写得很直白：
     * "just in case we had autofocus running"（config.c:574-577）。
     * 对焦驱动没结束前相机会对 {@code 0x9110} 属性写入统一回 {@code 0x2019 DeviceBusy}——
     * 实测症状正是「按过一次对焦（或拍过一张，快门本身走的就是 AF）之后三要素再也写不进去」，
     * 以及「关闭遥控面板后机身屏幕点不亮、按键无响应」：退出序列里那些命令全被 0x2019 挡掉，
     * 而它们各自兜了异常，于是整段收尾静默失败。
     */
    private static final int CANON_EOS_AF_CANCEL = 0x9160;
    /** Canon EOS 取景帧（data-in；数据段是 TLV 块序列）。 */
    private static final int CANON_GET_VIEW_FINDER_DATA = 0x9153;
    /**
     * 取景输出设备掩码属性 {@code 0xD1B0}。低 4 位是掩码：{@code 1}=机身屏(TFT)、
     * {@code 2}=PC(高)、{@code 4}=MOBILE(中)、{@code 8}=MOBILE2(低)；机型支持集是
     * 0x0..0xB 的全部位组合（canon-eos-m50m2.txt 事件 225 的 suppval 列表）。
     */
    private static final int CANON_PROP_EVF_OUTPUT_DEVICE = 0xd1b0;
    /**
     * {@code 0xD1B0} 的机身屏位。<b>取景中改画面尺寸</b>要保留它：libgphoto2 的
     * {@code _put_Canon_LiveViewSize} 写的是 {@code (current & ~0xe) | outputval}
     * （config.c:2669-2670「keep the TFT flag」）。
     *
     * <p><b>退出取景时不保留</b>：退出的写法是把输出位清 0（config.c:582-583 的 EOS M
     * unprepare、config.c:9189-9193 的 viewfinder 关闭兜底）。退出时留着任何输出位都会让
     * 画面上游继续跑，之后全按快门就被判 {@code 0x2019 DeviceBusy}。
     */
    private static final int CANON_EVF_OUTPUT_TFT = 0x01;
    /** 取景模式属性（写 1 = 开）。 */
    private static final int CANON_PROP_EVF_MODE = 0xd1b1;
    /**
     * Canon EOS 对焦方式属性 {@code 0xD1BA EOS_LvAfSystem}（整体 AF 方法：单点/区域等）。
     * 与曝光三要素同属 0xD1xx 一族——没有同步读接口，当前值只能从 0x9116 事件缓存取、
     * 经 0x9110 写入（见 {@link #readFocusArea()} / {@link #writeFocusArea(int)}）。
     */
    private static final int CANON_PROP_AF_METHOD = 0xd1ba;

    /**
     * Canon EOS 请相机把某个 {@code 0xD1xx} 属性的当前值**推送**到事件队列
     * （{@code 0x9127 EOS_RequestDevicePropValue}，1 个参数 = 属性码）。
     *
     * <p>EOS 的 0xD1xx 属性没有同步读接口——标准 {@code 0x1015 GetDevicePropValue} 对它
     * 一律回 {@code 0x2005 DevicePropNotSupported}，旧版读路径因此永远读不到值。正确做法是
     * 发这条请求走普通事务（与 0x9116 排空同一种事务，不是裸读），相机随后以 {@code 0xC189}
     * 事件推回当前值，再从事件块里解析——libgphoto2 的
     * {@code _ptp_canon_eos_requestdevicepropvalue}（config.c:8837 附近）也是这么做的。
     */
    private static final int CANON_REQUEST_DEVICE_PROP_VALUE = 0x9127;
    /**
     * Canon EOS 触摸对焦框定位 {@code 0x915B EOS_TouchAfPosition(type, x, y)}：无数据段、
     * 3 个参数（ptp.h:5082）。libgphoto2 注释说坐标是取景帧像素、约 64px 粒度（config.c:8861），
     * 但那是把「取景帧」当成整个画面写的粗略说法；按抓包复现，X/Y 的实际量纲是感光器像素
     * （见 {@link #CANON_LV_SEG_SENSOR_RESOLUTION}），且 {@code type} 必须发 3。
     */
    private static final int CANON_TOUCH_AF_POSITION = 0x915b;
    /**
     * {@code 0x915B} 的第一个参数（libgphoto2 里叫 {@code type}、语义未定义）。
     * EOS Utility 抓包里固定发 {@code 3}；发 0 时相机静默忽略这条命令。
     */
    private static final long CANON_TOUCH_AF_TYPE = 3L;

    /** 事件块 TLV 记录类型：属性当前值变化（{@code 0xC189 EOS_PropValueChanged}）。 */
    private static final int CANON_EVENT_PROP_VALUE_CHANGED = 0xc189;
    /** 事件块 TLV 记录类型：属性允许值列表变化（{@code 0xC18A EOS_AvailListChanged}）。 */
    private static final int CANON_EVENT_AVAIL_LIST_CHANGED = 0xc18a;
    /** 0xC18A 记录里 {@code propxtype=3} 才表示「枚举」；1/7 等是别的类型，不能当枚举解。 */
    private static final int CANON_PROPX_TYPE_ENUM = 3;

    /**
     * 等相机把某个 0xD1xx 属性的当前值推到事件队列时的排空轮数上限与间隔。EOS「偶尔会先回一批
     * 空事件，多半是主机问得太快」，libgphoto2 因此用 10 次 × 50ms 重试（config.c:429-436）。
     */
    private static final int CANON_PROP_PUSH_ATTEMPTS = 10;
    private static final long CANON_PROP_PUSH_INTERVAL_MS = 50L;
    /** 等待属性推送的总预算：兜住最坏时长，避免命令锁被占太久。 */
    private static final long CANON_PROP_PUSH_BUDGET_MS = 1000L;
    /**
     * 能力探测里「原值回写后重新读一遍」的等待预算。比普通读宽一些：刚写过属性的相机可能
     * 还在忙，且探测是用户显式触发的一次性动作，多等一会儿换来确定的事实是划算的。
     */
    private static final long CANON_PROBE_READ_BUDGET_MS = 3000L;
    /**
     * 写厂商属性成功后，等一下再排空事件：相机不会立刻把新值推回 {@code 0xC189}，
     * 不等这一拍就排空，缓存里留的还是写前那份旧值，上层随即做的回读核对会拿到旧值。
     */
    private static final long CANON_WRITE_SETTLE_MS = 300L;

    /** 差分快照：排空之后再等几拍，把机身改设置后迟到的事件收进来。 */
    private static final int CANON_SNAPSHOT_SETTLE_ROUNDS = 3;
    private static final long CANON_SNAPSHOT_SETTLE_MS = 150L;

    /** 0x9153 的固定参数（libgphoto2 抓包值：0x00200000, 0, 0）。 */
    private static final long CANON_VIEWFINDER_PARAMETER = 0x0020_0000L;

    /** 0x9153 数据段里 JPEG 帧的块类型：1/11 为常规 JPEG，9 为 Movie 模式下的帧。 */
    private static final int CANON_LV_SEG_JPEG = 1;
    private static final int CANON_LV_SEG_JPEG_MOVIE = 9;
    private static final int CANON_LV_SEG_JPEG_ALT = 11;
    /**
     * 取景载荷里的感光器分辨率段（TLV type {@code 0x0E}），载荷为两个 u32：传感器宽、高。
     *
     * <p>它决定了 {@code 0x915B TouchAfPosition} 的坐标空间：该操作的 X/Y 不是取景画面像素，
     * 而是<b>感光器像素</b>（0..传感器宽/高）。依据是公开的 PTP/IP 协议逆向记录里 EOS Utility
     * 抓包复现：SetTouchAfPosition 报文的 X/Y 与同一帧取景载荷中 TouchAutofocus 段的坐标同量级，
     * 且后者的 X/Y 上限就是传感器宽/高。libgphoto2 未实现该操作，也不解析这个段。
     */
    private static final int CANON_LV_SEG_SENSOR_RESOLUTION = 0x0e;

    /** 相机「这一帧还没准备好」的响应码；属正常情况，退避重试即可。 */
    private static final int RESPONSE_CANON_OBJECT_NOT_READY = 0xa102;
    private static final int RESPONSE_DEVICE_BUSY = 0x2019;
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
    /**
     * 0x9129 只带 1 个参数（ptp.h:455「0x9129 args (0x1/0x2)」）：
     * 1 = 松开半按，2 = 松开全按。libgphoto2 在全按成功后依次松开这两级
     * （library.c:6212/6216），否则相机会一直保持半按，下一次全按就可能被判忙。
     */
    private static final long[] HALF_RELEASE = {1};
    private static final long[] FULL_RELEASE = {2};
    /**
     * 全按后等「相机上报新对象」的窗口与轮询间隔。
     *
     * <p>判据是相机自己推的 {@code 0x4002 ObjectAdded} 事件（PTP/IP 事件通道，由 event reader
     * 线程计数），**不再**枚举存储卡句柄：原先每 500ms 枚举一次整卡句柄、最多 10 次，
     * 用户侧的感受就是「按一下快门等 8 秒」。这里只是给「机型记忆」一个判据，拍摄成败
     * 本来由上层 {@code NewPhotoResolver} 判定（事件 + 句柄差集，超时 20s），所以窗口可以短：
     * 事件没来就按「未确认」处理，不影响拍摄结果。
     */
    private static final long CANON_SHOT_EVENT_WAIT_MS = 2000L;
    private static final long CANON_SHOT_EVENT_POLL_MS = 100L;
    /**
     * 全按被拒 {@code 0x2019 DeviceBusy} 时的重试次数与退避基数。
     * libgphoto2 对 {@code PTP_RC_DeviceBusy} 的处理就是重试（library.c:6484
     * 「Canon Powershot busy ... retrying...」）——相机还在忙上一步（写卡/退出取景）时
     * 会瞬时拒绝全按，等一下再按就能成。
     */
    private static final int CANON_RELEASE_BUSY_RETRIES = 3;
    private static final long CANON_RELEASE_BUSY_BACKOFF_MS = 400L;
    /**
     * Canon EOS M 机型的快门编码：全按是按钮参数 {@code 3}（普通 EOS 才是半按 (1,0) + 全按
     * (2,0)）。libgphoto2 对 EOS M 只发一条 {@code RemoteReleaseOn(3, 0)}，再发
     * {@code RemoteReleaseOff(3)} 松开（library.c:6218-6262 的 EOS M 分支）。
     * 本机实测：套用普通 EOS 的 (2,0) 全按恒回 0x2019 DeviceBusy。
     */
    private static final long[] EOS_M_FULL_PRESS = {3, 0};
    private static final long[] EOS_M_FULL_RELEASE = {3};
    /**
     * EOS M 机型的 {@code SetRemoteMode} 取值。libgphoto2 在初始化时对 EOS M 系列发
     * {@code 0x15}、普通 EOS 发 {@code 1}（library.c:9942-9962；拍摄前的 prepare 同值，
     * config.c:380-391）。取值不对时遥控快门即使回 OK 相机也不会真拍。
     */
    private static final long CANON_EOS_M_REMOTE_MODE = 0x15L;
    /**
     * {@code 0x9110} 写属性遇 {@code 0x2019 DeviceBusy} 的重试次数。取景中相机时常忙碌，
     * libgphoto2 也把 0x9110 的 DeviceBusy 当「暂时忙」而非不支持（config.c:355
     * "not so bad if its just busy, would also fail later."），故退避重试而不是直接报错。
     */
    private static final int CANON_PROP_WRITE_BUSY_RETRIES = 4;
    /**
     * 读 {@code GetDeviceInfo} 取机型串的次数。机型判定一旦落空，快门编码与遥控模式取值都会
     * 选错（普通 EOS 编码戳 EOS M 机身 → 全按恒回 {@code 0x2019}），而相机忙时会回
     * {@code 0x2019} 让我们读不到，所以要退避重试。
     */
    private static final int CANON_MODEL_READ_ATTEMPTS = 3;
    /**
     * {@code 0x9154 EOS_DoAf} 遇 {@code 0x2019 DeviceBusy} 的重试次数。取景中相机忙着推流，
     * 对焦命令会被瞬时拒绝；重试仍失败才算真失败（失败要把响应码报出来，不再当成功吞掉）。
     */
    private static final int CANON_AF_BUSY_RETRIES = 2;

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
    /** GetDeviceInfo 报出的型号（如 {@code Canon EOS M50m2}）；判 EOS M 系列要用它。 */
    private String cameraModel = "";
    private CameraVendor vendor = CameraVendor.UNKNOWN;
    /** Canon 遥控/事件模式是否已在本会话设置过（每次重连复位）。 */
    private boolean canonRemoteModeReady;
    /**
     * 是否已由实测确认本机走 EOS M 快门编码。机型串读不到时会误选普通 EOS 编码（全按恒回
     * {@code 0x2019}），这时按 {@code (3,0)} 试一次可能成功。
     *
     * <p>置位判据只能是「<b>确认拍到了新对象</b>」，不能只看响应码：真机 EOS RP（普通 EOS）
     * 会**接受** {@code (3,0)} 并回 {@code 0x2001}，却根本不释放快门——据此记住机型就会让整个
     * 会话只用 {@code (3,0)}，表现为「快门回 OK 却没拍到」。会话结束（{@link #close()}）时复位：
     * 真机可能换台相机连，带着上一台的结论会让新会话一路走错分支。
     */
    private volatile boolean canonEosMEncodingLearned;
    /** 最近一次 capture() 的厂商快门逐步诊断（每步响应码与应答载荷）。 */
    private final StringBuilder captureSteps = new StringBuilder();
    private volatile ObjectEventListener objectEventListener;
    /**
     * 相机上报过的 {@code 0x4002 ObjectAdded} 次数（event reader 线程递增）。
     * 快门路径靠「按快门前后的计数有没有变」判断这次全按是否真的产生了新对象——
     * 不需要任何额外 PTP 往返（见 {@link #awaitObjectAdded}）。
     */
    private volatile long objectAddedSeq;
    /**
     * 存储卡分页缓存（存储卷 → 句柄，以及「仅 JPG」的筛查结果）：
     * 快速分页翻页时不必重新遍历目录，JPG 筛查结果也只算一遍。
     */
    private final StorageBrowser.PageCache pageCache = new StorageBrowser.PageCache();
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
     * 进入 Canon 遥控/事件模式（每个会话只需一次）。遥控快门与实时取景共用这个前置状态：
     * 没进遥控态时 0x9110 写属性和 0x9128 释放快门都可能回 OK 而相机不动作。
     *
     * @return 本次真正执行时的两步响应码文案（供快门诊断记录）；已在本次会话设置过则返回 {@code null}
     */
    private String ensureCanonRemoteMode() throws Exception {
        if (canonRemoteModeReady) return null;
        ensureCameraModel();
        // 遥控模式取值按机型分：EOS M 系列 0x15、普通 EOS 1（library.c:9942-9962）。
        // 这两步的响应码不能忽略：若遥控模式没设置成功，后面的释放命令即使回 OK
        // 相机也不会真的拍。以前丢掉了返回值，导致「回 OK 但没拍照」无从判断。
        // 机型串读不到时**先按 EOS M 试**：本项目所有真机结论都来自 EOS M 机身，而遥控模式取值
        // 不对时遥控快门即使回 OK 相机也不会真拍（与快门编码是同一个坑）。取值不被接受再换另一个，
        // 两代机型就这两个取值，来回试一次即可，别把一次本来能用的连接卡死。
        boolean modelUnknown = cameraModel == null || cameraModel.isEmpty();
        long remoteModeValue = (modelUnknown || isCanonEosMSeries())
                ? CANON_EOS_M_REMOTE_MODE : 1L;
        int remoteMode = command(CANON_SET_REMOTE_MODE, transactionId++, new long[]{remoteModeValue});
        if (remoteMode != RESPONSE_OK) {
            remoteModeValue = remoteModeValue == 1L ? CANON_EOS_M_REMOTE_MODE : 1L;
            remoteMode = command(CANON_SET_REMOTE_MODE, transactionId++, new long[]{remoteModeValue});
        }
        int eventMode = command(CANON_SET_EVENT_MODE, transactionId++, new long[]{1});
        canonRemoteModeReady = true;
        return "0x9114 SetRemoteMode(" + remoteModeText(remoteModeValue) + ") → " + hexCode(remoteMode)
                + "\n0x9115 SetEventMode(1) → " + hexCode(eventMode);
    }

    /** 诊断用：遥控模式取值写成 {@code 1} 或 {@code 0x15}。 */
    private static String remoteModeText(long value) {
        return value == 1L ? "1" : String.format(Locale.ROOT, "0x%02X", value);
    }

    /**
     * 是否属于 Canon EOS M 系列。libgphoto2 对这类机身用 {@code SetRemoteMode(0x15)} 且全按
     * 只发一条 {@code RemoteReleaseOn(3, 0)}（ptp-private.h:118-135、library.c:6218-6262），
     * 与普通 EOS 的 {@code 1} / {@code (1,0)+(2,0)} 是两套编码。机型串优先取 GetDeviceInfo
     * 的 Model，读不到时退回握手名称（EOS M 机身的握手名常见「EOSM50」这种无空格写法）。
     */
    private boolean isCanonEosMSeries() {
        // 实测学过一次就够：这是机身固有属性，不受机型串读不读得到影响。
        if (canonEosMEncodingLearned) return true;
        String model = cameraModel;
        if (model != null && model.startsWith("Canon EOS M")) return true;
        String name = cameraName == null
                ? "" : cameraName.replace(" ", "").toLowerCase(Locale.ROOT);
        return name.contains("eosm");
    }

    /**
     * Canon EOS 遥控快门：进入遥控/事件模式后按下-释放快门。
     * 部分机型连上即遥控态，因此这些命令失败也继续尝试释放。
     */
    private void canonRemoteRelease() throws Exception {
        String remoteModeDiagnostics = ensureCanonRemoteMode();
        if (remoteModeDiagnostics != null) recordCaptureStep(remoteModeDiagnostics);

        // 关取景这一步带进诊断：全按快门能否成功与它直接相关。
        if (lastLiveViewStopCode >= 0) {
            recordCaptureStep("关取景 EVFOutputDevice 写入 → " + hexCode(lastLiveViewStopCode));
        }
        // 按快门前必须排空事件积压：libgphoto2 明确写着「Get all pending event data from
        // the camera, otherwise capture might return busy」（library.c:6106-6107）。
        // 排空失败不影响拍照尝试，只记一笔诊断。
        drainEosEventsForCapture();
        // 对焦没结束时相机会把后续厂商命令一律回 0x2019——libgphoto2 只要动相机就先停对焦
        // （config.c:574-577），按快门也不例外。标志位保证「本来没在跑对焦」时不白发命令。
        cancelCanonAutofocus();

        // 机型判定的依据直接进诊断：判定落空会误用普通 EOS 的快门编码，把全按一直挡在
        // 0x2019——症状和「相机忙」完全一样，不打依据就分不清是哪一种。
        recordCaptureStep("机型判定 " + (isCanonEosMSeries() ? "EOS M 系列" : "普通 EOS")
                + "（Model="
                + (cameraModel == null || cameraModel.isEmpty() ? "读不到" : cameraModel)
                + "；握手名=" + cameraName + "）");

        // Canon 对两代机型的快门是两套编码，libgphoto2 用 is_canon_eos_m() 二选一，绝不混用
        // （library.c:6136-6262）：EOS M 系列只发一条 RemoteReleaseOn(3, 0)（按钮值 3）；
        // 普通 EOS 才是半按 (1,0) + 全按 (2,0)。判据只能是机型串：真机 EOS RP 会对 (3,0) 回
        // 0x2001 却根本不释放快门，光看响应码分不出用的是哪套编码（见 close() 的机型记忆注释）。
        // 按快门前记下事件计数，拍完靠「它有没有变」判断这次全按到底有没有拍出东西。
        long objectSeqBefore = objectAddedSeq;

        if (isCanonEosMSeries()) {
            ReleaseResult press = releaseCommandRetrying(CANON_REMOTE_RELEASE_ON, EOS_M_FULL_PRESS);
            recordCaptureStep(stepText("0x9128 RemoteReleaseOn(3,0) 全按（EOS M 编码）", press));
            // 全按后立刻松开（与普通 EOS 分支同一条纪律）：0x9128 等价于「按住快门不放」，
            // 驱动模式是连拍时按住多久就拍多久。libgphoto2 是「等 Button 事件 → 再松」，
            // 但那是为了拿错误码；本机实测那段等待会把快门多按住两秒（我们没发
            // 0x913D SetRequestOLCInfoGroup，相机根本不推 Button 事件），白白卡顿。
            // 拍摄成败由上层「发现新 JPEG」判定，不需要靠这个事件。
            try {
                ReleaseResult released =
                        releaseCommand(CANON_REMOTE_RELEASE_OFF, EOS_M_FULL_RELEASE);
                recordCaptureStep(stepText("0x9129 RemoteReleaseOff(3) 松全按", released));
            } catch (Exception ignored) {
                // 释放失败不影响本次已触发的拍摄；下次拍摄会重新按下-释放。
            }
            // 尽力记一笔 Button 事件：只读一次已到的事件，队列空就跳过，不等待。
            Integer button = drainEosEventsForButton();
            if (button != null) {
                recordCaptureStep("EOS M 拍摄事件 Button " + button
                        + (button >= 4 ? "（全按成功）" : "（全按未成功，可能未对上焦）"));
            }
            if (press.code != RESPONSE_OK) throw rejected(press.code);
            // 快门本身就带 AF：拍完对焦驱动可能还在跑，之后写属性前要先停掉它。
            canonAfMaybeRunning = true;
            // 「回 OK」不等于「真拍了」：真机实测 EOS RP（普通 EOS）会**接受** (3,0) 并回
            // 0x2001，却根本不释放快门——只按响应码认机型就会把这个会话永久带偏。
            // 所以机型记忆必须以「相机上报了新对象」为准，没拍到就把它撤回，下次拍摄改走
            // 普通 EOS 编码。
            if (awaitObjectAdded(objectSeqBefore)) {
                recordCaptureStep("已确认产生新对象（EOS M 编码）");
            } else {
                recordCaptureStep("按 EOS M 编码全按后未收到新对象事件：机型判定可能落空，"
                        + "已撤回机型记忆，下次拍摄改按普通 EOS 编码");
                canonEosMEncodingLearned = false;
            }
            return;
        }

        // 半按：实测相机在此步会测光/对焦（屏幕里对焦成功）但不拍，因此后面必须补全按。
        // 半按同样要退避重试：取景里按快门时这一步最容易撞上 0x2019，而原先一次被拒就整单失败。
        ReleaseResult half = releaseCommandRetrying(CANON_REMOTE_RELEASE_ON, HALF_PRESS);
        recordCaptureStep(stepText("0x9128 RemoteReleaseOn(1,0) 半按", half));
        if (half.code != RESPONSE_OK) throw rejected(half.code);
        sleepQuietly(CANON_RELEASE_HOLD_MS);
        // 半按期间相机会推对焦/属性事件（libgphoto2 正是靠读它们判断对焦完成）。
        // 这里不等对焦结果，但必须把队列清掉，否则又会变成下一次全按的积压。
        drainEosEventsForCapture();

        // 全按：用 libgphoto2 实证过的 (2, 0)。取景中相机在退出推流/写卡时都会瞬时回
        // DeviceBusy，退避重试由 releaseCommandRetrying 统一处置（与写属性同一套兜底）。
        ReleaseResult press = releaseCommandRetrying(CANON_REMOTE_RELEASE_ON, FULL_PRESS);
        recordCaptureStep(stepText("0x9128 RemoteReleaseOn(2,0) 全按", press));
        // 「半按被接受、全按恒忙」可能是 EOS M 机身（按钮值 3），也可能只是普通 EOS 瞬时忙
        // （写卡/退出取景）。两种情形在响应码上分不开，所以这里只**试**一次 (3,0)，不记机型；
        // 要不要记住由下面的「发现新对象」决定。
        boolean usedEosMEncoding = false;
        if (press.code == RESPONSE_DEVICE_BUSY && half.code == RESPONSE_OK) {
            ReleaseResult eosMPress = retryWithEosMEncoding();
            if (eosMPress != null) {
                press = eosMPress;
                usedEosMEncoding = true;
            }
        }

        // 全按后必须立刻松开：0x9128 等价于「按住快门不放」，机身驱动模式是连拍时，
        // 按住多久就一直拍多久——原先要等 waitForNewObject 探到对象才松手，慢速连拍下
        // 一次点击会拍出好几张。现在先松手，再等对象出现（等待不影响已拍张数）。
        // 依次松开全按与半按（libgphoto2 library.c:6212/6216）：只松全按会让相机
        // 一直保持半按，下次全按就可能被判忙。
        // 走了 EOS M 回退的话，它自己已经松过 (3)，这里就不要再按普通 EOS 的 (2)/(1) 松一遍。
        if (!usedEosMEncoding) {
            try {
                ReleaseResult released = releaseCommand(CANON_REMOTE_RELEASE_OFF, FULL_RELEASE);
                recordCaptureStep(stepText("0x9129 RemoteReleaseOff(2) 松全按", released));
            } catch (Exception ignored) {
                // 释放失败不影响本次已触发的拍摄；下次拍摄会重新按下-释放。
            }
            drainEosEventsForCapture();
            try {
                ReleaseResult released = releaseCommand(CANON_REMOTE_RELEASE_OFF, HALF_RELEASE);
                recordCaptureStep(stepText("0x9129 RemoteReleaseOff(1) 松半按", released));
            } catch (Exception ignored) {
                // 同上。
            }
        }
        if (press.code != RESPONSE_OK) {
            throw new IOException(
                    "相机拒绝了 Canon 遥控快门 0x9128 RemoteReleaseOn(2,0)。"
                            + captureStepsSuffix(),
                    rejected(press.code));
        }
        // 快门本身就带 AF：拍完对焦驱动可能还在跑，之后写属性前要先停掉它。
        canonAfMaybeRunning = true;
        if (awaitObjectAdded(objectSeqBefore)) {
            recordCaptureStep("已确认产生新对象");
            // 只有真拍到才认机型：光看响应码会把「普通 EOS 瞬时忙 + 恰好接受 (3,0)」
            // 误记成 EOS M，之后每次拍摄都只用 (3,0)——真机（EOS RP）上就是「回 OK 却不拍」。
            if (usedEosMEncoding) canonEosMEncodingLearned = true;
        }
    }

    /**
     * 普通 EOS 编码失败后的兜底：改按 EOS M 的 {@code RemoteReleaseOn(3,0)} 再试一次。
     *
     * <p>只在「半按被接受、全按回 {@code 0x2019}」这个签名下调用。这个签名**不足以判定机型**：
     * 普通 EOS 瞬时忙（写卡、刚退出取景）时也会出现，而真机 EOS RP 恰好会接受 (3,0) 并回
     * {@code 0x2001}——据此记住机型就会让整个会话只用 (3,0)，表现为「快门回 OK 却没拍到」。
     * 所以这里只负责试一次并返回响应；是否记录机型由调用方在**确认拍到新对象**后决定。
     * 失败返回 {@code null}，调用方保留原始的响应码与诊断，不让回退看起来像主因。
     */
    private ReleaseResult retryWithEosMEncoding() {
        try {
            ReleaseResult press = releaseCommand(CANON_REMOTE_RELEASE_ON, EOS_M_FULL_PRESS);
            recordCaptureStep(stepText(
                    "0x9128 RemoteReleaseOn(3,0) 全按（按 EOS M 编码回退）", press));
            if (press.code != RESPONSE_OK) return null;
            try {
                ReleaseResult released =
                        releaseCommand(CANON_REMOTE_RELEASE_OFF, EOS_M_FULL_RELEASE);
                recordCaptureStep(stepText("0x9129 RemoteReleaseOff(3) 松全按", released));
            } catch (Exception ignored) {
                // 释放失败不影响本次已触发的拍摄。
            }
            Integer button = drainEosEventsForButton();
            if (button != null) {
                recordCaptureStep("EOS M 拍摄事件 Button " + button
                        + (button >= 4 ? "（全按成功）" : "（全按未成功，可能未对上焦）"));
            }
            return press;
        } catch (Exception error) {
            recordCaptureStep("0x9128 RemoteReleaseOn(3,0) 全按（按 EOS M 编码回退）→ "
                    + error.getMessage());
            return null;
        }
    }

    /**
     * 排空 Canon EOS 的事件队列（0x9116 GetEvent，返回 TLV 事件块序列）。
     *
     * <p>队列空时相机会回「纯 ResponseOK、无数据段」——{@link #commandWithData} 正好把这种情况
     * 当响应码抛出，据此判定排空完成。这样收发始终一一对应，不会串包。
     * 数据段是 TLV 序列 {@code [u32 size][u32 code][payload]}（libgphoto2 ptp-pack.c:1861），
     * 不足一条记录（&lt; 8 字节）即为空。
     *
     * <p><b>必须在每次厂商命令前调用</b>。libgphoto2 在取景循环里每取一帧前都调一次
     * （library.c:3670），因为 SetEventMode(1) 之后相机持续往队列里堆事件，主机不读就会
     * 积压，之后所有厂商命令（写曝光 0x9110、对焦 0x9154、开关取景 0x9151/0x9152、
     * 全按 0x9128）都会统一回 0x2019 DeviceBusy。这就是本次「ISO/光圈/快门写入失败 +
     * 对焦无效 + 关面板不恢复显示」的共同根因。
     *
     * @return 失败原因，成功（含本来就空）返回 {@code null}；调用方自行决定要不要报出去。
     */
    private String drainEosEvents() {
        long deadline = System.currentTimeMillis() + CANON_EVENT_DRAIN_BUDGET_MS;
        for (int round = 0; round < CANON_EVENT_DRAIN_MAX_ROUNDS; round++) {
            if (round > 0 && System.currentTimeMillis() >= deadline) break;
            byte[] block;
            try {
                block = commandWithData(CANON_GET_EVENT, transactionId++, new long[0]);
            } catch (PtpResponseException empty) {
                // 队列已空（相机回纯 ResponseOK）——正常终点，不是故障。
                return empty.responseCode == RESPONSE_OK
                        ? null : "0x9116 GetEvent 排空失败 → " + hexCode(empty.responseCode);
            } catch (Exception failure) {
                return "0x9116 GetEvent 排空失败：" + describe(failure);
            }
            // 顺手把这一块里的 0xC189/0xC18A 事件收进缓存：0xD1xx 属性的当前值与允许值列表
            // 只能从这里攒（见 harvestEosEventBlock）。
            harvestEosEventBlock(block, canonPropValues, canonPropEnums, canonPropRaw, canonPropEnumRaw);
            if (isEmptyEosEventBlock(block)) return null;
        }
        // 撞上限说明事件还在往外冒，队列**没排干净**。这里必须报出来：返回 null 会被上层
        // 当成「已排空」，紧接着的 0x9110 写入撞在脏队列上回 0x2019，看起来就成了
        // 「档位写入被相机拒绝」——真凶（队列）被错误信息盖掉了。
        return "0x9116 GetEvent 连读 " + CANON_EVENT_DRAIN_MAX_ROUNDS + " 轮仍有事件，事件队列未排空";
    }

    /**
     * 事件数据段里有没有完整的事件记录。libgphoto2 的解析循环要求
     * {@code curdata - data + 8 < datasize}（ptp-pack.c:1869），即**不长于 8 字节的数据段
     * 一条记录都解不出来**；相机还会用 {@code [size=8][code=0]} 这种 8 字节空记录表示
     * 「没有更多事件」（ptp-pack.c:1881）。
     */
    private static boolean isEmptyEosEventBlock(byte[] block) {
        return block.length <= 8;
    }

    /** 拍摄路径上的排空：失败要进快门诊断，方便判断「全按被拒」是否由事件积压引起。 */
    private void drainEosEventsForCapture() {
        String failure = drainEosEvents();
        if (failure != null) recordCaptureStep(failure);
    }

    /** 排空事件并尝试取出 {@code Button N} 里的按钮值；没读到就返回 {@code null}。 */
    private Integer drainEosEventsForButton() {
        for (int round = 0; round < CANON_EVENT_DRAIN_MAX_ROUNDS; round++) {
            byte[] block;
            try {
                block = commandWithData(CANON_GET_EVENT, transactionId++, new long[0]);
            } catch (Exception empty) {
                // 队列已空（相机回纯 ResponseOK）：本轮没读到按钮事件。
                return null;
            }
            harvestEosEventBlock(block, canonPropValues, canonPropEnums, canonPropRaw, canonPropEnumRaw);
            if (block.length < 8) return null;
            Integer button = parseButtonEvent(block);
            if (button != null) return button;
        }
        return null;
    }

    /** 在事件块里找 ASCII 的 {@code "Button N"}，返回 N；找不到返回 {@code null}。 */
    private Integer parseButtonEvent(byte[] block) {
        final byte[] needle = {'B', 'u', 't', 't', 'o', 'n', ' '};
        for (int start = 0; start + needle.length < block.length; start++) {
            int offset = 0;
            while (offset < needle.length && block[start + offset] == needle[offset]) offset++;
            if (offset != needle.length) continue;
            int digit = block[start + needle.length] - '0';
            if (digit >= 0 && digit <= 9) return digit;
        }
        return null;
    }

    /**
     * 把一段 {@code 0x9116 GetEvent} 数据段里的 TLV 记录收进三个缓存。记录序列为
     * {@code [u32 size][u32 code][payload…]}，{@code size} **含** 8 字节头
     * （libgphoto2 {@code ptp_unpack_EOS_events}，ptp-pack.c:1869-1899）。
     *
     * <p>只关心两类：{@code 0xC189} 属性当前值（收进 {@code values}，同时把原始字节原样收进
     * {@code raw}）与 {@code 0xC18A} 属性允许值列表（收进 {@code enums}，仅 {@code propxtype==3}
     * 的枚举；同时把列表的原始字节原样收进 {@code enumRaw}）。相机积攒很久的事件块里会混着
     * 属性描述等其它记录，逐个跳过即可。终止条件与 libgphoto2 对齐：记录头不足 8 字节、
     * {@code size==8 && code==0}（相机的「没有更多事件」标记，ptp-pack.c:1881）、或声明长度越界，
     * 都立刻停止而不报错——截断/未知记录一律忽略。
     *
     * <p>{@code raw} 是给能力探测用的：{@code 0xD134}/{@code 0xD13D} 的当前值在 EOS RP 上是
     * 24 字节的不透明区域，{@code long} 装不下，必须原样留字节。
     *
     * <p>{@code enumRaw} 是给画质（{@code 0xD120}）用的：它的允许值列表项不是「每项 4 字节的
     * u16」，而是变长 u32 序列，{@code propEnumOf} 解出来的 {@code int[]} 是错的，只能留原始
     * 字节给 {@link com.imagefetch.ptp.CanonImageQuality} 解析。
     *
     * <p>做成 {@code static} 且只操作传入的四个 Map，便于脱离 socket 单测（见 EosEventHarvestTest）。
     */
    static void harvestEosEventBlock(
            byte[] block,
            Map<Integer, Long> values,
            Map<Integer, int[]> enums,
            Map<Integer, byte[]> raw,
            Map<Integer, byte[]> enumRaw) {
        if (block == null) return;
        int offset = 0;
        while (offset + 8 <= block.length) {
            long size = u32(block, offset);
            long code = u32(block, offset + 4);
            if (size < 8 || (size == 8 && code == 0) || offset + size > block.length) break;
            int recordSize = (int) size;
            if (code == CANON_EVENT_PROP_VALUE_CHANGED) {
                int property = recordProperty(block, offset, recordSize);
                if (property >= 0) {
                    long value = propValueOf(block, offset, recordSize);
                    if (value >= 0) values.put(property, value);
                    byte[] region = propRawOf(block, offset, recordSize);
                    if (region != null) raw.put(property, region);
                }
            } else if (code == CANON_EVENT_AVAIL_LIST_CHANGED) {
                int property = recordProperty(block, offset, recordSize);
                // 只有 propxtype==3 才是「允许值枚举」；1/7 等其它类型（属性描述）不是列表，
                // 提前判掉，免得把描述块当成允许值收进来。
                if (property >= 0 && propxtypeOf(block, offset, recordSize) == CANON_PROPX_TYPE_ENUM) {
                    byte[] region = propEnumRawOf(block, offset, recordSize);
                    if (region != null) enumRaw.put(property, region);
                    // 画质（0xD120）那一族的列表项是变长 u32 序列，按 stride 取 u16 会解出
                    // n/type 之类的垃圾值——只留原始字节，不往 enums 里塞错表。
                    if (!isImageFormat(property)) {
                        int[] allowed = propEnumOf(block, offset, recordSize);
                        if (allowed != null) enums.put(property, allowed);
                    }
                }
            }
            offset += recordSize;
        }
    }

    /** 画质一族（{@code 0xD120–0xD123}）：值区域是变长 u32 序列，不能用通用枚举解析。 */
    private static boolean isImageFormat(int propertyCode) {
        return propertyCode == CanonImageQuality.IMAGE_FORMAT
                || propertyCode == CanonImageQuality.IMAGE_FORMAT_CF
                || propertyCode == CanonImageQuality.IMAGE_FORMAT_SD
                || propertyCode == CanonImageQuality.IMAGE_FORMAT_EXT;
    }

    /**
     * 0xC18A 记录的性质字段 {@code propxtype}（payload +0x0c）。{@code 3} 才是枚举；
     * 1/7 等是属性描述，不是值列表。记录太短返回 -1。
     */
    private static long propxtypeOf(byte[] block, int offset, int size) {
        return size >= 0x10 ? u32(block, offset + 0x0c) : -1;
    }

    /**
     * {@code 0xC18A} 记录里允许值列表的**原始字节**（payload +0x14 到记录末尾）。
     * 给画质（{@code 0xD120}）用：它的列表项是变长 u32 序列而不是 4 字节 u16，
     * 只能原样留字节给 {@link com.imagefetch.ptp.CanonImageQuality} 解析。
     * 记录里没有列表区域（不长于 0x14）时返回 {@code null}。
     */
    private static byte[] propEnumRawOf(byte[] block, int offset, int size) {
        if (size <= 0x14) return null;
        byte[] region = new byte[size - 0x14];
        System.arraycopy(block, offset + 0x14, region, 0, region.length);
        return region;
    }

    /** TLV 记录里的属性码（payload +0x08）；记录太短返回 -1。 */
    private static int recordProperty(byte[] block, int offset, int size) {
        return size >= 0x0c ? (int) u32(block, offset + 0x08) : -1;
    }

    /**
     * 0xC189 记录的属性当前值（payload +0x0c）。曝光/ISO/AF 方法都是 DataType
     * {@code PTP_DTC_UINT16}（ptp-pack.c:2214-2216），故只取低 16 位；先按 4 字节值读
     * （多数固件按 u32 对齐），实在只有 2 字节时才读 u16。太短返回 -1。
     */
    private static long propValueOf(byte[] block, int offset, int size) {
        if (size >= 0x10) return u32(block, offset + 0x0c) & 0xffffL;
        if (size >= 0x0e) return u16(block, offset + 0x0c) & 0xffffL;
        return -1;
    }

    /**
     * {@code 0xC189} 记录的值区域**原始字节**（payload +0x0c 到记录末尾）。给能力探测用：
     * {@code 0xD134}/{@code 0xD13D} 的当前值在 EOS RP 上是 24 字节的不透明区域，
     * 只有原样留字节才能既完整显示、又在「原值回写」验证时按原样写回去。
     * 记录里没有值区域（不长于 12 字节）时返回 {@code null}。
     */
    private static byte[] propRawOf(byte[] block, int offset, int size) {
        if (size <= 0x0c) return null;
        byte[] region = new byte[size - 0x0c];
        System.arraycopy(block, offset + 0x0c, region, 0, region.length);
        return region;
    }

    /**
     * 0xC18A 记录的允许值列表（payload +0x08 属性码、+0x0c propxtype、+0x10 个数、
     * +0x14 起每项 4 字节且值在低 2 字节，libgphoto2 stride 4，ptp-pack.c:2073）。
     * 只有 {@code propxtype==3} 才是枚举，1/7 等其它类型返回 {@code null} 忽略；
     * 个数为 0、声明长度不足或数组越界时同样返回 {@code null}。
     */
    private static int[] propEnumOf(byte[] block, int offset, int size) {
        if (size < 0x14) return null;
        if (u32(block, offset + 0x0c) != CANON_PROPX_TYPE_ENUM) return null;
        long count = u32(block, offset + 0x10);
        if (count <= 0 || count > 0x1000 || size < 0x14 + 4 * count) return null;
        int[] allowed = new int[(int) count];
        for (int index = 0; index < count; index++) {
            allowed[index] = u16(block, offset + 0x14 + 4 * index);
        }
        return allowed;
    }

    /**
     * 等相机上报「新增对象」事件（{@code 0x4002 ObjectAdded}，由 event reader 线程递增
     * {@link #objectAddedSeq}）。
     *
     * <p>这是「相机回了 OK 但没拍」能在传输层自证的最省事办法：事件是相机自己推的，不需要
     * 任何额外 PTP 往返。原先靠轮询整卡句柄（每 500ms 一次全卡枚举、最多 10 次）判定，
     * 正是「按一下快门等 8 秒」的主要来源。
     *
     * <p>事件不来（部分机型在 PTP/IP 下不推这条）时按「未确认」处理：本次拍摄的成败由上层
     * {@code NewPhotoResolver}（事件 + 句柄差集，超时 20s）判定，这里的结果只影响机型记忆与诊断。
     */
    private boolean awaitObjectAdded(long startSequence) {
        long deadline = System.currentTimeMillis() + CANON_SHOT_EVENT_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (objectAddedSeq != startSequence) return true;
            sleepQuietly(CANON_SHOT_EVENT_POLL_MS);
        }
        return false;
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

    /**
     * 发一次快门命令，遇 {@code 0x2019 DeviceBusy} 退避重试；退避完仍忙就重建遥控/事件态再补发一次。
     *
     * <p>为什么要与 {@link #writeExposure} 完全同一套处置：取景中按快门时，相机在「退出取景推流」
     * 与「写卡」两个时刻都会瞬时回 {@code 0x2019}。原先只有全按 (2,0) 带退避重试，**半按 (1,0)
     * 与 EOS M 的 (3,0) 一次被拒就整单失败**——症状是「在取景里拍，时好时坏」。
     * 退避完仍忙说明相机可能已经不在遥控/事件态（与 0x9110 写入被拒同因），
     * 这时先 {@link #rebuildCanonStateAfterBusy()} 把状态重建回来，再补发一次。
     *
     * <p>重试是安全的：回 {@code 0x2019} 意味着快门**没有**被释放，重发不会多拍。
     */
    private ReleaseResult releaseCommandRetrying(int operation, long[] parameters) throws Exception {
        ReleaseResult result = releaseCommand(operation, parameters);
        for (int retry = 1; result.code == RESPONSE_DEVICE_BUSY
                && retry <= CANON_RELEASE_BUSY_RETRIES; retry++) {
            sleepQuietly(CANON_RELEASE_BUSY_BACKOFF_MS * retry);
            drainEosEventsForCapture();
            result = releaseCommand(operation, parameters);
        }
        if (result.code == RESPONSE_DEVICE_BUSY) {
            rebuildCanonStateAfterBusy();
            drainEosEventsForCapture();
            result = releaseCommand(operation, parameters);
        }
        return result;
    }

    // ── Canon EOS 实时取景与曝光参数 ──

    /**
     * 开关实时取景。开启序列对齐 libgphoto2 {@code camera_capture_preview}
     * （library.c:3622-3662）：EVFMode=1 → EVFOutputDevice=PC → KeepDeviceOn。
     *
     * <p>{@code size} 写进 0xD1B0 的输出位（2/4/8 = Large/Medium/Small），相机据此决定推给 App 的
     * 帧分辨率——这是真正降低传输分辨率的地方，App 端只是收什么画什么。写入时保留 TFT 位
     * （机身屏一起亮），与 libgphoto2 改画面尺寸的做法一致（config.c:2669-2670）。
     *
     * <p>{@code enabled=false}（关面板）只把画面输出收回机身屏，**不退出遥控态**——
     * 理由与真机证据见 {@link #stopLiveViewOutput()}。
     *
     * <p>Movie 模式下写 EVFMode 会回 DeviceBusy 但可以继续，故容忍该响应码。
     * 这里不做 libgphoto2 的「先读再写」优化：0xD1xx 属性只能从 0x9116 事件缓存里读，
     * 而标准 0x1015 对 0xD1xx 直接回 DevicePropNotSupported，读不到就白多两次往返。
     */
    @Override
    public synchronized void setLiveViewEnabled(boolean enabled, LiveViewSize size) throws Exception {
        ensureConnected();
        if (enabled) {
            // 取景前必须先进入遥控态，否则 0x9110 写入可能回 OK 但机身不切取景。
            ensureCanonRemoteMode();
            // 反复开关取景时队列里会残留事件，不清掉下面的命令会回 0x2019。
            drainEosEvents();
            // 对焦驱动还在跑时取景属性同样写不进去（0x2019），先停掉它。
            cancelCanonAutofocus();
            // 真正启动取景推流要用专用命令（libgphoto2 config.c:9177-9180 的首选路径）。
            // 只写属性时相机往往要等一次释放周期才开始出画面——这正是「要先拍一张，
            // 取景才正常」的原因。失败不致命，属性写入仍会兜底。
            lastLiveViewStartCode = command(CANON_INITIATE_VIEWFINDER, transactionId++, new long[0]);
            lastEvfModeValue = 1;
            int mode = writeCanonProperty(CANON_PROP_EVF_MODE, 1);
            if (mode != RESPONSE_OK && mode != RESPONSE_DEVICE_BUSY) {
                throw new IOException(
                        "相机拒绝了实时取景开启（0x9110 EVFMode=1）：" + hexCode(mode), rejected(mode));
            }
            // 输出位必须带上 TFT 位（= 保留机身屏），与 libgphoto2 改画面尺寸的做法一致
            // （config.c:2669-2670「replace the current outputsize, but keep the TFT flag」）。
            // 该属性只有 0x0..0xB 这些位组合合法，写纯输出位会让机身屏黑掉。
            int outputValue = CANON_EVF_OUTPUT_TFT | size.outputBit;
            lastEvfOutputValue = outputValue;
            int output = writeCanonPropertyRetrying(CANON_PROP_EVF_OUTPUT_DEVICE, outputValue);
            if (output != RESPONSE_OK) {
                throw new IOException(
                        "相机拒绝了取景输出切换（0x9110 EVFOutputDevice="
                                + outputValue + "）：" + hexCode(output),
                        rejected(output));
            }
            keepDeviceOn();
            return;
        }
        // 关取景前先排空事件：积压时下面每一步都会被回 0x2019，机身屏就点不亮。
        drainEosEvents();
        // 对焦驱动若还在跑，属性写入会被回 0x2019（libgphoto2 拆拍摄准备的第一步正是停对焦，
        // config.c:574-577）。
        cancelCanonAutofocus();
        // 0x9152 只在相机能力集里有它时才有效；本机型没有，固定回 0x2005，属预期噪音。
        try {
            command(CANON_TERMINATE_VIEWFINDER, transactionId++, new long[0]);
        } catch (Exception ignored) {
            // 机型不支持就跳过，退出取景靠下面的属性回退。
        }
        // 把画面输出从 PC 收回，只留机身屏（bit0）：这就是「面板关了、画面还给机身」的全部动作。
        stopLiveViewOutput();
    }

    /**
     * 关面板时收回 PC 画面输出：{@code 0x9110 EVFOutputDevice = 0x01}（只留 TFT 位）。
     *
     * <p><b>为什么写 0x01 而不是 0</b>：bit0 表示「画面输出到机身屏」，写纯输出位会让机身屏黑掉；
     * libgphoto2 改画面尺寸时也是「替换输出位、保留 TFT 标志」（config.c:2669-2670）。
     *
     * <p><b>为什么不再跑那套「交还机身」收尾</b>（{@code SetRemoteMode(0)} + {@code SetEventMode(0)}
     * + {@code ResetUILock} + {@code SetModeDialDisable(0)}，即 config.c:571-605 的
     * camera_unprepare_canon_eos_capture 加 library.c:3287-3297 的 camera_exit）：那套是给
     * **马上要断开连接**的会话用的收尾——libgphoto2 收尾之后必然关掉 PTP/IP 会话，真正让相机
     * 离开遥控态的是随后的断开。我们按要求保持连接，断开这一步永远不发生，于是相机被留在
     * 「会话还开着、遥控态却已退出」的半状态：真机（EOS RP）上就是**机身按键无响应、屏幕显示
     * busy**。反过来，实测留在遥控态时手机能调参、机身也能操作——这正是要保留的状态，所以这里
     * 只收回画面输出，{@link #canonRemoteModeReady} 也保持不变（遥控态与事件模式都还开着，
     * 下次开面板不必重发）。
     *
     * <p>只发一次、不做退避重试：失败不致命（最坏情况是机身屏这一会儿没画面），而相机写卡时
     * 每次重试要白等几百毫秒，关面板不该因此卡住。
     */
    private void stopLiveViewOutput() {
        lastEvfOutputValue = CANON_EVF_OUTPUT_TFT;
        try {
            lastLiveViewStopCode = writeCanonProperty(
                    CANON_PROP_EVF_OUTPUT_DEVICE, CANON_EVF_OUTPUT_TFT);
        } catch (Exception error) {
            lastLiveViewStopCode = -1;
        }
        if (lastLiveViewStopCode != RESPONSE_OK) {
            recordCaptureStep("关取景 EVFOutputDevice=0x01 未成功 → "
                    + (lastLiveViewStopCode < 0 ? "发送失败/超时" : hexCode(lastLiveViewStopCode)));
        }
    }

    @Override
    public synchronized void pauseLiveViewForCapture(LiveViewSize size) throws Exception {
        ensureConnected();
        drainEosEvents();
        // libgphoto2 拆拍摄准备的第一步：停掉可能还在跑的对焦（config.c:574-577）。
        // 不停对焦时下面的 EVFOutputDevice 写入会被回 0x2019，而全按快门紧跟其后。
        cancelCanonAutofocus();
        // 拍摄要求画面上游真正停住，这里必须写 0——libgphoto2 的内存拍摄路径也是先把
        // EVFOutputDevice 清 0（EOS M 的 camera_unprepare_canon_eos_capture，config.c:582-583）。
        // 与「关面板」的区别：不动遥控态、不做交还机身的收尾，拍完立刻恢复取景。
        //
        // 这里**只发一次、不做退避重试**：本机实测该写入每次都回 0x2019（相机在推流时
        // 不接受清 0），而 0x9110 的退避重试是 400/800/1200/1600ms，五次尝试白白多花 4 秒，
        // 用户侧的感受就是「点一次拍摄卡好几秒」。快门成败不依赖这一步（0x9128 才是关键），
        // 响应码照样记进诊断。
        try {
            lastEvfOutputValue = 0;
            lastLiveViewStopCode = writeCanonProperty(CANON_PROP_EVF_OUTPUT_DEVICE, 0);
        } catch (Exception error) {
            lastLiveViewStopCode = -1;
        }
        try {
            lastEvfModeValue = 0;
            writeCanonProperty(CANON_PROP_EVF_MODE, 0);
        } catch (Exception ignored) {
            // EVFMode 写失败同样不致命：取景推流会在拍完恢复时重新打开。
        }
        drainEosEvents();
    }

    /** 最近一次开启取景时 {@code 0x9151} 的响应码；-1 表示本会话还没开过。仅供诊断。 */
    private int lastLiveViewStartCode = -1;
    /** 最近一次关闭取景时 {@code 0x9152} 的响应码；-1 表示本会话还没关过。仅供诊断。 */
    private int lastLiveViewStopCode = -1;
    /**
     * 最近一次写入 {@code 0xD1B0 EVFOutputDevice} 的取值；-1 表示本会话还没写过。
     * 相机「不听 0x9110」时靠它把取景输出写回我们最后要求的样子（见
     * {@link #rebuildCanonStateAfterBusy()}）。
     */
    private int lastEvfOutputValue = -1;
    /** 最近一次写入 {@code 0xD1B1 EVFMode} 的取值；-1 表示本会话还没写过。同上，供自愈用。 */
    private int lastEvfModeValue = -1;
    /**
     * 「相机的对焦驱动可能还在跑」。按过 {@code 0x9154}、或按过快门（快门本身就带 AF）之后置位，
     * 发过 {@code 0x9160 EOS_AfCancel} 后清零。之所以要记这个状态，是因为「停对焦」这一步
     * 只在**接下来要动相机**时才有必要（写属性、退出取景）——每次调用都无脑发一遍会白搭一次
     * 往返，而曝光三档是逐档点击的。
     */
    private boolean canonAfMaybeRunning;

    /**
     * 事件缓存：{@code 0xC189 EOS_PropValueChanged} 推来的「属性当前值」（属性码 → 机上编码值）。
     * EOS 的 0xD1xx 属性没有同步读接口，当前值只能从 0x9116 事件块里攒——见
     * {@link #harvestEosEventBlock}。只在 synchronized 方法里读写，锁即 transport 实例锁。
     */
    private final Map<Integer, Long> canonPropValues = new LinkedHashMap<>();
    /**
     * 事件缓存：{@code 0xC18A EOS_AvailListChanged} 推来的「属性允许值列表」
     * （属性码 → 取值数组，保持相机给的顺序）。界面用它把曝光档位收窄到机身真正支持的子集。
     */
    private final Map<Integer, int[]> canonPropEnums = new LinkedHashMap<>();
    /**
     * 事件缓存：{@code 0xC189} 推来的属性当前值的**原始字节**（属性码 → 值区域）。
     * {@code canonPropValues} 只留低 16 位，装不下 {@code 0xD134}/{@code 0xD13D} 的 24 字节
     * 不透明区域——能力探测（P0）需要原样字节，故单独存一份。同样只在 synchronized 方法里读写。
     */
    private final Map<Integer, byte[]> canonPropRaw = new LinkedHashMap<>();
    /**
     * 事件缓存：{@code 0xC18A} 推来的允许值列表的**原始字节**（属性码 → 列表区域）。
     * {@code canonPropEnums} 只存「每项 4 字节 u16」那一族的解析结果；画质（{@code 0xD120}）
     * 的列表项是变长 u32 序列，装不进 {@code int[]}，只能留原始字节给
     * {@link com.imagefetch.ptp.CanonImageQuality} 解析。只在 synchronized 方法里读写。
     */
    private final Map<Integer, byte[]> canonPropEnumRaw = new LinkedHashMap<>();
    /** 最近一帧取景载荷里上报的感光器分辨率（宽, 高）；没上报过为 null。 */
    private int[] lvSensorSize;
    /** 最近一帧取景载荷里出现的 TLV 段类型（诊断用，只增不减）。 */
    private String lvSegmentTypes;


    /**
     * 单次自动对焦（{@code 0x9154 EOS_DoAf}，无参数）。对焦过程由相机完成，本方法在相机
     * 返回响应后即返回；此时取景画面里应能看到对焦动作。需取景已开启。
     */
    @Override
    public synchronized void autofocus() throws Exception {
        ensureConnected();
        ensureCanonRemoteMode();
        // 事件积压会让相机对厂商命令统一回 0x2019 DeviceBusy，对焦就会「看起来没用」。
        drainEosEvents();
        // 上一次对焦（或拍摄，快门本身就带 AF）留下的对焦驱动若还没结束，这一次 0x9154
        // 会被相机直接拒掉。真机症状正是「自动对焦只有第一次生效，之后必须先调一次三要素
        // 才能对焦」——调档位会走 writeExposure → cancelCanonAutofocus() 把对焦停掉，
        // 于是下一次对焦又能动了。libgphoto2 把 DoAf 与 AfCancel 配成同一个 toggle
        // （config.c:8419-8431），这里照做。
        cancelCanonAutofocus();
        int response = command(CANON_DO_AF, transactionId++, new long[0]);
        // 0x2019 是「暂时忙」而不是不支持，退避重试几次再判定失败。上一版把 DeviceBusy
        // 当成功直接返回，表现就是「按了对焦没反应」——错误被静默吞掉了。
        for (int retry = 1; response == RESPONSE_DEVICE_BUSY && retry <= CANON_AF_BUSY_RETRIES; retry++) {
            sleepQuietly(CANON_RELEASE_BUSY_BACKOFF_MS * retry);
            drainEosEvents();
            response = command(CANON_DO_AF, transactionId++, new long[0]);
        }
        if (response != RESPONSE_OK) {
            throw new IOException("相机拒绝了对焦命令（0x9154 EOS_DoAf）：" + hexCode(response),
                    rejected(response));
        }
        // 相机的对焦驱动这时还在跑，后续任何 0x9110 写入都会被回 0x2019。记下来，
        // 等下一次真要动相机时先发 0x9160 停对焦（对齐 libgphoto2 config.c:574-577）。
        canonAfMaybeRunning = true;
        // 对焦后必须再排空一次：libgphoto2 的 _put_Canon_EOS_AFDrive 在 0x9154 成功后紧跟
        // ptp_check_eos_events（config.c:8428-8429「Get the next set of event data」）。
        // 对焦过程本身会产生一批事件，不读走就卡住后面的命令，表现为「按了没反应」。
        drainEosEvents();
    }

    /**
     * 若相机的对焦驱动可能还在跑，先发 {@code 0x9160 EOS_AfCancel} 停掉它再继续。
     *
     * <p>这是 libgphoto2 拆拍摄准备的第一步（config.c:574-577，注释 "just in case we had
     * autofocus running"）。不停对焦时相机对 {@code 0x9110} 属性写入统一回
     * {@code 0x2019 DeviceBusy}，而且退出取景那一串收尾命令会被逐个挡掉（它们各自兜异常，
     * 于是整段静默失败）——表现就是「按过对焦/拍过一张后三要素写不进去」与「关了面板机身屏
     * 还不回来」。只在置位后发一次，失败也不再重试：机型不支持时相机会自行结束对焦驱动。
     */
    private void cancelCanonAutofocus() {
        if (!canonAfMaybeRunning) return;
        canonAfMaybeRunning = false;
        try {
            command(CANON_EOS_AF_CANCEL, transactionId++, new long[0]);
        } catch (Exception ignored) {
            // 能力集里没有 0x9160 的机型会回 0x2005；不影响后续步骤。
        }
    }

    /**
     * 半按再松开快门，把相机的拍摄/取景管线唤醒。实测相机「拍一张之后取景才出画面」，
     * 说明取景输出要经过一次释放周期才会真正启动；半按只测光/对焦、不拍照
     * （{@link #canonRemoteRelease()} 的半按同理）。
     */
    @Override
    public synchronized void wakeForLiveView() throws Exception {
        ensureConnected();
        ensureCanonRemoteMode();
        try {
            releaseCommand(CANON_REMOTE_RELEASE_ON, HALF_PRESS);
        } catch (Exception ignored) {
            // 唤醒失败不额外报错：上层会继续取帧重试。
        }
        sleepQuietly(CANON_RELEASE_HOLD_MS);
        try {
            releaseCommand(CANON_REMOTE_RELEASE_OFF, FULL_PRESS);
        } catch (Exception ignored) {
            // 同上。
        }
    }

    /**
     * 取一帧实时取景 JPEG。相机这一帧还没准备好（0xA102 ObjectNotReady / DeviceBusy）时返回
     * {@code null}，由上层退避重试——这在取景刚开启时很常见，不是错误。
     */
    @Override
    public synchronized byte[] liveViewFrame() throws Exception {
        ensureConnected();
        // 取景期间事件产生得最快，必须每帧排空一次（libgphoto2 library.c:3670 同款做法），
        // 否则队列很快积满，之后写曝光/对焦/开关取景全被回 DeviceBusy。
        drainEosEvents();
        byte[] data;
        try {
            data = commandWithData(
                    CANON_GET_VIEW_FINDER_DATA,
                    transactionId++,
                    new long[]{CANON_VIEWFINDER_PARAMETER, 0, 0});
        } catch (PtpResponseException notReady) {
            if (notReady.responseCode == RESPONSE_CANON_OBJECT_NOT_READY
                    || notReady.responseCode == RESPONSE_DEVICE_BUSY) {
                return null;
            }
            throw notReady;
        }
        byte[] jpeg = extractLiveViewJpeg(data);
        recordLiveViewPayload(data);
        return jpeg;
    }

    /**
     * 解析取景载荷：记下感光器分辨率（供 0x915B 换算）与本帧出现过的段类型（诊断用，
     * 便于在「对焦框仍不动」时判断相机到底发了哪些段）。解析失败不影响这一帧。
     */
    private void recordLiveViewPayload(byte[] data) {
        if (data == null || data.length < 8) return;
        StringBuilder types = new StringBuilder();
        int offset = 0;
        while (offset + 8 <= data.length) {
            long length = u32(data, offset);
            int type = (int) u32(data, offset + 4);
            if (length < 8 || offset + length > data.length) break;
            if (types.length() > 0) types.append(',');
            types.append(type);
            if (type == CANON_LV_SEG_SENSOR_RESOLUTION && length >= 16) {
                long width = u32(data, offset + 8);
                long height = u32(data, offset + 12);
                // 只有像「横版传感器」才认：像素上限按 80 MP 卡，避免把别的段误读成分辨率。
                if (width > height && height >= 480 && width <= 40000 && height <= 30000) {
                    lvSensorSize = new int[]{(int) width, (int) height};
                }
            }
            offset += (int) length;
        }
        if (types.length() > 0) lvSegmentTypes = types.toString();
    }

    @Override
    public synchronized int[] liveViewSensorSize() {
        return lvSensorSize == null ? null : lvSensorSize.clone();
    }

    @Override
    public synchronized String liveViewSegmentTypes() {
        return lvSegmentTypes;
    }

    /**
     * 把取景画面里的一个点设为对焦框（{@code 0x915B EOS_TouchAfPosition}，<b>不触发</b>对焦）。
     *
     * <p>坐标是<b>感光器像素</b>（0..传感器宽/高），不是取景帧像素——这正是旧版「点了没反应」
     * 的原因：把取景帧坐标（如 960×640）当感光器坐标发出去，落在画面左上角附近，看起来像没动。
     * 第一个参数是固定常量 {@code 3}（EOS Utility 抓包值），旧版发 0 时相机静默忽略。</p>
     */
    @Override
    public synchronized void touchAfPosition(int x, int y) throws Exception {
        ensureConnected();
        drainEosEvents();
        int response = command(
                CANON_TOUCH_AF_POSITION,
                transactionId++,
                new long[]{CANON_TOUCH_AF_TYPE, x & 0xffff_ffffL, y & 0xffff_ffffL});
        if (response != RESPONSE_OK) {
            throw new IOException("相机拒绝了对焦框移动（0x915B）：" + hexCode(response),
                    rejected(response));
        }
        drainEosEvents();
    }

    /** 先按感光器坐标移动对焦框，再触发一次对焦——「自动对焦」按用户点的那一点对焦。 */
    @Override
    public synchronized void autofocusAt(int x, int y) throws Exception {
        touchAfPosition(x, y);
        autofocus();
    }

    /** 写入曝光参数（取值来自 {@link CanonExposure.entries} 的机上编码）。 */
    @Override
    public synchronized void writeExposure(CanonExposure.Setting setting, int value) throws Exception {
        ensureConnected();
        // 先排空事件：积压时相机会直接回 0x2019，表现为「档位写入失败」。排空失败的原因
        // 要留到最后一起报——否则「队列没排干净」会被写成「属性写入被相机拒绝」，让人
        // 一直往档位/机型上找原因。刚对过焦时正是这种情形：0x9154 会连推一批属性事件。
        String drainFailure = drainEosEvents();
        // 排空事件还不够：对焦驱动本身没结束的话，0x9110 一律回 0x2019（不是队列脏，
        // 是相机在 AF）。libgphoto2 只要动相机就先停对焦（config.c:574-577）——这一步
        // 正是「按过一次对焦/拍过一张之后三要素再也写不进去」的解药。
        cancelCanonAutofocus();
        // 取景中相机繁忙时 0x9110 会瞬时回 0x2019，退避重试几次（libgphoto2 同样把 0x9110 的
        // DeviceBusy 当「暂时忙」，config.c:355）。
        int code = writeCanonPropertyRetrying(setting.vendorProperty, value & 0xffffL);
        if (code == RESPONSE_DEVICE_BUSY) {
            // 退避重试仍然被拒：相机很可能已经不在遥控/事件态了（真机实测：按过一次自动对焦
            // 之后就是这种状态，0x9110 此后再也写不进去，只能关面板重开或重连才恢复）。
            // 先把状态重建回来，再补写一次——状态重建好的话，这一次立刻就能成。
            rebuildCanonStateAfterBusy();
            code = writeCanonProperty(setting.vendorProperty, value & 0xffffL);
        }
        if (code != RESPONSE_OK) {
            // 带上档位文案与机上编码：ISO 的可选范围因机型而异（表里 6/12/25… 未必被机身接受），
            // 报出实际写进去的值才能分辨是「档位越界」还是「相机忙」。
            throw new IOException(
                    setting.label + "写入被相机拒绝（0x9110 属性 "
                            + Integer.toHexString(setting.vendorProperty) + "，值 0x"
                            + Integer.toHexString(value & 0xffff) + "）：" + hexCode(code)
                            + (drainFailure == null ? "" : "\n" + drainFailure),
                    rejected(code));
        }
    }

    /**
     * 读取曝光参数的当前编码值，取值与 {@link #writeExposure} 同一套 {@link CanonExposure} 表值，
     * 由调用方用 {@link CanonExposure#indexOfValue} 换算成档位。
     *
     * <p>EOS 的 0xD1xx 属性没有同步读接口：标准 {@code 0x1015 GetDevicePropValue} 对它一律回
     * {@code 0x2005 DevicePropNotSupported}，旧版按 0x1015 读的路径因此永远读不到值（面板从不与
     * 机身同步）。正确做法是从 {@code 0x9116 GetEvent} 事件块里取 {@code 0xC189} 推送的当前值；
     * 缓存里没有时再补发一次 {@code 0x9127 RequestDevicePropValue} 请相机推。
     * libgphoto2 走的正是这条路：{@code ptp_canon_eos_getdevicepropdesc} 直接从 eos 事件派生的
     * 属性描述缓存里取值（ptp.c:3757-3769），一般 PTP 路径对 Canon 厂商属性段也一律改走这个缓存、
     * 查不到就回 {@code PTP_RC_DevicePropNotSupported}（ptp.c:4760-4779）；缓存的填充靠
     * {@code ptp_check_eos_events}（ptp.c:3733-3743），开机后先 request 一批属性再反复 check
     * （config.c:400-436）。
     *
     * <p>读之前先 {@code ensureCanonRemoteMode()}（相机只在 SetEventMode(1) 之后才推这些属性），
     * 再排空一次事件队列（不排空时相机对厂商命令统一回 0x2019 DeviceBusy）。
     */
    @Override
    public synchronized long readExposure(CanonExposure.Setting setting) throws Exception {
        ensureConnected();
        ensureCanonRemoteMode();
        return readCanonProperty(setting.vendorProperty, setting.label);
    }

    /**
     * 取一个 {@code 0xD1xx} 属性的当前值：先看事件缓存，没有就发 {@code 0x9127} 请相机推一次，
     * 然后在若干轮 0x9116 排空里等它出现；等不到抛中文错误（不丢裸响应码给用户）。
     *
     * <p>为什么要等若干轮：libgphoto2 在 config.c:429-436 明确写了 EOS「偶尔会先回一批空事件，
     * 多半是主机问得太快」，于是它用 10 次 × 50ms 的 {@code ptp_check_eos_events} 重试等属性到齐。
     * 这里照做，并用一个总预算兜住等待时长，避免把命令锁占太久。
     */
    private long readCanonProperty(int propertyCode, String label) throws Exception {
        drainEosEvents();
        Long cached = canonPropValues.get(propertyCode);
        if (cached != null) return cached;
        // 缓存里没有：请相机把该属性推一次（这条请求自己被拒也不影响后面的等待）。
        requestCanonProperty(propertyCode);
        String drainFailure = null;
        long deadline = System.currentTimeMillis() + CANON_PROP_PUSH_BUDGET_MS;
        for (int attempt = 0; attempt < CANON_PROP_PUSH_ATTEMPTS; attempt++) {
            String failure = drainEosEvents();
            if (failure != null) drainFailure = failure;
            cached = canonPropValues.get(propertyCode);
            if (cached != null) return cached;
            if (attempt + 1 >= CANON_PROP_PUSH_ATTEMPTS) break;
            if (System.currentTimeMillis() >= deadline) break;
            Thread.sleep(CANON_PROP_PUSH_INTERVAL_MS);
        }
        throw new IOException("相机没有推送 " + label + "（0x"
                + Integer.toHexString(propertyCode) + "）的当前值"
                + (drainFailure == null ? "" : "\n" + drainFailure));
    }

    /**
     * {@code 0x9127}：请相机把某属性的当前值推到事件队列。走普通事务（不是裸读），该请求自身
     * 不带数据段，相机通常只回纯 ResponseOK——故用 {@link #commandWithData} 并对「纯响应」这种
     * 正常情形放行（与 {@link #drainEosEvents()} 判空是同一种写法）。
     *
     * <p>这条请求是尽力而为：相机明确应答了（哪怕是 0x2005 之类的拒绝）说明通道正常，值也可能
     * 已经推来了，所以一律吞掉；真读不到由 {@link #readCanonProperty} 报统一的中文错误，
     * 不把裸响应码丢给用户。
     */
    private void requestCanonProperty(int propertyCode) {
        try {
            commandWithData(
                    CANON_REQUEST_DEVICE_PROP_VALUE,
                    transactionId++,
                    new long[]{propertyCode});
        } catch (PtpResponseException answered) {
            // 相机应答即可，拒绝不影响后面的等待。
        } catch (Exception ignored) {
            // 超时等失败同样不影响：值可能已经推到队列里，后面照常排空。
        }
    }

    /**
     * 读取对焦方式（{@code 0xD1BA EOS_LvAfSystem}）的当前编码值。取值路径与
     * {@link #readExposure} 相同：0xD1xx 只能从 0x9116 事件缓存的 {@code 0xC189} 取，
     * 缓存没有就补一次 0x9127。
     */
    @Override
    public synchronized long readFocusArea() throws Exception {
        ensureConnected();
        ensureCanonRemoteMode();
        return readCanonProperty(CANON_PROP_AF_METHOD, "对焦方式");
    }

    /**
     * 写入对焦方式（{@code 0xD1BA}，机上编码值）。序列与 {@link #writeExposure} 完全一致：
     * 先排空事件、停掉可能还在跑的对焦驱动，再经 0x9110 写入并对 0x2019 DeviceBusy 退避重试，
     * 重试仍失败就重建遥控态后补写一次。
     */
    @Override
    public synchronized void writeFocusArea(int value) throws Exception {
        ensureConnected();
        String drainFailure = drainEosEvents();
        cancelCanonAutofocus();
        int code = writeCanonPropertyRetrying(CANON_PROP_AF_METHOD, value & 0xffffL);
        if (code == RESPONSE_DEVICE_BUSY) {
            rebuildCanonStateAfterBusy();
            code = writeCanonProperty(CANON_PROP_AF_METHOD, value & 0xffffL);
        }
        if (code != RESPONSE_OK) {
            throw new IOException("对焦方式写入被相机拒绝（0x9110 属性 d1ba，值 0x"
                    + Integer.toHexString(value & 0xffff) + "）：" + hexCode(code)
                    + (drainFailure == null ? "" : "\n" + drainFailure), rejected(code));
        }
    }

    /**
     * 事件缓存里某个属性的允许值列表（0xC18A 枚举，保持相机顺序）；未知返回 {@code null}。
     * 返回副本，避免调用方改动缓存。
     */
    @Override
    public synchronized int[] supportedValues(int propertyCode) {
        int[] allowed = canonPropEnums.get(propertyCode);
        return allowed == null ? null : allowed.clone();
    }

    /**
     * P0 能力探测：把 {@link VendorProperty#PROBE_TARGETS} 这几个厂商属性的事实读出来——
     * 是否存在、当前值原始字节、{@code 0xC18A} 给不给允许值、写入是否被接受。
     *
     * <p>只读部分：每个属性先排空 {@code 0x9116} 事件队列，缓存里没有值就发一次
     * {@code 0x9127 RequestDevicePropValue} 催读，再按 10 次 × 50ms 重排（与
     * {@link #readCanonProperty} 是同一条路——libgphoto2 也用 10×50ms 等 EOS 推属性，
     * config.c:429-436）。
     *
     * <p>{@code verifyWrite=true} 时，对每个读到的属性再做一次**原值回写**：把刚读到的字节
     * 原样经 {@code 0x9110} 写回去，然后重新读一遍比对。写的是相机自己刚报的值（语义上
     * no-op），但它确实是一次真实写入，所以只由用户显式触发。这一步同时回答一个决定后续
     * 阶段可行性的问题：<b>{@code 0x9110} 是否接受超过 12 字节的载荷</b>——libgphoto2 没有
     * 先例（{@code ptp_canon_eos_setdevicepropvalue} 的默认分支固定写 12 字节，ptp.c:3951-3955，
     * 且要求属性有 descriptor，{@code 0xD134}/{@code 0xD13D} 都没有）。
     *
     * <p>单个属性读不到不算失败：记下原因继续下一个，最后一起汇报。
     */
    @Override
    public synchronized CapabilityReport probeVendorCapabilities(boolean verifyWrite)
            throws Exception {
        ensureConnected();
        ensureCanonRemoteMode();
        List<CapabilityReport.Entry> entries = new ArrayList<>();
        for (int property : VendorProperty.PROBE_TARGETS) {
            entries.add(probeVendorProperty(property, verifyWrite));
        }
        return new CapabilityReport(getCameraName(), entries, verifyWrite);
    }

    /**
     * 把当前事件缓存里**所有**被 {@code 0xC189} 报过的厂商属性打一份只读快照，供差分法反推
     * 取值语义与属性码（见 {@link VendorPropertySnapshot}）。纯读，不写相机。
     *
     * <p>机身改设置后事件会晚到几拍，所以排空之后再等一小会儿，重排几轮把迟到的事件收进来。
     */
    @Override
    public synchronized VendorPropertySnapshot snapshotVendorProperties() throws Exception {
        ensureConnected();
        ensureCanonRemoteMode();
        for (int round = 0; round < CANON_SNAPSHOT_SETTLE_ROUNDS; round++) {
            if (round > 0) sleepQuietly(CANON_SNAPSHOT_SETTLE_MS);
            drainEosEvents();
        }
        // 值事件与原始字节事件是两套缓存（有的属性只报了其中一种），取并集才不会漏条目。
        Map<Integer, VendorPropertySnapshot.Item> byCode = new LinkedHashMap<>();
        for (Map.Entry<Integer, byte[]> entry : canonPropRaw.entrySet()) {
            int code = entry.getKey();
            Long value = canonPropValues.get(code);
            byCode.put(code, new VendorPropertySnapshot.Item(
                    code, value == null ? VendorPropertySnapshot.UNSET : value, entry.getValue()));
        }
        for (Map.Entry<Integer, Long> entry : canonPropValues.entrySet()) {
            if (!byCode.containsKey(entry.getKey())) {
                byCode.put(entry.getKey(), new VendorPropertySnapshot.Item(
                        entry.getKey(), entry.getValue(), null));
            }
        }
        return new VendorPropertySnapshot(System.currentTimeMillis(), byCode);
    }

    /**
     * 读一个厂商属性的**原始值区域**（只读）。0xD134 / 0xD13D / 0xD208 这类值是 24 字节的不透明块，
     * 没有文档可查（libgphoto2 对这些属性一条字段定义都没有），所以只能把原始字节拿回来，
     * 由上层决定怎么解读——绝不在这里「按猜的结构解析」。
     *
     * <p><b>为什么这里用 {@code fresh=false}（缓存优先）</b>：{@code 0xD13D} 这类没有 descriptor
     * 的块，相机并不是每次 {@code 0x9127} 都会按需重推。真机实测：同一条链路上「能力探测」
     * （{@code fresh=false}，从 {@code 0xC189} 事件缓存取值）能拿到完整的 24 字节，而这里原先用
     * {@code fresh=true} 先把自己唯一的缓存删掉、再等相机重推，结果是既没等到推送、这一轮的
     * {@code 0x9116 GetEvent} 还超时（{@code SocketTimeoutException}），值就此永久丢失。
     * 缓存里的块来自 {@code SetEventMode(1)} 后的初始事件灌流或机身改设置时的 {@code 0xC189}，
     * 是相机自己给的原值；读取时先排空一轮把已排队的变化事件收进来，再取缓存，
     * 没有缓存才退化到 {@code 0x9127} 请相机推。
     *
     * @param propertyCode 见 {@link VendorProperty}
     * @throws IOException 缓存为空且相机在预算内没有推送该属性的值时
     */
    @Override
    public synchronized byte[] readVendorPropertyBlock(int propertyCode) throws Exception {
        ensureConnected();
        ensureCanonRemoteMode();
        return readCanonPropertyRaw(propertyCode, false);
    }

    /**
     * 读一个厂商属性允许值列表的**原始字节**（只读，来自 {@code 0xC18A} 事件缓存）。
     *
     * <p><b>为什么不在这里做 {@code 0x9127} 催读</b>：允许值列表不像当前值那样可以被
     * {@code 0x9127 RequestDevicePropValue} 催出来——它只在 {@code SetEventMode(1)} 之后的
     * 初始事件灌流（以及机身设置变化时）推一次。缓存为空就是「这次会话相机没给过」，
     * 再催一轮也只会白等，所以直接返回 {@code null} 让上层降级。
     *
     * @param propertyCode 见 {@link com.imagefetch.ptp.VendorProperty}
     */
    @Override
    public synchronized byte[] readVendorPropertyEnumsRaw(int propertyCode) throws Exception {
        ensureConnected();
        ensureCanonRemoteMode();
        drainEosEvents();
        byte[] cached = canonPropEnumRaw.get(propertyCode);
        return cached == null || cached.length == 0 ? null : cached.clone();
    }

    /**
     * 整体回写一个厂商属性的值区域（会真的写相机，只应由用户显式操作触发）。
     *
     * <p>写入前按项目规矩排空 {@code 0x9116} 并停掉可能还在跑的对焦，遇 {@code 0x2019} 退避重试
     * ——真机实测：这四个属性里除 0xD11D 外都接受 24 字节长载荷并回读一致。
     *
     * @return 相机响应码（{@code 0x2001} 为成功）
     */
    @Override
    public synchronized int writeVendorPropertyBlock(int propertyCode, byte[] block) throws Exception {
        ensureConnected();
        ensureCanonRemoteMode();
        drainEosEvents();
        cancelCanonAutofocus();
        int code = writeCanonPropertyBlobRetrying(propertyCode, block);
        if (code == RESPONSE_OK) {
            // 写成功后补一次「等一拍 + 排空」：写入本身不会让相机立刻把新值推回 0xC189，
            // 不补这一下，事件缓存里留的还是写前那份旧值，上层随后的回读核对（缓存优先）
            // 就会拿旧值去比，把「相机收下了」误报成「布局与设想不同」。
            sleepQuietly(CANON_WRITE_SETTLE_MS);
            drainEosEvents();
        }
        return code;
    }

    /** 探测单个属性：读（必须）→ 原值回写（可选）→ 重读比对（可选）。 */
    private CapabilityReport.Entry probeVendorProperty(int propertyCode, boolean verifyWrite) {
        byte[] current = null;
        String readFailure = null;
        try {
            current = readCanonPropertyRaw(propertyCode, false);
        } catch (Exception error) {
            readFailure = describe(error);
        }
        int[] allowed = canonPropEnums.get(propertyCode);
        if (!verifyWrite || current == null || current.length == 0) {
            return new CapabilityReport.Entry(propertyCode, current, readFailure, allowed,
                    CapabilityReport.WRITE_NOT_ATTEMPTED, null, null, null);
        }
        int code = CapabilityReport.WRITE_NOT_ATTEMPTED;
        String writeFailure = null;
        try {
            // 与 writeExposure 同一套前置：先排空 0x9116，再停掉可能还在跑的对焦——
            // 对焦没结束时 0x9110 一律回 0x2019（writeExposure 的注释里有实测记录）。
            drainEosEvents();
            cancelCanonAutofocus();
            code = writeCanonPropertyBlobRetrying(propertyCode, current);
        } catch (Exception error) {
            writeFailure = describe(error);
        }
        if (code != RESPONSE_OK) {
            return new CapabilityReport.Entry(propertyCode, current, readFailure, allowed,
                    code, writeFailure, null, null);
        }
        byte[] readBack = null;
        String readBackFailure = null;
        try {
            readBack = readCanonPropertyRaw(propertyCode, true);
        } catch (Exception error) {
            readBackFailure = describe(error);
        }
        return new CapabilityReport.Entry(propertyCode, current, readFailure, allowed,
                code, writeFailure, readBack, readBackFailure);
    }

    /**
     * 取一个厂商属性当前值的**原始字节**：先看事件缓存，没有就 {@code 0x9127} 催一次再重排。
     *
     * @param fresh {@code true} 时先丢掉该属性的缓存（回写验证后必须重读，否则拿到的还是旧值）
     */
    private byte[] readCanonPropertyRaw(int propertyCode, boolean fresh) throws Exception {
        if (fresh) {
            canonPropRaw.remove(propertyCode);
            canonPropValues.remove(propertyCode);
        }
        drainEosEvents();
        byte[] cached = canonPropRaw.get(propertyCode);
        if (cached != null && cached.length > 0) return cached.clone();
        requestCanonProperty(propertyCode);
        String drainFailure = null;
        long deadline = System.currentTimeMillis() + CANON_PROBE_READ_BUDGET_MS;
        for (int attempt = 0; attempt < CANON_PROP_PUSH_ATTEMPTS; attempt++) {
            String failure = drainEosEvents();
            if (failure != null) drainFailure = failure;
            cached = canonPropRaw.get(propertyCode);
            if (cached != null && cached.length > 0) return cached.clone();
            if (attempt + 1 >= CANON_PROP_PUSH_ATTEMPTS) break;
            if (System.currentTimeMillis() >= deadline) break;
            Thread.sleep(CANON_PROP_PUSH_INTERVAL_MS);
        }
        throw new IOException("相机没有推送 " + VendorProperty.label(propertyCode) + " 的当前值"
                + (drainFailure == null ? "" : "\n" + drainFailure));
    }

    /**
     * {@code 0x9110} 写厂商属性，值区域可能超过 4 字节。数据段形态与
     * {@code ptp_canon_eos_setdevicepropvalue} 完全一致（ptp.c:3980-3983）：
     * {@code [u32 总长度（含本字段与属性码）][u32 属性码][值区域…]}，总长度 = {@code 8 + 值区域长度}。
     * libgphoto2 的 ImageFormat/CustomFuncEx 分支正是这么写的（{@code size = 8 + pack(...)}，
     * ptp.c:3937-3948），默认分支则退化成 {@code 8 + 4 = 12} 字节——与本工程既有的
     * {@link #writeCanonProperty} 一致。
     */
    private int writeCanonPropertyBlob(int propertyCode, byte[] region) throws Exception {
        if (region.length > 4) {
            return commandSendData(
                    CANON_SET_DEVICE_PROP_VALUE_EX,
                    transactionId++,
                    new long[0],
                    canonPropValueExPayload(propertyCode, region));
        }
        if (region.length >= 4) return writeCanonProperty(propertyCode, u32(region, 0));
        if (region.length >= 2) return writeCanonProperty(propertyCode, u16(region, 0));
        return writeCanonProperty(propertyCode, region.length == 1 ? region[0] & 0xffL : 0);
    }

    /**
     * {@link #writeCanonPropertyBlob} 的重试版：遇 {@code 0x2019 DeviceBusy} 退避重试（重试前先排空
     * 事件队列）；退避完仍被拒时重建遥控/事件态再补写一次。处置与 {@link #writeExposure} 一致——
     * 真机上 0x2019 多数是「瞬时忙」，但也可能是相机已经不在遥控态，两种情形要分开兜。
     */
    private int writeCanonPropertyBlobRetrying(int propertyCode, byte[] region) throws Exception {
        int code = RESPONSE_DEVICE_BUSY;
        for (int attempt = 0;
                attempt <= CANON_PROP_WRITE_BUSY_RETRIES && code == RESPONSE_DEVICE_BUSY;
                attempt++) {
            if (attempt > 0) {
                sleepQuietly(CANON_RELEASE_BUSY_BACKOFF_MS * attempt);
                drainEosEvents();
            }
            code = writeCanonPropertyBlob(propertyCode, region);
        }
        if (code == RESPONSE_DEVICE_BUSY) {
            rebuildCanonStateAfterBusy();
            code = writeCanonPropertyBlob(propertyCode, region);
        }
        return code;
    }

    /**
     * {@code 0x9110} 长载荷的数据段：{@code [u32 总长度 = 8 + 值区域长度][u32 属性码][值区域…]}。
     * 单独拆出来是为了能在 JVM 单测里把这段字节钉死——长度字段写错在真机上只会表现为
     * 「相机静默忽略」或 {@code 0x2006 ParameterNotSupported}，很难反推。
     */
    static byte[] canonPropValueExPayload(int propertyCode, byte[] region) {
        ByteWriter payload = new ByteWriter();
        payload.u32(8 + region.length);
        payload.u32(propertyCode);
        payload.bytes(region);
        return payload.data();
    }

    /** 0x9110 写厂商属性，返回响应码（由调用方决定哪些码可以容忍）。 */
    private int writeCanonProperty(int propertyCode, long value) throws Exception {
        ByteWriter payload = new ByteWriter();
        payload.u32(12);
        payload.u32(propertyCode);
        payload.u32(value);
        return commandSendData(
                CANON_SET_DEVICE_PROP_VALUE_EX, transactionId++, new long[0], payload.data());
    }

    /**
     * 写厂商属性，遇 {@code 0x2019 DeviceBusy} 退避重试后返回最后一次响应码。
     * 取景中相机忙碌是常态，重试前先排空事件队列，给相机回到可写状态的机会。
     */
    private int writeCanonPropertyRetrying(int propertyCode, long value) throws Exception {
        int code = RESPONSE_DEVICE_BUSY;
        for (int attempt = 0;
                attempt <= CANON_PROP_WRITE_BUSY_RETRIES && code == RESPONSE_DEVICE_BUSY;
                attempt++) {
            if (attempt > 0) {
                sleepQuietly(CANON_RELEASE_BUSY_BACKOFF_MS * attempt);
                drainEosEvents();
            }
            code = writeCanonProperty(propertyCode, value);
        }
        return code;
    }

    /**
     * 相机「不听 0x9110」时的自愈：重进遥控/事件态，再把取景属性写回我们最后要求的样子。
     *
     * <p>触发场景是实测出来的：按过一次自动对焦（{@code 0x9154}）之后，0x9110 写入开始恒回
     * {@code 0x2019}，而且此后一直失败，只有关面板重开或重连才恢复。那两步的共同点正是把
     * {@link #canonRemoteModeReady} 复位后重发一遍遥控态与取景属性——关面板走
     * {@link #setLiveViewEnabled(boolean, LiveViewSize)} 的 false 分支，它末尾就会把这个标志
     * 复位。这里做同一件事，省掉用户手动重开面板。
     *
     * <p>只在写入已经失败之后才走这条路，正常路径不额外发命令：早先「每次调用都重发
     * SetRemoteMode/SetEventMode」的写法会把相机反复拽进事件模式，反而打断 0x9110 写入。
     */
    private void rebuildCanonStateAfterBusy() {
        drainEosEvents();
        canonRemoteModeReady = false;
        try {
            ensureCanonRemoteMode();
        } catch (Exception ignored) {
            // 重建失败不改判定：调用方紧随其后的那次补写会给出最终响应码。
        }
        try {
            if (lastEvfModeValue >= 0) writeCanonProperty(CANON_PROP_EVF_MODE, lastEvfModeValue);
            if (lastEvfOutputValue >= 0) {
                writeCanonProperty(CANON_PROP_EVF_OUTPUT_DEVICE, lastEvfOutputValue);
            }
        } catch (Exception ignored) {
            // 同上：写不进去由补写那次给出结论。
        }
    }

    /** 0x911D 保活：不进实时取景时相机会自动关机。失败不致命，忽略。 */
    private void keepDeviceOn() {
        try {
            command(CANON_KEEP_DEVICE_ON, transactionId++, new long[0]);
        } catch (Exception ignored) {
            // 保活失败只影响相机自动关机时间，不影响取景本身。
        }
    }

    /**
     * data-out 事务（DataPhaseInfo=2）：OperationRequest → StartData(9) → EndData(12) → 响应。
     * 数据量小于一个包，直接全部放在 EndData 里（CIPA DC-X005 允许），不发 Data(10)。
     */
    private int commandSendData(
            int operation, int transaction, long[] parameters, byte[] payload) throws Exception {
        ensureConnected();
        long expected = transaction & 0xffff_ffffL;
        ByteWriter request = new ByteWriter();
        request.u32(2);
        request.u16(operation);
        request.u32(transaction);
        for (long parameter : parameters) request.u32(parameter);
        writeCommandPacket(PACKET_COMMAND_REQUEST, request.data());

        ByteWriter start = new ByteWriter();
        start.u32(transaction);
        start.u64(payload.length);
        writeCommandPacket(PACKET_START_DATA, start.data());

        ByteWriter end = new ByteWriter();
        end.u32(transaction);
        end.bytes(payload);
        writeCommandPacket(PACKET_END_DATA, end.data());

        Packet response = readResponseFor(expected);
        if (response.type != PACKET_COMMAND_RESPONSE
                || response.data.length < 14
                || u32(response.data, 10) != expected) {
            throw commandChannelFailed(new IOException(String.format(
                    Locale.ROOT,
                    "相机返回了无效的 PTP/IP 响应（op=0x%04X type=%d 期望事务=%d）",
                    operation,
                    response.type,
                    expected)));
        }
        return u16(response.data, 8);
    }

    /**
     * 从 0x9153 的数据段里取出 JPEG。
     *
     * <p>数据段是 TLV 块序列：每块 {@code [u32 len][u32 type][payload]}，{@code len} 含 8 字节头，
     * 载荷从块内 +8 起、长 {@code len-8}；type 1/11 是常规 JPEG、9 是 Movie 模式下的帧
     * （libgphoto2 library.c:3685-3743）。块头读不通时退化为整段 FF D8…FF D9 扫描，
     * 这样即使块头字节序判断有误也能保住这一帧。
     *
     * @return JPEG 字节；数据段里没有 JPEG 时返回 {@code null}
     */
    static byte[] extractLiveViewJpeg(byte[] data) {
        if (data == null || data.length < 8) return null;
        int offset = 0;
        while (offset + 8 <= data.length) {
            long length = u32(data, offset);
            int type = (int) u32(data, offset + 4);
            if (length < 8 || offset + length > data.length) break;
            if (type == CANON_LV_SEG_JPEG || type == CANON_LV_SEG_JPEG_MOVIE
                    || type == CANON_LV_SEG_JPEG_ALT) {
                byte[] jpeg = new byte[(int) length - 8];
                System.arraycopy(data, offset + 8, jpeg, 0, jpeg.length);
                byte[] scanned = scanJpeg(jpeg);
                if (scanned != null) return scanned;
            }
            offset += (int) length;
        }
        return scanJpeg(data);
    }

    /** 在字节流里按 JPEG 标记（FF D8 起、FF D9 止）截出一张图；找不到返回 {@code null}。 */
    private static byte[] scanJpeg(byte[] data) {
        int start = -1;
        for (int i = 0; i + 1 < data.length; i++) {
            if (start < 0) {
                if ((data[i] & 0xff) == 0xff && (data[i + 1] & 0xff) == 0xd8) start = i;
            } else if ((data[i] & 0xff) == 0xff && (data[i + 1] & 0xff) == 0xd9) {
                byte[] jpeg = new byte[i + 2 - start];
                System.arraycopy(data, start, jpeg, 0, jpeg.length);
                return jpeg;
            }
        }
        return null;
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
    public synchronized CameraStorage.Page listStoragePage(
            int pageSize, int pageIndex, boolean jpegOnly) throws Exception {
        ensureConnected();
        return StorageBrowser.loadPage(this::invokeOperation, pageSize, pageIndex, jpegOnly, pageCache);
    }

    @Override
    public void invalidateStorageCache() {
        synchronized (pageCache) {
            pageCache.clear();
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
     * 删除卡内对象（标准 {@code 0x100B DeleteObject}，参数 handle + objectFormatCode=0）。
     * 任意格式都能删，与「只下载 JPEG」的下载限制无关。
     *
     * <p>动手前先排空 EOS 事件队列：{@code SetEventMode(1)} 后事件积压会让后续命令统一回
     * {@code 0x2019}。排空失败不阻塞删除（它不是厂商命令，事件脏队列只会让相机回忙，
     * 不排空时更容易撞上），因此这里忽略返回值。
     */
    @Override
    public synchronized void deleteObject(long handle) throws Exception {
        ensureConnected();
        drainEosEvents();
        int code = command(OPERATION_DELETE_OBJECT, transactionId++, new long[]{handle, 0});
        if (code != RESPONSE_OK) throw rejected(code);
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
        long expected = transaction & 0xffff_ffffL;
        ByteWriter request = new ByteWriter();
        request.u32(1);
        request.u16(operation);
        request.u32(transaction);
        for (long parameter : parameters) request.u32(parameter);
        writeCommandPacket(PACKET_COMMAND_REQUEST, request.data());

        Packet first = readResponseFor(expected);
        if (first.type == PACKET_COMMAND_RESPONSE) {
            if (first.data.length < 14 || u32(first.data, 10) != expected) {
                throw commandChannelFailed(
                        new IOException("相机返回了无效的 PTP/IP 响应"));
            }
            throw rejected(u16(first.data, 8));
        }
        if (first.type != PACKET_START_DATA || first.data.length < 20
                || u32(first.data, 8) != expected) {
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
            Packet packet = readResponseFor(expected);
            if ((packet.type != PACKET_DATA && packet.type != PACKET_END_DATA)
                    || packet.data.length < 12
                    || u32(packet.data, 8) != expected) {
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
        Packet response = readResponseFor(expected);
        if (response.type != PACKET_COMMAND_RESPONSE
                || response.data.length < 14
                || u32(response.data, 10) != expected) {
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
            String[] texts = deviceInfoManufacturerAndModel(info);
            if (texts != null) {
                String manufacturer = texts[0];
                if (manufacturer != null && !manufacturer.trim().isEmpty()) {
                    resolved = vendorForManufacturer(manufacturer, nameBased);
                }
                if (texts[1] != null) cameraModel = texts[1];
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

    /**
     * 取一次 {@code GetDeviceInfo} 里的型号串（判 EOS M 系列要用）。已取到或读不到就保持原值：
     * 失败时退回握手名称判断，不影响快门尝试。
     */
    private void ensureCameraModel() {
        if (cameraModel != null && !cameraModel.isEmpty()) return;
        // 相机忙时会回 0x2019 让我们读不到机型，而判定落空的代价是快门编码选错、
        // 全按恒回 0x2019——症状和「相机忙」一模一样，所以这里退避多读几次。
        for (int attempt = 1; attempt <= CANON_MODEL_READ_ATTEMPTS; attempt++) {
            try {
                byte[] info = commandWithData(
                        OPERATION_GET_DEVICE_INFO, transactionId++, new long[0]);
                String[] texts = deviceInfoManufacturerAndModel(info);
                if (texts != null) {
                    if (texts[1] != null && !texts[1].isEmpty()) cameraModel = texts[1];
                    if (texts[0] != null && !texts[0].trim().isEmpty()) {
                        vendor = vendorForManufacturer(texts[0], vendor);
                    }
                    if (cameraModel != null && !cameraModel.isEmpty()) return;
                }
            } catch (Exception ignored) {
                // 落到下面退避重试。
            }
            if (attempt < CANON_MODEL_READ_ATTEMPTS) {
                sleepQuietly(CANON_RELEASE_BUSY_BACKOFF_MS * attempt);
            }
        }
        // 都读不到：isCanonEosMSeries() 退回握手名称，快门另有实测回退兜底。
    }

    /**
     * GetDeviceInfo 数据段里的 {@code Manufacturer} 与 {@code Model}（PTP STR + AUINT16），
     * 顺序遵守 ISO 15740，见 ptp-pack.c 的 DeviceInfo 解包。解析不出返回 {@code null}。
     */
    private static String[] deviceInfoManufacturerAndModel(byte[] data) {
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
        String manufacturer = readPtpString(data, offset);
        String model = manufacturer == null ? null : readPtpString(data, offset);
        return new String[]{manufacturer, model};
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

    /**
     * 一次事务里最多丢弃多少个「过期响应」。相机在某个命令（实测是 {@code 0x9154 EOS_DoAf}）
     * 上迟到应答时，那个包会在**下一次**事务的读取里被先读到；libgphoto2 的做法是把这种
     * 「事务号小于期望值」的回包当作上一次的迟到响应丢弃、继续读真正的应答
     * （ptp.c:276-286 "suspecting old reply" → continue），并不判定通道故障。
     * 这里同样只跳过、不判死——一旦把命令通道判死，{@link #ensureConnected()} 会让之后
     * **所有**遥控命令立即抛错，只能靠重连恢复，正是「按过自动对焦后一切都阻塞」的成因。
     */
    private static final int STALE_REPLY_MAX_SKIPS = 8;

    private int command(int operation, int transaction, long[] parameters)
            throws Exception {
        ensureConnected();
        long expected = transaction & 0xffff_ffffL;
        ByteWriter request = new ByteWriter();
        request.u32(1);
        request.u16(operation);
        request.u32(transaction);
        for (long parameter : parameters) request.u32(parameter);
        writeCommandPacket(PACKET_COMMAND_REQUEST, request.data());
        Packet response = readResponseFor(expected);
        if (response.type != PACKET_COMMAND_RESPONSE
                || response.data.length < 14
                || u32(response.data, 10) != expected) {
            // 带上实收内容，便于区分「相机回错事务」与「命令通道串包」。
            throw commandChannelFailed(new IOException(String.format(
                    Locale.ROOT,
                    "相机返回了无效的 PTP/IP 响应（op=0x%04X type=%d 实收事务=%s 期望=%d）",
                    operation,
                    response.type,
                    response.data.length >= 14
                            ? String.valueOf(u32(response.data, 10))
                            : "缺失",
                    expected)));
        }
        return u16(response.data, 8);
    }

    /**
     * 读一包，并丢弃事务号**小于** {@code expected} 的「过期响应」后返回。
     *
     * <p>丢弃的是上一次事务的迟到应答（典型场景：{@code 0x9154} 对焦命令的应答迟到，
     * 在下一次读数时先到达）。只有连续丢弃超过 {@link #STALE_REPLY_MAX_SKIPS} 个仍读不到
     * 本次应答，才认为通道真的失步——这时才判故障。
     */
    private Packet readResponseFor(long expected) throws IOException {
        for (int skip = 0; skip < STALE_REPLY_MAX_SKIPS; skip++) {
            Packet packet = readCommandPacket();
            if (!isStaleReply(packet, expected)) return packet;
        }
        throw commandChannelFailed(new IOException(
                "PTP/IP 命令通道连续收到 " + STALE_REPLY_MAX_SKIPS + " 个过期响应，已失步"));
    }

    /** 该包是否为「上一次事务的迟到应答」：能解出事务号且严格小于本次期望值。 */
    private static boolean isStaleReply(Packet packet, long expected) {
        long actual = packetTransaction(packet);
        return actual >= 0 && actual < expected;
    }

    /** 取包内事务号；包型或长度对不上时返回 -1（交由调用方按原逻辑判错）。 */
    private static long packetTransaction(Packet packet) {
        switch (packet.type) {
            case PACKET_COMMAND_RESPONSE:
                return packet.data.length >= 14 ? u32(packet.data, 10) : -1;
            case PACKET_START_DATA:
            case PACKET_DATA:
            case PACKET_END_DATA:
                return packet.data.length >= 12 ? u32(packet.data, 8) : -1;
            default:
                return -1;
        }
    }

    private byte[] commandWithData(int operation, int transaction, long[] parameters)
            throws Exception {
        ensureConnected();
        long expected = transaction & 0xffff_ffffL;
        ByteWriter request = new ByteWriter();
        request.u32(1);
        request.u16(operation);
        request.u32(transaction);
        for (long parameter : parameters) request.u32(parameter);
        writeCommandPacket(PACKET_COMMAND_REQUEST, request.data());

        Packet first = readResponseFor(expected);
        if (first.type == PACKET_COMMAND_RESPONSE) {
            if (first.data.length < 14 || u32(first.data, 10) != expected) {
                throw commandChannelFailed(
                        new IOException("相机返回了无效的 PTP/IP 响应"));
            }
            throw rejected(u16(first.data, 8));
        }
        if (first.type != PACKET_START_DATA || first.data.length < 20
                || u32(first.data, 8) != expected) {
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
            Packet packet = readResponseFor(expected);
            if ((packet.type != PACKET_DATA && packet.type != PACKET_END_DATA)
                    || packet.data.length < 12
                    || u32(packet.data, 8) != expected) {
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
        Packet response = readResponseFor(expected);
        if (response.type != PACKET_COMMAND_RESPONSE
                || response.data.length < 14
                || u32(response.data, 10) != expected) {
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
        // 计数与业务监听器无关：没有传输任务时监听器也为 null，但快门路径仍要能靠它判断
        // 「这次全按有没有拍出东西」。
        objectAddedSeq++;
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
        } catch (SocketTimeoutException timeout) {
            // 「一个字节都没等到」= 本次事务相机没应答（实测 0x9154 EOS_DoAf 期间相机可能
            // 不回或迟到回）。只让本次操作失败，**不**把命令通道判死：判死后
            // ensureConnected() 会让之后所有遥控命令立即抛错，只能靠重连恢复。
            // libgphoto2 对无响应同样只返回错误、不终止会话（ptp.c:264-300）。
            // 注意：读包中途超时走的是 readExactly 包装后的 IOException，仍按故障处理。
            throw new IOException("相机在 PTP/IP 事务上超时未应答", timeout);
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
        cameraModel = "";
        vendor = CameraVendor.UNKNOWN;
        canonRemoteModeReady = false;
        // 机型记忆也要清：真机可能换台相机连（EOS M ↔ 普通 EOS），带着上一台的编码会让
        // 新会话一路走错分支——症状正是「快门回 OK 却没拍到」。下一会话重新按机型串判定。
        canonEosMEncodingLearned = false;
        // 新会话下相机自己的对焦状态已重置，别把上一次的「可能还在对焦」带过来。
        canonAfMaybeRunning = false;
        pageCache.clear();
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
