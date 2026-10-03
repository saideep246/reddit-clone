import { useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { fetchMyPostVotes, searchAllPosts } from '../lib/feedApi';
import type { Post } from '../types/post';
import { usePostVoteAction } from './usePostVoteAction';

interface UsePostSearchResult {
  posts: Post[];
  loading: boolean;
  error: string | null;
  applyVote: (postId: string, dir: 1 | -1) => void;
}

// Not paginated — GET /r/all/search returns a single capped page server-side, same reasoning as
// useCommunitySearch and the backend's own search queries. myVote is merged in the same way useFeed does
// (never sent by the search response itself, see Post.myVote's own comment).
export function usePostSearch(query: string, community?: string): UsePostSearchResult {
  const { user } = useAuth();
  const [posts, setPosts] = useState<Post[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestId = useRef(0);
  const { applyVote } = usePostVoteAction(setPosts);

  useEffect(() => {
    if (!query.trim()) {
      setPosts([]);
      setLoading(false);
      setError(null);
      return;
    }
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    (async () => {
      try {
        const results = await searchAllPosts(query.trim(), community);
        if (id !== requestId.current) return;
        if (!user || results.length === 0) {
          setPosts(results);
          return;
        }
        try {
          const votes = await fetchMyPostVotes(results.map((p) => p.id));
          if (id !== requestId.current) return;
          setPosts(results.map((p) => (votes[p.id] ? { ...p, myVote: votes[p.id] } : p)));
        } catch {
          // Vote-state is a nice-to-have overlay, not core to showing results — a failure here shouldn't
          // block the results from rendering (same reasoning as useFeed's own mergeMyVotes).
          if (id === requestId.current) setPosts(results);
        }
      } catch {
        if (id === requestId.current) setError('Could not search posts. Please try again.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [query, user, community]);

  return { posts, loading, error, applyVote };
}
