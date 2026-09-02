# MyShop 项目指令

## 生产环境操作
- 后端连接命令：`ssh myshop-prod`
- 验收账号通过安全渠道提供，密码不得写入仓库、日志或文档。
- 不需要保留任何兼容旧版的东西，我们目前是新开发阶段，无任何用户

## 修改与验收
- 修改完成必须进行消融实验
- 部署代码，推送main
- 验收遇到持续阻碍允许跳过

## Docker 版本与数据盘清理
- 生产环境只保留当前运行版本和之前两个可回滚版本，共三个版本；版本以
  `/opt/myshop/releases` 中的完整 40 位 SHA 目录以及对应的
  `myshop-api:<SHA>`、`myshop-web:<SHA>` 镜像为准。
- 每次生产部署完成且 API/Web 健康检查通过后，必须执行
  `bash /opt/myshop/scripts/cleanup-docker-retention.sh`；定时任务也会执行同一脚本
  作为兜底。
- 清理范围仅包括未使用的 Docker 镜像、BuildKit/containerd 构建缓存和三个版本之外的
  发布目录。默认不保留长期构建缓存，避免快速迭代持续占满 `/data`。
- 严禁通过 `docker volume prune` 或 `docker system prune --volumes` 清理；不得删除任何
  Docker volume，尤其是 `myshop_postgres_data`、`myshop_caddy_data`、
  `myshop_caddy_config`，也不得删除 `/data/myshop/uploads` 或 `/data/myshop/backups`。
