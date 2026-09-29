export const cartQuantityTotal = (cartItems) =>
    (cartItems || []).reduce((total, item) => total + (Number(item.quantity) || 0), 0);
