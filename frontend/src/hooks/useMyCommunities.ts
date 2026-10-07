import { useEffect, useRef, useState } from 'react';
import { fetchMyCommunities } from '../lib/communityApi';
import type { Community } from '../types/community';
import { useCommunityListActions } from './useCommunityListActions';

interface UseMyCommunitiesResult {
  communities: Community[];
  loading: boolean;
  error: string | null;
  join: (community: Community) => void;
  leave: (community: Community) => void;
  requestJoin: (community: Community) => void;
}

// Loaded once. Leaving a community only flips its isMember flag (via the shared list actions), so the row stays on the page
// with a Join button and the user can rejoin without a reload; it drops off the list the next time the page loads.
export function useMyCommunities(enabled: boolean): UseMyCommunitiesResult {
  const [communities, setCommunities] = useState<Community[]>([]);
  const [loading, setLoading] = useState(enabled);
  const [error, setError] = useState<string | null>(null);
  const requestId = useRef(0);
  const { join, leave, requestJoin } = useCommunityListActions(setCommunities);

  useEffect(() => {
    if (!enabled) return;
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    (async () => {
      try {
        const list = await fetchMyCommunities();
        if (id === requestId.current) setCommunities(list);
      } catch {
        if (id === requestId.current) setError('Could not load your communities. Please try again.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [enabled]);

  return { communities, loading, error, join, leave, requestJoin };
}
