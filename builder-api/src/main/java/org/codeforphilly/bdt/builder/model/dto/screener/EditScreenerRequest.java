package org.codeforphilly.bdt.builder.model.dto.screener;

import java.util.List;
import org.codeforphilly.bdt.builder.api.validation.AtLeastOneProvided;

@AtLeastOneProvided(fields = {"screenerName", "integrationOrigins"})
public record EditScreenerRequest(String screenerName, List<String> integrationOrigins) {
  public EditScreenerRequest(String screenerName) {
    this(screenerName, null);
  }
}
