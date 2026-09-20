import apiClient from "../util/apiClient.js";

export const fetchDashboardData = async () => {
    return await apiClient.get("/dashboard");
}
