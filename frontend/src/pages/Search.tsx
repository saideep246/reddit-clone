import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { CommunityCard } from '../components/CommunityCard';
import { PostCard } from '../components/PostCard';
import { UserResultCard } from '../components/UserResultCard';
import { useCommentSearch } from '../hooks/useCommentSearch';
import { useCommunitySearch } from '../hooks/useCommunitySearch';
import { usePostSearch } from '../hooks/usePostSearch';
import { useUserSearch } from '../hooks/useUserSearch';
import { decodeHtmlEntities } from '../lib/html';
import { timeAgo } from '../lib/time';
import styles from './Search.module.css';

export function Search() {
  const [searchParams] = useSearchParams();
  const urlQuery = searchParams.get('q') ?? '';
  // ?community=<name> scopes post and comment search to one community (set by the community page's own box).
  const scope = searchParams.get('community') || undefined;
  const [inputValue, setInputValue] = useState(urlQuery);
  const [debouncedQuery, setDebouncedQuery] = useState(urlQuery);

  // Same "resync when the URL's own q changes from outside" need as CommunityDiscovery — the nav bar's
  // search box navigates here with a new ?q= while this page may already be mounted.
  useEffect(() => {
    setInputValue(urlQuery);
    setDebouncedQuery(urlQuery);
  }, [urlQuery]);

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuery(inputValue), 300);
    return () => clearTimeout(timer);
  }, [inputValue]);

  const query = debouncedQuery.trim();
  const posts = usePostSearch(query, scope);
  const commentHits = useCommentSearch(query, scope);
  // People type "r/name" and "u/name" the way Reddit writes them; names are stored without the prefix.
  const communities = useCommunitySearch(query.replace(/^\/?r\//i, ''));
  const users = useUserSearch(query.replace(/^\/?u(ser)?\//i, ''));

  return (
    <div>
      <input
        className={styles.searchInput}
        type="search"
        placeholder="Search reddit"
        value={inputValue}
        onChange={(e) => setInputValue(e.target.value)}
        autoFocus
      />

      {scope && (
        <p className={styles.scopeNote}>
          Searching in <strong>r/{scope}</strong> · <Link to={`/search?q=${encodeURIComponent(query)}`}>search all of Reddit</Link>
        </p>
      )}

      {!query ? (
        <div className={styles.state}>Type something to search.</div>
      ) : (
        <>
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Posts</h2>
            {posts.loading ? (
              <div className={styles.state}>Loading…</div>
            ) : posts.error ? (
              <div className={styles.state}>{posts.error}</div>
            ) : posts.posts.length === 0 ? (
              <div className={styles.state}>No posts found for "{query}".</div>
            ) : (
              posts.posts.map((post) => <PostCard key={post.id} post={post} onVote={posts.applyVote} />)
            )}
          </section>

          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Comments</h2>
            {commentHits.loading ? (
              <div className={styles.state}>Loading…</div>
            ) : commentHits.error ? (
              <div className={styles.state}>{commentHits.error}</div>
            ) : commentHits.comments.length === 0 ? (
              <div className={styles.state}>No comments found for "{query}".</div>
            ) : (
              commentHits.comments.map((c) => (
                <Link key={c.id} className={styles.commentHit} to={`/r/${c.communityName}/comments/${c.postId}`}>
                  <div className={styles.commentMeta}>
                    u/{c.authorUsername ?? '[deleted]'} on “{decodeHtmlEntities(c.postTitle ?? '')}” · r/{c.communityName} · {timeAgo(c.createdAt)}
                  </div>
                  <div className={styles.commentBody}>{decodeHtmlEntities(c.body)}</div>
                </Link>
              ))
            )}
          </section>

          {!scope && (
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Communities</h2>
            {communities.loading ? (
              <div className={styles.state}>Loading…</div>
            ) : communities.error ? (
              <div className={styles.state}>{communities.error}</div>
            ) : communities.communities.length === 0 ? (
              <div className={styles.state}>No communities found for "{query}".</div>
            ) : (
              communities.communities.map((community) => (
                <CommunityCard
                  key={community.id}
                  community={community}
                  onJoin={communities.join}
                  onLeave={communities.leave}
                  onRequestJoin={communities.requestJoin}
                />
              ))
            )}
          </section>
          )}

          {!scope && (
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>People</h2>
            {users.actionError && <p className={styles.actionError}>{users.actionError}</p>}
            {users.loading ? (
              <div className={styles.state}>Loading…</div>
            ) : users.error ? (
              <div className={styles.state}>{users.error}</div>
            ) : users.users.length === 0 ? (
              <div className={styles.state}>No people found for "{query}".</div>
            ) : (
              users.users.map((profile) => (
                <UserResultCard key={profile.id} profile={profile} onFollow={users.follow} onUnfollow={users.unfollow} />
              ))
            )}
          </section>
          )}
        </>
      )}
    </div>
  );
}
