import {homePathFor, isKnownRole} from "./roles.js";

// Stores a successful /login response and sends the user to their role's home screen. Shared by
// the Login and Register pages so both use the same session handling and role-aware redirect.
export const startSession = (authData, setAuthData, navigate) => {
    localStorage.setItem("token", authData.token);
    localStorage.setItem("role", authData.role);

    setAuthData(authData.token, authData.role);

    navigate(homePathFor(authData.role));
};

// True only for a real session (a token AND a known role). Anything else - notably the
// setAuthData(null, null) used on logout and after a password change - is "signed out", and must
// never trigger requests to protected endpoints such as the catalog.
export const hasSession = (token, role) => Boolean(token) && isKnownRole(role);
