import { useSearchParams } from 'react-router-dom';
import { PostList } from '../components/PostList';
import styles from './Home.module.css';
import { SortTabs } from '../components/SortTabs';
import { useFeed } from '../hooks/useFeed';
import type { SortType, TopPeriod } from '../types/post';

export function Home() {
  const [searchParams] = useSearchParams();
  const sort = (searchParams.get('sort') as SortType) || 'hot';
  const period = (searchParams.get('t') as TopPeriod) || 'all';

  const { posts, loading, loadingMore, error, hasMore, loadMore, applyVote } = useFeed('all', sort, period);

  return (
    <div className={styles.feed}>
      <SortTabs />
      <PostList
        posts={posts}
        loading={loading}
        loadingMore={loadingMore}
        error={error}
        hasMore={hasMore}
        onLoadMore={loadMore}
        onVote={applyVote}
      />
    </div>
  );
}
