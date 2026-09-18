import axios from "axios";

// ADMIN-only: every order placed in the system.
export const latestOrders = async () => {
    return await axios.get("http://localhost:8080/api/v1.0/orders/latest", {headers: {'Authorization': `Bearer ${localStorage.getItem('token')}`}});
}

// USER (and ADMIN): the currently authenticated user's own orders.
export const myOrders = async () => {
    return await axios.get("http://localhost:8080/api/v1.0/orders/my-orders", {headers: {'Authorization': `Bearer ${localStorage.getItem('token')}`}});
}

export const createOrder = async (order) => {
    return await axios.post("http://localhost:8080/api/v1.0/orders", order, {headers: {'Authorization': `Bearer ${localStorage.getItem('token')}`}});
}

// ADMIN-only: hard-delete an order.
export const deleteOrder = async (id) => {
    return await axios.delete(`http://localhost:8080/api/v1.0/orders/${id}`, {headers: {'Authorization': `Bearer ${localStorage.getItem('token')}`}});
}

// USER + ADMIN: cancel a PENDING_PAYMENT order (ownership enforced backend-side).
export const cancelOrder = async (orderId) => {
    return await axios.post(`http://localhost:8080/api/v1.0/orders/${orderId}/cancel`, {}, {headers: {'Authorization': `Bearer ${localStorage.getItem('token')}`}});
}

// USER + ADMIN: mark a PENDING_PAYMENT order as PAYMENT_FAILED (ownership enforced backend-side).
export const failPaymentOrder = async (orderId) => {
    return await axios.post(`http://localhost:8080/api/v1.0/orders/${orderId}/fail-payment`, {}, {headers: {'Authorization': `Bearer ${localStorage.getItem('token')}`}});
}