package com.imagefetch.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.imagefetch.testutil.Bytes;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code 0x9116 GetEvent} 事件块 TLV 解析的契约测试（见
 * {@link PtpIpTransport#harvestEosEventBlock}）。
 *
 * <p>记录格式为 {@code [u32 size][u32 code][payload…]}，{@code size} 含 8 字节头。
 * 这里不接 socket，直接喂合成块，把关键偏移与终止/防护规则钉住：
 * {@code 0xC189} 收当前值（同时收一份原始字节）、{@code 0xC18A} 收允许值列表、
 * {@code [size=8][code=0]} 终止、截断记录忽略、非 3 的 propxtype 不当枚举。
 */
public class EosEventHarvestTest {
    private static final long PROP_VALUE_CHANGED = 0xc189L;
    private static final long AVAIL_LIST_CHANGED = 0xc18aL;
    private static final long PROP_APERTURE = 0xd101L;
    private static final long PROP_AF_METHOD = 0xd1baL;

    /**
     * Canon EOS RP 真机 dump 里 {@code 0xD134 IntervalShootSetting} 的值区域（24 字节）。
     * 首 4 字节 {@code 0x18} = 24 恰好等于区域长度，是 P0 要钉住的「疑似内层长度前缀」事实。
     */
    private static final byte[] RP_INTERVAL_SETTING_REGION = Bytes.concat(
            Bytes.u32(0x18),
            Bytes.zeros(12),
            Bytes.u32(0x0a),
            Bytes.u32(0x0a));

    private final Map<Integer, Long> values = new LinkedHashMap<>();
    private final Map<Integer, int[]> enums = new LinkedHashMap<>();
    private final Map<Integer, byte[]> raw = new LinkedHashMap<>();

    @Test
    public void 当前值事件被收进缓存() {
        byte[] block = propValueRecord(PROP_APERTURE, 0x0020);

        PtpIpTransport.harvestEosEventBlock(block, values, enums, raw);

        assertEquals("光圈当前值应取自 +0x0c 的低 16 位",
                Long.valueOf(0x0020), values.get((int) PROP_APERTURE));
    }

    @Test
    public void 当前值事件的原始字节按原样收进缓存() {
        byte[] block = propValueRecord(PROP_AF_METHOD, RP_INTERVAL_SETTING_REGION);

        PtpIpTransport.harvestEosEventBlock(block, values, enums, raw);

        assertArrayEquals("24 字节不透明区域必须逐字节保真（不做任何结构解读）",
                RP_INTERVAL_SETTING_REGION, raw.get((int) PROP_AF_METHOD));
    }

    @Test
    public void 允许值列表按相机顺序收进缓存() {
        byte[] block = availListRecord(PROP_APERTURE, 3, new int[]{0x0050, 0x0020, 0x0038});

        PtpIpTransport.harvestEosEventBlock(block, values, enums, raw);

        assertArrayEquals("允许值必须保持相机给的顺序",
                new int[]{0x0050, 0x0020, 0x0038}, enums.get((int) PROP_APERTURE));
    }

    @Test
    public void 空的终止记录结束遍历() {
        // 一条有效记录 + [size=8][code=0] 终止标记 + 一条「本不该被读到」的记录。
        byte[] block = Bytes.concat(
                propValueRecord(PROP_APERTURE, 0x0020),
                Bytes.concat(Bytes.u32(8), Bytes.u32(0)),
                propValueRecord(PROP_AF_METHOD, 0x0004));

        PtpIpTransport.harvestEosEventBlock(block, values, enums, raw);

        assertEquals("终止标记前的记录应被收下", Long.valueOf(0x0020), values.get((int) PROP_APERTURE));
        assertNull("终止标记之后的记录不得被解析", values.get((int) PROP_AF_METHOD));
    }

    @Test
    public void 多个记录依次解析() {
        byte[] block = Bytes.concat(
                propValueRecord(PROP_APERTURE, 0x0020),
                availListRecord(PROP_AF_METHOD, 3, new int[]{0, 1, 2}));

        PtpIpTransport.harvestEosEventBlock(block, values, enums, raw);

        assertEquals(Long.valueOf(0x0020), values.get((int) PROP_APERTURE));
        assertArrayEquals(new int[]{0, 1, 2}, enums.get((int) PROP_AF_METHOD));
    }

    @Test
    public void 声明的长度越界时忽略该记录且不抛异常() {
        // 头部声明 0x40 字节，但块里只有 12 字节：必须安全停下、什么都不收。
        byte[] truncated = Bytes.concat(
                Bytes.u32(0x40), Bytes.u32(PROP_VALUE_CHANGED), Bytes.u32(PROP_APERTURE));

        PtpIpTransport.harvestEosEventBlock(truncated, values, enums, raw);

        assertEquals("越界记录不得被收下", 0, values.size());
        assertEquals("越界记录也不得留下原始字节", 0, raw.size());
    }

    @Test
    public void 非3的propxtype不被当成枚举() {
        // propxtype=1 是别的类型：即使后面跟着「像」枚举的数据也不能收。
        byte[] block = availListRecord(PROP_APERTURE, 1, new int[]{0x0020, 0x0038});

        PtpIpTransport.harvestEosEventBlock(block, values, enums, raw);

        assertNull("只有 propxtype==3 才是枚举", enums.get((int) PROP_APERTURE));
    }

    @Test
    public void 值区域不足12字节时不收原始字节() {
        // +0x0c 起才是值；记录到 +0x0c 就结束（正好 12 字节）时没有可收的值区域。
        byte[] block = Bytes.concat(
                Bytes.u32(0x0c), Bytes.u32(PROP_VALUE_CHANGED), Bytes.u32(PROP_APERTURE));

        PtpIpTransport.harvestEosEventBlock(block, values, enums, raw);

        assertNull("没有值区域时不得留下空字节数组", raw.get((int) PROP_APERTURE));
    }

    private static byte[] propValueRecord(long property, long value) {
        return propValueRecord(property, Bytes.u32(value));
    }

    /** {@code 0xC189} 记录：+0x08 属性、+0x0c 起为值区域（长度可变）。 */
    private static byte[] propValueRecord(long property, byte[] region) {
        byte[] payload = Bytes.concat(Bytes.u32(property), region);
        return Bytes.concat(Bytes.u32(8 + payload.length), Bytes.u32(PROP_VALUE_CHANGED), payload);
    }

    /** {@code 0xC18A} 记录：+0x08 属性、+0x0c propxtype、+0x10 个数、+0x14 起每项 4 字节。 */
    private static byte[] availListRecord(long property, long propxtype, int[] items) {
        byte[][] parts = new byte[1 + items.length][];
        parts[0] = Bytes.concat(
                Bytes.u32(property), Bytes.u32(propxtype), Bytes.u32(items.length));
        for (int i = 0; i < items.length; i++) {
            parts[i + 1] = Bytes.u32(items[i]);
        }
        byte[] payload = Bytes.concat(parts);
        return Bytes.concat(Bytes.u32(8 + payload.length), Bytes.u32(AVAIL_LIST_CHANGED), payload);
    }
}
