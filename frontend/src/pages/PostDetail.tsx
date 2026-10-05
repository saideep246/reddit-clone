import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { CommentSortDropdown } from '../components/CommentSortDropdown';
import { CommentThread } from '../components/CommentThread';
import { EditHistoryDialog } from '../components/EditHistoryDialog';
import { OwnContentActions } from '../components/OwnContentActions';
import { PostMedia } from '../components/PostMedia';
import { ReplyBox } from '../components/ReplyBox';
import { PostActionBar } from '../components/PostActionBar';
import { usePostDetail } from '../hooks/usePostDetail';
import { ApiError } from '../lib/apiClient';
import { fetchCommunityAbout } from '../lib/communityApi';
import { decodeHtmlEntities } from '../lib/html';
import { fetchPostHistory } from '../lib/postApi';
import { timeAgo } from '../lib/time';
import type { CommentSortType } from '../types/comment';
import { hasPermission, PERM_MANAGE_POSTS, PERM_REMOVE_CONTENT } from '../types/moderation';
import styles from './PostDetail.module.css';

export function PostDetail() {
  const { communityName = '', postId = '' } = useParams();
  const [searchParams] = useSearchParams();
  // The top-level comment box stays hidden until the comment pill is clicked (or the thread is opened from a
  // feed card's comment pill, which adds ?comment=1).
  const [composing, setComposing] = useState(searchParams.get('comment') === '1');
  const sort = (searchParams.get('commentSort') as CommentSortType) || 'best';

  const { user } = useAuth();
  const [editingPost, setEditingPost] = useState(false);
  const [canModerate, setCanModerate] = useState(false);
  const [myPerms, setMyPerms] = useState<number | null>(null);
  const [modDeleting, setModDeleting] = useState(false);
  const [editingMeta, setEditingMeta] = useState(false);
  const [metaTitle, setMetaTitle] = useState('');
  const [metaUrl, setMetaUrl] = useState('');
  const [metaError, setMetaError] = useState<string | null>(null);
  const [metaSaving, setMetaSaving] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [modDeleteError, setModDeleteError] = useState<string | null>(null);

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
    reloadComments,
    editPostBody,
    editPostMeta,
    removePost,
    editCommentBody,
    removeComment,
  } = usePostDetail(communityName, postId, sort);

  // Moderators/owners holding the remove-content bit can delete anyone's post (the author uses OwnContentActions).
  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    fetchCommunityAbout(communityName)
      .then((c) => {
        if (cancelled) return;
        setCanModerate(hasPermission(c.myPermissions, PERM_REMOVE_CONTENT));
        setMyPerms(c.isModerator ? (c.myPermissions ?? 0) : null);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [user, communityName]);

  const handleModDelete = async () => {
    if (!window.confirm('Delete this post? This cannot be undone.')) return;
    setModDeleting(true);
    setModDeleteError(null);
    try {
      await removePost();
    } catch (e) {
      setModDeleteError(e instanceof Error ? e.message : 'Could not delete the post.');
    } finally {
      setModDeleting(false);
    }
  };

  // Title/link edits are only accepted by the server within a grace window after posting; this client-side
  // check just avoids offering a button that is guaranteed to be rejected (the server stays authoritative).
  const TITLE_EDIT_WINDOW_MS = 10 * 60 * 1000;

  const startMetaEdit = () => {
    if (!post) return;
    setMetaTitle(post.title);
    setMetaUrl(post.url ?? '');
    setMetaError(null);
    setEditingMeta(true);
  };

  const saveMeta = async () => {
    if (!post) return;
    setMetaSaving(true);
    setMetaError(null);
    try {
      const fields: { title?: string; url?: string } = {};
      if (metaTitle.trim() && metaTitle.trim() !== post.title) fields.title = metaTitle.trim();
      if (post.kind === 'link' && metaUrl.trim() && metaUrl.trim() !== post.url) fields.url = metaUrl.trim();
      if (Object.keys(fields).length > 0) await editPostMeta(fields);
      setEditingMeta(false);
    } catch (e) {
      setMetaError(e instanceof ApiError ? e.message : 'Could not save your changes.');
    } finally {
      setMetaSaving(false);
    }
  };

  if (loading) {
    return <div className={styles.state}>Loading…</div>;
  }

  if (error || !post) {
    return <div className={styles.state}>{error ?? 'Post not found.'}</div>;
  }

  return (
    <div className={styles.page}>
      <article className={styles.header}>
        <div className={styles.body}>
          <div className={styles.meta}>
            <Link to={`/r/${post.communityName}`} className={styles.avatarLink}>
              {post.communityIconUrl ? (
                <img className={styles.avatar} src={post.communityIconUrl} alt="" />
              ) : (
                <span className={styles.avatarFallback}>{(post.communityName ?? '?').slice(0, 1).toUpperCase()}</span>
              )}
            </Link>
            <div>
              <div>
                <Link className={styles.communityLink} to={`/r/${post.communityName}`}>
                  r/{post.communityName ?? 'unknown'}
                </Link>
                {' • '}
                {timeAgo(post.createdAt)}
                {post.editedAt && !post.deleted && (
                  <>
                    {' • '}
                    {user && (user.id === post.authorId || canModerate) ? (
                      <button type="button" className={styles.linkButton} onClick={() => setShowHistory(true)}>
                        edited
                      </button>
                    ) : (
                      'edited'
                    )}
                  </>
                )}
              </div>
              <div className={styles.byline}>
                {post.authorUsername ? (
                  <Link className={styles.authorLink} to={`/user/${post.authorUsername}`}>
                    u/{post.authorUsername}
                  </Link>
                ) : (
                  'u/[deleted]'
                )}
              </div>
            </div>
          </div>

          {editingMeta ? (
            <div className={styles.metaEdit}>
              <input className={styles.metaInput} value={metaTitle} maxLength={300} onChange={(e) => setMetaTitle(e.target.value)} aria-label="Title" />
              {post.kind === 'link' && (
                <input className={styles.metaInput} value={metaUrl} onChange={(e) => setMetaUrl(e.target.value)} aria-label="Link URL" />
              )}
              <div className={styles.metaActions}>
                <button type="button" className={styles.deleteButton} disabled={metaSaving} onClick={saveMeta}>
                  {metaSaving ? 'Saving…' : 'Save'}
                </button>
                <button type="button" className={styles.deleteButton} disabled={metaSaving} onClick={() => setEditingMeta(false)}>
                  Cancel
                </button>
              </div>
              {metaError && <div className={styles.deleteError}>{metaError}</div>}
            </div>
          ) : (
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
          )}

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

          <PostActionBar
            post={post}
            onVote={applyPostVote}
            commentsHref={`/r/${communityName}/comments/${postId}`}
            onCommentClick={() => setComposing((c) => !c)}
            composing={composing}
          />

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

          {user &&
            user.id === post.authorId &&
            !editingMeta &&
            !editingPost &&
            !post.deleted &&
            !post.removed &&
            Date.now() - new Date(post.createdAt).getTime() < TITLE_EDIT_WINDOW_MS && (
              <button type="button" className={styles.deleteButton} onClick={startMetaEdit}>
                Edit {post.kind === 'link' ? 'title/link' : 'title'}
              </button>
            )}

          {user && canModerate && user.id !== post.authorId && !post.deleted && !post.removed && !editingPost && (
            <button type="button" className={styles.deleteButton} disabled={modDeleting} onClick={handleModDelete}>
              {modDeleting ? 'Deleting…' : 'Delete'}
            </button>
          )}
          {modDeleteError && <div className={styles.deleteError}>{modDeleteError}</div>}
        </div>
      </article>

      {showHistory && <EditHistoryDialog load={() => fetchPostHistory(communityName, postId)} onClose={() => setShowHistory(false)} />}

      <div className={styles.commentsSection}>
        <CommentSortDropdown />

        {!post.deleted && composing && (
          <ReplyBox
            autoFocus
            onCancel={() => setComposing(false)}
            onSubmit={async (body) => {
              await submitComment(null, body);
              setComposing(false);
            }}
          />
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
              canModerate={canModerate}
              communityName={communityName}
              isModerator={myPerms !== null}
              canSticky={myPerms !== null && hasPermission(myPerms, PERM_MANAGE_POSTS)}
              onModChanged={reloadComments}
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