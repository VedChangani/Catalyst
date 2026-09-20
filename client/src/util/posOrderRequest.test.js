import test from "node:test";
import assert from "node:assert/strict";
import {buildOnlineOrderRequest, buildPosOrderRequest} from "./posOrderRequest.js";

const CART = [{itemId: "item-1", name: "Keyboard", price: 2500, quantity: 2}];

test("a selected registered customer is sent by its stable userId", () => {
    const body = buildPosOrderRequest({
        customer: {userId: "cust-123", name: "User1", email: "user1@example.com"},
        paymentMethod: "CASH",
        cartItems: CART,
    });
    assert.equal(body.customerUserId, "cust-123");
});

test("a walk-in sale sends no customerUserId at all", () => {
    const body = buildPosOrderRequest({customer: null, paymentMethod: "CASH", cartItems: CART});
    assert.equal("customerUserId" in body, false);
});

test("a selected customer with no userId fails instead of silently becoming a walk-in", () => {
    assert.throws(
        () => buildPosOrderRequest({
            customer: {name: "User1", email: "user1@example.com"},
            paymentMethod: "CASH",
            cartItems: CART,
        }),
        /registered customer/i);
});

test("billing name and phone are optional and never replace the customer selection", () => {
    const walkIn = buildPosOrderRequest({
        customerName: "  John  ",
        phoneNumber: "9876543210",
        paymentMethod: "CASH",
        cartItems: CART,
    });
    assert.equal(walkIn.customerName, "John");
    assert.equal(walkIn.phoneNumber, "9876543210");
    assert.equal("customerUserId" in walkIn, false);

    const blank = buildPosOrderRequest({customerName: "   ", paymentMethod: "CASH", cartItems: CART});
    assert.equal("customerName" in blank, false);
    assert.equal("phoneNumber" in blank, false);
});

test("only itemId and quantity are sent for each cart line - never name or price", () => {
    const body = buildPosOrderRequest({paymentMethod: "CASH", cartItems: CART});
    assert.deepEqual(body.cartItems, [{itemId: "item-1", quantity: 2}]);
    assert.equal(body.paymentMethod, "CASH");
});

test("the online request carries no identity, only items and payment method", () => {
    const body = buildOnlineOrderRequest({paymentMethod: "UPI", cartItems: CART});
    assert.deepEqual(body, {cartItems: [{itemId: "item-1", quantity: 2}], paymentMethod: "UPI"});
});
