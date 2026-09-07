"""표준노드링크 + 어린이보호구역 -> 앱용 제한속도 SQLite 생성.

전국 노드링크는 링크 155만 개(shp 275MB)라 앱에 통째로 넣을 수 없다.
운행 지역만 잘라 내고, 맵매칭에 필요한 최소 정보만 담는다.

설계
  - 제한속도의 출처는 표준노드링크 MAX_SPD 하나뿐이다.
  - 어린이보호구역은 제한속도를 덮어쓰지 않는다. 실측 결과 노드링크가 학교 인접
    도로의 82.5%를 이미 30으로 표시하고 있고, 보호구역 데이터에는 제한속도 컬럼도
    구역 경계도 없어서 반경으로 덮어쓰면 간선도로까지 30이 되어 과속 오탐이 커진다.
    대신 "학교 근처인데 MAX_SPD가 40 이상"인 링크에 주의 플래그를 달아
    앱이 그 구간에서 과속 판정을 보류하게 한다.
  - 세그먼트 단위 격자 인덱스를 함께 만들어 앱이 선형 탐색을 하지 않게 한다.

사용법
  python build_speedlimit_db.py --region daejeon --out speedlimit_daejeon.db
"""
import argparse
import csv
import io
import math
import os
import sqlite3
import struct
from collections import defaultdict

import tm

# 운행 지역 사전 정의. 노선이 시·도 경계를 넘으면 여기에 넓혀 잡는다.
REGIONS = {
    # 대전 + 세종 + 계룡 일부까지 여유 있게
    "daejeon": (36.10, 127.20, 36.60, 127.65),
    "sejong": (36.40, 127.15, 36.75, 127.40),
    "seoul": (37.40, 126.75, 37.72, 127.20),
    "busan": (35.03, 128.75, 35.40, 129.30),
}

# 링크를 넣을 격자 크기 [m]. 앱에서 조회 시 주변 9칸만 보면 된다.
CELL_M = 200.0

# 학교로부터 이 거리 이내인데 MAX_SPD가 아래 임계값 이상이면 주의 플래그를 단다.
SCHOOL_NEAR_M = 50.0
SCHOOL_SUSPECT_SPD = 40


def read_dbf_fields(path):
    f = open(path, "rb")
    hdr = f.read(32)
    nrec, hlen, rlen = struct.unpack("<I H H", hdr[4:12])
    fields, pos = [], 1
    while True:
        d = f.read(32)
        if d[0:1] in (b"\x0d", b""):
            break
        name = d[0:11].split(b"\x00")[0].decode("cp949", "replace")
        ln = d[16]
        fields.append((name, pos, ln))
        pos += ln
    return f, nrec, hlen, rlen, {n: (o, l) for n, o, l in fields}


def seg_dist(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shp", default="nodelink/MOCT_LINK.shp")
    ap.add_argument("--dbf", default="nodelink/MOCT_LINK.dbf")
    ap.add_argument("--zones", default=r"C:\Users\yaku4\Downloads\전국어린이보호구역표준데이터.csv")
    ap.add_argument("--region", default="daejeon", choices=sorted(REGIONS))
    ap.add_argument("--out", default="speedlimit.db")
    args = ap.parse_args()

    lat0, lon0, lat1, lon1 = REGIONS[args.region]
    xs, ys = [], []
    for la in (lat0, lat1):
        for lo in (lon0, lon1):
            x, y = tm.to_xy(la, lo)
            xs.append(x); ys.append(y)
    bx0, by0, bx1, by1 = min(xs), min(ys), max(xs), max(ys)
    print(f"[{args.region}] 위경도 {lat0}~{lat1}, {lon0}~{lon1}")
    print(f"          EPSG:5186 X {bx0:,.0f}~{bx1:,.0f}  Y {by0:,.0f}~{by1:,.0f}")

    # 1) 영역 내 링크 형상 추출
    print("\n[1/5] 링크 형상 읽는 중...")
    f = open(args.shp, "rb")
    f.seek(100)
    links = []
    idx = 0
    while True:
        head = f.read(8)
        if len(head) < 8:
            break
        content_len = struct.unpack(">I", head[4:8])[0] * 2
        bh = f.read(40)
        if len(bh) < 40:
            break
        box = struct.unpack("<4d", bh[4:36])
        nparts = struct.unpack("<I", bh[36:40])[0]
        rest = content_len - 40
        if box[2] < bx0 or box[0] > bx1 or box[3] < by0 or box[1] > by1:
            f.seek(rest, 1)
        else:
            data = f.read(rest)
            npoints = struct.unpack("<I", data[0:4])[0]
            off = 4 + nparts * 4
            p = struct.unpack("<%dd" % (npoints * 2), data[off:off + npoints * 16])
            links.append((idx, [(p[i], p[i + 1]) for i in range(0, len(p), 2)]))
        idx += 1
    print(f"      전체 {idx:,}개 중 {len(links):,}개 선택")

    # 2) 속성 읽기
    print("[2/5] 속성 읽는 중...")
    df, nrec, hlen, rlen, off = read_dbf_fields(args.dbf)
    attrs = {}
    for i, _ in links:
        df.seek(hlen + i * rlen)
        raw = df.read(rlen)

        def get(k):
            o, l = off[k]
            return raw[o:o + l].decode("cp949", "replace").strip()

        attrs[i] = (get("LINK_ID"), get("MAX_SPD"), get("ROAD_RANK"), get("ROAD_NAME"))

    # 3) 어린이보호구역 주의 플래그
    print("[3/5] 어린이보호구역 대조 중...")
    zones = []
    with io.open(args.zones, encoding="cp949", errors="replace", newline="") as fh:
        r = csv.DictReader(fh)
        lk = [k for k in r.fieldnames if "위도" in k][0]
        ok = [k for k in r.fieldnames if "경도" in k][0]
        for row in r:
            try:
                la, lo = float(row[lk]), float(row[ok])
            except (TypeError, ValueError):
                continue
            if lat0 <= la <= lat1 and lon0 <= lo <= lon1:
                zones.append(tm.to_xy(la, lo))

    grid = defaultdict(list)
    for i, pts in links:
        for a, b in zip(pts, pts[1:]):
            for cx in range(int(min(a[0], b[0]) // CELL_M), int(max(a[0], b[0]) // CELL_M) + 1):
                for cy in range(int(min(a[1], b[1]) // CELL_M), int(max(a[1], b[1]) // CELL_M) + 1):
                    grid[(cx, cy)].append((i, a, b))

    suspect = set()
    span = int(SCHOOL_NEAR_M // CELL_M) + 1
    for px, py in zones:
        cx, cy = int(px // CELL_M), int(py // CELL_M)
        for dx in range(-span, span + 1):
            for dy in range(-span, span + 1):
                for i, a, b in grid.get((cx + dx, cy + dy), ()):
                    if i in suspect:
                        continue
                    spd = attrs[i][1]
                    if not spd.isdigit() or int(spd) < SCHOOL_SUSPECT_SPD:
                        continue
                    if seg_dist(px, py, a[0], a[1], b[0], b[1]) <= SCHOOL_NEAR_M:
                        suspect.add(i)
    print(f"      보호구역 {len(zones):,}곳, 주의 플래그 링크 {len(suspect):,}개")

    # 4) SQLite 기록
    print("[4/5] SQLite 기록 중...")
    if os.path.exists(args.out):
        os.remove(args.out)
    db = sqlite3.connect(args.out)
    db.executescript("""
        PRAGMA journal_mode=OFF;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE links (
            id INTEGER PRIMARY KEY,
            link_id TEXT NOT NULL,
            max_spd INTEGER,
            road_rank TEXT,
            road_name TEXT,
            school_suspect INTEGER NOT NULL DEFAULT 0,
            geom BLOB NOT NULL          -- float32 (lat, lon) 쌍의 나열
        );
        -- 좌표는 links.geom(float32)에만 두고 여기서는 참조만 한다.
        -- 좌표를 중복 저장하면 파일이 3배 가까이 커진다.
        -- WITHOUT ROWID로 만들어 데이터와 인덱스를 한 벌만 유지한다.
        CREATE TABLE seg_index (
            cx INTEGER NOT NULL,
            cy INTEGER NOT NULL,
            link INTEGER NOT NULL,
            seg INTEGER NOT NULL,       -- 링크 내 세그먼트 번호
            bearing INTEGER NOT NULL,   -- 진행 방위 [deg], 맵매칭에서 GPS 방위와 비교
            PRIMARY KEY (cx, cy, link, seg)
        ) WITHOUT ROWID;
    """)

    def bearing_deg(alat, alon, blat, blon):
        # 짧은 세그먼트라 평면 근사로 충분하다.
        dy = blat - alat
        dx = (blon - alon) * math.cos(math.radians((alat + blat) / 2))
        return (math.degrees(math.atan2(dx, dy)) + 360.0) % 360.0

    rows, segs = [], []
    for new_id, (i, pts) in enumerate(links):
        link_id, spd, rank, name = attrs[i]
        ll = [tm.to_latlon(x, y) for x, y in pts]
        blob = struct.pack("<%df" % (len(ll) * 2), *[v for p in ll for v in p])
        rows.append((
            new_id, link_id, int(spd) if spd.isdigit() else None,
            rank, name if name != "-" else None,
            1 if i in suspect else 0, blob,
        ))
        for s, (a, b) in enumerate(zip(ll, ll[1:])):
            (ax, ay), (bx, by) = pts[s], pts[s + 1]
            for cx in range(int(min(ax, bx) // CELL_M), int(max(ax, bx) // CELL_M) + 1):
                for cy in range(int(min(ay, by) // CELL_M), int(max(ay, by) // CELL_M) + 1):
                    segs.append((cx, cy, new_id, s,
                                 int(round(bearing_deg(a[0], a[1], b[0], b[1]))) % 360))

    db.executemany("INSERT INTO links VALUES (?,?,?,?,?,?,?)", rows)
    db.executemany("INSERT OR IGNORE INTO seg_index VALUES (?,?,?,?,?)", segs)
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("region", args.region),
        ("bbox", f"{lat0},{lon0},{lat1},{lon1}"),
        ("cell_m", str(CELL_M)),
        ("source_link", "MOCT 표준노드링크 (국가교통정보센터)"),
        ("source_zone", "전국어린이보호구역표준데이터 (공공데이터포털)"),
        ("school_near_m", str(SCHOOL_NEAR_M)),
        ("school_suspect_spd", str(SCHOOL_SUSPECT_SPD)),
        ("link_count", str(len(rows))),
        ("seg_count", str(len(segs))),
    ])
    db.commit()
    db.execute("VACUUM")
    db.close()

    # 5) 요약
    print("[5/5] 완료\n")
    db = sqlite3.connect(args.out)
    size = os.path.getsize(args.out)
    print(f"  파일      {args.out}  ({size/1024/1024:.1f} MB)")
    print(f"  링크      {len(rows):,}개")
    print(f"  세그먼트  {len(segs):,}개")
    print(f"  주의 링크 {len(suspect):,}개")
    print("\n  제한속도 분포")
    for spd, n in db.execute(
        "SELECT max_spd, COUNT(*) FROM links GROUP BY max_spd ORDER BY COUNT(*) DESC LIMIT 8"
    ):
        print(f"    {str(spd) if spd else '(없음)':>6} km/h : {n:>7,}")
    db.close()


if __name__ == "__main__":
    main()
