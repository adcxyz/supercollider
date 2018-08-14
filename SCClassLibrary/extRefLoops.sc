+ Object {
	hasReferenceLoop { |containers| ^false }
	loopSafeEquals { |that| ^this == that }
}

+ Collection {
	hasReferenceLoop { |containers|
		if (containers.notNil) {
			if (containers.includes(this)) { ^true };
			containers = containers.add(this);
		} {
			containers = [this]
		};

		this.do { |elem|
			if (elem.isKindOf(Collection)) {
				if (containers.includes(elem)) { ^true };
				if (elem.hasReferenceLoop(containers)) { ^true };
			}
		};
		containers.remove(this);
		^false
	}

	//////// don't know how to do recursion generally enough here yet
// loopSafeEquals { |that, containers|
// 	if (this === that) { ^true };
// 	if (that.class != this.class) { ^false };
// 	if (that.size != this.size) { ^false };
// 	containers = (containers ?? { [] }).add(this);
//
// 	that.do { | item, i |
// 		if (this.includes(item).not) { ^false };
// 		if (item.isKindOf(Collection)) {
// 	^item.loopSafeEquals(item, containers)).not) { ^false };
// };
// ^true
// }
// }
}

+ IdentityDictionary {
	hasReferenceLoop { |containers|
		if (containers.notNil) {
			if (containers.includes(this)) { ^true };
			containers = containers.add(this);
		} {
			containers = [this]
		};

		this.do { |elem|
			if (elem.isKindOf(Collection)) {
				if (containers.includes(elem)) { ^true };
				if (elem.hasReferenceLoop(containers)) { ^true };
			}
		};
		if (parent.hasReferenceLoop(containers)) { ^true };
		if (proto.hasReferenceLoop(containers)) { ^true };
		containers.remove(this);
		^false
	}
}

+ SequenceableCollection {
	loopSafeEquals { |that, containers|
		if (this === that) { ^true };
		if (that.class != this.class) { ^false };
		if (that.size != this.size) { ^false };

		if (containers.notNil) {
			if (containers.includes(this)) { ^true };
			containers = containers.add(this);
		} {
			containers = [this]
		};

		this.do { | item, i |
			var thatItem = that[i];
			if (containers.includes(thatItem).not) { ^false };
			if (thatItem.loopSafeEquals(item, containers).not) { ^false };
		};
		^true
	}
}
