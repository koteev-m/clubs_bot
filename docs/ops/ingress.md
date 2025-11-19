# Ingress header buffers for Telegram Mini App

Для заголовка `X-Telegram-Init-Data` иногда нужны расширенные буферы:

```nginx
# nginx.ingress.kubernetes.io/server-snippet: |
large_client_header_buffers 8 32k;
proxy_buffer_size 32k;
```

Для ingress-nginx аннотации могут отличаться в зависимости от версии.

(Если у вас Helm/Ingress уже описан — просто сослаться на актуальный способ включения параметров.)
