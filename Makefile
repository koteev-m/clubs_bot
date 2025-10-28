SHELL := /bin/bash

.PHONY: up down rebuild logs ps tail health psql smoke

up:
	@docker compose up -d --build

down:
	@docker compose down -v

rebuild:
	@docker compose build --no-cache app

logs:
	@docker compose logs -f --tail=200 app

ps:
	@docker compose ps

tail:
	@docker compose logs -f app postgres

health:
	@code=$$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/health); \
if [ "$$code" = "200" ]; then echo "OK"; else echo "FAIL ($$code)"; exit 1; fi

psql:
	@docker exec -it bot_postgres psql -U botuser -d botdb

smoke:
	@chmod +x scripts/smoke.sh
	@APP_IMAGE=app-bot:smoke APP_PORT=8080 ./scripts/smoke.sh
