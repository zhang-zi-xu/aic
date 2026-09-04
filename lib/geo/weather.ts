// Open-Meteo 天气封装：当前天气 + 7 日预报（为七日农事方案提供决策变量）

import tzlookup from 'tz-lookup';

export type CurrentWeather = {
  temperature: number | null;
  apparentTemperature: number | null;
  humidity: number | null;
  precipitation: number | null;
  weatherCode: number | null;
  windSpeed: number | null;
  windDirection: number | null;
};

export type DailyWeather = {
  date: string; // YYYY-MM-DD
  weatherCode: number | null;
  tempMax: number | null;
  tempMin: number | null;
  precipitationSum: number | null;
  precipitationProbability: number | null;
  windSpeedMax: number | null;
  humidityMax: number | null;
};

export type WeatherBundle = {
  timezone: string;
  timezoneAbbreviation: string | null;
  observedAt: string | null;
  current: CurrentWeather;
  daily: DailyWeather[];
  source: 'MET Norway' | 'Open-Meteo';
};

type OpenMeteoResponse = {
  timezone?: unknown;
  timezone_abbreviation?: unknown;
  current?: {
    time?: unknown;
    temperature_2m?: unknown;
    apparent_temperature?: unknown;
    relative_humidity_2m?: unknown;
    precipitation?: unknown;
    weather_code?: unknown;
    wind_speed_10m?: unknown;
    wind_direction_10m?: unknown;
  };
  daily?: {
    time?: unknown;
    weather_code?: unknown;
    temperature_2m_max?: unknown;
    temperature_2m_min?: unknown;
    precipitation_sum?: unknown;
    precipitation_probability_max?: unknown;
    wind_speed_10m_max?: unknown;
    relative_humidity_2m_max?: unknown;
  };
};

type MetNorwayResponse = {
  properties?: {
    timeseries?: Array<{
      time?: unknown;
      data?: {
        instant?: { details?: Record<string, unknown> };
        next_1_hours?: { details?: Record<string, unknown>; summary?: { symbol_code?: unknown } };
        next_6_hours?: { details?: Record<string, unknown>; summary?: { symbol_code?: unknown } };
      };
    }>;
  };
};

function num(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

const WEATHER_CODES: Record<number, string> = {
  0: '晴', 1: '大部晴', 2: '少云', 3: '阴', 45: '雾', 48: '雾凇',
  51: '毛毛雨', 53: '毛毛雨', 55: '毛毛雨', 56: '冻毛毛雨', 57: '冻毛毛雨',
  61: '小雨', 63: '中雨', 65: '大雨', 66: '冻雨', 67: '冻雨',
  71: '小雪', 73: '中雪', 75: '大雪', 77: '雪粒',
  80: '阵雨', 81: '阵雨', 82: '强阵雨', 85: '阵雪', 86: '强阵雪',
  95: '雷暴', 96: '雷暴伴冰雹', 99: '雷暴伴冰雹',
};

export function weatherText(code: number | null) {
  if (code === null) return '未知';
  return WEATHER_CODES[code] ?? `代码${code}`;
}

const BASE = 'https://api.open-meteo.com/v1/forecast';

async function fetchOpenMeteo(lat: number, lon: number, days: 1 | 7): Promise<WeatherBundle> {
  const url = new URL(BASE);
  url.searchParams.set('latitude', lat.toFixed(4));
  url.searchParams.set('longitude', lon.toFixed(4));
  url.searchParams.set('current', 'temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m');
  url.searchParams.set('timezone', 'auto');
  url.searchParams.set('forecast_days', String(days));
  if (days > 1) {
    url.searchParams.set('daily', 'weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,relative_humidity_2m_max');
  }

  // 超时保护：8 秒（Worker 环境节流）
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch(url.toString(), {
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`天气服务返回 ${response.status}`);
    const data = (await response.json()) as OpenMeteoResponse;
    const current = data.current;
    if (!current || num(current.temperature_2m) === null) {
      throw new Error('天气服务没有返回当前数据');
    }
    const dailyList: DailyWeather[] = [];
    const times = arrayOf(data.daily?.time);
    times.forEach((time, index) => {
      dailyList.push({
        date: typeof time === 'string' ? time : '',
        weatherCode: num(arrayOf(data.daily?.weather_code)[index]),
        tempMax: num(arrayOf(data.daily?.temperature_2m_max)[index]),
        tempMin: num(arrayOf(data.daily?.temperature_2m_min)[index]),
        precipitationSum: num(arrayOf(data.daily?.precipitation_sum)[index]),
        precipitationProbability: num(arrayOf(data.daily?.precipitation_probability_max)[index]),
        windSpeedMax: num(arrayOf(data.daily?.wind_speed_10m_max)[index]),
        humidityMax: num(arrayOf(data.daily?.relative_humidity_2m_max)[index]),
      });
    });
    return {
      timezone: textOf(data.timezone) || 'UTC',
      timezoneAbbreviation: textOf(data.timezone_abbreviation),
      observedAt: textOf(current.time),
      current: {
        temperature: num(current.temperature_2m),
        apparentTemperature: num(current.apparent_temperature),
        humidity: num(current.relative_humidity_2m),
        precipitation: num(current.precipitation),
        weatherCode: num(current.weather_code),
        windSpeed: num(current.wind_speed_10m),
        windDirection: num(current.wind_direction_10m),
      },
      daily: dailyList,
      source: 'Open-Meteo',
    };
  } finally {
    clearTimeout(timer);
  }
}

function metSymbolCode(symbol: unknown) {
  const value = typeof symbol === 'string' ? symbol : '';
  if (value.startsWith('clearsky')) return 0;
  if (value.startsWith('fair')) return 1;
  if (value.startsWith('partlycloudy')) return 2;
  if (value.startsWith('cloudy')) return 3;
  if (value.includes('fog')) return 45;
  if (value.includes('thunder')) return 95;
  if (value.includes('heavysnow')) return 75;
  if (value.includes('snow')) return 73;
  if (value.includes('sleet')) return 67;
  if (value.includes('heavyrain')) return 65;
  if (value.includes('rain')) return 63;
  return null;
}

function localDate(iso: string, timezone: string) {
  try {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: timezone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(new Date(iso));
    const get = (type: string) => parts.find((part) => part.type === type)?.value;
    const year = get('year');
    const month = get('month');
    const day = get('day');
    return year && month && day ? `${year}-${month}-${day}` : '';
  } catch {
    return '';
  }
}

function weatherSeverity(code: number | null) {
  if (code === null) return 0;
  if (code >= 95) return 8;
  if ([65, 67, 75, 82, 86].includes(code)) return 7;
  if ([63, 66, 73, 81, 85].includes(code)) return 6;
  if ([61, 71, 80].includes(code)) return 5;
  if ([45, 48].includes(code)) return 4;
  if (code === 3) return 3;
  if (code === 2) return 2;
  return 1;
}

async function fetchMetNorway(lat: number, lon: number, days: 1 | 7): Promise<WeatherBundle> {
  const url = new URL('https://api.met.no/weatherapi/locationforecast/2.0/compact');
  url.searchParams.set('lat', lat.toFixed(4));
  url.searchParams.set('lon', lon.toFixed(4));
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch(url, {
      headers: { Accept: 'application/json', 'User-Agent': 'NongxinAgent/1.0 github.com/zhang-zi-xu/aic' },
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`天气服务返回 ${response.status}`);
    const data = await response.json() as MetNorwayResponse;
    const points = data.properties?.timeseries ?? [];
    const first = points[0];
    const firstDetails = first?.data?.instant?.details;
    const temperature = num(firstDetails?.air_temperature);
    if (!first || temperature === null) throw new Error('天气服务没有返回当前数据');

    const timezone = tzlookup(lat, lon);
    const dailyMap = new Map<string, DailyWeather>();
    for (const point of points) {
      const time = textOf(point.time);
      if (!time) continue;
      const date = localDate(time, timezone);
      if (!date) continue;
      const details = point.data?.instant?.details;
      const temp = num(details?.air_temperature);
      const humidity = num(details?.relative_humidity);
      const windMs = num(details?.wind_speed);
      const windKmh = windMs === null ? null : Math.round(windMs * 36) / 10;
      const next = point.data?.next_1_hours ?? point.data?.next_6_hours;
      const precipitation = num(next?.details?.precipitation_amount);
      const code = metSymbolCode(next?.summary?.symbol_code);
      const current = dailyMap.get(date) ?? {
        date,
        weatherCode: code,
        tempMax: temp,
        tempMin: temp,
        precipitationSum: 0,
        precipitationProbability: null,
        windSpeedMax: windKmh,
        humidityMax: humidity,
      };
      if (temp !== null) {
        current.tempMax = current.tempMax === null ? temp : Math.max(current.tempMax, temp);
        current.tempMin = current.tempMin === null ? temp : Math.min(current.tempMin, temp);
      }
      if (precipitation !== null) current.precipitationSum = (current.precipitationSum ?? 0) + precipitation;
      if (windKmh !== null) current.windSpeedMax = current.windSpeedMax === null ? windKmh : Math.max(current.windSpeedMax, windKmh);
      if (humidity !== null) current.humidityMax = current.humidityMax === null ? humidity : Math.max(current.humidityMax, humidity);
      if (weatherSeverity(code) > weatherSeverity(current.weatherCode)) current.weatherCode = code;
      dailyMap.set(date, current);
    }

    const firstWindMs = num(firstDetails?.wind_speed);
    const firstNext = first.data?.next_1_hours ?? first.data?.next_6_hours;
    return {
      timezone,
      timezoneAbbreviation: null,
      observedAt: textOf(first.time),
      current: {
        temperature,
        apparentTemperature: null,
        humidity: num(firstDetails?.relative_humidity),
        precipitation: num(firstNext?.details?.precipitation_amount),
        weatherCode: metSymbolCode(firstNext?.summary?.symbol_code),
        windSpeed: firstWindMs === null ? null : Math.round(firstWindMs * 36) / 10,
        windDirection: num(firstDetails?.wind_from_direction),
      },
      daily: [...dailyMap.values()].slice(0, days),
      source: 'MET Norway',
    };
  } finally {
    clearTimeout(timer);
  }
}

/** 生产环境优先使用可达的 MET Norway，失败时回退 Open-Meteo。 */
export async function fetchWeather(lat: number, lon: number, days: 1 | 7 = 1): Promise<WeatherBundle> {
  const errors: string[] = [];
  for (const provider of [fetchMetNorway, fetchOpenMeteo]) {
    try {
      return await provider(lat, lon, days);
    } catch (error) {
      errors.push(error instanceof Error ? error.message : '未知天气错误');
    }
  }
  console.error('All weather providers failed:', errors.join(' | '));
  throw new Error('天气服务暂时不可用');
}

function arrayOf(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function textOf(value: unknown) {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

/** 生成 7 日预报的文字描述（供 agent 上下文使用） */
export function describeDaily(daily: DailyWeather[]): string {
  return daily
    .filter((d) => d.date)
    .map((d) => {
      const parts = [
        `${d.date} ${weatherText(d.weatherCode)}`,
        `最高${d.tempMax ?? '--'}°C/最低${d.tempMin ?? '--'}°C`,
        d.precipitationProbability !== null ? `降水概率${Math.round(d.precipitationProbability)}%` : '',
        d.precipitationSum !== null && d.precipitationSum > 0 ? `降水${d.precipitationSum.toFixed(1)}mm` : '',
        d.windSpeedMax !== null ? `最大风速${d.windSpeedMax.toFixed(0)}km/h` : '',
      ].filter(Boolean);
      return parts.join('，');
    })
    .join('\n');
}
