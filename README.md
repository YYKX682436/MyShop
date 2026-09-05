# MyShop

MyShop 是 Phase 1 的数字商品（卡密）商店。它提供商品详情、立即购买、订单查询、后台和主题界面，同时把订单凭证、支付回调和自动发货收敛到可审计的安全链路。

本版本的实现依据与许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。Orion Key 是主要代码基线，ACG-Faka 仅用于行为和界面设计参考；本仓库不运行 PHP，也不复制 ACG 运行时。

## Phase 1 范围

- 前台：商品/规格、游客或会员立即购买、支付宝/微信支付、订单查询和卡密展示/导出。
- 后台：商品、分类、卡密池、订单、用户、用户等级、站点配置、操作日志和支付渠道管理。
- 定价：零售价/成本价、等级成本加价、等级特殊价、公开个性化报价，以及订单价格快照。
- 支付：可扩展 `PaymentProvider` SPI；本阶段内置 Epay，且只开放支付宝和微信支付。
- 数据：PostgreSQL + Flyway 版本化迁移；非测试环境 `ddl-auto=validate`，不执行 `data.sql`。
- 安全：BCrypt、只在 HttpOnly Cookie 中传输 JWT、CSRF 双提交保护、固定 Caddy 代理 IP 解析，以及游客订单随机凭证的 hash + 受控可恢复值。
- 交付：支付回调提交 `PAID` 后由服务端直接触发独立 fulfillment 事务；按订单行逐池 `FOR UPDATE SKIP LOCKED` 锁定，先检查全部库存，再统一修改，任何一个池缺货都会回滚整笔发货并由 1 分钟补偿任务重试。

商店统一使用人民币（CNY）计价。明确不在 Phase 1：优惠券、Kafka/RabbitMQ/Elasticsearch/Kubernetes、Redis 默认限流和微服务拆分。

## 目录

```text
apps/api/       Spring Boot API、领域服务、Flyway migration 和测试
apps/web/       Next.js storefront/admin
docs/           支付 SPI 和部署 runbook
Caddyfile       Compose 反向代理配置
docker-compose.yml
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

## 订单凭证与发货不变量

游客创建订单时，API 返回随机 `lookup_token`，数据库同时保存 SHA-256 摘要和一个用于站长恢复的敏感明文值。常规鉴权仍只比较 hash；明文值不进入普通日志、错误日志、操作日志或后台批量列表，包含它的数据库/备份必须按密码等敏感凭证保护。相同 guest session 的同一 `idempotency_key` 重放会返回同一订单和同一凭证；不同 session/用户会被拒绝。订单号本身不能读取卡密或导出文件。

付款回调只在签名、金额、服务端查询通过后提交 `PAID` 和 webhook 幂等事件；提交成功后，内部 service API 才启动独立 fulfillment 事务。发货事务会锁定订单，再为每个订单行锁定对应商品/规格的可用卡密池。事务先收集和校验所有数量，随后才把全部卡密标记为 SOLD、绑定订单并把订单改为 DELIVERED；缺货只回滚 fulfillment，不回滚已确认付款；同一订单的并发发货会由订单行锁串行化。

## 支付渠道

支付创建、回调验签、精确金额比较、服务端订单查询和 webhook 幂等均使用订单创建时的渠道配置。回调不会因为客户端传来的“成功”而直接改订单状态；重复 `trade_no` 通过唯一 webhook 事件安全返回。新增 provider 的接口约定见 [docs/PAYMENT_PROVIDER.md](docs/PAYMENT_PROVIDER.md)。

易支付兼容协议渠道只需配置 `pid`、`key` 和 `api_url`（可填写网关根地址或完整 `mapi.php` 地址）。回调与支付完成回跳地址由服务端根据 `PUBLIC_SITE_URL` 自动生成；`key` 等敏感字段在后台响应中会被掩码，公开接口不会返回 provider 配置或密钥。

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
