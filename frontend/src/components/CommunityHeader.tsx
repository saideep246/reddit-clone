import { useState } from 'react';
import { useCommunityImages } from '../hooks/useCommunityImages';
import type { Community } from '../types/community';
import styles from './CommunityHeader.module.css';

// The banner + round icon + name strip at the top of a community page, as on Reddit.
export function CommunityHeader({ community }: { community: Community }) {
  const { iconUrl, bannerUrl } = useCommunityImages(community);
  // A URL that fails to load (e.g. not yet visible on the CDN) falls back to the letter avatar instead of a broken image.
  const [iconBroken, setIconBroken] = useState(false);
  return (
    <header className={styles.header}>
      <div className={styles.banner} style={bannerUrl ? { backgroundImage: `url(${bannerUrl})` } : undefined} />
      <div className={styles.row}>
        {iconUrl && !iconBroken ? (
          <img className={styles.icon} src={iconUrl} alt="" onError={() => setIconBroken(true)} />
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
