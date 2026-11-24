#!/usr/bin/env bash
set -Eeuo pipefail

# This CI-only smoke test runs the built image against a tiny HTTP backend.
# It exercises the actual server-rendered Host header and unknown-site response.
tmp_dir=$(mktemp -d)
backend_pid=
container_id=
cleanup() {
  [[ -z "$container_id" ]] || docker rm -f "$container_id" >/dev/null 2>&1 || true
  [[ -z "$backend_pid" ]] || kill "$backend_pid" 2>/dev/null || true
  rm -rf -- "$tmp_dir"
}
trap cleanup EXIT
on_error() {
  local status=$?
  printf 'WEB_IMAGE_HOST_ROUTING_TEST_FAILED line=%s status=%s command=%s\n' \
    "${BASH_LINENO[0]:-unknown}" "$status" "$BASH_COMMAND" >&2
  [[ -z "$container_id" ]] || docker logs "$container_id" >&2 || true
  for output in main.html subsite.html product.html default.html orders.html support.html admin.html; do
    [[ ! -f "$tmp_dir/$output" ]] || {
      printf '%s response preview: ' "$output" >&2
      head -c 1600 "$tmp_dir/$output" >&2
      printf '\n' >&2
    }
  done
  return "$status"
}
trap on_error ERR

node >"$tmp_dir/backend.log" 2>&1 <<'NODE' &
const http = require('node:http')
http.createServer((req, res) => {
  const names = { 'mc.mc520.top': 'Main Smoke', 'shop.mc520.top': 'Subsite Smoke', 'empty.mc520.top': 'Default Icon Smoke' }
  const site = names[req.headers.host]
  if (!site) { res.writeHead(404); res.end(); return }
  if (req.url === '/api/uploads/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png') {
    const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEUlEQVR4nGP4z8DQAMIMMAYAOOgF/REzMMkAAAAASUVORK5CYII=', 'base64')
    res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=31536000, immutable' })
    res.end(image); return
  }
  res.setHeader('Content-Type', 'application/json')
  const price = req.headers.host === 'mc.mc520.top' ? 19.25 : 27.50
  const favicon = req.headers.host === 'mc.mc520.top' ? '/api/uploads/main.png'
    : req.headers.host === 'shop.mc520.top' ? '/api/uploads/subsite.png' : null
  const product = { id: 'smoke-product', title: 'Smoke Item', price, retail_price: price,
    cover_url: '/api/uploads/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png',
    category_id: 'smoke', stock_available: 1, has_specs: false, specs: [], customer_input_fields: [] }
  const data = req.url.startsWith('/api/site/config') ? { site_name: site, favicon_url: favicon }
    : req.url.startsWith('/api/products/smoke-product') ? product
    : req.url.startsWith('/api/products') ? {
      list: [{ id: 'smoke-product', title: 'Smoke Item', price, retail_price: price,
        cover_url: '/api/uploads/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png',
        category_id: 'smoke', stock_available: 1, has_specs: false, customer_input_fields: [] }],
      pagination: { page: 1, page_size: 24, total: 1 },
    }
    : []
  res.end(JSON.stringify({ code: 0, data }))
}).listen(18931, '0.0.0.0')
NODE
backend_pid=$!
container_id=$(docker run -d --network host -e BACKEND_URL=http://127.0.0.1:18931 \
  -e PUBLIC_SITE_URL=https://mc.mc520.top -e PORT=18932 myshop-web:ci)
for attempt in $(seq 1 60); do
  if curl --fail --silent --max-time 2 -H 'Host: mc.mc520.top' http://127.0.0.1:18932/ >"$tmp_dir/main.html"; then break; fi
  [[ "$attempt" != 60 ]] || { docker logs "$container_id" >&2; exit 1; }
  sleep 1
done
grep -Fq 'Main Smoke' "$tmp_dir/main.html"
grep -Fq '19.25' "$tmp_dir/main.html"
curl --fail --silent --max-time 15 -H 'Host: shop.mc520.top' http://127.0.0.1:18932/ >"$tmp_dir/subsite.html"
grep -Fq 'Subsite Smoke' "$tmp_dir/subsite.html"
grep -Fq '27.50' "$tmp_dir/subsite.html"
! grep -Fq 'Main Smoke' "$tmp_dir/subsite.html"
! grep -Fq '19.25' "$tmp_dir/subsite.html"
curl --fail --silent --max-time 15 -H 'Host: shop.mc520.top' http://127.0.0.1:18932/product/smoke-product >"$tmp_dir/product.html"
curl --fail --silent --max-time 15 -H 'Host: empty.mc520.top' http://127.0.0.1:18932/ >"$tmp_dir/default.html"
curl --fail --silent --max-time 15 -H 'Host: mc.mc520.top' http://127.0.0.1:18932/my/orders >"$tmp_dir/orders.html"
curl --fail --silent --max-time 15 -H 'Host: shop.mc520.top' http://127.0.0.1:18932/messages/support/new >"$tmp_dir/support.html"
curl --fail --silent --max-time 15 -H 'Host: mc.mc520.top' http://127.0.0.1:18932/admin/site-config >"$tmp_dir/admin.html"
python3 - "$tmp_dir/main.html" "$tmp_dir/subsite.html" "$tmp_dir/product.html" "$tmp_dir/default.html" \
  "$tmp_dir/orders.html" "$tmp_dir/support.html" "$tmp_dir/admin.html" <<'PY'
from html.parser import HTMLParser
from pathlib import Path
import sys

class Metadata(HTMLParser):
    def __init__(self):
        super().__init__(); self.in_title=False; self.title=''; self.icons=[]; self.og_site=[]; self.twitter=[]
    def handle_starttag(self, tag, attrs):
        attrs=dict(attrs)
        if tag=='title': self.in_title=True
        if tag=='link' and 'icon' in attrs.get('rel','').split(): self.icons.append(attrs.get('href'))
        if tag=='meta' and attrs.get('property')=='og:site_name': self.og_site.append(attrs.get('content'))
        if tag=='meta' and attrs.get('name')=='twitter:title': self.twitter.append(attrs.get('content'))
    def handle_endtag(self, tag):
        if tag=='title': self.in_title=False
    def handle_data(self, data):
        if self.in_title: self.title+=data

for filename, title, icon, site in zip(sys.argv[1:],
        ['Main Smoke','Subsite Smoke','Smoke Item | Subsite Smoke','Default Icon Smoke',
         '购买记录 | Main Smoke','联系客服 | Subsite Smoke','网站设置 | Main Smoke'],
        ['/api/uploads/main.png','/api/uploads/subsite.png','/api/uploads/subsite.png','/default-icon.svg',
         '/api/uploads/main.png','/api/uploads/subsite.png','/api/uploads/main.png'],
        ['Main Smoke','Subsite Smoke','Subsite Smoke','Default Icon Smoke',
         'Main Smoke','Subsite Smoke','Main Smoke']):
    page=Metadata(); page.feed(Path(filename).read_text())
    assert page.title == title, (filename, page.title)
    assert page.icons and all(item == icon for item in page.icons), (filename, page.icons)
    assert site in page.og_site, (filename, page.og_site)
    assert title in page.twitter, (filename, page.twitter)
PY
status=$(curl --silent --max-time 15 -o "$tmp_dir/unknown.html" -w '%{http_code}' \
  -H 'Host: unknown.mc520.top' -H 'X-Forwarded-Host: mc.mc520.top' http://127.0.0.1:18932/)
[[ "$status" == 404 ]] || { printf 'unknown storefront returned %s, expected 404\n' "$status" >&2; exit 1; }
! grep -Fq 'Main Smoke' "$tmp_dir/unknown.html"
image_url='http://127.0.0.1:18932/_next/image?url=%2Fimage-source%2Faaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png&w=384&q=75'
for attempt in 1 2; do
  curl --fail --silent --max-time 15 -H 'Host: mc.mc520.top' -H 'Accept: image/webp' \
    -D "$tmp_dir/image-$attempt.headers" -o "$tmp_dir/image-$attempt.webp" "$image_url"
  [[ -s "$tmp_dir/image-$attempt.webp" ]] || { echo 'optimizer returned an empty image' >&2; exit 1; }
  grep -Eiq '^content-type: image/webp' "$tmp_dir/image-$attempt.headers"
  grep -Eiq '^cache-control:.*max-age=31536000' "$tmp_dir/image-$attempt.headers"
done
grep -Eiq '^x-nextjs-cache: HIT' "$tmp_dir/image-2.headers"
curl --fail --silent --max-time 15 -H 'Host: mc.mc520.top' \
  -D "$tmp_dir/live.headers" -o /dev/null \
  'http://127.0.0.1:18932/image-source/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png?live=1'
grep -Eiq '^cache-control:.*no-store' "$tmp_dir/live.headers"
status=$(curl --silent --max-time 15 -o /dev/null -w '%{http_code}' -H 'Host: mc.mc520.top' \
  'http://127.0.0.1:18932/_next/image?url=http%3A%2F%2F127.0.0.1%3A18931%2Fapi%2Fsite%2Fconfig&w=384&q=75')
[[ "$status" == 400 ]] || { printf 'remote optimizer request returned %s, expected 400\n' "$status" >&2; exit 1; }
printf 'WEB_IMAGE_HOST_ROUTING_OK main=200 subsite=200 unknown=404\n'
printf 'WEB_BRANDING_HOST_ROUTING_OK titles=7 icons=7 open_graph=7 twitter=7\n'
printf 'WEB_IMAGE_OPTIMIZER_OK webp=200 repeat=HIT cache=31536000 remote=400\n'
