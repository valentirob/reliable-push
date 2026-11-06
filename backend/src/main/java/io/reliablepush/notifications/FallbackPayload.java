package io.reliablepush.notifications;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;

/** The fallback payload is a JSON object of at most {@link #MAX_BYTES} bytes serialized (spec 5, 4 KB). */
@Documented
@Constraint(validatedBy = FallbackPayload.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface FallbackPayload {

    int MAX_BYTES = 4096;

    String message() default "is required and must be a JSON object of at most 4096 bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<FallbackPayload, JsonNode> {
        @Override
        public boolean isValid(JsonNode value, ConstraintValidatorContext context) {
            return value != null
                    && value.isObject()
                    && value.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
        }
    }
}
