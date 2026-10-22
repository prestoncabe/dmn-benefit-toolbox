package org.codeforphilly.bdt.builder.model.dto.custombenefit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.codeforphilly.bdt.builder.model.domain.NextSteps;

public record CreateCustomBenefitRequest(
    @NotBlank(message = "Custom Benefit name must be provided.") String name,
    String description,
    @Valid NextSteps nextSteps
) {}
