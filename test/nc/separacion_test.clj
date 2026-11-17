(ns nc.separacion-test
  "Etapa 5, E9: la Empresa A consulta y opera mientras la B tiene compras abiertas
  del mismo producto. Ninguna consulta, resolución ni efecto de una ve o toca
  datos de la otra (D22)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.ejecutor :as ej]
            [nc.estado :as est]
            [nc.marco-test :as m]
            [nc.resolvedor :as r]))

(use-fixtures :once
  (fn [pruebas]
    (let [a (alm/abrir)]
      (try (binding [m/*alm* a] (pruebas))
           (finally (alm/cerrar a))))))

(defn- recepcion [orden cantidad]
  {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad cantidad :un]})

(defn- existencia [empresa]
  (alm/leer m/*alm* (:empresa empresa) :saldos
            [:inventario/existencia {:producto :P-100 :bodega :bodega/central}]))

(deftest e9-separacion-por-empresa
  (let [a (m/empresa! {})
        b (m/empresa! m/empresa-b)
        {:keys [solicitud] orden-b :orden} (m/compra-formal! b)
        _ (m/ejecutar! b :persona/ana :compras/registrar-recepcion (recepcion orden-b 5))
        abierta-b (-> (m/ejecutar! b :persona/ana :compras/registrar-solicitud
                                   {:producto :P-100 :cantidad [:cantidad 50 :un]})
                      :salidas :solicitud)]
    (testing "las políticas de B no rigen en A: la misma orden está lista sin pasos previos"
      (let [res (r/resolver a :persona/ana :compras/emitir-orden m/orden-20)]
        (is (= :listo (:estado res)))
        (is (= {} (:reglas res)))))
    (let [orden-a (-> (m/ejecutar! a :persona/ana :compras/emitir-orden m/orden-20) :salidas :orden)]
      (m/ejecutar! a :persona/ana :compras/registrar-recepcion (recepcion orden-a 12))
      (testing "los efectos de A no tocan los saldos ni los compromisos de B"
        (is (= {:cantidad [:cantidad 12 :un]} (:saldo/valor (existencia a))))
        (is (= {:cantidad [:cantidad 5 :un]} (:saldo/valor (existencia b))))
        (is (= :activo (:compromiso/estado (m/compromiso b abierta-b)))))
      (testing "A no ve los datos de B, ni por su identificador ni buscando"
        (is (nil? (est/leer a solicitud)))
        (is (nil? (est/leer a orden-b)))
        (is (empty? (alm/historia m/*alm* (:empresa a) :compromisos solicitud)))
        (is (empty? (alm/historia m/*alm* (:empresa a) :hechos orden-b)))
        (is (= #{orden-a} (set (map :hecho/id (alm/buscar m/*alm* (:empresa a) :hechos
                                                          {:hecho/tipo :compras/orden}))))))
      (testing "A no puede operar sobre datos de B: la referencia no existe en A"
        (is (= "No existe" (re-find #"No existe" (str (:problema (m/requisito
                                                                     (r/resolver a :persona/ana :compras/registrar-oferta
                                                                                 {:solicitud abierta-b :proveedor :proveedor/acme
                                                                                  :producto :P-100 :cantidad [:cantidad 50 :un]
                                                                                  :precio-unitario [:monto 1 :CLP]
                                                                                  :vence (m/dias-desde-hoy 5)})
                                                                     :entrada/solicitud))))))
        (is (= :bloqueado (:estado (m/ejecutar! a :persona/ana :compras/registrar-recepcion (recepcion orden-b 1)))))
        (is (= :bloqueado (:estado (ej/ejecutar! a :persona/ana :compras/consultar-estado {:orden orden-b} {}))))
        (is (= {:cantidad [:cantidad 5 :un]} (:saldo/valor (existencia b)))))
      (testing "la consulta de estado de A solo ve su caso"
        (let [{:keys [hechos compromisos]} (:salidas (ej/ejecutar! a :persona/ana :compras/consultar-estado
                                                                   {:orden orden-a} {}))]
          (is (= #{(:empresa a)} (set (map :empresa (concat hechos compromisos)))))))
      (testing "las escrituras de A no chocan con las de B: cada empresa tiene su marca (D74)"
        (let [plan-b (ej/preparar b :persona/ana :compras/registrar-recepcion (recepcion orden-b 3))]
          (m/ejecutar! a :persona/ana :compras/registrar-recepcion (recepcion orden-a 1))
          (is (= :ejecutado (:estado (ej/aplicar! b plan-b)))))))))
