import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {
    buildResetPayload, FORGOT_MESSAGE, initialState, RESEND_SECONDS, reducer, requestErrorMessage,
    sanitizeOtp, secondsLeft, TOO_MANY_MESSAGE, validateEmailStep, validateResetForm,
} from "./passwordReset.js";
import {passwordPolicyError, passwordRuleStatus} from "./accountValidation.js";

const GOOD = "Fresh#Pass1";
const form = {otp: "123456", newPassword: GOOD, confirmNewPassword: GOOD};
const NOW = 1_000_000;
const read = (relative) => readFileSync(new URL(relative, import.meta.url), "utf8");

test("step 1: the initial screen is the email step with nothing stored", () => {
    assert.equal(initialState.step, "email");
    assert.equal(initialState.email, "");
    assert.equal(initialState.otp + initialState.newPassword + initialState.confirmNewPassword, "");
});

test("email validation", () => {
    assert.equal(validateEmailStep("cara@example.com"), null);
    assert.equal(validateEmailStep("  cara@example.com  "), null);
    for (const bad of ["", "   ", "nope", "a@b", "a b@example.com"]) assert.ok(validateEmailStep(bad), bad);
});

test("a successful /forgot-password moves to step 2, keeps the email and starts the 60 s countdown", () => {
    let state = reducer(initialState, {type: "email", value: "  cara@example.com "});
    state = reducer(state, {type: "codeSent", now: NOW});
    assert.equal(state.step, "code");
    assert.equal(state.email, "cara@example.com");
    assert.equal(RESEND_SECONDS, 60);
    assert.equal(secondsLeft(state.resendAvailableAt, NOW), 60);
});

test("the neutral message is the backend one and says nothing about the account", () => {
    assert.equal(FORGOT_MESSAGE, "If an eligible account exists for that email, a verification code has been sent.");
    assert.doesNotMatch(FORGOT_MESSAGE, /cashier|admin|disabled|not found|customer/i);
});

test("the code field accepts only up to six digits", () => {
    assert.equal(sanitizeOtp("12a3-45 678"), "123456");
    assert.equal(sanitizeOtp("abc"), "");
    assert.equal(sanitizeOtp(null), "");
    const state = reducer({...initialState, step: "code"}, {type: "field", name: "otp", value: "9x8 7654321"});
    assert.equal(state.otp, "987654");
});

test("resend is locked during the countdown and unlocks when it reaches zero", () => {
    const state = reducer({...initialState, email: "a@b.co"}, {type: "codeSent", now: NOW});
    assert.equal(secondsLeft(state.resendAvailableAt, NOW + 13_000), 47);
    assert.equal(secondsLeft(state.resendAvailableAt, NOW + 59_001), 1);
    assert.ok(secondsLeft(state.resendAvailableAt, NOW + 59_999) > 0, "still disabled just before 60 s");
    assert.equal(secondsLeft(state.resendAvailableAt, NOW + 60_000), 0, "available after 60 s");
    assert.equal(secondsLeft(state.resendAvailableAt, NOW + 90_000), 0);
    assert.equal(secondsLeft(null, NOW), 0);
});

test("a resend restarts the countdown and keeps what the user typed", () => {
    let state = reducer({...initialState, email: "a@b.co"}, {type: "codeSent", now: NOW});
    state = reducer(state, {type: "field", name: "newPassword", value: GOOD});
    state = reducer(state, {type: "resent", now: NOW + 61_000});
    assert.equal(state.newPassword, GOOD);
    assert.equal(secondsLeft(state.resendAvailableAt, NOW + 61_000), 60);
});

test("password policy: weak passwords are rejected, a strong one is accepted", () => {
    const weakOnes = ["", "Sh0rt!a", "alllowercase1!", "ALLUPPERCASE1!", "NoNumbers!!", "NoSpecial123", "Aa1!" + "x".repeat(70)];
    for (const weak of weakOnes) {
        assert.ok(validateResetForm({...form, newPassword: weak, confirmNewPassword: weak}).newPassword, weak);
    }
    assert.deepEqual(validateResetForm(form), {});
    assert.equal(passwordPolicyError(GOOD), null);
    assert.match(passwordPolicyError("password"), /uppercase.*number.*special/);
});

test("live hints report each rule individually", () => {
    const met = (password) => Object.fromEntries(passwordRuleStatus(password).map((r) => [r.key, r.met]));
    assert.deepEqual(met(""), {length: false, lower: false, upper: false, number: false, special: false});
    assert.deepEqual(met("abcdefgh"), {length: true, lower: true, upper: false, number: false, special: false});
    assert.deepEqual(met(GOOD), {length: true, lower: true, upper: true, number: true, special: true});
});

test("a mismatched confirmation is caught before any request", () => {
    const errors = validateResetForm({...form, confirmNewPassword: GOOD + "x"});
    assert.equal(errors.confirmNewPassword, "Passwords do not match");
    assert.ok(validateResetForm({...form, confirmNewPassword: ""}).confirmNewPassword);
});

test("a malformed code is caught before any request", () => {
    for (const otp of ["", "12345", "1234567", "12345a"]) assert.ok(validateResetForm({...form, otp}).otp, otp);
});

test("the /reset-password request has exactly the expected structure", () => {
    assert.deepEqual(buildResetPayload(" cara@example.com ", form), {
        email: "cara@example.com", otp: "123456", newPassword: GOOD, confirmNewPassword: GOOD,
    });
    assert.deepEqual(Object.keys(buildResetPayload("a@b.co", form)).sort(),
        ["confirmNewPassword", "email", "newPassword", "otp"]);
});

test("success shows the done step and leaves no code, password or email in memory", () => {
    let state = reducer({...initialState, step: "code", email: "a@b.co"}, {type: "field", name: "otp", value: "123456"});
    state = reducer(state, {type: "field", name: "newPassword", value: GOOD});
    state = reducer(state, {type: "resetDone"});
    assert.equal(state.step, "done");
    assert.deepEqual(
        {o: state.otp, p: state.newPassword, c: state.confirmNewPassword, e: state.email, t: state.resendAvailableAt},
        {o: "", p: "", c: "", e: "", t: null});
});

test("the reset flow never stores anything or starts a session, and logs nothing", () => {
    for (const source of [read("../pages/ForgotPassword/ForgotPassword.jsx"), read("./passwordReset.js")]) {
        assert.doesNotMatch(source, /localStorage|sessionStorage|setAuthData|startSession|console\.(log|debug|info)/);
    }
});

test("errors: the backend 400 text is shown as it is, 429 gets its own message, a bare failure gets the fallback", () => {
    assert.equal(requestErrorMessage({friendlyMessage: "The code is invalid or has expired.", response: {status: 400}}, "x"),
        "The code is invalid or has expired.");
    assert.equal(requestErrorMessage({friendlyMessage: "Something went wrong. Please try again.", response: {status: 429}}, "x"),
        TOO_MANY_MESSAGE);
    assert.equal(requestErrorMessage({}, "Unable to send the code"), "Unable to send the code");
});

test("a failed reset dispatches nothing, so the entered fields and step are kept", () => {
    const before = {...initialState, step: "code", email: "a@b.co", otp: "123456", newPassword: GOOD, confirmNewPassword: GOOD};
    assert.deepEqual(reducer(before, {type: "unrelated"}), before);
});

test("use a different email returns to step 1 and clears everything sensitive and the timer", () => {
    const state = reducer({
        ...initialState, step: "code", email: "a@b.co", otp: "123456", newPassword: GOOD,
        confirmNewPassword: GOOD, resendAvailableAt: NOW + 30_000,
    }, {type: "differentEmail"});
    assert.deepEqual(state, initialState);
});

test("the login screen links to /forgot-password and the route is public with no app navigation", () => {
    assert.match(read("../pages/Login/Login.jsx"), /to="\/forgot-password"[\s\S]*Forgot password\?/);
    const app = read("../App.jsx");
    assert.match(app, /path="\/forgot-password" element=\{<LoginRoute element=\{<ForgotPassword \/>\} \/>\}/);
    assert.match(app, /location\.pathname === "\/forgot-password"/);
});

test("both API calls are the public endpoints, with nothing in the URL", () => {
    const service = read("../Service/AuthService.js");
    assert.match(service, /post\("\/forgot-password", \{email\}\)/);
    assert.match(service, /post\("\/reset-password", payload\)/);
});
