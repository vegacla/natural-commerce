(ns nc.expresion
  "Lenguaje de expresiones (notación, sección 3). Una expresión solo lee: no
  tiene efectos y siempre termina.

  El contexto de evaluación es un mapa:
    :refs        símbolo base → valor ('entrada, 'actor, 'empresa, 'ahora, 'caso...)
    :buscar      id → documento (entidad, hecho o compromiso)
    :hechos      filtro → colección de hechos
    :compromisos filtro → colección de compromisos
    :concepto    id → definición del concepto
    :tabla       nombre, claves → valor
    :rol         nombre → conjunto de personas
    :traza       átomo opcional donde quedan las versiones y la evidencia (D38, D48)"
  (:require [nc.valor :as v])
  (:import (java.time DayOfWeek ZoneOffset ZonedDateTime)
           (java.time.temporal ChronoUnit TemporalAdjusters)
           (java.util Date)))

(defn error! [tipo mensaje datos]
  (throw (ex-info mensaje (assoc datos :nc/error tipo))))

(declare evaluar)

;; --- Fechas ---------------------------------------------------------------

(defn- zdt ^ZonedDateTime [^Date d] (.atZone (.toInstant d) ZoneOffset/UTC))
(defn- fecha [^ZonedDateTime z] (Date/from (.toInstant z)))
(defn- cuantos [x] (if (v/valor? x) (v/numero x) x))

(defn- habil-siguiente [d]
  (loop [z (zdt d)]
    (if (#{DayOfWeek/SATURDAY DayOfWeek/SUNDAY} (.getDayOfWeek z))
      (recur (.plusDays z 1))
      (fecha z))))

;; --- Operaciones que evalúan todos sus argumentos ---------------------------

(defn- ordenado [pred] (fn [a b] (pred (v/comparar a b))))

(def estrictas
  "Operación → aridad (:min, :max) y función sobre los argumentos ya evaluados."
  {:+          {:min 2 :f (fn [& xs] (reduce v/sumar xs))}
   :-          {:min 2 :f (fn [& xs] (reduce v/restar xs))}
   :*          {:min 2 :f (fn [& xs] (reduce v/multiplicar xs))}
   :/          {:min 2 :max 2 :f v/dividir}
   :redondear  {:min 1 :max 2 :f v/redondear}
   :=          {:min 2 :max 2 :f v/igual?}
   :!=         {:min 2 :max 2 :f (complement v/igual?)}
   :>          {:min 2 :max 2 :f (ordenado pos?)}
   :>=         {:min 2 :max 2 :f (ordenado (complement neg?))}
   :<          {:min 2 :max 2 :f (ordenado neg?)}
   :<=         {:min 2 :max 2 :f (ordenado (complement pos?))}
   :entre      {:min 3 :max 3 :f (fn [x a b] (and (not (neg? (v/comparar x a)))
                                                  (not (pos? (v/comparar x b)))))}
   :no         {:min 1 :max 1 :f not}
   :+dias      {:min 2 :max 2 :f (fn [d n] (fecha (.plusDays (zdt d) (long (cuantos n)))))}
   :+meses     {:min 2 :max 2 :f (fn [d n] (fecha (.plusMonths (zdt d) (long (cuantos n)))))}
   :dias-entre {:min 2 :max 2 :f (fn [a b] (.between ChronoUnit/DAYS (zdt a) (zdt b)))}
   :fin-de-mes {:min 1 :max 1 :f (fn [d] (fecha (.with (zdt d) (TemporalAdjusters/lastDayOfMonth))))}
   :habil-siguiente {:min 1 :max 1 :f habil-siguiente}
   :cuenta     {:min 1 :max 1 :f count}
   :existe     {:min 1 :max 1 :f (comp boolean seq)}
   :monto      {:min 2 :max 2 :f (fn [n u] [:monto n u])}
   :cantidad   {:min 2 :max 2 :f (fn [n u] [:cantidad n u])}
   :%          {:min 1 :max 1 :f (fn [n] [:% n])}
   :dias       {:min 1 :max 1 :f (fn [n] [:dias n])}
   :meses      {:min 1 :max 1 :f (fn [n] [:meses n])}})

(def especiales
  "Operaciones que controlan cómo se evalúan sus argumentos."
  {:y           {:min 1}
   :o           {:min 1}
   :si          {:min 3 :max 3}
   :caso        {:min 3}
   :->          {:min 2}
   :hechos      {:min 1 :max 1}
   :compromisos {:min 1 :max 1}
   :todos       {:min 2 :max 2}
   :cada        {:min 2 :max 2}
   :suma        {:min 2 :max 2}
   :max         {:min 2 :max 2}
   :min         {:min 2 :max 2}
   :tabla       {:min 2}
   :saldo       {:min 2 :max 3}
   :rol         {:min 1 :max 1}})

(def operaciones
  "Todo el vocabulario. Una operación que no está aquí no existe."
  (merge estrictas especiales))

;; --- Referencias ------------------------------------------------------------

(defn- buscar [id ctx]
  (or (when-let [f (:buscar ctx)] (f id))
      (error! :referencia-rota (str "No existe " (pr-str id)) {:id id})))

(defn- campo
  "Busca el dato `nombre` en un documento: como clave simple, como clave del
  sobre (:hecho/x, :compromiso/x, :entidad/x) o dentro de sus datos. Devuelve
  [valor] o nil."
  [doc nombre]
  (let [k (keyword nombre)]
    (some (fn [ruta]
            (let [x (get-in doc ruta ::nada)]
              (when (not= ::nada x) [x])))
          [[k] [(keyword "hecho" nombre)] [(keyword "compromiso" nombre)] [(keyword "entidad" nombre)]
           [:hecho/datos k] [:compromiso/objeto k]])))

(defn- referencia
  "`entrada` es el documento completo; `entrada/x` es uno de sus datos. Un dato
  ausente es un dato que falta (P4); uno presente con valor nil no."
  [sym ctx]
  (let [base  (symbol (or (namespace sym) (name sym)))
        falta #(error! :falta-dato (str "Falta el dato " sym) {:ref sym})]
    (when-not (contains? (:refs ctx) base) (falta))
    (let [doc (get (:refs ctx) base)]
      (if-not (namespace sym)
        doc
        (let [doc (if (or (map? doc) (nil? doc)) doc (buscar doc ctx))]
          (if-let [[x] (campo doc (name sym))] x (falta)))))))

(defn- navegar [[x & claves] ctx]
  (reduce (fn [doc k]
            (when (some? doc)
              (get (if (map? doc) doc (buscar doc ctx)) k)))
          (evaluar x ctx) claves))

;; --- Colecciones y conceptos --------------------------------------------------

(defn- de-cada
  "Valor de `expr` para cada elemento. `expr` es un campo o una expresión que
  ve el elemento como `cada`."
  [expr col ctx]
  (map (fn [elemento]
         (if (keyword? expr)
           (get elemento expr)
           (evaluar expr (assoc-in ctx [:refs 'cada] elemento))))
       col))

(defn- extremo [pred expr col ctx]
  (when-let [xs (seq (de-cada expr col ctx))]
    (reduce (fn [a b] (if (pred (v/comparar b a)) b a)) xs)))

(defn- anotar! [ctx id version evidencia]
  (when-let [traza (:traza ctx)]
    (swap! traza (fn [t] (cond-> (assoc-in t [:reglas id] version)
                           (some? evidencia) (assoc-in [:evidencia id] evidencia))))))

(defn- concepto [id [params] ctx]
  (let [c (or (when-let [f (:concepto ctx)] (f id))
              (error! :concepto-desconocido (str "No existe el concepto " id) {:concepto id}))]
    (when (contains? (:pila ctx) id)
      (error! :ciclo (str "El concepto " id " se usa a sí mismo") {:concepto id}))
    (let [args  (evaluar (or params {}) ctx)
          refs  (into (select-keys (:refs ctx) ['empresa 'ahora])
                      (map (fn [[k x]] [(symbol (name k)) x]) args))
          ctx'  (-> ctx (assoc :refs refs) (update :pila (fnil conj #{}) id))
          algoritmo (:concepto/calculo c)
          valor (if algoritmo
                  ((requiring-resolve algoritmo) args ctx')
                  (evaluar (:concepto/forma c) ctx'))]
      (anotar! ctx id (:concepto/version c) (when algoritmo valor))
      valor)))

;; --- Evaluación --------------------------------------------------------------

(defn- especial [k args ctx]
  (let [ev #(evaluar % ctx)]
    (case k
      :y    (every? ev args)
      :o    (boolean (some ev args))
      :si   (let [[c a b] args] (if (ev c) (ev a) (ev b)))
      :caso (loop [[c x & mas :as resto] args]
              (cond (= 1 (count resto)) (ev c)
                    (empty? resto)      nil
                    (ev c)              (ev x)
                    :else               (recur mas)))
      :->   (navegar args ctx)
      :hechos      ((:hechos ctx) (ev (first args)))
      :compromisos ((:compromisos ctx) (ev (first args)))
      :todos (let [[col c] args] (every? boolean (de-cada c (ev col) ctx)))
      ;; La expresión para cada elemento: las líneas de un documento (D113).
      :cada  (let [[col c] args] (vec (de-cada c (ev col) ctx)))
      :suma  (let [[expr col] args] (reduce v/sumar 0 (de-cada expr (ev col) ctx)))
      :max   (let [[expr col] args] (extremo pos? expr (ev col) ctx))
      :min   (let [[expr col] args] (extremo neg? expr (ev col) ctx))
      :tabla ((:tabla ctx) (first args) (mapv ev (rest args)))
      ;; [:saldo concepto clave] es el valor del saldo, {} si no existe;
      ;; [:saldo concepto clave campo], uno de sus datos, 0 si no existe.
      :saldo (let [[c clave campo] args
                   valor (or ((:saldo ctx) c (ev clave)) {})]
               (if campo (get valor campo 0) valor))
      :rol   ((:rol ctx) (first args)))))

(defn- aplicar [[k & args :as expr] ctx]
  (cond
    (not (keyword? k))    (error! :forma-invalida (str "No es una expresión: " (pr-str expr)) {:en expr})
    (estrictas k)         (apply (:f (estrictas k)) (map #(evaluar % ctx) args))
    (especiales k)        (especial k args ctx)
    (namespace k)         (concepto k args ctx)
    :else                 (error! :operacion-desconocida (str "No existe la operación " k) {:en expr})))

(defn evaluar
  "Evalúa una expresión. Un símbolo es una referencia; un vector que empieza con
  palabra clave es una operación; un mapa se evalúa valor por valor; todo lo
  demás vale por sí mismo."
  [expr ctx]
  (cond
    (symbol? expr) (referencia expr ctx)
    (vector? expr) (aplicar expr ctx)
    (map? expr)    (update-vals expr #(evaluar % ctx))
    :else          expr))

(defn evaluar-seguro
  "Como evaluar, pero un dato que falta no es un error: devuelve {:falta ref}
  para que el resolvedor lo informe como indeterminado (P4)."
  [expr ctx]
  (try
    {:valor (evaluar expr ctx)}
    (catch clojure.lang.ExceptionInfo e
      (if (= :falta-dato (:nc/error (ex-data e)))
        {:falta (:ref (ex-data e))}
        (throw e)))))
