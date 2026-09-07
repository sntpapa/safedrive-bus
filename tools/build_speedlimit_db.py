"""표준노드링크 + 무인교통단속카메라 + 어린이보호구역 -> 앱용 제한속도 SQLite 생성.

전국 노드링크는 링크 155만 개(shp 275MB)라 앱에 통째로 넣을 수 없다.
운행 지역만 잘라 내고, 맵매칭에 필요한 최소 정보만 담는다.

제한속도를 정하는 순서
  1. 무인교통단속카메라의 제한속도 (있으면 이걸 쓴다)
     경찰이 실제로 단속하는 기준값이라 가장 권위 있다. 대전 영역 실측에서
     노드링크 MAX_SPD와 19.6%가 불일치했고, 그 차이가 곧 과속 오탐/미탐이 된다.
  2. 표준노드링크 MAX_SPD (카메라가 없는 구간)

어린이보호구역 표준데이터는 제한속도로 쓰지 않는다
  제한속도 컬럼도 구역 경계도 없이 학교 건물 좌표 한 점뿐이라, 반경을 씌워 30으로
  덮어쓰면 학교 옆 간선도로까지 30이 되어 과속 오탐이 커진다. 실측상 노드링크가
  이미 학교 인접 도로의 82.5%를 30으로 표시하고 있다.
  대신 "학교 근처인데 제한속도가 40 이상"인 링크에 주의 플래그만 달아,
  앱이 그 구간에서 과속 판정을 보류하게 한다.

격자 인덱스는 위경도 기준이다
  앱이 좌표 투영 없이 floor(lat/CELL), floor(lon/CELL)만으로 조회할 수 있게 한다.

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
from collections import Counter, defaultdict

import tm

REGIONS = {
    # 대전 + 세종 + 계룡 일부까지 여유 있게
    "daejeon": (36.10, 127.20, 36.60, 127.65),
    "sejong": (36.40, 127.15, 36.75, 127.40),
    "seoul": (37.40, 126.75, 37.72, 127.20),
    "busan": (35.03, 128.75, 35.40, 129.30),
}

# 격자 한 칸 크기 [도]. 0.002도 = 위도 약 222m, 대전 위도에서 경도 약 179m.
# 앱은 자기 위치가 속한 칸과 주변 8칸만 조회하면 된다.
CELL_DEG = 0.002

# 카메라를 이 거리 이내의 링크에 대응시킨다.
CAM_MATCH_M = 40.0

# 학교로부터 이 거리 이내인데 제한속도가 아래 임계값 이상이면 주의 플래그를 단다.
SCHOOL_NEAR_M = 50.0
SCHOOL_SUSPECT_SPD = 40

CELL_M = 250.0  # 내부 매칭용 미터 격자


def read_dbf(path):
    f = open(path, "rb")
    hdr = f.read(32)
    _, hlen, rlen = struct.unpack("<I H H", hdr[4:12])
    off, pos = {}, 1
    while True:
        d = f.read(32)
        if d[0:1] in (b"\x0d", b""):
            break
        off[d[0:11].split(b"\x00")[0].decode("cp949", "replace")] = (pos, d[16])
        pos += d[16]
    return f, hlen, rlen, off


def seg_dist(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def load_points(path, cols, lat0, lon0, lat1, lon1):
    """CSV에서 영역 안의 (위도, 경도, 추가컬럼들) 을 읽는다."""
    out = []
    with io.open(path, encoding="cp949", errors="replace", newline="") as fh:
        r = csv.DictReader(fh)
        key = {}
        for want in ("위도", "경도", *cols):
            cand = [c for c in r.fieldnames if want in c]
            if not cand:
                raise SystemExit(f"'{want}' 컬럼을 찾을 수 없습니다: {path}")
            key[want] = cand[0]
        for row in r:
            try:
                la, lo = float(row[key["위도"]]), float(row[key["경도"]])
            except (TypeError, ValueError):
                continue
            if lat0 <= la <= lat1 and lon0 <= lo <= lon1:
                out.append((la, lo, *[(row[key[c]] or "").strip() for c in cols]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shp", default="nodelink/MOCT_LINK.shp")
    ap.add_argument("--dbf", default="nodelink/MOCT_LINK.dbf")
    ap.add_argument("--cameras", default=r"C:\Users\yaku4\Downloads\전국무인교통단속카메라표준데이터.csv")
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

    # 1) 영역 내 링크 형상
    print("[1/6] 링크 형상 읽는 중...")
    f = open(args.shp, "rb")
    f.seek(100)
    links, idx = [], 0
    while True:
        head = f.read(8)
        if len(head) < 8:
            break
        clen = struct.unpack(">I", head[4:8])[0] * 2
        bh = f.read(40)
        if len(bh) < 40:
            break
        box = struct.unpack("<4d", bh[4:36])
        nparts = struct.unpack("<I", bh[36:40])[0]
        rest = clen - 40
        if box[2] < bx0 or box[0] > bx1 or box[3] < by0 or box[1] > by1:
            f.seek(rest, 1)
        else:
            data = f.read(rest)
            npts = struct.unpack("<I", data[0:4])[0]
            o = 4 + nparts * 4
            p = struct.unpack("<%dd" % (npts * 2), data[o:o + npts * 16])
            links.append((idx, [(p[i], p[i + 1]) for i in range(0, len(p), 2)]))
        idx += 1
    print(f"      전체 {idx:,}개 중 {len(links):,}개 선택")

    # 2) 속성
    print("[2/6] 속성 읽는 중...")
    df, hlen, rlen, off = read_dbf(args.dbf)
    attrs = {}
    for i, _ in links:
        df.seek(hlen + i * rlen)
        raw = df.read(rlen)

        def g(k):
            o, l = off[k]
            return raw[o:o + l].decode("cp949", "replace").strip()

        attrs[i] = {"link_id": g("LINK_ID"), "max_spd": g("MAX_SPD"),
                    "rank": g("ROAD_RANK"), "name": g("ROAD_NAME")}

    # 미터 격자 (카메라/학교 매칭용)
    grid = defaultdict(list)
    for i, pts in links:
        for a, b in zip(pts, pts[1:]):
            for cx in range(int(min(a[0], b[0]) // CELL_M), int(max(a[0], b[0]) // CELL_M) + 1):
                for cy in range(int(min(a[1], b[1]) // CELL_M), int(max(a[1], b[1]) // CELL_M) + 1):
                    grid[(cx, cy)].append((i, a, b))

    def nearest_link(px, py, limit_m):
        cx, cy = int(px // CELL_M), int(py // CELL_M)
        best, bd = None, 1e9
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for i, a, b in grid.get((cx + dx, cy + dy), ()):
                    d = seg_dist(px, py, a[0], a[1], b[0], b[1])
                    if d < bd:
                        bd, best = d, i
        return best if bd <= limit_m else None

    # 3) 단속카메라 제한속도 반영
    print("[3/6] 단속카메라 대조 중...")
    cams = load_points(args.cameras, ("제한속도",), lat0, lon0, lat1, lon1)
    cam_spd, agree, disagree = {}, 0, 0
    for la, lo, spd in cams:
        if not spd.isdigit() or int(spd) == 0:
            continue
        v = int(spd)
        i = nearest_link(*tm.to_xy(la, lo), CAM_MATCH_M)
        if i is None:
            continue
        ms = attrs[i]["max_spd"]
        if ms.isdigit():
            if int(ms) == v:
                agree += 1
            else:
                disagree += 1
        # 한 링크에 여러 카메라가 걸리면 낮은 쪽을 택한다(보수적)
        cam_spd[i] = min(cam_spd.get(i, v), v)
    tot = agree + disagree
    print(f"      카메라 {len(cams):,}대, 링크 매칭 {len(cam_spd):,}개")
    if tot:
        print(f"      MAX_SPD와 일치 {agree:,} / 불일치 {disagree:,} ({disagree*100/tot:.1f}%)")

    # 4) 어린이보호구역 주의 플래그
    print("[4/6] 어린이보호구역 대조 중...")
    zones = load_points(args.zones, (), lat0, lon0, lat1, lon1)
    suspect = set()
    span = int(SCHOOL_NEAR_M // CELL_M) + 1
    for la, lo in zones:
        px, py = tm.to_xy(la, lo)
        cx, cy = int(px // CELL_M), int(py // CELL_M)
        for dx in range(-span, span + 1):
            for dy in range(-span, span + 1):
                for i, a, b in grid.get((cx + dx, cy + dy), ()):
                    if i in suspect:
                        continue
                    ms = attrs[i]["max_spd"]
                    eff = cam_spd.get(i, int(ms) if ms.isdigit() else None)
                    if eff is None or eff < SCHOOL_SUSPECT_SPD:
                        continue
                    if seg_dist(px, py, a[0], a[1], b[0], b[1]) <= SCHOOL_NEAR_M:
                        suspect.add(i)
    print(f"      보호구역 {len(zones):,}곳, 주의 플래그 링크 {len(suspect):,}개")

    # 5) SQLite
    print("[5/6] SQLite 기록 중...")
    if os.path.exists(args.out):
        os.remove(args.out)
    db = sqlite3.connect(args.out)
    db.executescript("""
        PRAGMA journal_mode=OFF;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE links (
            id INTEGER PRIMARY KEY,
            link_id TEXT NOT NULL,
            max_spd INTEGER,            -- 표준노드링크 원본
            cam_spd INTEGER,            -- 단속카메라 기준 (없으면 NULL)
            eff_spd INTEGER,            -- 실제 적용값 = COALESCE(cam_spd, max_spd)
            road_rank TEXT,
            road_name TEXT,
            school_suspect INTEGER NOT NULL DEFAULT 0,
            geom BLOB NOT NULL          -- float32 (lat, lon) 쌍의 나열
        );
        -- 좌표는 links.geom에만 두고 여기서는 참조만 한다. 중복 저장하면 파일이 3배가 된다.
        -- WITHOUT ROWID로 데이터와 인덱스를 한 벌만 유지한다.
        CREATE TABLE seg_index (
            cx INTEGER NOT NULL,        -- floor(경도 / CELL_DEG)
            cy INTEGER NOT NULL,        -- floor(위도 / CELL_DEG)
            link INTEGER NOT NULL,
            seg INTEGER NOT NULL,
            bearing INTEGER NOT NULL,   -- 진행 방위 [deg]. GPS 방위와 비교해 반대 차선을 거른다
            PRIMARY KEY (cx, cy, link, seg)
        ) WITHOUT ROWID;
    """)

    def bearing_deg(alat, alon, blat, blon):
        dy = blat - alat
        dx = (blon - alon) * math.cos(math.radians((alat + blat) / 2))
        return int(round((math.degrees(math.atan2(dx, dy)) + 360.0) % 360.0)) % 360

    rows, segs = [], []
    dist = Counter()
    for new_id, (i, pts) in enumerate(links):
        a = attrs[i]
        ms = int(a["max_spd"]) if a["max_spd"].isdigit() else None
        cs = cam_spd.get(i)
        eff = cs if cs is not None else ms
        dist[eff] += 1
        ll = [tm.to_latlon(x, y) for x, y in pts]
        rows.append((
            new_id, a["link_id"], ms, cs, eff, a["rank"],
            a["name"] if a["name"] != "-" else None,
            1 if i in suspect else 0,
            struct.pack("<%df" % (len(ll) * 2), *[v for p in ll for v in p]),
        ))
        for s, (p, q) in enumerate(zip(ll, ll[1:])):
            br = bearing_deg(p[0], p[1], q[0], q[1])
            cx0 = int(math.floor(min(p[1], q[1]) / CELL_DEG))
            cx1 = int(math.floor(max(p[1], q[1]) / CELL_DEG))
            cy0 = int(math.floor(min(p[0], q[0]) / CELL_DEG))
            cy1 = int(math.floor(max(p[0], q[0]) / CELL_DEG))
            for cx in range(cx0, cx1 + 1):
                for cy in range(cy0, cy1 + 1):
                    segs.append((cx, cy, new_id, s, br))

    db.executemany("INSERT INTO links VALUES (?,?,?,?,?,?,?,?,?)", rows)
    db.executemany("INSERT OR IGNORE INTO seg_index VALUES (?,?,?,?,?)", segs)
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("region", args.region),
        ("bbox", f"{lat0},{lon0},{lat1},{lon1}"),
        ("cell_deg", str(CELL_DEG)),
        ("source_link", "MOCT 표준노드링크 (국가교통정보센터)"),
        ("source_camera", "전국무인교통단속카메라표준데이터 (공공데이터포털)"),
        ("source_zone", "전국어린이보호구역표준데이터 (공공데이터포털)"),
        ("cam_match_m", str(CAM_MATCH_M)),
        ("school_near_m", str(SCHOOL_NEAR_M)),
        ("link_count", str(len(rows))),
        ("cam_link_count", str(len(cam_spd))),
        ("suspect_count", str(len(suspect))),
    ])
    db.commit()
    db.execute("VACUUM")
    db.close()

    print("[6/6] 완료\n")
    print(f"  파일      {args.out}  ({os.path.getsize(args.out)/1024/1024:.1f} MB)")
    print(f"  링크      {len(rows):,}개  (카메라 보정 {len(cam_spd):,}개)")
    print(f"  세그먼트  {len(segs):,}개")
    print(f"  주의 링크 {len(suspect):,}개")
    print("\n  적용 제한속도 분포")
    for spd, n in sorted(dist.items(), key=lambda kv: -kv[1])[:8]:
        print(f"    {str(spd) if spd else '(없음)':>6} km/h : {n:>7,}")


if __name__ == "__main__":
    main()
