declare module 'markdown-it' {
  export interface MarkdownItOptions {
    html?: boolean
    linkify?: boolean
    breaks?: boolean
  }
  export interface MarkdownItToken {
    info?: string
    content?: string
  }
  export type MarkdownItRule = (tokens: MarkdownItToken[], idx: number) => string
  export default class MarkdownIt {
    constructor(options?: MarkdownItOptions)
    use(plugin: unknown, options?: Record<string, unknown>): this
    render(src: string): string
    renderer: {
      rules: Record<string, MarkdownItRule | undefined>
    }
    utils: {
      escapeHtml(str: string): string
    }
  }
}

declare module 'markdown-it-texmath' {
  const texmath: unknown
  export default texmath
}
