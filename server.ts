import express from 'express';
import { createServer as createViteServer } from 'vite';
import axios from 'axios';
import * as cheerio from 'cheerio';
import path from 'path';
import { fileURLToPath } from 'url';
import cors from 'cors';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

async function startServer() {
  const app = express();
  const PORT = 3000;

  app.use(cors());
  app.use(express.json());

  // Ad and Tracker Blocklist (Basic patterns)
  const blocklist = [
    'googlesyndication.com',
    'google-analytics.com',
    'doubleclick.net',
    'adnxs.com',
    'facebook.net',
    'scorecardresearch.com',
    'quantserve.com',
    'amazon-adsystem.com',
    'taboola.com',
    'outbrain.com',
    'adsbygoogle',
    'analytics.js',
    'gtm.js',
  ];

  const adSelectors = [
    '.ad-container',
    '.adsbygoogle',
    '#ad-banner',
    '[id^="google_ads_"]',
    '[class*="sponsor"]',
    'ins.adsbygoogle',
    'div[data-ad-unit]',
    '.banner-ad',
  ];

  // Proxy endpoint
  app.get('/api/proxy', async (req, res) => {
    const targetUrl = req.query.url as string;
    if (!targetUrl) {
      return res.status(400).send('URL is required');
    }

    try {
      const response = await axios.get(targetUrl, {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        },
        responseType: 'text',
      });

      const html = response.data;
      const $ = cheerio.load(html);

      let blockedCount = 0;

      // 1. Block Scripts
      $('script').each((i, el) => {
        const src = $(el).attr('src');
        if (src) {
          const isBlocked = blocklist.some(pattern => src.includes(pattern));
          if (isBlocked) {
            $(el).remove();
            blockedCount++;
          }
        }
        
        // Block inline scripts that look like analytics
        const content = $(el).html();
        if (content && (content.includes('gtag') || content.includes('ga(') || content.includes('fbq'))) {
          $(el).remove();
          blockedCount++;
        }
      });

      // 2. Block Ad Elements
      adSelectors.forEach(selector => {
        $(selector).each((i, el) => {
          $(el).remove();
          blockedCount++;
        });
      });

      // 3. Rewrite Links to stay in proxy (Optional, but helps)
      // For now, we'll just handle the main page content.
      
      // 4. Inject a small script to block popups and handle stats
      $('head').prepend(`
        <script>
          // Popup Blocker
          window.open = function() {
            console.log('CDG Browser: Popup blocked');
            return null;
          };
          
          // Click Redirection Blocker (Basic)
          document.addEventListener('click', function(e) {
            const target = e.target.closest('a');
            if (target && target.target === '_blank') {
              // Prevent opening in new tab if it's a suspicious link
              // For now, just log it
              console.log('CDG Browser: Prevented potential popup redirection');
            }
          }, true);

          // Notify parent about blocked items
          window.parent.postMessage({ type: 'STATS', blocked: ${blockedCount} }, '*');
        </script>
      `);

      // 5. Fix relative paths and rewrite links to stay in proxy
      const baseUrl = new URL(targetUrl);
      $('link, script, img, source, a').each((i, el) => {
        const attr = $(el).attr('src') ? 'src' : ($(el).attr('href') ? 'href' : null);
        if (attr) {
          let val = $(el).attr(attr);
          if (val && !val.startsWith('data:')) {
            try {
              const resolved = new URL(val, targetUrl).href;
              
              // If it's a link, rewrite it to go through our proxy
              if (el.tagName === 'a' && attr === 'href' && !val.startsWith('#')) {
                $(el).attr('href', `/api/proxy?url=${encodeURIComponent(resolved)}`);
                // Force links to stay in the same frame/tab for better control
                $(el).removeAttr('target');
              } else {
                $(el).attr(attr, resolved);
              }
            } catch (e) {}
          }
        }
      });

      res.send($.html());
    } catch (error: any) {
      console.error('Proxy error:', error.message);
      res.status(500).send(`Error fetching URL: ${error.message}`);
    }
  });

  // Vite middleware for development
  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), 'dist');
    app.use(express.static(distPath));
    app.get('*', (req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`CDG Browser Server running on http://localhost:${PORT}`);
  });
}

startServer();
