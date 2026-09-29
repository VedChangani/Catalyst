import apiClient from "../util/apiClient.js";

export const login = async (data) => {
    return await apiClient.post("/login", data);
}

export const register = async (data) => {
    return await apiClient.post("/register", data);
}
