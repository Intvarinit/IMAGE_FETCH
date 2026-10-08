package com.imagefetch.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.imagefetch.testutil.Bytes;

import org.junit.Test;

/**
 * {@link HdrPlan} 的契约测试。
 *
 * <p>0xD13D 的字段含义没有文档，本类只认定「偏移 4 = 模式」这一条差分实测结论，
 * 其余字节必须原样保留——所以最要紧的一条是：除了偏移 4，任何字节都不许被改动。
 */
public class HdrPlanTest {
    /** 一份 RP 实测形态：HDR 关时偏移 4 = 0（拓自差分报告的前后字节）。 */
    private static byte[] rpBlock() {
        return Bytes.concat(
                Bytes.u32(24),
                Bytes.u32(0),
                Bytes.u32(0),
                Bytes.u32(0),
                Bytes.u32(1),
                Bytes.u32(1));
    }

    @Test
    public void 只改偏移4其余字节逐字节原样() {
        byte[] original = new byte[HdrPlan.BLOCK_LENGTH];
        for (int i = 0; i < original.length; i++) original[i] = (byte) (0x30 + i);

        byte[] result = new HdrPlan(HdrPlan.MODE_AUTO).applyTo(original);

        assertEquals(HdrPlan.BLOCK_LENGTH, result.length);
        for (int offset = 0; offset < result.length; offset++) {
            if (offset >= HdrPlan.OFFSET_MODE && offset < HdrPlan.OFFSET_MODE + 4) continue;
            assertEquals("偏移 " + offset + " 不该被改动", original[offset], result[offset]);
        }
        assertEquals(HdrPlan.MODE_AUTO, Bytes.getU32(result, HdrPlan.OFFSET_MODE));
    }

    @Test
    public void 不修改传入的原始数组() {
        byte[] original = rpBlock();
        byte[] copy = original.clone();

        new HdrPlan(HdrPlan.MODE_AUTO).applyTo(original);

        assertArrayEquals("底稿要留给下一次写入复用，不能被就地改掉", copy, original);
    }

    @Test
    public void 开关两种已知模式往返() {
        assertEquals(HdrPlan.MODE_OFF, HdrPlan.fromBlock(rpBlock()).mode());
        assertEquals(HdrPlan.MODE_AUTO,
                HdrPlan.fromBlock(new HdrPlan(HdrPlan.MODE_AUTO).applyTo(rpBlock())).mode());
    }

    @Test
    public void 未知模式取值照原样写回不编造名字() {
        // 机身菜单里还有带曝光补偿量的几档，取值没有证据：只能原样搬运，读回来必须一致。
        long unknown = 3L;

        HdrPlan roundTrip = HdrPlan.fromBlock(new HdrPlan(unknown).applyTo(rpBlock()));

        assertEquals(unknown, roundTrip.mode());
        assertTrue("未知码必须如实标注，不能假装认识", HdrPlan.describeMode(unknown).contains("未证实"));
        assertEquals("0（关闭）", HdrPlan.describeMode(HdrPlan.MODE_OFF));
        assertEquals("1（自动）", HdrPlan.describeMode(HdrPlan.MODE_AUTO));
    }

    @Test
    public void 长度不是24就拒绝() {
        assertThrows(IllegalArgumentException.class, () -> HdrPlan.fromBlock(Bytes.zeros(20)));
        assertThrows(IllegalArgumentException.class, () -> new HdrPlan(1).applyTo(Bytes.zeros(4)));
        assertThrows(IllegalArgumentException.class, () -> new HdrPlan(1).applyTo(null));
        assertThrows(IllegalArgumentException.class, () -> HdrPlan.fromBlock(null));
    }

    @Test
    public void 模式取值越界就拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new HdrPlan(-1));
        assertThrows(IllegalArgumentException.class, () -> new HdrPlan(HdrPlan.MAX_MODE + 1));
        assertEquals(HdrPlan.MAX_MODE, new HdrPlan(HdrPlan.MAX_MODE).mode());
    }

    @Test
    public void 报告按u32分组并标出已证实的模式字段() {
        String text = HdrPlan.describeBlock(rpBlock());

        assertTrue(text.contains("24 字节"));
        assertTrue(text.contains("+00: 00000018 = 24"));
        assertTrue(text.contains("+04: 00000000 = 0"));
        assertTrue("只有模式是已证实的，标注必须区别于「假定」", text.contains("已证实：模式"));
    }

    @Test
    public void 报告支持长度不是四的倍数的字节() {
        String text = HdrPlan.describeBlock(new byte[]{1, 0, 0});

        assertTrue("余字节要如实说明，不能悄悄吞掉", text.contains("余 3 字节"));
    }
}