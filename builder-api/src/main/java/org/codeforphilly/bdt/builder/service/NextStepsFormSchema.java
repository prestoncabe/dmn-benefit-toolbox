package org.codeforphilly.bdt.builder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** Keeps component copies attached to their benefits when an import allocates new IDs. */
final class NextStepsFormSchema {
    private NextStepsFormSchema() {}

    static JsonNode remapBenefits(JsonNode schema, Map<String, String> benefitIds) {
        if (schema == null) return null;
        JsonNode copy = schema.deepCopy();
        remapComponent(copy, benefitIds);
        return copy;
    }

    private static void remapComponent(JsonNode component, Map<String, String> benefitIds) {
        if (!component.isObject()) return;
        if ("next_steps".equals(component.path("type").asText())
                && component.path("benefitContent").isObject()) {
            ObjectNode content = JsonNodeFactory.instance.objectNode();
            component.path("benefitContent").fields().forEachRemaining(entry -> {
                String importedId = benefitIds.get(entry.getKey());
                // Copies for removed benefits have no destination and can be discarded.
                if (importedId != null) content.set(importedId, entry.getValue());
            });
            ((ObjectNode) component).set("benefitContent", content);
        }
        JsonNode children = component.path("components");
        if (children.isArray()) children.forEach(child -> remapComponent(child, benefitIds));
    }
}
