package org.codeforphilly.bdt.builder.model.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Benefit {
    private String id;
    private String description;
    private String name;
    private List<CheckConfig> checks;
    private String ownerId;
    @Valid
    private NextSteps nextSteps;

    public Benefit() {
    }

    public Benefit(String id, String name, String description, String ownerId, List<CheckConfig> checks) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.ownerId = ownerId;
        this.checks = checks;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<CheckConfig> getChecks() {
        return checks;
    }

    public void setChecks(List<CheckConfig> checks) {
        this.checks = checks;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public NextSteps getNextSteps() {
        return nextSteps;
    }

    public void setNextSteps(NextSteps nextSteps) {
        this.nextSteps = nextSteps;
    }
}
