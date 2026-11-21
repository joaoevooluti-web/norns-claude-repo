package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class AndroidKeyboardApiContractTest {
    @Test public void exposesNornsKeyboardStateAndLayoutHelpers() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("keyboard = {available = true, state = {}, _code_chars = {}}"));
        assertTrue(boot.contains("function keyboard.shift()"));
        assertTrue(boot.contains("function keyboard.altgr()"));
        assertTrue(boot.contains("function keyboard.code_to_char(code)"));
        assertTrue(boot.contains("chars.shift_altgr or chars.altgr"));
        assertTrue(boot.contains("function _norns.keyboard_event(code, value, ch, shift, alt, ctrl, meta,"));
        assertTrue(boot.contains("keyboard.state[code] = value > 0"));
    }

    @Test public void physicalKeyEventsUseActiveAndroidLayoutAndReleaseHeldKeys() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        assertTrue(host.contains("boolean keyboardEvent(final KeyEvent event)"));
        assertTrue(host.contains("KeyCharacterMap.load(deviceId)"));
        assertTrue(host.contains("KeyEvent.META_ALT_RIGHT_ON"));
        assertTrue(host.contains("void releaseKeyboardKeys()"));
        assertTrue(host.contains("keyboardDeviceState.releaseAll()"));
        assertTrue(host.contains("linuxKeyCodeFromName(code)"));
        assertTrue(activity.contains("dispatchKeyEvent(android.view.KeyEvent event)"));
        assertTrue(activity.contains("if (current == TAB_NORNS && host != null && host.keyboardEvent(event)) return true;"));
        assertTrue(activity.contains("return super.dispatchKeyEvent(event);"));
        assertTrue(activity.contains("protected void onPause()"));
        assertTrue(activity.contains("host.releaseKeyboardKeys();"));
        assertTrue(activity.contains("onWindowFocusChanged(boolean hasFocus)"));
        assertTrue(activity.contains("private void showTab(int index)"));
    }

    @Test public void keyboardEventsDoNotInterceptEditorOrOtherTabs() throws Exception {
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        assertTrue(activity.contains("if (current == TAB_NORNS && host != null && host.keyboardEvent(event)) return true;"));
        assertTrue(activity.contains("return super.dispatchKeyEvent(event);"));
        assertTrue(activity.contains("if (current == TAB_NORNS && index != TAB_NORNS && host != null)"));
        assertTrue(activity.contains("host.releaseKeyboardKeys();"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
