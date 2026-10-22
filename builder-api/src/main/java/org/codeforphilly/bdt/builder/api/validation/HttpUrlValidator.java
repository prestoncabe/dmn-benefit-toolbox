package org.codeforphilly.bdt.builder.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.URI;

public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || isHttpUrl(value);
    }

    public static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            // Userinfo lets a link look like one site while going to another (https://good.example@evil.example).
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getPort() <= 65535;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
