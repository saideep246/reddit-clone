import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../lib/apiClient';
import { cancelModeratorInvite, fetchModeratorInvites, fetchModerators, removeModerator, saveModerator, sendModeratorInvite } from '../lib/moderationApi';
import type { ModeratorEntry, ModeratorInviteEntry } from '../types/moderation';

interface UseModeratorsResult {
  moderators: ModeratorEntry[];
  loading: boolean;
  error: string | null;
  // Pending invitations (live ones only; the server drops answered, cancelled and lapsed ones from this list).
  invites: ModeratorInviteEntry[];
  // Sends an invitation by username; the person becomes a moderator only when they accept. Throws an Error with a
  // user-readable message (the server's own text, e.g. "cannot grant permissions beyond your own").
  invite: (username: string, permissions: number) => Promise<void>;
  cancelInvite: (inviteId: string) => Promise<void>;
  updatePermissions: (userId: string, permissions: number) => Promise<void>;
  remove: (userId: string) => Promise<void>;
}

function describe(err: unknown, fallback: string): Error {
  // The backend's own messages (e.g. "cannot grant permissions beyond your own") are already user-readable.
  return new Error(err instanceof ApiError && err.message ? err.message : fallback);
}

export function useModerators(communityName: string): UseModeratorsResult {
  const [moderators, setModerators] = useState<ModeratorEntry[]>([]);
  const [invites, setInvites] = useState<ModeratorInviteEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [mods, pending] = await Promise.all([fetchModerators(communityName), fetchModeratorInvites(communityName)]);
      setModerators(mods);
      setInvites(pending);
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

  const invite = useCallback(
    async (username: string, permissions: number) => {
      try {
        await sendModeratorInvite(communityName, username, permissions);
      } catch (err) {
        throw describe(err, 'Could not send the invitation.');
      }
      await load();
    },
    [communityName, load],
  );

  const cancelInvite = useCallback(
    async (inviteId: string) => {
      try {
        await cancelModeratorInvite(communityName, inviteId);
      } catch (err) {
        throw describe(err, 'Could not cancel the invitation.');
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

  return { moderators, invites, loading, error, invite, cancelInvite, updatePermissions, remove };
}
