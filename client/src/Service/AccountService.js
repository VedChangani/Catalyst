import apiClient from "../util/apiClient.js";

// The caller's own account. No user id is ever sent: the backend uses the authenticated user.

export const fetchMyAccount = async (signal) => {
    return await apiClient.get("/account/me", {signal});
}

// {name, email, mobile}. Resolves {account, token}; token is set only when the email changed.
export const updateMyAccount = async (data) => {
    return await apiClient.patch("/account/me", data);
}

// {currentPassword, newPassword, confirmNewPassword} - sent in the body only, never in the URL.
export const changeMyPassword = async (data) => {
    return await apiClient.patch("/account/me/password", data);
}
