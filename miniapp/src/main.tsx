import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './app/App';
import { TelegramProvider } from './app/providers/TelegramProvider';
import { ThemeProvider } from './app/providers/ThemeProvider';
import './index.css';

const root = ReactDOM.createRoot(document.getElementById('root') as HTMLElement);
root.render(
  <React.StrictMode>
    <TelegramProvider>
      <ThemeProvider>
        <App />
      </ThemeProvider>
    </TelegramProvider>
  </React.StrictMode>
);
