import { Link } from 'react-router-dom';
import { decodeHtmlEntities } from '../lib/html';
import type { CrosspostParent } from '../types/post';
import styles from './CrosspostEmbed.module.css';

export function CrosspostEmbed({ parent }: { parent: CrosspostParent | null | undefined }) {
  if (!parent) return null;
  if (!parent.available) {
    return <div className={`${styles.embed} ${styles.unavailable}`}>The original post is no longer available.</div>;
  }
  return (
    <Link
      className={styles.embed}
      to={`/r/${parent.communityName}/comments/${parent.id}`}
      onClick={(e) => e.stopPropagation()}
    >
      <div className={styles.meta}>
        Crossposted from r/{parent.communityName} · u/{parent.authorUsername ?? '[deleted]'}
      </div>
      <div className={styles.title}>{decodeHtmlEntities(parent.title ?? '')}</div>
      {parent.body && <div className={styles.body}>{decodeHtmlEntities(parent.body)}</div>}
      {parent.url && <div className={styles.url}>{parent.url}</div>}
    </Link>
  );
}
