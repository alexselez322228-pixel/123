#!/usr/bin/env python3
import sys, json, math, struct
from pathlib import Path

src, out = Path(sys.argv[1]), Path(sys.argv[2])
nodes = {}
coords = []
edges = []

ALLOWED = {
    "motorway","motorway_link","trunk","trunk_link","primary","primary_link",
    "secondary","secondary_link","tertiary","tertiary_link","residential",
    "unclassified","service","living_street"
}
def nid(lat, lon):
    key=(round(lat,6),round(lon,6))
    x=nodes.get(key)
    if x is None:
        x=len(coords); nodes[key]=x; coords.append(key)
    return x

def dist(a,b):
    la1,lo1=a; la2,lo2=b
    p1=math.radians(la1); p2=math.radians(la2)
    dp=math.radians(la2-la1); dl=math.radians(lo2-lo1)
    h=math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 6371000*2*math.asin(min(1,math.sqrt(h)))

with src.open("r", encoding="utf-8") as f:
    for line in f:
        try: g=json.loads(line)
        except: continue
        if g.get("type")!="Feature": continue
        geom=g.get("geometry") or {}
        if geom.get("type")!="LineString": continue
        p=g.get("properties") or {}
        hw=p.get("highway")
        if hw not in ALLOWED: continue
        c=geom.get("coordinates") or []
        if len(c)<2: continue
        one=str(p.get("oneway","")).lower() in ("yes","1","true")
        rev=str(p.get("oneway","")).strip()=="-1"
        ids=[nid(lat,lon) for lon,lat in c]
        for i in range(len(ids)-1):
            a,b=ids[i],ids[i+1]
            w=max(1,min(65535,int(round(dist(coords[a],coords[b])))))
            if rev: edges.append((b,a,w))
            else:
                edges.append((a,b,w))
                if not one: edges.append((b,a,w))

out.parent.mkdir(parents=True,exist_ok=True)
with out.open("wb") as f:
    f.write(b"NSG1")
    f.write(struct.pack("<II",len(coords),len(edges)))
    for lat,lon in coords:
        f.write(struct.pack("<ii",round(lat*1_000_000),round(lon*1_000_000)))
    for a,b,w in edges:
        f.write(struct.pack("<IIH",a,b,w))
print("nodes",len(coords),"edges",len(edges),"bytes",out.stat().st_size)
