(ns nc.almacen
  "Hechos, compromisos, saldos, entidades y configuración versionada sobre
  XTDB v2 (etapa 2 del prototipo).

  Cada dato tiene dos tiempos (notación, sección 1): registro, cuándo lo supo el
  sistema, y validez, desde cuándo rige. XTDB entrega ambos. Ver un dato en una
  fecha (D17) es consultar los dos en ese instante: opción `:al`.

  Una sola base para todas las empresas (D22): todo documento lleva `:empresa`
  y toda lectura filtra por ella.

  Verificar y aplicar ocurren en una transacción (D49, D74): cada empresa tiene
  una marca que crece con cada escritura. Quien escribe declara la marca que vio
  al leer; si otra escritura la movió, la transacción completa falla."
  (:require [clojure.walk :as walk]
            [xtdb.api :as xt]
            [xtdb.node :as xtn])
  (:import (java.time Instant ZonedDateTime OffsetDateTime)
           (java.util Date UUID)))

;; --- Nodo --------------------------------------------------------------------

(declare tablas)

(defn- crear-tablas!
  "XTDB crea una tabla con su primera escritura, y su SQL rechaza nombrar una
  tabla que no existe, aunque sea dentro de un ASSERT. Se crea cada tabla con un
  documento que se borra de inmediato, sin dejar historia."
  [nodo]
  (xt/execute-tx nodo (vec (for [t (keys tablas)] [:put-docs t {:xt/id ::semilla}])))
  (xt/execute-tx nodo (vec (for [t (keys tablas)] [:erase-docs t ::semilla]))))

(defn abrir
  "Arranca un nodo XTDB en el mismo proceso. Sin configuración, en memoria."
  ([] (abrir {}))
  ([config]
   (let [nodo (xtn/start-node config)]
     (crear-tablas! nodo)
     {:nodo nodo})))

(defn cerrar [{:keys [nodo]}] (.close ^java.lang.AutoCloseable nodo))

;; --- Documentos ----------------------------------------------------------------

(def tablas
  "Tabla → clave del identificador de dominio."
  {:hechos      :hecho/id
   :compromisos :compromiso/id
   :saldos      nil
   :entidades   :entidad/id
   :politicas   :politica/id
   :conceptos   :concepto/id
   :caminos     :camino/id
   :marcas      nil})

(def ^:private versionadas
  "Tablas cuyo dato cambia en operación, con :version y :rige-desde (D8)."
  {:politicas [:politica/version :politica/rige-desde]
   :conceptos [:concepto/version :concepto/rige-desde]
   :caminos   [:camino/version :camino/rige-desde]})

(defn- uuid-de [x] (UUID/nameUUIDFromBytes (.getBytes (pr-str x) "UTF-8")))

(defn- clave-saldo [doc] [(:saldo/concepto doc) (:saldo/clave doc)])

(defn- xt-id
  "Hechos y compromisos tienen un uuid propio. Lo demás se identifica por
  empresa e identificador de dominio, para que dos empresas puedan usar el mismo."
  [tabla empresa doc]
  (case tabla
    (:hechos :compromisos) (get doc (tablas tabla))
    :saldos                (uuid-de [empresa tabla (clave-saldo doc)])
    :marcas                (uuid-de [empresa tabla])
    (uuid-de [empresa tabla (get doc (tablas tabla))])))

(defn- a-instante [x]
  (cond
    (instance? ZonedDateTime x)  (.toInstant ^ZonedDateTime x)
    (instance? OffsetDateTime x) (.toInstant ^OffsetDateTime x)
    :else                        x))

(defn- a-fecha
  "XTDB devuelve las fechas como ZonedDateTime; el núcleo usa java.util.Date."
  [x]
  (let [i (a-instante x)]
    (if (instance? Instant i) (Date/from i) x)))

(def ^:private tiempos-xt [:xt/valid-from :xt/valid-to :xt/system-from])

(defn- a-xt
  "XTDB no guarda símbolos, y las expresiones de políticas, conceptos y contratos
  los usan como referencias. Se guardan como {:nc/simbolo \"entrada/x\"}."
  [doc]
  (walk/postwalk #(if (symbol? %) {:nc/simbolo (str %)} %) doc))

(defn- de-simbolo [x]
  (if (and (map? x) (= [:nc/simbolo] (keys x))) (symbol (:nc/simbolo x)) x))

(defn- de-xt
  "Los datos de dominio vuelven con fechas como java.util.Date. Los tiempos de
  XTDB quedan como Instant: tienen microsegundos, y Date los truncaría, con lo que
  una consulta `:al` un tiempo de registro dejaría fuera esa misma transacción."
  [doc]
  (when doc
    (merge (walk/postwalk (comp a-fecha de-simbolo) (apply dissoc doc :xt/id tiempos-xt))
           (update-vals (select-keys doc tiempos-xt) a-instante))))

;; --- Tiempos -------------------------------------------------------------------

(defn- opciones-de-consulta
  "`:al t` ve el dato como era en t: lo que regía en t, según lo que se sabía en
  t. `:validez` y `:registro` fijan cada eje por separado."
  [{:keys [al validez registro]}]
  (cond-> {}
    (or validez al)  (assoc :current-time (or validez al))
    (or registro al) (assoc :snapshot-time (or registro al))))

;; --- Lectura -------------------------------------------------------------------

(defn- consulta
  "Arma una consulta XTQL que trae los documentos de una tabla cuyos campos son
  iguales a los del filtro."
  [tabla filtro]
  (let [ks   (vec (keys filtro))
        syms (mapv #(symbol (str "p" %)) (range (count ks)))]
    (into [(list 'fn syms (list 'from tabla [(zipmap ks syms) '*]))]
          (map filtro ks))))

(defn buscar
  "Documentos de una empresa en una tabla cuyos campos coinciden con `filtro`."
  ([alm empresa tabla filtro] (buscar alm empresa tabla filtro {}))
  ([{:keys [nodo]} empresa tabla filtro opts]
   (mapv de-xt (xt/q nodo (consulta tabla (assoc filtro :empresa empresa))
                     (opciones-de-consulta opts)))))

(defn leer
  "Un documento por su identificador de dominio, o nil."
  ([alm empresa tabla id] (leer alm empresa tabla id {}))
  ([alm empresa tabla id opts]
   (let [filtro (if (= :saldos tabla)
                  {:saldo/concepto (first id) :saldo/clave (second id)}
                  {(tablas tabla) id})]
     (first (buscar alm empresa tabla filtro opts)))))

(defn historia
  "Todas las versiones de un documento, con sus dos tiempos, de la más nueva a la
  más antigua."
  [{:keys [nodo]} empresa tabla id]
  (let [xid (xt-id tabla empresa (if (= :saldos tabla)
                                   {:saldo/concepto (first id) :saldo/clave (second id)}
                                   {(tablas tabla) id}))]
    (->> (xt/q nodo [(list 'fn '[id e]
                           (list 'from tabla {:bind '[{:xt/id id :empresa e} * xt/valid-from xt/valid-to xt/system-from]
                                              :for-valid-time :all-time}))
                     xid empresa])
         (map de-xt)
         (sort-by (juxt :xt/system-from :xt/valid-from))
         reverse
         vec)))

;; --- Escritura -----------------------------------------------------------------

(defn- error! [tipo mensaje datos]
  (throw (ex-info mensaje (assoc datos :nc/error tipo))))

(defn- put [tabla empresa doc & [periodo]]
  [:put-docs (merge {:into tabla} periodo)
   (assoc (a-xt doc) :xt/id (xt-id tabla empresa doc) :empresa empresa)])

(defn marca
  "La marca de la empresa: cuántas escrituras lleva. 0 si ninguna."
  [{:keys [nodo]} empresa]
  (or (:n (first (xt/q nodo [(list 'fn '[id] (list 'from :marcas '[{:xt/id id} n])) (xt-id :marcas empresa {})])))
      0))

(defn- marca-vista
  "Exige que la marca siga siendo la que se vio al leer, y la avanza."
  [empresa n]
  (let [id (xt-id :marcas empresa {})]
    [(if (zero? n)
       [:sql "ASSERT NOT EXISTS (SELECT 1 FROM marcas WHERE _id = ?)" [id]]
       [:sql "ASSERT EXISTS (SELECT 1 FROM marcas WHERE _id = ? AND n = ?)" [id n]])
     [:put-docs :marcas {:xt/id id :empresa empresa :n (inc n)}]]))

(defn- hecho-nuevo
  "Un hecho no se modifica (D16): si ya existe, la transacción completa falla."
  [empresa hecho]
  [[:sql "ASSERT NOT EXISTS (SELECT 1 FROM hechos FOR ALL VALID_TIME WHERE _id = ?)" [(:hecho/id hecho)]]
   (put :hechos empresa hecho)])

(defn escribir!
  "Escribe en una sola transacción hechos, compromisos, saldos y entidades de una
  empresa. Un hecho que ya existe aborta todo: nada queda escrito. Un compromiso,
  saldo o entidad se reemplaza por su nuevo estado, y XTDB guarda el anterior.

  `:marca` es la marca de la empresa que vio quien preparó los cambios. Si otra
  escritura la movió desde entonces, nada se escribe y falla con :conflicto.

  `:registro` fija el tiempo de registro; solo sirve para cargar datos antiguos y
  para pruebas, y debe ser posterior al de la última transacción."
  ([alm empresa cambios] (escribir! alm empresa cambios {}))
  ([{:keys [nodo] :as alm} empresa {:keys [hechos compromisos saldos entidades]} {:keys [registro] n :marca}]
   (let [ops (concat (when n (marca-vista empresa n))
                     (mapcat #(hecho-nuevo empresa %) hechos)
                     (map #(put :compromisos empresa %) compromisos)
                     (map #(put :saldos empresa %) saldos)
                     (map #(put :entidades empresa %) entidades))]
     (try
       (let [{:keys [tx-id system-time]} (xt/execute-tx nodo (vec ops)
                                                        (cond-> {} registro (assoc :system-time registro)))]
         {:tx tx-id :registro (a-instante system-time)})
       (catch Exception e
         (cond
           (not (re-find #"Assert failed" (str (ex-message e)))) (throw e)
           (and n (not= n (marca alm empresa)))
           (error! :conflicto "Otra escritura cambió el estado de la empresa desde que se leyó" {:marca n})
           :else
           (error! :hecho-existente "Un hecho no se modifica: ya existe un hecho con ese identificador"
                   {:hechos (map :hecho/id hechos)})))))))

(defn publicar!
  "Publica una versión de una política, concepto o camino, vigente desde su
  `:rige-desde` (D8, D9). Si ya hay una versión que rige más tarde, la nueva
  rige solo hasta ella, sin pisarla."
  ([alm empresa tabla doc] (publicar! alm empresa tabla doc {}))
  ([{:keys [nodo] :as alm} empresa tabla doc {:keys [registro]}]
   (let [[k-version k-desde] (or (versionadas tabla)
                                 (error! :tabla-no-versionada (str tabla " no tiene versiones") {:tabla tabla}))
         desde   (or (get doc k-desde)
                     (error! :sin-vigencia (str "Falta " k-desde) {:doc doc}))
         despues (->> (historia alm empresa tabla (get doc (tablas tabla)))
                      (map k-desde)
                      (filter #(pos? (compare % desde)))
                      sort
                      first)
         periodo (cond-> {:valid-from desde} despues (assoc :valid-to despues))]
     (when-not (get doc k-version)
       (error! :sin-version (str "Falta " k-version) {:doc doc}))
     (let [{:keys [tx-id system-time]}
           (xt/execute-tx nodo [(put tabla empresa doc periodo)]
                          (cond-> {} registro (assoc :system-time registro)))]
       {:tx tx-id :registro (a-instante system-time)}))))

(defn retirar!
  "Una política, concepto o camino deja de regir desde `:desde` (por defecto,
  ahora). Su historia queda: se puede ver como regía antes (D75)."
  ([alm empresa tabla id] (retirar! alm empresa tabla id {}))
  ([{:keys [nodo]} empresa tabla id {:keys [desde]}]
   (when-not (versionadas tabla)
     (error! :tabla-no-versionada (str tabla " no tiene versiones") {:tabla tabla}))
   (let [{:keys [tx-id system-time]}
         (xt/execute-tx nodo [[:delete-docs {:from tabla :valid-from (or desde (Date.))}
                               (xt-id tabla empresa {(tablas tabla) id})]])]
     {:tx tx-id :registro (a-instante system-time)})))

(defn vigente
  "La versión de una política, concepto o camino que rige en `:al` (o ahora), según
  lo que se sabía entonces. Con `:validez` y `:registro` se separan los ejes: qué
  regirá en una fecha según lo que se sabe hoy."
  ([alm empresa tabla id] (vigente alm empresa tabla id {}))
  ([alm empresa tabla id opts] (leer alm empresa tabla id opts)))
