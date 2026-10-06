import { Link } from 'react-router-dom';
import { useCommunityImages } from '../hooks/useCommunityImages';
import type { Community, CommunityRule } from '../types/community';
import { JoinButton } from './JoinButton';
import styles from './CommunitySidebar.module.css';

interface CommunitySidebarProps {
  community: Community;
  rules: CommunityRule[];
  actionError: string | null;
  onJoin: () => void;
  onLeave: () => void;
  onRequestJoin: () => void;
}

export function CommunitySidebar({ community, rules, actionError, onJoin, onLeave, onRequestJoin }: CommunitySidebarProps) {
  const { iconUrl, bannerUrl } = useCommunityImages(community);
  const createdDate = new Date(community.createdAt).toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });

  return (
    <aside className={styles.sidebar}>
      {bannerUrl && <img className={styles.banner} src={bannerUrl} alt="" />}
      <div className={styles.header}>
        <h2 className={styles.name}>
          {iconUrl && <img className={styles.icon} src={iconUrl} alt="" />}
          r/{community.name}
          {community.isModerator && <span className={styles.modBadge}>Mod</span>}
        </h2>
        {community.isModerator && (
          <Link to={`/r/${community.name}/mod`} className={styles.modToolsLink}>
            Mod Tools
          </Link>
        )}
      </div>
      {community.description && <p className={styles.description}>{community.description}</p>}
      <div className={styles.stats}>
        <div>
          <span className={styles.statValue}>{community.subscriberCount}</span>
          members
        </div>
        <div>
          <span className={styles.statValue}>Created</span>
          {createdDate}
        </div>
      </div>
      <JoinButton community={community} onJoin={onJoin} onLeave={onLeave} onRequestJoin={onRequestJoin} />
      {actionError && <p className={styles.actionError}>{actionError}</p>}

      {rules.length > 0 && (
        <>
          <h3 className={styles.rulesTitle}>Rules</h3>
          <ol className={styles.rulesList}>
            {rules.map((rule, i) => (
              <li key={i} className={styles.ruleItem}>
                {rule.title}
                {rule.description && <span className={styles.ruleDescription}>{rule.description}</span>}
              </li>
            ))}
          </ol>
        </>
      )}
    </aside>
  );
}
