import { LightningElement, api, wire } from "lwc";
import { getRecord, getFieldValue } from "lightning/uiRecordApi";
import NAME from "@salesforce/schema/Contact.Name";
import STATUS from "@salesforce/schema/Contact.BDT_Client_Status__c";

export default class BdtClientHeader extends LightningElement {
  @api recordId;
  @wire(getRecord, {
    recordId: "$recordId",
    fields: [NAME],
    optionalFields: [STATUS],
  })
  client;
  get name() {
    return this.client?.data?.id === this.recordId
      ? getFieldValue(this.client.data, NAME)
      : "Client";
  }
  get status() {
    return this.client?.data?.id === this.recordId
      ? (getFieldValue(this.client.data, STATUS) ?? "Status not recorded")
      : "";
  }
}
