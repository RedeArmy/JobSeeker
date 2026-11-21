import json,sys,time,urllib.request,os
D=sys.argv[1]; OUT="real_vectors.json"; URL="http://127.0.0.1:21434/api/embed"
QWEN_I="Instruct: Given a job posting, retrieve candidate profiles that fit the role\nQuery: "
d=json.load(open(D)); items=[("c",c["id"],c["text"]) for c in d["candidates"]]+[("j",j["id"],QWEN_I+j["text"]) for j in d["jobs"]]
res=json.load(open(OUT)) if os.path.exists(OUT) else {}
t0=time.time()
for kind,i,text in items:
    k=f"{kind}:{i}"
    if k in res: continue
    body=json.dumps({"model":"qwen3-embedding:0.6b","input":text,"truncate":False,"options":{"num_ctx":1024},"keep_alive":"30m"}).encode()
    for intento in range(3):
        try:
            r=json.load(urllib.request.urlopen(urllib.request.Request(URL,body,{"Content-Type":"application/json"}),timeout=600)); res[k]=r["embeddings"][0]; break
        except Exception as e: print("reintento",k,str(e)[:80],flush=True); time.sleep(5)
    json.dump(res,open(OUT,"w")); print(f"{len(res)}/{len(items)} {time.time()-t0:.0f}s",flush=True)
print("FIN",flush=True)
