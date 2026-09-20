import test from "node:test";
import assert from "node:assert/strict";
import {normalizeMobile, passwordPolicyError, validatePasswordChange, validateProfile} from "./accountValidation.js";

const profile = {name: "Cara", email: "cara@example.com", mobile: "98765 43210"};

test("mobile normalization matches the backend rules", () => {
    for (const ok of ["9876543210", "+91 98765 43210", "098765-43210", "919876543210"]) {
        assert.equal(normalizeMobile(ok), "9876543210", ok);
    }
    for (const bad of ["", "12345", "5876543210", "abcdefghij", "+1 9876543210"]) {
        assert.equal(normalizeMobile(bad), null, bad);
    }
});

test("valid profile passes; each invalid field is reported", () => {
    assert.deepEqual(validateProfile(profile, true), {});
    assert.ok(validateProfile({...profile, name: "  "}, true).name);
    assert.ok(validateProfile({...profile, name: "n".repeat(101)}, true).name);
    assert.ok(validateProfile({...profile, email: "nope"}, true).email);
    assert.ok(validateProfile({...profile, mobile: "123"}, true).mobile);
});

test("a blank mobile is only allowed for an account that never had one", () => {
    assert.ok(validateProfile({...profile, mobile: ""}, true).mobile);
    assert.deepEqual(validateProfile({...profile, mobile: ""}, false), {});
});

test("password policy", () => {
    assert.equal(passwordPolicyError("Abcdefg1"), null);
    for (const bad of ["", "short1", "onlyletters", "12345678", "a1".repeat(40)]) {
        assert.ok(passwordPolicyError(bad), bad);
    }
});

test("password change requires current, a policy-passing different new password, and a matching confirmation", () => {
    const good = {currentPassword: "Oldpass123", newPassword: "Newpass456", confirmNewPassword: "Newpass456"};
    assert.deepEqual(validatePasswordChange(good), {});
    assert.ok(validatePasswordChange({...good, currentPassword: ""}).currentPassword);
    assert.ok(validatePasswordChange({...good, newPassword: "short", confirmNewPassword: "short"}).newPassword);
    assert.ok(validatePasswordChange({...good, confirmNewPassword: "Different1"}).confirmNewPassword);
    assert.ok(validatePasswordChange({...good, confirmNewPassword: ""}).confirmNewPassword);
    assert.ok(validatePasswordChange({...good, newPassword: "Oldpass123", confirmNewPassword: "Oldpass123"}).newPassword);
});
