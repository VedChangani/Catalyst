import test from "node:test";
import assert from "node:assert/strict";
import {actionLabel, actionTone, buildActivityParams, formatDetails, roleLabel} from "./activityFormat.js";

test("personal activity requests never carry an owner/actor id, whatever filters exist", () => {
    const filters = {actorUserId: "someone-else", actorRole: "ROLE_ADMIN", action: "PASSWORD_CHANGED", userId: "x"};
    assert.deepEqual(buildActivityParams({system: false, page: 0, filters}), {});
    assert.deepEqual(buildActivityParams({system: false, page: 3, filters}), {page: 3});
});

test("system activity forwards only non-empty, known admin filters", () => {
    const params = buildActivityParams({
        system: true,
        page: 1,
        filters: {action: "ITEM_CREATED", actorRole: "", targetType: "ITEM", actorUserId: "  uid-1 ",
            dateFrom: "2026-01-01", dateTo: null, somethingElse: "ignored"},
    });
    assert.deepEqual(params, {page: 1, action: "ITEM_CREATED", targetType: "ITEM", actorUserId: "uid-1", dateFrom: "2026-01-01"});
});

test("details are rendered as readable key/value lines", () => {
    assert.deepEqual(formatDetails({changedFields: ["name", "email"], delta: -2, salesChannel: "POS"}),
        ["Changed Fields: name, email", "Delta: -2", "Sales Channel: POS"]);
    assert.deepEqual(formatDetails(null), []);
    assert.deepEqual(formatDetails({}), []);
});

test("labels and tones", () => {
    assert.equal(actionLabel("POS_ORDER_CREATED"), "POS sale entered");
    assert.equal(actionLabel("SOMETHING_NEW"), "SOMETHING NEW");
    assert.equal(roleLabel("SYSTEM"), "System");
    assert.equal(roleLabel("ROLE_CASHIER"), "Cashier");
    assert.equal(actionTone("PAYMENT_FAILED"), "danger");
    assert.equal(actionTone("ITEM_CREATED"), "success");
    assert.equal(actionTone("PASSWORD_CHANGED"), "warning");
    assert.equal(actionTone("AUTH_LOGIN_SUCCESS"), "info");
});
