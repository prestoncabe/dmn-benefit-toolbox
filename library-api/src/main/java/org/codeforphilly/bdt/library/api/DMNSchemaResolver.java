package org.codeforphilly.bdt.library.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Unremovable;
import org.kie.dmn.openapi.DMNOASGeneratorFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.logging.Logger;

/**
 * Resolves DMN type schemas from the compiled DMN models.
 *
 * Older Kogito versions emitted one aggregate dmnDefinitions.json resource.
 * Kogito 10 only emits per-model schema files when its REST generator is
 * enabled, but this application intentionally provides its own REST layer.
 */
@ApplicationScoped
@Unremovable
public class DMNSchemaResolver {
    private static final Logger LOG = Logger.getLogger(DMNSchemaResolver.class.getName());
    private static final String DMN_DEFINITIONS_PATH = "/META-INF/resources/dmnDefinitions.json";

    private final Map<String, JsonNode> schemas;
    private final JsonNode rootNode;

    @Inject
    public DMNSchemaResolver(ModelRegistry modelRegistry) {
        this.schemas = new HashMap<>();
        JsonNode loadedRoot = loadDmnDefinitions();
        this.rootNode = loadedRoot != null ? loadedRoot : generateDmnDefinitions(modelRegistry);
        if (rootNode != null && rootNode.has("definitions")) {
            JsonNode definitions = rootNode.get("definitions");
            definitions.properties().forEach(entry -> {
                schemas.put(entry.getKey(), entry.getValue());
            });
            LOG.info("Loaded " + schemas.size() + " DMN type schemas");
        }
    }

    private JsonNode generateDmnDefinitions(ModelRegistry modelRegistry) {
        try {
            List<org.kie.dmn.api.core.DMNModel> models = modelRegistry.getDmnModels();
            if (models.isEmpty()) {
                LOG.warning("No compiled DMN models available for OpenAPI schema generation");
                return null;
            }

            JsonNode generated = DMNOASGeneratorFactory.generator(models).build().getJsonSchemaNode();
            LOG.info("Generated aggregate DMN schemas from " + models.size() + " compiled models");
            return generated;
        } catch (RuntimeException e) {
            LOG.warning("Failed to generate aggregate DMN schemas: " + e.getMessage());
            return null;
        }
    }

    private JsonNode loadDmnDefinitions() {
        try (InputStream is = getClass().getResourceAsStream(DMN_DEFINITIONS_PATH)) {
            if (is == null) {
                LOG.info("Could not find legacy " + DMN_DEFINITIONS_PATH +
                    "; generating schemas from compiled DMN models");
                return null;
            }
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readTree(is);
        } catch (IOException e) {
            LOG.warning("Failed to parse dmnDefinitions.json: " + e.getMessage());
            return null;
        }
    }

    /**
     * Find the input schema reference for a decision service.
     * Pattern: ns{N}InputSet{ModelName}DS{ServiceName}
     */
    public String findInputSchemaRef(String modelName, String serviceName) {
        // Try to find exact match first
        String exactPattern = "InputSet" + modelName + "DS" + serviceName;
        Optional<String> match = schemas.keySet().stream()
            .filter(key -> key.endsWith(exactPattern))
            .findFirst();

        if (match.isPresent()) {
            return "#/components/schemas/" + match.get();
        }

        // Fallback: try just the model name (for simple decision models)
        String simplePattern = "InputSet" + modelName;
        match = schemas.keySet().stream()
            .filter(key -> key.endsWith(simplePattern))
            .findFirst();

        return match.map(s -> "#/components/schemas/" + s).orElse(null);
    }

    /**
     * Find the output schema reference for a decision service.
     * Pattern: ns{N}OutputSet{ModelName}DS{ServiceName}
     */
    public String findOutputSchemaRef(String modelName, String serviceName) {
        // Try to find exact match first
        String exactPattern = "OutputSet" + modelName + "DS" + serviceName;
        Optional<String> match = schemas.keySet().stream()
            .filter(key -> key.endsWith(exactPattern))
            .findFirst();

        if (match.isPresent()) {
            return "#/components/schemas/" + match.get();
        }

        // Fallback: try just the model name
        String simplePattern = "OutputSet" + modelName;
        match = schemas.keySet().stream()
            .filter(key -> key.endsWith(simplePattern))
            .findFirst();

        return match.map(s -> "#/components/schemas/" + s).orElse(null);
    }

    /**
     * Get a schema definition by its key.
     */
    public JsonNode getSchema(String schemaKey) {
        return schemas.get(schemaKey);
    }

    /**
     * Get all schema keys.
     */
    public Set<String> getAllSchemaKeys() {
        return schemas.keySet();
    }

    /**
     * Generate a realistic example from a schema reference.
     */
    public Map<String, Object> generateExample(String schemaRef) {
        if (schemaRef == null) {
            return createFallbackExample();
        }

        // Handle both OpenAPI (#/components/schemas/) and JSON Schema (#/definitions/) references
        String schemaKey;
        if (schemaRef.startsWith("#/components/schemas/")) {
            schemaKey = schemaRef.substring("#/components/schemas/".length());
        } else if (schemaRef.startsWith("#/definitions/")) {
            schemaKey = schemaRef.substring("#/definitions/".length());
        } else {
            return createFallbackExample();
        }
        JsonNode schema = schemas.get(schemaKey);

        if (schema == null) {
            return createFallbackExample();
        }

        return generateExampleFromSchema(schema);
    }

    /**
     * Generate an example Map from a JSON schema node.
     * Package-private to allow test access.
     */
    Map<String, Object> generateExampleFromSchema(JsonNode schema) {
        Map<String, Object> example = new LinkedHashMap<>();

        if (!schema.has("properties")) {
            return example;
        }

        JsonNode properties = schema.get("properties");
        properties.properties().forEach(entry -> {
            String fieldName = entry.getKey();
            JsonNode fieldSchema = entry.getValue();
            example.put(fieldName, generateFieldExample(fieldName, fieldSchema));
        });

        return example;
    }

    private Object generateFieldExample(String fieldName, JsonNode fieldSchema) {
        // Resolve the schema by following any $ref chains
        JsonNode resolvedSchema = resolveSchemaRef(fieldSchema);

        // Handle arrays
        if (resolvedSchema.has("type") && "array".equals(resolvedSchema.get("type").asText())) {
            JsonNode items = resolvedSchema.get("items");
            if (items != null) {
                // Generate one example item for the array
                Object exampleItem = generateFieldExample(fieldName + "_item", items);
                return Collections.singletonList(exampleItem);
            }
            return Collections.emptyList();
        }

        // Handle objects
        if (resolvedSchema.has("type") && "object".equals(resolvedSchema.get("type").asText())) {
            return generateExampleFromSchema(resolvedSchema);
        }

        // Handle primitives based on field name and type
        String type = resolvedSchema.has("type") ? resolvedSchema.get("type").asText() : "string";
        String format = resolvedSchema.has("format") ? resolvedSchema.get("format").asText() : null;

        return generatePrimitiveExample(fieldName, type, format, resolvedSchema);
    }

    /**
     * Recursively resolve a schema by following $ref chains.
     * This handles cases like ns2tSituation__people -> ns2tPersonList -> array of ns2tPerson
     */
    private JsonNode resolveSchemaRef(JsonNode schema) {
        // If this is not a reference, return as-is
        if (!schema.has("$ref")) {
            return schema;
        }

        String ref = schema.get("$ref").asText();
        String refKey = null;

        // Handle both OpenAPI and JSON Schema reference styles
        if (ref.startsWith("#/components/schemas/")) {
            refKey = ref.substring("#/components/schemas/".length());
        } else if (ref.startsWith("#/definitions/")) {
            refKey = ref.substring("#/definitions/".length());
        }

        if (refKey != null) {
            JsonNode refSchema = schemas.get(refKey);
            if (refSchema != null) {
                // Recursively resolve in case the referenced schema is also a reference
                return resolveSchemaRef(refSchema);
            }
        }

        // If we can't resolve, return the original schema
        return schema;
    }

    private Object generatePrimitiveExample(String fieldName, String type, String format, JsonNode schema) {
        // Check for enum values
        if (schema.has("enum")) {
            JsonNode enumNode = schema.get("enum");
            if (enumNode.isArray() && enumNode.size() > 0) {
                return enumNode.get(0).asText();
            }
        }

        switch (type) {
            case "string":
                if ("date".equals(format)) {
                    // A screening date within published rules; other dates are realistic birth dates for age-based checks
                    return "asOfDate".equals(fieldName) ? "2026-10-15" : "1960-05-15";
                }
                // Generate realistic examples based on field name
                if (fieldName.contains("Id") || fieldName.equals("id")) {
                    return "p1";
                } else if (fieldName.contains("benefit") || fieldName.contains("Benefit")) {
                    return "PhlHomesteadExemption";
                } else if (fieldName.contains("type")) {
                    return "spouse";
                }
                return "example-" + fieldName;

            case "number":
                if (fieldName.contains("Age") || fieldName.contains("age")) {
                    return 65;
                }
                if (fieldName.contains("Size") || fieldName.contains("size")) {
                    return 1;  // Household sizes index income tables starting at 1
                }
                return 42;

            case "boolean":
                return fieldName.startsWith("Not") ? false : true;

            case "integer":
                return 1;

            default:
                return null;
        }
    }

    private Map<String, Object> createFallbackExample() {
        Map<String, Object> example = new LinkedHashMap<>();

        // Basic tSituation structure
        Map<String, Object> situation = new LinkedHashMap<>();
        situation.put("primaryPersonId", "p1");

        List<Map<String, Object>> people = new ArrayList<>();
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("id", "p1");
        person.put("dateOfBirth", "1960-05-15");
        people.add(person);
        situation.put("people", people);

        situation.put("enrollments", Collections.emptyList());
        situation.put("relationships", Collections.emptyList());
        situation.put("TenYearTaxAbatement", false);
        situation.put("PhillyHomeownerOccupant", true);

        example.put("situation", situation);

        // Generic parameters
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("personId", "p1");
        example.put("parameters", parameters);

        return example;
    }
}
