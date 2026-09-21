// Pure helpers for the customer Home dashboard. `orders` is the caller's own list from
// GET /orders/my-orders (newest first); nothing here decides ownership.

// Active = awaiting payment. Total spent counts PAID orders only, from the stored grand totals
// (summed in paise so decimals never drift).
export const summarizeOrders = (orders) => {
    let paise = 0;
    let active = 0;
    for (const order of orders) {
        if (order.orderStatus === "PENDING_PAYMENT") active += 1;
        if (order.orderStatus === "PAID") paise += Math.round(Number(order.grandTotal || 0) * 100);
    }
    return {activeOrders: active, totalOrders: orders.length, totalSpent: paise / 100};
};

// Distinct products from PAID orders, most recent purchase first.
export const recentlyPurchased = (orders, limit = 4) => {
    const seen = new Set();
    const result = [];
    for (const order of orders) {
        if (order.orderStatus !== "PAID") continue;
        for (const item of order.items || []) {
            if (!item.itemId || seen.has(item.itemId)) continue;
            seen.add(item.itemId);
            result.push(item);
            if (result.length === limit) return result;
        }
    }
    return result;
};
