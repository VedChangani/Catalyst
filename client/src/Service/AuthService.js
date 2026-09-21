import apiClient from "../util/apiClient.js";

export const login = async (data) => {
    return await apiClient.post("/login", data);
}

// Public customer self-registration. The backend always creates a ROLE_USER account.
export const register = async (data) => {
    return await apiClient.post("/register", data);
}

// Customer forgot-password, step 1. The backend always answers 202 with the same generic message
// (it never says whether the account exists); the same call is used to resend the code.
export const requestPasswordReset = async (email) => {
    return await apiClient.post("/forgot-password", {email});
}

// Customer forgot-password, step 2: {email, otp, newPassword, confirmNewPassword}. Returns no session:
// the customer signs in normally afterwards.
export const resetPassword = async (payload) => {
    return await apiClient.post("/reset-password", payload);
}
