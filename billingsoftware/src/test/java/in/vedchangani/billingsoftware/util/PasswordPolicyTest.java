package in.vedchangani.billingsoftware.util;

import in.vedchangani.billingsoftware.io.PasswordResetRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {

    @Test
    void acceptsAPasswordWithEveryRequiredClass() {
        assertTrue(PasswordPolicy.isValid("Passw0rd!"));
        assertTrue(PasswordPolicy.isValid("aA1!aaaa")); // exactly 8
        assertTrue(PasswordPolicy.isValid("aA1!" + "x".repeat(68))); // exactly 72
        assertTrue(PasswordPolicy.isValid("Pass word1 ")); // space counts as special
    }

    @Test
    void rejectsEachMissingRequirement() {
        assertFalse(PasswordPolicy.isValid("passw0rd!"), "no uppercase");
        assertFalse(PasswordPolicy.isValid("PASSW0RD!"), "no lowercase");
        assertFalse(PasswordPolicy.isValid("Password!"), "no digit");
        assertFalse(PasswordPolicy.isValid("Passw0rdd"), "no special character");
        assertFalse(PasswordPolicy.isValid("aA1!aaa"), "7 characters");
        assertFalse(PasswordPolicy.isValid("aA1!" + "x".repeat(69)), "73 characters");
        assertFalse(PasswordPolicy.isValid(""));
        assertFalse(PasswordPolicy.isValid(null));
    }

    @Test
    void legacyLetterAndNumberPasswordsDoNotSatisfyThePolicyForNewPasswords() {
        assertFalse(PasswordPolicy.isValid("password123"));
        assertFalse(PasswordPolicy.isValid("Staffpass123"));
    }

    @Test
    void theResetRequestUsesThePolicyAndRequiresASixDigitCode() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        PasswordResetRequest ok = new PasswordResetRequest("a@example.com", "012345", "Passw0rd!", "Passw0rd!");
        assertTrue(validator.validate(ok).isEmpty());

        assertFalse(validator.validate(
                new PasswordResetRequest("a@example.com", "012345", "password123", "password123")).isEmpty());
        for (String badCode : new String[]{"12345", "1234567", "12345a", "abcdef", " 12345", ""}) {
            assertFalse(validator.validate(
                            new PasswordResetRequest("a@example.com", badCode, "Passw0rd!", "Passw0rd!")).isEmpty(),
                    "code [" + badCode + "] must be rejected");
        }
    }

    @Test
    void theResetRequestNeverPrintsTheCodeOrPasswords() {
        String text = new PasswordResetRequest("a@example.com", "654321", "Passw0rd!", "Passw0rd!").toString();
        assertFalse(text.contains("654321"));
        assertFalse(text.contains("Passw0rd!"));
    }
}
