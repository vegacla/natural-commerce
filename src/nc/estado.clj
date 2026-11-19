(ns nc.estado
  "La vista que MARCO tiene de una empresa: su catálogo, su estado en el almacén
  y las políticas que rigen cada operación en cada caso. Arma el contexto con
  que el evaluador (nc.expresion) lee entidades, hechos y compromisos.

  Una operación en curso todavía no escribe nada: lo que ya produjo va en
  `pendientes` ({:hechos {id doc} :compromisos {id doc}}), y las lecturas lo ven
  por encima del almacén.

  La vista mira la empresa hoy, o en otra fecha con `al` (D17)."
  (:require [nc.almacen :as alm]
            [nc.esquema :as esq])
  (:import (java.time Instant)
           (java.util Date)))

(defn abrir
  "Lo que MARCO necesita para actuar en una empresa."
  [almacen catalogo empresa]
  {:almacen almacen :catalogo catalogo :empresa empresa})

(defn al
  "La misma vista en otro instante. En el pasado se ve lo que regía entonces
  según lo que se sabía entonces; en el futuro, lo que regirá según lo que se
  sabe hoy. Una vista en otra fecha solo lee (D18, D73)."
  [estado instante]
  (let [i (if (instance? Date instante) (.toInstant ^Date instante) instante)]
    (assoc estado :al i :consulta (if (.isAfter ^Instant i (Instant/now)) {:validez i} {:al i}))))

(defn ahora
  "El instante en que se evalúa: el de la vista, o el reloj."
  [estado]
  (if-let [i (:al estado)] (Date/from i) (Date.)))

;; --- Lectura -------------------------------------------------------------------

(defn leer
  "Un documento por su identificador: hecho o compromiso si es un uuid, entidad
  si no. Nil si no existe en la empresa."
  ([estado id] (leer estado {} id))
  ([{:keys [almacen empresa consulta]} pendientes id]
   (if (uuid? id)
     (or (get-in pendientes [:hechos id])
         (get-in pendientes [:compromisos id])
         (alm/leer almacen empresa :hechos id consulta)
         (alm/leer almacen empresa :compromisos id consulta))
     (or (get-in pendientes [:entidades id])
         (alm/leer almacen empresa :entidades id consulta)))))

(defn- coincide? [doc filtro] (every? (fn [[k x]] (= x (get doc k))) filtro))

(defn- buscar-con
  "Documentos que coinciden con un filtro de claves simples ({:tipo t :caso c}),
  con lo pendiente de la operación en lugar de su versión guardada."
  [{:keys [almacen empresa consulta]} pendientes tabla prefijo filtro]
  (let [filtro (update-keys filtro #(keyword prefijo (name %)))
        id     (keyword prefijo "id")]
    (into (filterv #(coincide? % filtro) (vals pendientes))
          (remove #(contains? pendientes (id %)))
          (alm/buscar almacen empresa tabla filtro consulta))))

(defn- saldo
  "El valor de un saldo, con lo que la operación en curso ya movió."
  [{:keys [almacen empresa consulta]} pendientes concepto clave]
  (:saldo/valor (or (get pendientes [concepto clave])
                    (alm/leer almacen empresa :saldos [concepto clave] consulta))))

(defn- de-tabla
  "Un valor de una tabla de la empresa (lista de precios, tasas) en la versión que
  rige en el instante de la vista. Anota la versión usada (D48, H9)."
  [{:keys [almacen empresa consulta]} traza id claves]
  (when-let [t (alm/leer almacen empresa :tablas id consulta)]
    (swap! traza assoc-in [:reglas id] (:tabla/version t))
    (some #(when (= claves (:clave %)) (:valor %)) (:tabla/filas t))))

(defn- con-rol [{:keys [almacen empresa consulta]} rol]
  (->> (alm/buscar almacen empresa :entidades {:entidad/tipo :persona} consulta)
       (filter #(contains? (set (:persona/roles %)) rol))
       (mapv :entidad/id)))

(defn contexto
  "Contexto de evaluación con estas referencias. `traza` acumula las versiones de
  los conceptos usados (D48); una operación usa una sola."
  ([estado refs] (contexto estado refs {} (atom {})))
  ([estado refs pendientes traza]
   {:refs        refs
    :buscar      #(leer estado pendientes %)
    :hechos      #(buscar-con estado (:hechos pendientes) :hechos "hecho" %)
    :compromisos #(buscar-con estado (:compromisos pendientes) :compromisos "compromiso" %)
    :concepto    #(get-in estado [:catalogo :conceptos %])
    :saldo       #(saldo estado (:saldos pendientes) %1 %2)
    :tabla       #(de-tabla estado traza %1 %2)
    :rol         #(con-rol estado %)
    :traza       traza}))

;; --- Caso ------------------------------------------------------------------------

(defn caso-de
  "El compromiso raíz del caso de una operación (D15, D68): el de la primera
  entrada, en el orden del contrato, que apunta a un compromiso o a un hecho de
  un caso. Nil si ninguna lo hace; entonces el primer compromiso que la operación
  abra será la raíz."
  [estado contrato entradas]
  (some (fn [[k]]
          (let [x (get entradas k)]
            (when (uuid? x)
              (when-let [raiz (some-> (leer estado x) ((some-fn :compromiso/raiz :hecho/caso)))]
                (leer estado raiz)))))
        (esq/campos-ordenados (:op/entradas contrato))))

;; --- Políticas -------------------------------------------------------------------

(defn vigentes
  "Las políticas de la empresa en la versión que rige en el instante de la vista."
  [{:keys [almacen empresa consulta]}]
  (alm/buscar almacen empresa :politicas {} consulta))

(defn- version [{:keys [almacen empresa]} id v]
  (some #(when (= v (:politica/version %)) %) (alm/historia almacen empresa :politicas id)))

(defn ultimo-cierre
  "Cuándo se cerró el último período contable de la empresa, o nil (T4, H4)."
  [{:keys [almacen empresa consulta]}]
  (some->> (alm/buscar almacen empresa :entidades {:entidad/tipo :periodo :periodo/estado :cerrado} consulta)
           (keep :periodo/cerrado-en)
           seq
           (apply max-key #(.getTime ^Date %))))

(defn- version-al-cierre
  "La versión que regía al último cierre de período; sin cierres, la primera.
  Un cambio rige desde el cierre siguiente, sin recálculo hacia atrás (D44)."
  [{:keys [almacen empresa] :as estado} id]
  (if-let [c (ultimo-cierre estado)]
    (alm/vigente almacen empresa :politicas id {:validez c})
    (first (sort-by :politica/version (alm/historia almacen empresa :politicas id)))))

(defn- aplica-a? [politica op]
  (let [a (:politica/aplica-a politica)]
    (or (= a op) (and (set? a) (contains? a op)))))

(defn politicas
  "Las políticas que rigen `op` en un caso, cada una en la versión que elige su
  `:aplica-con` (D50). Sin `:aplica-con`, la del día del paso (D70). Lo que
  declara la versión vigente decide si el caso abierto la adopta (D9, D72)."
  [estado op caso]
  (let [del-caso (:compromiso/reglas caso)]
    (keep (fn [{:politica/keys [id aplica-con] :as p}]
            (case (or aplica-con :fecha-del-paso)
              :fecha-del-paso  p
              ;; Sin caso, la vigente. Con caso, la anotada en su raíz; si no está
              ;; anotada, la política no existía al abrirlo y no le aplica.
              :inicio-del-caso (cond
                                 (nil? caso)                                p
                                 (= (get del-caso id) (:politica/version p)) p
                                 (get del-caso id)                          (version estado id (get del-caso id)))
              :cierre-del-periodo (version-al-cierre estado id)
              (throw (ex-info (str "Opción de vigencia no implementada: " aplica-con)
                              {:nc/error :opcion-no-implementada :politica id}))))
          (filter #(aplica-a? % op) (vigentes estado)))))

(defn de-evento
  "Las políticas vigentes que aplican a un evento, como [:registra tipo-de-hecho]."
  [estado evento]
  (filter #(= evento (:politica/aplica-a %)) (vigentes estado)))

(def combinacion-por-defecto
  "Cómo se combinan varias políticas de una forma (notación 6.3, P9). La empresa
  puede cambiarlo en su configuración (`:combinacion`)."
  {:sugiere :la-mas-especifica
   :exime   :todas})

(defn combinacion [estado forma]
  (get (merge combinacion-por-defecto (get-in estado [:catalogo :combinacion])) forma))

(defn reglas-de-inicio
  "Versiones vigentes de las políticas que se aplican con la del inicio del caso.
  Se anotan en el compromiso raíz al abrirlo (notación 6.4)."
  [estado]
  (into {} (for [p (vigentes estado) :when (= :inicio-del-caso (:politica/aplica-con p))]
             [(:politica/id p) (:politica/version p)])))

;; --- Plan contratado (visión 3.2, D2) -----------------------------------------------

(defn plan
  "El plan que rige en el instante de la vista, o nil si la empresa no tiene uno.
  Sin plan, la empresa tiene todas las capacidades del catálogo (D83)."
  [{:keys [almacen empresa consulta]}]
  (alm/leer almacen empresa :planes :plan consulta))

(defn habilitada?
  "Si la operación pertenece a una capacidad del plan de la empresa."
  ([estado op] (habilitada? estado (plan estado) op))
  ([estado plan op]
   (or (nil? plan)
       (contains? (set (:plan/capacidades plan))
                  (get-in estado [:catalogo :contratos op :op/capacidad])))))

(defn operaciones
  "Los contratos de las operaciones que la empresa puede usar: las de su plan."
  [estado]
  (let [p (plan estado)]
    (into (sorted-map) (filter #(habilitada? estado p (key %))) (get-in estado [:catalogo :contratos]))))

;; --- Catálogo --------------------------------------------------------------------

(defn registradora
  "La operación que registra hechos de un tipo: con ella se cumple un \"exige\"."
  [catalogo tipo-hecho]
  (some (fn [{:op/keys [id efectos]}]
          (when (some #(and (vector? %) (= [:registra-hecho tipo-hecho] (take 2 %)))
                      (tree-seq vector? seq (vec efectos)))
            id))
        (vals (:contratos catalogo))))
