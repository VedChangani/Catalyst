import apiClient from "../util/apiClient.js";

// CASHIER + ADMIN: look up registered customer accounts (name/email match, min. 2 characters,
// at most 20 results). Returns [{userId, name, email}]. Selecting one is an explicit choice by
// the cashier - accounts are never inferred from a billing name or phone number.
export const searchPosCustomers = async (search, signal) => {
    return await apiClient.get("/pos/customers", {params: {search}, signal});
}
