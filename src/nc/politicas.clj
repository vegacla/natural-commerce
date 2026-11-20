(ns nc.politicas
  "Publicar una política buscando antes sus choques con las vigentes (D35,
  notación 6.3). Un conflicto se informa al publicar, no al ejecutar: la política
  no se publica hasta que el autor lo resuelva con `:politica/prioridad` o
  ajustando las condiciones. Las legales siempre ganan, y la empresa no las
  edita (D5)."
  (:require [clojure.set :as set]
            [nc.almacen :as alm]
            [nc.catalogo :as cat]
            [nc.estado :as est]))

(defn- ops-de [p]
  (let [a (:politica/aplica-a p)]
    (cond (keyword? a) #{a} (set? a) a :else #{})))

(defn- se-cruzan? [p q] (boolean (seq (set/intersection (ops-de p) (ops-de q)))))

(defn- campo
  "El campo que fija una política: el de la entrada, o el de cada línea de una
  lista (D114)."
  [p] ((juxt :cada :campo) (:politica/cuerpo p)))

(defn- legal? [p] (= :legal (:politica/origen p)))

(defn- fija-contra-fija
  "Dos \"fija\" del mismo campo en una misma operación. La legal gana; una de la
  empresa no puede contradecirla. Entre dos de la empresa, distinta prioridad lo
  resuelve."
  [p q]
  (when (and (= :fija (:politica/forma p) (:politica/forma q)) (se-cruzan? p q) (= (campo p) (campo q)))
    (cond
      (legal? p) nil
      (legal? q) {:conflicto :contradice-legal :con (:politica/id q)
                  :razon (str "La política legal " (:politica/id q) " ya fija " (pr-str (campo p)))}
      ;; Sin prioridad, una política cuenta como 0.
      (not= (or (:politica/prioridad p) 0) (or (:politica/prioridad q) 0)) nil
      :else {:conflicto :fija-dos-veces :con (:politica/id q)
             :razon (str (:politica/id q) " ya fija " (pr-str (campo p)) "; distinga la prioridad o ajuste las condiciones")})))

(defn- exige-contra-prohibe
  "Un \"exige\" de un hecho cuya única operación está prohibida siempre (sin
  condición): el requisito no se puede cumplir nunca."
  [estado p q]
  (let [[exige prohibe] (if (= :exige (:politica/forma p)) [p q] [q p])]
    (when (and (= :exige (:politica/forma exige)) (= :prohibe (:politica/forma prohibe))
               (not (contains? prohibe :politica/cuando)))
      (some (fn [{:keys [hecho]}]
              (let [via (est/registradora (:catalogo estado) hecho)]
                (when (contains? (ops-de prohibe) via)
                  {:conflicto :exige-lo-prohibido :con (:politica/id q)
                   :razon (str (:politica/id exige) " exige " hecho ", que solo registra " via
                               ", y " (:politica/id prohibe) " lo prohíbe siempre")})))
            (:politica/cuerpo exige)))))

(defn conflictos
  "Los choques de una política con las vigentes de la empresa; vacío si no hay."
  [estado p]
  (vec (for [q (est/vigentes estado)
             :when (not= (:politica/id q) (:politica/id p))
             c [(fija-contra-fija p q) (exige-contra-prohibe estado p q)]
             :when c]
         c)))

(declare publicar!)

(def paquete-por-defecto
  "Las políticas por defecto de la plataforma (D107)."
  "defecto/politicas.edn")

(defn por-defecto [] (:politicas (cat/leer paquete-por-defecto)))

(defn intacta?
  "Si una política vigente es una por defecto que la empresa no cambió: la misma
  versión que trae el paquete. La plataforma la maneja junto con el plan (D107)."
  [p]
  (boolean (some #(= ((juxt :politica/id :politica/version) %) ((juxt :politica/id :politica/version) p))
                 (por-defecto))))

(defn instalar-por-defecto!
  "Publica las políticas por defecto de la plataforma (D46, D107) que la empresa
  puede usar con su plan y no tiene vigentes. Son de la empresa: después las
  cambia o las retira. Una que la empresa cambió (con una versión posterior en
  su historia) no se vuelve a instalar. Devuelve lo que pasó con cada una."
  [{:keys [almacen empresa] :as estado}]
  (vec (for [p (por-defecto)
             :let [id (:politica/id p)
                   historia (alm/historia almacen empresa :politicas id)]
             :when (every? #(est/habilitada? estado %) (ops-de p))
             :when (nil? (alm/vigente almacen empresa :politicas id))
             :when (every? #(<= (:politica/version %) (:politica/version p)) historia)
             ;; Si ya rigió y se retiró, vuelve desde ahora: su historia no se reescribe.
             :let [p (cond-> p (seq historia) (assoc :politica/rige-desde (est/ahora estado)))]]
         (assoc (publicar! estado p {:autor :plataforma}) :politica id))))

(defn instalar-legal!
  "Publica un paquete legal (las políticas de un régimen tributario) en una
  empresa, como plataforma (D5). Devuelve lo que pasó con cada política."
  [estado politicas]
  (mapv (fn [p] (assoc (publicar! estado p {:autor :plataforma}) :politica (:politica/id p)))
        politicas))

(defn publicar!
  "Publica una política si no choca con las vigentes. `:autor` es :empresa (por
  omisión) o :plataforma: una empresa no publica políticas legales (D5)."
  ([estado p] (publicar! estado p {}))
  ([{:keys [almacen empresa] :as estado} p {:keys [autor] :or {autor :empresa}}]
   (let [anterior (alm/vigente almacen empresa :politicas (:politica/id p))]
     (if (and (= :empresa autor) (or (legal? p) (legal? anterior)))
       {:estado :rechazada :razon "Una política legal no la edita la empresa (D5)"}
       (let [cs (conflictos estado p)]
         (if (seq cs)
           {:estado :conflicto :conflictos cs}
           (do (alm/publicar! almacen empresa :politicas p)
               {:estado :publicada})))))))
