import { api } from './apiClient';
import type { AutomodAction, AutomodRule, AutomodRuleConfig, AutomodRuleType, BanEntry, JoinRequestEntry, ModQueueItem, ReportEntry } from '../types/moderation';

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
  userId: string;
  authorId: string;
  authorUsername: string | null;
  note: string;
  createdAt: string;
}

export function fetchModNotes(communityName: string, userId: string): Promise<ModNote[]> {
  return api.get(`/r/${communityName}/mod/notes?userId=${userId}`) as Promise<ModNote[]>;
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
