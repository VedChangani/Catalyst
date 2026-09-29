import apiClient from "../util/apiClient.js";

export const latestOrders = async () => {
    return await apiClient.get("/orders/latest");
}

export const adminOrders = async (params, signal) => {
    const cleaned = Object.fromEntries(
        Object.entries(params).filter(([, value]) => value !== "" && value !== null && value !== undefined)
    );
    return await apiClient.get("/admin/orders", {params: cleaned, signal});
}

export const myOrders = async (signal) => {
    return await apiClient.get("/orders/my-orders", {signal});
}

export const myOrder = async (orderId, signal) => {
    return await apiClient.get(`/orders/${encodeURIComponent(orderId)}`, {signal});
}

export const createOrder = async (order, isStaff = false, idempotencyKey = null) => {
    const config = idempotencyKey ? {headers: {"Idempotency-Key": idempotencyKey}} : undefined;
    return await apiClient.post(isStaff ? "/pos/orders" : "/orders", order, config);
}

export const cancelOrder = async (orderId) => {
    return await apiClient.post(`/orders/${orderId}/cancel`, {});
}

export const failPaymentOrder = async (orderId) => {
    return await apiClient.post(`/orders/${orderId}/fail-payment`, {});
}
