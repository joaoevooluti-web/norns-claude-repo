// Laboratório de engines: carrega uma engine do norns num scsynth local, dispara cada
// comando com argumentos de teste e registra as mensagens OSC resultantes.
(
var engineName = "LAB_ENGINE".getenv;
var outDir = "LAB_OUT".getenv;
var srv;
Server.default = Server.local;
srv = Server.default;
srv.options.numOutputBusChannels = 2;
srv.options.numInputBusChannels = 2;
srv.options.memSize = 65536;
srv.latency = 0.05;
LabNetAddr.open(outDir);
srv.waitForBoot({
	var ctx, engine, cls, done = false;
	srv.addr = LabNetAddr(srv.addr.hostname ? "127.0.0.1", srv.addr.port);
	CroneDefs.sendDefs(srv);
	srv.sync;
	ctx = CroneAudioContext.new(srv);
	srv.sync;
	cls = engineName.asSymbol.asClass;
	if(cls.isNil, {
		LabNetAddr.mark("ERROR classe não encontrada: " ++ engineName);
		0.exit;
	});
	LabNetAddr.mark("ENGINE_LOAD_BEGIN " ++ engineName);
	engine = cls.new(ctx, { arg e; done = true; });
	40.do({ if(done.not, { 0.5.wait }) });
	LabNetAddr.mark("ENGINE_LOAD_" ++ if(done, "OK", "TIMEOUT"));
	srv.sync;
	LabNetAddr.mark("POLLS " ++ engine.pollNames.asArray.collect(_.asString).join(","));
	engine.commands.do({ arg cmd;
		var fmt = cmd.format.asString;
		var args = fmt.collect({ arg c;
			switch(c, $f, { 0.5 }, $i, { 1 }, $s, { "test" }, { 0 });
		});
		LabNetAddr.mark("CMD " ++ cmd.name ++ " " ++ fmt ++ " args=" ++ args.asString);
		try {
			NetAddr.localAddr.sendMsg(*(["/command/" ++ cmd.name] ++ args));
		} { arg err;
			LabNetAddr.mark("CMD_ERROR " ++ cmd.name ++ " " ++ err.errorString);
		};
		0.2.wait;
	});
	srv.sync;
	LabNetAddr.mark("DONE");
	0.exit;
});
)
