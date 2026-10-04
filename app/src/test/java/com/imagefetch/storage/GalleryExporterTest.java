package com.imagefetch.storage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 相册导出的归类契约。
 * ContentResolver 的实际写入需要真机/模拟器，不在 JVM 单测范围内；
 * 这里固定的是可静态验证的部分：MIME 归类与相册内目标目录。
 */
public class GalleryExporterTest {
    @Test
    public void jpegVariantsMapToImageJpeg() {
        assertEquals("image/jpeg", GalleryExporter.mimeTypeFor("IMG_0001.JPG"));
        assertEquals("image/jpeg", GalleryExporter.mimeTypeFor("IMG_0001.jpg"));
        assertEquals("image/jpeg", GalleryExporter.mimeTypeFor("img_0001.jpeg"));
    }

    @Test
    public void rawFormatsMapByVendor() {
        assertEquals("image/x-canon-cr2", GalleryExporter.mimeTypeFor("IMG_0001.CR2"));
        assertEquals("image/x-canon-cr3", GalleryExporter.mimeTypeFor("IMG_0001.CR3"));
        assertEquals("image/x-nikon-nef", GalleryExporter.mimeTypeFor("DSC_0001.NEF"));
        assertEquals("image/x-sony-arw", GalleryExporter.mimeTypeFor("DSC00001.ARW"));
        assertEquals("image/x-adobe-dng", GalleryExporter.mimeTypeFor("shot.dng"));
    }

    @Test
    public void unknownOrMissingExtensionsFallBackToImageWildcard() {
        assertEquals("image/*", GalleryExporter.mimeTypeFor("capture.bin"));
        assertEquals("image/*", GalleryExporter.mimeTypeFor(null));
        assertEquals("image/*", GalleryExporter.mimeTypeFor(""));
    }

    @Test
    public void exportsIntoTheDcimTree() {
        // DCIM 下才会被图库当作照片收录；Pictures 子目录不进主相册流。
        assertEquals("DCIM/IMAGE_FETCH", GalleryExporter.RELATIVE_PATH);
        assertEquals("IMAGE_FETCH", GalleryExporter.ALBUM_FOLDER);
    }
}
