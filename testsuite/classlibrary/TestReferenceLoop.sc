TestReferenceLoop : UnitTest {

	test_nil {
		this.assert(
			nil.hasReferenceLoop.not,
			"nil should have none."
		);
	}
	test_Integer {
		this.assert(
			123.hasReferenceLoop.not,
			"an Integer should have none."
		);
	}

	test_Set_noCyclicRef {
		this.assert(
			Set[1, 2, 3].hasReferenceLoop.not,
			"should find none."
		);
	}

	test_Set_cyclicRef {
		var set = Set[1, 2, 3];
		set.add([1, 2, 3, set]);
		this.assert(
			set.hasReferenceLoop,
			"should find none."
		);
	}

	test_Array_noCyclicRef {
		var a = [1, 2, [3, 4, [5, 6]]];
		this.assert(
			a.hasReferenceLoop.not,
			"should find none."
		);
	}

	test_Array_identicalObjects_AtDifferentLevels_NoLoop {
		var b = [1, 2];
		var a = [b, [3, b, [4, b, [5, [b]]]]];
		this.assert(
			a.hasReferenceLoop.not,
			"should find none."
		);
	}

	test_Array_cyclicRef_atBase {
		var a = [1, 2, [3, 4, [5, 6]]];
		a.put(1, a);
		this.assert(
			a.hasReferenceLoop,
			"should find reference loop."
		);
	}
	test_Array_cyclicRef_atDepth {
		var a = [1, 2, [3, 4, [5, 6]]];
		a[2][2].put(1, a);
		this.assert(
			a.hasReferenceLoop,
			"should find reference loop."
		);
	}

	test_Event_noCyclicRef {
		var d;
		d = (a: 1, d: (a: 2, d: (a: 3))).parent_((par: 1));
		this.assert(
			d.hasReferenceLoop.not,
			"should find none."
		);
	}

	test_Event_cyclicRef_atBase {
		var d = (a: 1, d: (a: 2, d: (a: 3))).parent_((par: 1));
		d.put(\c, d);
		this.assert(
			d.hasReferenceLoop,
			"should find reference loop."
		);
	}
	test_Event_cyclicRef_atDepth {
		var d = (a: 1, d: (a: 2, d: (a: 3))).parent_((par: 1));
		d.d.d.put(\d, d);
		this.assert(
			d.hasReferenceLoop,
			"should find reference loop."
		);
	}

	test_Event_cyclicRef_inParent {
		var d;
		d = (a: 1, d: (a: 2, d: (a: 3))).parent_((par: 1));
		d.parent.put(\d, d);
		// d.parent.includes(d);
		this.assert(
			d.hasReferenceLoop,
			"should find reference loop."
		);
	}

	test_Event_cyclicRef_inNestedParent {
		var d;
		d = (a: 1, d: (a: 2, d: (a: 3)));
		d.d.d.parent = (par: d);
		this.assert(
			d.hasReferenceLoop,
			"should find reference loop."
		);
	}

	test_Event_cyclicRef_inNestedProto {
		var d;
		d = (a: 1, d: (a: 2, d: (a: 3)));
		d.d.d.proto = (pro: d);
		this.assert(
			d.hasReferenceLoop,
			"should find reference loop."
		);
	}

	test_Event_defaultParentEvent {
		var d;
		// get Event:defaultParentEvent indirectly
		d = (\instrument: \none).play.parent;
		this.assert(
			d.hasReferenceLoop.not,
			"should never contain reference loop."
		);
	}
}