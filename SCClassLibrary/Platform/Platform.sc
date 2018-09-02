Platform {
	classvar defaultStartupFile;

	// IDE actions
	classvar <>makeServerWindowAction, <>makeSynthDescWindowAction, <>openHelpFileAction, <>openHTMLFileAction;

	classvar <classLibraryDir, <helpDir, <>recordingsDir, features;

	*initClass {
		defaultStartupFile = this.userConfigDir +/+ "startup.scd"
	}

	*initPlatform {
		classLibraryDir = thisMethod.filenameSymbol.asString.dirname.dirname;
		helpDir = thisMethod.filenameSymbol.asString.dirname.dirname.dirname ++ "/Help";
		features = IdentityDictionary.new;
		recordingsDir = this.userAppSupportDir +/+ "Recordings";
	}

	*platformName { ^thisProcess.platform.platformName }

	*recompile {
		_Recompile
		^this.primitiveFailed
	}
	*case { | ... cases |
		^thisProcess.platform.name.switch(*cases)
	}

	*userHomeDir {
		_Platform_userHomeDir
		^this.primitiveFailed
	}

	*systemAppSupportDir {
		_Platform_systemAppSupportDir
		^this.primitiveFailed
	}

	*userAppSupportDir {
		_Platform_userAppSupportDir
		^this.primitiveFailed
	}

	*systemExtensionDir {
		_Platform_systemExtensionDir
		^this.primitiveFailed
	}

	*userExtensionDir {
		_Platform_userExtensionDir
		^this.primitiveFailed
	}

	*userConfigDir {
		_Platform_userConfigDir
		^this.primitiveFailed
	}

	*resourceDir {
		_Platform_resourceDir
		^this.primitiveFailed
	}

	*defaultTempDir { ^thisProcess.platform.defaultTempDir }

	// The "ideName" is for ide-dependent compilation.
	// From SC.app, the value is "scapp" meaning "scide_scapp" folders will be compiled and other "scide_*" ignored.
	*ideName {
		_Platform_ideName
		^this.primitiveFailed
	}

	*platformDir { ^this.platformName.asString }

	*pathSeparator { ^thisProcess.platform.pathSeparator }
	*pathDelimiter { ^thisProcess.platform.pathDelimiter }
	*isPathSeparator { |char| ^thisProcess.platform.isPathSeparator(char) }

	*clearMetadata { |path| ^thisProcess.platform.clearMetadata(path) }

	// startup/shutdown hooks
	*startup { }
	*shutdown { }

	*startupFiles {
		^[defaultStartupFile];
	}

	*deprecatedStartupFiles {|paths|
		var postWarning = false;
		paths.do {|path|
			if (File.exists(path.standardizePath)) {
				warn("Deprecated startup file found: %\n".format(path));
				postWarning = true;
			}
		};
		if (postWarning) {
			postln("Please use % as startup file.\nDeprecated startup files will be ignored in future versions.\n".format(defaultStartupFile));
		}
	}

	*loadStartupFiles { this.startupFiles.do{|afile|
		afile = afile.standardizePath;
		if(File.exists(afile), {afile.load})
		}
	}

	// features
	*declareFeature { | aFeature |
		var str = aFeature.asString;
		if (str.first.isUpper) {
			Error("cannot declare class name features").throw;
		};
		if (str.first == $_) {
			Error("cannot declare primitive name features").throw;
		};
		features.put(aFeature, true);
	}
	*hasFeature { | symbol |
		if (features.includesKey(symbol).not) {
			features.put(
				symbol,
				symbol.asSymbol.asClass.notNil or: { symbol.isPrimitive }
			)
		};
		^features.at(symbol)
	}

	*when { | features, ifFunction, elseFunction |
		^features.asArray.inject(true, { |v,x|
			v and: { this.hasFeature(x) }
		}).if(ifFunction, elseFunction)
	}

	// Prefer qt but fall back to swing if qt not installed.
	*defaultGUIScheme { if (GUI.get(\qt).notNil) {^\qt} {^\swing} }

	*isSleeping { ^false } // unless defined otherwise

	// hook for clients to write frontend.css
	*writeClientCSS {}

	*killProcessByID { |pid|
		^thisProcess.platform.killProcessByID(pid)
	}

	*killAll { |cmdLineArgs|
		^thisProcess.platform.killAll(cmdLineArgs)
	}

	// used to format paths correctly for command-line calls
	// On Windows, encloses with quotes; on Unix systems, escapes spaces.
	*formatPathForCmdLine { |path|
		^thisProcess.platform.formatPathForCmdLine(path)
	}

}

UnixPlatform : Platform {
	*pathSeparator { ^$/ }
    *pathDelimiter { ^$: }

	*isPathSeparator { |char|
		^(char === this.pathSeparator)
	}

	*clearMetadata { |path|
		"rm -f %\.*meta".format(path.splitext[0].escapeChar($ )).systemCmd;
	}

	*arch {
		var pipe, arch;
		pipe = Pipe("arch", "r");
		arch = pipe.getLine;
		pipe.close;
		^arch.asSymbol;
	}

	*killProcessByID { |pid|
		("kill -9 " ++ pid).unixCmd;
	}

	*killAll { |cmdLineArgs|
		("killall -9 " ++ cmdLineArgs).unixCmd;
	}

	*defaultTempDir {
		// +/+ "" looks funny but ensures trailing slash
		^["/tmp/", this.userAppSupportDir +/+ ""].detect({ |path|
			File.exists(path);
		});
	}

	*formatPathForCmdLine { |path|
		^path.escapeChar($ );
	}

}
