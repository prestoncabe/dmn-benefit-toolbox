package org.codeforphilly.bdt.api;

import java.util.List;
import java.util.Objects;

/**
 * Metadata about a DMN model discovered at runtime.
 */
public class ModelInfo {
    private final String namespace;
    private final String modelName;
    private final List<String> decisionServices;
    private final List<String> decisions;
    private final String path;
    private final String dmnDescription;
    private final List<BenefitCheckInfo> benefitChecks;

    public ModelInfo(String namespace, String modelName, List<String> decisionServices, List<String> decisions,
                     String path, String dmnDescription, List<BenefitCheckInfo> benefitChecks) {
        this.namespace = namespace;
        this.modelName = modelName;
        this.decisionServices = decisionServices;
        this.decisions = decisions;
        this.path = path;
        this.dmnDescription = dmnDescription;
        this.benefitChecks = benefitChecks;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getModelName() {
        return modelName;
    }

    public List<String> getDecisionServices() {
        return decisionServices;
    }

    public List<String> getDecisions() {
        return decisions;
    }

    public String getPath() {
        return path;
    }

    /**
     * Extract category/tag from the path for OpenAPI grouping.
     * Examples:
     * - "checks/age/PersonMinAge" -> "Age Checks"
     * - "benefits/pa/philadelphia/PhlHomesteadExemption" -> "Benefits"
     */
    public String getCategory() {
        if (path == null || path.isEmpty()) {
            return "DMN Decisions";
        }

        String[] parts = path.split("/");
        if (parts.length == 0) {
            return "DMN Decisions";
        }

        // First part determines the category
        String firstPart = parts[0];
        switch (firstPart) {
            case "checks":
                // For checks, use the second part if available (e.g., "age" -> "Age Checks")
                if (parts.length > 1) {
                    return capitalize(parts[1]) + " Checks";
                }
                return "Checks";
            case "benefits":
                return "Benefits";
            default:
                return capitalize(firstPart);
        }
    }

    /**
     * Get the description for this model from the DMN file.
     * Returns the value from the <dmn:description> element, or null/empty if not present.
     */
    public String getDescription() {
        return dmnDescription;
    }

    public List<BenefitCheckInfo> getBenefitChecks() {
        return benefitChecks;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModelInfo modelInfo = (ModelInfo) o;
        return Objects.equals(namespace, modelInfo.namespace) &&
                Objects.equals(modelName, modelInfo.modelName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, modelName);
    }

    @Override
    public String toString() {
        return "ModelInfo{" +
                "namespace='" + namespace + '\'' +
                ", modelName='" + modelName + '\'' +
                ", decisionServices=" + decisionServices +
                ", decisions=" + decisions +
                ", path='" + path + '\'' +
                ", dmnDescription='" + dmnDescription + '\'' +
                '}';
    }
}
