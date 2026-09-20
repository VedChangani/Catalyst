// Pure cart helpers. The single source of truth for the cart is AppContext.cartItems; anything
// derived from it (count, display totals) is computed from that array, never stored separately.

// Total quantity across all lines: 2 products with quantities 2 and 4 -> 6.
export const cartQuantityTotal = (cartItems) =>
    (cartItems || []).reduce((total, item) => total + (Number(item.quantity) || 0), 0);
