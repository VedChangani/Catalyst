import apiClient from "../util/apiClient.js";

// ADMIN-only aggregate analytics. `range` is today | 7d | 30d | custom; `from`/`to` (yyyy-MM-dd)
// only apply to custom. The backend resolves and validates the range; empty values are dropped.
export const fetchAnalytics = async (params, signal) => {
    const cleaned = Object.fromEntries(
        Object.entries(params).filter(([, value]) => value !== "" && value !== null && value !== undefined)
    );
    return await apiClient.get("/admin/analytics", {params: cleaned, signal});
}
