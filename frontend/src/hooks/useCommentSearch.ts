import { useEffect, useRef, useState } from 'react';
import { searchComments, type CommentSearchResult } from '../lib/feedApi';

interface UseCommentSearchResult {
  comments: CommentSearchResult[];
  loading: boolean;
  error: string | null;
}

// Not paginated — one ranked, capped page, same as post/community search.
export function useCommentSearch(query: string, community?: string): UseCommentSearchResult {
  const [comments, setComments] = useState<CommentSearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestId = useRef(0);

  useEffect(() => {
    if (!query.trim()) {
      setComments([]);
      setLoading(false);
      setError(null);
      return;
    }
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    searchComments(query.trim(), community)
      .then((r) => {
        if (id === requestId.current) setComments(r);
      })
      .catch(() => {
        if (id === requestId.current) setError('Could not search comments. Please try again.');
      })
      .finally(() => {
        if (id === requestId.current) setLoading(false);
      });
  }, [query, community]);

  return { comments, loading, error };
}
