import test from "node:test";
import assert from "node:assert/strict";
import {createLatestOnly} from "./latestOnly.js";

test("a single request is current", () => {
    const gate = createLatestOnly();
    assert.equal(gate.begin()(), true);
});

test("an older request that finishes late is no longer current", () => {
    const gate = createLatestOnly();
    const first = gate.begin();
    const second = gate.begin();
    assert.equal(first(), false);
    assert.equal(second(), true);
});

test("the newest response wins regardless of arrival order", () => {
    const gate = createLatestOnly();
    let shown = "initial";
    const older = gate.begin();
    const newer = gate.begin();
    if (newer()) shown = "stock 7";
    if (older()) shown = "stock 10";
    assert.equal(shown, "stock 7");
});
