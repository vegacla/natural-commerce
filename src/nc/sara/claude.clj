(ns nc.sara.claude
  "El modelo de SARA con Claude, por la API de mensajes (SDK de Java). Recibe lo
  que arma nc.sara.lenguaje y devuelve la herramienta elegida o el texto. No
  sabe nada del ERP: las herramientas y el sistema vienen de SARA.

  Usa Claude Opus 5.5 con esfuerzo medio. Si el modelo rechaza la petición, el
  servidor reintenta con otro modelo (fallbacks), y si aun así la rechaza, SARA
  lo dice como texto."
  (:import (com.anthropic.client AnthropicClient)
           (com.anthropic.client.okhttp AnthropicOkHttpClient)
           (com.anthropic.core JsonValue)
           (com.anthropic.models.messages ContentBlock Message MessageCreateParams OutputConfig Tool$InputSchema$Properties$Builder
                                          OutputConfig$Effort Tool Tool$InputSchema Tool$InputSchema$Properties
                                          ToolUseBlock)
           (java.util Map)))

(def modelo-por-defecto "claude-opus-5-5")

(defn- herramienta ^Tool [{:keys [nombre descripcion campos]}]
  (let [props (reduce (fn [^Tool$InputSchema$Properties$Builder b {:keys [nombre descripcion opciones]}]
                        (.putAdditionalProperty b nombre
                                                (JsonValue/from (cond-> {"type" "string" "description" descripcion}
                                                                  (seq opciones) (assoc "enum" opciones)))))
                      (Tool$InputSchema$Properties/builder)
                      campos)]
    (-> (Tool/builder)
        (.name ^String nombre)
        (.description ^String descripcion)
        (.inputSchema (-> (Tool$InputSchema/builder)
                          (.properties (.build props))
                          (.required ^java.util.List (vec (keep #(when (:obligatorio %) (:nombre %)) campos)))
                          (.build)))
        (.build))))

(defn- parametros ^MessageCreateParams [modelo {:keys [sistema mensajes herramientas]}]
  (let [b (-> (MessageCreateParams/builder)
              (.model ^String modelo)
              (.maxTokens 16000)
              (.system ^String sistema)
              (.outputConfig (-> (OutputConfig/builder) (.effort OutputConfig$Effort/MEDIUM) (.build)))
              ;; Si el modelo rechaza, el servidor reintenta con otro.
              (.putAdditionalHeader "anthropic-beta" "server-side-fallback-2026-07-01")
              (.putAdditionalBodyProperty "fallbacks" (JsonValue/from "default")))]
    (doseq [h herramientas] (.addTool b (herramienta h)))
    (doseq [{:keys [rol texto]} mensajes]
      (if (= :persona rol) (.addUserMessage b ^String texto) (.addAssistantMessage b ^String texto)))
    (.build b)))

(defn- respuesta [^Message m]
  (let [bloques (.content m)
        uso     (some (fn [^ContentBlock b] (.orElse (.toolUse b) nil)) bloques)
        texto   (apply str (keep (fn [^ContentBlock b] (some-> (.orElse (.text b) nil) (.text))) bloques))]
    (cond
      (= "refusal" (str (.orElse (.stopReason m) nil)))
      {:texto "No puedo ayudar con eso." :rechazo true}

      uso
      {:llamada {:herramienta (.name ^ToolUseBlock uso)
                 :entradas    (into {} (map (fn [[k v]] [(keyword k) (str v)]))
                                    (.convert (._input ^ToolUseBlock uso) Map))}
       :texto   texto}

      :else {:texto texto})))

(defn modelo
  "Una función de modelo para nc.sara.lenguaje. Sin cliente, lo arma con las
  credenciales del entorno (ANTHROPIC_API_KEY)."
  ([] (modelo (AnthropicOkHttpClient/fromEnv) modelo-por-defecto))
  ([^AnthropicClient cliente id-modelo]
   (fn [pedido]
     (respuesta (.create (.messages cliente) (parametros id-modelo pedido))))))

(defn parametros-de-prueba
  "Los parámetros que se enviarían, sin enviarlos: para revisar la forma."
  [pedido]
  (parametros modelo-por-defecto pedido))
