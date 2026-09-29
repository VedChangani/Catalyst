package in.vedchangani.billingsoftware.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {

    @Test
    void acceptsAPasswordWithEveryRequiredClass() {
        assertTrue(PasswordPolicy.isValid("Passw0rd!"));
        assertTrue(PasswordPolicy.isValid("aA1!aaaa"));
        assertTrue(PasswordPolicy.isValid("aA1!" + "x".repeat(68)));
        assertTrue(PasswordPolicy.isValid("Pass word1 "));
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
    void namedWeakPasswordsAreRejected_andAStrongOneIsAccepted() {
        for (String weak : new String[]{"password123", "Password123", "Password!", "12345678!"}) {
            assertFalse(PasswordPolicy.isValid(weak), weak);
        }
        assertTrue(PasswordPolicy.isValid("CatalystTest123!"));
    }
}
