package com.imagefetch.ptp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link AfMethodChoices} 的契约测试。
 *
 * <p>旧实现在相机没上报 0xD1BA 允许列表时退化成「只有当前值一项」，下拉框根本切不动；
 * 上报列表不含当前值时又会把第 0 项错显为选中（显示值 ≠ 相机值）。这两条正是
 * 「对焦点切换没效果」的可解释来源，必须守住。
 */
public class AfMethodChoicesTest {
    @Test
    public void 相机列表含当前值时原样返回() {
        // M50 Mark II 上报的可选值为 {2, 10, 1, 4}（LiveFace / LiveSpotAF / Live / LiveZone）。
        int[] reported = {2, 10, 1, 4};
        AfMethodChoices.Choices choices = AfMethodChoices.resolve(reported, 1);

        assertTrue("来自相机上报", choices.fromCamera);
        assertArrayEquals(reported, choices.values);
        assertEquals("当前值 1 在下标 2", 2, choices.currentIndex);
    }

    @Test
    public void 相机列表不含当前值时追加并选中() {
        int[] reported = {2, 10, 4};
        AfMethodChoices.Choices choices = AfMethodChoices.resolve(reported, 1);

        assertTrue(choices.fromCamera);
        assertArrayEquals("当前值追加到末尾", new int[]{2, 10, 4, 1}, choices.values);
        assertEquals("选中的必须是相机实际值而不是第 0 项", 3, choices.currentIndex);
    }

    @Test
    public void 相机没上报时退化为候选全表() {
        AfMethodChoices.Choices choices = AfMethodChoices.resolve(new int[0], 4);

        assertFalse("候选列表必须标注非相机确认", choices.fromCamera);
        assertEquals("全表 0..14 共 15 项", 15, choices.values.length);
        assertEquals(4, choices.currentIndex);
        assertEquals(0, choices.values[0]);
        assertEquals(14, choices.values[14]);
    }

    @Test
    public void null列表同样退化为候选全表() {
        AfMethodChoices.Choices choices = AfMethodChoices.resolve(null, 0);

        assertFalse(choices.fromCamera);
        assertEquals(15, choices.values.length);
        assertEquals(0, choices.currentIndex);
    }

    @Test
    public void 候选全表外的当前值也会追加保留() {
        AfMethodChoices.Choices choices = AfMethodChoices.resolve(null, 20);

        assertFalse(choices.fromCamera);
        assertEquals("候选 15 项 + 追加的当前值", 16, choices.values.length);
        assertEquals(15, choices.currentIndex);
        assertEquals(20, choices.values[15]);
    }

    @Test
    public void 返回的列表是副本改不动入参() {
        int[] reported = {2, 10, 1, 4};
        AfMethodChoices.Choices choices = AfMethodChoices.resolve(reported, 5);

        choices.values[0] = 99;
        assertEquals("入参不得被改写", 2, reported[0]);
    }
}
