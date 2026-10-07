// A link post's url is user-supplied, so it is only ever turned into an <a href> when it is an absolute http(s) URL.
// Anything else (javascript:, data:, a relative path, a row stored before the backend validated this) is shown as text.
export function isSafeHttpUrl(url: string): boolean {
  try {
    const { protocol, hostname } = new URL(url);
    return (protocol === 'http:' || protocol === 'https:') && hostname !== '';
  } catch {
    return false;
  }
}
