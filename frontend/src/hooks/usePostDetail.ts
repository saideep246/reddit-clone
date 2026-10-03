import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import {
  deleteComment,
  editComment,
  fetchMoreChildren,
  fetchMyCommentVotes,
  fetchPostWithComments,
  postComment,
} from '../lib/commentApi';
import { castVote, removeVote } from '../lib/feedApi';
import { deletePost, editPost, editPostFields, type PostEditFields } from '../lib/postApi';
import type { CommentNode, CommentSortType } from '../types/comment';
import type { Post } from '../types/post';

function flattenIds(nodes: CommentNode[]): string[] {
  return nodes.flatMap((n) => [n.id, ...flattenIds(n.replies)]);
}

function mergeVotes(nodes: CommentNode[], votes: Record<string, 1 | -1>): CommentNode[] {
  return nodes.map((n) => ({ ...n, myVote: votes[n.id], replies: mergeVotes(n.replies, votes) }));
}

function updateNode(nodes: CommentNode[], id: string, updater: (n: CommentNode) => CommentNode): CommentNode[] {
  return nodes.map((n) => {
    if (n.id === id) return updater(n);
    if (n.replies.length === 0) return n;
    return { ...n, replies: updateNode(n.replies, id, updater) };
  });
}

function findNode(nodes: CommentNode[], id: string): CommentNode | undefined {
  for (const n of nodes) {
    if (n.id === id) return n;
    const found = findNode(n.replies, id);
    if (found) return found;
  }
  return undefined;
}

interface UsePostDetailResult {
  post: Post | null;
  comments: CommentNode[];
  loading: boolean;
  error: string | null;
  hasMoreComments: boolean;
  loadingMoreComments: boolean;
  loadMoreComments: () => void;
  applyPostVote: (dir: 1 | -1) => void;
  applyCommentVote: (commentId: string, dir: 1 | -1) => void;
  submitComment: (parentId: string | null, body: string) => Promise<void>;
  loadMoreReplies: (parentId: string) => void;
  reloadComments: () => Promise<void>;
  editPostBody: (body: string) => Promise<void>;
  editPostMeta: (fields: PostEditFields) => Promise<void>;
  removePost: () => Promise<void>;
  editCommentBody: (commentId: string, body: string) => Promise<void>;
  removeComment: (commentId: string) => Promise<void>;
}

export function usePostDetail(communityName: string, postId: string, sort: CommentSortType): UsePostDetailResult {
  const { user } = useAuth();
  const [post, setPost] = useState<Post | null>(null);
  const [comments, setComments] = useState<CommentNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingMoreComments, setLoadingMoreComments] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [after, setAfter] = useState<string | null>(null);
  const requestId = useRef(0);

  const mergeMyVotes = useCallback(
    async (tree: CommentNode[]): Promise<CommentNode[]> => {
      if (!user || tree.length === 0) return tree;
      try {
        const votes = await fetchMyCommentVotes(flattenIds(tree));
        return mergeVotes(tree, votes);
      } catch {
        // Vote-state overlay is best-effort — the thread still renders without it.
        return tree;
      }
    },
    [user],
  );

  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const data = await fetchPostWithComments(communityName, postId, sort);
      if (id !== requestId.current) return;
      const tree = await mergeMyVotes(data.comments.data.children.map((c) => c.data));
      if (id !== requestId.current) return;
      setPost(data.post);
      setComments(tree);
      setAfter(data.comments.data.after);
    } catch {
      if (id === requestId.current) setError('Could not load this post. It may have been removed.');
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, [communityName, postId, sort, mergeMyVotes]);

  useEffect(() => {
    load();
  }, [load]);

  // Refetches the comment tree in place (no full-page loading state) — used after a moderator action such as
  // stickying a comment, which changes the order the server returns it in.
  const reloadComments = useCallback(async () => {
    const id = ++requestId.current;
    try {
      const data = await fetchPostWithComments(communityName, postId, sort);
      if (id !== requestId.current) return;
      const tree = await mergeMyVotes(data.comments.data.children.map((c) => c.data));
      if (id !== requestId.current) return;
      setComments(tree);
      setAfter(data.comments.data.after);
    } catch {
      // Best effort: the previous tree stays on screen.
    }
  }, [communityName, postId, sort, mergeMyVotes]);

  // Real top-level comment pagination (feature 8) — appends the next page of root comments, same
  // "requestId guard + append, not replace" shape as useFeed.loadMore.
  const loadMoreComments = useCallback(() => {
    if (loadingMoreComments || loading || after === null) return;
    const id = requestId.current;
    setLoadingMoreComments(true);
    (async () => {
      try {
        const data = await fetchPostWithComments(communityName, postId, sort, after);
        if (id !== requestId.current) return;
        const page = await mergeMyVotes(data.comments.data.children.map((c) => c.data));
        if (id !== requestId.current) return;
        setComments((prev) => [...prev, ...page]);
        setAfter(data.comments.data.after);
      } catch {
        if (id === requestId.current) setError('Could not load more comments.');
      } finally {
        if (id === requestId.current) setLoadingMoreComments(false);
      }
    })();
  }, [communityName, postId, sort, after, loading, loadingMoreComments, mergeMyVotes]);

  // Same optimistic toggle/swing logic as useFeed.applyVote, operating on this single post instead of a
  // list entry.
  const applyPostVote = useCallback(
    (dir: 1 | -1) => {
      let previous: Post | undefined;
      setPost((prev) => {
        if (!prev) return prev;
        previous = prev;
        const removing = prev.myVote === dir;
        const delta = removing ? -dir : prev.myVote ? dir * 2 : dir;
        return { ...prev, score: prev.score + delta, myVote: removing ? undefined : dir };
      });
      const action = previous?.myVote === dir ? removeVote('post', postId) : castVote('post', postId, dir);
      action.catch(() => {
        if (previous) {
          const snapshot = previous;
          setPost(snapshot);
        }
      });
    },
    [postId],
  );

  // Optimistic, same toggle/swing logic as useFeed.applyVote, just updating a node anywhere in the
  // nested tree instead of a flat list.
  const applyCommentVote = useCallback((commentId: string, dir: 1 | -1) => {
    let previous: CommentNode | undefined;
    setComments((prev) =>
      updateNode(prev, commentId, (n) => {
        previous = n;
        const removing = n.myVote === dir;
        const delta = removing ? -dir : n.myVote ? dir * 2 : dir;
        return { ...n, score: n.score + delta, myVote: removing ? undefined : dir };
      }),
    );
    const action = previous?.myVote === dir ? removeVote('comment', commentId) : castVote('comment', commentId, dir);
    action.catch(() => {
      if (previous) {
        const snapshot = previous;
        setComments((prev) => updateNode(prev, commentId, () => snapshot));
      }
    });
  }, []);

  // Simplest correct option: post, then refetch the whole first page — constructing a locally-shaped
  // CommentNode by hand (right author username, ranks, nesting position) isn't worth it here.
  const submitComment = useCallback(
    async (parentId: string | null, body: string) => {
      await postComment(postId, parentId, body);
      await load();
    },
    [postId, load],
  );

  // GET /api/morechildren (feature 8) — the "N more replies" affordance. Reads the target node's own
  // server-computed repliesAfter cursor (never constructed client-side), fetches the next page of its
  // direct children, and appends them plus the new continuation cursor onto that same node.
  const loadMoreReplies = useCallback(
    (parentId: string) => {
      const node = findNode(comments, parentId);
      if (!node || node.repliesAfter == null) return;
      const cursor = node.repliesAfter;
      (async () => {
        try {
          const listing = await fetchMoreChildren(postId, parentId, sort, cursor);
          const newChildren = await mergeMyVotes(listing.data.children.map((c) => c.data));
          setComments((prev) =>
            updateNode(prev, parentId, (n) => ({
              ...n,
              replies: [...n.replies, ...newChildren],
              repliesAfter: listing.data.after,
            })),
          );
        } catch {
          setError('Could not load more replies.');
        }
      })();
    },
    [comments, postId, sort, mergeMyVotes],
  );

  // Optimistic body edit with rollback, same shape as applyPostVote. The server response is applied only for
  // body and editedAt — a PostWithComments/CommentView response doesn't carry this client's loaded state
  // (votes, reply subtree), so it's never merged in wholesale.
  const editPostBody = useCallback(
    async (body: string) => {
      const previousBody = post?.body ?? null;
      setPost((prev) => (prev ? { ...prev, body } : prev));
      try {
        const saved = await editPost(communityName, postId, body);
        setPost((prev) => (prev ? { ...prev, body: saved.body, editedAt: saved.editedAt } : prev));
      } catch (err) {
        setPost((prev) => (prev ? { ...prev, body: previousBody } : prev));
        throw err;
      }
    },
    [communityName, postId, post],
  );

  // Title/link edits are not optimistic: the server may reject them (grace window, validation), and showing a
  // title that then snaps back would be more confusing than a short wait.
  const editPostMeta = useCallback(
    async (fields: PostEditFields) => {
      const saved = await editPostFields(communityName, postId, fields);
      setPost((prev) => (prev ? { ...prev, title: saved.title, url: saved.url, editedAt: saved.editedAt } : prev));
    },
    [communityName, postId],
  );

  // Not optimistic: content loss is irreversible, so the tombstone is only applied once the server confirms.
  // Mirrors what the server wipes (see PostService.delete), so the page reflects it without a refetch.
  const removePost = useCallback(async () => {
    await deletePost(communityName, postId);
    setPost((prev) =>
      prev
        ? {
            ...prev,
            deleted: true,
            title: '[deleted]',
            body: null,
            url: null,
            mediaId: null,
            media: null,
            mediaItems: null,
            flairId: null,
            flair: null,
            pinned: false,
            authorUsername: null,
          }
        : prev,
    );
  }, [communityName, postId]);

  const editCommentBody = useCallback(
    async (commentId: string, body: string) => {
      const previousBody = findNode(comments, commentId)?.body;
      setComments((prev) => updateNode(prev, commentId, (n) => ({ ...n, body })));
      try {
        const saved = await editComment(commentId, body);
        setComments((prev) =>
          updateNode(prev, commentId, (n) => ({ ...n, body: saved.body, editedAt: saved.editedAt })),
        );
      } catch (err) {
        if (previousBody !== undefined) {
          setComments((prev) => updateNode(prev, commentId, (n) => ({ ...n, body: previousBody })));
        }
        throw err;
      }
    },
    [comments],
  );

  const removeComment = useCallback(async (commentId: string) => {
    await deleteComment(commentId);
    setComments((prev) =>
      updateNode(prev, commentId, (n) => ({ ...n, deleted: true, body: '[deleted]', authorUsername: null })),
    );
  }, []);

  return {
    post,
    comments,
    loading,
    error,
    hasMoreComments: after !== null,
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
  };
}
