import { authDelete, authGet, authPatch, authPost } from "@/api/auth";
import { env } from "@/config/environment";
import { authFetch } from "@/api/auth";

import { Benefit, UpdateCustomBenefitRequest, ParameterValues } from "@/types";

const apiUrl = env.apiUrl;

export const fetchScreenerBenefit = async (
  srceenerId: string,
  benefitId: string,
): Promise<Benefit> => {
  const url = apiUrl + "/screener/" + srceenerId + "/benefit/" + benefitId;
  try {
    const response = await authGet(url);

    if (!response.ok) {
      throw new Error(`Fetch failed with status: ${response.status}`);
    }
    const data = await response.json();
    return data as Benefit;
  } catch (error) {
    console.error("Error fetching screener:", error);
    throw error; // rethrow so you can handle it in your component if needed
  }
};

export const fetchLibraryBenefits = async (): Promise<Benefit[]> => {
  const response = await authGet(apiUrl + "/library-benefits");
  if (!response.ok) {
    throw new Error(`Fetch failed with status: ${response.status}`);
  }
  return response.json();
};

export const updateScreenerBenefit = async (
  screenerId: string,
  benefitId: string,
  benefitData: UpdateCustomBenefitRequest,
): Promise<Benefit> => {
  const url = apiUrl + "/screener/" + screenerId + "/benefit/" + benefitId;
  try {
    const response = await authPatch(url.toString(), benefitData);

    if (!response.ok) {
      throw new Error(`Update failed with status: ${response.status}`);
    }
    const data = await response.json();
    return data as Benefit;
  } catch (error) {
    console.error("Error updating benefit:", error);
    throw error;
  }
};

export const addCheckToBenefit = async (
  screenerId: string,
  benefitId: string,
  checkId: string,
): Promise<void> => {
  const url =
    apiUrl + "/screener/" + screenerId + "/benefit/" + benefitId + "/check";
  try {
    const response = await authPost(url.toString(), { checkId });
    if (!response.ok) {
      throw new Error(`Add check failed with status: ${response.status}`);
    }
  } catch (error) {
    console.error("Error adding check to benefit:", error);
    throw error;
  }
};

export const removeCheckFromBenefit = async (
  screenerId: string,
  benefitId: string,
  checkId: string,
): Promise<void> => {
  const url =
    apiUrl +
    "/screener/" +
    screenerId +
    "/benefit/" +
    benefitId +
    "/check/" +
    checkId;
  try {
    const response = await authDelete(url);

    if (!response.ok) {
      throw new Error(`Remove check failed with status: ${response.status}`);
    }
  } catch (error) {
    console.error("Error removing check from benefit:", error);
    throw error;
  }
};

export const updateCheckParameters = async (
  screenerId: string,
  benefitId: string,
  checkId: string,
  parameters: ParameterValues,
): Promise<void> => {
  const url =
    apiUrl +
    "/screener/" +
    screenerId +
    "/benefit/" +
    benefitId +
    "/check/" +
    checkId +
    "/parameters";
  try {
    const response = await authPatch(url.toString(), { parameters });
    if (!response.ok) {
      throw new Error(
        `Update parameters failed with status: ${response.status}`,
      );
    }
  } catch (error) {
    console.error("Error updating check parameters:", error);
    throw error;
  }
};

export const updateCheckAlias = async (
  screenerId: string,
  benefitId: string,
  checkId: string,
  aliasName: string | null,
): Promise<void> => {
  const url =
    apiUrl +
    "/screener/" +
    screenerId +
    "/benefit/" +
    benefitId +
    "/check/" +
    checkId +
    "/alias";
  try {
    const response = await authPatch(url.toString(), { aliasName });
    if (!response.ok) {
      throw new Error(`Update alias failed with status: ${response.status}`);
    }
  } catch (error) {
    console.error("Error updating check alias:", error);
    throw error;
  }
};
