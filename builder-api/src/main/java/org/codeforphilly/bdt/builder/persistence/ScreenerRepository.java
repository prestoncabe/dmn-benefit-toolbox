package org.codeforphilly.bdt.builder.persistence;

import org.codeforphilly.bdt.builder.model.domain.Benefit;
import org.codeforphilly.bdt.builder.model.domain.BenefitDetail;
import org.codeforphilly.bdt.builder.model.domain.Screener;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ScreenerRepository {
    public List<Screener> getWorkingScreeners(String userId);

    public Optional<Screener> getWorkingScreener(String screenerId);

    public Optional<Screener> getWorkingScreenerMetaDataOnly(String screenerId);

    public String saveNewWorkingScreener(Screener screener) throws Exception;

    public void updateWorkingScreener(Screener screener) throws Exception;

    /** Saves a new name, rejecting one already used by another of the owner's screeners. */
    public void renameWorkingScreener(Screener screener) throws Exception;

    public void addBenefitDetailToWorkingScreener(String screenerId, BenefitDetail benefitDetail) throws Exception;

    public void deleteWorkingScreener(String screenerId) throws Exception;

    // Custom Benefit logic
    public Optional<Benefit> getCustomBenefit(String screenerId, String benefitId);

    public String saveNewCustomBenefit(String ScreenerId, Benefit benefit) throws Exception;

    public List<Benefit> getBenefitsInScreener(Screener screener) throws Exception;

    public void deleteCustomBenefit(String screenerId, String benefitId) throws Exception;

    public void updateCustomBenefit(String screenerId, Benefit benefit) throws Exception;

    /** Writes only the given top-level fields, leaving the rest (such as checks) as stored. */
    public void updateCustomBenefitFields(String screenerId, String benefitId, Map<String, Object> fields) throws Exception;

    // Publishing logic
    public void publishScreener(Screener screener) throws Exception;
}
