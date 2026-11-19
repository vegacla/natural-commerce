(ns nc.marco-test
  "Etapas 3 y 4: resolvedor y ejecutor, vigencia e historia. Escenarios E1 a E5
  de prototipo-minimo.md.
  Cada prueba usa su propia empresa sobre un mismo nodo XTDB."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.bitacora :as bit]
            [nc.catalogo :as cat]
            [nc.ejecutor :as ej]
            [nc.estado :as est]
            [nc.resolvedor :as r])
  (:import (java.util Date)))

(def ^:dynamic *alm* nil)

(def empresas
  "Las empresas creadas por `empresa!` en esta corrida: al terminar, cada una
  debe cumplir los invariantes de la bitácora (D82)."
  (atom #{}))

(defn verificar-todas!
  "Verifica los invariantes de la bitácora en cada empresa creada."
  []
  (doseq [e @empresas]
    (is (= [] (bit/verificar *alm* e)) (str "invariantes de la bitácora en " e)))
  (reset! empresas #{}))

(defn con-almacen
  "Fixture: un almacén en memoria para las pruebas, y los invariantes de la
  bitácora verificados al final."
  [pruebas]
  (let [a (alm/abrir)]
    (try (binding [*alm* a] (pruebas) (verificar-todas!))
         (finally (alm/cerrar a)))))

(use-fixtures :once con-almacen)

(def empresa-b (cat/leer "empresas/empresa-b.edn"))

(defn dias-desde-hoy [n] (Date. (+ (System/currentTimeMillis) (* n 24 60 60 1000))))

(def entidades
  [{:entidad/id :P-100 :entidad/tipo :producto :producto/codigo "P-100"
    :producto/categoria :insumos :producto/unidad :un}
   {:entidad/id :proveedor/acme :entidad/tipo :proveedor :proveedor/nombre "Acme"}
   {:entidad/id :proveedor/beta :entidad/tipo :proveedor :proveedor/nombre "Beta"}
   {:entidad/id :bodega/central :entidad/tipo :bodega :bodega/nombre "Central"}
   {:entidad/id :persona/ana :entidad/tipo :persona :persona/nombre "Ana"}
   {:entidad/id :persona/luis :entidad/tipo :persona :persona/nombre "Luis"
    :persona/roles #{:jefe-compras}}])

(defn empresa!
  "Una empresa nueva con las entidades del caso y la configuración dada. Las
  políticas se publican vigentes desde hace un mes: las pruebas usan el reloj real."
  [config]
  (let [e (keyword "empresa" (str (random-uuid)))]
    (swap! empresas conj e)
    (alm/cargar-entidades! *alm* e entidades)
    (doseq [p (:politicas config)]
      (alm/publicar! *alm* e :politicas (assoc p :politica/rige-desde (dias-desde-hoy -30))))
    (est/abrir *alm* (cat/de-empresa config) e)))

(defn ejecutar! [empresa actor op entradas]
  (ej/ejecutar! empresa actor op entradas {:confirmado true}))

(defn requisito [res id] (first (filter #(= id (:id %)) (:requisitos res))))

(defn compromiso [empresa id] (est/leer empresa id))

(def orden-20
  {:proveedor       :proveedor/acme
   :producto        :P-100
   :cantidad        [:cantidad 20 :un]
   :precio-unitario [:monto 30000 :CLP]})

;; --- E1 --------------------------------------------------------------------------

(deftest e1-compra-sin-pasos-previos
  (let [a (empresa! {})]
    (testing "sin políticas, la orden está lista y la solicitud y la oferta son opcionales (D58)"
      (let [res (r/resolver a :persona/ana :compras/emitir-orden orden-20)]
        (is (= :listo (:estado res)))
        (is (= #{{:id :entrada/solicitud :estado :opcional}
                 {:id :entrada/oferta :estado :opcional}}
               (set (:requisitos res))))))
    (testing "sin confirmación no se ejecuta (D53)"
      (is (= :por-confirmar
             (:estado (ej/ejecutar! a :persona/ana :compras/emitir-orden orden-20 {}))))
      (is (empty? (alm/buscar *alm* (:empresa a) :hechos {:hecho/tipo :compras/orden}))))
    (let [{:keys [estado salidas caso]} (ejecutar! a :persona/ana :compras/emitir-orden orden-20)
          orden        (:orden salidas)
          de-la-orden  (fn [tipo] (first (alm/buscar *alm* (:empresa a) :compromisos
                                                     {:compromiso/tipo tipo :compromiso/origen orden})))]
      (testing "la orden se emite y abre la recepción pendiente y el pago latente"
        (is (= :ejecutado estado))
        (is (= {:cantidad [:cantidad 20 :un]}
               (:compromiso/saldo (de-la-orden :compras/recepcion-pendiente))))
        (is (= :latente (:compromiso/estado (de-la-orden :compras/pago-contra-factura))))
        (is (= {:monto [:monto 600000 :CLP]}
               (:compromiso/saldo (de-la-orden :compras/pago-contra-factura)))))
      (testing "el caso es la recepción pendiente, el primer compromiso que abrió (D68)"
        (is (= caso (:compromiso/id (de-la-orden :compras/recepcion-pendiente))))
        (is (= caso (:hecho/caso (est/leer a orden)))))
      (testing "llegan 12: queda un compromiso de recepción por 8"
        (is (= :ejecutado
               (:estado (ejecutar! a :persona/ana :compras/registrar-recepcion
                                   {:orden orden :producto :P-100 :bodega :bodega/central
                                    :cantidad [:cantidad 12 :un]}))))
        (is (= {:cantidad [:cantidad 8 :un]}
               (:compromiso/saldo (de-la-orden :compras/recepcion-pendiente))))
        (is (= :activo (:compromiso/estado (de-la-orden :compras/recepcion-pendiente)))))
      (testing "la recepción movió la existencia de la bodega, valorizada al precio de la orden (D57)"
        (is (= {:cantidad [:cantidad 12 :un] :costo [:monto 360000 :CLP]}
               (:saldo/valor (alm/leer *alm* (:empresa a) :saldos
                                       [:inventario/existencia {:producto :P-100 :bodega :bodega/central}])))))
      (testing "consultar el estado de la adquisición"
        (let [{:keys [estado salidas]} (ej/ejecutar! a :persona/ana :compras/consultar-estado {:orden orden} {})]
          (is (= :consultado estado))
          (is (= [:cantidad 8 :un] (:por-recibir salidas)))
          (is (= #{:compras/orden :compras/recepcion} (set (map :hecho/tipo (:hechos salidas)))))
          (is (= 2 (count (:compromisos salidas))))))
      (testing "llegan los 8 restantes: la recepción pendiente se extingue por cumplimiento (D69)"
        (ejecutar! a :persona/ana :compras/registrar-recepcion
                   {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 8 :un]})
        (let [c (de-la-orden :compras/recepcion-pendiente)]
          (is (= :extinguido (:compromiso/estado c)))
          (is (= :cumplimiento (get-in c [:compromiso/extincion :causa]))))))))

(deftest el-contrato-valida-las-entradas
  (let [a (empresa! {})]
    (testing "una entrada que falta o no calza queda pendiente, con lo que pide (P5, D65)"
      (let [res (r/resolver a :persona/ana :compras/emitir-orden
                            (-> orden-20 (dissoc :proveedor) (assoc :precio-unitario 30000)))]
        (is (= :bloqueado (:estado res)))
        (is (= {:id :entrada/proveedor :estado :pendiente :pide [:entrada/proveedor]}
               (requisito res :entrada/proveedor)))
        (is (= :pendiente (:estado (requisito res :entrada/precio-unitario))))
        (is (string? (:problema (requisito res :entrada/precio-unitario))))))
    (testing "una referencia debe existir en la empresa con el tipo declarado"
      (let [res (r/resolver a :persona/ana :compras/emitir-orden (assoc orden-20 :proveedor :proveedor/no-existe))]
        (is (= "No existe :proveedor/no-existe como :proveedor"
               (:problema (requisito res :entrada/proveedor)))))
      (is (= :bloqueado
             (:estado (ejecutar! a :persona/ana :compras/emitir-orden (assoc orden-20 :producto :proveedor/acme))))))))

;; --- E2 --------------------------------------------------------------------------

(deftest e2-compra-sobre-el-umbral
  (let [b       (empresa! empresa-b)
        orden   (fn [& {:as mas}] (merge orden-20 mas))
        resolver #(r/resolver b :persona/ana :compras/emitir-orden %)]
    (testing "sin precio no se sabe si supera el umbral: indeterminado (P4)"
      (is (= {:id :empresa-b/compra-formal :estado :indeterminado :pide [:entrada/precio-unitario]}
             (requisito (resolver (dissoc orden-20 :precio-unitario)) :empresa-b/compra-formal))))
    (testing "bajo el umbral no se exige nada"
      (is (= :listo (:estado (resolver (orden :cantidad [:cantidad 10 :un]))))))
    (testing "sobre el umbral faltan solicitud, dos ofertas y aprobación"
      (let [res (resolver (orden))]
        (is (= :bloqueado (:estado res)))
        (is (= {:id :compras/solicitud :politica :empresa-b/compra-formal :estado :pendiente :faltan 1
                :via :compras/registrar-solicitud :quien :cualquiera :entrega :entrada/solicitud}
               (requisito res :compras/solicitud)))
        (is (= 2 (:faltan (requisito res :compras/oferta))))
        (is (= {:via :compras/aprobar :quien [:rol :jefe-compras] :no-puede :persona/ana}
               (select-keys (requisito res :compras/aprobacion) [:via :quien :no-puede])))
        (is (= {:empresa-b/compra-formal 3} (:reglas res)))
        (testing "la solicitud y la oferta no son opcionales: las exige la política (D58, D71)"
          (is (nil? (requisito res :entrada/solicitud))))))
    (let [solicitud (-> (ejecutar! b :persona/ana :compras/registrar-solicitud
                                   {:producto :P-100 :cantidad [:cantidad 20 :un]})
                        :salidas :solicitud)]
      (testing "la solicitud abre el caso y su raíz anota la versión de la política (notación 6.4)"
        (is (= {:empresa-b/compra-formal 3} (:compromiso/reglas (compromiso b solicitud))))
        (let [res (resolver (orden :solicitud solicitud))]
          (is (= :satisfecho (:estado (requisito res :compras/solicitud))))
          (is (= :pendiente (:estado (requisito res :compras/oferta))))))
      (let [oferta (fn [proveedor precio]
                     (-> (ejecutar! b :persona/ana :compras/registrar-oferta
                                    {:solicitud solicitud :proveedor proveedor :producto :P-100
                                     :cantidad [:cantidad 20 :un] :precio-unitario precio
                                     :vence (dias-desde-hoy 15)})
                         :salidas :oferta))
            oferta-1 (oferta :proveedor/acme [:monto 31000 :CLP])
            oferta-2 (oferta :proveedor/beta [:monto 30000 :CLP])
            con-todo (orden :proveedor :proveedor/beta :solicitud solicitud :oferta oferta-2)]
        (testing "con dos ofertas falta solo la aprobación"
          (let [res (resolver con-todo)]
            (is (= :satisfecho (:estado (requisito res :compras/oferta))))
            (is (= :pendiente (:estado (requisito res :compras/aprobacion))))
            (is (= :latente (:compromiso/estado (compromiso b oferta-1))))))
        (testing "Luis, jefe de compras, aprueba la oferta 2"
          (is (= :ejecutado (:estado (ejecutar! b :persona/luis :compras/aprobar
                                                {:solicitud solicitud :oferta oferta-2})))))
        (testing "una aprobación de otra oferta no sirve para esta orden"
          (is (= :pendiente (:estado (requisito (resolver (assoc con-todo :oferta oferta-1))
                                                :compras/aprobacion)))))
        (testing "con todo satisfecho, pide confirmación y se emite"
          (let [res (resolver con-todo)]
            (is (= :listo (:estado res)))
            (is (= :requerida (:confirmacion res))))
          (let [{:keys [estado salidas]} (ejecutar! b :persona/ana :compras/emitir-orden con-todo)]
            (is (= :ejecutado estado))
            (is (= {:empresa-b/compra-formal 3} (:hecho/reglas (est/leer b (:orden salidas)))))))
        (testing "la necesidad y la oferta 2 se extinguen por cumplimiento; la oferta 1 sigue latente"
          (is (= [:extinguido :cumplimiento]
                 ((juxt :compromiso/estado (comp :causa :compromiso/extincion)) (compromiso b solicitud))))
          (is (= :extinguido (:compromiso/estado (compromiso b oferta-2))))
          (is (= :latente (:compromiso/estado (compromiso b oferta-1)))))
        (testing "todo quedó en el mismo caso"
          (is (= #{:compras/solicitud :compras/oferta :compras/aprobacion :compras/orden}
                 (->> (ej/ejecutar! b :persona/ana :compras/consultar-estado {:solicitud solicitud} {})
                      :salidas :hechos (map :hecho/tipo) set))))))))

;; --- E3 --------------------------------------------------------------------------

(deftest e3-quien-solicita-no-aprueba
  (let [b         (empresa! empresa-b)
        solicitud (-> (ejecutar! b :persona/luis :compras/registrar-solicitud
                                 {:producto :P-100 :cantidad [:cantidad 20 :un]})
                      :salidas :solicitud)
        aprobar   #(ejecutar! b % :compras/aprobar {:solicitud solicitud})]
    (testing "Luis solicitó: aunque es jefe de compras, no puede aprobar"
      (let [res (aprobar :persona/luis)]
        (is (= :prohibido (:estado res)))
        (is (= [{:id :empresa-b/segregacion :estado :prohibido
                 :razon "Quien solicita una compra no puede aprobarla"}]
               (filter #(= :prohibido (:estado %)) (:requisitos res))))))
    (testing "Ana no solicitó, pero no tiene permiso para aprobar"
      (let [res (aprobar :persona/ana)]
        (is (= :prohibido (:estado res)))
        (is (= {:id :permiso :estado :prohibido :quien [:rol :jefe-compras]
                :razon ":persona/ana no tiene permiso para :compras/aprobar"}
               (requisito res :permiso)))))
    (testing "nada quedó registrado"
      (is (empty? (alm/buscar *alm* (:empresa b) :hechos {:hecho/tipo :compras/aprobacion}))))))

;; --- E4 --------------------------------------------------------------------------

(def compra-formal-v3
  (first (filter #(= :empresa-b/compra-formal (:politica/id %)) (:politicas empresa-b))))

(defn- compra-formal-v4
  "La versión siguiente: sin ofertas, y la aprobación ya no se ata a una oferta."
  [rige-desde]
  (assoc compra-formal-v3
         :politica/version    4
         :politica/rige-desde rige-desde
         :politica/cuerpo     [{:hecho :compras/solicitud} {:hecho :compras/aprobacion}]))

(defn- con-aplica-con [config opcion]
  (update config :politicas
          (partial mapv #(cond-> % (= :empresa-b/compra-formal (:politica/id %))
                           (assoc :politica/aplica-con opcion)))))

(deftest e4-version-siguiente-de-una-politica
  (doseq [[opcion el-abierto-cambia?] [[:inicio-del-caso false] [:fecha-del-paso true]]]
    (testing (str "compra-formal con :aplica-con " opcion)
      (let [b         (empresa! (con-aplica-con empresa-b opcion))
            abierto   (-> (ejecutar! b :persona/ana :compras/registrar-solicitud
                                     {:producto :P-100 :cantidad [:cantidad 20 :un]})
                          :salidas :solicitud)
            _         (alm/publicar! *alm* (:empresa b) :politicas
                                     (assoc (compra-formal-v4 (dias-desde-hoy 10)) :politica/aplica-con opcion))
            en-11     (est/al b (dias-desde-hoy 11))
            pide      (fn [estado entradas]
                        (let [res (r/resolver estado :persona/ana :compras/emitir-orden entradas)]
                          [(set (keep #(when (:politica %) (:id %)) (:requisitos res))) (:reglas res)]))
            con-ofertas [#{:compras/solicitud :compras/oferta :compras/aprobacion} {:empresa-b/compra-formal 3}]
            sin-ofertas [#{:compras/solicitud :compras/aprobacion} {:empresa-b/compra-formal 4}]]
        (testing "antes de su fecha de vigencia, un caso nuevo sigue pidiendo ofertas"
          (is (= con-ofertas (pide b orden-20))))
        (testing "desde su fecha, un caso nuevo no pide ofertas"
          (is (= sin-ofertas (pide en-11 orden-20))))
        (testing "el caso abierto hace lo que declara la política (D9, D72)"
          (is (= (if el-abierto-cambia? sin-ofertas con-ofertas)
                 (pide en-11 (assoc orden-20 :solicitud abierto)))))
        (testing "en otra fecha solo se consulta (D18)"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"solo se consulta"
                                (ejecutar! en-11 :persona/ana :compras/registrar-solicitud
                                           {:producto :P-100 :cantidad [:cantidad 5 :un]}))))))))

;; --- E5 --------------------------------------------------------------------------

(defn compra-formal!
  "Un caso completo de la Empresa B hasta la orden. Devuelve la solicitud, la
  orden y el instante en que se registró la orden."
  [b]
  (let [solicitud (-> (ejecutar! b :persona/ana :compras/registrar-solicitud
                                 {:producto :P-100 :cantidad [:cantidad 20 :un]})
                      :salidas :solicitud)
        oferta    (fn [proveedor]
                    (-> (ejecutar! b :persona/ana :compras/registrar-oferta
                                   {:solicitud solicitud :proveedor proveedor :producto :P-100
                                    :cantidad [:cantidad 20 :un] :precio-unitario [:monto 30000 :CLP]
                                    :vence (dias-desde-hoy 15)})
                        :salidas :oferta))
        _         (oferta :proveedor/acme)
        elegida   (oferta :proveedor/beta)
        _         (ejecutar! b :persona/luis :compras/aprobar {:solicitud solicitud :oferta elegida})
        orden     (ejecutar! b :persona/ana :compras/emitir-orden
                             (assoc orden-20 :proveedor :proveedor/beta :solicitud solicitud :oferta elegida))]
    {:solicitud solicitud :orden (get-in orden [:salidas :orden]) :registro (:registro orden)}))

(deftest e5-consulta-en-fecha-pasada
  (let [b (empresa! empresa-b)
        {:keys [orden registro]} (compra-formal! b)
        _ (ejecutar! b :persona/ana :compras/registrar-recepcion
                     {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 12 :un]})
        _ (alm/publicar! *alm* (:empresa b) :politicas (compra-formal-v4 (Date.)))
        al-emitir (est/al b registro)
        consulta  #(:salidas (ej/ejecutar! % :persona/ana :compras/consultar-estado {:orden orden} {}))]
    (testing "al emitir la orden faltaba recibir todo; hoy faltan 8 (D17)"
      (is (= [:cantidad 20 :un] (:por-recibir (consulta al-emitir))))
      (is (= [:cantidad 8 :un] (:por-recibir (consulta b)))))
    (testing "la recepción no existía al emitir la orden"
      (is (not-any? #{:compras/recepcion} (map :hecho/tipo (:hechos (consulta al-emitir)))))
      (is (some #{:compras/recepcion} (map :hecho/tipo (:hechos (consulta b))))))
    (testing "la regla con que se abrió el caso queda en él"
      (is (= {:empresa-b/compra-formal 3} (:reglas (consulta al-emitir)) (:reglas (consulta b)))))
    (testing "la regla vigente en ese momento era la versión 3; hoy rige la 4"
      (is (= {:empresa-b/compra-formal 3}
             (:reglas (r/resolver al-emitir :persona/ana :compras/emitir-orden orden-20))))
      (is (= {:empresa-b/compra-formal 4}
             (:reglas (r/resolver b :persona/ana :compras/emitir-orden orden-20)))))))
