(ns nc.bitacora
  "La bitácora de MARCO (D82): cada evaluación de una operación queda guardada,
  también las rechazadas. Y la verificación de invariantes que demuestra, con lo
  guardado, que nada se escribió sin que MARCO lo validara."
  (:require [nc.almacen :as alm]))

(defn decisiones
  "Las decisiones de una empresa que coinciden con `filtro` (campos de la
  decisión), de la más antigua a la más nueva."
  ([alm empresa] (decisiones alm empresa {}))
  ([alm empresa filtro]
   (sort-by :decision/en (alm/buscar alm empresa :decisiones filtro))))

(def ^:private sin-validar #{:pendiente :indeterminado :prohibido})

(defn- violacion [tipo id mensaje] {:violacion tipo :id id :mensaje mensaje})

(defn verificar
  "Las violaciones de los invariantes de la bitácora en una empresa; vacío si se
  cumplen todos:

  1. Todo hecho, compromiso y saldo lleva la decisión que lo escribió, y esa
     decisión existe y es una ejecución. Una entidad también, salvo la carga
     inicial de datos maestros.
  2. Toda decisión ejecutada declara sus hechos, y todos existen y la nombran.
  3. Ninguna decisión ejecutada tenía requisitos sin cumplir ni le faltaba
     confirmación."
  [alm empresa]
  (let [ds        (alm/buscar alm empresa :decisiones {})
        por-id    (into {} (map (juxt :decision/id identity)) ds)
        hechos    (alm/buscar alm empresa :hechos {})
        hecho-ids (set (map :hecho/id hechos))
        escrito   (fn [tabla id-de doc obligatoria?]
                    (let [d (get por-id (:nc/decision doc))]
                      (cond
                        (and (nil? (:nc/decision doc)) obligatoria?)
                        [(violacion :sin-decision (id-de doc) (str tabla " sin decisión que lo escribiera"))]

                        (and (:nc/decision doc) (nil? d))
                        [(violacion :decision-inexistente (id-de doc) (str tabla " nombra una decisión que no existe"))]

                        (and d (not= :ejecutado (:decision/resultado d)))
                        [(violacion :decision-no-ejecutada (id-de doc) (str tabla " escrito por una decisión " (:decision/resultado d)))])))]
    (vec
     (concat
      (mapcat #(escrito "hecho" :hecho/id % true) hechos)
      (for [h hechos
            :let [d (get por-id (:nc/decision h))]
            :when (and d (not (contains? (set (:decision/hechos d)) (:hecho/id h))))]
        (violacion :hecho-no-declarado (:hecho/id h) "la decisión no declara este hecho"))
      (mapcat #(escrito "compromiso" :compromiso/id % true) (alm/buscar alm empresa :compromisos {}))
      (mapcat #(escrito "saldo" (juxt :saldo/concepto :saldo/clave) % true) (alm/buscar alm empresa :saldos {}))
      (mapcat #(escrito "entidad" :entidad/id % false) (alm/buscar alm empresa :entidades {}))
      (for [d ds :when (= :ejecutado (:decision/resultado d))
            h (:decision/hechos d) :when (not (hecho-ids h))]
        (violacion :hecho-perdido h (str "la decisión " (:decision/id d) " declara un hecho que no existe")))
      (for [d ds :when (= :ejecutado (:decision/resultado d))
            :when (or (some #(sin-validar (:estado %)) (:decision/requisitos d))
                      (= :falta (:decision/confirmacion d)))]
        (violacion :ejecutada-sin-validar (:decision/id d) "una ejecución con requisitos sin cumplir o sin confirmación"))))))
