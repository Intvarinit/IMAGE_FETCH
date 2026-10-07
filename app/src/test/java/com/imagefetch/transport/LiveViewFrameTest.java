package com.imagefetch.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import com.imagefetch.testutil.Bytes;

import org.junit.Test;

/**
 * 0x9153 实时取景数据段的 TLV 拆解契约测试。
 *
 * <p>数据段是 {@code [u32 len][u32 type][payload]} 的块序列（len 含 8 字节头），
 * JPEG 从块内 +8 起（libgphoto2 library.c:3685-3743）。相机固件若换了块布局，
 * 这里必须退化到整段 JPEG 标记扫描，而不是把整帧丢掉。
 */
public class LiveViewFrameTest {

    /** 一帧最小可用 JPEG：SOI + 填充 + EOI，只验证字节边界，不要求真能解码。 */
    private static byte[] jpeg(int fillerLength) {
        byte[] body = new byte[fillerLength];
        for (int i = 0; i < fillerLength; i++) body[i] = (byte) i;
        return Bytes.concat(new byte[]{(byte) 0xff, (byte) 0xd8}, body,
                new byte[]{(byte) 0xff, (byte) 0xd9});
    }

    @Test
    public void 单块type1直接取出Jpeg载荷() {
        byte[] image = jpeg(16);
        byte[] block = Bytes.concat(Bytes.u32(image.length + 8), Bytes.u32(1), image);
        assertArrayEquals(image, PtpIpTransport.extractLiveViewJpeg(block));
    }

    @Test
    public void Movie模式下type9同样可取() {
        byte[] image = jpeg(8);
        byte[] block = Bytes.concat(Bytes.u32(image.length + 8), Bytes.u32(9), image);
        assertArrayEquals(image, PtpIpTransport.extractLiveViewJpeg(block));
    }

    @Test
    public void 跳过非Jpeg块后取到后面的帧() {
        byte[] histogram = new byte[32];
        byte[] image = jpeg(12);
        byte[] data = Bytes.concat(
                Bytes.u32(histogram.length + 8), Bytes.u32(17), histogram,
                Bytes.u32(image.length + 8), Bytes.u32(1), image);
        assertArrayEquals(image, PtpIpTransport.extractLiveViewJpeg(data));
    }

    @Test
    public void 块头读不通时退化为整段扫描() {
        // 伪造一个长度越界的块头：TLV 遍历必须中断而不是越界读。
        byte[] image = jpeg(20);
        byte[] data = Bytes.concat(Bytes.u32(0xffff_ffffL), Bytes.u32(1), image);
        assertArrayEquals(image, PtpIpTransport.extractLiveViewJpeg(data));
    }

    @Test
    public void 没有Jpeg时返回null() {
        byte[] histogram = new byte[32];
        byte[] data = Bytes.concat(Bytes.u32(histogram.length + 8), Bytes.u32(17), histogram);
        assertNull(PtpIpTransport.extractLiveViewJpeg(data));
        assertNull(PtpIpTransport.extractLiveViewJpeg(new byte[0]));
        assertNull(PtpIpTransport.extractLiveViewJpeg(null));
    }

    @Test
    public void 只有起始标记没有结束标记时返回null() {
        byte[] truncated = Bytes.concat(new byte[]{(byte) 0xff, (byte) 0xd8}, new byte[16]);
        assertNull(PtpIpTransport.extractLiveViewJpeg(truncated));
    }
}
