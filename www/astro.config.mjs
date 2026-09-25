// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// The deployed origin, for canonical URLs and the sitemap. Set ELCI_SITE_URL when publishing.
const site = process.env.ELCI_SITE_URL;

export default defineConfig({
  site,
  trailingSlash: 'always',
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
        styleOverrides: { borderRadius: '0.75rem' },
      },
      sidebar: [
        { label: 'Start here', items: [{ autogenerate: { directory: 'start' } }] },
        { label: 'Learn the language', items: [{ autogenerate: { directory: 'learn' } }] },
        { label: 'Write contracts', items: [{ autogenerate: { directory: 'contracts' } }] },
        { label: 'Test your contracts', items: [{ autogenerate: { directory: 'testing' } }] },
        { label: 'Tutorials', items: [{ autogenerate: { directory: 'tutorials' } }] },
        { label: 'Advanced', items: [{ autogenerate: { directory: 'advanced' } }] },
        { label: 'Reference', collapsed: true, items: [{ autogenerate: { directory: 'reference' } }] },
      ],
      head: [{ tag: 'meta', attrs: { name: 'theme-color', content: '#fffaf5' } }],
    }),
  ],
});
