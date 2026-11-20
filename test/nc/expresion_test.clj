(ns nc.expresion-test
  (:require [clojure.test :refer [deftest is testing]]
            [nc.expresion :as e]))

;; --- Estado en memoria para las pruebas --------------------------------------

(def entidades
  {:P-100         {:producto/categoria :insumos}
   :cliente/c-17  {:cliente/cupo-asignado [:monto 1000000 :CLP]}})

(def cobros
  [{:compromiso/tipo :ventas/cobro :compromiso/deudor :cliente/c-17 :compromiso/estado :activo
    :compromiso/saldo {:monto [:monto 300000 :CLP]}}
   {:compromiso/tipo :ventas/cobro :compromiso/deudor :cliente/c-17 :compromiso/estado :activo
    :compromiso/saldo {:monto [:monto 200000 :CLP]}}
   {:compromiso/tipo :ventas/cobro :compromiso/deudor :cliente/c-17 :compromiso/estado :extinguido
    :compromiso/saldo {:monto [:monto 0 :CLP]}}])

(defn- filtrar [docs prefijo]
  (fn [filtro]
    (filter (fn [d] (every? (fn [[k x]] (= x (get d (keyword prefijo (name k))))) filtro))
            docs)))

(defn costo-fijo [_args _ctx] [:monto 4210 :CLP])

(def conceptos
  {:credito/cupo-disponible
   {:concepto/id :credito/cupo-disponible :concepto/version 2 :concepto/tipo :monto
    :concepto/params {:cliente :cliente}
    :concepto/forma '[:- [:-> cliente :cliente/cupo-asignado]
                         [:suma [:-> cada :compromiso/saldo :monto]
                                [:compromisos {:tipo :ventas/cobro :deudor cliente :estado :activo}]]]}
   :costo/promedio
   {:concepto/id :costo/promedio :concepto/version 1 :concepto/tipo :monto
    :concepto/params {:producto :producto}
    :concepto/calculo 'nc.expresion-test/costo-fijo}
   :prueba/ciclo
   {:concepto/id :prueba/ciclo :concepto/version 1 :concepto/tipo :numero
    :concepto/params {} :concepto/forma [:prueba/ciclo {}]}})

(defn ctx [entrada]
  {:refs        {'entrada entrada 'actor :persona/ana 'empresa :empresa-b
                 'ahora #inst "2026-12-04T10:15:00Z"}
   :buscar      entidades
   :compromisos (filtrar cobros "compromiso")
   :concepto    conceptos
   :traza       (atom {})})

(def compra {:producto :P-100
             :cantidad [:cantidad 20 :un]
             :precio-unitario [:monto 30000 :CLP]
             :solicitud nil})

(defn- error-de [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:nc/error (ex-data e)))))

;; --- Pruebas -----------------------------------------------------------------

(def umbral '[:> [:* entrada/cantidad entrada/precio-unitario] [:monto 500000 :CLP]])

(deftest umbral-de-compra
  (testing "20 unidades a $30.000 superan $500.000"
    (is (true? (e/evaluar umbral (ctx compra)))))
  (testing "5 unidades no"
    (is (false? (e/evaluar umbral (ctx (assoc compra :cantidad [:cantidad 5 :un])))))))

(deftest unidades
  (testing "no se comparan pesos con dólares"
    (is (= :unidades-distintas
           (error-de #(e/evaluar umbral (ctx (assoc compra :precio-unitario [:monto 35 :USD])))))))
  (testing "un número suelto no es un monto"
    (is (= :unidades-distintas
           (error-de #(e/evaluar '[:> [:* entrada/cantidad entrada/precio-unitario] 500000]
                                 (ctx compra))))))
  (testing "porcentaje y división"
    (is (= [:monto 5000 :CLP] (e/evaluar [:* [:monto 100000 :CLP] [:% 5]] {})))
    (is (= [:monto 100000 :CLP] (e/evaluar [:/ [:monto 600000 :CLP] 6] {})))
    (is (= 2 (e/evaluar [:/ [:monto 600000 :CLP] [:monto 300000 :CLP]] {})))))

(deftest datos-que-faltan
  (testing "un dato ausente se informa, no revienta (P4)"
    (is (= {:falta 'entrada/precio-unitario}
           (e/evaluar-seguro umbral (ctx (dissoc compra :precio-unitario))))))
  (testing "una entrada opcional presente con nil no falta"
    (is (= {:valor nil} (e/evaluar-seguro 'entrada/solicitud (ctx compra))))))

(deftest navegacion
  (is (true? (e/evaluar '[:= [:-> entrada/producto :producto/categoria] :insumos] (ctx compra))))
  (is (nil? (e/evaluar '[:-> entrada/solicitud :compromiso/deudor] (ctx compra))))
  (is (= :referencia-rota
         (error-de #(e/evaluar '[:-> entrada/producto :producto/categoria]
                               (ctx (assoc compra :producto :no-existe)))))))

(deftest conceptos-y-traza
  (let [c (ctx compra)]
    (testing "cupo disponible = asignado menos cobros activos"
      (is (= [:monto 500000 :CLP]
             (e/evaluar [:credito/cupo-disponible {:cliente :cliente/c-17}] c))))
    (testing "el concepto algorítmico deja su valor como evidencia"
      (is (= [:monto 4210 :CLP] (e/evaluar [:costo/promedio {:producto :P-100}] c))))
    (testing "quedan las versiones usadas (D48)"
      (is (= {:reglas    {:credito/cupo-disponible 2 :costo/promedio 1}
              :evidencia {:costo/promedio [:monto 4210 :CLP]}}
             @(:traza c)))))
  (is (= :ciclo (error-de #(e/evaluar [:prueba/ciclo {}] (ctx compra)))))
  (is (= :concepto-desconocido (error-de #(e/evaluar [:no/existe {}] (ctx compra))))))

(deftest colecciones
  (let [c   (ctx compra)
        col '[:compromisos {:tipo :ventas/cobro :deudor :cliente/c-17}]]
    (is (= 3 (e/evaluar [:cuenta col] c)))
    (is (true? (e/evaluar [:existe col] c)))
    (is (false? (e/evaluar [:existe '[:compromisos {:tipo :no/hay}]] c)))
    (is (false? (e/evaluar [:todos col '[:= cada/estado :activo]] c)))
    (is (= [:monto 300000 :CLP] (e/evaluar [:max '[:-> cada :compromiso/saldo :monto] col] c)))
    (is (= 0 (e/evaluar [:suma :x '[:compromisos {:tipo :no/hay}]] c)))))

(deftest logica
  (is (= :b (e/evaluar [:si [:y true false] :a :b] {})))
  (is (= :dos (e/evaluar [:caso false :uno true :dos :otro] {})))
  (is (= :otro (e/evaluar [:caso false :uno false :dos :otro] {})))
  (is (true? (e/evaluar [:entre [:monto 5 :CLP] [:monto 1 :CLP] [:monto 9 :CLP]] {}))))

(deftest fechas
  (let [c (ctx compra)]
    (is (= #inst "2027-06-02T10:15:00Z" (e/evaluar '[:+dias ahora [:dias 180]] c)))
    (is (= #inst "2027-01-04T10:15:00Z" (e/evaluar '[:+meses ahora 1] c)))
    (is (= #inst "2026-12-31T10:15:00Z" (e/evaluar '[:fin-de-mes ahora] c)))
    (is (= 16 (e/evaluar '[:dias-entre ahora #inst "2026-12-20T10:15:00Z"] c)))
    (testing "el 5 de diciembre de 2026 es sábado"
      (is (= #inst "2026-12-07T00:00:00Z"
             (e/evaluar [:habil-siguiente #inst "2026-12-05T00:00:00Z"] c))))))

(deftest vocabulario-cerrado
  (is (= :operacion-desconocida (error-de #(e/evaluar [:potencia 2 3] {}))))
  (is (= :forma-invalida (error-de #(e/evaluar [1 2 3] {})))))

(deftest comparar-con-nada
  (testing "un valor con unidad comparado con nada no es un error: [:!= x nil] pregunta si el dato está (D114)"
    (is (true? (e/evaluar '[:!= [:monto 7000 :CLP] nil] {:refs {}})))
    (is (false? (e/evaluar '[:= [:monto 7000 :CLP] nil] {:refs {}})))
    (is (true? (e/evaluar '[:= nil nil] {:refs {}})))))
