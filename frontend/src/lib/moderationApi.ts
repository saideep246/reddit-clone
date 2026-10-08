import { api } from './apiClient';
import type { Flair } from '../types/post';
import type {
  AutomodAction, AutomodRule, AutomodRuleConfig, AutomodRuleType, BanEntry, JoinRequestEntry, ModQueueItem, ApprovedSubmitterEntry, PostingRequestEntry, ModeratorEntry, ModeratorInviteEntry, ReportEntry } from '../types/moderation';

export function fetchModQueue(communityName: string): Promise<ModQueueItem[]> {
  return api.get(`/r/${communityName}/mod/queue`) as Promise<ModQueueItem[]>;
}

export function fetchReportsForTarget(communityName: string, targetType: string, targetId: string): Promise<ReportEntry[]> {
  const params = new URLSearchParams({ targetType, targetId });
  return api.get(`/r/${communityName}/mod/reports?${params.toString()}`) as Promise<ReportEntry[]>;
}

export function resolveReport(communityName: string, reportId: string): Promise<unknown> {
  return api.post(`/r/${communityName}/mod/reports/${reportId}/resolve`);
}

export function dismissReport(communityName: string, reportId: string): Promise<unknown> {
  return api.post(`/r/${communityName}/mod/reports/${reportId}/dismiss`);
}

export function removeContent(communityName: string, targetType: string, targetId: string, reason?: string): Promise<unknown> {
  return api.post(`/r/${communityName}/mod/remove/${targetType}/${targetId}`, reason ? { reason } : undefined);
}

export function fetchBans(communityName: string): Promise<BanEntry[]> {
  return api.get(`/r/${communityName}/mod/bans`) as Promise<BanEntry[]>;
}

export function issueBan(communityName: string, userId: string, reason?: string, expiresAt?: string | null): Promise<unknown> {
  return api.post(`/r/${communityName}/mod/ban`, { userId, reason, expiresAt });
}

export function liftBan(communityName: string, userId: string): Promise<unknown> {
  return api.del(`/r/${communityName}/mod/ban/${userId}`);
}

export function fetchJoinRequests(communityName: string): Promise<JoinRequestEntry[]> {
  return api.get(`/r/${communityName}/mod/join-requests`) as Promise<JoinRequestEntry[]>;
}

export function updateCommunityDescription(communityName: string, description: string): Promise<{ description: string | null }> {
  return api.patch(`/r/${communityName}/mod/settings`, { description }) as Promise<{ description: string | null }>;
}

export function approveJoinRequest(communityName: string, userId: string): Promise<unknown> {
  return api.post(`/r/${communityName}/mod/join-requests/${userId}/approve`);
}

export function denyJoinRequest(communityName: string, userId: string): Promise<unknown> {
  return api.post(`/r/${communityName}/mod/join-requests/${userId}/deny`);
}

// AutomodRule.config is stored and returned as a JSON *string* (a plain String column on the backend, see
// its class comment) — parsed into a real object here, in exactly one place, rather than leaving every
// caller to JSON.parse it themselves.
interface RawAutomodRule {
  id: string;
  communityId: string;
  ruleType: AutomodRuleType;
  config: string;
  action: AutomodAction;
  enabled: boolean;
  createdAt: string;
}

export async function fetchAutomodRules(communityName: string): Promise<AutomodRule[]> {
  const raw = (await api.get(`/r/${communityName}/mod/automod-rules`)) as RawAutomodRule[];
  return raw.map((r) => ({ ...r, config: JSON.parse(r.config) as AutomodRuleConfig }));
}

// The create endpoint's AutomodRuleRequest.config is already a real JSON object (Map<String,Object> on the
// backend) — passed straight through, unlike the read side above.
export async function addAutomodRule(
  communityName: string,
  ruleType: AutomodRuleType,
  config: AutomodRuleConfig,
  action: AutomodAction,
): Promise<AutomodRule> {
  const raw = (await api.post(`/r/${communityName}/mod/automod-rules`, { ruleType, config, action })) as RawAutomodRule;
  return { ...raw, config: JSON.parse(raw.config) as AutomodRuleConfig };
}

export function removeAutomodRule(communityName: string, ruleId: string): Promise<unknown> {
  return api.del(`/r/${communityName}/mod/automod-rules/${ruleId}`);
}

// ---- Mod notes (private, moderator-only)
export interface ModNote {
  id: string;
  userId: string; // the user the note is about
  subjectUsername: string | null;
  authorId: string;
  authorUsername: string | null;
  note: string;
  createdAt: string;
}

// With a userId: that user's notes. Without one: the community's most recent notes about everyone (what the Notes tab shows on open).
export function fetchModNotes(communityName: string, userId?: string): Promise<ModNote[]> {
  return api.get(`/r/${communityName}/mod/notes${userId ? `?userId=${userId}` : ''}`) as Promise<ModNote[]>;
}

export function addModNote(communityName: string, userId: string, note: string): Promise<ModNote> {
  return api.post(`/r/${communityName}/mod/notes`, { userId, note }) as Promise<ModNote>;
}

export function deleteModNote(communityName: string, noteId: string): Promise<unknown> {
  return api.del(`/r/${communityName}/mod/notes/${noteId}`);
}

// ---- Mod log (filterable)
export interface ModLogEntry {
  id: string;
  communityId: string;
  actorId: string;
  actorUsername: string | null;
  action: string;
  targetType: string;
  targetId: string;
  // Set when the target is a user (bans, invitations, approved submitters).
  targetUsername?: string | null;
  reason: string | null;
  createdAt: string;
}

export interface ModLogFilters {
  action?: string;
  actorId?: string;
  targetType?: string;
  before?: string;
}

export function fetchModLog(communityName: string, filters: ModLogFilters): Promise<ModLogEntry[]> {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([k, v]) => {
    if (v) params.set(k, v);
  });
  const qs = params.toString();
  return api.get(`/r/${communityName}/mod/actions${qs ? `?${qs}` : ''}`) as Promise<ModLogEntry[]>;
}

// ---- Sticky / distinguished comments
export function stickyComment(communityName: string, commentId: string, sticky: boolean): Promise<unknown> {
  return sticky ? api.post(`/r/${communityName}/mod/comments/${commentId}/sticky`) : api.del(`/r/${communityName}/mod/comments/${commentId}/sticky`);
}

export function distinguishComment(communityName: string, commentId: string, on: boolean): Promise<unknown> {
  return on ? api.post(`/r/${communityName}/mod/comments/${commentId}/distinguish`) : api.del(`/r/${communityName}/mod/comments/${commentId}/distinguish`);
}

export function fetchModerators(communityName: string): Promise<ModeratorEntry[]> {
  return api.get(`/r/${communityName}/mod/moderators`) as Promise<ModeratorEntry[]>;
}

// Adding an existing moderator again replaces their permissions, so this doubles as "edit permissions".
export function saveModerator(communityName: string, userId: string, permissions: number): Promise<unknown> {
  return api.post(`/r/${communityName}/mod/moderators`, { userId, permissions });
}

// Restricted communities: requests for posting access. Distinct from join requests (membership in a private community).
export function fetchPostingRequests(communityName: string): Promise<PostingRequestEntry[]> {
  return api.get(`/r/${communityName}/mod/posting-requests`) as Promise<PostingRequestEntry[]>;
}

export function approvePostingRequest(communityName: string, userId: string): Promise<unknown> {
  return api.post(`/r/${communityName}/mod/posting-requests/${userId}/approve`);
}

export function denyPostingRequest(communityName: string, userId: string): Promise<unknown> {
  return api.post(`/r/${communityName}/mod/posting-requests/${userId}/deny`);
}

// Restricted communities: people (besides moderators) who may post. All three need "Manage access".
export function fetchApprovedSubmitters(communityName: string): Promise<ApprovedSubmitterEntry[]> {
  return api.get(`/r/${communityName}/mod/approved-submitters`) as Promise<ApprovedSubmitterEntry[]>;
}

export function approveSubmitter(communityName: string, userId: string): Promise<unknown> {
  return api.post(`/r/${communityName}/mod/approved-submitters`, { userId });
}

export function removeApprovedSubmitter(communityName: string, userId: string): Promise<unknown> {
  return api.del(`/r/${communityName}/mod/approved-submitters/${userId}`);
}

export function fetchModeratorInvites(communityName: string): Promise<ModeratorInviteEntry[]> {
  return api.get(`/r/${communityName}/mod/moderator-invites`) as Promise<ModeratorInviteEntry[]>;
}

// Permissions go to the server as chosen, but the server checks them against the sender's own and stores them on the invitation.
export function sendModeratorInvite(communityName: string, username: string, permissions: number): Promise<ModeratorInviteEntry> {
  return api.post(`/r/${communityName}/mod/moderator-invites`, { username, permissions }) as Promise<ModeratorInviteEntry>;
}

export function cancelModeratorInvite(communityName: string, inviteId: string): Promise<unknown> {
  return api.del(`/r/${communityName}/mod/moderator-invites/${inviteId}`);
}

export function removeModerator(communityName: string, userId: string): Promise<unknown> {
  return api.del(`/r/${communityName}/mod/moderators/${userId}`);
}

// Flair definitions. Listing is public (the same endpoint the submit page's picker uses); the mutations need "Manage flairs".
export function fetchFlairs(communityName: string): Promise<Flair[]> {
  return api.get(`/r/${communityName}/flairs`) as Promise<Flair[]>;
}

export function createFlair(communityName: string, text: string, color: string, type: 'post' | 'user'): Promise<Flair> {
  return api.post(`/r/${communityName}/mod/flairs`, { text, color, type }) as Promise<Flair>;
}

// A flair's type never changes; only its text and colour can be edited.
export function updateFlair(communityName: string, flairId: string, text: string, color: string): Promise<Flair> {
  return api.patch(`/r/${communityName}/mod/flairs/${flairId}`, { text, color }) as Promise<Flair>;
}

export function deleteFlair(communityName: string, flairId: string): Promise<unknown> {
  return api.del(`/r/${communityName}/mod/flairs/${flairId}`);
}
