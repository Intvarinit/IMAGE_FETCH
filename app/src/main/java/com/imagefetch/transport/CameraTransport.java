package com.imagefetch.transport;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.CanonExposure;
import com.imagefetch.ptp.CapabilityReport;
import com.imagefetch.ptp.LiveViewSize;
import com.imagefetch.ptp.VendorPropertySnapshot;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 相机传输抽象。首版只有 Wi‑Fi/PTP‑IP 实现（{@link PtpIpTransport}）；
 * USB Host PTP 实现留待计划阶段 4 接入，接口保持不变。
 */
public interface CameraTransport extends Closeable {
    /** 相机上报新增对象（标准 PTP ObjectAdded 0x4002，或后续厂商等价事件）。 */
    interface ObjectEventListener {
        void onObjectAdded(long storageId, long objectHandle);
    }

    /** 流式下载进度；total 为 -1 表示相机未声明长度。 */
    interface ProgressListener {
        void onProgress(long received, long total);
    }

    /**
     * 建立会话并返回相机名。
     * Wi‑Fi/PTP‑IP 使用 host/port；USB Host PTP 忽略两者，
     * 连接在枚举阶段已选定的 USB 相机。
     */
    String connect(String host, int port) throws Exception;

    boolean isConnected();

    String getCameraName();

    /**
     * 枚举存储卷与对象（含嵌套关联对象）。
     *
     * @param maxItems 每个目录最多取多少张最新图片（按句柄单调递增假设）；
     *                 &lt;= 0 表示不限、整卡枚举。
     */
    CameraStorage.Snapshot listStorage(int maxItems) throws Exception;

    /**
     * 快速分页：只列文件句柄（浅层目录探测）并只对当前页取 ObjectInfo，
     * 大卡翻页无需整卡枚举。实现内部缓存句柄列表，翻页复用。
     *
     * @param jpegOnly true 时按 JPG 分页：每页 {@code pageSize} 张 JPG（不掺 RAW/视频），
     *                 总数也是 JPG 张数。JPG 与否只能从 ObjectInfo 判断，因此实现按需
     *                 逐个筛查并缓存结果——翻到第 N 页才扫到第 N 页所需的量。
     */
    CameraStorage.Page listStoragePage(int pageSize, int pageIndex, boolean jpegOnly)
            throws Exception;

    /** 存储卡结构变化后（拍摄、删除、换卡）清掉句柄缓存。 */
    default void invalidateStorageCache() {
        // 默认无缓存。
    }

    byte[] getStorageThumbnail(long handle) throws Exception;

    /**
     * 流式下载原始对象到 output（分块写出，不整文件载入内存），
     * 返回实际字节数；调用方负责与 ObjectInfo 声明长度核对。
     */
    long downloadStorageObject(long handle, OutputStream output, ProgressListener progress)
            throws Exception;

    /**
     * 删除相机存储卡上的一个对象（标准 PTP {@code DeleteObject 0x100B}，
     * 参数为对象句柄 + objectFormatCode=0）。删除与下载不同，<b>任意格式</b>都可删。
     * 不支持的传输实现保持默认（抛 UnsupportedOperationException）。
     */
    default void deleteObject(long handle) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持删除相机文件");
    }

    /**
     * 触发一次相机快门。
     * 返回可直接 GetObject 的对象句柄；返回空数组表示图像已写入存储卡，
     * 由 {@code NewPhotoResolver} 通过句柄差集发现（PTP/IP 路径）。
     * Nikon USB 的 SDRAM 拍摄会把图像放入机身内存，此时直接返回其句柄。
     */
    long[] capture() throws Exception;

    /** 是否支持 event 通道心跳探测（PTP/IP 支持；USB 无事件通道，返回 false）。 */
    default boolean supportsHeartbeat() {
        return false;
    }

    /**
     * 开关实时取景预览。Canon EOS 走 0x9110 写 EVFMode/EVFOutputDevice 并 0x911D 保活；
     * {@code size} 写入 0xD1B0 的输出位，决定相机推给 App 的帧分辨率（{@link LiveViewSize}）。
     * 关闭时还会把机身屏重新点亮（0x9114 SetRemoteMode(1)）。
     * 不支持的传输实现保持默认（抛 UnsupportedOperationException）。
     */
    default void setLiveViewEnabled(boolean enabled, LiveViewSize size) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持实时取景");
    }

    /**
     * 只为「拍摄」临时退出取景：把取景输出彻底关掉，不做面板关闭那套交还机身的收尾，
     * 拍完由调用方恢复取景。全按快门要求画面上游真的停住，否则会被拒 {@code 0x2019}。
     * 默认实现退化为 {@link #setLiveViewEnabled(boolean, LiveViewSize)}。
     */
    default void pauseLiveViewForCapture(LiveViewSize size) throws Exception {
        setLiveViewEnabled(false, size);
    }

    /**
     * 半按再松开快门以唤醒相机的取景管线（不拍照）。取景长时间收不到帧时由上层调用；
     * 不支持的传输实现保持默认（抛 UnsupportedOperationException）。
     */
    default void wakeForLiveView() throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持实时取景");
    }

    /**
     * 取一帧实时取景 JPEG。
     * 相机这一帧还没准备好（ObjectNotReady / DeviceBusy）时返回 {@code null}，
     * 上层退避后重试即可，不要当成连接故障。
     */
    default byte[] liveViewFrame() throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持实时取景");
    }

    /**
     * 触发一次单次自动对焦（Canon EOS {@code 0x9154 EOS_DoAf}）。对焦由相机完成，本方法
     * 在相机返回响应后即返回。需要取景已开启。不支持的传输实现保持默认。
     */
    default void autofocus() throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持自动对焦");
    }

    /** 写入曝光参数；{@code value} 是机上编码值，取自 {@link CanonExposure.entries}。 */
    default void writeExposure(CanonExposure.Setting setting, int value) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持曝光参数调整");
    }

    /**
     * 读取曝光参数的当前编码值（与 {@link #writeExposure} 同一套
     * {@link CanonExposure#entries} 表值）；交给 {@link CanonExposure#indexOfValue} 换算成档位。
     */
    default long readExposure(CanonExposure.Setting setting) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持曝光参数调整");
    }

    /**
     * 相机事件缓存里某个厂商属性的允许值列表（Canon {@code 0xC18A} 枚举，保持相机给的顺序）；
     * 未知返回 {@code null}。界面据此把可选档位收窄到机身真正支持的子集。
     */
    default int[] supportedValues(int propertyCode) {
        return null;
    }

    /**
     * P0 能力探测：对 {@link com.imagefetch.ptp.VendorProperty#PROBE_TARGETS} 里的厂商属性逐个
     * 取当前值原始字节，并（可选）做一次「原值回写」验证可写性。只读部分可以随时跑；
     * {@code verifyWrite=true} 会真的写相机，必须由用户显式触发。不支持的传输实现保持默认。
     */
    default CapabilityReport probeVendorCapabilities(boolean verifyWrite) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持厂商属性能力探测");
    }

    /**
     * 取一份「相机上报过的所有厂商属性」的只读快照，用于机身改设置前后做差分比对。
     * 纯读，不写相机。不支持的传输实现保持默认。
     */
    default VendorPropertySnapshot snapshotVendorProperties() throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持厂商属性快照");
    }

    /**
     * 读一个厂商属性的原始值区域（只读）。用于 0xD134/0xD13D/0xD208 这类无文档的 24 字节块。
     */
    default byte[] readVendorPropertyBlock(int propertyCode) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持读取厂商属性值区域");
    }

    /**
     * 读一个厂商属性允许值列表的**原始字节**（只读）。给「列表项不是固定 4 字节 u16」的属性用：
     * 画质 {@code 0xD120 EOS_ImageFormat} 的每一项是变长 u32 序列，只能拿原始字节交给
     * {@link com.imagefetch.ptp.CanonImageQuality} 解析；相机本次会话没上报过该列表时返回
     * {@code null}（不支持的传输实现保持默认）。
     */
    default byte[] readVendorPropertyEnumsRaw(int propertyCode) throws Exception {
        return null;
    }

    /**
     * 整体回写一个厂商属性的值区域（会真的写相机）。返回相机响应码。
     */
    default int writeVendorPropertyBlock(int propertyCode, byte[] block) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持写入厂商属性值区域");
    }

    /**
     * 读取对焦方式（Canon {@code 0xD1BA EOS_LvAfSystem}）的当前编码值。与
     * {@link #readExposure} 同属 0xD1xx 一族，只能从事件缓存取。不支持的传输实现保持默认。
     */
    default long readFocusArea() throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持对焦方式调整");
    }

    /** 写入对焦方式（机上编码值）。不支持的传输实现保持默认。 */
    default void writeFocusArea(int value) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持对焦方式调整");
    }

    /**
     * 读取白平衡（Canon {@code 0xD109 EOS_WhiteBalance}）的当前编码值，
     * 与 {@link #readFocusArea()} 同属 0xD1xx 一族。不支持的传输实现保持默认。
     */
    default long readWhiteBalance() throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持白平衡调整");
    }

    /** 写入白平衡（机上编码值）。不支持的传输实现保持默认。 */
    default void writeWhiteBalance(int value) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持白平衡调整");
    }

    /**
     * 读取色温（Canon {@code 0xD10A EOS_ColorTemperature}）的当前值，单位 K，普通 u32 标量。
     * 不支持的传输实现保持默认。
     */
    default long readColorTemperature() throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持色温调整");
    }

    /** 写入色温（单位 K）。不支持的传输实现保持默认。 */
    default void writeColorTemperature(int kelvin) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持色温调整");
    }

    /**
     * 把取景画面里的一个点设为对焦框（Canon {@code 0x915B EOS_TouchAfPosition}，
     * <b>不触发</b>对焦）。{@code x}/{@code y} 是<b>感光器像素</b>坐标
     * （可先用 {@link #liveViewSensorSize()} 把取景帧像素换算过来）。
     * 不支持的传输实现保持默认。
     */
    default void touchAfPosition(int x, int y) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持移动对焦框");
    }

    /**
     * 先按感光器像素坐标移动对焦框，再触发一次对焦：让「自动对焦」落在用户点选的那一点，
     * 而不是相机当前的对焦点。默认实现退化为 {@link #autofocus()}（忽略坐标）。
     * 不支持的传输实现保持默认。
     */
    default void autofocusAt(int x, int y) throws Exception {
        autofocus();
    }

    /**
     * 最近一帧取景载荷里上报的感光器分辨率（{@code [宽, 高]}），没上报过返回 {@code null}。
     * 用于把取景帧像素坐标换算成 {@link #touchAfPosition} 需要的感光器像素坐标。
     * 不支持的传输实现保持默认。
     */
    default int[] liveViewSensorSize() {
        return null;
    }

    /**
     * 最近一帧取景载荷里出现的 TLV 段类型列表（逗号分隔，诊断用）；没收到过返回 {@code null}。
     * 当 {@link #liveViewSensorSize()} 取不到时，用它排查相机是否压根没推分辨率段。
     */
    default String liveViewSegmentTypes() {
        return null;
    }

    /**
     * 最近一次 {@link #capture()} 的厂商快门逐步诊断（每步响应码与应答载荷）。
     * 用于「快门回 OK 但相机没拍照」这类只能靠响应码判断的场景；无诊断时返回空串。
     */
    default String captureDiagnostics() {
        return "";
    }

    /** 发布“已连接”状态前的双通道健康屏障。 */
    void assertHealthy() throws IOException;

    /** 立即打断阻塞中的底层读写并释放句柄；可从任意线程调用（不等实例锁）。 */
    default void abortTransport() {
        // 默认无操作：没有需要打断的阻塞 IO 的实现可以忽略。
    }

    void setObjectEventListener(ObjectEventListener listener);
}
