import type { JSONSchema7 } from "json-schema";

/* Types for managing benefits in a screener */
export interface ScreenerBenefits {
  benefits: BenefitDetail[];
}
export interface BenefitDetail {
  id: string;
  name: string;
  description: string;
}

export interface Benefit {
  id: string;
  name: string;
  description: string;
  checks: CheckConfig[];
}
// An EligibilityCheck, as configured by a particular Benefit
export interface CheckConfig {
  checkId: string;
  checkName: string;
  checkVersion: string;
  checkModule: string;
  checkDescription: string;
  // API endpoint for evaluating check (only for library checks)
  evaluationUrl?: string;
  parameters: ParameterValues;
  inputDefinition: JSONSchema7;
  parameterDefinitions: ParameterDefinition[];
  // Optional user-defined alias for display purposes
  aliasName?: string;
  // True when aliasName was generated and not hand-edited
  aliasGenerated?: boolean;
}
export interface ParameterValues {
  [key: string]: string | number | boolean | string[] | null;
}

export interface EligibilityCheck {
  id: string;
  name: string;
  module: string;
  version: string;
  description: string;
  inputDefinition: JSONSchema7;
  parameterDefinitions: ParameterDefinition[];
  isArchived?: boolean;
  // API endpoint for evaluating check (Library checks only)
  evaluationUrl?: string;
  // Milliseconds since the epoch; null on working checks
  datePublished?: number | null;
}
export interface CustomCheckWithDmn extends EligibilityCheck {
  dmnModel: string;
}

// Request types for EligibilityCheck API endpoints
export interface CreateCheckRequest {
  name: string;
  module: string;
  description: string;
  parameterDefinitions: ParameterDefinition[];
}

export interface UpdateCheckRequest {
  name?: string;
  dmnModel?: string;
  originalDmnModel?: string;
  description?: string;
  parameterDefinitions?: ParameterDefinition[];
}

// Request types for Custom Benefit API endpoints
export interface CreateCustomBenefitRequest {
  name: string;
  description: string;
}

export interface UpdateCustomBenefitRequest {
  name: string;
  description: string;
}

export interface ImportLibraryBenefitRequest {
  benefitId: string;
}

export interface AddCheckRequest {
  checkId: string;
  parameters: ParameterValues;
}

export interface UpdateCheckParametersRequest {
  parameters: ParameterValues;
}

// Parameter Types
export type ParameterType = "string" | "number" | "boolean" | "date" | "array";
export type ParameterDefinition = {
  key: string;
  label: string;
  type: "string" | "number" | "boolean" | "date" | "array";
  required: boolean;
};

/* Screener Evaluation Results */
export interface ScreenerResult {
  [key: string]: BenefitResult;
}
export interface BenefitResult {
  name: string;
  result: OptionalBoolean;
  check_results: {
    [key: string]: CheckResult;
  };
}
export interface CheckResult {
  name: string;
  aliasName?: string;
  result: OptionalBoolean;
  module: string;
  version: string;
  parameters: ParameterValues;
  effectiveParameters?: ParameterValues;
  defaultedParameters?: string[];
  /** Form fields this check contributes to its configured benefit's question votes. */
  inputPaths: string[];
}
export type OptionalBoolean = "TRUE" | "FALSE" | "UNABLE_TO_DETERMINE";

/* Form Data for Preview */
export interface PreviewFormData {
  [key: string]: any;
}

// Published Screener Types
export interface PublishedScreener {
  screenerName: string;
  formSchema: any;
  /** Exact origins of CRMs allowed to prefill and receive results. */
  integrationOrigins?: string[];
}

// Selectable Form Path in the Form Editor view
export interface FormPath {
  path: string;
  type: string;
}
