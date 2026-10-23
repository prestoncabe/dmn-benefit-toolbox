import { YesNoQuestion } from './YesNoQuestion';
import { ChecklistWithNone } from './ChecklistWithNone';
import { NextSteps } from '../nextSteps/NextSteps';

/*
 * This is a module definition to register custom
 * form fields with the form-js FormEditor.
 */
class CustomFormFieldsModule {
  constructor(formFields) {
    formFields.register(YesNoQuestion.config.type, YesNoQuestion);
    formFields.register(ChecklistWithNone.config.type, ChecklistWithNone);
    formFields.register(NextSteps.config.type, NextSteps);
  }
}

CustomFormFieldsModule.$inject = ["formFields"]

export default {
  __init__: [ 'customFields' ],
  customFields: [ 'type', CustomFormFieldsModule ]
};
