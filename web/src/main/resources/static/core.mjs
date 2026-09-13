const HOSTS = new Set(['facebook.com', 'www.facebook.com', 'm.facebook.com', 'web.facebook.com', 'mbasic.facebook.com', 'fb.watch', 'www.fb.watch']);

export function validateFacebookUrl(raw) {
  if (typeof raw !== 'string' || !raw.trim()) throw new Error('請先貼上 Facebook 影片連結。');
  if (raw.trim().length > 2048 || /[\s<>\\]/.test(raw.trim())) throw new Error('連結格式不正確，請重新複製完整網址。');
  let url;
  try { url = new URL(raw.trim()); } catch { throw new Error('請貼上以 https:// 開頭的完整影片連結。'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.port || !HOSTS.has(url.hostname)) throw new Error('只接受 https://facebook.com 或 fb.watch 的影片連結。');
  if (url.pathname === '/' && !url.searchParams.get('v')) throw new Error('這是 Facebook 主頁，請改貼特定影片的分享連結。');
  if (url.pathname.startsWith('/l.php') || url.pathname.startsWith('/flx/')) throw new Error('請貼上影片連結，而非外部網站的轉址連結。');
  url.hash = '';
  return url.href;
}

export function formatDuration(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return '長度未提供';
  const m = Math.floor(seconds / 60), s = Math.floor(seconds % 60);
  return `${m}:${String(s).padStart(2, '0')}`;
}

export function validateMetadata(data) {
  if (!data || typeof data.id !== 'string' || !Array.isArray(data.formats) || !data.formats.length || !data.formats.every(f => typeof f.id === 'string' && typeof f.label === 'string')) throw new Error('下載服務回傳的影片資訊不完整，請稍後重試。');
  return data;
}

export function safeApiPath(base, value) {
  if (typeof value !== 'string' || !/^\/api\/jobs\/[a-zA-Z0-9-]+\/file(?:\?inline=true)?$/.test(value)) throw new Error('下載服務回傳了無效的影片網址。');
  return base + value;
}
