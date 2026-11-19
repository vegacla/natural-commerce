(ns nc.almacen-test
  "Etapa 2: hechos y compromisos sobre XTDB. Escritura, lectura y consulta en
  fecha pasada. Cada prueba usa su propia empresa sobre un mismo nodo."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.prueba :as p])
  (:import (java.util Date)))

(def ^:dynamic *alm* nil)

(use-fixtures :once
  (fn [pruebas]
    (let [a (alm/abrir)]
      (try (binding [*alm* a] (pruebas))
           (finally (alm/cerrar a))))))

(defn- empresa [] (keyword "empresa" (str (random-uuid))))

(defn- dias-desde-hoy [n] (Date. (+ (System/currentTimeMillis) (* n 24 60 60 1000))))

(defn- error-de [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:nc/error (ex-data e)))))

;; --- Datos del caso de la Empresa B (notación, sección 8) ----------------------

(defn- orden [id]
  {:hecho/id    id
   :hecho/tipo  :compras/orden
   :hecho/en    #inst "2026-12-04T10:15:00Z"
   :hecho/actor :persona/ana
   :hecho/datos {:proveedor :proveedor/acme :producto :P-100
                 :cantidad [:cantidad 20 :un] :precio-unitario [:monto 30000 :CLP]}
   :hecho/reglas {:empresa-b/compra-formal 3}})

(defn- recepcion-pendiente [id origen saldo]
  {:compromiso/id       id
   :compromiso/tipo     :compras/recepcion-pendiente
   :compromiso/deudor   :proveedor/acme
   :compromiso/acreedor :empresa-b
   :compromiso/objeto   {:producto :P-100 :cantidad [:cantidad 20 :un]}
   :compromiso/saldo    {:cantidad saldo}
   :compromiso/estado   :activo
   :compromiso/origen   origen
   :compromiso/raiz     origen})

;; --- Pruebas -------------------------------------------------------------------

(deftest escribir-y-leer
  (let [e  (empresa)
        h  (random-uuid)
        c  (random-uuid)
        _  (alm/escribir! *alm* e (p/con-decision {:hechos      [(orden h)]
                                   :compromisos [(recepcion-pendiente c h [:cantidad 20 :un])]}))]
    (testing "los datos vuelven tal como se escribieron: unidades, claves y fechas"
      (is (= (assoc (orden h) :empresa e) (dissoc (alm/leer *alm* e :hechos h) :nc/decision)))
      (is (= [:cantidad 20 :un] (get-in (alm/leer *alm* e :compromisos c) [:compromiso/saldo :cantidad])))
      (is (instance? Date (:hecho/en (alm/leer *alm* e :hechos h)))))
    (testing "se busca por cualquier campo"
      (is (= [c] (map :compromiso/id
                      (alm/buscar *alm* e :compromisos {:compromiso/tipo :compras/recepcion-pendiente
                                                        :compromiso/origen h})))))))

(deftest un-hecho-no-se-modifica
  (let [e (empresa)
        h (random-uuid)
        c (random-uuid)]
    (alm/escribir! *alm* e (p/con-decision {:hechos [(orden h)]}))
    (testing "registrar de nuevo el mismo hecho falla (D16)"
      (is (= :hecho-existente
             (error-de #(alm/escribir! *alm* e (p/con-decision {:hechos      [(assoc-in (orden h) [:hecho/datos :cantidad] [:cantidad 99 :un])]
                                                :compromisos [(recepcion-pendiente c h [:cantidad 99 :un])]})))))
      (is (= [:cantidad 20 :un] (get-in (alm/leer *alm* e :hechos h) [:hecho/datos :cantidad]))))
    (testing "y la transacción completa se descarta: el compromiso no quedó escrito"
      (is (nil? (alm/leer *alm* e :compromisos c))))))

(deftest consulta-en-fecha-pasada
  (let [e  (empresa)
        h  (random-uuid)
        c  (random-uuid)
        r1 (:registro (alm/escribir! *alm* e (p/con-decision {:hechos      [(orden h)]
                                              :compromisos [(recepcion-pendiente c h [:cantidad 20 :un])]})))
        r2 (:registro (alm/escribir! *alm* e (p/con-decision {:compromisos [(recepcion-pendiente c h [:cantidad 8 :un])]})))
        saldo (fn [opts] (get-in (alm/leer *alm* e :compromisos c opts) [:compromiso/saldo :cantidad]))]
    (testing "hoy se ve el saldo reducido por la recepción"
      (is (= [:cantidad 8 :un] (saldo {}))))
    (testing "en la fecha de la orden se ve como era entonces (D17)"
      (is (= [:cantidad 20 :un] (saldo {:al r1})))
      (is (= [:cantidad 8 :un] (saldo {:al r2}))))
    (testing "antes de la orden no existía"
      (is (nil? (saldo {:al (.minusSeconds ^java.time.Instant r1 1)}))))
    (testing "la historia guarda las dos versiones con sus tiempos"
      (let [versiones (alm/historia *alm* e :compromisos c)]
        (is (= [[:cantidad 8 :un] [:cantidad 20 :un]]
               (map #(get-in % [:compromiso/saldo :cantidad]) versiones)))
        (is (= [r2 r1] (map :xt/system-from versiones)))))))

(defn- compra-formal [version desde]
  {:politica/id         :empresa-b/compra-formal
   :politica/version    version
   :politica/rige-desde desde
   :politica/origen     :empresa
   :politica/aplica-a   :compras/emitir-orden
   :politica/forma      :exige
   :politica/cuerpo     []})

(deftest politicas-con-vigencia
  (let [e      (empresa)
        en-30  (dias-desde-hoy 30)
        en-60  (dias-desde-hoy 60)
        r3     (:registro (alm/publicar! *alm* e :politicas (compra-formal 3 (dias-desde-hoy -10))))
        r4     (:registro (alm/publicar! *alm* e :politicas (compra-formal 4 en-30)))
        ;; Instant, no Date: con milisegundos, hoy podría quedar antes de la
        ;; transacción que se acaba de escribir (knowledge/xtdb-bitemporalidad.md).
        hoy    (java.time.Instant/now)
        version (fn [opts] (:politica/version (alm/vigente *alm* e :politicas :empresa-b/compra-formal opts)))]
    (testing "una versión cargada hoy rige recién desde su fecha (H9)"
      (is (= 3 (version {:al hoy})))
      (is (= 4 (version {:validez en-60}))))
    (testing "lo que se sabía antes de publicar la versión 4"
      (is (= 3 (version {:validez en-60 :registro r3}))))
    (testing "publicar una versión intermedia no pisa la que rige después"
      (alm/publicar! *alm* e :politicas (compra-formal 5 (dias-desde-hoy 15)))
      (is (= 3 (version {:validez hoy})))
      (is (= 5 (version {:validez (dias-desde-hoy 20)})))
      (is (= 4 (version {:validez en-60}))))
    (testing "toda versión necesita número y fecha de vigencia"
      (is (= :sin-vigencia (error-de #(alm/publicar! *alm* e :politicas (dissoc (compra-formal 6 (dias-desde-hoy 0)) :politica/rige-desde)))))
      (is (= :sin-version (error-de #(alm/publicar! *alm* e :politicas (dissoc (compra-formal 6 (dias-desde-hoy 0)) :politica/version))))))
    (is (some? r4))))

(deftest saldos
  (let [e     (empresa)
        clave {:producto :P-100 :bodega :central}
        saldo (fn [cantidad]
                {:saldo/concepto :inventario/existencia
                 :saldo/clave    clave
                 :saldo/valor    {:cantidad cantidad :costo [:monto 50520 :CLP]}
                 :saldo/reglas   {:empresa-b/valorizacion 1}})]
    (alm/escribir! *alm* e (p/con-decision {:saldos [(saldo [:cantidad 12 :un])]}))
    (alm/escribir! *alm* e (p/con-decision {:saldos [(saldo [:cantidad 20 :un])]}))
    (testing "un saldo se identifica por concepto y clave, y guarda la versión de su regla (D48)"
      (let [s (alm/leer *alm* e :saldos [:inventario/existencia clave])]
        (is (= [:cantidad 20 :un] (get-in s [:saldo/valor :cantidad])))
        (is (= {:empresa-b/valorizacion 1} (:saldo/reglas s)))))
    (is (= 2 (count (alm/historia *alm* e :saldos [:inventario/existencia clave]))))))

(deftest separacion-por-empresa
  (let [a (empresa)
        b (empresa)
        h (random-uuid)]
    (alm/escribir! *alm* b (p/con-decision {:hechos [(orden h)]}))
    (alm/publicar! *alm* b :politicas (compra-formal 3 (dias-desde-hoy -1)))
    (testing "una empresa no ve los datos de otra (D22)"
      (is (nil? (alm/leer *alm* a :hechos h)))
      (is (empty? (alm/buscar *alm* a :hechos {:hecho/tipo :compras/orden})))
      (is (nil? (alm/vigente *alm* a :politicas :empresa-b/compra-formal))))
    (testing "dos empresas pueden usar el mismo identificador de política"
      (alm/publicar! *alm* a :politicas (compra-formal 1 (dias-desde-hoy -1)))
      (is (= 1 (:politica/version (alm/vigente *alm* a :politicas :empresa-b/compra-formal))))
      (is (= 3 (:politica/version (alm/vigente *alm* b :politicas :empresa-b/compra-formal)))))))
