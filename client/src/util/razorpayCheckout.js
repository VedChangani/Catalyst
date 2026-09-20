// Options for Razorpay Standard Checkout (https://checkout.razorpay.com/v1/checkout.js).
// Pure function, so the exact options can be unit-tested.
//
// Everything that decides WHAT is paid comes from the backend's create-order response:
//   key       - the PUBLIC key id the Razorpay order was created with (never a secret; the
//               frontend has no key configuration of its own, so the two cannot disagree)
//   order_id  - the Razorpay order stored against our local order
//   amount    - paise, derived server-side from the order's grand total
//   currency  - INR, fixed server-side
// The browser callback is never proof of payment: the handler only forwards Razorpay's result to
// the backend, which verifies the signature and settles the order.
export const buildCheckoutOptions = ({razorpayOrder, prefill = {}, handler, onDismiss}) => {
    if (!razorpayOrder?.keyId || !razorpayOrder?.id || !razorpayOrder?.amount || !razorpayOrder?.currency) {
        // Fail loudly: opening Checkout with a missing key/order shows the customer a broken form.
        throw new Error("The payment could not be started: incomplete Razorpay order from the server");
    }
    return {
        key: razorpayOrder.keyId,
        amount: razorpayOrder.amount,
        currency: razorpayOrder.currency,
        order_id: razorpayOrder.id,
        name: "My Retail Shop",
        description: "Order payment",
        handler,
        prefill: {
            ...(prefill.name ? {name: prefill.name} : {}),
            ...(prefill.contact ? {contact: prefill.contact} : {}),
        },
        theme: {
            color: "#2563EB",
        },
        // One attempt per checkout. The first failed attempt already marks the order
        // PAYMENT_FAILED and releases its stock, so Checkout must not offer an in-modal retry that
        // could then succeed against an order that is no longer payable.
        retry: {
            enabled: false,
        },
        // The customer chose UPI: show Razorpay's UPI block (UPI apps / QR, whichever Razorpay
        // offers on this device) first. The other methods stay available. Razorpay only shows UPI
        // when UPI is enabled for the merchant account - this cannot switch it on.
        config: {
            display: {
                blocks: {
                    upi: {
                        name: "Pay using UPI",
                        instruments: [{method: "upi"}],
                    },
                },
                sequence: ["block.upi"],
                preferences: {
                    show_default_blocks: true,
                },
            },
        },
        modal: {
            ondismiss: onDismiss,
        },
    };
};
