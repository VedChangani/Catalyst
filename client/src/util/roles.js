export const ROLE_USER = "ROLE_USER";
export const ROLE_CASHIER = "ROLE_CASHIER";
export const ROLE_ADMIN = "ROLE_ADMIN";

export const KNOWN_ROLES = [ROLE_USER, ROLE_CASHIER, ROLE_ADMIN];

export const isKnownRole = (role) => KNOWN_ROLES.includes(role);

export const homePathFor = (role) => {
    if (role === ROLE_ADMIN) return "/dashboard";
    if (role === ROLE_CASHIER) return "/pos";
    if (role === ROLE_USER) return "/home";
    return "/login";
};

export const rootPathFor = (token, role) => (token && isKnownRole(role) ? homePathFor(role) : "/login");

export const NAV_ITEMS = {
    [ROLE_USER]: [
        {to: "/home", label: "Home"},
        {to: "/explore", label: "Shop"},
        {to: "/cart", label: "Cart"},
        {to: "/orders", label: "My Orders"},
    ],
    [ROLE_CASHIER]: [
        {to: "/pos", label: "POS"},
        {to: "/sales", label: "My Sales"},
    ],
    [ROLE_ADMIN]: [
        {to: "/dashboard", label: "Dashboard"},
        {to: "/analytics", label: "Analytics"},
        {to: "/orders", label: "All Orders"},
        {to: "/items", label: "Manage Items"},
        {to: "/category", label: "Manage Categories"},
        {to: "/cashiers", label: "Manage Cashiers"},
        {to: "/activity", label: "System Activity"},
    ],
};
