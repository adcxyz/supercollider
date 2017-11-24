Server {

	classvar <>local, <>internal, <default;
	classvar <>named, <>all, <>program, <>sync_s = true;
	classvar <>nodeAllocClass, <>bufferAllocClass, <>busAllocClass;
	classvar <>postingBootInfo = false;

	var <name, <addr, <clientID;
	var <isLocal, <inProcess, <>bootAndQuitDisabled = false;
	var maxNumClients; // maxLogins as sent from booted scsynth

	var <>options, <>latency = 0.2, <dumpMode = 0;

	var <nodeAllocator, <controlBusAllocator, <audioBusAllocator, <bufferAllocator, <scopeBufferAllocator;

	var <>tree;
	var <defaultGroup, <defaultGroups;

	var <syncThread, <syncTasks;
	var <window, <>scopeWindow, <emacsbuf;
	var <volume, <recorder, <process;
	var <pid, serverInterface;

	*initClass {
		Class.initClassTree(ServerOptions);
		Class.initClassTree(NotificationCenter);
		named = IdentityDictionary.new;
		all = Set.new;

		nodeAllocClass = NodeIDAllocator;
		// nodeAllocClass = ReadableNodeIDAllocator;
		bufferAllocClass = ContiguousBlockAllocator;
		busAllocClass = ContiguousBlockAllocator;

		default = local = Server.new(\localhost, NetAddr("127.0.0.1", 57110));
		internal = Server.new(\internal, NetAddr.new);
	}

	*fromName { |name|
		^Server.named[name] ?? {
			Server(name, NetAddr.new("127.0.0.1", 57110), ServerOptions.new)
		}
	}

	*default_ { |server|
		default = server;
		if(sync_s) { thisProcess.interpreter.s = server };  // sync with s?
		this.all.do { |each| each.changed(\default, server) };
	}

	*new { |name, addr, options, clientID|
		var existing = Server.all.detect { |sv| sv.name == name };
		if (existing.notNil) {
			"Server(%) already exists, returning existing.\n"
			.format(name).warn;
			^existing
		};
		^super.new.init(name, addr, options, clientID)
	}

	*remote { |name, addr, options, clientID|
		var result = this.new(name, addr, options, clientID);
		result.bootAndQuitDisabled = true;
		result.process.startWatching;
		^result;
	}

	init { |argName, argAddr, argOptions, argClientID|
		this.addr = argAddr;
		options = argOptions ?? { ServerOptions.new };

		// set name to get readable posts from clientID set
		name = argName.asSymbol;

		// make statusWatcher/process before clientID
		process = ServerProcess(this);

		// go thru setter to test validity
		this.clientID = argClientID ? 0;

		volume = Volume(server: this, persist: true);
		recorder = Recorder(server: this);
		recorder.notifyServer = true;

		this.name = argName;
		all.add(this);

		Server.changed(\serverAdded, this);

	}

	maxNumClients { ^maxNumClients ?? { options.maxLogins } }

	remove {
		all.remove(this);
		named.removeAt(this.name);
		ServerTree.objects !? { ServerTree.objects.removeAt(this) };
		ServerBoot.objects !? { ServerBoot.objects.removeAt(this) };
	}

	addr_ { |netAddr|
		addr = netAddr ?? { NetAddr("127.0.0.1", 57110) };
		inProcess = addr.addr == 0;
		isLocal = inProcess || { addr.isLocal };
	}

	name_ { |argName|
		name = argName.asSymbol;
		if(named.at(argName).notNil) {
			"Server name already exists: '%'. Please use a unique name".format(name, argName).warn;
		} {
			named.put(name, this);
		}
	}

	state { ^process.state }
	state_ { |newState| process.state_(newState) }

	initTree {
		if (Server.postingBootInfo) { "% .%\n".postf(this, thisMethod.name) };
		forkIfNeeded({
			this.sendDefaultGroups;
			tree.value(this);
			ServerTree.run(this);
		}, AppClock);
	}

	// everything up to ServerBoot runs in ServerProcess,
	// ServerTree and used code goes here:
	prRunTree {
		this.state_(\isSettingUp);
		if (Server.postingBootInfo) { "%.%\n".postf(this, thisMethod.name) };

		forkIfNeeded( {
			if (Server.postingBootInfo) { "prRun: % .%\n".postf(this, "initTree") };
			this.initTree;
			this.sync;
			this.state_(\isReady);
			process.serverRunning (true);
			this.changed(\serverRunning);
		}, AppClock);
	}

	doWhenBooted { |onComplete, limit = 100, onFailure|
		"*** % (-: % - this method intentionally left blank ;-)\n"
		"... just needs proper condition handling.".postf(thisMethod);

		// ^statusWatcher.doWhenBooted(onComplete, limit, onFailure);
	}

	/* id allocators */

	// clientID is settable while server is off, and locked while server is running
	// called from prHandleClientLoginInfoFromServer once after booting.
	clientID_ { |val|
		var failstr = "Server % couldn't set clientID to: % - %. clientID is still %.\n";
		if (this.serverRunning) {
			"%: setting clientID is locked after server is fully booted."
			.postf(thisMethod);
			^this
		};

		if(val.isInteger.not) {
			failstr.format(name, val.cs, "not an Integer", clientID).warn;
			^this
		};
		if (val < 0 or: { val >= this.maxNumClients }) {
			failstr.format(name,
				val.cs,
				"outside of allowed server.maxNumClients range of 0 - %".format(this.maxNumClients),
				clientID
			).warn;
			^this
		};

		if (clientID != val) {
			"% : setting clientID to %.\n".postf(this, val);
		};
		clientID = val;
		this.newAllocators;
	}

	newAllocators {
		if (Server.postingBootInfo) { "%.%\n".postf(this, thisMethod.name) };

		this.newNodeAllocators;
		this.newBusAllocators;
		this.newBufferAllocators;
		this.newScopeBufferAllocators;
		NotificationCenter.notify(this, \newAllocators);
	}

	newNodeAllocators {
		nodeAllocator = nodeAllocClass.new(
			clientID,
			options.initialNodeID,
			this.maxNumClients
		);
		// defaultGroup and defaultGroups depend on allocator,
		// so always make them here:
		this.makeDefaultGroups;
	}

	newBusAllocators {
		var numControlPerClient, numAudioPerClient;
		var controlReservedOffset, controlBusClientOffset;
		var audioReservedOffset, audioBusClientOffset;

		var audioBusIOOffset = options.firstPrivateBus;

		numControlPerClient = options.numControlBusChannels div: this.maxNumClients;
		numAudioPerClient = options.numAudioBusChannels - audioBusIOOffset div: this.maxNumClients;

		controlReservedOffset = options.reservedNumControlBusChannels;
		controlBusClientOffset = numControlPerClient * clientID;

		audioReservedOffset = options.reservedNumAudioBusChannels;
		audioBusClientOffset = numAudioPerClient * clientID;

		controlBusAllocator = busAllocClass.new(
			numControlPerClient,
			controlReservedOffset,
			controlBusClientOffset
		);
		audioBusAllocator = busAllocClass.new(
			numAudioPerClient,
			audioReservedOffset,
			audioBusClientOffset + audioBusIOOffset
		);
	}

	newBufferAllocators {
		var numBuffersPerClient = options.numBuffers div: this.maxNumClients;
		var numReservedBuffers = options.reservedNumBuffers;
		var bufferClientOffset = numBuffersPerClient * clientID;

		bufferAllocator = bufferAllocClass.new(
			numBuffersPerClient,
			numReservedBuffers,
			bufferClientOffset
		);
	}

	newScopeBufferAllocators {
		if(isLocal) {
			scopeBufferAllocator = StackNumberAllocator.new(0, 127)
		}
	}

	nextBufferNumber { |n|
		var bufnum = bufferAllocator.alloc(n);
		if(bufnum.isNil) {
			if(n > 1) {
				Error("No block of % consecutive buffer numbers is available.".format(n)).throw
			} {
				Error("No more buffer numbers -- free some buffers before allocating more.").throw
			}
		};
		^bufnum
	}

	freeAllBuffers {
		var bundle;
		bufferAllocator.blocks.do { arg block;
			(block.address .. block.address + block.size - 1).do { |i|
				bundle = bundle.add( ["/b_free", i] );
			};
			bufferAllocator.free(block.address);
		};
		this.sendBundle(nil, *bundle);
	}

	nextNodeID {
		^nodeAllocator.alloc
	}

	nextPermNodeID {
		^nodeAllocator.allocPerm
	}

	freePermNodeID { |id|
		^nodeAllocator.freePerm(id)
	}

	prHandleClientLoginInfoFromServer { |newClientID, newMaxLogins|

		if (Server.postingBootInfo) {
			"% % - newClientID: % newMaxLogins: %.\n"
			.postf(this, thisMethod.name, newClientID, newMaxLogins);
		};

		// turn notified off to allow setting clientID
		process.notified = false;

		// only set maxLogins if not internal server
		if (inProcess.not) {
			if (newMaxLogins.notNil) {
				if (newMaxLogins != options.maxLogins) {
					"%: server process has maxLogins % - adjusting my options accordingly.\n"
					.postf(this, newMaxLogins);
				} {
					"%: server process's maxLogins (%) matches with my options.\n"
					.postf(this, newMaxLogins);
				};
				options.maxLogins = maxNumClients = newMaxLogins;
			} {
				"%: no maxLogins info from server process.\n"
				.postf(this, newMaxLogins);
			};
		};

		if (newClientID == clientID) {
			"%: keeping clientID (%) as confirmed by server process.\n"
			.postf(this, newClientID);
		} {
			"%: setting clientID to %, as obtained from server process.\n"
			.postf(this, newClientID);
		};
		this.clientID = newClientID;
		process.notified = true; // and lock again

	}

	prHandleNotifyFailString {|failString, msg|

		if (Server.postingBootInfo) { "% .%\n".postf(this, thisMethod.name) };

		// post info on some known error cases
		case
		{ failString.asString.contains("already registered") } {
			"% - already registered with clientID %.\n".postf(this, msg[3]);
			process.notified = true;
		} { failString.asString.contains("not registered") } {
			// unregister when already not registered:
			"% - not registered.\n".postf(this);
			process.notified = false;
		} { failString.asString.contains("too many users") } {
			"% - could not register, too many users.\n".postf(this);
			process.notified = false;
		} {
			// throw error if unknown failure
			Error(
				"Failed to register with server '%' for notifications: %\n"
				"To recover, please reboot the server.".format(this, msg)).throw;
		};
	}

	/* network messages */

	sendMsg { |... msg|
		addr.sendMsg(*msg)
	}

	sendBundle { |time ... msgs|
		addr.sendBundle(time, *msgs)
	}

	sendRaw { |rawArray|
		addr.sendRaw(rawArray)
	}

	sendMsgSync { |condition ... args|
		var cmdName, resp;
		if(condition.isNil) { condition = Condition.new };
		cmdName = args[0].asString;
		if(cmdName[0] != $/) { cmdName = cmdName.insert(0, $/) };
		resp = OSCFunc({|msg|
			if(msg[1].asString == cmdName) {
				resp.free;
				condition.test = true;
				condition.signal;
			};
		}, '/done', addr);
		condition.test = false;
		addr.sendBundle(nil, args);
		condition.wait;
	}

	sync { |condition, bundles, latency| // array of bundles that cause async action
		addr.sync(condition, bundles, latency)
	}

	schedSync { |func|
		syncTasks = syncTasks.add(func);
		if(syncThread.isNil) {
			syncThread = Routine.run {
				var c = Condition.new;
				while { syncTasks.notEmpty } { syncTasks.removeAt(0).value(c) };
				syncThread = nil;
			}
		}
	}

	listSendMsg { |msg|
		addr.sendMsg(*msg)
	}

	listSendBundle { |time, msgs|
		addr.sendBundle(time, *(msgs.asArray))
	}

	reorder { |nodeList, target, addAction=\addToHead|
		target = target.asTarget;
		this.sendMsg(62, Node.actionNumberFor(addAction), target.nodeID, *(nodeList.collect(_.nodeID))) //"/n_order"
	}

	// load from disk locally, send remote
	sendSynthDef { |name, dir|
		var file, buffer;
		dir = dir ? SynthDef.synthDefDir;
		file = File(dir ++ name ++ ".scsyndef","r");
		if(file.isNil) { ^nil };
		protect {
			buffer = Int8Array.newClear(file.length);
			file.read(buffer);
		} {
			file.close;
		};
		this.sendMsg("/d_recv", buffer);
	}

	// tell server to load from disk
	loadSynthDef { |name, completionMsg, dir|
		dir = dir ? SynthDef.synthDefDir;
		this.listSendMsg(
			["/d_load", dir ++ name ++ ".scsyndef", completionMsg ]
		)
	}

	//loadDir
	loadDirectory { |dir, completionMsg|
		this.listSendMsg(["/d_loadDir", dir, completionMsg])
	}


	/* network message bundling */

	openBundle { |bundle|	// pass in a bundle that you want to
		// continue adding to, or nil for a new bundle.
		if(addr.hasBundle) {
			bundle = addr.bundle.addAll(bundle);
			addr.bundle = []; // debatable
		};
		addr = BundleNetAddr.copyFrom(addr, bundle);
	}

	closeBundle { |time| // set time to false if you don't want to send.
		var bundle;
		if(addr.hasBundle) {
			bundle = addr.closeBundle(time);
			addr = addr.saveAddr;
		} {
			"there is no open bundle.".warn
		};
		^bundle;
	}

	makeBundle { |time, func, bundle|
		this.openBundle(bundle);
		try {
			func.value(this);
			bundle = this.closeBundle(time);
		} {|error|
			addr = addr.saveAddr; // on error restore the normal NetAddr
			error.throw
		}
		^bundle
	}

	bind { |func|
		^this.makeBundle(this.latency, func)
	}


	/* scheduling */

	wait { |responseName|
		var routine = thisThread;
		OSCFunc({
			routine.resume(true)
		}, responseName, addr).oneShot;
	}

	waitForBoot { |onComplete, limit = 100, onFailure|
		var routine = Routine (onComplete);

		if (Server.postingBootInfo) { "% waitForBoot ... ".postf(this) };

		if (this.isReady) {
			routine.play;
			^routine
		};

		this.boot(onComplete: { routine.play(AppClock); },
			onFailure: onFailure,
			timeout: limit * 0.2 // 0.2 was wait timestep in loop
		);
		^routine
	}

	ifRunning { |func, failFunc|
		^if(process.unresponsive) {
			"server '%' not responsive".format(this.name).postln;
			failFunc.value(this)
		} {
			if(process.serverRunning) {
				func.value(this)
			} {
				"server '%' not running".format(this.name).postln;
				failFunc.value(this)
			}
		}

	}

	ifNotRunning { |func|
		^ifRunning(this, nil, func)
	}


	bootSync { |condition|
		if (Server.postingBootInfo) { "% .%\n".postf(this, thisMethod.name) };

		condition ?? { condition = Condition.new };
		condition.test = false;
		this.waitForBoot {
			// Setting func to true indicates that our condition has become true and we can go when signaled.
			condition.test = true;
			condition.signal
		};
		condition.wait;
	}


	ping { |n = 1, wait = 0.1, func|
		var result = 0, pingFunc;
		if(process.serverRunning.not) { "server not running".postln; ^this };
		pingFunc = {
			Routine.run {
				var t, dt;
				t = Main.elapsedTime;
				this.sync;
				dt = Main.elapsedTime - t;
				("measured latency:" + dt + "s").postln;
				result = max(result, dt);
				n = n - 1;
				if(n > 0) {
					SystemClock.sched(wait, { pingFunc.value; nil })
				} {
					("maximum determined latency of" + name + ":" + result + "s").postln;
					func.value(result)
				}
			};
		};
		pingFunc.value;
	}

	cachedBuffersDo { |func|
		Buffer.cachedBuffersDo(this, func)
	}

	cachedBufferAt { |bufnum|
		^Buffer.cachedBufferAt(this, bufnum)
	}

	// defaultGroups for all clients on this server:

	allClientIDs { ^(0..this.maxNumClients-1) }

	// keep defaultGroups for all clients on this server:
	makeDefaultGroups {
		defaultGroups = this.allClientIDs.collect { |clientID|
			Group.basicNew(this, nodeAllocator.numIDs * clientID + 1);
		};
		defaultGroup = defaultGroups[clientID];
	}

	defaultGroupID { ^defaultGroup.nodeID }

	sendDefaultGroups {
		defaultGroups.do { |group|
			this.sendMsg("/g_new", group.nodeID, 0, 0);
		};
	}

	sendDefaultGroupsForClientIDs { |clientIDs|
		defaultGroups[clientIDs].do { |group|
			this.sendMsg("/g_new", group.nodeID, 0, 0);
		}
	}

	inputBus {
		^Bus(\audio, this.options.numOutputBusChannels, this.options.numInputBusChannels, this)
	}

	outputBus {
		^Bus(\audio, 0, this.options.numOutputBusChannels, this)
	}

	/* recording formats */

	recHeaderFormat { ^options.recHeaderFormat }
	recHeaderFormat_ { |string| options.recHeaderFormat_(string) }
	recSampleFormat { ^options.recSampleFormat }
	recSampleFormat_ { |string| options.recSampleFormat_(string) }
	recChannels { ^options.recChannels }
	recChannels_ { |n| options.recChannels_(n) }
	recBufSize { ^options.recBufSize }
	recBufSize_ { |n| options.recBufSize_(n) }

	/* server status */

	numUGens { ^process.numUGens }
	numSynths { ^process.numSynths }
	numGroups { ^process.numGroups }
	numSynthDefs { ^process.numSynthDefs }
	avgCPU { ^process.avgCPU }
	peakCPU { ^process.peakCPU }
	sampleRate { ^process.sampleRate }
	actualSampleRate { ^process.actualSampleRate }

	hasBooted { ^process.hasBooted }
	serverRunning { ^process.serverRunning }
	serverBooting { ^process.serverBooting }
	unresponsive { ^process.unresponsive }
	isOff { ^process.isOff }
	isBooting { ^process.isBooting }
	isSettingUp { ^process.isSettingUp }
	isReady { ^process.isReady }
	isQuitting { ^process.isQitting }

	///// backwards compatibility
	// statusWatcher:
	statusWatcher { ^process }
	startAliveThread { | delay=0.0 | process.startWatching(delay) }
	stopAliveThread { process.stopWatching }
	aliveThreadIsRunning { ^process.aliveThread.isPlaying }
	aliveThreadPeriod_ { |val| process.aliveThreadPeriod_(val) }
	aliveThreadPeriod { |val| ^process.aliveThreadPeriod }

	// these were semi-duplicates already,
	// and may be deprecated at some point
	remoteControlled_ { |bool| bootAndQuitDisabled = bool }
	remoteControlled { ^bootAndQuitDisabled }
	sendQuit_ { |bool| bootAndQuitDisabled = bool.not }
	sendQuit { ^bootAndQuitDisabled.not }

	userSpecifiedClientID {
		thisMethod.deprecated;
		"userSpecifiedClientID is obsolete: clientID can now be changed while server is off, and is locked when server has booted.".postln;
	}

	// these are absorbed into the ServerProcess:boot method now
	bootInit {
		thisMethod.deprecated;
		"bootInit happens in Server:boot now, so you need not call it explicitly."
		.postln;
	}
	bootServerApp {
		thisMethod.deprecated;
		"bootServerApp happens in Server:boot now, so you need not call it explicitly."
		.postln;
	}


	//  SharedMemory
	disconnectSharedMemory {
		if(this.hasShmInterface) {
			"server '%' disconnected shared memory interface\n".postf(name);
			serverInterface.disconnect;
			serverInterface = nil;
		}
	}

	connectSharedMemory {
		var id;
		if(this.isLocal) {
			this.disconnectSharedMemory;
			id = if(this.inProcess) { thisProcess.pid } { addr.port };
			serverInterface = ServerShmInterface(id);
		}
	}

	hasShmInterface { ^serverInterface.notNil }

	*resumeThreads {
		all.do { |server| server.statusWatcher.resumeThread }
	}

	boot { | startAliveThread = true, recover = false, onFailure, onComplete, timeout = 5 |
		process.boot(onComplete, timeout, onFailure, recover)
	}

	reboot { |funcWhenOff, onFailure| // func is evaluated when server is off
		if (process.canBoot.not) {
			"can't reboot a remote server".postln;
			^this
		};

		if(process.hasBooted and: { process.unresponsive.not }) {
			process.quit({
				funcWhenOff.value(this);
				defer { this.boot }
			}, onFailure);
		} {
			funcWhenOff.value(this);
			this.boot(onFailure: onFailure)
		}
	}

	applicationRunning { ^this.processRunning }

	status {
		addr.sendStatusMsg // backward compatibility
	}

	sendStatusMsg {
		addr.sendStatusMsg
	}

	notify {
		^process.notify
	}

	notify_ { |flag|
		process.notify_(flag)
	}

	notified {
		^process.notified
	}

	dumpOSC { |code = 1|
		/*
		0 - turn dumping OFF.
		1 - print the parsed contents of the message.
		2 - print the contents in hexadecimal.
		3 - print both the parsed and hexadecimal representations of the contents.
		*/
		dumpMode = code;
		this.sendMsg(\dumpOSC, code);
		this.changed(\dumpOSC, code);
	}

	quit { |onComplete, onFailure, watchShutDown = true|
		process.quit(onComplete, onFailure, watchShutDown);
	}

	cleanupAfterQuit {
		this.disconnectSharedMemory;
		if(scopeWindow.notNil) { scopeWindow.quit };
		if(this.isRecording) { this.stopRecording };
		volume.freeSynth;
		RootNode(this).freeAll;
		// this.newAllocators;

		NotificationCenter.notify(this, \didQuit);
		{
			this.changed(\didQuit);
			this.changed(\serverRunning);
		}.defer;
	}

	*quitAll { |watchShutDown = true|
		all.do { |server|
			if(server.process.canBoot) {
				server.quit(watchShutDown: watchShutDown)
			}
		}
	}

	*killAll {
		// if you see Exception in World_OpenUDP: unable to bind udp socket
		// its because you have multiple servers running, left
		// over from crashes, unexpected quits etc.
		// you can't cause them to quit via OSC (the boot button)

		// this brutally kills them all off
		thisProcess.platform.killAll(this.program.basename);
		this.quitAll(watchShutDown: false);
	}

	freeAll {
		this.sendMsg("/g_freeAll", 0);
		this.sendMsg("/clearSched");
		this.initTree;
	}

	freeMyDefaultGroup {
		this.sendMsg("/g_freeAll", defaultGroup.nodeID);
	}

	freeDefaultGroups {
		defaultGroups.do { |group|
			this.sendMsg("/g_freeAll", group.nodeID);
		};
	}

	*freeAll { |evenRemote = false|
		if(evenRemote) {
			all.do { |server|
				if( server.serverRunning ) { server.freeAll }
			}
		} {
			all.do { |server|
				if(server.isLocal and:{ server.serverRunning }) { server.freeAll }
			}
		}
	}

	*hardFreeAll { |evenRemote = false|
		if(evenRemote) {
			all.do { |server|
				server.freeAll
			}
		} {
			all.do { |server|
				if(server.isLocal) { server.freeAll }
			}
		}
	}

	*allRunningServers {
		^this.all.select(_.serverRunning)
	}

	/* volume control */

	volume_ { | newVolume |
		volume.volume_(newVolume)
	}

	mute {
		volume.mute
	}

	unmute {
		volume.unmute
	}

	/* recording output */

	record { |path, bus, numChannels, node, duration| recorder.record(path, bus, numChannels, node, duration) }
	isRecording { ^recorder.isRecording }
	pauseRecording { recorder.pauseRecording }
	stopRecording { recorder.stopRecording }
	prepareForRecord { |path, numChannels| recorder.prepareForRecord(path, numChannels) }

	/* internal server commands */

	bootInProcess {
		^options.bootInProcess;
	}
	quitInProcess {
		_QuitInProcessServer
		^this.primitiveFailed
	}
	allocSharedControls { |numControls=1024|
		_AllocSharedControls
		^this.primitiveFailed
	}
	setSharedControl { |num, value|
		_SetSharedControl
		^this.primitiveFailed
	}
	getSharedControl { |num|
		_GetSharedControl
		^this.primitiveFailed
	}

	// redirect to process.ping?
	prPingApp { |func, onFailure, timeout = 3|
		var id = func.hash;
		var resp = OSCFunc({ |msg| if(msg[1] == id, { func.value; task.stop }) }, "/synced", addr);
		var task = timeout !? { fork { timeout.wait; resp.free;  onFailure.value } };
		addr.sendMsg("/sync", id);
	}

	/* CmdPeriod support for Server-scope and Server-record and Server-volume */

	cmdPeriod {
		addr = addr.recover;
		this.changed(\cmdPeriod)
	}

	queryAllNodes { | queryControls = false |
		var resp, done = false;
		if(isLocal) {
			this.sendMsg("/g_dumpTree", 0, queryControls.binaryValue)
		} {
			resp = OSCFunc({ |msg|
				var i = 2, tabs = 0, printControls = false, dumpFunc;
				if(msg[1] != 0) { printControls = true };
				("NODE TREE Group" + msg[2]).postln;
				if(msg[3] > 0) {
					dumpFunc = {|numChildren|
						var j;
						tabs = tabs + 1;
						numChildren.do {
							if(msg[i + 1] >= 0) { i = i + 2 } {
								i = i + 3 + if(printControls) { msg[i + 3] * 2 + 1 } { 0 };
							};
							tabs.do { "   ".post };
							msg[i].post; // nodeID
							if(msg[i + 1] >= 0) {
								" group".postln;
								if(msg[i + 1] > 0) { dumpFunc.value(msg[i + 1]) };
							} {
								(" " ++ msg[i + 2]).postln; // defname
								if(printControls) {
									if(msg[i + 3] > 0) {
										" ".post;
										tabs.do { "   ".post };
									};
									j = 0;
									msg[i + 3].do {
										" ".post;
										if(msg[i + 4 + j].isMemberOf(Symbol)) {
											(msg[i + 4 + j] ++ ": ").post;
										};
										msg[i + 5 + j].post;
										j = j + 2;
									};
									"\n".post;
								}
							}
						};
						tabs = tabs - 1;
					};
					dumpFunc.value(msg[3]);
				};
				done = true;
			}, '/g_queryTree.reply', addr).oneShot;

			this.sendMsg("/g_queryTree", 0, queryControls.binaryValue);
			SystemClock.sched(3, {
				if(done.not) {
					resp.free;
					"Remote server failed to respond to queryAllNodes!".warn;
				};
			})
		}
	}

	printOn { |stream|
		stream << name;
	}

	storeOn { |stream|
		var codeStr = switch(this,
			Server.default, { if(sync_s) { "s" } { "Server.default" } },
			Server.local,	{ "Server.local" },
			Server.internal, { "Server.internal" },
			{ "Server.fromName(" + name.asCompileString + ")" }
		);
		stream << codeStr;
	}

	archiveAsCompileString { ^true }
	archiveAsObject { ^true }

	getControlBusValue {|busIndex|
		if(serverInterface.isNil) {
			Error("Server-getControlBusValue only supports local servers").throw;
		} {
			^serverInterface.getControlBusValue(busIndex)
		}
	}

	getControlBusValues {|busIndex, busChannels|
		if(serverInterface.isNil) {
			Error("Server-getControlBusValues only supports local servers").throw;
		} {
			^serverInterface.getControlBusValues(busIndex, busChannels)
		}
	}

	setControlBusValue {|busIndex, value|
		if(serverInterface.isNil) {
			Error("Server-getControlBusValue only supports local servers").throw;
		} {
			^serverInterface.setControlBusValue(busIndex, value)
		}
	}

	setControlBusValues {|busIndex, valueArray|
		if(serverInterface.isNil) {
			Error("Server-getControlBusValues only supports local servers").throw;
		} {
			^serverInterface.setControlBusValues(busIndex, valueArray)
		}
	}

	*scsynth {
		this.program = this.program.replace("supernova", "scsynth")
	}

	*supernova {
		this.program = this.program.replace("scsynth", "supernova")
	}

}
