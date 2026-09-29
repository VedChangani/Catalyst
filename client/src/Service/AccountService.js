import apiClient from "../util/apiClient.js";

export const fetchMyAccount = async (signal) => {
    return await apiClient.get("/account/me", {signal});
}

export const updateMyAccount = async (data) => {
    return await apiClient.patch("/account/me", data);
}

export const changeMyPassword = async (data) => {
    return await apiClient.patch("/account/me/password", data);
}
