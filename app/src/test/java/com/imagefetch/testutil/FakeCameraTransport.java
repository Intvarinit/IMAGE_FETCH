package com.imagefetch.testutil;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.transport.CameraTransport;

import java.io.OutputStream;
import java.util.ArrayList;

/**
 * 内存伪 transport：供 NewPhotoResolver / PhotoTransferWorker 的行为测试使用，
 * 可注入下载失败、声明长度不一致与已连接状态。
 */
public final class FakeCameraTransport implements CameraTransport {
    private String cameraName = "Fake Camera";
    private CameraStorage.Snapshot snapshot =
            new CameraStorage.Snapshot(new ArrayList<>(), new ArrayList<>());
    private byte[] objectBytes = new byte[0];
    private int failuresBeforeSuccess;
    private Exception failure;
    private ObjectEventListener listener;
    private long[] captureHandles = new long[0];

    public int downloadCalls;
    public int captureCalls;
    /** 最近一次 listStorage 收到的张数上限（便于断言协调器真的把设定透传下去）。 */
    public int lastMaxItems;

    /** 模拟“相机把图像直接放入机身内存”的拍摄路径（如 Nikon USB SDRAM）。 */
    public FakeCameraTransport captureHandles(long[] value) {
        this.captureHandles = value;
        return this;
    }

    public FakeCameraTransport cameraName(String value) {
        this.cameraName = value;
        return this;
    }

    public FakeCameraTransport snapshot(CameraStorage.Snapshot value) {
        this.snapshot = value;
        return this;
    }

    public FakeCameraTransport objectBytes(byte[] value) {
        this.objectBytes = value;
        return this;
    }

    /** 前 failuresBeforeSuccess 次下载抛 failure，之后成功。 */
    public FakeCameraTransport failDownloads(int failuresBeforeSuccess, Exception failure) {
        this.failuresBeforeSuccess = failuresBeforeSuccess;
        this.failure = failure;
        return this;
    }

    public void emitObjectAdded(long storageId, long objectHandle) {
        if (listener != null) listener.onObjectAdded(storageId, objectHandle);
    }

    @Override
    public String connect(String host, int port) {
        return cameraName;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public String getCameraName() {
        return cameraName;
    }

    @Override
    public CameraStorage.Snapshot listStorage(int maxItems) {
        lastMaxItems = maxItems;
        return snapshot;
    }

    @Override
    public CameraStorage.Page listStoragePage(int pageSize, int pageIndex) {
        return CameraStorage.Page.ofItems(snapshot.items, pageSize, pageIndex);
    }

    @Override
    public byte[] getStorageThumbnail(long handle) {
        return new byte[0];
    }

    @Override
    public long downloadStorageObject(
            long handle, OutputStream output, ProgressListener progress) throws Exception {
        downloadCalls++;
        if (failure != null && downloadCalls <= failuresBeforeSuccess) {
            throw failure;
        }
        output.write(objectBytes);
        if (progress != null) progress.onProgress(objectBytes.length, objectBytes.length);
        return objectBytes.length;
    }

    @Override
    public long[] capture() {
        captureCalls++;
        return captureHandles;
    }

    @Override
    public void assertHealthy() {
    }

    @Override
    public void setObjectEventListener(ObjectEventListener value) {
        listener = value;
    }

    @Override
    public void close() {
    }
}
