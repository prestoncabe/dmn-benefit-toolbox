package org.codeforphilly.bdt.builder.model.dto.screener;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.EligibilityCheck;
import java.util.List;

/** One portable draft screener, with DMN models embedded in its custom checks. */
public record ScreenerTransfer(String format, int formatVersion, String screenerName,
        JsonNode formSchema, List<@Valid Benefit> benefits, List<EligibilityCheck> customChecks) {
    public static final String FORMAT = "bdt-screener";
    public static final int VERSION = 1;
}
