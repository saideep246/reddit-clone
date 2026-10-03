import { useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../lib/apiClient';
import { fetchPoll, votePoll } from '../lib/postApi';
import type { PollInfo, Post } from '../types/post';
import styles from './PollBlock.module.css';

interface PollBlockProps {
  post: Post;
  // Detail page: the viewer's own choice is fetched and voting is enabled. Feed cards show read-only results
  // (counts ride along in the feed payload; the per-viewer choice is deliberately not fetched per card).
  interactive: boolean;
}

function endsLabel(poll: PollInfo): string {
  if (poll.ended) return 'Poll closed';
  const ms = new Date(poll.endsAt).getTime() - Date.now();
  const hours = Math.max(1, Math.round(ms / 3_600_000));
  return hours >= 48 ? `${Math.round(hours / 24)} days left` : `${hours} hour${hours === 1 ? '' : 's'} left`;
}

export function PollBlock({ post, interactive }: PollBlockProps) {
  const { user } = useAuth();
  const [poll, setPoll] = useState<PollInfo | null>(post.poll ?? null);
  const [voting, setVoting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const communityName = post.communityName ?? '';

  useEffect(() => {
    if (!interactive || !user || !communityName) return;
    let cancelled = false;
    fetchPoll(communityName, post.id)
      .then((p) => {
        if (!cancelled) setPoll(p);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [interactive, user, communityName, post.id]);

  if (!poll) return null;

  const canVote = interactive && !!user && !poll.ended && !poll.myOptionId;

  const vote = async (optionId: string) => {
    setVoting(true);
    setError(null);
    try {
      setPoll(await votePoll(communityName, post.id, optionId));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not record your vote.');
    } finally {
      setVoting(false);
    }
  };

  return (
    <div className={styles.poll} onClick={(e) => interactive && e.stopPropagation()}>
      {poll.options.map((o) => {
        const pct = poll.totalVotes === 0 ? 0 : Math.round((o.votes / poll.totalVotes) * 100);
        const mine = poll.myOptionId === o.id;
        return canVote ? (
          <button key={o.id} type="button" className={styles.voteButton} disabled={voting} onClick={() => vote(o.id)}>
            {o.text}
          </button>
        ) : (
          <div key={o.id} className={`${styles.result} ${mine ? styles.mine : ''}`}>
            <div className={styles.bar} style={{ width: `${pct}%` }} />
            <span className={styles.label}>
              {o.text}
              {mine && ' ✓'}
            </span>
            <span className={styles.pct}>
              {pct}% · {o.votes}
            </span>
          </div>
        );
      })}
      <div className={styles.meta}>
        {poll.totalVotes} vote{poll.totalVotes === 1 ? '' : 's'} · {endsLabel(poll)}
        {interactive && !user && !poll.ended && ' · log in to vote'}
      </div>
      {error && <div className={styles.error}>{error}</div>}
    </div>
  );
}
