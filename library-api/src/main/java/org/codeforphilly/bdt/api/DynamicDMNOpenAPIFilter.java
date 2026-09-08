package org.codeforphilly.bdt.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.examples.Example;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;

import jakarta.enterprise.inject.spi.CDI;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * OpenAPI filter that dynamically generates individual endpoints for each DMN model.
 * This creates specific paths like /api/v1/checks/age/PersonMinAge instead of a single
 * parameterized path, making the API more discoverable and providing proper type examples.
 *
 * Note: This is registered via mp.openapi.filter in application.properties, not as a CDI bean.
 */
public class DynamicDMNOpenAPIFilter implements OASFilter {
    private static final Logger LOG = Logger.getLogger(DynamicDMNOpenAPIFilter.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ModelRegistry modelRegistry;
    private DMNSchemaResolver schemaResolver;

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        LOG.info("Filtering OpenAPI spec to add individual DMN decision service endpoints");

        // Get dependencies from CDI since this is not a CDI bean
        if (modelRegistry == null) {
            modelRegistry = CDI.current().select(ModelRegistry.class).get();
        }
        if (schemaResolver == null) {
            schemaResolver = CDI.current().select(DMNSchemaResolver.class).get();
        }

        // Ensure components exist
        if (openAPI.getComponents() == null) {
            openAPI.setComponents(OASFactory.createComponents());
        }

        // Get all discovered DMN models
        Map<String, ModelInfo> allModels = modelRegistry.getAllModels();
        LOG.info("Discovered " + allModels.size() + " DMN models");

        // Filter to only models that expose {ModelName}Service
        Map<String, ModelInfo> exposedModels = new java.util.LinkedHashMap<>();
        for (ModelInfo model : allModels.values()) {
            String expectedServiceName = model.getModelName() + "Service";
            if (model.getDecisionServices().contains(expectedServiceName)) {
                exposedModels.put(model.getModelName(), model);
            } else {
                LOG.fine("Skipping model " + model.getModelName() +
                        " - does not have expected service: " + expectedServiceName +
                        " (has: " + model.getDecisionServices() + ")");
            }
        }

        LOG.info("Generating OpenAPI paths for " + exposedModels.size() + " DMN models with {ModelName}Service");

        // Add all DMN schemas from dmnDefinitions.json to OpenAPI components (only for exposed models)
        addDMNSchemas(openAPI, exposedModels);

        // Remove the generic parameterized path if it exists
        if (openAPI.getPaths() != null) {
            openAPI.getPaths().removePathItem("/api/v1/{path}");
        }

        // Add individual paths for each exposed model
        for (ModelInfo model : exposedModels.values()) {
            addPathForModel(openAPI, model);
        }

        LOG.info("OpenAPI spec filtering complete. Total paths: " +
                 (openAPI.getPaths() != null ? openAPI.getPaths().getPathItems().size() : 0));
    }

    /**
     * Add only the DMN schemas that are actually used by the API endpoints.
     * This keeps the schema list clean by excluding internal wrapper types.
     */
    private void addDMNSchemas(OpenAPI openAPI, Map<String, ModelInfo> exposedModels) {
        Set<String> usedSchemas = new HashSet<>();

        // Collect all schema keys that will be referenced by endpoints
        for (ModelInfo model : exposedModels.values()) {
            // We know all exposed models have {ModelName}Service
            String serviceName = model.getModelName() + "Service";

            String inputRef = schemaResolver.findInputSchemaRef(model.getModelName(), serviceName);
            String outputRef = schemaResolver.findOutputSchemaRef(model.getModelName(), serviceName);

            if (inputRef != null) {
                collectReferencedSchemas(inputRef, usedSchemas);
            }
            if (outputRef != null) {
                collectReferencedSchemas(outputRef, usedSchemas);
            }
        }

        // Add all the used schemas (including wrapper types that are referenced)
        int addedCount = 0;
        for (String schemaKey : usedSchemas) {
            com.fasterxml.jackson.databind.JsonNode schemaNode = schemaResolver.getSchema(schemaKey);
            if (schemaNode != null) {
                Schema schema = convertJsonNodeToSchema(schemaNode);
                openAPI.getComponents().addSchema(schemaKey, schema);
                addedCount++;
            }
        }

        LOG.info("Added " + addedCount + " DMN schemas to OpenAPI components (only actually referenced schemas)");
    }

    /**
     * Recursively collect all schemas referenced by the given schema.
     */
    private void collectReferencedSchemas(String schemaRef, Set<String> collectedSchemas) {
        if (schemaRef == null) {
            return;
        }

        // Extract schema key from reference
        String schemaKey = null;
        if (schemaRef.startsWith("#/components/schemas/")) {
            schemaKey = schemaRef.substring("#/components/schemas/".length());
        } else if (schemaRef.startsWith("#/definitions/")) {
            schemaKey = schemaRef.substring("#/definitions/".length());
        }

        if (schemaKey == null || collectedSchemas.contains(schemaKey)) {
            return; // Already processed or invalid
        }

        collectedSchemas.add(schemaKey);

        // Get the schema and find any nested references
        com.fasterxml.jackson.databind.JsonNode schemaNode = schemaResolver.getSchema(schemaKey);
        if (schemaNode != null) {
            collectReferencesFromNode(schemaNode, collectedSchemas);
        }
    }

    /**
     * Recursively find all $ref references in a JSON node.
     */
    private void collectReferencesFromNode(com.fasterxml.jackson.databind.JsonNode node, Set<String> collectedSchemas) {
        if (node.isObject()) {
            // Check for $ref
            if (node.has("$ref")) {
                String ref = node.get("$ref").asText();
                collectReferencedSchemas(ref, collectedSchemas);
            }

            // Recurse into all fields
            node.properties().forEach(entry -> {
                collectReferencesFromNode(entry.getValue(), collectedSchemas);
            });
        } else if (node.isArray()) {
            // Recurse into array elements
            node.forEach(element -> {
                collectReferencesFromNode(element, collectedSchemas);
            });
        }
    }

    /**
     * Convert a Jackson JsonNode to an OpenAPI Schema object.
     * This handles the basic schema properties we need for DMN types.
     */
    private Schema convertJsonNodeToSchema(com.fasterxml.jackson.databind.JsonNode node) {
        Schema schema = OASFactory.createSchema();

        // Handle type
        if (node.has("type")) {
            String type = node.get("type").asText();
            schema.type(List.of(Schema.SchemaType.valueOf(type.toUpperCase())));
        }

        // Handle format
        if (node.has("format")) {
            schema.format(node.get("format").asText());
        }

        // Handle description
        if (node.has("description")) {
            schema.description(node.get("description").asText());
        }

        // Handle $ref (update to use #/components/schemas/ instead of #/definitions/)
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            if (ref.startsWith("#/definitions/")) {
                ref = "#/components/schemas/" + ref.substring("#/definitions/".length());
            }
            schema.ref(ref);
        }

        // Handle properties
        if (node.has("properties")) {
            com.fasterxml.jackson.databind.JsonNode properties = node.get("properties");
            properties.properties().forEach(entry -> {
                Schema propSchema = convertJsonNodeToSchema(entry.getValue());
                schema.addProperty(entry.getKey(), propSchema);
            });
        }

        // Handle items (for arrays)
        if (node.has("items")) {
            schema.items(convertJsonNodeToSchema(node.get("items")));
        }

        // Handle required fields
        if (node.has("required")) {
            com.fasterxml.jackson.databind.JsonNode required = node.get("required");
            if (required.isArray()) {
                required.forEach(field -> schema.addRequired(field.asText()));
            }
        }

        // Handle enum values
        if (node.has("enum")) {
            com.fasterxml.jackson.databind.JsonNode enumNode = node.get("enum");
            if (enumNode.isArray()) {
                enumNode.forEach(value -> schema.addEnumeration(value.asText()));
            }
        }

        return schema;
    }

    private void addPathForModel(OpenAPI openAPI, ModelInfo model) {
        String path = "/api/v1/" + model.getPath();

        // Service name follows convention: {ModelName}Service
        String serviceName = model.getModelName() + "Service";

        // Find schema references
        String inputRef = schemaResolver.findInputSchemaRef(model.getModelName(), serviceName);
        String outputRef = schemaResolver.findOutputSchemaRef(model.getModelName(), serviceName);

        // Create the path item with POST operation
        PathItem pathItem = OASFactory.createPathItem();
        pathItem.POST(createPostOperation(model, serviceName, inputRef, outputRef));

        // Add to OpenAPI spec
        openAPI.getPaths().addPathItem(path, pathItem);

        LOG.fine("Added path: " + path + " (service: " + serviceName + ")");
    }

    private Operation createPostOperation(ModelInfo model, String serviceName,
                                         String inputRef, String outputRef) {
        Operation operation = OASFactory.createOperation();

        // Basic operation metadata
        operation.operationId(serviceName);
        operation.summary("Execute " + model.getModelName() + " decision");
        operation.description(model.getDescription());
        operation.addTag(model.getCategory());

        if (!model.getBenefitChecks().isEmpty()) {
            List<Map<String, Object>> checks = model.getBenefitChecks().stream().map(check -> {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("operationId", check.operationId());
                metadata.put("alias", check.alias());
                metadata.put("parameters", check.parameters());
                metadata.put("parameterBindings", check.parameterBindings());
                return metadata;
            }).toList();
            operation.addExtension("x-bdt-benefit", Map.of("checks", checks));
        }

        // Request body
        operation.requestBody(createRequestBody(model, serviceName, inputRef));

        // Responses
        operation.responses(createResponses(model, outputRef));

        return operation;
    }

    private RequestBody createRequestBody(ModelInfo model, String serviceName, String inputRef) {
        RequestBody requestBody = OASFactory.createRequestBody();
        requestBody.description("Input data for " + serviceName);
        requestBody.required(true);

        Content content = OASFactory.createContent();
        MediaType mediaType = OASFactory.createMediaType();

        // Set schema reference if available
        if (inputRef != null) {
            Schema schema = OASFactory.createSchema();
            schema.ref(inputRef);
            mediaType.schema(schema);

            // Generate example from schema
            Map<String, Object> exampleData = schemaResolver.generateExample(inputRef);
            if (!exampleData.isEmpty()) {
                Example example = OASFactory.createExample();
                example.value(convertToOpenAPIValue(exampleData));
                mediaType.addExample("Example request", example);
            }
        } else {
            // Fallback to generic schema
            mediaType.schema(createFallbackInputSchema());
            mediaType.addExample("Example request", createFallbackExample());
        }

        content.addMediaType("application/json", mediaType);
        requestBody.content(content);

        return requestBody;
    }

    private APIResponses createResponses(ModelInfo model, String outputRef) {
        APIResponses responses = OASFactory.createAPIResponses();

        // 200 Success response
        APIResponse successResponse = OASFactory.createAPIResponse();
        successResponse.description("Decision evaluated successfully - returns full DMN context including inputs and outputs");

        Content successContent = OASFactory.createContent();
        MediaType successMediaType = OASFactory.createMediaType();

        String serviceName = model.getModelName() + "Service";
        String inputRef = schemaResolver.findInputSchemaRef(model.getModelName(), serviceName);

        // Create a composite schema that represents the full DMN context (inputs + outputs)
        Schema contextSchema = createDMNContextSchema(model, inputRef, outputRef);
        successMediaType.schema(contextSchema);

        // Generate example from the composite schema
        Map<String, Object> exampleData = generateContextExample(inputRef, outputRef, model);
        if (!exampleData.isEmpty()) {
            Example example = OASFactory.createExample();
            example.value(convertToOpenAPIValue(exampleData));
            successMediaType.addExample("Example response", example);
        }

        successContent.addMediaType("application/json", successMediaType);
        successResponse.content(successContent);
        responses.addAPIResponse("200", successResponse);

        // 400 Bad Request
        APIResponse badRequestResponse = OASFactory.createAPIResponse();
        badRequestResponse.description("Invalid input or decision evaluation error");
        responses.addAPIResponse("400", badRequestResponse);

        // 404 Not Found
        APIResponse notFoundResponse = OASFactory.createAPIResponse();
        notFoundResponse.description("Model or decision service not found");
        responses.addAPIResponse("404", notFoundResponse);

        return responses;
    }

    private Schema createFallbackInputSchema() {
        Schema schema = OASFactory.createSchema();
        schema.type(List.of(Schema.SchemaType.OBJECT));
        schema.description("Generic DMN input");

        // Add situation property
        Schema situationSchema = OASFactory.createSchema();
        situationSchema.type(List.of(Schema.SchemaType.OBJECT));
        situationSchema.description("Situation context");
        schema.addProperty("situation", situationSchema);

        // Add parameters property
        Schema parametersSchema = OASFactory.createSchema();
        parametersSchema.type(List.of(Schema.SchemaType.OBJECT));
        parametersSchema.description("Decision parameters");
        schema.addProperty("parameters", parametersSchema);

        return schema;
    }

    private Example createFallbackExample() {
        Example example = OASFactory.createExample();
        Map<String, Object> exampleData = schemaResolver.generateExample(null);
        example.value(convertToOpenAPIValue(exampleData));
        return example;
    }

    /**
     * Helper method to get schema node from a ref like "#/components/schemas/SchemaKey"
     */
    private com.fasterxml.jackson.databind.JsonNode getSchemaByRef(String ref) {
        if (ref == null) {
            return null;
        }

        String schemaKey = null;
        if (ref.startsWith("#/components/schemas/")) {
            schemaKey = ref.substring("#/components/schemas/".length());
        } else if (ref.startsWith("#/definitions/")) {
            schemaKey = ref.substring("#/definitions/".length());
        }

        return schemaKey != null ? schemaResolver.getSchema(schemaKey) : null;
    }

    /**
     * Create a schema representing the full DMN context (inputs + outputs).
     * This matches what DynamicDMNResource returns via result.getDmnContext().
     */
    private Schema createDMNContextSchema(ModelInfo model, String inputRef, String outputRef) {
        Schema contextSchema = OASFactory.createSchema();
        contextSchema.type(List.of(Schema.SchemaType.OBJECT));
        contextSchema.description("DMN evaluation context containing all input and output variables");

        // Get the input schema to extract its properties (situation, parameters, etc.)
        if (inputRef != null) {
            com.fasterxml.jackson.databind.JsonNode inputSchemaNode = getSchemaByRef(inputRef);
            if (inputSchemaNode != null && inputSchemaNode.has("properties")) {
                com.fasterxml.jackson.databind.JsonNode inputProps = inputSchemaNode.get("properties");
                inputProps.properties().forEach(entry -> {
                    Schema propSchema = convertJsonNodeToSchema(entry.getValue());
                    contextSchema.addProperty(entry.getKey(), propSchema);
                });
            }
        }

        // Add the output decision(s) to the context
        if (outputRef != null) {
            com.fasterxml.jackson.databind.JsonNode outputSchemaNode = getSchemaByRef(outputRef);
            if (outputSchemaNode != null) {
                // Check if output schema has properties (multiple output decisions like checks + isEligible)
                if (outputSchemaNode.has("properties")) {
                    // Multiple outputs: add each property directly to context
                    // This handles Decision Services with multiple output decisions (e.g., PhlHomesteadExemption)
                    com.fasterxml.jackson.databind.JsonNode outputProps = outputSchemaNode.get("properties");
                    outputProps.properties().forEach(entry -> {
                        Schema propSchema = convertJsonNodeToSchema(entry.getValue());
                        contextSchema.addProperty(entry.getKey(), propSchema);
                    });
                } else {
                    // Single output: wrap under decision name
                    // This handles Decision Services with one output decision (e.g., PersonMinAge)
                    Schema outputSchema = convertJsonNodeToSchema(outputSchemaNode);
                    String serviceName = model.getModelName() + "Service";
                    String decisionName = getOutputDecisionName(model, serviceName);
                    contextSchema.addProperty(decisionName, outputSchema);
                }
            }
        }

        return contextSchema;
    }

    /**
     * Read DMN file content from classpath.
     * Works in both dev mode (filesystem) and production (JAR packaging).
     * Pattern mirrors ModelRegistry.scanDMNFiles() for consistency.
     *
     * @param relativePath path relative to classpath root (e.g., "checks/age/PersonMinAge")
     * @return DMN file content as string, or null if not found
     */
    private String readDMNFileContent(String relativePath) {
        try {
            String resourcePath = relativePath + ".dmn";
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

            try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    LOG.warning("DMN file not found on classpath: " + resourcePath);
                    return null;
                }
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOG.warning("Error reading DMN file " + relativePath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the actual output decision name from the DMN file.
     * Parses the decision service definition to find the output decision's name attribute.
     */
    private String getOutputDecisionName(ModelInfo model, String serviceName) {
        try {
            // Read the DMN file from classpath
            String dmnContent = readDMNFileContent(model.getPath());

            if (dmnContent == null) {
                LOG.warning("DMN file not found for model: " + model.getModelName() + " at path: " + model.getPath());
                return "result"; // fallback
            }

            // Find the decision service with the given name
            String servicePattern = "name=\"" + serviceName + "\"";
            int serviceIndex = dmnContent.indexOf(servicePattern);

            if (serviceIndex == -1) {
                LOG.warning("Decision service not found in DMN: " + serviceName);
                return "result";
            }

            // Find the outputDecision element within this decision service
            // Look for <dmn:outputDecision href="#DECISION_ID"/>
            int serviceStart = dmnContent.lastIndexOf("<dmn:decisionService", serviceIndex);
            int serviceEnd = dmnContent.indexOf("</dmn:decisionService>", serviceIndex);

            if (serviceStart == -1 || serviceEnd == -1) {
                return "result";
            }

            String serviceSection = dmnContent.substring(serviceStart, serviceEnd);
            String outputDecisionPattern = "<dmn:outputDecision href=\"#";
            int outputIndex = serviceSection.indexOf(outputDecisionPattern);

            if (outputIndex == -1) {
                return "result";
            }

            // Extract the decision ID
            int idStart = outputIndex + outputDecisionPattern.length();
            int idEnd = serviceSection.indexOf("\"", idStart);
            String decisionId = serviceSection.substring(idStart, idEnd);

            // Now find the decision element with this ID and get its name
            String decisionPattern = "id=\"" + decisionId + "\"";
            int decisionIndex = dmnContent.indexOf(decisionPattern);

            if (decisionIndex == -1) {
                return "result";
            }

            // Find the name attribute in this decision element
            int decisionStart = dmnContent.lastIndexOf("<dmn:decision", decisionIndex);
            int decisionEnd = dmnContent.indexOf(">", decisionIndex);
            String decisionElement = dmnContent.substring(decisionStart, decisionEnd);

            String namePattern = "name=\"";
            int nameIndex = decisionElement.indexOf(namePattern);

            if (nameIndex == -1) {
                return "result";
            }

            int nameStart = nameIndex + namePattern.length();
            int nameEnd = decisionElement.indexOf("\"", nameStart);
            String decisionName = decisionElement.substring(nameStart, nameEnd);

            LOG.fine("Found output decision name for " + serviceName + ": " + decisionName);
            return decisionName;

        } catch (Exception e) {
            LOG.warning("Error parsing DMN file for " + model.getModelName() + ": " + e.getMessage());
            return "result"; // fallback
        }
    }

    /**
     * Generate an example combining input and output examples.
     */
    private Map<String, Object> generateContextExample(String inputRef, String outputRef, ModelInfo model) {
        Map<String, Object> contextExample = new java.util.LinkedHashMap<>();

        // Add input examples
        if (inputRef != null) {
            Map<String, Object> inputExample = schemaResolver.generateExample(inputRef);
            contextExample.putAll(inputExample);
        }

        // Add output example
        if (outputRef != null) {
            // Get the output schema to check if it has multiple properties (multiple output decisions)
            com.fasterxml.jackson.databind.JsonNode outputSchemaNode = getSchemaByRef(outputRef);

            if (outputSchemaNode != null) {
                // Check if output schema has properties (multiple output decisions like checks + isEligible)
                if (outputSchemaNode.has("properties")) {
                    // Multiple outputs: generate examples for each property directly
                    com.fasterxml.jackson.databind.JsonNode outputProps = outputSchemaNode.get("properties");
                    outputProps.properties().forEach(entry -> {
                        String propName = entry.getKey();
                        com.fasterxml.jackson.databind.JsonNode propNode = entry.getValue();

                        // Generate example based on property type
                        Object exampleValue = generateExampleForProperty(propNode);
                        if (exampleValue != null) {
                            contextExample.put(propName, exampleValue);
                        }
                    });
                } else {
                    // Single output: wrap under decision name
                    String serviceName = model.getModelName() + "Service";
                    String decisionName = getOutputDecisionName(model, serviceName);

                    // Check if it's a primitive type (boolean, number, string) or complex type
                    if (outputSchemaNode.has("type")) {
                        String type = outputSchemaNode.get("type").asText();

                        // For primitive types, generate a simple example value
                        Object exampleValue;
                        switch (type) {
                            case "boolean":
                                exampleValue = true;
                                break;
                            case "number":
                            case "integer":
                                exampleValue = 0;
                                break;
                            case "string":
                                exampleValue = "example";
                                break;
                            default:
                                // For complex types, use the schema resolver
                                Map<String, Object> outputExample = schemaResolver.generateExample(outputRef);
                                exampleValue = outputExample.isEmpty() ? null : outputExample;
                        }

                        if (exampleValue != null) {
                            contextExample.put(decisionName, exampleValue);
                        }
                    } else {
                        // No type specified, try to generate example
                        Map<String, Object> outputExample = schemaResolver.generateExample(outputRef);
                        if (!outputExample.isEmpty()) {
                            contextExample.put(decisionName, outputExample);
                        }
                    }
                }
            }
        }

        return contextExample;
    }

    /**
     * Generate an example value for a property node.
     * Handles primitive types and complex types.
     */
    private Object generateExampleForProperty(com.fasterxml.jackson.databind.JsonNode propNode) {
        if (propNode.has("type")) {
            String type = propNode.get("type").asText();
            switch (type) {
                case "boolean":
                    return true;
                case "number":
                case "integer":
                    return 0;
                case "string":
                    return "example";
                case "object":
                    // For objects, try to generate example from properties
                    Map<String, Object> objectExample = new java.util.LinkedHashMap<>();
                    if (propNode.has("properties")) {
                        com.fasterxml.jackson.databind.JsonNode properties = propNode.get("properties");
                        properties.properties().forEach(entry -> {
                            Object value = generateExampleForProperty(entry.getValue());
                            if (value != null) {
                                objectExample.put(entry.getKey(), value);
                            }
                        });
                    }
                    return objectExample.isEmpty() ? null : objectExample;
                default:
                    return null;
            }
        } else if (propNode.has("$ref")) {
            // For references, use the schema resolver
            String ref = propNode.get("$ref").asText();
            if (ref.startsWith("#/definitions/")) {
                ref = "#/components/schemas/" + ref.substring("#/definitions/".length());
            }
            Map<String, Object> refExample = schemaResolver.generateExample(ref);
            return refExample.isEmpty() ? null : refExample;
        } else if (propNode.has("x-dmn-type")) {
            // Handle DMN-specific types that don't have OpenAPI "type" field
            // These are typically dynamic contexts or complex FEEL types
            String dmnType = propNode.get("x-dmn-type").asText();
            if (dmnType.equals("FEEL:context") || dmnType.startsWith("FEEL:")) {
                // Return a placeholder example for dynamic contexts
                Map<String, Object> placeholderExample = new java.util.LinkedHashMap<>();
                placeholderExample.put("example-check", true);
                return placeholderExample;
            }
        } else if (propNode.isObject() && propNode.size() == 0) {
            // Handle empty object definition like "checks: {}"
            // This represents a dynamically structured object, so return a placeholder
            Map<String, Object> placeholderExample = new java.util.LinkedHashMap<>();
            placeholderExample.put("example-check", true);
            return placeholderExample;
        }
        return null;
    }

    /**
     * Convert a Map to a format suitable for OpenAPI example values.
     * The OpenAPI library expects examples as plain objects, but we need to ensure
     * proper serialization.
     */
    private Object convertToOpenAPIValue(Object value) {
        // The OpenAPI library accepts Map/List/primitives directly
        // Just ensure complex objects are properly structured
        if (value instanceof Map || value instanceof java.util.List ||
            value instanceof String || value instanceof Number ||
            value instanceof Boolean) {
            return value;
        }

        // For other types, try to convert via JSON
        try {
            String json = OBJECT_MAPPER.writeValueAsString(value);
            return OBJECT_MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            LOG.warning("Failed to convert example value: " + e.getMessage());
            return value;
        }
    }
}
