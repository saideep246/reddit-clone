import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../lib/apiClient';
import { approveSubmitter, fetchApprovedSubmitters, removeApprovedSubmitter } from '../lib/moderationApi';
import type { ApprovedSubmitterEntry } from '../types/moderation';

interface UseApprovedSubmittersResult {
  submitters: ApprovedSubmitterEntry[];
  loading: boolean;
  error: string | null;
  // Both throw an Error with a user-readable message (the server's own text where it has one).
  approve: (userId: string) => Promise<void>;
  remove: (userId: string) => Promise<void>;
}

function describe(err: unknown, fallback: string): Error {
  return new Error(err instanceof ApiError && err.message ? err.message : fallback);
}

export function useApprovedSubmitters(communityName: string): UseApprovedSubmittersResult {
  const [submitters, setSubmitters] = useState<ApprovedSubmitterEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setSubmitters(await fetchApprovedSubmitters(communityName));
      setError(null);
    } catch {
      setError('Could not load the approved posters.');
    } finally {
      setLoading(false);
    }
  }, [communityName]);

  useEffect(() => {
    load();
  }, [load]);

  const approve = useCallback(
    async (userId: string) => {
      try {
        await approveSubmitter(communityName, userId);
      } catch (err) {
        throw describe(err, 'Could not approve that person.');
      }
      await load();
    },
    [communityName, load],
  );

  const remove = useCallback(
    async (userId: string) => {
      try {
        await removeApprovedSubmitter(communityName, userId);
      } catch (err) {
        throw describe(err, 'Could not remove that person.');
      }
      await load();
    },
    [communityName, load],
  );

  return { submitters, loading, error, approve, remove };
}
