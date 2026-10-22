package org.codeforphilly.bdt.builder.controller;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.CheckConfig;
import org.codeforphilly.bdt.builder.model.domain.EligibilityCheck;
import org.codeforphilly.bdt.builder.model.domain.ParameterDefinition;
import org.codeforphilly.bdt.builder.model.domain.Screener;
import org.codeforphilly.bdt.builder.model.domain.NextSteps;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.CreateCustomBenefitRequest;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.UpdateCustomBenefitRequest;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.AddCheckRequest;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.UpdateCheckAliasRequest;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.UpdateCheckParametersRequest;
import org.codeforphilly.bdt.builder.persistence.EligibilityCheckRepository;
import org.codeforphilly.bdt.builder.persistence.ScreenerRepository;
import org.codeforphilly.bdt.builder.service.EligibilityCheckAliasService;
import org.codeforphilly.bdt.builder.service.LibraryApiService;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomBenefitResourceTest {
    private static final String USER_ID = "analyst-1";
    private static final String SCREENER_ID = "screener-1";
    private static final String BENEFIT_ID = "benefit-1";

    private final ScreenerRepository screenerRepository = mock(ScreenerRepository.class);
    private final EligibilityCheckRepository checkRepository = mock(EligibilityCheckRepository.class);
    private final LibraryApiService libraryApiService = mock(LibraryApiService.class);
    private final EligibilityCheckAliasService aliasService = mock(EligibilityCheckAliasService.class);
    private final SecurityIdentity identity = mock(SecurityIdentity.class);
    private final CustomBenefitResource resource = new CustomBenefitResource();

    @BeforeEach
    void setUp() {
        resource.screenerRepository = screenerRepository;
        resource.eligibilityCheckRepository = checkRepository;
        resource.libraryApiMetadataService = libraryApiService;
        resource.eligibilityCheckAliasService = aliasService;

        JsonWebToken token = mock(JsonWebToken.class);
        when(identity.getPrincipal()).thenReturn(token);
        when(token.getClaim("user_id")).thenReturn(USER_ID);

        Screener screener = new Screener();
        screener.setOwnerId(USER_ID);
        when(screenerRepository.getWorkingScreenerMetaDataOnly(SCREENER_ID))
            .thenReturn(Optional.of(screener));
    }

    @Test
    void createsAndUpdatesNextStepsWithoutChangingRulesOrBenefitDetails() throws Exception {
        Screener screener = new Screener();
        screener.setOwnerId(USER_ID);
        screener.setBenefits(List.of());
        when(screenerRepository.getWorkingScreener(SCREENER_ID)).thenReturn(Optional.of(screener));
        var content = new NextSteps(new NextSteps.Eligible("https://example.org/apply", null, "215-555-0100", "Apply here"),
            new NextSteps.Ineligible("Ask about other programs"));
        Response created = resource.createCustomBenefit(identity, SCREENER_ID,
            new CreateCustomBenefitRequest("Benefit", "Description", content));
        assertEquals(200, created.getStatus());
        Benefit benefit = (Benefit) created.getEntity();
        assertEquals(content, benefit.getNextSteps());
        assertEquals(USER_ID, benefit.getOwnerId());

        CheckConfig check = new CheckConfig();
        check.setCheckId("existing-check");
        benefit.setChecks(List.of(check));
        when(screenerRepository.getCustomBenefit(SCREENER_ID, benefit.getId())).thenReturn(Optional.of(benefit));
        var cleared = new NextSteps(new NextSteps.Eligible(null, null, null, null), new NextSteps.Ineligible(null));
        Response updated = resource.updateCustomBenefit(identity, SCREENER_ID, benefit.getId(),
            new UpdateCustomBenefitRequest(null, null, cleared));
        assertEquals(200, updated.getStatus());
        assertEquals(cleared, benefit.getNextSteps());
        assertEquals("Benefit", benefit.getName());
        assertEquals(List.of(check), benefit.getChecks());
        verify(screenerRepository, never()).updateWorkingScreener(any());

        resource.updateCustomBenefit(identity, SCREENER_ID, benefit.getId(),
            new UpdateCustomBenefitRequest("Renamed", null, null));
        assertEquals(cleared, benefit.getNextSteps(), "Omitting nextSteps must preserve saved content");
    }

    @Test
    void addCheckStoresParametersAndAnAutomaticallyGeneratedAlias() throws Exception {
        Benefit benefit = new Benefit(BENEFIT_ID, "Benefit", "", USER_ID, new ArrayList<>());
        EligibilityCheck check = new EligibilityCheck();
        check.setId("published-check");
        check.setName("PersonNotEnrolledInBenefit");
        check.setModule("enrollment");
        check.setVersion("1.0.0");
        check.setParameterDefinitions(List.of());
        Map<String, Object> parameters = Map.of(
            "personId", "client",
            "benefit", "PhlHomesteadExemption"
        );

        when(screenerRepository.getCustomBenefit(SCREENER_ID, BENEFIT_ID)).thenReturn(Optional.of(benefit));
        when(checkRepository.getPublishedCustomCheck(USER_ID, "published-check")).thenReturn(Optional.of(check));
        when(aliasService.generate(check.getName(), parameters))
            .thenReturn(Optional.of("Client not already enrolled in Homestead Exemption"));

        Response response = resource.addCheckToBenefit(
            identity,
            SCREENER_ID,
            BENEFIT_ID,
            new AddCheckRequest("published-check", parameters)
        );

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        ArgumentCaptor<Benefit> benefitCaptor = ArgumentCaptor.forClass(Benefit.class);
        verify(screenerRepository).updateCustomBenefit(
            org.mockito.ArgumentMatchers.eq(SCREENER_ID),
            benefitCaptor.capture()
        );
        CheckConfig savedCheck = benefitCaptor.getValue().getChecks().getFirst();
        assertEquals(parameters, savedCheck.getParameters());
        assertEquals("Client not already enrolled in Homestead Exemption", savedCheck.getAliasName());
        assertTrue(savedCheck.isAliasGenerated());
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateCheckAliasReturnsAPreviewWithoutOverwritingManualAlias() throws Exception {
        CheckConfig check = new CheckConfig();
        check.setCheckId("configured-check");
        check.setCheckName("IncomeThreshold");
        check.setParameters(Map.of("limit", 50_000));
        check.setAliasName("Manual alias");
        Benefit benefit = new Benefit(BENEFIT_ID, "Benefit", "", USER_ID, List.of(check));

        when(screenerRepository.getCustomBenefit(SCREENER_ID, BENEFIT_ID)).thenReturn(Optional.of(benefit));
        when(aliasService.generate("IncomeThreshold", check.getParameters())).thenReturn(Optional.of("Income under $50,000"));

        Response response = resource.generateCheckAlias(identity, SCREENER_ID, BENEFIT_ID, "configured-check");

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals("Income under $50,000", ((Map<String, String>) response.getEntity()).get("aliasName"));
        assertEquals("Manual alias", check.getAliasName());
        verify(screenerRepository, never()).updateCustomBenefit(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void addCheckStillAddsTheCheckWhenAliasGenerationFails() throws Exception {
        Benefit benefit = new Benefit(BENEFIT_ID, "Benefit", "", USER_ID, new ArrayList<>());
        EligibilityCheck check = new EligibilityCheck();
        check.setId("published-check");
        check.setName("OwnerOccupant");
        check.setParameterDefinitions(List.of());

        when(screenerRepository.getCustomBenefit(SCREENER_ID, BENEFIT_ID)).thenReturn(Optional.of(benefit));
        when(checkRepository.getPublishedCustomCheck(USER_ID, "published-check")).thenReturn(Optional.of(check));
        when(aliasService.generate(any(), any())).thenReturn(Optional.empty());

        Response response = resource.addCheckToBenefit(
            identity,
            SCREENER_ID,
            BENEFIT_ID,
            new AddCheckRequest("published-check", Map.of())
        );

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(false, ((Map<String, Object>) response.getEntity()).get("aliasGenerated"));
        ArgumentCaptor<Benefit> benefitCaptor = ArgumentCaptor.forClass(Benefit.class);
        verify(screenerRepository).updateCustomBenefit(
            org.mockito.ArgumentMatchers.eq(SCREENER_ID),
            benefitCaptor.capture()
        );
        assertEquals(null, benefitCaptor.getValue().getChecks().getFirst().getAliasName());
    }

    @Test
    void generateCheckAliasReportsFailure() throws Exception {
        CheckConfig check = new CheckConfig();
        check.setCheckId("configured-check");
        check.setCheckName("IncomeThreshold");
        check.setParameters(Map.of("limit", 50_000));
        Benefit benefit = new Benefit(BENEFIT_ID, "Benefit", "", USER_ID, List.of(check));

        when(screenerRepository.getCustomBenefit(SCREENER_ID, BENEFIT_ID)).thenReturn(Optional.of(benefit));
        when(aliasService.generate(any(), any())).thenReturn(Optional.empty());

        Response response = resource.generateCheckAlias(identity, SCREENER_ID, BENEFIT_ID, "configured-check");

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateCheckParametersRegeneratesAGeneratedAlias() throws Exception {
        CheckConfig check = configuredIncomeCheck("Income under $50,000", true);
        Benefit benefit = new Benefit(BENEFIT_ID, "Benefit", "", USER_ID, List.of(check));
        Map<String, Object> updatedParameters = Map.of("limit", 60_000);

        when(screenerRepository.getCustomBenefit(SCREENER_ID, BENEFIT_ID)).thenReturn(Optional.of(benefit));
        when(aliasService.generate("IncomeThreshold", updatedParameters))
            .thenReturn(Optional.of("Income under $60,000"));

        Response response = resource.updateCheckParameters(
            identity, SCREENER_ID, BENEFIT_ID, "configured-check",
            new UpdateCheckParametersRequest(updatedParameters)
        );

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(false, ((Map<String, Object>) response.getEntity()).get("aliasCleared"));
        assertEquals("Income under $60,000", check.getAliasName());
        assertTrue(check.isAliasGenerated());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateCheckParametersClearsAGeneratedAliasWhenRegenerationFails() throws Exception {
        CheckConfig check = configuredIncomeCheck("Income under $50,000", true);
        Benefit benefit = new Benefit(BENEFIT_ID, "Benefit", "", USER_ID, List.of(check));

        when(screenerRepository.getCustomBenefit(SCREENER_ID, BENEFIT_ID)).thenReturn(Optional.of(benefit));
        when(aliasService.generate(any(), any())).thenReturn(Optional.empty());

        Response response = resource.updateCheckParameters(
            identity, SCREENER_ID, BENEFIT_ID, "configured-check",
            new UpdateCheckParametersRequest(Map.of("limit", 60_000))
        );

        assertEquals(true, ((Map<String, Object>) response.getEntity()).get("aliasCleared"));
        assertNull(check.getAliasName());
        assertFalse(check.isAliasGenerated());
    }

    @Test
    void updateCheckParametersKeepsAHandWrittenAlias() throws Exception {
        CheckConfig check = configuredIncomeCheck("My income check", false);
        Benefit benefit = new Benefit(BENEFIT_ID, "Benefit", "", USER_ID, List.of(check));

        when(screenerRepository.getCustomBenefit(SCREENER_ID, BENEFIT_ID)).thenReturn(Optional.of(benefit));

        resource.updateCheckParameters(
            identity, SCREENER_ID, BENEFIT_ID, "configured-check",
            new UpdateCheckParametersRequest(Map.of("limit", 60_000))
        );

        assertEquals("My income check", check.getAliasName());
        verify(aliasService, never()).generate(any(), any());
    }

    @Test
    void updateCheckParametersSkipsRegenerationWhenValuesAreUnchanged() throws Exception {
        CheckConfig check = configuredIncomeCheck("Income under $50,000", true);
        // Firestore returns whole numbers as Long; the frontend sends Integer
        check.setParameters(Map.of("limit", 50_000L));
        Benefit benefit = new Benefit(BENEFIT_ID, "Benefit", "", USER_ID, List.of(check));

        when(screenerRepository.getCustomBenefit(SCREENER_ID, BENEFIT_ID)).thenReturn(Optional.of(benefit));

        resource.updateCheckParameters(
            identity, SCREENER_ID, BENEFIT_ID, "configured-check",
            new UpdateCheckParametersRequest(Map.of("limit", 50_000))
        );

        assertEquals("Income under $50,000", check.getAliasName());
        verify(aliasService, never()).generate(any(), any());
    }

    @Test
    void updateCheckAliasRecordsWhetherTheAliasWasGenerated() throws Exception {
        CheckConfig check = configuredIncomeCheck("Income under $50,000", true);
        Benefit benefit = new Benefit(BENEFIT_ID, "Benefit", "", USER_ID, List.of(check));
        when(screenerRepository.getCustomBenefit(SCREENER_ID, BENEFIT_ID)).thenReturn(Optional.of(benefit));

        resource.updateCheckAlias(
            identity, SCREENER_ID, BENEFIT_ID, "configured-check",
            new UpdateCheckAliasRequest("My income check", false)
        );
        assertFalse(check.isAliasGenerated());

        resource.updateCheckAlias(
            identity, SCREENER_ID, BENEFIT_ID, "configured-check",
            new UpdateCheckAliasRequest("Income under $50,000", true)
        );
        assertTrue(check.isAliasGenerated());

        resource.updateCheckAlias(
            identity, SCREENER_ID, BENEFIT_ID, "configured-check",
            new UpdateCheckAliasRequest(null, true)
        );
        assertFalse(check.isAliasGenerated());
    }

    private static CheckConfig configuredIncomeCheck(String aliasName, boolean aliasGenerated) {
        CheckConfig check = new CheckConfig();
        check.setCheckId("configured-check");
        check.setCheckName("IncomeThreshold");
        check.setParameters(Map.of("limit", 50_000));
        check.setAliasName(aliasName);
        check.setAliasGenerated(aliasGenerated);
        return check;
    }

    @Test
    void addCheckRejectsMissingRequiredParameters() throws Exception {
        Benefit benefit = new Benefit(BENEFIT_ID, "Benefit", "", USER_ID, new ArrayList<>());
        EligibilityCheck check = new EligibilityCheck();
        check.setId("published-check");
        check.setName("IncomeThreshold");
        ParameterDefinition limit = new ParameterDefinition();
        limit.setKey("limit");
        limit.setType("number");
        limit.setRequired(true);
        check.setParameterDefinitions(List.of(limit));

        when(screenerRepository.getCustomBenefit(SCREENER_ID, BENEFIT_ID)).thenReturn(Optional.of(benefit));
        when(checkRepository.getPublishedCustomCheck(USER_ID, "published-check")).thenReturn(Optional.of(check));

        Response response = resource.addCheckToBenefit(
            identity,
            SCREENER_ID,
            BENEFIT_ID,
            new AddCheckRequest("published-check", Map.of())
        );

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        verify(aliasService, never()).generate(any(), any());
        verify(screenerRepository, never()).updateCustomBenefit(any(), any());
    }
}
