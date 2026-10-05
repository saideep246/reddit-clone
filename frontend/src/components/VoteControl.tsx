import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { DownvoteIcon, UpvoteIcon } from './icons';
import styles from './VoteControl.module.css';

interface VoteControlProps {
  score: number;
  myVote?: 1 | -1;
  onVote: (dir: 1 | -1) => void;
  // pill: Reddit's rounded vote pill on posts. compact: the small inline control on comments.
  variant?: 'pill' | 'compact';
}

function formatScore(n: number): string {
  if (Math.abs(n) >= 10_000) return `${(n / 1000).toFixed(0)}k`;
  if (Math.abs(n) >= 1000) return `${(n / 1000).toFixed(1).replace(/\.0$/, '')}k`;
  return String(n);
}

export function VoteControl({ score, myVote, onVote, variant = 'pill' }: VoteControlProps) {
  const { user } = useAuth();
  const navigate = useNavigate();

  const handleClick = (e: React.MouseEvent, dir: 1 | -1) => {
    e.stopPropagation();
    if (!user) {
      navigate('/login');
      return;
    }
    onVote(dir);
  };

  const rootClass = [styles.control, variant === 'pill' ? styles.pill : styles.compact, myVote === 1 ? styles.up : '', myVote === -1 ? styles.down : '']
    .join(' ')
    .trim();

  return (
    <div className={rootClass} onClick={(e) => e.stopPropagation()}>
      <button type="button" className={`${styles.arrow} ${styles.arrowUp}`} aria-label="Upvote" aria-pressed={myVote === 1} onClick={(e) => handleClick(e, 1)}>
        <UpvoteIcon filled={myVote === 1} />
      </button>
      <span className={styles.score}>{formatScore(score)}</span>
      <button type="button" className={`${styles.arrow} ${styles.arrowDown}`} aria-label="Downvote" aria-pressed={myVote === -1} onClick={(e) => handleClick(e, -1)}>
        <DownvoteIcon filled={myVote === -1} />
      </button>
    </div>
  );
}
