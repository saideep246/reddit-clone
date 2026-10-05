import type { Community } from '../types/community';
import styles from './CommunityHeader.module.css';

// The banner + round icon + name strip at the top of a community page, as on Reddit.
export function CommunityHeader({ community }: { community: Community }) {
  return (
    <header className={styles.header}>
      <div className={styles.banner} style={community.bannerUrl ? { backgroundImage: `url(${community.bannerUrl})` } : undefined} />
      <div className={styles.row}>
        {community.iconUrl ? (
          <img className={styles.icon} src={community.iconUrl} alt="" />
        ) : (
          <span className={`${styles.icon} ${styles.iconFallback}`}>{community.name.slice(0, 1).toUpperCase()}</span>
        )}
        <div>
          <h1 className={styles.name}>r/{community.name}</h1>
          <div className={styles.sub}>
            {community.subscriberCount.toLocaleString()} member{community.subscriberCount === 1 ? '' : 's'}
            {community.type !== 'public' && <> · {community.type}</>}
          </div>
        </div>
      </div>
    </header>
  );
}
