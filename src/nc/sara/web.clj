(ns nc.sara.web
  "Un cliente web mínimo de SARA (etapa 6): dibuja como HTML lo que describe
  nc.sara. Usa el servidor HTTP del JDK, sin dependencias. Arranca con los datos
  de ejemplo en un almacén en memoria, que se pierde al detenerlo.

  El chat (/chat) conversa con SARA por nc.sara.lenguaje (D100, D110): la persona
  escribe, confirma con un botón y elige con un botón entre las opciones que SARA
  ofreció. Sin ANTHROPIC_API_KEY no hay modelo, y el chat lo dice.

  Uso: .\\sara.ps1 (o deps -M:xtdb:sara) y abrir http://localhost:8081."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [nc.almacen :as alm]
            [nc.ejecutor :as ej]
            [nc.ejemplo :as ejemplo]
            [nc.estado :as est]
            [nc.sara :as sara]
            [nc.sara.lenguaje :as sl])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URLDecoder URLEncoder)))

;; --- HTML ------------------------------------------------------------------------

(defn- esc [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(def ^:private vacias #{:input :br :meta})

(defn html
  "Hiccup mínimo: [:etiqueta.clase {atributos} hijos...]. El texto se escapa."
  [x]
  (cond
    (vector? x) (let [[tag & mas]   x
                      [tag & clases] (str/split (name tag) #"\.")
                      tag           (keyword tag)
                      [attrs hijos] (if (map? (first mas)) [(first mas) (rest mas)] [{} mas])
                      attrs         (cond-> attrs (seq clases) (assoc :class (str/join " " clases)))
                      a (apply str (for [[k v] attrs :when (and (some? v) (not (false? v)))]
                                     (if (true? v) (str " " (name k)) (str " " (name k) "=\"" (esc v) "\""))))]
                  (if (vacias tag)
                    (str "<" (name tag) a ">")
                    (str "<" (name tag) a ">" (apply str (map html hijos)) "</" (name tag) ">")))
    (seq? x)    (apply str (map html x))
    (nil? x)    ""
    :else       (esc x)))

(def ^:private estilo "
:root{--fondo:#f7f7f5;--tinta:#1d1d1b;--suave:#6b6b66;--borde:#d9d9d4;--caja:#fff;--acento:#2f5d8a;
--ok:#2e7d4f;--pendiente:#9a6a00;--prohibido:#b3261e}
@media (prefers-color-scheme:dark){:root{--fondo:#171716;--tinta:#ececea;--suave:#a3a39e;--borde:#3a3a37;
--caja:#21211f;--acento:#8fb6de;--ok:#7cc79a;--pendiente:#e0b25a;--prohibido:#f08a82}}
*{box-sizing:border-box}body{margin:0;font:15px/1.5 system-ui,sans-serif;background:var(--fondo);color:var(--tinta)}
header{display:flex;flex-wrap:wrap;gap:12px;align-items:center;padding:10px 16px;border-bottom:1px solid var(--borde);background:var(--caja)}
header select{width:auto}header a.marca{font-weight:600;color:var(--tinta);text-decoration:none;margin-right:auto}
main{max-width:980px;margin:0 auto;padding:16px}a{color:var(--acento)}
h1{font-size:22px;margin:8px 0 4px}h2{font-size:17px;margin:20px 0 8px}
.caja{background:var(--caja);border:1px solid var(--borde);border-radius:8px;padding:14px;margin:10px 0}
label{display:block;font-weight:600;margin-top:12px}.nota{color:var(--suave);font-size:13px;font-weight:400}
input,select{font:inherit;padding:6px 8px;border:1px solid var(--borde);border-radius:6px;background:var(--caja);color:var(--tinta);width:100%;max-width:520px}
button{font:inherit;padding:7px 14px;border-radius:6px;border:1px solid var(--acento);background:var(--acento);color:#fff;cursor:pointer;margin:14px 8px 0 0}
button.sec{background:transparent;color:var(--acento)}button[disabled]{opacity:.45;cursor:not-allowed}
.error{color:var(--prohibido);font-size:13px}.estado{display:inline-block;padding:1px 8px;border-radius:10px;font-size:13px;border:1px solid}
.listo,.satisfecho,.ejecutado,.consultado{color:var(--ok)}.bloqueado,.pendiente,.indeterminado,.por-confirmar{color:var(--pendiente)}.prohibido{color:var(--prohibido)}
ul.avisos{padding-left:18px}ul.avisos li{margin:4px 0}
.tabla{overflow-x:auto}table{border-collapse:collapse;font-size:13px;width:100%}
th,td{text-align:left;padding:5px 8px;border-bottom:1px solid var(--borde);white-space:nowrap}th{color:var(--suave);font-weight:600}
.ops{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:8px}
.ops a{display:block;padding:10px;border:1px solid var(--borde);border-radius:8px;background:var(--caja);text-decoration:none}
.chat{display:flex;flex-direction:column;gap:8px;margin:12px 0}
.msg{max-width:min(640px,92%);padding:8px 12px;border-radius:10px;border:1px solid var(--borde);background:var(--caja);white-space:pre-wrap}
.msg.persona{align-self:flex-end;background:var(--acento);color:#fff;border-color:var(--acento)}
.msg.sara{align-self:flex-start}.msg .estado{margin-right:6px}
form.decir{display:flex;gap:8px;align-items:flex-start;margin-top:8px}form.decir input{max-width:none;flex:1}form.decir button{margin:0}
form.en-linea{display:inline}
")

;; --- Parámetros ------------------------------------------------------------------

(defn- decodificar [s] (URLDecoder/decode (str s) "UTF-8"))
(defn- enc [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- parametros [s]
  (into {} (for [par (str/split (or s "") #"&") :when (seq par)
                 :let [[k v] (str/split par #"=" 2)]]
             [(decodificar k) (decodificar v)])))

(defn- url [ruta params]
  (str ruta "?" (str/join "&" (for [[k v] params :when (some? v)] (str (enc (name k)) "=" (enc v))))))

(defn- clave [s] (when-not (str/blank? s) (keyword s)))

;; --- Páginas ---------------------------------------------------------------------

(defn- pagina [{:keys [estados]} {:strs [e actor]} titulo & cuerpo]
  (let [estado (get estados (clave e))]
    (str
     "<!doctype html>"
     (html
     (list
      [:meta {:charset "utf-8"}] [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
      [:title (str titulo " · SARA")] [:style estilo]
      [:header
       [:a.marca {:href (url "/" {:e e :actor actor})} "SARA mínima"]
       [:form {:method "get" :action "/"}
        [:select {:name "e" :onchange "this.form.submit()"}
         (for [k (keys estados)] [:option {:value (name k) :selected (= (name k) e)} (sara/titulo k)])]
        " "
        [:select {:name "actor" :onchange "this.form.submit()"}
         (for [[id etiqueta] (when estado (sara/opciones estado [:ref :persona]))]
           [:option {:value (subs (str id) 1) :selected (= (subs (str id) 1) actor)} etiqueta])]]
       [:a {:href (url "/chat" {:e e :actor actor})} "Chat"]
       [:a {:href (url "/datos" {:e e :actor actor})} "Datos"]]
      [:main cuerpo])))))

(defn- de-la-empresa [ctx {:strs [e]}] (get (:estados ctx) (clave e)))

(defn- inicio [ctx params]
  (let [estado (de-la-empresa ctx params)
        ops    (sara/operaciones estado)
        enlace (fn [[op _]] [:a {:href (url "/op" (assoc (select-keys params ["e" "actor"]) :op (subs (str op) 1)))}
                             (sara/titulo op)])]
    (pagina ctx params "Inicio"
            [:h1 "Operaciones"]
            [:p.nota "Cada formulario sale del contrato de la operación y de lo que dice el resolvedor. "
             "SARA no tiene código propio para ninguna."]
            [:h2 "Registrar"] [:div.ops (map enlace (filter #(= :efecto (:op/tipo (val %))) ops))]
            [:h2 "Consultar"] [:div.ops (map enlace (remove #(= :efecto (:op/tipo (val %))) ops))])))

(defn- campo [{:keys [nombre etiqueta tipo obligatorio texto opciones problema falta viene-de]}]
  (let [n (name nombre)]
    (list
     [:label {:for n} etiqueta (when obligatorio " *")
      (when viene-de [:span.nota (str " · sale de «" (sara/titulo viene-de) "»")])]
     (cond
       opciones       [:select {:id n :name n}
                       [:option {:value ""} "—"]
                       (for [[valor etiqueta] opciones]
                         [:option {:value (pr-str valor) :selected (= (pr-str valor) texto)} etiqueta])]
       (= :fecha tipo) [:input {:id n :name n :type "date" :value texto}]
       :else          [:input {:id n :name n :value texto
                               :placeholder (case tipo :monto "30000 CLP" :cantidad "20 un" nil)}])
     (cond
       problema [:div.error problema]
       falta    [:div.error "Falta"]))))

(defn- tabla [{:keys [columnas filas]}]
  (if (empty? filas)
    [:p.nota "Sin datos."]
    [:div.tabla [:table [:thead [:tr (for [c columnas] [:th (:titulo c)])]]
                 [:tbody (for [f filas] [:tr (for [x f] [:td x])])]]]))

(defn- formulario [ctx {:strs [actor op] :as params}]
  (let [estado (de-la-empresa ctx params)
        op     (clave op)
        actor  (clave actor)
        f      (sara/formulario estado actor op (sara/entradas-de-texto estado op params))
        listo? (= :listo (:estado f))]
    (pagina ctx params (:titulo f)
            [:h1 (:titulo f)]
            [:span {:class (str "estado " (name (:estado f)))} (sara/titulo (:estado f))]
            (when (and listo? (= :efecto (:tipo f)))
              [:span.nota (if (= :eximida (:confirmacion f)) "  sin confirmación" "  pide confirmación")])
            (when (seq (:avisos f))
              [:ul.avisos (for [{:keys [nivel texto]} (:avisos f)] [:li {:class (name nivel)} texto])])
            [:form.caja {:method "get" :action "/op"}
             (for [k ["e" "actor" "op"]] [:input {:type "hidden" :name k :value (get params k)}])
             (map campo (:campos f))
             [:button.sec {:type "submit"} "Revisar"]
             [:button {:type "submit" :formaction "/ejecutar" :formmethod "post" :disabled (not listo?)}
              (if (= :efecto (:tipo f)) "Confirmar y ejecutar" "Consultar")]]
            (when (seq (:reglas f))
              [:p.nota "Reglas: " (sara/mostrar (:reglas f))]))))

(defn- ejecutar [ctx {:strs [e actor op] :as params}]
  (let [estado (de-la-empresa ctx params)
        op     (clave op)
        r      (ej/ejecutar! estado (clave actor) op (sara/entradas-de-texto estado op params) {:confirmado true :origen :sara})]
    (if-not (#{:ejecutado :consultado} (:estado r))
      (formulario ctx params)
      (pagina ctx params (sara/titulo op)
              [:h1 (sara/titulo op)]
              [:span {:class (str "estado " (name (:estado r)))} (sara/titulo (:estado r))]
              (for [{:keys [nombre valor] t :tabla} (sara/salidas (:salidas r) #(sara/nombre estado %))]
                (list [:h2 nombre] (if t (tabla t) [:p valor])))
              (when-let [sig (seq (sara/siguientes estado (:salidas r)))]
                (list [:h2 "Siguiente"]
                      [:div.ops (for [{:keys [operacion titulo entradas]} sig]
                                  [:a {:href (url "/op" (merge {:e e :actor actor :op (subs (str operacion) 1)}
                                                               (update-vals entradas pr-str)))}
                                   titulo])]))))))

;; --- Chat (D110) -------------------------------------------------------------------

(defn- de-la-conversacion
  "La conversación de una persona en una empresa: la de nc.sara.lenguaje, y lo
  que se dijo turno a turno, para dibujarlo."
  [ctx {:strs [e actor]}]
  (get @(:conversaciones ctx) [e actor] {:conv (sl/conversacion) :turnos []}))

(defn- guardar! [ctx {:strs [e actor]} c] (swap! (:conversaciones ctx) assoc [e actor] c))

(defn- con-respuesta
  "Agrega un turno: lo que dijo o hizo la persona y lo que SARA respondió."
  [c conv persona]
  (-> c (assoc :conv conv) (update :turnos conj {:persona persona :respuesta (:respuesta conv)})))

(defn- boton [ruta params ocultos texto & [clase]]
  [:form.en-linea {:method "post" :action ruta}
   (for [[k v] (merge (select-keys params ["e" "actor"]) ocultos)] [:input {:type "hidden" :name k :value v}])
   [:button {:type "submit" :class clase} texto]])

(defn- acciones
  "Lo que la persona puede hacer con la última respuesta: confirmar, elegir una
  de las opciones que SARA ofreció, completar en el formulario o ver lo que salió."
  [estado params respuesta pendiente]
  (let [{:keys [opciones campo salidas]} respuesta]
    (case (:estado respuesta)
      :por-confirmar (boton "/chat/confirmar" params {} "Confirmar")
      :pregunta      (for [[valor etiqueta] opciones]
                       (boton "/chat/elegir" params {"campo" (name campo) "valor" (pr-str valor)} etiqueta "sec"))
      (:ejecutado :consultado)
      (for [{:keys [nombre valor] t :tabla} (sara/salidas salidas #(sara/nombre estado %))]
        (list [:h2 nombre] (if t (tabla t) [:p valor])))
      (when (and pendiente (#{:bloqueado :prohibido :error} (:estado respuesta)))
        [:a {:href (url "/op" (merge (select-keys params ["e" "actor"])
                                     {:op (subs (str (:operacion pendiente)) 1)}
                                     (update-vals (:entradas pendiente) pr-str)))}
         "Completar en el formulario"]))))

(defn- chat [ctx params & [aviso]]
  (let [estado (de-la-empresa ctx params)
        {:keys [conv turnos]} (de-la-conversacion ctx params)
        uso    (:uso conv)
        sin-modelo? (nil? (:modelo ctx))]
    (pagina ctx params "Chat"
            [:h1 "Conversar con SARA"]
            (if sin-modelo?
              [:p.error "No hay modelo de lenguaje: falta ANTHROPIC_API_KEY (sara.ps1 la toma de .env). Los formularios funcionan igual."]
              [:p.nota "Un modelo de lenguaje elige la operación; MARCO decide. Nada se ejecuta hasta que confirmas con el botón."])
            [:div.chat
             (for [{:keys [persona respuesta]} turnos]
               (list (when persona [:div.msg.persona persona])
                     [:div.msg.sara
                      (when-let [e (:estado respuesta)]
                        (when-not (= :texto e) [:span {:class (str "estado " (name e))} (sara/titulo e)]))
                      (:texto respuesta)]))
             (when-let [{:keys [respuesta]} (peek turnos)]
               [:div (acciones estado params respuesta (:pendiente conv))])]
            (when aviso [:p.error aviso])
            [:form.decir {:method "post" :action "/chat/decir"}
             (for [k ["e" "actor"]] [:input {:type "hidden" :name k :value (get params k)}])
             [:input {:name "texto" :autofocus true :autocomplete "off" :disabled sin-modelo?
                      :placeholder "Por ejemplo: necesito comprar 20 unidades del P-100"}]
             [:button {:type "submit" :disabled sin-modelo?} "Enviar"]]
            (boton "/chat/nueva" params {} "Nueva conversación" "sec")
            (when (seq uso)
              [:p.nota (format "%d llamadas al modelo; tokens de entrada: %d (la última, %d); de salida: %d."
                               (count uso) (reduce + (map :entrada uso)) (:entrada (peek uso))
                               (reduce + (map :salida uso)))]))))

(defn- chat-decir [ctx {:strs [actor texto] :as params}]
  (if (or (nil? (:modelo ctx)) (str/blank? texto))
    (chat ctx params)
    (let [c (de-la-conversacion ctx params)]
      (try
        (guardar! ctx params (con-respuesta c (sl/turno! (de-la-empresa ctx params) (clave actor) (:modelo ctx) (:conv c) texto)
                                            texto))
        (chat ctx params)
        (catch Exception ex
          (chat ctx params (str "El modelo no respondió: " (ex-message ex))))))))

(defn- chat-confirmar [ctx {:strs [actor] :as params}]
  (let [c (de-la-conversacion ctx params)]
    (guardar! ctx params (con-respuesta c (sl/confirmar! (de-la-empresa ctx params) (clave actor) (:conv c)) "Confirmar"))
    (chat ctx params)))

(defn- chat-elegir [ctx {:strs [actor campo valor] :as params}]
  (let [c      (de-la-conversacion ctx params)
        v      (edn/read-string valor)
        opcion (some #(when (= v (first %)) (second %)) (get-in c [:conv :respuesta :opciones]))]
    (guardar! ctx params (con-respuesta c (sl/elegir! (de-la-empresa ctx params) (clave actor) (:conv c) (keyword campo) v)
                                        (or opcion valor)))
    (chat ctx params)))

(defn- chat-nueva [ctx params]
  (guardar! ctx params {:conv (sl/conversacion) :turnos []})
  (chat ctx params))

(defn- datos [ctx params]
  (let [{:keys [almacen empresa] :as estado} (de-la-empresa ctx params)
        de    (fn [t] (alm/buscar almacen empresa t {}))
        tabla-de #(tabla (sara/tabla % (fn [id] (sara/nombre estado id))))]
    (pagina ctx params "Datos"
            [:h1 "Datos de " (sara/titulo empresa)]
            [:h2 "Compromisos"] (tabla-de (sort-by (comp str :compromiso/tipo) (de :compromisos)))
            [:h2 "Hechos"] (tabla-de (sort-by :hecho/en (de :hechos)))
            [:h2 "Saldos"] (tabla-de (de :saldos)))))

;; --- Servidor --------------------------------------------------------------------

(defn- responder [^HttpExchange x estado cuerpo]
  (let [b (.getBytes ^String cuerpo "UTF-8")]
    (.add (.getResponseHeaders x) "Content-Type" "text/html; charset=utf-8")
    (.sendResponseHeaders x estado (alength b))
    (with-open [o (.getResponseBody x)] (.write o b))))

(defn pagina-de
  "La página de una ruta, como texto HTML, o nil si la ruta no existe. Las pruebas
  la llaman sin servidor."
  [ctx ruta params]
  (let [params (merge {"e" (name (first (keys (:estados ctx)))) "actor" "persona/ana"} params)]
    (case ruta
      "/"               (inicio ctx params)
      "/op"             (formulario ctx params)
      "/ejecutar"       (ejecutar ctx params)
      "/datos"          (datos ctx params)
      "/chat"           (chat ctx params)
      "/chat/decir"     (chat-decir ctx params)
      "/chat/confirmar" (chat-confirmar ctx params)
      "/chat/elegir"    (chat-elegir ctx params)
      "/chat/nueva"     (chat-nueva ctx params)
      nil)))

(defn- manejar [ctx ^HttpExchange x]
  (let [ruta   (.getPath (.getRequestURI x))
        params (merge (parametros (.getRawQuery (.getRequestURI x)))
                      (when (= "POST" (.getRequestMethod x)) (parametros (slurp (.getRequestBody x)))))]
    (if-let [p (pagina-de ctx ruta params)]
      (responder x 200 p)
      (responder x 404 (html [:p "No existe " ruta])))))

(defn modelo-del-entorno
  "El modelo de Claude si hay ANTHROPIC_API_KEY; si no, nil."
  []
  (when-not (str/blank? (System/getenv "ANTHROPIC_API_KEY"))
    ((requiring-resolve 'nc.sara.claude/modelo))))

(defn contexto
  "Lo que usan las páginas: la vista de cada empresa, el modelo (o nil) y las
  conversaciones abiertas."
  [estados modelo]
  {:estados estados :modelo modelo :conversaciones (atom {})})

(defn iniciar
  "Carga los datos de ejemplo y arranca el servidor. Devuelve lo necesario para detenerlo."
  [puerto]
  (let [almacen  (alm/abrir)
        ctx      (contexto (ejemplo/cargar! almacen) (modelo-del-entorno))
        servidor (HttpServer/create (InetSocketAddress. "127.0.0.1" (int puerto)) 0)]
    (.createContext servidor "/"
                    (reify HttpHandler
                      (handle [_ x]
                        (try (manejar ctx x)
                             (catch Throwable t
                               (responder x 500 (html [:pre "Error: " (ex-message t) "\n" (pr-str (ex-data t))])))))))
    (.start servidor)
    {:servidor servidor :almacen almacen}))

(defn detener [{:keys [servidor almacen]}]
  (.stop ^HttpServer servidor 0)
  (alm/cerrar almacen))

(defn -main [& [puerto]]
  (let [p (Long/parseLong (or puerto "8081"))]
    (iniciar p)
    (println (str "SARA mínima en http://localhost:" p
                  (if (modelo-del-entorno) " (chat con Claude Haiku 5.5)" " (sin ANTHROPIC_API_KEY: el chat no tiene modelo)")))
    @(promise)))
