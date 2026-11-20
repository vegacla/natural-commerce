(ns nc.disco-test
  "D102, primer paso del pendiente: medir en disco las búsquedas que no van por
  `_id`, con muchas empresas. En memoria (E26) recorren la tabla con los datos de
  todas las empresas. En disco, XTDB guarda los datos en bloques con metadatos
  por página; la pregunta es si esos metadatos descartan las páginas de otras
  empresas.

  Una empresa chica (X) y `NC_DISCO` empresas más (por omisión 49), cada una con
  `NC_DISCO_DIAS` días de operación (por omisión 20), todo por MARCO. Se miden las
  búsquedas de X en cuatro momentos:
  1. sin las otras empresas;
  2. con sus datos todavía en el índice vivo (memoria), sin cerrar bloque;
  3. con los datos pasados a un bloque en disco;
  4. con el nodo reabierto desde el disco.

  Corre solo con NC_DISCO (unos minutos); sin ella se omite."
  (:require [clojure.test :refer [deftest is testing]]
            [nc.almacen :as alm]
            [nc.marco-test :as m]
            [nc.volumen-test]
            [xtdb.db-catalog :as db]
            [xtdb.log :as xt-log])
  (:import (java.io File)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.time Duration)))

(def ^:private otras (some-> (System/getenv "NC_DISCO") parse-long))
(def ^:private dias (or (some-> (System/getenv "NC_DISCO_DIAS") parse-long) 20))

(defn- config [^Path raiz]
  {:log     [:local {:path (.resolve raiz "log")}]
   :storage [:local {:path (.resolve raiz "almacen")}]})

(defn- archivos [^Path p] (count (filter #(.isFile ^File %) (file-seq (.toFile p)))))

(defn- cerrar-bloque!
  "Pasa al almacenamiento lo que está solo en el log y espera el archivo nuevo."
  [{:keys [nodo]} ^Path almacen]
  (let [antes (archivos almacen)]
    (xt-log/send-flush-block-msg! (db/primary-db nodo))
    (xt-log/sync-node nodo (Duration/ofSeconds 120))
    (loop [n 0]
      (when (and (= antes (archivos almacen)) (< n 600))
        (Thread/sleep 100)
        (recur (inc n))))
    (- (archivos almacen) antes)))

(defn- mediana-ms [n f]
  (dotimes [_ n] (f))
  (let [ts (sort (for [_ (range n)]
                   (let [t (System/nanoTime)] (f) (/ (- (System/nanoTime) t) 1e6))))]
    (nth ts (quot n 2))))

(defn- medir
  "Mediana de cada búsqueda de X, en ms."
  [alm x {:keys [orden caso]}]
  (let [e (:empresa x)
        b (fn [tabla filtro] #(alm/buscar alm e tabla filtro))]
    {:por-id      (mediana-ms 25 #(alm/leer alm e :hechos orden))
     :hechos-caso (mediana-ms 25 (b :hechos {:hecho/caso caso}))
     :raiz        (mediana-ms 25 (b :compromisos {:compromiso/raiz caso}))
     :origen      (mediana-ms 25 (b :compromisos {:compromiso/origen orden}))
     :por-tipo    (mediana-ms 25 (b :hechos {:hecho/tipo :compras/orden}))
     :consulta    (mediana-ms 25 #(m/ejecutar! x :persona/ana :compras/consultar-estado {:orden orden}))}))

(def ^:private columnas [:por-id :hechos-caso :raiz :origen :por-tipo :consulta])

(defn- fila [nombre med]
  (println (apply format (str "  %-42s" (apply str (repeat (count columnas) "%12.1f")))
                  nombre (map #(double (med %)) columnas))))

(defn- año!
  "Una compra primero: sin existencia no se vende (D107)."
  [e]
  (#'nc.volumen-test/compra-simple! e)
  (with-redefs-fn {#'nc.volumen-test/dias dias}
    #(#'nc.volumen-test/año! e false)))

(deftest busquedas-en-disco-con-muchas-empresas
  (if-not otras
    (do (println "nc.disco-test: sin NC_DISCO, se omite la medición en disco.") (is true))
    (let [raiz    (Files/createTempDirectory "nc-disco" (make-array FileAttribute 0))
          almacen (.resolve raiz "almacen")
          a       (alm/abrir (config raiz))]
      (try
        (binding [m/*alm* a]
          (let [x      (m/empresa! {})
                orden  (#'nc.volumen-test/compra-simple! x)
                caso   (:hecho/caso (alm/leer a (:empresa x) :hechos orden))
                ids    {:orden orden :caso caso}
                _      (dotimes [i 10] (#'nc.volumen-test/venta! x i))
                m0     (medir a x ids)
                t      (System/nanoTime)
                _      (dotimes [i otras]
                         (año! (m/empresa! {}))
                         (when (zero? (mod (inc i) 5))
                           (println (format "  cargadas %d de %d empresas (%.0f s)" (inc i) otras
                                            (/ (- (System/nanoTime) t) 1e9)))
                           (flush)))
                carga  (/ (- (System/nanoTime) t) 1e9)
                total  (reduce + (for [e @m/empresas] (count (alm/buscar a e :hechos {}))))
                m1     (medir a x ids)
                nuevos (cerrar-bloque! a almacen)
                m2     (medir a x ids)
                _      (alm/cerrar a)
                a      (alm/abrir (config raiz))
                x      (assoc x :almacen a)
                m3     (binding [m/*alm* a] (medir a x ids))]
            (println (str "Disco: X y " otras " empresas con " dias " días cada una ("
                          (format "carga %.0f s" carga) ", " nuevos " archivos de bloque, "
                          total " hechos en total)"))
            (println (apply format (str "  %-42s" (apply str (repeat (count columnas) "%12s")))
                            "momento (ms, mediana de 25)" (map name columnas)))
            (fila "1. X sola" m0)
            (fila "2. con las otras, en memoria" m1)
            (fila "3. con las otras, en un bloque en disco" m2)
            (fila "4. nodo reabierto desde el disco" m3)
            (testing "lo que X ve no cambia"
              (is (= 4 (count (alm/buscar a (:empresa x) :hechos {:hecho/caso caso})))))
            (alm/cerrar a)))
        (finally
          (reset! m/empresas #{})
          (doseq [^File f (reverse (file-seq (.toFile raiz)))] (.delete f)))))))
