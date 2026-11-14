package com.imagefetch.ptp;

import com.imagefetch.transport.CameraTransport;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

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

    /** 只枚举拍摄基线之后新增的对象（各卷按最大句柄判界），用于收图轮询。 */
    public CameraStorage.Snapshot snapshotNewerThan(
            Map<Long, Long> maxHandles, int maxItems) throws Exception {
        return transport.listStorageNewerThan(maxHandles, maxItems);
    }

    /** 快速分页：只对当前页取 ObjectInfo，翻页复用句柄缓存。 */
    public CameraStorage.Page page(int pageSize, int pageIndex, boolean jpegOnly) throws Exception {
        return transport.listStoragePage(pageSize, pageIndex, jpegOnly);
    }

    /** 按照片分页（JPG + RAW）：胶片模拟页的两种来源都用它。 */
    public CameraStorage.Page photoPage(int pageSize, int pageIndex) throws Exception {
        return transport.listStoragePhotoPage(pageSize, pageIndex);
    }

    /** 存储卡结构变化后清掉句柄缓存。 */
    public void invalidateStorageCache() {
        transport.invalidateStorageCache();
    }

    /** 进入机身遥控/事件模式；官方连上后立即执行。 */
    public void enterRemoteMode() throws Exception {
        transport.enterRemoteMode();
    }

    public byte[] thumbnail(long handle) throws Exception {
        return transport.getStorageThumbnail(handle);
    }

    public long[] capture() throws Exception {
        return transport.capture();
    }

    /** 开关实时取景（Canon EOS 厂商路径）。 */
    public void setLiveViewEnabled(boolean enabled, LiveViewSize size) throws Exception {
        transport.setLiveViewEnabled(enabled, size);
    }

    /** 只为「拍摄」临时退出取景：彻底关掉取景输出，拍完由调用方恢复。 */
    public void pauseLiveViewForCapture(LiveViewSize size) throws Exception {
        transport.pauseLiveViewForCapture(size);
    }

    public void wakeForLiveView() throws Exception {
        transport.wakeForLiveView();
    }

    /** 取一帧实时取景 JPEG；相机未就绪时返回 null。 */
    public byte[] liveViewFrame() throws Exception {
        return transport.liveViewFrame();
    }

    /** 触发一次单次自动对焦（Canon EOS 厂商路径）。 */
    public void autofocus() throws Exception {
        transport.autofocus();
    }

    /** 写入曝光参数（机上编码值）。 */
    public void writeExposure(CanonExposure.Setting setting, int value) throws Exception {
        transport.writeExposure(setting, value);
    }

    /** 读取曝光参数的当前编码值；交给 {@link CanonExposure#indexOfValue} 换算成档位。 */
    public long readExposure(CanonExposure.Setting setting) throws Exception {
        return transport.readExposure(setting);
    }

    /** 相机事件缓存里某属性的允许值列表（Canon 0xC18A 枚举）；未知返回 null。 */
    public int[] supportedValues(int propertyCode) {
        return transport.supportedValues(propertyCode);
    }

    /**
     * P0 能力探测（见 {@link com.imagefetch.transport.CameraTransport#probeVendorCapabilities}）。
     * {@code verifyWrite=true} 会真的写相机，只应由用户显式触发。
     */
    public CapabilityReport probeVendorCapabilities(boolean verifyWrite) throws Exception {
        return transport.probeVendorCapabilities(verifyWrite);
    }

    /**
     * 取一份厂商属性只读快照，用于机身改设置前后做差分比对
     * （见 {@link com.imagefetch.transport.CameraTransport#snapshotVendorProperties}）。
     */
    public VendorPropertySnapshot snapshotVendorProperties() throws Exception {
        return transport.snapshotVendorProperties();
    }

    /** 读一个厂商属性的原始值区域（只读）。 */
    public byte[] readVendorPropertyBlock(int propertyCode) throws Exception {
        return transport.readVendorPropertyBlock(propertyCode);
    }

    /**
     * 读一个厂商属性允许值列表的原始字节（只读）。列表项不是固定 4 字节 u16 的属性
     * （如画质 {@code 0xD120}）靠它拿原始字节自行解析；相机没上报过则返回 {@code null}。
     */
    public byte[] readVendorPropertyEnumsRaw(int propertyCode) throws Exception {
        return transport.readVendorPropertyEnumsRaw(propertyCode);
    }

    /** 整体回写一个厂商属性的值区域（会真的写相机）。返回相机响应码。 */
    public int writeVendorPropertyBlock(int propertyCode, byte[] block) throws Exception {
        return transport.writeVendorPropertyBlock(propertyCode, block);
    }

    /** 读取对焦方式（Canon 0xD1BA）的当前编码值。 */
    public long readFocusArea() throws Exception {
        return transport.readFocusArea();
    }

    /** 写入对焦方式（机上编码值）。 */
    public void writeFocusArea(int value) throws Exception {
        transport.writeFocusArea(value);
    }

    /** 读取白平衡（Canon 0xD109）的当前编码值。 */
    public long readWhiteBalance() throws Exception {
        return transport.readWhiteBalance();
    }

    /** 写入白平衡（机上编码值）。 */
    public void writeWhiteBalance(int value) throws Exception {
        transport.writeWhiteBalance(value);
    }

    /** 读取色温（Canon 0xD10A）的当前值，单位 K。 */
    public long readColorTemperature() throws Exception {
        return transport.readColorTemperature();
    }

    /** 写入色温（单位 K）。 */
    public void writeColorTemperature(int kelvin) throws Exception {
        transport.writeColorTemperature(kelvin);
    }

    /** 把取景画面里的一个点设为对焦框（感光器像素坐标 x, y）；不触发对焦。 */
    public void touchAfPosition(int x, int y) throws Exception {
        transport.touchAfPosition(x, y);
    }

    /** 先按感光器像素坐标移动对焦框，再触发一次对焦。 */
    public void autofocusAt(int x, int y) throws Exception {
        transport.autofocusAt(x, y);
    }

    /** 最近一帧取景载荷里上报的感光器分辨率 [宽, 高]；没上报过返回 null。 */
    public int[] liveViewSensorSize() {
        return transport.liveViewSensorSize();
    }

    /** 最近一帧取景载荷里出现的 TLV 段类型列表（诊断用）；没收到过返回 null。 */
    public String liveViewSegmentTypes() {
        return transport.liveViewSegmentTypes();
    }

    public long download(
            long handle,
            OutputStream output,
            CameraTransport.ProgressListener progress) throws Exception {
        return transport.downloadStorageObject(handle, output, progress);
    }

    /** 删除相机存储卡上的一个对象（标准 PTP DeleteObject 0x100B）；任意格式均可。 */
    public void delete(long handle) throws Exception {
        transport.deleteObject(handle);
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
