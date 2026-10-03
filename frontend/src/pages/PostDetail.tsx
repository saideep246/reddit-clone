import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { CommentSortDropdown } from '../components/CommentSortDropdown';
import { CommentThread } from '../components/CommentThread';
import { OwnContentActions } from '../components/OwnContentActions';
import { PostMedia } from '../components/PostMedia';
import { ReplyBox } from '../components/ReplyBox';
import { VoteControl } from '../components/VoteControl';
import { usePostDetail } from '../hooks/usePostDetail';
import { decodeHtmlEntities } from '../lib/html';
import { timeAgo } from '../lib/time';
import type { CommentSortType } from '../types/comment';
import styles from './PostDetail.module.css';

export function PostDetail() {
  const { communityName = '', postId = '' } = useParams();
  const [searchParams] = useSearchParams();
  const sort = (searchParams.get('commentSort') as CommentSortType) || 'best';

  const { user } = useAuth();
  const [editingPost, setEditingPost] = useState(false);

  const {
    post,
    comments,
    loading,
    error,
    hasMoreComments,
    loadingMoreComments,
    loadMoreComments,
    applyPostVote,
    applyCommentVote,
    submitComment,
    loadMoreReplies,
    editPostBody,
    removePost,
    editCommentBody,
    removeComment,
  } = usePostDetail(communityName, postId, sort);

  if (loading) {
    return <div className={styles.state}>Loading…</div>;
  }

  if (error || !post) {
    return <div className={styles.state}>{error ?? 'Post not found.'}</div>;
  }

  return (
    <div>
      <article className={styles.header}>
        <VoteControl
          score={post.score}
          myVote={post.myVote}
          onVote={applyPostVote}
        />

        <div className={styles.body}>
          <div className={styles.meta}>
            Posted by{' '}
            {post.authorUsername ? (
              <Link
                className={styles.communityLink}
                to={`/user/${post.authorUsername}`}
              >
                u/{post.authorUsername}
              </Link>
            ) : (
              'u/[deleted]'
            )}{' '}
            in{' '}
            <Link
              className={styles.communityLink}
              to={`/r/${post.communityName}`}
            >
              r/{post.communityName ?? 'unknown'}
            </Link>{' '}
            · {timeAgo(post.createdAt)}
            {post.editedAt && !post.deleted && <> · edited</>}
          </div>

          <h1 className={styles.title}>
            {decodeHtmlEntities(post.title)}
            {post.nsfw && (
              <span className={`${styles.badge} ${styles.badgeNsfw}`}>
                NSFW
              </span>
            )}
            {post.spoiler && (
              <span className={`${styles.badge} ${styles.badgeSpoiler}`}>
                Spoiler
              </span>
            )}
          </h1>

          {!post.deleted &&
            (editingPost ? (
              <ReplyBox
                placeholder="Edit your post"
                submitLabel="Save"
                initialBody={decodeHtmlEntities(post.body ?? '')}
                onCancel={() => setEditingPost(false)}
                onSubmit={async (body) => {
                  await editPostBody(body);
                  setEditingPost(false);
                }}
              />
            ) : (
              <PostMedia post={post} fullBody />
            ))}

          <div className={styles.footer}>
            {post.commentCount} comments
          </div>

          {user && !editingPost && (
            <OwnContentActions
              viewerId={user.id}
              authorId={post.authorId}
              removed={post.removed}
              deleted={post.deleted}
              canEdit={post.kind === 'text'}
              onEdit={() => setEditingPost(true)}
              onDelete={removePost}
            />
          )}
        </div>
      </article>

      <div className={styles.commentsSection}>
        <CommentSortDropdown />

        {!post.deleted && (
          <ReplyBox onSubmit={(body) => submitComment(null, body)} />
        )}

        {comments.length === 0 ? (
          <p>No comments yet. Be the first to share what you think!</p>
        ) : (
          comments.map((c) => (
            <CommentThread
              key={c.id}
              comment={c}
              onVote={applyCommentVote}
              onReply={submitComment}
              onLoadMoreReplies={loadMoreReplies}
              onEdit={editCommentBody}
              onDelete={removeComment}
              readOnly={post.deleted}
            />
          ))
        )}

        {hasMoreComments && (
          <button
            type="button"
            className={styles.loadMoreComments}
            disabled={loadingMoreComments}
            onClick={loadMoreComments}
          >
            {loadingMoreComments ? 'Loading…' : 'Load more comments'}
          </button>
        )}
      </div>
    </div>
  );
}