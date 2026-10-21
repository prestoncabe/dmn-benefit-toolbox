package org.codeforphilly.bdt.builder.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for transforming form data between different formats.
 *
 * The Form-JS editor uses a "people" object with personId keys (e.g., {applicant: {...}, spouse: {...}}),
 * while DMN models expect a "people" array with id fields (e.g., [{id: "applicant", ...}, {id: "spouse", ...}]).
 *
 * The "people.spouse" object holds the primary person's spouse fields and is linked
 * to primaryPersonId through reciprocal spouse relationships.
 *
 * Enrollments are stored per-person as arrays of benefit strings (e.g., {applicant: {enrollments: ["SNAP", "Medicaid"]}}),
 * while DMN models expect a flat enrollments array (e.g., [{personId: "applicant", benefit: "SNAP"}, ...]).
 *
 * Income is asked once as incomeAssessments.annualIncome and incomeAssessments.householdSize,
 * while DMN models key incomeAssessments by income basis (e.g., {paLiheap: {...}, pecoCap: {...}}).
 */
public class FormDataTransformer {

    public static final String DEFAULT_PRIMARY_PERSON_ID = "client";

    /**
     * Transforms form data by applying all data transformations.
     * Defaults a missing primaryPersonId to the screener's conventional "client" ID.
     * Then applies:
     * 1. Spouse transformation: links people.spouse to the primary person through relationships
     * 2. People transformation: converts people object to array with id fields
     * 3. Enrollments transformation: extracts enrollments from people objects into flat array
     *
     * @param formData The form data from the user
     * @return A new Map with all transformations applied
     */
    public static Map<String, Object> transformFormData(Map<String, Object> formData) {
        if (formData == null) {
            return new HashMap<>();
        }

        // Apply each transformation in sequence
        Map<String, Object> result = new HashMap<>(formData);
        Object primaryPersonId = result.get("primaryPersonId");
        if (primaryPersonId == null || (primaryPersonId instanceof String id && id.isBlank())) {
            result.put("primaryPersonId", DEFAULT_PRIMARY_PERSON_ID);
        }
        result = transformSpouseData(result);
        result = transformPeopleData(result);
        result = transformEnrollmentsData(result);

        return result;
    }

    /**
     * Copies the screener's single income answers to every income basis the checks read.
     * Each basis gets the same annual income and household size, so screener results
     * approximate programs that count income or household members differently.
     * Data already keyed by basis is returned unchanged.
     *
     * Example:
     *   Input:  { incomeAssessments: { annualIncome: 20000, householdSize: 2 } }, bases [paLiheap, pecoCap]
     *   Output: { incomeAssessments: { paLiheap: { annualIncome: 20000, householdSize: 2 },
     *                                  pecoCap: { annualIncome: 20000, householdSize: 2 } } }
     */
    public static Map<String, Object> expandIncomeAssessments(Map<String, Object> formData, Collection<String> bases) {
        Map<String, Object> result = formData != null ? new HashMap<>(formData) : new HashMap<>();
        if (!(result.get("incomeAssessments") instanceof Map<?, ?> answers)
                || !(answers.containsKey("annualIncome") || answers.containsKey("householdSize"))) {
            return result;
        }

        Map<String, Object> assessments = new HashMap<>();
        for (String basis : bases) {
            assessments.put(basis, new HashMap<>(answers));
        }
        result.put("incomeAssessments", assessments);
        return result;
    }

    /**
     * Maps people.spouse.* to spouse relationships for primaryPersonId.
     * people.spouse.exists distinguishes unknown, no spouse, and spouse present.
     * Must be called before transformPeopleData. An existing spouse relationship
     * determines the spouse's ID; otherwise the ID is "spouse". transformFormData
     * supplies the default primaryPersonId when the form does not provide one.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> transformSpouseData(Map<String, Object> formData) {
        Map<String, Object> result = formData != null ? new HashMap<>(formData) : new HashMap<>();
        if (!(result.get("people") instanceof Map<?, ?> peopleObject) || !peopleObject.containsKey("spouse")) {
            return result;
        }

        Map<String, Object> people = new HashMap<>((Map<String, Object>) peopleObject);
        Object spouseValue = people.remove("spouse");
        result.put("people", people);
        Map<String, Object> spouse = spouseValue instanceof Map<?, ?> spouseMap
            ? new HashMap<>((Map<String, Object>) spouseMap) : null;
        boolean hasExistsAnswer = spouse != null && spouse.containsKey("exists");
        Object existsAnswer = hasExistsAnswer ? spouse.remove("exists") : null;
        boolean hasSpouseDetails = spouse != null && spouse.entrySet().stream()
            .filter(entry -> !"id".equals(entry.getKey()))
            .map(Map.Entry::getValue)
            .anyMatch(FormDataTransformer::hasMeaningfulValue);

        Object primaryPersonId = result.get("primaryPersonId");
        if (hasExistsAnswer && !Boolean.TRUE.equals(existsAnswer)) {
            if (Boolean.FALSE.equals(existsAnswer)) {
                List<Map<String, Object>> relationships = result.get("relationships") instanceof List<?> existing
                    ? new ArrayList<>((List<Map<String, Object>>) existing) : new ArrayList<>();
                relationships.removeIf(relationship -> "spouse".equals(relationship.get("type"))
                    && (primaryPersonId != null && (primaryPersonId.equals(relationship.get("personId"))
                        || primaryPersonId.equals(relationship.get("relatedPersonId")))));
                result.put("relationships", relationships);
            } else {
                result.put("relationships", null);
            }
            return result;
        }

        // Existing forms did not have an explicit existence question. Preserve compatibility
        // by treating populated spouse details as "yes", while blank details remain unknown.
        if (!hasExistsAnswer && !hasSpouseDetails) {
            return result;
        }

        List<Map<String, Object>> relationships = result.get("relationships") instanceof List<?> existing
            ? new ArrayList<>((List<Map<String, Object>>) existing) : new ArrayList<>();
        result.put("relationships", relationships);
        String spouseId = "spouse";
        for (Map<String, Object> relationship : relationships) {
            if ("spouse".equals(relationship.get("type"))
                    && primaryPersonId != null && primaryPersonId.equals(relationship.get("personId"))
                    && relationship.get("relatedPersonId") instanceof String relatedId && !relatedId.isBlank()) {
                spouseId = relatedId;
                break;
            }
        }

        Map<String, Object> spouseData = new HashMap<>();
        if (people.get(spouseId) instanceof Map<?, ?> existingSpouse) {
            spouseData.putAll((Map<String, Object>) existingSpouse);
        }
        spouseData.putAll((Map<String, Object>) spouse);
        people.put(spouseId, spouseData);

        if (primaryPersonId instanceof String personId && !personId.isBlank()) {
            addSpouseRelationship(relationships, personId, spouseId);
            addSpouseRelationship(relationships, spouseId, personId);
        }
        return result;
    }

    private static boolean hasMeaningfulValue(Object value) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return false;
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private static void addSpouseRelationship(List<Map<String, Object>> relationships,
            String personId, String relatedPersonId) {
        boolean exists = relationships.stream().anyMatch(relationship ->
            "spouse".equals(relationship.get("type"))
                && personId.equals(relationship.get("personId"))
                && relatedPersonId.equals(relationship.get("relatedPersonId")));
        if (!exists) {
            relationships.add(Map.of("type", "spouse", "personId", personId, "relatedPersonId", relatedPersonId));
        }
    }

    /**
     * Transforms a "people" object (with personId keys) into a "people" array (with id fields).
     *
     * Example:
     *   Input:  { people: { applicant: { dateOfBirth: "1960-01-01" } } }
     *   Output: { people: [{ id: "applicant", dateOfBirth: "1960-01-01" }] }
     *
     * @param formData The form data potentially containing a "people" object
     * @return A new Map with the "people" object converted to an array
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> transformPeopleData(Map<String, Object> formData) {
        if (formData == null) {
            return new HashMap<>();
        }

        Object peopleValue = formData.get("people");

        // If no people key, or it's already a List (array), return a copy of the original
        if (peopleValue == null || peopleValue instanceof List) {
            return new HashMap<>(formData);
        }

        // If people is not a Map (object), return a copy of the original
        if (!(peopleValue instanceof Map)) {
            return new HashMap<>(formData);
        }

        Map<String, Object> peopleObject = (Map<String, Object>) peopleValue;
        List<Map<String, Object>> peopleArray = new ArrayList<>();

        // Convert each entry in the people object to an array element with an "id" field
        for (Map.Entry<String, Object> entry : peopleObject.entrySet()) {
            String personId = entry.getKey();
            Object personValue = entry.getValue();

            if (personValue instanceof Map) {
                Map<String, Object> personData = new HashMap<>((Map<String, Object>) personValue);
                personData.put("id", personId);
                peopleArray.add(personData);
            } else {
                // If the value is not a Map, create a simple object with just the id
                Map<String, Object> personData = new HashMap<>();
                personData.put("id", personId);
                peopleArray.add(personData);
            }
        }

        // Create the result with the transformed people array
        Map<String, Object> result = new HashMap<>(formData);
        result.put("people", peopleArray);

        return result;
    }

    /**
     * Extracts enrollments from people objects and creates a flat enrollments array.
     * Must be called after transformPeopleData (expects people to be an array).
     *
     * Example:
     *   Input:  { people: [{ id: "applicant", enrollments: ["SNAP", "Medicaid"] }] }
     *   Output: { people: [{ id: "applicant" }], enrollments: [{ personId: "applicant", benefit: "SNAP" }, { personId: "applicant", benefit: "Medicaid" }] }
     *
     * @param formData The form data with people as an array
     * @return A new Map with enrollments extracted into a flat array
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> transformEnrollmentsData(Map<String, Object> formData) {
        if (formData == null) {
            return new HashMap<>();
        }

        Object peopleValue = formData.get("people");

        // If no people key or it's not a List, return a copy of the original
        if (!(peopleValue instanceof List)) {
            return new HashMap<>(formData);
        }

        List<Map<String, Object>> peopleArray = (List<Map<String, Object>>) peopleValue;
        List<Map<String, Object>> enrollmentsList = new ArrayList<>();
        List<Map<String, Object>> transformedPeopleArray = new ArrayList<>();
        boolean hasEnrollmentAnswer = false;
        boolean hasUnansweredEnrollments = false;

        // Extract enrollments from each person
        for (Map<String, Object> person : peopleArray) {
            String personId = (String) person.get("id");
            Object personEnrollments = person.get("enrollments");
            if (person.containsKey("enrollments")) {
                hasEnrollmentAnswer = true;
                if (personEnrollments == null) {
                    hasUnansweredEnrollments = true;
                }
            }

            // Create a copy of the person data without enrollments
            Map<String, Object> personCopy = new HashMap<>(person);

            if (person.containsKey("enrollments")) {
                // The DMN receives a flat enrollments field, not per-person fields.
                personCopy.remove("enrollments");
            }

            if (personEnrollments instanceof List) {
                // Convert each enrollment string to an enrollment object
                for (Object enrollment : (List<?>) personEnrollments) {
                    if (enrollment instanceof String) {
                        Map<String, Object> enrollmentEntry = new HashMap<>();
                        enrollmentEntry.put("personId", personId);
                        enrollmentEntry.put("benefit", enrollment);
                        enrollmentsList.add(enrollmentEntry);
                    }
                }
            }

            transformedPeopleArray.add(personCopy);
        }

        // Create the result
        Map<String, Object> result = new HashMap<>(formData);
        result.put("people", transformedPeopleArray);

        // Preserve the distinction between unanswered (null), an explicit
        // "None of these" answer ([]), and selected enrollments. If any
        // person's answer is unknown, the combined answer is still unknown.
        if (hasEnrollmentAnswer) {
            result.put("enrollments", hasUnansweredEnrollments ? null : enrollmentsList);
        }

        return result;
    }
}
