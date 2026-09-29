import apiClient from "../util/apiClient.js";

export const fetchAnalytics = async (params, signal) => {
    const cleaned = Object.fromEntries(
        Object.entries(params).filter(([, value]) => value !== "" && value !== null && value !== undefined)
    );
    return await apiClient.get("/admin/analytics", {params: cleaned, signal});
}
