"""EPSG:5186 (Korea 2000 / Central Belt 2010) <-> WGS84 변환.

표준노드링크 shapefile의 좌표계다.
  Transverse Mercator, GRS80, lat0=38, lon0=127, k0=1.0, FE=200000, FN=600000
ITRF2000/GRS80과 WGS84의 차이는 우리 용도(수 m 단위 판단)에서는 무시할 수 있다.
"""
import math

A = 6378137.0
F = 1 / 298.257222101
E2 = F * (2 - F)
EP2 = E2 / (1 - E2)
K0 = 1.0
LAT0 = math.radians(38.0)
LON0 = math.radians(127.0)
FE = 200000.0
FN = 600000.0


def _M(lat):
    return A * (
        (1 - E2 / 4 - 3 * E2**2 / 64 - 5 * E2**3 / 256) * lat
        - (3 * E2 / 8 + 3 * E2**2 / 32 + 45 * E2**3 / 1024) * math.sin(2 * lat)
        + (15 * E2**2 / 256 + 45 * E2**3 / 1024) * math.sin(4 * lat)
        - (35 * E2**3 / 3072) * math.sin(6 * lat)
    )


_M0 = _M(LAT0)


def to_xy(lat_deg, lon_deg):
    """WGS84 위경도 -> EPSG:5186 (x, y) 미터."""
    lat = math.radians(lat_deg)
    lon = math.radians(lon_deg)
    sin_lat, cos_lat, tan_lat = math.sin(lat), math.cos(lat), math.tan(lat)
    N = A / math.sqrt(1 - E2 * sin_lat**2)
    T = tan_lat**2
    C = EP2 * cos_lat**2
    Aa = (lon - LON0) * cos_lat
    x = FE + K0 * N * (
        Aa + (1 - T + C) * Aa**3 / 6
        + (5 - 18 * T + T**2 + 72 * C - 58 * EP2) * Aa**5 / 120
    )
    y = FN + K0 * (
        _M(lat) - _M0 + N * tan_lat * (
            Aa**2 / 2 + (5 - T + 9 * C + 4 * C**2) * Aa**4 / 24
            + (61 - 58 * T + T**2 + 600 * C - 330 * EP2) * Aa**6 / 720
        )
    )
    return x, y


def to_latlon(x, y):
    """EPSG:5186 (x, y) 미터 -> WGS84 위경도."""
    e1 = (1 - math.sqrt(1 - E2)) / (1 + math.sqrt(1 - E2))
    M = _M0 + (y - FN) / K0
    mu = M / (A * (1 - E2 / 4 - 3 * E2**2 / 64 - 5 * E2**3 / 256))
    phi1 = (
        mu
        + (3 * e1 / 2 - 27 * e1**3 / 32) * math.sin(2 * mu)
        + (21 * e1**2 / 16 - 55 * e1**4 / 32) * math.sin(4 * mu)
        + (151 * e1**3 / 96) * math.sin(6 * mu)
        + (1097 * e1**4 / 512) * math.sin(8 * mu)
    )
    sin1, cos1, tan1 = math.sin(phi1), math.cos(phi1), math.tan(phi1)
    C1 = EP2 * cos1**2
    T1 = tan1**2
    N1 = A / math.sqrt(1 - E2 * sin1**2)
    R1 = A * (1 - E2) / (1 - E2 * sin1**2) ** 1.5
    D = (x - FE) / (N1 * K0)
    lat = phi1 - (N1 * tan1 / R1) * (
        D**2 / 2
        - (5 + 3 * T1 + 10 * C1 - 4 * C1**2 - 9 * EP2) * D**4 / 24
        + (61 + 90 * T1 + 298 * C1 + 45 * T1**2 - 252 * EP2 - 3 * C1**2) * D**6 / 720
    )
    lon = LON0 + (
        D - (1 + 2 * T1 + C1) * D**3 / 6
        + (5 - 2 * C1 + 28 * T1 - 3 * C1**2 + 8 * EP2 + 24 * T1**2) * D**5 / 120
    ) / cos1
    return math.degrees(lat), math.degrees(lon)


if __name__ == "__main__":
    # 왕복 변환 오차 확인
    for lat, lon in [(36.33202, 127.39937), (37.5665, 126.9780), (35.1796, 129.0756)]:
        x, y = to_xy(lat, lon)
        b = to_latlon(x, y)
        err_m = math.hypot((b[0] - lat) * 111320, (b[1] - lon) * 111320 * math.cos(math.radians(lat)))
        print(f"({lat:.5f},{lon:.5f}) -> ({x:,.1f},{y:,.1f}) 왕복오차 {err_m*1000:.3f}mm")
