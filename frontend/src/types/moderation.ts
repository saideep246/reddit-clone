// Mirrors community.CommunityModerator's bit values exactly (CommunityModerator.java) — the bitmask
// itself travels over the wire as Community.myPermissions (see types/community.ts).
export const PERM_REMOVE_CONTENT = 1;
export const PERM_BAN_USERS = 1 << 1;
export const PERM_MUTE_USERS = 1 << 2;
export const PERM_MANAGE_AUTOMOD = 1 << 3;
export const PERM_MANAGE_MODERATORS = 1 << 4;
export const PERM_MANAGE_FLAIRS = 1 << 5;
export const PERM_MANAGE_POSTS = 1 << 6;
export const PERM_MANAGE_RULES = 1 << 7;
export const PERM_MANAGE_ACCESS = 1 << 8;
export const PERM_MANAGE_SETTINGS = 1 << 9;

// true for the owner's Integer.MAX_VALUE (every bit set) on every single bit, same as the backend's own
// (permissions & requiredBit) == requiredBit check (CommunityService.hasModPermission).
export function hasPermission(myPermissions: number | null | undefined, bit: number): boolean {
  return ((myPermissions ?? 0) & bit) === bit;
}

// One entry of the community's moderator list (GET /r/{name}/mod/moderators). `owner` is the creator: never editable or removable.
export interface ModeratorEntry {
  userId: string;
  username: string | null;
  permissions: number;
  owner: boolean;
  addedAt: string;
}

// The individual permission bits a moderator can hold, with plain-language labels, in display order.
export const PERMISSION_OPTIONS: { bit: number; label: string; description: string }[] = [
  { bit: PERM_REMOVE_CONTENT, label: 'Remove posts and comments', description: 'Remove or delete other people\'s posts and comments, handle reports' },
  { bit: PERM_BAN_USERS, label: 'Ban users', description: 'Ban and unban people in this community' },
  { bit: PERM_MANAGE_POSTS, label: 'Pin and lock posts', description: 'Pin, lock and sticky content' },
  { bit: PERM_MANAGE_FLAIRS, label: 'Manage flairs', description: 'Create and delete flairs, set flairs on posts and users' },
  { bit: PERM_MANAGE_RULES, label: 'Edit rules', description: 'Change the community rules' },
  { bit: PERM_MANAGE_AUTOMOD, label: 'Manage automod', description: 'Add and remove automod rules' },
  { bit: PERM_MANAGE_ACCESS, label: 'Manage access', description: 'Approve join requests and approved submitters' },
  { bit: PERM_MUTE_USERS, label: 'Manage mod mail', description: 'Mute people from contacting the moderators' },
  { bit: PERM_MANAGE_SETTINGS, label: 'Edit settings', description: 'Change description, icon, banner' },
  { bit: PERM_MANAGE_MODERATORS, label: 'Manage moderators', description: 'Add and remove moderators (up to your own permissions)' },
];

export const ALL_PERMISSION_BITS = PERMISSION_OPTIONS.reduce((acc, o) => acc | o.bit, 0);

// Quick starting points for the permission picker; the checkboxes can still be adjusted afterwards.
export const PERMISSION_PRESETS: { label: string; bits: number }[] = [
  { label: 'Basic (remove + ban)', bits: PERM_REMOVE_CONTENT | PERM_BAN_USERS },
  { label: 'Content (remove, ban, pin, flairs)', bits: PERM_REMOVE_CONTENT | PERM_BAN_USERS | PERM_MANAGE_POSTS | PERM_MANAGE_FLAIRS },
  { label: 'Full permissions', bits: ALL_PERMISSION_BITS },
];

export interface ModQueueItem {
  communityId: string;
  targetType: 'post' | 'comment';
  targetId: string;
  reportCount: number;
  firstReportedAt: string;
  preview: string | null;
  authorUsername: string | null;
}

export interface ReportEntry {
  id: string;
  targetType: 'post' | 'comment';
  targetId: string;
  communityId: string;
  reporterId: string;
  reporterUsername: string | null;
  reason: string;
  status: 'open' | 'resolved' | 'dismissed';
  resolverId: string | null;
  createdAt: string;
}

export interface BanEntry {
  communityId: string;
  userId: string;
  username: string | null;
  issuerId: string;
  issuerUsername: string | null;
  reason: string | null;
  expiresAt: string | null;
  createdAt: string;
}

export interface JoinRequestEntry {
  communityId: string;
  userId: string;
  username: string | null;
  status: 'pending' | 'approved' | 'denied';
  requestedAt: string;
}

export type AutomodRuleType = 'keyword' | 'regex' | 'karma_threshold';
export type AutomodAction = 'remove' | 'report';

// config as a real discriminated object — what the backend actually stores/returns is a JSON *string*
// (AutomodRule.config is a plain String column, see its class comment), parsed into this shape by
// lib/moderationApi.ts's fetchAutomodRules, never left as a string for components to parse themselves.
export type AutomodRuleConfig =
  | { keywords: string[] }
  | { pattern: string }
  | { minKarma: number };

export interface AutomodRule {
  id: string;
  communityId: string;
  ruleType: AutomodRuleType;
  config: AutomodRuleConfig;
  action: AutomodAction;
  enabled: boolean;
  createdAt: string;
}
