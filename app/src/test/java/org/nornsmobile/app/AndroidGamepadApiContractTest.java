package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class AndroidGamepadApiContractTest {
    @Test public void exposesNornsGamepadApi() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("gamepad = {"));
        assertTrue(boot.contains("available = true, connected = false, devices = {}, state = {"));
        assertTrue(boot.contains("function gamepad.up()"));
        assertTrue(boot.contains("function _norns.gamepad_device(id, name, connected)"));
        assertTrue(boot.contains("function _norns.gamepad_button(name, value, id, device_name)"));
        assertTrue(boot.contains("function _norns.gamepad_axis(sensor_axis, value, half_reso, id, device_name)"));
    }

    @Test public void routesButtonsAndAxesOnlyOnNornsTab() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        assertTrue(host.contains("boolean gamepadKeyEvent(final KeyEvent event)"));
        assertTrue(host.contains("boolean gamepadMotionEvent(final MotionEvent event)"));
        assertTrue(host.contains("MotionEvent.AXIS_HAT_X"));
        assertTrue(host.contains("MotionEvent.AXIS_LTRIGGER"));
        assertTrue(host.contains("callNorns(\"gamepad_button\""));
        assertTrue(host.contains("callNorns(\"gamepad_axis\""));
        assertTrue(activity.contains("setupGamepadDiagnostics();"));
        assertTrue(activity.contains("dispatchGenericMotionEvent(android.view.MotionEvent event)"));
        assertTrue(activity.contains("current == TAB_NORNS && host != null && host.gamepadKeyEvent(event)"));
        assertTrue(activity.contains("current == TAB_NORNS && host != null && host.gamepadMotionEvent(event)"));
        assertTrue(activity.contains("unregisterInputDeviceListener(inputDeviceListener)"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
