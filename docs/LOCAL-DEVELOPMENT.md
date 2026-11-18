# Mac 本地预览

需要 Node.js 22+、pnpm 10、Java 25、Maven 和 PostgreSQL 16。首次准备：

```bash
brew install postgresql@16
pnpm install --frozen-lockfile
```

在仓库目录启动：

```bash
bash scripts/dev-local.sh
```

打开 http://localhost:3000；前端修改会自动刷新。在运行命令的终端按 `Ctrl+C` 停止 Web、API 和 PostgreSQL。脚本只使用本机 3000、8083、55432 端口，端口被占用时会退出，不停止其他程序。

首次运行会初始化独立数据库、创建演示分类和商品，以及本地管理员 `muchen-admin` / `LocalPreview123!`。本地数据和日志保存在 `~/Library/Application Support/MyShop/local-dev/`，停止后保留。测试数据库不包含生产数据，默认没有支付渠道；不要填入真实支付凭据。

正常提交和推送不会运行 Actions。正式发布由 Codex 在你明确要求后手动运行 GitHub Actions，生成固定 SHA 镜像和发布清单，再按共享服务器部署流程执行。共享服务器说明见 [DEPLOYMENT-SHARED-SERVER.md](DEPLOYMENT-SHARED-SERVER.md)。
