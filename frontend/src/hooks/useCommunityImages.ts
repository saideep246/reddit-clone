import { useLiveMedia } from './useLiveMedia';
import type { Community } from '../types/community';

// The community's icon and banner URLs, kept current while an image is still being processed. Uses the same media
// status mechanism as post media (useLiveMedia): until the file is 'ready' there is no URL — callers show their
// fallback (the letter avatar / gradient banner) — and when it becomes ready the real image appears without a
// reload. A 'failed' image simply stays on the fallback. Older responses without the media objects fall back to the
// plain iconUrl/bannerUrl fields.
export function useCommunityImages(community: Community): { iconUrl: string | null; bannerUrl: string | null } {
  const icon = useLiveMedia(community.iconMedia);
  const banner = useLiveMedia(community.bannerMedia);
  const iconUrl = icon ? (icon.processingStatus === 'ready' ? (icon.thumbnailUrl ?? icon.displayUrl) : null) : (community.iconUrl ?? null);
  const bannerUrl = banner ? (banner.processingStatus === 'ready' ? banner.displayUrl : null) : (community.bannerUrl ?? null);
  return { iconUrl, bannerUrl };
}
