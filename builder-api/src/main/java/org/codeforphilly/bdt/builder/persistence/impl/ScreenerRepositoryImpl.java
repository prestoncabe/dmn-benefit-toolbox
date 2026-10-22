package org.codeforphilly.bdt.builder.persistence.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.codeforphilly.bdt.builder.constants.CollectionNames;
import org.codeforphilly.bdt.builder.constants.FieldNames;
import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.BenefitDetail;
import org.codeforphilly.bdt.builder.model.domain.Screener;
import org.codeforphilly.bdt.builder.persistence.FirestoreUtils;
import org.codeforphilly.bdt.builder.persistence.ScreenerRepository;
import org.codeforphilly.bdt.builder.persistence.StorageService;
import org.codeforphilly.bdt.builder.persistence.PublishedScreenerRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ScreenerRepositoryImpl implements ScreenerRepository {
    
    @Inject
    private PublishedScreenerRepository publishedScreenerRepository;

    @Inject
    private StorageService storageService;

    public String calculateCustomBenefitCollection(String screenerId) {
        return CollectionNames.WORKING_SCREENER_COLLECTION + "/" + screenerId + "/customBenefit";
    }

    @Override
    public List<Screener> getWorkingScreeners(String userId) {

        List<Map<String, Object>> screenersMaps = FirestoreUtils.getFirestoreDocsByField(
                CollectionNames.WORKING_SCREENER_COLLECTION,
                FieldNames.OWNER_ID,
                userId);

        ObjectMapper mapper = new ObjectMapper();
        List<Screener> screeners = (
            screenersMaps.stream().map(
                screenerMap -> mapper.convertValue(screenerMap, Screener.class)
            ).toList()
        );

        return screeners;
    }

    @Override
    public Optional<Screener> getWorkingScreener(String screenerId){
        Optional<Map<String, Object>> dataOpt = FirestoreUtils.getFirestoreDocById(CollectionNames.WORKING_SCREENER_COLLECTION, screenerId);
        if (dataOpt.isEmpty()){
            return Optional.empty();
        }
        Map<String, Object> data = dataOpt.get();

        ObjectMapper mapper = new ObjectMapper();
        Screener screener = mapper.convertValue(data, Screener.class);

        String formPath = storageService.getScreenerWorkingFormSchemaPath(screenerId);
        Map<String, Object>  formSchema = storageService.getFormSchemaFromStorage(formPath);
        screener.setFormSchema(formSchema);

        return Optional.of(screener);
    }

    @Override
    public Optional<Screener> getWorkingScreenerMetaDataOnly(String screenerId){
        Optional<Map<String, Object>> dataOpt = FirestoreUtils.getFirestoreDocById(CollectionNames.WORKING_SCREENER_COLLECTION, screenerId);
        if (dataOpt.isEmpty()){
            return Optional.empty();
        }
        Map<String, Object> data = dataOpt.get();
        ObjectMapper mapper = new ObjectMapper();
        Screener screener = mapper.convertValue(data, Screener.class);

        return Optional.of(screener);
    }

    @Override
    public String saveNewWorkingScreener(Screener screener) throws Exception{
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Map<String, Object> data = mapper.convertValue(screener, Map.class);
        data.remove("formSchema");
        if (screener.getId() == null) screener.setId(java.util.UUID.randomUUID().toString());
        validateName(screener);
        data.put("screenerName", screener.getScreenerName());
        data.put("id", screener.getId());
        FirestoreUtils.saveScreenerWithUniqueName(CollectionNames.WORKING_SCREENER_COLLECTION,
                screener.getId(), data, true);
        return screener.getId();
    }

    @Override
    public void updateWorkingScreener(Screener screener) throws Exception {
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Map<String, Object> data = mapper.convertValue(screener, Map.class);

        // Remove form schema if included on the model.
        // We don't want to save this artifact as a field on the firestore document.
        // It is saved separately on in cloud storage.
        data.remove("formSchema");

        FirestoreUtils.updateDocument(CollectionNames.WORKING_SCREENER_COLLECTION, data, screener.getId());
    }

    @Override
    public void renameWorkingScreener(Screener screener) throws Exception {
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Map<String, Object> data = mapper.convertValue(screener, Map.class);
        data.remove("formSchema");
        validateName(screener);
        data.put("screenerName", screener.getScreenerName());
        FirestoreUtils.saveScreenerWithUniqueName(CollectionNames.WORKING_SCREENER_COLLECTION,
                screener.getId(), data, false);
    }

    private void validateName(Screener screener) {
        if (screener.getScreenerName() == null || screener.getScreenerName().isBlank())
            throw new jakarta.ws.rs.BadRequestException("Please enter a screener name.");
        screener.setScreenerName(screener.getScreenerName().strip());
    }

    public void addBenefitDetailToWorkingScreener(String screenerId, BenefitDetail benefitDetail) throws Exception {
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Map<String, Object> data = mapper.convertValue(benefitDetail, Map.class);
        FirestoreUtils.addObjectToArrayField(CollectionNames.WORKING_SCREENER_COLLECTION, screenerId, FieldNames.BENEFITS, data);
    }

    @Override
    public void deleteWorkingScreener(String screenerId) throws Exception {
        FirestoreUtils.deleteDocument(CollectionNames.WORKING_SCREENER_COLLECTION, screenerId);
    }

    /* Screener Benefits logic */
    public Optional<Benefit> getCustomBenefit(String screenerId, String benefitId) {
        Optional<Map<String, Object>> benefitMap = FirestoreUtils.getFirestoreDocById(
            calculateCustomBenefitCollection(screenerId), benefitId
        );

        if (benefitMap.isEmpty()) {
            return Optional.empty();
        }
        ObjectMapper mapper = new ObjectMapper();
        return Optional.of(mapper.convertValue(benefitMap.get(), Benefit.class));
    }

    public List<Benefit> getBenefitsInScreener(Screener screener) throws Exception{
        List<Map<String, Object>> customBenefitMaps = (
            FirestoreUtils.getAllDocsInCollection(calculateCustomBenefitCollection(screener.getId()))
        );

        ObjectMapper mapper = new ObjectMapper();
        return customBenefitMaps.stream().map(
            benefitMap -> mapper.convertValue(benefitMap, Benefit.class)
        ).toList();
    }

    public void updateCustomBenefit(String screenerId, Benefit benefit) throws Exception {
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Map<String, Object> data = mapper.convertValue(benefit, Map.class);
        String benefitDocId = benefit.getId();
        System.out.println("Updating custom benefit: " + benefit.getId());

        FirestoreUtils.updateDocument(calculateCustomBenefitCollection(screenerId), data, benefitDocId);
    }

    public void updateCustomBenefitFields(String screenerId, String benefitId, Map<String, Object> fields) throws Exception {
        Map<String, Object> data = new ObjectMapper().convertValue(fields, Map.class);
        FirestoreUtils.updateDocument(calculateCustomBenefitCollection(screenerId), data, benefitId);
    }

    public String saveNewCustomBenefit(String screenerId, Benefit benefit) throws Exception{
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Map<String, Object> data = mapper.convertValue(benefit, Map.class);
        String benefitDocId = benefit.getId();
        return FirestoreUtils.persistDocumentWithId(calculateCustomBenefitCollection(screenerId), benefitDocId, data);
    }

    public void deleteCustomBenefit(String screenerId, String benefitId) throws Exception {
        FirestoreUtils.deleteDocument(calculateCustomBenefitCollection(screenerId), benefitId);
    }

    /* Publishing logic */
    @Override
    public void publishScreener(Screener screener) throws Exception {
        if (screener.getPublishedScreenerId() == null || screener.getPublishedScreenerId().isEmpty()) {
            String publishedScreenerId = publishedScreenerRepository.createPublishedScreener(screener);
            screener.setPublishedScreenerId(publishedScreenerId);
        } else {
            publishedScreenerRepository.updatePublishedScreener(screener.getPublishedScreenerId(), screener);
        }

        screener.setLastPublishDate(Instant.now().toString());
        this.updateWorkingScreener(screener);

        publishedScreenerRepository.refreshPublishedScreenerbenefits(
            screener.getPublishedScreenerId(),
            this.getBenefitsInScreener(screener)
        );

        storageService.updatePublishedFormSchemaArtifact(
            screener.getId(), screener.getPublishedScreenerId()
        );
    }
}
