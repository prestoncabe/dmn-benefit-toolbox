package org.codeforphilly.bdt.api;

import java.util.Map;

/** Metadata for one check invocation in a benefit's DMN {@code checks} context. */
public record BenefitCheckInfo(
    String operationId,
    String alias,
    Map<String, Object> parameters,
    Map<String, String> parameterBindings
) {}
