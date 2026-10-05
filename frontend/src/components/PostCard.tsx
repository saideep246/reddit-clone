import { Link, useNavigate } from 'react-router-dom';
import { decodeHtmlEntities } from '../lib/html';
import { timeAgo } from '../lib/time';
import type { Post } from '../types/post';
import { PostActionBar } from './PostActionBar';
import { PostMedia } from './PostMedia';
import styles from './PostCard.module.css';

interface PostCardProps {
  post: Post;
  onVote: (postId: string, dir: 1 | -1) => void;
}

// Reddit's feed card: flat, separated by a hairline, community avatar + "r/name · time" on top, the whole card
// clickable through to the thread, and the vote/comment/repost/share pills along the bottom.
export function PostCard({ post, onVote }: PostCardProps) {
  const navigate = useNavigate();
  const detailHref = `/r/${post.communityName ?? 'all'}/comments/${post.id}`;
  const community = post.communityName ?? 'unknown';

  // Clicking anywhere on the card opens the thread, except on real controls (links, buttons, media players).
  const open = (e: React.MouseEvent) => {
    if ((e.target as HTMLElement).closest('a, button, input, select, textarea, video, summary')) return;
    if (window.getSelection()?.toString()) return;
    navigate(detailHref);
  };

  return (
    <article className={styles.card} onClick={open}>
      <div className={styles.header}>
        <Link to={`/r/${community}`} className={styles.avatarLink} aria-label={`r/${community}`}>
          {post.communityIconUrl ? (
            <img className={styles.avatar} src={post.communityIconUrl} alt="" />
          ) : (
            <span className={styles.avatarFallback}>{community.slice(0, 1).toUpperCase()}</span>
          )}
        </Link>
        <Link className={styles.communityLink} to={`/r/${community}`}>
          r/{community}
        </Link>
        <span className={styles.dot}>•</span>
        <span className={styles.time}>{timeAgo(post.createdAt)}</span>
        {post.pinned && <span className={`${styles.badge} ${styles.badgePinned}`}>Pinned</span>}
      </div>
      <h2 className={styles.title}>
        <Link className={styles.titleLink} to={detailHref}>
          {decodeHtmlEntities(post.title)}
        </Link>
        {post.nsfw && <span className={`${styles.badge} ${styles.badgeNsfw}`}>NSFW</span>}
        {post.spoiler && <span className={`${styles.badge} ${styles.badgeSpoiler}`}>Spoiler</span>}
        {post.flair && (
          <span className={styles.flairChip} style={{ backgroundColor: post.flair.color, color: '#fff' }}>
            {post.flair.text}
          </span>
        )}
      </h2>
      <PostMedia post={post} />
      <PostActionBar post={post} onVote={(dir) => onVote(post.id, dir)} commentsHref={`${detailHref}?comment=1`} />
    </article>
  );
}
