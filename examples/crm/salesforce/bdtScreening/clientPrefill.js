const PROGRAMS = [
  "PhlHomesteadExemption",
  "PhlOwnerOccupiedPaymentAgreement",
  "PhlSeniorCitizenTaxFreeze",
];

function yesNo(value) {
  return value === "Yes" ? true : value === "No" ? false : null;
}

// Keys belong to the published Philadelphia example, not the Salesforce schema.
// An explicit None marker is required by the form's checklist_none field.
export function clientPrefill(values) {
  const selected = (values.BDT_Enrolled_Programs__c ?? "")
    .split(";")
    .filter((program) => PROGRAMS.includes(program));
  const enrollments =
    values.BDT_Enrollment_Review__c === "Confirmed"
      ? selected.length
        ? selected
        : ["__bdt_none_of_these__"]
      : null;
  const hasSpouse = yesNo(values.BDT_Has_Spouse__c);
  return {
    people: {
      client: { dateOfBirth: values.Birthdate ?? null, enrollments },
      spouse: {
        exists: hasSpouse,
        dateOfBirth:
          hasSpouse === true ? (values.BDT_Spouse_Birthdate__c ?? null) : null,
      },
    },
    custom: {
      wantsExtraCash: yesNo(values.BDT_Wants_Cash__c),
      householdIncome: values.BDT_Household_Income__c ?? null,
    },
    simpleChecks: {
      livesInPhiladelphiaPa: yesNo(values.BDT_Philly_Resident__c),
      ownerOccupant: yesNo(values.BDT_Owner_Occupant__c),
      taxDelinquent: yesNo(values.BDT_Tax_Delinquent__c),
      tenYearTaxAbatement: yesNo(values.BDT_Tax_Abatement__c),
      lateSpouseWasAtLeast65: yesNo(values.BDT_Late_Spouse_65__c),
    },
  };
}
