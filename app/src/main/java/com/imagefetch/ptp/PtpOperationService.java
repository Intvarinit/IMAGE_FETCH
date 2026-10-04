package com.imagefetch.ptp;

import com.imagefetch.transport.CameraTransport;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 会话与操作的单一入口：连接、拍摄、存储卷/对象枚举、对象下载。
 * 上层（NewPhotoResolver / PhotoTransferWorker / 协调器）只依赖本类，
 * 不直接接触 transport，便于后续接入 USB Host PTP 实现。
 */
public final class PtpOperationService {
    private final CameraTransport transport;

    public PtpOperationService(CameraTransport transport) {
        this.transport = transport;
    }

    public CameraTransport transport() {
        return transport;
    }

    public String connect(String host, int port) throws Exception {
        return transport.connect(host, port);
    }

    public void disconnect() {
        try {
            transport.close();
        } catch (IOException ignored) {
            // 关闭阶段的 IO 异常不影响已释放的会话状态。
        }
    }

    public boolean isConnected() {
        return transport.isConnected();
    }

    public String cameraName() {
        return transport.getCameraName();
    }

    /** 会话内相机身份，用于对象去重键（与文件名/时间无关）。 */
    public String cameraIdentity() {
        return transport.getCameraName();
    }

    /** 整卡枚举（不限张数）。 */
    public CameraStorage.Snapshot snapshot() throws Exception {
        return transport.listStorage(0);
    }

    /** 枚举存储卡对象；maxItems &gt; 0 时每目录只取最新的 maxItems 张。 */
    public CameraStorage.Snapshot snapshot(int maxItems) throws Exception {
        return transport.listStorage(maxItems);
    }

    /** 快速分页：只对当前页取 ObjectInfo，翻页复用句柄缓存。 */
    public CameraStorage.Page page(int pageSize, int pageIndex) throws Exception {
        return transport.listStoragePage(pageSize, pageIndex);
    }

    /** 存储卡结构变化后清掉句柄缓存。 */
    public void invalidateStorageCache() {
        transport.invalidateStorageCache();
    }

    public byte[] thumbnail(long handle) throws Exception {
        return transport.getStorageThumbnail(handle);
    }

    public long[] capture() throws Exception {
        return transport.capture();
    }

    public long download(
            long handle,
            OutputStream output,
            CameraTransport.ProgressListener progress) throws Exception {
        return transport.downloadStorageObject(handle, output, progress);
    }

    public void assertHealthy() throws Exception {
        transport.assertHealthy();
    }

    /** 在快照中按句柄查找对象（事件候选句柄 → 完整 ObjectInfo）。 */
    public static CameraStorage.Item findItem(CameraStorage.Snapshot snapshot, long handle) {
        if (snapshot == null) return null;
        for (CameraStorage.Item item : snapshot.items) {
            if (item.handle == handle) return item;
        }
        return null;
    }
}
