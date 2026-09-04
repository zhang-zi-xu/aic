// 地理编码：优先内置城市表（离线、精准），失败再走 Photon 兜底。
// 逆地理：Photon reverse（失败降级为坐标文本）。

import { CITY_TABLE, resolveCity, type GeoEntry } from './cities';

const PHOTON_SEARCH = 'https://photon.komoot.io/api';
const PHOTON_REVERSE = 'https://photon.komoot.io/reverse';

type PhotonFeature = {
  properties?: Record<string, unknown>;
  geometry?: { coordinates?: unknown };
};

function textValue(value: unknown) {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function photonName(properties: Record<string, unknown>) {
  const parts = [
    textValue(properties.state),
    textValue(properties.city) || textValue(properties.county),
    textValue(properties.district) || textValue(properties.locality),
  ].filter((part, index, values): part is string => Boolean(part) && values.indexOf(part) === index);
  return parts.length ? parts.join(' · ') : null;
}

async function photonSearch(query: string): Promise<GeoEntry | null> {
  const url = new URL(PHOTON_SEARCH);
  url.searchParams.set('q', query);
  url.searchParams.set('limit', '1');
  const response = await fetch(url, {
    headers: { Accept: 'application/json', 'User-Agent': 'NongxinAgent/1.0' },
  });
  if (!response.ok) return null;
  const data = (await response.json()) as { features?: PhotonFeature[] };
  const feature = data.features?.[0];
  const coordinates = feature?.geometry?.coordinates;
  if (!Array.isArray(coordinates) || coordinates.length < 2) return null;
  const lon = Number(coordinates[0]);
  const lat = Number(coordinates[1]);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  const name = photonName(feature?.properties ?? {}) || query;
  return { name, prov: '', lat, lon };
}

/** 城市名 → 坐标。返回 { entry, source: 'table' | 'photon' | null } */
export async function geocodeCity(query: string): Promise<{ entry: GeoEntry | null; source: 'table' | 'photon' | null }> {
  const clean = query.trim().slice(0, 80);
  // 1. 内置表：离线、精准（覆盖全国省市级）
  const tableHit = resolveCity(clean);
  if (tableHit) return { entry: tableHit, source: 'table' };
  // 2. Photon 兜底：县级/镇级及特殊地名
  try {
    const photonHit = await photonSearch(clean);
    if (photonHit) return { entry: photonHit, source: 'photon' };
  } catch {
    // 兜底失败继续
  }
  return { entry: null, source: null };
}

/** 坐标 → 地名（失败返回 null，由调用方降级显示坐标文本） */
export async function reverseGeocode(lat: number, lon: number): Promise<string | null> {
  try {
    const url = new URL(PHOTON_REVERSE);
    url.searchParams.set('lat', lat.toFixed(5));
    url.searchParams.set('lon', lon.toFixed(5));
    const response = await fetch(url, {
      headers: { Accept: 'application/json', 'User-Agent': 'NongxinAgent/1.0' },
    });
    if (!response.ok) return null;
    const data = (await response.json()) as { features?: PhotonFeature[] };
    const name = photonName(data.features?.[0]?.properties ?? {});
    return name;
  } catch {
    return null;
  }
}

export { CITY_TABLE, resolveCity };
