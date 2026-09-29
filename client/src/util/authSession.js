import {homePathFor, isKnownRole} from "./roles.js";

export const startSession = (authData, setAuthData, navigate) => {
    localStorage.setItem("token", authData.token);
    localStorage.setItem("role", authData.role);

    setAuthData(authData.token, authData.role);

    navigate(homePathFor(authData.role));
};

export const hasSession = (token, role) => Boolean(token) && isKnownRole(role);
