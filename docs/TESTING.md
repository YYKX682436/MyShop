# 独立回归测试

Web 逻辑测试使用 `pnpm --filter @myshop/web test:logic`。供应商订单、售后和支付恢复的浏览器回归使用构建产物与模拟接口：先运行 `pnpm --filter @myshop/web build`，再通过 `pnpm --filter @myshop/web` 分别执行 `test:supplier-order-ui`、`test:supplier-aftercare-ui`、`test:payment-recovery-ui`。这些脚本自行启动并关闭临时测试服务，不连接真实支付或供应商。

`test:card-ui`、`test:admin-user-sync`、`test:admin-product-list-sync`、`test:admin-refresh-ui` 使用独立测试服务，地址只能是 loopback；通过 `BASE_URL`、`MYSHOP_TEST_ADMIN` 和 `MYSHOP_TEST_PASSWORD` 提供测试地址与凭据。刷新回归还要求隔离数据库中已有分类和商品，并通过 `MYSHOP_TEST_PRODUCT_TITLE` 指定该商品。该测试会创建并清理专门命名的测试商品，不得连接生产数据。

迁移、发货和退款 PostgreSQL 测试默认使用 Docker；也可为每个测试类指定独立的临时 PostgreSQL 16 数据库，例如：

```bash
cd apps/api
MYSHOP_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55439/myshop_test_delivery \
MYSHOP_TEST_POSTGRES_USER=myshop_test mvn -B -Dtest=PostgresDeliveryIntegrationTest test
```

外部测试地址只接受 `127.0.0.1` 上名称以 `myshop_test_` 开头的数据库；迁移链测试会清空测试数据库，其他类也会修改测试数据，必须为每个类使用专门的临时库。
