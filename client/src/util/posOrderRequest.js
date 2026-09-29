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
        cartItems: (cartItems || []).map(({itemId, quantity}) => ({itemId, quantity})),
        paymentMethod,
    };
};

export const buildOnlineOrderRequest = ({paymentMethod, cartItems}) => ({
    cartItems: (cartItems || []).map(({itemId, quantity}) => ({itemId, quantity})),
    paymentMethod,
});
