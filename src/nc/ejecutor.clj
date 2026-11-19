(ns nc.ejecutor
  "Ejecuta una operación aplicando los efectos que declara su contrato (notación
  5.1). Es uno solo para todas las operaciones: ninguna tiene código propio (P6,
  D66).

  Antes de aplicar nada pregunta al resolvedor. Ejecuta solo si la operación está
  lista y quien ejecuta confirmó, o una política lo exime (D53). Todo lo que la
  operación produce se escribe en una transacción.

  Cada evaluación deja una decisión en la bitácora (D82), también las que no
  ejecutan: rechazos, faltas de confirmación, conflictos y errores del contrato.
  Es el único módulo que llama a `alm/escribir!`."
  (:require [malli.core :as m]
            [malli.error :as me]
            [nc.almacen :as alm]
            [nc.esquema :as esq]
            [nc.estado :as est]
            [nc.expresion :as e]
            [nc.resolvedor :as r]
            [nc.validar :as val]
            [nc.valor :as v]))

;; La aplicación de los efectos avanza sobre un mapa:
;;   :estado :op :actor  de quién y dónde
;;   :refs               referencias, que crecen con cada `:como`
;;   :hechos             hechos registrados, en orden
;;   :compromisos        id → compromiso abierto o cambiado por la operación
;;   :entidades          id → entidad creada o cambiada por la operación
;;   :nuevos             ids de los compromisos que abrió
;;   :saldos             [concepto clave] → saldo movido
;;   :caso               compromiso raíz, si ya se conoce
;;   :traza              versiones de los conceptos usados (D48)

(defn- pendientes [app]
  {:hechos      (into {} (map (juxt :hecho/id identity)) (:hechos app))
   :compromisos (:compromisos app)
   :entidades   (:entidades app)
   :saldos      (:saldos app)})

(defn- contexto [app]
  (est/contexto (:estado app) (:refs app) (pendientes app) (:traza app)))

(defn- sin-nada [m] (into {} (remove (comp nil? val)) m))

;; --- Hechos ----------------------------------------------------------------------

(defn- mover
  "Actualiza los saldos que mueve el tipo de hecho (D57), en la misma operación."
  [app h tipo-hecho]
  (reduce (fn [app {:keys [saldo clave suma]}]
            (let [ctx    (assoc-in (contexto app) [:refs 'hecho] h)
                  clave  (e/evaluar clave ctx)
                  k      [saldo clave]
                  {:keys [almacen empresa]} (:estado app)
                  actual (or (get-in app [:saldos k])
                             (alm/leer almacen empresa :saldos k)
                             {:saldo/concepto saldo :saldo/clave clave :saldo/valor {}})]
              (assoc-in app [:saldos k]
                        (update actual :saldo/valor #(merge-with v/sumar % (e/evaluar suma ctx))))))
          app
          (:tipo-hecho/mueve tipo-hecho)))

(defn- registra-hecho [app [tipo datos]]
  (let [{:keys [estado actor op refs]} app
        tipo-hecho (or (get-in estado [:catalogo :tipos-hecho tipo])
                       (e/error! :tipo-hecho-desconocido (str "No existe el tipo de hecho " tipo) {:tipo tipo}))
        forma      (:tipo-hecho/datos tipo-hecho)
        datos      (sin-nada (select-keys datos (keys (esq/campos-de forma))))
        h          {:hecho/id    (random-uuid)
                    :hecho/tipo  tipo
                    :hecho/en    (get refs 'ahora)
                    :hecho/actor actor
                    :hecho/datos datos
                    :hecho/causa {:operacion op}}]
    (when-let [error (m/explain (esq/esquema-de-datos forma) datos)]
      (e/error! :datos-invalidos (str "Datos inválidos para " tipo ": " (me/humanize error)) {:tipo tipo}))
    [(mover (update app :hechos conj h) h tipo-hecho) h]))

;; --- Compromisos -----------------------------------------------------------------

(defn- extinguido? [c] (= :extinguido (:compromiso/estado c)))

(defn- de-objetivo
  "Los compromisos sobre los que actúa un verbo: uno por su identificador, o los
  no extinguidos de una colección (T9). Un compromiso extinguido no cambia (D65)."
  [app x]
  (if (sequential? x)
    (remove extinguido? x)
    (let [c (est/leer (:estado app) (pendientes app) x)]
      (cond
        (not (contains? c :compromiso/id)) (e/error! :no-es-compromiso (str (pr-str x) " no es un compromiso") {:id x})
        (extinguido? c) (e/error! :compromiso-extinguido (str "El compromiso " x " ya está extinguido") {:id x})
        :else [c]))))

(defn- cambiar [app cs f]
  (let [cs (mapv f cs)]
    [(reduce #(assoc-in %1 [:compromisos (:compromiso/id %2)] %2) app cs)
     (if (next cs) cs (first cs))]))

(defn- extinguir [app c causa]
  (assoc c :compromiso/estado :extinguido
           :compromiso/extincion {:causa causa :hecho (:hecho/id (first (:hechos app)))}))

(defn- abre-compromiso [app [{:keys [tipo deudor acreedor estado vence origen objeto saldo]}]]
  (let [id   (random-uuid)
        raiz (get-in app [:caso :compromiso/id] id)
        c    (sin-nada {:compromiso/id       id
                        :compromiso/tipo     tipo
                        :compromiso/deudor   deudor
                        :compromiso/acreedor acreedor
                        :compromiso/objeto   objeto
                        :compromiso/saldo    (or saldo (into {} (filter (comp v/valor? val)) objeto))
                        :compromiso/estado   (or estado :activo)
                        :compromiso/vence    vence
                        :compromiso/origen   (or origen (:hecho/id (first (:hechos app))))
                        :compromiso/raiz     raiz})
        app  (-> app (assoc-in [:compromisos id] c) (update :nuevos conj id))]
    [(cond-> app (= raiz id) (-> (assoc :caso c) (assoc-in [:refs 'caso] c))) c]))

(defn- modifica-compromiso [app [x cambios]]
  (when (contains? cambios :saldo)
    (e/error! :modifica-saldo "modifica-compromiso cambia los términos, no el saldo (D56)" {:cambios cambios}))
  (when (= :extinguido (:estado cambios))
    (e/error! :modifica-estado "Un compromiso se extingue con :extingue-compromiso" {:cambios cambios}))
  (cambiar app (de-objetivo app x) #(merge % (update-keys cambios (fn [k] (keyword "compromiso" (name k)))))))

(defn- reduce-compromiso
  "Resta del saldo. Si todo el saldo llega a cero o menos, el compromiso queda
  extinguido por cumplimiento (D69)."
  [app [x cuanto]]
  (let [cs (de-objetivo app x)]
    (when (next cs)
      (e/error! :reduccion-ambigua "Reducir varios compromisos a la vez no está definido" {:compromisos (map :compromiso/id cs)}))
    (cambiar app cs (fn [c]
                      (let [c (update c :compromiso/saldo
                                      #(merge-with v/restar % (select-keys cuanto (keys %))))]
                        (if (every? #(not (pos? (v/numero %))) (vals (:compromiso/saldo c)))
                          (extinguir app c :cumplimiento)
                          c))))))

(defn- extingue-compromiso [app [x causa]]
  (cambiar app (de-objetivo app x) #(extinguir app % causa)))

;; --- Entidades ------------------------------------------------------------------

(defn- campos-propios
  "Los campos que la empresa agregó al tipo (D3) y vienen en la entrada."
  [app tipo]
  (sin-nada (select-keys (get-in app [:refs 'entrada])
                         (keys (get-in app [:estado :catalogo :extensiones tipo])))))

(defn- validar-entidad!
  "Cada campo pertenece al tipo, del canon o de la empresa, y calza con su tipo."
  [app {:entidad/keys [tipo] :as doc}]
  (let [catalogo (get-in app [:estado :catalogo])
        tipos    (merge (get-in catalogo [:entidades tipo :entidad/campos])
                        (get-in catalogo [:extensiones tipo]))]
    (doseq [[k x] (dissoc doc :entidad/id :entidad/tipo :entidad/activa)]
      (let [forma (or (tipos k)
                      (e/error! :campo-desconocido (str "El tipo " tipo " no tiene el campo " k) {:campo k}))
            forma (if (and (vector? forma) (map? (second forma))) (into [(first forma)] (drop 2 forma)) forma)]
        (when-let [error (some-> (esq/esquema-de-datos forma) (m/explain x))]
          (e/error! :datos-invalidos (str k ": " (me/humanize error)) {:campo k}))))
    doc))

(defn- entidad-activa [app x]
  (let [id  (if (map? x) (:entidad/id x) x)
        doc (est/leer (:estado app) (pendientes app) id)]
    (cond
      (not (contains? doc :entidad/tipo)) (e/error! :no-es-entidad (str (pr-str id) " no es una entidad") {:id id})
      (false? (:entidad/activa doc))      (e/error! :entidad-desactivada (str (pr-str id) " está desactivada") {:id id})
      ;; Lo que el almacén agrega al guardar no es parte de la entidad.
      :else (dissoc doc :empresa :nc/decision))))

(defn- guardar-entidad [app doc]
  [(assoc-in app [:entidades (:entidad/id doc)] doc) doc])

(defn- crea-entidad [app [tipo campos]]
  (let [id (:id campos)]
    (when-not (contains? (get-in app [:estado :catalogo :entidades]) tipo)
      (e/error! :tipo-entidad-desconocido (str "No existe el tipo de entidad " tipo) {:tipo tipo}))
    (when-not (keyword? id)
      (e/error! :entidad-sin-id "Una entidad nueva necesita un :id (palabra clave)" {:tipo tipo}))
    (when (est/leer (:estado app) (pendientes app) id)
      (e/error! :entidad-existente (str "Ya existe " (pr-str id)) {:id id}))
    (guardar-entidad app (validar-entidad! app (merge (sin-nada (dissoc campos :id))
                                                      (campos-propios app tipo)
                                                      {:entidad/id id :entidad/tipo tipo :entidad/activa true})))))

(defn- modifica-entidad [app [x cambios]]
  (let [doc (entidad-activa app x)]
    (guardar-entidad app (validar-entidad! app (merge doc (sin-nada cambios)
                                                      (campos-propios app (:entidad/tipo doc)))))))

(defn- desactiva-entidad [app [x]]
  (guardar-entidad app (assoc (entidad-activa app x) :entidad/activa false)))

(def ^:private verbos
  {:crea-entidad        crea-entidad
   :modifica-entidad    modifica-entidad
   :desactiva-entidad   desactiva-entidad
   :registra-hecho      registra-hecho
   :abre-compromiso     abre-compromiso
   :modifica-compromiso modifica-compromiso
   :reduce-compromiso   reduce-compromiso
   :extingue-compromiso extingue-compromiso})

;; --- Efectos ---------------------------------------------------------------------

(defn- efecto [app ef]
  (if (= :cuando (first ef))
    (let [[_ condicion & efectos] ef]
      (if (e/evaluar condicion (contexto app)) (reduce efecto app efectos) app))
    (let [[verbo args nombre] (val/partes ef)
          aplicar   (or (verbos verbo)
                        (e/error! :verbo-no-implementado (str "El verbo " verbo " aún no se implementa") {:verbo verbo}))
          ctx       (contexto app)
          [app doc] (aplicar app (mapv #(e/evaluar % ctx) args))]
      (cond-> app nombre (assoc-in [:refs nombre] doc)))))

(defn- cerrar
  "Lo que la operación escribe: cada hecho con su caso (D68) y todo lo calculado
  con la versión de lo que lo calculó (D48). El compromiso raíz anota además las
  versiones con que se aplicará el resto del caso (notación 6.4)."
  [app reglas]
  (let [conceptos (:reglas @(:traza app) {})
        reglas    (merge reglas conceptos)
        caso      (get-in app [:caso :compromiso/id])]
    {:hechos      (mapv #(cond-> (assoc % :hecho/reglas reglas) caso (assoc :hecho/caso caso))
                        (:hechos app))
     :compromisos (mapv (fn [[id c]]
                          (cond
                            (not ((:nuevos app) id)) c
                            (= id caso)              (assoc c :compromiso/reglas (merge (est/reglas-de-inicio (:estado app)) reglas))
                            :else                    (assoc c :compromiso/reglas reglas)))
                        (:compromisos app))
     :saldos      (mapv #(assoc % :saldo/reglas conceptos) (vals (:saldos app)))
     :entidades   (vec (vals (:entidades app)))}))

(defn- salidas [estado contrato refs]
  (let [ctx (est/contexto estado refs)]
    (update-vals (:op/salidas contrato) #(e/evaluar % ctx))))

(defn preparar
  "Lo que haría ejecutar `op` ahora, sin escribir nada: la respuesta del
  resolvedor y, si la operación está lista y cambia algo, los cambios. Lleva la
  marca de la empresa leída antes que todo lo demás (D74). Un error del contrato
  al aplicar los efectos queda en `:error`."
  [estado actor op entradas]
  (let [marca (alm/marca (:almacen estado) (:empresa estado))
        {:keys [resolucion contrato refs reglas caso]} (r/analizar estado actor op entradas)
        plan  {:op op :actor actor :entradas entradas :resolucion resolucion :contrato contrato
               :refs refs :reglas reglas :marca marca}]
    (if (and (= :listo (:estado resolucion)) (= :efecto (:op/tipo contrato)) (not (:al estado)))
      (try
        (let [app (reduce efecto
                          {:estado estado :op op :actor actor :refs refs :caso caso :traza (atom {})
                           :hechos [] :compromisos {} :nuevos #{} :saldos {} :entidades {}}
                          (:op/efectos contrato))]
          (assoc plan :app app :cambios (cerrar app reglas)))
        (catch clojure.lang.ExceptionInfo ex
          (if-let [tipo (:nc/error (ex-data ex))]
            (assoc plan :error {:tipo tipo :mensaje (ex-message ex)} :excepcion ex)
            (throw ex))))
      plan)))

;; --- Bitácora (D82) ----------------------------------------------------------------

(defn- confirmacion [resolucion confirmado]
  (cond (= :eximida (:confirmacion resolucion)) :eximida
        confirmado                              :confirmada
        :else                                   :falta))

(defn- decision
  "Lo que MARCO decidió en una evaluación, para la bitácora."
  [{:keys [op actor entradas resolucion cambios error]} resultado {:keys [confirmado origen]}]
  (sin-nada {:decision/id           (random-uuid)
             :decision/en           (java.util.Date.)
             :decision/actor        actor
             :decision/origen       (or origen :persona)
             :decision/operacion    op
             :decision/entradas     (sin-nada entradas)
             :decision/resultado    resultado
             :decision/requisitos   (:requisitos resolucion)
             :decision/reglas       (:reglas resolucion)
             :decision/confirmacion (confirmacion resolucion confirmado)
             :decision/caso         (:caso resolucion)
             :decision/error        error
             :decision/hechos       (when (= :ejecutado resultado) (mapv :hecho/id (:hechos cambios)))}))

(defn- anotar!
  "Guarda una decisión que no escribe cambios. Devuelve su identificador."
  [estado plan resultado opts]
  (let [d (decision plan resultado opts)]
    (alm/escribir! (:almacen estado) (:empresa estado) {:decision d})
    (:decision/id d)))

;; --- Inicia (H7, D51) -------------------------------------------------------------

(declare ejecutar!)

(def actor-marco
  "La identidad con que MARCO actúa por iniciativa propia (P8)."
  :marco)

(def ^:private profundidad-maxima
  "Un objetivo iniciado puede iniciar otros; la cadena se corta aquí."
  3)

(defn- iniciar!
  "Las políticas \"inicia\" que se disparan con los hechos escritos: MARCO
  plantea su objetivo con su propia identidad y lo ejecuta si puede, sin
  confirmación solo si una política lo exime; si no, queda propuesto, con lo que
  falta (D51). Una con `:despues` espera al tiempo (etapa 9) y no se dispara aquí."
  [estado hechos {:keys [profundidad] :or {profundidad 0}}]
  (when (< profundidad profundidad-maxima)
    (vec (for [h hechos
               p (est/de-evento estado [:registra (:hecho/tipo h)])
               :let [{:keys [objetivo entradas despues]} (:politica/cuerpo p)
                     ctx (est/contexto estado {'hecho h 'actor actor-marco 'empresa (:empresa estado)
                                               'ahora (est/ahora estado)})]
               :when (and (nil? despues)
                          (or (not (contains? p :politica/cuando))
                              (true? (:valor (e/evaluar-seguro (:politica/cuando p) ctx)))))
               :let [ents (e/evaluar (or entradas {}) ctx)
                     res  (ejecutar! estado actor-marco objetivo ents
                                     {:origen :marco :profundidad (inc profundidad)})]]
           {:politica  (:politica/id p)
            :objetivo  objetivo
            :entradas  ents
            :estado    (:estado res)
            :decision  (:decision res)
            :requisitos (vec (remove #(#{:satisfecho :opcional} (:estado %)) (:requisitos res)))}))))

;; --- Ejecución ---------------------------------------------------------------------

(defn- resultado-de
  "Qué corresponde hacer con un plan: :aplicar, o el resultado que lo impide."
  [estado {:keys [resolucion contrato error]} confirmado]
  (cond
    (not= :listo (:estado resolucion))                               (:estado resolucion)
    (not= :efecto (:op/tipo contrato))                               :consultado
    (:al estado)                                                     :solo-lectura
    error                                                            :error
    (and (not confirmado) (= :requerida (:confirmacion resolucion))) :por-confirmar
    :else                                                            :aplicar))

(defn aplicar!
  "Aplica un plan preparado. Escribe solo si el plan está listo, confirmado o
  eximido, y el estado de la empresa no cambió desde que se preparó (D49, D74).
  Toda llamada deja su decisión en la bitácora (D82): la ejecución, en la misma
  transacción que los cambios; lo demás, sola. Un conflicto se anota y lanza
  :conflicto; un error del contrato se anota y se vuelve a lanzar."
  ([estado plan] (aplicar! estado plan {:confirmado true}))
  ([estado {:keys [app cambios marca contrato reglas resolucion] :as plan} {:keys [confirmado] :as opts}]
   (let [r (resultado-de estado plan confirmado)]
     (case r
       :solo-lectura (e/error! :solo-lectura "En otra fecha solo se consulta: el pasado no se corrige (D18)" {:op (:op plan)})
       :error        (do (anotar! estado plan :error opts) (throw (:excepcion plan)))
       :consultado   {:estado   :consultado
                      :salidas  (salidas estado contrato (:refs plan))
                      :decision (anotar! estado plan :consultado opts)}
       :aplicar      (let [d (decision plan :ejecutado opts)]
                       (try
                         (let [{:keys [registro]} (alm/escribir! (:almacen estado) (:empresa estado)
                                                                 (assoc cambios :decision d) {:marca marca})
                               iniciadas (iniciar! estado (:hechos cambios) opts)]
                           (cond-> {:estado   :ejecutado
                                    :salidas  (salidas estado contrato (:refs app))
                                    :caso     (get-in app [:caso :compromiso/id])
                                    :hechos   (mapv :hecho/id (:hechos cambios))
                                    :reglas   (merge reglas (:reglas @(:traza app)))
                                    :registro registro
                                    :decision (:decision/id d)}
                             (seq iniciadas) (assoc :iniciadas iniciadas)))
                         (catch clojure.lang.ExceptionInfo ex
                           (when (= :conflicto (:nc/error (ex-data ex)))
                             (anotar! estado plan :conflicto opts))
                           (throw ex))))
       (assoc resolucion :estado r :decision (anotar! estado plan r opts))))))

(def ^:private intentos 5)

(defn- conflicto? [ex] (= :conflicto (:nc/error (ex-data ex))))

(defn ejecutar!
  "Ejecuta `op` con `entradas` en nombre de `actor`. Si la operación no está
  lista, devuelve la respuesta del resolvedor (`:bloqueado` o `:prohibido`); si
  falta la confirmación, la misma respuesta con estado `:por-confirmar`. Una
  consulta no cambia nada y no se confirma. Toda respuesta lleva `:decision`, su
  entrada en la bitácora (D82).

  `:origen` dice de dónde vino el pedido: :persona (por omisión), :sara, :marco.

  Si otra escritura se adelanta, vuelve a preparar desde el estado nuevo: las
  políticas se verifican otra vez antes de escribir (D49, D74)."
  [estado actor op entradas opts]
  (loop [intento 1]
    (let [resultado (try (aplicar! estado (preparar estado actor op entradas) opts)
                         (catch clojure.lang.ExceptionInfo ex
                           (if (and (conflicto? ex) (< intento intentos)) ::otra-vez (throw ex))))]
      (if (= ::otra-vez resultado) (recur (inc intento)) resultado))))
