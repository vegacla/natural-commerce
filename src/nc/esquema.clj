(ns nc.esquema
  "Esquemas de lo que se escribe como dato: tipos de entidad y de hecho,
  conceptos, contratos, políticas y caminos (notación, secciones 2 a 7).

  Cada `problemas-*` devuelve una lista de problemas, vacía si el dato es
  válido. Primero comprueba la forma con Malli y después cada expresión con
  nc.validar, usando el catálogo para saber qué existe (D6)."
  (:require [clojure.set :as set]
            [malli.core :as m]
            [malli.error :as me]
            [nc.valor :as v]
            [nc.validar :as val]))

;; --- Tipos de dato de las entradas y los campos --------------------------------

(defn- simple [tipo pred mensaje]
  (m/-simple-schema {:type tipo :pred pred :type-properties {:error/message mensaje}}))

(def registro
  "Registro de Malli con los tipos del dominio. `:ref` reemplaza al de Malli:
  [:ref :proveedor] es la referencia a un proveedor."
  (merge (m/default-schemas)
         {:monto    (simple :monto v/monto? "debe ser un monto, como [:monto 500000 :CLP]")
          :cantidad (simple :cantidad v/cantidad? "debe ser una cantidad, como [:cantidad 20 :un]")
          :texto    (simple :texto string? "debe ser un texto")
          :booleano (simple :booleano boolean? "debe ser verdadero o falso")
          :fecha    (simple :fecha inst? "debe ser una fecha")
          :unidad   (simple :unidad keyword? "debe ser una unidad")
          :ref      (m/-simple-schema {:type :ref :min 1 :max 1 :pred some?
                                       :type-properties {:error/message "falta la referencia"}})}))

(defn esquema-de-datos
  "Compila un esquema de entradas o de datos. Devuelve nil si está mal escrito."
  [forma]
  (try (m/schema forma {:registry registro}) (catch Exception _ nil)))

(defn campos-ordenados
  "Campos de un esquema [:map ...] en el orden declarado: [[nombre {:tipo forma :opcional bool}] ...]."
  [forma]
  (when-let [s (esquema-de-datos forma)]
    (when (= :map (m/type s))
      (mapv (fn [[k props hijo]] [k {:tipo (m/form hijo) :opcional (boolean (:optional props))}])
            (m/children s)))))

(defn campos-de
  "Campos de un esquema [:map ...]: nombre → {:tipo forma :opcional bool}."
  [forma]
  (some->> (campos-ordenados forma) (into {})))

(defn- tipos-de
  "Campo → tipo de un esquema [:map ...], o nil si está mal escrito."
  [forma]
  (some-> (campos-de forma) (update-vals :tipo)))

;; --- Forma de cada dato ---------------------------------------------------------

(def ^:private Entidad
  [:map {:closed true}
   [:entidad/tipo :keyword]
   [:entidad/campos [:map-of :qualified-keyword :any]]
   [:entidad/extensible {:optional true} :boolean]])

(def ^:private TipoHecho
  [:map {:closed true}
   [:tipo-hecho/id :qualified-keyword]
   [:tipo-hecho/datos :any]
   [:tipo-hecho/regimen [:enum :solo-agregar :flexible]]
   [:tipo-hecho/mueve {:optional true}
    [:vector [:map {:closed true}
              [:saldo :qualified-keyword]
              [:cada {:optional true} :any]
              [:clave [:map-of :keyword :any]]
              [:suma [:map-of :keyword :any]]]]]])

(def ^:private Concepto
  [:and
   [:map {:closed true}
    [:concepto/id :qualified-keyword]
    [:concepto/version pos-int?]
    [:concepto/rige-desde {:optional true} inst?]
    [:concepto/tipo :keyword]
    [:concepto/params [:map-of :keyword :any]]
    [:concepto/forma {:optional true} :any]
    [:concepto/calculo {:optional true} :qualified-symbol]
    [:concepto/evidencia {:optional true} [:enum :guardar]]]
   [:fn {:error/message "lleva :concepto/forma o :concepto/calculo, uno solo"}
    (fn [c] (not= (contains? c :concepto/forma) (contains? c :concepto/calculo)))]])

(def ^:private Contrato
  [:map {:closed true}
   [:op/id :qualified-keyword]
   [:op/capacidad :keyword]
   [:op/tipo [:enum :consulta :efecto :propuesta]]
   [:op/entradas :any]
   [:op/salidas {:optional true} [:map-of :keyword :any]]
   [:op/efectos {:optional true} [:vector :any]]
   [:op/inverso {:optional true} :qualified-keyword]
   [:op/entidad {:optional true} :keyword]
   [:op/impl {:optional true} :qualified-keyword]])

(def eventos
  "Catálogo de eventos que admite :politica/aplica-a (notación 6.1)."
  #{:vence :registra :cada})

(def opciones-de-vigencia
  "Catálogo de opciones de :politica/aplica-con (D50)."
  #{:inicio-del-caso :fecha-del-paso :cierre-del-periodo})

(def ^:private Politica
  [:map {:closed true}
   [:politica/id :qualified-keyword]
   [:politica/version pos-int?]
   [:politica/rige-desde inst?]
   [:politica/origen [:enum :empresa :legal]]
   [:politica/aplica-a [:or :qualified-keyword
                        [:set :qualified-keyword]
                        [:tuple (into [:enum] eventos) :any]]]
   [:politica/aplica-con {:optional true} (into [:enum] opciones-de-vigencia)]
   [:politica/cuando {:optional true} :any]
   [:politica/forma [:enum :exige :prohibe :limita :sugiere :fija :exime :inicia :permite]]
   [:politica/cuerpo :any]
   [:politica/prioridad {:optional true} [:maybe :int]]
   [:politica/fuente {:optional true} :string]
   [:politica/verificada {:optional true} :boolean]])

(def ^:private campo-y-valor
  "Fija y sugiere: un campo de la entrada (`:entrada/x`), o con `:cada` un campo
  de cada línea de una entrada que es una lista (D114), opcionalmente solo de
  las líneas que cumplen `:donde`."
  [:or
   [:map {:closed true} [:campo :qualified-keyword] [:valor :any]]
   [:map {:closed true} [:cada :qualified-keyword] [:campo :keyword] [:valor :any] [:donde {:optional true} :any]]])

(def ^:private cuerpos
  "Forma del cuerpo de cada una de las ocho formas de política (D31, D53)."
  {:exige   [:vector {:min 1}
             [:map {:closed true}
              [:hecho :qualified-keyword]
              [:minimo {:optional true} pos-int?]
              [:donde {:optional true} :any]]]
   :prohibe [:map {:closed true} [:razon :string]]
   :limita  [:map {:closed true}
             [:valor :any] [:maximo :any]
             [:tolerancia {:optional true} :any]
             [:al-exceder-tolerancia {:optional true} :keyword]
             ;; Con qué operación se cubre lo que excede el máximo (D97).
             [:cubre-con {:optional true}
              [:map {:closed true}
               [:operacion :qualified-keyword]
               [:entradas {:optional true} [:map-of :keyword :any]]
               [:parcial {:optional true} :boolean]]]]
   :sugiere campo-y-valor
   :fija    campo-y-valor
   :exime   [:map {:closed true} [:hasta {:optional true} :any]]
   :inicia  [:map {:closed true} [:objetivo :qualified-keyword] [:despues {:optional true} :any]
             [:entradas {:optional true} [:map-of :keyword :any]]]
   :permite [:map {:closed true} [:quien :any] [:ambito {:optional true} :any]]})

(def ^:private Camino
  [:map {:closed true}
   [:camino/id :qualified-keyword]
   [:camino/version pos-int?]
   [:camino/objetivo :qualified-keyword]
   [:camino/reglas [:map-of :qualified-keyword pos-int?]]
   [:camino/pasos [:vector {:min 1} :qualified-keyword]]
   [:camino/estado [:enum :borrador :activo :retirado]]])

;; --- Utilidades -----------------------------------------------------------------

(defn- forma
  "Problemas de forma de un dato contra un esquema de Malli."
  [esquema dato]
  (when-let [errores (m/explain esquema dato)]
    [{:problema :forma :en dato :mensaje (pr-str (me/humanize errores))}]))

(defn- problema [tipo en mensaje] {:problema tipo :en en :mensaje mensaje})

(def ^:private refs-comunes
  {'actor :ref 'empresa :ref 'ahora :fecha 'caso :cualquiera 'origen :cualquiera})

(defn- entorno
  "Entorno de validación para las expresiones de una operación."
  [catalogo entradas]
  {:refs      (assoc refs-comunes 'entrada entradas 'impl :cualquiera)
   :campos    (:campos catalogo)
   :conceptos (:conceptos catalogo)})

;; --- Tipos de entidad y de hecho -------------------------------------------------

(defn problemas-entidad [entidad]
  (or (forma Entidad entidad)
      (for [[campo tipo] (:entidad/campos entidad)
            :when (nil? (esquema-de-datos tipo))]
        (problema :tipo-desconocido campo (str "No existe el tipo " (pr-str tipo))))))

(defn problemas-tipo-hecho [tipo-hecho catalogo]
  (or (forma TipoHecho tipo-hecho)
      (let [datos (tipos-de (:tipo-hecho/datos tipo-hecho))
            ent   (-> (entorno catalogo {})
                      (assoc-in [:refs 'hecho] datos)
                      (assoc-in [:refs 'cada] :cualquiera))]
        (if (nil? datos)
          [(problema :datos-invalidos (:tipo-hecho/datos tipo-hecho) "Los datos deben ser un [:map ...] válido")]
          (for [mueve (:tipo-hecho/mueve tipo-hecho)
                expr  (concat (vals (:clave mueve)) (vals (:suma mueve)))
                p     (val/problemas-expresion expr ent)]
            p)))))

;; --- Conceptos -------------------------------------------------------------------

(defn problemas-concepto [concepto catalogo]
  (or (forma Concepto concepto)
      (when-let [expr (:concepto/forma concepto)]
        (let [params (update-keys (:concepto/params concepto) (comp symbol name))
              ent    {:refs      (merge (select-keys refs-comunes ['empresa 'ahora]) params)
                      :campos    (:campos catalogo)
                      :conceptos (:conceptos catalogo)}]
          (val/problemas-expresion expr ent)))))

(defn- conceptos-usados [catalogo expr]
  (->> (tree-seq coll? seq expr)
       (filter #(and (vector? %) (contains? (:conceptos catalogo) (first %))))
       (map first)))

(defn problemas-de-ciclos
  "Los conceptos forman un grafo sin ciclos (notación 3.3)."
  [catalogo]
  (let [usa   (update-vals (:conceptos catalogo) #(set (conceptos-usados catalogo (:concepto/forma %))))
        llega (fn llega [desde hasta vistos]
                (some (fn [c] (or (= c hasta)
                                  (and (not (vistos c)) (llega c hasta (conj vistos c)))))
                      (usa desde)))]
    (for [id (keys usa) :when (llega id id #{})]
      (problema :ciclo id (str "El concepto " id " depende de sí mismo")))))

;; --- Contratos -------------------------------------------------------------------

(defn problemas-contrato [contrato catalogo]
  (or (forma Contrato contrato)
      (let [entradas (tipos-de (:op/entradas contrato))
            efectos  (:op/efectos contrato)
            ent      (entorno catalogo entradas)
            {:keys [problemas] ent-final :ent} (val/analizar-efectos efectos ent)]
        (cond
          (nil? entradas)
          [(problema :entradas-invalidas (:op/entradas contrato) "Las entradas deben ser un [:map ...] válido")]

          (and (not= :efecto (:op/tipo contrato)) (seq efectos))
          [(problema :efectos-en-consulta (:op/id contrato) "Solo una operación de tipo :efecto declara efectos")]

          :else
          (concat
           problemas
           (for [ef   (tree-seq vector? seq (vec efectos))
                 :when (and (vector? ef) (#{:registra-hecho} (first ef))
                            (seq (:tipos-hecho catalogo))
                            (not (contains? (:tipos-hecho catalogo) (second ef))))]
             (problema :tipo-hecho-desconocido ef (str "No existe el tipo de hecho " (second ef))))
           (for [expr (vals (:op/salidas contrato))
                 p    (val/problemas-expresion expr ent-final)]
             p))))))

;; --- Políticas -------------------------------------------------------------------

(defn- entradas-de
  "Entradas que ve una política según a qué aplica: las de la operación, las
  comunes a un conjunto de operaciones, o ninguna si aplica a un evento."
  [catalogo aplica-a]
  (let [de (fn [op] (some-> (get-in catalogo [:contratos op :op/entradas]) tipos-de))]
    (cond
      (keyword? aplica-a) (de aplica-a)
      (set? aplica-a)     (when-let [todas (seq (map de aplica-a))]
                            (when (every? some? todas)
                              (into {} (apply set/intersection (map set todas)))))
      :else               {})))

(defn- expresiones-de
  "Expresiones del cuerpo de una política: [expr tipo-esperado símbolos-extra]."
  [{:politica/keys [forma cuerpo]}]
  (case forma
    :exige   (for [r cuerpo :when (contains? r :donde)] [(:donde r) :booleano {'hecho :cualquiera}])
    :prohibe []
    :limita  (concat (for [k [:valor :maximo :tolerancia] :when (contains? cuerpo k)] [(k cuerpo) nil nil])
                     (for [x (vals (get-in cuerpo [:cubre-con :entradas]))] [x nil {'faltante :cualquiera}]))
    (:sugiere :fija) (if (contains? cuerpo :cada)
                       (cond-> [[(:valor cuerpo) nil {'cada :cualquiera}]]
                         (contains? cuerpo :donde) (conj [(:donde cuerpo) :booleano {'cada :cualquiera}]))
                       [[(:valor cuerpo) nil nil]])
    :exime   (when (contains? cuerpo :hasta) [[(:hasta cuerpo) :booleano nil]])
    :inicia  (concat (when (contains? cuerpo :despues) [[(:despues cuerpo) :duracion nil]])
                     (for [x (vals (:entradas cuerpo))] [x nil {'hecho :cualquiera}]))
    :permite (cond-> [[(:quien cuerpo) nil nil]]
               (contains? cuerpo :ambito) (conj [(:ambito cuerpo) :booleano nil]))))

(defn problemas-politica [politica catalogo]
  (or (forma Politica politica)
      (forma (cuerpos (:politica/forma politica)) (:politica/cuerpo politica))
      (let [{:politica/keys [aplica-a cuando cuerpo] f :politica/forma} politica
            ops      (cond (keyword? aplica-a) [aplica-a] (set? aplica-a) aplica-a)
            faltan   (remove #(contains? (:contratos catalogo) %) ops)
            entradas (entradas-de catalogo aplica-a)
            ent      (entorno catalogo entradas)
            revisar  (fn [[expr esperado extra]]
                       (let [ent (update ent :refs merge extra)]
                         (if esperado
                           (val/problemas-expresion expr ent esperado)
                           (val/problemas-expresion expr ent))))]
        (if (seq faltan)
          (for [op faltan] (problema :operacion-desconocida op (str "No existe la operación " op)))
          (concat
           (when (contains? politica :politica/cuando) (revisar [cuando :booleano nil]))
           (mapcat revisar (expresiones-de politica))
           (when (and (#{:sugiere :fija} f) (not (contains? cuerpo :cada))
                      (not (contains? entradas (keyword (name (:campo cuerpo))))))
             [(problema :campo-desconocido (:campo cuerpo)
                        (str "La operación no tiene la entrada " (:campo cuerpo)))])
           (when (and (#{:sugiere :fija} f) (contains? cuerpo :cada))
             (for [op ops
                   :let [forma (get (campos-de (get-in catalogo [:contratos op :op/entradas]))
                                    (keyword (name (:cada cuerpo))))
                         linea (when (and (vector? (:tipo forma)) (= :vector (first (:tipo forma))))
                                 (campos-de (last (:tipo forma))))]
                   :when (not (contains? linea (:campo cuerpo)))]
               (problema :campo-desconocido (:campo cuerpo)
                         (str op " no tiene una lista " (:cada cuerpo) " con el campo " (:campo cuerpo)))))
           (when (and (= :inicia f) (not (contains? (:contratos catalogo) (:objetivo cuerpo))))
             [(problema :operacion-desconocida (:objetivo cuerpo)
                        (str "No existe la operación " (:objetivo cuerpo)))])
           (when-let [op (and (= :limita f) (get-in cuerpo [:cubre-con :operacion]))]
             (when-not (contains? (:contratos catalogo) op)
               [(problema :operacion-desconocida op (str "No existe la operación " op))]))
           (when (and (= :limita f) (get-in cuerpo [:cubre-con :parcial])
                      (not (and (symbol? (:valor cuerpo)) (= "entrada" (namespace (:valor cuerpo))))))
             [(problema :parcial-sin-entrada (:valor cuerpo)
                        "Un límite parcial mide una entrada (entrada/x): es la que se divide")])
           (when (= :exige f)
             (for [{:keys [hecho]} cuerpo
                   :when (not (contains? (:tipos-hecho catalogo) hecho))]
               (problema :tipo-hecho-desconocido hecho (str "No existe el tipo de hecho " hecho)))))))))

;; --- Caminos ---------------------------------------------------------------------

(defn problemas-camino [camino catalogo]
  (or (forma Camino camino)
      (let [versiones (set (map (juxt :politica/id :politica/version) (:politicas catalogo)))]
        (concat
         (for [op (conj (:camino/pasos camino) (:camino/objetivo camino))
               :when (not (contains? (:contratos catalogo) op))]
           (problema :operacion-desconocida op (str "No existe la operación " op)))
         (for [regla (:camino/reglas camino) :when (not (versiones regla))]
           (problema :regla-desconocida regla (str "No existe la política " (pr-str regla))))))))
