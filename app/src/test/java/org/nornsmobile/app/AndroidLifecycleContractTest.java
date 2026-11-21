package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Lifecycle ordering contracts for the Android boundary (no device/runtime required). */
public class AndroidLifecycleContractTest {
    private static String read(String path) throws Exception {
        File f = new File(path);
        if (!f.isFile() && path.startsWith("app/")) f = new File(path.substring(4));
        if (!f.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void pauseAndFocusLossReleaseEveryHeldInputAndPersistEditor() throws Exception {
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        int pause = activity.indexOf("protected void onPause()");
        int pauseEnd = activity.indexOf("protected void onStop()", pause);
        assertTrue(pause >= 0 && pauseEnd > pause);
        String block = activity.substring(pause, pauseEnd);
        assertTrue(block.contains("host.releaseKeyboardKeys()"));
        assertTrue(block.contains("host.releaseMouseButtons()"));
        assertTrue(block.contains("host.releaseGamepadInputs()"));
        assertTrue(block.contains("norns.releaseInputs()"));
        assertTrue(block.contains("editor.saveIfDirty()"));

        int focus = activity.indexOf("public void onWindowFocusChanged(boolean hasFocus)");
        int focusEnd = activity.indexOf("public void onBackPressed()", focus);
        assertTrue(focus >= 0 && focusEnd > focus);
        String focusBlock = activity.substring(focus, focusEnd);
        assertTrue(focusBlock.contains("if (!hasFocus && host != null)"));
        assertTrue(focusBlock.contains("host.releaseKeyboardKeys()"));
        assertTrue(focusBlock.contains("host.releaseMouseButtons()"));
        assertTrue(focusBlock.contains("host.releaseGamepadInputs()"));
    }

    @Test public void destroyUnregistersInputAndMidiBeforeDisposingHostAndAudio() throws Exception {
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        int destroy = activity.indexOf("protected void onDestroy()");
        int end = activity.indexOf("public boolean dispatchKeyEvent", destroy);
        assertTrue(destroy >= 0 && end > destroy);
        String block = activity.substring(destroy, end);
        int input = block.indexOf("unregisterInputDeviceListener");
        int midi = block.indexOf("unregisterDeviceCallback");
        int host = block.indexOf("host.dispose()");
        int audio = block.indexOf("audio.stop()");
        int superDestroy = block.indexOf("super.onDestroy()");
        assertTrue(input >= 0 && midi > input && host > midi && audio > host && superDestroy > audio);
    }

    @Test public void inputHotplugQueuesGamepadReleaseBeforeHidRemoval() throws Exception {
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        int removed = activity.indexOf("onInputDeviceRemoved(int deviceId)");
        int changed = activity.indexOf("onInputDeviceChanged(int deviceId)", removed);
        assertTrue(removed >= 0 && changed > removed);
        String callback = activity.substring(removed, changed);
        assertTrue(callback.indexOf("releaseKeyboardDevice(deviceId)") < callback.indexOf("updateGamepadDevice(deviceId, false)"));
        assertTrue(callback.indexOf("releaseMouseDevice(deviceId)") < callback.indexOf("updateGamepadDevice(deviceId, false)"));
        assertTrue(callback.indexOf("updateGamepadDevice(deviceId, false)") < callback.indexOf("updateHidDevice(deviceId, false)"));
    }

    @Test public void stopOnlyStopsAudioWhenLuaHostIsNotRunning() throws Exception {
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        int stop = activity.indexOf("protected void onStop()");
        int end = activity.indexOf("protected void onDestroy()", stop);
        assertTrue(stop >= 0 && end > stop);
        String block = activity.substring(stop, end);
        assertTrue(block.contains("host != null && audio != null && !host.isRunning()"));
        assertTrue(block.contains("audio.stop()"));
    }
}
