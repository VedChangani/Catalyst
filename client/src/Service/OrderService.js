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

// USER (and ADMIN): the currently authenticated user's own orders.
export const myOrders = async (signal) => {
    return await apiClient.get("/orders/my-orders", {signal});
}

// USER: one order from the caller's own history (ONLINE or POS). Ownership is enforced
// backend-side; the id is only an identifier.
export const myOrder = async (orderId, signal) => {
    return await apiClient.get(`/orders/${encodeURIComponent(orderId)}`, {signal});
}

// USER places ONLINE orders via /orders; ADMIN (and CASHIER) enter sales via /pos/orders.
// idempotencyKey identifies ONE checkout attempt: the backend returns the already-created order
// (HTTP 200) when the same key is sent again, so a retry can never create a second order.
export const createOrder = async (order, isStaff = false, idempotencyKey = null) => {
    const config = idempotencyKey ? {headers: {"Idempotency-Key": idempotencyKey}} : undefined;
    return await apiClient.post(isStaff ? "/pos/orders" : "/orders", order, config);
}

// ADMIN-only: hard-delete an order.
export const deleteOrder = async (id) => {
    return await apiClient.delete(`/orders/${id}`);
}

// USER + ADMIN: cancel a PENDING_PAYMENT order (ownership enforced backend-side).
export const cancelOrder = async (orderId) => {
    return await apiClient.post(`/orders/${orderId}/cancel`, {});
}

// USER + ADMIN: mark a PENDING_PAYMENT order as PAYMENT_FAILED (ownership enforced backend-side).
export const failPaymentOrder = async (orderId) => {
    return await apiClient.post(`/orders/${orderId}/fail-payment`, {});
}
