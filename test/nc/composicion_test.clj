(ns nc.composicion-test
  "Etapa 11, E20: composición en profundidad y entre dominios. B quiere tener 30
  P-100 vendidos y despachados, y solo tiene 10. MARCO deduce los pasos de los
  requisitos de cada paso (D97), desde un objetivo de estado deseado (D98):
  vender, reservar lo que hay, abastecer el resto con la compra formal, recibir,
  reservar lo recibido y despachar. Ejecuta lo que puede y propone el resto. Una
  necesidad se cubre con dos compras, y una compra atiende dos (D99)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.esquema :as esq]
            [nc.estado :as est]
            [nc.marco-test :as m]
            [nc.planificador :as p]
            [nc.resolvedor :as r]
            [nc.sara :as sara]))

(use-fixtures :once m/con-almacen)

(defn- politica [id forma aplica-a cuerpo & {:as mas}]
  (merge {:politica/id id :politica/version 1 :politica/origen :empresa :politica/rige-desde (m/dias-desde-hoy -30)
          :politica/aplica-a aplica-a :politica/forma forma :politica/cuerpo cuerpo}
         mas))

(def politicas-de-b
  "Cómo trabaja B, además de la compra formal: los precios salen de sus listas,
  se reserva solo lo que hay en bodega y nadie reservó, se despacha solo lo
  reservado, y recibe la bodega. Los dos límites dicen con qué se cubre lo que
  falta (D97)."
  [(politica :empresa/precio-de-lista :sugiere :ventas/registrar-pedido
             '{:campo :entrada/precio-unitario :valor [:tabla :lista-precios entrada/producto]})
   (politica :empresa/precio-de-compra :sugiere :compras/emitir-orden
             '{:campo :entrada/precio-unitario :valor [:tabla :precios-compra entrada/producto]})
   (politica :empresa/reservar-lo-libre :limita :inventario/reservar
             '{:valor     entrada/cantidad
               :maximo    [:inventario/libre {:producto entrada/producto :bodega entrada/bodega}]
               :cubre-con {:operacion :compras/registrar-recepcion
                           :entradas  {:producto entrada/producto :bodega entrada/bodega :cantidad faltante}
                           :parcial   true}})
   (politica :empresa/despachar-lo-reservado :limita :ventas/despachar
             '{:valor     entrada/cantidad
               :maximo    [:suma [:-> cada :compromiso/saldo :cantidad]
                                 [:compromisos {:tipo :inventario/reservado :raiz caso/id :estado :activo}]]
               :cubre-con {:operacion :inventario/reservar
                           :entradas  {:entrega entrada/entrega :producto entrada/producto
                                       :bodega entrada/bodega :cantidad faltante}}})
   (politica :empresa/recibe-bodega :permite :compras/registrar-recepcion '{:quien [:rol :bodega]})])

(defn- tabla! [estado id precio]
  (alm/publicar! m/*alm* (:empresa estado) :tablas
                 {:tabla/id id :tabla/version 1 :tabla/rige-desde (m/dias-desde-hoy -30)
                  :tabla/filas [{:clave [:P-100] :valor [:monto precio :CLP]}]}))

(defn- empresa-b! []
  (let [b (m/empresa! (update m/empresa-b :politicas into politicas-de-b))]
    (alm/cargar-entidades! m/*alm* (:empresa b) [{:entidad/id :persona/pedro :entidad/tipo :persona
                                                  :persona/nombre "Pedro" :persona/roles #{:bodega}}])
    (tabla! b :lista-precios 45000)
    (tabla! b :precios-compra 30000)
    (m/ejecutar! b :persona/ana :ventas/alta-cliente {:id :cliente/ferreteria :nombre "Ferretería"
                                                      :cupo [:monto 100000000 :CLP]})
    ;; 10 P-100 en bodega: una orden bajo el umbral de la compra formal.
    (let [orden (-> (m/ejecutar! b :persona/ana :compras/emitir-orden (assoc m/orden-20 :cantidad [:cantidad 10 :un]))
                    :salidas :orden)]
      (m/ejecutar! b :persona/pedro :compras/registrar-recepcion
                   {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 10 :un]}))
    b))

(def treinta-despachados
  {:cumplir  :ventas/entrega-pendiente
   :entradas {:cliente :cliente/ferreteria :producto :P-100 :bodega :bodega/central
              :cantidad [:cantidad 30 :un]}})

(defn- ops [plan] (mapv :operacion (:pasos plan)))
(defn- estados [plan] (mapv :estado (:pasos plan)))
(defn- cantidades [plan] (mapv #(some-> % :entradas :cantidad second) (:pasos plan)))
(defn- falta [plan i] (set (map :id (get-in plan [:pasos i :falta]))))

(defn- existencia [estado]
  (get-in (alm/leer m/*alm* (:empresa estado) :saldos [:inventario/existencia {:producto :P-100 :bodega :bodega/central}])
          [:saldo/valor :cantidad]))

(deftest e20-composicion-en-profundidad
  (let [b    (empresa-b!)
        plan (p/planear b :persona/ana treinta-despachados)]
    (testing "el estado deseado se alcanza con la operación que cumple la entrega (D98)"
      (is (= :ventas/despachar (:operacion plan)))
      (is (= [] (:alternativas plan)))
      (is (= [:ventas/registrar-pedido :ventas/despachar] (ops plan))
          "sin pedido no hay caso: lo que exige el despacho se ve al registrarlo"))
    (let [plan (p/avanzar! b :persona/ana plan)]
      (testing "MARCO deduce en profundidad: vender, reservar lo que hay, abastecer 20 con la compra formal, recibir, reservar y despachar"
        (is (= [:ventas/registrar-pedido
                :inventario/reservar
                :compras/registrar-solicitud :compras/registrar-oferta :compras/registrar-oferta :compras/aprobar
                :compras/emitir-orden
                :compras/registrar-recepcion
                :inventario/reservar
                :ventas/despachar]
               (ops plan)))
        (is (= [nil 10 20 20 20 20 20 20 20 nil] (cantidades plan))
            "la reserva se divide en lo que cabe hoy y lo que espera la recepción"))
      (testing "ejecuta lo que Ana puede y propone el resto (D51)"
        (is (= [:ejecutado :ejecutado :ejecutado :propuesto :propuesto :propuesto
                :propuesto :propuesto :propuesto :propuesto]
               (estados plan)))
        (is (= #{:permiso :empresa-b/segregacion} (falta plan 5)))
        (is (contains? (falta plan 7) :permiso) "recibe la bodega, no Ana")
        (is (= [:cantidad 20 :un]
               (:faltante (first (filter #(= :empresa/despachar-lo-reservado (:id %)) (get-in plan [:pasos 9 :falta]))))))
        (is (= [:cantidad 10 :un] (existencia b)) "nada se despachó todavía"))
      (testing "SARA dice con qué se cubre lo que falta"
        (let [avisos (:avisos (sara/formulario b :persona/ana :ventas/despachar
                                               (merge (:entradas treinta-despachados)
                                                      (select-keys (:entradas plan) [:entrega]))))]
          (is (some #(re-find #"Faltan 20 un.*Se cubre con «Reservar»" (:texto %)) avisos))))
      (let [solicitud (get-in plan [:entradas :solicitud])
            oferta    (fn [proveedor precio]
                        (-> (m/ejecutar! b :persona/ana :compras/registrar-oferta
                                         {:solicitud solicitud :proveedor proveedor :producto :P-100
                                          :cantidad [:cantidad 20 :un] :precio-unitario [:monto precio :CLP]
                                          :vence (m/dias-desde-hoy 15)})
                            :salidas :oferta))
            _         (oferta :proveedor/acme 32000)
            elegida   (oferta :proveedor/beta 30000)
            _         (m/ejecutar! b :persona/luis :compras/aprobar {:solicitud solicitud :oferta elegida})
            plan      (p/avanzar! b :persona/ana plan {:proveedor :proveedor/beta :oferta elegida})]
        (testing "con las ofertas y la aprobación que otros registraron, Ana emite la orden"
          (is (= [:ejecutado :ejecutado :ejecutado :satisfecho :satisfecho :satisfecho
                  :ejecutado :propuesto :propuesto :propuesto]
                 (estados plan)))
          (is (= :extinguido (:compromiso/estado (est/leer b solicitud))) "la orden cubre la necesidad"))
        (let [plan (p/avanzar! b :persona/pedro plan)]
          (testing "la bodega recibe, y el mismo plan reserva lo recibido y despacha los 30"
            (is (= [:ejecutado :ejecutado :ejecutado :satisfecho :satisfecho :satisfecho
                    :ejecutado :ejecutado :ejecutado :ejecutado]
                   (estados plan)))
            (is (= :cumplido (:estado plan)))
            (is (= [:extinguido :cumplimiento]
                   ((juxt :compromiso/estado (comp :causa :compromiso/extincion))
                    (est/leer b (get-in plan [:entradas :entrega])))))
            (is (= [:cantidad 0 :un] (existencia b))))
          (testing "el camino queda como borrador, con las políticas que produjeron sus pasos (D54)"
            (is (= :camino/despachar (get-in plan [:borrador :camino/id])))
            (is (= #{:empresa/despachar-lo-reservado :empresa/reservar-lo-libre :empresa-b/compra-formal}
                   (set (keys (get-in plan [:borrador :camino/reglas])))))))))))

(deftest las-politicas-con-cobertura-se-validan-al-escribir
  (let [catalogo (:catalogo (empresa-b!))]
    (testing "las políticas de B son válidas, también lo que se cubre con `faltante` (D97)"
      (is (= [] (vec (mapcat #(esq/problemas-politica % catalogo) politicas-de-b)))))
    (testing "la cobertura nombra una operación que existe"
      (is (= [:operacion-desconocida]
             (map :problema (esq/problemas-politica (assoc-in (politicas-de-b 2) [:politica/cuerpo :cubre-con :operacion]
                                                              :compras/no-existe)
                                                    catalogo)))))
    (testing "un límite parcial mide una entrada, que es la que se divide"
      (is (= [:parcial-sin-entrada]
             (map :problema (esq/problemas-politica (assoc-in (politicas-de-b 2) [:politica/cuerpo :valor]
                                                              '[:+ entrada/cantidad [:cantidad 1 :un]])
                                                    catalogo)))))))

(deftest estado-deseado
  (let [b (empresa-b!)]
    (testing "un estado deseado sin operación que lo cumpla no tiene plan, y se dice por qué"
      (is (= :sin-camino (:estado (p/planear b :persona/ana {:cumplir :compras/pago-contra-factura :entradas {}})))
          "el pago a proveedor se reduce con una colección, no con una entrada: no es una cumplidora")
      (is (= [:ventas/despachar] (est/cumplidoras b :ventas/entrega-pendiente))))
    (testing "una necesidad la cumple la orden, que la reduce"
      (is (= [:compras/emitir-orden] (est/cumplidoras b :compras/necesidad))))
    (testing "una entrada la produce la operación que crea algo de su tipo; una entidad no se deduce"
      (is (= :ventas/registrar-pedido (est/productora b :entrega [:ref :ventas/entrega-pendiente])))
      (is (nil? (est/productora b :cliente [:ref :cliente]))
          "modificar cliente tiene la salida :cliente, pero no crea un cliente"))))

(deftest sin-cobertura-el-limite-prohibe
  (testing "un límite sin :cubre-con sigue prohibiendo, como en E12"
    (let [b (empresa-b!)
          sin (assoc-in (politicas-de-b 2) [:politica/cuerpo] '{:valor entrada/cantidad
                                                                 :maximo [:inventario/libre {:producto entrada/producto
                                                                                             :bodega entrada/bodega}]})
          b2 (m/empresa! {:politicas [sin]})
          entrega (fn [e] (-> (m/ejecutar! e :persona/ana :ventas/registrar-pedido
                                           {:cliente :cliente/ferreteria :producto :P-100 :cantidad [:cantidad 5 :un]
                                            :precio-unitario [:monto 45000 :CLP]})
                              :salidas :entrega))]
      (m/ejecutar! b2 :persona/ana :ventas/alta-cliente {:id :cliente/ferreteria :nombre "F" :cupo [:monto 1000000 :CLP]})
      (let [res (r/resolver b2 :persona/ana :inventario/reservar
                            {:entrega (entrega b2) :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 5 :un]})]
        (is (= :prohibido (:estado res))))
      (let [res (r/resolver b :persona/ana :inventario/reservar
                            {:entrega (entrega b) :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 15 :un]})]
        (is (= :bloqueado (:estado res)) "con cobertura, lo que excede queda pendiente, no prohibido")))))

(deftest necesidades-y-compras-de-muchas-a-muchas
  (let [a         (m/empresa! {})
        solicitar #(-> (m/ejecutar! a :persona/ana :compras/registrar-solicitud {:producto :P-100 :cantidad [:cantidad % :un]})
                       :salidas :solicitud)
        n1        (solicitar 20)
        n2        (solicitar 5)
        saldo     #(get-in (est/leer a %) [:compromiso/saldo :cantidad])]
    (testing "una orden por menos de lo pedido reduce la necesidad, sin extinguirla"
      (m/ejecutar! a :persona/ana :compras/emitir-orden (assoc m/orden-20 :cantidad [:cantidad 12 :un] :solicitud n1))
      (is (= [:activo [:cantidad 8 :un]] [(:compromiso/estado (est/leer a n1)) (saldo n1)])))
    (testing "una orden atiende dos necesidades, y con ella la primera queda cubierta por dos órdenes"
      (m/ejecutar! a :persona/ana :compras/emitir-orden
                   (assoc m/orden-20 :proveedor :proveedor/beta :cantidad [:cantidad 13 :un] :solicitudes [n1 n2]))
      (is (every? #(= [:extinguido :cumplimiento]
                      ((juxt :compromiso/estado (comp :causa :compromiso/extincion)) (est/leer a %)))
                  [n1 n2]))
      (let [ordenes (alm/buscar m/*alm* (:empresa a) :hechos {:hecho/tipo :compras/orden})]
        (is (= 2 (count (filter #(or (= n1 (get-in % [:hecho/datos :solicitud]))
                                     (some #{n1} (get-in % [:hecho/datos :solicitudes])))
                                ordenes))))))
    (testing "cada elemento de la colección debe ser una necesidad de la empresa (D65)"
      (let [res (r/resolver a :persona/ana :compras/emitir-orden (assoc m/orden-20 :solicitudes [n1 (random-uuid)]))]
        (is (= :bloqueado (:estado res)))
        (is (re-find #"No existe" (:problema (m/requisito res :entrada/solicitudes))))))))
