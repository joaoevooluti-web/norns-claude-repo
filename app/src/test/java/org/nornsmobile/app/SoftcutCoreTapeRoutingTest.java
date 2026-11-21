package org.nornsmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SoftcutCoreTapeRoutingTest {
    @Test public void tapeSendRecordsIntoSoftcutIndependentlyOfAdcAndEngine() {
        SoftcutCore core = new SoftcutCore(48000);
        core.command(1, "loop_end", 0.1);
        core.command(1, "position", 0);
        core.command(1, "input_level", 0);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec", 1);
        core.command(0, "tape_level", 0.75);

        float[] output = new float[4];
        float[] tape = new float[]{0.4f, -0.2f};
        core.render(output, 2, 48000, null, null, tape);

        float[] recorded = core.snapshotBuffer(1, 0, 2.0 / 48000.0);
        assertEquals(2, recorded.length);
        assertEquals(0.3f, recorded[0], 0.0001f);
        assertEquals(-0.15f, recorded[1], 0.0001f);
    }

    @Test public void tapeSendDefaultsToMuted() {
        SoftcutCore core = new SoftcutCore(48000);
        core.command(1, "loop_end", 0.1);
        core.command(1, "input_level", 0);
        core.command(1, "rec_level", 1);
        core.command(1, "pre_level", 0);
        core.command(1, "rec", 1);
        core.render(new float[2], 1, 48000, null, null, new float[]{1f});
        float[] recorded = core.snapshotBuffer(1, 0, 1.0 / 48000.0);
        assertEquals(0f, recorded[0], 0.0001f);
    }
}
