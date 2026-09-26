// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import aiArtifacts from './scripts/ai-artifacts.mjs';

// The deployed origin, for canonical URLs and the sitemap. Set ELCI_SITE_URL when publishing.
const site = process.env.ELCI_SITE_URL;
const origin = (site || 'https://<your-elci-docs-host>').replace(/\/$/, '');

// Pages write absolute links to the site (agent setup commands, prompts) with this placeholder
// origin; it becomes the configured site at build time.
const SITE_TOKEN = 'https://__ELCI_DOCS__';
function siteUrls() {
  const replace = (node) => {
    for (const key of ['value', 'url'])
      if (typeof node[key] === 'string') node[key] = node[key].replaceAll(SITE_TOKEN, origin);
    node.children?.forEach(replace);
  };
  return (tree) => replace(tree);
}

export default defineConfig({
  site,
  trailingSlash: 'always',
  markdown: { remarkPlugins: [siteUrls] },
  integrations: [
    starlight({
      title: 'Elci',
      description:
        'Elci is an Elm-syntax functional language for Cardano smart contracts, compiled to Plutus V3 through Julc.',
      logo: { src: './src/assets/logo.svg' },
      favicon: '/favicon.svg',
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/bloxbean/elci-lang' }],
      editLink: { baseUrl: 'https://github.com/bloxbean/elci-lang/edit/main/www/' },
      customCss: ['./src/styles/docs.css'],
      expressiveCode: {
        themes: ['rose-pine', 'rose-pine-dawn'],
        // Warm code surfaces that sit on the sand and espresso page colours.
        styleOverrides: {
          borderRadius: '0.75rem',
          codeBackground: ({ theme }) => (theme.type === 'dark' ? '#18110e' : '#ffffff'),
          frames: {
            editorTabBarBackground: ({ theme }) => (theme.type === 'dark' ? '#221813' : '#f1f0ee'),
            editorActiveTabBackground: ({ theme }) => (theme.type === 'dark' ? '#18110e' : '#ffffff'),
            terminalBackground: ({ theme }) => (theme.type === 'dark' ? '#18110e' : '#ffffff'),
            terminalTitlebarBackground: ({ theme }) => (theme.type === 'dark' ? '#221813' : '#f1f0ee'),
          },
        },
      },
      sidebar: [
        { label: 'Start here', items: [{ autogenerate: { directory: 'start' } }] },
        { label: 'Build with AI', items: [{ autogenerate: { directory: 'ai' } }] },
        { label: 'Learn the language', items: [{ autogenerate: { directory: 'learn' } }] },
        { label: 'Write contracts', items: [{ autogenerate: { directory: 'contracts' } }] },
        { label: 'Test your contracts', items: [{ autogenerate: { directory: 'testing' } }] },
        { label: 'Tutorials', items: [{ autogenerate: { directory: 'tutorials' } }] },
        { label: 'Advanced', items: [{ autogenerate: { directory: 'advanced' } }] },
        { label: 'Reference', collapsed: true, items: [{ autogenerate: { directory: 'reference' } }] },
      ],
      head: [
        { tag: 'meta', attrs: { name: 'theme-color', content: '#fafaf9' } },
        { tag: 'link', attrs: { rel: 'alternate', type: 'text/plain', href: '/llms.txt', title: 'Elci docs for AI agents' } },
      ],
    }),
    aiArtifacts({ site }),
  ],
});
