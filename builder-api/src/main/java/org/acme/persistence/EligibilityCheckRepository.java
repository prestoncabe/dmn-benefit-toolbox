package org.acme.persistence;

import org.acme.model.domain.Benefit;
import org.acme.model.domain.EligibilityCheck;

import java.util.List;
import java.util.Optional;

public interface EligibilityCheckRepository {

    List<EligibilityCheck> getWorkingCustomChecks(String userId);

    List<EligibilityCheck> getArchivedCustomChecks(String userId);

    List<EligibilityCheck> getPublishedCheckVersions(EligibilityCheck workingCustomCheck) throws Exception;

    List<EligibilityCheck> getLatestVersionPublishedCustomChecks(String userId);

    List<EligibilityCheck> getPublishedCustomChecks(String userId);

    Optional<EligibilityCheck> getWorkingCustomCheck(String userId, String checkId);

    Optional<EligibilityCheck> getWorkingCustomCheck(String userId, String checkId, boolean includeArchived);

    /* The working check document, archived or not, without its DMN model. */
    Optional<EligibilityCheck> getWorkingCustomCheckMetadata(String userId, String checkId);

    Optional<EligibilityCheck> getPublishedCustomCheck(String userId, String checkId);

    String getWorkingId(EligibilityCheck check);

    String saveNewWorkingCustomCheck(EligibilityCheck check) throws Exception;

    String saveNewPublishedCustomCheck(EligibilityCheck check) throws Exception;

    void updateWorkingCustomCheck(EligibilityCheck check) throws Exception;

    void deleteWorkingCustomCheck(String checkId) throws Exception;

    void updatePublishedCustomCheck(EligibilityCheck check) throws Exception;

    /* The published document id a check would get at the given version. */
    String getPublishedId(EligibilityCheck check, String version);
}
