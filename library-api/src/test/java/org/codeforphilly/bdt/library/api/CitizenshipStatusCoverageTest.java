package org.codeforphilly.bdt.library.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HouseholdHasLiheapCitizenshipStatus lists its qualifying statuses in FEEL, separately from the
 * BDT.tCitizenshipStatus enumeration. A status added to the enumeration but not to the check would
 * be accepted by the schema yet silently screen as unknown, so every value must get its intended result.
 */
@QuarkusTest
public class CitizenshipStatusCoverageTest {

    private static final String BDT_NAMESPACE = "https://kie.apache.org/dmn/_1B91A885-130A-4E0B-A762-E12AA6DD5C79";
    private static final String CHECK_PATH = "/api/v1/checks/citizenship/household-has-liheap-citizenship-status";

    @Inject
    DMNSchemaResolver schemaResolver;

    @Test
    public void testEveryCitizenshipStatusHasItsLiheapResult() {
        List<String> statuses = bdtCitizenshipStatuses();
        assertTrue(statuses.contains("us-citizen"), "BDT.tCitizenshipStatus enumeration should be found");

        for (String status : statuses) {
            Boolean expected = switch (status) {
                case "none-of-these" -> false;
                case "other-or-unsure" -> null;
                default -> true;
            };

            Boolean checkResult = given()
                .contentType(ContentType.JSON)
                .body(Map.of("situation", Map.of("people", List.of(
                    Map.of("citizenshipStatus", status, "excludedFromLiheapHousehold", false)))))
            .when()
                .post(CHECK_PATH)
            .then()
                .statusCode(200)
                .extract().path("checkResult");

            assertEquals(expected, checkResult, "Unexpected LIHEAP result for citizenshipStatus '" + status + "'");
        }
    }

    private List<String> bdtCitizenshipStatuses() {
        for (String key : schemaResolver.getAllSchemaKeys()) {
            JsonNode schema = schemaResolver.getSchema(key);
            if (schema != null && schema.has("x-dmn-type") && schema.has("enum")) {
                String dmnType = schema.get("x-dmn-type").asText();
                if (dmnType.contains(BDT_NAMESPACE) && dmnType.contains("tCitizenshipStatus")) {
                    List<String> values = new ArrayList<>();
                    schema.get("enum").forEach(value -> values.add(value.asText()));
                    return values;
                }
            }
        }
        return List.of();
    }
}
