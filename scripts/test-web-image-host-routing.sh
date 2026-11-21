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

node >"$tmp_dir/backend.log" 2>&1 <<'NODE' &
const http = require('node:http')
http.createServer((req, res) => {
  const names = { 'mc.mc520.top': 'Main Smoke', 'shop.mc520.top': 'Subsite Smoke' }
  const site = names[req.headers.host]
  if (!site) { res.writeHead(404); res.end(); return }
  if (req.url === '/api/uploads/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png') {
    const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEUlEQVR4nGP4z8DQAMIMMAYAOOgF/REzMMkAAAAASUVORK5CYII=', 'base64')
    res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=31536000, immutable' })
    res.end(image); return
  }
  res.setHeader('Content-Type', 'application/json')
  const price = req.headers.host === 'mc.mc520.top' ? 19.25 : 27.50
  const data = req.url.startsWith('/api/site/config') ? { site_name: site, contact_items: [] }
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
printf 'WEB_IMAGE_OPTIMIZER_OK webp=200 repeat=HIT cache=31536000 remote=400\n'
