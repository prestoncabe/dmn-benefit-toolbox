package org.codeforphilly.bdt.library.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class OpenAPISchemaPatternTest {

    @Inject
    ModelRegistry modelRegistry;

    private JsonPath openApiSpec;

    @BeforeEach
    public void loadOpenAPISpec() {
        openApiSpec = given()
            .queryParam("format", "JSON")
        .when()
            .get("/q/openapi")
        .then()
            .statusCode(200)
        .extract()
            .jsonPath();
    }

    @Test
    public void testAllCheckEndpointsHaveCheckResultInSchema() {
        // Discover all check models dynamically
        Map<String, ModelInfo> allModels = modelRegistry.getAllModels();

        List<ModelInfo> checkModels = allModels.values().stream()
            .filter(model -> model.getPath().startsWith("checks/"))
            .filter(model -> model.getDecisionServices().contains(model.getModelName() + "Service"))
            .collect(Collectors.toList());

        assertTrue(checkModels.size() > 0, "Should have at least one check model");

        for (ModelInfo model : checkModels) {
            String path = "/api/v1/" + model.getPath();
            String schemaPath = "paths.'" + path + "'.post.responses.'200'.content.'application/json'.schema.properties";

            // Verify response schema has required fields
            Map<String, Object> properties = openApiSpec.getMap(schemaPath);
            assertNotNull(properties, "Schema properties should exist for " + path);
            assertTrue(properties.containsKey("checkResult"),
                path + " should have 'checkResult' in response schema, not 'result'");
            assertTrue(properties.containsKey("situation"),
                path + " should have 'situation' in response schema");
            // 'parameters' is only present for checks that declare a parameters input in their DMN model
        }
    }

    @Test
    public void testAllBenefitEndpointsHaveExpectedFields() {
        Map<String, ModelInfo> allModels = modelRegistry.getAllModels();

        List<ModelInfo> benefitModels = allModels.values().stream()
            .filter(model -> model.getPath().startsWith("benefits/"))
            .filter(model -> model.getDecisionServices().contains(model.getModelName() + "Service"))
            .collect(Collectors.toList());

        assertTrue(benefitModels.size() > 0, "Should have at least one benefit model");

        for (ModelInfo model : benefitModels) {
            String path = "/api/v1/" + model.getPath();
            String schemaPath = "paths.'" + path + "'.post.responses.'200'.content.'application/json'.schema.properties";

            Map<String, Object> properties = openApiSpec.getMap(schemaPath);
            assertNotNull(properties, "Schema properties should exist for " + path);

            // Benefits should have checks and isEligible
            assertTrue(properties.containsKey("checks"),
                path + " should have 'checks' in response schema");
            assertTrue(properties.containsKey("isEligible"),
                path + " should have 'isEligible' in response schema");

            // Benefits should also echo back input
            assertTrue(properties.containsKey("situation"),
                path + " should have 'situation' in response schema");
        }
    }

    @Test
    public void testBenefitCompositionIsPublishedFromDmn() {
        String homesteadPath = "paths.'/api/v1/benefits/pa/phl/homestead-exemption'.post.'x-bdt-benefit'";
        Map<String, Object> homestead = openApiSpec.getMap(homesteadPath);
        assertNotNull(homestead);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) homestead.get("checks");
        assertEquals(4, checks.size());
        assertEquals("NotAlreadyOnHomestead", checks.getFirst().get("alias"));
        assertEquals("PersonNotEnrolledInBenefitService", checks.getFirst().get("operationId"));
        assertEquals(Map.of("benefit", "PhlHomesteadExemption"), checks.getFirst().get("parameters"));
        assertEquals(Map.of("personId", "primaryPersonId"), checks.getFirst().get("parameterBindings"));

        String sctfPath = "paths.'/api/v1/benefits/pa/phl/senior-citizen-tax-freeze'.post.'x-bdt-benefit'.checks";
        List<Map<String, Object>> sctfChecks = openApiSpec.getList(sctfPath);
        assertTrue(sctfChecks.stream().anyMatch(check ->
            "SctfAgeRequirementService".equals(check.get("operationId"))
                && "MeetsAgeRequirement".equals(check.get("alias"))));
    }

    @Test
    public void testAllSixPropertyTaxBenefitsPublishResolvableComposition() {
        Map<String, String> benefits = Map.of(
            "homestead-exemption", "PhlHomesteadExemptionService",
            "owner-occupied-payment-agreement", "PhlOwnerOccupiedPaymentAgreementService",
            "senior-citizen-tax-freeze", "PhlSeniorCitizenTaxFreezeService",
            "low-income-tax-freeze", "PhlLowIncomeTaxFreezeService",
            "loop", "PhlLoopService",
            "retip", "PhlRetipService");

        Map<String, Map<String, Object>> paths = openApiSpec.getMap("paths");
        Map<String, String> checkPathsByService = modelRegistry.getAllModels().values().stream()
            .filter(model -> model.getPath().startsWith("checks/"))
            .filter(model -> model.getDecisionServices().contains(model.getModelName() + "Service"))
            .collect(Collectors.toMap(model -> model.getModelName() + "Service", ModelInfo::getPath));

        benefits.forEach((slug, service) -> {
            String operation = "paths.'/api/v1/benefits/pa/phl/" + slug + "'.post";
            assertEquals(service, openApiSpec.getString(operation + ".operationId"));
            List<Map<String, Object>> checks = openApiSpec.getList(operation + ".'x-bdt-benefit'.checks");
            assertNotNull(checks, slug + " must expose its composition to the builder");
            assertFalse(checks.isEmpty(), slug + " must have checks");
            for (Map<String, Object> check : checks) {
                String checkService = (String) check.get("operationId");
                String checkPath = checkPathsByService.get(checkService);
                assertNotNull(checkPath, slug + " references an undiscovered check: " + checkService);
                assertTrue(paths.containsKey("/api/v1/" + checkPath), checkService + " must be callable");
                Map<String, String> bindings = (Map<String, String>) check.get("parameterBindings");
                assertTrue(bindings.values().stream().allMatch("primaryPersonId"::equals),
                    slug + " must use parameter bindings the builder can resolve");
            }
        });

        List<Map<String, Object>> loopChecks = openApiSpec.getList(
            "paths.'/api/v1/benefits/pa/phl/loop'.post.'x-bdt-benefit'.checks");
        assertEquals(8, loopChecks.size());
        Map<String, Object> years = loopChecks.stream()
            .filter(check -> "MinYearsOwnerOccupantService".equals(check.get("operationId")))
            .findFirst().orElseThrow();
        assertEquals(Map.of("minYears", 10), years.get("parameters"));
        Map<String, Object> assessment = loopChecks.stream()
            .filter(check -> "PropertyAssessmentIncreaseService".equals(check.get("operationId")))
            .findFirst().orElseThrow();
        assertEquals(Map.of("oneYearPercent", 50, "fiveYearPercent", 75), assessment.get("parameters"));
    }

    @Test
    public void testAllEightUtilityBenefitsPublishResolvableComposition() {
        Map<String, Integer> benefits = Map.of(
            "pa/liheap", 6,
            "pa/liheap-crisis", 6,
            "pa/peco-cap", 2,
            "pa/peco-meaf", 2,
            "pa/phl/pgw-crp", 3,
            "pa/phl/tiered-assistance-program", 3,
            "pa/phl/senior-citizen-water-discount", 4,
            "pa/weatherization-assistance-program", 3);

        Map<String, Map<String, Object>> paths = openApiSpec.getMap("paths");
        Map<String, String> checkPaths = modelRegistry.getAllModels().values().stream()
            .filter(model -> model.getPath().startsWith("checks/"))
            .collect(Collectors.toMap(model -> model.getModelName() + "Service", ModelInfo::getPath));

        benefits.forEach((slug, count) -> {
            String operation = "paths.'/api/v1/benefits/" + slug + "'.post";
            List<Map<String, Object>> checks = openApiSpec.getList(operation + ".'x-bdt-benefit'.checks");
            assertNotNull(checks, slug + " must publish its composition");
            assertEquals(count, checks.size(), slug + " must publish every named check");
            for (Map<String, Object> check : checks) {
                String service = (String) check.get("operationId");
                assertTrue(paths.containsKey("/api/v1/" + checkPaths.get(service)),
                    slug + " references an uncallable check: " + service);
                Map<?, ?> bindings = (Map<?, ?>) check.get("parameterBindings");
                // The builder supplies primaryPersonId and leaves the benefit's asOfDate to each check's default.
                assertTrue(bindings.entrySet().stream().allMatch(binding -> "primaryPersonId".equals(binding.getValue())
                        || ("asOfDate".equals(binding.getKey()) && "asOfDate".equals(binding.getValue()))),
                    slug + " must use bindings the builder supports");
            }
        });
    }

    @Test
    public void testAllCheckEndpointsHaveCheckResultInExamples() {
        Map<String, ModelInfo> allModels = modelRegistry.getAllModels();

        List<ModelInfo> checkModels = allModels.values().stream()
            .filter(model -> model.getPath().startsWith("checks/"))
            .filter(model -> model.getDecisionServices().contains(model.getModelName() + "Service"))
            .collect(Collectors.toList());

        for (ModelInfo model : checkModels) {
            String path = "/api/v1/" + model.getPath();
            String examplePath = "paths.'" + path + "'.post.responses.'200'.content.'application/json'.examples.'Example response'.value";

            Map<String, Object> exampleValue = openApiSpec.getMap(examplePath);
            assertNotNull(exampleValue, "Example should exist for " + path);
            assertTrue(exampleValue.containsKey("checkResult"),
                path + " example should have 'checkResult', not 'result'");
            assertTrue(exampleValue.containsKey("situation"),
                path + " example should have 'situation'");
            // 'parameters' is only present for checks that declare a parameters input in their DMN model
        }
    }

    @Test
    public void testExampleKeysMatchSchemaKeys() {
        // For ALL endpoints, verify example keys match schema properties
        Map<String, ModelInfo> allModels = modelRegistry.getAllModels();

        List<ModelInfo> exposedModels = allModels.values().stream()
            .filter(model -> model.getPath().startsWith("checks/") || model.getPath().startsWith("benefits/"))
            .filter(model -> model.getDecisionServices().contains(model.getModelName() + "Service"))
            .collect(Collectors.toList());

        assertTrue(exposedModels.size() > 0, "Should have at least one exposed model");

        for (ModelInfo model : exposedModels) {
            String path = "/api/v1/" + model.getPath();
            String schemaPath = "paths.'" + path + "'.post.responses.'200'.content.'application/json'.schema.properties";
            String examplePath = "paths.'" + path + "'.post.responses.'200'.content.'application/json'.examples.'Example response'.value";

            Map<String, Object> schemaProperties = openApiSpec.getMap(schemaPath);
            Map<String, Object> exampleValue = openApiSpec.getMap(examplePath);

            if (schemaProperties != null && exampleValue != null) {
                // Every key in schema should exist in example
                for (String schemaKey : schemaProperties.keySet()) {
                    assertTrue(exampleValue.containsKey(schemaKey),
                        path + ": example missing schema key '" + schemaKey + "'");
                }

                // Every key in example should exist in schema
                for (String exampleKey : exampleValue.keySet()) {
                    assertTrue(schemaProperties.containsKey(exampleKey),
                        path + ": schema missing example key '" + exampleKey + "'");
                }
            }
        }
    }
}
