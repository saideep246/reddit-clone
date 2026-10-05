import { useEffect, useState } from 'react';
import { Navigate, useParams } from 'react-router-dom';
import { api } from '../lib/apiClient';

// Short share links (/p/<postId>) resolve to the canonical /r/<community>/comments/<postId> thread.
export function PostRedirect() {
  const { postId = '' } = useParams();
  const [community, setCommunity] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (api.get(`/api/p/${postId}`) as Promise<{ communityName: string }>)
      .then((r) => {
        if (!cancelled) setCommunity(r.communityName);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [postId]);

  if (community) return <Navigate to={`/r/${community}/comments/${postId}`} replace />;
  if (failed) return <div style={{ padding: 32, textAlign: 'center' }}>This post doesn't exist or you can't see it.</div>;
  return <div style={{ padding: 32, textAlign: 'center' }}>Loading…</div>;
}
