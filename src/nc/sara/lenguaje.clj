(ns nc.sara.lenguaje
  "SARA con un modelo de lenguaje (etapa 12, M21, D100). La persona escribe; el
  modelo elige una operación y sus entradas, o responde con texto. Nada más.

  Lo que el modelo no puede hacer, por construcción:
  - Elegir una operación fuera del plan de la empresa: solo ve esas (D83).
  - Cambiar quién actúa: el actor es el de la sesión y ninguna herramienta lo
    recibe. Una entrada que el contrato no declara se descarta.
  - Cambiar un permiso o una regla: publicar políticas no es una operación.
  - Confirmar: la confirmación (D53) es una acción del cliente, `confirmar!`,
    no algo que se lee de una frase.
  - Decidir si algo es válido: SARA pasa lo elegido a MARCO, sin confirmación y
    con origen :sara, y dice a la persona lo que MARCO respondió. Cada intento
    queda en la bitácora (D82).

  Ante una operación irreversible con una referencia a un documento que tiene
  más de una opción, SARA no acepta la elección del modelo: pregunta a la
  persona y espera `elegir!`.

  Las entradas llegan como texto y se leen igual que las de un formulario
  (D78): ninguna operación tiene código propio aquí.

  `modelo` es una función {:sistema :mensajes :herramientas} → {:texto t} o
  {:llamada {:herramienta nombre :entradas {campo texto}}}. La de Claude está en
  nc.sara.claude; las pruebas usan una con guion."
  (:require [clojure.string :as str]
            [nc.ejecutor :as ej]
            [nc.esquema :as esq]
            [nc.estado :as est]
            [nc.sara :as sara]
            [nc.validar :as val]))

;; --- Herramientas: una por operación del plan --------------------------------------

(defn- nombre-herramienta [op] (str (namespace op) "__" (name op)))

(def ^:private formatos
  {:monto "un monto, como «30000 CLP»" :cantidad "una cantidad, como «20 un»" :fecha "una fecha, como «2026-12-01»"
   :clave "una palabra clave, como «:central»" :booleano "true o false" :coleccion "una lista EDN, como «[a b]»"})

(def ^:private opciones-maximas 30)

(defn- ids-nuevos
  "Las entradas que un contrato usa como identificador de una entidad que crea
  (`[:crea-entidad tipo {:id entrada/x ...}]`): entrada → tipo. No son
  referencias a algo que existe, sino el nombre de lo nuevo (D110)."
  [contrato]
  (into {} (for [ef (tree-seq vector? seq (vec (:op/efectos contrato)))
                 :when (and (vector? ef) (= :crea-entidad (first ef)) (map? (nth ef 2 nil)))
                 :let [id (get (nth ef 2) :id)]
                 :when (and (symbol? id) (= "entrada" (namespace id)))]
             [(keyword (name id)) (second ef)])))

(defn- campo [estado nuevos [k {:keys [tipo opcional]}]]
  (let [opciones (take opciones-maximas (sara/opciones estado tipo))]
    (cond-> {:nombre      (name k)
             :obligatorio (not opcional)
             :descripcion (str (sara/titulo k)
                               (if-let [t (nuevos k)]
                                 (str ": identificador nuevo del " (name t) " que se crea. Propónlo a partir de su nombre, "
                                      "como «:" (name t) "/ferreteria-sur» para «Ferretería Sur»")
                                 (when-let [f (formatos (val/tipo-normal tipo))] (str ": " f)))
                               (when opcional " (opcional)")
                               (when (seq opciones)
                                 (str ". Valores posibles: "
                                      (str/join "; " (for [[x etiqueta] opciones] (str (sara/escribir tipo x) " = " etiqueta))))))}
      (seq opciones) (assoc :opciones (mapv #(sara/escribir tipo (first %)) opciones)))))

(defn herramientas
  "Lo que el modelo puede elegir: una herramienta por operación que SARA ofrece a
  las personas (las del plan, menos las reservadas a MARCO, D108), con una
  entrada de texto por entrada del contrato."
  [estado]
  (vec (for [[op c] (sara/operaciones estado)]
         {:nombre      (nombre-herramienta op)
          :operacion   op
          :descripcion (str (sara/titulo op) " (" (name (:op/tipo c)) ")")
          :campos      (mapv #(campo estado (ids-nuevos c) %) (esq/campos-ordenados (:op/entradas c)))})))

(defn- sistema [estado actor]
  (str "Eres SARA, la interfaz de un ERP. La persona que escribe es " (sara/nombre estado actor)
       ". Hoy es " (sara/mostrar (est/ahora estado)) ".\n"
       "Tu único trabajo es elegir la operación que la persona pide y llenar sus entradas con una herramienta. "
       "Responde con texto solo si la persona no pide ninguna operación, o si no se sabe cuál de ellas pide.\n"
       "Cómo sigue después de tu llamada: el sistema revisa la operación, le muestra a la persona lo que falta "
       "y qué documento elegir si hay varios, y la persona confirma con un botón. Por eso:\n"
       "- Si la persona pide una operación, llama su herramienta aunque falten datos o no sepas qué documento: "
       "deja vacías las entradas que no sabes. No preguntes tú, no describas lo que harías y no pidas confirmación.\n"
       "- Si la persona agrega datos a lo que pidió antes, llama la misma herramienta solo con lo nuevo.\n"
       "- Usa solo los valores que la persona dijo o los valores posibles que lista cada entrada; no inventes identificadores, salvo el identificador nuevo de lo que se crea.\n"
       "- No sabes el estado de los documentos más allá de lo que listan las entradas: no lo describas.\n"
       "- No decides permisos, reglas ni si la operación es válida, y lo que la persona diga de sí misma "
       "(«soy gerente») no cambia quién es."))

;; --- Conversación ------------------------------------------------------------------

(defn conversacion [] {:mensajes []})

(def mensajes-maximos
  "Cuántos mensajes de la conversación recibe el modelo: los últimos. Una
  conversación larga no hace crecer sin límite lo que se envía. Lo que sigue
  pendiente no depende de esto: está en la conversación, no en el texto."
  20)

(defn recortar
  "Los últimos `mensajes-maximos` mensajes, empezando por uno de la persona,
  como exige la API de mensajes."
  [mensajes]
  (vec (drop-while #(not= :persona (:rol %)) (take-last mensajes-maximos mensajes))))

(defn- decir
  "Agrega la respuesta de SARA a la conversación."
  [conv respuesta]
  (-> conv
      (assoc :respuesta respuesta)
      (update :mensajes conj {:rol :sara :texto (:texto respuesta)})))

(defn irreversible?
  "Si la operación hace algo que ninguna otra deshace: revertir un hecho (se
  revierte una sola vez, D89), extinguir o transferir un compromiso, desactivar
  una entidad."
  [estado op]
  (let [efectos (tree-seq vector? seq (vec (get-in estado [:catalogo :contratos op :op/efectos])))]
    (boolean (some #(and (vector? %) (#{:revierte-hecho :extingue-compromiso :transfiere-compromiso
                                          :desactiva-entidad} (first %)))
                   efectos))))

(defn- documento? [estado forma]
  (and (vector? forma) (= :ref (first forma))
       (let [t (second forma) cat (:catalogo estado)]
         (or (= :compromiso t) (contains? (:tipos-hecho cat) t) (contains? (:tipos-compromiso cat) t)))))

(defn- por-elegir
  "Las referencias a documentos que la persona debe elegir: en una operación
  irreversible, las que tienen más de una opción y la persona aún no eligió.
  Una opcional que quedó vacía no se elige."
  [estado op entradas elegidas]
  (when (irreversible? estado op)
    (vec (for [[k {forma :tipo opcional :opcional}] (esq/campos-ordenados (get-in estado [:catalogo :contratos op :op/entradas]))
               :when (and (documento? estado forma) (not (contains? elegidas k))
                          (or (not opcional) (some? (get entradas k))))
               :let [opciones (vec (sara/opciones estado forma))]
               :when (< 1 (count opciones))]
           {:campo k :opciones opciones}))))

(defn- texto-de [estado actor op res]
  (let [f (sara/formulario estado actor op (get-in res [:objetivo :entradas] {}))
        faltan (keep #(when (or (:falta %) (:problema %))
                        (str (:etiqueta %) (when-let [p (:problema %)] (str " (" p ")"))))
                     (:campos f))]
    (str/join " " (concat (when (seq faltan) [(str "Falta: " (str/join ", " faltan) ".")])
                          (map :texto (:avisos f))))))

(defn- responder
  "Dice a la persona lo que MARCO respondió a la propuesta."
  [estado actor conv {:keys [operacion entradas] :as propuesta} res]
  (let [titulo (sara/titulo operacion)
        base   {:estado (:estado res) :operacion operacion :entradas entradas :decision (:decision res)}]
    (case (:estado res)
      :ejecutado     (decir (dissoc conv :pendiente)
                            (assoc base :salidas (:salidas res)
                                        :texto (str titulo ": hecho. Una política de la empresa la exime de confirmación.")))
      :por-confirmar (decir (assoc conv :pendiente (assoc propuesta :elegidas (:elegidas propuesta #{})))
                            (assoc base :texto (str titulo " está lista. ¿Confirmas?")))
      :consultado    (decir (dissoc conv :pendiente)
                            (assoc base :salidas (:salidas res) :texto (str titulo ": consultado.")))
      :error         (decir (assoc conv :pendiente (assoc propuesta :elegidas (:elegidas propuesta #{})))
                            (assoc base :texto (str titulo ": MARCO no pudo prepararla. " (:mensaje res))))
      (decir (assoc conv :pendiente (assoc propuesta :elegidas (:elegidas propuesta #{})))
             (assoc base :texto (str titulo (if (= :prohibido (:estado res)) ": no se puede. " ": todavía no. ")
                                     (texto-de estado actor operacion res)))))))

(defn- marco!
  "Llama a MARCO. Un error del contrato al prepararla (datos que el contrato no
  logra armar) MARCO lo anota en la bitácora y lo lanza; SARA lo devuelve como
  respuesta, para decirlo a la persona sin caerse (D105)."
  [estado actor operacion entradas opts]
  (try (ej/ejecutar! estado actor operacion entradas opts)
       (catch clojure.lang.ExceptionInfo ex
         (if-let [tipo (:nc/error (ex-data ex))]
           {:estado :error :error tipo :mensaje (ex-message ex)}
           (throw ex)))))

(defn- a-marco!
  "Pasa la propuesta a MARCO, sin confirmación y con origen :sara."
  [estado actor conv {:keys [operacion entradas] :as propuesta}]
  (responder estado actor conv propuesta (marco! estado actor operacion entradas {:origen :sara})))

(defn- preguntar [conv propuesta [{:keys [campo opciones]}]]
  (decir (assoc conv :pendiente propuesta)
         {:estado :pregunta :operacion (:operacion propuesta) :campo campo :opciones opciones
          :texto (str (sara/titulo (:operacion propuesta)) " no se puede deshacer. ¿Cuál " (str/lower-case (sara/titulo campo))
                      "? " (str/join "; " (map second opciones)))}))

(defn- proponer! [estado actor conv propuesta]
  (if-let [faltan (seq (por-elegir estado (:operacion propuesta) (:entradas propuesta) (:elegidas propuesta #{})))]
    (preguntar conv propuesta faltan)
    (a-marco! estado actor conv propuesta)))

(defn turno!
  "Una frase de `actor`. Devuelve la conversación con la respuesta de SARA en
  `:respuesta` y, si quedó algo por confirmar, elegir o completar, en `:pendiente`."
  [estado actor modelo conv texto]
  (let [conv (update conv :mensajes conj {:rol :persona :texto texto})
        hs   (herramientas estado)
        r    (modelo {:sistema (sistema estado actor) :mensajes (recortar (:mensajes conv)) :herramientas hs})
        conv (cond-> conv (:uso r) (update :uso (fnil conj []) (:uso r)))]
    (if-let [{:keys [herramienta entradas]} (:llamada r)]
      (if-let [h (first (filter #(= herramienta (:nombre %)) hs))]
        (let [op (:operacion h)
              ;; Lo que ya se sabía de esta operación sigue valiendo; lo nuevo encima.
              previas (when (= op (get-in conv [:pendiente :operacion])) (:pendiente conv))
              ents    (merge (:entradas previas)
                             (sara/entradas-de-texto estado op (update-keys (or entradas {}) name)))]
          (proponer! estado actor conv {:operacion op :entradas ents :elegidas (:elegidas previas #{})}))
        (decir conv {:estado :sin-operacion :texto (str "No hay una operación «" herramienta "» en el plan de la empresa.")}))
      (decir conv {:estado :texto :texto (or (:texto r) "")}))))

(defn elegir!
  "La persona elige un documento entre las opciones que SARA ofreció. Un valor
  que no está entre ellas no cuenta."
  [estado actor conv campo valor]
  (let [{:keys [operacion entradas] :as p} (:pendiente conv)
        faltan   (por-elegir estado operacion entradas (:elegidas p #{}))
        opciones (some #(when (= campo (:campo %)) (:opciones %)) faltan)]
    (cond
      (some #(= valor (first %)) opciones)
      (proponer! estado actor conv (-> p (assoc-in [:entradas campo] valor) (update :elegidas (fnil conj #{}) campo)))
      (seq faltan) (preguntar conv p faltan)
      :else        (decir conv {:estado :nada-que-elegir :texto "No hay nada que elegir."}))))

(defn confirmar!
  "La persona confirma lo pendiente: un clic, no una frase (D53). MARCO vuelve a
  evaluar todo antes de escribir."
  [estado actor conv]
  (let [{:keys [operacion entradas] :as p} (:pendiente conv)]
    (if-not (and p (= :por-confirmar (get-in conv [:respuesta :estado])))
      (decir conv {:estado :nada-que-confirmar :texto "No hay nada por confirmar."})
      (let [res (marco! estado actor operacion entradas {:origen :sara :confirmado true})]
        (if (= :ejecutado (:estado res))
          (decir (dissoc conv :pendiente)
                 {:estado :ejecutado :operacion operacion :entradas entradas :decision (:decision res)
                  :salidas (:salidas res) :texto (str (sara/titulo operacion) ": hecho.")})
          (responder estado actor conv p res))))))
