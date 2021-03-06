;;   Copyright (c) Olivier Grisel, 2009
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

;; corpusmaker - Clojure tools to build training dataset for machine learning
;; based NLP algorithms out of Wikimedia dumps

(ns corpusmaker.wikipedia
  (:use
    clojure.contrib.duck-streams
    clojure.contrib.str-utils)
  (:require
    (cascading.clojure
      [api :as c]
      [parse :as cp]))
  (:import
    java.util.regex.Pattern
    javax.xml.stream.XMLInputFactory
    javax.xml.stream.XMLStreamReader
    javax.xml.stream.XMLStreamConstants
    info.bliki.wiki.model.WikiModel
    org.apache.lucene.analysis.tokenattributes.TermAttribute
    org.apache.lucene.analysis.Tokenizer
    org.apache.lucene.analysis.standard.StandardAnalyzer
    org.apache.lucene.util.Version
    org.apache.lucene.wikipedia.analysis.WikipediaTokenizer
    corpusmaker.wikipedia.LinkAnnotationTextConverter
    corpusmaker.wikipedia.Annotation
    corpusmaker.cascading.scheme.WikipediaPageScheme))

;; Simple utility to chunk a wikidump file into smaller files suitable for
;; parallel processing locally (using pmap) or with Hadoop MapReduce

(defn chunk-dump
  "Split a big XML dump into smaller XML files with the same structure"
  [input-file output-folder chunk-size]
  (println "TODO"))

;; Utilities to parse a complete Wikimedia XML dump to extract sentence that
;; contain token annotated with a wiki link that point to a page that matches a
;; named entity with a type among the classes of the DBPedia ontology:
;; Person, Organisation, Place, ...
;;
;; Also extract tokenized cleaned-up version of the text of the wikipedia
;; articles for TF-IDF / bag of words + ngrams categorization with wiki
;; categories or wiki languages as target label.

;; Parsing big dumps can be really slow if the program spends time handling
;; types dynamically
;(set! *warn-on-reflection* true)

; remove new lines after links or templates that are not to be rendered in the
; text version
(def *spurious-eol* #"(?m)(\}\}|\]\])\n")

; the {{ndash}} ubiquitous template
(def *ndash* #"\{\{ndash\}\}")

(def *replacements*
  [{:pattern *ndash* :replacement " - "}
  {:pattern *spurious-eol* :replacement "$1"}])

;; TODO: rewrite this using http://github.com/marktriggs/xml-picker-seq since
;; using a zipper keeps all the parsed elements in memory which is not suitable
;; for large XML chunks

(defn no-redirect?
  "Check that the page content does not forward to another article"
  [#^String page-markup]
  (-> page-markup .trim (.startsWith "#REDIRECT") not))

(defn replace-all
  "Replace all occurrences of a pattern in the given text"
  [text {#^Pattern pattern :pattern replacement :replacement}]
  (-> pattern (.matcher text) (.replaceAll replacement)))

(defn remove-all
  "Replace all occurrences of a pattern in the given text by an empty string"
  [text #^Pattern pattern]
  (replace-all text {:pattern pattern :replacement ""}))

(defn clean-markup
  "Proprocess wiki markup to remove unneeded parts"
  [#^String page]
  (reduce replace-all (.trim page) *replacements*))

(defn parser-to-text-seq
  "Extract raw wikimarkup from the text tags encountered by an XML stream parser"
  [#^XMLStreamReader parser]
  (lazy-seq
    (if (.hasNext parser)
      (if (and (== (.next parser) XMLStreamConstants/START_ELEMENT)
        (= (.getLocalName parser) "text"))
        (cons (.getElementText parser) (parser-to-text-seq parser))
        (parser-to-text-seq parser))
      (.close parser)))) ; returns nil, suitable for seq building

(defn collect-raw-text
  "collect wikimarkup payload of a dump in seqable xml"
  [dumpfile]
  (let [factory (XMLInputFactory/newInstance)
        is (java.io.FileInputStream. (file-str dumpfile))
        parser (.createXMLStreamReader factory (reader is))]
    (parser-to-text-seq parser)))

(defn collect-text
  "collect and preprocess wikimarkup payload of a dump in seqable xml"
  [dumpfile]
  (map clean-markup (filter no-redirect? (collect-raw-text dumpfile))))

(defn annotation [#^Annotation a]
  {:label (.label a) :start (.start a) :end (.end a)})

(defn parse-markup
  "Remove wikimarkup while collecting links to entities and categories"
  [page-markup]
  (let [#^WikiModel model (LinkAnnotationTextConverter/newWikiModel)
        converter (LinkAnnotationTextConverter.)
        text (.render model converter page-markup)]
    [text (vec (map annotation (.getWikiLinks converter)))
     (-> model (.getCategories) (.keySet) set)]))

(defn tokenizer-seq
  "Build a lazy-seq out of a tokenizer with TermAttribute"
  [tokenizer term-att]
  (lazy-seq
    (when (.incrementToken tokenizer)
      (cons (.term term-att) (tokenizer-seq tokenizer term-att)))))

(defn tokenize-markup
  "Apply a lucene tokenizer to the markup content as a lazy-seq"
  [page-markup]
  (let [reader (java.io.StringReader. page-markup)
        tokenizer (WikipediaTokenizer. reader)
        term-att (.addAttribute tokenizer TermAttribute)]
    (tokenizer-seq tokenizer term-att)))

(defn tokenize-text
  "Apply a lucene tokenizer to cleaned text content as a lazy-seq"
  [page-text]
  (let [reader (java.io.StringReader. page-text)
        analyzer (StandardAnalyzer. Version/LUCENE_30 #{})
        tokenizer (.tokenStream analyzer nil reader)
        term-att (.addAttribute tokenizer TermAttribute)]
    (tokenizer-seq tokenizer term-att)))

(defn ngrams [n tokens]
  "Compute n-grams of a seq of tokens"
  (partition n 1 tokens))

(defn ngrams-text [n text]
  "Compute text representation of ngrams of words in a tokenized text"
  (map #(str-join " " %) (ngrams n (tokenize-text text))))

(defn bigrams-text [text]
  "Compute bigrams of a piece of text"
  (ngrams-text 2 text))

(defn trigrams-text [text]
  "Compute trigrams of a piece of text"
  (ngrams-text 3 text))

(defn padded-ngrams
  "Compute n-grams with padding (nil by default)"
  ([n tokens]
    (padded-ngrams n tokens nil))
  ([n tokens pad]
    (let [pad (repeat (dec n) pad)]
      (partition  n 1 (concat pad tokens pad)))))

(defn wikipedia-tap
  "Build a Cascading source tap from a wikipedia XML dump"
  ([input-path-or-file]
    (c/hfs-tap (WikipediaPageScheme.) input-path-or-file))
  ([input-path-or-file field-names]
   (c/hfs-tap
     (WikipediaPageScheme. (cp/fields field-names))
     input-path-or-file)))

