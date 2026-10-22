package org.codeforphilly.bdt.builder.controller;

import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.codeforphilly.bdt.builder.auth.AuthUtils;
import org.codeforphilly.bdt.builder.model.domain.*;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.AddCheckRequest;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.CreateCustomBenefitRequest;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.UpdateCheckAliasRequest;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.UpdateCheckParametersRequest;
import org.codeforphilly.bdt.builder.model.dto.custombenefit.UpdateCustomBenefitRequest;
import org.codeforphilly.bdt.builder.persistence.EligibilityCheckRepository;
import org.codeforphilly.bdt.builder.persistence.ScreenerRepository;
import org.codeforphilly.bdt.builder.service.EligibilityCheckAliasService;
import org.codeforphilly.bdt.builder.service.LibraryApiService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Path("/api")
public class CustomBenefitResource {
    private static final ObjectMapper PARAMETER_COMPARISON_MAPPER = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Inject
    ScreenerRepository screenerRepository;

    @Inject
    EligibilityCheckRepository eligibilityCheckRepository;

    @Inject
    LibraryApiService libraryApiMetadataService;  // Inject the singleton bean

    @Inject
    EligibilityCheckAliasService eligibilityCheckAliasService;

    // ========== Collection Endpoints ==========

    @GET
    @Path("/screener/{screenerId}/benefit")
    public Response getCustomBenefits(
        @Context SecurityIdentity identity,
        @PathParam("screenerId") String screenerId
    ) {
        String userId = AuthUtils.getUserId(identity);

        Optional<Screener> screenerOpt = screenerRepository.getWorkingScreener(screenerId);
        if (screenerOpt.isEmpty()) {
            throw new NotFoundException();
        }
        Screener screener = screenerOpt.get();

        if (!isUserAuthorizedForScreener(userId, screener)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try {
            List<Benefit> benefits = screenerRepository.getBenefitsInScreener(screener);
            return Response.ok().entity(benefits).build();
        } catch (Exception e) {
            Log.error(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not fetch benefits"))
                    .build();
        }
    }

    @POST
    @Path("/screener/{screenerId}/benefit")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createCustomBenefit(
        @Context SecurityIdentity identity,
        @PathParam("screenerId") String screenerId,
        @Valid CreateCustomBenefitRequest request
    ) {
        String userId = AuthUtils.getUserId(identity);

        // Create new Benefit with server-generated ID
        String newBenefitId = UUID.randomUUID().toString();
        Benefit newBenefit = new Benefit(
            newBenefitId,
            request.name(),
            request.description(),
            userId,
            Collections.emptyList()
        );
        newBenefit.setNextSteps(request.nextSteps());

        // Create corresponding BenefitDetail
        BenefitDetail benefitDetail = new BenefitDetail(newBenefitId, request.name(), request.description());

        try {
            // Check to make sure screener exists
            Optional<Screener> screenerOpt = screenerRepository.getWorkingScreener(screenerId);
            if (screenerOpt.isEmpty()) {
                Log.error("Screener not found. Screener ID:" + screenerId);
                throw new NotFoundException();
            }

            Screener screener = screenerOpt.get();

            // Authorize action
            if (!isUserAuthorizedForScreener(userId, screener)) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }

            screenerRepository.saveNewCustomBenefit(screenerId, newBenefit);
            screenerRepository.addBenefitDetailToWorkingScreener(screenerId, benefitDetail);
            return Response.ok(newBenefit, MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            Log.error(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not save benefit"))
                    .build();
        }
    }

    // ========== Single Resource Endpoints ==========

    @GET
    @Path("/screener/{screenerId}/benefit/{benefitId}")
    public Response getCustomBenefit(
        @Context SecurityIdentity identity,
        @PathParam("screenerId") String screenerId,
        @PathParam("benefitId") String benefitId
    ) {
        String userId = AuthUtils.getUserId(identity);
        if (!isUserAuthorizedForScreener(userId, screenerId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try {
            Optional<Benefit> benefitOpt = screenerRepository.getCustomBenefit(screenerId, benefitId);
            if (benefitOpt.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok().entity(benefitOpt.get()).build();
        } catch (Exception e) {
            Log.error(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not fetch benefit"))
                    .build();
        }
    }

    @PATCH
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/screener/{screenerId}/benefit/{benefitId}")
    public Response updateCustomBenefit(
        @Context SecurityIdentity identity,
        @PathParam("screenerId") String screenerId,
        @PathParam("benefitId") String benefitId,
        @Valid UpdateCustomBenefitRequest request
    ) {
        String userId = AuthUtils.getUserId(identity);

        if (!isUserAuthorizedForScreener(userId, screenerId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try {
            // Get the existing benefit
            Optional<Benefit> benefitOpt = screenerRepository.getCustomBenefit(screenerId, benefitId);
            if (benefitOpt.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            Benefit existingBenefit = benefitOpt.get();

            // Write only the supplied fields, so a concurrent check edit is not overwritten
            // with the checks read above.
            Map<String, Object> changes = new HashMap<>();
            if (request.name() != null) {
                existingBenefit.setName(request.name());
                changes.put("name", request.name());
            }
            if (request.description() != null) {
                existingBenefit.setDescription(request.description());
                changes.put("description", request.description());
            }
            if (request.nextSteps() != null) {
                existingBenefit.setNextSteps(request.nextSteps());
                changes.put("nextSteps", request.nextSteps());
            }

            screenerRepository.updateCustomBenefitFields(screenerId, benefitId, changes);

            // Also update the BenefitDetail in the screener's benefits list
            Optional<Screener> screenerOpt = request.name() != null || request.description() != null
                ? screenerRepository.getWorkingScreener(screenerId) : Optional.empty();
            if (screenerOpt.isPresent()) {
                Screener screener = screenerOpt.get();
                List<BenefitDetail> benefits = screener.getBenefits();
                if (benefits != null) {
                    List<BenefitDetail> updatedBenefits = new ArrayList<>();
                    for (BenefitDetail detail : benefits) {
                        if (detail.getId().equals(benefitId)) {
                            // Update name and description
                            if (request.name() != null) {
                                detail.setName(request.name());
                            }
                            if (request.description() != null) {
                                detail.setDescription(request.description());
                            }
                        }
                        updatedBenefits.add(detail);
                    }
                    screener.setBenefits(updatedBenefits);
                    screenerRepository.updateWorkingScreener(screener);
                }
            }

            return Response.ok(existingBenefit, MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            Log.error(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not update custom benefit"))
                    .build();
        }
    }

    @DELETE
    @Path("/screener/{screenerId}/benefit/{benefitId}")
    public Response deleteCustomBenefit(
        @Context SecurityIdentity identity,
        @PathParam("screenerId") String screenerId,
        @PathParam("benefitId") String benefitId
    ) {
        try {
            // Check if Screener and Benefit exist
            Optional<Screener> screenerOpt = screenerRepository.getWorkingScreener(screenerId);
            Optional<Benefit> benefitOpt = screenerRepository.getCustomBenefit(screenerId, benefitId);
            if (screenerOpt.isEmpty()) {
                throw new NotFoundException();
            }
            if (benefitOpt.isEmpty()) {
                throw new NotFoundException();
            }

            // Confirm user is authorized to make the change
            String userId = AuthUtils.getUserId(identity);
            Screener screener = screenerOpt.get();
            if (!isUserAuthorizedForScreener(userId, screener)) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }

            // Delete the benefit and remove the benefitDetail from the screener
            screenerRepository.deleteCustomBenefit(screenerId, benefitId);
            List<BenefitDetail> updatedBenefits = screener.getBenefits()
                    .stream()
                    .filter(benefitDetail -> !benefitDetail.getId().equals(benefitId))
                    .toList();
            screener.setBenefits(updatedBenefits);
            screenerRepository.updateWorkingScreener(screener);

            return Response.ok().build();
        } catch (Exception e) {
            Log.error(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not delete custom benefit"))
                    .build();
        }
    }

    // ========== Sub-Resource Endpoints: Checks ==========

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/screener/{screenerId}/benefit/{benefitId}/check")
    public Response addCheckToBenefit(
        @Context SecurityIdentity identity,
        @PathParam("screenerId") String screenerId,
        @PathParam("benefitId") String benefitId,
        @Valid AddCheckRequest request
    ) {
        String userId = AuthUtils.getUserId(identity);

        if (!isUserAuthorizedForScreener(userId, screenerId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try {
            // Get the benefit
            Optional<Benefit> benefitOpt = screenerRepository.getCustomBenefit(screenerId, benefitId);
            if (benefitOpt.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Benefit not found"))
                        .build();
            }

            // Find the EligibilityCheck - first try user's custom checks, then library checks
            Optional<EligibilityCheck> checkOpt = (
                eligibilityCheckRepository.getPublishedCustomCheck(userId, request.checkId())
            );
            if (checkOpt.isEmpty()) {
                checkOpt = libraryApiMetadataService.getById(request.checkId());
            }

            if (checkOpt.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "EligibilityCheck not found"))
                        .build();
            }

            EligibilityCheck check = checkOpt.get();
            Benefit benefit = benefitOpt.get();
            Map<String, Object> parameters = request.parameters() != null
                ? new HashMap<>(request.parameters())
                : new HashMap<>();
            List<String> missingParameters = check.getParameterDefinitions() == null
                ? List.of()
                : check.getParameterDefinitions().stream()
                    .filter(ParameterDefinition::isRequired)
                    .filter(parameter -> !usesEvaluationDateDefault(check, parameter))
                    .filter(parameter -> isBlankParameter(parameters.get(parameter.getKey())))
                    .map(ParameterDefinition::getKey)
                    .toList();
            if (!missingParameters.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                        "error", "Required check parameters are missing",
                        "parameters", missingParameters
                    ))
                    .build();
            }

            // Create CheckConfig snapshot from the EligibilityCheck
            String newCheckConfigId = UUID.randomUUID().toString();
            CheckConfig checkConfig = new CheckConfig(
                newCheckConfigId,
                check.getId(),
                check.getName(),
                check.getVersion(),
                check.getModule(),
                check.getEvaluationUrl(),
                check.getInputDefinition(),
                check.getParameterDefinitions() != null ? check.getParameterDefinitions() : List.of(),
                parameters
            );
            // Without a generated alias the check's original name is displayed instead
            Optional<String> aliasName = eligibilityCheckAliasService.generate(check.getName(), parameters);
            aliasName.ifPresent(checkConfig::setAliasName);
            checkConfig.setAliasGenerated(aliasName.isPresent());

            // Add the check to the benefit
            List<CheckConfig> checks = benefit.getChecks();
            if (checks == null) {
                checks = new ArrayList<>();
            } else {
                checks = new ArrayList<>(checks);
            }
            checks.add(checkConfig);
            benefit.setChecks(checks);

            // Save the updated benefit
            screenerRepository.updateCustomBenefit(screenerId, benefit);

            return Response.ok(Map.of("aliasGenerated", aliasName.isPresent())).build();
        } catch (Exception e) {
            Log.error(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not add check to benefit"))
                    .build();
        }
    }

    @DELETE
    @Path("/screener/{screenerId}/benefit/{benefitId}/check/{checkId}")
    public Response removeCheckFromBenefit(
        @Context SecurityIdentity identity,
        @PathParam("screenerId") String screenerId,
        @PathParam("benefitId") String benefitId,
        @PathParam("checkId") String checkId
    ) {
        String userId = AuthUtils.getUserId(identity);

        if (!isUserAuthorizedForScreener(userId, screenerId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try {
            // Get the benefit
            Optional<Benefit> benefitOpt = screenerRepository.getCustomBenefit(screenerId, benefitId);
            if (benefitOpt.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Benefit not found"))
                        .build();
            }

            Benefit benefit = benefitOpt.get();
            List<CheckConfig> checks = benefit.getChecks();

            if (checks == null || checks.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Check not found in benefit"))
                        .build();
            }

            // Remove the check with the matching checkId
            List<CheckConfig> updatedChecks = checks.stream()
                    .filter(check -> !check.getCheckId().equals(checkId))
                    .toList();

            if (updatedChecks.size() == checks.size()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Check not found in benefit"))
                        .build();
            }

            benefit.setChecks(new ArrayList<>(updatedChecks));

            // Save the updated benefit
            screenerRepository.updateCustomBenefit(screenerId, benefit);

            return Response.ok().build();
        } catch (Exception e) {
            Log.error(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not remove check from benefit"))
                    .build();
        }
    }

    @PATCH
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/screener/{screenerId}/benefit/{benefitId}/check/{checkId}/parameters")
    public Response updateCheckParameters(
        @Context SecurityIdentity identity,
        @PathParam("screenerId") String screenerId,
        @PathParam("benefitId") String benefitId,
        @PathParam("checkId") String checkId,
        UpdateCheckParametersRequest request
    ) {
        String userId = AuthUtils.getUserId(identity);

        if (!isUserAuthorizedForScreener(userId, screenerId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try {
            // Get the benefit
            Optional<Benefit> benefitOpt = screenerRepository.getCustomBenefit(screenerId, benefitId);
            if (benefitOpt.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Benefit not found"))
                        .build();
            }

            Benefit benefit = benefitOpt.get();
            List<CheckConfig> checks = benefit.getChecks();

            if (checks == null || checks.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Check not found in benefit"))
                        .build();
            }

            // Find and update the check with the matching checkId
            Boolean checkUpdated = false;
            boolean aliasCleared = false;
            List<CheckConfig> checkListAfterUpdate = new ArrayList<>();
            for (CheckConfig check : checks) {
                if (check.getCheckId().equals(checkId)) {
                    Map<String, Object> parameters = request.parameters() != null ? request.parameters() : new HashMap<>();
                    boolean parametersChanged = !sameParameterValues(check.getParameters(), parameters);
                    check.setParameters(parameters);
                    // A generated alias describes the old parameter values, so refresh it.
                    // Hand-written aliases are left alone.
                    if (parametersChanged && check.isAliasGenerated()) {
                        Optional<String> aliasName = eligibilityCheckAliasService.generate(check.getCheckName(), parameters);
                        check.setAliasName(aliasName.orElse(null));
                        check.setAliasGenerated(aliasName.isPresent());
                        aliasCleared = aliasName.isEmpty();
                    }
                    checkUpdated = true;
                }
                checkListAfterUpdate.add(check);
            }

            if (!checkUpdated) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Check not found in benefit"))
                        .build();
            }

            benefit.setChecks(checkListAfterUpdate);

            // Save the updated benefit
            screenerRepository.updateCustomBenefit(screenerId, benefit);

            return Response.ok(Map.of("aliasCleared", aliasCleared)).build();
        } catch (Exception e) {
            Log.error(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not update check parameters"))
                    .build();
        }
    }

    @PATCH
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/screener/{screenerId}/benefit/{benefitId}/check/{checkId}/alias")
    public Response updateCheckAlias(
        @Context SecurityIdentity identity,
        @PathParam("screenerId") String screenerId,
        @PathParam("benefitId") String benefitId,
        @PathParam("checkId") String checkId,
        UpdateCheckAliasRequest request
    ) {
        String userId = AuthUtils.getUserId(identity);

        if (!isUserAuthorizedForScreener(userId, screenerId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try {
            // Get the benefit
            Optional<Benefit> benefitOpt = screenerRepository.getCustomBenefit(screenerId, benefitId);
            if (benefitOpt.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Benefit not found"))
                        .build();
            }

            Benefit benefit = benefitOpt.get();
            List<CheckConfig> checks = benefit.getChecks();

            if (checks == null || checks.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Check not found in benefit"))
                        .build();
            }

            // Find and update the check with the matching checkId
            Boolean checkUpdated = false;
            List<CheckConfig> checkListAfterUpdate = new ArrayList<>();
            for (CheckConfig check : checks) {
                if (check.getCheckId().equals(checkId)) {
                    check.setAliasName(request.aliasName());
                    check.setAliasGenerated(
                        request.aliasName() != null && Boolean.TRUE.equals(request.aliasGenerated())
                    );
                    checkUpdated = true;
                }
                checkListAfterUpdate.add(check);
            }

            if (!checkUpdated) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Check not found in benefit"))
                        .build();
            }

            benefit.setChecks(checkListAfterUpdate);

            // Save the updated benefit
            screenerRepository.updateCustomBenefit(screenerId, benefit);

            return Response.ok().build();
        } catch (Exception e) {
            Log.error(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Could not update check alias"))
                    .build();
        }
    }

    @POST
    @Path("/screener/{screenerId}/benefit/{benefitId}/check/{checkId}/alias/generate")
    public Response generateCheckAlias(
        @Context SecurityIdentity identity,
        @PathParam("screenerId") String screenerId,
        @PathParam("benefitId") String benefitId,
        @PathParam("checkId") String checkId
    ) {
        String userId = AuthUtils.getUserId(identity);

        if (!isUserAuthorizedForScreener(userId, screenerId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try {
            Optional<Benefit> benefitOpt = screenerRepository.getCustomBenefit(screenerId, benefitId);
            if (benefitOpt.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Benefit not found"))
                    .build();
            }

            List<CheckConfig> checks = benefitOpt.get().getChecks();
            Optional<CheckConfig> checkOpt = (checks == null ? List.<CheckConfig>of() : checks).stream()
                .filter(check -> check.getCheckId().equals(checkId))
                .findFirst();
            if (checkOpt.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Check not found in benefit"))
                    .build();
            }

            CheckConfig check = checkOpt.get();
            Optional<String> aliasName = eligibilityCheckAliasService.generate(check.getCheckName(), check.getParameters());
            if (aliasName.isEmpty()) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Could not generate check alias"))
                    .build();
            }
            return Response.ok(Map.of("aliasName", aliasName.get())).build();
        } catch (Exception e) {
            Log.error(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", "Could not generate check alias"))
                .build();
        }
    }

    // ========== Private Helper Methods ==========

    private static boolean usesEvaluationDateDefault(EligibilityCheck check, ParameterDefinition parameter) {
        return check.getEvaluationUrl() != null
            && parameter.getKey().equals("asOfDate")
            && "date".equals(parameter.getType());
    }

    // Compares as JSON so numbers read back from Firestore as Long still match
    // the Integer values sent by the frontend.
    private static boolean sameParameterValues(Map<String, Object> current, Map<String, Object> updated) {
        try {
            return PARAMETER_COMPARISON_MAPPER.writeValueAsString(current == null ? Map.of() : current)
                .equals(PARAMETER_COMPARISON_MAPPER.writeValueAsString(updated));
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private static boolean isBlankParameter(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String stringValue) {
            return stringValue.isBlank();
        }
        return value instanceof List<?> listValue && listValue.isEmpty();
    }

    private boolean isUserAuthorizedForScreener(String userId, String screenerId) {
        Optional<Screener> screenerOptional = screenerRepository.getWorkingScreenerMetaDataOnly(screenerId);
        if (screenerOptional.isEmpty()) {
            return false;
        }
        return isUserAuthorizedForScreener(userId, screenerOptional.get());
    }

    private boolean isUserAuthorizedForScreener(String userId, Screener screener) {
        String ownerId = screener.getOwnerId();
        return userId != null && userId.equals(ownerId);
    }
}
