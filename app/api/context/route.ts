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
  return { latitude, longitude, name: locationName(data) || city };
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
  const weatherUrl = new URL('https://api.open-meteo.com/v1/forecast');
  weatherUrl.searchParams.set('latitude', lat);
  weatherUrl.searchParams.set('longitude', lon);
  weatherUrl.searchParams.set('current', 'temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m');
  weatherUrl.searchParams.set('timezone', 'auto');
  weatherUrl.searchParams.set('forecast_days', '1');

  const reverseUrl = new URL('https://photon.komoot.io/reverse');
  reverseUrl.searchParams.set('lat', lat);
  reverseUrl.searchParams.set('lon', lon);

  try {
    const [weatherResult, reverseResult] = await Promise.allSettled([
      fetch(weatherUrl, { headers: { Accept: 'application/json' } }),
      fetch(reverseUrl, {
        headers: {
          Accept: 'application/json',
          'User-Agent': 'NongxinAgent/1.0',
        },
      }),
    ]);

    if (weatherResult.status !== 'fulfilled' || !weatherResult.value.ok) {
      return Response.json({ error: '天气服务暂时不可用' }, { status: 502 });
    }
    const weather = await weatherResult.value.json() as WeatherResponse;
    const reverse = reverseResult.status === 'fulfilled' && reverseResult.value.ok
      ? await reverseResult.value.json() as ReverseResponse
      : null;
    const current = weather.current;
    if (!current || numberValue(current.temperature_2m) === null) {
      return Response.json({ error: '天气服务没有返回当前数据' }, { status: 502 });
    }

    return Response.json({
      location: locationName(reverse) || locationHint,
      latitude,
      longitude,
      timezone: textValue(weather.timezone) || 'UTC',
      timezoneAbbreviation: textValue(weather.timezone_abbreviation),
      utcOffsetSeconds: numberValue(weather.utc_offset_seconds),
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
      sources: { weather: 'Open-Meteo', location: reverse ? 'Photon / OpenStreetMap' : null },
    });
  } catch {
    return Response.json({ error: '实时环境信息获取失败' }, { status: 502 });
  }
}
