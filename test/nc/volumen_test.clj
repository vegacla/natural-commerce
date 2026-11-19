(ns nc.volumen-test
  "E26: dos empresas con un año de operación de un comercio pequeño (del orden de
  20.000 hechos cada una). Se mide el tiempo del resolvedor y de la consulta de
  estado, y que ninguna empresa se haga más lenta con los datos de la otra.

  Todo pasa por MARCO (ejecutar!), con bitácora: no hay carga directa al almacén.
  El año completo corre solo con NC_VOLUMEN (unos minutos); sin ella, la misma
  prueba corre con tres días, para que el código siga probado."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.bitacora :as bit]
            [nc.marco-test :as m]
            [nc.resolvedor :as r]))

(use-fixtures :once m/con-almacen)

(def ^:private completo? (some? (System/getenv "NC_VOLUMEN")))
(def ^:private dias (if completo? 300 3))

;; --- Un año de operación -----------------------------------------------------------

(defn- x! [e actor op entradas]
  (let [res (m/ejecutar! e actor op entradas)]
    (when-not (= :ejecutado (:estado res))
      (throw (ex-info (str op " no se ejecutó: " (:estado res)) {:op op :res (dissoc res :decision)})))
    (:salidas res)))

(defn- fecha [dias-desde-hoy] (m/dias-desde-hoy dias-desde-hoy))

(defn- venta! [e i]
  (x! e :persona/ana :ventas/venta-mostrador
      {:producto :P-100 :bodega :bodega/central :cantidad [:cantidad (inc (mod i 3)) :un]
       :precio-unitario [:monto 2000 :CLP]}))

(defn- compra-simple!
  "Orden, recepción, factura y pago, bajo el umbral de la compra formal de B."
  [e]
  (let [orden (:orden (x! e :persona/ana :compras/emitir-orden
                          (assoc m/orden-20 :cantidad [:cantidad 300 :un] :precio-unitario [:monto 1000 :CLP])))]
    (x! e :persona/ana :compras/registrar-recepcion
        {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 300 :un]})
    (x! e :persona/ana :compras/registrar-factura {:orden orden :monto [:monto 300000 :CLP] :vencimiento (fecha 30)})
    (x! e :persona/ana :compras/registrar-pago {:orden orden :monto [:monto 300000 :CLP]})
    orden))

(defn- compra-formal!
  "Sobre el umbral de B: solicitud, dos ofertas, aprobación de otra persona, orden,
  recepción, factura y pago."
  [e]
  (let [cantidad  [:cantidad 1000 :un]
        solicitud (:solicitud (x! e :persona/ana :compras/registrar-solicitud {:producto :P-100 :cantidad cantidad}))
        oferta    (fn [prov precio]
                    (:oferta (x! e :persona/ana :compras/registrar-oferta
                                 {:solicitud solicitud :proveedor prov :producto :P-100 :cantidad cantidad
                                  :precio-unitario [:monto precio :CLP] :vence (fecha 20)})))
        elegida   (oferta :proveedor/acme 1000)
        _         (oferta :proveedor/beta 1100)
        _         (x! e :persona/luis :compras/aprobar {:solicitud solicitud :oferta elegida})
        orden     (:orden (x! e :persona/ana :compras/emitir-orden
                              {:proveedor :proveedor/acme :producto :P-100 :cantidad cantidad
                               :precio-unitario [:monto 1000 :CLP] :solicitud solicitud :oferta elegida}))]
    (x! e :persona/ana :compras/registrar-recepcion
        {:orden orden :producto :P-100 :bodega :bodega/central :cantidad cantidad})
    (x! e :persona/ana :compras/registrar-factura {:orden orden :monto [:monto 1000000 :CLP] :vencimiento (fecha 30)})
    (x! e :persona/ana :compras/registrar-pago {:orden orden :monto [:monto 1000000 :CLP]})))

(defn- venta-a-credito!
  "Pedido, despacho, factura y cobro de un cliente."
  [e]
  (let [{:keys [pedido entrega]} (x! e :persona/ana :ventas/registrar-pedido
                                     {:cliente :cliente/ferreteria :producto :P-100 :cantidad [:cantidad 5 :un]
                                      :precio-unitario [:monto 2000 :CLP]})]
    (x! e :persona/ana :ventas/despachar
        {:entrega entrega :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 5 :un]})
    (x! e :persona/ana :ventas/emitir-factura
        {:pedido pedido :monto [:monto 10000 :CLP] :fecha (fecha 0) :vencimiento (fecha 30)})
    (x! e :persona/ana :ventas/registrar-cobro {:cliente :cliente/ferreteria :monto [:monto 10000 :CLP]})))

(defn- año!
  "`dias` días hábiles: 20 ventas en mesón al día y una compra simple a la semana.
  Con `formal?` (B), además una venta a crédito al día y una compra formal al mes."
  [e formal?]
  (dotimes [d dias]
    (dotimes [i 20] (venta! e i))
    (when (zero? (mod d 6)) (compra-simple! e))
    (when formal?
      (venta-a-credito! e)
      (when (zero? (mod d 25)) (compra-formal! e)))))

;; --- Medición ----------------------------------------------------------------------

(defn- mediana-ms
  "La mediana de `n` corridas de `f`, en milisegundos, tras `n` de calentamiento:
  con menos, la primera medición paga la compilación de la JVM."
  [n f]
  (dotimes [_ n] (f))
  (let [ts (sort (for [_ (range n)]
                   (let [t (System/nanoTime)] (f) (/ (- (System/nanoTime) t) 1e6))))]
    (nth ts (quot n 2))))

(defn- hechos [e] (count (alm/buscar m/*alm* (:empresa e) :hechos {})))

(defn- medir
  "Mediana de lo que se mide en `e`: el resolvedor en una venta y en una orden
  (con las políticas de la empresa), y la consulta de estado de `orden`."
  [e orden]
  {:hechos (hechos e)
   :resolver-venta (mediana-ms 25 #(r/resolver e :persona/ana :ventas/venta-mostrador
                                               {:producto :P-100 :bodega :bodega/central :cantidad [:cantidad 1 :un]
                                                :precio-unitario [:monto 2000 :CLP]}))
   :resolver-orden (mediana-ms 25 #(r/resolver e :persona/ana :compras/emitir-orden m/orden-20))
   :consulta       (mediana-ms 25 #(m/ejecutar! e :persona/ana :compras/consultar-estado {:orden orden}))})

(defn- no-crece?
  "Que `despues` no supere el doble de `antes` más 2 ms, medida por medida: el
  margen cubre el ruido de una JVM compartida, no un crecimiento con los datos."
  [antes despues]
  (every? (fn [[k t]] (<= (despues k) (+ 2 (* 2 t)))) (dissoc antes :hechos)))

(defn- fila [nombre med]
  (println (format "  %-34s %7d %10.1f %10.1f %10.1f" nombre (:hechos med)
                   (double (:resolver-venta med)) (double (:resolver-orden med)) (double (:consulta med)))))

(deftest e26-un-año-de-dos-empresas
  (let [a (m/empresa! {})
        b (m/empresa! m/empresa-b)
        _ (x! b :persona/ana :ventas/alta-cliente
              {:id :cliente/ferreteria :nombre "Ferretería" :cupo [:monto 100000000 :CLP]})
        orden-a (compra-simple! a)
        orden-b (compra-simple! b)
        a0 (medir a orden-a)
        b0 (medir b orden-b)
        t-a (System/nanoTime)
        _  (año! a false)
        t-a (/ (- (System/nanoTime) t-a) 1e9)
        a1 (medir a orden-a)
        b1 (medir b orden-b)
        consulta-a (m/ejecutar! a :persona/ana :compras/consultar-estado {:orden orden-a})
        t-b (System/nanoTime)
        _  (año! b true)
        t-b (/ (- (System/nanoTime) t-b) 1e9)
        a2 (medir a orden-a)
        b2 (medir b orden-b)]
    (println (str "E26 (" dias " días" (when-not completo? "; el año completo corre con NC_VOLUMEN") ")"))
    (println (format "  %-34s %7s %10s %10s %10s" "momento" "hechos" "res. venta" "res. orden" "consulta"))
    (fila "A vacía" a0)
    (fila "B vacía" b0)
    (fila "A con su año" a1)
    (fila "B vacía, con el año de A" b1)
    (fila "A con su año y el de B" a2)
    (fila "B con su año" b2)
    (println (format "  carga: A %.0f s, B %.0f s (todo por ejecutar!, con bitácora)" t-a t-b))
    (when completo?
      ;; Con tres días, los tiempos son ruido de la JVM que corre el resto de la
      ;; suite: solo se imprimen.
      (testing "ninguna empresa se hace más lenta con los datos de la otra"
        (is (no-crece? b0 b1) "B, con el año de A cargado")
        (is (no-crece? a1 a2) "A, con el año de B cargado")))
    (testing "y lo que A ve no cambia con los datos de B"
      (is (= (:salidas consulta-a)
             (:salidas (m/ejecutar! a :persona/ana :compras/consultar-estado {:orden orden-a}))))
      (is (= (hechos a) (+ 4 (* dias 20 3) (* 4 (count (filter #(zero? (mod % 6)) (range dias))))))))
    (testing "la bitácora cumple sus invariantes con el volumen"
      (let [t (System/nanoTime)]
        (is (= [] (bit/verificar m/*alm* (:empresa b))))
        (println (format "  verificar la bitácora de B: %.1f s" (/ (- (System/nanoTime) t) 1e9)))))))
