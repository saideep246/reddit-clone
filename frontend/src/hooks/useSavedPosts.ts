import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { useEngagement } from '../engagement/EngagementContext';
import { castVote, fetchMyPostVotes, removeVote } from '../lib/feedApi';
import { fetchSavedPosts } from '../lib/engagementApi';
import type { Post } from '../types/post';

interface UseSavedPostsResult {
  posts: Post[];
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  hasMore: boolean;
  loadMore: () => void;
  applyVote: (postId: string, dir: 1 | -1) => void;
}

// The viewer's own "Saved" page — same shape as useUserPosts, trimmed of the username param (always the
// current viewer) and seeding EngagementContext's saved-set from each loaded page via markSaved, since
// these posts are saved by definition and the page's own Unsave control needs isSaved to know that.
export function useSavedPosts(): UseSavedPostsResult {
  const { user } = useAuth();
  const { markSaved } = useEngagement();
  const [posts, setPosts] = useState<Post[]>([]);
  const [after, setAfter] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const requestId = useRef(0);

  const mergeMyVotes = useCallback(
    async (page: Post[]): Promise<Post[]> => {
      if (!user || page.length === 0) return page;
      try {
        const votes = await fetchMyPostVotes(page.map((p) => p.id));
        return page.map((p) => (votes[p.id] ? { ...p, myVote: votes[p.id] } : p));
      } catch {
        return page;
      }
    },
    [user],
  );

  useEffect(() => {
    if (!user) {
      setLoading(false);
      return;
    }
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    setPosts([]);
    setAfter(null);
    setHasMore(true);
    (async () => {
      try {
        const listing = await fetchSavedPosts(null);
        if (id !== requestId.current) return;
        const page = listing.data.children.map((c) => c.data);
        markSaved('post', page.map((p) => p.id));
        const merged = await mergeMyVotes(page);
        if (id !== requestId.current) return;
        setPosts(merged);
        setAfter(listing.data.after);
        setHasMore(listing.data.after !== null);
      } catch {
        if (id !== requestId.current) return;
        setError('Could not load your saved posts.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [user, mergeMyVotes, markSaved]);

  const loadMore = useCallback(() => {
    if (loadingMore || loading || !hasMore || after === null) return;
    const id = requestId.current;
    setLoadingMore(true);
    (async () => {
      try {
        const listing = await fetchSavedPosts(after);
        if (id !== requestId.current) return;
        const page = listing.data.children.map((c) => c.data);
        markSaved('post', page.map((p) => p.id));
        const merged = await mergeMyVotes(page);
        if (id !== requestId.current) return;
        setPosts((prev) => [...prev, ...merged]);
        setAfter(listing.data.after);
        setHasMore(listing.data.after !== null);
      } catch {
        if (id === requestId.current) setError('Could not load more posts.');
      } finally {
        if (id === requestId.current) setLoadingMore(false);
      }
    })();
  }, [after, hasMore, loading, loadingMore, mergeMyVotes, markSaved]);

  // Same optimistic toggle/swing logic as useFeed.applyVote.
  const applyVote = useCallback((postId: string, dir: 1 | -1) => {
    let previous: Post | undefined;
    setPosts((prev) =>
      prev.map((p) => {
        if (p.id !== postId) return p;
        previous = p;
        const wasVote = p.myVote;
        const removing = wasVote === dir;
        const delta = removing ? -dir : wasVote ? dir * 2 : dir;
        return { ...p, score: p.score + delta, myVote: removing ? undefined : dir };
      }),
    );
    const wasVote = previous?.myVote;
    const action = wasVote === dir ? removeVote('post', postId) : castVote('post', postId, dir);
    action.catch(() => {
      if (previous) {
        const snapshot = previous;
        setPosts((prev) => prev.map((p) => (p.id === postId ? snapshot : p)));
      }
    });
  }, []);

  return { posts, loading, loadingMore, error, hasMore, loadMore, applyVote };
}
