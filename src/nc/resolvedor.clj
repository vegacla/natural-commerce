(ns nc.resolvedor
  "Dado un objetivo (una operación con sus entradas, quizás incompletas), el
  estado y las políticas que rigen, dice qué está satisfecho, qué falta y quién
  puede hacerlo (notación 7.3).

  El contrato decide si las entradas son válidas (P5, D65); las políticas, qué
  más hace falta. Fija y sugiere dan valor a entradas antes de revisarlas:
  sugiere llena una que falta; fija la llena y rechaza un valor distinto (P4,
  H6). Limita compara un valor con un máximo, con tolerancia (D34). Exige,
  prohíbe y permite dicen qué falta y quién puede; exime levanta la
  confirmación. Inicia no se evalúa aquí: la dispara el ejecutor al registrar
  un hecho (H7).

  Una operación fuera del plan de la empresa está prohibida, y también lo está
  un requisito que solo se cumple con una operación fuera del plan: nada queda
  bloqueado en silencio (D36, D83)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [nc.esquema :as esq]
            [nc.estado :as est]
            [nc.expresion :as e]
            [nc.valor :as v]))

(defn- dato [sym] (keyword (namespace sym) (name sym)))

(defn- entrada [k] (keyword "entrada" (name k)))

;; --- Entradas: lo que dice el contrato (P5) ----------------------------------------

(defn- existe?
  "Una referencia apunta a algo de la empresa con el tipo declarado: un
  compromiso o un hecho de ese tipo, o una entidad de ese tipo (D77)."
  [estado tipo id]
  (let [doc (est/leer estado id)]
    (cond
      (nil? doc)                                       false
      (= :compromiso tipo)                             (contains? doc :compromiso/id)
      (contains? (get-in estado [:catalogo :tipos-compromiso]) tipo) (= tipo (:compromiso/tipo doc))
      (contains? (get-in estado [:catalogo :tipos-hecho]) tipo) (= tipo (:hecho/tipo doc))
      :else                                            (= tipo (:entidad/tipo doc)))))

(defn- problema-de-referencia [estado forma valor]
  (cond
    (not (existe? estado (second forma) valor))
    (str "No existe " (pr-str valor) " como " (second forma))

    (false? (:entidad/activa (est/leer estado valor)))
    (str (pr-str valor) " está desactivado")))

(defn- ref? [forma] (and (vector? forma) (= :ref (first forma))))

(defn- problema-de-entrada
  "Lo que no calza de una entrada con su tipo. Una colección de referencias
  ([:vector [:ref tipo]]) se revisa elemento por elemento."
  [estado forma valor]
  (if-let [error (m/explain (esq/esquema-de-datos forma) valor)]
    (str/join "; " (flatten [(me/humanize error)]))
    (cond
      (ref? forma) (problema-de-referencia estado forma valor)
      (and (vector? forma) (= :vector (first forma)) (ref? (second forma)))
      (some #(problema-de-referencia estado (second forma) %) valor))))

(defn- revisar-entradas
  "Separa las entradas válidas de los requisitos que deja el contrato: una
  obligatoria que falta, o una que no calza, queda pendiente. Una opcional no
  entregada vale nada, no falta."
  [estado contrato entradas]
  (reduce (fn [r [k {:keys [tipo opcional]}]]
            (let [v        (get entradas k)
                  problema (when (some? v) (problema-de-entrada estado tipo v))]
              (cond
                problema    (update r :requisitos conj {:id (entrada k) :estado :pendiente
                                                        :pide [(entrada k)] :problema problema})
                (some? v)   (cond-> (assoc-in r [:validas k] v) opcional (update :opcionales conj k))
                opcional    (-> r (assoc-in [:validas k] nil) (update :opcionales conj k))
                :else       (update r :requisitos conj {:id (entrada k) :estado :pendiente :pide [(entrada k)]}))))
          {:validas {} :requisitos [] :opcionales []}
          (esq/campos-ordenados (:op/entradas contrato))))

;; --- Políticas -----------------------------------------------------------------

(defn- aplica
  "{:valor true} si la política aplica, {:valor false} si no, {:falta ref} si
  falta un dato para saberlo."
  [ctx p]
  (if (contains? p :politica/cuando)
    (e/evaluar-seguro (:politica/cuando p) ctx)
    {:valor true}))

(defn- aplica? [ctx p] (true? (:valor (aplica ctx p))))

(defn- de-forma [forma ps] (filter #(= forma (:politica/forma %)) ps))

(defn- permiso
  "Quién tiene permiso según las políticas \"permite\" que aplican, y si el actor
  lo tiene. Sin ninguna, cualquiera de la empresa (D67)."
  [ctx ps]
  (let [permites (filter #(aplica? ctx %) (de-forma :permite ps))
        actor    (get-in ctx [:refs 'actor])
        incluye? (fn [{{:keys [quien ambito]} :politica/cuerpo}]
                   (let [q (:valor (e/evaluar-seguro quien ctx))]
                     (and (contains? (if (coll? q) (set q) #{q}) actor)
                          (or (nil? ambito) (true? (:valor (e/evaluar-seguro ambito ctx)))))))]
    (if (empty? permites)
      {:quien :cualquiera :puede true}
      (let [qs (mapv #(get-in % [:politica/cuerpo :quien]) permites)]
        {:quien (if (next qs) qs (first qs))
         :puede (boolean (some incluye? permites))}))))

(defn- quien-puede
  "Quién puede ejecutar `op` en el caso, y si el actor no puede: le falta permiso
  o una prohibición lo alcanza. Las entradas de `op` aún no existen, así que se
  ignora lo que dependa de ellas."
  [estado ctx op]
  (let [ctx  (update ctx :refs dissoc 'entrada)
        ps   (est/politicas estado op (get-in ctx [:refs 'caso]))
        {:keys [quien puede]} (permiso ctx ps)]
    (cond-> {:quien quien}
      (or (not puede) (some #(aplica? ctx %) (de-forma :prohibe ps)))
      (assoc :no-puede (get-in ctx [:refs 'actor])))))

(defn- entrega
  "La entrada de este contrato que llena una salida de la operación `via` (D10, D71)."
  [estado contrato via]
  (let [salidas (set (keys (get-in estado [:catalogo :contratos via :op/salidas])))]
    (some (fn [[k]] (when (salidas k) (entrada k))) (esq/campos-ordenados (:op/entradas contrato)))))

(defn- requisito-exigido
  "Un requisito de una política \"exige\": `minimo` hechos del tipo en el caso,
  que cumplan `donde`. Sin caso, ninguno."
  [estado ctx contrato p {:keys [hecho minimo donde]}]
  (let [n          (or minimo 1)
        caso       (get-in ctx [:refs 'caso])
        candidatos (if caso ((:hechos ctx) {:tipo hecho :caso (:compromiso/id caso)}) [])
        evaluados  (for [h candidatos]
                     (if donde (e/evaluar-seguro donde (assoc-in ctx [:refs 'hecho] h)) {:valor true}))
        cumplen    (count (filter #(true? (:valor %)) evaluados))
        falta      (some :falta evaluados)
        via        (est/registradora (:catalogo estado) hecho)]
    (merge {:id hecho :politica (:politica/id p) :via via}
           (cond
             (>= cumplen n) {:estado :satisfecho}
             falta          {:estado :indeterminado :pide [(dato falta)]}
             (not (and via (est/habilitada? estado via)))
             {:estado :prohibido
              :razon  (str (:politica/id p) " exige " hecho ", pero "
                           (if via (str via " no está en el plan contratado") "ninguna operación lo registra"))}
             :else          (merge {:estado :pendiente :faltan (- n cumplen)}
                                   (quien-puede estado ctx via)))
           (when-let [k (entrega estado contrato via)] {:entrega k}))))

(declare requisito-limite)

(defn- requisitos-de-politica [estado ctx contrato p]
  (let [r (aplica ctx p)]
    (cond
      (not (#{:exige :prohibe :permite :limita} (:politica/forma p))) []
      (contains? r :falta) [{:id (:politica/id p) :estado :indeterminado :pide [(dato (:falta r))]}]
      (not (:valor r))     []
      :else
      (case (:politica/forma p)
        :prohibe [{:id (:politica/id p) :estado :prohibido :razon (get-in p [:politica/cuerpo :razon])}]
        :exige   (mapv #(requisito-exigido estado ctx contrato p %) (:politica/cuerpo p))
        :limita  [(requisito-limite estado ctx p)]
        :permite []))))

(defn- eximida?
  "La confirmación se levanta si alguna \"exime\" aplica y se cumplen todas las
  que aplican, o una sola si así lo configura la empresa (D53, P9)."
  [estado ctx ps]
  (let [eximen (filter #(aplica? ctx %) (de-forma :exime ps))
        cumple (fn [p] (let [hasta (get-in p [:politica/cuerpo :hasta])]
                         (or (nil? hasta) (true? (:valor (e/evaluar-seguro hasta ctx))))))]
    (and (seq eximen)
         (if (= :basta-una (est/combinacion estado :exime))
           (boolean (some cumple eximen))
           (every? cumple eximen)))))

;; --- Fija y sugiere: valores de entradas (P4, H6) -----------------------------------

(defn- campo-de [p] (keyword (name (get-in p [:politica/cuerpo :campo]))))

(defn- valor-seguro
  "El valor de una expresión, o nil si falta un dato o no se puede calcular con
  entradas todavía sin revisar."
  [expr ctx]
  (try (:valor (e/evaluar-seguro expr ctx))
       (catch clojure.lang.ExceptionInfo _ nil)))

(defn- prioridad [p] (or (:politica/prioridad p) 0))

(defn- ganadora-fija
  "Entre varias \"fija\" del mismo campo: la legal, o la de mayor prioridad (D35).
  Si empatan, es conflicto: nil y las empatadas."
  [ps]
  (let [legales (filter #(= :legal (:politica/origen %)) ps)
        grupo   (if (seq legales) legales ps)
        maxima  (apply max (map prioridad grupo))
        tope    (filter #(= maxima (prioridad %)) grupo)]
    (if (next tope) [nil tope] [(first tope) nil])))

(defn- ganadora-sugiere
  "Entre varias \"sugiere\" del mismo campo, la que dice la combinación de la
  empresa: por defecto, la más específica, la que tiene condición (P9)."
  [estado ps]
  (let [ordenadas (sort-by (juxt #(if (contains? % :politica/cuando) 0 1) (comp - prioridad)) ps)]
    (case (est/combinacion estado :sugiere)
      :la-de-mayor-prioridad (first (sort-by (comp - prioridad) ps))
      (first ordenadas))))

(defn- valores
  "Las entradas con los valores que fijan y sugieren las políticas, y lo que eso
  deja dicho: los valores usados y los rechazos por un valor distinto del fijado."
  [estado ctx ps entradas]
  (let [aplican (fn [forma] (group-by campo-de (filter #(aplica? ctx %) (de-forma forma ps))))
        fijas   (aplican :fija)
        sugeridas (aplican :sugiere)
        r0      {:entradas entradas :valores [] :requisitos []}
        r1      (reduce (fn [r [k grupo]]
                          (let [[p empate] (ganadora-fija grupo)
                                x (when p (valor-seguro (get-in p [:politica/cuerpo :valor]) ctx))
                                dado (get entradas k)]
                            (cond
                              empate
                              (update r :requisitos conj
                                      {:id (entrada k) :estado :prohibido :conflicto (mapv :politica/id empate)
                                       :razon (str "Varias políticas fijan " (name k) " con la misma prioridad")})
                              (nil? x) r
                              (and (some? dado) (not (v/igual? dado x)))
                              (update r :requisitos conj
                                      {:id (entrada k) :estado :prohibido :politica (:politica/id p) :valor x
                                       :razon (str (:politica/id p) " fija " (name k) " en " (pr-str x))})
                              :else
                              (-> r (assoc-in [:entradas k] x)
                                  (update :valores conj {:campo (entrada k) :forma :fija :politica (:politica/id p) :valor x})))))
                        r0 fijas)]
    (reduce (fn [r [k grupo]]
              (let [p (ganadora-sugiere estado grupo)
                    x (valor-seguro (get-in p [:politica/cuerpo :valor]) ctx)]
                (if (or (nil? x) (some? (get-in r [:entradas k])) (contains? fijas k))
                  r
                  (-> r (assoc-in [:entradas k] x)
                      (update :valores conj {:campo (entrada k) :forma :sugiere :politica (:politica/id p) :valor x})))))
            r1 sugeridas)))

;; --- Limita (D34) -----------------------------------------------------------------

(defn- cubierto
  "Lo que excede un \"limita\" que dice con qué se cubre (`:cubre-con`, D97): un
  requisito pendiente que se cumple con esa operación, por lo que falta
  (`faltante`). Con `:parcial`, lo que cabe bajo el máximo puede ir antes: el
  planificador divide el paso en la entrada que el límite mide."
  [estado ctx p base faltante]
  (let [{:keys [operacion entradas parcial]} (get-in p [:politica/cuerpo :cubre-con])
        valor (get-in p [:politica/cuerpo :valor])
        ctx   (assoc-in ctx [:refs 'faltante] faltante)
        ents  (:valor (e/evaluar-seguro (or entradas {}) ctx))]
    (merge base
           {:faltante faltante :via operacion}
           (if (est/habilitada? estado operacion)
             (merge {:estado :pendiente :cubre {:operacion operacion :entradas ents}}
                    (quien-puede estado ctx operacion))
             {:estado :prohibido
              :razon  (str (:politica/id p) " se cubre con " operacion ", que no está en el plan contratado")})
           (when (and parcial (symbol? valor) (= "entrada" (namespace valor)))
             {:parcial {:campo (keyword (name valor)) :cabe (:maximo base)}}))))

(defn- requisito-limite
  "Un \"limita\": el valor no supera el máximo, o lo supera dentro de la
  tolerancia. Más allá se prohíbe, salvo que la política diga con qué se cubre
  lo que falta: entonces queda pendiente (D97). Si varias aplican, rige la más
  restrictiva: cada una se evalúa."
  [estado ctx p]
  (let [{:keys [valor maximo tolerancia cubre-con]} (:politica/cuerpo p)
        x   (e/evaluar-seguro valor ctx)
        m   (e/evaluar-seguro maximo ctx)
        tol (when tolerancia (e/evaluar-seguro tolerancia ctx))
        id  (:politica/id p)]
    (if-let [falta (some :falta [x m tol])]
      {:id id :estado :indeterminado :pide [(dato falta)]}
      (let [x (:valor x) m (:valor m)
            tope (if tol (v/sumar m (v/multiplicar m (:valor tol))) m)
            base {:id id :politica id :valor x :maximo m}]
        (cond
          (not (pos? (v/comparar x m)))    (assoc base :estado :satisfecho)
          (not (pos? (v/comparar x tope))) (assoc base :estado :satisfecho :tolerado true :tope tope)
          cubre-con                        (cubierto estado ctx p base (v/restar x m))
          :else (assoc base :estado :prohibido
                       :razon (str "Supera el límite de " id ": " (pr-str x) " sobre " (pr-str (if tol tope m)))))))))

;; --- Respuesta -------------------------------------------------------------------

(defn- estado-general [requisitos]
  (let [estados (set (map :estado requisitos))]
    (cond (estados :prohibido)                        :prohibido
          (some estados [:pendiente :indeterminado])  :bloqueado
          :else                                       :listo)))

(defn analizar
  "Todo lo que el resolvedor sabe de un objetivo: la respuesta en `:resolucion` y
  lo que el ejecutor reutiliza (contrato, entradas válidas, caso, referencias,
  políticas)."
  [estado actor op entradas]
  (let [contrato (or (get-in estado [:catalogo :contratos op])
                     (e/error! :operacion-desconocida (str "No existe la operación " op) {:op op}))
        ;; Fija y sugiere ven las entradas como llegaron, antes de revisarlas.
        caso0    (est/caso-de estado contrato entradas)
        traza0   (atom {})
        ctx0     (est/contexto estado (cond-> {'entrada entradas 'actor actor 'empresa (:empresa estado)
                                               'ahora (est/ahora estado)}
                                        caso0 (assoc 'caso caso0))
                               {} traza0)
        {entradas :entradas :keys [valores] fijadas :requisitos}
        (valores estado ctx0 (est/politicas estado op caso0) entradas)
        {:keys [validas requisitos opcionales]} (revisar-entradas estado contrato entradas)
        caso     (est/caso-de estado contrato validas)
        refs     (cond-> {'entrada validas 'actor actor 'empresa (:empresa estado) 'ahora (est/ahora estado)}
                   caso (assoc 'caso caso))
        ctx      (est/contexto estado refs)
        ps       (est/politicas estado op caso)
        exigidos (mapcat #(requisitos-de-politica estado ctx contrato %) ps)
        {:keys [quien puede]} (permiso ctx ps)
        en-plan? (est/habilitada? estado op)
        ;; Las versiones de las políticas y de las tablas con que se dio valor a
        ;; las entradas (D48).
        reglas   (merge (into {} (map (juxt :politica/id :politica/version)) ps)
                        (:reglas @traza0))
        entregan (set (keep :entrega exigidos))
        todos    (vec (concat (when-not en-plan?
                                [{:id :plan :estado :prohibido
                                  :razon (str "La capacidad " (:op/capacidad contrato) " no está en el plan contratado")}])
                              requisitos
                              fijadas
                              exigidos
                              (when-not puede
                                [{:id :permiso :estado :prohibido :quien quien
                                  :razon (str actor " no tiene permiso para " op)}])
                              (for [k opcionales :when (not (entregan (entrada k)))]
                                {:id (entrada k) :estado :opcional})))]
    {:contrato contrato
     :validas  validas
     :caso     caso
     :refs     refs
     :reglas   reglas
     :resolucion
     {:objetivo     {:operacion op :entradas entradas}
      :estado       (estado-general todos)
      :requisitos   todos
      :valores      valores
      :confirmacion (if (eximida? estado ctx ps) :eximida :requerida)
      :caso         (:compromiso/id caso)
      :reglas       reglas}}))

(defn resolver
  "Qué está satisfecho, qué falta y quién puede hacerlo para que `actor` ejecute
  `op` con `entradas` (notación 7.3)."
  [estado actor op entradas]
  (:resolucion (analizar estado actor op entradas)))
