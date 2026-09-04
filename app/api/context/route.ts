import tzlookup from 'tz-lookup';

type WeatherResponse = {
  timezone?: unknown;
  timezone_abbreviation?: unknown;
  utc_offset_seconds?: unknown;
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
};

type MetNorwayResponse = {
  properties?: {
    timeseries?: Array<{
      time?: unknown;
      data?: {
        instant?: { details?: Record<string, unknown> };
        next_1_hours?: {
          details?: Record<string, unknown>;
          summary?: { symbol_code?: unknown };
        };
      };
    }>;
  };
};

type NormalizedWeather = {
  timezone: string;
  timezoneAbbreviation: string | null;
  utcOffsetSeconds: number | null;
  observedAt: string | null;
  weather: {
    temperature: number | null;
    apparentTemperature: number | null;
    humidity: number | null;
    precipitation: number | null;
    weatherCode: number | null;
    windSpeed: number | null;
    windDirection: number | null;
  };
  source: string;
};

type ReverseResponse = {
  features?: Array<{
    properties?: Record<string, unknown>;
    geometry?: { coordinates?: unknown };
  }>;
};

function numberValue(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function textValue(value: unknown) {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function locationName(data: ReverseResponse | null) {
  const properties = data?.features?.[0]?.properties;
  if (!properties) return null;
  const parts = [
    textValue(properties.state),
    textValue(properties.city) || textValue(properties.county),
    textValue(properties.district) || textValue(properties.locality),
  ].filter((part, index, values): part is string => Boolean(part) && values.indexOf(part) === index);
  return parts.length ? parts.join(' · ') : null;
}

async function geocodeCity(city: string) {
  const geocodeUrl = new URL('https://photon.komoot.io/api');
  geocodeUrl.searchParams.set('q', city);
  geocodeUrl.searchParams.set('limit', '1');
  const response = await fetch(geocodeUrl, { headers: { Accept: 'application/json', 'User-Agent': 'NongxinAgent/1.0' } });
  if (!response.ok) throw new Error('城市查询服务暂时不可用');
  const data = await response.json() as ReverseResponse;
  const coordinates = data.features?.[0]?.geometry?.coordinates;
  if (!Array.isArray(coordinates) || coordinates.length < 2) throw new Error(`找不到“${city}”，请填写更完整的城市名称`);
  const longitude = Number(coordinates[0]);
  const latitude = Number(coordinates[1]);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) throw new Error('城市坐标无效');
  return { latitude, longitude, name: city };
}

async function fetchOpenMeteo(latitude: number, longitude: number): Promise<NormalizedWeather> {
  const weatherUrl = new URL('https://api.open-meteo.com/v1/forecast');
  weatherUrl.searchParams.set('latitude', latitude.toFixed(5));
  weatherUrl.searchParams.set('longitude', longitude.toFixed(5));
  weatherUrl.searchParams.set('current', 'temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m');
  weatherUrl.searchParams.set('timezone', 'auto');
  weatherUrl.searchParams.set('forecast_days', '1');

  const response = await fetch(weatherUrl, { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`Open-Meteo ${response.status}`);
  const data = await response.json() as WeatherResponse;
  const current = data.current;
  if (!current || numberValue(current.temperature_2m) === null) throw new Error('Open-Meteo returned no current weather');

  return {
    timezone: textValue(data.timezone) || tzlookup(latitude, longitude),
    timezoneAbbreviation: textValue(data.timezone_abbreviation),
    utcOffsetSeconds: numberValue(data.utc_offset_seconds),
    observedAt: textValue(current.time),
    weather: {
      temperature: numberValue(current.temperature_2m),
      apparentTemperature: numberValue(current.apparent_temperature),
      humidity: numberValue(current.relative_humidity_2m),
      precipitation: numberValue(current.precipitation),
      weatherCode: numberValue(current.weather_code),
      windSpeed: numberValue(current.wind_speed_10m),
      windDirection: numberValue(current.wind_direction_10m),
    },
    source: 'Open-Meteo',
  };
}

function metSymbolToWeatherCode(symbol: string | null) {
  if (!symbol) return null;
  if (symbol.startsWith('clearsky')) return 0;
  if (symbol.startsWith('fair')) return 1;
  if (symbol.startsWith('partlycloudy')) return 2;
  if (symbol.startsWith('cloudy')) return 3;
  if (symbol.includes('fog')) return 45;
  if (symbol.includes('thunder')) return 95;
  if (symbol.includes('heavysnow')) return 75;
  if (symbol.includes('snow')) return 73;
  if (symbol.includes('sleet')) return 67;
  if (symbol.includes('heavyrain')) return 65;
  if (symbol.includes('rain')) return 63;
  return null;
}

async function fetchMetNorway(latitude: number, longitude: number): Promise<NormalizedWeather> {
  const weatherUrl = new URL('https://api.met.no/weatherapi/locationforecast/2.0/compact');
  weatherUrl.searchParams.set('lat', latitude.toFixed(5));
  weatherUrl.searchParams.set('lon', longitude.toFixed(5));
  const response = await fetch(weatherUrl, {
    headers: {
      Accept: 'application/json',
      'User-Agent': 'NongxinAgent/1.0 github.com/zhang-zi-xu/aic',
    },
  });
  if (!response.ok) throw new Error(`MET Norway ${response.status}`);
  const data = await response.json() as MetNorwayResponse;
  const first = data.properties?.timeseries?.[0];
  const details = first?.data?.instant?.details;
  const temperature = numberValue(details?.air_temperature);
  if (temperature === null) throw new Error('MET Norway returned no current weather');
  const windSpeedMs = numberValue(details?.wind_speed);

  return {
    timezone: tzlookup(latitude, longitude),
    timezoneAbbreviation: null,
    utcOffsetSeconds: null,
    observedAt: textValue(first?.time),
    weather: {
      temperature,
      apparentTemperature: null,
      humidity: numberValue(details?.relative_humidity),
      precipitation: numberValue(first?.data?.next_1_hours?.details?.precipitation_amount),
      weatherCode: metSymbolToWeatherCode(textValue(first?.data?.next_1_hours?.summary?.symbol_code)),
      windSpeed: windSpeedMs === null ? null : Math.round(windSpeedMs * 36) / 10,
      windDirection: numberValue(details?.wind_from_direction),
    },
    source: 'MET Norway',
  };
}

async function fetchWeather(latitude: number, longitude: number) {
  const errors: string[] = [];
  // MET Norway is used first because it is reachable from the production Worker.
  // Open-Meteo remains as a real-data fallback rather than a fabricated default.
  for (const provider of [fetchMetNorway, fetchOpenMeteo]) {
    try {
      return await provider(latitude, longitude);
    } catch (error) {
      errors.push(error instanceof Error ? error.message : 'unknown weather error');
    }
  }
  console.error('All weather providers failed:', errors.join(' | '));
  throw new Error('所有天气服务均暂时不可用');
}

export async function GET(request: Request) {
  const url = new URL(request.url);
  const latitudeParam = url.searchParams.get('lat');
  const longitudeParam = url.searchParams.get('lon');
  let latitude = Number(latitudeParam);
  let longitude = Number(longitudeParam);
  let locationHint: string | null = null;
  const city = url.searchParams.get('city')?.trim().slice(0, 80) || '';
  const validCoordinates = latitudeParam !== null && longitudeParam !== null && Number.isFinite(latitude) && latitude >= -90 && latitude <= 90 && Number.isFinite(longitude) && longitude >= -180 && longitude <= 180;
  if (!validCoordinates) {
    if (!city) return Response.json({ error: '请提供定位坐标或城市名称' }, { status: 400 });
    try {
      const geocoded = await geocodeCity(city);
      latitude = geocoded.latitude;
      longitude = geocoded.longitude;
      locationHint = geocoded.name;
    } catch (error) {
      return Response.json({ error: error instanceof Error ? error.message : '城市查询失败' }, { status: 502 });
    }
  }

  const lat = latitude.toFixed(5);
  const lon = longitude.toFixed(5);
  const reverseUrl = new URL('https://photon.komoot.io/reverse');
  reverseUrl.searchParams.set('lat', lat);
  reverseUrl.searchParams.set('lon', lon);

  try {
    const [weatherResult, reverseResult] = await Promise.allSettled([
      fetchWeather(latitude, longitude),
      fetch(reverseUrl, {
        headers: {
          Accept: 'application/json',
          'User-Agent': 'NongxinAgent/1.0',
        },
      }),
    ]);

    if (weatherResult.status !== 'fulfilled') {
      return Response.json({ error: '天气服务暂时不可用' }, { status: 502 });
    }
    const reverse = reverseResult.status === 'fulfilled' && reverseResult.value.ok
      ? await reverseResult.value.json() as ReverseResponse
      : null;
    const weather = weatherResult.value;

    return Response.json({
      location: locationHint || locationName(reverse),
      latitude,
      longitude,
      timezone: weather.timezone,
      timezoneAbbreviation: weather.timezoneAbbreviation,
      utcOffsetSeconds: weather.utcOffsetSeconds,
      observedAt: weather.observedAt,
      weather: weather.weather,
      sources: { weather: weather.source, location: reverse ? 'Photon / OpenStreetMap' : null },
    });
  } catch {
    return Response.json({ error: '实时环境信息获取失败' }, { status: 502 });
  }
}
