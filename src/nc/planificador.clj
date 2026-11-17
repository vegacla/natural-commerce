(ns nc.planificador
  "Planes para alcanzar un objetivo (D33, D51). Un plan es una lista de pasos,
  cada uno una operación. Sale de un camino guardado si sus políticas aplican al
  caso (D54); si no, se deduce de los requisitos que da el resolvedor.

  Avanzar un plan ejecuta los pasos que el usuario puede ejecutar y deja
  propuestos los demás, con lo que les falta y quién puede hacerlos. Pedir que
  avance es la confirmación del usuario (D14). Al cumplirse el objetivo de un plan
  deducido, el camino queda guardado como borrador por activar."
  (:require [nc.almacen :as alm]
            [nc.ejecutor :as ej]
            [nc.esquema :as esq]
            [nc.estado :as est]
            [nc.resolvedor :as r]))

(defn- entradas-de [estado op pool]
  (select-keys pool (map first (esq/campos-ordenados (get-in estado [:catalogo :contratos op :op/entradas])))))

(defn- resolver [estado actor op pool]
  (r/resolver estado actor op (entradas-de estado op pool)))

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

(defn- pasos-deducidos
  "Un paso por cada hecho que falta según las políticas, en su orden, y al final
  la operación objetivo."
  [res op]
  (conj (vec (for [{:keys [estado via faltan]} (:requisitos res)
                   :when (and via (= :pendiente estado))
                   _ (range (or faltan 1))]
               {:operacion via}))
        {:operacion op}))

(defn planear
  "Un plan para que `actor` alcance la operación del objetivo. No ejecuta nada."
  [estado actor {:keys [operacion entradas] :as objetivo}]
  (let [res        (resolver estado actor operacion entradas)
        aplican    (politicas-que-aplican res)
        caminos    (caminos-activos estado operacion)
        rechazados (vec (for [c caminos :let [razon (rechazo c aplican)] :when razon]
                          {:camino (:camino/id c) :version (:camino/version c) :razon razon}))
        camino     (first (remove #(rechazo % aplican) caminos))]
    {:objetivo   objetivo
     :origen     (if camino {:camino (:camino/id camino) :version (:camino/version camino)} :deducido)
     :rechazados rechazados
     :reglas     aplican
     :pasos      (if camino
                   (mapv (fn [op] {:operacion op}) (:camino/pasos camino))
                   (pasos-deducidos res operacion))
     :entradas   entradas
     :estado     :en-curso}))

;; --- Avance ----------------------------------------------------------------------

(defn- cumplidas
  "Operaciones cuyo requisito para el objetivo ya está satisfecho."
  [estado actor {:keys [objetivo entradas]}]
  (set (keep #(when (= :satisfecho (:estado %)) (:via %))
             (:requisitos (resolver estado actor (:operacion objetivo) entradas)))))

(defn- lo-que-falta [res]
  (vec (remove #(#{:satisfecho :opcional} (:estado %)) (:requisitos res))))

(defn- avanzar-paso [estado actor plan i]
  (let [{:keys [operacion] :as paso} (get-in plan [:pasos i])
        objetivo? (= operacion (get-in plan [:objetivo :operacion]))]
    (cond
      (= :ejecutado (:estado paso))
      plan

      (and (not objetivo?) ((cumplidas estado actor plan) operacion))
      (assoc-in plan [:pasos i] (assoc paso :estado :satisfecho))

      :else
      (let [res (resolver estado actor operacion (:entradas plan))]
        (if (= :listo (:estado res))
          (let [hecho (ej/ejecutar! estado actor operacion (entradas-de estado operacion (:entradas plan))
                                    {:confirmado true})]
            (cond-> (-> plan
                        (assoc-in [:pasos i] (assoc paso :estado :ejecutado :salidas (:salidas hecho)))
                        (update :entradas merge (:salidas hecho)))
              objetivo? (assoc :estado :cumplido)))
          (assoc-in plan [:pasos i] (assoc paso :estado :propuesto :falta (lo-que-falta res))))))))

(defn- guardar-borrador!
  "Guarda el camino de un plan deducido como borrador (D14), en la versión
  siguiente de su identificador."
  [{:keys [almacen empresa]} {:keys [objetivo pasos reglas]}]
  (let [op      (:operacion objetivo)
        id      (keyword "camino" (name op))
        version (inc (reduce max 0 (map :camino/version (alm/historia almacen empresa :caminos id))))
        camino  {:camino/id         id
                 :camino/version    version
                 :camino/rige-desde (java.util.Date.)
                 :camino/objetivo   op
                 :camino/reglas     reglas
                 :camino/pasos      (vec (distinct (map :operacion pasos)))
                 :camino/estado     :borrador}]
    (alm/publicar! almacen empresa :caminos camino)
    camino))

(defn avanzar!
  "Ejecuta los pasos del plan que `actor` puede ejecutar y propone los demás.
  `entradas` agrega datos que el usuario entrega ahora. Devuelve el plan con el
  estado de cada paso; si el objetivo se cumplió y el plan fue deducido, con el
  camino guardado como borrador en `:borrador`."
  ([estado actor plan] (avanzar! estado actor plan {}))
  ([estado actor plan entradas]
   (let [plan (update plan :entradas merge entradas)
         plan (reduce #(avanzar-paso estado actor %1 %2) plan (range (count (:pasos plan))))]
     (if (and (= :cumplido (:estado plan)) (= :deducido (:origen plan)) (< 1 (count (:pasos plan)))
              (not (:borrador plan)))
       (assoc plan :borrador (guardar-borrador! estado plan))
       plan))))
