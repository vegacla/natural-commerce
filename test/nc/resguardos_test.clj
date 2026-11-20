(ns nc.resguardos-test
  "D80: la separación por empresa (D22) se protege con resguardos en código.
  Complementa a E9: aquí no se prueban escenarios, sino reglas que impiden que
  un cambio futuro abra una fuga."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.marco-test :as m]
            [nc.prueba :as p]))

(def ^:dynamic *alm* nil)

(use-fixtures :once
  (fn [pruebas]
    (let [a (alm/abrir)]
      (try (binding [*alm* a] (pruebas))
           (finally (alm/cerrar a))))))

(defn- error-de [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:nc/error (ex-data e)))))

;; --- 1. XTDB solo en el almacén -----------------------------------------------

(defn- fuentes [] (filter #(str/ends-with? (str %) ".clj") (file-seq (io/file "src"))))

(deftest solo-el-almacen-toca-xtdb
  (testing "ningún otro módulo de src requiere xtdb ni lo importa"
    (let [culpables (for [f (fuentes)
                          :when (not= "almacen.clj" (.getName f))
                          :when (re-find #"xtdb\.|XTDB\w*Node|\bxt/" (slurp f))
                          :when (re-find #"\(:require[^)]*xtdb|\(:import[^)]*xtdb|\[xtdb\." (slurp f))]
                      (str f))]
      (is (empty? culpables)))))

;; --- D82: solo el ejecutor escribe por MARCO -------------------------------------

(deftest solo-el-ejecutor-escribe
  (testing "ningún módulo de src, salvo el ejecutor, llama a alm/escribir!"
    (let [culpables (for [f (fuentes)
                          :when (not (#{"almacen.clj" "ejecutor.clj"} (.getName f)))
                          :when (re-find #"escribir!" (slurp f))]
                      (str f))]
      (is (empty? culpables)))))

;; --- D100: el modelo de lenguaje no toca MARCO ------------------------------------

(deftest solo-sara-habla-con-el-modelo
  (testing "solo nc.sara.claude usa el SDK de Anthropic"
    (is (= ["claude.clj"] (vec (for [f (fuentes) :when (re-find #"com\.anthropic" (slurp f))] (.getName f))))))
  (testing "y no conoce nada del ERP: no requiere ningún módulo nc"
    (is (not (re-find #"\[nc\." (slurp (io/file "src/nc/sara/claude.clj"))))))
  (testing "SARA con lenguaje llega a MARCO solo por ejecutar!, que evalúa y anota en la bitácora"
    (let [fuente (slurp (io/file "src/nc/sara/lenguaje.clj"))]
      (is (not (re-find #"ej/(aplicar!|preparar)|alm/" fuente))))))

;; --- E26: leer por identificador no recorre los datos de otras empresas -----------

(deftest leer-va-por-la-llave-primaria
  (testing "XTDB solo indexa :xt/id: filtrar por otra columna recorre la tabla con los
  datos de todas las empresas, y una empresa se hace más lenta con los datos de otra"
    (let [consultas (atom [])
          q         xtdb.api/q]
      (with-redefs [xtdb.api/q (fn [nodo consulta & mas]
                                 (swap! consultas conj consulta)
                                 (apply q nodo consulta mas))]
        (doseq [tabla [:hechos :compromisos :entidades :decisiones :politicas]]
          (alm/leer *alm* :empresa/a tabla (random-uuid)))
        (alm/leer *alm* :empresa/a :saldos [:inventario/existencia {:producto :P-100}]))
      (is (= 6 (count @consultas)))
      (is (every? #(re-find #":xt/id" (pr-str (first %))) @consultas)))))

;; --- D109: los índices propios dicen lo mismo que recorrer la tabla ------------------

(defn- con-operacion
  "Una empresa con casos de compra y de venta, y una factura revertida: hechos con
  caso y con reversión, compromisos con raíz y origen, escritos en varias
  transacciones."
  []
  (binding [m/*alm* *alm*]
    (let [e (m/empresa! {})
          x (fn [op entradas] (:salidas (m/ejecutar! e :persona/ana op entradas)))
          orden (:orden (x :compras/emitir-orden m/orden-20))]
      (x :compras/registrar-recepcion {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 12 :un]})
      (x :compras/registrar-recepcion {:orden orden :producto :P-100 :bodega :bodega/central :cantidad [:cantidad 8 :un]})
      (x :ventas/alta-cliente {:id :cliente/ferreteria :nombre "Ferretería" :cupo [:monto 10000000 :CLP]})
      (let [pedido  (:pedido (x :ventas/registrar-pedido {:cliente :cliente/ferreteria :producto :P-100
                                                          :cantidad [:cantidad 2 :un] :precio-unitario [:monto 50000 :CLP]}))
            factura (:factura (x :ventas/emitir-factura {:pedido pedido :monto [:monto 100000 :CLP] :fecha (java.util.Date.)
                                                         :vencimiento (m/dias-desde-hoy 30)}))]
        (x :ventas/emitir-nota-credito {:factura factura :fecha (java.util.Date.)}))
      (reset! m/empresas #{})
      (:empresa e))))

(defn- filtros
  "Cada búsqueda indexada posible en la empresa: [tabla filtro]."
  [e]
  (let [hs (alm/buscar *alm* e :hechos {})
        cs (alm/buscar *alm* e :compromisos {})]
    (distinct
     (concat (for [h hs k [:hecho/caso :hecho/revierte] :when (get h k)] [:hechos {k (get h k)}])
             (for [c cs k [:compromiso/raiz :compromiso/origen] :when (get c k)] [:compromisos {k (get c k)}])))))

(deftest el-indice-dice-lo-mismo-que-recorrer
  (testing "cada búsqueda por un campo indexado devuelve lo mismo que recorrer la tabla (D109)"
    (let [e  (con-operacion)
          fs (filtros e)]
      (is (= #{:hecho/caso :hecho/revierte :compromiso/raiz :compromiso/origen}
             (set (map (comp ffirst second) fs)))
          "el escenario usa los cuatro campos")
      (is (some #(< 1 (count (alm/buscar *alm* e (first %) (second %)))) fs)
          "y alguna búsqueda junta documentos de varias transacciones")
      (doseq [[tabla filtro] fs]
        (is (= (set (#'alm/recorrer *alm* e tabla filtro {}))
               (set (alm/buscar *alm* e tabla filtro)))
            (pr-str tabla filtro))))))

(deftest buscar-por-un-campo-indexado-va-por-la-llave-primaria
  (testing "con un campo indexado, buscar lee el índice y cada documento por :xt/id (D109)"
    (let [e  (con-operacion)
          fs (filtros e)
          consultas (atom [])
          q xtdb.api/q]
      (with-redefs [xtdb.api/q (fn [nodo consulta & mas]
                                 (swap! consultas conj consulta)
                                 (apply q nodo consulta mas))]
        (doseq [[tabla filtro] fs] (alm/buscar *alm* e tabla filtro)))
      (is (seq @consultas))
      (is (every? #(re-find #":xt/id|_id IN \(" (pr-str (first %))) @consultas)))))

;; --- 2 y 3. La empresa es obligatoria y no se pisa -----------------------------

(def ^:private funciones-con-empresa
  "Cada función pública del almacén, con cómo llamarla con una empresa dada.
  Una función nueva debe sumarse aquí: la prueba de abajo falla si falta."
  {'buscar    (fn [a e] (alm/buscar a e :hechos {}))
   'leer      (fn [a e] (alm/leer a e :hechos :x))
   'historia  (fn [a e] (alm/historia a e :hechos :x))
   'marca     (fn [a e] (alm/marca a e))
   'escribir! (fn [a e] (alm/escribir! a e {}))
   'cargar-entidades! (fn [a e] (alm/cargar-entidades! a e []))
   'publicar! (fn [a e] (alm/publicar! a e :politicas {:politica/id :x :politica/version 1 :politica/rige-desde #inst "2026-01-01"}))
   'retirar!  (fn [a e] (alm/retirar! a e :politicas :x))
   'vigente   (fn [a e] (alm/vigente a e :politicas :x))})

(def ^:private sin-empresa #{'abrir 'cerrar})

(deftest toda-funcion-publica-esta-clasificada
  (let [publicas (set (for [[k v] (ns-publics 'nc.almacen) :when (fn? @v)] k))]
    (is (= publicas (into (set (keys funciones-con-empresa)) sin-empresa))
        "una función pública nueva del almacén debe declararse con empresa o sin ella")))

(deftest toda-funcion-exige-empresa
  (doseq [[nombre llamar] funciones-con-empresa
          mala [nil "empresa-a" {:id 1} 42]]
    (is (= :empresa-invalida (error-de #(llamar *alm* mala)))
        (str nombre " con empresa " (pr-str mala)))))

(deftest el-filtro-no-lleva-empresa
  (is (= :filtro-con-empresa (error-de #(alm/buscar *alm* :empresa/a :hechos {:empresa :empresa/b})))))

(deftest no-se-escribe-un-documento-de-otra-empresa
  (let [compromiso {:compromiso/id (random-uuid) :compromiso/tipo :compras/recepcion-pendiente
                    :compromiso/estado :activo :empresa :empresa/b}]
    (is (= :documento-ajeno (error-de #(alm/escribir! *alm* :empresa/a (p/con-decision {:compromisos [compromiso]})))))))

(deftest no-se-pisa-un-compromiso-ajeno
  (let [id (random-uuid)
        mio {:compromiso/id id :compromiso/tipo :compras/recepcion-pendiente :compromiso/estado :activo}]
    (alm/escribir! *alm* :empresa/b (p/con-decision {:compromisos [mio]}))
    (is (= :documento-ajeno
           (error-de #(alm/escribir! *alm* :empresa/a (p/con-decision {:compromisos [(assoc mio :compromiso/estado :extinguido)]})))))
    (testing "el compromiso de B quedó intacto y A no lo ve"
      (is (= :activo (:compromiso/estado (first (alm/buscar *alm* :empresa/b :compromisos {:compromiso/id id})))))
      (is (empty? (alm/buscar *alm* :empresa/a :compromisos {:compromiso/id id}))))))

;; --- 4. Mismos nombres e identificadores en dos empresas -----------------------

(deftest las-lecturas-no-cruzan-empresas
  (let [id-c (random-uuid)
        tipo :compras/recepcion-pendiente
        hecho (fn [id] {:hecho/id id :hecho/tipo :compras/orden :hecho/en #inst "2026-12-04T10:15:00Z"
                        :hecho/actor :persona/ana :hecho/datos {:producto :P-100}})
        hid-a (random-uuid) hid-b (random-uuid)
        politica (fn [v] {:politica/id :compartida/politica :politica/version v
                          :politica/rige-desde #inst "2026-01-01T00:00:00Z"})]
    (alm/escribir! *alm* :empresa/a (p/con-decision {:hechos [(hecho hid-a)]
                                     :compromisos [{:compromiso/id id-c :compromiso/tipo tipo :compromiso/estado :activo}]}))
    (alm/escribir! *alm* :empresa/b (p/con-decision {:hechos [(hecho hid-b)]}))
    (alm/publicar! *alm* :empresa/a :politicas (politica 1))
    (alm/publicar! *alm* :empresa/b :politicas (politica 7))
    (testing "mismo identificador de dominio, datos distintos por empresa"
      (is (= 1 (:politica/version (alm/vigente *alm* :empresa/a :politicas :compartida/politica))))
      (is (= 7 (:politica/version (alm/vigente *alm* :empresa/b :politicas :compartida/politica)))))
    (testing "toda lectura de B sobre datos de A viene vacía"
      (is (nil? (alm/leer *alm* :empresa/b :compromisos id-c)))
      (is (empty? (alm/historia *alm* :empresa/b :compromisos id-c)))
      (is (empty? (alm/historia *alm* :empresa/b :hechos hid-a)))
      (is (empty? (alm/buscar *alm* :empresa/b :hechos {:hecho/id hid-a})))
      (is (= #{hid-b} (set (map :hecho/id (alm/buscar *alm* :empresa/b :hechos {}))))))
    (testing "toda búsqueda devuelve solo documentos de la empresa pedida"
      (doseq [tabla (keys alm/tablas)
              e [:empresa/a :empresa/b]]
        (is (every? #{e} (map :empresa (alm/buscar *alm* e tabla {}))) (str tabla " " e))))))
