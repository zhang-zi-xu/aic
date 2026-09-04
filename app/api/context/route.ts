import { geocodeCity, reverseGeocode } from '@/lib/geo/geocode';
import { describeDaily, fetchWeather } from '@/lib/geo/weather';

function validCoordinate(latitude: number, longitude: number) {
  return Number.isFinite(latitude) && latitude >= -90 && latitude <= 90
    && Number.isFinite(longitude) && longitude >= -180 && longitude <= 180;
}

export async function GET(request: Request) {
  const url = new URL(request.url);
  const latitudeParam = url.searchParams.get('lat');
  const longitudeParam = url.searchParams.get('lon');
  let latitude = Number(latitudeParam);
  let longitude = Number(longitudeParam);
  let location: string | null = null;
  let locationSource: string | null = null;
  const city = url.searchParams.get('city')?.trim().slice(0, 80) ?? '';
  const days = url.searchParams.get('days') === '7' ? 7 : 1;

  if (latitudeParam === null || longitudeParam === null || !validCoordinate(latitude, longitude)) {
    if (!city) return Response.json({ error: '请提供定位坐标或城市名称' }, { status: 400 });
    const result = await geocodeCity(city);
    if (!result.entry) {
      return Response.json({ error: `找不到“${city}”，请填写更完整的城市或区县名称` }, { status: 404 });
    }
    latitude = result.entry.lat;
    longitude = result.entry.lon;
    // 手动查询显示用户填写的地区，避免反向解析擅自细化为邻近街道。
    location = city;
    locationSource = result.source === 'table' ? '内置城市表' : 'Photon / OpenStreetMap';
  } else {
    location = await reverseGeocode(latitude, longitude);
    locationSource = location ? 'Photon / OpenStreetMap' : null;
  }

  try {
    const bundle = await fetchWeather(latitude, longitude, days);
    return Response.json({
      location,
      latitude,
      longitude,
      timezone: bundle.timezone,
      timezoneAbbreviation: bundle.timezoneAbbreviation,
      observedAt: bundle.observedAt,
      weather: bundle.current,
      daily: bundle.daily,
      dailyText: describeDaily(bundle.daily),
      weatherError: null,
      sources: { weather: bundle.source, location: locationSource },
    });
  } catch (error) {
    return Response.json({
      location,
      latitude,
      longitude,
      timezone: null,
      timezoneAbbreviation: null,
      observedAt: null,
      weather: null,
      daily: [],
      dailyText: '',
      weatherError: error instanceof Error ? error.message : '天气服务暂时不可用',
      sources: { weather: null, location: locationSource },
    });
  }
}
