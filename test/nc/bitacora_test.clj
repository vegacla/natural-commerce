(ns nc.bitacora-test
  "Etapa 7, E27: la bitácora de MARCO (D82). Toda evaluación queda guardada,
  también los rechazos; el almacén no acepta cambios sin una decisión ejecutada;
  y la verificación de invariantes demuestra, con lo guardado, que nada se
  escribió sin que MARCO lo validara."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.bitacora :as bit]
            [nc.ejecutor :as ej]
            [nc.marco-test :as m]
            [nc.prueba :as p]
            [xtdb.api :as xt]))

(use-fixtures :once m/con-almacen)

(defn- error-de [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:nc/error (ex-data e)))))

(defn- decision [b id]
  (first (bit/decisiones m/*alm* (:empresa b) {:decision/id id})))

(defn- recepcion [orden n]
  {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad n :un]})

(deftest e27-bitacora-de-marco
  (let [b (m/empresa! m/empresa-b)
        e (:empresa b)
        {:keys [solicitud orden]} (m/compra-formal! b)
        otra   (-> (m/ejecutar! b :persona/luis :compras/registrar-solicitud
                                {:producto :P-100 :cantidad [:cantidad 5 :un]})
                   :salidas :solicitud)
        propia (m/ejecutar! b :persona/luis :compras/aprobar {:solicitud otra})
        ajena  (m/ejecutar! b :persona/ana :compras/aprobar {:solicitud otra})
        sin-si (ej/ejecutar! b :persona/ana :compras/registrar-recepcion (recepcion orden 2) {})]
    (testing "Luis aprueba su propia solicitud: prohibido, con la razón y la versión de la regla"
      (let [d (decision b (:decision propia))]
        (is (= :prohibido (:estado propia) (:decision/resultado d)))
        (is (= :persona/luis (:decision/actor d)))
        (is (= :compras/aprobar (:decision/operacion d)))
        (is (some #(= {:id :empresa-b/segregacion :estado :prohibido
                       :razon "Quien solicita una compra no puede aprobarla"} %)
                  (:decision/requisitos d)))
        (is (= 1 (get-in d [:decision/reglas :empresa-b/segregacion])))))
    (testing "Ana, sin permiso, intenta aprobar: prohibido"
      (is (= :permiso (:id (first (filter #(= :prohibido (:estado %))
                                          (:decision/requisitos (decision b (:decision ajena)))))))))
    (testing "sin confirmar no se ejecuta, y también queda anotado"
      (is (= [:por-confirmar :falta]
             ((juxt :decision/resultado :decision/confirmacion) (decision b (:decision sin-si))))))
    (testing "dos recepciones preparadas sobre el mismo estado: la segunda choca y queda anotada (E7)"
      (let [p1 (ej/preparar b :persona/ana :compras/registrar-recepcion (recepcion orden 3))
            p2 (ej/preparar b :persona/ana :compras/registrar-recepcion (recepcion orden 4))]
        (is (= :ejecutado (:estado (ej/aplicar! b p1))))
        (is (= :conflicto (error-de #(ej/aplicar! b p2))))
        (is (= [:compras/registrar-recepcion]
               (map :decision/operacion (bit/decisiones m/*alm* e {:decision/resultado :conflicto}))))))
    (testing "la orden se emitió confirmada, y su decisión declara el hecho que escribió"
      (let [d (first (bit/decisiones m/*alm* e {:decision/operacion :compras/emitir-orden}))]
        (is (= [:ejecutado :confirmada [orden]]
               ((juxt :decision/resultado :decision/confirmacion :decision/hechos) d)))
        (is (= (:decision/id d) (:nc/decision (alm/leer m/*alm* e :hechos orden))))))
    (testing "aplicar un plan que no está listo no escribe nada: aplicar! también pasa por MARCO"
      (let [antes (count (alm/buscar m/*alm* e :hechos {:hecho/tipo :compras/aprobacion}))
            r     (ej/aplicar! b (ej/preparar b :persona/ana :compras/aprobar {:solicitud otra}))]
        (is (= :prohibido (:estado r)))
        (is (= :prohibido (:decision/resultado (decision b (:decision r)))))
        (is (= antes (count (alm/buscar m/*alm* e :hechos {:hecho/tipo :compras/aprobacion}))))))
    (testing "toda evaluación quedó en la bitácora"
      (is (= #{:ejecutado :prohibido :por-confirmar :conflicto}
             (set (map :decision/resultado (bit/decisiones m/*alm* e))))))
    (testing "los invariantes se cumplen"
      (is (= [] (bit/verificar m/*alm* e))))
    (is (some? solicitud))))

(deftest el-almacen-no-escribe-sin-marco
  (let [b (m/empresa! {})
        e (:empresa b)
        h {:hecho/id (random-uuid) :hecho/tipo :compras/orden :hecho/en (java.util.Date.)
           :hecho/actor :persona/ana :hecho/datos {}}]
    (testing "sin decisión no se escribe"
      (is (= :sin-decision (error-de #(alm/escribir! m/*alm* e {:hechos [h]})))))
    (testing "una decisión que no es una ejecución no escribe cambios"
      (is (= :decision-no-ejecutada
             (error-de #(alm/escribir! m/*alm* e {:hechos [h] :decision (assoc (p/decision [h]) :decision/resultado :prohibido)})))))
    (testing "la decisión debe declarar los hechos que se escriben"
      (is (= :decision-inconsistente
             (error-de #(alm/escribir! m/*alm* e {:hechos [h] :decision (p/decision [])})))))
    (testing "nada quedó escrito"
      (is (nil? (alm/leer m/*alm* e :hechos (:hecho/id h))))
      (is (= [] (bit/verificar m/*alm* e))))))

(deftest los-invariantes-detectan-lo-que-se-salta-a-marco
  (let [b (m/empresa! {})
        e (:empresa b)
        {:keys [salidas decision]} (m/ejecutar! b :persona/ana :compras/emitir-orden m/orden-20)]
    (is (= [] (bit/verificar m/*alm* e)))
    (testing "una ejecución con un requisito sin cumplir, escrita por fuera del ejecutor"
      (let [h {:hecho/id (random-uuid) :hecho/tipo :compras/orden :hecho/en (java.util.Date.)
               :hecho/actor :persona/ana :hecho/datos {}}
            d (assoc (p/decision [h]) :decision/requisitos [{:id :compras/aprobacion :estado :pendiente}])]
        (alm/escribir! m/*alm* e {:hechos [h] :decision d})
        (is (= [:ejecutada-sin-validar] (map :violacion (bit/verificar m/*alm* e))))))
    (testing "borrar de la bitácora la decisión de la orden deja el hecho sin respaldo"
      (xt/execute-tx (:nodo m/*alm*) [[:erase-docs :decisiones decision]])
      (swap! m/empresas disj e)
      (is (some #(= [:decision-inexistente (:orden salidas)] ((juxt :violacion :id) %))
                (bit/verificar m/*alm* e))))))
