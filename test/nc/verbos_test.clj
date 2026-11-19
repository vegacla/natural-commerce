(ns nc.verbos-test
  "Etapa 9: verbos y tiempo. Despacho con costo (E13), cobro de varias facturas y
  cesión (E15), venta de mesón (E16), nota de crédito y período contable (E17),
  arriendo con calendario y MARCO por iniciativa propia (E18)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.bitacora :as bit]
            [nc.ejecutor :as ej]
            [nc.estado :as est]
            [nc.expresion :as e]
            [nc.marco-test :as m]
            [nc.resolvedor :as r]
            [nc.tiempo :as tiempo])
  (:import (java.util Date)))

(use-fixtures :once m/con-almacen)

(defn- politica [id forma aplica-a cuerpo & {:as mas}]
  (merge {:politica/id id :politica/version 1 :politica/origen :empresa
          :politica/aplica-a aplica-a :politica/forma forma :politica/cuerpo cuerpo}
         mas))

(defn- cliente! [estado id]
  (m/ejecutar! estado :persona/ana :ventas/alta-cliente {:id id :nombre (name id) :cupo [:monto 100000000 :CLP]}))

(defn- existencia!
  "Compra y recibe `n` P-100 a `precio` en la bodega central."
  [estado n precio]
  (let [orden (-> (m/ejecutar! estado :persona/ana :compras/emitir-orden
                               (assoc m/orden-20 :cantidad [:cantidad n :un] :precio-unitario [:monto precio :CLP]))
                  :salidas :orden)]
    (m/ejecutar! estado :persona/ana :compras/registrar-recepcion
                 {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad n :un]})))

(defn- pedido! [estado cliente n precio]
  (:salidas (m/ejecutar! estado :persona/ana :ventas/registrar-pedido
                         {:cliente cliente :producto :P-100 :cantidad [:cantidad n :un]
                          :precio-unitario [:monto precio :CLP]})))

(defn- factura! [estado pedido monto & {:keys [fecha vence] :or {fecha (Date.) vence 30}}]
  (m/ejecutar! estado :persona/ana :ventas/emitir-factura
               {:pedido pedido :monto [:monto monto :CLP] :fecha fecha :vencimiento (m/dias-desde-hoy vence)}))

(defn- existencia [estado]
  (:saldo/valor (alm/leer m/*alm* (:empresa estado) :saldos [:inventario/existencia {:producto :P-100 :bodega :bodega/central}])))

(defn- por-cobrar [estado pedido]
  (first (alm/buscar m/*alm* (:empresa estado) :compromisos {:compromiso/tipo :ventas/por-cobrar :compromiso/origen pedido})))

(defn- requisito [res id] (first (filter #(= id (:id %)) (:requisitos res))))

;; --- E13 -------------------------------------------------------------------------

(deftest e13-despacho-parcial-con-costo
  (let [b (m/empresa! m/empresa-b)
        _ (cliente! b :cliente/ferreteria)
        _ (existencia! b 10 30000)
        _ (existencia! b 10 36000)
        {:keys [entrega]} (pedido! b :cliente/ferreteria 8 45000)
        _ (m/ejecutar! b :persona/ana :inventario/reservar
                       {:entrega entrega :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 8 :un]})
        despacho (m/ejecutar! b :persona/ana :ventas/despachar
                              {:entrega entrega :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 5 :un]})
        hecho (est/leer b (get-in despacho [:salidas :despacho]))]
    (testing "el costo sale del costo promedio, un cálculo en código, con su versión (D38, D48)"
      (is (= [:monto 33000 :CLP] (get-in hecho [:hecho/datos :costo-unitario])))
      (is (= 1 (get-in hecho [:hecho/reglas :inventario/costo-unitario]))))
    (testing "un hecho reduce la entrega y la reserva (T9), y mueve la existencia con su costo"
      (is (= [(:hecho/id hecho)] (:hechos despacho)))
      (is (= {:cantidad [:cantidad 3 :un]} (:compromiso/saldo (est/leer b entrega))))
      (is (= [{:cantidad [:cantidad 3 :un]}]
             (map :compromiso/saldo (alm/buscar m/*alm* (:empresa b) :compromisos {:compromiso/tipo :inventario/reservado}))))
      (is (= {:cantidad [:cantidad 15 :un] :costo [:monto 495000 :CLP]} (existencia b))))
    (testing "el resto del pedido extingue la entrega y la reserva por cumplimiento"
      (m/ejecutar! b :persona/ana :ventas/despachar
                   {:entrega entrega :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 3 :un]})
      (is (= :extinguido (:compromiso/estado (est/leer b entrega))))
      (is (every? #(= :extinguido (:compromiso/estado %))
                  (alm/buscar m/*alm* (:empresa b) :compromisos {:compromiso/tipo :inventario/reservado}))))))

;; --- E15 -------------------------------------------------------------------------

(deftest e15-cobro-de-varias-facturas-y-cesion
  (let [a  (m/empresa! {})
        _  (cliente! a :cliente/ferreteria)
        p1 (:pedido (pedido! a :cliente/ferreteria 2 50000))
        p2 (:pedido (pedido! a :cliente/ferreteria 4 50000))
        p3 (:pedido (pedido! a :cliente/ferreteria 1 80000))
        f1 (get-in (factura! a p1 100000 :vence 10) [:salidas :factura])
        f2 (get-in (factura! a p2 200000 :vence 20) [:salidas :factura])
        f3 (get-in (factura! a p3 80000 :vence 30) [:salidas :factura])
        saldo #(get-in (por-cobrar a %) [:compromiso/saldo :monto])]
    (testing "la factura extingue lo que el pedido dejó por facturar y abre lo que se cobra, con su vencimiento"
      (is (= [:extinguido :facturado]
             ((juxt :compromiso/estado (comp :causa :compromiso/extincion)) (por-cobrar a p1))))
      (is (= [:activo [:monto 100000 :CLP]]
             ((juxt :compromiso/estado (comp :monto :compromiso/saldo)) (por-cobrar a f1)))))
    (testing "un cobro reduce varias facturas, de la que vence antes a la que vence después (T9)"
      (let [cobro (m/ejecutar! a :persona/ana :ventas/registrar-cobro {:cliente :cliente/ferreteria :monto [:monto 150000 :CLP]})]
        (is (= 1 (count (:hechos cobro)))))
      (is (= [:extinguido [:monto 0 :CLP]] ((juxt :compromiso/estado (comp :monto :compromiso/saldo)) (por-cobrar a f1))))
      (is (= [:monto 150000 :CLP] (saldo f2))))
    (testing "ceder una factura cambia el acreedor sin extinguir lo que se cobra (T8)"
      (let [cesion (m/ejecutar! a :persona/ana :finanzas/ceder-factura {:factura f3 :cesionario :proveedor/beta})
            c      (por-cobrar a f3)]
        (is (= [:proveedor/beta :activo [:monto 80000 :CLP]]
               ((juxt :compromiso/acreedor :compromiso/estado (comp :monto :compromiso/saldo)) c)))
        (is (= (first (:hechos cesion)) (:compromiso/transferido-por c)))))
    (testing "el cobro siguiente de la empresa ya no toca la factura cedida: la cobra el nuevo acreedor"
      (m/ejecutar! a :persona/ana :ventas/registrar-cobro {:cliente :cliente/ferreteria :monto [:monto 150000 :CLP]})
      (is (= :extinguido (:compromiso/estado (por-cobrar a f2))))
      (is (= [:monto 80000 :CLP] (saldo f3))))))

;; --- E16 -------------------------------------------------------------------------

(deftest e16-venta-de-meson
  (let [a (m/empresa! {})
        _ (existencia! a 10 30000)
        compromisos #(count (alm/buscar m/*alm* (:empresa a) :compromisos {}))
        antes (compromisos)
        venta (m/ejecutar! a :persona/ana :ventas/venta-mostrador
                           {:producto :P-100 :bodega :bodega/central :cantidad [:cantidad 2 :un]
                            :precio-unitario [:monto 50000 :CLP]})]
    (testing "una operación registra boleta, entrega y cobro, en una sola decisión (D62)"
      (is (= #{:ventas/boleta :ventas/despacho :ventas/cobro-contado}
             (set (map #(:hecho/tipo (est/leer a %)) (:hechos venta)))))
      (is (= (set (:hechos venta))
             (set (:decision/hechos (first (bit/decisiones m/*alm* (:empresa a) {:decision/id (:decision venta)})))))))
    (testing "pagada al contado, no deja compromisos abiertos"
      (is (= antes (compromisos))))
    (testing "la entrega bajó la existencia, al costo promedio"
      (is (= {:cantidad [:cantidad 8 :un] :costo [:monto 240000 :CLP]} (existencia a))))))

;; --- E17 -------------------------------------------------------------------------

(def periodo-cerrado
  (politica :empresa/periodo-cerrado :prohibe #{:ventas/emitir-factura :ventas/emitir-nota-credito}
            {:razon "El período contable de esa fecha está cerrado"}
            :politica/cuando '[:= [:contabilidad/estado-del-periodo {:fecha entrada/fecha}] :cerrado]))

(defn- valorizacion [version metodo]
  (politica :empresa/valorizacion :fija :ventas/despachar {:campo :entrada/metodo :valor metodo}
            :politica/version version :politica/aplica-con :cierre-del-periodo))

(deftest e17-nota-de-credito-y-periodo-contable
  (let [a (m/empresa! {:politicas [periodo-cerrado (valorizacion 1 :promedio)]})
        _ (alm/publicar! m/*alm* (:empresa a) :politicas
                         (assoc (valorizacion 2 :ultimo-costo) :politica/rige-desde (m/dias-desde-hoy -1)))
        _ (cliente! a :cliente/ferreteria)
        _ (existencia! a 10 30000)
        _ (existencia! a 10 36000)
        {:keys [pedido entrega]} (pedido! a :cliente/ferreteria 20 50000)
        f1 (get-in (factura! a pedido 1000000) [:salidas :factura])
        facturado #(get-in (alm/leer m/*alm* (:empresa a) :saldos [:ventas/facturado {:cliente :cliente/ferreteria}])
                           [:saldo/valor :monto])
        despachar #(get-in (m/ejecutar! a :persona/ana :ventas/despachar
                                        {:entrega entrega :producto :P-100 :bodega :bodega/central
                                         :cantidad [:cantidad 2 :un]})
                           [:salidas :despacho])]
    (is (= [:monto 1000000 :CLP] (facturado)))
    (testing "la nota de crédito revierte la factura sin modificarla (D16)"
      (let [antes (est/leer a f1)
            nota  (get-in (m/ejecutar! a :persona/ana :ventas/emitir-nota-credito {:factura f1 :fecha (Date.)})
                          [:salidas :nota])]
        (is (= f1 (:hecho/revierte (est/leer a nota))))
        (is (= (dissoc antes :xt/valid-from :xt/system-from) (dissoc (est/leer a f1) :xt/valid-from :xt/system-from)))
        (is (= [:monto 0 :CLP] (facturado)))
        (is (= :extinguido (:compromiso/estado (por-cobrar a f1))))))
    (testing "una factura se revierte una sola vez; el intento queda en la bitácora como error del contrato"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ya fue revertido"
                            (m/ejecutar! a :persona/ana :ventas/emitir-nota-credito {:factura f1 :fecha (Date.)})))
      (is (= [:hecho-ya-revertido]
             (map (comp :tipo :decision/error) (bit/decisiones m/*alm* (:empresa a) {:decision/resultado :error})))))
    (let [antes-del-cierre (despachar)]
      (testing "antes del cierre rige la valorización que regía al último cierre: la primera (D44)"
        (is (= [:monto 33000 :CLP] (get-in (est/leer a antes-del-cierre) [:hecho/datos :costo-unitario])))
        (is (= 1 (get-in (est/leer a antes-del-cierre) [:hecho/reglas :empresa/valorizacion]))))
      (m/ejecutar! a :persona/ana :contabilidad/cerrar-periodo {:fecha (m/dias-desde-hoy -40)})
      (testing "registrar con fecha de un período cerrado se prohíbe, con la razón (T4)"
        (let [res (r/resolver a :persona/ana :ventas/emitir-factura
                              {:pedido pedido :monto [:monto 1 :CLP] :fecha (m/dias-desde-hoy -40)
                               :vencimiento (m/dias-desde-hoy 30)})]
          (is (= :prohibido (:estado res)))
          (is (= "El período contable de esa fecha está cerrado" (:razon (requisito res :empresa/periodo-cerrado)))))
        (is (= :listo (:estado (r/resolver a :persona/ana :ventas/emitir-factura
                                           {:pedido pedido :monto [:monto 1 :CLP] :fecha (Date.)
                                            :vencimiento (m/dias-desde-hoy 30)})))))
      (testing "desde el cierre rige la versión nueva, sin recálculo hacia atrás"
        (let [despues (despachar)]
          (is (= [:monto 36000 :CLP] (get-in (est/leer a despues) [:hecho/datos :costo-unitario])))
          (is (= 2 (get-in (est/leer a despues) [:hecho/reglas :empresa/valorizacion])))
          (is (= [:monto 33000 :CLP] (get-in (est/leer a antes-del-cierre) [:hecho/datos :costo-unitario]))))))
    (testing "un período se cierra una sola vez"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Ya existe"
                            (m/ejecutar! a :persona/ana :contabilidad/cerrar-periodo {:fecha (m/dias-desde-hoy -40)}))))))

;; --- E18 -------------------------------------------------------------------------

(def devengar-al-vencer
  (politica :empresa/devengar-arriendo :inicia [:vence :finanzas/arriendo]
            '{:objetivo :finanzas/devengar-cuota :entradas {:arriendo compromiso/id}}))

(def extinguir-ofertas-vencidas
  (politica :empresa/ofertas-vencidas :inicia [:vence :compras/oferta-vigente]
            '{:objetivo :compras/extinguir-oferta-vencida :entradas {:oferta compromiso/id}}))

(def marco-sin-confirmar
  (politica :empresa/marco-sin-confirmar :exime #{:finanzas/devengar-cuota :compras/extinguir-oferta-vencida} {}
            :politica/cuando '[:= actor :marco]))

(defn- en [n-meses n-dias]
  (e/evaluar [:+dias [:+meses (Date.) n-meses] n-dias] {}))

(defn- oferta-vencida! [estado]
  (let [solicitud (-> (m/ejecutar! estado :persona/ana :compras/registrar-solicitud
                                   {:producto :P-100 :cantidad [:cantidad 5 :un]})
                      :salidas :solicitud)]
    (-> (m/ejecutar! estado :persona/ana :compras/registrar-oferta
                     {:solicitud solicitud :proveedor :proveedor/acme :producto :P-100 :cantidad [:cantidad 5 :un]
                      :precio-unitario [:monto 30000 :CLP] :vence (m/dias-desde-hoy -1)})
        :salidas :oferta)))

(deftest e18-arriendo-que-marco-devenga
  (let [a        (m/empresa! {:politicas [devengar-al-vencer extinguir-ofertas-vencidas marco-sin-confirmar]})
        arriendo (-> (m/ejecutar! a :persona/ana :finanzas/registrar-arriendo
                                  {:arrendador :proveedor/acme :monto [:monto 500000 :CLP]
                                   :cuotas [:cantidad 12 :cuota] :desde (en -3 5)})
                     :salidas :arriendo)
        oferta   (oferta-vencida! a)
        cuotas   #(sort-by (comp :fecha :hecho/datos)
                           (alm/buscar m/*alm* (:empresa a) :hechos {:hecho/tipo :finanzas/cuota}))
        planteado (tiempo/avanzar! a)]
    (testing "pasan tres vencimientos: MARCO devenga cada cuota, sin una persona (H8, P8)"
      (is (= 3 (count (cuotas))))
      (is (every? #(= :marco (:hecho/actor %)) (cuotas)))
      (is (= 3 (count (alm/buscar m/*alm* (:empresa a) :compromisos {:compromiso/tipo :finanzas/cuota-por-pagar}))))
      (is (= {:cuotas [:cantidad 9 :cuota]} (:compromiso/saldo (est/leer a arriendo))))
      (is (= (e/evaluar [:+meses (get-in (first (alm/buscar m/*alm* (:empresa a) :hechos {:hecho/tipo :finanzas/contrato-arriendo}))
                                         [:hecho/datos :desde])
                             3]
                        {})
             (:compromiso/vence (est/leer a arriendo)))))
    (testing "MARCO extingue la oferta vencida, por vencimiento"
      (is (= [:extinguido :vencimiento]
             ((juxt :compromiso/estado (comp :causa :compromiso/extincion)) (est/leer a oferta)))))
    (testing "todo quedó en la bitácora, con origen MARCO y eximido de confirmación"
      (is (= 4 (count planteado)))
      (is (every? #(= [:marco :eximida :ejecutado] ((juxt :decision/origen :decision/confirmacion :decision/resultado) %))
                  (bit/decisiones m/*alm* (:empresa a) {:decision/origen :marco}))))
    (testing "avanzar de nuevo hasta el mismo instante no repite nada"
      (is (empty? (tiempo/avanzar! a))))
    (testing "un anexo cambia el monto desde la cuota siguiente; las devengadas no cambian (D45)"
      (m/ejecutar! a :persona/ana :finanzas/registrar-anexo {:arriendo arriendo :monto [:monto 550000 :CLP] :via :bilateral})
      (tiempo/avanzar! a (en 2 10))
      (is (= [500000 500000 500000 550000 550000 550000]
             (map #(second (get-in % [:hecho/datos :monto])) (cuotas)))))))

(deftest sin-exime-marco-propone-una-vez
  (let [a (m/empresa! {:politicas [extinguir-ofertas-vencidas]})
        oferta (oferta-vencida! a)
        [propuesta :as planteado] (tiempo/avanzar! a)]
    (testing "sin una política que lo exima, MARCO no ejecuta: propone (D51, D53)"
      (is (= 1 (count planteado)))
      (is (= :por-confirmar (:estado propuesta)))
      (is (= :latente (:compromiso/estado (est/leer a oferta)))))
    (testing "y no vuelve a proponer lo mismo al avanzar otra vez"
      (is (empty? (tiempo/avanzar! a))))))

(deftest inicia-con-plazo
  (let [a (m/empresa! {:politicas [(politica :empresa/revisar-orden :inicia [:registra :compras/orden]
                                             '{:objetivo :compras/consultar-estado :despues [:dias 30]
                                               :entradas {:orden hecho/id}})]})
        orden (get-in (m/ejecutar! a :persona/ana :compras/emitir-orden m/orden-20) [:salidas :orden])]
    (testing "una \"inicia\" con :despues no se dispara al registrar el hecho"
      (is (empty? (tiempo/avanzar! a))))
    (testing "sí cuando pasa el plazo, y una sola vez"
      (let [[r] (tiempo/avanzar! a (m/dias-desde-hoy 31))]
        (is (= [:compras/consultar-estado :consultado {:orden orden}] ((juxt :objetivo :estado :entradas) r))))
      (is (empty? (tiempo/avanzar! a (m/dias-desde-hoy 32)))))))
