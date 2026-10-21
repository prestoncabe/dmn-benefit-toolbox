package org.codeforphilly.bdt.builder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;

import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.CheckConfig;
import org.codeforphilly.bdt.builder.model.domain.FormPath;

import java.util.*;

/**
 * Service for transforming and extracting paths from JSON Schema input definitions.
 */
@ApplicationScoped
public class InputSchemaService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Extracts all unique input paths from all benefits in a screener.
     *
     * @param benefits List of benefits containing checks with inputDefinitions
     * @return List of unique FormPath objects
     */
    public List<FormPath> extractUniqueInputPaths(List<Benefit> benefits) {
        Map<String, String> pathTypeMap = new HashMap<>();

        for (Benefit benefit : benefits) {
            List<CheckConfig> checks = benefit.getChecks();
            if (checks == null) continue;

            for (CheckConfig check : checks) {
                for (FormPath checkFormPath : extractInputPaths(check)) {
                    // If the same path exists with different types, keep the first one found
                    pathTypeMap.putIfAbsent(checkFormPath.getPath(), checkFormPath.getType());
                }
            }
        }

        // Convert to sorted list of FormPath objects
        return pathTypeMap.entrySet().stream()
            .map(entry -> new FormPath(entry.getKey(), entry.getValue()))
            .toList();
    }

    /**
     * Extracts the form paths requested by one configured eligibility check.
     * These paths contribute to the configured benefit's votes for questions on the screener form.
     */
    public List<FormPath> extractInputPaths(CheckConfig check) {
        return extractJsonSchemaPaths(transformInputDefinitionSchema(check));
    }

    /**
     * Transforms a CheckConfig's inputDefinition JSON Schema by applying all schema transformations.
     * Currently applies:
     * 1. People transformation: converts people array to object keyed by personId(s)
     * 2. Spouse transformation: exposes the primary person's spouse as people.spouse.*
     * 3. Enrollments transformation: moves enrollments under people.{personId}.enrollments
     * 4. Income transformation: asks basis-keyed incomeAssessments as one question pair
     *
     * Supports both single personId (String) and multiple peopleIds (List<String>) parameters.
     * Checks using primaryPersonId use the conventional "client" ID in form paths.
     *
     * @param checkConfig The CheckConfig containing inputDefinition and parameters
     * @return A new JsonNode with all transformations applied
     */
    public JsonNode transformInputDefinitionSchema(CheckConfig checkConfig) {
        JsonNode inputDefinition = checkConfig.getInputDefinition();

        if (inputDefinition == null || !inputDefinition.has("properties")) {
            return inputDefinition != null ? inputDefinition.deepCopy() : objectMapper.createObjectNode();
        }

        // Extract personId(s) from parameters - supports both single personId and multiple peopleIds
        List<String> personIds = new ArrayList<>(extractPersonIds(checkConfig.getParameters()));
        if (inputDefinition.path("properties").has("primaryPersonId")
                && !personIds.contains(FormDataTransformer.DEFAULT_PRIMARY_PERSON_ID)) {
            personIds.add(FormDataTransformer.DEFAULT_PRIMARY_PERSON_ID);
        }

        // Apply each transformation in sequence
        JsonNode schema = inputDefinition.deepCopy();
        schema = transformPeopleSchema(schema, personIds);
        schema = transformSpouseSchema(schema);
        schema = transformEnrollmentsSchema(schema, personIds);
        schema = transformIncomeAssessmentsSchema(schema);

        // The form uses people.client.*; the submission transformer supplies this ID.
        ((ObjectNode) schema.get("properties")).remove("primaryPersonId");
        if (schema.path("required").isArray()) {
            JsonNode originalRequired = schema.get("required");
            var required = ((ObjectNode) schema).putArray("required");
            originalRequired.forEach(property -> {
                if (!"primaryPersonId".equals(property.asText())) {
                    required.add(property.deepCopy());
                }
            });
        }

        return schema;
    }

    /**
     * Exposes people.spouse fields and a synthetic people.spouse.exists Boolean
     * for checks such as SCTF that
     * look up the primary person's spouse through the relationships array.
     * Must be called after transformPeopleSchema.
     * The relationship IDs are supplied by FormDataTransformer at evaluation time.
     */
    public JsonNode transformSpouseSchema(JsonNode schema) {
        if (schema == null) {
            return objectMapper.createObjectNode();
        }

        JsonNode properties = schema.path("properties");
        JsonNode personSchema = properties.path("people").path("properties")
            .path(FormDataTransformer.DEFAULT_PRIMARY_PERSON_ID);
        JsonNode relationshipProperties = properties.path("relationships").path("items").path("properties");
        JsonNode relationshipTypes = relationshipProperties.path("type").path("enum");
        if (!properties.has("primaryPersonId") || !personSchema.has("properties")
                || !relationshipProperties.has("personId") || !relationshipProperties.has("relatedPersonId")
                || !relationshipTypes.isArray() || relationshipTypes.size() != 1
                || !"spouse".equals(relationshipTypes.get(0).asText())) {
            return schema.deepCopy();
        }

        ObjectNode transformedSchema = schema.deepCopy();
        ObjectNode transformedProperties = (ObjectNode) transformedSchema.get("properties");
        ObjectNode spouseSchema = personSchema.deepCopy();
        ObjectNode spouseProperties = (ObjectNode) spouseSchema.path("properties");
        ObjectNode existsSchema = objectMapper.createObjectNode();
        existsSchema.put("type", "boolean");
        spouseProperties.set("exists", existsSchema);
        ((ObjectNode) transformedProperties.path("people").path("properties"))
            .set("spouse", spouseSchema);
        transformedProperties.remove("relationships");
        if (transformedSchema.path("required").isArray()) {
            var required = transformedSchema.putArray("required");
            schema.path("required").forEach(property -> {
                if (!"relationships".equals(property.asText())) {
                    required.add(property.deepCopy());
                }
            });
        }
        return transformedSchema;
    }

    /**
     * Collapses incomeAssessments, which library checks key by income basis (e.g. paLiheap,
     * pecoCap), into one incomeAssessments.annualIncome / householdSize question pair.
     * Programs count income differently, but a screener asks once;
     * FormDataTransformer.expandIncomeAssessments copies the answers to every basis.
     */
    public JsonNode transformIncomeAssessmentsSchema(JsonNode schema) {
        if (schema == null) {
            return objectMapper.createObjectNode();
        }

        JsonNode bases = schema.path("properties").path("incomeAssessments").path("properties");
        ObjectNode fields = objectMapper.createObjectNode();
        for (JsonNode basis : bases) {
            if (!basis.has("properties")) {
                return schema.deepCopy();
            }
            basis.get("properties").properties()
                .forEach(field -> fields.putIfAbsent(field.getKey(), field.getValue().deepCopy()));
        }
        if (fields.isEmpty()) {
            return schema.deepCopy();
        }

        ObjectNode transformedSchema = schema.deepCopy();
        ObjectNode incomeSchema = ((ObjectNode) transformedSchema.get("properties")).putObject("incomeAssessments");
        incomeSchema.put("type", "object");
        incomeSchema.set("properties", fields);
        return transformedSchema;
    }

    /**
     * Returns the income bases that the screener's checks read, which receive the
     * screener's single income answer.
     */
    public Set<String> extractIncomeAssessmentBases(List<Benefit> benefits) {
        Set<String> bases = new TreeSet<>();
        for (Benefit benefit : benefits) {
            if (benefit.getChecks() == null) continue;
            for (CheckConfig check : benefit.getChecks()) {
                JsonNode inputDefinition = check.getInputDefinition();
                if (inputDefinition == null) continue;
                inputDefinition.path("properties").path("incomeAssessments").path("properties")
                    .fieldNames().forEachRemaining(bases::add);
            }
        }
        return bases;
    }

    /**
     * Extracts person IDs from parameters, supporting both:
     * - personId: a single String ID
     * - peopleIds: a List of String IDs
     *
     * @param parameters The parameters map from CheckConfig
     * @return List of person IDs (may be empty if neither parameter is set)
     */
    private List<String> extractPersonIds(Map<String, Object> parameters) {
        if (parameters == null) {
            return Collections.emptyList();
        }

        List<String> personIds = new ArrayList<>();

        // Check for single personId
        Object personId = parameters.get("personId");
        if (personId instanceof String && !((String) personId).isEmpty()) {
            personIds.add((String) personId);
        }

        // Check for multiple peopleIds
        Object peopleIds = parameters.get("peopleIds");
        if (peopleIds instanceof List) {
            for (Object id : (List<?>) peopleIds) {
                if (id instanceof String && !((String) id).isEmpty()) {
                    personIds.add((String) id);
                }
            }
        }

        return personIds;
    }

    /**
     * Transforms the `people` array property into an object with personId-keyed properties.
     *
     * Example (single ID):
     *   Input:  { people: { type: "array", items: { properties: { dateOfBirth: ... } } } }
     *   Output: { people: { type: "object", properties: { [personId]: { properties: { dateOfBirth: ... } } } } }
     *
     * Example (multiple IDs):
     *   Input:  { people: { type: "array", items: { properties: { dateOfBirth: ... } } } }
     *   Output: { people: { type: "object", properties: { [id1]: {...}, [id2]: {...} } } }
     *
     * @param schema The JSON Schema to transform
     * @param personIds List of personIds to use as keys under people
     * @return A new JsonNode with `people` transformed, or the original if no transformation needed
     */
    public JsonNode transformPeopleSchema(JsonNode schema, List<String> personIds) {
        if (schema == null || !schema.has("properties")) {
            return schema != null ? schema.deepCopy() : objectMapper.createObjectNode();
        }

        JsonNode properties = schema.get("properties");
        JsonNode peopleProperty = properties.get("people");

        // If no people property, return a copy of the original schema
        if (peopleProperty == null) {
            return schema.deepCopy();
        }

        // If people property exists but no personIds, return original (can't transform)
        if (personIds == null || personIds.isEmpty()) {
            return schema.deepCopy();
        }

        // Deep clone the schema to avoid mutations
        ObjectNode transformedSchema = schema.deepCopy();
        ObjectNode transformedProperties = (ObjectNode) transformedSchema.get("properties");

        // Get the items schema from the people array
        JsonNode itemsSchema = peopleProperty.get("items");

        // Transform people from array to object with personIds as nested properties
        ObjectNode newPeopleSchema = objectMapper.createObjectNode();
        newPeopleSchema.put("type", "object");
        ObjectNode newPeopleProperties = objectMapper.createObjectNode();
        if (itemsSchema != null) {
            for (String personId : personIds) {
                newPeopleProperties.set(personId, itemsSchema.deepCopy());
            }
        }

        newPeopleSchema.set("properties", newPeopleProperties);
        transformedProperties.set("people", newPeopleSchema);
        return transformedSchema;
    }

    /**
     * Transforms the `enrollments` array property by moving it under people.{personId}.enrollments
     * as an array of strings (benefit names).
     *
     * Example (single ID):
     *   Input:  { enrollments: { type: "array", items: { properties: { personId: ..., benefit: ... } } } }
     *   Output: { people: { type: "object", properties: { [personId]: { properties: { enrollments: { type: "array", items: { type: "string" } } } } } } }
     *
     * Example (multiple IDs):
     *   Input:  { enrollments: { type: "array", ... } }
     *   Output: { people: { type: "object", properties: { [id1]: { properties: { enrollments: ... } }, [id2]: { properties: { enrollments: ... } } } } }
     *
     * @param schema The JSON Schema to transform
     * @param personIds List of personIds to use as keys under people
     * @return A new JsonNode with `enrollments` transformed, or the original if no transformation needed
     */
    public JsonNode transformEnrollmentsSchema(JsonNode schema, List<String> personIds) {
        if (schema == null || !schema.has("properties")) {
            return schema != null ? schema.deepCopy() : objectMapper.createObjectNode();
        }

        JsonNode properties = schema.get("properties");
        JsonNode enrollmentsProperty = properties.get("enrollments");

        // If no enrollments property, return a copy of the original schema
        if (enrollmentsProperty == null) {
            return schema.deepCopy();
        }

        // If enrollments property exists but no personIds, return original (can't transform)
        if (personIds == null || personIds.isEmpty()) {
            return schema.deepCopy();
        }

        // Deep clone the schema to avoid mutations
        ObjectNode transformedSchema = schema.deepCopy();
        ObjectNode transformedProperties = (ObjectNode) transformedSchema.get("properties");

        // Remove the top-level enrollments property
        transformedProperties.remove("enrollments");

        // Create enrollments schema as array of strings
        ObjectNode enrollmentsSchema = objectMapper.createObjectNode();
        enrollmentsSchema.put("type", "array");
        ObjectNode itemsSchema = objectMapper.createObjectNode();
        itemsSchema.put("type", "string");
        enrollmentsSchema.set("items", itemsSchema);

        // Get or create the people property
        JsonNode existingPeople = transformedProperties.get("people");
        ObjectNode peopleSchema;
        ObjectNode peopleProps;

        if (existingPeople != null && existingPeople.has("properties")) {
            // People already exists (from transformPeopleSchema)
            peopleSchema = (ObjectNode) existingPeople;
            peopleProps = (ObjectNode) peopleSchema.get("properties");
        } else {
            // Create new people structure
            peopleSchema = objectMapper.createObjectNode();
            peopleSchema.put("type", "object");
            peopleProps = objectMapper.createObjectNode();
            peopleSchema.set("properties", peopleProps);
            transformedProperties.set("people", peopleSchema);
        }

        // Add enrollments under each personId
        for (String personId : personIds) {
            // Get or create the personId property under people
            JsonNode existingPersonId = peopleProps.get(personId);
            ObjectNode personIdSchema;
            ObjectNode personIdProps;

            if (existingPersonId != null && existingPersonId.has("properties")) {
                // PersonId already exists
                personIdSchema = (ObjectNode) existingPersonId;
                personIdProps = (ObjectNode) personIdSchema.get("properties");
            } else {
                // Create new personId structure
                personIdSchema = objectMapper.createObjectNode();
                personIdSchema.put("type", "object");
                personIdProps = objectMapper.createObjectNode();
                personIdSchema.set("properties", personIdProps);
                peopleProps.set(personId, personIdSchema);
            }

            // Add enrollments under the personId
            personIdProps.set("enrollments", enrollmentsSchema.deepCopy());
        }

        return transformedSchema;
    }

    /**
     * Extracts all property paths from a JSON Schema inputDefinition.
     * Recursively traverses nested objects to build dot-separated paths.
     * Excludes the top-level "parameters" property and "id" properties.
     *
     * @param jsonSchema The JSON Schema to parse
     * @return List of dot-separated paths (e.g., ["people.applicant.dateOfBirth", "income"])
     */
    public List<FormPath> extractJsonSchemaPaths(JsonNode jsonSchema) {
        if (jsonSchema == null || !jsonSchema.has("properties")) {
            return new ArrayList<>();
        }

        return traverseSchema(jsonSchema, "");
    }

    private List<FormPath> traverseSchema(JsonNode schema, String parentPath) {
        List<FormPath> formPaths = new ArrayList<>();

        if (schema == null || !schema.has("properties")) {
            return formPaths;
        }

        JsonNode propertiesJsonNode = schema.get("properties");
        Iterator<Map.Entry<String, JsonNode>> nestedProperties = propertiesJsonNode.properties().iterator();

        while (nestedProperties.hasNext()) {
            Map.Entry<String, JsonNode> currentProperty = nestedProperties.next();
            String propKey = currentProperty.getKey();
            JsonNode propValue = currentProperty.getValue();

            // Skip top-level "parameters" property
            if (parentPath.isEmpty() && "parameters".equals(propKey)) {
                continue;
            }

            // Skip "id" properties
            if ("id".equals(propKey)) {
                continue;
            }

            String currentPath = parentPath.isEmpty() ? propKey : parentPath + "." + propKey;
            String currentType = getEffectiveType(propValue);

            // If this property has nested properties, recurse into it
            if (propValue.has("properties")) {
                formPaths.addAll(traverseSchema(propValue, currentPath));
            } else if ("array".equals(currentType) && propValue.has("items")) {
                // Handle arrays - recurse into items schema with the current path
                JsonNode itemsSchema = propValue.get("items");
                if (itemsSchema.has("properties")) {
                    formPaths.addAll(traverseSchema(itemsSchema, currentPath));
                } else {
                    // Array of primitives - add the path
                    String itemType = getType(itemsSchema);
                    formPaths.add(new FormPath(currentPath, "array:" + (itemType != null ? itemType : "any")));
                }
            } else {
                // Leaf property - add the path
                formPaths.add(new FormPath(currentPath, currentType));
            }
        }

        return formPaths;
    }

    /**
     * Determines the effective type of a JSON Schema property, considering format hints.
     * For example, a string with format "date" returns "date" instead of "string".
     */
    private String getEffectiveType(JsonNode schema) {
        if (schema == null) {
            return "any";
        }

        String type = getType(schema);
        if (type == null) {
            return "any";
        }

        // Check for format hints that provide more specific type info
        if (type.equals("string") && schema.has("format")) {
            String format = schema.get("format").asText();
            // Common date/time formats
            if ("date".equals(format) || "date-time".equals(format) || "time".equals(format)) {
                return format;
            }
        }

        return type;
    }

    private String getType(JsonNode schema) {
        if (schema == null || !schema.has("type")) {
            return null;
        }
        return schema.get("type").asText();
    }
}
