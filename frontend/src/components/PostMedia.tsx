import { useState } from 'react';
import { useLiveMedia } from '../hooks/useLiveMedia';
import { decodeHtmlEntities } from '../lib/html';
import { useSettings } from '../settings/SettingsContext';
import { CrosspostEmbed } from './CrosspostEmbed';
import { PollBlock } from './PollBlock';
import type { MediaView, Post } from '../types/post';
import styles from './PostMedia.module.css';

interface PostMediaProps {
  post: Post;
  // false (default, feed cards): clamps text-post bodies to 3 lines. true (post detail page): shows the
  // complete body.
  fullBody?: boolean;
}

function domainOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

// One image in whatever state processing is in. Only the processed, public renditions are ever shown — never the original
// upload — so until the file is 'ready' there is just a placeholder. `size` picks the rendition: 'thumb' (256px) for feed
// cards, 'display' (1280px) for the post detail page. Refreshes itself (useLiveMedia) when processing finishes.
// `lazy` defers loading until near the viewport — only worth it for the long gallery strip; a post's main image is on
// screen straight away and must start loading at once.
function LiveImage({ media, className, size, lazy = false }: { media: MediaView | null | undefined; className: string; size: 'thumb' | 'display'; lazy?: boolean }) {
  const live = useLiveMedia(media);
  if (!live) return <div className={styles.mediaPlaceholder}>Image unavailable</div>;
  if (live.processingStatus === 'failed') {
    return <div className={styles.mediaPlaceholder}>This image couldn't be processed.</div>;
  }
  const src = live.processingStatus === 'ready' ? (size === 'display' ? (live.displayUrl ?? live.thumbnailUrl) : (live.thumbnailUrl ?? live.displayUrl)) : null;
  if (!src) return <div className={styles.mediaPlaceholder}>Image processing…</div>;
  return <img className={className} src={src} alt="" loading={lazy ? 'lazy' : undefined} />;
}

function LiveVideo({ media }: { media: MediaView | null | undefined }) {
  const live = useLiveMedia(media);
  if (!live) return <div className={styles.mediaPlaceholder}>Video unavailable</div>;
  if (live.processingStatus === 'failed') {
    return <div className={styles.mediaPlaceholder}>This video couldn't be processed.</div>;
  }
  if (live.processingStatus !== 'ready' || !live.displayUrl) {
    return <div className={styles.mediaPlaceholder}>Video processing… it will appear here when it's ready.</div>;
  }
  return <video className={styles.thumbnail} src={live.displayUrl} poster={live.thumbnailUrl ?? undefined} controls preload="metadata" />;
}

function renderContent(post: Post, fullBody: boolean) {
  if (post.kind === 'image' || post.kind === 'video') {
    return post.kind === 'video' ? (
      <LiveVideo media={post.media} />
    ) : (
      <LiveImage media={post.media} className={styles.thumbnail} size={fullBody ? 'display' : 'thumb'} />
    );
  }
  if (post.kind === 'gallery' && post.mediaItems && post.mediaItems.length > 0) {
    if (!fullBody) {
      // Feed card: first image only, same treatment as a single-image post, plus a "+N" badge when
      // there's more than one — matches real Reddit's own feed-vs-detail gallery treatment.
      const first = post.mediaItems[0];
      return (
        <div className={styles.galleryPreview}>
          <LiveImage media={first} className={styles.thumbnail} size="thumb" />
          {post.mediaItems.length > 1 && <span className={styles.galleryCountBadge}>+{post.mediaItems.length - 1}</span>}
        </div>
      );
    }
    // Detail page: the full ordered strip, scrollable since a gallery can have up to 20 images.
    return (
      <div className={styles.galleryStrip}>
        {post.mediaItems.map((item, index) => (
          <LiveImage key={item.id ?? index} media={item} className={styles.galleryStripImage} size="display" lazy={index > 2} />
        ))}
      </div>
    );
  }
  if (post.kind === 'poll') {
    return <PollBlock post={post} interactive={fullBody} />;
  }
  if (post.kind === 'crosspost') {
    return <CrosspostEmbed parent={post.crosspostParent} />;
  }
  if (post.kind === 'link' && post.url) {
    return <div className={styles.domain}>({domainOf(post.url)})</div>;
  }
  if (post.kind === 'text' && post.body) {
    const snippetClass = fullBody ? styles.snippet : `${styles.snippet} ${styles.snippetTruncated}`;
    return <p className={snippetClass}>{decodeHtmlEntities(post.body)}</p>;
  }
  return null;
}

export function PostMedia({ post, fullBody = false }: PostMediaProps) {
  const { nsfwBlurEffective } = useSettings();
  const [revealed, setRevealed] = useState(false);
  const content = renderContent(post, fullBody);
  if (content === null) return null;

  if (post.nsfw && nsfwBlurEffective && !revealed) {
    return (
      <div className={styles.nsfwWrapper}>
        <div className={styles.nsfwBlurred}>{content}</div>
        <button type="button" className={styles.nsfwReveal} onClick={() => setRevealed(true)}>
          NSFW — click to view
        </button>
      </div>
    );
  }
  return content;
}
