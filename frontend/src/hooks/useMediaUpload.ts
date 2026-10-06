import { useCallback, useRef, useState } from 'react';
import { completeUpload, describeUploadError, requestUploadUrl, uploadFileDirectly } from '../lib/mediaApi';
import { IMAGE_TYPES, MAX_IMAGE_BYTES, MAX_VIDEO_BYTES, VIDEO_TYPES } from '../lib/mediaValidation';

interface UseMediaUploadResult {
  uploading: boolean;
  error: string | null;
  mediaId: string | null;
  kind: 'image' | 'video' | null;
  previewUrl: string | null;
  selectFile: (file: File) => void;
  reset: () => void;
}

export function useMediaUpload(): UseMediaUploadResult {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mediaId, setMediaId] = useState<string | null>(null);
  const [kind, setKind] = useState<'image' | 'video' | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const previewUrlRef = useRef<string | null>(null);

  const reset = useCallback(() => {
    if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
    previewUrlRef.current = null;
    setPreviewUrl(null);
    setMediaId(null);
    setKind(null);
    setError(null);
    setUploading(false);
  }, []);

  const selectFile = useCallback((file: File) => {
    let detectedKind: 'image' | 'video';
    if (IMAGE_TYPES.has(file.type)) {
      detectedKind = 'image';
    } else if (VIDEO_TYPES.has(file.type)) {
      detectedKind = 'video';
    } else {
      setError('Unsupported file type. Use JPEG, PNG, WebP, GIF, MP4, QuickTime, or WebM.');
      return;
    }
    const maxBytes = detectedKind === 'image' ? MAX_IMAGE_BYTES : MAX_VIDEO_BYTES;
    if (file.size > maxBytes) {
      setError(`File is too large (max ${Math.round(maxBytes / 1024 / 1024)}MB).`);
      return;
    }

    if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
    const preview = URL.createObjectURL(file);
    previewUrlRef.current = preview;
    setPreviewUrl(preview);
    setKind(detectedKind);
    setMediaId(null);
    setError(null);
    setUploading(true);

    (async () => {
      try {
        const { mediaId: newMediaId, uploadUrl } = await requestUploadUrl(file.name, file.type, file.size);
        await uploadFileDirectly(uploadUrl, file, file.type);
        await completeUpload(newMediaId);
        setMediaId(newMediaId);
      } catch (err) {
        setError(describeUploadError(err));
      } finally {
        setUploading(false);
      }
    })();
  }, []);

  return { uploading, error, mediaId, kind, previewUrl, selectFile, reset };
}
