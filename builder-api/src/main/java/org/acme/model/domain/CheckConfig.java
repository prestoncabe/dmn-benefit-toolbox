package org.acme.model.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CheckConfig {
    private String checkId;
    // original checkId of the check this was cloned from
    private String sourceCheckId;
    private String checkName;
    private String checkVersion;
    private String checkModule;
    private Map<String, Object> parameters;
    // parameter name -> dotted path within the submitted situation
    private Map<String, String> parameterBindings;
    // evaluation endpoint url for library checks
    private String evaluationUrl;
    private JsonNode inputDefinition;
    private List<ParameterDefinition> parameterDefinitions;
    // optional alias name for this check instance
    private String aliasName;

    public CheckConfig() {
    }

    public CheckConfig(
        String checkId,
        String sourceCheckId,
        String checkName,
        String checkVersion,
        String checkModule,
        String evaluationUrl,
        JsonNode inputDefinition,
        List<ParameterDefinition> parameterDefinitions,
        Map<String, Object> parameters
    ) {
        this.checkId = checkId;
        this.sourceCheckId = sourceCheckId;
        this.checkName = checkName;
        this.checkVersion = checkVersion;
        this.checkModule = checkModule;
        this.evaluationUrl = evaluationUrl;
        this.inputDefinition = inputDefinition;
        this.parameterDefinitions = parameterDefinitions;
        this.parameters = parameters;
    }

    public String getCheckId() {
        return checkId;
    }

    public void setCheckId(String checkId) {
        this.checkId = checkId;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public Map<String, String> getParameterBindings() {
        return parameterBindings;
    }

    public void setParameterBindings(Map<String, String> parameterBindings) {
        this.parameterBindings = parameterBindings;
    }

    public String getCheckName() {
        return checkName;
    }

    public void setCheckName(String checkName) {
        this.checkName = checkName;
    }

    public String getEvaluationUrl() {
        return evaluationUrl;
    }

    public void setEvaluationUrl(String libraryCheckEvaluationUrl) {
        this.evaluationUrl = libraryCheckEvaluationUrl;
    }

    public JsonNode getInputDefinition() {
        return inputDefinition;
    }

    public void setInputDefinition(JsonNode inputDefinition) {
        this.inputDefinition = inputDefinition;
    }

    public List<ParameterDefinition> getParameterDefinitions() {
        return parameterDefinitions;
    }

    public void setParameterDefinitions(List<ParameterDefinition> parameterDefinitions) {
        this.parameterDefinitions = parameterDefinitions;
    }

    public String getCheckVersion() {
        return checkVersion;
    }

    public void setCheckVersion(String checkVersion) {
        this.checkVersion = checkVersion;
    }

    public String getCheckModule() {
        return checkModule;
    }

    public void setCheckModule(String checkModule) {
        this.checkModule = checkModule;
    }

    public String getSourceCheckId() {
        return sourceCheckId;
    }

    public void setSourceCheckId(String sourceCheckId) {
        this.sourceCheckId = sourceCheckId;
    }

    public String getAliasName() {
        return aliasName;
    }

    public void setAliasName(String aliasName) {
        this.aliasName = aliasName;
    }
}
