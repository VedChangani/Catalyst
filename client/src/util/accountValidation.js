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

export const passwordPolicyError = (password) => {
    if (!password) return "Password is required";
    if (password.length < 8 || password.length > 72) return "Password must be between 8 and 72 characters";
    if (!/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) {
        return "Password must contain at least one letter and one number";
    }
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
