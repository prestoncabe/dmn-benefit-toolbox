package org.codeforphilly.bdt.builder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.BenefitDetail;
import org.codeforphilly.bdt.builder.model.domain.CheckConfig;
import org.codeforphilly.bdt.builder.model.domain.EligibilityCheck;
import org.codeforphilly.bdt.builder.model.domain.Screener;
import org.codeforphilly.bdt.builder.model.dto.examplescreener.Manifest;
import org.codeforphilly.bdt.builder.model.dto.examplescreener.ScreenerManifest;
import org.codeforphilly.bdt.builder.persistence.DocumentAlreadyExistsException;
import org.codeforphilly.bdt.builder.persistence.EligibilityCheckRepository;
import org.codeforphilly.bdt.builder.persistence.ScreenerRepository;
import org.codeforphilly.bdt.builder.persistence.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ExampleScreenerImportService {
    private static final String BUNDLED_SEED_MANIFEST = "seed-data/example-screener/manifest.json";

    private final ScreenerRepository screenerRepository;
    private final EligibilityCheckRepository eligibilityCheckRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    @Inject
    public ExampleScreenerImportService(ScreenerRepository screenerRepository,
            EligibilityCheckRepository eligibilityCheckRepository,
            StorageService storageService) {
        this.screenerRepository = screenerRepository;
        this.eligibilityCheckRepository = eligibilityCheckRepository;
        this.storageService = storageService;
        this.objectMapper = new ObjectMapper();
    }

    public List<String> importForUser(String userId) throws Exception {
        Manifest manifest = readJsonRes(BUNDLED_SEED_MANIFEST, Manifest.class);
        SeedData seedData = loadSeedData(manifest);

        Map<String, String> importedCustomCheckIds = importReferencedCustomChecks(
                seedData,
                userId);
        List<String> importedScreenerIds = new ArrayList<>();

        for (SeedScreenerData seedScreener : seedData.screeners()) {
            List<Benefit> importedBenefits = new ArrayList<>();
            List<BenefitDetail> importedBenefitDetails = new ArrayList<>();
            Map<String, String> benefitIds = new LinkedHashMap<>();
            for (Benefit seedBenefit : seedScreener.benefits()) {
                Benefit importedBenefit = cloneBenefit(
                        seedBenefit,
                        userId,
                        importedCustomCheckIds);
                importedBenefits.add(importedBenefit);
                benefitIds.put(seedBenefit.getId(), importedBenefit.getId());
                importedBenefitDetails.add(
                        new BenefitDetail(importedBenefit.getId(),
                                importedBenefit.getName(),
                                importedBenefit.getDescription()));
            }

            Screener importedScreener = new Screener();
            importedScreener.setOwnerId(userId);
            importedScreener
                    .setScreenerName(seedScreener.screener().getScreenerName());
            importedScreener.setBenefits(importedBenefitDetails);

            String newScreenerId = screenerRepository
                    .saveNewWorkingScreener(importedScreener);
            importedScreener.setId(newScreenerId);

            for (Benefit importedBenefit : importedBenefits) {
                screenerRepository
                        .saveNewCustomBenefit(newScreenerId, importedBenefit);
            }

            if (seedScreener.formSchema() != null) {
                String formPath = storageService
                        .getScreenerWorkingFormSchemaPath(newScreenerId);
                storageService.writeJsonToStorage(
                        formPath,
                        NextStepsFormSchema.remapBenefits(seedScreener.formSchema(), benefitIds));
            }

            importedScreenerIds.add(newScreenerId);
            Log.info(
                    "Imported example screener " + newScreenerId + " for user "
                            + userId);
        }

        return importedScreenerIds;
    }

    private Map<String, String> importReferencedCustomChecks(SeedData seedData,
            String userId) throws Exception {
        Set<String> referencedCustomCheckIds = new LinkedHashSet<>();
        for (SeedScreenerData seedScreener : seedData.screeners()) {
            for (Benefit benefit : seedScreener.benefits()) {
                if (benefit.getChecks() == null) {
                    continue;
                }
                for (CheckConfig checkConfig : benefit.getChecks()) {
                    String sourceCheckId = checkConfig.resolveSourceCheckId();
                    if (sourceCheckId != null
                            && !CheckConfig.isLibraryCheckId(sourceCheckId)) {
                        referencedCustomCheckIds.add(sourceCheckId);
                    }
                }
            }
        }

        Map<String, String> remappedCheckIds = new HashMap<>();
        for (String seedSourceCheckId : referencedCustomCheckIds) {
            SeedCustomCheckVersions seedCustomCheckVersions = findSeedCustomCheckVersions(
                    seedData,
                    seedSourceCheckId);

            EligibilityCheck seedCheck = seedCustomCheckVersions.workingCheck() != null
                    ? seedCustomCheckVersions.workingCheck()
                    : seedCustomCheckVersions.publishedCheck();
            if (seedCheck != null) {
                String exampleSourceId = exampleSourceId(seedCheck);
                Optional<EligibilityCheck> existingCheck = findImportedWorkingCheck(
                        userId, seedCheck, exampleSourceId);
                String workingId = existingCheck.map(EligibilityCheck::getId)
                        .orElseGet(eligibilityCheckRepository::newWorkingId);

                if (seedCustomCheckVersions.workingCheck() != null) {
                    // An earlier import's check may since have been renamed or edited, so keep it
                    if (existingCheck.isEmpty()) {
                        saveWorkingCustomCheck(
                                userId,
                                workingId,
                                exampleSourceId,
                                seedCustomCheckVersions.workingCheck(),
                                seedData.dmnByCheckId());
                    }
                    remappedCheckIds.put(
                            seedCustomCheckVersions.workingCheck().getId(),
                            workingId);
                }

                if (seedCustomCheckVersions.publishedCheck() != null) {
                    String newPublishedId = savePublishedCustomCheck(
                            userId,
                            workingId,
                            exampleSourceId,
                            seedCustomCheckVersions.publishedCheck(),
                            seedData.dmnByCheckId());
                    remappedCheckIds.put(
                            seedCustomCheckVersions.publishedCheck().getId(),
                            newPublishedId);
                }
            }

            if (!remappedCheckIds.containsKey(seedSourceCheckId)) {
                throw new IllegalStateException(
                        "No imported check mapping found for seed check "
                                + seedSourceCheckId);
            }
        }

        return remappedCheckIds;
    }

    private SeedCustomCheckVersions findSeedCustomCheckVersions(
            SeedData seedData, String seedSourceCheckId) {
        EligibilityCheck referencedCheck = seedData.workingCustomChecks()
                .get(seedSourceCheckId);
        if (referencedCheck == null) {
            referencedCheck = seedData.publishedCustomChecks()
                    .get(seedSourceCheckId);
        }
        if (referencedCheck == null) {
            throw new IllegalStateException(
                    "Missing seed custom check for referenced id "
                            + seedSourceCheckId);
        }

        String seedWorkingId = eligibilityCheckRepository
                .getWorkingId(referencedCheck);
        String seedPublishedId = eligibilityCheckRepository
                .getPublishedId(referencedCheck, referencedCheck.getVersion());

        return new SeedCustomCheckVersions(
                seedData.workingCustomChecks().get(seedWorkingId),
                seedData.publishedCustomChecks().get(seedPublishedId));
    }

    /* A check keeps the identity of the example check it came from, because exporting the example
       replaces the seed check's own id with the exporting account's id. */
    private String exampleSourceId(EligibilityCheck seedCheck) {
        if (seedCheck.getExampleSourceId() != null
                && !seedCheck.getExampleSourceId().isBlank()) {
            return seedCheck.getExampleSourceId();
        }
        return eligibilityCheckRepository.getWorkingId(seedCheck);
    }

    /* Finds the check an earlier import of this example created, even if it was renamed since.
       Checks imported before exampleSourceId existed are found by the id that was built from
       their name, as long as they still have that name. */
    private Optional<EligibilityCheck> findImportedWorkingCheck(String userId,
            EligibilityCheck seedCheck, String exampleSourceId) {
        List<EligibilityCheck> userChecks = eligibilityCheckRepository
                .getAllWorkingCustomChecks(userId);
        Optional<EligibilityCheck> importedCheck = userChecks.stream()
                .filter(check -> exampleSourceId
                        .equals(check.getExampleSourceId()))
                .findFirst();
        if (importedCheck.isPresent()) {
            return importedCheck;
        }

        String legacyId = "W-" + userId + "-" + seedCheck.getModule() + "-"
                + seedCheck.getName();
        Optional<EligibilityCheck> sameNameCheck = userChecks.stream()
                .filter(check -> Objects.equals(check.getModule(),
                        seedCheck.getModule())
                        && Objects.equals(check.getName(), seedCheck.getName()))
                .findFirst();
        if (sameNameCheck.isEmpty()) {
            return Optional.empty();
        }
        if (legacyId.equals(sameNameCheck.get().getId())
                && sameNameCheck.get().getExampleSourceId() == null) {
            return sameNameCheck;
        }
        throw new IllegalStateException("User " + userId
                + " already has a check named " + seedCheck.getName()
                + " in module " + seedCheck.getModule());
    }

    private void saveWorkingCustomCheck(String userId, String workingId,
            String exampleSourceId, EligibilityCheck seedCheck,
            Map<String, String> dmnByCheckId) throws Exception {
        EligibilityCheck importedCheck = cloneEligibilityCheck(seedCheck);
        importedCheck.setOwnerId(userId);
        importedCheck.setIsArchived(false);
        importedCheck.setId(workingId);
        importedCheck.setExampleSourceId(exampleSourceId);

        try {
            eligibilityCheckRepository.reserveCheckName(userId,
                    importedCheck.getModule(), importedCheck.getName(),
                    workingId);
        } catch (DocumentAlreadyExistsException e) {
            throw new IllegalStateException("User " + userId
                    + " already has a check named " + importedCheck.getName()
                    + " in module " + importedCheck.getModule(), e);
        }
        eligibilityCheckRepository.saveNewWorkingCustomCheck(importedCheck);
        writeCheckDmn(workingId, seedCheck, dmnByCheckId);
    }

    // Published versions are immutable, so one that already exists is reused as it is
    private String savePublishedCustomCheck(String userId, String workingId,
            String exampleSourceId, EligibilityCheck seedCheck,
            Map<String, String> dmnByCheckId) throws Exception {
        EligibilityCheck importedCheck = cloneEligibilityCheck(seedCheck);
        importedCheck.setOwnerId(userId);
        importedCheck.setIsArchived(false);
        importedCheck.setExampleSourceId(exampleSourceId);
        importedCheck.setId(workingId);
        String newPublishedId = eligibilityCheckRepository
                .getPublishedId(importedCheck, importedCheck.getVersion());
        importedCheck.setId(newPublishedId);

        try {
            eligibilityCheckRepository
                    .saveNewPublishedCustomCheck(importedCheck);
        } catch (DocumentAlreadyExistsException e) {
            return newPublishedId;
        }
        writeCheckDmn(newPublishedId, seedCheck, dmnByCheckId);
        return newPublishedId;
    }

    private void writeCheckDmn(String newCheckId, EligibilityCheck seedCheck,
            Map<String, String> dmnByCheckId) throws Exception {
        String dmnModel = dmnByCheckId.get(seedCheck.getId());
        if ((dmnModel == null || dmnModel.isBlank())
                && seedCheck.getDmnModel() != null) {
            dmnModel = seedCheck.getDmnModel();
        }
        if (dmnModel == null || dmnModel.isBlank()) {
            throw new IllegalStateException(
                    "Missing DMN model for seed check " + seedCheck.getId());
        }

        storageService.writeStringToStorage(
                storageService.getCheckDmnModelPath(newCheckId),
                dmnModel,
                "application/xml");
    }

    private Benefit cloneBenefit(Benefit seedBenefit, String userId,
            Map<String, String> importedCustomCheckIds) {
        Benefit importedBenefit = objectMapper
                .convertValue(seedBenefit, Benefit.class);
        importedBenefit.setId(UUID.randomUUID().toString());
        importedBenefit.setOwnerId(userId);
        importedBenefit.setChecks(
                remapCheckConfigs(
                        seedBenefit.getChecks(),
                        importedCustomCheckIds));
        return importedBenefit;
    }

    private List<CheckConfig> remapCheckConfigs(List<CheckConfig> seedChecks,
            Map<String, String> importedCustomCheckIds) {
        if (seedChecks == null || seedChecks.isEmpty()) {
            return Collections.emptyList();
        }

        List<CheckConfig> importedChecks = new ArrayList<>();
        for (CheckConfig seedCheck : seedChecks) {
            CheckConfig importedCheck = objectMapper
                    .convertValue(seedCheck, CheckConfig.class);
            importedCheck.setCheckId(UUID.randomUUID().toString());

            String sourceCheckId = seedCheck.resolveSourceCheckId();
            if (sourceCheckId != null) {
                if (CheckConfig.isLibraryCheckId(sourceCheckId)) {
                    importedCheck.setSourceCheckId(sourceCheckId);
                } else {
                    String remappedSourceCheckId = importedCustomCheckIds
                            .get(sourceCheckId);
                    if (remappedSourceCheckId == null) {
                        throw new IllegalStateException(
                                "Missing imported custom check id for "
                                        + sourceCheckId);
                    }
                    importedCheck.setSourceCheckId(remappedSourceCheckId);
                }
            }

            importedChecks.add(importedCheck);
        }

        return importedChecks;
    }

    private EligibilityCheck cloneEligibilityCheck(EligibilityCheck seedCheck) {
        return objectMapper.convertValue(seedCheck, EligibilityCheck.class);
    }

    private SeedData loadSeedData(Manifest manifest) throws IOException {
        List<ScreenerManifest> screenerFiles = manifest.screeners();

        List<SeedScreenerData> screeners = new ArrayList<>();
        for (ScreenerManifest screenerManifest : screenerFiles) {
            String screenerPath = screenerManifest.screenerPath();
            Screener screener = readJsonRes(screenerPath, Screener.class);

            List<String> benefitsFiles = screenerManifest.benefits();
            List<Benefit> benefits = new ArrayList<>();
            for (String benefitPath : benefitsFiles) {
                benefits.add(readJsonRes(benefitPath, Benefit.class));
            }

            JsonNode formSchema = screenerManifest.formSchema().length() > 0
                    ? loadFormSchema(screenerManifest.formSchema())
                    : JsonNodeFactory.instance.objectNode();

            screeners.add(new SeedScreenerData(screener, benefits, formSchema));
        }

        return new SeedData(screeners,
                loadChecks(manifest.workingCustomChecks()),
                loadChecks(manifest.publishedCustomChecks()),
                loadDmnFiles(manifest.dmnPaths()));
    }

    private JsonNode loadFormSchema(String formPath) {
        return readJsonRes(formPath, JsonNode.class);
    }

    private Map<String, EligibilityCheck> loadChecks(List<String> checksPaths) {
        Map<String, EligibilityCheck> checksById = new LinkedHashMap<>();

        for (String checkPath : checksPaths) {
            EligibilityCheck check = readJsonRes(
                    checkPath,
                    EligibilityCheck.class);
            checksById.put(check.getId(), check);
        }
        return checksById;
    }

    private Map<String, String> loadDmnFiles(List<String> dmnPaths) {
        Map<String, String> dmnByCheckId = new HashMap<>();

        dmnPaths.stream().forEach(path -> {
            try {
                InputStream stream = getPathStream(path);
                String contents = new String(stream.readAllBytes(),
                        StandardCharsets.UTF_8);
                dmnByCheckId.put(getIdFromPath(path), contents);
            } catch (IOException exception) {
                Log.info("Error reading DMN file: " + path);
            }
        });

        return dmnByCheckId;
    }

    private <T> T readJsonRes(String path, Class<T> clazz) {
        try {
            InputStream stream = getPathStream(path);
            return objectMapper.readValue(stream, clazz);
        } catch (IOException exception) {
            Log.info("Failed to read resource: " + path);
            throw new IllegalStateException(exception);
        }
    }

    private InputStream getPathStream(String path) throws IOException {
        try {
            if (LaunchMode.current() == LaunchMode.DEVELOPMENT) {
                return Files.newInputStream(
                        Path.of("src", "main", "resources").resolve(path));
            } else {
                InputStream stream = Thread.currentThread()
                        .getContextClassLoader().getResourceAsStream(path);
                if (stream == null) {
                    throw new IOException("Resource not found: " + path);
                }
                return stream;
            }
        } catch (IOException exception) {
            throw new IOException("Could not find: " + path);
        }
    }

    private String getIdFromPath(String path) {
        return stripExtension(Paths.get(path).getFileName().toString());
    }

    private String stripExtension(String filename) {
        int extensionIndex = filename.lastIndexOf('.');
        if (extensionIndex == -1) {
            return filename;
        }
        return filename.substring(0, extensionIndex);
    }

    private record SeedData(List<SeedScreenerData> screeners,
            Map<String, EligibilityCheck> workingCustomChecks,
            Map<String, EligibilityCheck> publishedCustomChecks,
            Map<String, String> dmnByCheckId) {
    }

    private record SeedScreenerData(Screener screener, List<Benefit> benefits,
            JsonNode formSchema) {
    }

    private record SeedCustomCheckVersions(EligibilityCheck workingCheck,
            EligibilityCheck publishedCheck) {
    }
}
