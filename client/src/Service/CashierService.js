import apiClient from "../util/apiClient.js";

// ADMIN-only cashier management. There is deliberately no delete: cashiers are deactivated so
// their historical POS sales keep pointing at them.

export const fetchCashiers = async (signal) => {
    return await apiClient.get("/admin/cashiers", {signal});
}

// {name, email, mobile, password} only - the backend always creates an enabled ROLE_CASHIER.
export const createCashier = async (data) => {
    return await apiClient.post("/admin/cashiers", data);
}

export const setCashierEnabled = async (cashierId, enabled) => {
    return await apiClient.patch(`/admin/cashiers/${encodeURIComponent(cashierId)}/status`, {enabled});
}

export const resetCashierPassword = async (cashierId, password) => {
    return await apiClient.post(`/admin/cashiers/${encodeURIComponent(cashierId)}/reset-password`, {password});
}
