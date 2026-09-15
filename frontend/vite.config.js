import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { gzipSync } from 'node:zlib'

/**
 * 配合 index.html 里的首屏引导态, 只在 build 时生效。做两件事:
 *
 * 1. 解除样式表的渲染阻塞。vite 会把 <link rel="stylesheet"> 注入 <head>,
 *    浏览器要等它下完才肯画第一帧 —— 那个 CSS 有 340 KB, 限流时等于
 *    引导态根本画不出来, 内联再多也白搭。改成 media="print" 先不参与匹配,
 *    onload 再换回 all。<noscript> 兜没有 JS 的情况。
 *
 * 2. 把主包 gzip 后的字节数写进 <meta>, 引导脚本拿它和实测带宽算 ETA,
 *    进度条才有真实节奏, 不用瞎编。
 *
 * 删掉这个插件, index.html 里那段引导态就失效了(白屏照旧)。
 */
function bootLoader() {
  return {
    name: 'boot-loader',
    apply: 'build',
    enforce: 'post',
    transformIndexHtml: {
      order: 'post',
      handler(html, ctx) {
        let bytes = 0
        for (const file of Object.values(ctx.bundle || {})) {
          if (file.type === 'chunk' && file.isEntry) {
            bytes += gzipSync(file.code).length
          }
        }
        if (bytes) {
          html = html.replace('</title>', `</title>\n  <meta name="boot-bytes" content="${bytes}" />`)
        }

        return html.replace(
          /<link([^>]*?)rel="stylesheet"([^>]*?)href="([^"]+)"([^>]*?)>/g,
          (_m, a, b, href, c) =>
            `<link${a}rel="stylesheet"${b}href="${href}"${c} media="print" onload="this.media='all'">` +
            `<noscript><link rel="stylesheet" href="${href}"></noscript>`
        )
      }
    }
  }
}

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue(), bootLoader()],
  server: {
    host: '0.0.0.0',
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:7899',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
})
