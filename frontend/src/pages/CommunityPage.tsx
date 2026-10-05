import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { CommunityHeader } from '../components/CommunityHeader';
import { CommunitySidebar } from '../components/CommunitySidebar';
import { PostCard } from '../components/PostCard';
import { PostList } from '../components/PostList';
import { SortTabs } from '../components/SortTabs';
import { useCommunity } from '../hooks/useCommunity';
import { useFeed } from '../hooks/useFeed';
import type { SortType, TopPeriod } from '../types/post';
import styles from './CommunityPage.module.css';

export function CommunityPage() {
  const { communityName = '' } = useParams();
  const [searchParams] = useSearchParams();
  const sort = (searchParams.get('sort') as SortType) || 'hot';
  const period = (searchParams.get('t') as TopPeriod) || 'all';

  const { user } = useAuth();
  const { community, rules, pinned, loading, error, actionError, join, leave, requestJoin, applyPinnedVote } = useCommunity(communityName);
  const feed = useFeed(communityName, sort, period);
  const navigate = useNavigate();
  const [searchText, setSearchText] = useState('');

  const searchHere = (e: FormEvent) => {
    e.preventDefault();
    const q = searchText.trim();
    if (q) navigate(`/search?q=${encodeURIComponent(q)}&community=${encodeURIComponent(communityName)}`);
  };

  if (loading) {
    return <div className={styles.state}>Loading…</div>;
  }
  if (error || !community) {
    return <div className={styles.state}>{error ?? 'Community not found.'}</div>;
  }

  return (
    <>
    <CommunityHeader community={community} />
    <div className={styles.layout}>
      <div className={styles.main}>
        <form onSubmit={searchHere} className={styles.communitySearch}>
          <input
            className={styles.communitySearchInput}
            type="search"
            placeholder={`Search r/${communityName}`}
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
          />
        </form>
        {user && (
          <Link to={`/r/${communityName}/submit`} className={styles.createPostButton}>
            Create Post
          </Link>
        )}
        {pinned.length > 0 && (
          <div className={styles.pinnedSection}>
            {pinned.map((post) => (
              <PostCard key={post.id} post={post} onVote={applyPinnedVote} />
            ))}
          </div>
        )}
        <SortTabs />
        {feed.forbidden ? (
          <div className={styles.state}>This community is private. Request access to view its posts.</div>
        ) : (
          <PostList
            posts={feed.posts}
            loading={feed.loading}
            loadingMore={feed.loadingMore}
            error={feed.error}
            hasMore={feed.hasMore}
            onLoadMore={feed.loadMore}
            onVote={feed.applyVote}
          />
        )}
      </div>
      <div className={styles.sidebarColumn}>
        <CommunitySidebar community={community} rules={rules} actionError={actionError} onJoin={join} onLeave={leave} onRequestJoin={requestJoin} />
      </div>
    </div>
    </>
  );
}
