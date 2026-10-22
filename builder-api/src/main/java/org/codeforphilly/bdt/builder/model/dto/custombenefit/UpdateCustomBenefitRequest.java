package org.codeforphilly.bdt.builder.model.dto.custombenefit;

import org.codeforphilly.bdt.builder.api.validation.AtLeastOneProvided;
import org.codeforphilly.bdt.builder.model.domain.NextSteps;
import jakarta.validation.Valid;

@AtLeastOneProvided(fields = {"name", "description", "nextSteps"})
public record UpdateCustomBenefitRequest(String name, String description, @Valid NextSteps nextSteps) {}
