import { api, ApiError } from './apiClient';
import type { MediaView } from '../types/post';

interface UploadUrlResponse {
  mediaId: string;
  uploadUrl: string;
}

export function requestUploadUrl(filename: string, contentType: string, byteSize: number): Promise<UploadUrlResponse> {
  return api.post('/api/media/upload-url', { filename, contentType, byteSize }) as Promise<UploadUrlResponse>;
}

export function completeUpload(mediaId: string): Promise<unknown> {
  return api.post(`/api/media/${mediaId}/complete`);
}

// Goes straight to the presigned storage URL, not through this app's own API — no Authorization header
// (the presigned URL itself carries its authorization) and no apiClient wrapper (that attaches the
// Bearer token and targets VITE_API_BASE_URL, neither of which applies here).
export class StorageUploadError extends Error {
  // status is null when the request never got a response at all (network failure or a blocked CORS preflight).
  status: number | null;

  constructor(status: number | null, message: string) {
    super(message);
    this.status = status;
  }
}

export async function uploadFileDirectly(uploadUrl: string, file: File, contentType: string): Promise<void> {
  let res: Response;
  try {
    res = await fetch(uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': contentType },
      body: file,
    });
  } catch {
    // fetch only rejects (with an opaque TypeError) when no response was readable: offline, or the browser blocked
    // the request because the bucket's CORS rule doesn't allow this site — indistinguishable from here.
    throw new StorageUploadError(null, 'network');
  }
  if (!res.ok) {
    throw new StorageUploadError(res.status, `status ${res.status}`);
  }
}

// Turns any failure in the three-step upload (ask for a URL, PUT to storage, mark complete) into a message that
// says which step failed and what to do, instead of one generic "try again".
export function describeUploadError(err: unknown): string {
  if (err instanceof StorageUploadError) {
    if (err.status === null) {
      return "Couldn't reach file storage. Check your connection; if it keeps failing, the storage bucket may not allow uploads from this site (CORS).";
    }
    if (err.status === 403) {
      return 'Storage refused the upload (the upload link may have expired — try again).';
    }
    return `Storage rejected the upload (${err.message}).`;
  }
  if (err instanceof ApiError) {
    if (err.status === 401) return 'Please log in again to upload files.';
    return err.message;
  }
  return 'Could not upload this file. Please try again.';
}

export function fetchMediaView(mediaId: string): Promise<MediaView> {
  return api.get(`/api/media/${mediaId}`) as Promise<MediaView>;
}
