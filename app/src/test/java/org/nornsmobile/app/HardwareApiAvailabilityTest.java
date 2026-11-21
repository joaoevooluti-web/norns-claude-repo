package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class HardwareApiAvailabilityTest {
    @Test public void gridAndArcUseSerialoscUdpWhileHidOutputRemainsUnavailable() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String bridge = read("app/src/main/java/org/nornsmobile/app/MonomeSerialoscBridge.java");
        assertTrue(boot.contains("available = false, connected = false"));
        assertTrue(boot.contains("function p:led(x, y, level, rel)"));
        assertTrue(boot.contains("function p:segment(ring, from, to, level, rel)"));
        assertTrue(boot.contains("local function monome_flag(value)"));
        assertTrue(boot.contains("monome_flag(enabled) and 1 or 0"));
        assertTrue(boot.contains("monome_send(device, \"/tilt/set\", {sensor - 1, monome_flag(enabled) and 1 or 0}) and ok"));
        assertTrue(boot.contains("local function wrapped_overlap(a, b, c, d)"));
        assertTrue(boot.contains("if p.device then p:all(0); p:refresh() end"));
        assertTrue(boot.contains("if not port.device and port.name == fullName then selected = index; break end"));
        assertTrue(boot.contains("_norns.grid_tilt = function(id, x, y, z)"));
        assertTrue(boot.contains("host.monome_send(device.id, path, args or {})"));
        assertTrue(boot.contains("function _norns.grid_device_add"));
        assertTrue(boot.contains("function _norns.arc_device_add"));
        assertFalse(boot.contains("hid.send"));
        assertFalse(boot.contains("unsupported_api"));
        assertTrue(host.contains("monome_devices"));
        assertTrue(host.contains("monome_send"));
        assertTrue(bridge.contains("/serialosc/list"));
        assertTrue(bridge.contains("/serialosc/notify"));
        assertTrue(bridge.contains("/grid/key"));
        assertTrue(bridge.contains("/enc/delta"));
        assertTrue(bridge.contains("onGridTilt(int deviceId, int x, int y, int z)"));
        assertTrue(bridge.contains("serialosc includes a sensor index"));
        assertTrue(boot.contains("serial.add_handler({"));
        assertTrue(boot.contains("id = \"norns.crow.usb\""));
        assertTrue(boot.contains("attrs.vendor == \"monome & whimsical raps\""));
        assertTrue(boot.contains("attrs.model == \"crow: telephone line\""));
        assertTrue(boot.contains("attrs.vendor_id == 1155 and attrs.product_id == 22336"));
        assertTrue(boot.contains("norns.crow.events[code] = fn"));
        assertTrue(boot.contains("function norns.crow.loadscript(file, is_persistent, continuation)"));
    }

    @Test public void serialBackendExposesNornsHandlersAndCDCTransportWithoutClaimingVendorDrivers() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String bridge = read("app/src/main/java/org/nornsmobile/app/UsbCdcSerialBridge.java");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        assertTrue(boot.contains("serial = {"));
        assertTrue(boot.contains("function serial.add_handler(args)"));
        assertTrue(boot.contains("function serial.send(dev, data)"));
        assertTrue(boot.contains("function serial.refresh()"));
        assertTrue(boot.contains("function _norns.serial.match(vendor, model, serial_num, interface_num, vendor_id, product_id)"));
        assertTrue(boot.contains("function _norns.serial.configure(handler_id, term)"));
        assertTrue(boot.contains("function _norns.serial.add(handler_id, id, name, dev)"));
        assertTrue(boot.contains("function _norns.serial.remove(handler_id, id)"));
        assertTrue(boot.contains("function _norns.serial.event(handler_id, id, data)"));
        assertTrue(boot.contains("function _norns.serial_data(id, data)"));
        assertTrue(bridge.contains("CDC-ACM"));
        assertTrue(bridge.contains("findDataInterface"));
        assertTrue(bridge.contains("interfaceName=String.format(\"%02x\",interfaceId)"));
        assertTrue(bridge.contains("FTDI, CP210x, CH340"));
        assertTrue(host.contains("usb_serial_devices"));
        assertTrue(host.contains("usbSerial.write"));
    }

    @Test public void crowHasSpecificCDCTransportAndNornsCommandEventBridge() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("local function crow_send(command)"));
        assertTrue(boot.contains("crow_send(\"^^s\")"));
        assertTrue(boot.contains("local function crow_parse_args(source)"));
        assertTrue(boot.contains("local function crow_receive(data)"));
        assertTrue(boot.contains("crow.output = crow_proxy(\"output\")"));
        assertTrue(boot.contains("crow.input = crow_proxy(\"input\")"));
        assertTrue(boot.contains("crow.ii = crow_proxy(\"ii\")"));
    }

    @Test public void gamepadHasGenericAndroidButtonAndAxisBackend() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("gamepad = {"));
        assertTrue(boot.contains("available = true, connected = false, devices = {}, state = {"));
        assertTrue(boot.contains("function _norns.gamepad_button(name, value, id, device_name)"));
        assertTrue(boot.contains("function _norns.gamepad_axis(sensor_axis, value, half_reso, id, device_name)"));
    }

    @Test public void physicalKeyboardIsImplementedRatherThanReportedAsUnavailable() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("keyboard = {available = true, state = {}, _code_chars = {}}"));
        assertTrue(boot.contains("function keyboard.code_to_char(code)"));
        assertTrue(boot.contains("function _norns.keyboard_event(code, value, ch, shift, alt, ctrl, meta,"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
@Test public void serialoscGridAndArcCallbacksReachTheNornsLuaHandlers() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(host.contains("callNorns(\"grid_key\""));
        assertTrue(host.contains("callNorns(\"grid_tilt\""));
        assertTrue(host.contains("callNorns(\"arc_delta\""));
        assertTrue(host.contains("callNorns(\"arc_key\""));
        assertTrue(boot.contains("_norns.grid_key = function(id, x, y, z)"));
        assertTrue(boot.contains("_norns.grid_tilt = function(id, x, y, z)"));
        assertTrue(boot.contains("_norns.arc_delta = function(id, n, delta)"));
        assertTrue(boot.contains("_norns.arc_key = function(id, n, state)"));
        assertTrue(boot.contains("safe(d.key, x, y, z)"));
        assertTrue(boot.contains("safe(d.tilt, x, y, z)"));
        assertTrue(boot.contains("safe(d.delta, n, delta)"));
        assertTrue(boot.contains("safe(d.key, n, z)"));
    }

@Test public void serialScriptCleanupAndHotplugCallbacksRetirePendingAndActivePorts() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int cleanup = boot.indexOf("function serial.cleanup()");
        int end = boot.indexOf("_norns.serial = {}", cleanup);
        assertTrue(cleanup >= 0 && end > cleanup);
        String cleanupPath = boot.substring(cleanup, end);
        assertTrue(cleanupPath.contains("for id in pairs(serial._pending) do host.usb_serial_close(id) end"));
        assertTrue(cleanupPath.contains("for id, port in pairs(serial._ports) do"));
        assertTrue(cleanupPath.contains("_norns.serial.remove(port.handler_id, id)"));
        assertTrue(cleanupPath.contains("serial._ports, serial._pending = {}, {}"));
        assertTrue(cleanupPath.contains("Keep registered transports"));
        assertTrue(boot.contains("function _norns.serial_connected(id, name)"));
        assertTrue(boot.contains("function _norns.serial_data(id, data)"));
        assertTrue(boot.contains("for offset = 1, #payload, 255 do"));
        assertTrue(boot.contains("function _norns.serial_disconnected(id, reason)"));
        assertTrue(boot.contains("serial._pending[id], serial._ports[id] = nil, nil"));
        assertTrue(host.contains("callNorns(\"serial_connected\""));
        assertTrue(host.contains("callNorns(\"serial_data\""));
        assertTrue(host.contains("callNorns(\"serial_disconnected\""));
    }

@Test public void gamepadStateIsAggregatedAcrossDevicesWithoutStaleButtons() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("gamepad._device_state = gamepad._device_state or {}"));
        assertTrue(boot.contains("state.buttons[name] = numeric > 0"));
        assertTrue(boot.contains("for key in pairs(state.buttons or {}) do gamepad.state[key] = false end"));
        assertTrue(boot.contains("if pressed then gamepad.state[key] = true end"));
        assertTrue(boot.contains("gamepad_rebuild_state()"));
        assertTrue(boot.contains("gamepad._device_state[id] = nil"));
        assertTrue(boot.contains("local released_buttons = {}"));
        assertTrue(boot.contains("if not held_elsewhere and type(gamepad.button) == \"function\" then"));
        assertTrue(boot.contains("safe(gamepad.button, name, 0)"));
    }

@Test public void gamepadAxisCallbacksAreScopedPerDeviceAndRejectInvalidValues() throws Exception {
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(boot.contains("local axisKey = tostring(deviceId) .. \":\" .. sensor_axis"));
        assertTrue(boot.contains("gamepad._axis_signs[axisKey] ~= sign"));
        assertTrue(boot.contains("if not v or v ~= v or v == math.huge or v == -math.huge then return false end"));
        assertTrue(boot.contains("v = math.max(-1, math.min(1, v))"));
        assertTrue(boot.contains("if string.sub(key, 1, #prefix) == prefix then gamepad._axis_signs[key] = nil end"));
    }

@Test public void hidDevicesExposeAndroidKeyboardMouseAndGamepadCapabilities() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        String boot = read("app/src/main/assets/lua/boot.lua");
        assertTrue(host.contains("device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC"));
        assertTrue(host.contains("(sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE"));
        assertTrue(host.contains("(sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD"));
        assertTrue(host.contains("LuaValue.valueOf(asciiKeyboard), LuaValue.valueOf(mouse),"));
        assertTrue(boot.contains("function _norns.hid_device_add(id, name, vendor, product, ascii_keyboard, mouse, gamepad_device)"));
        assertTrue(boot.contains("device.is_ascii_keyboard = ascii_keyboard == true"));
        assertTrue(boot.contains("device.is_mouse = mouse == true"));
        assertTrue(boot.contains("device.is_gamepad = gamepad_device == true"));
        assertTrue(boot.contains("if added and type(hid.add) == \"function\" then safe(hid.add, device) end"));
    }

}
