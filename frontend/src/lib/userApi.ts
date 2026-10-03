import { api } from './apiClient';
import type { Listing } from '../types/listing';
import type { Post } from '../types/post';
import type { UserComment } from '../types/comment';

export interface PublicProfile {
  // F8: lets the moderation dashboard resolve a username typed into a ban form to the id POST /mod/ban
  // actually needs, via this same already-public endpoint.
  id: string;
  username: string;
  karmaPost: number;
  karmaComment: number;
  createdAt: string;
  status: 'active' | 'banned' | 'deleted';
  followerCount: number;
  followingCount: number;
  // Always null straight off GET /user/{username}/about or GET /user/search — auth has no dependency on
  // follow (see backend ModuleBoundaryTest), so those endpoints can never attach this themselves. A
  // logged-in viewer's real value is merged in client-side from fetchFollowStatus/fetchFollowStatusBatch.
  // GET /user/{username}/followers and /following are the one exception: those are owned by the follow
  // module itself and already attach a real true/false there.
  isFollowing: boolean | null;
}

export function fetchPublicProfile(username: string): Promise<PublicProfile> {
  return api.get(`/user/${username}/about`) as Promise<PublicProfile>;
}

// Not paginated — GET /user/search returns a single capped page server-side, same "relevance ranking
// isn't a stable keyset sort key" reasoning as searchCommunities/the backend's own search queries.
export function searchUsers(query: string): Promise<PublicProfile[]> {
  return api.get(`/user/search?q=${encodeURIComponent(query)}`) as Promise<PublicProfile[]>;
}

export function fetchUserPosts(username: string, after?: string | null): Promise<Listing<Post>> {
  const query = after ? `?after=${encodeURIComponent(after)}` : '';
  return api.get(`/user/${username}/submitted${query}`) as Promise<Listing<Post>>;
}

export function fetchUserComments(username: string, after?: string | null): Promise<Listing<UserComment>> {
  const query = after ? `?after=${encodeURIComponent(after)}` : '';
  return api.get(`/user/${username}/comments${query}`) as Promise<Listing<UserComment>>;
}

// "changed" is false for the idempotent already-following/already-not-following case — callers use it to
// decide whether to actually apply a follower-count delta, rather than assuming their own pre-click
// isFollowing guess was right.
export function followUser(username: string): Promise<{ changed: boolean }> {
  return api.post(`/user/${username}/follow`) as Promise<{ changed: boolean }>;
}

export function unfollowUser(username: string): Promise<{ changed: boolean }> {
  return api.del(`/user/${username}/follow`) as Promise<{ changed: boolean }>;
}

// Authenticated-only on the backend — only ever call this for a logged-in viewer.
export function fetchFollowStatus(username: string): Promise<{ isFollowing: boolean }> {
  return api.get(`/user/${username}/follow`) as Promise<{ isFollowing: boolean }>;
}

// Batch counterpart of fetchFollowStatus for a page of search/list results — one round trip regardless of
// how many usernames are being checked. Authenticated-only on the backend, same as fetchFollowStatus.
export function fetchFollowStatusBatch(usernames: string[]): Promise<Record<string, boolean>> {
  return api.post('/user/follow-status', usernames) as Promise<Record<string, boolean>>;
}

export function fetchFollowers(username: string, after?: string | null): Promise<Listing<PublicProfile>> {
  const query = after ? `?after=${encodeURIComponent(after)}` : '';
  return api.get(`/user/${username}/followers${query}`) as Promise<Listing<PublicProfile>>;
}

export function fetchFollowing(username: string, after?: string | null): Promise<Listing<PublicProfile>> {
  const query = after ? `?after=${encodeURIComponent(after)}` : '';
  return api.get(`/user/${username}/following${query}`) as Promise<Listing<PublicProfile>>;
}

export interface BlockedUser {
  id: string;
  username: string;
  blockedAt: string;
}

export function blockUser(username: string): Promise<{ changed: boolean }> {
  return api.post(`/user/${username}/block`) as Promise<{ changed: boolean }>;
}

export function unblockUser(username: string): Promise<{ changed: boolean }> {
  return api.del(`/user/${username}/block`) as Promise<{ changed: boolean }>;
}

// Authenticated-only on the backend — only ever call this for a logged-in viewer.
export function fetchBlockStatus(username: string): Promise<{ isBlocked: boolean }> {
  return api.get(`/user/${username}/block`) as Promise<{ isBlocked: boolean }>;
}

export function fetchBlockedUsers(): Promise<BlockedUser[]> {
  return api.get('/api/blocked') as Promise<BlockedUser[]>;
}
