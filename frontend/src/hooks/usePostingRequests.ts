import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../lib/apiClient';
import { approvePostingRequest, denyPostingRequest, fetchPostingRequests } from '../lib/moderationApi';
import type { PostingRequestEntry } from '../types/moderation';

interface UsePostingRequestsResult {
  requests: PostingRequestEntry[];
  loading: boolean;
  error: string | null;
  // Both throw an Error with a user-readable message; a request someone else already decided simply drops off the list.
  approve: (userId: string) => Promise<void>;
  deny: (userId: string) => Promise<void>;
}

function describe(err: unknown, fallback: string): Error {
  return new Error(err instanceof ApiError && err.message ? err.message : fallback);
}

export function usePostingRequests(communityName: string, onApproved?: () => void): UsePostingRequestsResult {
  const [requests, setRequests] = useState<PostingRequestEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setRequests(await fetchPostingRequests(communityName));
      setError(null);
    } catch {
      setError('Could not load the posting approval requests.');
    } finally {
      setLoading(false);
    }
  }, [communityName]);

  useEffect(() => {
    load();
  }, [load]);

  const decide = useCallback(
    async (action: () => Promise<unknown>, fallback: string, approved: boolean) => {
      try {
        await action();
      } catch (err) {
        await load(); // the list may simply be stale (another moderator already decided)
        throw describe(err, fallback);
      }
      await load();
      if (approved) onApproved?.();
    },
    [load, onApproved],
  );

  const approve = useCallback((userId: string) => decide(() => approvePostingRequest(communityName, userId), 'Could not approve that request.', true), [communityName, decide]);
  const deny = useCallback((userId: string) => decide(() => denyPostingRequest(communityName, userId), 'Could not deny that request.', false), [communityName, decide]);

  return { requests, loading, error, approve, deny };
}
