(ns nc.validar
  "Validación al escribir (notación 3.3 y 5.1). Sin ejecutar nada, comprueba que
  toda operación exista, que toda referencia esté en el catálogo (D6), que los
  tipos calcen y que una expresión no contenga efectos (D55).

  El entorno de validación es un mapa:
    :refs      símbolo base → tipo, o mapa de campo → tipo ('entrada)
    :campos    campo de entidad → tipo, para comprobar la navegación con :->
    :conceptos id → definición"
  (:require [nc.expresion :as e]))

;; --- Efectos: los diez verbos (D30, D56) --------------------------------------

(def verbos
  "Verbo → número de argumentos, sin contar `:como nombre`."
  {:crea-entidad 2 :modifica-entidad 2 :desactiva-entidad 1
   :registra-hecho 2 :revierte-hecho 1
   :abre-compromiso 1 :modifica-compromiso 2 :reduce-compromiso 2
   :extingue-compromiso 2 :transfiere-compromiso 2})

(def verbos-de-hecho #{:registra-hecho :revierte-hecho})
(def verbos-de-compromiso
  #{:abre-compromiso :modifica-compromiso :reduce-compromiso
    :extingue-compromiso :transfiere-compromiso})

;; --- Tipos ---------------------------------------------------------------------

(def ^:private tipos-simples
  #{:numero :monto :cantidad :porcentaje :duracion :booleano :fecha :texto
    :clave :ref :coleccion :mapa :cualquiera})

(defn tipo-normal
  "Lleva un tipo declarado en un contrato o concepto al tipo que usa el validador."
  [t]
  (cond
    (tipos-simples t)                    t
    (= :unidad t)                        :clave
    (and (vector? t) (= :ref (first t))) :ref
    (vector? t)                          (tipo-normal (first t))
    :else                                :cualquiera))

(defn- nota! [ent problema en mensaje]
  (swap! (::acumulado ent) conj {:problema problema :en en :mensaje mensaje})
  :cualquiera)

(defn- unificar!
  "Los tipos conocidos deben ser uno solo. Una referencia se escribe como clave."
  [ent expr tipos]
  (let [conocidos (->> tipos (remove #{:cualquiera}) (map #(if (= :ref %) :clave %)) distinct)]
    (if (< 1 (count conocidos))
      (nota! ent :tipos-incompatibles expr
             (str "No se puede operar " (pr-str (vec conocidos)) " en " (pr-str expr)))
      (or (first conocidos) :cualquiera))))

(declare tipo-de)

(defn- tipo-referencia [sym ent]
  (let [base (symbol (or (namespace sym) (name sym)))]
    (if-not (contains? (:refs ent) base)
      (nota! ent :referencia-desconocida sym (str "No existe la referencia " sym))
      (let [forma (get (:refs ent) base)]
        (cond
          (nil? (namespace sym)) (if (map? forma) :mapa (tipo-normal forma))
          (not (map? forma))     :cualquiera
          :else (let [k (keyword (name sym))]
                  (if (contains? forma k)
                    (tipo-normal (get forma k))
                    (nota! ent :campo-desconocido sym (str "No existe el dato " sym)))))))))

(defn- tipo-navegacion [[x & claves :as args] ent expr]
  (tipo-de x ent)
  (let [campos    (:campos ent)
        entidades (set (map namespace (keys campos)))]
    (doseq [k claves
            :when (and campos (keyword? k) (entidades (namespace k)) (not (contains? campos k)))]
      (nota! ent :campo-desconocido expr (str "No existe el campo " k)))
    (tipo-normal (get campos (last args)))))

(defn- tipo-de-cada
  "Tipo de `expr` evaluada sobre cada elemento de una colección."
  [expr col ent]
  (tipo-de col ent)
  (if (keyword? expr)
    :cualquiera
    (tipo-de expr (assoc-in ent [:refs 'cada] :cualquiera))))

(defn- tipo-concepto [id [params] ent expr]
  (if-let [c (get (:conceptos ent) id)]
    (let [declarados (set (keys (:concepto/params c)))
          pasados    (set (keys params))]
      (when (not= declarados pasados)
        (nota! ent :parametros expr
               (str "El concepto " id " espera " (pr-str declarados) " y recibe " (pr-str pasados))))
      (run! #(tipo-de % ent) (vals params))
      (tipo-normal (:concepto/tipo c)))
    (nota! ent :concepto-desconocido expr (str "No existe el concepto " id))))

(defn- tipo-operacion [[k & args :as expr] ent]
  (let [tipos (fn [] (mapv #(tipo-de % ent) args))]
    (case k
      (:+ :-)    (unificar! ent expr (tipos))
      :*         (let [ts (tipos)]
                   (when (< 1 (count (filter #{:monto} ts)))
                     (nota! ent :tipos-incompatibles expr "No se multiplican dos montos"))
                   (or (some #{:monto} ts) (some #{:cualquiera} ts) (some #{:cantidad} ts) :numero))
      :/         (let [[a b] (tipos)]
                   (cond (= :cualquiera a) a, (= a b) :numero, :else a))
      :redondear (first (tipos))
      (:= :!= :> :>= :< :<= :entre) (do (unificar! ent expr (tipos)) :booleano)
      (:y :o :no) (do (tipos) :booleano)
      :si        (let [[_ a b] (tipos)] (if (= a b) a :cualquiera))
      :caso      (do (tipos) :cualquiera)
      (:+dias :+meses :fin-de-mes :habil-siguiente) (do (tipos) :fecha)
      :dias-entre (do (tipos) :numero)
      (:hechos :compromisos) (do (tipos) :coleccion)
      :existe    (do (tipos) :booleano)
      :cuenta    (do (tipos) :numero)
      :todos     (let [[col c] args] (tipo-de-cada c col ent) :booleano)
      (:suma :max :min) (let [[x col] args] (tipo-de-cada x col ent))
      :->        (tipo-navegacion args ent expr)
      :tabla     (do (run! #(tipo-de % ent) (rest args)) :cualquiera)
      :rol       :coleccion
      :monto     :monto
      :cantidad  :cantidad
      :%         :porcentaje
      (:dias :meses) :duracion)))

(defn- tipo-vector [[k & args :as expr] ent]
  (let [{:keys [min max]} (e/operaciones k)]
    (cond
      (not (keyword? k))
      (nota! ent :forma-invalida expr (str "No es una expresión: " (pr-str expr)))

      (or (verbos k) (= :cuando k))
      (nota! ent :efecto-en-expresion expr
             (str "Una expresión no puede contener el efecto " k " (D55)"))

      (e/operaciones k)
      (if (or (< (count args) min) (and max (> (count args) max)))
        (nota! ent :aridad expr (str k " no acepta " (count args) " argumentos"))
        (tipo-operacion expr ent))

      (namespace k) (tipo-concepto k args ent expr)

      :else (nota! ent :operacion-desconocida expr (str "No existe la operación " k)))))

(defn- tipo-de [expr ent]
  (cond
    (symbol? expr)  (tipo-referencia expr ent)
    (vector? expr)  (tipo-vector expr ent)
    (map? expr)     (do (run! #(tipo-de % ent) (vals expr)) :mapa)
    (number? expr)  :numero
    (string? expr)  :texto
    (boolean? expr) :booleano
    (keyword? expr) :clave
    (inst? expr)    :fecha
    :else           :cualquiera))

(defn analizar
  "Devuelve {:tipo t :problemas [...]} para una expresión."
  [expr ent]
  (let [acumulado (atom [])
        tipo      (tipo-de expr (assoc ent ::acumulado acumulado))]
    {:tipo tipo :problemas @acumulado}))

(defn problemas-expresion
  "Problemas de una expresión. Si se indica `esperado`, también comprueba su tipo."
  ([expr ent] (:problemas (analizar expr ent)))
  ([expr ent esperado]
   (let [{:keys [tipo problemas]} (analizar expr ent)]
     (cond-> problemas
       (not (#{esperado :cualquiera} tipo))
       (conj {:problema :tipo-inesperado :en expr
              :mensaje (str "Se esperaba " esperado " y es " tipo)})))))

;; --- Efectos -----------------------------------------------------------------

(defn partes
  "Separa un efecto en verbo, argumentos y el nombre dado con `:como`."
  [[k & resto]]
  (if (= :como (last (butlast resto)))
    [k (drop-last 2 resto) (last resto)]
    [k resto nil]))

(defn- efecto
  "Valida un efecto y devuelve el estado acumulado: problemas, verbos usados y
  el entorno, que crece con cada nombre dado por `:como`."
  [{:keys [ent] :as estado} ef]
  (let [nota (fn [estado problema mensaje]
               (update estado :problemas conj {:problema problema :en ef :mensaje mensaje}))
        expr (fn [estado x] (update estado :problemas into (problemas-expresion x ent)))]
    (cond
      (not (and (vector? ef) (keyword? (first ef))))
      (nota estado :efecto-invalido (str "No es un efecto: " (pr-str ef)))

      (= :cuando (first ef))
      (let [[_ condicion & efectos] ef]
        (reduce efecto (expr estado condicion) efectos))

      (verbos (first ef))
      (let [[k args nombre] (partes ef)]
        (cond-> (reduce expr (update estado :verbos conj k) args)
          (not= (verbos k) (count args))
          (nota :aridad (str k " espera " (verbos k) " argumentos"))

          (and nombre (not (simple-symbol? nombre)))
          (nota :nombre-invalido (str ":como espera un nombre simple, no " (pr-str nombre)))

          (simple-symbol? nombre)
          (assoc-in [:ent :refs nombre] :cualquiera)))

      :else
      (nota estado :verbo-desconocido (str "No existe el verbo " (first ef))))))

(defn analizar-efectos
  "Valida una lista de efectos. Devuelve {:problemas [...] :verbos #{...} :ent ent},
  donde `ent` incluye los nombres dados con `:como`."
  [efectos ent]
  (let [r      (reduce efecto {:problemas [] :verbos #{} :ent ent} efectos)
        usados (:verbos r)]
    (cond-> r
      (and (some verbos-de-compromiso usados) (not-any? verbos-de-hecho usados))
      (update :problemas conj
              {:problema :compromiso-sin-hecho :en efectos
               :mensaje "Ningún compromiso cambia sin un hecho en la misma operación (D61)"}))))
