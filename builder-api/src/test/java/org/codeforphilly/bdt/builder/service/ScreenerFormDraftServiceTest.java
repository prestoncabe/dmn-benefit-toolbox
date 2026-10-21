package org.codeforphilly.bdt.builder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.CheckConfig;
import org.codeforphilly.bdt.builder.model.domain.FormPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ScreenerFormDraftServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ScreenerFormDraftService service;
    private HttpServer server;
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> requestKey = new AtomicReference<>();
    private final List<FormPath> paths = List.of(new FormPath("people.client.dateOfBirth", "date"),
        new FormPath("simpleChecks.resident", "boolean"), new FormPath("custom.income", "number"),
        new FormPath("people.client.enrollments", "array:string"));
    // Question IDs follow sorted paths: q0 custom.income, q1 people.client.dateOfBirth,
    // q2 people.client.enrollments, q3 simpleChecks.resident.
    private static final String DRAFT = """
        {"questions":[
          {"key":"simpleChecks.resident","label":"Do you live here?","description":"","values":[]},
          {"key":"people.client.enrollments","label":"Which benefits do you receive?","description":"",
           "values":[{"label":"Housing assistance","value":"Housing"},{"label":"Food assistance","value":"Food"}]},
          {"key":"people.client.dateOfBirth","label":"Your date of birth","description":"","values":[]},
          {"key":"custom.income","label":"Your yearly income","description":"Before taxes","values":[]}
        ]}
        """;

    @BeforeEach
    void setUp() {
        service = new ScreenerFormDraftService();
        service.objectMapper = mapper;
        service.inputSchemaService = new InputSchemaService();
        service.enabled = true;
        service.geminiClient = new GeminiClient();
        service.geminiClient.objectMapper = mapper;
        service.geminiClient.apiKey = Optional.empty();
        service.geminiClient.model = "test-model";
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void buildsTypedFieldsWithSharedKeysAndNoDefaultAnswers() throws Exception {
        var form = draftForm(DRAFT);
        assertEquals("default", form.path("type").asText());
        assertEquals(18, form.path("schemaVersion").asInt());
        var fields = form.path("components");
        assertEquals(4, fields.size());
        assertEquals("yes_no", fields.get(0).path("type").asText());
        assertEquals("checklist_none", fields.get(1).path("type").asText());
        assertEquals("Housing", fields.get(1).path("values").get(0).path("value").asText());
        assertEquals("datetime", fields.get(2).path("type").asText());
        assertEquals("date", fields.get(2).path("subtype").asText());
        assertEquals("number", fields.get(3).path("type").asText());
        assertEquals("Field_draft_3", fields.get(3).path("id").asText());
        assertEquals("Row_draft_3", fields.get(3).path("layout").path("row").asText());
        assertFalse(form.toString().contains("defaultValue"));
        assertFalse(form.toString().contains("Screening complete"));
    }

    @Test
    void leavesOmittedDuplicateInventedAndInvalidQuestionsForRepair() throws Exception {
        Map<String, String> invalidDrafts = Map.of(
            DRAFT.replace("custom.income", "simpleChecks.resident"), "q0",
            DRAFT.replace("custom.income", "custom.invented"), "q0",
            DRAFT.replace("Your yearly income", " "), "q0",
            DRAFT.replace("Do you live here?", "= true"), "q3",
            DRAFT.replace("[{\"label\":\"Housing assistance\",\"value\":\"Housing\"},{\"label\":\"Food assistance\",\"value\":\"Food\"}]", "[]"), "q2");
        invalidDrafts.forEach((draft, missing) -> {
            var fields = service.draftedFields(flatDraft(draft), service.questionPaths(paths), Map.of());
            assertEquals(3, fields.size());
            assertFalse(fields.containsKey(missing));
        });
        assertTrue(service.draftedFields(flatDraft("{\"questions\":[]}"), service.questionPaths(paths), Map.of()).isEmpty());
    }

    @Test
    void buildsFieldsForUntypedInputsFromTheModelsAnswerType() throws Exception {
        var untyped = service.questionPaths(List.of(new FormPath("custom.wantsExtraCash", "any"),
            new FormPath("custom.householdIncome", "any"), new FormPath("custom.county", "any"),
            new FormPath("custom.programs", "array:any")));
        var draft = mapper.readTree("""
            {"questions":[
              {"id":"q0","label":"County","description":"","answerType":"text",
               "values":[{"label":"Philadelphia","value":"Philadelphia"}]},
              {"id":"q1","label":"Household income","description":"","answerType":"number","values":[]},
              {"id":"q2","label":"Programs","description":"","values":[{"label":"SNAP","value":"SNAP"}]},
              {"id":"q3","label":"Want extra cash?","description":"","answerType":"yes_no","values":[]}]}
            """);
        var fields = service.toFormSchema(service.draftedFields(draft, untyped, Map.of()).values()).path("components");
        assertEquals("radio", fields.get(0).path("type").asText());
        assertEquals("number", fields.get(1).path("type").asText());
        assertEquals("checklist_none", fields.get(2).path("type").asText());
        assertEquals("yes_no", fields.get(3).path("type").asText());

        ((ObjectNode) draft.path("questions").get(1)).remove("answerType");
        assertFalse(service.draftedFields(draft, untyped, Map.of()).containsKey("q1"));
    }

    @Test
    void restrictsChoicesToSchemaEnumsAndConfiguredBenefitCodes() throws Exception {
        var configured = enrollmentScreener();
        var questions = service.questionPaths(service.inputSchemaService.extractUniqueInputPaths(configured));
        assertEquals("custom.relationship", questions.get("q0").getPath());
        assertEquals("people.client.enrollments", questions.get("q1").getPath());
        var allowed = service.allowedOptionValues(configured, questions);
        assertEquals(Map.of("q0", List.of("child", "spouse"), "q1", List.of("PhlHomesteadExemption")), allowed);

        String valid = """
            {"questions":[
              {"id":"q0","label":"Relationship","description":"","values":[{"label":"Spouse","value":"spouse"}]},
              {"id":"q1","label":"Enrolled in","description":"","values":[{"label":"Homestead","value":"PhlHomesteadExemption"}]}]}
            """;
        assertEquals(2, service.draftedFields(mapper.readTree(valid), questions, allowed).size());
        for (String invalid : List.of(
            valid.replace("\"value\":\"spouse\"", "\"value\":\"Spouse\""),
            valid.replace("\"value\":\"PhlHomesteadExemption\"", "\"value\":\"Homestead\""),
            valid.replace("[{\"label\":\"Spouse\",\"value\":\"spouse\"}]", "[]")
        )) {
            assertEquals(1, service.draftedFields(mapper.readTree(invalid), questions, allowed).size());
        }
    }

    @Test
    void describesTypesAndAllowedValuesInThePromptInsteadOfPerQuestionSchemas() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        startSequencedGemini(requests, List.of(DRAFT, DRAFT), List.of("STOP", "STOP"));
        var configured = enrollmentScreener();
        service.generate(configured, service.inputSchemaService.extractUniqueInputPaths(configured));
        JsonNode first = requests.getFirst();
        String prompt = first.path("contents").get(0).path("parts").get(0).path("text").asText();
        assertTrue(prompt.contains("\"q1\":{\"path\":\"people.client.enrollments\",\"type\":\"array:string\",\"allowedValues\":[\"PhlHomesteadExemption\"]}"));
        var item = first.path("generationConfig").path("responseJsonSchema")
            .path("properties").path("questions").path("items");
        assertEquals("[\"q0\",\"q1\"]", item.path("properties").path("id").path("enum").toString());
        assertFalse(item.path("properties").path("values").path("items").path("properties").path("value").has("enum"));
        assertTrue(item.path("properties").has("answerType"));
        assertFalse(item.path("required").toString().contains("answerType"));
    }

    @Test
    void generatesUsingConfiguredChecksAndDeduplicatedTransformedSchemas() throws Exception {
        startGemini(200, DRAFT, "STOP");
        var form = service.generate(benefits(), paths).orElseThrow();
        assertEquals(4, form.path("components").size());
        assertEquals("simpleChecks.resident", form.path("components").get(0).path("key").asText());
        assertEquals("test-key", requestKey.get());
        var config = request.get().path("generationConfig");
        assertEquals("application/json", config.path("responseMimeType").asText());
        assertEquals(ScreenerFormDraftService.MAX_OUTPUT_TOKENS, config.path("maxOutputTokens").asInt());
        var questionSchema = config.path("responseJsonSchema").path("properties").path("questions");
        assertEquals("array", questionSchema.path("type").asText());
        assertEquals(4, questionSchema.path("items").path("properties").path("id").path("enum").size());
        assertFalse(questionSchema.has("minItems"));
        assertFalse(config.path("responseJsonSchema").path("properties").has("order"));
        String prompt = request.get().path("contents").get(0).path("parts").get(0).path("text").asText();
        assertTrue(prompt.contains("minimum number of questions"));
        assertTrue(prompt.contains("ALL benefits"));
        assertTrue(prompt.contains("do not over-explain") || prompt.contains("Do not over-explain"));
        assertTrue(prompt.contains("Housing"));
        assertTrue(prompt.contains("Food"));
        assertTrue(prompt.contains("\"incomeLimit\":40000"));
        assertTrue(prompt.contains("people.client.dateOfBirth"));
        assertTrue(prompt.contains("Residency and income"));
        assertFalse(prompt.contains("already drafted"));
    }

    @Test
    void skipsRequestsWithoutConfigurationOrWhenDisabled() throws Exception {
        startGemini(200, DRAFT, "STOP");
        service.geminiClient.apiKey = Optional.empty();
        assertTrue(service.generate(benefits(), paths).isEmpty());
        service.geminiClient.apiKey = Optional.of(" ");
        assertTrue(service.generate(benefits(), paths).isEmpty());
        service.geminiClient.apiKey = Optional.of("test-key");
        service.enabled = false;
        assertTrue(service.generate(benefits(), paths).isEmpty());
        assertNull(request.get());
    }

    @Test
    void rejectsFailedTruncatedAndMalformedProviderResponses() throws Exception {
        startGemini(500, DRAFT, "STOP");
        var failure = assertThrows(ScreenerFormDraftService.DraftGenerationException.class,
            () -> service.generate(benefits(), paths));
        assertEquals("Gemini is unavailable (HTTP 500). Try again later.", failure.getMessage());
        server.stop(0);
        startGemini(200, DRAFT, "MAX_TOKENS");
        assertTrue(service.generate(benefits(), paths).isEmpty());
        server.stop(0);
        startGemini(200, "not json", "STOP");
        assertTrue(service.generate(benefits(), paths).isEmpty());
    }

    @Test
    void doesNotDraftAPartialFormWhenACheckSchemaIsMissing() throws Exception {
        startGemini(200, DRAFT, "STOP");
        var configured = benefits();
        var unconfigured = new CheckConfig();
        unconfigured.setCheckName("Unfinished check");
        configured.get(1).setChecks(List.of(unconfigured));
        var error = assertThrows(ScreenerFormDraftService.UndraftableFormException.class,
            () -> service.generate(configured, paths));
        assertTrue(error.getMessage().contains("\"Unfinished check\" in Food"));
        assertNull(request.get());
    }

    @Test
    void namesUnsupportedInputTypesWithoutCallingGemini() throws Exception {
        startGemini(200, DRAFT, "STOP");
        var unsupported = List.of(new FormPath("custom.amounts", "array:number"), new FormPath("custom.income", "number"));
        var error = assertThrows(ScreenerFormDraftService.UndraftableFormException.class,
            () -> service.generate(benefits(), unsupported));
        assertTrue(error.getMessage().contains("custom.amounts (array:number)"));
        assertFalse(error.getMessage().contains("custom.income"));
        assertNull(request.get());
    }

    @Test
    void repairsOmittedAndInvalidQuestionsInOneFollowUpRequest() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        String first = DRAFT.replace("Your yearly income", " ")
            .replace("{\"key\":\"simpleChecks.resident\",\"label\":\"Do you live here?\",\"description\":\"\",\"values\":[]},", "");
        String repair = """
            {"questions":[
              {"key":"custom.income","label":"Your yearly income","description":"","values":[]},
              {"key":"simpleChecks.resident","label":"Do you live here?","description":"","values":[]}]}
            """;
        startSequencedGemini(requests, List.of(first, repair), List.of("STOP", "STOP"));

        var fields = service.generate(benefits(), paths).orElseThrow().path("components");

        assertEquals(2, requests.size());
        assertEquals(List.of("people.client.enrollments", "people.client.dateOfBirth", "custom.income", "simpleChecks.resident"),
            fields.findValuesAsText("key"));
        var repairRequest = requests.get(1);
        assertEquals("[\"q0\",\"q3\"]", repairRequest.path("generationConfig").path("responseJsonSchema")
            .path("properties").path("questions").path("items").path("properties").path("id").path("enum").toString());
        String prompt = repairRequest.path("contents").get(0).path("parts").get(0).path("text").asText();
        assertTrue(prompt.contains("already drafted"));
        assertTrue(prompt.contains("Which benefits do you receive?"));
        assertTrue(prompt.contains("Residency and income"));
    }

    @Test
    void neverReturnsAPartialFormWhenRepairFails() throws Exception {
        String first = DRAFT.replace("Your yearly income", " ");
        for (var repair : List.of(List.of(first, "STOP"), List.of("{\"questions\":[]}", "STOP"), List.of(DRAFT, "MAX_TOKENS"))) {
            List<JsonNode> requests = new ArrayList<>();
            startSequencedGemini(requests, List.of(first, repair.get(0)), List.of("STOP", repair.get(1)));
            assertTrue(service.generate(benefits(), paths).isEmpty());
            assertEquals(2, requests.size(), "Repair at most once");
            server.stop(0);
        }
    }

    @Test
    void duplicateEntriesKeepTheFirstValidQuestion() throws Exception {
        var draft = (ObjectNode) flatDraft(DRAFT);
        var duplicate = (ObjectNode) draft.path("questions").get(3).deepCopy();
        duplicate.put("label", "Income again");
        ((com.fasterxml.jackson.databind.node.ArrayNode) draft.get("questions")).add(duplicate);
        var fields = service.toFormSchema(service.draftedFields(draft, service.questionPaths(paths), Map.of()).values())
            .path("components");
        assertEquals(4, fields.size());
        assertEquals("Your yearly income", fields.get(3).path("label").asText());
        assertEquals(4, fields.findValuesAsText("key").stream().distinct().count());
    }

    @Test
    void largeDraftsUseOneRequestThatGeminiAccepts() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        startSchemaLimitedGemini(requests);
        List<Benefit> configured = largeScreener();
        List<FormPath> inputs = service.inputSchemaService.extractUniqueInputPaths(configured);
        assertEquals(80, inputs.size());

        JsonNode fields = service.generate(configured, inputs).orElseThrow().path("components");

        assertEquals(1, requests.size());
        assertEquals(80, fields.size());
        assertEquals(inputs.stream().map(FormPath::getPath).collect(java.util.stream.Collectors.toSet()),
            new HashSet<>(fields.findValuesAsText("key")));
        // The fake model returns questions in reverse; the form keeps the model's order.
        assertEquals(inputs.stream().map(FormPath::getPath).sorted().toList().getLast(),
            fields.get(0).path("key").asText());
    }

    @Test
    void providerFailuresExposeOnlySafeDiagnostics() throws Exception {
        for (int status : List.of(400, 403, 404, 429, 503)) {
            startGemini(status, "private provider response with test-key", "STOP");
            var failure = assertThrows(ScreenerFormDraftService.DraftGenerationException.class,
                () -> service.generate(benefits(), paths));
            assertTrue(failure.getMessage().contains("Gemini"));
            assertFalse(failure.getMessage().contains("private provider response"));
            assertFalse(failure.getMessage().contains("test-key"));
            if (status == 429) assertTrue(failure.getMessage().contains("rate or usage limit"));
            assertEquals(status == 429 || status == 503, failure.isRetryable());
            if (status == 400) assertTrue(failure.getMessage().contains("will not help"));
            server.stop(0);
        }
    }

    private JsonNode draftForm(String draft) {
        return service.toFormSchema(service.draftedFields(flatDraft(draft), service.questionPaths(paths), Map.of()).values());
    }

    private List<Benefit> enrollmentScreener() throws Exception {
        var check = new CheckConfig();
        check.setCheckName("person-not-enrolled-in-benefit");
        check.setParameters(Map.of("personId", "client", "benefit", "PhlHomesteadExemption"));
        check.setInputDefinition(mapper.readTree("""
            {"type":"object","properties":{
              "enrollments":{"type":"array","items":{"type":"object","properties":{
                "personId":{"type":"string"},"benefit":{"type":"string"}}}},
              "custom":{"type":"object","properties":{"relationship":{"type":"string","enum":["spouse","child"]}}}
            }}
            """));
        return List.of(new Benefit("homestead", "Homestead", null, "owner", List.of(check)));
    }

    private List<Benefit> largeScreener() {
        var definition = mapper.createObjectNode();
        definition.put("type", "object");
        var properties = definition.putObject("properties");
        for (int i = 0; i < 80; i++) properties.putObject("field" + i).put("type", "number");
        var check = new CheckConfig();
        check.setCheckName("Shared household inputs");
        check.setInputDefinition(definition);
        return List.of(new Benefit("first", "First benefit", null, "owner", List.of(check)),
            new Benefit("second", "Second benefit", null, "owner", List.of(check)));
    }

    // Mimics the Gemini schema limits observed for structured output: large minItems/maxItems or
    // many keyed properties are rejected with HTTP 400 before generation starts.
    private void startSchemaLimitedGemini(List<JsonNode> requests) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test-model:generateContent", exchange -> {
            JsonNode body = mapper.readTree(exchange.getRequestBody());
            requests.add(body);
            JsonNode schema = body.path("generationConfig").path("responseJsonSchema");
            boolean rejected = !schema.findValues("minItems").stream().allMatch(n -> n.asInt() <= 30)
                || !schema.findValues("maxItems").stream().allMatch(n -> n.asInt() <= 30)
                || schema.findValues("properties").stream().anyMatch(p -> p.size() > 12);
            var output = mapper.createObjectNode();
            var questions = output.putArray("questions");
            List<String> ids = new ArrayList<>();
            schema.path("properties").path("questions").path("items").path("properties").path("id").path("enum")
                .forEach(id -> ids.add(id.asText()));
            java.util.Collections.reverse(ids);
            ids.forEach(id -> questions.addObject().put("id", id).put("label", "Synthetic question " + id)
                .put("description", "").putArray("values"));
            String response = mapper.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "finishReason", "STOP",
                "content", Map.of("parts", List.of(Map.of("text", mapper.writeValueAsString(output))))))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(rejected ? 400 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        service.geminiClient.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        service.geminiClient.apiKey = Optional.of("test-key");
    }

    // Answers successive requests with the given drafts, written with keys like DRAFT.
    private void startSequencedGemini(List<JsonNode> requests, List<String> drafts, List<String> finishReasons) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test-model:generateContent", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            int index = requests.size() - 1;
            String response = mapper.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "finishReason", finishReasons.get(index),
                "content", Map.of("parts", List.of(Map.of("text", mapper.writeValueAsString(flatDraft(drafts.get(index))))))))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        service.geminiClient.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        service.geminiClient.apiKey = Optional.of("test-key");
    }

    // Replaces each question's input path key with the question ID the service assigns it.
    private JsonNode flatDraft(String text) {
        try {
            JsonNode original = mapper.readTree(text);
            var result = mapper.createObjectNode();
            var questions = result.putArray("questions");
            List<String> keys = paths.stream().map(FormPath::getPath).sorted().toList();
            for (JsonNode entry : original.path("questions")) {
                var question = (ObjectNode) entry.deepCopy();
                String key = question.remove("key").asText();
                questions.addObject().put("id", keys.contains(key) ? "q" + keys.indexOf(key) : "invented").setAll(question);
            }
            return result;
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private List<Benefit> benefits() throws Exception {
        var check = new CheckConfig();
        check.setCheckName("Residency and income");
        check.setParameters(Map.of("personId", "client", "incomeLimit", 40000));
        check.setInputDefinition(mapper.readTree("""
            {"type":"object","properties":{
              "people":{"type":"array","items":{"type":"object","properties":{
                "dateOfBirth":{"type":"string","format":"date"}}}},
              "simpleChecks":{"type":"object","properties":{"resident":{"type":"boolean"}}},
              "custom":{"type":"object","properties":{"income":{"type":"number"}}},
              "enrollments":{"type":"array","items":{"type":"string"}}
            }}
            """));
        return List.of(new Benefit("housing", "Housing", "Housing assistance", "owner", List.of(check)),
            new Benefit("food", "Food", "Food assistance", "owner", List.of(check)));
    }

    private void startGemini(int status, String draft, String finishReason) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String output;
        try {
            output = mapper.writeValueAsString(flatDraft(draft));
        } catch (Exception malformed) {
            output = draft;
        }
        String response = mapper.writeValueAsString(Map.of("candidates", List.of(Map.of(
            "finishReason", finishReason, "content", Map.of("parts", List.of(Map.of("text", output)))))));
        server.createContext("/test-model:generateContent", exchange -> {
            requestKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            request.set(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        service.geminiClient.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        service.geminiClient.apiKey = Optional.of("test-key");
    }
}
