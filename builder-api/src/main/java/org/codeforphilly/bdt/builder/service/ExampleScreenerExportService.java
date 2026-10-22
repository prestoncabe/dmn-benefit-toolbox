package org.codeforphilly.bdt.builder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.codeforphilly.bdt.builder.constants.CollectionNames;
import org.codeforphilly.bdt.builder.constants.FieldNames;
import org.codeforphilly.bdt.builder.model.dto.examplescreener.ScreenerManifest;
import org.codeforphilly.bdt.builder.persistence.FirestoreUtils;
import org.codeforphilly.bdt.builder.persistence.StorageService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class ExampleScreenerExportService {
    private static final Path EXPORT_ROOT = Paths
            .get("src", "main", "resources", "seed-data", "example-screener");
    private static final List<String> EXPORTED_PATHS = List.of("firestore",
            "storage", "manifest.json");

    private final StorageService storageService;
    private final String bucketName;
    private final ObjectMapper objectMapper;

    @Inject
    public ExampleScreenerExportService(StorageService storageService,
            @ConfigProperty(
                    name = "GCS_BUCKET_NAME",
                    defaultValue = "demo-bdt-dev.appspot.com") String bucketName) {
        this.storageService = storageService;
        this.bucketName = bucketName;
        this.objectMapper = new ObjectMapper();
    }

    public ExportSummary exportForUser(String userId) throws Exception {
        resetExportRoot();

        List<Map<String, Object>> workingScreeners = getDocumentsByOwner(
                CollectionNames.WORKING_SCREENER_COLLECTION,
                userId);
        List<Map<String, Object>> workingCustomChecks = getDocumentsByOwner(
                CollectionNames.WORKING_CUSTOM_CHECK_COLLECTION,
                userId);
        List<Map<String, Object>> publishedCustomChecks = getDocumentsByOwner(
                CollectionNames.PUBLISHED_CUSTOM_CHECK_COLLECTION,
                userId);

        int firestoreDocuments = 0;
        ExportScreenerResult exportedScreeners = exportScreeners(
                workingScreeners);
        ExportDocumentResult exportedWorkingChecks = exportChecks(
                CollectionNames.WORKING_CUSTOM_CHECK_COLLECTION,
                workingCustomChecks);
        ExportDocumentResult exportedPublishedChecks = exportChecks(
                CollectionNames.PUBLISHED_CUSTOM_CHECK_COLLECTION,
                publishedCustomChecks);

        firestoreDocuments += exportedScreeners.numExported()
                + exportedWorkingChecks.numExported()
                + exportedPublishedChecks.numExported();

        int storageFiles = 0;
        ExportDocumentResult exportedWorkingCheckDmns = exportCheckDmns(
                workingCustomChecks);
        ExportDocumentResult exportedPublishedCheckDmns = exportCheckDmns(
                publishedCustomChecks);

        storageFiles += exportedWorkingCheckDmns.numExported()
                + exportedPublishedCheckDmns.numExported();

        writeManifest(
                exportedScreeners,
                exportedWorkingChecks,
                exportedPublishedChecks,
                exportedWorkingCheckDmns,
                exportedPublishedCheckDmns);

        Log.info(
                "Exported Firebase seed data for user " + userId + " to "
                        + EXPORT_ROOT.toAbsolutePath().normalize());
        return new ExportSummary(
                EXPORT_ROOT.toAbsolutePath().normalize().toString(),
                workingScreeners.size(), firestoreDocuments, storageFiles);
    }

    private List<Map<String, Object>> getDocumentsByOwner(String collectionName,
            String userId) {
        List<Map<String, Object>> documents = new ArrayList<>(
                FirestoreUtils.getFirestoreDocsByField(
                        collectionName,
                        FieldNames.OWNER_ID,
                        userId));
        documents.sort(
                Comparator.comparing(
                        document -> requiredString(
                                document,
                                FieldNames.ID,
                                collectionName)));
        return documents;
    }

    private ExportScreenerResult exportScreeners(
            List<Map<String, Object>> workingScreeners) throws IOException {
        int numExported = 0;
        List<ScreenerManifest> screeners = new ArrayList<>();

        for (Map<String, Object> screener : workingScreeners) {
            String screenerId = requiredString(
                    screener,
                    FieldNames.ID,
                    CollectionNames.WORKING_SCREENER_COLLECTION);
            Path screenerPath = EXPORT_ROOT.resolve("firestore")
                    .resolve("workingScreener").resolve(screenerId + ".json");
            writeJsonFile(
                    screenerPath,
                    firestoreDocumentForExport(screener, screenerId));
            numExported++;

            ExportDocumentResult exportedBenefits = exportBenefits(screenerId);
            String exportedFormPath = exportScreenerForm(screenerId);

            numExported += exportedBenefits.numExported();

            screeners.add(
                    new ScreenerManifest(skipFirstPath(screenerPath).toString(),
                            exportedBenefits.outputPaths(), exportedFormPath));
        }

        return new ExportScreenerResult(numExported, screeners);
    }

    private ExportDocumentResult exportBenefits(String screenerId)
            throws IOException {
        String collectionPath = CollectionNames.WORKING_SCREENER_COLLECTION
                + "/" + screenerId + "/customBenefit";
        List<Map<String, Object>> benefits = new ArrayList<>(
                FirestoreUtils.getAllDocsInCollection(collectionPath));
        benefits.sort(
                Comparator.comparing(
                        benefit -> requiredString(
                                benefit,
                                FieldNames.ID,
                                collectionPath)));

        int numExported = 0;
        List<String> outputPaths = new ArrayList<>();
        for (Map<String, Object> benefit : benefits) {
            String benefitId = requiredString(
                    benefit,
                    FieldNames.ID,
                    collectionPath);
            Path outputPath = EXPORT_ROOT.resolve("firestore")
                    .resolve("workingScreener").resolve(screenerId)
                    .resolve("customBenefit").resolve(benefitId + ".json");
            writeJsonFile(
                    outputPath,
                    firestoreDocumentForExport(benefit, benefitId));
            numExported++;
            outputPaths.add(skipFirstPath(outputPath).toString());
        }

        return new ExportDocumentResult(numExported, outputPaths);
    }

    private ExportDocumentResult exportChecks(String collectionName,
            List<Map<String, Object>> checks) throws IOException {
        int numExported = 0;
        List<String> checkPaths = new ArrayList<>();

        for (Map<String, Object> check : checks) {
            String checkId = requiredString(
                    check,
                    FieldNames.ID,
                    collectionName);
            Path checkPath = EXPORT_ROOT.resolve("firestore")
                    .resolve(collectionName).resolve(checkId + ".json");
            writeJsonFile(
                    checkPath,
                    firestoreDocumentForExport(check, checkId));
            numExported++;
            checkPaths.add(skipFirstPath(checkPath).toString());
        }
        return new ExportDocumentResult(numExported, checkPaths);
    }

    private String exportScreenerForm(String screenerId) throws IOException {

        Optional<String> formSchema = storageService.getStringFromStorage(
                storageService.getScreenerWorkingFormSchemaPath(screenerId));

        Path formPath = !formSchema.isEmpty()
                ? EXPORT_ROOT.resolve("storage").resolve("form")
                        .resolve("working").resolve(screenerId + ".json")
                : Path.of("");
        if (!formSchema.isEmpty()) {
            writeStringFile(formPath, formSchema.get());
        }

        return skipFirstPath(formPath).toString();
    }

    private ExportDocumentResult exportCheckDmns(
            List<Map<String, Object>> checks) throws IOException {
        int numExported = 0;
        List<String> exportedDmns = new ArrayList<>();
        Set<String> exportedIds = new LinkedHashSet<>();

        for (Map<String, Object> check : checks) {
            String checkId = requiredString(
                    check,
                    FieldNames.ID,
                    "customCheck");
            if (!exportedIds.add(checkId)) {
                continue;
            }

            Optional<String> dmnModel = storageService.getStringFromStorage(
                    storageService.getCheckDmnModelPath(checkId));
            if (dmnModel.isEmpty()) {
                continue;
            }
            Path exportPath = EXPORT_ROOT.resolve("storage").resolve("check")
                    .resolve(checkId + ".dmn");
            writeStringFile(exportPath, dmnModel.get());
            numExported++;
            exportedDmns.add(skipFirstPath(exportPath).toString());
        }

        return new ExportDocumentResult(numExported, exportedDmns);
    }

    private void writeManifest(ExportScreenerResult exportedScreeners,
            ExportDocumentResult exportedWorkingChecks,
            ExportDocumentResult exportedPublishedChecks,
            ExportDocumentResult exportedWorkingCheckDmns,
            ExportDocumentResult exportedPublishedCheckDmns)
            throws IOException {
        List<String> combinedDmnPaths = new ArrayList<>(
                exportedWorkingCheckDmns.outputPaths());
        combinedDmnPaths.addAll(exportedPublishedCheckDmns.outputPaths());

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("screeners", exportedScreeners.screeners());
        manifest.put(
                "workingCustomChecks",
                exportedWorkingChecks.outputPaths());
        manifest.put(
                "publishedCustomChecks",
                exportedPublishedChecks.outputPaths());
        manifest.put("dmnPaths", combinedDmnPaths);

        writeJsonFile(EXPORT_ROOT.resolve("manifest.json"), manifest);
    }

    private Map<String, Object> firestoreDocumentForExport(
            Map<String, Object> rawData, String documentId) {
        Map<String, Object> exportData = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawData.entrySet()) {
            exportData.put(
                    entry.getKey(),
                    normalizeFirestoreValue(entry.getValue()));
        }
        exportData.put("_id", documentId);
        return exportData;
    }

    private Object normalizeFirestoreValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            Map<String, Object> exportedTimestamp = new LinkedHashMap<>();
            exportedTimestamp.put("_type", "timestamp");
            exportedTimestamp
                    .put("value", timestamp.toDate().toInstant().toString());
            return exportedTimestamp;
        }

        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> normalizedMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                normalizedMap.put(
                        String.valueOf(entry.getKey()),
                        normalizeFirestoreValue(entry.getValue()));
            }
            return normalizedMap;
        }

        if (value instanceof List<?> listValue) {
            List<Object> normalizedList = new ArrayList<>();
            for (Object item : listValue) {
                normalizedList.add(normalizeFirestoreValue(item));
            }
            return normalizedList;
        }

        return value;
    }

    private void resetExportRoot() throws IOException {
        resetExportRoot(EXPORT_ROOT);
    }

    // Only the paths the export writes are cleared, so hand-maintained files like the README survive
    static void resetExportRoot(Path exportRoot) throws IOException {
        for (String exportedPath : EXPORTED_PATHS) {
            deleteRecursively(exportRoot.resolve(exportedPath));
        }
        Files.createDirectories(exportRoot);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to delete " + path, e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    private void writeJsonFile(Path path, Object data) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(data));
    }

    private void writeStringFile(Path path, String data) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, data);
    }

    private String requiredString(Map<String, Object> data, String fieldName,
            String context) {
        Object value = data.get(fieldName);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalStateException(
                    "Missing field '" + fieldName + "' for " + context);
        }
        return stringValue;
    }

    private Path skipFirstPath(Path path) {
        int pathCount = path.getNameCount();
        if (pathCount <= 3) {
            return path;
        }
        return path.subpath(3, path.getNameCount());
    }

    private record ExportDocumentResult(int numExported,
            List<String> outputPaths) {
    }

    private record ExportScreenerResult(int numExported,
            List<ScreenerManifest> screeners) {
    }

    public record ExportSummary(String outputPath, int screenerCount,
            int firestoreDocuments, int storageFiles) {
    }
}
