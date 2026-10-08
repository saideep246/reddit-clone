import { api } from './apiClient';

// What the shared picker search returns, and nothing more: GET /api/users/search never sends profile fields.
export interface UserSearchHit {
  id: string;
  username: string;
}

export type UserSearchPurpose = 'moderator' | 'chat';

export const USER_SEARCH_MIN_LENGTH = 2;

export function searchUsersForPicker(query: string, purpose: UserSearchPurpose): Promise<UserSearchHit[]> {
  return api.get(`/api/users/search?q=${encodeURIComponent(query)}&purpose=${purpose}`) as Promise<UserSearchHit[]>;
}
