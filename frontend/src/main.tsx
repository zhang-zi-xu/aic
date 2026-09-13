import React from 'react';
import { createRoot } from 'react-dom/client';
import Home from './App';
import { ErrorBoundary } from './components/ui/error-boundary';
import './styles/globals.css';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <Home />
    </ErrorBoundary>
  </React.StrictMode>,
);
