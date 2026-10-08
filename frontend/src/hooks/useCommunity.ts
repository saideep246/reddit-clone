import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../lib/apiClient';
import { fetchCommunityAbout, fetchCommunityRules, fetchPinnedPosts, joinCommunity, leaveCommunity, requestToJoin } from '../lib/communityApi';
import { castVote, fetchMyPostVotes, removeVote } from '../lib/feedApi';
import type { Community, CommunityRule } from '../types/community';
import type { Post } from '../types/post';

interface UseCommunityResult {
  community: Community | null;
  rules: CommunityRule[];
  pinned: Post[];
  loading: boolean;
  error: string | null;
  actionError: string | null;
  join: () => Promise<void>;
  leave: () => Promise<void>;
  requestJoin: () => Promise<void>;
  // Re-reads the community in place (no loading state), e.g. after a posting request changes or to notice an approval.
  refreshCommunity: () => Promise<void>;
  applyPinnedVote: (postId: string, dir: 1 | -1) => void;
}

export function useCommunity(name: string): UseCommunityResult {
  const { user } = useAuth();
  const [community, setCommunity] = useState<Community | null>(null);
  const [rules, setRules] = useState<CommunityRule[]>([]);
  const [pinned, setPinned] = useState<Post[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const requestId = useRef(0);

  useEffect(() => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    (async () => {
      try {
        const [about, rulesList, pinnedList] = await Promise.all([
          fetchCommunityAbout(name),
          fetchCommunityRules(name),
          fetchPinnedPosts(name),
        ]);
        if (id !== requestId.current) return;
        let pinnedWithVotes = pinnedList;
        if (user && pinnedList.length > 0) {
          try {
            const votes = await fetchMyPostVotes(pinnedList.map((p) => p.id));
            if (id !== requestId.current) return;
            pinnedWithVotes = pinnedList.map((p) => (votes[p.id] ? { ...p, myVote: votes[p.id] } : p));
          } catch {
            // Vote-state overlay is best-effort — the pinned list still renders without it.
          }
        }
        setCommunity(about);
        setRules(rulesList);
        setPinned(pinnedWithVotes);
      } catch (err) {
        if (id !== requestId.current) return;
        setError(err instanceof ApiError && err.status === 404 ? "This community doesn't exist." : 'Could not load this community.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [name, user]);

  const join = useCallback(async () => {
    if (!community) return;
    setActionError(null);
    const snapshot = community;
    setCommunity({ ...community, isMember: true, subscriberCount: community.subscriberCount + 1 });
    try {
      await joinCommunity(name);
    } catch (err) {
      setCommunity(snapshot);
      setActionError(err instanceof ApiError ? err.message : 'Could not join this community.');
    }
  }, [community, name]);

  const leave = useCallback(async () => {
    if (!community) return;
    setActionError(null);
    const snapshot = community;
    setCommunity({ ...community, isMember: false, subscriberCount: community.subscriberCount - 1 });
    try {
      await leaveCommunity(name);
    } catch (err) {
      setCommunity(snapshot);
      setActionError(err instanceof ApiError ? err.message : 'Could not leave this community.');
    }
  }, [community, name]);

  const requestJoin = useCallback(async () => {
    if (!community) return;
    setActionError(null);
    const snapshot = community;
    setCommunity({ ...community, joinRequestStatus: 'pending' });
    try {
      await requestToJoin(name);
    } catch (err) {
      setCommunity(snapshot);
      setActionError(err instanceof ApiError ? err.message : 'Could not request to join this community.');
    }
  }, [community, name]);

  const refreshCommunity = useCallback(async () => {
    try {
      setCommunity(await fetchCommunityAbout(name));
    } catch {
      // Keep what is shown; the next refresh tries again.
    }
  }, [name]);

  // Pinned posts come from their own fetch (GET /r/{name}/pinned), not useFeed's `posts` list — a pinned
  // post may or may not also appear in the regular sorted feed below, so its vote state has to be tracked
  // here, not borrowed from useFeed.applyVote (which only knows about its own list). Same optimistic
  // toggle/swing logic as useFeed.applyVote, just scoped to this separate `pinned` array.
  const applyPinnedVote = useCallback((postId: string, dir: 1 | -1) => {
    let previous: Post | undefined;
    setPinned((prev) =>
      prev.map((p) => {
        if (p.id !== postId) return p;
        previous = p;
        const removing = p.myVote === dir;
        const delta = removing ? -dir : p.myVote ? dir * 2 : dir;
        return { ...p, score: p.score + delta, myVote: removing ? undefined : dir };
      }),
    );
    const action = previous?.myVote === dir ? removeVote('post', postId) : castVote('post', postId, dir);
    action.catch(() => {
      if (previous) {
        const snapshot = previous;
        setPinned((prev) => prev.map((p) => (p.id === postId ? snapshot : p)));
      }
    });
  }, []);

  return { community, rules, pinned, loading, error, actionError, join, leave, requestJoin, refreshCommunity, applyPinnedVote };
}
