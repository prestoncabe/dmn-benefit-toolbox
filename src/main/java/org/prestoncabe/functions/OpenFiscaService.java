package org.prestoncabe.functions;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

public class OpenFiscaService {
    private static final String API_BASE_URL = "https://api.policyengine.org/us";
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Logger logger = Logger.getLogger(OpenFiscaService.class.getName());

    public static Boolean calculateOpenFiscaVariable(String variableName, Map<String, Object> inputs) {
        try {
            logger.info("Starting PolicyEngine API call process");
            logger.info("Input parameters: " + inputs.toString());
            
            // Construct JSON
            logger.info("Beginning JSON construction");
            ObjectNode root = mapper.createObjectNode();
            ObjectNode data = mapper.createObjectNode();
            
            // People
            logger.info("Constructing people object");
            ObjectNode people = mapper.createObjectNode();
            ObjectNode you = mapper.createObjectNode();
            ObjectNode age = mapper.createObjectNode();
            ObjectNode employment = mapper.createObjectNode();
            
            age.put("2024", (Integer)inputs.get("age"));
            employment.put("2024", (Integer)inputs.get("employment_income"));
            you.set("age", age);
            you.set("employment_income", employment);
            people.set("you", you);
            logger.info("People object constructed successfully");
            
            // Create member array
            logger.info("Creating member array");
            ArrayNode members = mapper.createArrayNode();
            members.add("you");
            
            // Create all units with members
            logger.info("Constructing family units");
            ObjectNode familyUnit = mapper.createObjectNode();
            familyUnit.set("members", members);
            ObjectNode families = mapper.createObjectNode();
            families.set("your family", familyUnit);
            
            logger.info("Constructing marital units");
            ObjectNode maritalUnit = mapper.createObjectNode();
            maritalUnit.set("members", members);
            ObjectNode maritalUnits = mapper.createObjectNode();
            maritalUnits.set("your marital unit", maritalUnit);
            
            logger.info("Constructing tax units");
            ObjectNode taxUnit = mapper.createObjectNode();
            taxUnit.set("members", members);
            ObjectNode taxUnits = mapper.createObjectNode();
            taxUnits.set("your tax unit", taxUnit);
            
            logger.info("Constructing SPM units");
            ObjectNode spmUnit = mapper.createObjectNode();
            spmUnit.set("members", members);
            ObjectNode spmUnits = mapper.createObjectNode();
            spmUnits.set("your household", spmUnit);
            
            // Household with state
            logger.info("Constructing household with state information");
            ObjectNode household = mapper.createObjectNode();
            household.set("members", members);
            ObjectNode stateName = mapper.createObjectNode();
            stateName.put("2024", "NY");
            household.set("state_name", stateName);
            ObjectNode households = mapper.createObjectNode();
            households.set("your household", household);
            
            // Combine everything
            logger.info("Combining all components");
            data.set("people", people);
            data.set("families", families);
            data.set("marital_units", maritalUnits);
            data.set("tax_units", taxUnits);
            data.set("spm_units", spmUnits);
            data.set("households", households);
            root.set("data", data);

            // Make API calls
            String householdJson = mapper.writeValueAsString(root);
            logger.info("Final JSON payload constructed: " + householdJson);
            
            logger.info("Sending POST request to PolicyEngine API");
            HttpRequest postRequest = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL + "/household"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(householdJson))
                .build();

            HttpResponse<String> postResponse = client.send(postRequest, 
                HttpResponse.BodyHandlers.ofString());
            
            logger.info("POST response status code: " + postResponse.statusCode());
            logger.info("POST response body: " + postResponse.body());

            // Parse the POST response more carefully
            JsonNode postResponseJson = mapper.readTree(postResponse.body());
            if (!postResponseJson.has("status") || !"ok".equals(postResponseJson.get("status").asText())) {
                logger.severe("Invalid POST response format - missing or invalid status");
                return false;
            }
            
            String householdId;
            try {
                // Fix #1: Correct path to household_id
                householdId = postResponseJson.get("result").get("household_id").asText();
                logger.info("Successfully extracted household ID: " + householdId);
            } catch (Exception e) {
                logger.severe("Failed to extract household ID from response: " + e.getMessage());
                return false;
            }

            // Fix: Change the URL structure to match the correct API endpoint
            logger.info("Sending GET request for calculation results");
            String getUrl = String.format("https://api.policyengine.org/us/%s/policy/2", householdId);
            logger.info("GET URL: " + getUrl);  // Add this for debugging

            HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create(getUrl))
                .header("Accept", "application/json")  // Add this header
                .GET()
                .build();

            logger.info("Waiting for calculation results (this may take up to a minute)...");
            HttpResponse<String> getResponse = client.send(getRequest,
                HttpResponse.BodyHandlers.ofString());
            
            // Add retry logic for initial 404s (API might need time to process)
            int maxRetries = 3;
            int retryCount = 0;
            while (getResponse.statusCode() == 404 && retryCount < maxRetries) {
                logger.info("Got 404, waiting 5 seconds and retrying... (attempt " + (retryCount + 1) + " of " + maxRetries + ")");
                Thread.sleep(5000);  // Wait 5 seconds
                getResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString());
                retryCount++;
            }

            // Status code check
            if (getResponse.statusCode() != 200) {
                logger.severe("GET request failed with status code: " + getResponse.statusCode());
                logger.severe("Response body: " + getResponse.body());
                return false;
            }

            logger.info("GET response status code: " + getResponse.statusCode());
            logger.info("GET response body: " + getResponse.body());
  

            JsonNode responseJson = mapper.readTree(getResponse.body());
            if (!responseJson.has("result")) {
                logger.severe("Invalid GET response format - missing result field");
                return false;
            }

            Boolean result = responseJson.get("result").get("people").get("you")
                .get(variableName).get("2024").asBoolean();
            
            logger.info("Final result for " + variableName + ": " + result);
            return result;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in PolicyEngine API call", e);
            logger.severe("Stack trace: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}