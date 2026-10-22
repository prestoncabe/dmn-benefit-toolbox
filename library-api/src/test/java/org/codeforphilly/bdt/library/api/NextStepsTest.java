package org.codeforphilly.bdt.library.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.kie.dmn.api.core.DMNModel;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class NextStepsTest {

    @Inject
    ModelRegistry registry;

    @Test
    @SuppressWarnings("unchecked")
    void benefitContentMatchesMetadataForUndecidedAndIneligibleResults() {
        JsonPath openApi = given().queryParam("format", "JSON")
            .get("/q/openapi").then().statusCode(200).extract().jsonPath();
        List<ModelInfo> benefits = registry.getAllModels().values().stream()
            .filter(this::isBenefit)
            .toList();
        assertFalse(benefits.isEmpty());

        for (ModelInfo benefit : benefits) {
            Map<String, Object> content = benefit.getNextSteps();
            assertContent(content);
            String path = "/api/v1/" + benefit.getPath();
            assertEquals(content, openApi.getMap("paths.'" + path + "'.post.'x-bdt-benefit'.nextSteps"));

            JsonPath undecided = evaluate(path, Map.of());
            assertNull(undecided.get("isEligible"), path);
            assertEquals(content, undecided.getMap("nextSteps"), path);

            // Every Philadelphia property-tax benefit requires owner occupancy.
            if (benefit.getPath().startsWith("benefits/pa/phl/")) {
                JsonPath ineligible = evaluate(path, Map.of("simpleChecks", Map.of("ownerOccupant", false)));
                assertEquals(Boolean.FALSE, ineligible.get("isEligible"), path);
                assertEquals(content, ineligible.getMap("nextSteps"), path);
            }

            Map<String, Object> schema = properties(openApi, openApi.getMap("paths.'" + path
                + "'.post.responses.'200'.content.'application/json'.schema.properties.nextSteps"));
            assertEquals(Set.of("eligible", "ineligible"), schema.keySet(), path);
            assertEquals(Set.of("applyUrl", "helpUrl", "helpPhone", "body"),
                properties(openApi, (Map<String, Object>) schema.get("eligible")).keySet(), path);
            assertEquals(Set.of("note"),
                properties(openApi, (Map<String, Object>) schema.get("ineligible")).keySet(), path);
        }
    }

    @Test
    void nextStepsHasNoInformationRequirementsAndIsAnOutputOfEachBenefitService() {
        for (DMNModel model : registry.getDmnModels()) {
            ModelInfo info = registry.getModelInfo(model.getName());
            if (!isBenefit(info)) {
                assertTrue(info.getNextSteps().isEmpty(), model.getName());
                continue;
            }
            var decision = model.getDecisionByName("nextSteps");
            assertNotNull(decision, model.getName());
            assertTrue(decision.getDecision().getInformationRequirement().isEmpty(), model.getName());
            assertTrue(model.getRequiredInputsForDecisionName("nextSteps").isEmpty(), model.getName());
            var service = model.getDefinitions().getDecisionService().stream()
                .filter(candidate -> candidate.getName().equals(model.getName() + "Service"))
                .findFirst().orElseThrow();
            assertTrue(service.getOutputDecision().stream()
                .anyMatch(output -> output.getHref().equals("#" + decision.getId())), model.getName());
        }
    }

    private boolean isBenefit(ModelInfo model) {
        return model.getPath().startsWith("benefits/") && !model.getBenefitChecks().isEmpty();
    }

    private JsonPath evaluate(String path, Map<String, Object> situation) {
        return given().contentType(ContentType.JSON).body(Map.of("situation", situation))
            .post(path).then().statusCode(200).extract().jsonPath();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(JsonPath openApi, Map<String, Object> schema) {
        assertNotNull(schema);
        while (schema.containsKey("$ref")) {
            String ref = (String) schema.get("$ref");
            assertTrue(ref.startsWith("#/components/schemas/"));
            schema = openApi.getMap("components.schemas.'" + ref.substring("#/components/schemas/".length()) + "'");
            assertNotNull(schema, ref + " must resolve");
        }
        return (Map<String, Object>) schema.get("properties");
    }

    /** Checks the tNextSteps contract: known fields only, each a string or null. */
    private void assertContent(Map<String, Object> content) {
        assertEquals(Set.of("eligible", "ineligible"), content.keySet());
        Map<?, ?> eligible = assertInstanceOf(Map.class, content.get("eligible"));
        assertEquals(Set.of("applyUrl", "helpUrl", "helpPhone", "body"), eligible.keySet());
        for (String field : List.of("applyUrl", "helpUrl")) {
            String url = optionalString(eligible.get(field));
            if (url != null) {
                assertEquals("https", URI.create(url).getScheme(), field);
            }
        }
        optionalString(eligible.get("helpPhone"));
        assertMarkdown(optionalString(eligible.get("body")));
        Map<?, ?> ineligible = assertInstanceOf(Map.class, content.get("ineligible"));
        assertEquals(Set.of("note"), ineligible.keySet());
        assertMarkdown(optionalString(ineligible.get("note")));
    }

    private String optionalString(Object value) {
        return value == null ? null : assertInstanceOf(String.class, value);
    }

    private void assertMarkdown(String markdown) {
        if (markdown != null) {
            assertFalse(markdown.isBlank());
            assertFalse(markdown.contains("\\n"), "FEEL must decode Markdown newlines");
        }
    }
}
