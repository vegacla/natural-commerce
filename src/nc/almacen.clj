(ns nc.almacen
  "Hechos, compromisos, saldos, entidades y configuración versionada sobre
  XTDB v2 (etapa 2 del prototipo).

  Cada dato tiene dos tiempos (notación, sección 1): registro, cuándo lo supo el
  sistema, y validez, desde cuándo rige. XTDB entrega ambos. Ver un dato en una
  fecha (D17) es consultar los dos en ese instante: opción `:al`.

  Una sola base para todas las empresas (D22): todo documento lleva `:empresa`
  y toda lectura filtra por ella. Resguardos (D80): este es el único módulo que
  toca XTDB; toda función pública exige una empresa válida; `buscar` no acepta
  una empresa dentro del filtro; no se escribe un documento de otra empresa ni se
  pisa un compromiso ajeno. Los verifican `nc.separacion-test` y
  `nc.resguardos-test`.

  Bitácora (D82): `escribir!` exige la decisión de MARCO que produjo los
  cambios, la guarda en la misma transacción y marca cada documento escrito con
  `:nc/decision`. Solo la carga inicial de entidades (`cargar-entidades!`) se
  escribe sin decisión.

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
  documento que se borra de inmediato, sin dejar historia. El documento lleva la
  columna `:empresa`, que las comprobaciones de D80 nombran en SQL."
  [nodo]
  (xt/execute-tx nodo (vec (for [t (keys tablas)] [:put-docs t {:xt/id ::semilla :empresa ::semilla}])))
  (xt/execute-tx nodo (vec (for [t (keys tablas)] [:erase-docs t ::semilla]))))

(defn abrir
  "Arranca un nodo XTDB en el mismo proceso. Sin configuración, en memoria."
  ([] (abrir {}))
  ([config]
   (let [nodo (xtn/start-node config)]
     (crear-tablas! nodo)
     {:nodo nodo :ultimo (atom nil)})))

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
   :planes      :plan/id
   :tablas      :tabla/id
   :decisiones  :decision/id
   :marcas      nil})

(def ^:private versionadas
  "Tablas cuyo dato cambia en operación, con :version y :rige-desde (D8)."
  {:politicas [:politica/version :politica/rige-desde]
   :conceptos [:concepto/version :concepto/rige-desde]
   :caminos   [:camino/version :camino/rige-desde]
   :planes    [:plan/version :plan/rige-desde]
   :tablas    [:tabla/version :tabla/rige-desde]})

(defn- uuid-de [x] (UUID/nameUUIDFromBytes (.getBytes (pr-str x) "UTF-8")))

(defn- clave-saldo [doc] [(:saldo/concepto doc) (:saldo/clave doc)])

(defn- xt-id
  "Hechos y compromisos tienen un uuid propio. Lo demás se identifica por
  empresa e identificador de dominio, para que dos empresas puedan usar el mismo."
  [tabla empresa doc]
  (case tabla
    (:hechos :compromisos :decisiones) (get doc (tablas tabla))
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

(defn- registrar!
  "Anota el tiempo de registro de la última escritura de este almacén."
  [{:keys [ultimo]} system-time]
  (let [i (a-instante system-time)]
    (swap! ultimo #(if (and % (.isAfter ^Instant % i)) % i))
    i))

(defn- ahora
  "El tiempo de validez de una lectura sin fecha: el reloj, o la última escritura
  si el reloj no la alcanza. Un dato rige desde su tiempo de registro; leer con
  un reloj que no llega a él no ve lo que se acaba de escribir (ver
  knowledge/xtdb-bitemporalidad.md)."
  [{:keys [ultimo]}]
  (let [n (Instant/now) u @ultimo]
    (if (and u (.isAfter ^Instant u n)) u n)))

(defn- opciones-de-consulta
  "`:al t` ve el dato como era en t: lo que regía en t, según lo que se sabía en
  t. `:validez` y `:registro` fijan cada eje por separado. Sin fecha de
  validez, la de `ahora`."
  [alm {:keys [al validez registro]}]
  (cond-> {:current-time (or validez al (ahora alm))}
    (or registro al) (assoc :snapshot-time (or registro al))))

;; --- Resguardos (D80) ----------------------------------------------------------

(defn- error! [tipo mensaje datos]
  (throw (ex-info mensaje (assoc datos :nc/error tipo))))

(defn- exigir-empresa!
  "Toda operación nombra una empresa: un keyword. Nil, texto o un mapa fallan
  antes de llegar a XTDB, para que una consulta nunca quede sin filtro."
  [empresa]
  (when-not (keyword? empresa)
    (error! :empresa-invalida "Toda operación del almacén exige una empresa (keyword)" {:empresa empresa}))
  empresa)

;; --- Lectura -------------------------------------------------------------------

(defn- consulta
  "Arma una consulta XTQL que trae los documentos de una empresa en una tabla,
  cuyos campos son iguales a los del filtro. La empresa va siempre, y el filtro
  no puede traer otra."
  [tabla empresa filtro]
  (exigir-empresa! empresa)
  (when (contains? filtro :empresa)
    (error! :filtro-con-empresa "La empresa no va en el filtro: es un argumento aparte" {:filtro filtro}))
  (let [filtro (assoc filtro :empresa empresa)
        ks   (vec (keys filtro))
        syms (mapv #(symbol (str "p" %)) (range (count ks)))]
    (into [(list 'fn syms (list 'from tabla [(zipmap ks syms) '*]))]
          (map filtro ks))))

(defn buscar
  "Documentos de una empresa en una tabla cuyos campos coinciden con `filtro`."
  ([alm empresa tabla filtro] (buscar alm empresa tabla filtro {}))
  ([{:keys [nodo] :as alm} empresa tabla filtro opts]
   (mapv de-xt (xt/q nodo (consulta tabla empresa filtro)
                     (opciones-de-consulta alm opts)))))

(defn leer
  "Un documento por su identificador de dominio, o nil."
  ([alm empresa tabla id] (leer alm empresa tabla id {}))
  ([alm empresa tabla id opts]
   ;; Un saldo se busca por su identificador, no por su clave: XTDB no compara
   ;; bien un mapa cuando la columna guarda claves de formas distintas
   ;; (knowledge/xtdb-trampas.md).
   (let [filtro (if (= :saldos tabla)
                  {:xt/id (xt-id :saldos empresa {:saldo/concepto (first id) :saldo/clave (second id)})}
                  {(tablas tabla) id})]
     (first (buscar alm empresa tabla filtro opts)))))

(defn historia
  "Todas las versiones de un documento, con sus dos tiempos, de la más nueva a la
  más antigua."
  [{:keys [nodo]} empresa tabla id]
  (exigir-empresa! empresa)
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

(defn- put [tabla empresa doc & [periodo decision]]
  (exigir-empresa! empresa)
  (when (and (contains? doc :empresa) (not= empresa (:empresa doc)))
    (error! :documento-ajeno "El documento es de otra empresa" {:empresa empresa :documento-de (:empresa doc)}))
  [:put-docs (merge {:into tabla} periodo)
   (cond-> (assoc (a-xt doc) :xt/id (xt-id tabla empresa doc) :empresa empresa)
     decision (assoc :nc/decision decision))])

(defn marca
  "La marca de la empresa: cuántas escrituras lleva. 0 si ninguna."
  [{:keys [nodo] :as alm} empresa]
  (exigir-empresa! empresa)
  (or (:n (first (xt/q nodo [(list 'fn '[id] (list 'from :marcas '[{:xt/id id} n])) (xt-id :marcas empresa {})]
                       {:current-time (ahora alm)})))
      0))

(defn- marca-vista
  "Exige que la marca siga siendo la que se vio al leer, y la avanza."
  [empresa n]
  (let [id (xt-id :marcas empresa {})]
    [(if (zero? n)
       [:sql "ASSERT NOT EXISTS (SELECT 1 FROM marcas WHERE _id = ?)" [id]]
       [:sql "ASSERT EXISTS (SELECT 1 FROM marcas WHERE _id = ? AND n = ?)" [id n]])
     [:put-docs :marcas {:xt/id id :empresa empresa :n (inc n)}]]))

(defn- compromiso-propio
  "Un compromiso se reemplaza por su nuevo estado, pero solo si el identificador
  no pertenece a otra empresa: de lo contrario la escritura pisaría sus datos."
  [empresa compromiso decision]
  [[:sql "ASSERT NOT EXISTS (SELECT 1 FROM compromisos FOR ALL VALID_TIME WHERE _id = ? AND empresa <> ?)"
    [(:compromiso/id compromiso) empresa]]
   (put :compromisos empresa compromiso nil decision)])

(defn- ajenos
  "Identificadores de compromisos que ya pertenecen a otra empresa."
  [{:keys [nodo]} empresa compromisos]
  (vec (for [{id :compromiso/id} compromisos
             :let [duenos (set (map :empresa (xt/q nodo [(list 'fn '[id]
                                                               (list 'from :compromisos
                                                                     {:bind '[{:xt/id id} empresa]
                                                                      :for-valid-time :all-time}))
                                                         id])))]
             :when (seq (disj duenos empresa))]
         id)))

(defn- hecho-nuevo
  "Un hecho no se modifica (D16): si ya existe, la transacción completa falla."
  [empresa hecho decision]
  [[:sql "ASSERT NOT EXISTS (SELECT 1 FROM hechos FOR ALL VALID_TIME WHERE _id = ?)" [(:hecho/id hecho)]]
   (put :hechos empresa hecho nil decision)])

(defn- decision-nueva
  "La decisión se guarda una vez y no cambia: si ya existe, nada se escribe."
  [empresa decision]
  [[:sql "ASSERT NOT EXISTS (SELECT 1 FROM decisiones FOR ALL VALID_TIME WHERE _id = ?)" [(:decision/id decision)]]
   (put :decisiones empresa decision)])

(defn- exigir-decision!
  "Toda escritura lleva la decisión de MARCO que la produjo (D82). Si escribe
  algo, la decisión debe ser una ejecución, y declarar exactamente los hechos
  que se escriben."
  [{:keys [decision hechos compromisos saldos entidades]}]
  (let [escribe? (some seq [hechos compromisos saldos entidades])
        {:decision/keys [id resultado]} decision]
    (cond
      (not (and (uuid? id) (keyword? resultado)))
      (error! :sin-decision "Toda escritura lleva la decisión de MARCO que la produjo (D82)" {})

      (and escribe? (not= :ejecutado resultado))
      (error! :decision-no-ejecutada "Solo una decisión ejecutada escribe cambios (D82)" {:resultado resultado})

      (not= (set (map :hecho/id hechos)) (set (:decision/hechos decision)))
      (error! :decision-inconsistente "La decisión no declara los hechos que se escriben (D82)"
              {:declarados (:decision/hechos decision) :escritos (map :hecho/id hechos)}))))

(defn escribir!
  "Escribe en una sola transacción la decisión de MARCO y lo que produjo: hechos,
  compromisos, saldos y entidades de una empresa (D82). Sin decisión, o con una
  que no es una ejecución, no escribe cambios. Una decisión de rechazo se
  escribe sola. Un hecho que ya existe aborta todo: nada queda escrito. Un
  compromiso, saldo o entidad se reemplaza por su nuevo estado, y XTDB guarda
  el anterior.

  `:marca` es la marca de la empresa que vio quien preparó los cambios. Si otra
  escritura la movió desde entonces, nada se escribe y falla con :conflicto.

  `:registro` fija el tiempo de registro; solo sirve para cargar datos antiguos y
  para pruebas, y debe ser posterior al de la última transacción."
  ([alm empresa cambios] (escribir! alm empresa cambios {}))
  ([{:keys [nodo] :as alm} empresa {:keys [decision hechos compromisos saldos entidades] :as cambios} {:keys [registro] n :marca}]
   (exigir-empresa! empresa)
   (exigir-decision! cambios)
   (let [d   (:decision/id decision)
         ops (concat (when n (marca-vista empresa n))
                     (decision-nueva empresa decision)
                     (mapcat #(hecho-nuevo empresa % d) hechos)
                     (mapcat #(compromiso-propio empresa % d) compromisos)
                     (map #(put :saldos empresa % nil d) saldos)
                     (map #(put :entidades empresa % nil d) entidades))]
     (try
       (let [{:keys [tx-id system-time]} (xt/execute-tx nodo (vec ops)
                                                        (cond-> {} registro (assoc :system-time registro)))]
         {:tx tx-id :registro (registrar! alm system-time)})
       (catch Exception e
         (cond
           (not (re-find #"Assert failed" (str (ex-message e)))) (throw e)
           (and n (not= n (marca alm empresa)))
           (error! :conflicto "Otra escritura cambió el estado de la empresa desde que se leyó" {:marca n})
           (seq (ajenos alm empresa compromisos))
           (error! :documento-ajeno "Un compromiso con ese identificador pertenece a otra empresa"
                   {:compromisos (ajenos alm empresa compromisos)})
           :else
           (error! :hecho-existente "Un hecho no se modifica: ya existe un hecho con ese identificador"
                   {:hechos (map :hecho/id hechos)})))))))

(defn cargar-entidades!
  "Carga inicial de datos maestros (productos, personas, bodegas...) sin pasar
  por MARCO. Es lo único que se escribe sin decisión (D82): no son hechos,
  compromisos ni saldos. Una entidad creada o cambiada por una operación pasa
  por `escribir!`."
  [{:keys [nodo] :as alm} empresa entidades]
  (exigir-empresa! empresa)
  (let [{:keys [tx-id system-time]} (xt/execute-tx nodo (mapv #(put :entidades empresa %) entidades))]
    {:tx tx-id :registro (registrar! alm system-time)}))

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
       {:tx tx-id :registro (registrar! alm system-time)}))))

(defn retirar!
  "Una política, concepto o camino deja de regir desde `:desde` (por defecto,
  ahora). Su historia queda: se puede ver como regía antes (D75)."
  ([alm empresa tabla id] (retirar! alm empresa tabla id {}))
  ([{:keys [nodo] :as alm} empresa tabla id {:keys [desde]}]
   (exigir-empresa! empresa)
   (when-not (versionadas tabla)
     (error! :tabla-no-versionada (str tabla " no tiene versiones") {:tabla tabla}))
   (let [{:keys [tx-id system-time]}
         (xt/execute-tx nodo [[:delete-docs {:from tabla :valid-from (or desde (Date.))}
                               (xt-id tabla empresa {(tablas tabla) id})]])]
     {:tx tx-id :registro (registrar! alm system-time)})))

(defn vigente
  "La versión de una política, concepto o camino que rige en `:al` (o ahora), según
  lo que se sabía entonces. Con `:validez` y `:registro` se separan los ejes: qué
  regirá en una fecha según lo que se sabe hoy."
  ([alm empresa tabla id] (vigente alm empresa tabla id {}))
  ([alm empresa tabla id opts] (leer alm empresa tabla id opts)))
