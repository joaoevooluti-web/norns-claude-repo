package org.nornsmobile.app;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class SuperColliderSoftcutBuffersContractTest {
    @Test public void nativeRecorderRejectsInvalidRangesAndNonFiniteControls() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/SuperColliderSoftcutBuffers.java");
        assertTrue(source.contains("requiredFrames > 16_777_216"));
        assertTrue(source.contains("endFrame > requiredFrames"));
        assertTrue(source.contains("endFrame > 16_777_216"));
        assertTrue(source.contains("!isFinite(position) || position < 0"));
        assertTrue(source.contains("!isFinite(rate)"));
        assertTrue(source.contains("!isFinite(recLevel)"));
        assertTrue(source.contains("!isFinite(preLevel)"));
        assertTrue(source.contains("!isFinite(inputLevel)"));
        assertTrue(source.contains("!isFinite(recPreSlew) || recPreSlew < 0"));
        assertTrue(source.contains("position > requiredFrames / (double)SuperColliderBridge.sampleRate()"));
        assertTrue(source.contains("!Float.isFinite((float)position)"));
        assertTrue(source.contains("!Float.isFinite((float)recPreSlew)"));
    }

    @Test public void recorderCursorReanchorIsFiniteAndBufferBounded() throws Exception {
        String source = read("app/src/main/java/org/nornsmobile/app/SuperColliderSoftcutBuffers.java");
        int start = source.indexOf("static boolean reanchorRecordingCursor");
        int end = source.indexOf("static boolean needsFullSnapshot", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("!isFinite(positionSeconds)"));
        assertTrue(method.contains("positionSeconds < 0"));
        assertTrue(method.contains("positionSeconds > capacity / (double)SuperColliderBridge.sampleRate()"));
        assertTrue(method.contains("!Float.isFinite((float)positionSeconds)"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        if (!file.isFile()) throw new AssertionError("Missing source " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
