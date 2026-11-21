package org.nornsmobile.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class LuaHostNativeEngineSelectionTest {
    @Test public void nativeOnlyEnginesStartTheNativeServer() {
        assertTrue(LuaHost.scriptNeedsNativeEngine("engine.name = \"Stratum\"\n"));
        assertTrue(LuaHost.scriptNeedsNativeEngine("engine.name='32Harmony'"));
        assertTrue(LuaHost.scriptNeedsNativeEngine("engine.name  =  \"SimpleDelayKolor\""));
    }

    @Test public void javaEnginesKeepTheJavaMixerAvailable() {
        assertFalse(LuaHost.scriptNeedsNativeEngine("engine.name = \"PolyPerc\""));
        assertFalse(LuaHost.scriptNeedsNativeEngine("engine.name = \"TestSine\""));
        assertFalse(LuaHost.scriptNeedsNativeEngine("print('no engine')"));
        assertFalse(LuaHost.scriptNeedsNativeEngine(null));
    }
}
