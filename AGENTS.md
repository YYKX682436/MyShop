# MyShop 项目指令

## 生产环境操作
- 后端连接命令：`ssh myshop-prod`
- 验收账号通过安全渠道提供，密码不得写入仓库、日志或文档。
- 当前处于新开发阶段，可以删除错误设计并进行破坏性重构，不要求旧代码兼容。
- 生产数据、持久卷、secrets、备份和部署安全仍必须按生产标准处理。

## 修改与验收
- 修改完成后必须依次完成：本地验证、push `main`、精确 SHA 的 CI 全绿、部署该精确 SHA，以及生产 smoke/schema/health 验收。
- 生产验收不得因为“没有真实用户”而随意跳过。
- Compose production project 固定为 `myshop`；生产使用 `scripts/myshop-compose.sh`，禁止通过 release 目录名生成新 Compose project。
- 修改完成后进行安全消融实验；详细生产步骤继续维护在 `docs/DEPLOYMENT.md`。
- secrets 不得进入仓库或日志。
