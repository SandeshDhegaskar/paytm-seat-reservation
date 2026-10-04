#!/usr/bin/env python3
"""Concurrent hot-seat + idempotency burst test. Python 3.10+ standard library only."""
import argparse, concurrent.futures, json, random, sys, threading, time
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


def call(base, method, path, token, body=None, headers=None):
    h={"Authorization":f"Bearer {token}","Content-Type":"application/json","X-Request-Id":str(random.random())}
    if headers: h.update(headers)
    data=json.dumps(body).encode() if body is not None else None
    try:
        with urlopen(Request(base.rstrip('/')+path,data=data,headers=h,method=method),timeout=20) as r:
            return r.status, json.loads(r.read() or b"{}")
    except HTTPError as e:
        try: payload=json.loads(e.read() or b"{}")
        except Exception: payload={"message":str(e)}
        return e.code,payload
    except Exception as e: return 599,{"error":repr(e)}


def main():
    ap=argparse.ArgumentParser(); ap.add_argument("base_url"); ap.add_argument("--requests",type=int,default=500); ap.add_argument("--seat",default="A12"); ap.add_argument("--show-id"); ap.add_argument("--create",action="store_true"); args=ap.parse_args()
    base=args.base_url.rstrip('/')
    if args.create:
        seats=[f"A{i}" for i in range(1,51)]
        st,show=call(base,"POST","/shows","admin",{"name":"burst-show","seats":seats,"price_paise":25000,"per_user_limit":4})
        if st not in (200,201): print("create failed",st,show); return 1
        show_id=show["id"]
    elif args.show_id: show_id=args.show_id
    else: print("Use --create or --show-id"); return 2
    print("show:",show_id)
    def worker(i):
        user=f"user-{i}"
        # Every worker has a unique key for the hot-seat storm.
        return call(base,"POST",f"/shows/{show_id}/reserve",user,{"seats":[args.seat],"idempotency_key":f"burst-{i}"})
    counts={}
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(args.requests,200)) as ex:
        for st,payload in ex.map(worker,range(args.requests)):
            key="confirmed" if st==201 else (payload.get("code") if isinstance(payload,dict) else f"http-{st}")
            counts[key]=counts.get(key,0)+1
    print("outcomes:",json.dumps(counts,indent=2))
    st,state=call(base,"GET",f"/shows/{show_id}","observer")
    print("final reconciliation:",json.dumps(state.get("counts",{}),indent=2),"total=",len(state.get("seats",[])))
    total=sum(state.get("counts",{}).values()); ok=st==200 and total==len(state.get("seats",[])) and counts.get("confirmed",0)<=1
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1
if __name__=="__main__": sys.exit(main())
