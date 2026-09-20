import axios from "axios";

// Backend API base URL from VITE_API_BASE_URL (client/.env.local / build environment; vite.config.js
// supplies the local default in development). Public by nature - never put secrets in VITE_ vars.
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/+$/, "");

const apiClient = axios.create({
    baseURL: API_BASE_URL,
});

// Attach the JWT to every request automatically - services no longer repeat this per call.
apiClient.interceptors.request.use((config) => {
    const token = localStorage.getItem("token");
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});

// Normalize every failure into a single `error.friendlyMessage` that any screen can show
// directly, and handle the two cross-cutting cases (expired/invalid session, network outage)
// in one place instead of in every component.
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
