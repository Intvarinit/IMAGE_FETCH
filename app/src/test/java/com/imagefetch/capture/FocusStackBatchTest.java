package com.imagefetch.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * {@link FocusStackBatch} 的契约测试。
 *
 * <p>这个类的全部价值就在「收集窗口」上：{@code onFileSaved} 对每一次落盘都会回调——
 * 单张拍摄、手动下载、批量下载都走它，而景深合成只关心本次对焦包围那一批。
 * 所以最容易出错、也最该守住的是两条：<b>窗口外的文件一律不收</b>、
 * <b>关窗不清空快照</b>（合成正是关窗之后才跑的）。
 */
public class FocusStackBatchTest {
    private static File file(String name) {
        // 单元测试里 java.io.File 是真实实现，不碰文件系统就不会有副作用。
        return new File(name);
    }

    @Test
    public void 未开窗时收不到任何文件() {
        FocusStackBatch batch = new FocusStackBatch();
        batch.add(file("a.jpg"));
        batch.add(file("b.jpg"));

        assertEquals(0, batch.size());
        assertTrue(batch.files().isEmpty());
        assertFalse(batch.ready());
    }

    @Test
    public void 开窗后按落盘顺序收集() {
        FocusStackBatch batch = new FocusStackBatch();
        batch.arm();
        batch.add(file("a.jpg"));
        batch.add(file("b.jpg"));
        batch.add(file("c.jpg"));

        List<File> files = batch.files();
        assertEquals(3, files.size());
        assertEquals("a.jpg", files.get(0).getName());
        assertEquals("b.jpg", files.get(1).getName());
        assertEquals("c.jpg", files.get(2).getName());
    }

    @Test
    public void 不足两张不算就绪() {
        FocusStackBatch batch = new FocusStackBatch();
        batch.arm();
        batch.add(file("only.jpg"));

        assertFalse(batch.ready());

        batch.add(file("second.jpg"));
        assertTrue(batch.ready());
    }

    @Test
    public void 关窗后不再收集但保留快照() {
        FocusStackBatch batch = new FocusStackBatch();
        batch.arm();
        batch.add(file("a.jpg"));
        batch.add(file("b.jpg"));
        batch.disarm();

        // 关窗之后到达的落盘回调（例如紧接着的单张拍摄）不能被算进这一批。
        batch.add(file("later.jpg"));

        assertEquals(2, batch.size());
        assertEquals("b.jpg", batch.files().get(1).getName());
    }

    @Test
    public void 再次开窗会清掉上一批() {
        FocusStackBatch batch = new FocusStackBatch();
        batch.arm();
        batch.add(file("a.jpg"));
        batch.add(file("b.jpg"));
        batch.disarm();

        batch.arm();

        assertEquals(0, batch.size());
        assertFalse(batch.ready());
    }

    @Test
    public void 空引用被忽略() {
        FocusStackBatch batch = new FocusStackBatch();
        batch.arm();
        batch.add(null);
        batch.add(file("a.jpg"));

        assertEquals(1, batch.size());
    }

    @Test
    public void 快照是只读副本() {
        FocusStackBatch batch = new FocusStackBatch();
        batch.arm();
        batch.add(file("a.jpg"));
        batch.add(file("b.jpg"));

        // 必须返回只读快照：调用方改不动收集器内部状态。
        assertThrows(UnsupportedOperationException.class, () -> batch.files().clear());
        assertEquals(2, batch.size());
    }
}
