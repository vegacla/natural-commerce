(ns nc.plan
  "El plan contratado de una empresa (visión 3.2, D2): qué capacidades puede
  usar. Lo publica la plataforma, versionado y con fecha de vigencia, como una
  política. Sin plan publicado, la empresa tiene todas las capacidades (D83).

  Dejar una capacidad de la que dependen políticas de la empresa no rompe nada
  en silencio: se informan las políticas afectadas y la empresa elige (D36)."
  (:require [nc.almacen :as alm]
            [nc.estado :as est]
            [nc.politicas :as pol]))

(def opciones
  "Lo que puede elegir una empresa cuyas políticas dependen de una capacidad
  que deja (D36)."
  #{:retirar-politicas :conservar-capacidades})

(defn- capacidad [estado op] (get-in estado [:catalogo :contratos op :op/capacidad]))

(defn- dependencias
  "Las capacidades de las que depende una política: la de las operaciones a
  que aplica, la de su objetivo si inicia algo, y la de las operaciones que
  registran los hechos que exige."
  [estado {:politica/keys [aplica-a forma cuerpo]}]
  (let [ops (cond (keyword? aplica-a) [aplica-a] (set? aplica-a) aplica-a :else [])]
    (set (concat (map #(capacidad estado %) ops)
                 (when (= :inicia forma) [(capacidad estado (:objetivo cuerpo))])
                 (when (= :exige forma)
                   (for [{:keys [hecho]} cuerpo]
                     (capacidad estado (est/registradora (:catalogo estado) hecho))))))))

(defn- afectadas-todas [estado capacidades]
  (for [p     (est/vigentes estado)
        :let  [faltan (remove (set capacidades) (dependencias estado p))]
        :when (seq faltan)]
    {:politica (:politica/id p) :faltan (set faltan) :defecto (pol/intacta? p)}))

(defn afectadas
  "Las políticas vigentes de la empresa que dependen de una capacidad que el plan
  nuevo no incluye, con las capacidades que les faltarían. Las por defecto que la
  empresa no cambió no cuentan: las maneja la plataforma (D107)."
  [estado capacidades]
  (vec (for [a (afectadas-todas estado capacidades) :when (not (:defecto a))]
         (dissoc a :defecto))))

(defn- ajustar-por-defecto!
  "Con el plan nuevo publicado: retira las políticas por defecto intactas que
  dependen de una capacidad que el plan deja, e instala las que ahora puede usar
  (D107). La empresa no elige nada sobre lo que no escribió."
  [{:keys [almacen empresa] :as estado} capacidades rige-desde]
  (let [retiradas (vec (for [a (afectadas-todas estado capacidades) :when (:defecto a)]
                         (do (alm/retirar! almacen empresa :politicas (:politica a) {:desde rige-desde})
                             (:politica a))))]
    (pol/instalar-por-defecto! estado)
    retiradas))

(defn cambiar!
  "Publica un plan nuevo desde `rige-desde`. Si hay políticas que dependen de
  una capacidad que se deja, no publica nada hasta que la empresa elija una de
  `opciones`: retirarlas desde la misma fecha, o conservar esas capacidades."
  [{:keys [almacen empresa] :as estado} {:plan/keys [capacidades rige-desde] :as plan} {:keys [eleccion]}]
  (let [af      (afectadas estado capacidades)
        version (inc (or (:plan/version (est/plan estado)) 0))
        publicar (fn [caps]
                   (alm/publicar! almacen empresa :planes
                                  (assoc plan :plan/id :plan :plan/version version
                                         :plan/capacidades (set caps)))
                   (ajustar-por-defecto! estado caps rige-desde))]
    (cond
      (empty? af)
      {:estado :publicado :afectadas [] :defecto-retiradas (publicar capacidades)}

      (nil? eleccion)
      {:estado :requiere-eleccion :afectadas af :opciones opciones}

      (= :retirar-politicas eleccion)
      (do (doseq [{:keys [politica]} af]
            (alm/retirar! almacen empresa :politicas politica {:desde rige-desde}))
          {:estado :publicado :afectadas af :retiradas (mapv :politica af)
           :defecto-retiradas (publicar capacidades)})

      (= :conservar-capacidades eleccion)
      (let [caps (into (set capacidades) (mapcat :faltan af))]
        {:estado :publicado :afectadas af :conservadas (set (mapcat :faltan af))
         :defecto-retiradas (publicar caps)})

      :else
      (throw (ex-info (str "Elección desconocida: " eleccion) {:nc/error :eleccion-desconocida :opciones opciones})))))
