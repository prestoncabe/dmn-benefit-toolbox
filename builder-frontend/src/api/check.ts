import { authGet, authPatch, authPost, authPut } from "@/api/auth";
import { env } from "@/config/environment";

import type {
  EligibilityCheck,
  OptionalBoolean,
  CreateCheckRequest,
  UpdateCheckRequest,
} from "@/types";

const apiUrl = env.apiUrl;

/* Carries the response status so callers can tell a rejected request apart from a failed one. */
export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

export const fetchPublicChecks = async (): Promise<EligibilityCheck[]> => {
  const url = apiUrl + "/library-checks";
  try {
    const response = await authGet(url);

    if (!response.ok) {
      throw new Error(`Fetch failed with status: ${response.status}`);
    }
    const data = await response.json();
    return data;
  } catch (error) {
    console.error("Error fetching checks:", error);
    // TODO: handle error appropriately
    return [];
  }
};

export const fetchCheck = async (
  checkId: string,
): Promise<EligibilityCheck> => {
  let url = apiUrl + `/custom-checks/${checkId}`;
  if (checkId.charAt(0) === "L") {
    url = apiUrl + `/library-checks/${checkId}`;
  }

  try {
    const response = await authGet(url);

    if (!response.ok) {
      throw new Error(`Fetch failed with status: ${response.status}`);
    }
    const data = await response.json();
    console.log("Fetched custom check:", data);
    return data;
  } catch (error) {
    console.error("Error fetching custom check:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const addCheck = async (
  check: CreateCheckRequest,
): Promise<EligibilityCheck> => {
  const url = apiUrl + "/custom-checks";
  try {
    const response = await authPost(url, {
      name: check.name,
      module: check.module,
      description: check.description,
      parameterDefinitions: check.parameterDefinitions,
    });

    if (!response.ok) {
      let message = `Post failed with status: ${response.status}`;
      try {
        const body = await response.json();
        if (typeof body.error === "string") message = body.error;
      } catch {
        // Keep the status-based fallback when the server does not return JSON.
      }
      throw new ApiError(message, response.status);
    }
    const data = await response.json();
    return data;
  } catch (error) {
    console.error("Error creating new check:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const updateCheck = async (
  checkId: string,
  updates: UpdateCheckRequest,
): Promise<EligibilityCheck> => {
  const url = apiUrl + `/custom-checks/${checkId}`;
  try {
    // Build request body with only non-undefined fields (partial update)
    const body: UpdateCheckRequest = {};
    if (updates.description !== undefined)
      body.description = updates.description;
    if (updates.parameterDefinitions !== undefined)
      body.parameterDefinitions = updates.parameterDefinitions;

    const response = await authPatch(url, body);

    if (!response.ok) {
      throw new Error(`Update failed with status: ${response.status}`);
    }
    const data = await response.json();
    return data;
  } catch (error) {
    console.error("Error updating check:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const saveCheckDmn = async (checkId: string, dmnModel: string) => {
  const url = apiUrl + `/custom-checks/${checkId}/dmn`;
  try {
    const response = await authPut(url, { dmnModel: dmnModel });

    if (!response.ok) {
      throw new Error(`DMN save failed with status: ${response.status}`);
    }
  } catch (error) {
    console.error("Error saving DMN for check:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const validateCheckDmn = async (
  checkId: string,
  dmnModel: string,
): Promise<string[]> => {
  const url = apiUrl + `/custom-checks/${checkId}/dmn/validate`;
  try {
    const response = await authPost(url, { dmnModel: dmnModel });

    if (!response.ok) {
      throw new Error(`Validation failed with status: ${response.status}`);
    }

    const data = await response.json();
    return data.errors;
  } catch (error) {
    console.error("Error validating DMN for check:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const fetchUserDefinedChecks = async (
  working: boolean,
  archived = false,
): Promise<EligibilityCheck[]> => {
  const workingQueryParam = working ? "true" : "false";
  const archivedQueryParam = archived ? "&archived=true" : "";
  const url =
    apiUrl + `/custom-checks?working=${workingQueryParam}${archivedQueryParam}`;

  try {
    const response = await authGet(url);

    if (!response.ok) {
      throw new Error(`Fetch failed with status: ${response.status}`);
    }
    const data = await response.json();
    return data;
  } catch (error) {
    console.error("Error fetching checks:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const evaluateWorkingCheck = async (
  checkId: string,
  checkConfig: any,
  inputData: Record<string, any>,
): Promise<OptionalBoolean> => {
  const url = apiUrl + `/decision/working-check?checkId=${checkId}`;
  try {
    const response = await authPost(url, {
      checkConfig: checkConfig,
      inputData: inputData,
    });

    if (!response.ok) {
      throw new Error(`Test check failed with status: ${response.status}`);
    }
    const data = await response.json();
    return data["result"];
  } catch (error) {
    console.error("Error testing check:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const getRelatedPublishedChecks = async (
  checkId: string,
): Promise<EligibilityCheck[]> => {
  const url = apiUrl + `/custom-checks/${checkId}/versions`;
  try {
    const response = await authGet(url);

    if (!response.ok) {
      throw new Error(`Fetch failed with status: ${response.status}`);
    }
    const data = await response.json();
    return data;
  } catch (error) {
    console.error("Error fetching related published checks:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const publishCheck = async (
  checkId: string,
): Promise<OptionalBoolean> => {
  const url = apiUrl + `/custom-checks/${checkId}/publish`;
  try {
    const response = await authPost(url);

    if (!response.ok) {
      throw new Error(`Publish failed with status: ${response.status}`);
    }
    const data = await response.json();
    return data["result"];
  } catch (error) {
    console.error("Error publishing check:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const archiveCheck = async (checkId: string): Promise<void> => {
  const url = apiUrl + `/custom-checks/${checkId}/archive`;
  try {
    const response = await authPost(url);

    if (!response.ok) {
      throw new Error(`Archive failed with status: ${response.status}`);
    }
  } catch (error) {
    console.error("Error archiving check:", error);
    throw error;
  }
};

export const restoreCheck = async (checkId: string): Promise<void> => {
  const url = apiUrl + `/custom-checks/${checkId}/restore`;
  try {
    const response = await authPost(url);

    if (!response.ok) {
      throw new Error(`Restore failed with status: ${response.status}`);
    }
  } catch (error) {
    console.error("Error restoring check:", error);
    throw error;
  }
};
