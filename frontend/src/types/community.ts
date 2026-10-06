import type { MediaView } from './post';

export type CommunityType = 'public' | 'restricted' | 'private';

export interface Community {
  id: string;
  name: string;
  type: CommunityType;
  description: string | null;
  creatorId: string;
  // Resolved from the community's icon/banner media on GET /r/{name}/about only.
  iconUrl?: string | null;
  bannerUrl?: string | null;
  // Full media status of the icon/banner, so the site can show a placeholder while one is still processing and update
  // when it is ready (see hooks/useLiveMedia). iconUrl/bannerUrl are null until then.
  iconMedia?: MediaView | null;
  bannerMedia?: MediaView | null;
  subscriberCount: number;
  createdAt: string;
  // Never sent to an anonymous viewer, and never attached at all outside GET /r/{name}/about (e.g. browse
  // or search) — see CommunityService.attachViewerContext.
  isMember?: boolean | null;
  isModerator?: boolean | null;
  joinRequestStatus?: 'pending' | 'approved' | 'denied' | null;
  // The viewer's own CommunityModerator.permissions bitmask (F8) — null for a non-moderator/anonymous
  // viewer (0 would be ambiguous with "a moderator granted zero bits"), real bits otherwise. See
  // lib/moderationApi.ts's PERM_* constants for what each bit means.
  myPermissions?: number | null;
}

export interface CommunityRule {
  title: string;
  description: string;
}
