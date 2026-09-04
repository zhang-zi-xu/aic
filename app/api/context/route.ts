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
  features?: Array<{ properties?: Record<string, unknown> }>;
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

export async function GET(request: Request) {
  const url = new URL(request.url);
  const latitude = Number(url.searchParams.get('lat'));
  const longitude = Number(url.searchParams.get('lon'));
  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    return Response.json({ error: '定位坐标无效' }, { status: 400 });
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
      location: locationName(reverse),
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
