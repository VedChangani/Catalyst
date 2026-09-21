import test from "node:test";
import assert from "node:assert/strict";
import {recentlyPurchased, summarizeOrders} from "./customerHome.js";

const orders = [
    {orderStatus: "PENDING_PAYMENT", grandTotal: 50, items: [{itemId: "x"}]},
    {orderStatus: "PAID", grandTotal: 10.1, items: [{itemId: "a"}, {itemId: "b"}]},
    {orderStatus: "PAID", grandTotal: 20.2, items: [{itemId: "a"}, {itemId: "c"}]},
    {orderStatus: "CANCELLED", grandTotal: 99, items: [{itemId: "d"}]},
];

test("summary: active = pending, total = all, spent = PAID only", () => {
    assert.deepEqual(summarizeOrders(orders), {activeOrders: 1, totalOrders: 4, totalSpent: 30.3});
    assert.deepEqual(summarizeOrders([]), {activeOrders: 0, totalOrders: 0, totalSpent: 0});
});

test("recently purchased: PAID only, distinct, newest first, limited", () => {
    assert.deepEqual(recentlyPurchased(orders).map((i) => i.itemId), ["a", "b", "c"]);
    assert.equal(recentlyPurchased(orders, 2).length, 2);
});
