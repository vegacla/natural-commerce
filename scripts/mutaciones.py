"""Pruebas de mutación de los resguardos de separación por empresa (D80).

Rompe cada resguardo de src/nc/almacen.clj (y la regla de que solo el almacén
toca XTDB), corre la suite y muestra qué pruebas fallan. Cada mutación debe
hacer fallar al menos una prueba; si no, falta una prueba. Restaura los archivos
al terminar. Uso, desde la raíz del proyecto: python scripts/mutaciones.py
Requiere `deps` en el PATH. Ajustar JAVA_CMD si el JDK 21 está en otra ruta.
"""
import subprocess, re, shutil, os
ALM='src/nc/almacen.clj'; WEB='src/nc/sara/web.clj'; SARA='src/nc/sara.clj'
EJE='src/nc/ejecutor.clj'; RES='src/nc/resolvedor.clj'
env=dict(os.environ, JAVA_CMD=os.environ.get("JAVA_CMD") or "C:/Users/ceva2/AppData/Local/Programs/Temurin21/jdk-21.0.13+9/bin/java.exe")
def run():
    r=subprocess.run("deps -M:test",shell=True,capture_output=True,text=True,env=env,encoding='utf-8',errors='replace')
    o=r.stdout+r.stderr
    m=re.search(r"Ran (\d+) tests.*?\n(\d+) failures, (\d+) errors",o,re.S)
    fails=sorted(set(re.findall(r"(?:FAIL|ERROR) in \(([^)]+)\)",o)))
    return (m.groups() if m else ("?",)),fails
muts=[
 ("historia sin filtro de empresa",ALM,"{:bind '[{:xt/id id :empresa e} * xt/valid-from","{:bind '[{:xt/id id} * xt/valid-from"),
 ("consulta sin empresa",ALM,"(let [filtro (assoc filtro :empresa empresa)","(let [filtro filtro"),
 ("sin exigir-empresa en marca",ALM,"  (exigir-empresa! empresa)\n  (or (:n","  (or (:n"),
 ("sin comprobar compromiso ajeno",ALM,'''[[:sql "ASSERT NOT EXISTS (SELECT 1 FROM compromisos FOR ALL VALID_TIME WHERE _id = ? AND empresa <> ?)"
    [(:compromiso/id compromiso) empresa]]
   (put :compromisos empresa compromiso nil decision)]''',"[(put :compromisos empresa compromiso nil decision)]"),
 ("filtro con empresa permitido",ALM,"(when (contains? filtro :empresa)","(when false"),
 # D82: bitácora y garantía estructural
 ("el almacén escribe sin decisión",ALM,"   (exigir-decision! cambios)\n",""),
 ("un rechazo no queda en la bitácora",EJE,"(assoc resolucion :estado r :decision (anotar! estado plan r opts))","(assoc resolucion :estado r)"),
 ("se ejecuta sin confirmación",EJE,"    (and (not confirmado) (= :requerida (:confirmacion resolucion))) :por-confirmar\n",""),
 ("aplicar! no revisa que el plan esté listo",EJE,"    (not= :listo (:estado resolucion))                               (:estado resolucion)\n",""),
 # D83 y M1: plan y entidades
 ("una operación fuera del plan se ejecuta",RES,"(vec (concat (when-not en-plan?","(vec (concat (when false"),
 ("una entidad desactivada se puede usar",RES,"(false? (:entidad/activa (est/leer estado valor)))","false"),
]
res=[]
for nombre,f,a,b in muts:
    orig=open(f,encoding='utf-8',newline='').read()
    o=orig.replace('\r\n','\n')
    if a not in o: res.append((nombre,"PATRÓN NO ENCONTRADO",[])); continue
    try:
        open(f,'w',encoding='utf-8',newline='').write(o.replace(a,b,1))
        res.append((nombre,)+run())
    finally:
        open(f,'w',encoding='utf-8',newline='').write(orig)
# mutaciones estáticas: otro módulo requiere xtdb, u otro módulo escribe sin pasar por el ejecutor
for nombre,f,cambio in [("web.clj requiere xtdb",WEB,lambda o:o.replace("(:require","(:require [xtdb.api :as xt]",1)),
                        ("sara.clj llama a alm/escribir!",SARA,lambda o:o+"\n(comment (alm/escribir! nil nil {}))\n")]:
    orig=open(f,encoding='utf-8',newline='').read()
    try:
        open(f,'w',encoding='utf-8',newline='').write(cambio(orig))
        res.append((nombre,)+run())
    finally:
        open(f,'w',encoding='utf-8',newline='').write(orig)
for r in res: print(r)
