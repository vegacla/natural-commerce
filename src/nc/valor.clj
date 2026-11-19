(ns nc.valor
  "Valores con unidad (notación, secciones 0 y 3.3). Un valor se escribe igual
  en datos y en expresiones: [:monto 500000 :CLP], [:cantidad 20 :un], [:% 5],
  [:dias 180], [:meses 1]. Un número suelto no es un monto ni una cantidad.")

(def tipos #{:monto :cantidad :% :dias :meses})

(defn valor? [x]
  (and (vector? x) (contains? tipos (first x)) (number? (second x))))

(defn tipo [x] (when (valor? x) (first x)))
(defn numero [x] (second x))
(defn unidad [x] (nth x 2 nil))

(defn monto? [x] (and (valor? x) (= :monto (first x)) (keyword? (unidad x))))
(defn cantidad? [x] (and (valor? x) (= :cantidad (first x)) (keyword? (unidad x))))

(defn- error! [op a b]
  (throw (ex-info (str "No se puede aplicar " op " a " (pr-str a) " y " (pr-str b)
                       ": tipos o unidades distintos")
                  {:nc/error :unidades-distintas :op op :a a :b b})))

(defn- con-numero [x n] (assoc x 1 n))

(defn- misma-unidad? [a b]
  (and (valor? a) (valor? b) (= (tipo a) (tipo b)) (= (unidad a) (unidad b))))

(defn- lineal
  "Suma o resta. El 0 suelto es neutro, para poder sumar colecciones vacías."
  [op f a b]
  (cond
    (and (number? a) (number? b)) (f a b)
    (misma-unidad? a b)           (con-numero a (f (numero a) (numero b)))
    (and (valor? a) (number? b) (zero? b)) a
    (and (number? a) (zero? a) (valor? b)) (con-numero b (f 0 (numero b)))
    :else                         (error! op a b)))

(defn sumar [a b] (lineal :+ + a b))
(defn restar [a b] (lineal :- - a b))

(defn multiplicar
  "Número por valor escala el valor. Cantidad por monto (precio unitario) da un
  monto. Cualquier cosa por un porcentaje se escala a su centésima parte."
  [a b]
  (cond
    (and (number? a) (number? b)) (* a b)
    (= :% (tipo b))               (multiplicar a (/ (numero b) 100))
    (= :% (tipo a))               (multiplicar (/ (numero a) 100) b)
    (and (number? a) (valor? b))  (con-numero b (* a (numero b)))
    (and (valor? a) (number? b))  (con-numero a (* (numero a) b))
    (and (cantidad? a) (monto? b)) (con-numero b (* (numero a) (numero b)))
    (and (monto? a) (cantidad? b)) (con-numero a (* (numero a) (numero b)))
    :else                         (error! :* a b)))

(defn dividir [a b]
  (cond
    (and (number? a) (number? b)) (/ a b)
    (and (valor? a) (number? b))  (con-numero a (/ (numero a) b))
    (misma-unidad? a b)           (/ (numero a) (numero b))
    :else                         (error! :/ a b)))

(defn redondear
  ([x] (redondear x 0))
  ([x decimales]
   (let [r (fn [n] (let [d (.setScale (bigdec n) (int decimales) java.math.RoundingMode/HALF_UP)]
                     (if (zero? decimales) (.longValueExact d) d)))]
     (if (valor? x) (con-numero x (r (numero x))) (r x)))))

(defn- cero? [x] (and (number? x) (zero? x)))

(defn comparar
  "Como compare, pero rechaza comparar valores de distinto tipo o unidad, o un
  valor con un número suelto. El 0 suelto, que es la suma de una colección vacía,
  se compara con cualquier valor."
  [a b]
  (cond
    (misma-unidad? a b)         (compare (numero a) (numero b))
    (and (valor? a) (cero? b))  (compare (numero a) 0)
    (and (cero? a) (valor? b))  (compare 0 (numero b))
    (or (valor? a) (valor? b))  (error! :comparar a b)
    :else                       (compare a b)))

(defn igual? [a b]
  (if (or (valor? a) (valor? b))
    (zero? (comparar a b))
    (= a b)))
