package com.imagefetch.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** USB PTP 容器线级契约：12 字节头 + 小端载荷（ISO 15740）。 */
public class UsbPtpFramingTest {
    @Test
    public void commandContainerLayoutMatchesIso15740() {
        byte[] packet = UsbPtpFraming.packet(
                UsbPtpFraming.TYPE_COMMAND,
                0x1007,
                7,
                UsbPtpFraming.parameterBytes(new long[]{0x10000001L, 0L, 0xffff_ffffL}));

        assertEquals("12 字节头 + 3 个 4 字节参数", 24, packet.length);
        assertEquals("length 字段含头", 24L, readU32(packet, 0));
        assertEquals(UsbPtpFraming.TYPE_COMMAND, readU16(packet, 4));
        assertEquals(0x1007, readU16(packet, 6));
        assertEquals(7L, readU32(packet, 8));
        assertEquals("GetObjectHandles 的首参为存储卷 ID", 0x10000001L, readU32(packet, 12));
        assertEquals(0L, readU32(packet, 16));
        assertEquals("根关联必须用 PTP 全量标记", 0xffff_ffffL, readU32(packet, 20));
    }

    @Test
    public void headerParsingRoundTripsDataContainers() {
        byte[] packet = UsbPtpFraming.packet(
                UsbPtpFraming.TYPE_DATA, 0x1009, 3, new byte[]{9, 8, 7});
        UsbPtpFraming.Header header = UsbPtpFraming.parseHeader(packet);

        assertEquals(15, header.totalLength);
        assertEquals(UsbPtpFraming.TYPE_DATA, header.type);
        assertEquals(0x1009, header.code);
        assertEquals(3, header.transaction);
        assertEquals(3, header.payloadLength());
    }

    @Test
    public void invalidContainerLengthsAreRejected() {
        try {
            UsbPtpFraming.parseHeader(new byte[12]); // total = 0 < 12
            fail("长度为 0 的容器必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期路径。
        }
        byte[] oversized = new byte[12];
        writeU32(oversized, 0, Integer.MAX_VALUE);
        try {
            UsbPtpFraming.parseHeader(oversized);
            fail("超过上限的容器必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期路径。
        }
        try {
            UsbPtpFraming.parseHeader(new byte[11]);
            fail("不足 12 字节的容器头必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期路径。
        }
    }

    @Test
    public void parametersAreOptionalAndLittleEndian() {
        assertArrayEquals(new byte[0], UsbPtpFraming.parameterBytes(null));
        assertArrayEquals(new byte[0], UsbPtpFraming.parameterBytes(new long[0]));
        assertArrayEquals(
                new byte[]{4, 3, 2, 1},
                UsbPtpFraming.parameterBytes(new long[]{0x0102_0304L}));
    }

    @Test
    public void payloadIsAppendedVerbatim() {
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] packet = UsbPtpFraming.packet(
                UsbPtpFraming.TYPE_RESPONSE, 0x2001, 0, payload);

        assertEquals(17, packet.length);
        assertEquals(0x2001, readU16(packet, 6));
        assertArrayEquals(
                payload,
                Arrays.copyOfRange(packet, UsbPtpFraming.HEADER_BYTES, packet.length));
    }

    private static int readU16(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
    }

    private static long readU32(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                & 0xffff_ffffL;
    }

    private static void writeU32(byte[] target, int offset, int value) {
        ByteBuffer.wrap(target, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }
}
