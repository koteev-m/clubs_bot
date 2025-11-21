# Edge / Ingress рекомендации

## Caddy
```caddyfile
{
  # глобально включаем компрессию
  encode zstd gzip
}

your.domain.com {
  reverse_proxy 127.0.0.1:8080

  # Безопасные заголовки (дубли на уровне edge)
  header {
    # HSTS только в stage/prod
    Strict-Transport-Security "max-age=31536000; includeSubDomains"
    X-Content-Type-Options "nosniff"
    Referrer-Policy "no-referrer"
    Permissions-Policy "camera=(), microphone=(), geolocation=()"
    -Server
  }

  @webapp path /webapp/entry/*
  header @webapp Cache-Control "public, max-age=31536000, immutable"
}
```

## Nginx (standalone)
```nginx
server {
  listen 443 ssl http2;
  server_name your.domain.com;

  # Буферы для длинных заголовков (Telegram init-data)
  large_client_header_buffers 8 32k;

  # Компрессия
  gzip on;
  gzip_types text/plain text/css application/javascript application/json application/xml;
  # brotli если доступен модуль:
  # brotli on; brotli_comp_level 5; brotli_types text/plain text/css application/javascript application/json application/xml;

  location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;

    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "no-referrer" always;
    add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
  }

  location /webapp/entry/ {
    proxy_pass http://127.0.0.1:8080;
    add_header Cache-Control "public, max-age=31536000, immutable" always;
  }
}
```

## Kubernetes NGINX Ingress (аннотации)
```yaml
metadata:
  annotations:
    nginx.ingress.kubernetes.io/proxy-buffer-size: "32k"
    nginx.ingress.kubernetes.io/proxy-buffers-number: "8"
    nginx.ingress.kubernetes.io/enable-brotli: "true"
    nginx.ingress.kubernetes.io/proxy-hide-headers: "Server"
    nginx.ingress.kubernetes.io/configuration-snippet: |
      add_header X-Content-Type-Options "nosniff" always;
      add_header Referrer-Policy "no-referrer" always;
      add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;
      add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
      if ($request_uri ~* "^/webapp/entry/") {
        add_header Cache-Control "public, max-age=31536000, immutable" always;
      }
```
