import axios from 'axios';

let initDataHeader = '';

/** Sets raw initData to be attached to every request. */
export function setInitData(value: string) {
  initDataHeader = value;
}

/** Axios instance with Telegram initData header and trace id. */
export const http = axios.create();

http.interceptors.request.use((config) => {
  if (initDataHeader) {
    config.headers['X-Telegram-InitData'] = initDataHeader;
  }
  config.headers['X-Trace-Id'] = Math.random().toString(36).slice(2);
  return config;
});
