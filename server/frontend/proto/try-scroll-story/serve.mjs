// PROTOTYPE server: `node proto/try-scroll-story/serve.mjs` from server/frontend, then open
// http://127.0.0.1:5199/proto/try-scroll-story/?v=a (restrained) or ?v=b (bolder).
// Vite's dev server without the app's own config (no PWA, no router plugin): the prototype needs
// neither, and nothing here touches the app's build.
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { createServer } from 'vite'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const server = await createServer({ root, configFile: false, server: { port: 5199, strictPort: true, host: '127.0.0.1' } })
await server.listen()
server.printUrls()
console.log('Open /proto/try-scroll-story/?v=a or ?v=b')
