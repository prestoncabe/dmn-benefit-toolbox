package org.codeforphilly.bdt.builder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.codeforphilly.bdt.builder.model.domain.EligibilityCheck;
import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.NextSteps;
import org.codeforphilly.bdt.builder.persistence.EligibilityCheckRepository;
import org.codeforphilly.bdt.builder.persistence.ScreenerRepository;
import org.codeforphilly.bdt.builder.persistence.StorageService;
import org.codeforphilly.bdt.builder.persistence.impl.EligibilityCheckRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExampleScreenerImportServiceTest {

    private static final String USER_ID = "new-user";
    private List<EligibilityCheck> referencedSeedChecks;
    private EligibilityCheck seedCheck;
    private String seedExampleSourceId;

    private final ObjectMapper mapper = new ObjectMapper();
    private final EligibilityCheckRepositoryImpl ids = new EligibilityCheckRepositoryImpl();
    private final EligibilityCheckRepository checkRepository = mock(EligibilityCheckRepository.class);
    private final ScreenerRepository screenerRepository = mock(ScreenerRepository.class);
    private final StorageService storageService = mock(StorageService.class);
    private final ExampleScreenerImportService service =
            new ExampleScreenerImportService(screenerRepository, checkRepository, storageService);

    @BeforeEach
    void setUp() throws Exception {
        referencedSeedChecks = findReferencedSeedChecks();
        assumeTrue(!referencedSeedChecks.isEmpty(), "The example screener's benefits use no custom checks");
        seedCheck = referencedSeedChecks.get(0);
        seedExampleSourceId = exampleSourceId(seedCheck);
        when(checkRepository.getWorkingId(any())).thenAnswer(invocation -> ids.getWorkingId(invocation.getArgument(0)));
        when(checkRepository.getPublishedId(any(), anyString()))
                .thenAnswer(invocation -> ids.getPublishedId(invocation.getArgument(0), invocation.getArgument(1)));
        when(checkRepository.newWorkingId()).thenAnswer(invocation -> ids.newWorkingId());
        when(screenerRepository.saveNewWorkingScreener(any())).thenReturn("screener-1");
        when(storageService.getCheckDmnModelPath(anyString())).thenAnswer(invocation -> "check/" + invocation.getArgument(0));
    }

    // Seed checks exported from an account that imported the example carry its identity
    private String exampleSourceId(EligibilityCheck check) {
        return check.getExampleSourceId() != null ? check.getExampleSourceId() : ids.getWorkingId(check);
    }

    // The custom checks the benefits use, in order, as the seed stores them (working or published)
    private List<EligibilityCheck> findReferencedSeedChecks() throws Exception {
        JsonNode manifest = readResource("seed-data/example-screener/manifest.json");
        Map<String, JsonNode> seedChecks = new HashMap<>();
        for (String collection : List.of("workingCustomChecks", "publishedCustomChecks")) {
            for (JsonNode path : manifest.path(collection)) {
                JsonNode check = readResource(path.asText());
                seedChecks.put(check.path("id").asText(), check);
            }
        }
        List<EligibilityCheck> referenced = new ArrayList<>();
        for (JsonNode screener : manifest.path("screeners")) {
            for (JsonNode benefitPath : screener.path("benefits")) {
                for (JsonNode checkConfig : readResource(benefitPath.asText()).path("checks")) {
                    JsonNode check = seedChecks.get(checkConfig.path("sourceCheckId").asText());
                    if (check != null) {
                        referenced.add(mapper.treeToValue(check, EligibilityCheck.class));
                    }
                }
            }
        }
        return referenced;
    }

    private JsonNode readResource(String path) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            return mapper.readTree(stream);
        }
    }

    @Test
    void preservesNextStepsWhenImportingTheExampleScreener() throws Exception {
        Map<String, NextSteps> expected = new HashMap<>();
        for (JsonNode screener : readResource("seed-data/example-screener/manifest.json").path("screeners")) {
            for (JsonNode path : screener.path("benefits")) {
                Benefit benefit = mapper.treeToValue(readResource(path.asText()), Benefit.class);
                expected.put(benefit.getName(), benefit.getNextSteps());
            }
        }
        service.importForUser(USER_ID);
        ArgumentCaptor<Benefit> saved = ArgumentCaptor.forClass(Benefit.class);
        verify(screenerRepository, atLeastOnce()).saveNewCustomBenefit(anyString(), saved.capture());
        for (Benefit benefit : saved.getAllValues()) {
            assertEquals(expected.get(benefit.getName()), benefit.getNextSteps());
        }
    }

    @Test
    void exampleBenefitsPassScreenerImportValidation() throws Exception {
        // Seed benefits are written without validation, so check them here: a screener that
        // starts from the example must still export and import again.
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (JsonNode screener : readResource("seed-data/example-screener/manifest.json").path("screeners")) {
                for (JsonNode path : screener.path("benefits")) {
                    Benefit benefit = mapper.treeToValue(readResource(path.asText()), Benefit.class);
                    assertEquals(Set.of(), validator.validate(benefit), path.asText());
                }
            }
        }
    }

    @Test
    void importGivesChecksIdsIndependentOfTheirNames() throws Exception {
        service.importForUser(USER_ID);

        ArgumentCaptor<EligibilityCheck> saved = ArgumentCaptor.forClass(EligibilityCheck.class);
        verify(checkRepository, atLeastOnce()).saveNewWorkingCustomCheck(saved.capture());
        EligibilityCheck imported = saved.getAllValues().stream()
                .filter(check -> seedExampleSourceId.equals(check.getExampleSourceId()))
                .findFirst().orElseThrow();
        assertFalse(imported.getId().contains(imported.getName()));
        assertEquals(USER_ID, imported.getOwnerId());
    }

    @Test
    void importKeepsTheExampleIdentityOfAReExportedSeedCheck() throws Exception {
        assumeTrue(seedCheck.getExampleSourceId() != null, "The seed check was not exported from an imported example");
        assertFalse(seedCheck.getExampleSourceId().equals(ids.getWorkingId(seedCheck)));

        service.importForUser(USER_ID);

        ArgumentCaptor<EligibilityCheck> saved = ArgumentCaptor.forClass(EligibilityCheck.class);
        verify(checkRepository, atLeastOnce()).saveNewWorkingCustomCheck(saved.capture());
        assertTrue(saved.getAllValues().stream()
                .anyMatch(check -> seedCheck.getExampleSourceId().equals(check.getExampleSourceId())));
    }

    @Test
    void reimportKeepsARenamedCheckAndPublishesUnderItsId() throws Exception {
        EligibilityCheck renamed = new EligibilityCheck("renamed", seedCheck.getModule(), "", List.of(), USER_ID);
        renamed.setId("W-kept");
        renamed.setExampleSourceId(seedExampleSourceId);
        when(checkRepository.getAllWorkingCustomChecks(USER_ID)).thenReturn(List.of(renamed));
        Set<String> otherSourceIds = referencedSeedChecks.stream()
                .map(this::exampleSourceId)
                .filter(sourceId -> !sourceId.equals(seedExampleSourceId))
                .collect(Collectors.toSet());
        assumeTrue(!otherSourceIds.isEmpty(), "The example screener uses only one custom check");

        service.importForUser(USER_ID);

        verify(checkRepository, never()).updateWorkingCustomCheck(any());
        // Other example checks are still imported; only the renamed one is kept as it is
        ArgumentCaptor<EligibilityCheck> saved = ArgumentCaptor.forClass(EligibilityCheck.class);
        verify(checkRepository, atLeastOnce()).saveNewWorkingCustomCheck(saved.capture());
        assertEquals(otherSourceIds, saved.getAllValues().stream()
                .map(EligibilityCheck::getExampleSourceId)
                .collect(Collectors.toSet()));
        ArgumentCaptor<EligibilityCheck> published = ArgumentCaptor.forClass(EligibilityCheck.class);
        verify(checkRepository, atLeastOnce()).saveNewPublishedCustomCheck(published.capture());
        assertTrue(published.getAllValues().stream().anyMatch(check -> check.getId().startsWith("P-kept-")));
    }

    @Test
    void importReservesTheNamesOfTheChecksItCreates() throws Exception {
        service.importForUser(USER_ID);

        verify(checkRepository).reserveCheckName(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(seedCheck.getModule()), org.mockito.ArgumentMatchers.eq(seedCheck.getName()),
                org.mockito.ArgumentMatchers.startsWith("W-"));
    }

    @Test
    void importRefusesToDuplicateANameUsedByAnotherCheck() {
        EligibilityCheck other = new EligibilityCheck(seedCheck.getName(), seedCheck.getModule(), "", List.of(), USER_ID);
        other.setId("W-unrelated");
        when(checkRepository.getAllWorkingCustomChecks(USER_ID)).thenReturn(List.of(other));

        assertThrows(IllegalStateException.class, () -> service.importForUser(USER_ID));
    }
}
