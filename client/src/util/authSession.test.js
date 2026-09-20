import test from "node:test";
import assert from "node:assert/strict";
import {hasSession, startSession} from "./authSession.js";

test("only a token AND a role count as a session", () => {
    assert.equal(hasSession("jwt", "ROLE_USER"), true);
    assert.equal(hasSession(null, null), false);          // logout / password change
    assert.equal(hasSession(undefined, undefined), false);
    assert.equal(hasSession("", "ROLE_USER"), false);
    assert.equal(hasSession("jwt", null), false);
    assert.equal(hasSession(null, "ROLE_ADMIN"), false);
});

test("startSession stores the session, hands it to the context and goes to the role's home", () => {
    const store = {};
    globalThis.localStorage = {setItem: (k, v) => { store[k] = v; }};
    const calls = [];
    startSession({token: "jwt", role: "ROLE_CASHIER"}, (t, r) => calls.push([t, r]), (path) => calls.push(path));
    assert.deepEqual(store, {token: "jwt", role: "ROLE_CASHIER"});
    assert.deepEqual(calls, [["jwt", "ROLE_CASHIER"], "/pos"]);
    delete globalThis.localStorage;
});
