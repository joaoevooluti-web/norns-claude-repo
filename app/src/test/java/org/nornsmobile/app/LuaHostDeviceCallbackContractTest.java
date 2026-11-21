package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class LuaHostDeviceCallbackContractTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        assertTrue("missing source: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void usbCallbacksCarryTransportSessionTokenToLuaExecutor() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(host.contains("usbCallbackGate.begin(deviceId, sessionToken)"));
        assertTrue(host.contains("usbCallbackGate.accepts(deviceId, sessionToken, false)"));
        assertTrue(host.contains("usbCallbackGate.beginClose(deviceId, sessionToken)"));
        assertTrue(host.contains("usbCallbackGate.acceptsTerminal(deviceId, sessionToken)"));
        assertTrue(host.contains("usbCallbackGate.finishClose(deviceId, sessionToken)"));
        int data = host.indexOf("private void dispatchSerialData");
        int connected = host.indexOf("dispatchSerialConnected(deviceId, name)");
        assertTrue(data >= 0 && connected >= 0);
    }

    @Test public void hidQueuedEventsAreCheckedAtExecutionAndCloseAllowsOnlyReleases() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int event = host.indexOf("private void hidEvent(");
        int check = host.indexOf("hidCallbackGate.accepts(deviceId, session.token, release)", event);
        int call = host.indexOf("callNorns(\"hid_event\"", check);
        int close = host.indexOf("final LuaDeviceCallbackGate.Session session = hidCallbackGate.beginClose(deviceId)");
        int remove = host.indexOf("callNorns(\"hid_device_remove\"", close);
        int finish = host.indexOf("hidCallbackGate.finishClose(deviceId, session.token)", remove);
        assertTrue(event >= 0 && check > event && call > check);
        assertTrue(close >= 0 && remove > close && finish > remove);
        assertTrue(host.contains("final boolean release = value == 0 && (eventType == 1 || eventType == 3)"));
    }

    @Test public void disconnectQueuesKeyboardMouseAndGamepadReleasesBeforeHidRemoval() throws Exception {
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int removed = activity.indexOf("@Override public void onInputDeviceRemoved");
        int gamepad = activity.indexOf("updateGamepadDevice(deviceId, false)", removed);
        int hid = activity.indexOf("updateHidDevice(deviceId, false)", gamepad);
        assertTrue(removed >= 0 && gamepad > removed && hid > gamepad);
        int release = host.indexOf("void gamepadDeviceChanged(final int deviceId, final String deviceName, final boolean connected)");
        int releaseAxis = host.indexOf("callNorns(\"hid_event\"", release);
        int releaseButton = host.indexOf("callNorns(\"gamepad_button\"", releaseAxis);
        int gamepadRemoved = host.indexOf("callNorns(\"gamepad_device\"", releaseButton);
        assertTrue(release >= 0 && releaseAxis > release && releaseButton > releaseAxis
                && gamepadRemoved > releaseButton);
    }
    @Test public void staleAndroidInputEventsCannotRecreateADeviceSession() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(host.contains("private boolean isCurrentInputDevice(int deviceId, InputDevice device)"));
        assertTrue(host.contains("hidCallbackGate.matchesIdentity(deviceId, session.token, device.getDescriptor())"));
        assertTrue(host.contains("!isCurrentInputDevice(event.getDeviceId(), event.getDevice())"));
        assertTrue(host.contains("!isCurrentInputDevice(id, device)"));
        assertEquals("HID sessions must be created only by InputManager add/change notifications",
                2, occurrences(host, "void hidDeviceChanged("));
    }

    private static int occurrences(String source, String needle) {
        int count = 0, from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) { count++; from += needle.length(); }
        return count;
    }

    @Test public void explicitLuaSerialCloseInvalidatesQueuedCallbacksBeforeTransportClose() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int close = host.indexOf("private void closeUsbSerialPort(int deviceId)");
        int invalidate = host.indexOf("usbCallbackGate.beginClose(deviceId)", close);
        int finish = host.indexOf("usbCallbackGate.finishClose(deviceId, session.token)", invalidate);
        int transport = host.indexOf("usbSerial.close(deviceId)", finish);
        assertTrue(close >= 0 && invalidate > close && finish > invalidate && transport > finish);
        assertTrue(host.contains("case H_USB_SERIAL_CLOSE:"));
        assertTrue(host.contains("closeUsbSerialPort(a.checkint(1));"));
    }

    @Test public void queuedHostCallbacksAreBoundToTheLuaVmGenerationAndExecutor() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(host.contains("private volatile long vmGeneration = 1L;"));
        assertTrue(host.contains("vmGeneration++;"));
        assertTrue(host.contains("final ScheduledThreadPoolExecutor owner = exec;"));
        assertTrue(host.contains("final long generation = vmGeneration;"));
        assertTrue(host.contains("vmGeneration != generation || exec != owner || !running"));
        int stop = host.indexOf("private void stopInternal()");
        int stopEnd = host.indexOf("private synchronized void failStart", stop);
        assertTrue(stop >= 0 && stopEnd > stop);
        assertTrue(host.substring(stop, stopEnd).indexOf("vmGeneration++") < host.substring(stop, stopEnd).indexOf("ex.submit"));
    }

    @Test public void hidReplacementUsesAndroidDescriptorAndQueuesOldRemovalBeforeNewAdd() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        assertTrue(host.contains("hidCallbackGate.matchesIdentity(deviceId, current.token, descriptor)"));
        assertTrue(host.contains("hidCallbackGate.beginIfAbsent(deviceId, descriptor)"));
        assertTrue(host.contains("hidCallbackGate.acceptsOrderedTerminal(deviceId, session.token)"));
        int changed = activity.indexOf("@Override public void onInputDeviceChanged");
        int releaseKeyboard = activity.indexOf("host.releaseKeyboardDevice(deviceId)", changed);
        int gamepadRemove = activity.indexOf("updateGamepadDevice(deviceId, false)", changed);
        int hidRemove = activity.indexOf("updateHidDevice(deviceId, false)", changed);
        int hidAdd = activity.indexOf("updateHidDevice(deviceId, true)", hidRemove);
        assertTrue(changed >= 0 && releaseKeyboard > changed && gamepadRemove > releaseKeyboard && hidRemove > gamepadRemove && hidAdd > hidRemove);
        assertTrue(activity.contains("device.getDescriptor()"));
    }

    @Test public void androidInputEventsAreComparedWithDeviceDescriptor() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(host.contains("private boolean isCurrentInputDevice(int deviceId, InputDevice device)"));
        assertTrue(host.contains("hidCallbackGate.matchesIdentity(deviceId, session.token, device.getDescriptor())"));
        assertTrue(host.contains("!isCurrentInputDevice(event.getDeviceId(), event.getDevice())"));
        assertTrue(host.contains("!isCurrentInputDevice(id, device)"));
    }
}
