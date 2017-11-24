ServerOptions {
	// order of variables is important here. Only add new instance variables to the end.
	var <numAudioBusChannels=1024;
	var <>numControlBusChannels=16384;
	var <numInputBusChannels=2;
	var <numOutputBusChannels=2;
	var <>numBuffers=1026;

	var <>maxNodes=1024;
	var <>maxSynthDefs=1024;
	var <>protocol = \udp;
	var <>blockSize = 64;
	var <>hardwareBufferSize = nil;

	var <>memSize = 8192;
	var <>numRGens = 64;
	var <>numWireBufs = 64;

	var <>sampleRate = nil;
	var <>loadDefs = true;

	var <>inputStreamsEnabled;
	var <>outputStreamsEnabled;

	var <>inDevice = nil;
	var <>outDevice = nil;

	var <>verbosity = 0;
	var <>zeroConf = false; // Whether server publishes port to Bonjour, etc.

	var <>restrictedPath = nil;
	var <>ugenPluginsPath = nil;

	var <>initialNodeID = 1000;
	var <>remoteControlVolume = false;

	var <>memoryLocking = false;
	var <>threads = nil; // for supernova
	var <>useSystemClock = false;  // for supernova

	var <numPrivateAudioBusChannels=1020;

	var <>reservedNumAudioBusChannels = 0;
	var <>reservedNumControlBusChannels = 0;
	var <>reservedNumBuffers = 0;
	var <>pingsBeforeConsideredDead = 5;


	var <>maxLogins = 1;

	var <>recHeaderFormat="aiff";
	var <>recSampleFormat="float";
	var <>recChannels = 2;
	var <>recBufSize = nil;


	device {
		^if(inDevice == outDevice) {
			inDevice
		} {
			[inDevice, outDevice]
		}
	}

	device_ { |dev|
		inDevice = outDevice = dev;
	}

	asOptionsString { | port = 57110 |
		var o;
		o = if(protocol == \tcp, " -t ", " -u ");
		o = o ++ port;

		o = o ++ " -a " ++ (numPrivateAudioBusChannels + numInputBusChannels + numOutputBusChannels) ;

		if (numControlBusChannels != 16384, {
			o = o ++ " -c " ++ numControlBusChannels;
		});
		if (numInputBusChannels != 8, {
			o = o ++ " -i " ++ numInputBusChannels;
		});
		if (numOutputBusChannels != 8, {
			o = o ++ " -o " ++ numOutputBusChannels;
		});
		if (numBuffers != 1024, {
			o = o ++ " -b " ++ numBuffers;
		});
		if (maxNodes != 1024, {
			o = o ++ " -n " ++ maxNodes;
		});
		if (maxSynthDefs != 1024, {
			o = o ++ " -d " ++ maxSynthDefs;
		});
		if (blockSize != 64, {
			o = o ++ " -z " ++ blockSize;
		});
		if (hardwareBufferSize.notNil, {
			o = o ++ " -Z " ++ hardwareBufferSize;
		});
		if (memSize != 8192, {
			o = o ++ " -m " ++ memSize;
		});
		if (numRGens != 64, {
			o = o ++ " -r " ++ numRGens;
		});
		if (numWireBufs != 64, {
			o = o ++ " -w " ++ numWireBufs;
		});
		if (sampleRate.notNil, {
			o = o ++ " -S " ++ sampleRate;
		});
		if (loadDefs.not, {
			o = o ++ " -D 0";
		});
		if (inputStreamsEnabled.notNil, {
			o = o ++ " -I " ++ inputStreamsEnabled ;
		});
		if (outputStreamsEnabled.notNil, {
			o = o ++ " -O " ++ outputStreamsEnabled ;
		});
		if ((thisProcess.platform.name!=\osx) or: {inDevice == outDevice})
		{
			if (inDevice.notNil,
				{
					o = o ++ " -H %".format(inDevice.quote);
			});
		}
		{
			o = o ++ " -H % %".format(inDevice.asString.quote, outDevice.asString.quote);
		};
		if (verbosity != 0, {
			o = o ++ " -V " ++ verbosity;
		});
		if (zeroConf.not, {
			o = o ++ " -R 0";
		});
		if (restrictedPath.notNil, {
			o = o ++ " -P " ++ restrictedPath;
		});
		if (ugenPluginsPath.notNil, {
			o = o ++ " -U " ++ if(ugenPluginsPath.isString) {
				ugenPluginsPath
			} {
				ugenPluginsPath.join("; ");
			};
		});
		if (memoryLocking, {
			o = o ++ " -L";
		});
		if (threads.notNil, {
			if (Server.program.asString.endsWith("supernova")) {
				o = o ++ " -T " ++ threads;
			}
		});
		if (useSystemClock.notNil, {
			o = o ++ " -C " ++ useSystemClock.asInteger
		});
		if (maxLogins.notNil, {
			o = o ++ " -l " ++ maxLogins;
		});
		^o
	}

	firstPrivateBus { // after the outs and ins
		^numOutputBusChannels + numInputBusChannels
	}

	bootInProcess {
		_BootInProcessServer
		^this.primitiveFailed
	}

	numPrivateAudioBusChannels_ { |numChannels = 112|
		numPrivateAudioBusChannels = numChannels;
		this.recalcChannels;
	}

	numAudioBusChannels_ { |numChannels=1024|
		numAudioBusChannels = numChannels;
		numPrivateAudioBusChannels = numAudioBusChannels - numInputBusChannels - numOutputBusChannels;
	}

	numInputBusChannels_ { |numChannels=8|
		numInputBusChannels = numChannels;
		this.recalcChannels;
	}

	numOutputBusChannels_ { |numChannels=8|
		numOutputBusChannels = numChannels;
		this.recalcChannels;
	}

	recalcChannels {
		numAudioBusChannels = numPrivateAudioBusChannels + numInputBusChannels + numOutputBusChannels;
	}

	*prListDevices {
		arg in, out;
		_ListAudioDevices
		^this.primitiveFailed
	}

	*devices {
		^this.prListDevices(1, 1);
	}

	*inDevices {
		^this.prListDevices(1, 0);
	}

	*outDevices {
		^this.prListDevices(0, 1);
	}
}
