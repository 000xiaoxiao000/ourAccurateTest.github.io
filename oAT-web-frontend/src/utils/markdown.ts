function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function escapeAttribute(value: string) {
  return escapeHtml(value).replace(/`/g, '&#96;')
}

function isSafeUrl(value: string) {
  return /^https?:\/\//i.test(value)
}

function inlineMarkdown(value: string) {
  return escapeHtml(value)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/__([^_]+)__/g, '<strong>$1</strong>')
    .replace(/(^|\W)\*([^*]+)\*/g, '$1<em>$2</em>')
    .replace(/(^|\W)_([^_]+)_/g, '$1<em>$2</em>')
    .replace(/\[([^\]]+)\]\(([^\s)]+)\)/g, (_, label: string, url: string) => {
      if (!isSafeUrl(url)) return label
      return `<a href="${escapeAttribute(url)}" target="_blank" rel="noreferrer">${label}</a>`
    })
}

function isTableSeparator(line: string) {
  return /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line)
}

function splitTableRow(line: string) {
  const trimmed = line.trim().replace(/^\|/, '').replace(/\|$/, '')
  return trimmed.split('|').map((cell) => cell.trim())
}

export function renderMarkdown(markdown: string) {
  const lines = markdown.replace(/\r\n/g, '\n').split('\n')
  const html: string[] = []
  let listOpen: 'ul' | 'ol' | '' = ''
  let codeOpen = false
  let paragraph: string[] = []

  const closeList = () => {
    if (!listOpen) return
    html.push(`</${listOpen}>`)
    listOpen = ''
  }
  const flushParagraph = () => {
    if (!paragraph.length) return
    html.push(`<p>${inlineMarkdown(paragraph.join(' '))}</p>`)
    paragraph = []
  }
  const closeBlocks = () => {
    flushParagraph()
    closeList()
  }

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    const trimmed = line.trim()

    if (trimmed.startsWith('```')) {
      closeBlocks()
      html.push(codeOpen ? '</code></pre>' : '<pre><code>')
      codeOpen = !codeOpen
      continue
    }
    if (codeOpen) {
      html.push(`${escapeHtml(line)}\n`)
      continue
    }
    if (!trimmed) {
      closeBlocks()
      continue
    }

    const nextLine = lines[index + 1] || ''
    if (trimmed.includes('|') && isTableSeparator(nextLine)) {
      closeBlocks()
      const headers = splitTableRow(trimmed)
      html.push('<div class="markdown-table-scroll"><table><thead><tr>')
      html.push(headers.map((cell) => `<th>${inlineMarkdown(cell)}</th>`).join(''))
      html.push('</tr></thead><tbody>')
      index += 2
      while (index < lines.length && lines[index].trim().includes('|')) {
        const cells = splitTableRow(lines[index])
        html.push('<tr>')
        html.push(headers.map((_, cellIndex) => `<td>${inlineMarkdown(cells[cellIndex] || '')}</td>`).join(''))
        html.push('</tr>')
        index += 1
      }
      index -= 1
      html.push('</tbody></table></div>')
      continue
    }

    const heading = trimmed.match(/^(#{1,4})\s+(.+)$/)
    if (heading) {
      closeBlocks()
      const level = heading[1].length
      if (heading[2].length > 72) {
        html.push(`<p class="markdown-lead">${inlineMarkdown(heading[2])}</p>`)
        continue
      }
      html.push(`<h${level}>${inlineMarkdown(heading[2])}</h${level}>`)
      continue
    }

    const quote = trimmed.match(/^>\s+(.+)$/)
    if (quote) {
      closeBlocks()
      html.push(`<blockquote>${inlineMarkdown(quote[1])}</blockquote>`)
      continue
    }

    const ordered = trimmed.match(/^\d+[.)]\s+(.+)$/)
    if (ordered) {
      flushParagraph()
      if (listOpen !== 'ol') {
        closeList()
        listOpen = 'ol'
        html.push('<ol>')
      }
      html.push(`<li>${inlineMarkdown(ordered[1])}</li>`)
      continue
    }

    const bullet = trimmed.match(/^[-*+]\s+(.+)$/)
    if (bullet) {
      flushParagraph()
      if (listOpen !== 'ul') {
        closeList()
        listOpen = 'ul'
        html.push('<ul>')
      }
      html.push(`<li>${inlineMarkdown(bullet[1])}</li>`)
      continue
    }

    closeList()
    paragraph.push(trimmed)
  }

  closeBlocks()
  if (codeOpen) html.push('</code></pre>')
  return html.join('')
}
