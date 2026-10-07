package com.imagefetch.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** 落盘契约：.part 暂存 → 长度校验 → 原子提交 → 去重索引。 */
public class PhotoRepositoryTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private PhotoRepository repository() {
        return new PhotoRepository(folder.getRoot());
    }

    @Test
    public void stagedDownloadIsAtomicallyCommitted() throws Exception {
        PhotoRepository repository = repository();
        PhotoRepository.StagedFile staged = repository.stage("IMG_0001.JPG", "CamA");
        assertTrue("暂存文件必须是 .part", staged.staging.getName().endsWith(".part"));

        try (OutputStream output = repository.openStaging(staged.staging)) {
            output.write("hello-photo".getBytes(StandardCharsets.UTF_8));
        }
        repository.commit(staged, "CamA:1:2", 11L);

        assertFalse("提交后暂存文件必须消失", staged.staging.exists());
        assertTrue(staged.destination.isFile());
        assertEquals(11L, staged.destination.length());
        assertTrue(repository.isImported("CamA:1:2"));
    }

    @Test
    public void lengthMismatchIsRejectedWithoutProducingAFile() throws Exception {
        PhotoRepository repository = repository();
        PhotoRepository.StagedFile staged = repository.stage("IMG_0002.JPG", "CamA");
        try (OutputStream output = repository.openStaging(staged.staging)) {
            output.write(new byte[5]);
        }
        try {
            repository.commit(staged, "CamA:1:3", 9L);
            fail("长度不一致必须被拒绝");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("长度"));
        }
        assertFalse("拒绝后不得留下目标文件", staged.destination.exists());
        assertFalse(repository.isImported("CamA:1:3"));
    }

    @Test
    public void emptyObjectsAreRejected() throws Exception {
        PhotoRepository repository = repository();
        PhotoRepository.StagedFile staged = repository.stage("IMG_0003.JPG", "CamA");
        repository.openStaging(staged.staging).close();
        try {
            repository.commit(staged, "CamA:1:4", -1L);
            fail("空文件必须被拒绝");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("空"));
        }
        assertFalse(staged.destination.exists());
    }

    @Test
    public void importedIndexSurvivesRepositoryRecreation() throws Exception {
        PhotoRepository first = repository();
        PhotoRepository.StagedFile staged = first.stage("IMG_0004.JPG", "CamA");
        try (OutputStream output = first.openStaging(staged.staging)) {
            output.write(new byte[]{1, 2, 3});
        }
        first.commit(staged, "CamA:1:5", 3L);

        PhotoRepository reopened = repository();
        assertTrue("去重索引必须跨会话持久", reopened.isImported("CamA:1:5"));
        assertEquals(
                Collections.singletonList(staged.destination.getName()),
                reopened.indexedFilenames());
    }

    @Test
    public void markingTheSameObjectTwiceIsIdempotent() throws Exception {
        PhotoRepository repository = repository();
        repository.markImported("CamA:1:6", "a.jpg");
        repository.markImported("CamA:1:6", "a.jpg");
        assertEquals(1, repository.indexedFilenames().size());
    }

    @Test
    public void discardRemovesTheStagingFileOnly() throws Exception {
        PhotoRepository repository = repository();
        PhotoRepository.StagedFile staged = repository.stage("IMG_0006.JPG", "CamA");
        try (OutputStream output = repository.openStaging(staged.staging)) {
            output.write(new byte[]{7});
        }
        repository.discard(staged);
        assertFalse(staged.staging.exists());
        assertFalse(staged.destination.exists());
        assertFalse(repository.isImported("CamA:1:8"));
    }

    @Test
    public void forgetDropsKeySoAReusedHandleIsNotSkipped() throws Exception {
        PhotoRepository first = repository();
        PhotoRepository.StagedFile staged = first.stage("IMG_0008.JPG", "CamA");
        try (OutputStream output = first.openStaging(staged.staging)) {
            output.write(new byte[]{9});
        }
        first.commit(staged, "CamA:1:9", 1L);
        first.markImported("CamA:1:10", "keep.jpg");
        assertTrue(first.isImported("CamA:1:9"));

        first.forget("CamA:1:9");

        assertFalse("删除相机对象后必须丢掉去重键", first.isImported("CamA:1:9"));
        assertTrue("其它去重键不受影响", first.isImported("CamA:1:10"));
        // 索引须持久化到磁盘：重开会话后仍不认得被删对象的键。
        PhotoRepository reopened = repository();
        assertFalse(reopened.isImported("CamA:1:9"));
        assertTrue(reopened.isImported("CamA:1:10"));
        assertEquals(Collections.singletonList("keep.jpg"), reopened.indexedFilenames());
    }

    @Test
    public void outputDirectoryIsCreatedOnDemand() throws Exception {
        File nested = new File(folder.getRoot(), "captures");
        PhotoRepository repository = new PhotoRepository(nested);
        repository.stage("IMG_0007.JPG", "CamA");
        assertTrue(nested.isDirectory());
    }
}
