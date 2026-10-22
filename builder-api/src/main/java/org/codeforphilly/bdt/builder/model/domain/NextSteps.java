package org.codeforphilly.bdt.builder.model.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.codeforphilly.bdt.builder.api.validation.HttpUrl;

/** Benefit-level defaults, matching the library's BDT.tNextSteps type. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NextSteps(@Valid Eligible eligible, @Valid Ineligible ineligible) {
    // Keep these in step with the maxlength attributes in NextStepsEditor.tsx.
    public static final int MAX_URL_LENGTH = 2000;
    public static final int MAX_PHONE_LENGTH = 50;
    public static final int MAX_BODY_LENGTH = 10000;
    public static final int MAX_NOTE_LENGTH = 5000;

    // Keep null fields in Firestore merge writes so clearing a contact removes the old value.
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Eligible(
        // Name the field in each message: the API error response carries only the first message.
        @HttpUrl(message = "Application URL: use a full http:// or https:// URL.")
        @Size(max = MAX_URL_LENGTH, message = "Application URL must be {max} characters or fewer.")
        String applyUrl,
        @HttpUrl(message = "Help URL: use a full http:// or https:// URL.")
        @Size(max = MAX_URL_LENGTH, message = "Help URL must be {max} characters or fewer.")
        String helpUrl,
        @Size(max = MAX_PHONE_LENGTH, message = "Help phone number must be {max} characters or fewer.")
        String helpPhone,
        @Size(max = MAX_BODY_LENGTH, message = "Application instructions must be {max} characters or fewer.")
        String body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Ineligible(
        @Size(max = MAX_NOTE_LENGTH, message = "The ineligible note must be {max} characters or fewer.")
        String note) {}
}
