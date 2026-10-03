import { api } from './apiClient';
import type { Listing } from '../types/listing';
import type { Post, SortType, TopPeriod } from '../types/post';

// communityName is literally "all" for the home feed — the backend's r/all pseudo-community means this
// same function works unchanged for a real community's feed later (F4), no special-casing needed here.
export function fetchFeedPage(
  communityName: string,
  sort: SortType,
  after?: string | null,
  period?: TopPeriod,
): Promise<Listing<Post>> {
  const params = new URLSearchParams();
  if (after) params.set('after', after);
  if (sort === 'top' && period) params.set('t', period);
  const query = params.toString();
  return api.get(`/r/${communityName}/${sort}${query ? `?${query}` : ''}`) as Promise<Listing<Post>>;
}

// Returns a map of postId -> direction for whichever of the given ids the current user has voted on;
// an id absent from the result means no vote. Only call this when logged in — the endpoint requires auth.
export async function fetchMyPostVotes(postIds: string[]): Promise<Record<string, 1 | -1>> {
  if (postIds.length === 0) return {};
  const params = new URLSearchParams({ targetType: 'post', targetIds: postIds.join(',') });
  return (await api.get(`/api/vote/mine?${params.toString()}`)) as Record<string, 1 | -1>;
}

// Not paginated — GET /r/all/search returns a single capped page server-side, same "relevance ranking
// isn't a stable keyset sort key" reasoning as the per-community search this reuses the "all" pseudo-
// community convention from (see fetchFeedPage's own comment).
// `community` scopes the search to one community instead of sitewide ("all").
export async function searchAllPosts(query: string, community = 'all'): Promise<Post[]> {
  const listing = (await api.get(`/r/${community}/search?q=${encodeURIComponent(query)}`)) as Listing<Post>;
  return listing.data.children.map((c) => c.data);
}

export interface CommentSearchResult {
  id: string;
  postId: string;
  postTitle: string | null;
  communityName: string | null;
  authorUsername: string | null;
  body: string;
  score: number;
  createdAt: string;
}

export function searchComments(query: string, community = 'all'): Promise<CommentSearchResult[]> {
  return api.get(`/r/${community}/search/comments?q=${encodeURIComponent(query)}`) as Promise<CommentSearchResult[]>;
}

export function castVote(targetType: 'post' | 'comment', targetId: string, dir: 1 | -1): Promise<unknown> {
  return api.post('/api/vote', { targetType, targetId, dir });
}

export function removeVote(targetType: 'post' | 'comment', targetId: string): Promise<unknown> {
  return api.del(`/api/vote?targetType=${targetType}&targetId=${targetId}`);
}
