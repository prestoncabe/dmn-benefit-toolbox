package org.acme.controller;

import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.fasterxml.jackson.databind.JsonNode;
import org.acme.auth.AuthUtils;
import org.acme.constants.CheckStatus;
import org.acme.model.domain.CheckVersion;
import org.acme.model.domain.EligibilityCheck;
import org.acme.model.dto.EligibilityCheck.CheckDmnRequest;
import org.acme.model.dto.EligibilityCheck.CreateCheckRequest;
import org.acme.model.dto.EligibilityCheck.EditCheckRequest;
import org.acme.persistence.DocumentAlreadyExistsException;
import org.acme.persistence.EligibilityCheckRepository;
import org.acme.persistence.StorageService;
import org.acme.service.CustomCheckDmnTemplate;
import org.acme.service.DmnService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Path("/api/custom-checks")
public class EligibilityCheckResource {

    @Inject
    EligibilityCheckRepository eligibilityCheckRepository;

    @Inject
    StorageService storageService;

    @Inject
    DmnService dmnService;

    @Inject
    CustomCheckDmnTemplate customCheckDmnTemplate;

    // ========== Collection Endpoints ==========

    // By default, returns the most recent versions of all published checks owned by the calling user
    // If the query parameter 'working' is set to true,
    // then the active (non-archived) working check objects owned by the user are returned
    // Adding 'includeArchived=true' to a working request returns the archived ones alongside them,
    // so a caller that renders both lists can do it with a single read
    @GET
    public Response getCustomChecks(
        @Context SecurityIdentity identity,
        @QueryParam("working") Boolean working,
        @QueryParam("includeArchived") Boolean includeArchived
    ) {
        String userId = AuthUtils.getUserId(identity);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        List<EligibilityCheck> checks;

        if (Boolean.TRUE.equals(working)){
            if (Boolean.TRUE.equals(includeArchived)) {
                Log.info("Fetching active and archived custom checks. User:  " + userId);
                checks = eligibilityCheckRepository.getAllWorkingCustomChecks(userId);
            } else {
                Log.info("Fetching active working custom checks. User:  " + userId);
                checks = eligibilityCheckRepository.getWorkingCustomChecks(userId);
            }
        } else {
            Log.info("Fetching all published custom checks. User:  " + userId);
            checks = eligibilityCheckRepository.getLatestVersionPublishedCustomChecks(userId);
        }

        return Response.ok(checks, MediaType.APPLICATION_JSON).build();
    }

    @POST
    public Response createCustomCheck(@Context SecurityIdentity identity,
                                @Valid CreateCheckRequest request) {
        String userId = AuthUtils.getUserId(identity);

        // Build EligibilityCheck from allowed fields only
        EligibilityCheck newCheck = new EligibilityCheck(
            request.name(),
            request.module(),
            request.description(),
            request.parameterDefinitions(),
            userId
        );
        String checkId = eligibilityCheckRepository.getWorkingId(newCheck);
        Optional<EligibilityCheck> existingCheck = eligibilityCheckRepository
                .getWorkingCustomCheckMetadata(userId, checkId);
        if (existingCheck.isPresent()) {
            return duplicateCheckResponse(request, existingCheck.get().getIsArchived());
        }

        String initialDmnModel = customCheckDmnTemplate.create(request.name(), request.description());

        try {
            checkId = eligibilityCheckRepository.saveNewWorkingCustomCheck(newCheck);
        } catch (DocumentAlreadyExistsException e) {
            Log.info("Check " + checkId + " already exists after attempted creation");
            // The preflight lookup may have returned empty because its read failed, so inspect the
            // document again before describing the colliding check's archive state. This read sits
            // outside the sibling catch blocks, so it has to handle its own failures.
            try {
                Optional<EligibilityCheck> collidingCheck = eligibilityCheckRepository
                        .getWorkingCustomCheckMetadata(userId, checkId);
                if (collidingCheck.isPresent()) {
                    return duplicateCheckResponse(request, collidingCheck.get().getIsArchived());
                }
            } catch (Exception readFailure) {
                Log.error("Could not read the check " + checkId + " that collided with the new check",
                        readFailure);
            }
            return duplicateCheckResponse(request);
        } catch (Exception e){
            Log.error("Could not save new check for user " + userId, e);
            return  Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not save Check"))
                    .build();
        }
        newCheck.setId(checkId);

        try {
            storageService.writeStringToStorage(
                storageService.getCheckDmnModelPath(checkId),
                initialDmnModel,
                "application/xml"
            );
        } catch (Exception e){
            // A check without its DMN model is unusable, and its id is derived from the name, so
            // leaving the document behind would make every retry of the same name collide with it.
            Log.error("Could not save the DMN model of check " + checkId + ", removing the check", e);
            try {
                eligibilityCheckRepository.deleteWorkingCustomCheck(checkId);
            } catch (Exception deleteFailure){
                Log.error("Could not remove check " + checkId + " after its DMN model failed to save",
                        deleteFailure);
            }
            return  Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not save Check"))
                    .build();
        }

        newCheck.setDmnModel(initialDmnModel);
        return Response.ok(newCheck, MediaType.APPLICATION_JSON).build();
    }

    private Response duplicateCheckResponse(CreateCheckRequest request, boolean archived) {
        String message = archived
                ? "A check named \"" + request.name() + "\" in module \"" + request.module()
                    + "\" is archived. Restore it or choose a different name."
                : "You already have a check named \"" + request.name() + "\" in module \""
                    + request.module() + "\".";
        return duplicateCheckResponse(message);
    }

    private Response duplicateCheckResponse(CreateCheckRequest request) {
        return duplicateCheckResponse("A check named \"" + request.name() + "\" in module \""
                + request.module() + "\" already exists.");
    }

    private Response duplicateCheckResponse(String message) {
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", message))
                .build();
    }

    // ========== Single Resource Endpoints ==========

    @GET
    @Path("/{checkId}")
    public Response getCustomCheck(@Context SecurityIdentity identity, @PathParam("checkId") String checkId) {
        String userId = AuthUtils.getUserId(identity);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        char statusIndicator = (checkId != null && !checkId.isEmpty())
                ? checkId.charAt(0)
                : '\0';

        Optional<EligibilityCheck> checkOpt;

        if (statusIndicator == CheckStatus.WORKING.getCode()){
            Log.info("Fetching working custom check: " + checkId + " User:  " + userId);
            checkOpt = eligibilityCheckRepository.getWorkingCustomCheck(userId, checkId);
        } else {
            Log.info("Fetching published custom check: " + checkId + " User:  " + userId);
            checkOpt = eligibilityCheckRepository.getPublishedCustomCheck(userId, checkId);
        }

        if (checkOpt.isEmpty()){
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        EligibilityCheck check = checkOpt.get();

        if (!check.getOwnerId().equals(userId)){
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(check, MediaType.APPLICATION_JSON).build();
    }

    @PATCH
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{checkId}")
    public Response updateCustomCheck(@Context SecurityIdentity identity,
                                      @PathParam("checkId") String checkId,
                                      @Valid EditCheckRequest request){
        String userId = AuthUtils.getUserId(identity);

        // Check if the check exists and is not archived
        Optional<EligibilityCheck> existingCheckOpt = eligibilityCheckRepository.getWorkingCustomCheck(userId, checkId);
        if (existingCheckOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        EligibilityCheck existingCheck = existingCheckOpt.get();

        // Authorization: verify ownership using existing record (not from request)
        if (!userId.equals(existingCheck.getOwnerId())){
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        // Partial update: only update fields that are provided (non-null)
        if (request.description() != null) {
            existingCheck.setDescription(request.description());
        }
        if (request.parameterDefinitions() != null) {
            existingCheck.setParameterDefinitions(request.parameterDefinitions());
        }

        try {
            eligibilityCheckRepository.updateWorkingCustomCheck(existingCheck);
            return Response.ok().entity(existingCheck).build();
        } catch (Exception e){
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "could not update Check"))
                    .build();
        }
    }

    // ========== Sub-Resource Endpoints: DMN ==========

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{checkId}/dmn")
    public Response saveCheckDmn(@Context SecurityIdentity identity,
                                 @PathParam("checkId") String checkId,
                                 @Valid CheckDmnRequest saveDmnRequest){
        String dmnModel = saveDmnRequest.dmnModel();

        String userId = AuthUtils.getUserId(identity);
        Optional<EligibilityCheck> checkOpt = eligibilityCheckRepository.getWorkingCustomCheck(userId, checkId);
        if (checkOpt.isEmpty()){
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        EligibilityCheck check = checkOpt.get();
        if (!check.getOwnerId().equals(userId)){
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try {
            String filePath = storageService.getCheckDmnModelPath(checkId);
            storageService.writeStringToStorage(filePath, dmnModel, "application/xml");
            Log.info("Saved DMN model of check " + checkId + " to storage");

            // TODO: Need to figure out if we are allowing DMN versions to be mutable. If so, we need to update a
            // last_saved field so that we know the check was updated and needs to be recompiled on evaluation

            return Response.ok().build();
        } catch (Exception e){
            Log.info(("Failed to save DMN model for check " + checkId));
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{checkId}/dmn/validate")
    public Response validateCheckDmn(@Context SecurityIdentity identity,
                                     @PathParam("checkId") String checkId,
                                     @Valid CheckDmnRequest validateDmnRequest){
        String dmnModel = validateDmnRequest.dmnModel();

        String userId = AuthUtils.getUserId(identity);
        Optional<EligibilityCheck> checkOpt = eligibilityCheckRepository.getWorkingCustomCheck(userId, checkId);
        if (checkOpt.isEmpty()){
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        EligibilityCheck check = checkOpt.get();
        if (!check.getOwnerId().equals(userId)){
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        if (dmnModel == null || dmnModel.isBlank()){
            return Response.ok(Map.of("errors", List.of("DMN Definition cannot be empty"))).build();
        }

        try {
            HashMap<String, String> dmnDependenciesMap = new HashMap<String, String>();
            List<String> validationErrors = dmnService.validateDmnXml(dmnModel, dmnDependenciesMap, check.getName(), check.getName());
            if (!validationErrors.isEmpty()) {
                validationErrors = validationErrors.stream()
                    .map(error -> error.replaceAll("\\(.*?\\)", ""))
                    .collect(java.util.stream.Collectors.toList());

                return Response.ok(Map.of("errors", validationErrors)).build();
            }

            return Response.ok(Map.of("errors", List.of())).build();
        } catch (Exception e){
            Log.info(("Failed to validate DMN model for check " + checkId));
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ========== Sub-Resource Endpoints: Actions ==========

    @POST
    @Path("/{checkId}/publish")
    public Response publishCustomCheck(@Context SecurityIdentity identity, @PathParam("checkId") String checkId){

        String userId = AuthUtils.getUserId(identity);
        Optional<EligibilityCheck> checkOpt = eligibilityCheckRepository.getWorkingCustomCheck(userId, checkId);
        if (checkOpt.isEmpty()){
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        EligibilityCheck check = checkOpt.get();

        // Authorization
        if (!userId.equals(check.getOwnerId())){
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        // Retrieve DMN Path before incrementing version
        Optional<String> workingDmnOpt = storageService.getStringFromStorage(storageService.getCheckDmnModelPath(check.getId()));
        if (!workingDmnOpt.isPresent()) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "could not find DMN file for working Check"))
                    .build();
        }

        // Extract input schema from DMN
        try {
            String workingDmn = workingDmnOpt.get();
            HashMap<String, String> dmnDependenciesMap = new HashMap<String, String>();
            JsonNode inputSchema = dmnService.extractInputSchema(workingDmn, dmnDependenciesMap, check.getName());
            check.setInputDefinition(inputSchema);
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to extract input schema for check " + check.getId()))
                    .build();
        }

        // A check is created at 1.0.0, so the first publish keeps that version.
        List<EligibilityCheck> publishedChecks;
        try {
            publishedChecks = eligibilityCheckRepository.getPublishedCheckVersions(check);
        } catch (Exception e){
            Log.error("Could not read published versions of check " + check.getId(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "could not read published versions of Check, published check version was not created"))
                    .build();
        }
        check.setVersion(versionForPublish(check, publishedChecks));

        // Update the working check so the extracted input definition and current version are saved.
        try {
            eligibilityCheckRepository.updateWorkingCustomCheck(check);
        } catch (Exception e){
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "could not update working Check, published check version was not created"))
                    .build();
        }

        // Create new published custom check
        try {
            // save published check meta data document
            String publishedCheckId = eligibilityCheckRepository.saveNewPublishedCustomCheck(check);

            // save published check DMN to storage
            if (workingDmnOpt.isPresent()){
                String workingDmn = workingDmnOpt.get();
                storageService.writeStringToStorage(storageService.getCheckDmnModelPath(publishedCheckId), workingDmn, "application/xml");
            } else {
                Log.warn("Could not find working DMN model for check " + check.getId() + ", published check created without DMN model");
            }
        } catch (Exception e){
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "could not create new published custom check version"))
                    .build();
        }

        return Response.ok(check, MediaType.APPLICATION_JSON).build();
    }

    @POST
    @Path("/{checkId}/archive")
    public Response archiveCustomCheck(@Context SecurityIdentity identity, @PathParam("checkId") String checkId) {
        String userId = AuthUtils.getUserId(identity);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        Optional<EligibilityCheck> checkOpt = eligibilityCheckRepository.getWorkingCustomCheck(userId, checkId, true);
        if (checkOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        EligibilityCheck check = checkOpt.get();

        if (!check.getOwnerId().equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        if (check.getIsArchived()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Check is already archived"))
                    .build();
        }

        check.setIsArchived(true);
        try {
            eligibilityCheckRepository.updateWorkingCustomCheck(check);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not archive check"))
                    .build();
        }
    }

    @POST
    @Path("/{checkId}/restore")
    public Response restoreCustomCheck(@Context SecurityIdentity identity, @PathParam("checkId") String checkId) {
        String userId = AuthUtils.getUserId(identity);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        Optional<EligibilityCheck> checkOpt = eligibilityCheckRepository
                .getWorkingCustomCheck(userId, checkId, true);
        if (checkOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        EligibilityCheck check = checkOpt.get();
        if (!check.getOwnerId().equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        if (!check.getIsArchived()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Check is not archived"))
                    .build();
        }

        check.setIsArchived(false);
        try {
            eligibilityCheckRepository.updateWorkingCustomCheck(check);
            return Response.ok(check, MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            Log.error("Could not restore check " + checkId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not restore check"))
                    .build();
        }
    }

    // ========== Sub-Resource Endpoints: Related Resources ==========

    /* Endpoint for returning all Published Check Versions related to a given Working Eligibility Check */
    @GET
    @Path("/{checkId}/versions")
    public Response getPublishedVersionsOfWorkingCheck(@Context SecurityIdentity identity, @PathParam("checkId") String checkId){
        String userId = AuthUtils.getUserId(identity);
        Optional<EligibilityCheck> checkOpt = eligibilityCheckRepository.getWorkingCustomCheck(userId, checkId);
        if (checkOpt.isEmpty()){
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        EligibilityCheck check = checkOpt.get();

        // Authorization
        if (!userId.equals(check.getOwnerId())){
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try {
            List<EligibilityCheck> publishedChecks = eligibilityCheckRepository.getPublishedCheckVersions(check);

            return Response.ok(publishedChecks, MediaType.APPLICATION_JSON).build();
        } catch (Exception e){
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "could not update working Check, published check version was not created"))
                    .build();
        }
    }

    // ========== Private Helper Methods ==========

    /* The first publish keeps the working version; later ones increment past the highest published
       version, so a working version that lags what is already published cannot produce a duplicate
       published id. */
    String versionForPublish(EligibilityCheck check, List<EligibilityCheck> publishedChecks) {
        Set<String> publishedIds = publishedChecks.stream()
                .map(EligibilityCheck::getId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        int[] version = publishedChecks.stream()
                .map(EligibilityCheck::getVersion)
                .flatMap(publishedVersion -> parsePublishedVersion(publishedVersion).stream())
                .max(CheckVersion.ORDER)
                .map(CheckVersion::nextMajor)
                .orElseGet(() -> CheckVersion.parse(check.getVersion()).orElseGet(CheckVersion::initial));

        /* A published id is derived from the version and can never be written twice, so skip past
           versions that are already taken. A version ignored above still holds its id, which is the
           only remaining record that the version was used. */
        while (publishedIds.contains(eligibilityCheckRepository.getPublishedId(check, CheckVersion.format(version)))) {
            version = CheckVersion.nextMajor(version);
        }
        return CheckVersion.format(version);
    }

    private Optional<int[]> parsePublishedVersion(String version) {
        Optional<int[]> parsed = CheckVersion.parse(version);
        if (parsed.isEmpty()) {
            Log.warn("Ignoring malformed published check version: " + version);
        }
        return parsed;
    }
}
