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
                    "https://www.phila.gov@evil.example/apply", "https://user:pass@example.org/")) {
                var content = new NextSteps(new NextSteps.Eligible(url, null, null, "Apply"), null);
                assertFalse(validator.validate(new CreateCustomBenefitRequest("Benefit", "", content)).isEmpty(), url);
                assertFalse(validator.validate(new UpdateCustomBenefitRequest(null, null, content)).isEmpty(), url);
                var benefit = new Benefit("benefit", "Benefit", "", "owner", List.of());
                benefit.setNextSteps(content);
                var transfer = new ScreenerTransfer(ScreenerTransfer.FORMAT, ScreenerTransfer.VERSION,
                    "Screener", null, List.of(benefit), List.of());
                assertFalse(validator.validate(transfer).isEmpty(), url);
            }
            for (String url : List.of("https://example.org/apply?q=one#form", "http://localhost:8080/apply", "")) {
                var content = new NextSteps(new NextSteps.Eligible(url, url, null, null), null);
                assertTrue(validator.validate(new UpdateCustomBenefitRequest(null, null, content)).isEmpty(), url);
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
