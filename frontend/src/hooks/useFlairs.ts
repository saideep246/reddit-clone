import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../lib/apiClient';
import { createFlair, deleteFlair, fetchFlairs, updateFlair } from '../lib/moderationApi';
import type { Flair } from '../types/post';

interface UseFlairsResult {
  postFlairs: Flair[];
  userFlairs: Flair[];
  loading: boolean;
  error: string | null;
  create: (type: 'post' | 'user', text: string, color: string) => Promise<void>;
  update: (flairId: string, text: string, color: string) => Promise<void>;
  remove: (flairId: string) => Promise<void>;
}

// The backend's own messages (409 "a post flair with that name already exists", 403, 404...) are already user-readable.
function describe(err: unknown, fallback: string): Error {
  return new Error(err instanceof ApiError && err.message ? err.message : fallback);
}

export function useFlairs(communityName: string): UseFlairsResult {
  const [flairs, setFlairs] = useState<Flair[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setFlairs(await fetchFlairs(communityName));
      setError(null);
    } catch {
      setError('Could not load the flairs.');
    } finally {
      setLoading(false);
    }
  }, [communityName]);

  useEffect(() => {
    load();
  }, [load]);

  const create = useCallback(
    async (type: 'post' | 'user', text: string, color: string) => {
      try {
        await createFlair(communityName, text, color, type);
      } catch (err) {
        throw describe(err, 'Could not create the flair.');
      }
      await load();
    },
    [communityName, load],
  );

  const update = useCallback(
    async (flairId: string, text: string, color: string) => {
      try {
        await updateFlair(communityName, flairId, text, color);
      } catch (err) {
        throw describe(err, 'Could not save the flair.');
      }
      await load();
    },
    [communityName, load],
  );

  const remove = useCallback(
    async (flairId: string) => {
      try {
        await deleteFlair(communityName, flairId);
      } catch (err) {
        throw describe(err, 'Could not delete the flair.');
      }
      await load();
    },
    [communityName, load],
  );

  return {
    postFlairs: flairs.filter((f) => f.type === 'post'),
    userFlairs: flairs.filter((f) => f.type === 'user'),
    loading,
    error,
    create,
    update,
    remove,
  };
}
