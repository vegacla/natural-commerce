(ns nc.catalogo-test
  "El canon de compras y la configuración de la Empresa B son válidos, y cada
  error que el validador debe atrapar al escribir se atrapa."
  (:require [clojure.test :refer [deftest is testing]]
            [nc.catalogo :as cat]
            [nc.esquema :as esq]
            [nc.validar :as val]))

(def canon (cat/leer "canon/compras.edn"))
(def empresa-b (cat/leer "empresas/empresa-b.edn"))
(def catalogo (cat/armar canon empresa-b))

(defn- tipos-de-problema [ps] (set (map :problema ps)))

(defn- contrato [id] (get-in catalogo [:contratos id]))
(defn- politica [id] (first (filter #(= id (:politica/id %)) (:politicas catalogo))))

(deftest el-catalogo-es-valido
  (is (empty? (cat/problemas catalogo)))
  (is (= 5 (count (:contratos catalogo))))
  (testing "el campo propio de la Empresa B entra al catálogo (D3)"
    (is (contains? (:campos catalogo) :producto.b/requiere-certificado))))

;; --- Expresiones ---------------------------------------------------------------

(def ent
  {:refs      {'entrada {:cantidad :cantidad :precio-unitario :monto :producto [:ref :producto]}
               'actor :ref 'ahora :fecha}
   :campos    (:campos catalogo)
   :conceptos {:credito/cupo-disponible {:concepto/tipo :monto :concepto/params {:cliente :cliente}}}})

(defn- problemas [expr] (tipos-de-problema (val/problemas-expresion expr ent)))

(deftest expresiones
  (testing "una expresión válida no tiene problemas y conoce su tipo"
    (is (= {:tipo :booleano :problemas []}
           (val/analizar '[:> [:* entrada/cantidad entrada/precio-unitario] [:monto 500000 :CLP]] ent))))
  (testing "un número suelto no se compara con un monto"
    (is (= #{:tipos-incompatibles}
           (problemas '[:> [:* entrada/cantidad entrada/precio-unitario] 500000]))))
  (testing "no se suma una cantidad con un monto"
    (is (= #{:tipos-incompatibles} (problemas '[:+ entrada/cantidad entrada/precio-unitario]))))
  (testing "el vocabulario es cerrado"
    (is (= #{:operacion-desconocida} (problemas [:potencia 2 3])))
    (is (= #{:aridad} (problemas [:si true 1]))))
  (testing "toda referencia debe existir (D6)"
    (is (= #{:referencia-desconocida} (problemas 'cliente/nombre)))
    (is (= #{:campo-desconocido} (problemas 'entrada/descuento)))
    (is (= #{:campo-desconocido} (problemas '[:-> entrada/producto :producto/categria])))
    (is (= #{:concepto-desconocido} (problemas [:credito/no-existe {}])))
    (is (= #{:parametros} (problemas [:credito/cupo-disponible {:proveedor 1}]))))
  (testing "una condición no puede modificar el estado (D55)"
    (is (= #{:efecto-en-expresion}
           (problemas '[:y [:> entrada/cantidad [:cantidad 5 :un]]
                           [:extingue-compromiso entrada/producto :cumplimiento]]))))
  (testing "se comprueba el tipo esperado"
    (is (= #{:tipo-inesperado}
           (tipos-de-problema (val/problemas-expresion 'entrada/cantidad ent :booleano))))))

;; --- Contratos -----------------------------------------------------------------

(defn- problemas-contrato [c] (tipos-de-problema (esq/problemas-contrato c catalogo)))

(deftest contratos
  (let [orden (contrato :compras/emitir-orden)]
    (testing "forma"
      (is (= #{:forma} (problemas-contrato (assoc orden :op/tipo :otra))))
      (is (= #{:forma} (problemas-contrato (assoc orden :op/regimen :solo-agregar))))
      (is (= #{:entradas-invalidas} (problemas-contrato (assoc orden :op/entradas [:map [:x :no-existe]])))))
    (testing "los efectos solo usan lo que el contrato declara"
      (is (= #{:campo-desconocido}
             (problemas-contrato (update orden :op/efectos conj
                                         '[:extingue-compromiso entrada/contrato-marco :cumplimiento]))))
      (is (= #{:verbo-desconocido}
             (problemas-contrato (update orden :op/efectos conj '[:activa-compromiso entrada/oferta]))))
      (is (= #{:aridad}
             (problemas-contrato (update orden :op/efectos conj '[:extingue-compromiso entrada/oferta]))))
      (is (= #{:tipo-hecho-desconocido}
             (problemas-contrato (assoc orden
                                        :op/salidas {}
                                        :op/efectos '[[:registra-hecho :compras/no-existe entrada]])))))
    (testing "un nombre dado con :como solo existe después de darlo"
      (is (= #{:referencia-desconocida}
             (problemas-contrato (update orden :op/efectos #(vec (reverse %)))))))
    (testing "ningún compromiso cambia sin un hecho (D61)"
      (is (= #{:compromiso-sin-hecho}
             (problemas-contrato (assoc orden
                                        :op/salidas {}
                                        :op/efectos '[[:extingue-compromiso entrada/oferta :cumplimiento]])))))
    (testing "una consulta no declara efectos"
      (is (= #{:efectos-en-consulta} (problemas-contrato (assoc orden :op/tipo :consulta)))))))

;; --- Políticas -----------------------------------------------------------------

(defn- problemas-politica [p] (tipos-de-problema (esq/problemas-politica p catalogo)))

(deftest politicas
  (let [formal (politica :empresa-b/compra-formal)]
    (testing "forma de la envoltura y del cuerpo"
      (is (= #{:forma} (problemas-politica (assoc formal :politica/forma :automatiza))))
      (is (= #{:forma} (problemas-politica (assoc formal :politica/aplica-con :cuando-quiera))))
      (is (= #{:forma} (problemas-politica (assoc formal :politica/cuerpo {:razon "no"})))))
    (testing "la condición debe ser una condición, bien tipada"
      (is (= #{:tipos-incompatibles}
             (problemas-politica
              (assoc formal :politica/cuando
                     '[:> [:* entrada/cantidad entrada/precio-unitario] 500000]))))
      (is (= #{:tipo-inesperado}
             (problemas-politica (assoc formal :politica/cuando 'entrada/cantidad)))))
    (testing "no se puede crear una política sobre algo que el ERP no conoce (D6)"
      (is (= #{:operacion-desconocida}
             (problemas-politica (assoc formal :politica/aplica-a :compras/no-existe))))
      (is (= #{:campo-desconocido}
             (problemas-politica (assoc formal :politica/cuando '[:= entrada/centro :ventas]))))
      (is (= #{:tipo-hecho-desconocido}
             (problemas-politica (assoc formal :politica/cuerpo [{:hecho :compras/cotizacion}])))))
    (testing "sugiere y fija apuntan a una entrada de la operación"
      (let [fija (assoc formal :politica/forma :fija
                        :politica/cuerpo {:campo :entrada/proveedor
                                          :valor '[:-> entrada/oferta :compromiso/deudor]})]
        (is (empty? (problemas-politica fija)))
        (is (= #{:campo-desconocido}
               (problemas-politica (assoc-in fija [:politica/cuerpo :campo] :entrada/bodega))))))
    (testing "exime e inicia"
      (is (empty? (problemas-politica
                   (assoc formal :politica/forma :exime
                          :politica/cuerpo {:hasta '[:< [:* entrada/cantidad entrada/precio-unitario]
                                                        [:monto 100000 :CLP]]}))))
      (is (= #{:operacion-desconocida}
             (problemas-politica
              (-> formal
                  (dissoc :politica/cuando)
                  (assoc :politica/aplica-a [:vence :ventas/cobro]
                         :politica/forma :inicia
                         :politica/cuerpo {:objetivo :finanzas/castigar-deuda
                                           :despues [:dias 180]}))))))))

;; --- Conceptos, tipos de hecho y caminos ---------------------------------------

(deftest conceptos
  (let [cupo {:concepto/id :credito/cupo-disponible :concepto/version 2 :concepto/tipo :monto
              :concepto/params {:cliente :cliente}
              :concepto/forma '[:- [:-> cliente :cliente/cupo-asignado]
                                   [:suma [:-> cada :compromiso/saldo :monto]
                                          [:compromisos {:tipo :ventas/cobro :deudor cliente}]]]}]
    (is (empty? (esq/problemas-concepto cupo catalogo)))
    (testing "un concepto solo ve sus parámetros"
      (is (= #{:referencia-desconocida}
             (tipos-de-problema
              (esq/problemas-concepto (assoc cupo :concepto/forma 'entrada/cantidad) catalogo)))))
    (testing "forma o cálculo, uno solo"
      (is (= #{:forma}
             (tipos-de-problema
              (esq/problemas-concepto (assoc cupo :concepto/calculo 'nc.x/y) catalogo)))))
    (testing "sin ciclos"
      (let [a {:concepto/id :p/a :concepto/forma [:p/b {}]}
            b {:concepto/id :p/b :concepto/forma [:+ 1 [:p/a {}]]}]
        (is (= #{:p/a :p/b}
               (set (map :en (esq/problemas-de-ciclos {:conceptos {:p/a a :p/b b}})))))
        (is (empty? (esq/problemas-de-ciclos {:conceptos {:p/a a :p/b (assoc b :concepto/forma 1)}})))))))

(deftest tipos-de-hecho
  (let [recepcion {:tipo-hecho/id      :compras/recepcion
                   :tipo-hecho/datos   [:map
                                        [:orden [:ref :compras/orden]]
                                        [:producto [:ref :producto]]
                                        [:bodega [:ref :bodega]]
                                        [:cantidad :cantidad]]
                   :tipo-hecho/regimen :solo-agregar
                   :tipo-hecho/mueve   '[{:saldo :inventario/existencia
                                          :clave {:producto hecho/producto :bodega hecho/bodega}
                                          :suma  {:cantidad hecho/cantidad}}]}]
    (is (empty? (esq/problemas-tipo-hecho recepcion catalogo)))
    (is (= #{:campo-desconocido}
           (tipos-de-problema
            (esq/problemas-tipo-hecho
             (assoc-in recepcion [:tipo-hecho/mueve 0 :suma :cantidad] 'hecho/unidades) catalogo))))
    (is (= #{:forma}
           (tipos-de-problema
            (esq/problemas-tipo-hecho (assoc recepcion :tipo-hecho/regimen :libre) catalogo))))))

(deftest caminos
  (let [abastecer (first (:caminos catalogo))]
    (is (empty? (esq/problemas-camino abastecer catalogo)))
    (testing "un camino responde a políticas que existen, con su versión (D54)"
      (is (= #{:regla-desconocida}
             (tipos-de-problema
              (esq/problemas-camino (assoc abastecer :camino/reglas {:empresa-b/compra-formal 9}) catalogo)))))
    (is (= #{:operacion-desconocida}
           (tipos-de-problema
            (esq/problemas-camino (update abastecer :camino/pasos conj :compras/no-existe) catalogo))))))
