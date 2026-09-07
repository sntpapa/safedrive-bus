"""표준노드링크의 MAX_SPD가 어린이보호구역을 이미 반영하고 있는지 실측 확인.

방법
  1) 대전 영역 링크를 shapefile에서 뽑는다
  2) 대전 영역 어린이보호구역 지점을 CSV에서 뽑는다
  3) 각 보호구역 지점에서 30m 이내 링크의 MAX_SPD 분포를 본다
  4) 대전 전체 링크의 MAX_SPD 분포와 비교한다

보호구역 근처 링크가 이미 대부분 30이면 노드링크만으로 충분하고,
50/60이 많으면 어린이보호구역 데이터를 따로 덮어써야 한다.
"""
import csv
import io
import math
import struct
from collections import Counter, defaultdict

import tm

SHP = "nodelink/MOCT_LINK.shp"
DBF = "nodelink/MOCT_LINK.dbf"
ZONE_CSV = r"C:\Users\yaku4\Downloads\전국어린이보호구역표준데이터.csv"

# 대전 영역
LAT_MIN, LAT_MAX = 36.15, 36.50
LON_MIN, LON_MAX = 127.25, 127.60
NEAR_M = 30.0      # 링크가 보호구역 지점에 이만큼 이내면 "그 구역의 도로"로 본다
CELL = 250.0       # 격자 인덱스 한 칸 크기 [m]


def region_box():
    xs, ys = [], []
    for la in (LAT_MIN, LAT_MAX):
        for lo in (LON_MIN, LON_MAX):
            x, y = tm.to_xy(la, lo)
            xs.append(x); ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


def read_links(x0, y0, x1, y1):
    """영역과 겹치는 링크만 (record_index, [(x,y)...]) 로 반환."""
    f = open(SHP, "rb")
    f.seek(100)
    out = []
    idx = 0
    while True:
        head = f.read(8)
        if len(head) < 8:
            break
        content_words = struct.unpack(">I", head[4:8])[0]
        content_len = content_words * 2
        body_head = f.read(40)  # shapeType(4) + box(32) + numParts(4)
        if len(body_head) < 40:
            break
        box = struct.unpack("<4d", body_head[4:36])
        nparts = struct.unpack("<I", body_head[36:40])[0]
        rest = content_len - 40
        if box[2] < x0 or box[0] > x1 or box[3] < y0 or box[1] > y1:
            f.seek(rest, 1)
        else:
            data = f.read(rest)
            npoints = struct.unpack("<I", data[0:4])[0]
            off = 4 + nparts * 4
            pts = struct.unpack("<%dd" % (npoints * 2), data[off:off + npoints * 16])
            out.append((idx, [(pts[i], pts[i + 1]) for i in range(0, len(pts), 2)]))
        idx += 1
    return out, idx


def read_maxspd(indices):
    f = open(DBF, "rb")
    hdr = f.read(32)
    _, hlen, rlen = struct.unpack("<I H H", hdr[4:12])
    fields = []
    while True:
        d = f.read(32)
        if d[0:1] in (b"\x0d", b""):
            break
        fields.append((d[0:11].split(b"\x00")[0].decode("cp949", "replace"), d[16]))
    offsets, pos = {}, 1
    for name, ln in fields:
        offsets[name] = (pos, ln)
        pos += ln
    result = {}
    for i in sorted(indices):
        f.seek(hlen + i * rlen)
        raw = f.read(rlen)
        row = {}
        for key in ("MAX_SPD", "ROAD_RANK", "ROAD_NAME", "LANES"):
            o, ln = offsets[key]
            row[key] = raw[o:o + ln].decode("cp949", "replace").strip()
        result[i] = row
    return result


def seg_dist(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def main():
    x0, y0, x1, y1 = region_box()
    print(f"대전 영역 (EPSG:5186)  X {x0:,.0f}~{x1:,.0f}  Y {y0:,.0f}~{y1:,.0f}")

    links, total = read_links(x0, y0, x1, y1)
    print(f"전체 링크 {total:,}개 중 대전 영역 {len(links):,}개")

    attrs = read_maxspd([i for i, _ in links])

    overall = Counter(attrs[i]["MAX_SPD"] for i, _ in links)

    # 격자 인덱스
    grid = defaultdict(list)
    for i, pts in links:
        for a, b in zip(pts, pts[1:]):
            cx0 = int(min(a[0], b[0]) // CELL); cx1 = int(max(a[0], b[0]) // CELL)
            cy0 = int(min(a[1], b[1]) // CELL); cy1 = int(max(a[1], b[1]) // CELL)
            for cx in range(cx0, cx1 + 1):
                for cy in range(cy0, cy1 + 1):
                    grid[(cx, cy)].append((i, a, b))

    # 어린이보호구역 지점
    zones = []
    with io.open(ZONE_CSV, encoding="cp949", errors="replace", newline="") as fh:
        r = csv.DictReader(fh)
        lat_key = [k for k in r.fieldnames if "위도" in k][0]
        lon_key = [k for k in r.fieldnames if "경도" in k][0]
        name_key = [k for k in r.fieldnames if "시설명" in k][0]
        for row in r:
            try:
                la, lo = float(row[lat_key]), float(row[lon_key])
            except (ValueError, TypeError):
                continue
            if LAT_MIN <= la <= LAT_MAX and LON_MIN <= lo <= LON_MAX:
                zones.append((row[name_key], la, lo))
    print(f"대전 영역 어린이보호구역 {len(zones):,}곳 (전국 대비)")

    near = Counter()
    matched_links = set()
    zones_with_link = 0
    for _, la, lo in zones:
        px, py = tm.to_xy(la, lo)
        cx, cy = int(px // CELL), int(py // CELL)
        found = set()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for i, a, b in grid.get((cx + dx, cy + dy), ()):
                    if seg_dist(px, py, a[0], a[1], b[0], b[1]) <= NEAR_M:
                        found.add(i)
        if found:
            zones_with_link += 1
        for i in found:
            if i not in matched_links:
                matched_links.add(i)
                near[attrs[i]["MAX_SPD"]] += 1

    def show(title, c):
        tot = sum(c.values()) or 1
        print(f"\n=== {title} (총 {tot:,}) ===")
        for v, n in sorted(c.items(), key=lambda kv: -kv[1])[:8]:
            print(f"  {v or '(빈값)':>6} km/h : {n:>7,}  {n*100/tot:5.1f}%")

    show("대전 전체 링크 MAX_SPD", overall)
    show(f"어린이보호구역 {NEAR_M:.0f}m 이내 링크 MAX_SPD", near)
    print(f"\n링크가 잡힌 보호구역: {zones_with_link:,}/{len(zones):,}곳")

    n30 = near.get("30", 0)
    ntot = sum(near.values()) or 1
    o30 = overall.get("30", 0)
    otot = sum(overall.values()) or 1
    print(f"\n30km/h 비율  보호구역 근처 {n30*100/ntot:.1f}%  vs  대전 전체 {o30*100/otot:.1f}%")


if __name__ == "__main__":
    main()
