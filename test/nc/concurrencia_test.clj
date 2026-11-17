(ns nc.concurrencia-test
  "Etapa 5, E7: dos recepciones simultáneas que juntas superan lo ordenado. Con la
  política que lo prohíbe, una falla; sin ella, ambas pasan (D49, D74)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.ejecutor :as ej]
            [nc.marco-test :as m]))

(use-fixtures :once
  (fn [pruebas]
    (let [a (alm/abrir)]
      (try (binding [m/*alm* a] (pruebas))
           (finally (alm/cerrar a))))))

(defn- error-de [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:nc/error (ex-data e)))))

(defn- a-la-vez
  "Corre las funciones en hilos que parten juntos, y devuelve sus resultados."
  [& fs]
  (let [partida (promise)
        hilos   (mapv (fn [f] (future @partida (f))) fs)]
    (deliver partida true)
    (mapv deref hilos)))

(def bajo-el-umbral (assoc m/orden-20 :precio-unitario [:monto 20000 :CLP]))

(defn- orden! [b] (-> (m/ejecutar! b :persona/ana :compras/emitir-orden bajo-el-umbral) :salidas :orden))

(defn- recibir [b orden]
  #(m/ejecutar! b :persona/ana :compras/registrar-recepcion
                {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 12 :un]}))

(defn- pendiente [b orden]
  (first (alm/buscar m/*alm* (:empresa b) :compromisos
                     {:compromiso/tipo :compras/recepcion-pendiente :compromiso/origen orden})))

(deftest e7-recepciones-simultaneas
  (let [b (m/empresa! m/empresa-b)]
    (testing "con la política activa"
      (let [orden (orden! b)
            datos {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 12 :un]}]
        (testing "dos recepciones preparadas sobre el mismo estado pasan la política, pero la segunda choca al escribir"
          (let [p1 (ej/preparar b :persona/ana :compras/registrar-recepcion datos)
                p2 (ej/preparar b :persona/ana :compras/registrar-recepcion datos)]
            (is (= :listo (get-in p1 [:resolucion :estado]) (get-in p2 [:resolucion :estado])))
            (is (= :ejecutado (:estado (ej/aplicar! b p1))))
            (is (= :conflicto (error-de #(ej/aplicar! b p2))))
            (is (= {:cantidad [:cantidad 8 :un]} (:compromiso/saldo (pendiente b orden))))))
        (testing "al volver a preparar, la política ve lo ya recibido y la prohíbe"
          (is (= [{:id :empresa-b/recibir-hasta-lo-pendiente :estado :prohibido
                   :razon "No se recibe más de lo que queda pendiente de la orden"}]
                 (:requisitos ((recibir b orden))))))))
    (testing "dos recepciones en hilos simultáneos: una pasa y la otra queda prohibida"
      (let [orden (orden! b)]
        (is (= {:ejecutado 1 :prohibido 1}
               (frequencies (map :estado (a-la-vez (recibir b orden) (recibir b orden))))))
        (is (= {:cantidad [:cantidad 8 :un]} (:compromiso/saldo (pendiente b orden))))))
    (testing "con la política retirada, ambas pasan"
      (alm/retirar! m/*alm* (:empresa b) :politicas :empresa-b/recibir-hasta-lo-pendiente)
      (let [orden (orden! b)]
        (is (= [:ejecutado :ejecutado] (map :estado (a-la-vez (recibir b orden) (recibir b orden)))))
        (testing "se recibió más de lo ordenado: el saldo queda bajo cero y el compromiso, extinguido (D69)"
          (is (= {:cantidad [:cantidad -4 :un]} (:compromiso/saldo (pendiente b orden))))
          (is (= :extinguido (:compromiso/estado (pendiente b orden)))))))
    (testing "la política retirada conserva su historia (D75)"
      (is (= [1] (map :politica/version (alm/historia m/*alm* (:empresa b) :politicas
                                                      :empresa-b/recibir-hasta-lo-pendiente)))))))
