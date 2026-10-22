package org.codeforphilly.bdt.builder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import org.codeforphilly.bdt.builder.model.domain.*;
import org.codeforphilly.bdt.builder.model.dto.screener.ScreenerTransfer;
import org.codeforphilly.bdt.builder.persistence.*;

import java.util.*;

@ApplicationScoped
public class ScreenerTransferService {
    private final ScreenerRepository screeners;
    private final EligibilityCheckRepository checks;
    private final StorageService storage;
    private final LibraryApiService library;
    private final DmnService dmn;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public ScreenerTransferService(ScreenerRepository screeners, EligibilityCheckRepository checks,
            StorageService storage, LibraryApiService library, DmnService dmn) {
        this.screeners = screeners;
        this.checks = checks;
        this.storage = storage;
        this.library = library;
        this.dmn = dmn;
    }

    public ScreenerTransfer exportScreener(String owner, String id) throws Exception {
        // Authorize before fetching the form, benefits, or check models.
        Screener screener = screeners.getWorkingScreenerMetaDataOnly(id).orElseThrow(NotFoundException::new);
        if (!owner.equals(screener.getOwnerId())) throw new ForbiddenException();
        // A failed read must fail the export rather than silently drop the form.
        Map<String, Object> formSchema = storage.readFormSchema(storage.getScreenerWorkingFormSchemaPath(id)).orElse(null);
        List<Benefit> benefits = new ArrayList<>();
        Map<String, Benefit> byId = new HashMap<>();
        for (Benefit benefit : screeners.getBenefitsInScreener(screener)) byId.put(benefit.getId(), benefit);
        // Export what the editor shows: its benefit list, in order. Benefit writes are not atomic with that
        // list, so skip entries without a stored benefit and stored benefits the list no longer includes.
        if (screener.getBenefits() != null) {
            for (BenefitDetail detail : screener.getBenefits()) {
                Benefit benefit = byId.remove(detail.getId());
                if (benefit == null) {
                    Log.warn("Exporting screener " + id + " without missing benefit " + detail.getId());
                    continue;
                }
                benefits.add(copy(benefit, Benefit.class));
            }
        }
        if (!byId.isEmpty()) Log.warn("Exporting screener " + id + " without unlisted benefits " + byId.keySet());
        Map<String, EligibilityCheck> customChecks = new LinkedHashMap<>();
        for (Benefit benefit : benefits) {
            benefit.setOwnerId(null);
            for (CheckConfig config : configs(benefit)) {
                String source = sourceId(config);
                if (CheckConfig.isLibraryCheckId(source)) continue;
                // Only the versions the screener uses are shared, never the author's unpublished draft.
                if (customChecks.containsKey(source)) continue;
                customChecks.put(source, portableCheck(loadCheck(owner, source)));
            }
        }
        return new ScreenerTransfer(ScreenerTransfer.FORMAT, ScreenerTransfer.VERSION,
                screener.getScreenerName(), mapper.valueToTree(formSchema),
                benefits, new ArrayList<>(customChecks.values()));
    }

    private EligibilityCheck loadCheck(String owner, String id) {
        EligibilityCheck check = (id.startsWith("W-")
                ? checks.getWorkingCustomCheck(owner, id, true)
                : checks.getPublishedCustomCheck(owner, id, true))
                .orElseThrow(() -> new IllegalStateException("Missing custom check " + id));
        requireOwnedCheck(owner, check);
        return check;
    }

    private void requireOwnedCheck(String owner, EligibilityCheck check) {
        // Repository lookups do not themselves enforce ownership.
        if (!owner.equals(check.getOwnerId())) throw new ForbiddenException();
    }

    private EligibilityCheck portableCheck(EligibilityCheck original) {
        EligibilityCheck check = copy(original, EligibilityCheck.class);
        if (blank(check.getDmnModel())) throw new IllegalStateException("Missing custom check DMN model");
        check.setOriginCheckId(originId(original));
        check.setOwnerId(null);
        check.setExampleSourceId(null);
        check.setDatePublished(null);
        check.setEvaluationUrl(null);
        return check;
    }

    public Screener importScreener(String owner, ScreenerTransfer transfer) throws Exception {
        // Validate the complete file and resolve library references before any writes.
        Map<String, EligibilityCheck> originals = validate(transfer);
        String name = transfer.screenerName().strip();
        // Match the transactional check on save, so this early rejection never disagrees with it.
        if (screeners.getWorkingScreeners(owner).stream().anyMatch(existing ->
                Screener.normalizeName(name).equals(Screener.normalizeName(existing.getScreenerName())))) {
            throw new DuplicateScreenerNameException();
        }
        validateModels(originals.values());
        Map<String, EligibilityCheck> libraryChecks = new HashMap<>();
        for (Benefit benefit : transfer.benefits()) {
            for (CheckConfig config : configs(benefit)) {
                String source = sourceId(config);
                if (CheckConfig.isLibraryCheckId(source) && !libraryChecks.containsKey(source)) {
                    libraryChecks.put(source, library.getScreenerCheckById(source).orElseThrow(() ->
                            new BadRequestException("Library check " + source + " is unavailable on this server.")));
                }
            }
        }
        // Decide every reuse/conflict before reserving names or writing any artifact.
        List<EligibilityCheck> existing = matchableChecks(checks.getCustomChecksForImport(owner));
        CheckImportPlan plan = planChecks(owner, originals, existing, transfer);
        Deque<Cleanup> cleanup = new ArrayDeque<>();
        try {
            List<EligibilityCheck> occupiedNames = new ArrayList<>(existing.stream()
                    .filter(check -> owner.equals(check.getOwnerId()) && check.getId().startsWith("W-")).toList());
            for (FamilyImport family : plan.families().values()) {
                if (!family.createWorking()) continue;
                EligibilityCheck working = family.working();
                if (family.newFamily()) {
                    try {
                        checks.reserveImportIdentity(owner, working.getOriginCheckId(), working.getId());
                    } catch (DocumentAlreadyExistsException concurrentImport) {
                        throw new CustomCheckImportConflictException("Another import is creating check "
                                + working.getName() + ". Please retry the import.");
                    }
                    cleanup.push(() -> checks.releaseImportIdentity(owner, working.getOriginCheckId(), working.getId()));
                }
                reserveImportedName(working, occupiedNames);
                cleanup.push(() -> checks.releaseCheckName(owner, working.getModule(), working.getName(), working.getId()));
                occupiedNames.add(working);
                saveCheck(working, false, cleanup);
            }
            for (EligibilityCheck published : plan.publishedToCreate()) {
                // A new family's module may have changed while reserving its name.
                String workingId = checks.getWorkingId(published);
                for (FamilyImport family : plan.families().values()) {
                    if (family.working().getId().equals(workingId)) {
                        published.setModule(family.working().getModule());
                        break;
                    }
                }
                saveCheck(published, true, cleanup);
            }
            List<Benefit> benefits = new ArrayList<>();
            List<BenefitDetail> details = new ArrayList<>();
            Map<String, String> benefitIds = new LinkedHashMap<>();
            for (Benefit original : transfer.benefits()) {
                Benefit benefit = copy(original, Benefit.class);
                benefit.setId(UUID.randomUUID().toString());
                benefitIds.put(original.getId(), benefit.getId());
                benefit.setOwnerId(owner);
                for (CheckConfig config : configs(benefit)) {
                    String source = sourceId(config);
                    config.setCheckId(UUID.randomUUID().toString());
                    if (CheckConfig.isLibraryCheckId(source)) {
                        // Use this server's endpoint, never a URL supplied by the file.
                        config.setSourceCheckId(source);
                        config.setEvaluationUrl(libraryChecks.get(source).getEvaluationUrl());
                    } else {
                        EligibilityCheck target = plan.destinations().get(source);
                        config.setSourceCheckId(target.getId());
                        config.setEvaluationUrl(null);
                        config.setCheckModule(target.getModule());
                    }
                }
                benefits.add(benefit);
                details.add(new BenefitDetail(benefit.getId(), benefit.getName(), benefit.getDescription()));
            }
            Screener screener = Screener.create(owner, name, null);
            // Make the draft visible only once all its artifacts have been saved.
            screener.setId(UUID.randomUUID().toString());
            String id = screener.getId();
            for (Benefit benefit : benefits) {
                cleanup.push(() -> screeners.deleteCustomBenefit(id, benefit.getId()));
                screeners.saveNewCustomBenefit(id, benefit);
            }
            if (transfer.formSchema() != null && !transfer.formSchema().isNull()) {
                String path = storage.getScreenerWorkingFormSchemaPath(id);
                cleanup.push(() -> storage.deleteFile(path));
                storage.writeStringToStorage(path, mapper.writeValueAsString(
                        NextStepsFormSchema.remapBenefits(transfer.formSchema(), benefitIds)), "application/json");
            }
            screener.setBenefits(details);
            cleanup.push(() -> screeners.deleteWorkingScreener(id));
            screeners.saveNewWorkingScreener(screener);
            return screener;
        } catch (Exception failure) {
            while (!cleanup.isEmpty()) {
                try { cleanup.pop().run(); }
                catch (Exception rollbackFailure) { Log.error("Failed to clean up screener import", rollbackFailure); }
            }
            throw failure;
        }
    }

    /* A stored check whose ID does not match its version has no family to match, so import leaves it alone. */
    private List<EligibilityCheck> matchableChecks(List<EligibilityCheck> stored) {
        List<EligibilityCheck> matchable = new ArrayList<>();
        for (EligibilityCheck check : stored) {
            try {
                checks.getWorkingId(check);
                matchable.add(check);
            } catch (IllegalArgumentException malformed) {
                Log.warn("Ignoring custom check " + check.getId() + " while importing: " + malformed.getMessage());
            }
        }
        return matchable;
    }

    private String originId(EligibilityCheck check) {
        if (!blank(check.getOriginCheckId())) return check.getOriginCheckId();
        if (!blank(check.getExampleSourceId())) return check.getExampleSourceId();
        return checks.getWorkingId(check);
    }

    private CheckImportPlan planChecks(String owner, Map<String, EligibilityCheck> originals,
                                       List<EligibilityCheck> existing, ScreenerTransfer transfer) throws Exception {
        Map<String, EligibilityCheck> draftSources = new LinkedHashMap<>();
        Set<String> referencedWorking = new HashSet<>();
        for (Benefit benefit : transfer.benefits()) {
            for (CheckConfig config : configs(benefit)) {
                if (sourceId(config).startsWith("W-")) referencedWorking.add(sourceId(config));
            }
        }
        for (EligibilityCheck original : originals.values()) {
            String family = checks.getWorkingId(original);
            EligibilityCheck candidate = draftSources.get(family);
            if (candidate == null || original.getId().startsWith("W-")
                    || (!candidate.getId().startsWith("W-")
                        && CheckVersion.compare(original.getVersion(), candidate.getVersion()) > 0)) {
                draftSources.put(family, original);
            }
        }
        Map<String, FamilyImport> families = new LinkedHashMap<>();
        Map<String, EligibilityCheck> destinations = new HashMap<>();
        List<EligibilityCheck> publishedToCreate = new ArrayList<>();
        for (var entry : draftSources.entrySet()) {
            String sourceFamily = entry.getKey();
            String origin = originId(entry.getValue());
            List<EligibilityCheck> matches = existing.stream()
                    .filter(check -> owner.equals(check.getOwnerId()))
                    .filter(check -> origin.equals(originId(check)) || origin.equals(checks.getWorkingId(check))
                            || sourceFamily.equals(checks.getWorkingId(check)))
                    .toList();
            Set<String> matchingFamilies = new HashSet<>();
            for (EligibilityCheck match : matches) matchingFamilies.add(checks.getWorkingId(match));
            if (matchingFamilies.size() > 1)
                throw new CustomCheckImportConflictException("Several checks share the original identity of "
                        + entry.getValue().getName() + ". Resolve those duplicates before importing.");
            boolean newFamily = matchingFamilies.isEmpty();
            String workingId = newFamily ? checks.newWorkingId() : matchingFamilies.iterator().next();
            EligibilityCheck working = matches.stream().filter(check -> workingId.equals(check.getId()))
                    .findFirst().orElse(null);
            boolean createWorking = working == null;
            if (createWorking) {
                working = copy(entry.getValue(), EligibilityCheck.class);
                working.setId(workingId);
                working.setOriginCheckId(origin);
                resetCheckOwnership(working, owner);
            } else if (referencedWorking.contains(sourceFamily)) {
                working = loadCheck(owner, workingId);
                requireSameContent(entry.getValue(), working, true);
            }
            families.put(sourceFamily, new FamilyImport(working, createWorking, newFamily));
            destinations.put(sourceFamily, working);
        }
        for (EligibilityCheck original : originals.values()) {
            if (!original.getId().startsWith("P-")) continue;
            FamilyImport family = families.get(checks.getWorkingId(original));
            String id = checks.getPublishedId(family.working(), original.getVersion());
            EligibilityCheck metadata = existing.stream().filter(check -> id.equals(check.getId())).findFirst().orElse(null);
            EligibilityCheck target;
            if (metadata != null) {
                requireOwnedCheck(owner, metadata);
                target = loadCheck(owner, id);
                requireSameContent(original, target, false);
            } else {
                target = copy(original, EligibilityCheck.class);
                target.setId(id);
                target.setOriginCheckId(originId(original));
                target.setModule(family.working().getModule());
                resetCheckOwnership(target, owner);
                publishedToCreate.add(target);
            }
            destinations.put(original.getId(), target);
        }
        return new CheckImportPlan(families, destinations, publishedToCreate);
    }

    private void requireSameContent(EligibilityCheck incoming, EligibilityCheck existing, boolean working) {
        boolean same = false;
        try {
            same = Objects.equals(incoming.getName(), existing.getName())
                    && Objects.equals(incoming.getVersion(), existing.getVersion())
                    && mapper.valueToTree(incoming.getParameterDefinitions() == null ? List.of() : incoming.getParameterDefinitions())
                        .equals(mapper.valueToTree(existing.getParameterDefinitions() == null ? List.of() : existing.getParameterDefinitions()))
                    && !blank(existing.getDmnModel())
                    && new CustomCheckDmnRenameValidator().equivalentModels(incoming.getDmnModel(), existing.getDmnModel());
        } catch (Exception invalidModel) {
            // An unreadable existing model cannot safely be reused.
        }
        if (!same) throw new CustomCheckImportConflictException(working
                ? "The imported screener uses a draft of check " + incoming.getName()
                    + " that differs from your draft. Publish and use a check version before exporting."
                : "Check " + incoming.getName() + " version " + incoming.getVersion()
                    + " already exists with different rules or parameter definitions. The screener was not imported.");
    }

    private void saveCheck(EligibilityCheck check, boolean published, Deque<Cleanup> cleanup) throws Exception {
        String dmn = check.getDmnModel();
        EligibilityCheck metadata = copy(check, EligibilityCheck.class);
        metadata.setDmnModel(null);
        // Claim the document before touching its XML. A losing concurrent create never overwrites it.
        try {
            if (published) checks.saveNewPublishedCustomCheck(metadata);
            else checks.saveNewWorkingCustomCheck(metadata);
        } catch (DocumentAlreadyExistsException concurrentCreate) {
            if (!published) throw new CustomCheckImportConflictException("Another import created this check. Please retry.");
            EligibilityCheck winner = loadCheck(check.getOwnerId(), check.getId());
            requireSameContent(check, winner, false);
            check.setModule(winner.getModule());
            return;
        }
        cleanup.push(() -> {
            if (published) checks.deletePublishedCustomCheck(check.getId());
            else checks.deleteWorkingCustomCheck(check.getId());
        });
        String path = storage.getCheckDmnModelPath(check.getId());
        cleanup.push(() -> storage.deleteFile(path));
        storage.writeStringToStorage(path, dmn, "application/xml");
    }

    private record FamilyImport(EligibilityCheck working, boolean createWorking, boolean newFamily) {}
    private record CheckImportPlan(Map<String, FamilyImport> families, Map<String, EligibilityCheck> destinations,
                                   List<EligibilityCheck> publishedToCreate) {}

    private void resetCheckOwnership(EligibilityCheck check, String owner) {
        check.setOwnerId(owner);
        check.setIsArchived(false);
        check.setExampleSourceId(null);
        check.setDatePublished(null);
        check.setEvaluationUrl(null);
    }

    private void reserveImportedName(EligibilityCheck check, List<EligibilityCheck> existing) throws Exception {
        String module = check.getModule();
        for (int attempt = 1; attempt <= 100; attempt++) {
            String candidate = attempt == 1 ? module : module + " (imported " + attempt + ")";
            if (existing.stream().anyMatch(other -> Objects.equals(candidate, other.getModule())
                    && Objects.equals(check.getName(), other.getName()))) continue;
            try {
                checks.reserveCheckName(check.getOwnerId(), candidate, check.getName(), check.getId());
                check.setModule(candidate);
                return;
            } catch (DocumentAlreadyExistsException collision) {
                // Another import or check creation may have claimed this name since the read.
            }
        }
        throw new BadRequestException("Could not find an available module for imported check " + check.getName());
    }

    private Map<String, EligibilityCheck> validate(ScreenerTransfer transfer) {
        if (transfer == null || !ScreenerTransfer.FORMAT.equals(transfer.format())
                || transfer.formatVersion() != ScreenerTransfer.VERSION)
            throw new BadRequestException("This file is not a supported BDT screener export.");
        if (blank(transfer.screenerName()) || transfer.benefits() == null || transfer.customChecks() == null)
            throw new BadRequestException("The screener file is missing required fields.");
        if (transfer.formSchema() != null && !transfer.formSchema().isNull() && !transfer.formSchema().isObject())
            throw new BadRequestException("The form schema must be an object.");
        Map<String, EligibilityCheck> byId = new LinkedHashMap<>();
        for (EligibilityCheck check : transfer.customChecks()) {
            if (check == null || blank(check.getId()) || blank(check.getName()) || blank(check.getModule())
                    || blank(check.getVersion()) || CheckVersion.parse(check.getVersion()).isEmpty()
                    || blank(check.getDmnModel())
                    || !(check.getId().startsWith("W-") || check.getId().startsWith("P-")))
                throw new BadRequestException("A custom check is incomplete.");
            try {
                checks.getWorkingId(check);
                new CustomCheckDmnRenameValidator().validate(check.getDmnModel(), check.getDmnModel(),
                        check.getName(), check.getName());
            } catch (Exception invalid) { throw new BadRequestException("Invalid DMN model or ID for check " + check.getName()); }
            if (byId.putIfAbsent(check.getId(), check) != null)
                throw new BadRequestException("Duplicate custom check ID.");
        }
        Map<String, String> familyOrigins = new HashMap<>();
        Map<String, String> originFamilies = new HashMap<>();
        for (EligibilityCheck check : byId.values()) {
            String family = checks.getWorkingId(check);
            String origin = originId(check);
            String priorOrigin = familyOrigins.putIfAbsent(family, origin);
            String priorFamily = originFamilies.putIfAbsent(origin, family);
            if ((priorOrigin != null && !priorOrigin.equals(origin))
                    || (priorFamily != null && !priorFamily.equals(family)))
                throw new BadRequestException("Custom check family identities are inconsistent.");
        }
        Set<String> benefitIds = new HashSet<>();
        Set<String> referencedFamilies = new HashSet<>();
        Set<String> referencedIds = new HashSet<>();
        for (Benefit benefit : transfer.benefits()) {
            if (benefit == null || blank(benefit.getId()) || blank(benefit.getName()) || !benefitIds.add(benefit.getId()))
                throw new BadRequestException("Invalid or duplicate benefit.");
            for (CheckConfig config : configs(benefit)) {
                if (config == null || blank(config.getCheckName())) throw new BadRequestException("Invalid configured check.");
                String source = sourceId(config);
                if (CheckConfig.isLibraryCheckId(source)) continue;
                EligibilityCheck check = byId.get(source);
                if (check == null) throw new BadRequestException("Missing custom check " + source);
                if (!Objects.equals(config.getCheckName(), check.getName())
                        || !Objects.equals(config.getCheckVersion(), check.getVersion()))
                    throw new BadRequestException("Custom check configuration does not match its model.");
                referencedIds.add(source);
                referencedFamilies.add(checks.getWorkingId(check));
            }
        }
        for (EligibilityCheck check : byId.values()) {
            if (!referencedFamilies.contains(checks.getWorkingId(check))
                    || (check.getId().startsWith("P-") && !referencedIds.contains(check.getId())))
                throw new BadRequestException("The file contains an unrelated custom check.");
        }
        return byId;
    }

    /* Compiles each model as publishing does and replaces the file's input definition with the one derived
       from it, so a check whose model cannot run, or whose inputs disagree with it, is never created. */
    private void validateModels(Collection<EligibilityCheck> imported) {
        Map<String, com.fasterxml.jackson.databind.JsonNode> derived = new HashMap<>();
        for (EligibilityCheck check : imported) {
            // Versions of one check often share a model, so compile each distinct model once.
            String key = check.getName() + "\u0000" + check.getDmnModel();
            if (!derived.containsKey(key)) {
                List<String> errors;
                try {
                    errors = dmn.validateDmnXml(check.getDmnModel(), Map.of(), check.getName(), check.getName());
                    if (errors.isEmpty())
                        derived.put(key, dmn.extractInputSchema(check.getDmnModel(), Map.of(), check.getName()));
                } catch (Exception failure) {
                    errors = List.of("the model could not be compiled.");
                }
                if (!errors.isEmpty())
                    throw new BadRequestException("Invalid DMN model for check " + check.getName() + ": "
                            + errors.getFirst());
            }
            check.setInputDefinition(derived.get(key));
        }
    }

    private List<CheckConfig> configs(Benefit benefit) {
        return benefit.getChecks() == null ? List.of() : benefit.getChecks();
    }

    private String sourceId(CheckConfig config) {
        String id = config.resolveSourceCheckId();
        if (blank(id)) throw new BadRequestException("A configured check is missing its source ID.");
        return id;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private <T> T copy(T value, Class<T> type) { return mapper.convertValue(value, type); }
    @FunctionalInterface private interface Cleanup { void run() throws Exception; }
}
