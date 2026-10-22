package org.codeforphilly.bdt.builder.api.validation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.NextSteps;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.CreateCustomBenefitRequest;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.UpdateCustomBenefitRequest;
import org.codeforphilly.bdt.builder.model.dto.screener.ScreenerTransfer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NextStepsValidationTest {
    @Test
    void validatesLinksInCreateUpdateAndTransferRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String url : List.of("javascript:alert(1)", "ftp://example.org", "example.org", "https://", "https://bad host/", "https://example.org:99999/",
                    "https://www.phila.gov@evil.example/apply", "https://user:pass@example.org/", "https://my_site.example.org/")) {
                var content = new NextSteps(new NextSteps.Eligible(url, null, null, "Apply"), null);
                assertFalse(validator.validate(new CreateCustomBenefitRequest("Benefit", "", content)).isEmpty(), url);
                assertFalse(validator.validate(new UpdateCustomBenefitRequest(null, null, content)).isEmpty(), url);
                var benefit = new Benefit("benefit", "Benefit", "", "owner", List.of());
                benefit.setNextSteps(content);
                var transfer = new ScreenerTransfer(ScreenerTransfer.FORMAT, ScreenerTransfer.VERSION,
                    "Screener", null, List.of(benefit), List.of());
                assertFalse(validator.validate(transfer).isEmpty(), url);
            }
            // Includes the editor's normalized forms (see normalizeHttpUrl in NextStepsEditor.tsx).
            for (String url : List.of("https://example.org/apply?q=one#form", "http://localhost:8080/apply", "",
                    "https://example.org/my%20form", "https://example.org/apply?a%7Cb", "https://example.org/%25zz",
                    "https://example.org/a#b%23c", "https://xn--bcher-kva.de/")) {
                var content = new NextSteps(new NextSteps.Eligible(url, url, null, null), null);
                assertTrue(validator.validate(new UpdateCustomBenefitRequest(null, null, content)).isEmpty(), url);
            }
            var atLimit = new NextSteps(new NextSteps.Eligible(null, null, "1".repeat(NextSteps.MAX_PHONE_LENGTH),
                "b".repeat(NextSteps.MAX_BODY_LENGTH)), new NextSteps.Ineligible("n".repeat(NextSteps.MAX_NOTE_LENGTH)));
            assertTrue(validator.validate(new UpdateCustomBenefitRequest(null, null, atLimit)).isEmpty());
            for (var tooLong : List.of(
                    new NextSteps(new NextSteps.Eligible(null, null, null, "b".repeat(NextSteps.MAX_BODY_LENGTH + 1)), null),
                    new NextSteps(null, new NextSteps.Ineligible("n".repeat(NextSteps.MAX_NOTE_LENGTH + 1))),
                    new NextSteps(new NextSteps.Eligible(null, null, "1".repeat(NextSteps.MAX_PHONE_LENGTH + 1), null), null),
                    new NextSteps(new NextSteps.Eligible("https://example.org/" + "a".repeat(NextSteps.MAX_URL_LENGTH), null, null, null), null))) {
                assertEquals(1, validator.validate(new UpdateCustomBenefitRequest(null, null, tooLong)).size());
            }
            assertTrue(validator.validate(new CreateCustomBenefitRequest("Legacy benefit", "", null)).isEmpty());
            assertTrue(validator.validate(new UpdateCustomBenefitRequest("Renamed", null, null)).isEmpty());
            assertFalse(validator.validate(new UpdateCustomBenefitRequest(null, null, null)).isEmpty());
        }
    }

    @Test
    void firestoreMergeWritesKeepExplicitNullsForClearedContacts() throws Exception {
        var mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var benefit = new Benefit("benefit", "Benefit", "", "owner", List.of());
        benefit.setNextSteps(new NextSteps(new NextSteps.Eligible(null, null, null, "Keep these instructions"),
            new NextSteps.Ineligible(null)));
        var stored = mapper.valueToTree(benefit).path("nextSteps");
        for (String field : List.of("applyUrl", "helpUrl", "helpPhone")) {
            assertTrue(stored.path("eligible").has(field), field + " must be written to clear its old value");
            assertTrue(stored.path("eligible").get(field).isNull());
        }
        assertTrue(stored.path("ineligible").get("note").isNull());
        assertEquals(benefit.getNextSteps(), mapper.treeToValue(stored, NextSteps.class));
        assertNull(mapper.readValue("{\"id\":\"old\",\"name\":\"Legacy\"}", Benefit.class).getNextSteps());
    }
}
