package org.codeforphilly.bdt.builder.model.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import org.codeforphilly.bdt.builder.api.validation.HttpUrl;

/** Benefit-level defaults, matching the library's BDT.tNextSteps type. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NextSteps(@Valid Eligible eligible, @Valid Ineligible ineligible) {
    // Keep null fields in Firestore merge writes so clearing a contact removes the old value.
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Eligible(
        // Name the field in each message: the API error response carries only the first message.
        @HttpUrl(message = "Application URL: use a full http:// or https:// URL.") String applyUrl,
        @HttpUrl(message = "Help URL: use a full http:// or https:// URL.") String helpUrl,
        String helpPhone,
        String body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Ineligible(String note) {}
}
