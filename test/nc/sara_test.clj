(ns nc.sara-test
  "Etapa 6: SARA mínima genera formularios y tablas a partir del contrato y del
  resolvedor, sin código propio por operación (P12). Usa los datos de ejemplo."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.ejecutor :as ej]
            [nc.ejemplo :as ejemplo]
            [nc.sara :as sara]
            [nc.sara.web :as web]))

(def ^:dynamic *empresas* nil)

(use-fixtures :once
  (fn [pruebas]
    (let [a (alm/abrir)]
      (try (binding [*empresas* (ejemplo/cargar! a)] (pruebas))
           (finally (alm/cerrar a))))))

(defn- b [] (:empresa-b *empresas*))
(defn- a [] (:empresa-a *empresas*))

(defn- campo [f nombre] (first (filter #(= nombre (:nombre %)) (:campos f))))

(deftest un-formulario-por-contrato
  (testing "toda operación del canon tiene formulario, con un campo de tipo conocido por entrada (P12)"
    (doseq [[op c] (get-in (b) [:catalogo :contratos])
            :let [f (sara/formulario (b) :persona/ana op {})]]
      (is (= (count (rest (:op/entradas c))) (count (:campos f))) (str op))
      (is (not-any? #{:cualquiera} (map :tipo (:campos f))) (str op))))
  (testing "registrar pago, agregada solo con su contrato, también (E6)"
    (is (= [:orden :monto] (map :nombre (:campos (sara/formulario (a) :persona/ana :compras/registrar-pago {})))))))

(deftest lo-que-dice-el-resolvedor-llega-al-formulario
  (let [f (sara/formulario (b) :persona/ana :compras/emitir-orden
                           {:producto :P-100 :cantidad [:cantidad 20 :un] :precio-unitario [:monto 30000 :CLP]})]
    (is (= :bloqueado (:estado f)))
    (testing "la solicitud y la oferta son obligatorias porque la política las exige, y se dice de dónde salen (D71)"
      (is (= [true :compras/registrar-solicitud] ((juxt :obligatorio :viene-de) (campo f :solicitud))))
      (is (= [true :compras/registrar-oferta] ((juxt :obligatorio :viene-de) (campo f :oferta)))))
    (testing "en la Empresa A son opcionales"
      (is (false? (:obligatorio (campo (sara/formulario (a) :persona/ana :compras/emitir-orden {}) :solicitud)))))
    (testing "los avisos dicen qué falta, con qué operación y quién puede"
      (is (= ["Falta solicitud. Se registra con «Registrar solicitud»; puede hacerlo cualquiera."
              "Falta 2 × oferta. Se registra con «Registrar oferta»; puede hacerlo cualquiera."
              "Falta aprobacion. Se registra con «Aprobar»; puede hacerlo quien tenga el rol Jefe compras, pero no Ana."]
             (map :texto (:avisos f)))))
    (testing "las opciones de una referencia son solo del tipo declarado (D77)"
      (is (every? #(str/starts-with? (second %) "Necesidad") (:opciones (campo f :solicitud))))
      (is (= 2 (count (:opciones (campo f :solicitud))))))
    (testing "una entrada que no calza muestra el problema"
      (is (some? (:problema (campo (sara/formulario (b) :persona/ana :compras/emitir-orden
                                                    {:precio-unitario "treinta mil"})
                                   :precio-unitario)))))))

(deftest leer-lo-que-escribe-una-persona
  (is (= {:cantidad [:cantidad 20 :un] :precio-unitario [:monto 30000 :CLP] :producto :P-100}
         (sara/entradas-de-texto (b) :compras/emitir-orden
                                 {"cantidad" "20 un" "precio-unitario" "30.000 CLP" "producto" ":P-100" "oferta" ""})))
  (is (= "treinta" (sara/leer :monto "treinta")) "lo que no se entiende pasa tal cual: el contrato lo rechaza")
  (let [id (random-uuid)]
    (is (= id (sara/leer [:ref :compras/necesidad] (sara/escribir [:ref :compras/necesidad] id))))))

(deftest tablas-y-encadenamiento
  (let [orden (-> (ej/ejecutar! (a) :persona/ana :compras/emitir-orden
                                {:proveedor :proveedor/acme :producto :P-300 :cantidad [:cantidad 2 :un]
                                 :precio-unitario [:monto 15000 :CLP]}
                                {:confirmado true})
                  :salidas :orden)]
    (testing "después de emitir una orden, SARA ofrece las operaciones que la reciben (D10)"
      (is (= [:compras/consultar-estado :compras/registrar-factura :compras/registrar-pago :compras/registrar-recepcion]
             (map :operacion (sara/siguientes (a) {:orden orden})))))
    (testing "la consulta de estado se muestra como tablas"
      (let [salidas (sara/salidas (:salidas (ej/ejecutar! (a) :persona/ana :compras/consultar-estado {:orden orden} {})))
            {:keys [columnas filas]} (:tabla (first (filter #(= "Compromisos" (:nombre %)) salidas)))]
        (is (= 2 (count filas)))
        (is (= ["Id" "Tipo" "Estado"] (take 3 (map :titulo columnas))))
        (is (some #{"Pendiente: cantidad"} (map :titulo columnas)))
        (is (some #(= "2 un" %) (flatten filas)))))))

(deftest el-cliente-web-escapa-el-texto
  (is (= "<p class=\"nota\">&lt;b&gt; &amp;</p>" (web/html [:p.nota "<b> &"]))))

(deftest avisos-de-permiso-y-nombres
  (let [f (sara/formulario (b) :persona/ana :compras/aprobar {})]
    (is (some #{"Sin permiso para esta operación; puede hacerlo quien tenga el rol Jefe compras."}
              (map :texto (:avisos f)))))
  (is (= "Luis" (sara/nombre (b) :persona/luis)))
  (is (= "compras/orden" (sara/nombre (b) :compras/orden))))
