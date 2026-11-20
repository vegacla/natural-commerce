(ns nc.lineas-test
  "Etapa 15 (E28): documentos con varias líneas. Un pedido de tres productos se
  despacha en dos partes y se factura. Los contratos están en
  resources/lineas/ventas.edn, fuera del canon, como la cobertura (D103)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.bitacora :as bit]
            [nc.catalogo :as cat]
            [nc.ejecutor :as ej]
            [nc.marco-test :as m]
            [nc.resolvedor :as r]))

(use-fixtures :once m/con-almacen)

(def configuracion (cat/leer "lineas/ventas.edn"))

(def ^:private productos
  [{:entidad/id :P-200 :entidad/tipo :producto :producto/codigo "P-200" :producto/categoria :insumos :producto/unidad :un}
   {:entidad/id :P-300 :entidad/tipo :producto :producto/codigo "P-300" :producto/categoria :insumos :producto/unidad :un}])

(defn- x! [e op entradas]
  (let [res (m/ejecutar! e :persona/ana op entradas)]
    (is (= :ejecutado (:estado res)) (str op " " (pr-str (select-keys res [:requisitos :estado]))))
    (:salidas res)))

(defn- comprar!
  "Existencia en la bodega central: una orden y su recepción."
  [e producto cantidad precio]
  (let [orden (:orden (x! e :compras/emitir-orden {:proveedor :proveedor/acme :producto producto
                                                  :cantidad [:cantidad cantidad :un]
                                                  :precio-unitario [:monto precio :CLP]}))]
    (x! e :compras/registrar-recepcion {:orden orden :producto producto :bodega :bodega/central
                                        :cantidad [:cantidad cantidad :un]})))

(defn- empresa! []
  (let [e (m/empresa! configuracion)]
    (alm/cargar-entidades! m/*alm* (:empresa e) productos)
    (x! e :ventas/alta-cliente {:id :cliente/ferreteria :nombre "Ferretería" :cupo [:monto 10000000 :CLP]})
    (comprar! e :P-100 20 30000)
    (comprar! e :P-200 20 5000)
    (comprar! e :P-300 5 10000)
    e))

(defn- existencia [e producto]
  (:saldo/valor (alm/leer m/*alm* (:empresa e) :saldos [:inventario/existencia {:producto producto :bodega :bodega/central}])))

(defn- compromiso [e id] (first (alm/buscar m/*alm* (:empresa e) :compromisos {:compromiso/id id})))

(def pedido
  {:cliente :cliente/ferreteria
   :lineas  [{:producto :P-100 :cantidad [:cantidad 5 :un] :precio-unitario [:monto 50000 :CLP]}
             {:producto :P-200 :cantidad [:cantidad 10 :un] :precio-unitario [:monto 8000 :CLP]}
             {:producto :P-300 :cantidad [:cantidad 2 :un] :precio-unitario [:monto 15000 :CLP]}]})

(deftest e28-un-pedido-de-tres-lineas
  (let [e (empresa!)]
    (testing "el contrato revisa las referencias de cada línea (D65)"
      (let [res (r/resolver e :persona/ana :lineas/registrar-pedido (assoc-in pedido [:lineas 1 :producto] :P-999))]
        (is (not= :listo (:estado res)))
        (is (re-find #"P-999" (str (:problema (m/requisito res :entrada/lineas)))))))
    (let [{:keys [pedido entregas]} (x! e :lineas/registrar-pedido pedido)
          por-producto (into {} (for [c entregas] [(get-in c [:compromiso/objeto :producto]) c]))
          cobro (fn [estado] (first (alm/buscar m/*alm* (:empresa e) :compromisos
                                                {:compromiso/tipo :lineas/por-cobrar :compromiso/estado estado})))]
      (testing "el pedido es un hecho con sus tres líneas"
        (is (= 3 (count (get-in (alm/leer m/*alm* (:empresa e) :hechos pedido) [:hecho/datos :lineas])))))
      (testing "cada línea tiene su entrega pendiente, en el mismo caso"
        (is (= #{:P-100 :P-200 :P-300} (set (keys por-producto))))
        (is (= {:cantidad [:cantidad 10 :un]} (:compromiso/saldo (por-producto :P-200))))
        (is (= 1 (count (set (map :compromiso/raiz entregas))))))
      (testing "lo que se cobra es uno, por el total, latente hasta la factura"
        (is (= {:monto [:monto 360000 :CLP]} (:compromiso/saldo (cobro :latente)))))
      (testing "un despacho de dos líneas reduce cada entrega en lo suyo y mueve la existencia de cada producto"
        (x! e :lineas/despachar {:bodega :bodega/central
                                 :lineas [{:entrega (:compromiso/id (por-producto :P-100)) :cantidad [:cantidad 5 :un]}
                                          {:entrega (:compromiso/id (por-producto :P-200)) :cantidad [:cantidad 4 :un]}]})
        (is (= :extinguido (:compromiso/estado (compromiso e (:compromiso/id (por-producto :P-100))))))
        (is (= {:cantidad [:cantidad 6 :un]} (:compromiso/saldo (compromiso e (:compromiso/id (por-producto :P-200))))))
        (is (= {:cantidad [:cantidad 2 :un]} (:compromiso/saldo (compromiso e (:compromiso/id (por-producto :P-300))))))
        (is (= {:cantidad [:cantidad 15 :un] :costo [:monto 450000 :CLP]} (existencia e :P-100)))
        (is (= {:cantidad [:cantidad 16 :un] :costo [:monto 80000 :CLP]} (existencia e :P-200))))
      (testing "cada línea del despacho guarda su producto y su costo"
        (let [d (first (alm/buscar m/*alm* (:empresa e) :hechos {:hecho/tipo :lineas/despacho}))]
          (is (= [[:P-100 [:monto 30000 :CLP]] [:P-200 [:monto 5000 :CLP]]]
                 (mapv (juxt :producto :costo-unitario) (get-in d [:hecho/datos :lineas]))))))
      (testing "una línea que saca más de lo que hay se prohíbe, con la razón, y no se escribe nada"
        (let [res (ej/ejecutar! e :persona/ana :lineas/despachar
                                {:bodega :bodega/central
                                 :lineas [{:entrega (:compromiso/id (por-producto :P-200)) :cantidad [:cantidad 6 :un]}
                                          {:entrega (:compromiso/id (por-producto :P-300)) :cantidad [:cantidad 9 :un]}]}
                                {:confirmado true})]
          (is (= :prohibido (:estado res)))
          (is (some #(= "Una línea saca más de lo que hay de su producto en la bodega" (:razon %)) (:requisitos res)))
          (is (= {:cantidad [:cantidad 6 :un]} (:compromiso/saldo (compromiso e (:compromiso/id (por-producto :P-200))))))))
      (testing "el segundo despacho completa el pedido: ninguna entrega queda pendiente"
        (x! e :lineas/despachar {:bodega :bodega/central
                                 :lineas [{:entrega (:compromiso/id (por-producto :P-200)) :cantidad [:cantidad 6 :un]}
                                          {:entrega (:compromiso/id (por-producto :P-300)) :cantidad [:cantidad 2 :un]}]})
        (is (every? #(= :extinguido (:compromiso/estado (compromiso e (:compromiso/id %)))) entregas)))
      (testing "la factura lleva las líneas con su monto, y lo que se cobra pasa a exigible por el total"
        (let [{:keys [factura]} (x! e :lineas/emitir-factura {:pedido pedido :fecha (java.util.Date.)
                                                              :vencimiento (m/dias-desde-hoy 30)})
              f (alm/leer m/*alm* (:empresa e) :hechos factura)]
          (is (= [[:monto 250000 :CLP] [:monto 80000 :CLP] [:monto 30000 :CLP]]
                 (mapv :monto (get-in f [:hecho/datos :lineas]))))
          (is (= [:monto 360000 :CLP] (get-in f [:hecho/datos :monto])))
          (is (nil? (cobro :latente)))
          (is (= {:monto [:monto 360000 :CLP]} (:compromiso/saldo (cobro :activo))))))
      (testing "todo pasó por MARCO y la bitácora cumple sus invariantes"
        (is (= [] (bit/verificar m/*alm* (:empresa e))))))))

(deftest el-catalogo-con-lineas-es-valido
  (is (= [] (vec (cat/problemas (cat/de-empresa configuracion))))))

(deftest cada-solo-dentro-de-cada
  (testing "un contrato que lee `cada` después de su :cada no es válido: fuera, no hay elemento (D113)"
    (let [contrato {:op/id :lineas/mal :op/capacidad :ventas :op/tipo :efecto
                    :op/entradas [:map [:lineas [:vector [:map [:producto [:ref :producto]]]]]]
                    :op/efectos '[[:registra-hecho :lineas/pedido entrada]
                                  [:cada entrada/lineas [:abre-compromiso {:tipo :lineas/x :deudor empresa :acreedor empresa
                                                                           :objeto {:producto cada/producto}}]]
                                  [:abre-compromiso {:tipo :lineas/y :deudor empresa :acreedor empresa
                                                     :objeto {:producto cada/producto}}]]}
          problemas (cat/problemas (cat/de-empresa (update configuracion :contratos conj contrato)))]
      (is (= 1 (count (filter #(= :lineas/mal (:de %)) problemas))))
      (is (re-find #"cada" (pr-str (filter #(= :lineas/mal (:de %)) problemas)))))))
