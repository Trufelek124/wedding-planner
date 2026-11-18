import type { Inspiration, Source } from './inspirationsApi.ts'

export const SOURCE_LABEL: Record<Source, string> = {
  INSTAGRAM: 'Instagram',
  TIKTOK: 'TikTok',
  PINTEREST: 'Pinterest',
  WEB: 'Web',
  PHOTO: 'Photo',
}

/** What a card is called: the title a person gave it, else the page's own, else what it is. */
export function nameOf(inspiration: Pick<Inspiration, 'title' | 'previewTitle' | 'source' | 'url' | 'pictures'>): string {
  if (inspiration.title !== '') {
    return inspiration.title
  }
  if (inspiration.previewTitle !== '') {
    return inspiration.previewTitle
  }
  if (inspiration.url !== null) {
    return `From ${sourceName(inspiration)}`
  }
  return inspiration.pictures.length === 0 ? 'An idea' : inspiration.pictures.length === 1 ? 'A picture' : 'Pictures'
}

/** "Instagram", or the site's own name for a link somewhere else on the web. */
export function sourceName(inspiration: Pick<Inspiration, 'source' | 'url'>): string {
  if (inspiration.source !== 'WEB' || inspiration.url === null) {
    return SOURCE_LABEL[inspiration.source]
  }
  try {
    return new URL(inspiration.url).hostname.replace(/^www\./, '')
  } catch {
    return SOURCE_LABEL.WEB
  }
}

/** Tags as a person types them, "boho, lace sleeves", to a list; and back. */
export function tagsFrom(text: string): string[] {
  return text
    .split(',')
    .map((tag) => tag.trim())
    .filter((tag) => tag !== '')
}

export function tagsToText(tags: readonly string[]): string {
  return tags.join(', ')
}
