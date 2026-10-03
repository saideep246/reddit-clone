import { api } from './apiClient';
import type { PollInfo, Post, PostKind } from '../types/post';

export interface CreatePostRequest {
  kind: PostKind;
  title: string;
  body?: string;
  url?: string;
  mediaId?: string;
  mediaIds?: string[];
  flairId?: string;
  nsfw?: boolean;
  spoiler?: boolean;
  pollOptions?: string[];
  pollDays?: number;
  crosspostOf?: string;
}

export function submitPost(communityName: string, request: CreatePostRequest, idempotencyKey: string): Promise<Post> {
  return api.post(`/r/${communityName}/submit`, request, { 'Idempotency-Key': idempotencyKey }) as Promise<Post>;
}

export function editPost(communityName: string, postId: string, body: string): Promise<Post> {
  return api.patch(`/r/${communityName}/posts/${postId}`, { body }) as Promise<Post>;
}

export function deletePost(communityName: string, postId: string): Promise<unknown> {
  return api.del(`/r/${communityName}/posts/${postId}`);
}

export interface PostEditFields {
  title?: string;
  url?: string;
}

export function editPostFields(communityName: string, postId: string, fields: PostEditFields): Promise<Post> {
  return api.patch(`/r/${communityName}/posts/${postId}`, fields) as Promise<Post>;
}

export interface PostRevision {
  id: string;
  editorId: string;
  title: string | null;
  body: string | null;
  url: string | null;
  editedAt: string;
}

export function fetchPostHistory(communityName: string, postId: string): Promise<PostRevision[]> {
  return api.get(`/r/${communityName}/posts/${postId}/history`) as Promise<PostRevision[]>;
}

export function fetchPoll(communityName: string, postId: string): Promise<PollInfo> {
  return api.get(`/r/${communityName}/posts/${postId}/poll`) as Promise<PollInfo>;
}

export function votePoll(communityName: string, postId: string, optionId: string): Promise<PollInfo> {
  return api.post(`/r/${communityName}/posts/${postId}/poll/vote`, { optionId }) as Promise<PollInfo>;
}

// ---- Drafts (server-side, private to the author). The payload is whatever the submit form held.
export interface DraftPayload {
  kind: PostKind;
  title: string;
  body?: string;
  url?: string;
  pollOptions?: string[];
  pollDays?: number;
  nsfw?: boolean;
  spoiler?: boolean;
}

export interface Draft {
  id: string;
  communityName: string | null;
  payload: DraftPayload;
  updatedAt: string;
}

export function fetchDrafts(): Promise<Draft[]> {
  return api.get('/api/drafts') as Promise<Draft[]>;
}

export function createDraft(communityName: string | null, payload: DraftPayload): Promise<Draft> {
  return api.post('/api/drafts', { communityName, payload }) as Promise<Draft>;
}

export function updateDraft(id: string, communityName: string | null, payload: DraftPayload): Promise<Draft> {
  return api.put(`/api/drafts/${id}`, { communityName, payload }) as Promise<Draft>;
}

export function deleteDraft(id: string): Promise<unknown> {
  return api.del(`/api/drafts/${id}`);
}

// ---- Scheduled posts
export interface ScheduledPost {
  id: string;
  communityName: string;
  title: string | null;
  kind: string | null;
  publishAt: string;
  status: 'pending' | 'published' | 'failed' | 'cancelled';
  error: string | null;
  postId: string | null;
}

export function schedulePost(communityName: string, post: CreatePostRequest, publishAt: string): Promise<ScheduledPost> {
  return api.post(`/r/${communityName}/schedule`, { post, publishAt }) as Promise<ScheduledPost>;
}

export function fetchScheduledPosts(): Promise<ScheduledPost[]> {
  return api.get('/api/scheduled') as Promise<ScheduledPost[]>;
}

export function cancelScheduledPost(id: string): Promise<unknown> {
  return api.del(`/api/scheduled/${id}`);
}
