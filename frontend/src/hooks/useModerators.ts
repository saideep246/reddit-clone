import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../lib/apiClient';
import { fetchModerators, removeModerator, saveModerator } from '../lib/moderationApi';
import { fetchPublicProfile } from '../lib/userApi';
import type { ModeratorEntry } from '../types/moderation';

interface UseModeratorsResult {
  moderators: ModeratorEntry[];
  loading: boolean;
  error: string | null;
  // Resolves the typed username to an id (POST /mod/moderators needs a userId) and saves the permissions. Re-adding an
  // existing moderator just replaces their permissions. Throws an Error with a user-readable message.
  addByUsername: (username: string, permissions: number) => Promise<void>;
  updatePermissions: (userId: string, permissions: number) => Promise<void>;
  remove: (userId: string) => Promise<void>;
}

function describe(err: unknown, fallback: string): Error {
  // The backend's own messages (e.g. "cannot grant permissions beyond your own") are already user-readable.
  return new Error(err instanceof ApiError && err.message ? err.message : fallback);
}

export function useModerators(communityName: string): UseModeratorsResult {
  const [moderators, setModerators] = useState<ModeratorEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setModerators(await fetchModerators(communityName));
      setError(null);
    } catch {
      setError('Could not load the moderator list.');
    } finally {
      setLoading(false);
    }
  }, [communityName]);

  useEffect(() => {
    load();
  }, [load]);

  const addByUsername = useCallback(
    async (username: string, permissions: number) => {
      let userId: string;
      try {
        userId = (await fetchPublicProfile(username)).id;
      } catch (err) {
        throw new Error(err instanceof ApiError && err.status === 404 ? 'No such user.' : 'Could not look up that user.');
      }
      try {
        await saveModerator(communityName, userId, permissions);
      } catch (err) {
        throw describe(err, 'Could not add that moderator.');
      }
      await load();
    },
    [communityName, load],
  );

  const updatePermissions = useCallback(
    async (userId: string, permissions: number) => {
      try {
        await saveModerator(communityName, userId, permissions);
      } catch (err) {
        throw describe(err, 'Could not update the permissions.');
      }
      await load();
    },
    [communityName, load],
  );

  const remove = useCallback(
    async (userId: string) => {
      try {
        await removeModerator(communityName, userId);
      } catch (err) {
        throw describe(err, 'Could not remove that moderator.');
      }
      await load();
    },
    [communityName, load],
  );

  return { moderators, loading, error, addByUsername, updatePermissions, remove };
}
