import { api, ApiError } from './apiClient';
import type { MyModeratorInvite } from '../types/moderation';

export function fetchMyModeratorInvites(): Promise<MyModeratorInvite[]> {
  return api.get('/api/moderator-invites') as Promise<MyModeratorInvite[]>;
}

// No request body on purpose: the server derives the recipient, community and permissions from the stored invitation.
export function acceptModeratorInvite(inviteId: string): Promise<unknown> {
  return api.post(`/api/moderator-invites/${inviteId}/accept`);
}

export function declineModeratorInvite(inviteId: string): Promise<unknown> {
  return api.post(`/api/moderator-invites/${inviteId}/decline`);
}

// What the server said when it refused, in words a recipient can act on. The server decides every outcome (expired, already
// answered, cancelled, deleted community, banned, inactive account); this only phrases it.
export function inviteErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 404) return 'This invitation is no longer available. The community may have been deleted or the invitation withdrawn.';
    if (err.status === 409) return `${capitalize(err.message)}.`;
    if (err.status === 403) return err.message === 'banned from this community' ? 'You are banned from this community, so you cannot accept this invitation.' : `${capitalize(err.message)}.`;
    if (err.status === 401) return 'Please sign in again.';
  }
  return 'Could not reach the server. Check your connection and try again.';
}

function capitalize(text: string): string {
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : text;
}

