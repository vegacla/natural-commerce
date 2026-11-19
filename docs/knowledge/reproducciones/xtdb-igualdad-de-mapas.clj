;; Reproducción mínima (2026-11-19, XTDB 2.2.0): filtrar por igualdad de un mapa
;; deja de encontrar documentos cuando la columna tiene mapas de formas distintas.
;; Uso: JAVA_CMD=... deps -M:xtdb -i docs/knowledge/reproducciones/xtdb-igualdad-de-mapas.clj

(require '[xtdb.node :as xtn] '[xtdb.api :as xt])
(defn q [n clave] (count (xt/q n [(list 'fn '[p0] (list 'from :t '[{:clave p0} *])) clave])))
(with-open [n (xtn/start-node {})]
  (xt/execute-tx n [[:put-docs :t {:xt/id 1 :clave {:producto :P-100 :bodega :central}}]])
  (println "1) solo forma {:producto :bodega}:" (q n {:producto :P-100 :bodega :central}))
  (xt/execute-tx n [[:put-docs :t {:xt/id 2 :clave {:cliente :c1}}]])
  (println "2) después de agregar {:cliente}: busca {:producto :bodega} ->" (q n {:producto :P-100 :bodega :central})
           "; busca {:cliente} ->" (q n {:cliente :c1}))
  (println "3) mismo mapa, otro orden de claves:" (q n {:bodega :central :producto :P-100}))
  (println "4) igualdad en SQL:" (count (xt/q n ["SELECT * FROM t WHERE clave = ?" {:producto :P-100 :bodega :central}]))))
(with-open [n (xtn/start-node {})]
  (xt/execute-tx n [[:put-docs :t {:xt/id 2 :clave {:cliente :c1}}]])
  (xt/execute-tx n [[:put-docs :t {:xt/id 1 :clave {:producto :P-100 :bodega :central}}]])
  (println "5) orden inverso de escritura: busca {:producto :bodega} ->" (q n {:producto :P-100 :bodega :central})))
(with-open [n (xtn/start-node {})]
  (xt/execute-tx n [[:put-docs :t {:xt/id 1 :clave {:producto :P-100 :bodega :central}} {:xt/id 3 :clave {:producto :P-200 :bodega :norte}}]])
  (println "6) dos mapas de la misma forma: busca P-100 ->" (q n {:producto :P-100 :bodega :central})))
(System/exit 0)
