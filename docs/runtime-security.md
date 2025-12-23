# Цель и модель угроз (кратко)

Запуск приложения под непривилегированным пользователем (UID/GID `10001`) на read-only корневой ФС минимизирует ущерб при потенциальном RCE: бинарь и конфиги нельзя перезаписать, а процесс не обладает лишними правами. Запись разрешена только в строго ограниченные каталоги (`/tmp`, `/var/cache/app`) для временных файлов и кэша. Дополнительно контейнер стартует без Linux capabilities (`cap-drop=ALL`), что убирает оставшиеся привилегии ядра.

# Docker run пример

```bash
docker run \
  --rm \
  --name app-bot \
  --read-only \
  --cap-drop=ALL \
  --security-opt no-new-privileges \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --tmpfs /var/cache/app:rw,noexec,nosuid,size=64m \
  -p 8080:8080 \
  -e APP_ENV=prod \
  -e QR_SECRET=replace-me \
  <registry>/app-bot:<tag-or-digest>
```

Контейнерный образ по умолчанию запускается под UID/GID `10001`, поэтому флаг `--user` не требуется, если его специально не переопределять. Каталог `/var/cache/app` создаётся в образе с владельцем `10001:10001` и остаётся корректным writable-кэшем даже без `--read-only`/`tmpfs`, но в проде рекомендуем использовать read-only root FS и tmpfs для уменьшения стораджа и атакующей поверхности.

* `--read-only` делает корень ФС доступным только для чтения, защищая дистрибутив в `/opt/app`.
* `--tmpfs /tmp` и `/var/cache/app` дают временные writable-области в RAM для кэша/временных файлов, не нарушая read-only корень.
* `--security-opt no-new-privileges` и `--cap-drop=ALL` убирают повышение привилегий и полностью выключают capabilities.

# Kubernetes: securityContext пример

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: app-bot
spec:
  template:
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
        fsGroupChangePolicy: "OnRootMismatch"
      containers:
        - name: app-bot
          image: <registry>/app-bot@sha256:<digest>
          securityContext:
            readOnlyRootFilesystem: true
            allowPrivilegeEscalation: false
            capabilities:
              drop: ["ALL"]
          volumeMounts:
            - name: tmp
              mountPath: /tmp
            - name: cache
              mountPath: /var/cache/app
      volumes:
        - name: tmp
          emptyDir:
            medium: "Memory"
            sizeLimit: "64Mi"
        - name: cache
          emptyDir:
            medium: "Memory"
            sizeLimit: "128Mi"
```

Корневая ФС контейнера монтируется read-only, запись возможна только в `emptyDir` для `/tmp` и `/var/cache/app`. Capabilities целиком сброшены, повышение привилегий запрещено, процесс запускается под UID/GID `10001`; `runAsUser: 10001` зеркалирует настройки образа и закрепляет инвариант non-root на уровне кластера.

# Привязка к HEALTHCHECK / livenessProbe

В Dockerfile уже настроен `HEALTHCHECK` на HTTP `GET /health` (порт `8080`). В Kubernetes можно использовать тот же endpoint в `livenessProbe`/`readinessProbe`, чтобы повторять текущую схему проверки без изменений бизнес-логики.

# DR и бэкапы

Политика бэкапов/PITR, правила запуска миграций и параметры пула подключений описаны в `docs/dr.md`. Для prod/stage приложение валидирует схему на старте, а миграции запускаются только из CI (`db-migrate` workflow).
