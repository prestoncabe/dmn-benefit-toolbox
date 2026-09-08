import { createResource, createEffect, Accessor, createSignal } from "solid-js";
import { createStore } from "solid-js/store";
import toast from "solid-toast";

import type { EligibilityCheck, CreateCheckRequest } from "@/types";
import {
  addCheck,
  archiveCheck,
  fetchUserDefinedChecks,
  restoreCheck,
} from "@/api/check";

export interface EligibilityCheckResource {
  checks: () => EligibilityCheck[];
  archivedChecks: () => EligibilityCheck[];
  actions: {
    addNewCheck: (check: CreateCheckRequest) => Promise<void>;
    removeCheck: (checkIdToRemove: string) => Promise<void>;
    restoreCheck: (checkIdToRestore: string) => Promise<void>;
  };
  actionInProgress: Accessor<boolean>;
  initialLoadStatus: {
    loading: Accessor<boolean>;
    error: Accessor<unknown>;
  };
}

const eligibilityCheckResource = (): EligibilityCheckResource => {
  const [checksResource, { refetch: refetchChecks }] = createResource(() =>
    fetchUserDefinedChecks(true),
  );
  const [archivedChecksResource, { refetch: refetchArchivedChecks }] =
    createResource(() => fetchUserDefinedChecks(true, true));
  const [actionInProgress, setActionInProgress] = createSignal<boolean>(false);

  // Local fine-grained store
  const [checks, setChecks] = createStore<EligibilityCheck[]>([]);
  const [archivedChecks, setArchivedChecks] = createStore<EligibilityCheck[]>(
    [],
  );

  // When resource resolves, sync it into the store. Reading an errored
  // resource rethrows, so check for failure before touching the accessor.
  createEffect(() => {
    if (checksResource.error) return;
    const loadedChecks = checksResource();
    if (loadedChecks) {
      setChecks(loadedChecks);
    }
  });

  createEffect(() => {
    if (archivedChecksResource.error) return;
    const loadedArchivedChecks = archivedChecksResource();
    if (loadedArchivedChecks) {
      setArchivedChecks(loadedArchivedChecks);
    }
  });

  // Actions
  const addNewCheck = async (check: CreateCheckRequest) => {
    setActionInProgress(true);
    try {
      await addCheck(check);
      await refetchChecks();
    } catch (e) {
      console.error("Failed to add new check", e);
      throw e;
    } finally {
      setActionInProgress(false);
    }
  };

  const removeCheck = async (checkIdToRemove: string) => {
    setActionInProgress(true);
    try {
      await archiveCheck(checkIdToRemove);
      await Promise.all([refetchChecks(), refetchArchivedChecks()]);
      toast.success("Check archived.");
    } catch (e) {
      console.error("Failed to archive check", e);
      toast.error("Could not archive check. Please try again.");
    } finally {
      setActionInProgress(false);
    }
  };

  const restoreArchivedCheck = async (checkIdToRestore: string) => {
    setActionInProgress(true);
    try {
      await restoreCheck(checkIdToRestore);
      await Promise.all([refetchChecks(), refetchArchivedChecks()]);
      toast.success("Check restored.");
    } catch (e) {
      console.error("Failed to restore check", e);
      toast.error("Could not restore check. Please try again.");
    } finally {
      setActionInProgress(false);
    }
  };

  return {
    checks: () => checks,
    archivedChecks: () => archivedChecks,
    actions: {
      addNewCheck,
      removeCheck,
      restoreCheck: restoreArchivedCheck,
    },
    actionInProgress,
    initialLoadStatus: {
      loading: () => checksResource.loading || archivedChecksResource.loading,
      error: () => checksResource.error ?? archivedChecksResource.error,
    },
  };
};

export default eligibilityCheckResource;
