import React from 'react';
import { createRoot } from 'react-dom/client';
import Home from './App';
import './styles/globals.css';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Home />
  </React.StrictMode>,
);
