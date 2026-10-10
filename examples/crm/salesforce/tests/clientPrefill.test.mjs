import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = await readFile(
  new URL("../bdtScreening/clientPrefill.js", import.meta.url),
  "utf8",
);
const { clientPrefill } = await import(
  `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`
);

test("unanswered intake stays unknown rather than becoming No or no enrollments", () => {
  const data = clientPrefill({ BDT_Philly_Resident__c: "Unknown" });
  assert.equal(data.simpleChecks.livesInPhiladelphiaPa, null);
  assert.equal(data.custom.householdIncome, null);
  assert.equal(data.people.client.enrollments, null);
  assert.equal(data.people.spouse.exists, null);
});

test("explicit answers preserve zero income and the form's explicit None marker", () => {
  const data = clientPrefill({
    BDT_Philly_Resident__c: "Yes",
    BDT_Wants_Cash__c: "No",
    BDT_Household_Income__c: 0,
    BDT_Enrollment_Review__c: "Confirmed",
    BDT_Has_Spouse__c: "No",
    BDT_Spouse_Birthdate__c: "1950-01-01",
  });
  assert.equal(data.simpleChecks.livesInPhiladelphiaPa, true);
  assert.equal(data.custom.wantsExtraCash, false);
  assert.equal(data.custom.householdIncome, 0);
  assert.deepEqual(data.people.client.enrollments, ["__bdt_none_of_these__"]);
  assert.deepEqual(data.people.spouse, { exists: false, dateOfBirth: null });
});

test("confirmed program selections and spouse dates map to the published schema", () => {
  const data = clientPrefill({
    Birthdate: "1980-02-01",
    BDT_Has_Spouse__c: "Yes",
    BDT_Spouse_Birthdate__c: "1955-03-01",
    BDT_Enrollment_Review__c: "Confirmed",
    BDT_Enrolled_Programs__c: "PhlHomesteadExemption;PhlSeniorCitizenTaxFreeze",
    BDT_Owner_Occupant__c: "Yes",
    BDT_Tax_Delinquent__c: "Yes",
    BDT_Tax_Abatement__c: "No",
    BDT_Late_Spouse_65__c: "No",
    Email: "never-sent@example.org",
    BDT_Client_Notes__c: "Private client notes",
  });
  assert.deepEqual(data.people.client, {
    dateOfBirth: "1980-02-01",
    enrollments: ["PhlHomesteadExemption", "PhlSeniorCitizenTaxFreeze"],
  });
  assert.deepEqual(data.people.spouse, {
    exists: true,
    dateOfBirth: "1955-03-01",
  });
  assert.deepEqual(data.simpleChecks, {
    livesInPhiladelphiaPa: null,
    ownerOccupant: true,
    taxDelinquent: true,
    tenYearTaxAbatement: false,
    lateSpouseWasAtLeast65: false,
  });
  assert.equal(JSON.stringify(data).includes("Private client notes"), false);
  assert.equal(JSON.stringify(data).includes("never-sent@example.org"), false);
});

test("unconfirmed enrollment selections remain unknown", () => {
  const data = clientPrefill({
    BDT_Enrollment_Review__c: "Not reviewed",
    BDT_Enrolled_Programs__c: "PhlHomesteadExemption",
  });
  assert.equal(data.people.client.enrollments, null);
});
