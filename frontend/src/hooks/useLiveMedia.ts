import { useEffect, useState } from 'react';
import { fetchMediaView } from '../lib/mediaApi';
import type { MediaView } from '../types/post';

const IN_FLIGHT = new Set(['pending', 'uploaded', 'processing']);
const FAST_POLLS = 20; // first ~30s at 1.5s: images are usually ready within a few seconds
const FAST_MS = 1500;
const SLOW_MS = 4000;
const GIVE_UP_AFTER_MS = 5 * 60 * 1000; // a long video transcode can take minutes; stop after 5

// A file that is still being processed refreshes itself: polls the media's own endpoint until it is 'ready' or
// 'failed', so the image/video appears without the user reloading the page. Files already ready cost no requests.
//
// Polling is deliberately simple and bounded: it stops the moment the status leaves the in-flight set, runs at most
// GIVE_UP_AFTER_MS, backs off from 1.5s to 4s, and pauses while the tab is hidden. Each poll is one primary-key lookup
// on the backend. A push-based status update (the server notifying the page when processing finishes) would remove the
// polling entirely and is a possible future scalability optimization — not needed at the current scale.
export function useLiveMedia(initial: MediaView | null | undefined): MediaView | null {
  const [media, setMedia] = useState<MediaView | null>(initial ?? null);

  // A different file (or a fresh server value) replaces whatever we had.
  useEffect(() => {
    setMedia(initial ?? null);
  }, [initial]);

  const id = media?.id;
  const inFlight = media ? IN_FLIGHT.has(media.processingStatus) : false;

  useEffect(() => {
    if (!id || !inFlight) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const startedAt = Date.now();
    let attempt = 0;

    const tick = async () => {
      if (cancelled || Date.now() - startedAt > GIVE_UP_AFTER_MS) return;
      if (document.hidden) {
        // Nobody is looking at this tab: skip the request and check again shortly.
        timer = setTimeout(tick, SLOW_MS);
        return;
      }
      try {
        const latest = await fetchMediaView(id);
        if (cancelled) return;
        setMedia(latest);
        if (!IN_FLIGHT.has(latest.processingStatus)) return;
      } catch {
        // A blip while polling is not worth surfacing; try again on the next tick.
      }
      attempt += 1;
      timer = setTimeout(tick, attempt < FAST_POLLS ? FAST_MS : SLOW_MS);
    };

    timer = setTimeout(tick, FAST_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [id, inFlight]);

  return media;
}
