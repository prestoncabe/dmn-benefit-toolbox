import { LightningElement, api, wire } from "lwc";
import { getRecord, getFieldValue } from "lightning/uiRecordApi";
import CONTACT_ID from "@salesforce/schema/Contact.Id";
import SCREENING from "@salesforce/schema/Contact.BDT_Screening__c";
import { formatSavedResults } from "./formatResults";

export default class BdtScreeningResults extends LightningElement {
  @api recordId;
  @wire(getRecord, {
    recordId: "$recordId",
    fields: [CONTACT_ID],
    optionalFields: [SCREENING],
  })
  contact;

  get view() {
    const data = this.contact?.data;
    if (!data || data.id !== this.recordId) {
      return {
        message: this.contact?.error
          ? "Could not load saved results. Refresh the page to retry."
          : "Loading saved results…",
      };
    }
    if (
      !Object.prototype.hasOwnProperty.call(data.fields, SCREENING.fieldApiName)
    ) {
      return {
        message:
          "Saved results aren’t available. Check access to the BDT Screening field.",
      };
    }
    const raw = getFieldValue(data, SCREENING);
    if (!raw)
      return {
        message:
          "No screening results saved yet. Run a screening, then choose Save results to client.",
      };
    try {
      return { saved: true, ...formatSavedResults(raw) };
    } catch {
      return {
        message:
          "The saved snapshot could not be displayed. Run a screening and save a new result.",
      };
    }
  }
}
