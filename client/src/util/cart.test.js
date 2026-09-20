import test from "node:test";
import assert from "node:assert/strict";
import {cartQuantityTotal} from "./cart.js";

test("cart count is the total quantity, not the number of lines", () => {
    assert.equal(cartQuantityTotal([]), 0);
    assert.equal(cartQuantityTotal(undefined), 0);
    assert.equal(cartQuantityTotal([{itemId: "a", quantity: 1}]), 1);
    assert.equal(cartQuantityTotal([{itemId: "a", quantity: 3}]), 3);
    assert.equal(cartQuantityTotal([{itemId: "a", quantity: 2}, {itemId: "b", quantity: 4}]), 6);
});

test("count follows add / quantity change / remove", () => {
    let cart = [];
    cart = [...cart, {itemId: "a", quantity: 1}];
    assert.equal(cartQuantityTotal(cart), 1);
    cart = cart.map((i) => (i.itemId === "a" ? {...i, quantity: i.quantity + 2} : i));
    assert.equal(cartQuantityTotal(cart), 3);
    cart = [...cart, {itemId: "b", quantity: 1}];
    assert.equal(cartQuantityTotal(cart), 4);
    cart = cart.filter((i) => i.itemId !== "a");
    assert.equal(cartQuantityTotal(cart), 1);
    assert.equal(cartQuantityTotal([]), 0);
});
