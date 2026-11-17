(ns nc.sara
  "SARA mínima, sin LLM (etapa 6). Convierte un contrato y la respuesta del
  resolvedor en una descripción de interfaz: un formulario con un campo por
  entrada, avisos con lo que falta, y tablas para las colecciones. No conoce
  ninguna operación: todo sale del contrato y del resolvedor (P12).

  Lo que produce son datos; dibujarlos es trabajo de un cliente (nc.sara.web)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [nc.almacen :as alm]
            [nc.esquema :as esq]
            [nc.estado :as est]
            [nc.resolvedor :as r]
            [nc.validar :as val]
            [nc.valor :as v])
  (:import (java.text NumberFormat)
           (java.time LocalDate ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util Date Locale)))

;; --- Mostrar ---------------------------------------------------------------------

(def ^:private numeros (NumberFormat/getNumberInstance (Locale. "es" "CL")))

(defn titulo
  "Un nombre del catálogo dicho para personas: :compras/registrar-solicitud →
  \"Registrar solicitud\"."
  [k]
  (let [s (str/replace (name k) "-" " ")]
    (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn mostrar
  "Un valor del dominio como texto."
  [x]
  (cond
    (v/valor? x)  (str (.format ^NumberFormat numeros (v/numero x))
                       (if (= :% (v/tipo x)) " %" (some->> (v/unidad x) name (str " "))))
    (inst? x)     (.format DateTimeFormatter/ISO_LOCAL_DATE (.atZone (.toInstant ^Date x) ZoneOffset/UTC))
    (uuid? x)     (subs (str x) 0 8)
    (keyword? x)  (subs (str x) 1)
    (map? x)      (str/join ", " (for [[k y] x] (str (name k) ": " (mostrar y))))
    (coll? x)     (str/join ", " (map mostrar x))
    (nil? x)      ""
    :else         (str x)))

;; --- Leer lo que escribe una persona --------------------------------------------

(defn- numero [s]
  (let [n (bigdec (-> s (str/replace "." "") (str/replace "," ".")))]
    (if (zero? (rem n 1)) (long n) n)))

(defn leer
  "El valor de un campo a partir de su texto. Si no se entiende, devuelve el
  texto tal cual: el contrato dirá qué no calza (D65)."
  [tipo texto]
  (let [t (str/trim (or texto ""))]
    (when-not (str/blank? t)
      (try
        (case (val/tipo-normal tipo)
          (:monto :cantidad) (if-let [[_ n u] (re-matches #"(-?[\d.,]+)\s+(\S+)" t)]
                               [(val/tipo-normal tipo) (numero n) (keyword u)]
                               t)
          :fecha    (Date/from (.toInstant (.atStartOfDay (LocalDate/parse t) ZoneOffset/UTC)))
          :ref      (edn/read-string t)
          :booleano (= "true" t)
          t)
        (catch Exception _ t)))))

(defn escribir
  "El texto con que un campo muestra su valor: lo inverso de `leer`."
  [tipo x]
  (cond
    (nil? x)                        ""
    (string? x)                     x
    (= :ref (val/tipo-normal tipo)) (pr-str x)
    (v/valor? x)                    (str (v/numero x) " " (name (v/unidad x)))
    :else                           (mostrar x)))

;; --- Opciones de un campo --------------------------------------------------------

(defn- buscar [{:keys [almacen empresa consulta]} tabla filtro]
  (alm/buscar almacen empresa tabla filtro consulta))

(defn- etiqueta-entidad [e]
  (or (some (fn [[k x]] (when (= "nombre" (name k)) x)) e) (mostrar (:entidad/id e))))

(defn nombre
  "Cómo se llama algo para una persona: el nombre de una entidad, o su identificador."
  [estado id]
  (or (when (keyword? id) (some-> (est/leer estado id) etiqueta-entidad))
      (mostrar id)))

(defn opciones
  "Valores posibles de una referencia: [[valor etiqueta] ...]. Nil si el campo
  se escribe libre."
  [estado forma]
  (when (and (vector? forma) (= :ref (first forma)))
    (let [tipo (second forma)]
      (cond
        (or (= :compromiso tipo) (contains? (get-in estado [:catalogo :tipos-compromiso]) tipo))
        (for [c (buscar estado :compromisos (if (= :compromiso tipo) {} {:compromiso/tipo tipo}))
              :when (not= :extinguido (:compromiso/estado c))]
          [(:compromiso/id c) (str (titulo (:compromiso/tipo c)) " " (mostrar (:compromiso/id c))
                                   " · " (mostrar (:compromiso/objeto c))
                                   (when (not= (:compromiso/saldo c) (select-keys (:compromiso/objeto c) (keys (:compromiso/saldo c))))
                                     (str " · pendiente: " (mostrar (:compromiso/saldo c)))))])

        (contains? (get-in estado [:catalogo :tipos-hecho]) tipo)
        (for [h (buscar estado :hechos {:hecho/tipo tipo})]
          [(:hecho/id h) (str (titulo tipo) " " (mostrar (:hecho/id h)) " · " (mostrar (:hecho/datos h)))])

        :else
        (for [e (buscar estado :entidades {:entidad/tipo tipo})]
          [(:entidad/id e) (etiqueta-entidad e)])))))

;; --- Formulario ------------------------------------------------------------------

(defn- quien [q]
  (cond
    (= :cualquiera q)                        "cualquiera"
    (and (vector? q) (= :rol (first q)))     (str "quien tenga el rol " (titulo (second q)))
    :else                                    (mostrar q)))

(defn- aviso
  "Un requisito que no es una entrada, dicho para personas. Nil si no hay nada
  que decir."
  [vista {:keys [id estado via faltan razon pide no-puede] :as req}]
  (case estado
    :prohibido     {:nivel :prohibido
                    :texto (if (= :permiso id)
                             (str "Sin permiso para esta operación; puede hacerlo " (quien (:quien req)) ".")
                             razon)}
    :pendiente     {:nivel :pendiente
                    :texto (str "Falta " (when (< 1 faltan) (str faltan " × ")) (str/lower-case (titulo id))
                                ". Se registra con «" (titulo via) "»; puede hacerlo " (quien (:quien req))
                                (when no-puede (str ", pero no " (nombre vista no-puede))) ".")
                    :via via}
    :indeterminado {:nivel :indeterminado
                    :texto (str "Para saber si aplica " (mostrar id) " falta "
                                (str/join ", " (map (comp str/lower-case titulo) pide)) ".")}
    :satisfecho    {:nivel :satisfecho :texto (str (titulo id) ": cumplido.")}
    nil))

(defn- de-entrada? [{:keys [id]}] (= "entrada" (namespace id)))

(defn formulario
  "La interfaz de `op` para `actor` con las entradas que ya tiene: un campo por
  entrada del contrato, con su tipo, si es obligatorio, sus opciones y lo que el
  resolvedor dice de él; y los avisos de lo que falta fuera del formulario."
  [estado actor op entradas]
  (let [contrato (get-in estado [:catalogo :contratos op])
        res      (r/resolver estado actor op entradas)
        por-id   (into {} (map (juxt :id identity)) (filter de-entrada? (:requisitos res)))
        entrega  (into {} (keep #(when-let [k (:entrega %)] [k %])) (:requisitos res))]
    {:operacion    op
     :titulo       (titulo op)
     :tipo         (:op/tipo contrato)
     :estado       (:estado res)
     :confirmacion (:confirmacion res)
     :campos       (vec (for [[k {:keys [tipo opcional]}] (esq/campos-ordenados (:op/entradas contrato))
                              :let [id  (keyword "entrada" (name k))
                                    req (por-id id)
                                    exigida (entrega id)]]
                          {:nombre      k
                           :etiqueta    (titulo k)
                           :tipo        (val/tipo-normal tipo)
                           :forma       tipo
                           :obligatorio (or (not opcional) (some? exigida))
                           :valor       (get entradas k)
                           :texto       (escribir tipo (get entradas k))
                           :opciones    (opciones estado tipo)
                           :problema    (:problema req)
                           :falta       (= :pendiente (:estado req))
                           :viene-de    (:via exigida)}))
     :avisos       (vec (keep #(aviso estado %) (remove de-entrada? (:requisitos res))))
     :reglas       (:reglas res)}))

(defn entradas-de-texto
  "Las entradas de `op` a partir de los textos de un formulario (nombre → texto)."
  [estado op textos]
  (into {}
        (keep (fn [[k {:keys [tipo]}]]
                (when-some [x (leer tipo (get textos (name k)))] [k x])))
        (esq/campos-ordenados (get-in estado [:catalogo :contratos op :op/entradas]))))

;; --- Tablas ----------------------------------------------------------------------

(def ^:private ocultas #{:empresa :hecho/reglas :compromiso/reglas :hecho/causa :saldo/reglas})

(def ^:private abiertas
  "Mapas que se abren en columnas propias, con el prefijo de su título. El saldo
  de un compromiso es lo que queda pendiente."
  {:hecho/datos "datos" :compromiso/objeto "objeto" :compromiso/saldo "pendiente"})

(defn tabla
  "Una colección de documentos como tabla, con los datos de un hecho y el objeto
  y saldo de un compromiso abiertos en columnas propias. `nombrar` dice cómo
  mostrar un identificador (por omisión, tal cual)."
  ([docs] (tabla docs mostrar))
  ([docs nombrar]
  (let [plano   (fn [d]
                  (reduce-kv (fn [m k x]
                               (if-let [prefijo (and (map? x) (abiertas k))]
                                 (into m (map (fn [[k2 y]] [(keyword prefijo (name k2)) y])) x)
                                 (assoc m k x)))
                             (array-map) (apply dissoc d ocultas)))
        filas    (map plano docs)
        primero  (fn [k] (or (first (keep-indexed #(when (= (name k) %2) %1) ["id" "tipo" "estado" "en" "actor"])) 9))
        columnas (vec (sort-by primero (distinct (mapcat keys filas))))
        nombre   (fn [k] (if (#{"objeto" "pendiente"} (namespace k))
                           (str (titulo (keyword (namespace k))) ": " (name k))
                           (titulo k)))]
    {:columnas (mapv (fn [k] {:clave k :titulo (nombre k)}) columnas)
     :filas    (mapv (fn [f] (mapv #(let [x (get f %)] (if (keyword? x) (nombrar x) (mostrar x))) columnas)) filas)})))

(defn salidas
  "Las salidas de una operación para mostrar: las colecciones de documentos como
  tabla, lo demás como valor."
  ([xs] (salidas xs mostrar))
  ([xs nombrar]
  (vec (for [[k x] xs]
         (if (and (sequential? x) (every? map? x))
           {:nombre (titulo k) :tabla (tabla x nombrar)}
           {:nombre (titulo k) :valor (mostrar x)})))))

(defn siguientes
  "Las operaciones que reciben como entrada alguna de estas salidas (D10), con
  esas entradas ya puestas: así se encadena un paso con el siguiente."
  [estado salidas]
  (let [ids (into {} (filter (comp (some-fn uuid? keyword?) val)) salidas)]
    (vec (for [[op c] (sort-by key (get-in estado [:catalogo :contratos]))
               :let [comunes (select-keys ids (map first (esq/campos-ordenados (:op/entradas c))))]
               :when (seq comunes)]
           {:operacion op :titulo (titulo op) :entradas comunes}))))
