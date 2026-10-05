import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { decodeHtmlEntities } from '../lib/html';
import { fetchCommentHistory } from '../lib/commentApi';
import { distinguishComment, stickyComment } from '../lib/moderationApi';
import { timeAgo } from '../lib/time';
import type { CommentNode } from '../types/comment';
import { EditHistoryDialog } from './EditHistoryDialog';
import { OwnContentActions } from './OwnContentActions';
import { ReplyBox } from './ReplyBox';
import { VoteControl } from './VoteControl';
import styles from './CommentThread.module.css';

// Mirrors CommentService.MAX_DEPTH on the backend — replying past this would just 400.
const MAX_DEPTH = 10;

interface CommentThreadProps {
  comment: CommentNode;
  onVote: (commentId: string, dir: 1 | -1) => void;
  onReply: (parentId: string, body: string) => Promise<void>;
  onLoadMoreReplies: (parentId: string) => void;
  onEdit: (commentId: string, body: string) => Promise<void>;
  onDelete: (commentId: string) => Promise<void>;
  // True when the whole post is a tombstone — the server rejects replies to a deleted post, so no Reply
  // affordance is offered anywhere in its thread.
  readOnly?: boolean;
  // Moderators with remove-content permission may open a comment's edit history (the author always can).
  canModerate?: boolean;
  communityName?: string;
  // Viewer is a moderator of this community (may distinguish their OWN comments).
  isModerator?: boolean;
  // Viewer holds the manage-posts permission (may sticky top-level comments).
  canSticky?: boolean;
  // Called after a sticky/distinguish change so the parent can refetch the tree.
  onModChanged?: () => void;
}

export function CommentThread({ comment, onVote, onReply, onLoadMoreReplies, onEdit, onDelete, readOnly = false, canModerate = false, communityName, isModerator = false, canSticky = false, onModChanged }: CommentThreadProps) {
  const { user } = useAuth();
  const [replying, setReplying] = useState(false);
  const [editing, setEditing] = useState(false);
  const [showHistory, setShowHistory] = useState(false);

  const handleReply = async (body: string) => {
    await onReply(comment.id, body);
    setReplying(false);
  };

  const handleEdit = async (body: string) => {
    await onEdit(comment.id, body);
    setEditing(false);
  };

  const tombstoned = comment.deleted || comment.removed;

  return (
    <div>
      <div className={styles.comment}>
        <div className={styles.body}>
          <div className={styles.meta}>
            {comment.authorUsername ? (
              <Link className={styles.author} to={`/user/${comment.authorUsername}`}>
                u/{comment.authorUsername}
              </Link>
            ) : (
              <span className={styles.author}>u/[deleted]</span>
            )}{' '}
            {comment.distinguished === 'moderator' && <span className={styles.modBadge}>MOD</span>}
            {comment.sticky && <span className={styles.stickyBadge}>Stickied</span>}
            <span className={styles.time}>
              · {timeAgo(comment.createdAt)}
              {comment.editedAt && !comment.deleted && (
                <>
                  {' · '}
                  {user && (user.id === comment.authorId || canModerate) ? (
                    <button type="button" className={styles.historyLink} onClick={() => setShowHistory(true)}>
                      edited
                    </button>
                  ) : (
                    'edited'
                  )}
                </>
              )}
            </span>
          </div>
          {showHistory && <EditHistoryDialog load={() => fetchCommentHistory(comment.id)} onClose={() => setShowHistory(false)} />}
          {editing ? (
            <ReplyBox
              placeholder="Edit your comment"
              submitLabel="Save"
              initialBody={decodeHtmlEntities(comment.body)}
              onCancel={() => setEditing(false)}
              onSubmit={handleEdit}
            />
          ) : (
            <p className={tombstoned ? `${styles.text} ${styles.textRemoved}` : styles.text}>
              {decodeHtmlEntities(comment.body)}
            </p>
          )}
          <div className={styles.actionRow}>
            <VoteControl variant="compact" score={comment.score} myVote={comment.myVote} onVote={(dir) => onVote(comment.id, dir)} />
            {!readOnly && !comment.deleted && comment.depth < MAX_DEPTH && (
              <button type="button" className={styles.replyToggle} onClick={() => setReplying((r) => !r)}>
                Reply
              </button>
            )}
          {communityName && !comment.deleted && !comment.removed && (
            <>
              {canSticky && comment.parentId === null && (
                <button
                  type="button"
                  className={styles.replyToggle}
                  onClick={async () => {
                    await stickyComment(communityName, comment.id, !comment.sticky).catch(() => {});
                    onModChanged?.();
                  }}
                >
                  {comment.sticky ? 'Unsticky' : 'Sticky'}
                </button>
              )}
              {isModerator && user?.id === comment.authorId && (
                <button
                  type="button"
                  className={styles.replyToggle}
                  onClick={async () => {
                    await distinguishComment(communityName, comment.id, comment.distinguished !== 'moderator').catch(() => {});
                    onModChanged?.();
                  }}
                >
                  {comment.distinguished === 'moderator' ? 'Undistinguish' : 'Distinguish'}
                </button>
              )}
            </>
          )}
          </div>
          {user && !editing && (
            <OwnContentActions
              viewerId={user.id}
              authorId={comment.authorId}
              removed={comment.removed}
              deleted={comment.deleted}
              canEdit
              onEdit={() => setEditing(true)}
              onDelete={() => onDelete(comment.id)}
            />
          )}
          {replying && (
            <ReplyBox placeholder="What are your thoughts?" submitLabel="Reply" onCancel={() => setReplying(false)} onSubmit={handleReply} />
          )}
        </div>
      </div>
      {(comment.replies.length > 0 || comment.repliesAfter !== null) && (
        <div className={styles.replies}>
          {comment.replies.map((reply) => (
            <CommentThread
              key={reply.id}
              comment={reply}
              onVote={onVote}
              onReply={onReply}
              onLoadMoreReplies={onLoadMoreReplies}
              onEdit={onEdit}
              onDelete={onDelete}
              readOnly={readOnly}
              canModerate={canModerate}
              communityName={communityName}
              isModerator={isModerator}
              canSticky={canSticky}
              onModChanged={onModChanged}
            />
          ))}
          {comment.repliesAfter !== null && (
            <button type="button" className={styles.loadMoreReplies} onClick={() => onLoadMoreReplies(comment.id)}>
              {comment.childCount - comment.replies.length} more {comment.childCount - comment.replies.length === 1 ? 'reply' : 'replies'}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
