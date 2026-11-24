/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',   // Docker 部署必须：生成独立运行的 server.js，不依赖完整 node_modules
  // Vditor loads these resources only on editor pages. Keep the exact pinned
  // package files in the standalone server rather than depending on a CDN.
  outputFileTracingIncludes: {
    '/vditor-assets/*': [
      './node_modules/vditor/package.json',
      './node_modules/vditor/dist/js/lute/lute.min.js',
      './node_modules/vditor/dist/js/i18n/{zh_CN,en_US}.js',
      './node_modules/vditor/dist/js/icons/ant.js',
      './node_modules/vditor/dist/css/content-theme/{light,dark}.css',
    ],
  },
  images: {
    // Only our public, top-level uploads may be fetched by the optimizer.
    // In particular, never turn arbitrary supplier URLs into server-side fetches.
    localPatterns: [{ pathname: '/image-source/*', search: '' }],
    remotePatterns: [],
    maximumRedirects: 0,
    maximumResponseBody: 25 * 1024 * 1024,
    maximumDiskCacheSize: 256 * 1024 * 1024,
    minimumCacheTTL: 60 * 60 * 24 * 365,
  },
  experimental: {
    imgOptConcurrency: 1,
    imgOptMaxInputPixels: 20_000_000,
    imgOptSequentialRead: true,
  },
  // 开发模式: 将 /api/* 代理到 Spring Boot 后端（含上传文件 /api/uploads/*）
  async rewrites() {
    const backendUrl = process.env.BACKEND_URL || "http://localhost:8083"
    return [
      {
        source: "/api/:path*",
        destination: `${backendUrl}/api/:path*`,
      },
    ]
  },
}

export default nextConfig
