import { useState } from 'react';
import { CloudSun, LocateFixed, MapPin, RefreshCw, X } from 'lucide-react';
import type { LiveContext } from '@/types';
import { weatherLabel } from '@/lib/api';

export function ContextPanel({ context, now, busy, error, onLocate, onSearch, onClear }: {
  context: LiveContext | null; now: Date; busy: boolean; error: string;
  onLocate: () => void; onSearch: (city: string) => void; onClear: () => void;
}) {
  const [expanded, setExpanded] = useState(false); const [city, setCity] = useState(''); const [days, setDays] = useState(false);
  const w = context?.weather;
  const number = (n: number | null | undefined, unit: string) => n == null ? '—' : `${Math.round(n * 10) / 10}${unit}`;
  return <section className="nx-weather" aria-label="位置、天气与时间">
    <div className="nx-weather-title"><span><CloudSun size={16} />当地天气</span><button className="nx-icon-button" title="设置天气位置" aria-label="设置天气位置" onClick={() => setExpanded(!expanded)}><MapPin size={15} /></button></div>
    {context ? <>
      <div className="nx-weather-current"><strong>{number(w?.temperature, '°')}</strong><div><b>{w ? weatherLabel(w.weatherCode) : '天气暂不可用'}</b><span>{context.location}</span></div></div>
      <div className="nx-weather-meta"><span>湿度 {number(w?.humidity, '%')}</span><span>风速 {number(w?.windSpeed, ' km/h')}</span></div>
      <p className="nx-weather-source">{context.method === 'manual' ? '手动城市 · 非田块定位' : `设备定位${context.accuracy ? ` · 精度约 ${Math.round(context.accuracy)} 米` : ''}`}</p>
      {w && <p className="nx-weather-source">{context.sources.weather || '天气接口'} · {context.observedAt?.replace('T', ' ') || '更新时间未提供'}</p>}
      {context.weatherError && <p role="status" className="nx-weather-error">{context.weatherError}</p>}
      {context.daily.length > 0 && <button className="nx-text-button" onClick={() => setDays(!days)}>{days ? '收起预报' : '查看未来天气'} →</button>}
      {days && <div className="nx-weather-days">{context.daily.map(day => <div key={day.date}><span>{day.date.slice(5)}</span><span>{weatherLabel(day.weatherCode)}</span><b>{number(day.tempMin, '')} / {number(day.tempMax, '°')}</b></div>)}</div>}
    </> : <div className="nx-weather-empty"><p>还未选择位置</p><button onClick={() => setExpanded(true)}>选择城市，获取真实天气 →</button><button type="button" disabled={busy} onClick={onLocate}><LocateFixed size={13} />{busy ? '正在定位…' : '使用当前位置'}</button></div>}
    {expanded && <form className="nx-weather-form" onSubmit={e => { e.preventDefault(); if (city.trim()) onSearch(city.trim()); }}>
      <label className="sr-only" htmlFor="weather-city">天气城市</label><div><input id="weather-city" value={city} maxLength={80} placeholder="城市，例如：杭州市" onChange={e => setCity(e.target.value)} /><button type="submit" disabled={busy || !city.trim()} aria-label="查询天气"><RefreshCw size={15} className={busy ? 'spin' : ''} /></button></div>
      <button type="button" disabled={busy} className="nx-text-button" onClick={onLocate}><LocateFixed size={13} />{busy ? '正在获取…' : '使用设备位置'}</button>
      {context && <button type="button" className="nx-text-button" disabled={busy} onClick={onClear}><X size={13} />清除本次位置</button>}
      <p className="nx-weather-source">定位需授权；城市查询无需位置权限。位置仅用于本次天气和对话上下文。</p>
    </form>}
    {error && <p className="nx-weather-error" role="alert">{error}</p>}
    <div className="nx-clock"><span>{now.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' })}</span><time>{now.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })}</time></div>
    <p className="nx-weather-source">本机时间 · {Intl.DateTimeFormat().resolvedOptions().timeZone}</p>
  </section>;
}
