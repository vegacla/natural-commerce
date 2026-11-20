(ns nc.web-test
  "El chat del cliente web (D110), con un modelo guionado: la persona escribe,
  confirma con un botón y elige con un botón entre las opciones que SARA ofreció.
  Las páginas se piden sin servidor, con `pagina-de`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.bitacora :as bit]
            [nc.lenguaje-test :as lt]
            [nc.marco-test :as m]
            [nc.sara.lenguaje :as sl]
            [nc.sara.web :as web]))

(use-fixtures :once m/con-almacen)

(defn- llamada [herramienta entradas] {:llamada {:herramienta herramienta :entradas entradas} :uso {:entrada 100 :salida 10}})

(defn- opciones [hs herramienta campo]
  (some #(when (= campo (:nombre %)) (:opciones %))
        (:campos (first (filter #(= herramienta (:nombre %)) hs)))))

(defn- empresa-con-facturas!
  "B con un cliente y dos facturas: anular una pide elegir cuál."
  []
  (let [b (m/empresa! m/empresa-b)]
    (m/ejecutar! b :persona/ana :ventas/alta-cliente {:id :cliente/ferreteria :nombre "Ferretería" :cupo [:monto 10000000 :CLP]})
    (dotimes [_ 2]
      (let [pedido (get-in (m/ejecutar! b :persona/ana :ventas/registrar-pedido
                                        {:cliente :cliente/ferreteria :producto :P-100 :cantidad [:cantidad 1 :un]
                                         :precio-unitario [:monto 50000 :CLP]})
                           [:salidas :pedido])]
        (m/ejecutar! b :persona/ana :ventas/emitir-factura
                     {:pedido pedido :monto [:monto 50000 :CLP] :fecha (java.util.Date.) :vencimiento (m/dias-desde-hoy 30)})))
    b))

(defn- hechos [estado tipo] (alm/buscar m/*alm* (:empresa estado) :hechos {:hecho/tipo tipo}))

(deftest el-chat-confirma-y-elige-con-botones
  (let [b (empresa-con-facturas!)
        [modelo] (lt/guion {"necesito comprar 20 unidades del P-100"
                            (fn [_] (llamada "compras__registrar-solicitud" {:producto ":P-100" :cantidad "20 un"}))
                            "anula la factura del cliente"
                            (fn [hs] (llamada "ventas__emitir-nota-credito"
                                              {:factura (first (opciones hs "ventas__emitir-nota-credito" "factura"))
                                               :fecha "2026-11-19"}))
                            "consulta el estado de la compra"
                            (fn [_] (llamada "compras__consultar-estado" {}))})
        ctx (web/contexto {(:empresa b) b} modelo)
        e   (subs (str (:empresa b)) 1)
        p   (fn [ruta & {:as params}] (web/pagina-de ctx ruta (merge {"e" e "actor" "persona/ana"} params)))]
    (testing "la persona escribe; SARA dice que está lista y ofrece el botón, sin ejecutar nada"
      (let [h (p "/chat/decir" "texto" "necesito comprar 20 unidades del P-100")]
        (is (str/includes? h "Registrar solicitud está lista. ¿Confirmas?"))
        (is (str/includes? h "action=\"/chat/confirmar\""))
        (is (empty? (hechos b :compras/solicitud)))))
    (testing "el botón confirma: MARCO vuelve a evaluar y escribe"
      (let [h (p "/chat/confirmar")]
        (is (str/includes? h "Registrar solicitud: hecho."))
        (is (= 1 (count (hechos b :compras/solicitud))))))
    (testing "lo irreversible con dos facturas: SARA pregunta, con un botón por opción"
      (let [h (p "/chat/decir" "texto" "anula la factura del cliente")]
        (is (str/includes? h "no se puede deshacer. ¿Cuál factura?"))
        (is (= 2 (count (re-seq #"action=\"/chat/elegir\"" h))))))
    (testing "elegir una opción la propone a MARCO, y la persona confirma"
      (let [opcion (-> @(:conversaciones ctx) (get [e "persona/ana"]) :conv :respuesta :opciones second)
            h      (p "/chat/elegir" "campo" "factura" "valor" (pr-str (first opcion)))]
        (is (str/includes? h "¿Confirmas?"))
        (is (str/includes? h (second opcion)) "el turno muestra la opción elegida")
        (is (empty? (hechos b :ventas/nota-credito)))
        (p "/chat/confirmar")
        (is (= [(first opcion)] (map #(get-in % [:hecho/datos :factura]) (hechos b :ventas/nota-credito))))))
    (testing "un valor que SARA no ofreció no cuenta como elección"
      (p "/chat/decir" "texto" "anula la factura del cliente")
      (p "/chat/elegir" "campo" "factura" "valor" (pr-str (random-uuid)))
      (is (= 1 (count (hechos b :ventas/nota-credito)))))
    (testing "lo bloqueado ofrece completar en el formulario, con lo que ya se sabe"
      (let [h (p "/chat/decir" "texto" "consulta el estado de la compra")]
        (is (str/includes? h "Completar en el formulario"))
        (is (str/includes? h "op=compras%2Fconsultar-estado"))))
    (testing "la página cuenta las llamadas al modelo y sus tokens"
      (is (re-find #"\d+ llamadas al modelo; tokens de entrada: \d+" (p "/chat"))))
    (testing "cada conversación es de una persona y una empresa; nueva conversación la vacía"
      (is (not (str/includes? (p "/chat" "actor" "persona/luis") "Registrar solicitud")))
      (is (not (str/includes? (p "/chat/nueva") "Registrar solicitud"))))
    (testing "todo pasó por MARCO, con origen SARA, y la bitácora cumple sus invariantes"
      (is (seq (bit/decisiones m/*alm* (:empresa b) {:decision/origen :sara})))
      (is (= [] (bit/verificar m/*alm* (:empresa b)))))))

(deftest sin-modelo-el-chat-lo-dice
  (let [a   (m/empresa! {})
        ctx (web/contexto {(:empresa a) a} nil)
        e   (subs (str (:empresa a)) 1)
        h   (web/pagina-de ctx "/chat/decir" {"e" e "actor" "persona/ana" "texto" "hola"})]
    (is (str/includes? h "No hay modelo de lenguaje"))
    (is (re-find #"<button type=\"submit\" disabled>Enviar" h))
    (is (empty? (bit/decisiones m/*alm* (:empresa a) {:decision/origen :sara})))))

(deftest una-conversacion-larga-no-crece-sin-limite
  (testing "el modelo recibe los últimos mensajes, empezando por uno de la persona"
    (let [a (m/empresa! {})
          recibidos (atom [])
          modelo (fn [pedido] (swap! recibidos conj (count (:mensajes pedido)))
                   {:texto "Hola." :uso {:entrada 1 :salida 1}})
          conv (reduce (fn [c i] (sl/turno! a :persona/ana modelo c (str "frase " i))) (sl/conversacion) (range 30))]
      (is (= 60 (count (:mensajes conv))) "la conversación guarda todo")
      (is (<= (apply max @recibidos) sl/mensajes-maximos))
      (is (= :persona (:rol (first (sl/recortar (:mensajes conv))))))
      (is (= 30 (count (:uso conv)))))))
