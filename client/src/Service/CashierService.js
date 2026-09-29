import apiClient from "../util/apiClient.js";

export const fetchCashiers = async (signal) => {
    return await apiClient.get("/admin/cashiers", {signal});
}

export const createCashier = async (data) => {
    return await apiClient.post("/admin/cashiers", data);
}

export const setCashierEnabled = async (cashierId, enabled) => {
    return await apiClient.patch(`/admin/cashiers/${encodeURIComponent(cashierId)}/status`, {enabled});
}

export const resetCashierPassword = async (cashierId, password) => {
    return await apiClient.post(`/admin/cashiers/${encodeURIComponent(cashierId)}/reset-password`, {password});
}
