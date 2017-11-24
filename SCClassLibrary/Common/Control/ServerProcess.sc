// ServerProcess handles boot, quit, and running status of a server process.
// status code copied and adapted from ServerStatusWatcher
// boot and quit copied and refactored from Server

// Server now focuses on 'content': allocation, synths, controls.

/* TODO:
+ Tests for every if-branch in boot method
+ add remote method


// These already run!
TestServer_boot.run;
TestServer_clientID.run;
TestServer_clientID_booted.run;

s.boot; // superfast! sometimes 0.5 - 1 secs, compared to 1.3 - 2 secs earlier.

s.boot(onComplete: { "READY".postln; }, onFailure: { "failed".postln; }, timeout: 5);

s.quit;
s.reboot;
s.waitForBoot { Env.perc.test };


// test for missing scsynth:
Server.program = "noValidProgramName";
s.boot; // fails very quickly

// currently turned off:
s.doWhenBooted;

///--- not working yet ---///
r = Server.remote(\remote);

*/

ServerProcess {

	classvar states = #[\isOff, \isBooting, \isSettingUp, \isReady, \isQuitting];

	var <server;
	var <>bootAndQuitDisabled = false;

	var <state = \isOff, <processRunning = false, <pid;
	var <hasBooted = false, <isAlive = false, <unresponsive = false;
	var <notified = false, <notify = true;

	// reported state from process, status messages
	var <numUGens=0, <numSynths=0, <numGroups=0, <numSynthDefs=0;
	var <avgCPU, <peakCPU;
	var <sampleRate, <actualSampleRate;

	// maxLogins from booted scsynth, could also be in config
	var <maxLoginsFromProcess;
	// locked dict with all config info from server process.
	var configFromProcess;

	var <bootStartedTime = 0;

	// conditions that trigger ServerBoot, ServerQuit,
	// doWhenBooted routines etc
	var <hasBootedCondition, <hasQuitCondition, isReadyCondition;

	// watcher
	var <aliveThread, <>aliveThreadPeriod = 0.7, <watcher;
	var <>pingsBeforeDead, <reallyDeadCount = 0, <bootNotifyFirst = true;

	*new { |server|
		^super.newCopyArgs(server).init
	}

	init {
		configFromProcess = ();
		pingsBeforeDead = server.options.pingsBeforeConsideredDead;
		hasBootedCondition = Condition({ this.pidRunning });
		hasQuitCondition = Condition({ this.hasQuit });
		isReadyCondition = Condition({ this.isReady });
	}

	// process state
	state_ { |newState|
		if (states.includes(newState)) {
			state = newState;
			"%: process state is now %.\n".postf(server.name.cs, newState.cs);
		} {
			warn(
				"%:% - % is not a valid process state."
				.format(this, thisMethod.name, newState)
			);
		}
	}

	isBooting { ^state == \isBooting }
	isReady { ^state == \isReady }
	isSettingUp { ^state == \isSettingUp }
	isQitting { ^state == \isQuitting }

	storeArgs { ^[server.name] }
	printOn { |stream| this.storeOn(stream) }

	////////////// booting /////////////

	// read only config data from running process:
	configAt { |key| ^configFromProcess[key] }

	isLocal { ^server.addr.isLocal }
	inProcess { ^server.addr.addr == 0 }
	canBoot { ^bootAndQuitDisabled.not and: { this.isLocal } }

	// measure time for all boot steps:
	timeSinceBoot { ^(thisThread.seconds - bootStartedTime).round(0.0001) }

	postAt { |str="", always = true|
		var timeStr = this.timeSinceBoot.asString.keep(6);
		if (always or: { Server.postingBootInfo }) {
			"% bootAt % : %".format(server.name.cs, timeStr, str).postln
		}
	}

	boot { |onComplete, timeout = 5, onFailure, recover = false|
		var remainingTimeout, bootRout;
		bootStartedTime = thisThread.seconds;

		if(this.canBoot.not) {
			"% You cannot boot a remote or bootAndQuitDisabled server.\n".postf(this);
			^this
		};

		// prepare for reboot if unresponsive - is this ok?
		if(recover.not and: { this.unresponsive }) {
			this.postAt("% unresponsive, rebooting ...");
			this.quitWatcher(watchShutDown: false)
		};
		// early exits
		if(this.isReady) {
			"% already running".format(server).postln;
			if (onComplete.notNil) {
				" - running onComplete.".postln;
				onComplete.value(server);
			};
			^this
		};

		if(this.isBooting) { "% already booting".format(this).postln; ^this };

		this.state = \isBooting;

		this.postAt("starting boot routine:\n", false);

		bootRout = Routine {
			// if there is a server at my address, re-adopt or reboot it
			var cond1 = Condition.new, cond2 = Condition.new;
			var recovered = false;
			var origAlivePeriod = this.aliveThreadPeriod;

			this.postAt("pinging server process first ... ", true);

			server.prPingApp({
				// we find a server process already running at addr!
				// this is the rare exception, so post when it happens
				if (recover) {
					this.postAt("found running server process, recovering it.");
					hasBooted = true;
					recovered = true;
					cond1.unhang;
				} {
					this.postAt("found running server process, rebooting.");
					this.reboot;
					bootRout.stop;
				};
			}, {

				this.postAt("try to boot server process.", true);

				// this is the default case
				this.bootServerApp({
					this.postAt("within bootServerApp...");
					0.1.wait; // wait needed for failed pid to go away first
					processRunning = pid.pidRunning;
					if (processRunning) {
						hasBooted = true;
						this.postAt("unhang because app has booted");
						cond1.unhang;
					} {
						this.prBootFailed(onFailure);
						bootRout.stop;
					}
				})
			}, 0.25);

			cond1.hang(timeout);
			this.postAt("cond1 unhung after process boot or recover.", false);

			if (hasBooted) {
				//	app has booted OK, so continue
				//  TODO: copy boot state from options ...
				this.fillConfig;

				// ( wait faster ;-)
				aliveThreadPeriod = 0.1;
				this.startWatching(0.1);
				// TODO - TEMP FIX; REMOVE and do proper check
				server.changed(\serverRunning);

				fork {
					0.02.wait;
					while { (isAlive and: notified).not } { 0.1.wait };
					this.postAt(" booted and notified, so unhang cond2!", false);
					this.aliveThreadPeriod = origAlivePeriod;
					cond2.unhang;
				};
			} {
				// failed to boot, so exit
				this.prBootFailed(onFailure);
				bootRout.stop;
			};

			remainingTimeout = (timeout - this.timeSinceBoot).max(0.6).round(0.01);
			this.postAt("cond2.hang(timeout: %).".format(remainingTimeout));
			cond2.hang(remainingTimeout);

			this.postAt("cond2 unhung ...", false);

			if (isAlive and: notified) {
				this.postAt("running and notified - starting setup", false);
				this.state_(\isSettingUp);
				this.prRunBootSetup;

				this.postAt("running ServerTree setup ... ", false);

				server.prRunTree;
				this.state_(\isReady);
				this.postAt("<- total boot time.");
				this.postAt("running onComplete.", false);
				onComplete.value(this);
				this.postAt("onComplete done.", false);
				"*** server % is ready!".postf(server);
				isReadyCondition.signal
			} {
				this.prBootFailed(onFailure);
				bootRout.stop;
			};

		}.play(AppClock)
	}

	prBootFailed { |onFailure|
		"****** ".post;
		this.postAt(
			"\n****** BOOT FAILED OR TIMED OUT!"
			"\n****** running onFailure."
		);
		onFailure.value(server);
	}

	bootServerApp { |onComplete|
		this.state = \isBooting;
		if(this.inProcess) {
			"%: booting internal process\n".postf(server);
			this.bootInProcess;
			pid = thisProcess.pid;
			forkIfNeeded { onComplete.value };
		} {
			server.disconnectSharedMemory;
			pid = unixCmd(
				Server.program ++ server.options.asOptionsString(server.addr.port),
				{
					this.postAt(" has quit, cleaning up.");
					this.quitWatcher(watchShutDown:false);
				}
			);
			this.postAt("booting on addr %:%.".format(*server.addr.storeArgs));
			if(server.options.protocol == \tcp, {
				server.addr.tryConnectTCP(onComplete)
			}, onComplete);
		}
	}

	///////////////
	prRunBootSetup {
		forkIfNeeded( {
			this.postAt("runBootSetup ...", false);
			if(server.dumpMode != 0) { server.sendMsg(\dumpOSC, server.dumpMode) };
			server.connectSharedMemory;

			ServerBoot.run(server);
			server.sync;
			this.postAt("runBootSetup finished.", false);

			// signal boot-side setup done
			hasBootedCondition.unhang;

		}, AppClock)
	}

	ping { |func, onFailure, timeout = 3|
		var id, resp, task, now = thisThread.seconds;
		func = func ? { "% ping returned in % secs.\n".postf(server, thisThread.seconds - now) };
		id = func.hash;
		resp = OSCFunc({ |msg| if(msg[1] == id, { func.value; task.stop }) },
			"/synced",
			server.addr);
		task = timeout !? { fork { timeout.wait; resp.free;  onFailure.value } };
		server.addr.sendMsg("/sync", id);
	}

	fillConfig {
		// copyAllOptions to config so we know what the state was when booting...
	}

	/////// status reports from process ///////
	addWatcher {
		if(watcher.isNil) {
			watcher =
			OSCFunc({ arg msg;
				var cmd, one;
				if(notify and: { notified.not }) { this.sendNotifyRequest };
				isAlive = true;
				#cmd, one, numUGens, numSynths, numGroups, numSynthDefs,
				avgCPU, peakCPU, sampleRate, actualSampleRate = msg;
				{
					this.updateRunningState(true);
					server.changed(\counts);
				}.defer;
			}, '/status.reply', server.addr).fix;
		} {
			watcher.enable;
		}
	}

	startWatching { | delay = 0.5 |

		this.postAt("% with delay %.\n".postf(thisMethod.name, delay), false);

		this.addWatcher;

		^aliveThread ?? {
			// this thread polls the server to see if it is isAlive
			aliveThread = Routine {
				delay.wait;
				loop {
					isAlive = false;
					if (Server.postingBootInfo) {
						"% . sendStatusMsg...\n".postf(server);
					};
					server.sendStatusMsg;
					aliveThreadPeriod.wait;
					this.updateRunningState(isAlive);
				};
			}.play(AppClock);
			aliveThread
		}
	}

	stopWatching {
		aliveThread.stop;
		aliveThread = nil;
		this.disableWatcher;
	}

	disableWatcher { watcher !? { watcher.disable } }

	freeWatcher {

		watcher.free;
		watcher = nil;

		aliveThread.stop;
		isAlive = false;
		aliveThread = nil;
	}

	resumeThread { this.startWatching }

	serverRunning { ^hasBooted and: notified }

	serverRunning_ { | running |
		isAlive = running;
		this.unresponsive = false;

		if(running != server.serverRunning) {
			server.changed(\serverRunning);
			server.cleanupAfterQuit;
			if (running.not) {
				this.quit;
				notified = false;
				this.state_(\isOff);
			};
		}

	}

	updateRunningState { | running |
		if(server.addr.hasBundle) {
			{ server.changed(\bundling) }.defer;
		} {
			if(running) {
				this.serverRunning = true;
				this.unresponsive = false;
				reallyDeadCount = server.options.pingsBeforeConsideredDead;
			} {
				// parrot
				reallyDeadCount = reallyDeadCount - 1;
				this.unresponsive = (reallyDeadCount <= 0);
			}
		}
	}

	unresponsive_ { | val |
		if (val != unresponsive) {
			unresponsive = val;
			{ server.changed(\serverRunning) }.defer;
		}
	}

	//////// notification /////////
	notify_ { |flag = true|
		notify = flag;
		this.sendNotifyRequest(flag);
	}

	// flag true requests notification, false turns it off
	sendNotifyRequest { |flag = true|
		var doneOSCFunc, failOSCFunc;

		if(hasBooted.not) { ^this };

		this.postAt("request notify: %".format(flag), false);

		// set up oscfuncs for possible server responses, \done or \failed
		doneOSCFunc = OSCFunc({ |msg|
			var newClientID = msg[2], newMaxLogins = msg[3];
			failOSCFunc.free;

			if (newClientID.isNil) {
				// then msg was done notify off;
				notified = false;
				this.postAt("notify is off now.", false)
			} {
				notified = true;
				// on registering scsynth sends back a free clientID and its maxLogins,
				// which usually adjust the server object's settings:
				server.prHandleClientLoginInfoFromServer(newClientID, newMaxLogins);
			};

		}, '/done', server.addr, argTemplate:['/notify', nil]).oneShot;

		failOSCFunc = OSCFunc({|msg|

			doneOSCFunc.free;
			server.prHandleNotifyFailString(msg[2], msg);

		}, '/fail', server.addr, argTemplate:['/notify', nil, nil]).oneShot;

		server.sendMsg("/notify", flag.binaryValue, server.clientID);

		if(flag){
			"Requested notification messages from server '%'\n".postf(server.name)
		} {
			"Switched off notification messages from server '%'\n".postf(server.name);
		};
	}

	/////// quitting /////////
	quit { |onComplete, onFailure, watchShutDown = true|
		var func;

		if(bootAndQuitDisabled or:{ this.isLocal.not }) {
			"%: You will have to manually quit this server.\n".postf(server);
			^this
		};

		hasBooted = false;
		this.state_(\isQuitting);

		server.addr.sendMsg("/quit");

		if(watchShutDown and: { this.unresponsive }) {
			"Server '%' was unresponsive. Quitting anyway.".format(server.name).postln;
			watchShutDown = false;
		};

		if(server.options.protocol == \tcp) {
			this.quitWatcher({ server.addr.tryDisconnectTCP(onComplete, onFailure) }, nil, watchShutDown);
		} {
			this.quitWatcher(onComplete, onFailure, watchShutDown);
		};

		if(this.inProcess) {
			server.quitInProcess;
			"quit done\n".postln;
		} {
			"'/quit' sent\n".postln;
		};

		processRunning = hasBooted = notified = isAlive = false;
		unresponsive = false;
		pid = nil;

		this.state_(\isOff);
		server.statusWatcher.serverRunning_(false);
		server.changed(\serverRunning);

	}

	quitWatcher { |onComplete, onFailure, watchShutDown = true|

		if(watchShutDown) {
			this.watchQuit(onComplete, onFailure)
		} {
			this.disableWatcher;
			onComplete.value;
		};
		this.stopWatching;
		notified = false;
		this.state_(\isOff);
	}


	// move back to Server
	doWhenBooted { |onComplete, limit = 100, onFailure|
		var mBootNotifyFirst = bootNotifyFirst, postError = true;
		bootNotifyFirst = false;

		if (server.isReady) {
			^forkIfNeeded({ onComplete.value(server) })
		};

		^Routine {
			while {
				this.isReady.not
				and: { (limit = limit - 1) > 0 }
			} {
				0.2.wait;
			};

			if(this.isReady.not, {
				if(onFailure.notNil) {
					postError = (onFailure.value(server) == false);
				};
				if(postError) {
					"Server '%' on failed to start. You may need to kill all servers".format(server.name).error;
				};
				server.changed(\serverRunning);
			}, {
				server.sync;
				onComplete.value;
			});

		}.play(AppClock)
	}


	watchQuit { |onComplete, onFailure|
		var	serverReallyQuitWatcher, serverReallyQuit = false;
		watcher !? {
			watcher.disable;
			if(notified) {
				serverReallyQuitWatcher = OSCFunc({ |msg|
					if(msg[1] == '/quit') {
						watcher !? { watcher.enable };
						serverReallyQuit = true;
						serverReallyQuitWatcher.free;
						onComplete.value;
					}
				}, '/done', server.addr);

				AppClock.sched(3.0, {
					if(serverReallyQuit.not) {
						if(unresponsive) {
							"Server '%' remained unresponsive during quit."
						} {
							"Server '%' failed to quit after 3.0 seconds."
						}.format(server.name).warn;
						// don't accumulate quit-watchers if /done doesn't come back
						serverReallyQuitWatcher.free;
						watcher !? { watcher.disable };
						onFailure.value(server)
					}
				})
			}
		}
	}

}
