package org.codeforphilly.bdt.builder.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.codeforphilly.bdt.builder.enums.EvaluationResult;
import org.codeforphilly.bdt.builder.model.domain.CheckConfig;
import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.EligibilityCheck;
import org.codeforphilly.bdt.builder.persistence.StorageService;
import org.codeforphilly.bdt.builder.persistence.FirestoreUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;


@ApplicationScoped
public class LibraryApiService {
    private static final String DEFAULT_LIBRARY_API_URL = "http://localhost:8083";
    private static final String AS_OF_DATE_PARAMETER = "asOfDate";

    public record LibraryCheckEvaluation(
        EvaluationResult result,
        Map<String, Object> effectiveParameters,
        List<String> defaultedParameters
    ) {}

    record EffectiveParameters(
        Map<String, Object> parameters,
        List<String> defaultedParameters
    ) {}

    @Inject
    private StorageService storageService;

    @ConfigProperty(name = "library-api.base-url")
    Optional<String> libraryApiBaseUrl;

    private List<EligibilityCheck> checks = List.of();
    private List<Benefit> benefits = List.of();
    private String effectiveBaseUrl;
    private boolean useVersionedUrls;

    @PostConstruct
    void init() {
        try {
            // Determine effective base URL
            effectiveBaseUrl = libraryApiBaseUrl.orElse(DEFAULT_LIBRARY_API_URL);

            // Infer environment from URL - localhost = development, else = production
            boolean isProduction = !(effectiveBaseUrl.contains("localhost") || effectiveBaseUrl.contains("127.0.0.1"));
            useVersionedUrls = isProduction;

            Log.info("========================================");
            Log.info("Library API Configuration");
            Log.info("========================================");
            Log.info("Base URL: " + effectiveBaseUrl);
            Log.info("Mode: " + (isProduction ? "production" : "development"));
            Log.info("Versioned URLs: " + (useVersionedUrls ? "enabled" : "disabled"));
            Log.info("========================================");

            // Get path of most recent library schema json document
            Optional<Map<String, Object>> configOpt = FirestoreUtils.getFirestoreDocById("system", "config");
            if (configOpt.isEmpty()){
                Log.error("Failed to load library api config");
                return;
            }
            Map<String, Object> config = configOpt.get();
            String schemaPath = config.get("latestJsonStoragePath").toString();
            Optional<String> apiSchemaOpt = storageService.getStringFromStorage(schemaPath);
            if (apiSchemaOpt.isEmpty()){
                Log.error("Failed to load library api schema document");
                return;
            }
            String apiSchemaJson = apiSchemaOpt.get();

            loadMetadata(apiSchemaJson);

            Object benefitsSchemaPath = config.get("latestBenefitsJsonStoragePath");
            if (benefitsSchemaPath != null) {
                storageService.getStringFromStorage(benefitsSchemaPath.toString())
                    .ifPresent(this::loadBenefitsMetadataUnchecked);
            }
            Log.info("Loaded " + checks.size() + " library checks and "
                + benefits.size() + " library benefits");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load library api metadata", e);
        }
    }

    public List<EligibilityCheck> getAll() {
        return checks;
    }

    void loadMetadata(String apiSchemaJson) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.readTree(apiSchemaJson);
        if (root.isArray()) {
            // Backwards compatibility with metadata generated before library benefits.
            checks = mapper.convertValue(root, new TypeReference<List<EligibilityCheck>>() {});
            benefits = List.of();
            return;
        }

        checks = root.path("checks").isArray()
            ? mapper.convertValue(root.path("checks"), new TypeReference<List<EligibilityCheck>>() {})
            : List.of();
        benefits = root.path("benefits").isArray() ? readBenefits(mapper, root.path("benefits")) : List.of();
    }

    void loadBenefitsMetadata(String benefitsJson) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.readTree(benefitsJson);
        benefits = root.isArray() ? readBenefits(mapper, root) : List.of();
    }

    private List<Benefit> readBenefits(ObjectMapper mapper, JsonNode benefitsNode) {
        List<Benefit> result = new ArrayList<>();
        for (JsonNode benefitNode : benefitsNode) {
            ObjectNode benefit = benefitNode.deepCopy();
            if (resolveParameterBindings(benefit)) {
                result.add(mapper.convertValue(benefit, Benefit.class));
            }
        }
        return result;
    }

    /**
     * Library benefits bind some check parameters to situation fields (e.g. personId to
     * primaryPersonId). Screener checks only have plain parameters, so replace each binding
     * with the value the builder's form conventions supply for that field. An asOfDate parameter
     * bound to the benefit's asOfDate is left unset, so evaluation defaults it to today and screener
     * authors can set each check's date; other parameters can't be defaulted from that date.
     * Returns false if the benefit uses a binding the builder can't represent.
     */
    private boolean resolveParameterBindings(ObjectNode benefit) {
        for (JsonNode check : benefit.path("checks")) {
            JsonNode bindings = ((ObjectNode) check).remove("parameterBindings");
            if (bindings == null || bindings.isEmpty()) {
                continue;
            }
            JsonNode existingParameters = check.path("parameters");
            ObjectNode parameters = existingParameters.isObject()
                ? (ObjectNode) existingParameters
                : ((ObjectNode) check).putObject("parameters");
            for (var binding : bindings.properties()) {
                if (AS_OF_DATE_PARAMETER.equals(binding.getKey())
                        && AS_OF_DATE_PARAMETER.equals(binding.getValue().asText())) {
                    continue;
                }
                if (!"primaryPersonId".equals(binding.getValue().asText())) {
                    Log.warn("Skipping library benefit " + benefit.path("id").asText()
                        + ": unsupported parameter binding " + binding.getKey() + " -> " + binding.getValue().asText());
                    return false;
                }
                parameters.put(binding.getKey(), FormDataTransformer.DEFAULT_PRIMARY_PERSON_ID);
            }
        }
        return true;
    }

    private void loadBenefitsMetadataUnchecked(String benefitsJson) {
        try {
            loadBenefitsMetadata(benefitsJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid library benefit metadata", e);
        }
    }

    public List<EligibilityCheck> getByModule(String module) {
        return checks.stream()
                .filter(e -> module.equals(e.getModule()))
                .toList();
    }

    public Optional<EligibilityCheck> getById(String id) {
         List<EligibilityCheck> matches = checks.stream()
                .filter(e -> id.equals(e.getId()))
                .toList();
         if (matches.isEmpty()) {
             return Optional.empty();
         }
         return Optional.of(matches.getFirst());
    }

    public List<Benefit> getBenefits() {
        return benefits;
    }

    /**
     * A screener can reference composition-only checks carried by a library benefit.
     * Resolve those against trusted benefit metadata without adding them to the public picker.
     */
    public Optional<EligibilityCheck> getScreenerCheckById(String id) {
        return getById(id).or(() -> benefits.stream()
            .filter(benefit -> benefit.getChecks() != null)
            .flatMap(benefit -> benefit.getChecks().stream())
            .filter(config -> id.equals(config.getSourceCheckId() != null
                ? config.getSourceCheckId() : config.getCheckId()))
            .findFirst()
            .map(config -> {
                EligibilityCheck check = new EligibilityCheck();
                check.setId(id);
                check.setName(config.getCheckName());
                check.setModule(config.getCheckModule());
                check.setVersion(config.getCheckVersion());
                check.setEvaluationUrl(config.getEvaluationUrl());
                check.setInputDefinition(config.getInputDefinition());
                check.setParameterDefinitions(config.getParameterDefinitions());
                return check;
            }));
    }

    public Optional<Benefit> getBenefitById(String id) {
        return benefits.stream()
            .filter(benefit -> id.equals(benefit.getId()))
            .findFirst();
    }

    /**
     * Snapshot a library template into an independently editable screener benefit.
     */
    public Optional<Benefit> copyBenefitForOwner(String id, String ownerId) {
        ObjectMapper mapper = new ObjectMapper();
        return getBenefitById(id).map(template -> {
            Benefit copy = mapper.convertValue(template, Benefit.class);
            copy.setId(UUID.randomUUID().toString());
            copy.setOwnerId(ownerId);
            if (copy.getChecks() != null) {
                copy.getChecks().forEach(check -> check.setCheckId(UUID.randomUUID().toString()));
            }
            return copy;
        });
    }

    public LibraryCheckEvaluation evaluateCheck(CheckConfig checkConfig, Map<String, Object> inputs) throws JsonProcessingException {

        // TODO: Check that checkConfig has required attributes and handle null values
        EffectiveParameters effectiveParameters = buildEffectiveParameters(checkConfig);

        Map<String, Object> data = new HashMap<>();
        data.put("parameters", effectiveParameters.parameters());
        data.put("situation", inputs);
        ObjectMapper mapper = new ObjectMapper();
        String bodyJson = mapper.writeValueAsString(data);

        HttpClient client = HttpClient.newHttpClient();

        // Determine base URL based on configuration
        String baseUrl;
        if (useVersionedUrls) {
            // Production: Use versioned Cloud Run URLs
            String urlEncodedVersion = checkConfig.getCheckVersion().replace('.', '-');
            baseUrl = String.format("https://library-api-v%s---library-api-cnsoqyluna-uc.a.run.app", urlEncodedVersion);
            Log.debug("Using versioned URL: " + baseUrl);
        } else {
            // Development: Use configured base URL directly
            baseUrl = effectiveBaseUrl;
            Log.debug("Using base URL: " + baseUrl);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + checkConfig.getEvaluationUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            if (statusCode != 200){
                Log.error("Error evaluating library check " + checkConfig.getCheckId());
                Log.error("Inputs and parameters that caused error:" + bodyJson);
                return new LibraryCheckEvaluation(
                    EvaluationResult.UNABLE_TO_DETERMINE,
                    effectiveParameters.parameters(),
                    effectiveParameters.defaultedParameters()
                );
            }
            String body = response.body();
            Map<String, Object> responseBody = mapper.readValue(
                    body,
                    new TypeReference<Map<String, Object>>() {}
            );

            // TODO: Need a safer way to validate the returned data is in the right format
            Object result = responseBody.get("checkResult");
            if (result == null) {
                return new LibraryCheckEvaluation(
                    EvaluationResult.UNABLE_TO_DETERMINE,
                    effectiveParameters.parameters(),
                    effectiveParameters.defaultedParameters()
                );
            }
            return new LibraryCheckEvaluation(
                EvaluationResult.fromStringIgnoreCase(result.toString()),
                effectiveParameters.parameters(),
                effectiveParameters.defaultedParameters()
            );
        }
        catch (Exception e){
            Log.error(e);
            return new LibraryCheckEvaluation(
                EvaluationResult.UNABLE_TO_DETERMINE,
                effectiveParameters.parameters(),
                effectiveParameters.defaultedParameters()
            );
        }
    }

    EffectiveParameters buildEffectiveParameters(CheckConfig checkConfig) {
        Map<String, Object> configuredParameters = checkConfig.getParameters() != null
            ? checkConfig.getParameters()
            : Map.of();
        Map<String, Object> parameters = new HashMap<>(configuredParameters);
        List<String> defaultedParameters = new ArrayList<>();

        if (declaresAsOfDateParameter(checkConfig) && isMissingParameter(parameters.get(AS_OF_DATE_PARAMETER))) {
            parameters.put(AS_OF_DATE_PARAMETER, LocalDate.now().toString());
            defaultedParameters.add(AS_OF_DATE_PARAMETER);
        }

        return new EffectiveParameters(parameters, defaultedParameters);
    }

    private boolean declaresAsOfDateParameter(CheckConfig checkConfig) {
        if (checkConfig.getParameterDefinitions() == null) {
            return false;
        }
        return checkConfig.getParameterDefinitions().stream()
            .anyMatch(parameterDefinition -> AS_OF_DATE_PARAMETER.equals(parameterDefinition.getKey()));
    }

    private boolean isMissingParameter(Object value) {
        return value == null || (value instanceof String && ((String) value).isBlank());
    }
}
