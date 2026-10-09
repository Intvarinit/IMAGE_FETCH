package com.imagefetch.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.imagefetch.testutil.Bytes;

import org.junit.Test;

/**
 * {@link FocusBracketPlan} 的契约测试。
 *
 * <p>0xD208 只有「偏移 8 = 开关」是差分证实的，偏移 12/16/20 只是旁证——所以最要紧的两条是：
 * 除了这四个 u32，任何字节都不许被改动；报告必须把「已证实」与「旁证」分得开。
 *
 * <p>另有两条边界要守住：写入时按机身取值范围卡（张数 2–999、增量 1–10、平滑化 0/1），
 * 读取时从宽（读到什么都不许因为数值意外而丢整块）。
 */
public class FocusBracketPlanTest {
    /** 一份 RP 实测形态：关时偏移 8 = 0（拓自差分报告的前后字节）。 */
    private static byte[] rpOffBlock() {
        return Bytes.concat(
                Bytes.u32(24),
                Bytes.u32(1),
                Bytes.u32(0),
                Bytes.u32(0),
                Bytes.u32(0),
                Bytes.u32(0));
    }

    /** 一份 RP 实测形态：开 / 4 张 / 增量 4 / 平滑化关（差分实测的「后」字节）。 */
    private static byte[] rpOnBlock() {
        return Bytes.concat(
                Bytes.u32(24),
                Bytes.u32(1),
                Bytes.u32(1),
                Bytes.u32(4),
                Bytes.u32(4),
                Bytes.u32(0));
    }

    @Test
    public void 只改四个字段其余字节逐字节原样() {
        byte[] original = new byte[FocusBracketPlan.BLOCK_LENGTH];
        for (int i = 0; i < original.length; i++) original[i] = (byte) (0x30 + i);

        byte[] result = FocusBracketPlan.forWrite(1, 3, 5, 1).applyTo(original);

        assertEquals(FocusBracketPlan.BLOCK_LENGTH, result.length);
        for (int offset = 0; offset < result.length; offset++) {
            if (isTarget(offset)) continue;
            assertEquals("偏移 " + offset + " 不该被改动", original[offset], result[offset]);
        }
        assertEquals(1, Bytes.getU32(result, FocusBracketPlan.OFFSET_ENABLED));
        assertEquals(3, Bytes.getU32(result, FocusBracketPlan.OFFSET_COUNT));
        assertEquals(5, Bytes.getU32(result, FocusBracketPlan.OFFSET_INCREMENT));
        assertEquals(1, Bytes.getU32(result, FocusBracketPlan.OFFSET_SMOOTHING));
    }

    @Test
    public void 不修改传入的原始数组() {
        byte[] original = rpOffBlock();
        byte[] copy = original.clone();

        FocusBracketPlan.forWrite(1, 3, 5, 0).applyTo(original);

        assertArrayEquals("底稿要留给下一次写入复用，不能被就地改掉", copy, original);
    }

    @Test
    public void 实测形态往返() {
        FocusBracketPlan off = FocusBracketPlan.fromBlock(rpOffBlock());
        assertEquals(FocusBracketPlan.ENABLED_OFF, off.enabled());
        assertFalse(off.isOn());

        FocusBracketPlan on = FocusBracketPlan.fromBlock(rpOnBlock());
        assertEquals(FocusBracketPlan.ENABLED_ON, on.enabled());
        assertTrue(on.isOn());
        assertEquals(4, on.count());
        assertEquals(4, on.increment());
        assertEquals(0, on.smoothing());
    }

    @Test
    public void 每个字段都能改并原样读回() {
        FocusBracketPlan roundTrip = FocusBracketPlan.fromBlock(
                FocusBracketPlan.forWrite(1, 7, 9, 1).applyTo(rpOffBlock()));

        assertEquals(1, roundTrip.enabled());
        assertEquals(7, roundTrip.count());
        assertEquals(9, roundTrip.increment());
        assertEquals(1, roundTrip.smoothing());
    }

    @Test
    public void 未知开关取值照原样写回不编造名字() {
        long unknown = 5L;

        FocusBracketPlan roundTrip = FocusBracketPlan.fromBlock(
                FocusBracketPlan.forWrite(unknown, 3, 4, 0).applyTo(rpOffBlock()));

        assertEquals(unknown, roundTrip.enabled());
        assertTrue("未知码必须如实标注，不能假装认识",
                FocusBracketPlan.describeEnabled(unknown).contains("未证实"));
        assertEquals("0（关闭）", FocusBracketPlan.describeEnabled(FocusBracketPlan.ENABLED_OFF));
        assertEquals("1（开启）", FocusBracketPlan.describeEnabled(FocusBracketPlan.ENABLED_ON));
    }

    @Test
    public void 长度不是24就拒绝() {
        assertThrows(IllegalArgumentException.class, () -> FocusBracketPlan.fromBlock(Bytes.zeros(20)));
        assertThrows(IllegalArgumentException.class,
                () -> FocusBracketPlan.forWrite(1, 3, 5, 0).applyTo(Bytes.zeros(4)));
        assertThrows(IllegalArgumentException.class,
                () -> FocusBracketPlan.forWrite(1, 3, 5, 0).applyTo(null));
        assertThrows(IllegalArgumentException.class, () -> FocusBracketPlan.fromBlock(null));
    }

    @Test
    public void 开关取值越界就拒绝() {
        assertThrows(IllegalArgumentException.class, () -> FocusBracketPlan.forWrite(-1, 3, 5, 0));
        assertThrows(IllegalArgumentException.class,
                () -> FocusBracketPlan.forWrite(1, 3, FocusBracketPlan.MAX_FIELD + 1, 0));
        assertEquals(FocusBracketPlan.MAX_FIELD,
                FocusBracketPlan.forWrite(FocusBracketPlan.MAX_FIELD, 3, 5, 0).enabled());
    }

    @Test
    public void 开启时张数必须落在2到999() {
        assertEquals(FocusBracketPlan.MIN_COUNT,
                FocusBracketPlan.forWrite(1, FocusBracketPlan.MIN_COUNT, 1, 0).count());
        assertEquals(FocusBracketPlan.MAX_COUNT,
                FocusBracketPlan.forWrite(1, FocusBracketPlan.MAX_COUNT, 1, 0).count());
        assertThrows(IllegalArgumentException.class, () -> FocusBracketPlan.forWrite(1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> FocusBracketPlan.forWrite(1, 1000, 1, 0));
    }

    @Test
    public void 开启时对焦增量必须落在1到10() {
        assertEquals(FocusBracketPlan.MIN_INCREMENT,
                FocusBracketPlan.forWrite(1, 3, FocusBracketPlan.MIN_INCREMENT, 0).increment());
        assertEquals(FocusBracketPlan.MAX_INCREMENT,
                FocusBracketPlan.forWrite(1, 3, FocusBracketPlan.MAX_INCREMENT, 0).increment());
        assertThrows(IllegalArgumentException.class, () -> FocusBracketPlan.forWrite(1, 3, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FocusBracketPlan.forWrite(1, 3, 11, 0));
    }

    @Test
    public void 开启时曝光平滑化只有开关两档() {
        assertEquals("0（关闭）", FocusBracketPlan.describeSmoothing(FocusBracketPlan.SMOOTHING_OFF));
        assertEquals("1（启用）", FocusBracketPlan.describeSmoothing(FocusBracketPlan.SMOOTHING_ON));
        assertThrows(IllegalArgumentException.class, () -> FocusBracketPlan.forWrite(1, 3, 5, 2));
        assertTrue("超出两档的取值要如实标注，不能硬套开关",
                FocusBracketPlan.describeSmoothing(7).contains("超出开/关两档"));
    }

    @Test
    public void 关闭时三个参数放宽以便原样写回机身自己的值() {
        // 机身关掉对焦包围时可能把张数、增量都放成 0：原样写回去才是对的，不能拦。
        FocusBracketPlan off = FocusBracketPlan.forWrite(0, 0, 0, 0);
        assertEquals(0, off.count());
        assertEquals(0, off.increment());

        byte[] result = off.applyTo(rpOffBlock());
        assertEquals(0, Bytes.getU32(result, FocusBracketPlan.OFFSET_COUNT));
        assertEquals(0, Bytes.getU32(result, FocusBracketPlan.OFFSET_INCREMENT));
    }

    @Test
    public void 读取从宽数值意外也不丢整块() {
        // 读到「开着但张数 0 / 增量 0」也要能读出四个字段，否则界面就没法把底稿摆给用户看。
        byte[] odd = Bytes.concat(
                Bytes.u32(24),
                Bytes.u32(0),
                Bytes.u32(1),
                Bytes.u32(0),
                Bytes.u32(0),
                Bytes.u32(9));

        FocusBracketPlan plan = FocusBracketPlan.fromBlock(odd);

        assertEquals(1, plan.enabled());
        assertEquals(0, plan.count());
        assertEquals(0, plan.increment());
        assertEquals(9, plan.smoothing());
    }

    @Test
    public void 报告按u32分组并区分已证实与旁证() {
        String text = FocusBracketPlan.describeBlock(rpOnBlock());

        assertTrue(text.contains("24 字节"));
        assertTrue(text.contains("+00: 00000018 = 24"));
        assertTrue(text.contains("+08: 00000001 = 1"));
        assertTrue("开关是已证实的，标注必须区别于旁证", text.contains("已证实：开关"));
        assertTrue(text.contains("旁证：张数"));
        assertTrue(text.contains("旁证：对焦增量"));
        assertTrue(text.contains("旁证：曝光平滑化"));
    }

    @Test
    public void 报告支持长度不是四的倍数的字节() {
        String text = FocusBracketPlan.describeBlock(new byte[]{1, 0, 0});

        assertTrue("余字节要如实说明，不能悄悄吞掉", text.contains("余 3 字节"));
    }

    private static boolean isTarget(int offset) {
        return (offset >= FocusBracketPlan.OFFSET_ENABLED && offset < FocusBracketPlan.OFFSET_ENABLED + 4)
                || (offset >= FocusBracketPlan.OFFSET_COUNT && offset < FocusBracketPlan.OFFSET_COUNT + 4)
                || (offset >= FocusBracketPlan.OFFSET_INCREMENT
                        && offset < FocusBracketPlan.OFFSET_INCREMENT + 4)
                || (offset >= FocusBracketPlan.OFFSET_SMOOTHING
                        && offset < FocusBracketPlan.OFFSET_SMOOTHING + 4);
    }
}
