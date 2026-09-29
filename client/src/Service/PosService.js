import apiClient from "../util/apiClient.js";

export const searchPosCustomers = async (search, signal) => {
    return await apiClient.get("/pos/customers", {params: {search}, signal});
}

export const mySales = async (signal) => {
    return await apiClient.get("/pos/sales", {signal});
}
