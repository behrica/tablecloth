(ns tablecloth.api.join-concat-ds
  (:refer-clojure :exclude [concat])
  (:require [tech.v3.dataset :as ds]
            [tech.v3.dataset.join :as j]
            [tech.v3.dataset.column :as col]

            [clojure.set :as s]
            
            [tablecloth.api.join-separate :refer [join-columns]]
            [tablecloth.api.missing :refer [select-missing drop-missing]]
            [tablecloth.api.columns :refer [drop-columns]]
            [tablecloth.api.utils :refer [column-names]]))

;; joins

(defn- multi-join
  [ds-left ds-right join-fn cols options]
  (let [join-column-name (gensym "^___join_column_hash")
        dsl (join-columns ds-left join-column-name cols {:result-type hash
                                                         :drop-columns? false})
        dsr (join-columns ds-right join-column-name cols {:result-type hash
                                                          :drop-columns? false})
        joined-ds (join-fn join-column-name dsl dsr options)]
    (-> joined-ds
        (ds/drop-columns [join-column-name (-> joined-ds
                                               (meta)
                                               :right-column-names
                                               (get join-column-name))]))))

(defmacro make-join-fns
  [join-fns-list]
  `(do
     ~@(for [[n impl] join-fns-list]
         `(defn ~n
            ([~'ds-left ~'ds-right ~'columns-selector] (~n ~'ds-left ~'ds-right ~'columns-selector nil))
            ([~'ds-left ~'ds-right ~'columns-selector ~'options]
             (let [cols# (column-names ~'ds-left ~'columns-selector)
                   opts# (or ~'options {})]
               (if (= 1 (count cols#))
                 (~impl (first cols#) ~'ds-left ~'ds-right opts#)
                 (multi-join ~'ds-left ~'ds-right ~impl cols# opts#))))))))

(make-join-fns [[left-join j/left-join]
                [right-join j/right-join]
                [inner-join j/inner-join]])


(defn full-join
  ([ds-left ds-right columns-selector] (full-join ds-left ds-right columns-selector nil))
  ([ds-left ds-right columns-selector options]
   (let [rj (right-join ds-left ds-right columns-selector options)]
     (-> (->> rj
              (ds/concat (left-join ds-left ds-right columns-selector options)))
         (ds/unique-by identity)
         (with-meta (assoc (meta rj) :name "full-join"))))))

(defn semi-join
  ([ds-left ds-right columns-selector] (semi-join ds-left ds-right columns-selector nil))
  ([ds-left ds-right columns-selector options]
   (let [lj (left-join ds-left ds-right columns-selector options)]
     (-> lj
         (drop-missing)
         (drop-columns (vals (:right-column-names (meta lj))))
         (ds/unique-by identity)
         (vary-meta assoc :name "semi-join")))))

(defn anti-join
  ([ds-left ds-right columns-selector] (anti-join ds-left ds-right columns-selector nil))
  ([ds-left ds-right columns-selector options]
   (let [lj (left-join ds-left ds-right columns-selector options)]
     (-> lj
         (select-missing)
         (drop-columns (vals (:right-column-names (meta lj))))
         (ds/unique-by identity)
         (vary-meta assoc :name "anti-join")))))

(defn asof-join
  ([ds-left ds-right colname] (asof-join ds-left ds-right colname nil))
  ([ds-left ds-right colname options]
   (j/left-join-asof colname ds-left ds-right options)))

;; set operations

(defn intersect
  ([ds-left ds-right] (intersect ds-left ds-right nil))
  ([ds-left ds-right options]
   (-> (semi-join ds-left ds-right (distinct (clojure.core/concat (ds/column-names ds-left)
                                                                  (ds/column-names ds-right))) options)
       (vary-meta assoc :name "intersection"))))

(defn difference
  ([ds-left ds-right] (difference ds-left ds-right nil))
  ([ds-left ds-right options]
   (-> (anti-join ds-left ds-right (distinct (clojure.core/concat (ds/column-names ds-left)
                                                                  (ds/column-names ds-right))) options)
       (vary-meta assoc :name "difference"))))

(defn union
  [ds & datasets]
  (-> (apply ds/concat ds datasets)
      (ds/unique-by identity)
      (vary-meta assoc :name "union")))

(defn- add-empty-missing-column
  [ds name]
  (let [cnt (ds/row-count ds)]
    (->> (repeat cnt nil)
         (col/new-column name)
         (ds/add-column ds))))

(defn- add-empty-missing-columns
  [ds-left ds-right]
  (let [cols-l (set (ds/column-names ds-left))
        cols-r (set (ds/column-names ds-right))
        diff-l (s/difference cols-r cols-l)
        diff-r (s/difference cols-l cols-r)
        ds-left+ (reduce add-empty-missing-column ds-left diff-l)
        ds-right+ (reduce add-empty-missing-column ds-right diff-r)]
    (ds/concat ds-left+ ds-right+)))

(defn bind
  [ds & datasets]
  (reduce #(add-empty-missing-columns %1 %2) ds datasets))

;;

(defn append
  [ds & datasets]
  (reduce #(ds/append-columns %1 (ds/columns %2)) ds datasets))
