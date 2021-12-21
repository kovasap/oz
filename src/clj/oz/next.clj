(ns oz.next
  (:require ;[clojure.tools.analyzer :as ana]
            ;[clojure.tools.analyzer.env :as env]
            [taoensso.timbre :as log]
            [clojure.pprint :as pp]
            [clojure.spec.alpha :as s]
            [markdown-to-hiccup.decode :as md-decode]
            [markdown-to-hiccup.core :as md->hc]
            [clojure.test :as test :refer [testing deftest is]]
            [hickory.core :as hickory]
            [clojure.tools.reader :as reader]
            ;[rewrite-clj.zip :as rwz]
            [parcera.core :as parc]
            ;[clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.zip :as zip]
            [clojure.walk :as walk]
            [clojure.set :as set]
            ;[oz.core :as core]
            [clojure.java.io :as io]
            [markdown.core :as md]
            [hasch.core :as hasch]
            [oz.live :as live]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.core.async :as async :refer [go go-loop <! >! <!! >!!]]))



(def types
  #{:code :hiccup :md-comment :whitespace :code-comment}) 
(s/def ::type types)

(def evaluable-types
  #{:code :hiccup :md-comment :whitespace :code-comment})
(s/def ::evaluable-type evaluable-types)


(s/def ::block
  (s/keys :req-un [:oz.block/id]))



;(s/def ::evaluable-block
  ;(s/and ::block
         ;(comp (s/valid?))))
  


;(require '[clojure.tools.namespace.repl :refer [refresh]])
;(refresh)


; This is a comment that shouldn't be converted
;(parc/ast (slurp "test.clj"))
;(parc/ast "#(f %) (fn [x] x)")
;(parc/code (parc/ast "#(f %) (fn [x] x)"))
;(parc/failure? (nth (parc/ast "(+ 5 6) (+ 3 4") 3))
;(parc/code
  ;(second
    ;(parc/ast (slurp "test.clj"))))

;(parc/ast ";; this \n\n ;;that")
;(parc/code [:code [:list [:symbol "f"]]])
;(parc/code [:code (second (parc/ast "(f)"))])
;(let [ast (parc/ast "(f)")]
  ;(meta ast)) 
  ;(parc/code (with-meta (:code (rest ast))
                        ;(meta ast)))) 

;(parc/ast "(defn f [x]\n  ;;some code...\n(* x 2))")
;(meta (parc/default-hidden (parc/ast "(defn f [x]\n  ;;some code...\n(* x 2))")))

(def md-comment-regex
  #"^;;( (.*))?")
;(re-matches #"^;;( (.*))?" ";; This is the stuff that matters")

(defn- get-comment-line-md [comment-line]
  (or (last (re-matches md-comment-regex comment-line)) ""))

;(get-comment-line-md ";; this is a line!")

(def trailing-spaces-regex
  #"\s*[\n\r][ \t]+$")

; should match
;(re-matches trailing-spaces-regex "\n  ")
;(re-matches trailing-spaces-regex "\n\t")
;(re-matches trailing-spaces-regex "\n \n  ")

; should NOT match
;(re-matches trailing-spaces-regex "\n")
;(re-matches trailing-spaces-regex "\n \n")
;(re-matches trailing-spaces-regex "\n \n")


(defn unindented-newline?
  [last-form]
  ;; main condition: preceded by a whitespace line, without any indendation
  (or (and (= :whitespace (first last-form))
           (not (re-matches trailing-spaces-regex (second last-form))))
      ;; to handle the edge case of first line in a file being a comment line
      (nil? last-form)))


(def comment-metadata-regex
  ;#"^;; (\^[(:[^\s]+)(\{.*\})])+"
  #";; \^(:\S+|\{.*\})")

;(re-matches comment-metadata-regex ";; ^:this")
;(re-find #";; (\^(:\S+|\{.*\})\s+)+" ";; ^{:this :blah} ^{:other :stuff}")
;(re-matches #";; \^(:\S+|\{.*\})" ";; ^:this")
;(re-matches #";; \^(:\S+|\{.*\})" ";; ^{:this :that}")


(defn form-type
  [last-form form]
  (case (first form)
    :comment (cond
               ;; metadata annotations are (for now) only recognized between the 
               (and (re-matches md-comment-regex (second form))
                    (unindented-newline? last-form))
               :md-comment
               :else
               :code-comment)
    :whitespace :whitespace
    ;; Do we do it like this?
    :vector :hiccup
    :code))

(form-type
  [:whitespace "  \n\n"]
  [:comment ";; ^:this"])
  ;(re-matches comment-metadata-regex ";; ^:this"))

(defn multiline-whitespace?
  [[type code-str]]
  ;; Check for whitespace, and that it's just a single newline
  (and (= :whitespace type)
       (< 1 (count (re-seq #"\n" code-str)))))

(multiline-whitespace? [:whitespace " \n \n"])
(multiline-whitespace? [:whitespace " \n"])

;; FOCUS
(defn- new-block?
  [{:keys [last-form block-type]} next-form]
  (let [next-form-type (form-type last-form next-form)]
    (boolean
      (or
        ;; always end on a code block, unless it's just a trailing comment
        ;(and (#{:code :}))
        (multiline-whitespace? next-form)
        (and (#{:code :hiccup} block-type)
             (= :code-comment next-form-type))
        ;; markdown blocks can only be completed by more markdown blocks or whitespace
        (and (= :md-comment block-type)
             (not (#{:md-comment :whitespace} next-form-type)))
        ;; code comments can only be followed by more code-comments, whitespace or code
        (and (= :code-comment block-type)
             (not (#{:code-comment :whitespace :code :hiccup} next-form-type)))
        ;; we separate out leading whitespace, so as to not clutter code blocks with it
        (and (= :whitespace block-type)
             (not= :whitespace next-form-type))))))

(defn- set-last-form
  [aggr last-form]
  (assoc aggr :last-form last-form))

(defn read-meta-comment
  [md-string]
  (let [meta-line (first (string/split md-string #"\n"))]
    (try
      (meta
        (reader/read-string
          (str (apply str (drop 2 meta-line))
              " {}")))
      (catch Throwable t
        (log/error "Unable to read metadata from: " meta-line)
        nil))))

;; Defines how we add code to a block
(defn- add-to-current-block
  [{:as aggr :keys [last-form block-type]} next-form]
  (let [next-form-type (form-type last-form next-form)
        block-type (cond
                     ;; adding whitespace shouldn't change the type if already set
                     (= next-form-type :whitespace)
                     (or block-type :whitespace)
                     ;; we can add code comments to the end of code/hiccup blocks
                     (and (#{:hiccup :code} block-type)
                          (= next-form-type :code-comment))
                     (or block-type :code-comment)
                     ;; otherwise we take the new form type, assuming prior was deferential
                     :else
                     next-form-type)]
    (-> aggr
        (update :current-block conj next-form)
        (assoc :block-type block-type)
        (set-last-form next-form))))

;; TODO Do we need async vlaues for the metadata for this to work?

(defn- conclude-block
  [{:as aggr :keys [current-block block-type]}]
  (if current-block
    ;; only conclude a block if one actually exists
    (-> (dissoc aggr :block-type :current-block)
        (update :blocks conj {:type block-type :forms current-block}))
    aggr))

(defn- start-new-block
  [{:as aggr :keys [last-form]} next-form]
  (-> (conclude-block aggr)
      (assoc :current-block [next-form]
             :block-type (form-type last-form next-form))
      (set-last-form next-form)))

(defn- reconstitute-forms
  [{:as block :keys [forms]}]
  (assoc block :code-str (parc/code (into [:code] forms))))

(defn- apply-metadata
  [[tag first-elmnt & rest-elmnts] metadata]
  ;(log/info "QQQQQQ calling apply-metadata with meta: " meta " and hiccup: " hiccup)
  (->>
    rest-elmnts
    (into
      [tag
       (if (map? first-elmnt)
         (merge first-elmnt metadata)
         metadata)
       (when-not (map? first-elmnt)
         first-elmnt)])))


(defn- strip-metadata-if-needed
  [meta? lines]
  (if meta?
    (drop 1 lines)
    lines))

(defn- unwrap-single-child-div
  [[tag & forms :as form]]
  (let [attr-map (when (map? (first forms)) (first forms))
        non-attr-forms (cond->> (remove nil? forms)
                         attr-map (drop 1))]
    (if (and (= :div tag)
             (= 1
                (count non-attr-forms)))
      ;; TODO Do we need to merge meta here?
      (-> (first non-attr-forms)
          (apply-metadata attr-map))
      form)))

(defn- unwrap-single-child-p
  [[tag & forms :as form]]
  (let [attr-map (when (map? (first forms)) (first forms))
        non-attr-forms (cond->> (remove nil? forms)
                         (or attr-map (nil? (first forms)))
                         (drop 1))]
    (if (and (= :p tag)
             (= 1
                (count non-attr-forms))
             (#{:img} (ffirst non-attr-forms)))
      (-> (first non-attr-forms)
          (apply-metadata attr-map)
          (->> (remove nil?)))
      form)))

(defn has-metadata?
  [code-str]
  (boolean (re-matches comment-metadata-regex (first (string/split-lines code-str)))))

(has-metadata? ";; ^{:this :that}\n;; more stuff")

(defn- process-md-comments
  [{:as md-block :keys [code-str]}]
  (let [meta? (has-metadata? code-str)
        markdown
        (->> (string/split-lines code-str)
             (strip-metadata-if-needed meta?)
             (map get-comment-line-md)
             (string/join "\n"))
        {:keys [metadata html]}
        (try
          (md/md-to-html-string-with-meta markdown)
          (catch Throwable t
            (log/error "Unable to process markdown" t)))
        metadata (merge metadata (when meta? (read-meta-comment code-str)))
        ;;     parse the html as hiccup
        hiccup (when html
                 (-> html hickory/parse hickory/as-hiccup first
                   ;; not sure why we do this actually
                   md->hc/component md-decode/decode
                   unwrap-single-child-div
                   unwrap-single-child-p
                   (cond->
                     ;; we only apply the metadata if there's any to apply
                     (seq metadata) (apply-metadata metadata))))]
    (assoc md-block
           :markdown markdown
           :html-string html
           :hiccup hiccup)))

(defn- without-whitespace
  [forms]
  (walk/prewalk
    (fn [form]
      (if (coll? form)
        (remove
          #(when (coll? %)
             (= :whitespace (first %)))
          form)
        form))
    forms))

(defn parse-code
  [code-str]
  (->> (parc/ast code-str)
       ;; drop the :code demarkation
       rest
       (reduce
         (fn [aggr next-form]
           (if (new-block? aggr next-form)
             (start-new-block aggr next-form)
             (add-to-current-block aggr next-form)))
         {:blocks []})
       (conclude-block)
       :blocks
       (map
         (comp
           reconstitute-forms
           (fn [{:as block :keys [forms]}]
             (assoc block :forms-without-whitespace (without-whitespace forms)))))))


;; TODO Write tests
;(parse-code
 ;"
;(ns my.ns)\n \n(defn some-code [x]\n  (impl x))\n \n  ;(other-iml x))\n\n;; Actual markdown comments.\n
 ;")

;(parse-code
 ;"
;(ns my.ns)\n \n(defn some-code [x]\n  (impl x))\n  ;(other-iml x))\n\n;; Actual markdown comments.\n
 ;")

(deftest parse-code-tests
  (testing "basic functionality"
    (is (seq?
          (parse-code "(ns my.ns)\n(def thing :stuff)")))))
                    

;(parse-code (slurp "test.clj"))
;(defined-vars)


;(let [val (into {} (doall (map vec (partition 2
                                              ;(interleave (range 1000000)
                                                          ;(range 1000000))))))]
  ;(time (hash val)))


;core/hash -> 0.3s
;uuid -> 1.5s
;b64-hash -> 1.5s


;(ana.jvm/analyze '(def shit "yo"))
;(ana.jvm/analyze '(defmulti shit "yo"))
;(ana.jvm/analyze '(defmulti shit "yo"))

;(ana.jvm/analyze '(def a-number 4))
;(ana.jvm/analyze '(+ a-number 4))
;(ana.jvm/analyze '(do (def form []) (ana.jvm/analyze form)))
;a-number
;(+ a-number 4)


(defn analysis-zipper [analysis]
  (zip/zipper
    (fn [n] (map? n))
    (fn [n] (->> (dissoc n :env)
                 (filter (fn [[k v]]
                           (and (not (#{:env :form :arglists :raw-forms} k))
                                (coll? v))))
                 (mapcat (fn [[_ v]]
                           (if (sequential? v) v [v])))
                 (filter coll?)
                 (remove nil?)))
    (fn [n _] n)
    analysis))

(defn zipper-nodes [zip]
  (->> zip
       (iterate zip/next)
       (take-while #(not (zip/end? %)))
       (map zip/node)))

(defn analysis-nodes
  [ns-sym form]
  ;; ensure ns-sym exists
  (-> form (ana.jvm/analyze {:ns (or ns-sym 'user)}) analysis-zipper zipper-nodes))

;(comment
  ;(->>
    ;(analysis-nodes '(let [v (def shit "yo")] (println "defined" v)))
    ;(filter (fn [n] (= :def (:op n))))
    ;(map :var))

  ;(->>
    ;(analysis-nodes '(do (println "stuff" clojure.core/map)))
    ;(filter #(and (:var %) (not= (:op %) :def)))
    ;(map :var))

  ;(->>
    ;(analysis-nodes '(defmethod shit :stuff [args] (println args)))
    ;(filter #(and (:var %) (not= (:op %) :def)))
    ;(map :var))
    ;;(map :var))

  ;(->>
    ;(analysis-ndoes '^{:stuff map}[3 4])
    ;(filter #(and (:var %) (not= (:op %) :def)))
    ;(map :var)))


;(:body (ana.jvm/analyze '(let [v (def shit "yo")] (println "defined" v))))

;(ana.jvm/analyze 
  ;^{:with (+ 3 4)}[])

;(ana.jvm/analyze '(with-meta [] {:meta :stuff}))
;(ana.jvm/analyze '(swap! shit merge {:stuff :yeah}))
;(ana.jvm/analyze '(swap! (:stuff shit) merge {:stuff :yeah}))
;(ana.jvm/analyze '(reset! shit {:stuff :yeah}))
;(ana.jvm/analyze '(defmethod shit :stuff [args] (println args)))


(defn var-form? [n]
  (and (:var n)
       (not= (:op n) :def)))
  
(defn var-def-form? [{:keys [op]}]
  (= :def op))


(declare shit)
(deftest var-def-analysis-test
  (let [anal-node (first (analysis-nodes 'oz.next '(def shit "yo")))]
    (is (= true (var-def-form? anal-node)))
    (is (= (var oz.next/shit) (:var anal-node)))))


(defn addmethod-mutation? [{:keys [method]}]
  (= 'addMethod method))

(defn addmethod-var [{:keys [instance]}]
  (:var instance))


(deftest defmethod-analysis-test
  (let [anal-node
        (first (analysis-nodes 'oz.next '(defmethod shit :stuff [args] (println args))))]
    (is (= true (addmethod-mutation? anal-node)))
    (is (= (var oz.next/shit) (addmethod-var anal-node)))))

(defn mutated-atom-var? [{:as n :keys [op args]}]
  (and (= :invoke op)
       (#{#'clojure.core/swap! #'clojure.core/reset!} (:fn n))
       (= :var (:op (first args)))))

(defn mutated-atom-var [{:keys [args]}]
  (-> args first :var))


(defn -resolve-var-lists
  [defines]
  (->> (if (coll? defines)
         defines
         [defines])
       (map
         #(if (var? %) 
            %
            ;; find or declare the var if a symbol
            (or (find-var %) (eval (list 'declare %)))))))

(defn explicit-mutation [{:keys [op meta]}]
  (when (= :with-meta op)
    (when-let [mutates (:oz.block/mutates meta)]
      (-resolve-var-lists mutates))))

(defn explicit-dependencies [{:keys [op meta]}]
  (when (= :with-meta op)
    (when-let [depends (:oz.block/depends-on meta)]
      (-resolve-var-lists depends))))

(defn explicit-defines [{:keys [op meta]}]
  (when (= :with-meta op)
    (when-let [defines (:oz.block/defines meta)]
      (-resolve-var-lists defines))))

;(let [s `this] (or (find-var s) (eval '(declare ~s))))

(->>
  ;(ana.jvm/analyze '(defmethod shit :stuff [args] (println args)))
  ;(ana.jvm/analyze '(let [v (def shit "yo")] (println "defined" v)))
  (analysis-nodes 'oz.next '(def shit "yo"))
  (first))
  ;(map addmethod-mutation?))

(defn- set-conj
  [xs x]
  (conj (set xs) x))

(defn ns-form-ns
  [code-data]
  (and (list? code-data)
       (= 'ns (first code-data))
       (symbol? (second code-data))
       (second code-data)))


(defn defmulti-var
  [{:as code-data :keys [raw-forms]}]
  ;(log/info :raw-forms raw-forms)
  ;(log/info :anal-form (:form code-data))
  (let [form (first raw-forms)]
    (and (list? form)
         (= 'defmulti (first form))
         (symbol? (second form))
         (second form))))


(ns-form-ns '(ns this.that))

(defn analyze-block
  [{:as block :keys [code-str ns-sym forms] :or {ns-sym 'user}}]
  (let [code-data (reader/read-string code-str)
        anal-nodes (analysis-nodes ns-sym code-data)] 
    (merge block
      (reduce
        (fn [anal-results node]
          (cond-> anal-results
            (var-def-form? node)       (update :defined-vars set-conj (:var node))
            (var-form? node)           (update :used-vars set-conj (:var node))
            (mutated-atom-var? node)   (update :mutated-vars set-conj (mutated-atom-var node))
            (addmethod-mutation? node) (update :mutated-vars set-conj (addmethod-var node))))
        (merge
          {:code-data code-data
           :defined-vars (explicit-defines block)
           :used-vars (explicit-dependencies block)
           :mutated-vars (explicit-mutation block)})
         ;:analysis (first anal-nodes)}
        anal-nodes))))

(:defmulti-vars (analyze-block {:code-str "(let [f first] (defmulti shitterz f))"}))
;(analyze-block {:ns-sym 'user :code-str "(defmulti shitterz first)"})


;(nth
  ;;(analysis-nodes '(do (def shit "dawg") (println shit)))
  ;(analysis-nodes '(let [v (def shit "yo")] (println v)))
  ;0)

;(try
  ;(analyze-block {:code-str '(let [v (def shit "yo")] (println v))})
  ;;(analyze-block {:code-str '(do (def shit "dawg") (println shit))})
  ;(catch Throwable t
    ;(.printStackTrace t)))

(deftest analyze-block-test
  (testing "simple def"
    (let [anal (analyze-block {:ns-sym 'oz.next :code-str (str '(def shit "yo"))})]
      (is (= #{(var oz.next/shit)}
             (:defined-vars anal)))))
  (testing "nested def"
    (let [anal (analyze-block {:ns-sym 'oz.next :code-str (str '(do (def shit "yo") (println shit)))})]
      (is (= #{(var oz.next/shit)}
             (:defined-vars anal))))
    (let [anal (analyze-block {:ns-sym 'oz.next :code-str (str '(let [v (def shit "yo")] (println v)))})]
      (is (= #{(var oz.next/shit)}
             (:defined-vars anal)))))
  (testing "defmulti"
    (let [anal (analyze-block {:ns-sym 'oz.next :code-str (str '(defmulti shit first))})]
      (is (= #{(var oz.next/shit)}
             (:defined-vars anal)))))
  (testing "var usage"
    (let [anal (analyze-block {:ns-sym 'oz.next :code-str (str '(str shit "dude"))})]
      (is (= true (contains? (:used-vars anal)
                             (var oz.next/shit)))))))


;; Inferring dependencies....

;; For each code block, we compute a hash based on:
;; * the :forms-without-whitespace representation
;; * the hash for each block it depends on, based on
;;   * the block precedes, and
;;   * either defines or mutates a used var

;; As we do this, we cache the forms 

;; This implies that we need to be able to:
;; * quickly map vars to blocks which mutate those vars

;; We may not actually have to explicitly compute dependencies...
;; When we're ready to execute, we can simply iterate through the blocks and for each:
;; * look at the vars they use
;; * get the forms 

;(meta #'hasch.core/uuid)
;(require '[clojure.java.io :as io])
;(.getPath (io/resource "hasch/core.cljc"))
;(.getPath (io/resource "oz/next.clj"))
;(io/resource "oz/next.clj")

(def local-file?
  (memoize
    (fn [filename]
      (let [path (.getPath (io/resource filename))]
        (string/starts-with? path (System/getProperty "user.dir"))))))
;(local-file? "oz/next.clj")
;(local-file? "hasch/core.cljc")

(defn local-var?
  [v]
  (if-let [{:keys [file]} (meta v)]
    (local-file? file)
    true))

(defn block-hash
  "Computes a hasch using a blocks forms without whitespace, as well as all of its dependencies"
  [block]
  (hasch/uuid
    (select-keys block [:forms-without-whitespace :dependencies])))


;; debug the state of a file
;(doseq [[block-id result-chan]
        ;(:result-chans (:last-evaluation (get @build-state "/home/csmall/code/oz/notebook-demo.clj")))]
  ;(when-let [{:as result :keys [error aborted]} (<!! result-chan)]
    ;(when (or error aborted)
      ;(log/info "block: " block-id)
      ;(log/info result))))
      

;(parse-code (slurp "test.clj"))

;; Need to make it so thast upstream changes to vars will update the functions in question
  

(defn infer-dependencies
  [blocks]
  (->> blocks
       (reduce
         (fn [{:keys [var-mutation-blocks block-id-seq blocks-by-id ns-sym]}
              {:as block :keys [defined-vars mutated-vars used-vars declares-ns]}]
           (let [dependency-vars (set/difference
                                   (set (into used-vars mutated-vars))
                                   defined-vars)
                 block (assoc block :dependencies (apply set/union
                                                         (map var-mutation-blocks dependency-vars)))
                 id (block-hash block)
                 block (assoc block
                              :id id
                              ;; we always evaluate from the most recent ns
                              :ns-sym ns-sym)
                 new-var-mutations
                 (reduce
                   (fn [var-block-mapping v]
                     (update var-block-mapping v set-conj id))
                   var-mutation-blocks
                   (concat defined-vars mutated-vars))]
             ;; if this is a ns declaration form, set ;ns-sym for next reduce iteration
             {:ns-sym (or declares-ns ns-sym)
              :var-mutation-blocks new-var-mutations
              :block-id-seq (conj block-id-seq id)
              :blocks-by-id (assoc blocks-by-id id block)}))
         {:ns-sym 'user
          :block-id-seq []
          :var-mutation-blocks {}
          :blocks-by-id {}})))


(def code-block-types #{:code :hiccup})
(def code-block? (comp code-block-types :type))


(defn ns-form-references
  "Separate out all of the 'reference forms' from the rest of the ns declaration; we'll use this in a couple
  different ways later"
  [ns-form]
  (let [reference-forms (->> ns-form (filter seq?) (map #(vector (first %) (rest %))) (into {}))]
    (concat
      ;; We need to use explicit namespacing here for refer-clojure, since hasn't been
      ;; referenced yet, and always include this form as the very first to execute, regardless of whether it shows
      ;; up in the ns-form, otherwise refer, def, defn etc won't work
      [(concat (list 'clojure.core/refer-clojure)
               (:refer-clojure reference-forms))]
      ;; All other forms aside from :refer-clojure can be handled by changing keyword to symbol and quoting
      ;; arguments, since this is the corresponding call pattern from outside the ns-form
      (map
        (fn [[k v]]
          (concat (list (-> k name symbol))
                  (map #(list 'quote %) v)))
        (dissoc reference-forms :refer-clojure)))))


(defn process-blocks
  [blocks]
  (->> blocks
       (reduce
         (fn [{:as aggr :keys [ns-sym blocks] :or {ns-sym 'user}}
              {:as block :keys [type code-str]}]
           (if (code-block? block)
             (let [ns-sym (or ns-sym 'user)
                   code-data (reader/read-string code-str)
                   block (assoc block :code-data code-data)
                   declares-ns-sym (ns-form-ns code-data)
                   next-ns-sym (or declares-ns-sym ns-sym)]
               ;; need to run the namespace declarations syncronously
               (when declares-ns-sym
                 ;; For code blocks, we have to analyze the code, and track current ns symbol
                 (let [reference-forms (ns-form-references code-data)]
                   ;; Process namespace declarations synchronously, since we need to evaluate them before code
                   (log/info :NS-SYM-CREATION declares-ns-sym)
                   (let [result (binding [*ns* (create-ns declares-ns-sym)]
                                  (eval (concat '(do) reference-forms)))])))
               ;; analyze block
               (let [code-block (cond-> (analyze-block (assoc block :ns-sym ns-sym))
                                  declares-ns-sym (merge {:declares-ns declares-ns-sym}))]
                 (merge
                   {:ns-sym next-ns-sym
                    :blocks (conj blocks code-block)})))
             ;; for everything else, just make sure we've processed any markdown
             (let [new-block
                   (case type
                     :md-comment (process-md-comments block)
                     (:whitespace :code-comment) block)]
               (update aggr :blocks conj new-block))))
         {:blocks []})
       ;; We ust need the blocks out of this
       :blocks))

(defn block-seq
  [{:keys [block-id-seq blocks-by-id]}]
  (->> block-id-seq
       (map (fn [id] (get blocks-by-id id)))))

(defn spy-pp
  ([message xs]
   (log/info message (with-out-str (pp/pprint xs)))
   xs)
  ([xs] (spy-pp "" xs)))

(deftest infer-dependencies-tests
  (testing "returns a ns-symbol, which will be the first such in the file"
    (let [get-nss (fn [code-str]
                    (->> code-str
                         parse-code
                         process-blocks
                         infer-dependencies
                         (block-seq)
                         (filter (comp #{:code} :type))
                         (map :ns-sym)))]
      (testing "basic functionality"
        (is (= '[user my.ns]
               (get-nss "(ns my.ns)\n(def thing :stuff)")))))))


(defn analysis
  [code-str]
  (->> (parse-code code-str) process-blocks infer-dependencies))

(try
  (analysis "(defmulti f first)")
  (catch Throwable t (def error t)))


(deftest dependency-test
  (testing "simple dependency"
    (let [code-str "(def stuff :blah)\n(str stuff \"dude\")"
          analysis-results (analysis code-str)
          code-blocks (->> (block-seq analysis-results)
                           (filter #(= :code (:type %))))]
      (is (= #{} (-> code-blocks first :dependencies)))
      (is (= true
             (contains? (-> code-blocks second :dependencies)
                        (-> code-blocks first :id)))))))


(defn block-token
  [id]
  [:oz.doc/block id])

(defn apply-template
  [template-fn {:as analysis-results :keys [block-id-seq]}]
  (assoc analysis-results
         :template (template-fn (map block-token block-id-seq))))

;(defn publish-result [block result])

(defn log-long-running-form!
  [{:keys [result-chan timeout-chan error-chan long-running?]}
   {:keys [code-str id]}]
  (async/go
    (let [[_ chan] (async/alts! [result-chan timeout-chan error-chan])]
      (when (= chan timeout-chan)
        (log/info (live/color-str live/ANSI_YELLOW "Long running form (" id ") being processed:\n" code-str))
        (async/>! long-running? true)))))

;(try
  ;(/ 1 0)
  ;(catch Throwable t
    ;{:message (str t)
     ;:stacktrace (with-out-str (clojure.stacktrace/print-stack-trace t))}))

(defn error-data [t]
  {:message (str t)
   :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace t))})

(defn handle-error!
  [{:as evaluation :keys [t0 result-chan error-chan long-running? new-form-evals t0]}
   {:as block :keys [code-str id]}
   t]
  (let [compute-time (/ (- (System/currentTimeMillis) t0) 1000.0)]
    (async/>!! result-chan {:id id
                            :compute-time compute-time
                            :error (error-data t)
                            :result t})
    ;(log/error (live/color-str live/ANSI_RED "Error processing form (" id "):\n" code-str))
    (log/error (str "Error processing form (" id "):\n" code-str))
    (log/error t)
    (async/>!! error-chan t)
    (throw t)))

(defn handle-result!
  [{:as evaluation :keys [t0 result-chan long-running? new-form-evals t0]}
   {:as block :keys [id]}
   result]
  (let [compute-time (/ (- (System/currentTimeMillis) t0) 1000.0)]
    (async/>!! result-chan (merge result
                                  {:id id
                                   :compute-time compute-time}))
    ;; keep track of successfully run forms, so we don't redo work that completed
    ;; If long running, log out how long it took
    (when (async/poll! long-running?)
      (println (live/color-str live/ANSI_YELLOW "Form (" id ") processed in: " compute-time "s")))))

(defn init-block-evaluation [main-evaluation]
  (merge main-evaluation
    {:result-chan (async/promise-chan)
     :timeout-chan (async/timeout 1000)
     :long-running? (async/promise-chan)
     :error-chan (async/promise-chan)}))

(defn all-complete?
  [dependency-chans]
  (async/go
    (doseq [c dependency-chans]
      (<! c))
    :done))

(defn complete-results
  [dependency-chans]
  (async/go-loop [results []
                  chans dependency-chans]
    (when-let [chan (first chans)]
      (let [result (<! chan)
            results (conj results result)]
        (if (empty? (rest chans))
          results
          (recur results (rest chans)))))))

(defmacro result-with-out-str
  "Evaluates exprs in a context in which *out* is bound to a fresh
  StringWriter.  Returns the string created by any nested printing
  calls."
  [& body]
  `(let [out-s# (new java.io.StringWriter)
         err-s# (new java.io.StringWriter)]
     (binding [*out* out-s#
               *err* err-s#]
       (let [result# (do ~@body)]
         {:stdout (str out-s#)
          :stderr (str err-s#)
          :result result#}))))

;(result-with-out-str
  ;(println "shit storm")
  ;(/ 1 2))



(defn handle-abortion!
  [{:as evaluation :keys [t0 result-chan long-running? new-form-evals t0]}
   {:as block :keys [id]}]
  (log/info "XXXXXXXXXX calling handle-abortion! for block" id)
  (async/>!! result-chan {:id id
                          :aborted true}))


(defn defmulti-vars2
  [{:as block :keys [defined-vars]}]
  (->> defined-vars
       ;(spy-pp :defined-vars)
       (filter (comp (partial instance? clojure.lang.MultiFn) deref))
       ;(map (comp (partial instance? clojure.lang.MultiFn) deref) #{#'defmulti-test/f})
       ;(spy-pp :post-filter)
       (map symbol)))

(defn handle-defmulti-redefs2
  [{:as block :keys [ns-sym]}]
  (let [the-ns (create-ns ns-sym)]
    (doseq [v (defmulti-vars2 block)]
      (ns-unmap the-ns (symbol (name v))))))

(defn- evaluate-block!
  [{:as evaluation :keys [code-data result-chans kill-chan]}
   {:as block :keys [id code-str code-data dependencies ns-sym declares-ns]}]
  (let [block-evaluation (init-block-evaluation evaluation)
        dependency-chans (map result-chans dependencies)]
    ;; checks if a (ns ...) form, and fetches references if so
    (if-let [reference-forms (and declares-ns (ns-form-references code-data))]
      ;; We process namespace declarations synchronously, since we always want to evaluate them before the
      ;; code
      (let [t0 (System/currentTimeMillis)
            result (result-with-out-str
                     ;(with-bindings) ; should we be using with-bindings here?
                     (binding [*ns* (create-ns declares-ns)]
                       (eval (concat '(do) reference-forms))))]
        (>!! (:result-chan block-evaluation)
             (merge result {:id id :compute-time (/ (int (- (System/currentTimeMillis) t0)) 1000.)})))
      ;; Otherwise, we queue up a thread and move on
      (async/thread
        (let [[dependency-results chan] (async/alts!! [kill-chan (complete-results dependency-chans)])
              block-evaluation (assoc block-evaluation :t0 (System/currentTimeMillis))]
          (log/info "dependency-results" dependency-results)
          (when-not (= chan kill-chan)
            (if (log/spy :info (or (some :aborted dependency-results)
                                   (some :error dependency-results)))
              (handle-abortion! block-evaluation block)
              (try
                (log-long-running-form! block-evaluation block)
                (handle-defmulti-redefs2 block)
                (let [result
                      (result-with-out-str
                        (binding [*ns* (create-ns ns-sym)]
                          (eval code-data)))]
                  (handle-result! block-evaluation block result))
                (catch Throwable t
                  (log/error "hit a throwable")
                  (handle-error! block-evaluation block t))))))))
    ;; Note that by the beauty of immutability here, we are avoiding merging in evaluation context specific to
    ;; each block here, and only keeping the result chan from the block-evaluation in :result-chans
    (update evaluation :result-chans assoc id (:result-chan block-evaluation))))


;(let [c1 (async/chan 1)
      ;c2 (async/chan 1)]
  ;(>!! c1 :foo)
  ;(>!! c2 :bar)
  ;(Thread/sleep 100)
  ;(async/poll!
    ;;c1))
    ;(async/map vector [c1 c2] 1)))


;(let [c1 (async/to-chan (range 10))
      ;c2 (async/to-chan (range -10 0))
      ;c3 (all-complete? [c1 c2])]
  ;(Thread/sleep 100)
  ;(async/poll! c3))
  ;(async/poll!
    ;(async/map + [c1 c2] 1)))

;(let [c (async/promise-chan)]
  ;(>!! c :done)
  ;(<!! c)
  ;(<!! c)
  ;(<!! c))

(defn previous-result
  [{:keys [result-chans]}
   block-id]
  (get result-chans block-id))

;(let [c (async/thread (Thread/sleep 1000) :done)]
  ;(<!! c)
  ;(<!! c)
  ;(<!! c))

(defn apply-previous-result
  [evaluation block-id result-chan]
  (assoc-in evaluation [:result-chans block-id] result-chan))

(defn build-evaluation [analysis]
  (merge analysis
    {:kill-chan (async/promise-chan)
     :complete-chan (async/promise-chan)
     :result-chans {}}))


(defn evaluate-blocks!
  [previous-evaluation
   {:as analysis :keys [ns-sym block-id-seq blocks-by-id]}]
  (->> block-id-seq
    (reduce
      (fn [evaluation block-id]
        (let [{:as block :keys [type]}
              (blocks-by-id block-id)]
          (if (#{:code :hiccup} type)
            (if-let [result-chan (previous-result previous-evaluation block-id)]
              (apply-previous-result evaluation block-id result-chan)
              (evaluate-block! evaluation block))
            evaluation)))
      (build-evaluation analysis))))


;; Used this test code to write evaluations, but seems to not run now
(comment
  (def test-evaluation
    (let [code-str "(ns hip.hop) (def stuff :blah)\n(str stuff \"dude\")"
          analysis-results (analysis code-str)
          evaluation (evaluate-blocks! {} analysis-results)]
      (Thread/sleep 500)
      evaluation))
  (->> (:result-chans test-evaluation)
       (map #(vector (first %) (-> % second async/poll!))))
  (async/poll!
    (second (first (:result-chans test-evaluation))))
  :end-comment)


(defn lazy? [x]
  (instance? clojure.lang.LazySeq x))

;(lazy? (map inc (range 3)))

(defn result-chan-seq
  [{:keys [block-id-seq result-chans]}]
  (->> block-id-seq
       (map result-chans)
       (filter identity)))


(defn queue-result-callback!
  [{:keys [block-id-seq result-chans]}
   callback-fn]
  (doseq [block-id block-id-seq
          :let [result-chan (get result-chans block-id)]
          :when result-chan]
    (go
      (let [result (<! result-chan)]
        (callback-fn result)))))

;(queue-result-callback!
  ;test-evaluation
  ;(fn [result]
    ;;(println "have result for block" block)
    ;(println "result" result)))

;; How do errors propogate?


(defn eval-complete?
  [evaluation]
  (all-complete? (result-chan-seq evaluation)))


(defn queue-completion-callback!
  [{:as evaluation :keys [kill-chan block-id-seq blocks-by-id result-chans]}
   callback-fn]
  (async/thread
    (let [[_ chan] (async/alts!! [kill-chan (eval-complete? evaluation)])]
      (when-not (= chan kill-chan)
        (callback-fn evaluation)))))

(let [c (async/chan 1)]
  (try
    (/ 1 0)
    (catch Exception e
      (>!! c e)))
  (async/poll! c))

;stuff

(defonce build-state
  (atom {}))


(defn reset-build-state! []
  (reset! build-state {}))


(defn kill-evaluation! [{:keys [kill-chan]}]
  (when kill-chan
    (go (>! kill-chan :kill))))

(defn reload-file!
  [file]
  (let [filename (live/canonical-path file)
        contents (slurp file)
        {:keys [last-contents last-evaluation]} (get @build-state filename)]
    ;; This has a couple purposes, vs just using the (seq diff-forms) below:
    ;; * forces at least a check if the file is changed at all before doing all the rest of the work below
    ;;   (not sure how much perf benefit there is here)
    ;; * there's sort of a bug and a feature: the (seq diff-forms) bit below ends up not working if
    ;;   `forms` doesn't have everything, which will be the case if(f?) there was an error running
    ;;   things. This means changing a ns form (e.g.) will trigger an update on forms that failed
    ;; * this prevents the weird multiple callback issue with the way vim saves files creating 3/4 change
    ;;   events
    (when (not= contents last-contents)
      ;; kill last evaluation
      (kill-evaluation! last-evaluation)
      ;; Start the evaluation
      (let [evaluation (-> contents analysis (assoc :file file) (->> (evaluate-blocks! last-evaluation)))]
        ;; cache evaluation object in build state
        (log/info (live/color-str live/ANSI_GREEN "Reloading file: " filename))
        (swap! build-state assoc filename {:last-contents contents :last-evaluation evaluation :previous-evaluation last-evaluation})
        evaluation))))
        ;(log/info (live/color-str live/ANSI_GREEN "Done reloading file: " filename "\n")
        ;(catch Exception _
          ;(log/error (live/color-str live/ANSI_RED "Unable to process all of file: " filename "\n"))
          ;; Update forms in our state atom, only counting those forms which successfully ran
            ;(compile-forms-hiccup form-evals)

;; TODO need to be able to attach metadata that says that there's a random process, meaning that new runs will
;; have to be mixed with some random key
;; Actually... this should probably just be a `^:oz/force-rerun!` key

(defn changed-block-result?
  "Not entirely sure there won't be race conditions with this"
  [{:keys [id]}]
  (let [{:keys [results-by-id]}
        (get-in @build-state [:filename :previous-evaluation])]
    (get results-by-id id)))
       
(defn queue-new-result-callback!
  [previous-evaluation
   {:keys [block-id-seq result-chans]}
   callback-fn]
  (doseq [block-id block-id-seq
          :let [result-chan (get result-chans block-id)]
          :when result-chan]
    (if-let [result-chan (get-in previous-evaluation [:result-chans block-id])]
      (go
        (log/info "result chan" result-chan)
        (let [result (<! result-chan)]
          (callback-fn result))))))


;(let [code-str "(def stuff :blah)\n(str stuff \"dude\")"]
   ;(-> code-str parse-code process-blocks infer-dependencies))

;(map :dependencies (infer-dependencies (process-blocks (parse-code (slurp "test.clj")))))

;(doseq [{:keys [code type defined-vars]} (process-blocks (parse-code (slurp "test.clj")))]
  ;(when (#{:code :hiccup} type)
    ;;(clojure.pprint/pprint block)
    ;(println code)
    ;(println "=> " defined-vars "\n")))

;(first
;(analyze-block
  ;(nth
    ;(parse-code (slurp "test.clj"))
    ;2))


;(->
  ;(parse-code (slurp "test.clj"))
  ;(first)
  ;:forms
  ;(without-whitespace))


;(hasch/uuid {:some 'random-data})

;(hasch/b64-hash
  ;{:some 'random-data})


;(defn- process-code
  ;[{:as block :keys [code]}])

;(->> parsed-code





;; analysis

;(require '[clojure.tools.analyzer.jvm :as ana.jvm])
;(ana.jvm/analyze '(defmulti shit first))
;(macroexpand '(defmulti shit first))
;(macroexpand '(defmethod shit :stuff [_ crap] (println crap)))
;(ana.jvm/analyze '(def shit "yo"))
;(ana.jvm/analyze '(str shit "yo"))
;(ana.jvm/analyze '(do (clojure.string/split "this,that" #",") (Thread/sleep 1000) (+ 3 4)))

;(ana.jvm/analyze '(+ 3 4))
;(ana.jvm/analyze '(def shit "yo"))
;(ana.jvm/analyze '(str shit "dawg"))
;(ana.jvm/analyze '(do (def willit? "yay!") (println willit?)))
;(ana.jvm/analyze '(defn fun [] "yay!"))

;(ana/analyze (reader/read-string (slurp "test.clj")) {})
;(ana/analyze '(+ 3 4) {})

;; gneiss...



;; goals
;;
;; * [x] opt to include code in output
;;   * [ ] opt between marginalia and inline mode
;; * [x] differential transmission of results

;; sketch for how to solve problem:
;; * [x] organize data into blocks
;;   * [x] blocks are either :md-comments, or :code
;; * [x] assign each block a hasch
;; * [x] build dependency tree between hasches
;; * [ ] run diff on sequences of block hashes
;; questions
;;   * versioning between different blocks as relates to update decisions?
;;   * how does this relate to scons and file targets?
;;     * special form for identifying a file target?
;;     * versioninng of files?


;; TODO How do we inject content into our markdown blocks?

;; Something like this? @{{(ds/row-count data)}}

;; Something like this? @{(ds/row-count data)}@

;; Something like this? @{(ds/row-count data)}

;; Something like this? {{(ds/row-count data)}}


;; TODO How do we inject content into our markdown blocks?

;; TODO How do we namespace markdown files?
;; * I guess just use a ns declaration or assume user?



(defrecord PaginatedCollection
  [id cursor page])

;(->PaginatedCollection. 3 4 5)
;(map->PaginatedCollection
  ;{:id 3 :cursor 4 :page 5})

(defn create-paginated-collection
  [props]
  (map->PaginatedCollection props))

;(create-paginated-collection {:id 7})

;(with-bindings [#'clojure.core/*data-readers*
                ;(assoc *data-readers* 'oz.next/PaginatedCollection #'oz.next/create-paginated-collection)]
  ;*data-readers*)
;(map #(vector (type (first %)) (type (second %)))
     ;*data-readers*)
;#'*data-readers*

;#oz.next/PaginatedCollection {:id 3 :cursor {:x :y} :page 10}

;(defn result-zipper
  ;(zip/zipper
    ;(fn [n] (map? n))
    ;(fn [n] (->> (dissoc n :env)
                 ;(filter (fn [[k v]]
                           ;(and (not (#{:env :form :arglists :raw-forms} k))
                                ;(coll? v))))
                 ;(mapcat (fn [[_ v]]
                           ;(if (sequential? v) v [v])))
                 ;(filter coll?)
                 ;(remove nil?)))
    ;(fn [n _] n)
    ;analysis))




