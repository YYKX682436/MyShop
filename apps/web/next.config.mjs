/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',   // Docker 部署必须：生成独立运行的 server.js，不依赖完整 node_modules
  images: {
    unoptimized: true,
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
