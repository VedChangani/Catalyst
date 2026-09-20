import apiClient from "../util/apiClient.js";

// ADMIN-only: every order placed in the system.
export const latestOrders = async () => {
    return await apiClient.get("/orders/latest");
}

// ADMIN-only: filtered, sorted, paginated order management list. The backend is authoritative
// for every filter; empty values are dropped so only real filters reach the query string.
export const adminOrders = async (params, signal) => {
    const cleaned = Object.fromEntries(
        Object.entries(params).filter(([, value]) => value !== "" && value !== null && value !== undefined)
    );
    return await apiClient.get("/admin/orders", {params: cleaned, signal});
}

// USER only: the signed-in customer's own purchase history (ONLINE + POS sales linked to them).
// Admins use GET /admin/orders (All Orders); cashiers use GET /pos/sales (My Sales).
export const myOrders = async (signal) => {
    return await apiClient.get("/orders/my-orders", {signal});
}

// One order: for a USER from their own history (ONLINE or linked POS); for a CASHIER a POS sale
// they entered (My Sales). Ownership is enforced backend-side; the id is only an identifier.
export const myOrder = async (orderId, signal) => {
    return await apiClient.get(`/orders/${encodeURIComponent(orderId)}`, {signal});
}

// USER places ONLINE orders via /orders; the CASHIER enters store sales via /pos/orders (isStaff).
// idempotencyKey identifies ONE checkout attempt: the backend returns the already-created order
// (HTTP 200) when the same key is sent again, so a retry can never create a second order.
export const createOrder = async (order, isStaff = false, idempotencyKey = null) => {
    const config = idempotencyKey ? {headers: {"Idempotency-Key": idempotencyKey}} : undefined;
    return await apiClient.post(isStaff ? "/pos/orders" : "/orders", order, config);
}

// Cancel a PENDING_PAYMENT order: the ONLINE order's customer or the POS sale's cashier (ownership
// enforced backend-side). Orders are never deleted - cancellation keeps the history.
export const cancelOrder = async (orderId) => {
    return await apiClient.post(`/orders/${orderId}/cancel`, {});
}

// Mark a PENDING_PAYMENT order as PAYMENT_FAILED: same ownership rule as cancel.
export const failPaymentOrder = async (orderId) => {
    return await apiClient.post(`/orders/${orderId}/fail-payment`, {});
}
