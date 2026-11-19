(ns nc.formas-test
  "Etapa 8: las formas que faltaban. Fija, sugiere y limita en un pedido de
  venta con reserva de stock (E12), inicia (E19) y conflictos entre políticas
  (E21)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.bitacora :as bit]
            [nc.ejecutor :as ej]
            [nc.estado :as est]
            [nc.expresion :as e]
            [nc.marco-test :as m]
            [nc.plan :as plan]
            [nc.politicas :as pol]
            [nc.resolvedor :as r]
            [nc.sara :as sara]))

(use-fixtures :once m/con-almacen)

(defn- requisito [res id] (first (filter #(= id (:id %)) (:requisitos res))))

(defn- politica [id forma aplica-a cuerpo & {:as mas}]
  (merge {:politica/id id :politica/version 1 :politica/origen :empresa
          :politica/aplica-a aplica-a :politica/forma forma :politica/cuerpo cuerpo}
         mas))

(defn- lista-de-precios! [estado version desde precio]
  (alm/publicar! m/*alm* (:empresa estado) :tablas
                 {:tabla/id :lista-precios :tabla/version version :tabla/rige-desde desde
                  :tabla/filas [{:clave [:P-100] :valor [:monto precio :CLP]}]}))

(defn- cliente! [estado id cupo]
  (m/ejecutar! estado :persona/ana :ventas/alta-cliente {:id id :nombre (name id) :cupo [:monto cupo :CLP]}))

(defn- existencia!
  "Compra y recibe `n` P-100 en la bodega central, bajo el umbral de B."
  [estado n]
  (let [orden (-> (m/ejecutar! estado :persona/ana :compras/emitir-orden
                               (assoc m/orden-20 :cantidad [:cantidad n :un]))
                  :salidas :orden)]
    (m/ejecutar! estado :persona/ana :compras/registrar-recepcion
                 {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad n :un]})))

(defn- disponible [estado]
  (e/evaluar [:inventario/disponible {:producto :P-100 :bodega :bodega/central}] (est/contexto estado {})))

(def precio-de-lista
  (politica :empresa/precio-de-lista :sugiere :ventas/registrar-pedido
            '{:campo :entrada/precio-unitario :valor [:tabla :lista-precios entrada/producto]}))

(def cupo-de-credito
  (politica :empresa/cupo-de-credito :limita :ventas/registrar-pedido
            '{:valor      [:* entrada/cantidad entrada/precio-unitario]
             :maximo     [:credito/cupo-disponible {:cliente entrada/cliente}]
             :tolerancia [:% 10]}))

(def bodega-de-reservas
  (politica :empresa/bodega-de-reservas :fija :inventario/reservar
            {:campo :entrada/bodega :valor :bodega/central}))

;; --- E12 -------------------------------------------------------------------------

(deftest e12-pedido-con-precio-cupo-y-reserva
  (let [b (m/empresa! (update m/empresa-b :politicas into [precio-de-lista cupo-de-credito bodega-de-reservas]))
        _ (lista-de-precios! b 1 (m/dias-desde-hoy -30) 45000)
        _ (lista-de-precios! b 2 (m/dias-desde-hoy 30) 47000)
        _ (cliente! b :cliente/ferreteria 1000000)
        pedido (fn [n & {:as mas}] (merge {:cliente :cliente/ferreteria :producto :P-100 :cantidad [:cantidad n :un]} mas))]
    (testing "sin precio, se sugiere el de la lista vigente, con su versión (sugiere, D48)"
      (let [res (r/resolver b :persona/ana :ventas/registrar-pedido (pedido 10))]
        (is (= :listo (:estado res)))
        (is (= [{:campo :entrada/precio-unitario :forma :sugiere :politica :empresa/precio-de-lista
                 :valor [:monto 45000 :CLP]}]
               (:valores res)))
        (is (= 1 (get-in res [:reglas :lista-precios])))))
    (testing "SARA muestra el precio sugerido en el formulario"
      (is (= [:monto 45000 :CLP]
             (:valor (first (filter #(= :precio-unitario (:nombre %))
                                    (:campos (sara/formulario b :persona/ana :ventas/registrar-pedido (pedido 10)))))))))
    (testing "la lista que rige el próximo mes todavía no aplica; mirando en esa fecha, sí (H9)"
      (is (= [:monto 47000 :CLP]
             (:valor (first (:valores (r/resolver (est/al b (m/dias-desde-hoy 31)) :persona/ana
                                                  :ventas/registrar-pedido (pedido 10))))))))
    (testing "el precio sugerido se puede cambiar"
      (is (empty? (:valores (r/resolver b :persona/ana :ventas/registrar-pedido
                                        (pedido 10 :precio-unitario [:monto 44000 :CLP]))))))
    (let [{:keys [salidas] :as hecho} (m/ejecutar! b :persona/ana :ventas/registrar-pedido (pedido 10))
          entrega (:entrega salidas)]
      (testing "el pedido se registra con el precio sugerido, y abre la entrega y el cobro latente"
        (is (= :ejecutado (:estado hecho)))
        (is (= [:monto 45000 :CLP] (get-in (est/leer b (:pedido salidas)) [:hecho/datos :precio-unitario])))
        (is (= 1 (get-in (est/leer b (:pedido salidas)) [:hecho/reglas :lista-precios])))
        (is (= :activo (:compromiso/estado (est/leer b entrega)))))
      (testing "el cupo disponible es un concepto: el cupo menos lo que se le cobrará (H3)"
        (is (= [:monto 550000 :CLP] (e/evaluar [:credito/cupo-disponible {:cliente :cliente/ferreteria}]
                                               (est/contexto b {})))))
      (testing "limita: bajo el cupo pasa; sobre el cupo, dentro de la tolerancia, también (D34)"
        (is (= :satisfecho (:estado (requisito (r/resolver b :persona/ana :ventas/registrar-pedido (pedido 12))
                                               :empresa/cupo-de-credito))))
        (let [req (requisito (r/resolver b :persona/ana :ventas/registrar-pedido (pedido 13)) :empresa/cupo-de-credito)]
          (is (= [:satisfecho true] ((juxt :estado :tolerado) req)))))
      (testing "más allá de la tolerancia, se prohíbe con la razón"
        (let [res (r/resolver b :persona/ana :ventas/registrar-pedido (pedido 30))]
          (is (= :prohibido (:estado res)))
          (is (re-find #"Supera el límite" (:razon (requisito res :empresa/cupo-de-credito))))))
      (existencia! b 10)
      (testing "la disponibilidad es un cálculo, no un saldo guardado (visión 6.5)"
        (is (= [:cantidad 10 :un] (disponible b)))
        (is (nil? (alm/leer m/*alm* (:empresa b) :saldos [:inventario/disponible {:producto :P-100 :bodega :bodega/central}]))))
      (testing "reservar sin bodega: la fija la política; con otra bodega, se prohíbe (fija)"
        (let [res (r/resolver b :persona/ana :inventario/reservar
                              {:entrega entrega :producto :P-100 :cantidad [:cantidad 6 :un] :bodega :bodega/norte})]
          (is (= :prohibido (:estado res)))
          (is (some #(re-find #"fija bodega" (str (:razon %))) (:requisitos res))))
        (let [res (m/ejecutar! b :persona/ana :inventario/reservar {:entrega entrega :producto :P-100 :cantidad [:cantidad 6 :un]})]
          (is (= :ejecutado (:estado res)))
          (is (= :bodega/central (get-in (est/leer b (first (:hechos res))) [:hecho/datos :bodega])))))
      (testing "la reserva es un compromiso interno que baja la disponibilidad, no la existencia (T2)"
        (is (= [:cantidad 4 :un] (disponible b)))
        (is (= [:cantidad 10 :un] (get-in (alm/leer m/*alm* (:empresa b) :saldos
                                                    [:inventario/existencia {:producto :P-100 :bodega :bodega/central}])
                                          [:saldo/valor :cantidad])))))
    (testing "un cliente desactivado no recibe un pedido (E11)"
      (cliente! b :cliente/cerrado 500000)
      (m/ejecutar! b :persona/ana :ventas/desactivar-cliente {:cliente :cliente/cerrado})
      (is (= ":cliente/cerrado está desactivado"
             (:problema (requisito (r/resolver b :persona/ana :ventas/registrar-pedido
                                               (assoc (pedido 1) :cliente :cliente/cerrado))
                                   :entrada/cliente)))))))

(deftest sin-inventario-no-hay-reserva
  (testing "una empresa sin la capacidad de inventario vende pero no reserva (E10)"
    (let [a (m/empresa! {})]
      (plan/cambiar! a {:plan/capacidades #{:compras :ventas} :plan/rige-desde (m/dias-desde-hoy -1)} {})
      (cliente! a :cliente/kiosco 100000)
      (let [entrega (-> (m/ejecutar! a :persona/ana :ventas/registrar-pedido
                                     {:cliente :cliente/kiosco :producto :P-100 :cantidad [:cantidad 1 :un]
                                      :precio-unitario [:monto 1000 :CLP]})
                        :salidas :entrega)]
        (is (uuid? entrega))
        (is (not (contains? (est/operaciones a) :inventario/reservar)))
        (is (= :prohibido (:estado (r/resolver a :persona/ana :inventario/reservar
                                               {:entrega entrega :producto :P-100 :bodega :bodega/central
                                                :cantidad [:cantidad 1 :un]}))))))))

;; --- E19 -------------------------------------------------------------------------

(def reponer
  (politica :empresa/reponer :inicia [:registra :inventario/reserva]
            '{:objetivo :compras/emitir-orden
             :entradas {:producto        hecho/producto
                        :cantidad        [:cantidad 20 :un]
                        :proveedor       :proveedor/acme
                        :precio-unitario [:monto 30000 :CLP]}}
            :politica/cuando '[:< [:inventario/disponible {:producto hecho/producto :bodega hecho/bodega}]
                                 [:cantidad 5 :un]]))

(def compras-chicas-sin-confirmar
  (politica :empresa/compras-chicas :exime :compras/emitir-orden
            '{:hasta [:< [:* entrada/cantidad entrada/precio-unitario] [:monto 1000000 :CLP]]}))

(defn- vender-y-reservar! [estado n]
  (let [entrega (-> (m/ejecutar! estado :persona/ana :ventas/registrar-pedido
                                 {:cliente :cliente/ferreteria :producto :P-100 :cantidad [:cantidad n :un]
                                  :precio-unitario [:monto 45000 :CLP]})
                    :salidas :entrega)]
    (m/ejecutar! estado :persona/ana :inventario/reservar
                 {:entrega entrega :producto :P-100 :bodega :bodega/central :cantidad [:cantidad n :un]})))

(deftest e19-reposicion-que-inicia-marco
  (testing "en A, que exime de confirmación las compras chicas, MARCO emite la orden solo"
    (let [a (m/empresa! {:politicas [reponer compras-chicas-sin-confirmar]})
          _ (cliente! a :cliente/ferreteria 10000000)
          _ (existencia! a 20)
          {[iniciada] :iniciadas} (vender-y-reservar! a 18)]
      (is (= [:empresa/reponer :compras/emitir-orden :ejecutado]
             ((juxt :politica :objetivo :estado) iniciada)))
      (let [d (first (bit/decisiones m/*alm* (:empresa a) {:decision/id (:decision iniciada)}))]
        (is (= [:marco :marco :eximida] ((juxt :decision/actor :decision/origen :decision/confirmacion) d)))
        (is (= :marco (:hecho/actor (est/leer a (first (:decision/hechos d)))))))
      (testing "con la orden de MARCO en camino, la disponibilidad vuelve a subir"
        (is (= [:cantidad 22 :un] (disponible a))))))
  (testing "sin una política que lo exima, MARCO no se confirma a sí mismo: la orden queda por confirmar (D53)"
    (let [a (m/empresa! {:politicas [reponer]})
          _ (cliente! a :cliente/ferreteria 10000000)
          _ (existencia! a 20)
          {[iniciada] :iniciadas} (vender-y-reservar! a 18)]
      (is (= :por-confirmar (:estado iniciada)))
      (is (= 1 (count (alm/buscar m/*alm* (:empresa a) :hechos {:hecho/tipo :compras/orden}))))))
  (testing "en B, la misma política propone: la compra necesita solicitud, ofertas y aprobación (D51)"
    (let [b (m/empresa! (update m/empresa-b :politicas conj reponer))
          _ (cliente! b :cliente/ferreteria 10000000)
          _ (existencia! b 10)
          ordenes #(count (alm/buscar m/*alm* (:empresa b) :hechos {:hecho/tipo :compras/orden}))
          antes (ordenes)
          {[iniciada] :iniciadas} (vender-y-reservar! b 8)]
      (is (= :bloqueado (:estado iniciada)))
      (is (= #{:compras/solicitud :compras/oferta :compras/aprobacion}
             (set (map :id (filter :politica (:requisitos iniciada))))))
      (testing "la propuesta no escribe nada: queda en la bitácora, no como compromiso (D40)"
        (is (= antes (ordenes)))
        (is (= [:bloqueado :marco]
               ((juxt :decision/resultado :decision/origen)
                (first (bit/decisiones m/*alm* (:empresa b) {:decision/id (:decision iniciada)})))))))))

;; --- E21 -------------------------------------------------------------------------

(def bodega-norte
  (politica :empresa/bodega-norte :fija :inventario/reservar {:campo :entrada/bodega :valor :bodega/norte}))

(deftest e21-conflictos-entre-politicas
  (let [b (m/empresa! m/empresa-b)]
    (alm/cargar-entidades! m/*alm* (:empresa b) [{:entidad/id :bodega/norte :entidad/tipo :bodega :bodega/nombre "Norte"}])
    (testing "dos políticas que fijan el mismo campo chocan al publicar, no al ejecutar (D35)"
      (is (= :publicada (:estado (pol/publicar! b (assoc bodega-de-reservas :politica/rige-desde (m/dias-desde-hoy -1))))))
      (let [res (pol/publicar! b (assoc bodega-norte :politica/rige-desde (m/dias-desde-hoy -1)))]
        (is (= :conflicto (:estado res)))
        (is (= [:fija-dos-veces :empresa/bodega-de-reservas] ((juxt :conflicto :con) (first (:conflictos res)))))
        (is (nil? (alm/vigente m/*alm* (:empresa b) :politicas :empresa/bodega-norte)))))
    (testing "la prioridad lo resuelve: rige la de mayor prioridad"
      (is (= :publicada (:estado (pol/publicar! b (assoc bodega-norte :politica/rige-desde (m/dias-desde-hoy -1)
                                                         :politica/prioridad 10)))))
      (is (= [{:campo :entrada/bodega :forma :fija :politica :empresa/bodega-norte :valor :bodega/norte}]
             (:valores (r/resolver b :persona/ana :inventario/reservar {:producto :P-100 :cantidad [:cantidad 1 :un]})))))
    (testing "exigir un hecho cuya operación se prohíbe siempre es un conflicto"
      (let [res (pol/publicar! b (politica :empresa/nadie-aprueba :prohibe :compras/aprobar {:razon "No"}
                                           :politica/rige-desde (m/dias-desde-hoy -1)))]
        (is (= [:exige-lo-prohibido :empresa-b/compra-formal] ((juxt :conflicto :con) (first (:conflictos res)))))))
    (testing "una política legal gana: la empresa no la contradice ni la edita (D5)"
      (let [legal (politica :legal/precio-regulado :fija :ventas/registrar-pedido
                            {:campo :entrada/precio-unitario :valor [:monto 1000 :CLP]}
                            :politica/origen :legal :politica/rige-desde (m/dias-desde-hoy -1))]
        (is (= :rechazada (:estado (pol/publicar! b legal))))
        (is (= :publicada (:estado (pol/publicar! b legal {:autor :plataforma}))))
        (let [res (pol/publicar! b (politica :empresa/precio-propio :fija :ventas/registrar-pedido
                                             {:campo :entrada/precio-unitario :valor [:monto 2000 :CLP]}
                                             :politica/rige-desde (m/dias-desde-hoy -1) :politica/prioridad 99))]
          (is (= :contradice-legal (:conflicto (first (:conflictos res))))))))))

(deftest la-empresa-cambia-como-se-combinan
  (let [dos-eximen [(politica :empresa/eximir-chicas :exime :compras/emitir-orden
                              '{:hasta [:< [:* entrada/cantidad entrada/precio-unitario] [:monto 100000 :CLP]]})
                    (politica :empresa/eximir-siempre :exime :compras/emitir-orden {})]
        confirmacion (fn [config]
                       (:confirmacion (r/resolver (m/empresa! config) :persona/ana :compras/emitir-orden m/orden-20)))]
    (testing "por defecto, deben cumplirse todas las \"exime\" (P9)"
      (is (= :requerida (confirmacion {:politicas dos-eximen}))))
    (testing "la empresa puede decidir que baste una"
      (is (= :eximida (confirmacion {:politicas dos-eximen :combinacion {:exime :basta-una}})))))
  (testing "entre dos \"sugiere\", rige la más específica: la que tiene condición"
    (let [b (m/empresa! {:politicas [precio-de-lista
                                     (politica :empresa/precio-ferreteria :sugiere :ventas/registrar-pedido
                                               {:campo :entrada/precio-unitario :valor [:monto 40000 :CLP]}
                                               :politica/cuando '[:= entrada/cliente :cliente/ferreteria])]})]
      (lista-de-precios! b 1 (m/dias-desde-hoy -30) 45000)
      (cliente! b :cliente/ferreteria 1000000)
      (is (= [:monto 40000 :CLP]
             (:valor (first (:valores (r/resolver b :persona/ana :ventas/registrar-pedido
                                                  {:cliente :cliente/ferreteria :producto :P-100
                                                   :cantidad [:cantidad 1 :un]})))))))))
