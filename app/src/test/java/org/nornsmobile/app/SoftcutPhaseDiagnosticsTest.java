package org.nornsmobile.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class SoftcutPhaseDiagnosticsTest {
 @Test public void wrapsForwardAcrossLoopEnd(){assertEquals(0.02,SoftcutPhaseDiagnostics.signedErrorSeconds(9.99,0.01,0,10,true),1e-9);}
 @Test public void wrapsBackwardAcrossLoopStart(){assertEquals(-0.02,SoftcutPhaseDiagnostics.signedErrorSeconds(0.01,9.99,0,10,true),1e-9);}
 @Test public void remainsLinearWithoutLoop(){assertEquals(-9.98,SoftcutPhaseDiagnostics.signedErrorSeconds(9.99,0.01,0,10,false),1e-9);}
 @Test public void tracksMaxAndRmsAndIgnoresNonFiniteSamples(){
  SoftcutPhaseDiagnostics d=new SoftcutPhaseDiagnostics();d.record(1,1.001,0,10,false);d.record(1,1.003,0,10,false);
  SoftcutPhaseDiagnostics.Snapshot s=d.record(1,Double.NaN,0,10,false);
  assertEquals(2,s.samples);assertEquals(0.003,s.lastErrorSeconds,1e-9);assertEquals(0.003,s.maxAbsErrorSeconds,1e-9);
  assertEquals(Math.sqrt((0.001*0.001+0.003*0.003)/2),s.rmsErrorSeconds,1e-9);
  d.reset();assertEquals(0,d.snapshot().samples);
 }
}