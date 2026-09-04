'use client';

import { useState } from 'react';
import { ChevronsRight, CloudSun, LocateFixed, MapPin, RefreshCw, Sprout, TriangleAlert } from 'lucide-react';
import type { FieldProfile, LiveContext } from '@/lib/types';
import { getPhenology } from '@/lib/fields/phenology';

const WEEK = ['日', '一', '二', '三', '四', '五', '六'];

export function weatherCodeText(code: number | null) {
  if (code === null) return '未知';
  const map: Record<number, string> = {
    0: '晴', 1: '大部晴', 2: '少云', 3: '阴', 45: '雾', 48: '雾凇',
    51: '毛毛雨', 53: '毛毛雨', 55: '毛毛雨', 56: '冻毛雨', 57: '冻毛雨',
    61: '小雨', 63: '中雨', 65: '大雨', 66: '冻雨', 67: '冻雨',
    71: '小雪', 73: '中雪', 75: '大雪', 77: '雪粒',
    80: '阵雨', 81: '阵雨', 82: '强阵雨', 85: '阵雪', 86: '强阵雪',
    95: '雷暴', 96: '雷暴冰雹', 99: '雷暴冰雹',
  };
  return map[code] ?? `代码${code}`;
}

export function ContextPanel({
  context,
  onLocate,
  locating,
  onPickCity,
  field,
  onCollapse,
}: {
  context: LiveContext | null;
  onLocate: () => void;
  locating: boolean;
  onPickCity: (city: string) => void;
  field: FieldProfile | null;
  onCollapse?: () => void;
}) {
  const [manualCity, setManualCity] = useState('');
  const weather = context?.weather ?? null;
  const fieldPhen = field ? getPhenology(field.crop, field.sowDate) : null;

  const submitCity = (event: { preventDefault: () => void }) => {
    event.preventDefault();
    const city = manualCity.trim();
    if (city && !locating) onPickCity(city);
  };

  return (
    <aside className="context-panel">
      <div className="context-panel-head">
        <span>农情速览</span>
        <span className="context-head-actions">
        {onCollapse && <button onClick={onCollapse} className="field-collapse" aria-label="收起农情栏"><ChevronsRight size={15} /></button>}
        <button onClick={onLocate} disabled={locating} className="context-refresh" aria-label="刷新定位与天气">
          <RefreshCw size={14} className={locating ? 'spin' : ''} />
        </button>
        </span>
      </div>

      <div className="context-block">
        <div className="context-row">
          <span className="context-icon"><MapPin size={16} /></span>
          <div className="context-row-body">
            <small>位置</small>
            <b>{context?.location ?? '未定位'}</b>
            <p>{context && context.weatherError ? '天气暂不可用' : context?.sources.location ? `来源：${context.sources.location}` : '点击右上角刷新，或手动选择城市'}</p>
          </div>
        </div>

        <div className="context-row">
          <span className="context-icon"><CloudSun size={16} /></span>
          <div className="context-row-body">
            <small>当前天气</small>
            <b>{weather ? `${weatherCodeText(weather.weatherCode)} · ${weather.temperature ?? '--'}°C` : context?.weatherError ?? '等待获取'}</b>
            <p>{weather ? [weather.humidity !== null ? `湿度 ${weather.humidity}%` : '', weather.apparentTemperature !== null ? `体感 ${weather.apparentTemperature}°C` : '', weather.windSpeed !== null ? `风速 ${weather.windSpeed}km/h` : ''].filter(Boolean).join(' · ') : (context?.weatherError ?? '定位后自动获取')}</p>
          </div>
        </div>
        {context?.weatherError && (
          <div className="context-warn"><TriangleAlert size={13} />{context.weatherError}，尝试重新获取。</div>
        )}

        {context && context.daily && context.daily.length > 1 && (
          <div className="context-daily">
            <small>未来 7 天</small>
            <div className="daily-row">
              {context.daily.slice(0, 7).map((d) => {
                const dt = new Date(d.date + 'T00:00:00');
                const isToday = dt.toDateString() === new Date().toDateString();
                return (
                  <div key={d.date} className={`daily-cell ${isToday ? 'daily-today' : ''}`}>
                    <span className="daily-day">{isToday ? '今' : `周${WEEK[dt.getDay()]}`}</span>
                    <span className="daily-code">{weatherCodeText(d.weatherCode)}</span>
                    <span className="daily-temp">{d.tempMax !== null ? `${Math.round(d.tempMax)}°` : '--°'}</span>
                    <span className="daily-rain">{d.precipitationProbability !== null && d.precipitationProbability >= 30 ? `${d.precipitationProbability}%` : ''}</span>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>

      {field && (
        <div className="context-block context-field">
          <div className="context-field-head"><span className="context-icon"><Sprout size={16} /></span>
            <div className="context-row-body">
              <small>当前田块</small>
              <b>{field.name}</b>
              <p>{field.crop}{field.variety ? ` · ${field.variety}` : ''} · 播期 {field.sowDate}</p>
            </div>
          </div>
          <p className="field-phenology">{fieldPhen?.note ?? '待推算'}</p>
        </div>
      )}

      <div className="city-picker-block">
        <span className="city-picker-label">手动查询城市或区县</span>
        <form className="city-search" onSubmit={submitCity}>
          <input value={manualCity} onChange={(event) => setManualCity(event.target.value)} placeholder="例如：宿迁市宿豫区" aria-label="城市或区县" autoComplete="address-level2" />
          <button type="submit" disabled={!manualCity.trim() || locating}>查询</button>
        </form>
        <button className="context-locate" onClick={onLocate} disabled={locating}>
          <LocateFixed size={15} />{locating ? '正在获取…' : context ? '重新定位' : '使用我的位置'}
        </button>
      </div>
    </aside>
  );
}
