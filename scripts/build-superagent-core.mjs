import { build } from '../node_modules/.pnpm/esbuild@0.24.2/node_modules/esbuild/lib/main.js'
await build({ entryPoints: ['scripts/vendor/dsh-superagent/core/index.ts'], outfile: 'scripts/runtime-profile/plugins/dsh-mobile-shizuku/lib/superagent-core.js', bundle: true, platform: 'node', format: 'esm', target: 'node22', sourcemap: false, legalComments: 'inline' })
