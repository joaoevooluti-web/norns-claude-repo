package org.nornsmobile.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LuaHostEngineAvailabilityTest {
    @Test public void readsEngineName() {
        assertEquals("Ack", LuaHost.scriptEngineName("engine.name = 'Ack'\n"));
        assertEquals("PolyPerc", LuaHost.scriptEngineName("engine.name=\"PolyPerc\""));
        assertNull(LuaHost.scriptEngineName("print('sem engine')"));
        assertNull(LuaHost.scriptEngineName(null));
    }
}
