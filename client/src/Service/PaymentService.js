import apiClient from "../util/apiClient.js";

export const createRazorpayOrder = async (data) => {
    return await apiClient.post(`/payments/create-order`, data);
}

export const verifyPayment = async (paymentData) => {
    return await apiClient.post('/payments/verify', paymentData);
}
