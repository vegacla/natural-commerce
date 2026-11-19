(ns nc.legal-test
  "Etapa 10: lo legal y la contabilidad (E14), y sustituir implementaciones sin
  cambiar contratos (E22). Lo tributario no está verificado por un experto (D42)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.catalogo :as cat]
            [nc.estado :as est]
            [nc.marco-test :as m]
            [nc.politicas :as pol]
            [nc.resolvedor :as r]
            [nc.valor :as v])
  (:import (java.util Date)))

(use-fixtures :once m/con-almacen)

(def paquete-legal (:politicas (cat/leer "legal/cl.edn")))

(defn- politica [id forma aplica-a cuerpo & {:as mas}]
  (merge {:politica/id id :politica/version 1 :politica/origen :empresa
          :politica/aplica-a aplica-a :politica/forma forma :politica/cuerpo cuerpo}
         mas))

(def cuentas-de-b
  "Las cuentas de la Empresa B: las fija su política, no el contrato."
  (for [[campo cuenta] {:entrada/cuenta-clientes :cuenta/c1101
                        :entrada/cuenta-ventas   :cuenta/c4101
                        :entrada/cuenta-iva      :cuenta/c2111}]
    (politica (keyword "empresa-b" (str "cuenta-" (name campo))) :fija :ventas/emitir-factura
              {:campo campo :valor cuenta})))

(defn- requisito [res id] (first (filter #(= id (:id %)) (:requisitos res))))

(defn- con-ventas!
  "Una empresa con el paquete legal instalado, un cliente y un pedido de 10 a 50.000."
  [config]
  (let [e (m/empresa! config)]
    (pol/instalar-legal! e paquete-legal)
    (m/ejecutar! e :persona/ana :ventas/alta-cliente {:id :cliente/ferreteria :nombre "Ferretería"
                                                      :cupo [:monto 100000000 :CLP]})
    [e (get-in (m/ejecutar! e :persona/ana :ventas/registrar-pedido
                            {:cliente :cliente/ferreteria :producto :P-100 :cantidad [:cantidad 10 :un]
                             :precio-unitario [:monto 50000 :CLP]})
               [:salidas :pedido])]))

(defn- factura [pedido & {:as mas}]
  (merge {:pedido pedido :monto [:monto 500000 :CLP] :fecha (Date.) :vencimiento (m/dias-desde-hoy 30)} mas))

(defn- folios! [estado n]
  (m/ejecutar! estado :persona/ana :tributario/registrar-autorizacion-folios
               {:documento :factura :cantidad [:cantidad n :folio]}))

(defn- saldo [estado concepto clave]
  (:saldo/valor (alm/leer m/*alm* (:empresa estado) :saldos [concepto clave])))

;; --- E14 -------------------------------------------------------------------------

(deftest e14-factura-con-iva-folios-y-asiento
  (let [[b pedido] (con-ventas! (update m/empresa-b :politicas into cuentas-de-b))]
    (testing "sin folios autorizados no se emite, y la razón viene de la ley (D63)"
      (let [res (r/resolver b :persona/ana :ventas/emitir-factura (factura pedido))]
        (is (= :prohibido (:estado res)))
        (is (= "No quedan folios autorizados por el SII para facturas"
               (:razon (requisito res :legal.cl/sin-folios))))))
    (folios! b 100)
    (testing "con folios, el IVA y el folio los fija la ley; las cuentas, la empresa"
      (let [res (r/resolver b :persona/ana :ventas/emitir-factura (factura pedido))]
        (is (= :listo (:estado res)))
        (is (= {:entrada/iva [:monto 95000 :CLP] :entrada/folio [:cantidad 1 :folio]
                :entrada/cuenta-clientes :cuenta/c1101 :entrada/cuenta-ventas :cuenta/c4101 :entrada/cuenta-iva :cuenta/c2111}
               (into {} (map (juxt :campo :valor)) (:valores res))))))
    (testing "un IVA distinto del que fija la ley se prohíbe"
      (is (= :prohibido (:estado (r/resolver b :persona/ana :ventas/emitir-factura
                                             (factura pedido :iva [:monto 1 :CLP]))))))
    (let [{:keys [salidas] :as res} (m/ejecutar! b :persona/ana :ventas/emitir-factura (factura pedido))
          f (est/leer b (:factura salidas))]
      (testing "la factura guarda el IVA, el folio, el envío y las versiones que los dieron (D48)"
        (is (= [[:monto 95000 :CLP] [:cantidad 1 :folio] "V1-1"]
               ((juxt :iva :folio :envio) (:hecho/datos f))))
        (is (= {:legal.cl/iva 1 :legal.cl/folio-correlativo 1 :tributario/envio-dte 1}
               (select-keys (:hecho/reglas f) [:legal.cl/iva :legal.cl/folio-correlativo :tributario/envio-dte]))))
      (testing "lo que se cobra es el total, con IVA"
        (is (= {:monto [:monto 595000 :CLP]} (:compromiso/saldo (est/leer b (:cobro salidas))))))
      (testing "el asiento mueve las cuentas que fijó la empresa, y el balance cuadra"
        (let [cuentas (for [c [:cuenta/c1101 :cuenta/c4101 :cuenta/c2111]]
                        (saldo b :contabilidad/cuenta {:cuenta c}))]
          (is (= [{:debe [:monto 595000 :CLP] :haber 0}
                  {:debe 0 :haber [:monto 500000 :CLP]}
                  {:debe 0 :haber [:monto 95000 :CLP]}]
                 cuentas))
          (is (= (reduce v/sumar 0 (map :debe cuentas)) (reduce v/sumar 0 (map :haber cuentas))))))
      (testing "la factura consumió un folio"
        (is (= {:autorizados [:cantidad 100 :folio] :emitidos [:cantidad 1 :folio]}
               (saldo b :tributario/folios {:documento :factura}))))
      (testing "la nota de crédito revierte el asiento con la factura; el folio usado no vuelve"
        (m/ejecutar! b :persona/ana :ventas/emitir-nota-credito {:factura (:factura salidas) :fecha (Date.)})
        (is (= {:debe [:monto 0 :CLP] :haber 0} (saldo b :contabilidad/cuenta {:cuenta :cuenta/c1101})))
        (is (= {:debe 0 :haber [:monto 0 :CLP]} (saldo b :contabilidad/cuenta {:cuenta :cuenta/c2111})))
        (is (= [:cantidad 1 :folio] (:emitidos (saldo b :tributario/folios {:documento :factura}))))
        (is (= :extinguido (:compromiso/estado (est/leer b (:cobro salidas))))))
      (is (= :ejecutado (:estado res))))
    (testing "la empresa no edita ni contradice la ley (D5)"
      (is (= :rechazada (:estado (pol/publicar! b (assoc (first paquete-legal) :politica/version 2
                                                         :politica/cuerpo {:campo :entrada/iva :valor [:monto 0 :CLP]})))))
      (is (= :contradice-legal
             (:conflicto (first (:conflictos (pol/publicar! b (politica :empresa-b/iva-propio :fija :ventas/emitir-factura
                                                                        {:campo :entrada/iva :valor [:monto 0 :CLP]}
                                                                        :politica/rige-desde (m/dias-desde-hoy -1)
                                                                        :politica/prioridad 99))))))))
    (testing "todo lo tributario está marcado como no verificado, con su fuente (D42)"
      (let [legales (filter #(= :legal (:politica/origen %)) (est/vigentes b))]
        (is (= 3 (count legales)))
        (is (every? #(and (false? (:politica/verificada %)) (string? (:politica/fuente %))) legales))))))

(deftest sin-cuentas-no-hay-asiento
  (testing "una empresa sin cuentas configuradas factura igual, sin asiento"
    (let [[a pedido] (con-ventas! {})]
      (folios! a 10)
      (let [f (est/leer a (get-in (m/ejecutar! a :persona/ana :ventas/emitir-factura (factura pedido)) [:salidas :factura]))]
        (is (nil? (get-in f [:hecho/datos :asiento])))
        (is (empty? (alm/buscar m/*alm* (:empresa a) :saldos {:saldo/concepto :contabilidad/cuenta})))))))

;; --- E22 -------------------------------------------------------------------------

(defn- despachar! [estado entrega n]
  (est/leer estado (get-in (m/ejecutar! estado :persona/ana :ventas/despachar
                                        {:entrega entrega :producto :P-100 :bodega :bodega/central
                                         :cantidad [:cantidad n :un]})
                           [:salidas :despacho])))

(deftest e22-sustituir-implementaciones
  (testing "el costo promedio se reemplaza por FIFO: una versión nueva del concepto (D38, D48)"
    (let [a (m/empresa! {})
          _ (doseq [precio [30000 36000]]
              (let [orden (get-in (m/ejecutar! a :persona/ana :compras/emitir-orden
                                               (assoc m/orden-20 :cantidad [:cantidad 10 :un] :precio-unitario [:monto precio :CLP]))
                                  [:salidas :orden])]
                (m/ejecutar! a :persona/ana :compras/registrar-recepcion
                             {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 10 :un]})))
          _ (m/ejecutar! a :persona/ana :ventas/alta-cliente {:id :cliente/ferreteria :nombre "F" :cupo [:monto 100000000 :CLP]})
          entrega (get-in (m/ejecutar! a :persona/ana :ventas/registrar-pedido
                                       {:cliente :cliente/ferreteria :producto :P-100 :cantidad [:cantidad 20 :un]
                                        :precio-unitario [:monto 50000 :CLP]})
                          [:salidas :entrega])
          antes (despachar! a entrega 5)
          catalogo-2 (update-in (:catalogo a) [:conceptos :inventario/costo-unitario]
                                assoc :concepto/version 2 :concepto/calculo 'nc.calculos/costo-fifo)
          a-2 (est/abrir m/*alm* catalogo-2 (:empresa a))
          despues (despachar! a-2 entrega 7)]
      (is (= [[:monto 33000 :CLP] 1] ((juxt (comp :costo-unitario :hecho/datos) (comp :inventario/costo-unitario :hecho/reglas)) antes)))
      (testing "FIFO: las 5 unidades que quedan a 30.000 y 2 a 36.000"
        (is (= [[:monto 31714 :CLP] 2] ((juxt (comp :costo-unitario :hecho/datos) (comp :inventario/costo-unitario :hecho/reglas)) despues))))
      (testing "el despacho anterior conserva su valor y su versión"
        (is (= antes (est/leer a-2 (:hecho/id antes)))))
      (testing "ningún contrato cambió"
        (is (= (:contratos (:catalogo a)) (:contratos catalogo-2))))))
  (testing "el envío al SII cambia de implementación sin tocar el contrato de la factura"
    (let [[b pedido] (con-ventas! {})
          _ (folios! b 10)
          f1 (est/leer b (get-in (m/ejecutar! b :persona/ana :ventas/emitir-factura (factura pedido)) [:salidas :factura]))
          catalogo-2 (assoc-in (:catalogo b) [:implementaciones :tributario/envio-dte] {:fn 'nc.sii/enviar-v2 :version 2})
          b-2 (est/abrir m/*alm* catalogo-2 (:empresa b))
          pedido-2 (get-in (m/ejecutar! b-2 :persona/ana :ventas/registrar-pedido
                                        {:cliente :cliente/ferreteria :producto :P-100 :cantidad [:cantidad 1 :un]
                                         :precio-unitario [:monto 50000 :CLP]})
                           [:salidas :pedido])
          f2 (est/leer b-2 (get-in (m/ejecutar! b-2 :persona/ana :ventas/emitir-factura
                                                (factura pedido-2 :monto [:monto 50000 :CLP]))
                                   [:salidas :factura]))]
      (is (= ["V1-1" 1] ((juxt (comp :envio :hecho/datos) (comp :tributario/envio-dte :hecho/reglas)) f1)))
      (is (= ["V2-2" 2] ((juxt (comp :envio :hecho/datos) (comp :tributario/envio-dte :hecho/reglas)) f2)))
      (is (= f1 (est/leer b-2 (:hecho/id f1))))
      (is (= (:contratos (:catalogo b)) (:contratos catalogo-2))))))
