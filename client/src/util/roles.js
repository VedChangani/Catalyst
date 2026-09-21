// Single place that maps each role to its home screen and navigation. Routes (App.jsx) and the
// Menubar both read from here, so what a role can see in the menu and what it may open cannot
// drift apart. This is a UX layer only - the backend (SecurityConfig + service checks) is the
// real authority for every action.

export const ROLE_USER = "ROLE_USER";
export const ROLE_CASHIER = "ROLE_CASHIER";
export const ROLE_ADMIN = "ROLE_ADMIN";

export const KNOWN_ROLES = [ROLE_USER, ROLE_CASHIER, ROLE_ADMIN];

export const isKnownRole = (role) => KNOWN_ROLES.includes(role);

// Each role's landing page. An unrecognised role has no home and is sent to /login (anything
// else could bounce between routes that role is not allowed on).
export const homePathFor = (role) => {
    if (role === ROLE_ADMIN) return "/dashboard";
    if (role === ROLE_CASHIER) return "/pos";
    if (role === ROLE_USER) return "/home";
    return "/login";
};

// Where "/" sends a visitor: signed out -> /login, signed in -> that role's home.
export const rootPathFor = (token, role) => (token && isKnownRole(role) ? homePathFor(role) : "/login");

// Account and (for customers/cashiers) the personal Activity Log live in the header dropdown.
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
