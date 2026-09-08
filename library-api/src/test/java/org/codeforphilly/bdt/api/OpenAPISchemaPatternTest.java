package org.codeforphilly.bdt.api;

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
