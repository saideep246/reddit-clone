import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { CommunityCard } from '../components/CommunityCard';
import { useMyCommunities } from '../hooks/useMyCommunities';
import styles from './ManageCommunities.module.css';

export function ManageCommunities() {
  const { user } = useAuth();
  const mine = useMyCommunities(!!user);

  if (!user) {
    return (
      <div className={styles.state}>
        <Link to="/login">Log in</Link> to manage your communities.
      </div>
    );
  }

  return (
    <div>
      <h1 className={styles.title}>Manage communities</h1>
      <p className={styles.subtitle}>Communities you have joined. Use the button to leave one, or join it again.</p>
      {mine.loading ? (
        <div className={styles.state}>Loading…</div>
      ) : mine.error ? (
        <div className={styles.state}>{mine.error}</div>
      ) : mine.communities.length === 0 ? (
        <div className={styles.state}>
          You haven't joined any communities yet. <Link to="/communities">Explore communities</Link>
        </div>
      ) : (
        mine.communities.map((community) => (
          <CommunityCard key={community.id} community={community} onJoin={mine.join} onLeave={mine.leave} onRequestJoin={mine.requestJoin} />
        ))
      )}
    </div>
  );
}
