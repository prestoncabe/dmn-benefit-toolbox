import { LightningElement, api, wire } from "lwc";
import { getRecord, getFieldValue, updateRecord } from "lightning/uiRecordApi";
import BIRTHDATE from "@salesforce/schema/Contact.Birthdate";
import SCREENING from "@salesforce/schema/Contact.BDT_Screening__c";
import { createCrmIntegration } from "c/bdtCrmHost";

import { clientPrefill } from "./clientPrefill";
import PHILLY_RESIDENT from "@salesforce/schema/Contact.BDT_Philly_Resident__c";
import WANTS_CASH from "@salesforce/schema/Contact.BDT_Wants_Cash__c";
import HOUSEHOLD_INCOME from "@salesforce/schema/Contact.BDT_Household_Income__c";
import OWNER_OCCUPANT from "@salesforce/schema/Contact.BDT_Owner_Occupant__c";
import TAX_DELINQUENT from "@salesforce/schema/Contact.BDT_Tax_Delinquent__c";
import TAX_ABATEMENT from "@salesforce/schema/Contact.BDT_Tax_Abatement__c";
import HAS_SPOUSE from "@salesforce/schema/Contact.BDT_Has_Spouse__c";
import SPOUSE_BIRTHDATE from "@salesforce/schema/Contact.BDT_Spouse_Birthdate__c";
import LATE_SPOUSE_65 from "@salesforce/schema/Contact.BDT_Late_Spouse_65__c";
import ENROLLMENT_REVIEW from "@salesforce/schema/Contact.BDT_Enrollment_Review__c";
import ENROLLED_PROGRAMS from "@salesforce/schema/Contact.BDT_Enrolled_Programs__c";

const OPTIONAL_INTAKE_FIELDS = [
  PHILLY_RESIDENT,
  WANTS_CASH,
  HOUSEHOLD_INCOME,
  OWNER_OCCUPANT,
  TAX_DELINQUENT,
  TAX_ABATEMENT,
  HAS_SPOUSE,
  SPOUSE_BIRTHDATE,
  LATE_SPOUSE_65,
  ENROLLMENT_REVIEW,
  ENROLLED_PROGRAMS,
];

const CONNECTION_ERRORS = {
  INITIALIZATION_FAILED: () =>
    "Could not connect to the screener. Try Screen here or open a new screening window.",
  CONNECTION_TIMEOUT: () =>
    "The screener didn’t connect. Check the screener URL in App Builder and that Salesforce can frame or open it.",
  ORIGIN_MISMATCH: ({ origin }) =>
    `The screener URL redirects to ${origin}. Use that address in App Builder.`,
  SCREENER_UNAVAILABLE: () =>
    "The screener couldn’t be loaded. Check that it’s published and the screener URL in App Builder.",
};

export default class BdtScreening extends LightningElement {
  _recordId;
  @api
  get recordId() {
    return this._recordId;
  }
  set recordId(value) {
    if (value !== this._recordId) {
      this.connection?.dispose();
      this.frameUrl = undefined;
      this.latest = undefined;
      this.status =
        "Start a screening with this client’s saved intake answers.";
    }
    this._recordId = value;
  }
  @api screenerUrl;
  @wire(getRecord, {
    recordId: "$recordId",
    fields: [BIRTHDATE],
    optionalFields: OPTIONAL_INTAKE_FIELDS,
  })
  contact;
  frameUrl;
  latest;
  status = "Start a screening with this client’s saved intake answers.";
  saving = false;
  connection;
  popup;

  get cannotSave() {
    return (
      !this.latest || this.saving || this.latest.recordId !== this.recordId
    );
  }

  start(event) {
    this.connection?.dispose();
    this.frameUrl = undefined;
    this.latest = undefined;
    const recordId = this.recordId;
    if (!this.contact?.data || this.contact.data.id !== recordId) {
      this.status =
        "The client could not be loaded. Check field permissions and try again.";
      return;
    }
    const popupMode = event.currentTarget.dataset.mode === "popup";
    try {
      this.connection = createCrmIntegration({
        screenerUrl: this.screenerUrl,
        // LWS proxies can prevent structured cloning to a popup window.
        serializeMessages: true,
        getTargetWindow: () =>
          popupMode
            ? this.popup
            : this.template.querySelector("iframe")?.contentWindow,
        initialData: clientPrefill(
          Object.fromEntries(
            [BIRTHDATE, ...OPTIONAL_INTAKE_FIELDS].map((field) => [
              field.fieldApiName,
              getFieldValue(this.contact.data, field),
            ]),
          ),
        ),
        onResult: (message) => {
          if (this.recordId !== recordId) return;
          this.latest = { recordId, message };
          this.status =
            "Results received. Review them, then save to the client record.";
        },
        onError: (message) => {
          if (this.recordId !== recordId) return;
          this.latest = undefined;
          this.status =
            CONNECTION_ERRORS[message.code]?.(message) ??
            "Evaluation failed. Change an answer to retry.";
        },
      });
      this.status = "Waiting for screening results…";
      if (popupMode) {
        this.popup = window.open(this.connection.url, "_blank");
        if (!this.popup) {
          this.connection.dispose();
          this.status = "Allow popups for Salesforce, then try again.";
        }
      } else {
        this.frameUrl = this.connection.url;
      }
    } catch {
      this.status =
        "Configure a valid absolute published screener URL in App Builder.";
    }
  }

  async save() {
    if (this.cannotSave) return;
    const { recordId, message } = this.latest;
    const snapshot = JSON.stringify({
      screenerId: message.screenerId,
      requestId: message.requestId,
      evaluatedAt: message.evaluatedAt,
      results: message.results,
    });
    if (snapshot.length > 131072) {
      this.status =
        "Results exceed the configured field size; use a related screening object.";
      return;
    }
    this.saving = true;
    try {
      await updateRecord({
        fields: { Id: recordId, [SCREENING.fieldApiName]: snapshot },
      });
      if (this.recordId === recordId)
        this.status = "Screening results saved to the client record.";
    } catch {
      if (this.recordId === recordId)
        this.status = "Could not save. Check field permissions and try again.";
    } finally {
      this.saving = false;
    }
  }

  disconnectedCallback() {
    this.connection?.dispose();
  }
}
