export const summarizeOrders = (orders) => {
    let paise = 0;
    let active = 0;
    for (const order of orders) {
        if (order.orderStatus === "PENDING_PAYMENT") active += 1;
        if (order.orderStatus === "PAID") paise += Math.round(Number(order.grandTotal || 0) * 100);
    }
    return {activeOrders: active, totalOrders: orders.length, totalSpent: paise / 100};
};

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
