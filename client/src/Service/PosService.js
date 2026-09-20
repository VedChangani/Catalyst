import apiClient from "../util/apiClient.js";

// CASHIER: look up registered customer accounts (name/email match, min. 2 characters,
// at most 20 results). Returns [{userId, name, email}]. Selecting one is an explicit choice by
// the cashier - accounts are never inferred from a billing name or phone number.
export const searchPosCustomers = async (search, signal) => {
    return await apiClient.get("/pos/customers", {params: {search}, signal});
}

// CASHIER: the calling cashier's own POS sales ("My Sales"), newest first. The backend filters
// by the authenticated cashier, so no identifier is ever sent.
export const mySales = async (signal) => {
    return await apiClient.get("/pos/sales", {signal});
}
