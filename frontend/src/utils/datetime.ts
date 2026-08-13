/**
 * 后端时间字符串统一为 "yyyy-MM-dd HH:mm:ss"。
 * 部分浏览器对空格分隔的日期时间解析不稳定，此处归一化为 ISO 形式后再解析。
 */
export function toDate(t: string | null | undefined): Date | null {
  if (!t) return null;
  const d = new Date(t.includes('T') ? t : t.replace(' ', 'T'));
  return Number.isNaN(d.getTime()) ? null : d;
}
