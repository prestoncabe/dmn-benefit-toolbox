package org.acme.model.dto.CustomBenefit;

import jakarta.validation.constraints.NotBlank;

public record ImportLibraryBenefitRequest(
    @NotBlank(message = "Library Benefit id must be provided.") String benefitId
) {}
