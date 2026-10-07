package com.imagefetch.transport;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.CanonExposure;
import com.imagefetch.ptp.LiveViewSize;

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
     */
    CameraStorage.Page listStoragePage(int pageSize, int pageIndex) throws Exception;

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
     * 把取景画面里的一个点设为对焦框（Canon {@code 0x915B EOS_TouchAfPosition}，
     * <b>不触发</b>对焦）。{@code x}/{@code y} 为取景帧像素坐标。不支持的传输实现保持默认。
     */
    default void touchAfPosition(int x, int y) throws Exception {
        throw new UnsupportedOperationException("当前连接方式不支持移动对焦框");
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
