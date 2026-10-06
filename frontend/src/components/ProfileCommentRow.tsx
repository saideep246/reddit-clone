import { Link } from 'react-router-dom';
import { useEngagement } from '../engagement/EngagementContext';
import { decodeHtmlEntities } from '../lib/html';
import { timeAgo } from '../lib/time';
import type { UserComment } from '../types/comment';
import { ItemMenu } from './ItemMenu';
import { VoteControl } from './VoteControl';
import styles from './ProfileCommentRow.module.css';

interface ProfileCommentRowProps {
  comment: UserComment;
  onVote: (commentId: string, dir: 1 | -1) => void;
}

// A flat row for a user's own "comments" profile tab (F7) — distinct from CommentThread, which renders a
// single post's nested reply tree and has no reason to link back to its own post.
export function ProfileCommentRow({ comment, onVote }: ProfileCommentRowProps) {
  const { isHidden } = useEngagement();
  if (isHidden('comment', comment.id)) return null;
  const postHref = `/r/${comment.communityName ?? 'all'}/comments/${comment.postId}`;

  return (
    <article className={styles.row}>
      <VoteControl score={comment.score} myVote={comment.myVote} onVote={(dir) => onVote(comment.id, dir)} />
      <div className={styles.body}>
        <div className={styles.meta}>
          commented on{' '}
          <Link className={styles.postLink} to={postHref}>
            {decodeHtmlEntities(comment.postTitle) ?? '[deleted post]'}
          </Link>{' '}
          in{' '}
          <Link className={styles.communityLink} to={`/r/${comment.communityName}`}>
            r/{comment.communityName ?? 'unknown'}
          </Link>{' '}
          · {timeAgo(comment.createdAt)}
        </div>
        <p className={styles.text}>{decodeHtmlEntities(comment.body)}</p>
        <ItemMenu targetType="comment" targetId={comment.id} authorId={null} live compact />
      </div>
    </article>
  );
}
