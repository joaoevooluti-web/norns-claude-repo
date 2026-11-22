// Intercepta tudo que o sclang envia ao scsynth: registra o texto das mensagens e
// grava cada SynthDef (/d_recv) como .scsyndef. Usado só pelo laboratório de engines.
LabNetAddr : NetAddr {
	classvar <logFile, <outDir;

	*open { arg dir;
		outDir = dir;
		logFile = File(dir +/+ "osc.log", "w");
	}

	*mark { arg text;
		if(logFile.notNil, { logFile.write("### " ++ text ++ "\n"); logFile.flush; });
		("### " ++ text).postln;
	}

	*record { arg msg;
		var parts;
		if(logFile.isNil, { ^this });
		if(msg[0].asString == "/d_recv", {
			var bytes = msg[1];
			var len = bytes[10];
			var name = String.newFrom(bytes[11..(10 + len)].collect({ arg b; b.asInteger.asAscii }));
			var f = File(outDir +/+ "synthdefs" +/+ name ++ ".scsyndef", "wb");
			f.write(bytes);
			f.close;
		});
		parts = msg.collect({ arg e;
			if(e.isKindOf(Int8Array), { "<blob " ++ e.size ++ ">" }, { e.asString });
		});
		logFile.write(parts.join(" ") ++ "\n");
		logFile.flush;
	}

	sendMsg { arg ... args;
		LabNetAddr.record(args);
		^super.sendMsg(*args);
	}

	sendBundle { arg time ... args;
		args.do({ arg m; LabNetAddr.record(m) });
		^super.sendBundle(time, *args);
	}
}
