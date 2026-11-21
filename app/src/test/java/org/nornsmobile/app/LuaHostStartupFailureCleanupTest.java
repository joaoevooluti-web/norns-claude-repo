package org.nornsmobile.app;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class LuaHostStartupFailureCleanupTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        assertTrue("Missing source: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void failedBootInvokesLuaCleanupBeforeDiscardingTheVm() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int helperStart = host.indexOf("private void cleanupFailedLuaStart()");
        int helperEnd = host.indexOf("private synchronized void failStart", helperStart);
        assertTrue(helperStart >= 0 && helperEnd > helperStart);
        String helper = host.substring(helperStart, helperEnd);
        assertTrue(helper.contains("Globals g = globals"));
        assertTrue(helper.contains("g.get(\"_norns\")"));
        assertTrue(helper.contains("norns.get(\"cleanup\")"));
        assertTrue(helper.contains("cleanup.call()"));
        assertTrue(helper.contains("if (g == null) return"));
        assertTrue(helper.contains("norns.isnil()"));

        int failStart = helperEnd;
        int failEnd = host.indexOf("private void boot(", failStart);
        assertTrue(failEnd > failStart);
        String failurePath = host.substring(failStart, failEnd);
        int invalidate = failurePath.indexOf("exec = null;");
        int cleanup = failurePath.indexOf("cleanupFailedLuaStart();");
        int discardVm = failurePath.indexOf("globals = null;");
        assertTrue("failed start must invalidate queued callbacks before cleanup", invalidate >= 0 && cleanup > invalidate);
        assertTrue("cleanup must run while the Lua VM is still reachable", discardVm > cleanup);
        assertTrue("native/Java audio and transports must still be retired after Lua cleanup",
                failurePath.indexOf("stopSuperCollider();", cleanup) > cleanup
                        && failurePath.indexOf("midi.closeAll();", cleanup) > cleanup
                        && failurePath.indexOf("usbSerial.closeAll();", cleanup) > cleanup);
    }

    @Test public void failedBootCleanupDoesNotChangeNormalStopOrdering() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int stop = host.indexOf("private void stopInternal()");
        int stopEnd = host.indexOf("private void cleanupFailedLuaStart()", stop);
        assertTrue(stop >= 0 && stopEnd > stop);
        String normalStop = host.substring(stop, stopEnd);
        assertTrue(normalStop.contains("callNorns(\"cleanup\")"));
        assertTrue(normalStop.contains("f.get(700, TimeUnit.MILLISECONDS)"));
        assertTrue(normalStop.indexOf("callNorns(\"cleanup\")") < normalStop.indexOf("ex.shutdownNow()"));
    }
}
