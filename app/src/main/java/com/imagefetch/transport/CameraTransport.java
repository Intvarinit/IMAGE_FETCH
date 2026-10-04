package com.imagefetch.transport;

import com.imagefetch.ptp.CameraStorage;

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

    /** 发布“已连接”状态前的双通道健康屏障。 */
    void assertHealthy() throws IOException;

    /** 立即打断阻塞中的底层读写并释放句柄；可从任意线程调用（不等实例锁）。 */
    default void abortTransport() {
        // 默认无操作：没有需要打断的阻塞 IO 的实现可以忽略。
    }

    void setObjectEventListener(ObjectEventListener listener);
}
