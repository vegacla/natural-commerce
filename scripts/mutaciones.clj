(ns mutaciones
  "Pruebas de mutación de los resguardos (scripts/mutaciones.edn). Cada mutación
  rompe un resguardo en el código; alguna prueba debe fallar. Si ninguna falla,
  falta una prueba.

  Corre todo en una sola JVM: carga el proyecto y las pruebas una vez, y por
  cada mutación cambia el archivo, recarga su namespace, corre las pruebas y
  restaura el archivo. Las pruebas se corren por archivo, de la más rápida a la
  más lenta, y se para en el primero que falla: basta uno para detectarla.

  Uso: deps -M:mutaciones [desde hasta] [completa]
  `completa` corre todos los archivos de prueba y lista todas las que fallan."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :as t]))

(def ^:private orden-conocido
  "Los archivos de prueba, de los más rápidos a los más lentos (2026-11-19)."
  '[nc.resguardos-test nc.catalogo-test nc.expresion-test nc.maestros-test nc.extension-test
    nc.separacion-test nc.almacen-test nc.planificacion-test nc.concurrencia-test nc.sara-test
    nc.bitacora-test nc.formas-test nc.verbos-test nc.legal-test nc.marco-test nc.respaldo-test])

(def pruebas
  "Todos los archivos de prueba de test/nc: los conocidos en su orden, y uno
  nuevo al final, para que ninguno quede fuera."
  (let [todos (for [f (file-seq (java.io.File. "test/nc"))
                    :let [n (.getName f)]
                    :when (.endsWith n "_test.clj")]
                (symbol (str "nc." (-> n (str/replace #"\.clj$" "") (str/replace "_" "-")))))
        lugar (into {} (map-indexed (fn [i n] [n i])) orden-conocido)]
    (vec (sort-by #(get lugar % 999) todos))))

(defn- namespace-de [archivo]
  (-> archivo (str/replace #"^src/" "") (str/replace #"\.clj$" "")
      (str/replace "/" ".") (str/replace "_" "-") symbol))

(defn- correr
  "Corre un archivo de pruebas sin mostrar su salida. Devuelve las pruebas que
  fallaron o dieron error."
  [n]
  (let [fallan (atom (sorted-set))
        report t/report]
    (binding [t/*test-out* (java.io.StringWriter.)
              t/report     (fn [m]
                             (when (#{:fail :error} (:type m))
                               (swap! fallan conj (or (some-> t/*testing-vars* first meta :name) n)))
                             (report m))]
      (t/run-tests n))
    @fallan))

(defn- detectar
  "Corre los archivos de prueba hasta el primero que falla (o todos)."
  [completa?]
  (loop [[n & mas] pruebas, hallado {}]
    (cond
      (nil? n) hallado
      :else (let [f (correr n)
                  hallado (cond-> hallado (seq f) (assoc n f))]
              (if (and (seq f) (not completa?)) hallado (recur mas hallado))))))

(defn- mutar
  "El texto del archivo con la mutación, o nil si su patrón no está."
  [texto {:keys [buscar poner agregar]}]
  (let [texto (str/replace texto "\r\n" "\n")]
    (cond
      agregar                    (str texto agregar)
      (str/includes? texto buscar) (str/replace-first texto buscar poner))))

(defn- probar [{:keys [archivo] :as m} completa?]
  (let [original (slurp archivo)
        mutado   (mutar original m)
        ns-mut   (namespace-de archivo)]
    (if (nil? mutado)
      {:resultado :patron-no-encontrado}
      (try
        (spit archivo mutado)
        (if-let [e (try (require ns-mut :reload) nil (catch Throwable e e))]
          {:resultado :detectada :por "no compila" :detalle (ex-message e)}
          (let [hallado (detectar completa?)]
            {:resultado (if (seq hallado) :detectada :no-detectada) :por hallado}))
        (finally
          (spit archivo original)
          (require ns-mut :reload))))))

(defn -main [& args]
  (let [completa? (some #{"completa"} args)
        [desde hasta] (map parse-long (remove #{"completa"} args))
        todas  (edn/read-string (slurp "scripts/mutaciones.edn"))
        desde  (or desde 0)
        hasta  (min (or hasta (count todas)) (count todas))]
    (apply require pruebas)
    (print "Línea de base: ") (flush)
    (let [base (detectar true)]
      (when (seq base)
        (println "las pruebas fallan sin mutar nada:" base)
        (System/exit 2))
      (println "todas las pruebas pasan."))
    (println (str "Mutaciones " desde " a " (dec hasta) " de " (count todas)))
    (let [resultados
          (doall
           (for [i (range desde hasta)
                 :let [m (nth todas i)
                       t0 (System/nanoTime)
                       {:keys [resultado por]} (probar m completa?)]]
             (do (println i (:nombre m) "→" (name resultado)
                          (if (map? por) (pr-str (update-vals por vec)) (or por ""))
                          (str "(" (quot (- (System/nanoTime) t0) 1000000) " ms)"))
                 (flush)
                 resultado)))]
      (println (count (filter #{:detectada} resultados)) "de" (count resultados) "detectadas")
      (shutdown-agents)
      (System/exit (if (every? #{:detectada} resultados) 0 1)))))
