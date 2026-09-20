import test from "node:test";
import assert from "node:assert/strict";
import {homePathFor, isKnownRole, NAV_ITEMS, rootPathFor, ROLE_ADMIN, ROLE_CASHIER, ROLE_USER} from "./roles.js";

test("/ sends a signed-out visitor to /login", () => {
    assert.equal(rootPathFor(null, null), "/login");
    assert.equal(rootPathFor(undefined, undefined), "/login");
    assert.equal(rootPathFor("", ROLE_USER), "/login");
    assert.equal(rootPathFor("jwt", null), "/login");
});

test("/ sends a signed-in user to their role's home - never the login page", () => {
    assert.equal(rootPathFor("jwt", ROLE_USER), "/explore");
    assert.equal(rootPathFor("jwt", ROLE_CASHIER), "/pos");
    assert.equal(rootPathFor("jwt", ROLE_ADMIN), "/dashboard");
});

test("an unrecognised stored role has no home, so it cannot loop between routes", () => {
    assert.equal(isKnownRole("ROLE_ROOT"), false);
    assert.equal(homePathFor("ROLE_ROOT"), "/login");
    assert.equal(rootPathFor("jwt", "ROLE_ROOT"), "/login");
});

test("each role's home is in that role's own navigation, and roles do not see each other's pages", () => {
    for (const role of [ROLE_USER, ROLE_CASHIER, ROLE_ADMIN]) {
        assert.ok(NAV_ITEMS[role].some((item) => item.to === homePathFor(role)), role);
    }
    const adminPaths = NAV_ITEMS[ROLE_ADMIN].map((item) => item.to);
    assert.ok(!adminPaths.includes("/pos") && !adminPaths.includes("/cart") && !adminPaths.includes("/explore"));
    assert.ok(!NAV_ITEMS[ROLE_CASHIER].some((item) => ["/dashboard", "/cart", "/explore"].includes(item.to)));
    assert.ok(!NAV_ITEMS[ROLE_USER].some((item) => ["/dashboard", "/pos", "/sales"].includes(item.to)));
});
