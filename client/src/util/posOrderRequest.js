// Body of POST /pos/orders. Pure function, so the exact request contract can be unit-tested.
//
// The POS has exactly two customer modes and the contract distinguishes them by PRESENCE alone:
//   customerUserId present -> the registered customer the cashier explicitly selected
//   customerUserId absent  -> a genuine walk-in sale (order.user stays NULL)
// Nothing else identifies the customer: customerName/phoneNumber are billing details for the
// receipt, and the backend never uses them to find or associate an account.
//
// Because "absent" is what makes a sale a walk-in, a selected customer carrying no stable userId
// must never be allowed to fall through as absent - that would silently record a registered
// customer's purchase against no account at all. It throws instead, and the caller reports the
// failure rather than billing the sale as a walk-in.
export const buildPosOrderRequest = ({customer = null, customerName = "", phoneNumber = "",
                                         paymentMethod, cartItems}) => {
    if (customer && !customer.userId) {
        throw new Error(
            "This sale is for a registered customer, but that customer's id is missing. "
            + "Select the customer again before taking payment.");
    }
    const name = (customerName || "").trim();
    return {
        ...(customer ? {customerUserId: customer.userId} : {}),
        ...(name ? {customerName: name} : {}),
        ...(phoneNumber ? {phoneNumber} : {}),
        // Only itemId + quantity: name, price, subtotal, tax and grand total are resolved and
        // computed by the backend from its own catalog.
        cartItems: (cartItems || []).map(({itemId, quantity}) => ({itemId, quantity})),
        paymentMethod,
    };
};

// Body of POST /orders. The online customer is the authenticated account, so the request carries
// no identity at all - not even a name or phone number.
export const buildOnlineOrderRequest = ({paymentMethod, cartItems}) => ({
    cartItems: (cartItems || []).map(({itemId, quantity}) => ({itemId, quantity})),
    paymentMethod,
});
