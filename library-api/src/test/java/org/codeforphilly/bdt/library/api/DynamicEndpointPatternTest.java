package org.codeforphilly.bdt.library.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class DynamicEndpointPatternTest {

    @Inject
    ModelRegistry modelRegistry;

    @Inject
    DMNSchemaResolver schemaResolver;

    @Test
    public void testAllCheckEndpointsReturnCheckResult() {
        Map<String, ModelInfo> allModels = modelRegistry.getAllModels();

        List<ModelInfo> checkModels = allModels.values().stream()
            .filter(model -> model.getPath().startsWith("checks/"))
            .filter(model -> model.getDecisionServices().contains(model.getModelName() + "Service"))
            .collect(Collectors.toList());

        assertTrue(checkModels.size() > 0, "Should have at least one check model");

        for (ModelInfo model : checkModels) {
            String serviceName = model.getModelName() + "Service";
            String inputRef = schemaResolver.findInputSchemaRef(model.getModelName(), serviceName);
            assertNotNull(inputRef, "Should find input schema for " + serviceName);

            // Generate example request
            Map<String, Object> exampleRequest = schemaResolver.generateExample(inputRef);

            String path = "/api/v1/" + model.getPath();

            given()
                .contentType(ContentType.JSON)
                .body(exampleRequest)
            .when()
                .post(path)
            .then()
                .statusCode(200)
                .body("checkResult", notNullValue())
                .body("situation", notNullValue());
            // 'parameters' is only echoed back for checks that declare a parameters input
        }
    }

    @Test
    public void testAllCheckEndpointsReturnNullWithEmptyInputs() {
        Map<String, ModelInfo> allModels = modelRegistry.getAllModels();

        List<ModelInfo> checkModels = allModels.values().stream()
            .filter(model -> model.getPath().startsWith("checks/"))
            .filter(model -> model.getDecisionServices().contains(model.getModelName() + "Service"))
            .collect(Collectors.toList());

        assertTrue(checkModels.size() > 0, "Should have at least one check model");

        for (ModelInfo model : checkModels) {
            String path = "/api/v1/" + model.getPath();

            String checkResult = given()
                .contentType(ContentType.JSON)
                .body(Map.of("situation", Map.of()))
            .when()
                .post(path)
            .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("checkResult");

            assertNull(checkResult, path + " should return null checkResult with empty situation");
        }
    }

    @Test
    public void testAllBenefitEndpointsReturnExpectedStructure() {
        Map<String, ModelInfo> allModels = modelRegistry.getAllModels();

        List<ModelInfo> benefitModels = allModels.values().stream()
            .filter(model -> model.getPath().startsWith("benefits/"))
            .filter(model -> model.getDecisionServices().contains(model.getModelName() + "Service"))
            .collect(Collectors.toList());

        assertTrue(benefitModels.size() > 0, "Should have at least one benefit model");

        for (ModelInfo model : benefitModels) {
            String serviceName = model.getModelName() + "Service";
            String inputRef = schemaResolver.findInputSchemaRef(model.getModelName(), serviceName);

            if (inputRef != null) {
                Map<String, Object> exampleRequest = schemaResolver.generateExample(inputRef);
                String path = "/api/v1/" + model.getPath();

                given()
                    .contentType(ContentType.JSON)
                    .body(exampleRequest)
                .when()
                    .post(path)
                .then()
                    .statusCode(200)
                    .body("checks", notNullValue())
                    .body("isEligible", notNullValue())
                    .body("situation", notNullValue());
            }
        }
    }

    @Test
    public void testActualResponsesMatchOpenAPIExamples() {
        // For each endpoint, verify actual response structure matches OpenAPI example structure
        Map<String, ModelInfo> allModels = modelRegistry.getAllModels();

        List<ModelInfo> exposedModels = allModels.values().stream()
            .filter(model -> model.getPath().startsWith("checks/") || model.getPath().startsWith("benefits/"))
            .filter(model -> model.getDecisionServices().contains(model.getModelName() + "Service"))
            .collect(Collectors.toList());

        JsonPath openApiSpec = given()
            .queryParam("format", "JSON")
        .when()
            .get("/q/openapi")
        .then()
            .extract()
            .jsonPath();

        for (ModelInfo model : exposedModels) {
            String serviceName = model.getModelName() + "Service";
            String inputRef = schemaResolver.findInputSchemaRef(model.getModelName(), serviceName);

            if (inputRef != null) {
                Map<String, Object> exampleRequest = schemaResolver.generateExample(inputRef);
                String path = "/api/v1/" + model.getPath();

                // Get actual response
                Response response = given()
                    .contentType(ContentType.JSON)
                    .body(exampleRequest)
                .when()
                    .post(path)
                .then()
                    .extract()
                    .response();

                assertEquals(200, response.statusCode(), path + " should return 200");

                Map<String, Object> actualResponse = response.as(new TypeRef<Map<String, Object>>() {});

                // Get OpenAPI example
                String examplePath = "paths.'" + path + "'.post.responses.'200'.content.'application/json'.examples.'Example response'.value";
                Map<String, Object> openApiExample = openApiSpec.getMap(examplePath);

                if (openApiExample != null) {
                    // Verify same keys
                    assertEquals(openApiExample.keySet(), actualResponse.keySet(),
                        path + ": actual response keys should match OpenAPI example keys");
                }
            }
        }
    }
}
