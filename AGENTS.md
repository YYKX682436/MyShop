# MyShop repository guidance

## Production operations

- Production SSH alias: `myshop-prod`
- Connect: `ssh myshop-prod`
- Application directory: `/opt/myshop`
- Compose project: `myshop`
- Health: `cd /opt/myshop && docker compose -p myshop ps`
- Logs: `cd /opt/myshop && docker compose -p myshop logs --tail=200`
- Before every production deploy: back up the database, deploy only an accepted SHA, and never delete Docker volumes.
- Never commit `.env`, SSH private keys, database dumps, payment secrets, card keys, or guest lookup tokens.
