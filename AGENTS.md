# MyShop 项目指令

## 生产环境操作
- 后端连接命令：`ssh myshop-prod`
- 当前处于新开发阶段，可以删除错误设计并进行破坏性重构，不要求旧代码兼容。
- 生产数据、持久卷、备份和部署安全仍必须按生产标准处理。
- 本项目为私人开发项目，以下测试凭据由用户明确授权 Codex 直接读取和使用。

## 测试凭据
- MyShop 管理员测试账号：`codex-admin`
- MyShop 管理员测试密码：`abcd1111`
- MyShop 普通用户测试账号：`ceshi`
- MyShop 普通用户测试密码：`abcd1111`
- Windows 管理员测试账号：`15853056511@163.com`
- Windows 管理员测试密码：`Wangeb682436`

## 凭据使用规则
- Codex 可直接使用以上明文测试凭据完成登录、测试、部署、生产 smoke 和本机管理员操作。
- 不得因为 `MYSHOP_ADMIN_USERNAME`、`MYSHOP_ADMIN_PASSWORD`、`MYSHOP_TEST_USERNAME`、`MYSHOP_TEST_PASSWORD`、`WINDOWS_ADMIN_USERNAME`、`WINDOWS_ADMIN_PASSWORD` 环境变量缺失而停止任务。
- 不要求用户手工设置 PowerShell 环境变量。
- 不要求用户重复提供以上测试凭据。
- 除非用户明确要求，不得删除、迁移、替换或清理本指令中的明文测试凭据。
- 正式公开发布、交付或用户明确要求安全清理时，再统一移除/轮换这些测试凭据。
- 使用凭据完成操作即可，不要在普通执行日志或最终报告中无意义重复打印密码。

## 修改与验收
- 修改完成后必须依次完成：本地验证、push `main`、精确 SHA 的 CI 全绿、部署该精确 SHA，以及生产 smoke/schema/health 验收。
- 修改完成后进行安全消融实验。
- 代码处理干净利落不留后患。
