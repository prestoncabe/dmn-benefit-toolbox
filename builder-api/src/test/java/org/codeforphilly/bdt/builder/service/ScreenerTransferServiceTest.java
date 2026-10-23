package org.codeforphilly.bdt.builder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.codeforphilly.bdt.builder.model.domain.*;
import org.codeforphilly.bdt.builder.model.dto.screener.ScreenerTransfer;
import org.codeforphilly.bdt.builder.persistence.*;
import org.codeforphilly.bdt.builder.persistence.impl.EligibilityCheckRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScreenerTransferServiceTest {
    private final ScreenerRepository screeners = mock(ScreenerRepository.class);
    // Use the real ID helpers so round trips test the repository's versioned ID format.
    private final EligibilityCheckRepository checks = mock(EligibilityCheckRepositoryImpl.class, CALLS_REAL_METHODS);
    private final StorageService storage = mock(StorageService.class);
    private final LibraryApiService library = mock(LibraryApiService.class);
    private final DmnService dmn = mock(DmnService.class);
    private final ScreenerTransferService service = new ScreenerTransferService(screeners, checks, storage, library, dmn);
    private final ObjectMapper mapper = new ObjectMapper();
    private Screener source;
    private List<Benefit> benefits;
    private EligibilityCheck draft;
    private final com.fasterxml.jackson.databind.JsonNode derivedInputs =
            new ObjectMapper().createObjectNode().put("type", "object");

    @BeforeEach
    void setup() throws Exception {
        doReturn(List.of()).when(checks).getAllWorkingCustomChecks(anyString());
        doReturn(List.of()).when(checks).getCustomChecksForImport(anyString());
        doNothing().when(checks).reserveImportIdentity(anyString(), anyString(), anyString());
        doNothing().when(checks).releaseImportIdentity(anyString(), anyString(), anyString());
        doNothing().when(checks).reserveCheckName(anyString(), anyString(), anyString(), anyString());
        doNothing().when(checks).releaseCheckName(anyString(), anyString(), anyString(), anyString());
        doAnswer(invocation -> invocation.<EligibilityCheck>getArgument(0).getId()).when(checks).saveNewWorkingCustomCheck(any());
        doAnswer(invocation -> invocation.<EligibilityCheck>getArgument(0).getId()).when(checks).saveNewPublishedCustomCheck(any());
        doNothing().when(checks).deleteWorkingCustomCheck(anyString());
        doNothing().when(checks).deletePublishedCustomCheck(anyString());
        when(storage.getCheckDmnModelPath(anyString())).thenAnswer(i -> "check/" + i.getArgument(0) + ".dmn");
        when(storage.getScreenerWorkingFormSchemaPath(anyString())).thenAnswer(i -> "form/working/" + i.getArgument(0) + ".json");
        when(screeners.saveNewWorkingScreener(any())).thenAnswer(i -> i.<Screener>getArgument(0).getId());
        when(dmn.validateDmnXml(anyString(), anyMap(), anyString(), anyString())).thenReturn(List.of());
        when(dmn.extractInputSchema(anyString(), anyMap(), anyString())).thenReturn(derivedInputs);
        source = Screener.create("sender", "Housing screener", null);
        source.setId("source-screener");
        source.setPublishedScreenerId("public-link");
        source.setLastPublishDate("yesterday");
        source.setFormSchema(Map.of("type", "default", "components", List.of(Map.of("key", "income", "type", "number"))));
        draft = check("W-family", "3.0.0");
        EligibilityCheck v1 = check("P-family-1.0.0", "1.0.0");
        EligibilityCheck v2 = check("P-family-2.0.0", "2.0.0");
        doReturn(Optional.of(draft)).when(checks).getWorkingCustomCheck("sender", "W-family", true);
        doReturn(Optional.of(v1)).when(checks).getPublishedCustomCheck("sender", v1.getId(), true);
        doReturn(Optional.of(v2)).when(checks).getPublishedCustomCheck("sender", v2.getId(), true);
        CheckConfig c1 = config(v1.getId(), "1.0.0");
        c1.setAliasName("Income under $50,000");
        c1.setAliasGenerated(true);
        c1.setParameters(Map.of("limit", 50000));
        CheckConfig c2 = config(v2.getId(), "2.0.0");
        CheckConfig lc = config("L-library", "1.0.0");
        lc.setEvaluationUrl("http://sender/library");
        EligibilityCheck libraryCheck = new EligibilityCheck();
        libraryCheck.setEvaluationUrl("http://recipient/library");
        when(library.getScreenerCheckById("L-library")).thenReturn(Optional.of(libraryCheck));
        Benefit first = new Benefit("benefit-a", "First", "A description", "sender", List.of(c1, lc));
        Benefit second = new Benefit("benefit-b", "Second", "Another description", "sender", List.of(c2));
        benefits = List.of(second, first); // Repository collection order differs from screener order.
        source.setBenefits(List.of(new BenefitDetail("benefit-a", "First", "A description"),
                new BenefitDetail("benefit-b", "Second", "Another description")));
        when(screeners.getWorkingScreenerMetaDataOnly(source.getId())).thenReturn(Optional.of(source));
        when(storage.readFormSchema("form/working/" + source.getId() + ".json"))
                .thenReturn(Optional.of(source.getFormSchema()));
        when(screeners.getBenefitsInScreener(source)).thenReturn(benefits);
    }

    @Test
    void importRemapsNestedNextStepsCopiesWithoutReplacingAnalystContent() throws Exception {
        Map<String, Object> field = Map.of("id", "next", "type", "next_steps", "showWhen", "whenComplete",
                "benefitContent", Map.of("benefit-a", Map.of("eligible", Map.of("body", "Screener-specific instructions")),
                        "removed-benefit", Map.of("eligible", Map.of("body", "Removed content"))));
        source.setFormSchema(Map.of("type", "default", "components",
                List.of(Map.of("type", "group", "components", List.of(field)))));
        when(storage.readFormSchema("form/working/" + source.getId() + ".json"))
                .thenReturn(Optional.of(source.getFormSchema()));
        ScreenerTransfer transfer = service.exportScreener("sender", source.getId());
        Screener imported = service.importScreener("recipient", transfer);
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(storage).writeStringToStorage(eq("form/working/" + imported.getId() + ".json"), saved.capture(), eq("application/json"));
        var copiedField = mapper.readTree(saved.getValue()).path("components").get(0).path("components").get(0);
        String newId = imported.getBenefits().getFirst().getId();
        assertEquals("Screener-specific instructions", copiedField.path("benefitContent").path(newId).path("eligible").path("body").asText());
        assertEquals(1, copiedField.path("benefitContent").size());
        assertEquals("whenComplete", copiedField.path("showWhen").asText());
        assertTrue(transfer.formSchema().path("components").get(0).path("components").get(0).path("benefitContent").has("benefit-a"),
                "Import must not mutate a shared source schema");
    }

    @Test
    void roundTripPreservesRulesVersionsFormAndSettingsWithNewOwnershipAndIds() throws Exception {
        var content = new NextSteps(new NextSteps.Eligible("https://example.org/apply", null, null, "Instructions\n\n- Bring ID"),
            new NextSteps.Ineligible("A note"));
        benefits.get(1).setNextSteps(content);
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        String json = mapper.writeValueAsString(export);
        assertFalse(json.contains("ownerId\":\"sender"));
        assertFalse(json.contains("public-link"));
        assertEquals(List.of("P-family-1.0.0", "P-family-2.0.0"),
                export.customChecks().stream().map(EligibilityCheck::getId).sorted().toList());
        // Exercise the exact single-file representation, including embedded XML.
        Screener imported = service.importScreener("recipient", mapper.readValue(json, ScreenerTransfer.class));
        assertNotEquals(source.getId(), imported.getId());
        assertEquals("recipient", imported.getOwnerId());
        assertEquals(source.getScreenerName(), imported.getScreenerName());
        assertNull(imported.getPublishedScreenerId());
        assertNull(imported.getLastPublishDate());
        assertEquals(List.of("First", "Second"), imported.getBenefits().stream().map(BenefitDetail::getName).toList());
        ArgumentCaptor<Benefit> savedBenefits = ArgumentCaptor.forClass(Benefit.class);
        verify(screeners, times(2)).saveNewCustomBenefit(eq(imported.getId()), savedBenefits.capture());
        Benefit first = savedBenefits.getAllValues().getFirst();
        assertNotEquals("benefit-a", first.getId());
        assertEquals("recipient", first.getOwnerId());
        assertEquals(content, first.getNextSteps());
        first.setNextSteps(null);
        assertEquals(content, benefits.get(1).getNextSteps(), "Imported content must be independent of its source");
        CheckConfig configured = first.getChecks().getFirst();
        assertEquals(Map.of("limit", 50000), configured.getParameters());
        assertEquals("Income under $50,000", configured.getAliasName());
        assertTrue(configured.isAliasGenerated());
        assertEquals("1.0.0", configured.getCheckVersion());
        assertNotEquals("P-family-1.0.0", configured.getSourceCheckId());
        assertNull(configured.getEvaluationUrl());
        assertEquals("L-library", first.getChecks().get(1).getSourceCheckId());
        assertEquals("http://recipient/library", first.getChecks().get(1).getEvaluationUrl());
        ArgumentCaptor<EligibilityCheck> savedDraft = ArgumentCaptor.forClass(EligibilityCheck.class);
        verify(checks).saveNewWorkingCustomCheck(savedDraft.capture());
        EligibilityCheck importedDraft = savedDraft.getValue();
        assertEquals("2.0.0", importedDraft.getVersion());
        assertEquals("recipient", importedDraft.getOwnerId());
        assertFalse(importedDraft.getIsArchived());
        assertNull(importedDraft.getExampleSourceId());
        assertEquals("example", importedDraft.getOriginCheckId());
        ArgumentCaptor<EligibilityCheck> savedPublished = ArgumentCaptor.forClass(EligibilityCheck.class);
        verify(checks, times(2)).saveNewPublishedCustomCheck(savedPublished.capture());
        for (EligibilityCheck published : savedPublished.getAllValues()) {
            assertEquals(importedDraft.getId(), checks.getWorkingId(published));
            verify(storage).writeStringToStorage("check/" + published.getId() + ".dmn", check(published.getId(), published.getVersion()).getDmnModel(), "application/xml");
        }
        verify(storage).writeStringToStorage("form/working/" + imported.getId() + ".json",
                mapper.writeValueAsString(source.getFormSchema()), "application/json");
        assertEquals("sender", draft.getOwnerId());
        assertEquals("benefit-a", source.getBenefits().getFirst().getId());
    }

    @Test
    void repeatImportsReuseVersionsAndKeepEditedDraftsEvenAfterReExport() throws Exception {
        Map<String, EligibilityCheck> saved = recipientState();
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        Screener one = service.importScreener("recipient", export);
        EligibilityCheck localDraft = saved.values().stream().filter(c -> c.getId().startsWith("W-")).findFirst().orElseThrow();
        localDraft.setName("My renamed draft");
        localDraft.setModule("My module");
        localDraft.setVersion("9.0.0");
        localDraft.setDmnModel(draft.getDmnModel().replace("Income", "My renamed draft").replace("true", "false"));
        clearInvocations(checks, storage, screeners);
        Screener two = service.importScreener("recipient", withName(export, "Second copy"));
        assertNotEquals(one.getId(), two.getId());
        verify(checks, never()).saveNewWorkingCustomCheck(any());
        verify(checks, never()).saveNewPublishedCustomCheck(any());
        verify(checks, never()).updateWorkingCustomCheck(any());
        verify(checks, never()).reserveCheckName(anyString(), anyString(), anyString(), anyString());
        verify(storage, never()).writeStringToStorage(startsWith("check/"), anyString(), anyString());
        assertEquals("My renamed draft", localDraft.getName());
        assertTrue(localDraft.getDmnModel().contains("false"));
        ArgumentCaptor<Benefit> importedBenefits = ArgumentCaptor.forClass(Benefit.class);
        verify(screeners, times(2)).saveNewCustomBenefit(eq(two.getId()), importedBenefits.capture());
        assertEquals("income", importedBenefits.getAllValues().getFirst().getChecks().getFirst().getCheckModule());

        // Re-export from the recipient, then bring that file back to the original account.
        // The origin remains stable even though local family IDs and the draft's name changed.
        when(screeners.getWorkingScreenerMetaDataOnly(two.getId())).thenReturn(Optional.of(two));
        when(screeners.getBenefitsInScreener(two)).thenReturn(importedBenefits.getAllValues());
        ScreenerTransfer reexported = service.exportScreener("recipient", two.getId());
        assertTrue(reexported.customChecks().stream().allMatch(c -> "example".equals(c.getOriginCheckId())));
        List<EligibilityCheck> senderChecks = new ArrayList<>();
        senderChecks.add(draft);
        senderChecks.add(check("P-family-1.0.0", "1.0.0"));
        senderChecks.add(check("P-family-2.0.0", "2.0.0"));
        doReturn(senderChecks).when(checks).getCustomChecksForImport("sender");
        service.importScreener("sender", withName(reexported, "Back to sender"));
        verify(checks, never()).saveNewWorkingCustomCheck(any());
        verify(checks, never()).saveNewPublishedCustomCheck(any());
    }

    @Test
    void importIntoOriginalAccountReusesNativeIdsAndArchivedDrafts() throws Exception {
        draft.setIsArchived(true);
        doReturn(List.of(draft, check("P-family-1.0.0", "1.0.0"), check("P-family-2.0.0", "2.0.0")))
                .when(checks).getCustomChecksForImport("sender");
        service.importScreener("sender", service.exportScreener("sender", source.getId()));
        verify(checks, never()).saveNewWorkingCustomCheck(any());
        verify(checks, never()).saveNewPublishedCustomCheck(any());
        assertTrue(draft.getIsArchived());
        ArgumentCaptor<Benefit> imported = ArgumentCaptor.forClass(Benefit.class);
        verify(screeners, times(2)).saveNewCustomBenefit(anyString(), imported.capture());
        assertEquals("P-family-1.0.0", imported.getAllValues().getFirst().getChecks().getFirst().getSourceCheckId());
    }

    @Test
    void checksInAnotherAccountAreNeverReused() throws Exception {
        doReturn(List.of(draft, check("P-family-1.0.0", "1.0.0"), check("P-family-2.0.0", "2.0.0")))
                .when(checks).getCustomChecksForImport("recipient");
        service.importScreener("recipient", service.exportScreener("sender", source.getId()));
        var saved = ArgumentCaptor.forClass(EligibilityCheck.class);
        verify(checks).saveNewWorkingCustomCheck(saved.capture());
        assertNotEquals(draft.getId(), saved.getValue().getId());
        assertEquals("recipient", saved.getValue().getOwnerId());
        assertEquals("income", saved.getValue().getModule());
    }

    @Test
    void storedCheckWithAMalformedIdDoesNotBlockImport() throws Exception {
        EligibilityCheck malformed = check("P-legacy", "1.0.0");
        malformed.setOwnerId("recipient");
        doReturn(List.of(malformed)).when(checks).getCustomChecksForImport("recipient");
        service.importScreener("recipient", service.exportScreener("sender", source.getId()));
        verify(checks).saveNewWorkingCustomCheck(any());
    }

    @Test
    void unrelatedCheckWithSameNameGetsItsOwnFamilyAndModule() throws Exception {
        EligibilityCheck unrelated = check("W-unrelated", "1.0.0");
        unrelated.setOwnerId("recipient");
        unrelated.setExampleSourceId(null);
        doReturn(List.of(unrelated)).when(checks).getCustomChecksForImport("recipient");
        service.importScreener("recipient", service.exportScreener("sender", source.getId()));
        var saved = ArgumentCaptor.forClass(EligibilityCheck.class);
        verify(checks).saveNewWorkingCustomCheck(saved.capture());
        assertNotEquals(unrelated.getId(), saved.getValue().getId());
        assertEquals("income (imported 2)", saved.getValue().getModule());
    }

    @Test
    void missingPublishedVersionIsAddedWithoutReplacingTheDraft() throws Exception {
        Map<String, EligibilityCheck> saved = recipientState();
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        service.importScreener("recipient", export);
        var removed = saved.values().stream().filter(c -> c.getId().startsWith("P-") && "2.0.0".equals(c.getVersion()))
                .findFirst().orElseThrow();
        saved.remove(removed.getId());
        clearInvocations(checks, storage);
        service.importScreener("recipient", withName(export, "With version two"));
        verify(checks, never()).saveNewWorkingCustomCheck(any());
        verify(checks, never()).updateWorkingCustomCheck(any());
        verify(checks).saveNewPublishedCustomCheck(argThat(c -> c.getId().equals(removed.getId())));
        verify(storage).writeStringToStorage(eq("check/" + removed.getId() + ".dmn"), anyString(), eq("application/xml"));
    }

    @Test
    void conflictingPublishedRulesRejectTheWholeImportBeforeWrites() throws Exception {
        Map<String, EligibilityCheck> saved = recipientState();
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        service.importScreener("recipient", export);
        saved.values().stream().filter(c -> c.getId().startsWith("P-")).findFirst().orElseThrow()
                .setDmnModel(draft.getDmnModel().replace("true", "false"));
        clearInvocations(checks, screeners, storage);
        var failure = assertThrows(CustomCheckImportConflictException.class,
                () -> service.importScreener("recipient", withName(export, "Conflicting copy")));
        assertTrue(failure.getMessage().contains("different rules or parameter definitions"));
        verify(storage, never()).writeStringToStorage(anyString(), anyString(), anyString());
        verify(screeners, never()).saveNewCustomBenefit(anyString(), any());
        verify(checks, never()).deletePublishedCustomCheck(anyString());
    }

    @Test
    void conflictingParameterDefinitionsRejectReuse() throws Exception {
        Map<String, EligibilityCheck> saved = recipientState();
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        service.importScreener("recipient", export);
        ParameterDefinition parameter = new ParameterDefinition();
        parameter.setKey("new-limit");
        saved.values().stream().filter(c -> c.getId().startsWith("P-")).findFirst().orElseThrow()
                .setParameterDefinitions(List.of(parameter));
        clearInvocations(storage);
        assertThrows(CustomCheckImportConflictException.class,
                () -> service.importScreener("recipient", withName(export, "Conflicting parameters")));
        verify(storage, never()).writeStringToStorage(anyString(), anyString(), anyString());
    }

    @Test
    void xmlIndentationDoesNotCauseAFalseVersionConflict() throws Exception {
        Map<String, EligibilityCheck> saved = recipientState();
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        service.importScreener("recipient", export);
        saved.values().stream().filter(c -> c.getId().startsWith("P-")).forEach(c ->
                c.setDmnModel(c.getDmnModel().replace("><", ">\n  <")));
        clearInvocations(checks);
        service.importScreener("recipient", withName(export, "Formatted copy"));
        verify(checks, never()).saveNewPublishedCustomCheck(any());
    }

    @Test
    void referencedDraftConflictDoesNotSilentlyChangeTheImportedRules() throws Exception {
        Map<String, EligibilityCheck> saved = recipientState();
        service.importScreener("recipient", service.exportScreener("sender", source.getId()));
        saved.values().stream().filter(c -> c.getId().startsWith("W-")).findFirst().orElseThrow()
                .setDmnModel(draft.getDmnModel().replace("true", "false"));
        CheckConfig configured = benefits.getLast().getChecks().getFirst();
        configured.setSourceCheckId(draft.getId());
        configured.setCheckVersion(draft.getVersion());
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        clearInvocations(storage);
        var failure = assertThrows(CustomCheckImportConflictException.class,
                () -> service.importScreener("recipient", withName(export, "Draft conflict")));
        assertTrue(failure.getMessage().contains("differs from your draft"));
        verify(storage, never()).writeStringToStorage(anyString(), anyString(), anyString());
    }

    @Test
    void oldExportsWithoutOriginIdentityRemainImportableAndReusable() throws Exception {
        recipientState();
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        export.customChecks().forEach(c -> c.setOriginCheckId(null));
        service.importScreener("recipient", export);
        clearInvocations(checks, screeners);
        Screener legacyCopy = service.importScreener("recipient", withName(export, "Legacy copy"));
        verify(checks, never()).saveNewWorkingCustomCheck(any());
        verify(checks, never()).saveNewPublishedCustomCheck(any());
        var importedBenefits = ArgumentCaptor.forClass(Benefit.class);
        verify(screeners, times(2)).saveNewCustomBenefit(eq(legacyCopy.getId()), importedBenefits.capture());
        when(screeners.getWorkingScreenerMetaDataOnly(legacyCopy.getId())).thenReturn(Optional.of(legacyCopy));
        when(screeners.getBenefitsInScreener(legacyCopy)).thenReturn(importedBenefits.getAllValues());
        doReturn(List.of(draft, check("P-family-1.0.0", "1.0.0"), check("P-family-2.0.0", "2.0.0")))
                .when(checks).getCustomChecksForImport("sender");
        service.importScreener("sender", withName(service.exportScreener("recipient", legacyCopy.getId()), "Legacy shared back"));
        verify(checks, never()).saveNewWorkingCustomCheck(any());
        verify(checks, never()).saveNewPublishedCustomCheck(any());
    }

    @Test
    void rollbackNeverDeletesReusedCheckVersionsOrDrafts() throws Exception {
        recipientState();
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        service.importScreener("recipient", export);
        doThrow(new Exception("Storage unavailable")).when(storage)
                .writeStringToStorage(startsWith("form/"), anyString(), eq("application/json"));
        clearInvocations(checks, storage);
        assertThrows(Exception.class, () -> service.importScreener("recipient", withName(export, "Failed copy")));
        verify(checks, never()).deleteWorkingCustomCheck(anyString());
        verify(checks, never()).deletePublishedCustomCheck(anyString());
        verify(storage, never()).deleteFile(startsWith("check/"));
    }

    @Test
    void concurrentPublishedCreateDoesNotOverwriteOrDeleteTheWinner() throws Exception {
        Map<String, EligibilityCheck> saved = recipientState();
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        service.importScreener("recipient", export);
        var version = saved.values().stream().filter(c -> c.getId().startsWith("P-") && "2.0.0".equals(c.getVersion()))
                .findFirst().orElseThrow();
        doReturn(saved.values().stream().filter(c -> !c.getId().equals(version.getId())).toList())
                .when(checks).getCustomChecksForImport("recipient");
        doThrow(new DocumentAlreadyExistsException(version.getId(), null)).when(checks)
                .saveNewPublishedCustomCheck(argThat(c -> c.getId().equals(version.getId())));
        doThrow(new Exception("Storage unavailable")).when(storage)
                .writeStringToStorage(startsWith("form/"), anyString(), eq("application/json"));
        clearInvocations(checks, storage);
        assertThrows(Exception.class, () -> service.importScreener("recipient", withName(export, "Racing copy")));
        verify(storage, never()).writeStringToStorage(startsWith("check/"), anyString(), anyString());
        verify(checks, never()).deletePublishedCustomCheck(anyString());
    }

    @Test
    void concurrentFamilyCreationIsRejectedBeforeNameOrArtifactWrites() throws Exception {
        doThrow(new DocumentAlreadyExistsException("origin", null)).when(checks)
                .reserveImportIdentity(anyString(), anyString(), anyString());
        var export = service.exportScreener("sender", source.getId());
        assertThrows(CustomCheckImportConflictException.class, () -> service.importScreener("recipient", export));
        verify(checks, never()).reserveCheckName(anyString(), anyString(), anyString(), anyString());
        verify(storage, never()).writeStringToStorage(anyString(), anyString(), anyString());
    }

    @Test
    void unavailableMetadataReadDoesNotCreateDuplicateFamilies() throws Exception {
        doThrow(new Exception("Firestore unavailable")).when(checks).getCustomChecksForImport("recipient");
        var export = service.exportScreener("sender", source.getId());
        assertThrows(Exception.class, () -> service.importScreener("recipient", export));
        verify(checks, never()).reserveImportIdentity(anyString(), anyString(), anyString());
        verify(storage, never()).writeStringToStorage(anyString(), anyString(), anyString());
    }

    private ScreenerTransfer withName(ScreenerTransfer transfer, String name) {
        return new ScreenerTransfer(transfer.format(), transfer.formatVersion(), name,
                transfer.formSchema(), transfer.benefits(), transfer.customChecks());
    }

    private Map<String, EligibilityCheck> recipientState() throws Exception {
        Map<String, EligibilityCheck> saved = new LinkedHashMap<>();
        doAnswer(i -> new ArrayList<>(saved.values())).when(checks).getCustomChecksForImport("recipient");
        for (boolean published : List.of(false, true)) {
            org.mockito.stubbing.Answer<String> save = i -> {
                EligibilityCheck check = mapper.convertValue(i.getArgument(0), EligibilityCheck.class);
                saved.put(check.getId(), check);
                return check.getId();
            };
            if (published) {
                doAnswer(save).when(checks).saveNewPublishedCustomCheck(any());
                doAnswer(i -> Optional.ofNullable(saved.get(i.<String>getArgument(1))))
                        .when(checks).getPublishedCustomCheck(eq("recipient"), anyString(), eq(true));
            } else {
                doAnswer(save).when(checks).saveNewWorkingCustomCheck(any());
                doAnswer(i -> Optional.ofNullable(saved.get(i.<String>getArgument(1))))
                        .when(checks).getWorkingCustomCheck(eq("recipient"), anyString(), eq(true));
            }
        }
        doAnswer(i -> {
            String path = i.getArgument(0);
            String id = path.substring("check/".length(), path.length() - ".dmn".length());
            saved.get(id).setDmnModel(i.getArgument(1));
            return null;
        }).when(storage).writeStringToStorage(startsWith("check/"), anyString(), eq("application/xml"));
        return saved;
    }

    @Test
    void duplicateNameRejectsImportBeforeWritingArtifacts() throws Exception {
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        when(screeners.getWorkingScreeners("recipient"))
                .thenReturn(List.of(Screener.create("recipient", " housing SCREENER ", null)));
        assertThrows(DuplicateScreenerNameException.class, () -> service.importScreener("recipient", export));
        verify(checks, never()).reserveCheckName(anyString(), anyString(), anyString(), anyString());
        verify(storage, never()).writeStringToStorage(anyString(), anyString(), anyString());
        var renamed = new ScreenerTransfer(export.format(), export.formatVersion(), " Housing screener - Copy ",
                export.formSchema(), export.benefits(), export.customChecks());
        assertEquals("Housing screener - Copy", service.importScreener("recipient", renamed).getScreenerName());
    }

    @Test
    void nameCheckMatchesTheSavedNameNormalization() throws Exception {
        // equalsIgnoreCase treats "İ" as "i", but the save-time check lowercases it to "i" plus a combining dot.
        ScreenerTransfer export = withName(service.exportScreener("sender", source.getId()), "income");
        when(screeners.getWorkingScreeners("recipient")).thenReturn(List.of(Screener.create("recipient", "İncome", null)));
        assertEquals("income", service.importScreener("recipient", export).getScreenerName());
    }

    @Test
    void missingModelIsRejectedBeforeAnyWrites() throws Exception {
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        export.customChecks().getFirst().setDmnModel(null);
        assertThrows(BadRequestException.class, () -> service.importScreener("recipient", export));
        verify(checks, never()).reserveCheckName(anyString(), anyString(), anyString(), anyString());
        verify(screeners, never()).saveNewWorkingScreener(any());
        verify(storage, never()).writeStringToStorage(anyString(), anyString(), anyString());
    }

    @Test
    void modelThatDoesNotCompileIsRejectedBeforeAnyWrites() throws Exception {
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        when(dmn.validateDmnXml(anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(List.of("The Result DataType of Decision 'Income' must be of type 'boolean'."));
        var failure = assertThrows(BadRequestException.class, () -> service.importScreener("recipient", export));
        assertTrue(failure.getMessage().contains("must be of type 'boolean'"));
        verify(checks, never()).reserveCheckName(anyString(), anyString(), anyString(), anyString());
        verify(storage, never()).writeStringToStorage(anyString(), anyString(), anyString());
    }

    @Test
    void inputDefinitionsAreDerivedFromTheModelRatherThanTakenFromTheFile() throws Exception {
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        export.customChecks().forEach(c -> c.setInputDefinition(mapper.createObjectNode().put("forged", true)));
        service.importScreener("recipient", export);
        var saved = ArgumentCaptor.forClass(EligibilityCheck.class);
        verify(checks, times(2)).saveNewPublishedCustomCheck(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(c -> derivedInputs.equals(c.getInputDefinition())));
        // Both versions share one model, so it is compiled once.
        verify(dmn).validateDmnXml(anyString(), anyMap(), anyString(), anyString());
    }

    @Test
    void rejectsUnsupportedFormatAndUnresolvedReferences() throws Exception {
        assertThrows(BadRequestException.class, () -> service.importScreener("recipient",
                new ScreenerTransfer(ScreenerTransfer.FORMAT, 99, "Name", null, List.of(), List.of())));
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        export.benefits().getFirst().getChecks().getFirst().setSourceCheckId("P-missing-1.0.0");
        assertThrows(BadRequestException.class, () -> service.importScreener("recipient", export));
        verify(screeners, never()).saveNewWorkingScreener(any());
    }

    @Test
    void exportFollowsTheEditorsBenefitListWhenStoredBenefitsDisagree() throws Exception {
        // A partly failed benefit create or delete leaves the list and the stored benefits out of step.
        Benefit unlisted = new Benefit("benefit-orphan", "Orphan", null, "sender", List.of());
        when(screeners.getBenefitsInScreener(source)).thenReturn(List.of(benefits.get(0), unlisted));
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        assertEquals(List.of("benefit-b"), export.benefits().stream().map(Benefit::getId).toList());
        assertEquals(1, export.customChecks().size());
    }

    @Test
    void unreadableFormFailsTheExportInsteadOfDroppingTheForm() throws Exception {
        when(storage.readFormSchema(anyString())).thenThrow(new Exception("Storage unavailable"));
        assertThrows(Exception.class, () -> service.exportScreener("sender", source.getId()));
    }

    @Test
    void screenerWithoutASavedFormExportsWithoutOne() throws Exception {
        when(storage.readFormSchema(anyString())).thenReturn(Optional.empty());
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        assertTrue(export.formSchema() == null || export.formSchema().isNull());
    }

    @Test
    void refusesExportsForOtherUsersBeforeReadingArtifacts() throws Exception {
        assertThrows(ForbiddenException.class, () -> service.exportScreener("recipient", source.getId()));
        verify(storage, never()).readFormSchema(anyString());
        verify(screeners, never()).getBenefitsInScreener(any());
    }

    @Test
    void refusesCustomCheckBelongingToSomeoneElse() throws Exception {
        EligibilityCheck foreign = check("P-family-1.0.0", "1.0.0");
        foreign.setOwnerId("someone-else");
        doReturn(Optional.of(foreign)).when(checks).getPublishedCustomCheck("sender", foreign.getId(), true);
        assertThrows(ForbiddenException.class, () -> service.exportScreener("sender", source.getId()));
    }

    @Test
    void failedFormWriteRollsBackBenefitsChecksModelsAndNameReservations() throws Exception {
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        doThrow(new Exception("Storage unavailable")).when(storage)
                .writeStringToStorage(startsWith("form/"), anyString(), eq("application/json"));
        assertThrows(Exception.class, () -> service.importScreener("recipient", export));
        verify(screeners, times(2)).deleteCustomBenefit(anyString(), anyString());
        verify(checks).deleteWorkingCustomCheck(anyString());
        verify(checks, times(2)).deletePublishedCustomCheck(anyString());
        verify(checks).releaseCheckName(eq("recipient"), eq("income"), eq("Income"), anyString());
        verify(storage, times(4)).deleteFile(anyString());
        verify(screeners, never()).saveNewWorkingScreener(any());
    }

    @Test
    void emptyScreenerCanBeSharedWithoutAFormOrCustomChecks() throws Exception {
        Screener imported = service.importScreener("recipient", new ScreenerTransfer(ScreenerTransfer.FORMAT,
                ScreenerTransfer.VERSION, "Empty", null, List.of(), List.of()));
        assertEquals(List.of(), imported.getBenefits());
        verify(screeners).saveNewWorkingScreener(imported);
        verify(storage, never()).writeStringToStorage(anyString(), anyString(), anyString());
    }

    @Test
    void archivedChecksAlreadyUsedByAScreenerCanBeShared() throws Exception {
        draft.setIsArchived(true);
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        assertEquals(2, export.customChecks().size());
        service.importScreener("recipient", export);
        ArgumentCaptor<EligibilityCheck> saved = ArgumentCaptor.forClass(EligibilityCheck.class);
        verify(checks).saveNewWorkingCustomCheck(saved.capture());
        assertFalse(saved.getValue().getIsArchived());
    }

    @Test
    void unpublishedDraftIsNotSharedAndTheLatestUsedVersionSeedsTheRecipientDraft() throws Exception {
        // The author's draft is mid-edit and no longer has a decision named after the check.
        draft.setDmnModel("<definitions xmlns=\"https://www.omg.org/spec/DMN/20240513/MODEL/\" name=\"Income\"/>");
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        assertTrue(export.customChecks().stream().noneMatch(c -> c.getId().startsWith("W-")));
        service.importScreener("recipient", export);
        ArgumentCaptor<EligibilityCheck> saved = ArgumentCaptor.forClass(EligibilityCheck.class);
        verify(checks).saveNewWorkingCustomCheck(saved.capture());
        assertEquals("2.0.0", saved.getValue().getVersion());
    }

    @Test
    void unavailableLibraryCheckRejectsImportBeforeWrites() throws Exception {
        ScreenerTransfer export = service.exportScreener("sender", source.getId());
        when(library.getScreenerCheckById("L-library")).thenReturn(Optional.empty());
        assertThrows(BadRequestException.class, () -> service.importScreener("recipient", export));
        verify(checks, never()).reserveCheckName(anyString(), anyString(), anyString(), anyString());
        verify(storage, never()).writeStringToStorage(anyString(), anyString(), anyString());
    }

    @Test
    void bundledExampleCanBeExportedAndImportedWithItsInternalLibraryCheck() throws Exception {
        var manifest = readResource("seed-data/example-screener/manifest.json", com.fasterxml.jackson.databind.JsonNode.class);
        var screenerFiles = manifest.path("screeners").get(0);
        Screener example = readResource(screenerFiles.path("screenerPath").asText(), Screener.class);
        example.setOwnerId("sender");
        example.setFormSchema(mapper.convertValue(readResource(screenerFiles.path("formSchema").asText(),
                com.fasterxml.jackson.databind.JsonNode.class), Map.class));
        List<Benefit> exampleBenefits = new ArrayList<>();
        for (var path : screenerFiles.path("benefits")) {
            exampleBenefits.add(readResource(path.asText(), Benefit.class));
        }
        when(screeners.getWorkingScreenerMetaDataOnly(example.getId())).thenReturn(Optional.of(example));
        when(storage.readFormSchema("form/working/" + example.getId() + ".json"))
                .thenReturn(Optional.of(example.getFormSchema()));
        when(screeners.getBenefitsInScreener(example)).thenReturn(exampleBenefits);
        for (String group : List.of("workingCustomChecks", "publishedCustomChecks")) {
            for (var path : manifest.path(group)) {
                EligibilityCheck model = readResource(path.asText(), EligibilityCheck.class);
                model.setOwnerId("sender");
                try (var xml = getClass().getClassLoader().getResourceAsStream(
                        "seed-data/example-screener/storage/check/" + model.getId() + ".dmn")) {
                    model.setDmnModel(new String(xml.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                if (model.getId().startsWith("W-")) {
                    doReturn(Optional.of(model)).when(checks).getWorkingCustomCheck("sender", model.getId(), true);
                } else {
                    doReturn(Optional.of(model)).when(checks).getPublishedCustomCheck("sender", model.getId(), true);
                }
            }
        }
        LibraryApiService actualLibrary = new LibraryApiService();
        actualLibrary.loadBenefitsMetadata(mapper.writeValueAsString(exampleBenefits));
        String internalId = "L-internal-sctf-age-requirement-0.10.0";
        assertTrue(actualLibrary.getById(internalId).isEmpty());
        ScreenerTransferService transferService = new ScreenerTransferService(screeners, checks, storage, actualLibrary, dmn);
        var exported = transferService.exportScreener("sender", example.getId());
        var imported = transferService.importScreener("recipient",
                mapper.readValue(mapper.writeValueAsString(exported), ScreenerTransfer.class));
        assertEquals(4, imported.getBenefits().size());
        var saved = ArgumentCaptor.forClass(Benefit.class);
        verify(screeners, times(4)).saveNewCustomBenefit(eq(imported.getId()), saved.capture());
        var internal = saved.getAllValues().stream().flatMap(benefit -> benefit.getChecks().stream())
                .filter(config -> internalId.equals(config.getSourceCheckId())).findFirst().orElseThrow();
        assertEquals("/api/v1/checks/internal/sctf-age-requirement", internal.getEvaluationUrl());
    }

    private <T> T readResource(String path, Class<T> type) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            return mapper.readValue(stream, type);
        }
    }

    private EligibilityCheck check(String id, String version) {
        EligibilityCheck check = new EligibilityCheck("Income", "income", "Description", List.of(), "sender");
        check.setId(id);
        check.setVersion(version);
        check.setExampleSourceId("example");
        check.setDmnModel("<definitions xmlns=\"https://www.omg.org/spec/DMN/20240513/MODEL/\" namespace=\"income\" name=\"Income\">"
                + "<decision id=\"decision\" name=\"Income\"><literalExpression><text>true</text></literalExpression></decision></definitions>");
        return check;
    }

    private CheckConfig config(String source, String version) {
        CheckConfig config = new CheckConfig();
        config.setCheckId(UUID.randomUUID().toString());
        config.setSourceCheckId(source);
        config.setCheckName("Income");
        config.setCheckModule("income");
        config.setCheckVersion(version);
        return config;
    }
}
