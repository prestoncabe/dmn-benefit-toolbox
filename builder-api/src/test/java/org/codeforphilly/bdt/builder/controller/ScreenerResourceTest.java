package org.codeforphilly.bdt.builder.controller;

import io.quarkus.security.identity.SecurityIdentity;
import org.codeforphilly.bdt.builder.model.domain.Screener;
import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.FormPath;
import org.codeforphilly.bdt.builder.persistence.StorageService;
import org.codeforphilly.bdt.builder.service.InputSchemaService;
import org.codeforphilly.bdt.builder.service.ScreenerFormDraftService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.NotFoundException;
import org.codeforphilly.bdt.builder.model.dto.screener.EditScreenerRequest;
import org.codeforphilly.bdt.builder.persistence.DuplicateScreenerNameException;
import org.codeforphilly.bdt.builder.persistence.ScreenerRepository;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScreenerResourceTest {
    private final ScreenerRepository screenerRepository = mock(ScreenerRepository.class);
    private final SecurityIdentity identity = mock(SecurityIdentity.class);
    private final ScreenerResource resource = new ScreenerResource();
    private final InputSchemaService inputSchemaService = mock(InputSchemaService.class);
    private final ScreenerFormDraftService draftService = mock(ScreenerFormDraftService.class);
    private final StorageService storage = mock(StorageService.class);
    private Screener screener;

    @BeforeEach
    void setUp() {
        resource.screenerRepository = screenerRepository;
        resource.inputSchemaService = inputSchemaService;
        resource.screenerFormDraftService = draftService;
        resource.storageService = storage;
        JsonWebToken token = mock(JsonWebToken.class);
        when(identity.getPrincipal()).thenReturn(token);
        when(token.getClaim("user_id")).thenReturn("owner");
        screener = Screener.create("owner", "Housing", null);
        screener.setId("screener-1");
        when(screenerRepository.getWorkingScreener("screener-1")).thenReturn(Optional.of(screener));
    }

    @Test
    void changedNameIsCheckedForUniqueness() throws Exception {
        doThrow(new DuplicateScreenerNameException()).when(screenerRepository).renameWorkingScreener(any());
        try (var response = resource.updateScreener(identity, "screener-1", new EditScreenerRequest("Food"))) {
            assertEquals(409, response.getStatus());
        }
        verify(screenerRepository, never()).updateWorkingScreener(any());
    }

    @Test
    void unchangedNameSavesWithoutTheUniquenessCheck() throws Exception {
        // Screeners created before names were unique may already share this name.
        try (var response = resource.updateScreener(identity, "screener-1", new EditScreenerRequest(" housing "))) {
            assertEquals(200, response.getStatus());
        }
        verify(screenerRepository).updateWorkingScreener(screener);
        assertEquals("housing", screener.getScreenerName());
        verify(screenerRepository, never()).renameWorkingScreener(any());
    }

    @Test
    void integrationOriginsAreSavedAsBrowserOrigins() throws Exception {
        var request = new EditScreenerRequest(null, List.of(
            " HTTPS://CRM.Example:443/ ", "http://localhost:4174", "", "https://crm.example"));
        try (var response = resource.updateScreener(identity, "screener-1", request)) {
            assertEquals(200, response.getStatus());
        }
        assertEquals(List.of("https://crm.example", "http://localhost:4174"),
            screener.getIntegrationOrigins());
        verify(screenerRepository).updateWorkingScreener(screener);
    }

    @Test
    void invalidIntegrationOriginsAreRejected() throws Exception {
        for (String origin : List.of(
                "crm.example", "https://crm.example/path", "https://crm.example?x=1",
                "https://user@crm.example", "javascript:alert(1)", "*", "null")) {
            var request = new EditScreenerRequest(null, List.of(origin));
            try (var response = resource.updateScreener(identity, "screener-1", request)) {
                assertEquals(400, response.getStatus(), origin);
                assertTrue(((Map<?, ?>) response.getEntity()).get("error").toString()
                    .contains("CRM origin"), origin);
            }
        }
        assertNull(screener.getIntegrationOrigins());
        verify(screenerRepository, never()).updateWorkingScreener(any());
    }

    @Test
    void draftChecksOwnershipBeforeReadingBenefitsOrCallingGemini() throws Exception {
        screener.setOwnerId("someone-else");
        try (var response = resource.draftForm(identity, "screener-1")) {
            assertEquals(401, response.getStatus());
        }
        verifyNoInteractions(inputSchemaService, draftService, storage);
        verify(screenerRepository, never()).getBenefitsInScreener(any());
    }

    @Test
    void draftRejectsUnknownScreenersAndMissingCheckInputs() throws Exception {
        assertThrows(NotFoundException.class, () -> resource.draftForm(identity, "missing"));
        when(screenerRepository.getBenefitsInScreener(screener)).thenReturn(List.of());
        try (var response = resource.draftForm(identity, "screener-1")) {
            assertEquals(400, response.getStatus());
        }
        verifyNoInteractions(draftService, storage);
    }

    @Test
    void draftReturnsUnsavedSchemaAndReportsUnavailableGeneration() throws Exception {
        var benefits = List.of(new Benefit("benefit", "Benefit", null, "owner", List.of()));
        when(screenerRepository.getBenefitsInScreener(screener)).thenReturn(benefits);
        var paths = List.of(new FormPath("custom.income", "number"));
        when(inputSchemaService.extractUniqueInputPaths(benefits)).thenReturn(paths);
        var schema = new ObjectMapper().readTree("{\"components\":[]}");
        when(draftService.generate(benefits, paths)).thenReturn(Optional.of(schema));
        try (var response = resource.draftForm(identity, "screener-1")) {
            assertEquals(200, response.getStatus());
            assertEquals(java.util.Map.of("schema", schema), response.getEntity());
        }
        when(draftService.generate(benefits, paths)).thenReturn(Optional.empty());
        try (var response = resource.draftForm(identity, "screener-1")) {
            assertEquals(503, response.getStatus());
        }
        when(draftService.generate(benefits, paths))
            .thenThrow(new ScreenerFormDraftService.UndraftableFormException("Unsupported input"));
        try (var response = resource.draftForm(identity, "screener-1")) {
            assertEquals(400, response.getStatus());
            assertEquals(java.util.Map.of("error", "Unsupported input"), response.getEntity());
        }
        verifyNoInteractions(storage);
        verify(screenerRepository, never()).updateWorkingScreener(any());
    }
}
