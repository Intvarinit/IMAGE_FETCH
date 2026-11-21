package com.imagefetch.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.imagefetch.testutil.Bytes;
import com.imagefetch.testutil.FakePtpIpCamera;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PTP/IP 线级契约测试：用进程内伪相机驱动真实的 {@link PtpIpTransport}，
 * 断言包序列、字段偏移、数据阶段分块与事件派发（对齐 docs/PTPIP_PROTOCOL.md）。
 */
public class PtpIpTransportContractTest {
    private static final long STORAGE_ID = 0x10000001L;
    private static final long HANDLE = 0x2001L;
    private static final long OPEN_SESSION = 0x1002L;
    private static final long GET_DEVICE_INFO = 0x1001L;
    private static final long CAPTURE = 0x100eL;
    private static final long GET_OBJECT = 0x1009L;
    private static final long DELETE_OBJECT = 0x100bL;
    /** Canon EOS_GetEvent：删除前排空事件队列用。 */
    private static final long GET_EVENT = 0x9116L;
    private static final long RESPONSE_OK = 0x2001L;

    @Test
    public void handshakeAndOpenSessionMatchTheProtocolFraming() throws Exception {
        try (FakePtpIpCamera camera = new FakePtpIpCamera("Fake Canon", (channel, operation, tx, params) -> {
            if (operation == OPEN_SESSION) channel.respond(tx, RESPONSE_OK);
        })) {
            PtpIpTransport transport = new PtpIpTransport();
            try {
                String name = transport.connect("127.0.0.1", camera.port());
                assertEquals("Fake Canon", name);

                List<FakePtpIpCamera.Frame> commands = camera.awaitCommandFrames(2, 3000);
                assertEquals("应为 InitCommandRequest + OpenSession 两个命令包", 2, commands.size());

                // type 1：会话 GUID(16B) + InitiatorName(UTF-16 含终止符) + ProtocolVersion。
                FakePtpIpCamera.Frame init = commands.get(0);
                assertEquals(1, init.type);
                byte[] expectedInit = Bytes.concat(
                        new byte[16],
                        Bytes.utf16("IMAGE_FETCH Android"),
                        Bytes.u16(0),
                        Bytes.u32(0x00010000L));
                assertEquals(expectedInit.length, init.payloadLength());
                // GUID 随机，逐字节比对时跳过前 16 字节。
                assertArrayEquals(
                        Arrays.copyOfRange(expectedInit, 16, expectedInit.length),
                        Arrays.copyOfRange(init.data, 8 + 16, init.data.length));

                // type 6：DataPhaseInfo=1、opcode 0x1002、transaction=0、参数 [1]。
                FakePtpIpCamera.Frame open = commands.get(1);
                assertEquals(6, open.type);
                assertEquals(1L, open.u32(8));
                assertEquals(OPEN_SESSION, open.u16(12));
                assertEquals(0L, open.u32(14));
                assertEquals(1L, open.u32(18));

                // type 3：事件通道握手必须携带握手得到的 connectionNumber。
                List<FakePtpIpCamera.Frame> events = camera.awaitEventFrames(1, 3000);
                assertEquals(1, events.size());
                assertEquals(3, events.get(0).type);
                assertEquals(camera.connectionNumber(), events.get(0).u32(8));
            } finally {
                transport.close();
            }
        }
    }

    @Test
    public void getObjectStreamsChunkedDataWithoutBufferingTheWholeFile() throws Exception {
        byte[] payload = new byte[200 * 1024];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index * 31 % 256);
        }
        AtomicReference<long[]> getObjectParams = new AtomicReference<>();
        try (FakePtpIpCamera camera = new FakePtpIpCamera("Fake Nikon", (channel, operation, tx, params) -> {
            if (operation == OPEN_SESSION) {
                channel.respond(tx, RESPONSE_OK);
            } else if (operation == GET_OBJECT) {
                getObjectParams.set(params);
                // 强制拆成多个 Data 包，验证流式消费而非单包整文件。
                channel.sendData(tx, payload, 16 * 1024);
            }
        })) {
            PtpIpTransport transport = new PtpIpTransport();
            try {
                transport.connect("127.0.0.1", camera.port());

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                AtomicLong lastReceived = new AtomicLong(-1);
                AtomicLong lastTotal = new AtomicLong(-1);
                long received = transport.downloadStorageObject(
                        HANDLE,
                        output,
                        (progressReceived, progressTotal) -> {
                            lastReceived.set(progressReceived);
                            lastTotal.set(progressTotal);
                        });

                assertEquals((long) payload.length, received);
                assertArrayEquals(payload, output.toByteArray());
                assertEquals(payload.length, lastReceived.get());
                assertEquals(payload.length, lastTotal.get());
                assertArrayEquals(
                        "GetObject 必须只带对象句柄参数",
                        new long[]{HANDLE},
                        getObjectParams.get());
            } finally {
                transport.close();
            }
        }
    }

    @Test
    public void captureUsesTheStandardOperationWithTwoZeroParameters() throws Exception {
        AtomicReference<long[]> captureParams = new AtomicReference<>();
        AtomicReference<Long> captureTransaction = new AtomicReference<>();
        try (FakePtpIpCamera camera = new FakePtpIpCamera("Fake Sony", (channel, operation, tx, params) -> {
            if (operation == OPEN_SESSION) {
                channel.respond(tx, RESPONSE_OK);
            } else if (operation == CAPTURE) {
                captureParams.set(params);
                captureTransaction.set(tx);
                channel.respond(tx, RESPONSE_OK);
            }
        })) {
            PtpIpTransport transport = new PtpIpTransport();
            try {
                transport.connect("127.0.0.1", camera.port());
                long[] handles = transport.capture();

                assertArrayEquals(new long[]{0L, 0L}, captureParams.get());
                assertEquals("OpenSession 后第一个事务号为 1", 1L, captureTransaction.get().longValue());
                assertEquals("PTP/IP 拍摄后图像写入存储卡，不直接返回句柄", 0, handles.length);
            } finally {
                transport.close();
            }
        }
    }

    /** 删除必须走标准 0x100B DeleteObject，参数为句柄 + objectFormatCode(0)。 */
    @Test
    public void deleteObjectUsesStandardOperationWithHandleAndFormatZero() throws Exception {
        AtomicReference<long[]> deleteParams = new AtomicReference<>();
        try (FakePtpIpCamera camera = new FakePtpIpCamera("Fake Canon", (channel, operation, tx, params) -> {
            if (operation == OPEN_SESSION) {
                channel.respond(tx, RESPONSE_OK);
            } else if (operation == GET_EVENT) {
                // 删除前会先排空 EOS 事件队列：纯 ResponseOK 即「队列已空」。
                channel.respond(tx, RESPONSE_OK);
            } else if (operation == DELETE_OBJECT) {
                deleteParams.set(params);
                channel.respond(tx, RESPONSE_OK);
            }
        })) {
            PtpIpTransport transport = new PtpIpTransport();
            try {
                transport.connect("127.0.0.1", camera.port());
                transport.deleteObject(HANDLE);
                assertArrayEquals(
                        "DeleteObject 参数必须是句柄 + objectFormatCode=0",
                        new long[]{HANDLE, 0L},
                        deleteParams.get());
            } finally {
                transport.close();
            }
        }
    }

    /** 相机拒绝删除（如对象受保护）时，响应码必须作为异常上报，便于上层汇报单张失败。 */
    @Test
    public void deleteObjectSurfacesTheCameraRejectionCode() throws Exception {
        try (FakePtpIpCamera camera = new FakePtpIpCamera("Fake Canon", (channel, operation, tx, params) -> {
            if (operation == OPEN_SESSION) {
                channel.respond(tx, RESPONSE_OK);
            } else if (operation == GET_EVENT) {
                channel.respond(tx, RESPONSE_OK);
            } else if (operation == DELETE_OBJECT) {
                channel.respond(tx, 0x2015L); // NoValidObjectInfo，代表相机拒绝删除
            }
        })) {
            PtpIpTransport transport = new PtpIpTransport();
            try {
                transport.connect("127.0.0.1", camera.port());
                try {
                    transport.deleteObject(HANDLE);
                    fail("相机拒绝时必须抛异常");
                } catch (PtpResponseException expected) {
                    assertEquals(0x2015, expected.responseCode);
                }
            } finally {
                transport.close();
            }
        }
    }

    /**
     * 上一次事务的应答迟到时（实测由 0x9154 EOS_DoAf 引发），那个包会在下一次事务的读取里
     * 先被读到。此时必须当作过期响应丢弃、继续读本次应答，**不能**把命令通道判死——判死后
     * 之后所有遥控命令都会立即抛出通道故障，只能靠重连恢复。
     */
    @Test
    public void staleReplyFromThePreviousTransactionIsSkippedInsteadOfKillingTheChannel()
            throws Exception {
        try (FakePtpIpCamera camera = new FakePtpIpCamera("Fake Sony", (channel, operation, tx, params) -> {
            if (operation == OPEN_SESSION) {
                channel.respond(tx, RESPONSE_OK);
            } else if (operation == CAPTURE) {
                // 先送一个事务号更小的回包（上一次事务的迟到应答），再送本次真正的应答。
                channel.respond(tx - 1, RESPONSE_OK);
                channel.respond(tx, RESPONSE_OK);
            }
        })) {
            PtpIpTransport transport = new PtpIpTransport();
            try {
                transport.connect("127.0.0.1", camera.port());
                transport.capture();
                // 通道未被判死：下一次遥控操作必须照常成功。
                transport.capture();
            } finally {
                transport.close();
            }
        }
    }

    @Test
    public void probeRoundTripsAndObjectAddedIsDispatchedToTheListener() throws Exception {
        try (FakePtpIpCamera camera = new FakePtpIpCamera("Fake Sony", (channel, operation, tx, params) -> {
            if (operation == OPEN_SESSION) {
                channel.respond(tx, RESPONSE_OK);
            } else if (operation == GET_DEVICE_INFO) {
                // probe() 在 ProbeRequest 之后还会做一次真实命令往返（GetDeviceInfo），
                // 用于识别“PING 能回但会话已僵死”的相机；伪相机必须回数据阶段。
                channel.sendData(tx, new byte[8], 8);
            }
        })) {
            PtpIpTransport transport = new PtpIpTransport();
            try {
                CountDownLatch added = new CountDownLatch(1);
                AtomicLong storage = new AtomicLong();
                AtomicLong handle = new AtomicLong();
                transport.setObjectEventListener((objectStorageId, objectHandle) -> {
                    storage.set(objectStorageId);
                    handle.set(objectHandle);
                    added.countDown();
                });

                transport.connect("127.0.0.1", camera.port());

                // 心跳：event 通道 ProbeRequest(13) → ProbeResponse(14)。
                transport.probe();
                List<FakePtpIpCamera.Frame> events = camera.awaitEventFrames(1, 3000);
                assertTrue(
                        "必须发送 type 13 探测包",
                        events.stream().anyMatch(frame -> frame.type == 13));

                // 事件派发：type 8 + EventCode 0x4002 → 业务层收到存储 ID 与句柄。
                camera.pushObjectAdded(STORAGE_ID, HANDLE);
                assertTrue("ObjectAdded 必须派发到监听器", added.await(3, TimeUnit.SECONDS));
                assertEquals(STORAGE_ID, storage.get());
                assertEquals(HANDLE, handle.get());
            } finally {
                transport.close();
            }
        }
    }

    @Test
    public void droppedProbesTimeOutWithinTheContractWindow() throws Exception {
        try (FakePtpIpCamera camera = new FakePtpIpCamera("Fake", (channel, operation, tx, params) -> {
            if (operation == OPEN_SESSION) channel.respond(tx, RESPONSE_OK);
        })) {
            camera.setRespondToProbes(false);
            PtpIpTransport transport = new PtpIpTransport();
            try {
                transport.connect("127.0.0.1", camera.port());

                long started = System.currentTimeMillis();
                try {
                    transport.probe();
                    fail("探测无响应必须超时");
                } catch (Exception expected) {
                    // 预期路径：SocketTimeoutException。
                }
                long elapsed = System.currentTimeMillis() - started;
                assertTrue(
                        "超时不得早于契约值，实际 " + elapsed + " ms",
                        elapsed >= PtpIpTransport.PROBE_TIMEOUT_MS - 500);
                assertTrue(
                        "超时不得明显超过契约值，实际 " + elapsed + " ms",
                        elapsed <= PtpIpTransport.PROBE_TIMEOUT_MS + 4000);
            } finally {
                transport.close();
            }
        }
    }
}
