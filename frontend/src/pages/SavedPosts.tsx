import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { PostList } from '../components/PostList';
import { useSavedPosts } from '../hooks/useSavedPosts';
import styles from './SavedPosts.module.css';

export function SavedPosts() {
  const { user } = useAuth();
  const saved = useSavedPosts();

  if (!user) {
    return (
      <div className={styles.page}>
        <Link to="/login">Log in</Link> to see your saved posts.
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Saved</h1>
      <PostList
        posts={saved.posts}
        loading={saved.loading}
        loadingMore={saved.loadingMore}
        error={saved.error}
        hasMore={saved.hasMore}
        onLoadMore={saved.loadMore}
        onVote={saved.applyVote}
      />
    </div>
  );
}
