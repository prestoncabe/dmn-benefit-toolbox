package org.codeforphilly.bdt.builder.controller;

import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.codeforphilly.bdt.builder.auth.AuthUtils;
import org.codeforphilly.bdt.builder.model.domain.*;
import org.codeforphilly.bdt.builder.model.dto.screener.FormPathsResponse;
import org.codeforphilly.bdt.builder.model.dto.screener.PublishScreenerRequest;
import org.codeforphilly.bdt.builder.model.dto.screener.SaveSchemaRequest;
import org.codeforphilly.bdt.builder.persistence.EligibilityCheckRepository;
import org.codeforphilly.bdt.builder.persistence.ScreenerRepository;
import org.codeforphilly.bdt.builder.persistence.PublishedScreenerRepository;
import org.codeforphilly.bdt.builder.persistence.StorageService;
import org.codeforphilly.bdt.builder.service.DmnService;
import org.codeforphilly.bdt.builder.service.InputSchemaService;
import org.codeforphilly.bdt.builder.service.ScreenerFormDraftService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.codeforphilly.bdt.builder.persistence.DuplicateScreenerNameException;
import java.util.Optional;
import org.codeforphilly.bdt.builder.model.dto.screener.CreateScreenerRequest;
import org.codeforphilly.bdt.builder.model.dto.screener.EditScreenerRequest;

@Path("/api")
public class ScreenerResource {

  @Inject Validator validator;

  @Inject ScreenerRepository screenerRepository;

  @Inject PublishedScreenerRepository publishedScreenerRepository;

  @Inject EligibilityCheckRepository eligibilityCheckRepository;

  @Inject StorageService storageService;

  @Inject DmnService dmnService;

  @Inject
  InputSchemaService inputSchemaService;

  @Inject ScreenerFormDraftService screenerFormDraftService;

  @POST
  @Path("/screener/{screenerId}/draft-form")
  @Produces(MediaType.APPLICATION_JSON)
  public Response draftForm(@Context SecurityIdentity identity,
                            @PathParam("screenerId") String screenerId) {
    String userId = AuthUtils.getUserId(identity);
    if (userId == null) return Response.status(Response.Status.UNAUTHORIZED).build();
    Optional<Screener> existing = screenerRepository.getWorkingScreener(screenerId);
    if (existing.isEmpty()) throw new NotFoundException();
    Screener screener = existing.get();
    if (!isUserAuthorizedToAccessScreenerByScreener(userId, screener)) {
      return Response.status(Response.Status.UNAUTHORIZED).build();
    }
    try {
      // Return an editable draft; saving remains an explicit editor action.
      List<Benefit> benefits = screenerRepository.getBenefitsInScreener(screener);
      List<FormPath> paths = inputSchemaService.extractUniqueInputPaths(benefits);
      if (benefits.isEmpty() || paths.isEmpty()) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(Map.of("error", "Add benefits with configured checks before drafting a form.")).build();
      }
      var draft = screenerFormDraftService.generate(benefits, paths);
      if (draft.isEmpty()) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
            .entity(Map.of("error", "Could not draft the form with AI. Check the Gemini configuration or try again.")).build();
      }
      return Response.ok(Map.of("schema", draft.get())).build();
    } catch (ScreenerFormDraftService.UndraftableFormException e) {
      return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
    } catch (Exception e) {
      Log.error("Could not prepare a screener form draft", e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(Map.of("error", "Could not load the configured benefits and checks.")).build();
    }
  }

  @GET
  @Path("/screeners")
  public Response getScreeners(@Context SecurityIdentity identity) {
      String userId = AuthUtils.getUserId(identity);
      if (userId == null){
          return Response.status(Response.Status.UNAUTHORIZED).build();
      }
      Log.info("Fetching screeners for user: " + userId);
      List<Screener> screeners = screenerRepository.getWorkingScreeners(userId);

    return Response.ok(screeners, MediaType.APPLICATION_JSON).build();
  }

  @GET
  @Path("/screener/{screenerId}")
  public Response getScreener(
      @Context SecurityIdentity identity, @PathParam("screenerId") String screenerId) {
    String userId = AuthUtils.getUserId(identity);
    Log.info("Fetching screener " + screenerId + "  for user " + userId);

    Optional<Screener> screenerOptional = screenerRepository.getWorkingScreener(screenerId);

    if (screenerOptional.isEmpty()) {
      throw new NotFoundException();
    }

    Screener screener = screenerOptional.get();
    if (!isUserAuthorizedToAccessScreenerByScreener(userId, screener)) {
      return Response.status(Response.Status.UNAUTHORIZED).build();
    }

    return Response.ok(screener, MediaType.APPLICATION_JSON).build();
  }

  @GET
  @Path("/published/screener/{screenerId}")
  @PermitAll // This endpoint is accessible without authentication
  public Response getPublishedScreener(@PathParam("screenerId") String screenerId) {
    Optional<Screener> screenerOptional = publishedScreenerRepository.getScreener(screenerId);
    if (screenerOptional.isEmpty()) {
      throw new NotFoundException();
    }

    return Response.ok(screenerOptional.get(), MediaType.APPLICATION_JSON).build();
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/screener")
  public Response postScreener(
      @Context SecurityIdentity identity, @Valid CreateScreenerRequest request) {
    String userId = AuthUtils.getUserId(identity);

    Screener newScreener = Screener.create(userId, request.screenerName(), request.description());

    try {
      String screenerId = screenerRepository.saveNewWorkingScreener(newScreener);
      newScreener.setId(screenerId);
      return Response.ok(newScreener, MediaType.APPLICATION_JSON).build();
    } catch (DuplicateScreenerNameException duplicate) {
      return Response.status(409).entity(Map.of("error", duplicate.getMessage())).build();
    } catch (jakarta.ws.rs.BadRequestException invalid) {
      return Response.status(400).entity(Map.of("error", invalid.getMessage())).build();
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(Map.of("error", "Could not save Screener"))
          .build();
    }
  }

  @PATCH
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/screener/{screenerId}")
  public Response updateScreener(
      @Context SecurityIdentity identity,
      @PathParam("screenerId") String screenerId,
      @Valid EditScreenerRequest request) {
    String userId = AuthUtils.getUserId(identity);

    // Fetch Screener record and confirm user is authorized
    Optional<Screener> maybeScreener = screenerRepository.getWorkingScreener(screenerId);
    if (maybeScreener.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }

    Screener screener = maybeScreener.get();
    if (!isUserAuthorizedToAccessScreenerByScreener(userId, screener)) {
      return Response.status(Response.Status.UNAUTHORIZED).build();
    }

    Log.info(request.toString());

    // Update Screener fields from request
    // Only a changed name is checked for uniqueness, so screeners that already share a name stay editable.
    boolean renamed = request.screenerName() != null
        && !Screener.normalizeName(request.screenerName())
            .equals(Screener.normalizeName(screener.getScreenerName()));
    if (request.screenerName() != null) {
      screener.setScreenerName(request.screenerName().strip());
    }

    try {
      if (request.integrationOrigins() != null) {
        screener.setIntegrationOrigins(
            Screener.normalizeIntegrationOrigins(request.integrationOrigins()));
      }
      if (renamed) screenerRepository.renameWorkingScreener(screener);
      else screenerRepository.updateWorkingScreener(screener);
      return Response.ok(screener, MediaType.APPLICATION_JSON).build();
    } catch (DuplicateScreenerNameException duplicate) {
      return Response.status(409).entity(Map.of("error", duplicate.getMessage())).build();
    } catch (jakarta.ws.rs.BadRequestException invalid) {
      return Response.status(400).entity(Map.of("error", invalid.getMessage())).build();
    } catch (Exception e) {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(Map.of("error", "Could not update Screener"))
          .build();
    }
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/save-form-schema")
  public Response saveFormSchema(
      @Context SecurityIdentity identity,
      @QueryParam("screenerId") @NotBlank(message = "Must provide screenerId") String screenerId,
      @Valid SaveSchemaRequest request) {

    Log.info(
        "schema node = "
            + (request == null
                ? "request=null"
                : request.schema() == null
                    ? "schema=null"
                    : request.schema().getNodeType() + " : " + request.schema().toString()));

    var violations = validator.validate(request);
    if (!violations.isEmpty()) {
      return Response.status(400).entity(violations.toString()).build();
    }

    String userId = AuthUtils.getUserId(identity);

    // Fetch Screener record and confirm user is authorized
    Optional<Screener> maybeScreener = screenerRepository.getWorkingScreener(screenerId);
    if (maybeScreener.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", true, "message", "Screener " + screenerId + " cannot be found."))
          .build();
    }

    Screener screener = maybeScreener.get();
    if (!isUserAuthorizedToAccessScreenerByScreener(userId, screener)) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity(Map.of("error", true, "message", "Unauthorized access to the screener."))
          .build();
    }

    try {
      String filePath = storageService.getScreenerWorkingFormSchemaPath(screenerId);
      storageService.writeJsonToStorage(filePath, request.schema());
      return Response.ok().build();
    } catch (Exception e) {
      Log.info(("Failed to save form for screener " + screenerId));
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/publish")
  public Response publishScreener(
      @Context SecurityIdentity identity, PublishScreenerRequest publishScreenerRequest) {

    String screenerId = publishScreenerRequest.screenerId;
    if (screenerId == null || screenerId.isBlank()) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("Error: Missing required query parameter: screenerId")
          .build();
    }

    String userId = AuthUtils.getUserId(identity);
    if (!isUserAuthorizedToAccessScreener(userId, screenerId))
      return Response.status(Response.Status.UNAUTHORIZED).build();

    try {
      Optional<Screener> screenerOpt = screenerRepository.getWorkingScreener(screenerId);
      if (screenerOpt.isEmpty()) {
        return Response.status(Response.Status.NOT_FOUND).build();
      }
      Screener screener = screenerOpt.get();
      screenerRepository.publishScreener(screener);
      return Response.ok().build();
    } catch (Exception e) {
      Log.error("Error: Error updating screener to published. Screener: " + screenerId);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
  }

  @DELETE
  @Path("/screener/delete")
  public Response deleteScreener(
      @Context SecurityIdentity identity, @QueryParam("screenerId") String screenerId) {
    if (screenerId == null || screenerId.isBlank()) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("Error: Missing required query parameter: screenerId")
          .build();
    }

    String userId = AuthUtils.getUserId(identity);
    if (!isUserAuthorizedToAccessScreener(userId, screenerId))
      return Response.status(Response.Status.UNAUTHORIZED).build();

    try {
      screenerRepository.deleteWorkingScreener(screenerId);
      return Response.ok().build();
    } catch (Exception e) {
      Log.error("Error: error deleting screener " + screenerId);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Returns the list of unique input paths required by all checks in a screener.
   * This endpoint transforms inputDefinition schemas and extracts paths,
   * replacing the frontend's transformInputDefinitionSchema and extractJsonSchemaPaths logic.
   */
  @GET
  @Path("/screener/{screenerId}/form-paths")
  public Response getScreenerFormPaths(@Context SecurityIdentity identity,
                                        @PathParam("screenerId") String screenerId) {
    String userId = AuthUtils.getUserId(identity);

    Optional<Screener> screenerOpt = screenerRepository.getWorkingScreener(screenerId);
    if (screenerOpt.isEmpty()) {
      throw new NotFoundException();
    }
    Screener screener = screenerOpt.get();

    if (!isUserAuthorizedToAccessScreenerByScreener(userId, screener)) {
      return Response.status(Response.Status.UNAUTHORIZED).build();
    }

    try {
      List<Benefit> benefits = screenerRepository.getBenefitsInScreener(screener);
      List<FormPath> paths = new ArrayList<>(inputSchemaService.extractUniqueInputPaths(benefits));
      Collections.sort(paths, new Comparator<FormPath>() {
        public int compare(FormPath fp1, FormPath fp2) {
          // compare two instance of `Score` and return `int` as result.
          return fp1.getPath().compareTo(fp2.getPath());
        }
      });
      return Response.ok().entity(new FormPathsResponse(paths)).build();
    } catch (Exception e) {
      Log.error(e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
              .entity(Map.of("error", "Could not extract form paths"))
              .build();
    }
  }

  private boolean isUserAuthorizedToAccessScreener(String userId, String screenerId) {
    Optional<Screener> screenerOptional =
        screenerRepository.getWorkingScreenerMetaDataOnly(screenerId);
    if (screenerOptional.isEmpty()) {
      return false;
    }
    Screener screener = screenerOptional.get();
    return isUserAuthorizedToAccessScreenerByScreener(userId, screener);
  }

  private boolean isUserAuthorizedToAccessScreenerByScreener(String userId, Screener screener) {
    return userId.equals(screener.getOwnerId());
  }
}
