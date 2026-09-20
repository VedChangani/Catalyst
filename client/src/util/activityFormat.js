// Display and request helpers for the Activity page. Pure functions, so they can be unit-tested.

export const AUDIT_ACTIONS = [
    "AUTH_LOGIN_SUCCESS", "ACCOUNT_REGISTERED", "PROFILE_UPDATED", "PASSWORD_CHANGED",
    "CASHIER_CREATED", "CASHIER_DEACTIVATED", "CASHIER_REACTIVATED", "CASHIER_PASSWORD_RESET",
    "ONLINE_ORDER_CREATED", "POS_ORDER_CREATED", "PAYMENT_VERIFIED", "PAYMENT_FAILED",
    "ORDER_CANCELLED", "ORDER_DELETED",
    "ITEM_CREATED", "ITEM_UPDATED", "ITEM_DELETED", "INVENTORY_ADJUSTED",
    "CATEGORY_CREATED", "CATEGORY_DELETED",
];

export const AUDIT_TARGET_TYPES = ["ACCOUNT", "CASHIER", "ORDER", "ITEM", "CATEGORY"];

export const ACTOR_ROLES = ["ROLE_USER", "ROLE_CASHIER", "ROLE_ADMIN", "SYSTEM"];

const ACTION_LABELS = {
    AUTH_LOGIN_SUCCESS: "Signed in",
    ACCOUNT_REGISTERED: "Account created",
    PROFILE_UPDATED: "Profile updated",
    PASSWORD_CHANGED: "Password changed",
    CASHIER_CREATED: "Cashier created",
    CASHIER_DEACTIVATED: "Cashier deactivated",
    CASHIER_REACTIVATED: "Cashier reactivated",
    CASHIER_PASSWORD_RESET: "Cashier password reset",
    ONLINE_ORDER_CREATED: "Online order placed",
    POS_ORDER_CREATED: "POS sale entered",
    PAYMENT_VERIFIED: "Payment verified",
    PAYMENT_FAILED: "Payment failed",
    ORDER_CANCELLED: "Order cancelled",
    ORDER_DELETED: "Order deleted",
    ITEM_CREATED: "Item created",
    ITEM_UPDATED: "Item updated",
    ITEM_DELETED: "Item deleted",
    INVENTORY_ADJUSTED: "Stock adjusted",
    CATEGORY_CREATED: "Category created",
    CATEGORY_DELETED: "Category deleted",
};

const ROLE_LABELS = {
    ROLE_USER: "Customer",
    ROLE_CASHIER: "Cashier",
    ROLE_ADMIN: "Admin",
    SYSTEM: "System",
};

export const actionLabel = (action) => ACTION_LABELS[action] || (action || "").replaceAll("_", " ");

export const roleLabel = (role) => ROLE_LABELS[role] || role || "—";

export const actionTone = (action) => {
    if (!action) return "muted";
    if (action.includes("FAILED") || action.includes("DEACTIVATED") || action.includes("DELETED")
        || action.includes("CANCELLED")) return "danger";
    if (action.includes("CREATED") || action.includes("VERIFIED") || action.includes("REACTIVATED")
        || action === "ACCOUNT_REGISTERED") return "success";
    if (action.includes("PASSWORD")) return "warning";
    return "info";
};

const humanizeKey = (key) => key.replace(/([a-z])([A-Z])/g, "$1 $2").replace(/^./, (c) => c.toUpperCase());

const humanizeValue = (value) => {
    if (Array.isArray(value)) return value.join(", ");
    if (value === null || value === undefined) return "—";
    if (typeof value === "object") return JSON.stringify(value);
    return String(value);
};

// {changedFields: ["name","email"], delta: -2} -> ["Changed Fields: name, email", "Delta: -2"]
export const formatDetails = (details) => {
    if (!details || typeof details !== "object") return [];
    return Object.entries(details).map(([key, value]) => `${humanizeKey(key)}: ${humanizeValue(value)}`);
};

// Query parameters for a request. The personal log sends paging ONLY - its owner is the
// authenticated user, decided by the backend, so no user/actor id is ever included for it.
// Admin filters are forwarded only in system mode, and empty values are dropped.
export const buildActivityParams = ({system, page, filters = {}}) => {
    const params = {};
    if (page > 0) params.page = page;
    if (!system) return params;
    for (const key of ["action", "actorRole", "targetType", "actorUserId", "dateFrom", "dateTo"]) {
        const value = filters[key];
        if (value !== undefined && value !== null && String(value).trim() !== "") {
            params[key] = String(value).trim();
        }
    }
    return params;
};
