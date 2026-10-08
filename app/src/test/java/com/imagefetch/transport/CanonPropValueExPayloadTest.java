package com.imagefetch.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.imagefetch.ptp.VendorProperty;
import com.imagefetch.testutil.Bytes;

import org.junit.Test;

/**
 * {@code 0x9110 SetDevicePropValueEx} 数据段形态的契约测试（见
 * {@link PtpIpTransport#canonPropValueExPayload}）。
 *
 * <p>长度字段是「数据段总长」，含长度字段自身与属性码字段，即 {@code 8 + 值区域长度}——
 * 与 libgphoto2 的 {@code ptp_canon_eos_setdevicepropvalue} 一致
 * （{@code size = 8 + ptp_pack_EOS_ImageFormat(...)}，ptp.c:3937-3948；
 * 默认分支退化成 {@code sizeof(uint32_t)*3 = 12}，ptp.c:3951-3952、3980-3983）。
 * 写错的后果在真机上只是「相机静默忽略」或 {@code 0x2006}，只能靠这段字节反推。
 */
public class CanonPropValueExPayloadTest {
    /** RP 真机 dump 的 0xD134 值区域：24 字节，首 4 字节 0x18 == 区域长度。 */
    private static final byte[] RP_REGION = Bytes.concat(
            Bytes.u32(0x18), Bytes.zeros(12), Bytes.u32(0x0a), Bytes.u32(0x0a));

    @Test
    public void 长度字段是八加值区域长度() {
        byte[] payload = PtpIpTransport.canonPropValueExPayload(0xd134, RP_REGION);

        assertEquals("总长 = 8（长度字段 4 + 属性码 4）+ 值区域 24", 32, payload.length);
        assertEquals("长度字段写的是总长而不是载荷长度", 32L, Bytes.getU32(payload, 0));
    }

    @Test
    public void 属性码在第四字节起且值区域逐字节保真() {
        byte[] payload = PtpIpTransport.canonPropValueExPayload(
                VendorProperty.INTERVAL_SHOOT_SETTING, RP_REGION);

        assertEquals("属性码紧跟长度字段", 0xd134L, Bytes.getU32(payload, 4));
        assertArrayEquals("值区域必须原样搬运（不做任何重排）",
                RP_REGION,
                java.util.Arrays.copyOfRange(payload, 8, payload.length));
    }

    @Test
    public void 与既有十二字节写法同构() {
        // 值区域恰好 4 字节时，长载荷路径应等价于既有的 [12][prop][value] 三点式。
        byte[] four = Bytes.u32(0x0000000a);

        byte[] payload = PtpIpTransport.canonPropValueExPayload(0xd11d, four);

        assertEquals("4 字节值区域的等价形态就是 12 字节", 12, payload.length);
        assertEquals(12L, Bytes.getU32(payload, 0));
        assertEquals(0xd11dL, Bytes.getU32(payload, 4));
        assertEquals(10L, Bytes.getU32(payload, 8));
    }
}
