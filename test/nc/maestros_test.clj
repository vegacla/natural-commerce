(ns nc.maestros-test
  "Etapa 7: plan contratado (E10) y datos maestros (E11)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.bitacora :as bit]
            [nc.ejecutor :as ej]
            [nc.estado :as est]
            [nc.marco-test :as m]
            [nc.plan :as plan]
            [nc.politicas :as pol]
            [nc.resolvedor :as r]
            [nc.sara :as sara]))

(use-fixtures :once m/con-almacen)

(defn- con-plan! [estado capacidades]
  (plan/cambiar! estado {:plan/capacidades capacidades :plan/rige-desde (m/dias-desde-hoy -1)} {}))

(defn- requisito [res id] (first (filter #(= id (:id %)) (:requisitos res))))

(def ferreteria {:id :cliente/ferreteria :nombre "Ferretería Sur" :cupo [:monto 1000000 :CLP]})

;; --- E10 -------------------------------------------------------------------------

(deftest e10-plan-contratado
  (let [a (m/empresa! {})
        b (m/empresa! m/empresa-b)]
    (is (= :publicado (:estado (con-plan! a #{:compras}))))
    (testing "la política por defecto de ventas no pidió elegir: la plataforma la retiró con el plan (D107)"
      (is (empty? (est/vigentes a))))
    (is (= :publicado (:estado (con-plan! b #{:compras :ventas}))))
    (testing "A no contrató ventas: dar de alta un cliente está prohibido, y no se escribe nada"
      (let [res (ej/ejecutar! a :persona/ana :ventas/alta-cliente ferreteria {:confirmado true})]
        (is (= :prohibido (:estado res)))
        (is (= "La capacidad :ventas no está en el plan contratado" (:razon (requisito res :plan))))
        (is (nil? (est/leer a :cliente/ferreteria)))))
    (testing "SARA no ofrece a A operaciones fuera de su plan"
      (is (not-any? #(= "ventas" (namespace %)) (keys (est/operaciones a))))
      (is (some #(= "compras" (namespace %)) (keys (est/operaciones a)))))
    (testing "B sí la tiene"
      (is (contains? (est/operaciones b) :ventas/alta-cliente))
      (is (= :ejecutado (:estado (m/ejecutar! b :persona/ana :ventas/alta-cliente ferreteria)))))
    (testing "B quiere dejar compras, pero sus políticas dependen de esa capacidad: nada cambia hasta que elija (D36)"
      (let [res (con-plan! b #{:ventas})]
        (is (= :requiere-eleccion (:estado res)))
        (is (= #{:empresa-b/compra-formal :empresa-b/segregacion :empresa-b/quien-aprueba
                 :empresa-b/recibir-hasta-lo-pendiente}
               (set (map :politica (:afectadas res)))))
        (is (= plan/opciones (:opciones res)))
        (is (= 1 (:plan/version (est/plan b))))
        (is (= :listo (:estado (r/resolver b :persona/ana :compras/registrar-solicitud
                                           {:producto :P-100 :cantidad [:cantidad 1 :un]}))))))
    (testing "si elige conservar las capacidades, el plan nuevo las mantiene"
      (let [res (plan/cambiar! b {:plan/capacidades #{:ventas} :plan/rige-desde (m/dias-desde-hoy -1)}
                               {:eleccion :conservar-capacidades})]
        (is (= #{:compras} (:conservadas res)))
        (is (= [2 #{:compras :ventas}] ((juxt :plan/version :plan/capacidades) (est/plan b))))))
    (testing "si elige retirar las políticas, se retiran y compras queda fuera del plan"
      (let [b2  (m/empresa! m/empresa-b)
            _   (con-plan! b2 #{:compras :ventas})
            res (plan/cambiar! b2 {:plan/capacidades #{:ventas} :plan/rige-desde (m/dias-desde-hoy -1)}
                               {:eleccion :retirar-politicas})]
        (is (= 4 (count (:retiradas res))))
        (is (= [:defecto/salir-de-lo-que-hay] (map :politica/id (est/vigentes b2)))
            "queda solo la por defecto, que es de ventas (D107)")
        (is (= :prohibido (:estado (r/resolver b2 :persona/ana :compras/registrar-solicitud
                                               {:producto :P-100 :cantidad [:cantidad 1 :un]}))))))))

(deftest un-requisito-fuera-del-plan-no-bloquea-en-silencio
  (let [c (m/empresa! {:politicas [{:politica/id       :empresa-c/aprobar-clientes
                                    :politica/version  1
                                    :politica/origen   :empresa
                                    :politica/aplica-a :ventas/alta-cliente
                                    :politica/forma    :exige
                                    :politica/cuerpo   [{:hecho :compras/aprobacion}]}]})]
    (alm/publicar! m/*alm* (:empresa c) :planes
                   {:plan/id :plan :plan/version 1 :plan/rige-desde (m/dias-desde-hoy -1) :plan/capacidades #{:ventas}})
    (let [req (requisito (r/resolver c :persona/ana :ventas/alta-cliente ferreteria) :compras/aprobacion)]
      (is (= :prohibido (:estado req)))
      (is (re-find #"no está en el plan" (:razon req))))))

;; --- E11 -------------------------------------------------------------------------

(def empresa-b-con-giro
  (update m/empresa-b :extensiones conj
          {:extension/empresa :empresa-b :extension/tipo :cliente :extension/campo [:cliente.b/giro :texto]}))

(deftest e11-datos-maestros
  (let [b    (m/empresa! empresa-b-con-giro)
        e    (:empresa b)
        alta (m/ejecutar! b :persona/ana :ventas/alta-cliente (assoc ferreteria :cliente.b/giro "Ferretería"))
        r1   (:registro alta)]
    (testing "el alta crea la entidad con el campo propio de B, sin cambiar el canon (D3)"
      (is (= :ejecutado (:estado alta)))
      (is (= :cliente/ferreteria (get-in alta [:salidas :cliente])))
      (let [c (est/leer b :cliente/ferreteria)]
        (is (= ["Ferretería Sur" [:monto 1000000 :CLP] "Ferretería" true]
               ((juxt :cliente/nombre :cliente/cupo :cliente.b/giro :entidad/activa) c)))
        (is (= (:decision alta) (:nc/decision c))))
      (is (not (contains? (get-in b [:catalogo :entidades :cliente :entidad/campos]) :cliente.b/giro))))
    (testing "SARA muestra el campo propio en el formulario"
      (is (some #(= :cliente.b/giro (:nombre %))
                (:campos (sara/formulario b :persona/ana :ventas/alta-cliente {})))))
    (testing "cambiar el cupo; en la fecha del alta se ve el cupo de entonces (D17)"
      (m/ejecutar! b :persona/ana :ventas/modificar-cliente {:cliente :cliente/ferreteria :cupo [:monto 2000000 :CLP]})
      (is (= [:monto 2000000 :CLP] (:cliente/cupo (est/leer b :cliente/ferreteria))))
      (is (= [:monto 1000000 :CLP] (:cliente/cupo (est/leer (est/al b r1) :cliente/ferreteria))))
      (is (= "Ferretería Sur" (:cliente/nombre (est/leer b :cliente/ferreteria)))))
    (testing "un dato que no calza con su tipo queda pendiente (D65)"
      (is (= :pendiente (:estado (requisito (r/resolver b :persona/ana :ventas/modificar-cliente
                                                        {:cliente :cliente/ferreteria :cupo "mucho"})
                                            :entrada/cupo)))))
    (testing "dar de alta un id que ya existe es un error del contrato, y queda en la bitácora"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Ya existe"
                            (m/ejecutar! b :persona/ana :ventas/alta-cliente ferreteria)))
      (is (= [:entidad-existente]
             (map (comp :tipo :decision/error) (bit/decisiones m/*alm* e {:decision/resultado :error})))))
    (testing "desactivado, no se puede usar, y la razón se explica"
      (m/ejecutar! b :persona/ana :ventas/desactivar-cliente {:cliente :cliente/ferreteria})
      (is (false? (:entidad/activa (est/leer b :cliente/ferreteria))))
      (let [res (ej/ejecutar! b :persona/ana :ventas/modificar-cliente
                              {:cliente :cliente/ferreteria :cupo [:monto 1 :CLP]} {:confirmado true})]
        (is (= :bloqueado (:estado res)))
        (is (= ":cliente/ferreteria está desactivado" (:problema (requisito res :entrada/cliente))))))
    (testing "la desactivación no borra: la historia queda"
      (is (true? (:entidad/activa (est/leer (est/al b r1) :cliente/ferreteria)))))
    (testing "los invariantes de la bitácora se cumplen"
      (is (= [] (bit/verificar m/*alm* e))))))

(deftest una-politica-por-defecto-cambiada-es-de-la-empresa
  (testing "Si la empresa cambia una política por defecto, ya es suya: dejar la
  capacidad de que depende le pide elegir, como con cualquier otra (D36, D107)."
    (let [a (m/empresa! {})
          p (assoc (first (pol/por-defecto))
                   :politica/version 2 :politica/rige-desde (m/dias-desde-hoy -1)
                   :politica/cuerpo {:razon "Sin existencia no se despacha"})]
      (is (= :publicada (:estado (pol/publicar! a p))))
      (let [res (con-plan! a #{:compras})]
        (is (= :requiere-eleccion (:estado res)))
        (is (= [:defecto/salir-de-lo-que-hay] (map :politica (:afectadas res)))))
      (testing "y al volver a contratar ventas, la plataforma no reinstala la del paquete"
        (plan/cambiar! a {:plan/capacidades #{:compras} :plan/rige-desde (m/dias-desde-hoy -1)}
                       {:eleccion :retirar-politicas})
        (is (empty? (est/vigentes a)))
        (con-plan! a #{:compras :ventas})
        (is (empty? (est/vigentes a)))))
    (testing "una intacta sí vuelve con la capacidad"
      (let [b (m/empresa! {})]
        (con-plan! b #{:compras})
        (is (empty? (est/vigentes b)))
        (con-plan! b #{:compras :ventas})
        (is (= [:defecto/salir-de-lo-que-hay] (map :politica/id (est/vigentes b))))))))
