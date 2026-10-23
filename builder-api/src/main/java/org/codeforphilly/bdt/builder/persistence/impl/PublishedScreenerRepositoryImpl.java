package org.codeforphilly.bdt.builder.persistence.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.codeforphilly.bdt.builder.constants.CollectionNames;
import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.Screener;
import org.codeforphilly.bdt.builder.persistence.FirestoreUtils;
import org.codeforphilly.bdt.builder.persistence.PublishedScreenerRepository;
import org.codeforphilly.bdt.builder.persistence.StorageService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class PublishedScreenerRepositoryImpl implements PublishedScreenerRepository {

    @Inject
    private StorageService storageService;

    public String calculateCustomBenefitCollection(String screenerId) {
        return CollectionNames.PUBLISHED_SCREENER_COLLECTION + "/" + screenerId + "/customBenefit";
    }

    @Override
    public Optional<Screener> getScreener(String screenerId){
        Optional<Map<String, Object>> dataOpt = (
            FirestoreUtils.getFirestoreDocById(CollectionNames.PUBLISHED_SCREENER_COLLECTION, screenerId)
        );
        if (dataOpt.isEmpty()){
            return Optional.empty();
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = dataOpt.get();
        Screener publishedScreener = mapper.convertValue(data, Screener.class);

        String formPath = storageService.getScreenerPublishedFormSchemaPath(screenerId);
        Map<String, Object> formSchema = storageService.getFormSchemaFromStorage(formPath);
        publishedScreener.setFormSchema(formSchema);

        return Optional.of(publishedScreener);
    }

    @Override
    public String createPublishedScreener(Screener screener) throws Exception{
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Map<String, Object> data = mapper.convertValue(screener, Map.class);

        // Remove form schema if included on the model.
        // We don't want to save this artifact as a field on the firestore document.
        // It is saved separately on in cloud storage.
        data.remove("formSchema");
        return FirestoreUtils.persistDocument(CollectionNames.PUBLISHED_SCREENER_COLLECTION, data);
    }

    @Override
    public void updatePublishedScreener(String publishedScreenerId, Screener screener) throws Exception {
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Map<String, Object> data = mapper.convertValue(screener, Map.class);

        // Remove form schema if included on the model.
        // We don't want to save this artifact as a field on the firestore document.
        // It is saved separately on in cloud storage.
        data.remove("formSchema");

        data.put("id", publishedScreenerId);
        FirestoreUtils.updateDocument(CollectionNames.PUBLISHED_SCREENER_COLLECTION, data, publishedScreenerId);
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

    public void refreshPublishedScreenerbenefits(String publishedScreenerId, List<Benefit> benefits) throws Exception {
        FirestoreUtils.deleteAllDocuments(
            calculateCustomBenefitCollection(publishedScreenerId)
        );

        for (Benefit benefit : benefits) {
            ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
            Map<String, Object> data = mapper.convertValue(benefit, Map.class);
            // Schema components refer to the working benefit IDs. Keep those IDs
            // in the published subcollection so evaluation results match the snapshot.
            // Overwrite: a document the delete above missed must not fail the publish.
            FirestoreUtils.replaceDocument(
                calculateCustomBenefitCollection(publishedScreenerId),
                benefit.getId(),
                data
            );
        }
    }
}
