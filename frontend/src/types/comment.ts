export interface CommentNode {
  id: string;
  postId: string;
  parentId: string | null;
  path: string;
  depth: number;
  authorId: string;
  authorUsername: string | null;
  body: string;
  score: number;
  ups: number;
  downs: number;
  bestRank: number;
  controversialRank: number;
  childCount: number;
  removed: boolean;
  deleted: boolean;
  sticky?: boolean;
  distinguished?: string | null;
  editedAt: string | null;
  createdAt: string;
  replies: CommentNode[];
  // Non-null exactly when this node's own direct children were truncated (childCount exceeds
  // replies.length) — an opaque cursor already positioned right after the last included child, ready to
  // pass straight through to fetchMoreChildren's `after` param. Never constructed client-side.
  repliesAfter: string | null;
  // Never sent by the backend — merged in client-side from GET /api/vote/mine?targetType=comment,
  // same reasoning as Post.myVote (see types/post.ts).
  myVote?: 1 | -1;
}

export type CommentSortType = 'best' | 'top' | 'new' | 'old' | 'controversial';

// A flat row for a user's own "comments" profile tab (F7) — a separate shape from CommentNode, which is
// the nested-tree view a single post's comment thread uses (replies/path/depth have no meaning here).
export interface UserComment {
  id: string;
  postId: string;
  postTitle: string | null;
  communityName: string | null;
  parentId: string | null;
  body: string;
  score: number;
  createdAt: string;
  // Never sent by the backend — merged in client-side from GET /api/vote/mine?targetType=comment, same
  // reasoning as CommentNode.myVote.
  myVote?: 1 | -1;
}
