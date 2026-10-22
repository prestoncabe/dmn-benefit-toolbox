package org.codeforphilly.bdt.builder.service;

import org.codeforphilly.bdt.builder.model.domain.CheckConfig;
import org.codeforphilly.bdt.builder.model.domain.ParameterDefinition;
import org.codeforphilly.bdt.builder.model.domain.NextSteps;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class LibraryApiServiceTest {

    @Test
    void loadMetadata_readsChecksAndBenefits() throws Exception {
        LibraryApiService service = new LibraryApiService();
        service.loadMetadata("""
            {
              "checks": [{"id":"check-1","name":"check","module":"module"}],
              "benefits": [{
                "id":"benefit-1","name":"Benefit","description":"Description",
                "checks":[{
                  "checkId":"check-1","sourceCheckId":"check-1","parameters":{},
                  "parameterBindings":{"personId":"primaryPersonId"}
                }]
              }]
            }
            """);

        assertEquals(1, service.getAll().size());
        assertEquals(1, service.getBenefits().size());
        assertEquals("benefit-1", service.getBenefits().getFirst().getId());
        assertEquals(
            Map.of("personId", "client"),
            service.getBenefits().getFirst().getChecks().getFirst().getParameters()
        );
    }

    @Test
    void loadBenefitsMetadata_skipsBenefitsWithUnsupportedBindings() throws Exception {
        LibraryApiService service = new LibraryApiService();

        service.loadBenefitsMetadata("""
            [
              {"id":"supported","name":"Supported","checks":[
                {"checkId":"check-1","parameterBindings":{"personId":"primaryPersonId"}}
              ]},
              {"id":"unsupported","name":"Unsupported","checks":[
                {"checkId":"check-2","parameters":{},"parameterBindings":{"county":"address.county"}}
              ]}
            ]
            """);

        assertEquals(List.of("supported"), service.getBenefits().stream().map(b -> b.getId()).toList());
        assertEquals(
            Map.of("personId", "client"),
            service.getBenefits().getFirst().getChecks().getFirst().getParameters()
        );
    }

    @Test
    void screenerCheckLookupIncludesCompositionChecksFromBenefitMetadata() throws Exception {
        LibraryApiService service = new LibraryApiService();
        service.loadMetadata("""
            {"checks":[{"id":"L-public-0.9.0","name":"public"}],"benefits":[{
              "id":"sctf","name":"Senior Citizen Tax Freeze","checks":[{
                "checkId":"L-internal-sctf-age-requirement-0.9.0",
                "sourceCheckId":"L-internal-sctf-age-requirement-0.9.0",
                "checkName":"sctf-age-requirement","checkModule":"internal","checkVersion":"0.9.0",
                "evaluationUrl":"/api/v1/checks/internal/sctf-age-requirement",
                "parameters":{},"parameterDefinitions":[]
              }]
            }]}
            """);

        String id = "L-internal-sctf-age-requirement-0.9.0";
        var check = service.getScreenerCheckById(id).orElseThrow();
        assertEquals(id, check.getId());
        assertEquals("internal", check.getModule());
        assertEquals("sctf-age-requirement", check.getName());
        assertEquals("0.9.0", check.getVersion());
        assertEquals("/api/v1/checks/internal/sctf-age-requirement", check.getEvaluationUrl());
        assertTrue(service.getById(id).isEmpty());
        assertEquals(1, service.getAll().size());
        assertTrue(service.getScreenerCheckById("L-public-0.9.0").isPresent());
        assertTrue(service.getScreenerCheckById("L-internal-sctf-age-requirement-99.0.0").isEmpty());
    }

    @Test
    void loadMetadata_acceptsLegacyCheckArray() throws Exception {
        LibraryApiService service = new LibraryApiService();
        service.loadMetadata("[{\"id\":\"check-1\",\"name\":\"check\",\"module\":\"module\"}]");

        assertEquals(1, service.getAll().size());
        assertTrue(service.getBenefits().isEmpty());
    }

    @Test
    void loadBenefitsMetadata_readsSeparateBenefitArray() throws Exception {
        LibraryApiService service = new LibraryApiService();

        service.loadBenefitsMetadata("""
            [{"id":"benefit-1","name":"Benefit","description":"Description","checks":[]}]
            """);

        assertEquals(1, service.getBenefits().size());
        assertEquals("benefit-1", service.getBenefits().getFirst().getId());
    }

    @Test
    void copyBenefitForOwner_createsEditableSnapshotIds() throws Exception {
        LibraryApiService service = new LibraryApiService();
        service.loadMetadata("""
            {"checks":[],"benefits":[{
              "id":"library-benefit","name":"Benefit","description":"Description",
              "nextSteps":{"eligible":{"applyUrl":"https://example.org/apply","body":"Apply here"},"ineligible":{"note":"Ask for help"}},
              "checks":[{"checkId":"library-check","sourceCheckId":"library-check","parameters":{}}]
            }]}
            """);

        var imported = service.copyBenefitForOwner("library-benefit", "analyst-1").orElseThrow();

        assertNotEquals("library-benefit", imported.getId());
        assertEquals("analyst-1", imported.getOwnerId());
        assertNotEquals("library-check", imported.getChecks().getFirst().getCheckId());
        assertEquals("library-check", imported.getChecks().getFirst().getSourceCheckId());
        var template = service.getBenefitById("library-benefit").orElseThrow();
        assertEquals(template.getNextSteps(), imported.getNextSteps());
        assertEquals("https://example.org/apply", imported.getNextSteps().eligible().applyUrl());
        imported.setNextSteps(new NextSteps(null, new NextSteps.Ineligible("Edited note")));
        assertEquals("Ask for help", template.getNextSteps().ineligible().note());
    }

    @Test
    void loadMetadata_dropsNextStepsTheBuilderWouldReject() throws Exception {
        LibraryApiService service = new LibraryApiService();
        service.loadMetadata("""
            {"checks":[],"benefits":[{
              "id":"library-benefit","name":"Benefit","checks":[],
              "nextSteps":{
                "eligible":{"applyUrl":"mailto:help@example.org","helpUrl":"https://example.org/help",
                  "helpPhone":"215-555-0100","body":"%s"},
                "ineligible":{"note":"Ask for help"}}
            }]}
            """.formatted("b".repeat(NextSteps.MAX_BODY_LENGTH + 1)));

        var nextSteps = service.getBenefitById("library-benefit").orElseThrow().getNextSteps();

        assertEquals(new NextSteps(
            new NextSteps.Eligible(null, "https://example.org/help", "215-555-0100", null),
            new NextSteps.Ineligible("Ask for help")), nextSteps);
    }

    @Test
    void buildEffectiveParameters_defaultsMissingAsOfDateWhenDeclared() {
        LibraryApiService service = new LibraryApiService();
        CheckConfig checkConfig = checkConfigWithParameters(Map.of("minAge", 65), asOfDateParameter());

        LibraryApiService.EffectiveParameters result = service.buildEffectiveParameters(checkConfig);

        assertEquals(LocalDate.now().toString(), result.parameters().get("asOfDate"));
        assertEquals(List.of("asOfDate"), result.defaultedParameters());
        assertFalse(checkConfig.getParameters().containsKey("asOfDate"));
    }

    @Test
    void buildEffectiveParameters_defaultsBlankAsOfDateWhenDeclared() {
        LibraryApiService service = new LibraryApiService();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("asOfDate", " ");
        parameters.put("minAge", 65);
        CheckConfig checkConfig = checkConfigWithParameters(parameters, asOfDateParameter());

        LibraryApiService.EffectiveParameters result = service.buildEffectiveParameters(checkConfig);

        assertEquals(LocalDate.now().toString(), result.parameters().get("asOfDate"));
        assertEquals(List.of("asOfDate"), result.defaultedParameters());
        assertEquals(" ", checkConfig.getParameters().get("asOfDate"));
    }

    @Test
    void buildEffectiveParameters_keepsExplicitAsOfDate() {
        LibraryApiService service = new LibraryApiService();
        CheckConfig checkConfig = checkConfigWithParameters(
            Map.of("asOfDate", "2025-12-31", "minAge", 65),
            asOfDateParameter()
        );

        LibraryApiService.EffectiveParameters result = service.buildEffectiveParameters(checkConfig);

        assertEquals("2025-12-31", result.parameters().get("asOfDate"));
        assertTrue(result.defaultedParameters().isEmpty());
    }

    @Test
    void buildEffectiveParameters_doesNotDefaultWhenAsOfDateIsNotDeclared() {
        LibraryApiService service = new LibraryApiService();
        CheckConfig checkConfig = checkConfigWithParameters(Map.of("minAge", 65), minAgeParameter());

        LibraryApiService.EffectiveParameters result = service.buildEffectiveParameters(checkConfig);

        assertFalse(result.parameters().containsKey("asOfDate"));
        assertTrue(result.defaultedParameters().isEmpty());
    }

    @Test
    void buildEffectiveParameters_returnsMutableCopy() {
        LibraryApiService service = new LibraryApiService();
        Map<String, Object> parameters = Map.of("minAge", 65);
        CheckConfig checkConfig = checkConfigWithParameters(parameters, minAgeParameter());

        LibraryApiService.EffectiveParameters result = service.buildEffectiveParameters(checkConfig);

        assertNotSame(parameters, result.parameters());
    }

    private CheckConfig checkConfigWithParameters(
        Map<String, Object> parameters,
        ParameterDefinition parameterDefinition
    ) {
        CheckConfig checkConfig = new CheckConfig();
        checkConfig.setParameters(parameters);
        checkConfig.setParameterDefinitions(List.of(parameterDefinition));
        return checkConfig;
    }

    private ParameterDefinition asOfDateParameter() {
        ParameterDefinition parameterDefinition = new ParameterDefinition();
        parameterDefinition.setKey("asOfDate");
        parameterDefinition.setType("date");
        return parameterDefinition;
    }

    private ParameterDefinition minAgeParameter() {
        ParameterDefinition parameterDefinition = new ParameterDefinition();
        parameterDefinition.setKey("minAge");
        parameterDefinition.setType("number");
        return parameterDefinition;
    }
}
