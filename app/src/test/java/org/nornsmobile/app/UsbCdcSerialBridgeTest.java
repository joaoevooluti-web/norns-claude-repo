package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class UsbCdcSerialBridgeTest {
    // The callback gate tests below exercise the same synchronization primitive used by Port.
    // Deterministic lifecycle/race contracts; USB hardware remains outside JVM CI.
    @Test public void serialWritesPreserveBinaryPayloadAndHandleLargeTransfersInChunks() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        assertTrue(source.contains("UsbCdcWritePump.write(bytes,"));
        String pump = read("app/src/main/java/org/nornsmobile/app/UsbCdcWritePump.java");
        assertTrue(pump.contains("CHUNK_BYTES = 16384"));
        assertTrue(pump.contains("Math.min(bytes.length - offset, CHUNK_BYTES)"));
        assertTrue(pump.contains("offset += written"));
        assertFalse(source.contains("new String(bytes"));
    }

    @Test public void serialReceiveCopiesOnlyTheBytesActuallyRead() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        assertTrue(source.contains("byte[] data=new byte[n]"));
        assertTrue(source.contains("System.arraycopy(buffer,0,data,0,n)"));
        assertTrue(source.contains("notifyData(p.device.getDeviceId(),p.sessionToken,data)"));
    }

    @Test public void usbPermissionAndDetachPathsCleanUpPendingAndOpenPorts() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        assertTrue(source.contains("permissionRequests.cancel(id)"));
        assertTrue(source.contains("UsbManager.EXTRA_PERMISSION_GRANTED"));
        assertTrue(source.contains("UsbManager.ACTION_USB_DEVICE_DETACHED"));
        assertTrue(source.contains("close(id);"));
        assertTrue(source.contains("notifyDisconnected(id,"));
        assertTrue(source.contains("dispositivo USB desconectado"));
        assertTrue(source.contains("context.unregisterReceiver(receiver)"));
    }

    @Test public void usbPortShutdownWaitsForAnInProgressWrite() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        assertTrue(source.contains("synchronized(p.callbackGate.lock){"));
        assertTrue(source.contains("synchronized(p.callbackGate.lock){\n            if(!p.running.get()||!p.callbackGate.isActive())return false;"));
    }

    @Test public void idleUsbReadLoopBacksOffAndContainsListenerFailures() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        assertTrue(source.contains("Thread.sleep(10)"));
        assertTrue(source.contains("catch(InterruptedException interrupted)"));
        assertTrue(source.contains("catch(RuntimeException e){AppLog.e("));
        assertTrue(source.contains("if(!p.running.get()||closed.get())break;"));
    }

    @Test public void mapsLinuxCmsparMarkAndSpaceParityToCdcLineCoding() {
        UsbCdcSerialBridge.LineCoding mark =
                UsbCdcSerialBridge.lineCodingFromTermios(115200, 0x30 | 0x100 | 0x200 | 0x40000000);
        UsbCdcSerialBridge.LineCoding space =
                UsbCdcSerialBridge.lineCodingFromTermios(115200, 0x30 | 0x100 | 0x40000000);
        assertEquals(3, mark.parity);
        assertEquals(4, space.parity);
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void usbReadCallbacksAreSerializedAgainstClose() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        int readLoop = source.indexOf("private void readLoop(Port p)");
        int dispatch = source.indexOf("notifyData(p.device.getDeviceId(),p.sessionToken,data)", readLoop);
        int lock = source.lastIndexOf("p.callbackGate.dispatch(", dispatch);
        int close = source.indexOf("long close(int id)");
        assertTrue(readLoop >= 0 && dispatch > readLoop && lock > readLoop && lock < dispatch);
        assertTrue(close >= 0);
        assertTrue(source.substring(close, source.indexOf("void closeAll()", close))
                .contains("p.callbackGate.close("));
        assertTrue(source.substring(lock, dispatch).contains("ports.get(p.device.getDeviceId())==p"));
    }

    @Test public void usbListenerCallbacksAreContainedAcrossLifecyclePaths() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        assertTrue(source.contains("notifyConnected(id,p.sessionToken,new DeviceInfo(d).name)"));
        assertTrue(source.contains("notifyData(p.device.getDeviceId(),p.sessionToken,data)"));
        assertTrue(source.contains("notifyDisconnected(id,"));
        assertTrue(source.contains("notifyDevicesChanged()"));
        int callback = source.indexOf("private void notifyDevicesChanged()");
        int callbackEnd = source.indexOf("private static UsbInterface findDataInterface", callback);
        assertTrue(callback >= 0 && callbackEnd > callback);
        String guardedCallback = source.substring(callback, callbackEnd);
        assertTrue(guardedCallback.contains("try{listener.onDevicesChanged();}"));
        assertTrue(guardedCallback.contains("catch(RuntimeException e){AppLog.e("));
    }

    @Test public void rejectsShortCdcLineCodingControlTransfers() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        assertTrue(source.contains("if(configured!=coding.length)"));
        assertTrue(source.contains("CDC SET_LINE_CODING rejeitado"));
    }

    @Test public void mapsLinuxTermiosBaudConstantsToUsbLineRates() {
        assertEquals(9600, UsbCdcSerialBridge.baudFromTermios(13));
        assertEquals(115200, UsbCdcSerialBridge.baudFromTermios(4098));
        assertEquals(230400, UsbCdcSerialBridge.baudFromTermios(4099));
        assertEquals(115200, UsbCdcSerialBridge.baudFromTermios(115200));
    }

    @Test public void rejectsUnsupportedOrDisabledBaudRates() {
        assertEquals(0, UsbCdcSerialBridge.baudFromTermios(0));
        assertEquals(-1, UsbCdcSerialBridge.baudFromTermios(-1));
        assertEquals(-1, UsbCdcSerialBridge.baudFromTermios(42));
        assertNull(UsbCdcSerialBridge.lineCodingFromTermios(0, 0x30));
    }

    @Test public void mapsTermiosDataBitsParityAndStopBitsToCdcLineCoding() {
        UsbCdcSerialBridge.LineCoding eightN1 =
                UsbCdcSerialBridge.lineCodingFromTermios(115200, 0x30 | 0x80 | 0x800);
        assertEquals(115200, eightN1.baudRate);
        assertEquals(8, eightN1.dataBits);
        assertEquals(0, eightN1.parity);
        assertEquals(0, eightN1.stopBits);

        UsbCdcSerialBridge.LineCoding sevenE1 =
                UsbCdcSerialBridge.lineCodingFromTermios(9600, 0x20 | 0x100);
        assertEquals(7, sevenE1.dataBits);
        assertEquals(2, sevenE1.parity);
        assertEquals(0, sevenE1.stopBits);

        UsbCdcSerialBridge.LineCoding eightO2 =
                UsbCdcSerialBridge.lineCodingFromTermios(230400, 0x30 | 0x100 | 0x200 | 0x40);
        assertEquals(8, eightO2.dataBits);
        assertEquals(1, eightO2.parity);
        assertEquals(2, eightO2.stopBits);

        UsbCdcSerialBridge.LineCoding fiveN15 =
                UsbCdcSerialBridge.lineCodingFromTermios(9600, 0x40);
        assertEquals(5, fiveN15.dataBits);
        assertEquals(0, fiveN15.parity);
        assertEquals(1, fiveN15.stopBits);
    }

    @Test public void usbOpenFailureAfterPermissionIsContainedAndReported() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        int callback = source.indexOf("private void handlePermissionResult");
        int open = source.indexOf("private boolean openGranted", callback);
        assertTrue(callback >= 0 && open > callback);
        String path = source.substring(callback, open);
        assertTrue(path.contains("try {"));
        assertTrue(path.contains("openGranted(device,coding)"));
        assertTrue(path.contains("catch(RuntimeException error)"));
        assertTrue(path.contains("notifyDisconnected(id,\"falha ao abrir dispositivo USB: \"+error.getMessage())"));
    }

    @Test public void usbPermissionRequestRuntimeFailuresAreReturnedToLuaWithoutStalePendingState() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        int open = source.indexOf("boolean open(int id,int baud,int cflag)");
        int close = source.indexOf("long close(int id)", open);
        assertTrue(open >= 0 && close > open);
        String path = source.substring(open, close);
        assertTrue(path.contains("try {"));
        assertTrue(path.contains("manager.requestPermission(d,PendingIntent.getBroadcast(context,request.token,intent,flags));"));
        assertTrue(path.contains("catch(RuntimeException error)"));
        assertTrue(path.contains("permissionRequests.cancel(id,request.token);"));
        assertTrue(path.contains("return false;"));
        assertTrue(path.contains("falha ao solicitar permissão/abrir dispositivo"));
    }

    @Test public void usbPermissionCompletionCannotReopenAfterCloseOrShutdown() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        assertTrue(source.contains("lifecycleGate.runIfActive("));
        assertTrue(source.contains("boolean open(int id,int baud,int cflag)"));
        assertTrue(source.contains("long close(int id)"));
        assertTrue(source.contains("removed[0]=ports.remove(id);"));
        assertTrue(source.contains("permissionRequests.cancel(id);"));
        assertTrue(source.contains("void shutdown()"));
        assertTrue(source.contains("private final UsbLifecycleGate lifecycleGate=new UsbLifecycleGate();"));
        assertTrue(source.contains("private void handlePermissionResult"));
        assertTrue(source.contains("private boolean openGranted"));
        assertTrue(source.contains("permissionRequests.consume(id,token)"));
        assertTrue(source.contains("if(request==null||closed.get())return;"));
        assertTrue(source.contains("private boolean openGranted"));
    }

    @Test public void permissionGrantAndCloseShareOneLifecycleCriticalSection() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        int open = source.indexOf("boolean open(int id,int baud,int cflag)");
        int close = source.indexOf("long close(int id)", open);
        int permission = source.indexOf("private void handlePermissionResult");
        int granted = source.indexOf("if(granted)", permission);
        int opened = source.indexOf("openGranted(device,coding)", granted);
        assertTrue(open >= 0 && close > open && permission > close);
        assertTrue(source.substring(open, close).contains("lifecycleGate.runIfActive("));
        assertTrue(source.substring(permission, source.indexOf("private boolean openGranted", permission))
                .contains("lifecycleGate.runIfActive("));
        assertTrue(granted > permission && opened > granted);
        assertTrue(source.substring(permission, opened).contains("permissionRequests.consume(id,token)"));
        assertTrue(source.substring(permission, source.indexOf("private boolean openGranted", permission))
                .contains("if(request==null||closed.get())return;"));
    }

    @Test public void shutdownMarksBridgeClosedBeforeClearingPendingAndUnregisteringReceiver() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        int shutdown = source.indexOf("void shutdown()");
        int end = source.indexOf("private UsbDevice findDevice", shutdown);
        assertTrue(shutdown >= 0 && end > shutdown);
        String block = source.substring(shutdown, end);
        int closed = block.indexOf("closed.set(true)");
        int clear = block.indexOf("permissionRequests.clear()");
        int close = block.indexOf("closeAll()");
        int unregister = block.indexOf("context.unregisterReceiver(receiver)");
        assertTrue(closed >= 0 && clear > closed && close > clear && unregister > close);
    }

    @Test public void readCallbackRechecksPortIdentityAndClosedStateInsideIoLock() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        int loop = source.indexOf("private void readLoop(Port p)");
        int lock = source.indexOf("p.callbackGate.dispatch(", loop);
        int check = source.indexOf("ports.get(p.device.getDeviceId())==p", lock);
        int callback = source.indexOf("notifyData(p.device.getDeviceId(),p.sessionToken,data)", check);
        assertTrue(loop >= 0 && lock > loop && check > lock && callback > check);
        String guard = source.substring(lock, callback);
        assertTrue(guard.contains("p.running.get()"));
        assertTrue(guard.contains("closed.get()"));
    }

    @Test public void usbCallbackInFlightCompletesBeforeCloseAndLateCallbackIsRejected() throws Exception {
        final UsbPortCallbackGate gate = new UsbPortCallbackGate();
        final java.util.concurrent.CountDownLatch callbackEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseCallback = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch closeStarted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch closeFinished = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
        Thread reader = new Thread(new Runnable() { @Override public void run() {
            assertTrue(gate.dispatch(() -> true, () -> {
                callbacks.incrementAndGet(); callbackEntered.countDown();
                try { assertTrue(releaseCallback.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
        }}, "usb-callback-in-flight");
        reader.start();
        assertTrue(callbackEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        Thread closer = new Thread(new Runnable() { @Override public void run() {
            closeStarted.countDown();
            gate.close(() -> closeFinished.countDown());
        }}, "usb-close-race");
        closer.start();
        assertTrue(closeStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("close must wait while the callback owns the lifecycle lock", 1L,
                closeFinished.getCount());
        releaseCallback.countDown();
        reader.join(2000); closer.join(2000);
        assertFalse(reader.isAlive()); assertFalse(closer.isAlive());
        assertEquals(1, callbacks.get());
        assertEquals(0L, closeFinished.getCount());
        assertFalse("a late USB callback must not reach Lua after close",
                gate.dispatch(() -> true, () -> callbacks.incrementAndGet()));
        assertEquals(1, callbacks.get());
    }

    @Test public void usbCallbackGateRejectsStalePortIdentityBeforeDispatch() {
        UsbPortCallbackGate gate = new UsbPortCallbackGate();
        final java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
        assertFalse(gate.dispatch(() -> false, () -> callbacks.incrementAndGet()));
        assertEquals(0, callbacks.get());
        assertTrue(gate.dispatch(() -> true, () -> callbacks.incrementAndGet()));
        assertEquals(1, callbacks.get());
        gate.close(() -> {});
        assertFalse(gate.dispatch(() -> true, () -> callbacks.incrementAndGet()));
        assertEquals(1, callbacks.get());
    }


    @Test public void receiverHotplugBranchesAreSerializedAndDetachClosesBeforeCallbacks() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        int receiver = source.indexOf("private final BroadcastReceiver receiver");
        int constructor = source.indexOf("UsbCdcSerialBridge(Context context", receiver);
        assertTrue(receiver >= 0 && constructor > receiver);
        String dispatch = source.substring(receiver, constructor);
        assertTrue(dispatch.contains("receiverGate.dispatch(() -> lifecycleGate.isActive(), () -> {"));
        int detach = dispatch.indexOf("UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)");
        int close = dispatch.indexOf("close(id);", detach);
        int disconnected = dispatch.indexOf("notifyDisconnected(id", close);
        int changed = dispatch.indexOf("notifyDevicesChanged();", disconnected);
        assertTrue(detach >= 0 && close > detach && disconnected > close && changed > disconnected);
        int shutdown = source.indexOf("void shutdown()");
        int shutdownEnd = source.indexOf("private UsbDevice findDevice", shutdown);
        assertTrue(shutdown >= 0 && shutdownEnd > shutdown);
        String shutdownBlock = source.substring(shutdown, shutdownEnd);
        int receiverClose = shutdownBlock.indexOf("receiverGate.close(");
        int unregister = shutdownBlock.indexOf("context.unregisterReceiver(receiver)");
        int closeAll = shutdownBlock.lastIndexOf("closeAll();");
        assertTrue(receiverClose >= 0 && unregister > receiverClose && closeAll > unregister);
        assertTrue("shutdown must drain the receiver before closing ports",
                shutdownBlock.contains("receiverGate.close(") && shutdownBlock.contains("closeAll();"));
    }



    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            assertTrue("USB receiver gate barrier timed out",
                    latch.await(2, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test public void receiverGateSerializesAttachDetachAndRejectsEventsAfterShutdown() throws Exception {
        final UsbLifecycleGate lifecycle = new UsbLifecycleGate();
        final UsbPortCallbackGate receiver = new UsbPortCallbackGate();
        final java.util.List<String> events = java.util.Collections.synchronizedList(
                new java.util.ArrayList<String>());
        final java.util.concurrent.CountDownLatch attachEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseAttach = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch detachStarted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch detachFinished = new java.util.concurrent.CountDownLatch(1);

        Thread attach = new Thread(() -> receiver.dispatch(lifecycle::isActive, () -> {
            events.add("attach-start");
            attachEntered.countDown();
            await(releaseAttach);
            events.add("attach-end");
        }), "usb-receiver-attach");
        attach.start();
        await(attachEntered);

        Thread detach = new Thread(() -> {
            detachStarted.countDown();
            receiver.dispatch(lifecycle::isActive, () -> events.add("detach"));
            detachFinished.countDown();
        }, "usb-receiver-detach");
        detach.start();
        await(detachStarted);
        assertEquals("detach must wait for attach callback to leave the receiver gate",
                1L, detachFinished.getCount());

        releaseAttach.countDown();
        attach.join(2000);
        detach.join(2000);
        assertFalse(attach.isAlive());
        assertFalse(detach.isAlive());
        assertEquals(java.util.Arrays.asList("attach-start", "attach-end", "detach"), events);

        lifecycle.shutdown(() -> receiver.close(() -> {}));
        assertFalse("shutdown must reject a late receiver callback",
                receiver.dispatch(lifecycle::isActive, () -> events.add("late")));
        assertEquals(3, events.size());
    }

    @Test public void shutdownDrainsInflightHotplugBeforeClosingPorts() throws Exception {
        final UsbLifecycleGate lifecycle = new UsbLifecycleGate();
        final UsbPortCallbackGate receiver = new UsbPortCallbackGate();
        final java.util.List<String> events = java.util.Collections.synchronizedList(
                new java.util.ArrayList<String>());
        final java.util.concurrent.CountDownLatch callbackEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseCallback = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch shutdownStarted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch shutdownFinished = new java.util.concurrent.CountDownLatch(1);
        Thread callback = new Thread(() -> receiver.dispatch(lifecycle::isActive, () -> {
            events.add("hotplug-start");
            callbackEntered.countDown();
            await(releaseCallback);
            events.add("hotplug-end");
        }), "usb-hotplug-during-shutdown");
        callback.start();
        await(callbackEntered);
        Thread shutdown = new Thread(() -> {
            shutdownStarted.countDown();
            lifecycle.shutdown(() -> {
                receiver.close(() -> events.add("receiver-unregistered"));
                events.add("ports-closed");
            });
            shutdownFinished.countDown();
        }, "usb-shutdown-drain");
        shutdown.start();
        await(shutdownStarted);
        assertEquals("shutdown must wait for the receiver callback before transport cleanup",
                1L, shutdownFinished.getCount());
        releaseCallback.countDown();
        callback.join(2000); shutdown.join(2000);
        assertFalse(callback.isAlive()); assertFalse(shutdown.isAlive());
        assertEquals(java.util.Arrays.asList("hotplug-start", "hotplug-end",
                "receiver-unregistered", "ports-closed"), events);
        assertFalse(receiver.dispatch(lifecycle::isActive, () -> events.add("late-hotplug")));
        assertEquals(4, events.size());
    }


    @Test public void sameUsbDeviceCannotReopenUntilPriorCloseReleasesItsInterface() throws Exception {
        final UsbPortCloseGate gate = new UsbPortCloseGate();
        assertTrue(gate.beginClose(77));
        assertTrue(gate.beginClose(78));
        assertFalse("a device close must be registered only once", gate.beginClose(77));
        assertTrue(gate.isClosing(77));
        assertFalse("same-ID reconnect must wait for the previous USB interface release",
                gate.canOpen(77));

        final java.util.concurrent.CountDownLatch waiterStarted =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch waiterFinished =
                new java.util.concurrent.CountDownLatch(1);
        Thread shutdownWaiter = new Thread(() -> {
            waiterStarted.countDown();
            gate.awaitIdle();
            waiterFinished.countDown();
        }, "usb-close-drain-waiter");
        shutdownWaiter.start();
        assertTrue(waiterStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (shutdownWaiter.getState() != Thread.State.WAITING
                && System.nanoTime() < deadline) Thread.yield();
        assertEquals("shutdown waiter must block while the USB interface is still closing",
                Thread.State.WAITING, shutdownWaiter.getState());
        assertEquals(1L, waiterFinished.getCount());

        gate.finishClose(77);
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (shutdownWaiter.getState() != Thread.State.WAITING
                && waiterFinished.getCount() != 0L
                && System.nanoTime() < deadline) Thread.yield();
        assertTrue("shutdown must keep waiting while another USB port is closing",
                waiterFinished.getCount() == 1L);
        assertEquals(Thread.State.WAITING, shutdownWaiter.getState());
        gate.finishClose(78);
        shutdownWaiter.join(2000);
        assertFalse(shutdownWaiter.isAlive());
        assertEquals(0L, waiterFinished.getCount());
        assertTrue(gate.canOpen(77));
        assertTrue(gate.canOpen(78));
        assertFalse(gate.isClosing(77));
        assertFalse(gate.isClosing(78));
    }

    @Test public void stalePermissionGrantCannotConsumeSameIdReconnectRequest() {
        UsbPermissionRequestGate<String> gate = new UsbPermissionRequestGate<>();
        UsbPermissionRequestGate.Request<String> detached = gate.begin(91, "old-session");
        assertTrue(gate.cancel(91));
        UsbPermissionRequestGate.Request<String> reconnected = gate.begin(91, "new-session");

        assertNotEquals("each permission request needs a distinct callback token",
                detached.token, reconnected.token);
        assertNull("a late grant from the detached session must be rejected",
                gate.consume(91, detached.token));
        assertTrue("rejecting a stale callback must preserve the current request",
                gate.isPending(91));
        UsbPermissionRequestGate.Request<String> accepted =
                gate.consume(91, reconnected.token);
        assertNotNull(accepted);
        assertEquals("new-session", accepted.value);
        assertFalse(gate.isPending(91));
    }

    @Test public void permissionTokensAreUniqueAcrossDevicesAndCannotCancelReplacement() {
        UsbPermissionRequestGate<String> gate = new UsbPermissionRequestGate<>();
        UsbPermissionRequestGate.Request<String> first = gate.begin(5, "first");
        UsbPermissionRequestGate.Request<String> second = gate.begin(6, "second");
        assertNotEquals(first.token, second.token);

        UsbPermissionRequestGate.Request<String> replacement = gate.begin(5, "replacement");
        assertFalse("an old request cannot cancel a replacement for the same ID",
                gate.cancel(5, first.token));
        assertNull(gate.consume(5, first.token));
        assertTrue(gate.isPending(5));
        assertEquals("replacement", gate.consume(5, replacement.token).value);
        assertEquals("second", gate.consume(6, second.token).value);
    }

    @Test public void detachAndShutdownInvalidatePermissionTokens() {
        UsbPermissionRequestGate<String> gate = new UsbPermissionRequestGate<>();
        UsbPermissionRequestGate.Request<String> detached = gate.begin(14, "detached");
        assertTrue(gate.cancel(14));
        assertNull(gate.consume(14, detached.token));

        UsbPermissionRequestGate.Request<String> pendingA = gate.begin(14, "A");
        UsbPermissionRequestGate.Request<String> pendingB = gate.begin(15, "B");
        gate.clear();
        assertNull(gate.consume(14, pendingA.token));
        assertNull(gate.consume(15, pendingB.token));
        assertFalse(gate.isPending(14));
        assertFalse(gate.isPending(15));
    }


    @Test public void duplicateConcurrentPermissionBroadcastsConsumeRequestExactlyOnce() throws Exception {
        final UsbPermissionRequestGate<String> gate = new UsbPermissionRequestGate<>();
        final UsbPermissionRequestGate.Request<String> request = gate.begin(33, "single-grant");
        final java.util.concurrent.CountDownLatch ready =
                new java.util.concurrent.CountDownLatch(2);
        final java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch finished =
                new java.util.concurrent.CountDownLatch(2);
        final java.util.concurrent.atomic.AtomicInteger accepted =
                new java.util.concurrent.atomic.AtomicInteger();
        Runnable callback = () -> {
            ready.countDown();
            try { start.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (gate.consume(33, request.token) != null) accepted.incrementAndGet();
            finished.countDown();
        };
        Thread first = new Thread(callback, "usb-permission-callback-1");
        Thread second = new Thread(callback, "usb-permission-callback-2");
        first.start();
        second.start();
        assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS));
        first.join(2000);
        second.join(2000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals("only one duplicate broadcast may consume the pending request",
                1, accepted.get());
        assertFalse(gate.isPending(33));
    }


    @Test public void repeatedOpenDoesNotReplaceOrDuplicatePendingPermissionRequest() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        int open = source.indexOf("boolean open(int id,int baud,int cflag)");
        int close = source.indexOf("boolean write(", open);
        assertTrue(open >= 0 && close > open);
        String path = source.substring(open, close);
        int granted = path.indexOf("if(manager.hasPermission(d))");
        int cancel = path.indexOf("permissionRequests.cancel(id);", granted);
        int pending = path.indexOf("if(permissionRequests.isPending(id))return true;");
        int begin = path.indexOf("request=permissionRequests.begin(id,coding);");
        assertTrue(granted >= 0 && cancel > granted);
        assertTrue("a granted permission must retire any stale pending token", cancel > granted);
        assertTrue("a duplicate open must reuse the outstanding request", pending > cancel);
        assertTrue("a new token is allocated only after the pending-request check", begin > pending);
    }


    @Test public void eachUsbConnectionCarriesStableTokenAndAnnouncesBeforeReaderStarts() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        assertTrue(source.contains("final long sessionToken"));
        assertTrue(source.contains("SESSION_TOKENS.getAndIncrement()"));
        assertTrue(source.contains("listener.onConnected(id,token,name)"));
        assertTrue(source.contains("listener.onData(id,token,data)"));
        assertTrue(source.contains("listener.onDisconnected(id,token,reason)"));
        int connected = source.indexOf("notifyConnected(id,p.sessionToken,new DeviceInfo(d).name)");
        int readerStart = source.indexOf("p.reader.start()", connected);
        assertTrue(connected >= 0 && readerStart > connected);
    }

    @Test public void closeAllSerializesPortSnapshotAgainstConcurrentOpen() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        int closeAll = source.indexOf("void closeAll()");
        int shutdown = source.indexOf("void shutdown()", closeAll);
        assertTrue(closeAll >= 0 && shutdown > closeAll);
        String block = source.substring(closeAll, shutdown);
        assertTrue(block.contains("lifecycleGate.runSerialized(() -> {"));
        assertTrue(block.indexOf("new ArrayList<>(ports.keySet())") < block.indexOf("permissionRequests.clear();"));
        assertTrue(block.contains("portCloseGate.awaitIdle();"));
    }
}
