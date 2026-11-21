package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class NornsSystemApiContractTest {
    @Test public void failedScriptStartupRetiresExecutorAndPendingWakeups() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int start = host.indexOf("private synchronized void failStart(ScheduledThreadPoolExecutor owner)");
        int end = host.indexOf("private void boot(File script, ScheduledThreadPoolExecutor owner)", start);
        assertTrue("failStart implementation must exist", start >= 0 && end > start);
        String failurePath = host.substring(start, end);
        assertTrue(failurePath.contains("if (owner == null || exec != owner) return;"));
        assertTrue(host.contains("boot(script, ex);"));
        assertTrue(failurePath.contains("exec = null;"));
        assertTrue(failurePath.contains("pendingWake = null;"));
        assertTrue(failurePath.contains("paramsPending = false;"));
        assertTrue(failurePath.contains("failedExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);"));
        assertTrue(failurePath.contains("failedExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);"));
        assertTrue(failurePath.contains("failedExecutor.shutdown();"));
        assertTrue(failurePath.contains("audio.allNotesOff();"));
        assertTrue(failurePath.contains("midi.closeAll();"));
        assertTrue(failurePath.contains("usbSerial.closeAll();"));
        assertTrue(failurePath.contains("osc.close();"));
        assertTrue(failurePath.contains("listener.onState(false, \"\");"));
    }

    @Test public void mobileEncoderControlsExposeSensitivityAndAcceleration() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("local function encoder_set_sens(value)"));
        assertTrue(boot.contains("local function encoder_set_accel(value)"));
        assertTrue(boot.contains("encoders = norns.encoders"));
        assertTrue(boot.contains("factor = factor * (1 + encoder_acceleration"));
        assertTrue(boot.contains("safe(handler, n, scaled)"));
    }

    @Test public void nornsScriptLifecycleIsImplementedWithoutDevicePowerOrShellAccess() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("norns.script.load = function(filename)"));
        assertTrue(boot.contains("norns.script.clear = function()"));
        assertTrue(boot.contains("norns.rerun = function()"));
        assertTrue(boot.contains("norns.shutdown não desliga o Android"));
        assertTrue(boot.contains("norns.reboot não reinicia o Android"));
        assertTrue(boot.contains("if string.sub(filename, 1, 1) ~= \"/\" then filename = _path.code .. filename end"));
    }


    @Test public void physicalAndroidKeyboardMatchesNornsCodeCharAndModifierCallbacks() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        assertTrue(boot.contains("keyboard = {available = true, state = {}, _code_chars = {}}"));
        assertTrue(boot.contains("function keyboard.shift()"));
        assertTrue(boot.contains("keyboard.state.LEFTSHIFT == true or keyboard.state.RIGHTSHIFT == true"));
        assertTrue(boot.contains("function keyboard.altgr()"));
        assertTrue(boot.contains("keyboard.state[code] = value > 0"));
        assertTrue(boot.contains("function keyboard.code_to_char(code)"));
        assertTrue(boot.contains("chars.shift_altgr or chars.altgr"));
        assertTrue(boot.contains("normal = normal_char, shift = shift_char"));
        assertTrue(boot.contains("function _norns.keyboard_event(code, value, ch, shift, alt, ctrl, meta,"));
        assertTrue(host.contains("boolean keyboardEvent(final KeyEvent event)"));
        assertTrue(host.contains("keyboardLayoutCharacters(event.getDeviceId(), event.getKeyCode())"));
        assertTrue(host.contains("KeyEvent.META_ALT_RIGHT_ON"));
        assertTrue(host.contains("case KeyEvent.KEYCODE_NUMPAD_9: return \"KP9\";"));
        assertTrue(host.contains("case KeyEvent.KEYCODE_DPAD_LEFT: return \"LEFT\";"));
        assertTrue(activity.contains("dispatchKeyEvent(android.view.KeyEvent event)"));
        assertTrue(activity.contains("current == TAB_NORNS && host != null && host.keyboardEvent(event)"));
        assertTrue(activity.contains("current == TAB_NORNS && index != TAB_NORNS && host != null"));
        assertTrue(activity.contains("onWindowFocusChanged(boolean hasFocus)"));
    }

    @Test public void androidGamepadButtonsAxesAndStateCallbacksAreWired() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        assertTrue(boot.contains("gamepad = {"));
        assertTrue(boot.contains("function gamepad.up()"));
        assertTrue(boot.contains("function _norns.gamepad_device(id, name, connected)"));
        assertTrue(boot.contains("gamepad.connected = next(gamepad.devices) ~= nil"));
        assertTrue(boot.contains("function _norns.gamepad_button(name, value, id, device_name)"));
        assertTrue(boot.contains("function _norns.gamepad_axis(sensor_axis, value, half_reso, id, device_name)"));
        assertTrue(boot.contains("safe(gamepad.axis, sensor_axis, sign)"));
        assertTrue(boot.contains("safe(gamepad.dpad, axis, sign)"));
        assertTrue(host.contains("boolean gamepadKeyEvent(final KeyEvent event)"));
        assertTrue(host.contains("boolean gamepadMotionEvent(final MotionEvent event)"));
        assertTrue(host.contains("void gamepadDeviceChanged(final int deviceId"));
        assertTrue(activity.contains("registerInputDeviceListener(inputDeviceListener, null)"));
        assertTrue(activity.contains("unregisterInputDeviceListener(inputDeviceListener)"));
        assertTrue(activity.contains("host.gamepadDeviceChanged(deviceId, device.getName(), false)"));
        assertTrue(host.contains("MotionEvent.AXIS_HAT_X"));
        assertTrue(activity.contains("dispatchGenericMotionEvent(android.view.MotionEvent event)"));
    }

    @Test public void androidHidDevicesExposeNornsPortsAndReadOnlyEvdevEvents() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        assertTrue(boot.indexOf("_norns = _norns or {}") < boot.indexOf("function _norns.hid_device_add(id, name, vendor, product,"));
        assertTrue(boot.contains("function _norns.hid_device_add(id, name, vendor, product, ascii_keyboard, mouse, gamepad_device)"));
        assertTrue(boot.contains("function _norns.hid_device_remove(id)"));
        assertTrue(boot.contains("update_devices = function()"));
        assertTrue(boot.contains("types = {EV_SYN = 0, EV_KEY = 1, EV_REL = 2, EV_ABS = 3}"));
        assertTrue(host.contains("void hidDeviceChanged(final int deviceId"));
        assertTrue(host.contains("boolean hidMouseMotionEvent(final MotionEvent event)"));
        assertTrue(host.contains("MotionEvent.AXIS_VSCROLL"));
        assertTrue(builtInHidCodes(read("app/src/main/assets/lua/boot.lua")));
        assertTrue(activity.contains("host.hidMouseMotionEvent(event)"));
        assertTrue(host.contains("callNorns(\"hid_device_add\""));
        assertTrue(host.contains("callNorns(\"hid_event\""));
        assertTrue(host.contains("private static int linuxKeyCodeFromName(String name)"));
        assertTrue(activity.contains("updateHidDevice(deviceId, true)"));
        assertTrue(activity.contains("isHidInputDevice(device)"));
        assertFalse(boot.contains("hid.send"));
    }

    @Test public void physicalKeyboardReleasesHeldKeysWhenActivityPauses() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        assertTrue(host.contains("void releaseKeyboardKeys()"));
        assertTrue(host.contains("keyboardDeviceState.releaseAll()"));
        assertTrue(host.contains("LuaValue.ZERO"));
        assertTrue(activity.contains("host.releaseKeyboardKeys();"));
    }

    @Test public void unsupportedEngineIsNotSubstitutedByPolyPerc() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String synth = read("app/src/main/java/org/nornsmobile/app/SynthCore.java");
        assertTrue(boot.contains("engine._native_state = \"unavailable\""));
        assertTrue(boot.contains("engine.name == \"none\" or engine.name == \"None\""));
        assertTrue(boot.contains("engine.name ~= \"None\""));
        assertTrue(boot.contains("não será substituída por PolyPerc"));
        assertTrue(synth.contains("engine = ENGINE_NONE;"));
    }

    @Test public void keyboardPressedStateIsSynchronizedAcrossUiAndLuaThreads() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String keyboard = read("app/src/main/java/org/nornsmobile/app/KeyboardDeviceState.java");
        assertTrue(host.contains("keyboardDeviceState.keyDown(code, keyboardDeviceId)"));
        assertTrue(host.contains("keyboardDeviceState.keyUp(code, keyboardDeviceId)"));
        assertTrue(host.contains("keyboardDeviceState.removeDevice(deviceId)"));
        assertTrue(keyboard.contains("synchronized boolean keyDown("));
        assertTrue(keyboard.contains("synchronized boolean keyUp("));
        assertTrue(keyboard.contains("synchronized Release releaseAll()"));
        assertTrue(keyboard.contains("if (!owners.isEmpty()) return false;"));
    }

    @Test public void nornsOscEventReceivesPathArgumentsAndRemoteEndpoint() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String boot = read("app/src/main/assets/lua/boot.lua");
        int dispatch = host.indexOf("private void dispatchOsc(String path");
        int end = host.indexOf("private void dispatchMonomeDevice(", dispatch);
        assertTrue(dispatch >= 0 && end > dispatch);
        String callback = host.substring(dispatch, end);
        assertTrue(callback.contains("LuaValue.valueOf(path), args, source"));
        assertTrue(callback.contains("source.set(1, LuaValue.valueOf(from));"));
        assertTrue(callback.contains("source.set(2, LuaValue.valueOf(fromPort));"));
        assertTrue(callback.contains("event.invoke("));
        assertTrue(callback.contains("AppLog.e(\"OSC callback: \" + t.getMessage())"));
        int osc = boot.indexOf("osc = {");
        int softcut = boot.indexOf("softcut = {", osc);
        assertTrue(osc >= 0 && softcut > osc);
        String api = boot.substring(osc, softcut);
        assertTrue(api.contains("event = nil"));
        assertTrue(api.contains("send = function(to, path, args, legacy_args)"));
        assertTrue(api.contains("hostname, port, payload = to[1], to[2], args or {}"));
        assertTrue(api.contains("listen = function(port)"));
        assertTrue(api.contains("send_bundle = function(to, messages, timetag_ms)"));
    }

    @Test public void luaFilesystemPreservesWriteFailuresAndReadsConsecutiveNumbers() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int numericRead = boot.indexOf("elseif fmt == \"n\" then");
        int numericEnd = boot.indexOf("elseif type(fmt) == \"number\" then", numericRead);
        assertTrue(numericRead >= 0 && numericEnd > numericRead);
        String numberPath = boot.substring(numericRead, numericEnd);
        assertTrue(numberPath.contains("local remaining = string.sub(data, self.pos)"));
        assertTrue(numberPath.contains("local whitespace = string.match(remaining, \"^(%s*)\")"));
        assertTrue(numberPath.contains("self.pos = self.pos + #whitespace + #token"));
        assertTrue(numberPath.contains("tonumber(token)"));

        int writer = boot.indexOf("function FileW:write(...)");
        int ioOpen = boot.indexOf("local real_tostring = tostring", writer);
        assertTrue(writer >= 0 && ioOpen > writer);
        String writePath = boot.substring(writer, ioOpen);
        assertTrue(writePath.contains("if self.closed then return nil"));
        assertTrue(writePath.contains("self.data = prefix .. gap .. chunk .. suffix"));
        assertTrue(writePath.contains("if not host.write_file(self.path, self.data, false) then"));
        assertTrue(writePath.contains("return nil, tostring(self.path)"));
        assertTrue(writePath.contains("if not ok then return nil, err, code end"));
        assertTrue(writePath.contains("self.pos = target + 1"));
    }

    @Test public void luaIoSupportsDefaultInputOutputAndFileHandleClosing() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("local default_input = io.stdin"));
        assertTrue(boot.contains("local default_output = io.stdout"));
        assertTrue(boot.contains("function io.input(file)"));
        assertTrue(boot.contains("function io.output(file)"));
        assertTrue(boot.contains("default_input = opened"));
        assertTrue(boot.contains("default_output = opened"));
        assertTrue(boot.contains("function io.read(fmt)"));
        assertTrue(boot.contains("return default_input:read(fmt or \"l\")"));
        assertTrue(boot.contains("function io.write(...)"));
        assertTrue(boot.contains("return default_output:write(...)"));
        assertTrue(boot.contains("function io.flush()"));
        assertTrue(boot.contains("function io.close(file)"));
        assertTrue(boot.contains("return target:close()"));
        assertTrue(boot.contains("function io.type(file)"));
        assertTrue(boot.contains("function io.lines(path, fmt)"));
        assertTrue(boot.contains("if line == nil then\n"
                + "      if owns_file and type(f.close) == \"function\" then f:close() end\n"
                + "      closed = true\n"
                + "      f = nil\n"
                + "      return nil"));
    }


    @Test public void sharedFormattersModuleResolvesBothNornsRequireSpellings() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String formatters = read("app/src/main/assets/lua/lib/formatters.lua");
        assertTrue(boot.contains("Accept both norns spellings: require(\"formatters\") and require(\"lib/formatters\")"));
        assertTrue(boot.contains("src = host.read_asset(\"lua/lib/\" .. rel .. \".lua\")"));
        assertTrue(boot.contains("util = require 'util'"));
        assertTrue(formatters.contains("local ControlSpec = require \"controlspec\""));
        assertTrue(formatters.contains("function Formatters.format_freq_raw(freq)"));
        assertTrue(formatters.contains("function Formatters.format_secs_raw(secs)"));
        assertTrue(formatters.contains("function Formatters.unipolar_as_multimode_filter_freq(param)"));
        assertTrue(formatters.contains("function Formatters.bipolar_as_pan_widget(param)"));
        assertTrue(formatters.contains("function Formatters.round(precision)"));
        assertTrue(formatters.contains("return Formatters"));
    }

    private static boolean builtInHidCodes(String boot) {
        return boot.contains("BTN_FORWARD=0x115, BTN_BACK=0x116")
                && boot.contains("REL_WHEEL=8, REL_HWHEEL=6");
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void luaFileSeekValidatesOriginAndFiniteIntegralOffsets() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int seek = boot.indexOf("function FileR:seek(whence, off)");
        int end = boot.indexOf("function FileR:write()", seek);
        assertTrue(seek >= 0 && end > seek);
        String implementation = boot.substring(seek, end);
        assertTrue(implementation.contains("whence ~= \"set\" and whence ~= \"cur\" and whence ~= \"end\""));
        assertTrue(implementation.contains("off == math.huge or off == -math.huge"));
        assertTrue(implementation.contains("off ~= math.floor(off)"));
        assertTrue(implementation.contains("target < 0 or target > 2147483647"));
        assertTrue(implementation.contains("self.pos = target + 1"));
        int writeSeek = boot.indexOf("function FileW:seek(whence, off)");
        int writeSeekEnd = boot.indexOf("local real_tostring = tostring", writeSeek);
        assertTrue(writeSeek >= 0 && writeSeekEnd > writeSeek);
        String writeSeekImplementation = boot.substring(writeSeek, writeSeekEnd);
        assertTrue(writeSeekImplementation.contains("self.pos = target + 1"));
        assertTrue(writeSeekImplementation.contains("origem de seek inválida"));
        assertTrue(writeSeekImplementation.contains("off ~= math.floor(off)"));
        assertTrue(boot.contains("append = m == \"a\""));
        assertTrue(boot.contains("initial = host.read_file(path) or \"\""));
    }


    @Test public void luaIoOpenAcceptsOnlyImplementedReadWriteAndAppendModes() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        int open = boot.indexOf("function io.open(path, mode)");
        int end = boot.indexOf("io.stdout =", open);
        assertTrue(open >= 0 && end > open);
        String implementation = boot.substring(open, end);
        assertTrue(implementation.contains("type(path) ~= \"string\" or path == \"\""));
        assertTrue(implementation.contains("mode ~= \"r\" and mode ~= \"rb\""));
        assertTrue(implementation.contains("mode ~= \"w\" and mode ~= \"wb\""));
        assertTrue(implementation.contains("mode ~= \"a\" and mode ~= \"ab\""));
        assertTrue(implementation.contains("modo de arquivo inválido ou não suportado"));
    }


    @Test public void physicalKeyboardAndMouseStateIsReleasedOnDeviceLifecycleChanges() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String activity = read("app/src/main/java/org/nornsmobile/app/MainActivity.java");
        assertTrue(host.contains("keyboardDeviceState.releaseAll()"));
        assertTrue(host.contains("mouseButtonState.releaseAll()"));
        assertTrue(host.contains("releaseKeyboardDevice(final int deviceId)"));
        assertTrue(host.contains("releaseMouseDevice(int deviceId)"));
        assertTrue(activity.contains("host.releaseKeyboardKeys()"));
        assertTrue(activity.contains("host.releaseMouseButtons()"));
        assertTrue(activity.contains("host.releaseGamepadInputs()"));
    }


    @Test public void systemCommandIsExplicitlyBlockedWithoutExecutingAndroidShell() throws Exception {
        File file = new File("app/src/main/assets/lua/boot.lua");
        if (!file.isFile()) file = new File("src/main/assets/lua/boot.lua");
        assertTrue("Missing boot.lua", file.isFile());
        String boot = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int start = boot.indexOf("local function noop()");
        int end = boot.indexOf("-- Menu callback ownership mirrors norns:", start);
        assertTrue(start >= 0 && end > start);

        org.luaj.vm2.Globals globals = org.luaj.vm2.lib.jse.JsePlatform.standardGlobals();
        globals.load("host_messages={}; host={log=function(message, isError) "
                + "host_messages[#host_messages+1]={message=message,is_error=isError} end}",
                "norns-system-api-host.lua").call();
        globals.load(boot.substring(start, end), "production-norns-system-api.lua").call();

        org.luaj.vm2.LuaValue result = globals.load(
                "assert(norns.system_cmd('reboot') == false)\n"
                + "assert(norns.system_cmd('') == false)\n"
                + "assert(norns.system_cmd(nil) == false)\n"
                + "assert(norns.shutdown() == false)\n"
                + "assert(norns.reboot() == false)\n"
                + "assert(#host_messages == 3)\n"
                + "assert(host_messages[1].is_error == true)\n"
                + "assert(string.find(host_messages[1].message, 'bloqueada') ~= nil)\n"
                + "return true",
                "norns-system-api-fail-closed.lua").call();
        assertTrue("Android system commands must fail closed and explain the restriction", result.toboolean());
    }
}