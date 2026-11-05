export const checkItemNames = [
  '보험금을 주지 않는 경우', '기다려야 하는 기간', '보험료가 오를 수 있음',
  '알릴 의무', '약관이 정한 뜻', '중간에 해지하면',
]

export function sourceLabel(source) {
  if (!source) return '근거 정보가 확인되지 않았습니다'
  return [source.section, source.article, source.paragraph, source.page != null ? `${source.page}쪽` : null].filter(Boolean).join(' · ') || '근거 정보가 확인되지 않았습니다'
}

export function splitQuote(text, quote) {
  const original = String(text || '')
  // Match across PDF whitespace differences, keeping every character of the
  // original response (including line breaks) in the rendered excerpt.
  const offsets = []
  let normalizedText = ''
  for (const match of original.matchAll(/\s+|\S/g)) {
    normalizedText += /^\s/.test(match[0]) ? ' ' : match[0]
    offsets.push({ start: match.index, end: match.index + match[0].length })
  }
  const normalizedQuote = String(quote || '').replace(/\s+/g, ' ').trim()
  const index = normalizedQuote ? normalizedText.indexOf(normalizedQuote) : -1
  if (index < 0) return { before: original, quote: '', after: '' }
  const start = offsets[index].start
  const end = offsets[index + normalizedQuote.length - 1].end
  return {
    before: original.slice(0, start),
    quote: original.slice(start, end),
    after: original.slice(end),
  }
}
