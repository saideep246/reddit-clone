import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { Post } from '../types/post';
import { CommentIcon, RepostIcon } from './icons';
import { ItemMenu } from './ItemMenu';
import { ShareMenu } from './ShareMenu';
import { VoteControl } from './VoteControl';
import styles from './PostActionBar.module.css';

interface PostActionBarProps {
  post: Post;
  onVote: (dir: 1 | -1) => void;
  // Where the comments pill goes (the thread). Omitted on the thread page itself, where it just scrolls.
  commentsHref: string;
  // On the thread page the comments pill opens the composer instead of navigating; `composing` is its state.
  onCommentClick?: () => void;
  composing?: boolean;
  // Called after the post is hidden from this bar's overflow menu (the thread page navigates away).
  onHidden?: () => void;
}

function compact(n: number): string {
  if (n >= 10_000) return `${Math.round(n / 1000)}k`;
  if (n >= 1000) return `${(n / 1000).toFixed(1).replace(/\.0$/, '')}k`;
  return String(n);
}

// Reddit's pill row: [vote] [comments] [repost n] [share]. Repost opens the crosspost flow (pick a community,
// edit the title); Share offers the short link. Tombstoned posts keep only voting/commenting.
export function PostActionBar({ post, onVote, commentsHref, onCommentClick, composing = false, onHidden }: PostActionBarProps) {
  const { user } = useAuth();
  const navigate = useNavigate();
  const live = !post.deleted && !post.removed;

  const repost = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!user) {
      navigate('/login');
      return;
    }
    const origin = post.crosspostOf ?? post.id;
    const from = post.crosspostParent?.communityName ?? post.communityName;
    const title = post.crosspostParent?.title ?? post.title;
    navigate(`/submit?crosspost=${origin}&from=${from}&title=${encodeURIComponent(title)}`);
  };

  return (
    <div className={styles.bar} onClick={(e) => e.stopPropagation()}>
      <VoteControl score={post.score} myVote={post.myVote} onVote={onVote} />
      {onCommentClick ? (
        <button
          type="button"
          className={`${styles.pill} ${composing ? styles.pillActive : ''}`}
          aria-label={`${post.commentCount} comments — write a comment`}
          aria-expanded={composing}
          onClick={onCommentClick}
        >
          <CommentIcon />
          <span>{compact(post.commentCount)}</span>
        </button>
      ) : (
        <Link className={styles.pill} to={commentsHref} aria-label={`${post.commentCount} comments`}>
          <CommentIcon />
          <span>{compact(post.commentCount)}</span>
        </Link>
      )}
      {live && (
        <button type="button" className={styles.pill} onClick={repost} aria-label={`Repost, ${post.crosspostCount ?? 0} so far`}>
          <RepostIcon />
          <span>{compact(post.crosspostCount ?? 0)}</span>
        </button>
      )}
      {live && <ShareMenu post={post} />}
      <ItemMenu targetType="post" targetId={post.id} authorId={post.authorId} live={live} onHidden={onHidden} />
    </div>
  );
}
