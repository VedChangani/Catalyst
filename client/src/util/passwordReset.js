import {emailError, passwordPolicyError} from "./accountValidation.js";

// Pure logic of the customer Forgot Password screen, kept out of the component so it can be unit
// tested. Everything sensitive (code, passwords) lives only in this in-memory state: never
// persisted, put in a URL or logged.

export const OTP_LENGTH = 6;
export const RESEND_SECONDS = 60;
export const FORGOT_MESSAGE = "If an eligible account exists for that email, a verification code has been sent.";
export const TOO_MANY_MESSAGE = "Too many requests. Please try again later.";

// Only digits, at most six.
export const sanitizeOtp = (value) => (value || "").replace(/\D/g, "").slice(0, OTP_LENGTH);

export const validateEmailStep = (email) => emailError(email);

// Client-side checks before /reset-password is called; the backend re-validates everything.
export const validateResetForm = ({otp, newPassword, confirmNewPassword}) => {
    const errors = {};
    if (!/^\d{6}$/.test(otp || "")) errors.otp = "Enter the 6-digit code";
    const policy = passwordPolicyError(newPassword);
    if (policy) errors.newPassword = policy;
    if (!confirmNewPassword) errors.confirmNewPassword = "Please confirm the new password";
    else if (confirmNewPassword !== newPassword) errors.confirmNewPassword = "Passwords do not match";
    return errors;
};

// Exactly the structure POST /reset-password expects - no user id, role or mobile.
export const buildResetPayload = (email, {otp, newPassword, confirmNewPassword}) => ({
    email: email.trim(),
    otp,
    newPassword,
    confirmNewPassword,
});

// Whole seconds until the resend button unlocks (0 = available).
export const secondsLeft = (availableAt, now) =>
    availableAt == null ? 0 : Math.max(0, Math.ceil((availableAt - now) / 1000));

// What to show for a failed request. 429 is not mapped by the shared API client, so it is handled
// here; the backend's own 400 text (e.g. the generic invalid-code message) is shown as it is.
export const requestErrorMessage = (error, fallback) => {
    if (error?.response?.status === 429) return TOO_MANY_MESSAGE;
    return error?.friendlyMessage || fallback;
};

export const initialState = {
    step: "email", // "email" -> "code" -> "done"
    email: "",
    otp: "",
    newPassword: "",
    confirmNewPassword: "",
    resendAvailableAt: null,
};

const clearSecrets = {otp: "", newPassword: "", confirmNewPassword: ""};

export const reducer = (state, action) => {
    switch (action.type) {
        case "email":
            return {...state, email: action.value};
        case "field":
            return {...state, [action.name]: action.name === "otp" ? sanitizeOtp(action.value) : action.value};
        // A code was requested for the entered email: go to the code step and start the 60 s wait.
        case "codeSent":
            return {
                ...state, step: "code", email: state.email.trim(), ...clearSecrets,
                resendAvailableAt: action.now + RESEND_SECONDS * 1000,
            };
        // A resend: same email, new wait; whatever the user already typed is kept.
        case "resent":
            return {...state, resendAvailableAt: action.now + RESEND_SECONDS * 1000};
        case "differentEmail":
            return {...initialState};
        case "resetDone":
            return {...initialState, step: "done"};
        default:
            return state;
    }
};
