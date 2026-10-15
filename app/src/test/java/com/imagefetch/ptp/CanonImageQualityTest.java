package com.imagefetch.ptp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.imagefetch.testutil.Bytes;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * {@link CanonImageQuality} 的契约测试。画质（{@code 0xD120}）的值区域是变长 u32 序列，
 * 通用解析只会读到 n/type 之类的垃圾值——这里把「条目形状、标签、JPG 判定、兜底选取、
 * 越界容错」逐条钉住，防止下拉框显示错值或把只读 RAW 的相机误判成含 JPG。
 */
public class CanonImageQualityTest {
    /** n == 1 的条目：{@code [1][0x10][type][size][compression]}，20 字节。 */
    private static byte[] entry1(int type, int size, int compression) {
        return Bytes.concat(
                Bytes.u32(1), Bytes.u32(0x10), Bytes.u32(type), Bytes.u32(size), Bytes.u32(compression));
    }

    /** n == 2 的条目：{@code [2][0x10][t1][s1][c1][0x10][t2][s2][c2]}，36 字节。 */
    private static byte[] pair(int t1, int s1, int c1, int t2, int s2, int c2) {
        return Bytes.concat(Bytes.u32(2), Bytes.u32(0x10), Bytes.u32(t1), Bytes.u32(s1), Bytes.u32(c1),
                Bytes.u32(0x10), Bytes.u32(t2), Bytes.u32(s2), Bytes.u32(c2));
    }

    @Test
    public void 解析n1的JPG条目() {
        CanonImageQuality.Option option = CanonImageQuality.parseOne(entry1(1, 0, 3));

        assertEquals("尺寸 0 就是机身菜单里的「大」，并标出这是 JPG", "JPG 大", option.label);
        assertTrue(option.hasJpeg());
        assertFalse("纯 JPG 不该被当成会产生 RAW", option.hasRaw());
    }

    @Test
    public void coarse压缩的JPG带普通后缀() {
        assertEquals("compression=2 是 coarse，用「（普通）」代替看不懂的 c 前缀",
                "JPG 大（普通）", CanonImageQuality.parseOne(entry1(1, 0, 2)).label);
    }

    @Test
    public void 其余压缩档位也有中文说明() {
        assertEquals("JPG 小1（自定义）", CanonImageQuality.parseOne(entry1(1, 0xe, 0)).label);
        assertEquals("JPG 大（未知档）", CanonImageQuality.parseOne(entry1(1, 0, 1)).label);
    }

    @Test
    public void RAW条目按压缩区分RAW与cRAW() {
        CanonImageQuality.Option lossless = CanonImageQuality.parseOne(entry1(6, 0, 4));
        CanonImageQuality.Option compressed = CanonImageQuality.parseOne(entry1(6, 0, 3));

        assertEquals("RAW", lossless.label);
        assertEquals("cRAW", compressed.label);
        assertTrue(lossless.hasRaw());
        assertFalse("仅 RAW 的条目不含 JPG——这正是要拦快门的情形", lossless.hasJpeg());
    }

    @Test
    public void RAW加JPG的组合两个标志都为真() {
        CanonImageQuality.Option option = CanonImageQuality.parseOne(pair(6, 0, 4, 1, 0, 3));

        assertEquals("RAW + JPG 大", option.label);
        assertTrue(option.hasJpeg());
        assertTrue(option.hasRaw());
    }

    @Test
    public void parseList按序解析多个条目并按各自长度前进() {
        List<CanonImageQuality.Option> options = CanonImageQuality.parseList(Bytes.concat(
                entry1(1, 0, 3), entry1(6, 0, 4), pair(6, 0, 4, 1, 0, 3)));

        assertEquals(3, options.size());
        assertEquals("JPG 大", options.get(0).label);
        assertEquals("RAW", options.get(1).label);
        assertEquals("RAW + JPG 大", options.get(2).label);
        assertEquals("n=1 的条目是 20 字节", 20, options.get(0).raw.length);
        assertEquals("n=2 的条目是 36 字节，前进步长必须按这个来", 36, options.get(2).raw.length);
    }

    @Test
    public void indexOfRaw按原始字节定位且未命中返回负一() {
        byte[] target = entry1(1, 0, 3);
        List<CanonImageQuality.Option> options =
                CanonImageQuality.parseList(Bytes.concat(entry1(6, 0, 4), target));

        assertEquals(1, CanonImageQuality.indexOfRaw(options, target));
        assertEquals("尺寸不同就是另一个选项，不能误判命中",
                -1, CanonImageQuality.indexOfRaw(options, entry1(1, 1, 3)));
        assertEquals("空指针不得炸", -1, CanonImageQuality.indexOfRaw(null, target));
    }

    @Test
    public void firstJpegOption优先选既含RAW又含JPG的项() {
        List<CanonImageQuality.Option> options = Arrays.asList(
                CanonImageQuality.parseOne(entry1(1, 0, 3)),
                CanonImageQuality.parseOne(pair(6, 0, 4, 1, 0, 3)));

        CanonImageQuality.Option fallback = CanonImageQuality.firstJpegOption(options);

        assertEquals("切到组合项不丢 RAW，优先级最高", "RAW + JPG 大", fallback.label);
        assertTrue(fallback.hasRaw());
    }

    @Test
    public void firstJpegOption在没有组合项时退回第一个纯JPG() {
        List<CanonImageQuality.Option> options = Arrays.asList(
                CanonImageQuality.parseOne(entry1(6, 0, 4)),
                CanonImageQuality.parseOne(entry1(1, 0, 3)));

        assertEquals("JPG 大", CanonImageQuality.firstJpegOption(options).label);
    }

    @Test
    public void firstJpegOption在全RAW列表里返回null() {
        List<CanonImageQuality.Option> options = Arrays.asList(
                CanonImageQuality.parseOne(entry1(6, 0, 4)),
                CanonImageQuality.parseOne(entry1(6, 0, 3)));

        assertNull("一个含 JPG 的选项都没有时无从兜底，必须如实返回 null", 
                CanonImageQuality.firstJpegOption(options));
    }

    @Test
    public void resolve定位当前值并报告是否含JPG() {
        byte[] allowed = Bytes.concat(entry1(6, 0, 4), entry1(1, 0, 3));

        CanonImageQuality.Choices choices = CanonImageQuality.resolve(allowed, entry1(1, 0, 3));

        assertTrue("列表来自相机上报", choices.fromCamera);
        assertEquals(2, choices.options.size());
        assertEquals(1, choices.currentIndex);
        assertTrue(choices.currentHasJpeg);
    }

    @Test
    public void resolve像EOSRP那样当前为仅RAW时不报含JPG() {
        // 相机只给 RAW：currentHasJpeg 必须为 false，界面才会提示、开拍闸门才会介入兜底。
        byte[] allowed = Bytes.concat(entry1(6, 0, 4), entry1(1, 0, 3));

        CanonImageQuality.Choices choices = CanonImageQuality.resolve(allowed, entry1(6, 0, 4));

        assertEquals(0, choices.currentIndex);
        assertFalse("仅 RAW 不该报含 JPG", choices.currentHasJpeg);
    }

    @Test
    public void resolve把不在列表里的当前值追加到末尾并选中() {
        byte[] allowed = entry1(1, 0, 3);

        CanonImageQuality.Choices choices = CanonImageQuality.resolve(allowed, entry1(6, 0, 4));

        assertEquals("当前值不在列表里时追加，否则下拉框会把第 0 项错显为选中",
                2, choices.options.size());
        assertEquals(1, choices.currentIndex);
        assertEquals("RAW", choices.options.get(1).label);
        assertFalse(choices.currentHasJpeg);
    }

    @Test
    public void resolve在相机没给列表时返回空表且不发明候选值() {
        CanonImageQuality.Choices choices = CanonImageQuality.resolve(null, entry1(6, 0, 4));

        assertFalse("没列表就不许声称来自相机", choices.fromCamera);
        assertTrue("写错画质比留空更糟：这里不发明候选值", choices.options.isEmpty());
        assertEquals(-1, choices.currentIndex);
        assertFalse(choices.currentHasJpeg);
    }

    @Test
    public void 空或截断区域不抛异常() {
        assertTrue(CanonImageQuality.parseList(null).isEmpty());
        assertNull(CanonImageQuality.parseOne(null));
        assertNull(CanonImageQuality.parseOne(new byte[8]));
        assertNull("声明 n=1 但只有 16 字节（缺 type/size/compression）",
                CanonImageQuality.parseOne(Bytes.concat(
                        Bytes.u32(1), Bytes.u32(0x10), Bytes.u32(1), Bytes.u32(0))));
        assertNull("声明 n=2 但第二组没给全",
                CanonImageQuality.parseOne(Bytes.concat(
                        Bytes.u32(2), Bytes.u32(0x10), Bytes.u32(1), Bytes.u32(0), Bytes.u32(3),
                        Bytes.u32(0x10))));
    }

    @Test
    public void 非法的n值立刻停止解析() {
        assertTrue("n 只可能是 1 或 2，其它的直接判定为不合法并停下",
                CanonImageQuality.parseList(Bytes.concat(
                        Bytes.u32(3), Bytes.u32(0x10), Bytes.u32(1), Bytes.u32(0), Bytes.u32(3))).isEmpty());
    }

    @Test
    public void hasJpeg判定() {
        assertTrue(CanonImageQuality.hasJpeg(entry1(1, 0, 3)));
        assertFalse(CanonImageQuality.hasJpeg(entry1(6, 0, 4)));
        assertFalse("读不到时按不含 JPG 处理并不合适，但这里只保证不炸", CanonImageQuality.hasJpeg(null));
    }
}
