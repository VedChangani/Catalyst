package in.vedchangani.billingsoftware.io.validation;

import in.vedchangani.billingsoftware.util.PasswordPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || PasswordPolicy.isValid(value);
    }
}
