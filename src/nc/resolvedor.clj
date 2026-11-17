(ns nc.resolvedor
  "Dado un objetivo (una operación con sus entradas, quizás incompletas), el
  estado y las políticas que rigen, dice qué está satisfecho, qué falta y quién
  puede hacerlo (notación 7.3).

  El contrato decide si las entradas son válidas (P5, D65); las políticas, qué
  más hace falta. Formas que se evalúan en esta etapa: exige, prohíbe, permite
  y exime. Sugiere, fija, limita e inicia se agregan con los escenarios que las
  usan."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [nc.esquema :as esq]
            [nc.estado :as est]
            [nc.expresion :as e]))

(defn- dato [sym] (keyword (namespace sym) (name sym)))

(defn- entrada [k] (keyword "entrada" (name k)))

;; --- Entradas: lo que dice el contrato (P5) ----------------------------------------

(defn- existe?
  "Una referencia apunta a algo de la empresa con el tipo declarado: un
  compromiso, un hecho de ese tipo o una entidad de ese tipo."
  [estado tipo id]
  (let [doc (est/leer estado id)]
    (cond
      (nil? doc)                                       false
      (= :compromiso tipo)                             (contains? doc :compromiso/id)
      (contains? (get-in estado [:catalogo :tipos-hecho]) tipo) (= tipo (:hecho/tipo doc))
      :else                                            (= tipo (:entidad/tipo doc)))))

(defn- problema-de-entrada [estado forma valor]
  (if-let [error (m/explain (esq/esquema-de-datos forma) valor)]
    (str/join "; " (flatten [(me/humanize error)]))
    (when (and (vector? forma) (= :ref (first forma)) (not (existe? estado (second forma) valor)))
      (str "No existe " (pr-str valor) " como " (second forma)))))

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
    (merge {:id hecho :politica (:politica/id p)}
           (cond
             (>= cumplen n) {:estado :satisfecho}
             falta          {:estado :indeterminado :pide [(dato falta)]}
             :else          (merge {:estado :pendiente :faltan (- n cumplen) :via via}
                                   (quien-puede estado ctx via)))
           (when-let [k (entrega estado contrato via)] {:entrega k}))))

(defn- requisitos-de-politica [estado ctx contrato p]
  (let [r (aplica ctx p)]
    (cond
      (not (#{:exige :prohibe :permite} (:politica/forma p))) []
      (contains? r :falta) [{:id (:politica/id p) :estado :indeterminado :pide [(dato (:falta r))]}]
      (not (:valor r))     []
      :else
      (case (:politica/forma p)
        :prohibe [{:id (:politica/id p) :estado :prohibido :razon (get-in p [:politica/cuerpo :razon])}]
        :exige   (mapv #(requisito-exigido estado ctx contrato p %) (:politica/cuerpo p))
        :permite []))))

(defn- eximida?
  "La confirmación se levanta si alguna \"exime\" aplica y todas las que aplican
  se cumplen (D53, P9)."
  [ctx ps]
  (let [eximen (filter #(aplica? ctx %) (de-forma :exime ps))]
    (and (seq eximen)
         (every? (fn [p] (let [hasta (get-in p [:politica/cuerpo :hasta])]
                           (or (nil? hasta) (true? (:valor (e/evaluar-seguro hasta ctx))))))
                 eximen))))

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
        {:keys [validas requisitos opcionales]} (revisar-entradas estado contrato entradas)
        caso     (est/caso-de estado contrato validas)
        refs     (cond-> {'entrada validas 'actor actor 'empresa (:empresa estado) 'ahora (est/ahora estado)}
                   caso (assoc 'caso caso))
        ctx      (est/contexto estado refs)
        ps       (est/politicas estado op caso)
        exigidos (mapcat #(requisitos-de-politica estado ctx contrato %) ps)
        {:keys [quien puede]} (permiso ctx ps)
        reglas   (into {} (map (juxt :politica/id :politica/version)) ps)
        entregan (set (keep :entrega exigidos))
        todos    (vec (concat requisitos
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
      :confirmacion (if (eximida? ctx ps) :eximida :requerida)
      :caso         (:compromiso/id caso)
      :reglas       reglas}}))

(defn resolver
  "Qué está satisfecho, qué falta y quién puede hacerlo para que `actor` ejecute
  `op` con `entradas` (notación 7.3)."
  [estado actor op entradas]
  (:resolucion (analizar estado actor op entradas)))
