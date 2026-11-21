package org.nornsmobile.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class NornsInputDispatcherTest {
    private static final class RecordingSink implements NornsInputDispatcher.Sink {
        final List<String> events = new ArrayList<String>();
        @Override public void onKey(int n, int z) { events.add("key:" + n + ":" + z); }
        @Override public void onEnc(int n, int d) { events.add("enc:" + n + ":" + d); }
    }

    @Test public void keyPressEmitsOneDownAndOneUp() {
        RecordingSink sink = new RecordingSink();
        NornsInputDispatcher input = new NornsInputDispatcher(sink);
        assertTrue(input.keyDown(1));
        assertFalse("duplicate down must be suppressed", input.keyDown(1));
        assertTrue(input.keyUp(1));
        assertFalse("duplicate up must be suppressed", input.keyUp(1));
        assertEquals(2, sink.events.size());
        assertEquals("key:1:1", sink.events.get(0));
        assertEquals("key:1:0", sink.events.get(1));
    }

    @Test public void releaseAllPreventsStuckKeysAndIsIdempotent() {
        RecordingSink sink = new RecordingSink();
        NornsInputDispatcher input = new NornsInputDispatcher(sink);
        input.keyDown(1);
        input.keyDown(3);
        input.releaseAll();
        input.releaseAll();
        assertFalse(input.isPressed(1));
        assertFalse(input.isPressed(2));
        assertFalse(input.isPressed(3));
        assertEquals(4, sink.events.size());
        assertEquals("key:1:0", sink.events.get(2));
        assertEquals("key:3:0", sink.events.get(3));
    }

    @Test public void encoderForwardsSignedIntegerDeltas() {
        RecordingSink sink = new RecordingSink();
        NornsInputDispatcher input = new NornsInputDispatcher(sink);
        assertTrue(input.encoderDelta(2, 4));
        assertTrue(input.encoderDelta(2, -3));
        assertFalse(input.encoderDelta(2, 0));
        assertEquals("enc:2:4", sink.events.get(0));
        assertEquals("enc:2:-3", sink.events.get(1));
        assertEquals(2, sink.events.size());
    }

    @Test public void invalidControlNumbersAreIgnored() {
        RecordingSink sink = new RecordingSink();
        NornsInputDispatcher input = new NornsInputDispatcher(sink);
        assertFalse(input.keyDown(0));
        assertFalse(input.keyDown(4));
        assertFalse(input.keyUp(1));
        assertFalse(input.encoderDelta(4, 1));
        assertTrue(sink.events.isEmpty());
    }

    @Test public void releaseAllOnlyReleasesCurrentlyPressedKeys() {
        RecordingSink sink = new RecordingSink();
        NornsInputDispatcher input = new NornsInputDispatcher(sink);
        input.keyDown(2);
        input.keyUp(2);
        input.keyDown(2);
        input.releaseAll();
        assertEquals(4, sink.events.size());
        assertEquals("key:2:1", sink.events.get(0));
        assertEquals("key:2:0", sink.events.get(1));
        assertEquals("key:2:1", sink.events.get(2));
        assertEquals("key:2:0", sink.events.get(3));
    }
}
