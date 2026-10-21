package org.codeforphilly.bdt.builder.service;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class FormDataTransformerTest {

    // ==== transformPeopleData tests ====

    @Test
    void transformPeopleData_withPeopleObject_convertsToArray() {
        Map<String, Object> applicantData = new HashMap<>();
        applicantData.put("dateOfBirth", "1960-01-01");

        Map<String, Object> peopleObject = new HashMap<>();
        peopleObject.put("applicant", applicantData);

        Map<String, Object> formData = new HashMap<>();
        formData.put("people", peopleObject);

        Map<String, Object> result = FormDataTransformer.transformPeopleData(formData);

        // Verify people is now an array
        assertTrue(result.get("people") instanceof List);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> peopleArray = (List<Map<String, Object>>) result.get("people");
        assertEquals(1, peopleArray.size());

        // Verify the person has id field and original data
        Map<String, Object> person = peopleArray.get(0);
        assertEquals("applicant", person.get("id"));
        assertEquals("1960-01-01", person.get("dateOfBirth"));
    }

    @Test
    void transformPeopleData_withMultiplePeople_convertsAll() {
        Map<String, Object> applicantData = new HashMap<>();
        applicantData.put("dateOfBirth", "1960-01-01");

        Map<String, Object> spouseData = new HashMap<>();
        spouseData.put("dateOfBirth", "1965-05-15");

        Map<String, Object> peopleObject = new HashMap<>();
        peopleObject.put("applicant", applicantData);
        peopleObject.put("spouse", spouseData);

        Map<String, Object> formData = new HashMap<>();
        formData.put("people", peopleObject);

        Map<String, Object> result = FormDataTransformer.transformPeopleData(formData);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> peopleArray = (List<Map<String, Object>>) result.get("people");
        assertEquals(2, peopleArray.size());

        // Find both people by id
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> person : peopleArray) {
            ids.add((String) person.get("id"));
        }
        assertTrue(ids.contains("applicant"));
        assertTrue(ids.contains("spouse"));
    }

    @Test
    void transformPeopleData_withPeopleAlreadyArray_returnsOriginal() {
        List<Map<String, Object>> peopleArray = new ArrayList<>();
        Map<String, Object> person = new HashMap<>();
        person.put("id", "applicant");
        peopleArray.add(person);

        Map<String, Object> formData = new HashMap<>();
        formData.put("people", peopleArray);

        Map<String, Object> result = FormDataTransformer.transformPeopleData(formData);

        // Should return a copy of the original
        assertTrue(result.get("people") instanceof List);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultArray = (List<Map<String, Object>>) result.get("people");
        assertEquals(1, resultArray.size());
    }

    @Test
    void transformPeopleData_withNoPeople_returnsOriginal() {
        Map<String, Object> formData = new HashMap<>();
        formData.put("income", 50000);

        Map<String, Object> result = FormDataTransformer.transformPeopleData(formData);

        assertEquals(50000, result.get("income"));
        assertFalse(result.containsKey("people"));
    }

    @Test
    void transformPeopleData_withNullInput_returnsEmptyMap() {
        Map<String, Object> result = FormDataTransformer.transformPeopleData(null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void transformFormData_withSpouse_createsPersonAndRelationships() {
        Map<String, Object> formData = Map.of(
            "people", Map.of(
                "client", Map.of("dateOfBirth", "1970-08-01"),
                "spouse", Map.of("dateOfBirth", "1960-12-30", "enrollments", List.of("Medicare"))
            ),
            "simpleChecks", Map.of("lateSpouseWasAtLeast65", false)
        );

        Map<String, Object> result = FormDataTransformer.transformFormData(formData);

        assertEquals(Set.of(
            Map.of("id", "client", "dateOfBirth", "1970-08-01"),
            Map.of("id", "spouse", "dateOfBirth", "1960-12-30")
        ), new HashSet<>((List<?>) result.get("people")));
        assertEquals(2, ((List<?>) result.get("people")).size());
        assertEquals(List.of(
            Map.of("type", "spouse", "personId", "client", "relatedPersonId", "spouse"),
            Map.of("type", "spouse", "personId", "spouse", "relatedPersonId", "client")
        ), result.get("relationships"));
        assertEquals(List.of(Map.of("personId", "spouse", "benefit", "Medicare")), result.get("enrollments"));
        assertEquals(formData.get("simpleChecks"), result.get("simpleChecks"));
        assertEquals("client", result.get("primaryPersonId"));
        assertFalse(result.containsKey("spouse"));
        assertEquals(Map.of("dateOfBirth", "1960-12-30", "enrollments", List.of("Medicare")),
            ((Map<?, ?>) formData.get("people")).get("spouse"));
        assertFalse(formData.containsKey("primaryPersonId"));
        assertFalse(formData.containsKey("relationships"));
        assertEquals(result, FormDataTransformer.transformFormData(result));
    }

    @Test
    void transformFormData_withoutSpouse_defaultsMissingPrimaryPersonIdToClient() {
        Map<String, Object> formData = Map.of(
            "people", Map.of("client", Map.of("dateOfBirth", "1960-01-01"))
        );

        Map<String, Object> result = FormDataTransformer.transformFormData(formData);

        assertEquals("client", result.get("primaryPersonId"));
        assertEquals(List.of(Map.of("id", "client", "dateOfBirth", "1960-01-01")), result.get("people"));
        assertFalse(result.containsKey("relationships"));
    }

    @Test
    void transformFormData_withBlankPrimaryPersonId_defaultsSpouseRelationshipToClient() {
        for (String primaryPersonId : Arrays.asList(null, "", " ")) {
            Map<String, Object> formData = new HashMap<>();
            formData.put("primaryPersonId", primaryPersonId);
            formData.put("people", Map.of("spouse", Map.of("dateOfBirth", "1960-01-01")));

            Map<String, Object> result = FormDataTransformer.transformFormData(formData);

            assertEquals("client", result.get("primaryPersonId"));
            assertEquals(List.of(
                Map.of("type", "spouse", "personId", "client", "relatedPersonId", "spouse"),
                Map.of("type", "spouse", "personId", "spouse", "relatedPersonId", "client")
            ), result.get("relationships"));
            assertEquals(primaryPersonId, formData.get("primaryPersonId"));
        }
    }

    @Test
    void transformFormData_withExistingSpouse_reusesPersonAndPreservesRelationships() {
        Map<String, Object> applicant = Map.of("dateOfBirth", "1970-08-01");
        Map<String, Object> spouse = Map.of("name", "Pat", "dateOfBirth", "1961-01-01");
        List<Map<String, Object>> relationships = List.of(
            Map.of("type", "spouse", "personId", "p1", "relatedPersonId", "p2", "note", "existing"),
            Map.of("type", "spouse", "personId", "p2", "relatedPersonId", "p1"),
            Map.of("type", "spouse", "personId", "p3", "relatedPersonId", "p4")
        );
        Map<String, Object> formData = Map.of(
            "primaryPersonId", "p1",
            "people", Map.of("p1", applicant, "p2", spouse, "spouse", Map.of("dateOfBirth", "1960-12-30")),
            "relationships", relationships
        );

        Map<String, Object> result = FormDataTransformer.transformFormData(formData);

        assertEquals(Set.of(Map.of("id", "p1", "dateOfBirth", "1970-08-01"),
            Map.of("id", "p2", "name", "Pat", "dateOfBirth", "1960-12-30")),
            new HashSet<>((List<?>) result.get("people")));
        assertEquals(2, ((List<?>) result.get("people")).size());
        assertEquals(relationships, result.get("relationships"));
        assertEquals("1961-01-01", spouse.get("dateOfBirth"));
    }

    @Test
    void transformFormData_withPeopleSpouseAndExistingRelationship_doesNotDuplicate() {
        List<Map<String, Object>> relationships = List.of(
            Map.of("type", "spouse", "personId", "applicant", "relatedPersonId", "spouse"),
            Map.of("type", "spouse", "personId", "spouse", "relatedPersonId", "applicant")
        );
        Map<String, Object> formData = Map.of(
            "primaryPersonId", "applicant",
            "people", Map.of("spouse", Map.of("dateOfBirth", "1960-12-30", "enrollments", List.of("Medicare"))),
            "relationships", relationships
        );

        Map<String, Object> result = FormDataTransformer.transformFormData(formData);

        assertEquals(List.of(Map.of("id", "spouse", "dateOfBirth", "1960-12-30")), result.get("people"));
        assertEquals(List.of(Map.of("personId", "spouse", "benefit", "Medicare")), result.get("enrollments"));
        assertEquals(relationships, result.get("relationships"));
    }

    @Test
    void transformFormData_withBlankSpouse_keepsRelationshipStatusUnknown() {
        Map<String, Object> nullDate = new HashMap<>();
        nullDate.put("dateOfBirth", null);
        for (Object spouse : Arrays.asList(null, Map.of(), nullDate, Map.of("dateOfBirth", ""),
                Map.of("dateOfBirth", " ", "enrollments", List.of()))) {
            Map<String, Object> formData = new HashMap<>();
            formData.put("primaryPersonId", "p1");
            Map<String, Object> people = new HashMap<>();
            people.put("p1", Map.of("dateOfBirth", "1970-01-01"));
            people.put("spouse", spouse);
            formData.put("people", people);

            Map<String, Object> result = FormDataTransformer.transformFormData(formData);

            assertEquals(List.of(Map.of("id", "p1", "dateOfBirth", "1970-01-01")), result.get("people"));
            assertFalse(result.containsKey("relationships"));
            assertFalse(result.containsKey("spouse"));
            assertTrue(people.containsKey("spouse"));
            assertEquals(spouse, people.get("spouse"));
        }
    }

    @Test
    void transformFormData_withExplicitUnknownSpouse_setsRelationshipsToNull() {
        Map<String, Object> formData = Map.of(
            "people", Map.of(
                "client", Map.of("dateOfBirth", "2000-01-01"),
                "spouse", Collections.singletonMap("exists", null)
            )
        );

        Map<String, Object> result = FormDataTransformer.transformFormData(formData);

        assertTrue(result.containsKey("relationships"));
        assertNull(result.get("relationships"));
        assertEquals(List.of(Map.of("id", "client", "dateOfBirth", "2000-01-01")), result.get("people"));
    }

    @Test
    void transformFormData_withExplicitNoSpouse_createsEmptyRelationshipsAndIgnoresStaleDetails() {
        Map<String, Object> formData = Map.of(
            "people", Map.of(
                "client", Map.of("dateOfBirth", "2000-01-01"),
                "spouse", Map.of("exists", false, "dateOfBirth", "1960-01-01")
            )
        );

        Map<String, Object> result = FormDataTransformer.transformFormData(formData);

        assertEquals(List.of(), result.get("relationships"));
        assertEquals(List.of(Map.of("id", "client", "dateOfBirth", "2000-01-01")), result.get("people"));
    }

    @Test
    void transformFormData_withExplicitSpouse_createsRelationshipWithoutRequiringDetails() {
        Map<String, Object> formData = Map.of(
            "people", Map.of("spouse", Map.of("exists", true))
        );

        Map<String, Object> result = FormDataTransformer.transformFormData(formData);

        assertEquals(List.of(Map.of("id", "spouse")), result.get("people"));
        assertEquals(List.of(
            Map.of("type", "spouse", "personId", "client", "relatedPersonId", "spouse"),
            Map.of("type", "spouse", "personId", "spouse", "relatedPersonId", "client")
        ), result.get("relationships"));
    }

    @Test
    void transformFormData_withoutSpouse_preservesLibraryFormat() {
        Map<String, Object> formData = Map.of(
            "primaryPersonId", "p1",
            "people", List.of(Map.of("id", "p1"), Map.of("id", "p2")),
            "relationships", List.of(Map.of("type", "spouse", "personId", "p1", "relatedPersonId", "p2"))
        );

        assertEquals(formData, FormDataTransformer.transformFormData(formData));
        assertEquals(Map.of(), FormDataTransformer.transformFormData(null));
    }

    // ==== transformEnrollmentsData tests ====

    @Test
    void transformEnrollmentsData_withPersonEnrollments_extractsToFlatArray() {
        // Setup: people array with enrollments inside person object
        Map<String, Object> person = new HashMap<>();
        person.put("id", "applicant");
        person.put("dateOfBirth", "1960-01-01");
        person.put("enrollments", Arrays.asList("SNAP", "Medicaid"));

        List<Map<String, Object>> peopleArray = new ArrayList<>();
        peopleArray.add(person);

        Map<String, Object> formData = new HashMap<>();
        formData.put("people", peopleArray);

        Map<String, Object> result = FormDataTransformer.transformEnrollmentsData(formData);

        // Verify enrollments is now a flat array at top level
        assertTrue(result.containsKey("enrollments"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> enrollments = (List<Map<String, Object>>) result.get("enrollments");
        assertEquals(2, enrollments.size());

        // Verify enrollment entries have personId and benefit
        Map<String, Object> enrollment1 = enrollments.get(0);
        assertEquals("applicant", enrollment1.get("personId"));
        assertTrue(enrollment1.get("benefit").equals("SNAP") || enrollment1.get("benefit").equals("Medicaid"));

        // Verify enrollments removed from person object
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultPeople = (List<Map<String, Object>>) result.get("people");
        Map<String, Object> resultPerson = resultPeople.get(0);
        assertFalse(resultPerson.containsKey("enrollments"));
        assertEquals("applicant", resultPerson.get("id"));
        assertEquals("1960-01-01", resultPerson.get("dateOfBirth"));
    }

    @Test
    void transformEnrollmentsData_withMultiplePeopleEnrollments_extractsAll() {
        Map<String, Object> applicant = new HashMap<>();
        applicant.put("id", "applicant");
        applicant.put("enrollments", Arrays.asList("SNAP"));

        Map<String, Object> spouse = new HashMap<>();
        spouse.put("id", "spouse");
        spouse.put("enrollments", Arrays.asList("Medicare", "Medicaid"));

        List<Map<String, Object>> peopleArray = new ArrayList<>();
        peopleArray.add(applicant);
        peopleArray.add(spouse);

        Map<String, Object> formData = new HashMap<>();
        formData.put("people", peopleArray);

        Map<String, Object> result = FormDataTransformer.transformEnrollmentsData(formData);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> enrollments = (List<Map<String, Object>>) result.get("enrollments");
        assertEquals(3, enrollments.size());

        // Count enrollments per person
        int applicantCount = 0;
        int spouseCount = 0;
        for (Map<String, Object> enrollment : enrollments) {
            if ("applicant".equals(enrollment.get("personId"))) {
                applicantCount++;
            } else if ("spouse".equals(enrollment.get("personId"))) {
                spouseCount++;
            }
        }
        assertEquals(1, applicantCount);
        assertEquals(2, spouseCount);
    }

    @Test
    void transformEnrollmentsData_withNoEnrollments_doesNotAddEnrollmentsKey() {
        Map<String, Object> person = new HashMap<>();
        person.put("id", "applicant");
        person.put("dateOfBirth", "1960-01-01");

        List<Map<String, Object>> peopleArray = new ArrayList<>();
        peopleArray.add(person);

        Map<String, Object> formData = new HashMap<>();
        formData.put("people", peopleArray);

        Map<String, Object> result = FormDataTransformer.transformEnrollmentsData(formData);

        assertFalse(result.containsKey("enrollments"));
    }

    @Test
    void transformEnrollmentsData_withEmptyEnrollmentsArray_preservesExplicitNone() {
        Map<String, Object> person = new HashMap<>();
        person.put("id", "applicant");
        person.put("enrollments", new ArrayList<>());

        List<Map<String, Object>> peopleArray = new ArrayList<>();
        peopleArray.add(person);

        Map<String, Object> formData = new HashMap<>();
        formData.put("people", peopleArray);

        Map<String, Object> result = FormDataTransformer.transformEnrollmentsData(formData);

        assertEquals(List.of(), result.get("enrollments"));
    }

    @Test
    void transformEnrollmentsData_withNullEnrollments_preservesUnanswered() {
        Map<String, Object> person = new HashMap<>();
        person.put("id", "applicant");
        person.put("enrollments", null);

        Map<String, Object> result = FormDataTransformer.transformEnrollmentsData(
            Map.of("people", List.of(person)));

        assertTrue(result.containsKey("enrollments"));
        assertNull(result.get("enrollments"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultPeople = (List<Map<String, Object>>) result.get("people");
        assertFalse(resultPeople.getFirst().containsKey("enrollments"));
    }

    @Test
    void transformEnrollmentsData_withOneUnansweredPerson_doesNotInferNone() {
        Map<String, Object> applicant = new HashMap<>();
        applicant.put("id", "applicant");
        applicant.put("enrollments", List.of("SNAP"));
        Map<String, Object> spouse = new HashMap<>();
        spouse.put("id", "spouse");
        spouse.put("enrollments", null);

        Map<String, Object> result = FormDataTransformer.transformEnrollmentsData(
            Map.of("people", List.of(applicant, spouse)));

        assertTrue(result.containsKey("enrollments"));
        assertNull(result.get("enrollments"));
    }

    @Test
    void transformEnrollmentsData_withPeopleNotArray_returnsOriginal() {
        Map<String, Object> formData = new HashMap<>();
        formData.put("people", "not an array");

        Map<String, Object> result = FormDataTransformer.transformEnrollmentsData(formData);

        assertEquals("not an array", result.get("people"));
    }

    @Test
    void transformEnrollmentsData_withNullInput_returnsEmptyMap() {
        Map<String, Object> result = FormDataTransformer.transformEnrollmentsData(null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==== transformFormData composition tests ====

    @Test
    void transformFormData_appliesBothTransforms() {
        // Setup: people as object with enrollments inside
        Map<String, Object> applicantData = new HashMap<>();
        applicantData.put("dateOfBirth", "1960-01-01");
        applicantData.put("enrollments", Arrays.asList("SNAP", "Medicaid"));

        Map<String, Object> peopleObject = new HashMap<>();
        peopleObject.put("applicant", applicantData);

        Map<String, Object> formData = new HashMap<>();
        formData.put("people", peopleObject);

        Map<String, Object> result = FormDataTransformer.transformFormData(formData);

        // Verify people is now an array with id field
        assertTrue(result.get("people") instanceof List);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> peopleArray = (List<Map<String, Object>>) result.get("people");
        assertEquals(1, peopleArray.size());
        assertEquals("applicant", peopleArray.get(0).get("id"));
        assertEquals("1960-01-01", peopleArray.get(0).get("dateOfBirth"));
        assertFalse(peopleArray.get(0).containsKey("enrollments"));

        // Verify enrollments is now a flat array
        assertTrue(result.containsKey("enrollments"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> enrollments = (List<Map<String, Object>>) result.get("enrollments");
        assertEquals(2, enrollments.size());
    }

    @Test
    void expandIncomeAssessments_copiesAnswersToEachBasis() {
        Map<String, Object> formData = Map.of(
            "incomeAssessments", Map.of("annualIncome", 20000, "householdSize", 2),
            "asOfDate", "2026-10-15");

        Map<String, Object> result = FormDataTransformer.expandIncomeAssessments(formData, List.of("paLiheap", "pecoCap"));

        Map<String, Object> answers = Map.of("annualIncome", 20000, "householdSize", 2);
        assertEquals(Map.of("paLiheap", answers, "pecoCap", answers), result.get("incomeAssessments"));
        assertEquals("2026-10-15", result.get("asOfDate"));
    }

    @Test
    void expandIncomeAssessments_withKeyedOrMissingIncome_returnsUnchanged() {
        Map<String, Object> keyed = Map.of(
            "incomeAssessments", Map.of("paLiheap", Map.of("annualIncome", 20000)));

        assertEquals(keyed, FormDataTransformer.expandIncomeAssessments(keyed, List.of("paLiheap", "pecoCap")));
        assertEquals(Map.of("asOfDate", "2026-10-15"),
            FormDataTransformer.expandIncomeAssessments(Map.of("asOfDate", "2026-10-15"), List.of("paLiheap")));
    }

    @Test
    void transformFormData_preservesOtherFields() {
        Map<String, Object> applicantData = new HashMap<>();
        applicantData.put("dateOfBirth", "1960-01-01");

        Map<String, Object> peopleObject = new HashMap<>();
        peopleObject.put("applicant", applicantData);

        Map<String, Object> formData = new HashMap<>();
        formData.put("people", peopleObject);
        formData.put("income", 50000);
        formData.put("householdSize", 3);

        Map<String, Object> result = FormDataTransformer.transformFormData(formData);

        assertEquals(50000, result.get("income"));
        assertEquals(3, result.get("householdSize"));
    }
}
