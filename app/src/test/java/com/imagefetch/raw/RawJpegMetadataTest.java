package com.imagefetch.raw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RawJpegMetadataTest {
    @Test
    public void convertsObjectInfoDateToExifFormat() {
        assertEquals(
                "2026:01:01 12:00:00",
                RawJpegMetadata.exifDateTime("2026-01-01 12:00:00"));
    }

    @Test
    public void ignoresUnknownDate() {
        assertNull(RawJpegMetadata.exifDateTime(null));
        assertNull(RawJpegMetadata.exifDateTime("—"));
    }
}
