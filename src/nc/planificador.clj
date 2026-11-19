(ns nc.planificador
  "Planes para alcanzar un objetivo (D33, D51). Un plan es una lista de pasos,
  cada uno una operación con las entradas propias del paso. Sale de un camino
  guardado si sus políticas aplican al caso (D54); si no, se deduce.

  El objetivo es una operación ({:operacion op}) o un estado deseado
  ({:cumplir tipo-de-compromiso}): que se cumpla un compromiso de ese tipo. El
  estado deseado se alcanza con una operación que lo cumple; si hay varias, las
  otras quedan como alternativas (D98).

  Los pasos se deducen en profundidad (D97): de los requisitos del objetivo y de
  los requisitos de cada paso deducido. Un requisito deja pasos de tres formas:
  un \"exige\" con la operación que registra el hecho; una entrada que falta y
  que otra operación entrega como salida; y un \"limita\" excedido que dice con
  qué se cubre, que puede dividir el paso en lo que cabe ahora y lo que espera
  la cobertura. Cada paso deducido hereda las entradas propias del paso que lo
  necesita.

  Lo que se ve depende del estado: el caso de un pedido existe solo después de
  registrarlo. Por eso el plan se vuelve a deducir cada vez que avanza.

  Avanzar un plan ejecuta los pasos que el usuario puede ejecutar y deja
  propuestos los demás, con lo que les falta y quién puede hacerlos. Pedir que
  avance es la confirmación del usuario (D14). Al cumplirse el objetivo de un plan
  deducido, el camino queda guardado como borrador por activar."
  (:require [nc.almacen :as alm]
            [nc.ejecutor :as ej]
            [nc.esquema :as esq]
            [nc.estado :as est]
            [nc.resolvedor :as r]
            [nc.valor :as v]))

(defn- campos [estado op]
  (esq/campos-ordenados (get-in estado [:catalogo :contratos op :op/entradas])))

(defn- entradas-de [estado op pool]
  (select-keys pool (map first (campos estado op))))

(defn- paso [op entradas] {:operacion op :entradas (or entradas {})})

(defn- efectivas
  "Las entradas con que se resuelve y ejecuta un paso: las del plan, con las
  propias del paso encima."
  [estado pool {:keys [operacion entradas]}]
  (entradas-de estado operacion (merge pool entradas)))

(defn- resolver-paso [estado actor pool p]
  (r/resolver estado actor (:operacion p) (efectivas estado pool p)))

(defn- lo-que-falta [res]
  (vec (remove #(#{:satisfecho :opcional} (:estado %)) (:requisitos res))))

;; --- Objetivo --------------------------------------------------------------------

(defn- alcanzar
  "La operación con que se alcanza el objetivo y sus alternativas (D98)."
  [estado {:keys [operacion cumplir]}]
  (if operacion
    {:operacion operacion :alternativas []}
    (let [ops (est/cumplidoras estado cumplir)]
      {:operacion (first ops) :alternativas (vec (rest ops))})))

;; --- Caminos ---------------------------------------------------------------------

(defn- politicas-que-aplican
  "Políticas que hoy producen requisitos para el objetivo, con su versión."
  [res]
  (select-keys (:reglas res) (keep :politica (:requisitos res))))

(defn- rechazo
  "Por qué un camino no sirve para este caso, o nil si sirve (D54)."
  [camino aplican]
  (some (fn [[id version]]
          (cond
            (not (contains? aplican id)) (str "La política " id " no aplica a este caso")
            (not= version (aplican id))  (str "El camino responde a " id " v" version
                                              " y rige la v" (aplican id))))
        (:camino/reglas camino)))

(defn- caminos-activos [{:keys [almacen empresa consulta]} op]
  (alm/buscar almacen empresa :caminos {:camino/objetivo op :camino/estado :activo} consulta))

(defn- cumplidas
  "Operaciones cuyo requisito para el objetivo ya está satisfecho."
  [estado actor {:keys [operacion entradas]}]
  (set (keep #(when (= :satisfecho (:estado %)) (:via %))
             (:requisitos (r/resolver estado actor operacion (entradas-de estado operacion entradas))))))

;; --- Deducción en profundidad (D97) -----------------------------------------------

(def ^:private profundidad-maxima
  "Niveles de pasos que se deducen bajo el objetivo; más abajo, el paso se
  propone con lo que le falta."
  6)

(defn- salidas-de [estado op] (keys (get-in estado [:catalogo :contratos op :op/salidas])))

(defn- agregar [estado acc p]
  (-> acc
      (update :pasos conj (dissoc p :resto))
      (update :producidas into (salidas-de estado (:operacion p)))))

(defn- con-regla [acc res politica]
  (cond-> acc politica (assoc-in [:reglas politica] (get-in res [:reglas politica]))))

(declare deducir)

(defn- por-requisito
  "Los pasos que deja un requisito de `p`, antes de él, y `p` como queda: un
  límite parcial le deja solo lo que espera la cobertura."
  [estado actor pool nivel res [acc p] req]
  (let [sub  (fn [acc q] (deducir estado actor pool acc q (inc nivel)))
        ents (:entradas p)
        {:keys [id via faltan cubre parcial politica faltante]} req]
    (cond
      (not= :pendiente (:estado req))
      [acc p]

      ;; Una entrada que falta y que otra operación entrega (D10), si ningún
      ;; paso anterior la entrega ya.
      (= "entrada" (namespace id))
      (let [k    (keyword (name id))
            prod (when-not (:problema req)
                   (est/productora estado k (:tipo (get (into {} (campos estado (:operacion p))) k))))]
        (if (and prod (not ((:producidas acc) k)))
          [(sub acc (paso prod ents)) p]
          [acc p]))

      ;; Un límite excedido que dice con qué se cubre: lo que cabe va antes, y
      ;; el paso espera la cobertura con lo que falta.
      cubre
      (let [cabe (:cabe parcial)
            acc  (if (and parcial (pos? (v/comparar cabe 0)))
                   (sub acc (assoc-in p [:entradas (:campo parcial)] cabe))
                   acc)
            acc  (-> (sub acc (paso (:operacion cubre) (merge ents (:entradas cubre))))
                     (con-regla res politica))]
        [acc (cond-> p parcial (-> (assoc-in [:entradas (:campo parcial)] faltante) (assoc :resto true)))])

      ;; Un "exige": tantos pasos como hechos faltan.
      via
      [(-> (reduce sub acc (repeat (or faltan 1) (paso via ents))) (con-regla res politica)) p]

      :else
      [acc p])))

(defn- deducir
  "Agrega a `acc` ({:pasos :producidas :reglas}) los pasos que necesita `p` y
  después `p` mismo, con lo que dice el resolvedor de él."
  [estado actor pool acc p nivel]
  (let [res (resolver-paso estado actor pool p)]
    (if (>= nivel profundidad-maxima)
      (agregar estado acc (assoc p :res res))
      (let [[acc p] (reduce #(por-requisito estado actor pool nivel res %1 %2) [acc p] (:requisitos res))]
        ;; Lo que espera una cobertura no se vuelve a deducir: la cobertura es lo
        ;; que lo hará posible.
        (agregar estado acc (assoc p :res (if (:resto p) (resolver-paso estado actor pool p) res)))))))

(defn- actuales
  "Los pasos que hoy faltan para el objetivo, cada uno con lo que dice el
  resolvedor (`:res`), y las políticas que los produjeron con su versión."
  [estado actor plan]
  (let [objetivo (assoc (paso (:operacion plan) {}) :objetivo true)]
    (if-let [guia (:guia plan)]
      (let [hechas (cumplidas estado actor plan)]
        {:pasos  (vec (for [op guia
                            :let [obj? (= op (:operacion plan))
                                  p    (if obj? objetivo (paso op {}))]
                            :when (or obj? (not (hechas op)))]
                        (assoc p :res (resolver-paso estado actor (:entradas plan) p))))
         :reglas {}})
      (select-keys (deducir estado actor (:entradas plan) {:pasos [] :producidas #{} :reglas {}} objetivo 0)
                   [:pasos :reglas]))))

;; --- Plan ------------------------------------------------------------------------

(defn- sin-resolucion [pasos] (mapv #(dissoc % :res) pasos))

(defn planear
  "Un plan para que `actor` alcance el objetivo. No ejecuta nada."
  [estado actor {:keys [entradas] :as objetivo}]
  (let [{:keys [operacion alternativas]} (alcanzar estado objetivo)]
    (if-not operacion
      {:objetivo objetivo :estado :sin-camino :pasos []
       :razon    (str "Ninguna operación del plan contratado cumple " (:cumplir objetivo))}
      (let [res        (r/resolver estado actor operacion (entradas-de estado operacion entradas))
            aplican    (politicas-que-aplican res)
            caminos    (caminos-activos estado operacion)
            rechazados (vec (for [c caminos :let [razon (rechazo c aplican)] :when razon]
                              {:camino (:camino/id c) :version (:camino/version c) :razon razon}))
            camino     (first (remove #(rechazo % aplican) caminos))
            plan       (cond-> {:objetivo     objetivo
                                :operacion    operacion
                                :alternativas alternativas
                                :origen       (if camino {:camino (:camino/id camino) :version (:camino/version camino)} :deducido)
                                :rechazados   rechazados
                                :entradas     entradas
                                :estado       :en-curso}
                         camino (assoc :guia (:camino/pasos camino)))
            {:keys [pasos reglas]} (actuales estado actor plan)]
        (assoc plan :pasos (sin-resolucion pasos) :reglas (merge aplican reglas))))))

;; --- Avance ----------------------------------------------------------------------

(defn- clave [p] [(:operacion p) (:entradas p)])

(defn- fusionar
  "Los pasos del plan con los que se deducen ahora. Un paso ejecutado queda como
  está. Uno pendiente toma lo nuevo, o queda satisfecho si ya no hace falta,
  porque otra persona lo cumplió (D76). Un paso nuevo entra antes del primero
  que le sigue en la deducción."
  [anteriores nuevos]
  (let [[asignados libres]
        (reduce (fn [[acc libres] p]
                  (let [j (when-not (= :ejecutado (:estado p))
                            (first (filter #(= (clave p) (clave (nuevos %))) libres)))]
                    (cond
                      (= :ejecutado (:estado p)) [(conj acc [p nil]) libres]
                      j                          [(conj acc [p j]) (vec (remove #{j} libres))]
                      :else [(conj acc [(-> p (assoc :estado :satisfecho) (dissoc :falta)) nil]) libres])))
                [[] (vec (range (count nuevos)))]
                anteriores)
        sueltos (set libres)
        [pasos puestos]
        (reduce (fn [[pasos puestos] [p j]]
                  (if (nil? j)
                    [(conj pasos p) puestos]
                    (let [antes (filter #(and (sueltos %) (not (puestos %))) (range j))]
                      [(-> pasos (into (map nuevos antes)) (conj (nuevos j))) (into puestos antes)])))
                [[] #{}]
                asignados)]
    (into pasos (map nuevos (remove puestos (sort sueltos))))))

(defn- guardar-borrador!
  "Guarda el camino de un plan deducido como borrador (D14), en la versión
  siguiente de su identificador."
  [{:keys [almacen empresa]} {:keys [operacion pasos reglas]}]
  (let [id      (keyword "camino" (name operacion))
        version (inc (reduce max 0 (map :camino/version (alm/historia almacen empresa :caminos id))))
        camino  {:camino/id         id
                 :camino/version    version
                 :camino/rige-desde (java.util.Date.)
                 :camino/objetivo   operacion
                 :camino/reglas     reglas
                 :camino/pasos      (vec (distinct (map :operacion pasos)))
                 :camino/estado     :borrador}]
    (alm/publicar! almacen empresa :caminos camino)
    camino))

(defn- terminar
  "Lo que no se ejecutó queda propuesto, con lo que le falta."
  [estado plan]
  (let [plan (update plan :pasos (fn [pasos]
                                   (mapv #(if-let [res (:res %)]
                                            (-> % (dissoc :res) (assoc :estado :propuesto :falta (lo-que-falta res)))
                                            %)
                                         pasos)))]
    (if (and (= :cumplido (:estado plan)) (= :deducido (:origen plan)) (< 1 (count (:pasos plan)))
             (not (:borrador plan)))
      (assoc plan :borrador (guardar-borrador! estado plan))
      plan)))

(def ^:private pasadas-maximas
  "Pasos que un avance ejecuta como mucho."
  50)

(defn- ejecutable
  "El primer paso que el resolvedor da por listo y no se intentó en este avance."
  [pasos intentados]
  (first (keep-indexed (fn [i p] (when (and (= :listo (get-in p [:res :estado])) (not (intentados (clave p)))) i))
                       pasos)))

(defn avanzar!
  "Ejecuta los pasos del plan que `actor` puede ejecutar y propone los demás.
  Después de cada paso, vuelve a deducir lo que falta (D97). `entradas` agrega
  datos que el usuario entrega ahora. Devuelve el plan con el estado de cada
  paso; si el objetivo se cumplió y el plan fue deducido, con el camino guardado
  como borrador en `:borrador`."
  ([estado actor plan] (avanzar! estado actor plan {}))
  ([estado actor plan entradas]
   (if (not= :en-curso (:estado plan))
     plan
     (loop [plan (update plan :entradas merge entradas), intentados #{}, n 0]
       (let [{nuevos :pasos reglas :reglas} (actuales estado actor plan)
             plan (-> plan (update :pasos fusionar nuevos) (update :reglas merge reglas))
             i    (ejecutable (:pasos plan) intentados)]
         (if (or (nil? i) (>= n pasadas-maximas))
           (terminar estado plan)
           (let [p     (get-in plan [:pasos i])
                 hecho (ej/ejecutar! estado actor (:operacion p) (efectivas estado (:entradas plan) p) {:confirmado true})]
             (if (= :ejecutado (:estado hecho))
               (let [plan (-> plan
                              (assoc-in [:pasos i] (-> p (dissoc :res) (assoc :estado :ejecutado :salidas (:salidas hecho))))
                              (update :entradas merge (:salidas hecho)))]
                 (if (:objetivo p)
                   (terminar estado (assoc plan :estado :cumplido))
                   (recur plan intentados (inc n))))
               (recur (assoc-in plan [:pasos i :res] hecho) (conj intentados (clave p)) (inc n))))))))))
