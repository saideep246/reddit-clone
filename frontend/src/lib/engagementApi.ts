import type { Listing } from '../types/listing';
import type { Post } from '../types/post';
import type { TargetType } from '../types/engagement';
import { api } from './apiClient';

// Save/hide are idempotent on the backend and answer 200 with an empty body; both DELETEs carry a JSON body.
const body = (targetType: TargetType, targetId: string) => ({ targetType, targetId });

export function saveItem(targetType: TargetType, targetId: string): Promise<unknown> {
  return api.post('/api/save', body(targetType, targetId));
}

// The viewer's own "Saved" page — same Listing<Post> shape as fetchUserPosts, sorted by most-recently-saved.
export function fetchSavedPosts(after?: string | null): Promise<Listing<Post>> {
  const query = after ? `?after=${encodeURIComponent(after)}` : '';
  return api.get(`/api/save${query}`) as Promise<Listing<Post>>;
}

export function unsaveItem(targetType: TargetType, targetId: string): Promise<unknown> {
  return api.del('/api/save', body(targetType, targetId));
}

export function hideItem(targetType: TargetType, targetId: string): Promise<unknown> {
  return api.post('/api/hide', body(targetType, targetId));
}

export function unhideItem(targetType: TargetType, targetId: string): Promise<unknown> {
  return api.del('/api/hide', body(targetType, targetId));
}

export function reportItem(targetType: TargetType, targetId: string, reason: string): Promise<unknown> {
  return api.post('/api/report', { ...body(targetType, targetId), reason });
}
