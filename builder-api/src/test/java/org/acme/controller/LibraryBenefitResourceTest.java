package org.acme.controller;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.acme.model.domain.Benefit;
import org.acme.model.domain.CheckConfig;
import org.acme.model.domain.Screener;
import org.acme.model.dto.CustomBenefit.ImportLibraryBenefitRequest;
import org.acme.persistence.ScreenerRepository;
import org.acme.service.LibraryApiService;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibraryBenefitResourceTest {
    private static final String USER_ID = "analyst-1";
    private static final String SCREENER_ID = "screener-1";

    private final LibraryApiService libraryApiService = mock(LibraryApiService.class);
    private final ScreenerRepository screenerRepository = mock(ScreenerRepository.class);
    private final SecurityIdentity identity = mock(SecurityIdentity.class);
    private final LibraryBenefitResource resource = new LibraryBenefitResource();

    @BeforeEach
    void setUp() {
        resource.libraryApiService = libraryApiService;
        resource.screenerRepository = screenerRepository;

        JsonWebToken token = mock(JsonWebToken.class);
        when(identity.getPrincipal()).thenReturn(token);
        when(token.getClaim("user_id")).thenReturn(USER_ID);

        Screener screener = new Screener();
        screener.setId(SCREENER_ID);
        screener.setOwnerId(USER_ID);
        when(screenerRepository.getWorkingScreener(SCREENER_ID)).thenReturn(Optional.of(screener));
    }

    @Test
    void importLibraryBenefit_savesAnEditableCopyAndDetail() throws Exception {
        CheckConfig check = new CheckConfig();
        check.setCheckId("new-check-id");
        check.setSourceCheckId("library-check-id");
        check.setParameters(Map.of());
        Benefit imported = new Benefit("new-benefit-id", "Library Benefit", "Description", USER_ID, List.of(check));
        when(libraryApiService.copyBenefitForOwner("library-benefit-id", USER_ID))
            .thenReturn(Optional.of(imported));

        Response response = resource.importLibraryBenefit(
            identity,
            SCREENER_ID,
            new ImportLibraryBenefitRequest("library-benefit-id")
        );

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(screenerRepository).saveNewCustomBenefit(SCREENER_ID, imported);
        var detailCaptor = ArgumentCaptor.forClass(org.acme.model.domain.BenefitDetail.class);
        verify(screenerRepository).addBenefitDetailToWorkingScreener(eq(SCREENER_ID), detailCaptor.capture());
        assertEquals("new-benefit-id", detailCaptor.getValue().getId());
        assertEquals("Library Benefit", detailCaptor.getValue().getName());
    }

    @Test
    void importLibraryBenefit_rejectsAnotherUsersScreener() throws Exception {
        Screener screener = new Screener();
        screener.setOwnerId("someone-else");
        when(screenerRepository.getWorkingScreener(SCREENER_ID)).thenReturn(Optional.of(screener));

        Response response = resource.importLibraryBenefit(
            identity,
            SCREENER_ID,
            new ImportLibraryBenefitRequest("library-benefit-id")
        );

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        verify(screenerRepository, never()).saveNewCustomBenefit(any(), any());
    }

    @Test
    void importLibraryBenefit_returnsNotFoundForUnknownTemplate() throws Exception {
        when(libraryApiService.copyBenefitForOwner("missing", USER_ID)).thenReturn(Optional.empty());

        Response response = resource.importLibraryBenefit(
            identity,
            SCREENER_ID,
            new ImportLibraryBenefitRequest("missing")
        );

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        verify(screenerRepository, never()).saveNewCustomBenefit(any(), any());
    }
}
