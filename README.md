# MyShop

MyShop 是 Phase 1 的数字商品（卡密）商店。它提供商品详情、立即购买、单订单生命周期页面、后台和主题界面，同时把游客会话归属、支付回调和自动发货收敛到可审计的安全链路。

本版本的实现依据与许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。Orion Key 是主要代码基线，ACG-Faka 仅用于行为和界面设计参考；本仓库不运行 PHP，也不复制 ACG 运行时。

## Phase 1 范围

- 前台：商品/规格、游客或会员立即购买、支付宝/微信支付、单订单支付结果和卡密展示/导出。
- 后台：商品、分类、卡密池、订单、用户、用户等级、站点配置、操作日志和支付渠道管理。
- 定价：零售价/成本价、等级成本加价、等级特殊价、公开个性化报价，以及订单价格快照。
- 支付：可扩展 `PaymentProvider` SPI；本阶段内置 Epay，且只开放支付宝和微信支付。
- 数据：PostgreSQL + Flyway 版本化迁移；非测试环境 `ddl-auto=validate`，不执行 `data.sql`。
- 安全：BCrypt、只在 HttpOnly Cookie 中传输 JWT、CSRF 双提交保护、固定 Caddy 代理 IP 解析，以及游客订单的高熵浏览器会话归属。
- 交付：支付回调提交 `PAID` 后由服务端直接触发独立 fulfillment 事务；按订单行逐池 `FOR UPDATE SKIP LOCKED` 锁定，先检查全部库存，再统一修改，任何一个池缺货都会回滚整笔发货并由 1 分钟补偿任务重试。

商店统一使用人民币（CNY）计价。明确不在 Phase 1：优惠券、Kafka/RabbitMQ/Elasticsearch/Kubernetes、Redis 默认限流和微服务拆分。

## 目录

```text
apps/api/       Spring Boot API、领域服务、Flyway migration 和测试
apps/web/       Next.js storefront/admin
docs/           支付 SPI 和部署 runbook
Caddyfile       Compose 反向代理配置
docker-compose.yml
docker-compose.prod.yml
```

## 本地开发

前端需要 Node.js 22+ 和 pnpm 10；后端需要 Java 25 LTS 和 Maven 3.9+。本地 API 默认连接 `localhost:5432` 的 PostgreSQL，生产配置不能使用默认数据库密码或 JWT 密钥。

```powershell
Copy-Item .env.example .env
# 编辑 .env：至少填写 DB_URL、DB_USERNAME、DB_PASSWORD、JWT_SECRET。

pnpm install --frozen-lockfile
pnpm --filter @myshop/web dev

cd apps/api
mvn spring-boot:run
```

使用 Compose 时，先复制并编辑 `.env`，填写 `SITE_DOMAIN`、`ACME_EMAIL` 和数据库/JWT 配置，然后执行：

```powershell
docker compose up -d --build
docker compose ps
docker compose logs -f api
```

`docker-compose.yml` 是本地源码构建编排；`docker-compose.prod.yml` 是唯一生产
编排，明确禁止 `build:`。无论当前 release 目录是否以 SHA 命名，生产 Compose
都必须使用 canonical project identity `myshop`。生产操作统一从 release 根目录
调用受保护入口：

```bash
bash scripts/myshop-compose.sh --env-file /opt/myshop/.env config
bash scripts/myshop-compose.sh --env-file /opt/myshop/.env pull
bash scripts/myshop-compose.sh --env-file /opt/myshop/.env up -d
bash scripts/myshop-compose.sh --env-file /opt/myshop/.env ps
```

该入口固定 `docker-compose.prod.yml` 和 `--project-directory`，显式设置
`COMPOSE_PROJECT_NAME=myshop`，并拒绝 `build`、`--build`、`latest`、`local`、
非 approved registry 以及 `-p`、`--project-name`、`-f`、`--file`、
`--project-directory` 覆盖。本地或 CI 构建只能直接调用开发 Compose；这不改变
生产规则。生产持久化资源继续属于
`myshop`，包括 `myshop_postgres_data`、Caddy volumes 和 `myshop_internal`。

Compose 默认只有 PostgreSQL、API、Web 和 Caddy：数据库有持久化卷，API/Web 没有宿主机直连端口，公开流量只经过 Caddy。生产服务器初始化、备份、恢复和回滚步骤见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。

## 管理员初始化

应用不会创建默认 `admin/123456`，也不会打印管理员密码。首次启动前可在环境变量中同时设置：

```text
BOOTSTRAP_ADMIN_USERNAME
BOOTSTRAP_ADMIN_EMAIL
BOOTSTRAP_ADMIN_PASSWORD   # 6-72 位 ASCII 可见字符

BOOTSTRAP_SUPER_ADMIN_USERNAME
BOOTSTRAP_SUPER_ADMIN_PASSWORD  # 6-72 位 ASCII 可见字符；仅首次初始化使用
```

启动成功后应立即登录并移除 bootstrap 密码变量。超级管理员创建后不会因环境变量变化而重置密码；所有密码只以 BCrypt 哈希保存。

## 订单访问与发货不变量

游客订单的归属由下单时保存的浏览器 `session_token` 决定，前端在整个浏览器会话中自动通过 `X-Session-Token` 发送；登录订单只允许所属用户访问。订单详情、支付状态刷新、补付与卡密导出统一使用 `GET /orders/{id}`、`POST /orders/{id}/refresh`、`POST /orders/{id}/repay`、`GET /orders/{id}/export`，其中 UUID 只作为内部路由定位符，不能单独授予访问权限。相同 guest session 的同一 `idempotency_key` 重放会返回同一订单；不同 session 或用户会被拒绝。

付款回调会先由对应 Provider 校验签名并归一化为标准状态、金额和交易标识；支持查询的 Provider 还会执行服务端确认，然后在同一事务中提交 `PaymentAttempt=PAID`、订单 `PAID` 和 webhook 幂等事件。明确声明查询接口不可用且允许签名回调的 Provider 才能使用该策略；网络错误、空响应、未付款状态、金额不符或签名错误仍会失败。提交成功后，内部 service API 才启动独立 fulfillment 事务。发货事务会锁定订单，再为每个订单行锁定对应商品/规格的可用卡密池。事务先收集和校验所有数量，随后才把全部卡密标记为 SOLD、绑定订单并把订单改为 DELIVERED；缺货只回滚 fulfillment，不回滚已确认付款；同一订单的并发发货会由订单行锁串行化。

## 支付渠道

每次外部支付都创建独立 `PaymentAttempt`，保存 merchant trade number、渠道/Provider/支付类型快照、敏感配置快照、标准 `next_action` 和状态。重复创建会复用有效的 `PENDING` attempt；重新支付会关闭旧 attempt 并生成新的 merchant trade number。回调永远使用 attempt 快照，因此修改、禁用或软删除渠道不会影响历史交易。订单与支付 API 只传输标准 `PaymentStatus` 和 `PaymentNextAction`。新增 Provider 的接口约定见 [docs/PAYMENT_PROVIDER.md](docs/PAYMENT_PROVIDER.md)。

易支付兼容协议渠道配置只包含 `api_url`（支付网关）、`pid`（商户号 / PID）和 `key`（商户密钥 / KEY）。支付网关填写站点根地址时，服务端返回浏览器 `Form POST` 到 `/submit.php`；明确填写 `/submit.php` 时直接提交到该地址；明确填写 `/mapi.php` 或 `/pay/apisubmit` 时由服务端发起表单 API POST。API 返回 `qrcode` / `qr_code` 一律视为二维码原始内容，由前端本地生成二维码，即使内容本身是 HTTP(S) URL 也不会作为网页跳转或嵌入；`payurl`、`payurl2`、`url`、`pay_action_url` 的有效 HTTP(S) 值返回顶层页面跳转，`urlscheme` 返回 DeepLink。明确配置 API endpoint 却收到 HTML 或非 JSON 时会失败，并提示配置站点根地址或 `submit.php`；服务端支付状态确认会从配置地址去掉三种 endpoint 后访问站点根地址的 `/api.php`。回调地址与支付完成回跳地址由服务端根据 `PUBLIC_SITE_URL` 自动生成；`key` 等敏感字段在后台响应中会被掩码，公开接口不会返回 Provider 配置或密钥。详细的标准动作、快照和 Epay 渠道规则见 [docs/PAYMENT_PROVIDER.md](docs/PAYMENT_PROVIDER.md)。

## 验证命令

```powershell
# 前端
pnpm install --frozen-lockfile
pnpm --filter @myshop/web exec tsc --noEmit
pnpm --filter @myshop/web lint
pnpm --filter @myshop/web build

# 后端单元/应用测试
cd apps/api
mvn -B verify

# PostgreSQL 交付集成测试（需要本机 Docker）
mvn -B -Dtest=PostgresDeliveryIntegrationTest test

# Compose 环境变量和 Caddy 固定代理地址检查（需要 Docker Compose v2）
cd ..\..
bash scripts/check-compose.sh
```

完整的 GitHub Actions CI 只做构建、测试和 Compose 配置 smoke check，不包含任何服务器部署步骤。

## 参考实现与许可证

代码许可保留 Orion Key 的 MIT LICENSE；第三方来源、保留范围和 ACG 的行为参考范围记录在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
