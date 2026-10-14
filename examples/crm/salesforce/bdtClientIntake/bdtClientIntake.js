import { LightningElement, api, wire } from "lwc";
import { getRecord, getFieldValue } from "lightning/uiRecordApi";
import CONTACT_ID from "@salesforce/schema/Contact.Id";
import OWNER_NAME from "@salesforce/schema/Contact.Owner.Name";

const PROFILE = [
  {
    title: "Contact details",
    fields: ["Name", "Phone", "Email", "MailingAddress"],
  },
  {
    title: "Service coordination",
    fields: [
      "BDT_Client_Status__c",
      "BDT_Intake_Date__c",
      "BDT_Preferred_Contact__c",
      "BDT_Household_Size__c",
      "BDT_Client_Notes__c",
    ],
  },
];
const SCREENING = [
  {
    title: "Household & assistance",
    fields: [
      "Birthdate",
      "BDT_Philly_Resident__c",
      "BDT_Household_Income__c",
      "BDT_Wants_Cash__c",
    ],
  },
  {
    title: "Housing & property taxes",
    fields: [
      "BDT_Owner_Occupant__c",
      "BDT_Tax_Delinquent__c",
      "BDT_Tax_Abatement__c",
    ],
  },
  {
    title: "Age & family",
    fields: [
      "BDT_Has_Spouse__c",
      "BDT_Spouse_Birthdate__c",
      "BDT_Late_Spouse_65__c",
    ],
  },
  {
    title: "Current program enrollment",
    fields: ["BDT_Enrollment_Review__c", "BDT_Enrolled_Programs__c"],
  },
];

export default class BdtClientIntake extends LightningElement {
  _recordId;
  @api get recordId() {
    return this._recordId;
  }
  set recordId(value) {
    if (value !== this._recordId) {
      this.editing = false;
      this.status = "";
    }
    this._recordId = value;
  }
  @api variant = "screening";
  @wire(getRecord, {
    recordId: "$recordId",
    fields: [CONTACT_ID],
    optionalFields: [OWNER_NAME],
  })
  client;
  editing = false;
  saving = false;
  status = "";
  get profile() {
    return this.variant === "profile";
  }
  get title() {
    return this.profile ? "Client profile" : "Screening intake";
  }
  get editLabel() {
    return this.profile ? "Edit client profile" : "Edit screening intake";
  }
  get introduction() {
    return this.profile
      ? "Contact information and service coordination for benefits navigation."
      : "Saved answers prefill the benefit screener. Keep unanswered questions Unknown. Confirm enrollment information even when no programs are selected.";
  }
  get sections() {
    return (this.profile ? PROFILE : SCREENING).map((section) => ({
      ...section,
      fields: section.fields.map((apiName) => ({
        apiName,
        label: apiName === "Phone" ? "Phone" : null,
        variant: apiName === "Phone" ? "label-hidden" : "label-stacked",
      })),
    }));
  }
  get staffName() {
    return this.client?.data?.id === this.recordId
      ? (getFieldValue(this.client.data, OWNER_NAME) ?? "Not recorded")
      : "Loading…";
  }
  edit() {
    this.editing = true;
    this.status = "";
  }
  cancel() {
    this.editing = false;
    this.status = "";
  }
  submit() {
    this.saving = true;
    this.status = "Saving client information…";
  }
  success() {
    this.saving = false;
    this.editing = false;
    this.status = this.profile
      ? "Client profile saved."
      : "Intake saved. Start a new screening to use these answers.";
  }
  error() {
    this.saving = false;
    this.status = "Could not save. Review the field errors and try again.";
  }
}
