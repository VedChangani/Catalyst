import apiClient from "../util/apiClient.js";

export const fetchMyActivity = async (params, signal) => {
    return await apiClient.get("/activity/me", {params, signal});
}

export const fetchSystemActivity = async (params, signal) => {
    return await apiClient.get("/admin/activity", {params, signal});
}
