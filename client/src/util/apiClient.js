import axios from "axios";

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/+$/, "");

const apiClient = axios.create({
    baseURL: API_BASE_URL,
});

apiClient.interceptors.request.use((config) => {
    const token = localStorage.getItem("token");
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});

apiClient.interceptors.response.use(
    (response) => response,
    (error) => {
        const status = error.response?.status;
        const backendMessage = error.response?.data?.message;
        const isLoginRequest = error.config?.url?.includes("/login");

        if (status === 401) {
            if (isLoginRequest) {
                error.friendlyMessage = backendMessage || "Email/mobile or password is incorrect";
            } else {
                localStorage.removeItem("token");
                localStorage.removeItem("role");
                error.friendlyMessage = "Your session has expired. Please sign in again.";
                if (typeof window !== "undefined" && window.location.pathname !== "/login") {
                    window.location.assign("/login");
                }
            }
        } else if (status === 403) {
            error.friendlyMessage = backendMessage || "You don't have permission to do that.";
        } else if (status === 400 || status === 404 || status === 409) {
            error.friendlyMessage = backendMessage || "The request could not be completed.";
        } else if (!error.response) {
            error.friendlyMessage = "Network error. Please check your connection and try again.";
        } else {
            error.friendlyMessage = "Something went wrong. Please try again.";
        }

        return Promise.reject(error);
    }
);

export default apiClient;
