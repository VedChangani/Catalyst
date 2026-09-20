import apiClient from "../util/apiClient.js";

export const login = async (data) => {
    return await apiClient.post("/login", data);
}

// Public customer self-registration. The backend always creates a ROLE_USER account.
export const register = async (data) => {
    return await apiClient.post("/register", data);
}
