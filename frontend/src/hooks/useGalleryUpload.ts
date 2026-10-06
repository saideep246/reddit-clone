import { useCallback, useRef, useState } from 'react';
import { completeUpload, describeUploadError, requestUploadUrl, uploadFileDirectly } from '../lib/mediaApi';
import { IMAGE_TYPES, MAX_IMAGE_BYTES } from '../lib/mediaValidation';

// Matches the backend's CreatePostRequest.isMediaIdsValidForKind cap — gallery posts are images only (no
// video), kept in one small, easy-to-find constant rather than duplicated inline.
const MAX_ITEMS = 20;

export interface GalleryItem {
  id: string; // client-side temp id (crypto.randomUUID()) — items have no server id until uploaded
  file: File;
  previewUrl: string;
  mediaId: string | null;
  uploading: boolean;
  error: string | null;
}

interface UseGalleryUploadResult {
  items: GalleryItem[];
  error: string | null;
  addFiles: (files: FileList | File[]) => void;
  removeItem: (id: string) => void;
  moveItem: (id: string, direction: 'up' | 'down') => void;
  reset: () => void;
}

export function useGalleryUpload(): UseGalleryUploadResult {
  const [items, setItems] = useState<GalleryItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const itemsRef = useRef<GalleryItem[]>([]);
  itemsRef.current = items;

  const uploadOne = useCallback((item: GalleryItem) => {
    (async () => {
      try {
        const { mediaId, uploadUrl } = await requestUploadUrl(item.file.name, item.file.type, item.file.size);
        await uploadFileDirectly(uploadUrl, item.file, item.file.type);
        await completeUpload(mediaId);
        setItems((prev) => prev.map((i) => (i.id === item.id ? { ...i, mediaId, uploading: false } : i)));
      } catch (err) {
        const message = describeUploadError(err);
        setItems((prev) => prev.map((i) => (i.id === item.id ? { ...i, uploading: false, error: message } : i)));
      }
    })();
  }, []);

  const addFiles = useCallback(
    (files: FileList | File[]) => {
      setError(null);
      const current = itemsRef.current;
      const room = MAX_ITEMS - current.length;
      if (room <= 0) {
        setError(`A gallery can have at most ${MAX_ITEMS} images.`);
        return;
      }
      const toAdd: GalleryItem[] = [];
      let rejected = false;
      let overflow = false;
      for (const file of Array.from(files)) {
        if (!IMAGE_TYPES.has(file.type)) {
          rejected = true;
          continue;
        }
        if (file.size > MAX_IMAGE_BYTES) {
          rejected = true;
          continue;
        }
        if (toAdd.length >= room) {
          overflow = true;
          break;
        }
        toAdd.push({
          id: crypto.randomUUID(),
          file,
          previewUrl: URL.createObjectURL(file),
          mediaId: null,
          uploading: true,
          error: null,
        });
      }
      if (overflow) {
        setError(`A gallery can have at most ${MAX_ITEMS} images — only the first ${room} were added.`);
      } else if (rejected) {
        setError('Some files were skipped (gallery images must be JPEG, PNG, or WebP).');
      }
      if (toAdd.length === 0) return;
      setItems((prev) => [...prev, ...toAdd]);
      // Independent uploads, fired in parallel — no reason to serialize them, each is its own
      // presigned-URL round trip against a different mediaId.
      toAdd.forEach(uploadOne);
    },
    [uploadOne],
  );

  const removeItem = useCallback((id: string) => {
    setItems((prev) => {
      const target = prev.find((i) => i.id === id);
      if (target) URL.revokeObjectURL(target.previewUrl);
      return prev.filter((i) => i.id !== id);
    });
  }, []);

  const moveItem = useCallback((id: string, direction: 'up' | 'down') => {
    setItems((prev) => {
      const index = prev.findIndex((i) => i.id === id);
      const swapWith = direction === 'up' ? index - 1 : index + 1;
      if (index === -1 || swapWith < 0 || swapWith >= prev.length) return prev;
      const next = [...prev];
      [next[index], next[swapWith]] = [next[swapWith], next[index]];
      return next;
    });
  }, []);

  const reset = useCallback(() => {
    itemsRef.current.forEach((i) => URL.revokeObjectURL(i.previewUrl));
    setItems([]);
    setError(null);
  }, []);

  return { items, error, addFiles, removeItem, moveItem, reset };
}
