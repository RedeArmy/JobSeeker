"""Recall de HNSW sobre vectores reales (qwen3-embedding:0.6b, 1024 dim) y sobre corpus aumentados.
Los 104 vectores reales son los textos del bake-off. El corpus de 10.000 candidatos es SEMI-SINTÉTICO:
combinaciones convexas de 2-3 vectores reales más ruido, renormalizadas. No equivale a un corpus real."""
import json,random,subprocess,sys,math
import numpy as np
V=json.load(open("real_vectors.json"))
C=np.array([v for k,v in V.items() if k.startswith("c:")],dtype=np.float32); J=np.array([v for k,v in V.items() if k.startswith("j:")],dtype=np.float32)
C/=np.linalg.norm(C,axis=1,keepdims=True); J/=np.linalg.norm(J,axis=1,keepdims=True)
print("reales: candidatos",C.shape,"vacantes",J.shape)
S=J@C.T; print("coseno vacante-candidato REAL: min %.3f media %.3f máx %.3f"%(S.min(),S.mean(),S.max()))
print("coseno entre candidatos REALES (anisotropía): media %.3f"%((C@C.T)[np.triu_indices(len(C),1)].mean()))
rng=np.random.default_rng(11); N=10000; D=C.shape[1]
def aumentar(sigma):
    out=np.empty((N,D),dtype=np.float32)
    for i in range(N):
        k=rng.integers(2,4); idx=rng.choice(len(C),k,replace=False); w=rng.dirichlet(np.ones(k))
        x=(w[:,None]*C[idx]).sum(0)+sigma*rng.standard_normal(D).astype(np.float32)/math.sqrt(D)
        out[i]=x/np.linalg.norm(x)
    return out
def uniforme():
    x=rng.standard_normal((N,D)).astype(np.float32); return x/np.linalg.norm(x,axis=1,keepdims=True)
corpora={"real_aumentado_ruido0.3":aumentar(0.3),"real_aumentado_ruido0.8":aumentar(0.8),"uniforme_aleatorio":uniforme()}
def vtxt(v): return "["+",".join("%.6g"%x for x in v)+"]"
def psql(sql,inp=None):
    return subprocess.run(["docker","exec","-i","jsvec2","psql","-U","postgres","-d","t","-v","ON_ERROR_STOP=1","-tA","-q"],input=sql,capture_output=True,text=True)
res={}
for name,X in corpora.items():
    tbl="cp_"+name.replace(".","_")
    sql=f"drop table if exists {tbl}; create table {tbl}(id int primary key, discoverable boolean, country text, embedding vector(1024));\ncopy {tbl} from stdin;\n"
    cty=np.array(["GT","MX","SV","HN","US"])[rng.integers(0,5,N)]; dis=rng.random(N)<0.7
    sql+="".join(f"{i}\t{'t' if dis[i] else 'f'}\t{cty[i]}\t{vtxt(X[i])}\n" for i in range(N))+"\\.\n"
    sql+=f"create index {tbl}_hnsw on {tbl} using hnsw (embedding vector_cosine_ops) where discoverable;\nanalyze {tbl};\n"
    r=psql(sql); 
    if r.returncode: print("ERROR carga",name,r.stderr[:200]); sys.exit(1)
    for cfg,extra in (("descubribles","discoverable"),("descubribles+pais GT","discoverable and country='GT'")):
        for iter_mode in ("off","relaxed_order"):
            for ef in (40,100):
                tot10=tot20=n=0
                for q in J:
                    qt=vtxt(q)
                    s=f"""set hnsw.iterative_scan={iter_mode}; set hnsw.ef_search={ef};
set enable_indexscan=off; set enable_bitmapscan=off; set enable_seqscan=on;
create temp table ex as select id from {tbl} where {extra} order by embedding <=> '{qt}'::vector limit 20;
set enable_indexscan=on; set enable_seqscan=off; set enable_bitmapscan=off;
create temp table ap as select id from {tbl} where {extra} order by embedding <=> '{qt}'::vector limit 20;
select (select count(*) from ap), (select count(*) from ex join ap using(id)), (select count(*) from (select id from ex order by id limit 20) a); """
                    # recall@10: se calcula con los 10 primeros por distancia en ambos
                    s=s.replace("select (select count(*) from ap)","select (select count(*) from ap)")
                    r=psql(s); 
                    if r.returncode: print("ERROR",r.stderr[:200]); sys.exit(1)
                    got,common,_=[int(x) for x in r.stdout.strip().split("|")]
                    tot20+=common; n+=20
                res[(name,cfg,iter_mode,ef)]=tot20/n
                print(f"{name:26s} | {cfg:22s} | iterative_scan={iter_mode:13s} ef_search={ef:3d} | recall@20 = {tot20/n:.3f}",flush=True)
json.dump({"|".join(map(str,k)):v for k,v in res.items()},open("recall_result.json","w"),indent=1)
print("FIN")
