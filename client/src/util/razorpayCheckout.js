export const buildCheckoutOptions = ({razorpayOrder, prefill = {}, handler, onDismiss}) => {
    if (!razorpayOrder?.keyId || !razorpayOrder?.id || !razorpayOrder?.amount || !razorpayOrder?.currency) {
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
        retry: {
            enabled: false,
        },
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
