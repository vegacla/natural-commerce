(ns nc.cobertura-test
  "Etapa 13, sección 7 del prototipo ampliado: las 74 operaciones del análisis
  (docs/analisis/operaciones.csv), cada una expresada sin primitivos, verbos ni
  formas nuevos. Las que el canon no tiene están en resources/cobertura/ como
  contratos, y cada una se ejecuta aquí una vez, por MARCO y con bitácora."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.catalogo :as cat]
            [nc.estado :as est]
            [nc.marco-test :as m]))

(use-fixtures :once m/con-almacen)

(def areas ["compras" "inventario" "ventas" "finanzas" "costos" "produccion" "personal"])

(defn cobertura []
  (apply merge-with into (for [a areas :when (io/resource (str "cobertura/" a ".edn"))]
                           (update-vals (cat/leer (str "cobertura/" a ".edn")) vec))))

;; --- El análisis -------------------------------------------------------------------

(defn analisis
  "Las filas del CSV: área, operación, verbos y formas."
  []
  (let [[_ & filas] (str/split-lines (slurp (io/file "docs/analisis/operaciones.csv")))]
    (for [f filas :let [[area op _ verbos formas tensiones] (str/split f #";" -1)]]
      {:area area :operacion op
       :verbos (set (remove #{"-" ""} (str/split verbos #"\|")))
       :formas (set (remove #{"-" ""} (str/split formas #"\|")))
       :tensiones (set (remove #{""} (str/split tensiones #"\|")))})))

;; --- Ejecutar un guion -------------------------------------------------------------

(defn- resolver-salidas
  "Reemplaza [:de paso clave] por la salida `clave` del paso ya ejecutado."
  [salidas x]
  (cond
    (and (vector? x) (= :de (first x))) (get-in salidas [(second x) (nth x 2)])
    (map? x)    (update-vals x #(resolver-salidas salidas %))
    (vector? x) (mapv #(resolver-salidas salidas %) x)
    :else x))

(defn ejecutar-guion!
  "Ejecuta cada paso [nombre actor op entradas]; devuelve {nombre resultado}."
  [e pasos]
  (reduce (fn [hechos [nombre actor op entradas]]
            (let [res (m/ejecutar! e actor op (resolver-salidas (update-vals hechos :salidas) entradas))]
              (assoc hechos nombre (assoc res :op op))))
          {} pasos))

;; --- Las 74 operaciones ------------------------------------------------------------

(def mapa
  "Cada operación del análisis y cómo se expresa: con contratos (del canon o de
  la cobertura), o con una política o un concepto sobre otras operaciones."
  {"Alta o cambio de proveedor"                    {:contratos [:compras/alta-proveedor :compras/modificar-proveedor]}
   "Registrar solicitud de compra"                 {:contratos [:compras/registrar-solicitud]}
   "Solicitar cotizacion a proveedores"            {:contratos [:compras/solicitar-cotizacion]}
   "Registrar oferta de proveedor"                 {:contratos [:compras/registrar-oferta]}
   "Aprobar o rechazar compra"                     {:contratos [:compras/aprobar]}
   "Emitir orden de compra"                        {:contratos [:compras/emitir-orden]}
   "Firmar contrato marco con proveedor"           {:contratos [:compras/firmar-contrato-marco]}
   "Liberar pedido contra contrato marco"          {:contratos [:compras/liberar-contra-marco]}
   "Registrar recepcion parcial o total"           {:contratos [:compras/registrar-recepcion]}
   "Devolver mercaderia a proveedor"               {:contratos [:compras/devolver-a-proveedor]}
   "Registrar factura de proveedor (DTE recibido)" {:contratos [:compras/registrar-factura]}
   "Aceptar o reclamar DTE recibido"               {:contratos [:compras/acusar-dte]}
   "Cerrar orden con saldo pendiente"              {:contratos [:compras/cerrar-orden]}
   "Registrar nota de credito de proveedor"        {:contratos [:compras/registrar-nota-credito-proveedor]}
   "Alta de producto"                              {:contratos [:inventario/alta-producto :inventario/modificar-producto]}
   "Traspaso entre bodegas"                        {:contratos [:inventario/traspasar]}
   "Reservar stock para pedido o produccion"       {:contratos [:inventario/reservar]}
   "Ajuste por toma de inventario"                 {:contratos [:inventario/ajustar]}
   "Consumo interno"                               {:contratos [:inventario/consumir]}
   "Valorizar inventario (costo promedio o FIFO)"  {:concepto :inventario/costo-unitario :escenario "E13, E22"}
   "Consultar disponibilidad"                      {:concepto :inventario/disponible :escenario "E12"}
   "Reposicion automatica por stock minimo"        {:politica :inicia :escenario "E19"}
   "Bloquear lote o poner en cuarentena"           {:contratos [:inventario/alta-lote :inventario/poner-en-cuarentena]}
   "Alta o cambio de cliente"                      {:contratos [:ventas/alta-cliente :ventas/modificar-cliente :ventas/desactivar-cliente]}
   "Asignar cupo de credito a cliente"             {:contratos [:ventas/modificar-cliente] :concepto :credito/cupo-disponible :escenario "E11, E12"}
   "Emitir cotizacion a cliente"                   {:contratos [:ventas/emitir-cotizacion]}
   "Registrar pedido de cliente"                   {:contratos [:ventas/registrar-pedido]}
   "Determinar precio y descuentos"                {:politica :fija :escenario "E12"}
   "Bloquear y liberar pedido por credito"         {:politica :limita :escenario "E12; liberar es la cobertura de D97"}
   "Despachar con guia de despacho (DTE 52)"       {:contratos [:ventas/despachar]}
   "Facturar (DTE 33/34)"                          {:contratos [:ventas/emitir-factura]}
   "Venta de mostrador con boleta (DTE 39)"        {:contratos [:ventas/venta-mostrador]}
   "Emitir nota de credito (DTE 61)"               {:contratos [:ventas/emitir-nota-credito]}
   "Emitir nota de debito (DTE 56)"                {:contratos [:ventas/emitir-nota-debito]}
   "Recibir devolucion de cliente"                 {:contratos [:ventas/recibir-devolucion]}
   "Recibir anticipo de cliente"                   {:contratos [:ventas/recibir-anticipo]}
   "Calcular comision de vendedor"                 {:contratos [:ventas/registrar-comision]}
   "Mantener plan de cuentas"                      {:contratos [:contabilidad/alta-cuenta :contabilidad/modificar-cuenta :contabilidad/desactivar-cuenta]}
   "Registrar asiento manual"                      {:contratos [:contabilidad/registrar-asiento]}
   "Determinar cuentas contables automaticamente"  {:politica :fija :escenario "E14"}
   "Registrar cobro"                               {:contratos [:ventas/registrar-cobro]}
   "Aplicar un cobro o pago a varios documentos"   {:contratos [:ventas/registrar-cobro] :escenario "E15"}
   "Registrar pago a proveedor"                    {:contratos [:compras/registrar-pago]}
   "Proponer pagos por vencimiento"                {:politica :inicia :escenario "E19 (propuesta, D40)"}
   "Conciliar cartola bancaria"                    {:contratos [:finanzas/conciliar-cartola]}
   "Ceder factura a factoring"                     {:contratos [:finanzas/ceder-factura]}
   "Castigar deuda incobrable"                     {:contratos [:finanzas/castigar-deuda]}
   "Reajustar por UF o diferencia de cambio"       {:contratos [:finanzas/reajustar]}
   "Registrar provision de fin de mes con reverso" {:contratos [:contabilidad/registrar-provision :contabilidad/reversar-provision]}
   "Cerrar periodo contable"                       {:contratos [:contabilidad/cerrar-periodo]}
   "Reabrir periodo contable"                      {:contratos [:contabilidad/reabrir-periodo]}
   "Preparar declaracion mensual (F29)"            {:contratos [:tributario/preparar-declaracion-mensual]}
   "Cuadrar registro de compras y ventas con SII"  {:contratos [:tributario/cuadrar-registro]}
   "Alta de activo fijo"                           {:contratos [:finanzas/alta-activo]}
   "Depreciar activos"                             {:contratos [:finanzas/depreciar]}
   "Dar de baja activo fijo"                       {:contratos [:finanzas/dar-de-baja-activo]}
   "Aprobar presupuesto por cuenta o centro"       {:contratos [:finanzas/aprobar-presupuesto]}
   "Comprometer presupuesto con una orden"         {:contratos [:finanzas/comprometer-presupuesto]}
   "Mantener centros de costo"                     {:contratos [:costos/alta-centro :costos/modificar-centro :costos/desactivar-centro]}
   "Imputar costo a centro"                        {:contratos [:costos/imputar]}
   "Repartir costos periodicamente"                {:contratos [:costos/repartir]}
   "Calcular costo estandar de producto"           {:contratos [:costos/fijar-costo-estandar]}
   "Analizar rentabilidad"                         {:contratos [:costos/analizar-rentabilidad]}
   "Mantener lista de materiales"                  {:contratos [:produccion/alta-lista-materiales :produccion/modificar-lista-materiales]}
   "Mantener ruta de fabricacion"                  {:contratos [:produccion/alta-ruta :produccion/modificar-ruta]}
   "Planificar necesidades de materiales (MRP)"    {:politica :inicia :escenario "E20 (deducción, D97) y E19"}
   "Liberar orden de fabricacion"                  {:contratos [:produccion/liberar-orden]}
   "Consumir componentes"                          {:contratos [:produccion/consumir-componentes]}
   "Notificar avance de operacion"                 {:contratos [:produccion/notificar-avance]}
   "Recibir producto terminado"                    {:contratos [:produccion/recibir-terminado]}
   "Liquidar orden y variaciones"                  {:contratos [:produccion/liquidar-orden]}
   "Firmar contrato de trabajo"                    {:contratos [:personal/firmar-contrato]}
   "Calcular liquidacion de sueldo"                {:contratos [:personal/liquidar-sueldo]}
   "Registrar vacaciones"                          {:contratos [:personal/registrar-vacaciones]}})

(def ^:private codigo-verbo
  {:crea-entidad "ce" :modifica-entidad "me" :desactiva-entidad "de" :registra-hecho "rh" :revierte-hecho "rv"
   :abre-compromiso "ac" :reduce-compromiso "rc" :extingue-compromiso "ec" :transfiere-compromiso "tc"
   :modifica-compromiso "mc"})

(def ^:private formas #{:exige :prohibe :limita :sugiere :fija :exime :inicia :permite})

(defn verbos-de
  "Los verbos que usa un contrato, con los códigos del análisis."
  [contrato]
  (set (keep #(when (vector? %) (codigo-verbo (first %)))
             (tree-seq vector? seq (vec (:op/efectos contrato))))))

(defn- catalogo [] (cat/de-empresa (cobertura)))

(deftest las-74-tienen-expresion
  (let [filas (analisis)
        c     (catalogo)]
    (testing "el catálogo con la cobertura es válido: ningún verbo, forma ni primitivo nuevo"
      (is (= [] (vec (cat/problemas c)))))
    (testing "cada fila del análisis está en el mapa, y el mapa no tiene otras"
      (is (= 74 (count filas)))
      (is (= (set (map :operacion filas)) (set (keys mapa)))))
    (testing "cada contrato y cada concepto del mapa existen"
      (is (= [] (vec (for [[_ {:keys [contratos]}] mapa, op contratos
                           :when (not (get-in c [:contratos op]))]
                       op))))
      (is (= [] (vec (for [[_ {:keys [concepto]}] mapa
                           :when (and concepto (not (get-in c [:conceptos concepto])))]
                       concepto)))))
    (testing "los diez verbos se usan"
      (is (= (set (vals codigo-verbo))
             (set (mapcat verbos-de (vals (:contratos c)))))))
    (testing "las ocho formas de política se usan en algún escenario"
      (let [usadas (set (for [f (concat (file-seq (io/file "test")) (file-seq (io/file "resources")))
                              :when (.isFile f)
                              [_ forma] (re-seq #":politica/forma\s+:([a-z]+)" (slurp f))]
                          (keyword forma)))
            ;; Las pruebas de etapas 8 a 11 arman políticas con (politica id forma ...).
            en-ayudas (set (for [f (file-seq (io/file "test")) :when (.isFile f)
                                 [_ forma] (re-seq #"\(politica\s+:\S+\s+:([a-z]+)" (slurp f))]
                             (keyword forma)))]
        (is (= formas (set/intersection formas (into usadas en-ayudas))))))
    (let [distintas (for [{:keys [operacion verbos]} filas
                          :let [{:keys [contratos]} (mapa operacion)
                                usados (set (mapcat #(verbos-de (get-in c [:contratos %])) contratos))]
                          :when (and (seq contratos) (not= verbos usados))]
                      [operacion (vec (sort verbos)) (vec (sort usados))])]
      (println "Cobertura:" (count (filter (comp seq :contratos val) mapa)) "filas con contratos;"
               (count distintas) "con verbos distintos del análisis (análisis → contratos):")
      (doseq [d distintas] (println "  " (pr-str d))))))

;; --- Cada contrato nuevo se ejecuta ------------------------------------------------

(defn- un [n] [:cantidad n :un])
(defn- clp [n] [:monto n :CLP])

(def guion
  "Un recorrido por los contratos de la cobertura, en el orden en que una empresa
  los usaría. [:de paso salida] toma una salida de un paso anterior."
  (let [d m/dias-desde-hoy]
    [;; Compras
     [:prov   :persona/ana :compras/alta-proveedor {:id :proveedor/gama :nombre "Gama"}]
     [:modp   :persona/ana :compras/modificar-proveedor {:proveedor :proveedor/gama :nombre "Gama SpA"}]
     [:sol    :persona/ana :compras/registrar-solicitud {:producto :P-100 :cantidad (un 10)}]
     [:cot    :persona/ana :compras/solicitar-cotizacion {:solicitud [:de :sol :solicitud] :proveedor :proveedor/acme}]
     [:marco  :persona/ana :compras/firmar-contrato-marco {:proveedor :proveedor/acme :producto :P-100 :cantidad (un 100)
                                                          :precio-unitario (clp 900) :hasta (d 300)}]
     [:lib    :persona/ana :compras/liberar-contra-marco {:marco [:de :marco :marco] :cantidad (un 30)}]
     [:rec    :persona/ana :compras/registrar-recepcion {:orden [:de :lib :orden] :producto :P-100 :bodega :bodega/central
                                                        :cantidad (un 20)}]
     [:dev    :persona/ana :compras/devolver-a-proveedor {:orden [:de :lib :orden] :bodega :bodega/central :cantidad (un 2)}]
     [:fac    :persona/ana :compras/registrar-factura {:orden [:de :lib :orden] :monto (clp 27000) :vencimiento (d 30)}]
     [:acuse  :persona/ana :compras/acusar-dte {:factura [:de :fac :factura] :resultado :aceptado}]
     [:ncp    :persona/ana :compras/registrar-nota-credito-proveedor {:orden [:de :lib :orden] :monto (clp 1800)}]
     [:cierre :persona/ana :compras/cerrar-orden {:orden [:de :lib :orden]}]
     ;; Inventario
     [:p200   :persona/ana :inventario/alta-producto {:id :P-200 :codigo "P-200" :nombre "Tornillo" :unidad :un}]
     [:modpr  :persona/ana :inventario/modificar-producto {:producto :P-200 :nombre "Tornillo 6 mm"}]
     [:ajuste :persona/ana :inventario/ajustar {:producto :P-200 :bodega :bodega/central :diferencia (un 50)
                                                :costo-unitario (clp 500)}]
     [:trasp  :persona/ana :inventario/traspasar {:producto :P-200 :desde :bodega/central :hacia :bodega/norte
                                                  :cantidad (un 10)}]
     [:consu  :persona/ana :inventario/consumir {:producto :P-200 :bodega :bodega/central :cantidad (un 5)}]
     [:lote   :persona/ana :inventario/alta-lote {:id :lote/l1 :producto :P-200}]
     [:cuar   :persona/ana :inventario/poner-en-cuarentena {:lote :lote/l1}]
     ;; Ventas
     [:cli    :persona/ana :ventas/alta-cliente {:id :cliente/ferreteria :nombre "Ferretería" :cupo (clp 10000000)}]
     [:cotv   :persona/ana :ventas/emitir-cotizacion {:cliente :cliente/ferreteria :producto :P-200 :cantidad (un 4)
                                                      :precio-unitario (clp 1000) :vence (d 15)}]
     [:ped    :persona/ana :ventas/registrar-pedido {:cliente :cliente/ferreteria :producto :P-200 :cantidad (un 4)
                                                     :precio-unitario (clp 1000)}]
     [:facv   :persona/ana :ventas/emitir-factura {:pedido [:de :ped :pedido] :monto (clp 4000) :fecha (d 0)
                                                   :vencimiento (d 30)}]
     [:nd     :persona/ana :ventas/emitir-nota-debito {:factura [:de :facv :factura] :cliente :cliente/ferreteria
                                                       :monto (clp 500)}]
     [:devc   :persona/ana :ventas/recibir-devolucion {:cliente :cliente/ferreteria :producto :P-200 :bodega :bodega/central
                                                       :cantidad (un 1) :precio-unitario (clp 1000) :costo-unitario (clp 500)}]
     [:antic  :persona/ana :ventas/recibir-anticipo {:cliente :cliente/ferreteria :monto (clp 20000)}]
     [:comis  :persona/ana :ventas/registrar-comision {:vendedor :persona/luis :base (clp 4000) :monto (clp 200)}]
     [:mostr  :persona/ana :ventas/venta-mostrador {:producto :P-200 :bodega :bodega/central :cantidad (un 2)
                                                    :precio-unitario (clp 1200)}]
     ;; Finanzas, contabilidad y tributario
     [:caja   :persona/ana :contabilidad/alta-cuenta {:id :cuenta/caja :nombre "Caja" :naturaleza :activo}]
     [:gasto  :persona/ana :contabilidad/alta-cuenta {:id :cuenta/gastos :nombre "Gastos" :naturaleza :resultado}]
     [:modc   :persona/ana :contabilidad/modificar-cuenta {:cuenta :cuenta/gastos :nombre "Gastos generales"}]
     [:asien  :persona/ana :contabilidad/registrar-asiento {:glosa "Compra de útiles" :debe :cuenta/gastos
                                                           :haber :cuenta/caja :monto (clp 15000)}]
     [:vieja  :persona/ana :contabilidad/alta-cuenta {:id :cuenta/vieja :nombre "En desuso" :naturaleza :activo}]
     [:desc   :persona/ana :contabilidad/desactivar-cuenta {:cuenta :cuenta/vieja}]
     [:conc   :persona/ana :finanzas/conciliar-cartola {:cuenta :cuenta/caja :saldo-banco (clp 985000) :fecha (d 0)}]
     [:castig :persona/ana :finanzas/castigar-deuda {:cobro [:de :nd :cobro]}]
     [:reaj   :persona/ana :finanzas/reajustar {:indice :uf :monto (clp 1200) :fecha (d 0)}]
     [:prov1  :persona/ana :contabilidad/registrar-provision {:glosa "Vacaciones" :monto (clp 50000) :fecha (d 0)}]
     [:rever  :persona/ana :contabilidad/reversar-provision {:provision [:de :prov1 :provision]}]
     [:f29    :persona/ana :tributario/preparar-declaracion-mensual {:mes (d 0) :debito (clp 760) :credito (clp 342)}]
     [:rcv    :persona/ana :tributario/cuadrar-registro {:mes (d 0) :diferencia (clp 0)}]
     [:activo :persona/ana :finanzas/alta-activo {:id :activo/camioneta :nombre "Camioneta" :valor (clp 12000000)}]
     [:depre  :persona/ana :finanzas/depreciar {:activo :activo/camioneta :monto (clp 200000)}]
     [:baja   :persona/ana :finanzas/dar-de-baja-activo {:activo :activo/camioneta}]
     [:presu  :persona/ana :finanzas/aprobar-presupuesto {:id :presupuesto/gastos-2026 :cuenta :cuenta/gastos
                                                          :monto (clp 1000000)}]
     [:compp  :persona/ana :finanzas/comprometer-presupuesto {:presupuesto :presupuesto/gastos-2026
                                                              :orden [:de :lib :orden] :monto (clp 27000)}]
     [:cerrar :persona/ana :contabilidad/cerrar-periodo {:fecha (d -40)}]
     [:reabre :persona/ana :contabilidad/reabrir-periodo {:periodo [:de :cerrar :periodo]}]
     ;; Costos
     [:cc1    :persona/ana :costos/alta-centro {:id :centro/ventas :nombre "Ventas"}]
     [:cc2    :persona/ana :costos/alta-centro {:id :centro/administracion :nombre "Administración"}]
     [:modcc  :persona/ana :costos/modificar-centro {:centro :centro/administracion :nombre "Adm. y finanzas"}]
     [:imput  :persona/ana :costos/imputar {:centro :centro/administracion :monto (clp 90000) :glosa "Arriendo"}]
     [:repar  :persona/ana :costos/repartir {:desde :centro/administracion :hacia :centro/ventas :monto (clp 30000)}]
     [:cc3    :persona/ana :costos/alta-centro {:id :centro/viejo :nombre "Cerrado"}]
     [:desacc :persona/ana :costos/desactivar-centro {:centro :centro/viejo}]
     [:estan  :persona/ana :costos/fijar-costo-estandar {:producto :P-200 :costo (clp 480) :desde (d 30)}]
     [:rent   :persona/ana :costos/analizar-rentabilidad {:producto :P-200}]
     ;; Producción
     [:p300   :persona/ana :inventario/alta-producto {:id :P-300 :codigo "P-300" :nombre "Repisa" :unidad :un}]
     [:lista  :persona/ana :produccion/alta-lista-materiales {:id :lista/repisa :producto :P-300 :componente :P-200
                                                             :cantidad 2}]
     [:modl   :persona/ana :produccion/modificar-lista-materiales {:lista :lista/repisa :cantidad 3}]
     [:ruta   :persona/ana :produccion/alta-ruta {:id :ruta/repisa :producto :P-300 :operaciones [:cantidad 2 :op]}]
     [:modr   :persona/ana :produccion/modificar-ruta {:ruta :ruta/repisa :operaciones [:cantidad 3 :op]}]
     [:of     :persona/ana :produccion/liberar-orden {:lista :lista/repisa :ruta :ruta/repisa :cantidad (un 4)}]
     [:cons   :persona/ana :produccion/consumir-componentes {:orden [:de :of :orden] :bodega :bodega/central
                                                            :cantidad (un 12)}]
     [:avan   :persona/ana :produccion/notificar-avance {:orden [:de :of :orden] :operaciones [:cantidad 3 :op]}]
     [:term   :persona/ana :produccion/recibir-terminado {:orden [:de :of :orden] :bodega :bodega/central
                                                         :cantidad (un 3) :costo-unitario (clp 1600)}]
     [:liq    :persona/ana :produccion/liquidar-orden {:orden [:de :of :orden] :variacion (clp -1600)}]
     ;; Personal
     [:ctr    :persona/ana :personal/firmar-contrato {:trabajador :persona/luis :sueldo (clp 900000) :desde (d 0)
                                                      :vacaciones [:cantidad 15 :dia]}]
     [:sueldo :persona/ana :personal/liquidar-sueldo {:contrato [:de :ctr :contrato]}]
     [:vac    :persona/ana :personal/registrar-vacaciones {:saldo [:de :ctr :vacaciones] :dias [:cantidad 5 :dia]}]]))

(defn- compromiso [e id] (est/leer e id))
(defn- de-origen [e tipo origen]
  (first (alm/buscar m/*alm* (:empresa e) :compromisos {:compromiso/tipo tipo :compromiso/origen origen})))
(defn- saldo [e concepto clave] (:saldo/valor (alm/leer m/*alm* (:empresa e) :saldos [concepto clave])))
(defn- existencia [e producto bodega] (:cantidad (saldo e :inventario/existencia {:producto producto :bodega bodega})))
(defn- entidad [e id] (first (alm/buscar m/*alm* (:empresa e) :entidades {:entidad/id id})))

(deftest los-contratos-nuevos-se-ejecutan
  (let [e (m/empresa! (cobertura))
        _ (alm/cargar-entidades! m/*alm* (:empresa e)
                                 [{:entidad/id :bodega/norte :entidad/tipo :bodega :bodega/nombre "Norte"}])
        r (ejecutar-guion! e guion)
        s #(get-in r [%1 :salidas %2])]
    (testing "cada paso se ejecuta (o, si es consulta, se consulta)"
      (is (= [] (vec (for [[nombre _ op] guion
                           :let [estado (get-in r [nombre :estado])]
                           :when (not (#{:ejecutado :consultado} estado))]
                       [nombre op estado (vec (remove #(#{:satisfecho :opcional} (:estado %))
                                                      (get-in r [nombre :requisitos])))])))))
    (testing "cada contrato de la cobertura está en el guion"
      (is (= #{} (set/difference (set (map :op/id (:contratos (cobertura))))
                                 (set (map #(nth % 2) guion))))))
    (testing "compras: el contrato marco descuenta lo liberado; cerrar la orden extingue lo que no llegará"
      (is (= {:cantidad (un 70)} (:compromiso/saldo (compromiso e (s :marco :marco)))))
      (let [pendiente (de-origen e :compras/recepcion-pendiente (s :lib :orden))]
        (is (= [:extinguido :cierre] [(:compromiso/estado pendiente) (get-in pendiente [:compromiso/extincion :causa])])))
      (is (= {:monto (clp 25200)} (:compromiso/saldo (de-origen e :compras/pago-contra-factura (s :lib :orden)))))
      (is (= {:monto (clp 1800)} (:compromiso/saldo (de-origen e :compras/nota-credito-por-recibir (s :dev :devolucion))))))
    (testing "inventario: el traspaso mueve entre bodegas; el lote queda en cuarentena"
      ;; P-200: 50 de ajuste, −10 traspasados, −5 consumidos, +1 devuelto, −2 en mesón, −12 a fabricación.
      (is (= (un 22) (existencia e :P-200 :bodega/central)))
      (is (= (un 10) (existencia e :P-200 :bodega/norte)))
      (is (= (un 3) (existencia e :P-300 :bodega/central)))
      (is (= (un 18) (existencia e :P-100 :bodega/central)))
      (is (= :cuarentena (:lote/estado (entidad e :lote/l1)))))
    (testing "ventas y finanzas: la nota de débito abre lo que se cobra, y el castigo lo extingue"
      (is (= :castigo (get-in (compromiso e (s :nd :cobro)) [:compromiso/extincion :causa])))
      (is (= :latente (:compromiso/estado (compromiso e (s :cotv :cotizacion)))))
      (is (= {:monto (clp 418)} (:compromiso/saldo (compromiso e (s :f29 :impuesto))))))
    (testing "contabilidad: el asiento mueve las dos cuentas; la provisión queda revertida; el período, reabierto"
      (is (= (clp 15000) (:debe (saldo e :contabilidad/cuenta {:cuenta :cuenta/gastos}))))
      (is (= (clp 15000) (:haber (saldo e :contabilidad/cuenta {:cuenta :cuenta/caja}))))
      (is (= (s :prov1 :provision) (:hecho/revierte (est/leer e (s :rever :reverso)))))
      (is (= :abierto (:periodo/estado (entidad e (s :cerrar :periodo))))))
    (testing "costos: el reparto mueve entre centros; la rentabilidad sale de los hechos"
      (is (= (clp 60000) (:monto (saldo e :costos/centro {:centro :centro/administracion}))))
      (is (= (clp 30000) (:monto (saldo e :costos/centro {:centro :centro/ventas}))))
      (is (= (clp 2400) (s :rent :ventas))))
    (testing "producción: liquidar extingue lo que quedó de la orden"
      (is (every? #(= :extinguido (:compromiso/estado %))
                  (alm/buscar m/*alm* (:empresa e) :compromisos {:compromiso/origen (s :of :orden)}))))
    (testing "personal: el sueldo del mes queda por pagar; quedan 10 días de vacaciones"
      (is (= {:monto (clp 900000)}
             (:compromiso/saldo (de-origen e :personal/sueldo-por-pagar (s :sueldo :liquidacion)))))
      (is (= {:dias [:cantidad 10 :dia]} (:compromiso/saldo (compromiso e (s :ctr :vacaciones))))))))
