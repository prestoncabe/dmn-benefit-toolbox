package org.codeforphilly.bdt.builder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.CheckConfig;
import org.codeforphilly.bdt.builder.model.domain.FormPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
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
        var form = service.toFormSchema(keyedDraft(DRAFT), service.questionPaths(paths), Map.of());
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
        assertFalse(form.toString().contains("defaultValue"));
        assertFalse(form.toString().contains("Screening complete"));
    }

    @Test
    void rejectsMissingDuplicateInventedAndUnanswerableQuestions() throws Exception {
        for (String invalid : List.of(
            "{\"questions\":[]}",
            DRAFT.replace("custom.income", "simpleChecks.resident"),
            DRAFT.replace("custom.income", "custom.invented"),
            DRAFT.replace("Your yearly income", " "),
            DRAFT.replace("Do you live here?", "= true"),
            DRAFT.replace("[{\"label\":\"Housing assistance\",\"value\":\"Housing\"},{\"label\":\"Food assistance\",\"value\":\"Food\"}]", "[]")
        )) {
            assertThrows(IllegalArgumentException.class,
                () -> service.toFormSchema(keyedDraft(invalid), service.questionPaths(paths), Map.of()));
        }
    }

    @Test
    void buildsFieldsForUntypedInputsFromTheModelsAnswerType() throws Exception {
        var untyped = service.questionPaths(List.of(new FormPath("custom.wantsExtraCash", "any"),
            new FormPath("custom.householdIncome", "any"), new FormPath("custom.county", "any"),
            new FormPath("custom.programs", "array:any")));
        var draft = mapper.readTree("""
            {"order":["q0","q1","q2","q3"],"questions":{
              "q0":{"label":"County","description":"","answerType":"text",
                    "values":[{"label":"Philadelphia","value":"Philadelphia"}]},
              "q1":{"label":"Household income","description":"","answerType":"number","values":[]},
              "q2":{"label":"Programs","description":"","values":[{"label":"SNAP","value":"SNAP"}]},
              "q3":{"label":"Want extra cash?","description":"","answerType":"yes_no","values":[]}}}
            """);
        var fields = service.toFormSchema(draft, untyped, Map.of()).path("components");
        assertEquals("radio", fields.get(0).path("type").asText());
        assertEquals("number", fields.get(1).path("type").asText());
        assertEquals("checklist_none", fields.get(2).path("type").asText());
        assertEquals("yes_no", fields.get(3).path("type").asText());

        ((com.fasterxml.jackson.databind.node.ObjectNode) draft.path("questions").path("q1")).remove("answerType");
        assertThrows(IllegalArgumentException.class, () -> service.toFormSchema(draft, untyped, Map.of()));
    }

    @Test
    void asksTheModelForAnAnswerTypeOnlyForUntypedInputs() throws Exception {
        startGemini(200, DRAFT, "STOP");
        var untyped = List.of(new FormPath("custom.income", "any"), new FormPath("people.client.enrollments", "array:string"));
        service.generate(benefits(), untyped);
        var questions = request.get().path("generationConfig").path("responseJsonSchema")
            .path("properties").path("questions").path("properties");
        assertTrue(questions.path("q0").path("properties").has("answerType"));
        assertTrue(questions.path("q0").path("required").toString().contains("answerType"));
        assertFalse(questions.path("q1").path("properties").has("answerType"));
        assertEquals(1, questions.path("q1").path("properties").path("values").path("minItems").asInt());
    }

    @Test
    void restrictsChoicesToSchemaEnumsAndConfiguredBenefitCodes() throws Exception {
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
        var configured = List.of(new Benefit("homestead", "Homestead", null, "owner", List.of(check)));
        var questions = service.questionPaths(service.inputSchemaService.extractUniqueInputPaths(configured));
        assertEquals("custom.relationship", questions.get("q0").getPath());
        assertEquals("people.client.enrollments", questions.get("q1").getPath());
        var allowed = service.allowedOptionValues(configured, questions);
        assertEquals(Map.of("q0", List.of("child", "spouse"), "q1", List.of("PhlHomesteadExemption")), allowed);

        String valid = """
            {"order":["q0","q1"],"questions":{
              "q0":{"label":"Relationship","description":"","values":[{"label":"Spouse","value":"spouse"}]},
              "q1":{"label":"Enrolled in","description":"","values":[{"label":"Homestead","value":"PhlHomesteadExemption"}]}}}
            """;
        assertEquals(2, service.toFormSchema(mapper.readTree(valid), questions, allowed).path("components").size());
        for (String invalid : List.of(
            valid.replace("\"value\":\"spouse\"", "\"value\":\"Spouse\""),
            valid.replace("\"value\":\"PhlHomesteadExemption\"", "\"value\":\"Homestead\""),
            valid.replace("[{\"label\":\"Spouse\",\"value\":\"spouse\"}]", "[]")
        )) {
            assertThrows(IllegalArgumentException.class,
                () -> service.toFormSchema(mapper.readTree(invalid), questions, allowed));
        }

        startGemini(200, DRAFT, "STOP");
        service.generate(configured, List.copyOf(questions.values()));
        var enrollmentValue = request.get().path("generationConfig").path("responseJsonSchema").path("properties")
            .path("questions").path("properties").path("q1").path("properties").path("values")
            .path("items").path("properties").path("value");
        assertEquals("[\"PhlHomesteadExemption\"]", enrollmentValue.path("enum").toString());
    }

    @Test
    void generatesUsingConfiguredChecksAndDeduplicatedTransformedSchemas() throws Exception {
        startGemini(200, DRAFT, "STOP");
        var form = service.generate(benefits(), paths).orElseThrow();
        assertEquals(4, form.path("components").size());
        assertEquals("test-key", requestKey.get());
        var config = request.get().path("generationConfig");
        assertEquals("application/json", config.path("responseMimeType").asText());
        var questionSchema = config.path("responseJsonSchema").path("properties").path("questions");
        assertEquals("object", questionSchema.path("type").asText());
        assertEquals(4, questionSchema.path("required").size());
        assertTrue(questionSchema.path("properties").has("q2"));
        assertFalse(questionSchema.path("properties").has("people.client.enrollments"));
        assertEquals(4, config.path("responseJsonSchema").path("properties").path("order").path("minItems").asInt());
        String prompt = request.get().path("contents").get(0).path("parts").get(0).path("text").asText();
        assertTrue(prompt.contains("minimum number of questions"));
        assertTrue(prompt.contains("ALL benefits"));
        assertTrue(prompt.contains("do not over-explain") || prompt.contains("Do not over-explain"));
        assertTrue(prompt.contains("Housing"));
        assertTrue(prompt.contains("Food"));
        assertTrue(prompt.contains("\"incomeLimit\":40000"));
        assertTrue(prompt.contains("people.client.dateOfBirth"));
        assertTrue(prompt.contains("Residency and income"));
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
    void rejectsOmittedInputsEvenWhenTheModelConsidersThemUnnecessary() throws Exception {
        var draft = keyedDraft(DRAFT);
        ((com.fasterxml.jackson.databind.node.ObjectNode) draft.get("questions")).remove("q0");
        assertThrows(IllegalArgumentException.class, () -> service.toFormSchema(draft, service.questionPaths(paths), Map.of()));
    }

    @Test
    void repeatedOrderingEntriesDoNotDropOrDuplicateRequiredQuestions() throws Exception {
        var draft = keyedDraft(DRAFT);
        ((com.fasterxml.jackson.databind.node.ObjectNode) draft).putArray("order")
            .add("q3").add("q3");
        var fields = service.toFormSchema(draft, service.questionPaths(paths), Map.of()).path("components");
        assertEquals(4, fields.size());
        assertEquals("simpleChecks.resident", fields.get(0).path("key").asText());
        assertEquals(4, fields.findValuesAsText("key").stream().distinct().count());
    }

    @Test
    void largeDraftsUseBoundedSchemasAndOneGlobalOrderingWithoutLosingSharedInputs() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        startSchemaLimitedGemini(requests, "", -1);
        List<Benefit> configured = largeScreener();
        List<FormPath> inputs = service.inputSchemaService.extractUniqueInputPaths(configured);
        assertEquals(80, inputs.size());

        JsonNode form = service.generate(configured, inputs).orElseThrow();
        JsonNode fields = form.path("components");
        assertEquals(80, fields.size());
        assertEquals(80, new HashSet<>(fields.findValuesAsText("key")).size());
        assertEquals(inputs.stream().map(FormPath::getPath).collect(java.util.stream.Collectors.toSet()),
            new HashSet<>(fields.findValuesAsText("key")));
        // The ordering request reverses all IDs, independently of the earlier batch ordering.
        assertEquals(inputs.stream().map(FormPath::getPath).sorted().toList().getLast(),
            fields.get(0).path("key").asText());
        assertEquals(8, requests.size());
        for (JsonNode request : requests.subList(0, requests.size() - 1)) {
            int size = request.path("generationConfig").path("responseJsonSchema")
                .path("properties").path("questions").path("required").size();
            assertTrue(size > 0 && size <= 12);
        }
        assertFalse(requests.getLast().path("generationConfig").path("responseJsonSchema")
            .path("properties").has("questions"));
        assertEquals(80, requests.getLast().path("generationConfig").path("responseJsonSchema")
            .path("properties").path("order").path("items").path("enum").size());
    }

    @Test
    void incompleteOrTruncatedBatchesNeverReturnAPartialForm() throws Exception {
        for (String failure : List.of("MISSING_QUESTION", "MAX_TOKENS")) {
            List<JsonNode> requests = new ArrayList<>();
            startSchemaLimitedGemini(requests, failure, 2);
            List<Benefit> configured = largeScreener();
            assertTrue(service.generate(configured,
                service.inputSchemaService.extractUniqueInputPaths(configured)).isEmpty());
            assertEquals(2, requests.size(), "Stop after the invalid batch");
            server.stop(0);
        }
    }

    @Test
    void invalidGlobalOrderingNeverProducesAnUnreviewedPartialDraft() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        startSchemaLimitedGemini(requests, "UNKNOWN_ORDER_ID", 8);
        List<Benefit> configured = largeScreener();
        assertTrue(service.generate(configured,
            service.inputSchemaService.extractUniqueInputPaths(configured)).isEmpty());
        assertEquals(8, requests.size());
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
            server.stop(0);
        }
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

    private void startSchemaLimitedGemini(List<JsonNode> requests, String failure, int failAt) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test-model:generateContent", exchange -> {
            JsonNode body = mapper.readTree(exchange.getRequestBody());
            requests.add(body);
            JsonNode schema = body.path("generationConfig").path("responseJsonSchema");
            JsonNode properties = schema.path("properties").path("questions").path("properties");
            boolean ordering = properties.isMissingNode();
            int status = !ordering && properties.size() > 12 ? 400 : 200;
            var output = mapper.createObjectNode();
            var order = output.putArray("order");
            List<String> ids = new ArrayList<>();
            schema.path("properties").path("order").path("items").path("enum")
                .forEach(id -> ids.add(id.asText()));
            if (ordering) java.util.Collections.reverse(ids);
            ids.forEach(order::add);
            if (!ordering) {
                var questions = output.putObject("questions");
                ids.forEach(id -> {
                    var question = questions.putObject(id);
                    question.put("label", "Synthetic question " + id).put("description", "").putArray("values");
                });
                if (requests.size() == failAt && "MISSING_QUESTION".equals(failure)) questions.remove(ids.getLast());
            } else if (requests.size() == failAt && "UNKNOWN_ORDER_ID".equals(failure)) {
                order.add("invented");
            }
            String finishReason = requests.size() == failAt && "MAX_TOKENS".equals(failure) ? "MAX_TOKENS" : "STOP";
            String response = mapper.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "finishReason", finishReason,
                "content", Map.of("parts", List.of(Map.of("text", mapper.writeValueAsString(output))))))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        service.geminiClient.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        service.geminiClient.apiKey = Optional.of("test-key");
    }

    private JsonNode keyedDraft(String text) throws Exception {
        JsonNode original = mapper.readTree(text);
        var result = mapper.createObjectNode();
        var questions = result.putObject("questions");
        var order = result.putArray("order");
        List<String> keys = paths.stream().map(FormPath::getPath).sorted().toList();
        for (JsonNode entry : original.path("questions")) {
            var question = (com.fasterxml.jackson.databind.node.ObjectNode) entry.deepCopy();
            String key = question.remove("key").asText();
            String id = "q" + keys.indexOf(key);
            questions.set(id, question);
            order.add(id);
        }
        return result;
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
            output = mapper.writeValueAsString(keyedDraft(draft));
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
