package org.acme.controller;

import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.auth.AuthUtils;
import org.acme.model.domain.Benefit;
import org.acme.model.domain.BenefitDetail;
import org.acme.model.domain.Screener;
import org.acme.model.dto.CustomBenefit.ImportLibraryBenefitRequest;
import org.acme.persistence.ScreenerRepository;
import org.acme.service.LibraryApiService;

import java.util.Map;
import java.util.Optional;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class LibraryBenefitResource {

    @Inject
    LibraryApiService libraryApiService;

    @Inject
    ScreenerRepository screenerRepository;

    @GET
    @Path("/library-benefits")
    public Response getLibraryBenefits() {
        return Response.ok(libraryApiService.getBenefits()).build();
    }

    @POST
    @Path("/screener/{screenerId}/benefit/import")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response importLibraryBenefit(
        @Context SecurityIdentity identity,
        @PathParam("screenerId") String screenerId,
        @Valid ImportLibraryBenefitRequest request
    ) {
        String userId = AuthUtils.getUserId(identity);
        Optional<Screener> screenerOpt = screenerRepository.getWorkingScreener(screenerId);
        if (screenerOpt.isEmpty()) {
            throw new NotFoundException();
        }
        if (userId == null || !userId.equals(screenerOpt.get().getOwnerId())) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        Optional<Benefit> importedBenefitOpt =
            libraryApiService.copyBenefitForOwner(request.benefitId(), userId);
        if (importedBenefitOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Library benefit not found"))
                .build();
        }

        Benefit importedBenefit = importedBenefitOpt.get();
        BenefitDetail detail = new BenefitDetail(
            importedBenefit.getId(),
            importedBenefit.getName(),
            importedBenefit.getDescription()
        );

        try {
            screenerRepository.saveNewCustomBenefit(screenerId, importedBenefit);
            screenerRepository.addBenefitDetailToWorkingScreener(screenerId, detail);
            return Response.ok(importedBenefit).build();
        } catch (Exception e) {
            Log.error("Could not import library benefit", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", "Could not import library benefit"))
                .build();
        }
    }
}
