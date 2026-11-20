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

货源账号的密钥使用 AES-GCM 保存。需要使用货源管理时，启动前设置环境变量 `SUPPLIER_MASTER_KEY`，值为随机生成的 32 字节密钥的 Base64 编码；该值应保存在仓库外的私有环境配置中，并在后续启动时保持一致。缺少或更换此值会导致货源密钥无法保存或解密，但不影响本地商品和支付。导入的货源商品在采购功能验收前保持下架。

货源自动采购使用 `SUPPLIER_PURCHASE_ENABLED` 开关，默认关闭；本地模拟测试单独开启该开关，并使用模拟供应商，不能配置真实采购账号。采购回调地址由 `PUBLIC_SITE_URL` 生成，需要可公开访问的 HTTPS 地址。原 P1 商品的本地成本需要先执行一次货源同步，才会按发货倍数更新为每份顾客购买量对应的成本。图片、级联充值字段及无法安全执行的正则模板暂不开放销售；供应商退款仍需人工核实。

货源订单的移动端表单、订单进度和后台查询按钮可以使用纯本地模拟预览：先运行 `pnpm --filter @myshop/web build`，再运行 `pnpm --filter @myshop/web test:supplier-order-ui`。测试会启动并关闭临时本地服务，拦截订单请求，不连接真实支付或供应商。

卡券浏览器回归可在本地预览运行时，另开终端执行：

```bash
pnpm --filter @myshop/web test:card-ui
```

该测试使用本地预览管理员登录，并拦截测试商品、导入和订单接口，不会写入卡券库存。需要本机 Chrome 或 Playwright Chromium。

迁移、发货和退款的 PostgreSQL 测试默认使用 Docker；也可以分别为各测试类指定独立的临时 PostgreSQL 16 数据库，例如：

```bash
cd apps/api
MYSHOP_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55439/myshop_test_delivery \
MYSHOP_TEST_POSTGRES_USER=myshop_test mvn -B -Dtest=PostgresDeliveryIntegrationTest test
```

外部测试地址只接受 `127.0.0.1` 上名称以 `myshop_test_` 开头的数据库；迁移链测试会清空其测试数据库，其他类也会修改测试数据，必须为每个测试类使用专门的临时库。
