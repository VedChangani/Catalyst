import test from "node:test";
import assert from "node:assert/strict";
import {buildCheckoutOptions} from "./razorpayCheckout.js";

const serverOrder = {keyId: "rzp_test_PUBLICKEYID", id: "order_ABC123", amount: 3220, currency: "INR", status: "created"};

test("key, order id, amount and currency all come from the backend's create-order response", () => {
    const options = buildCheckoutOptions({razorpayOrder: serverOrder, handler: () => {}, onDismiss: () => {}});
    assert.equal(options.key, "rzp_test_PUBLICKEYID");
    assert.equal(options.order_id, "order_ABC123");
    assert.equal(options.amount, 3220);
    assert.equal(options.currency, "INR");
});

test("the options never contain anything secret", () => {
    const options = buildCheckoutOptions({razorpayOrder: {...serverOrder, secret: "x", key_secret: "y"},
        handler: () => {}, onDismiss: () => {}});
    const json = JSON.stringify(options);
    assert.ok(!json.toLowerCase().includes("secret"), json);
});

test("a failed attempt ends the checkout: Razorpay's in-modal retry is disabled", () => {
    const options = buildCheckoutOptions({razorpayOrder: serverOrder, handler: () => {}, onDismiss: () => {}});
    assert.deepEqual(options.retry, {enabled: false});
});

test("UPI is shown first via the documented display config, with the other methods kept", () => {
    const {display} = buildCheckoutOptions({razorpayOrder: serverOrder, handler: () => {}, onDismiss: () => {}}).config;
    assert.deepEqual(display.blocks.upi.instruments, [{method: "upi"}]);
    assert.deepEqual(display.sequence, ["block.upi"]);
    assert.equal(display.preferences.show_default_blocks, true);
});

// Checkout renders only the methods the Razorpay ACCOUNT has enabled; these options must never be
// what removes one. Two things matter here:
//  - no display.hide, so nothing the account offers is suppressed;
//  - the UPI instrument declares no `flows`, which would RESTRICT it to those listed. Leaving it
//    open is what lets desktop Checkout offer Razorpay's own UPI QR alongside collect/intent.
test("the options hide no payment method and do not restrict the UPI flows", () => {
    const {display} = buildCheckoutOptions({razorpayOrder: serverOrder, handler: () => {}, onDismiss: () => {}}).config;
    assert.equal(display.hide, undefined);
    assert.equal(display.blocks.upi.instruments[0].flows, undefined);
    assert.equal(JSON.stringify(display).includes("hide"), false);
});

test("success and dismissal are wired to the given callbacks", () => {
    const handler = () => "handled";
    const onDismiss = () => "dismissed";
    const options = buildCheckoutOptions({razorpayOrder: serverOrder, handler, onDismiss});
    assert.equal(options.handler, handler);
    assert.equal(options.modal.ondismiss, onDismiss);
});

test("prefill only carries the values that exist", () => {
    assert.deepEqual(buildCheckoutOptions({razorpayOrder: serverOrder, prefill: {name: "Cara", contact: "9876543210"}}).prefill,
        {name: "Cara", contact: "9876543210"});
    assert.deepEqual(buildCheckoutOptions({razorpayOrder: serverOrder, prefill: {name: undefined, contact: ""}}).prefill, {});
});

test("an incomplete server response is refused instead of opening a broken Checkout", () => {
    for (const broken of [null, {}, {...serverOrder, keyId: ""}, {...serverOrder, id: undefined},
        {...serverOrder, amount: 0}, {...serverOrder, currency: null}]) {
        assert.throws(() => buildCheckoutOptions({razorpayOrder: broken}), /incomplete Razorpay order/);
    }
});
