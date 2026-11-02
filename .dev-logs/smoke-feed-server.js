// Serves the fixture feed for the appendix A smoke sequence, and lets the test mutate it between refreshes so a
// conditional 304 and a real "new item" are both observable over HTTP rather than only through MockWebServer.
// The revision counter is bumped by GET /bump, which also changes the ETag.
const http = require('http');
const crypto = require('crypto');

let revision = 1;

function body() {
  const items = [];
  for (let i = 1; i <= revision; i++) {
    items.push(
      `<item><title>Smoke item ${i} of revision ${revision}</title>` +
        `<link>http://127.0.0.1:19999/post/${i}</link>` +
        `<guid>http://127.0.0.1:19999/post/${i}</guid>` +
        `<description>Body ${i}, revision ${revision}.</description>` +
        // content:encoded is what makes FR-7.2's gate observable over HTTP: a repack outlet may ship this body only
        // after the member feed's full-content flag is turned on.
        `<content:encoded><![CDATA[Full body ${i}, revision ${revision}.]]></content:encoded>` +
        `<pubDate>${new Date(Date.UTC(2026, 9, 1 + i, 8, 0, 0)).toUTCString()}</pubDate></item>`
    );
  }
  return (
    '<?xml version="1.0" encoding="UTF-8"?>' +
    '<rss version="2.0"><channel><title>Smoke source</title>' +
    '<link>http://127.0.0.1:19999/</link>' +
    '<description>Fixture feed for the curl smoke sequence.</description>' +
    `<ttl>60</ttl>${items.join('')}</channel></rss>`
  );
}

function etag() {
  return 'W/"' + crypto.createHash('sha256').update(String(revision)).digest('hex').slice(0, 16) + '"';
}

http
  .createServer((req, res) => {
    if (req.url.startsWith('/bump')) {
      revision++;
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end('revision=' + revision + '\n');
      return;
    }
    if (!req.url.startsWith('/feed.xml')) {
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end('not found\n');
      return;
    }
    const tag = etag();
    if (req.headers['if-none-match'] === tag || req.headers['if-none-match'] === '*') {
      res.writeHead(304, { etag: tag });
      res.end();
      return;
    }
    const text = body();
    res.writeHead(200, {
      'content-type': 'application/rss+xml; charset=utf-8',
      etag: tag,
      'last-modified': new Date().toUTCString(),
    });
    res.end(text);
  })
  .listen(19999, '127.0.0.1', () => console.log('fixture feed on http://127.0.0.1:19999/feed.xml'));
