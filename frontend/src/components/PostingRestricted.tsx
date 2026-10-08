import { Link } from 'react-router-dom';
import styles from './PostingRestricted.module.css';

interface PostingRestrictedProps {
  communityName: string;
  // On the composer page there is a way back to the community; on the community page itself there is not.
  showBackLink?: boolean;
}

// Shown in place of "Create Post" to someone who may not post in a restricted community. Only an explanation: the server still
// refuses their posts, this just saves them from filling in a form that could never be sent.
export function PostingRestricted({ communityName, showBackLink = false }: PostingRestrictedProps) {
  return (
    <div className={styles.box} role="note">
      <strong className={styles.title}>Posting is restricted</strong>
      <p className={styles.text}>
        Only approved submitters and moderators can post in r/{communityName}. You can still read and comment. Ask a moderator if you would
        like to be approved.
      </p>
      {showBackLink && (
        <Link to={`/r/${communityName}`} className={styles.link}>
          Back to r/{communityName}
        </Link>
      )}
    </div>
  );
}
