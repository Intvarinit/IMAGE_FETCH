package com.imagefetch.ptp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link WhiteBalanceChoices} 的解析规则与名称表——纯算术，不需要相机。
 */
public class WhiteBalanceChoicesTest {

    @Test
    public void label用佳能机身菜单的中文名() {
        assertEquals("自动", WhiteBalanceChoices.label(0));
        assertEquals("日光", WhiteBalanceChoices.label(1));
        assertEquals("阴天", WhiteBalanceChoices.label(2));
        assertEquals("白炽灯", WhiteBalanceChoices.label(3));
        assertEquals("荧光灯", WhiteBalanceChoices.label(4));
        assertEquals("闪光灯", WhiteBalanceChoices.label(5));
        assertEquals("阴影", WhiteBalanceChoices.label(8));
        assertEquals("色温", WhiteBalanceChoices.label(9));
        assertEquals("自定义 PC-1", WhiteBalanceChoices.label(10));
        assertEquals("AWB 白色优先", WhiteBalanceChoices.label(23));
    }

    @Test
    public void label对未登记的码返回未知编码而不是null() {
        // 13/14/17/22 在 libgphoto2 的表里是空洞：只有相机真的报了它们才该出现。
        assertEquals("未知编码 13", WhiteBalanceChoices.label(13));
        assertEquals("未知编码 22", WhiteBalanceChoices.label(22));
        assertEquals("未知编码 99", WhiteBalanceChoices.label(99));
    }

    @Test
    public void 上报列表含当前值时保持相机顺序并选中() {
        int[] reported = {0, 3, 9, 23};

        WhiteBalanceChoices.Choices choices = WhiteBalanceChoices.resolve(reported, 9);

        assertArrayEquals(reported, choices.values);
        assertEquals("当前值是 9，应选中下标 2", 2, choices.currentIndex);
        assertTrue("列表来自相机上报", choices.fromCamera);
    }

    @Test
    public void 上报列表不含当前值时追加到末尾并选中() {
        int[] reported = {0, 3, 9};

        WhiteBalanceChoices.Choices choices = WhiteBalanceChoices.resolve(reported, 23);

        assertArrayEquals(new int[]{0, 3, 9, 23}, choices.values);
        assertEquals("追加项必须被选中，否则下拉框会把第 0 项错显为当前值",
                3, choices.currentIndex);
        assertTrue(choices.fromCamera);
    }

    @Test
    public void 相机没上报时退化为候选表并标注未获确认() {
        WhiteBalanceChoices.Choices choices = WhiteBalanceChoices.resolve(null, 1);

        assertEquals(20, choices.values.length);
        assertEquals("候选表第 1 项是日光编码 1", 1, choices.values[1]);
        assertEquals(1, choices.currentIndex);
        assertFalse("候选表必须标注为「未获相机确认」", choices.fromCamera);
    }

    @Test
    public void 空上报列表与null等价() {
        WhiteBalanceChoices.Choices fromNull = WhiteBalanceChoices.resolve(null, 0);
        WhiteBalanceChoices.Choices fromEmpty = WhiteBalanceChoices.resolve(new int[0], 0);

        assertArrayEquals(fromNull.values, fromEmpty.values);
        assertEquals(fromNull.currentIndex, fromEmpty.currentIndex);
        assertFalse(fromNull.fromCamera);
    }

    @Test
    public void 当前值不在候选表里时追加到末尾() {
        // 13 是表里的空洞：相机如果自报这个值，仍要能显示出来并选中。
        WhiteBalanceChoices.Choices choices = WhiteBalanceChoices.resolve(null, 13);

        assertEquals(21, choices.values.length);
        assertEquals(13, choices.values[choices.values.length - 1]);
        assertEquals(choices.values.length - 1, choices.currentIndex);
        assertFalse(choices.fromCamera);
    }
}
