import { api } from './apiClient';
import type { Listing } from '../types/listing';
import type { Community, CommunityRule, CommunityType } from '../types/community';
import type { Post } from '../types/post';

export type CommunityBrowseSort = 'popular' | 'new';

export function createCommunity(name: string, description: string, type: CommunityType): Promise<Community> {
  return api.post('/r', { name, description: description || undefined, type }) as Promise<Community>;
}

export function fetchCommunityAbout(name: string): Promise<Community> {
  return api.get(`/r/${name}/about`) as Promise<Community>;
}

export function fetchCommunityRules(name: string): Promise<CommunityRule[]> {
  return api.get(`/r/${name}/rules`) as Promise<CommunityRule[]>;
}

export function fetchPinnedPosts(name: string): Promise<Post[]> {
  return api.get(`/r/${name}/pinned`) as Promise<Post[]>;
}

export function joinCommunity(name: string): Promise<unknown> {
  return api.post(`/r/${name}/subscribe`);
}

export function leaveCommunity(name: string): Promise<unknown> {
  return api.del(`/r/${name}/subscribe`);
}

export function requestToJoin(name: string): Promise<unknown> {
  return api.post(`/r/${name}/join-requests`);
}

export function browseCommunities(sort: CommunityBrowseSort, after?: string | null): Promise<Listing<Community>> {
  const params = new URLSearchParams({ sort });
  if (after) params.set('after', after);
  return api.get(`/r?${params.toString()}`) as Promise<Listing<Community>>;
}

export function searchCommunities(query: string): Promise<Community[]> {
  return api.get(`/r/search?q=${encodeURIComponent(query)}`) as Promise<Community[]>;
}

export interface CommunitySettingsUpdate {
  description?: string;
  iconMediaId?: string;
  bannerMediaId?: string;
  clearIcon?: boolean;
  clearBanner?: boolean;
}

export function updateCommunitySettings(name: string, update: CommunitySettingsUpdate): Promise<unknown> {
  return api.patch(`/r/${name}/mod/settings`, update);
}
