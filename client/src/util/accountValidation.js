// Client-side (UX only) validation for the Account page. The backend re-validates everything and
// is the authority; these rules mirror it so most mistakes are caught before a request is sent.

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// Optional +91 / 91 / leading 0, common separators, then a 10-digit Indian mobile (starts 6-9).
export const normalizeMobile = (value) => {
    let digits = (value || "").trim().replace(/[\s\-().]/g, "");
    if (digits.startsWith("+91")) {
        digits = digits.slice(3);
    } else if (digits.length === 12 && digits.startsWith("91")) {
        digits = digits.slice(2);
    } else if (digits.length === 11 && digits.startsWith("0")) {
        digits = digits.slice(1);
    }
    return /^[6-9][0-9]{9}$/.test(digits) ? digits : null;
};

// The password policy, mirroring the backend's PasswordPolicy (which is the authority). One list
// drives both the error message and the live hints, so they cannot disagree.
export const PASSWORD_RULES = [
    {key: "length", label: "8–72 characters", missing: "8–72 characters", test: (p) => p.length >= 8 && p.length <= 72},
    {key: "lower", label: "Lowercase letter", missing: "a lowercase letter", test: (p) => /[a-z]/.test(p)},
    {key: "upper", label: "Uppercase letter", missing: "an uppercase letter", test: (p) => /[A-Z]/.test(p)},
    {key: "number", label: "Number", missing: "a number", test: (p) => /[0-9]/.test(p)},
    {key: "special", label: "Special character", missing: "a special character", test: (p) => /[^A-Za-z0-9]/.test(p)},
];

// [{key, label, met}] for the live hints under a password field.
export const passwordRuleStatus = (password) =>
    PASSWORD_RULES.map(({key, label, test}) => ({key, label, met: test(password || "")}));

export const passwordPolicyError = (password) => {
    if (!password) return "Password is required";
    const unmet = PASSWORD_RULES.filter((rule) => !rule.test(password));
    if (unmet.length === 0) return null;
    if (unmet.length === 1 && unmet[0].key === "length") return "Password must be between 8 and 72 characters";
    return `Password must include ${unmet.map((rule) => rule.missing).join(", ")}`;
};

export const emailError = (email) => {
    const value = (email || "").trim();
    if (!value) return "Email is required";
    if (value.length > 254 || !EMAIL_PATTERN.test(value)) return "Enter a valid email address";
    return null;
};

// `hadMobile`: an account that already has a mobile number cannot clear it (one that never had
// one may leave it blank).
export const validateProfile = (form, hadMobile) => {
    const errors = {};
    const name = form.name.trim();
    if (!name) errors.name = "Name is required";
    else if (name.length > 100) errors.name = "Name must be at most 100 characters";
    const email = form.email.trim();
    if (!email) errors.email = "Email is required";
    else if (!EMAIL_PATTERN.test(email)) errors.email = "Enter a valid email address";
    const mobile = form.mobile.trim();
    if (!mobile) {
        if (hadMobile) errors.mobile = "Mobile is required";
    } else if (!normalizeMobile(mobile)) {
        errors.mobile = "Enter a valid 10-digit Indian mobile number";
    }
    return errors;
};

export const validatePasswordChange = (form) => {
    const errors = {};
    if (!form.currentPassword) errors.currentPassword = "Current password is required";
    const policy = passwordPolicyError(form.newPassword);
    if (policy) errors.newPassword = policy;
    else if (form.newPassword === form.currentPassword) errors.newPassword = "New password must be different from the current password";
    if (!form.confirmNewPassword) errors.confirmNewPassword = "Please confirm the new password";
    else if (form.confirmNewPassword !== form.newPassword) errors.confirmNewPassword = "Passwords do not match";
    return errors;
};
