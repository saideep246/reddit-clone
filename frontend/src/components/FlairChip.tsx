import styles from './FlairChip.module.css';

// The flair as members see it on a post: the flair's own colour as the background with white text, same look as PostCard's chip.
export function FlairChip({ text, color }: { text: string; color: string }) {
  return (
    <span className={styles.chip} style={{ backgroundColor: color, color: '#fff' }}>
      {text || 'Flair'}
    </span>
  );
}
