(ns nc.respaldo-test
  "Revisión de madurez operativa de XTDB (sección 6 del prototipo): respaldar y
  restaurar la base con los datos de ejemplo, en disco local. Ver
  docs/revision-xtdb.md."
  (:require [clojure.test :refer [deftest is testing]]
            [nc.almacen :as alm]
            [nc.ejecutor :as ej]
            [nc.ejemplo :as ejemplo]
            [xtdb.api :as xt]
            [xtdb.db-catalog :as db]
            [xtdb.log :as xt-log])
  (:import (java.io File)
           (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.time Duration Instant)))

;; --- Disco ---------------------------------------------------------------------

(defn- carpeta ^Path [] (Files/createTempDirectory "nc-respaldo" (make-array FileAttribute 0)))

(defn- copiar!
  "Copia una carpeta completa, como lo haría un respaldo de archivos."
  [^Path desde ^Path hacia]
  (with-open [s (Files/walk desde (make-array java.nio.file.FileVisitOption 0))]
    (doseq [^Path p (iterator-seq (.iterator s))
            :let [q (.resolve hacia (.relativize desde p))]]
      (if (Files/isDirectory p (make-array LinkOption 0))
        (Files/createDirectories q (make-array FileAttribute 0))
        (Files/copy p q (make-array java.nio.file.CopyOption 0))))))

(defn- borrar! [^Path p]
  (doseq [^File f (reverse (file-seq (.toFile p)))] (.delete f)))

(defn- config
  ([^Path raiz] (config raiz {}))
  ([^Path raiz log]
   {:log     [:local (merge {:path (.resolve raiz "log")} log)]
    :storage [:local {:path (.resolve raiz "almacen")}]}))

(defn- archivos [^Path p]
  (count (filter #(.isFile ^File %) (file-seq (.toFile p)))))

;; --- Lo que se compara -----------------------------------------------------------

(def ^:private tablas [:hechos :compromisos :saldos :entidades :politicas])

(defn- foto
  "Todo lo que una empresa tiene, con sus dos tiempos, y la historia de cada
  compromiso. Si dos fotos son iguales, la restauración no perdió ni cambió nada."
  ([a empresas] (foto a empresas {}))
  ([a empresas opts]
   (into (sorted-map)
         (for [e empresas]
           [e {:marca       (alm/marca a e)
               :tablas      (into {} (for [t tablas]
                                       [t (set (alm/buscar a e t {} opts))]))
               :historias   (into {} (for [c (alm/buscar a e :compromisos {} opts)
                                           :let [id (:compromiso/id c)]]
                                       [id (alm/historia a e :compromisos id)]))}]))))

(defn- cerrar-bloque!
  "Pide a XTDB que pase al almacenamiento lo que solo está en el log, y espera a
  que el bloque quede escrito."
  [{:keys [nodo]} ^Path almacen]
  (let [antes (archivos almacen)]
    (xt-log/send-flush-block-msg! (db/primary-db nodo))
    (xt-log/sync-node nodo (Duration/ofSeconds 30))
    (loop [n 0]
      (when (and (= antes (archivos almacen)) (< n 100))
        (Thread/sleep 100)
        (recur (inc n))))))

(defn- orden! [estado]
  (ej/ejecutar! estado :persona/ana :compras/emitir-orden
                {:proveedor :proveedor/acme :producto :P-300 :cantidad [:cantidad 1 :un]
                 :precio-unitario [:monto 15000 :CLP]}
                {:confirmado true}))

;; --- Respaldo en frío ------------------------------------------------------------

(deftest respaldo-en-frio-de-log-y-almacenamiento
  (let [origen (carpeta) respaldo (carpeta)
        a (alm/abrir (config origen))
        estados (ejemplo/cargar! a)
        empresas (keys estados)
        medio (Instant/now)
        _ (orden! (:empresa-a estados))
        antes (foto a empresas)
        antes-medio (foto a empresas {:al medio})]
    (alm/cerrar a)
    (copiar! origen respaldo)
    (borrar! origen)
    (let [r (alm/abrir (config respaldo))]
      (try
        (testing "con el nodo detenido, copiar las dos carpetas basta: todo vuelve igual, con sus dos tiempos"
          (is (= antes (foto r empresas))))
        (testing "y se puede ver la base como era antes de la última escritura (D17)"
          (is (= antes-medio (foto r empresas {:al medio}))))
        (testing "la restaurada sigue operando, con la concurrencia de cada empresa intacta (D74)"
          (let [estados (ejemplo/vistas r)]
            (is (= :ejecutado (:estado (orden! (:empresa-a estados)))))
            (is (= (inc (get-in antes [:empresa-a :marca])) (alm/marca r :empresa-a)))
            (is (= (get-in antes [:empresa-b :marca]) (alm/marca r :empresa-b)))))
        (finally (alm/cerrar r) (borrar! respaldo))))))

;; --- Solo el almacenamiento ------------------------------------------------------

(deftest restaurar-solo-el-almacenamiento
  (let [origen (carpeta) respaldo (carpeta)
        a (alm/abrir (config origen))
        estados (ejemplo/cargar! a)
        empresas (keys estados)
        _ (cerrar-bloque! a (.resolve origen "almacen"))
        en-bloque (foto a empresas)
        _ (orden! (:empresa-a estados))]
    (alm/cerrar a)
    (copiar! (.resolve origen "almacen") (.resolve respaldo "almacen"))
    (borrar! origen)
    (try
      (testing "sin el log, XTDB no arranca solo: exige declarar una época nueva"
        (is (thrown-with-msg? Exception #"invalid transaction log state \(the log is empty\)"
                              (alm/cerrar (alm/abrir (config respaldo))))))
      (let [r (alm/abrir (config respaldo {:epoch 1}))]
        (try
          (testing "con una época nueva vuelve todo lo que estaba en bloques; lo posterior se pierde"
            (is (= en-bloque (foto r empresas))))
          (testing "y sigue operando"
            (is (= :ejecutado (:estado (orden! (:empresa-a (ejemplo/vistas r)))))))
          (finally (alm/cerrar r))))
      (finally (borrar! respaldo)))))
