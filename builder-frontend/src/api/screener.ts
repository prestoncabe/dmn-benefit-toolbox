import { env } from "@/config/environment";

import { authDelete, authGet, authPatch, authPost } from "@/api/auth";

import type {
  CreateCustomBenefitRequest,
  FormPath,
  ScreenerResult,
} from "@/types";

const apiUrl = env.apiUrl;

export const fetchProjects = async () => {
  const url = apiUrl + "/screeners";
  try {
    const response = await authGet(url);

    if (!response.ok) {
      throw new Error(`Fetch failed with status: ${response.status}`);
    }
    const data = await response.json();
    return data;
  } catch (error) {
    console.error("Error fetching projects:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const fetchProject = async (screenerId: string) => {
  const url = apiUrl + "/screener/" + screenerId;
  try {
    const response = await authGet(url);

    if (!response.ok) {
      throw new Error(`Fetch failed with status: ${response.status}`);
    }
    const data = await response.json();
    return data;
  } catch (error) {
    console.error("Error fetching screener:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const createNewScreener = async (request: {
  screenerName: string;
  description?: string;
}) => {
  const url = apiUrl + "/screener";
  try {
    const response = await authPost(url.toString(), request);

    if (!response.ok) {
      throw new Error(`Post failed with status: ${response.status}`);
    }
    const data = await response.json();
    return data;
  } catch (error) {
    console.error("Error creating new project:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const updateScreener = async (
  screenerId: string,
  request: { screenerName: string },
) => {
  const url = new URL(`${apiUrl}/screener/${screenerId}`);

  try {
    const response = await authPatch(url.toString(), request);

    if (!response.ok) {
      const err = await response.json();
      throw new Error(err);
    }
  } catch (error) {
    console.error("Error updating project:", error);
    throw error;
  }
};

export const deleteScreener = async (screenerId: string) => {
  const url = apiUrl + "/screener/delete?screenerId=" + screenerId;
  try {
    const response = await authDelete(url);

    if (!response.ok) {
      throw new Error(`Update failed with status: ${response.status}`);
    }
  } catch (error) {
    console.error("Error updating project:", error);
    throw error;
  }
};

export const saveFormSchema = async (screenerId: string, schema: any) => {
  const requestData: any = {};
  requestData.schema = schema;
  const url = new URL(`${apiUrl}/save-form-schema`);
  url.searchParams.append("screenerId", screenerId);

  try {
    const response = await authPost(url.toString(), requestData);

    if (!response.ok) {
      throw new Error(`Post failed with status: ${response.status}`);
    }
  } catch (error) {
    console.error("Error saving form schema:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const publishScreener = async (screenerId: string): Promise<void> => {
  const url = apiUrl + "/publish";
  try {
    const response = await authPost(url, { screenerId: screenerId });

    if (!response.ok) {
      throw new Error(`Submit failed with status: ${response.status}`);
    }
  } catch (error) {
    console.error("Error submitting form:", error);
    throw error;
  }
};

export const addCustomBenefit = async (
  screenerId: string,
  benefit: CreateCustomBenefitRequest,
) => {
  const url = apiUrl + "/screener/" + screenerId + "/benefit";
  try {
    const response = await authPost(url, benefit);

    if (!response.ok) {
      throw new Error(`Create benefit failed with status: ${response.status}`);
    }
  } catch (error) {
    console.error("Error creating benefit:", error);
    throw error;
  }
};

export const importLibraryBenefit = async (
  screenerId: string,
  benefitId: string,
) => {
  const url = apiUrl + "/screener/" + screenerId + "/benefit/import";
  const response = await authPost(url, { benefitId });
  if (!response.ok) {
    throw new Error(`Import benefit failed with status: ${response.status}`);
  }
};

export const removeCustomBenefit = async (
  screenerId: string,
  benefitId: string,
) => {
  const url = apiUrl + "/screener/" + screenerId + "/benefit/" + benefitId;
  try {
    const response = await authDelete(url);

    if (!response.ok) {
      throw new Error(
        `Delete of benefit failed with status: ${response.status}`,
      );
    }
  } catch (error) {
    console.error("Error deleting custom benefit:", error);
    throw error;
  }
};

export interface FormPathsResponse {
  paths: FormPath[];
}

export const fetchFormPaths = async (
  screenerId: string,
): Promise<FormPathsResponse> => {
  const url = apiUrl + "/screener/" + screenerId + "/form-paths";
  try {
    const response = await authGet(url);

    if (!response.ok) {
      throw new Error(
        `Fetch form paths failed with status: ${response.status}`,
      );
    }
    const data = await response.json();
    return data;
  } catch (error) {
    console.error("Error fetching form paths:", error);
    throw error;
  }
};

export const evaluateScreener = async (
  screenerId: string,
  inputData: any,
): Promise<ScreenerResult> => {
  const url = apiUrl + "/decision/v2?screenerId=" + screenerId;
  try {
    const response = await authPost(url, inputData);

    if (!response.ok) {
      throw new Error(`Evaluation failed with status: ${response.status}`);
    }

    const data = await response.json();
    return data;
  } catch (error) {
    console.error("Error evaluating:", error);
    throw error;
  }
};
