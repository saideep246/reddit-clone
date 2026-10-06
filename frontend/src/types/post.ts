export interface MediaView {
  id: string;
  thumbnailUrl: string | null;
  // thumbnailUrl (256px) is for feeds/cards, displayUrl (1280px) for the post detail page. Both are null until
  // processingStatus is 'ready' — the original upload is never exposed.
  displayUrl: string | null;
  width: number | null;
  height: number | null;
  durationSeconds: number | null;
  processingStatus: 'pending' | 'uploaded' | 'processing' | 'ready' | 'failed';
}

export interface Flair {
  id: string;
  communityId: string;
  text: string;
  color: string;
  type: 'user' | 'post';
  createdAt: string;
}

export type PostKind = 'text' | 'link' | 'image' | 'video' | 'gallery' | 'poll' | 'crosspost';

export interface PollOption {
  id: string;
  text: string;
  votes: number;
}

export interface PollInfo {
  options: PollOption[];
  totalVotes: number;
  endsAt: string;
  ended: boolean;
  // Only present on the dedicated GET .../poll response (it's viewer-specific, so feeds never carry it).
  myOptionId?: string | null;
}

// The original post a crosspost points at; `available` is false when it was removed/deleted or now lives in a
// private community, in which case every other field is null.
export interface CrosspostParent {
  id: string;
  available: boolean;
  title: string | null;
  kind: string | null;
  body: string | null;
  url: string | null;
  authorUsername: string | null;
  communityName: string | null;
}

export interface Post {
  id: string;
  communityId: string;
  communityName: string | null;
  authorId: string;
  authorUsername: string | null;
  kind: PostKind;
  title: string;
  body: string | null;
  url: string | null;
  mediaId: string | null;
  media: MediaView | null;
  // Only populated for kind="gallery" — null for every other kind, same as mediaId/media staying null for
  // a gallery post. Ordered: index 0 is the first image in the gallery.
  mediaItems: MediaView[] | null;
  flairId: string | null;
  flair: Flair | null;
  nsfw: boolean;
  spoiler: boolean;
  score: number;
  commentCount: number;
  ups: number;
  downs: number;
  hotRank: number;
  risingRank: number;
  controversialRank: number;
  pinned: boolean;
  locked: boolean;
  removed: boolean;
  // Author-initiated tombstone: the backend has already wiped title/body/media, so the UI only needs to
  // hide author actions and skip the media renderer for these.
  deleted: boolean;
  editedAt: string | null;
  poll?: PollInfo | null;
  crosspostOf?: string | null;
  crosspostParent?: CrosspostParent | null;
  // Card extras attached by the backend: the community's icon and how many live crossposts (reposts) exist.
  communityIconUrl?: string | null;
  crosspostCount?: number;
  createdAt: string;
  // Never sent by the backend on the feed response itself — merged in client-side from a separate
  // GET /api/vote/mine call (see useFeed), because the vote module can't attach it to Post without
  // creating a module-boundary cycle (vote already depends on post).
  myVote?: 1 | -1;
}

export type SortType = 'hot' | 'new' | 'top' | 'rising' | 'controversial';

export type TopPeriod = 'hour' | 'day' | 'week' | 'month' | 'year' | 'all';
