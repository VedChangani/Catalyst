import apiClient from "../util/apiClient.js";

// The signed-in user's own activity. Only paging is sent: the backend decides whose log it is
// from the login, so there is no user/actor id parameter.
export const fetchMyActivity = async (params, signal) => {
    return await apiClient.get("/activity/me", {params, signal});
}

// ADMIN only: system-wide activity with optional server-side filters.
export const fetchSystemActivity = async (params, signal) => {
    return await apiClient.get("/admin/activity", {params, signal});
}
